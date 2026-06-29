package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set

        val lastScannedText = kotlinx.coroutines.flow.MutableStateFlow("")
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastProcessedText: String = ""
    private var lastProcessedTime: Long = 0L

    // Accelerometer variables for vibration detection
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastSampleTime = 0L
    private var vibrationSamples = 0
    private var lastVibrationTriggerTime = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val config = ClickEngine.currentConfig ?: return
            if (!config.priceConfig.enabled) return

            val now = System.currentTimeMillis()
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            if (lastSampleTime > 0) {
                val dt = now - lastSampleTime
                if (dt >= 10) {
                    val dx = Math.abs(x - lastX)
                    val dy = Math.abs(y - lastY)
                    val dz = Math.abs(z - lastZ)
                    val delta = dx + dy + dz

                    // Vibration is high-frequency, low-magnitude acceleration spikes
                    if (delta in 0.12f..3.5f) {
                        vibrationSamples++
                        if (vibrationSamples >= 6) {
                            if (now - lastVibrationTriggerTime > 1500L) {
                                lastVibrationTriggerTime = now
                                vibrationSamples = 0
                                onVibrationDetected()
                            }
                        }
                    } else {
                        vibrationSamples = Math.max(0, vibrationSamples - 1)
                    }
                }
            }
            lastX = x
            lastY = y
            lastZ = z
            lastSampleTime = now
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun onVibrationDetected() {
        val config = ClickEngine.currentConfig ?: return
        RideAutomationLogger.log("📳 Mobile Vibration Detected! Device is vibrating (incoming ride alert). Increasing alertness and forcing immediate screen template/text match...")
        
        val screenText = getAllScreenText()
        if (screenText.isBlank()) return
        
        serviceScope.launch {
            try {
                val engine = PriceFilterEngine(this@AutoClickService)
                engine.evaluateAndProcessScreen(screenText, config.priceConfig, this@AutoClickService)
            } catch (e: Exception) {
                Log.e("AutoClickService", "Vibration-triggered screen matching failed", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
                Log.d("AutoClickService", "Registered accelerometer sensor listener for vibration detection")
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "Failed to register accelerometer sensor listener", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        
        // Filter for relevant events to optimize performance and prevent excessive CPU usage
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val screenText = getAllScreenText()
        if (screenText.isNotBlank()) {
            lastScannedText.value = screenText
        }

        val config = ClickEngine.currentConfig ?: return
        if (!config.priceConfig.enabled) return

        val now = System.currentTimeMillis()
        if (screenText.isBlank()) return
        
        // Use a cooldown of 1.5 seconds if the screen text hasn't changed to prevent duplicate triggers
        if (screenText == lastProcessedText && (now - lastProcessedTime) < 1500L) {
            return
        }

        lastProcessedText = screenText
        lastProcessedTime = now

        serviceScope.launch {
            try {
                val engine = PriceFilterEngine(this@AutoClickService)
                engine.evaluateAndProcessScreen(screenText, config.priceConfig, this@AutoClickService)
            } catch (e: Exception) {
                Log.e("AutoClickService", "Error evaluating screen in accessibility service", e)
            }
        }
    }

    override fun onInterrupt() {
        // Not used, but required to override
    }

    private fun unregisterSensor() {
        try {
            sensorManager?.unregisterListener(sensorListener)
            Log.d("AutoClickService", "Unregistered accelerometer sensor listener")
        } catch (e: Exception) {
            Log.e("AutoClickService", "Failed to unregister accelerometer sensor listener", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterSensor()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterSensor()
        instance = null
        super.onDestroy()
    }

    fun clickAt(x: Float, y: Float, durationMs: Long = 50L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
        gestureBuilder.addStroke(strokeDescription)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke()
            }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gestureBuilder = GestureDescription.Builder()
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, durationMs)
        gestureBuilder.addStroke(strokeDescription)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke()
            }
        }, null)
    }

    fun findAndClickElement(text: String?, viewId: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (!viewId.isNullOrBlank()) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            nodes.forEach { it.recycle() }
                            return true
                        } else {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            if (!text.isNullOrBlank()) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            nodes.forEach { it.recycle() }
                            return true
                        } else {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "Error clicking accessibility element", e)
        } finally {
            root.recycle()
        }

        return false
    }

    fun getAllScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        try {
            traverseAndCollectText(root, sb)
        } finally {
            root.recycle()
        }
        return sb.toString()
    }

    private fun traverseAndCollectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        try {
            node.text?.let {
                if (it.isNotBlank()) {
                    sb.append(it).append("\n")
                }
            }
            node.contentDescription?.let {
                if (it.isNotBlank()) {
                    sb.append(it).append("\n")
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                var child: AccessibilityNodeInfo? = null
                try {
                    child = node.getChild(i)
                } catch (e: Exception) {
                    // Ignore child fetching error for dynamic/stale nodes
                }
                if (child != null) {
                    traverseAndCollectText(child, sb)
                    try {
                        child.recycle()
                    } catch (e: Exception) {
                        // Ignore recycling exception on stale nodes
                    }
                }
            }
        } catch (e: Exception) {
            // Safe fallback if nodes are invalidated asynchronously during traversal
        }
    }
}
