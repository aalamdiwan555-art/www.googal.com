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

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lastProcessedText: String = ""
    private var lastProcessedTime: Long = 0L
    @Volatile private var isProcessing: Boolean = false

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
            if (!config.priceConfig.vibrationTriggerEnabled) return

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
                    if (delta in 0.08f..4.5f) {
                        vibrationSamples++
                        if (vibrationSamples >= 3) {
                            if (now - lastVibrationTriggerTime > 400L) {
                                lastVibrationTriggerTime = now
                                vibrationSamples = 0
                                onVibrationDetected()
                            }
                        }
                    } else {
                        if (vibrationSamples > 0) vibrationSamples--
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
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.d("AutoClickService", "Registered accelerometer sensor listener (FASTEST rate) for vibration detection")
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "Failed to register accelerometer sensor listener", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        
        // Filter out events from our own application to prevent self-clicking/recursion
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) {
            return
        }

        // React to every UI change event for maximum speed
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) {
            return
        }

        val config = ClickEngine.currentConfig ?: return
        if (!config.priceConfig.enabled) return

        // Skip if already processing — prevents queue pileup under rapid events
        if (isProcessing) return

        val screenText = getAllScreenText()
        if (screenText.isNotBlank()) {
            lastScannedText.value = screenText
        }

        val now = System.currentTimeMillis()
        if (screenText.isBlank()) return

        // 200ms cooldown only if text is identical (changed content always fires immediately)
        if (screenText == lastProcessedText && (now - lastProcessedTime) < 200L) {
            return
        }

        lastProcessedText = screenText
        lastProcessedTime = now
        isProcessing = true

        serviceScope.launch {
            try {
                val engine = PriceFilterEngine(this@AutoClickService)
                engine.evaluateAndProcessScreen(screenText, config.priceConfig, this@AutoClickService)
            } catch (e: Exception) {
                Log.e("AutoClickService", "Error evaluating screen in accessibility service", e)
            } finally {
                isProcessing = false
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

    fun clickAt(x: Float, y: Float, durationMs: Long = 1L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
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

    private fun isStrictMatch(nodeText: String, term: String): Boolean {
        val cleanNodeText = nodeText.trim()
        val cleanTerm = term.trim()
        if (cleanNodeText.equals(cleanTerm, ignoreCase = true)) {
            return true
        }
        if (cleanTerm.length <= 4) {
            val regex = Regex("\\b${Regex.escape(cleanTerm)}\\b", RegexOption.IGNORE_CASE)
            return regex.containsMatchIn(cleanNodeText)
        }
        return cleanNodeText.contains(cleanTerm, ignoreCase = true)
    }

    private fun isClickableOrHasClickableParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                parent.recycle()
                return true
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
        }
        return false
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
                    val visibleNodes = nodes.filter { node ->
                        if (!node.isVisibleToUser) return@filter false
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        r.width() > 0 && r.height() > 0
                    }
                    if (visibleNodes.isNotEmpty()) {
                        val sortedNodes = visibleNodes.map { node ->
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            val area = rect.width() * rect.height()
                            Triple(node, area, isClickableOrHasClickableParent(node))
                        }.sortedWith(
                            compareBy<Triple<AccessibilityNodeInfo, Int, Boolean>> { !it.third }
                                .thenBy { it.second }
                        )

                        val bestTriple = sortedNodes.firstOrNull()
                        val node = bestTriple?.first ?: visibleNodes[0]
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // Human-like physical click at center
                        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                        
                        // Direct action click (including parents)
                        try {
                            if (node.isClickable) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            } else {
                                var parent = node.parent
                                while (parent != null) {
                                    if (parent.isClickable) {
                                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        parent.recycle()
                                        break
                                    }
                                    val temp = parent.parent
                                    parent.recycle()
                                    parent = temp
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AutoClickService", "Direct viewId click failed", e)
                        }
                        
                        nodes.forEach { it.recycle() }
                        return true
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            if (!text.isNullOrBlank()) {
                val rawNodes = root.findAccessibilityNodeInfosByText(text)
                if (!rawNodes.isNullOrEmpty()) {
                    val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
                    val rejectedNodes = mutableListOf<AccessibilityNodeInfo>()
                    for (node in rawNodes) {
                        if (!node.isVisibleToUser) {
                            rejectedNodes.add(node)
                            continue
                        }
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        if (r.width() <= 0 || r.height() <= 0) {
                            rejectedNodes.add(node)
                            continue
                        }
                        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                        if (isStrictMatch(nodeText, text)) {
                            matchingNodes.add(node)
                        } else {
                            rejectedNodes.add(node)
                        }
                    }
                    
                    rejectedNodes.forEach { it.recycle() }
                    
                    if (matchingNodes.isNotEmpty()) {
                        val sortedNodes = matchingNodes.map { node ->
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            val area = rect.width() * rect.height()
                            Triple(node, area, isClickableOrHasClickableParent(node))
                        }.sortedWith(
                            compareBy<Triple<AccessibilityNodeInfo, Int, Boolean>> { !it.third }
                                .thenBy { it.second }
                        )

                        val bestTriple = sortedNodes.firstOrNull()
                        val node = bestTriple?.first ?: matchingNodes[0]
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // Human-like physical click at center
                        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                        
                        // Direct action click (including parents)
                        try {
                            if (node.isClickable) {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            } else {
                                var parent = node.parent
                                while (parent != null) {
                                    if (parent.isClickable) {
                                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        parent.recycle()
                                        break
                                    }
                                    val temp = parent.parent
                                    parent.recycle()
                                    parent = temp
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AutoClickService", "Direct text click failed", e)
                        }
                        
                        matchingNodes.forEach { it.recycle() }
                        return true
                    }
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
            if (node.isVisibleToUser) {
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
