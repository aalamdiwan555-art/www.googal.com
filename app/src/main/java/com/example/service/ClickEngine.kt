package com.example.service

import android.content.Context
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.data.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

object ClickEngine {
    private const val TAG = "ClickEngine"

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _clickCount = MutableStateFlow(0)
    val clickCount: StateFlow<Int> = _clickCount.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Tracks last touched/target coordinates
    var targetPoint = PointF(500f, 1000f)

    // Expose active configuration for real-time triggers in AutoClickService
    @Volatile
    var currentConfig: ClickConfig? = null
        private set

    // Current sequence being played back
    private var activeSequence: List<RecordedAction>? = null
    private var sequenceIndex = 0

    fun updateSequence(sequence: List<RecordedAction>) {
        activeSequence = sequence
    }

    fun start(context: Context, config: ClickConfig, sequence: List<RecordedAction>? = null) {
        if (_isRunning.value) return
        _isRunning.value = true
        _clickCount.value = 0
        activeSequence = sequence
        sequenceIndex = 0
        currentConfig = config

        val priceFilterEngine = PriceFilterEngine(context)

        job = scope.launch {
            while (isActive && _isRunning.value) {
                val service = AutoClickService.instance
                if (service == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Please enable AutoClicker Pro in Accessibility Settings", Toast.LENGTH_LONG).show()
                    }
                    stop()
                    break
                }

                // Check screen off
                if (config.stopOnScreenOff) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (!pm.isInteractive) {
                        stop()
                        break
                    }
                }

                // Price filter check
                if (config.priceConfig.enabled) {
                    val screenText = service.getAllScreenText()
                    if (screenText.isNotBlank()) {
                        priceFilterEngine.evaluateAndProcessScreen(screenText, config.priceConfig, service)
                    }
                    // Standard radar scan interval delay
                    val delayMs = config.intervalMs
                    delay(maxOf(50L, delayMs))
                    continue
                }

                when (config.clickMode) {
                    ClickMode.FIXED_POINT -> {
                        performSingleGesture(service, targetPoint.x, targetPoint.y, config.clickType)
                    }
                    ClickMode.FOLLOW_CURSOR -> {
                        performSingleGesture(service, targetPoint.x, targetPoint.y, config.clickType)
                    }
                    ClickMode.TARGET_ELEMENT -> {
                        val clicked = service.findAndClickElement(config.targetText, config.targetViewId)
                        if (clicked) {
                            _clickCount.value++
                        }
                    }
                    ClickMode.SEQUENCE -> {
                        val seq = activeSequence
                        if (!seq.isNullOrEmpty()) {
                            val action = seq[sequenceIndex]
                            executeRecordedAction(service, action)
                            _clickCount.value++
                            sequenceIndex = (sequenceIndex + 1) % seq.size
                            
                            // Delay after action
                            delay(action.delayAfterMs)
                            continue // skip standard delay since sequence handles its own
                        } else {
                            stop()
                            break
                        }
                    }
                }

                if (config.clickMode != ClickMode.TARGET_ELEMENT) {
                    _clickCount.value++
                }

                // Check click limits
                if (config.maxClicks > 0 && _clickCount.value >= config.maxClicks) {
                    stop()
                    break
                }

                // Standard delay
                var delayMs = config.intervalMs
                if (config.randomizeInterval) {
                    val variance = (delayMs * 0.2f).toLong()
                    if (variance > 0) {
                        delayMs += Random.nextLong(-variance, variance)
                    }
                }
                delay(maxOf(1L, delayMs))
            }
        }
    }

    private fun performSingleGesture(service: AutoClickService, x: Float, y: Float, type: ClickType) {
        when (type) {
            ClickType.SINGLE -> {
                service.clickAt(x, y)
            }
            ClickType.DOUBLE -> {
                service.clickAt(x, y) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        service.clickAt(x, y)
                    }, 100)
                }
            }
            ClickType.LONG_PRESS -> {
                service.clickAt(x, y, durationMs = 1000L)
            }
        }
    }

    private fun executeRecordedAction(service: AutoClickService, action: RecordedAction) {
        if (action.type == ActionType.CLICK) {
            service.clickAt(action.x, action.y, durationMs = action.durationMs)
        } else {
            val endX = action.endX ?: action.x
            val endY = action.endY ?: action.y
            service.swipe(action.x, action.y, endX, endY, durationMs = action.durationMs)
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        currentConfig = null
        job?.cancel()
        job = null
    }
}
