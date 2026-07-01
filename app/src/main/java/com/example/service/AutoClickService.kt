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
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.data.models.ClickConfig
import com.example.data.models.PriceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set

        val lastScannedText = kotlinx.coroutines.flow.MutableStateFlow("")

        // After a successful accept, ignore new events for this many ms to prevent double-clicks
        private const val ACCEPT_COOLDOWN_MS = 2500L
    }

    // IO dispatcher — zero artificial delay, never blocks the accessibility thread
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Bug 2 fix: cancel-and-relaunch — never drops events, never blocks processing
    @Volatile private var pendingEvalJob: Job? = null

    // Bug 1 fix: cooldown ONLY after a confirmed successful accept, not after every scan
    @Volatile private var lastAcceptTimeMs: Long = 0L

    // Pre-allocated StringBuilder — avoids GC pressure on every text collection
    private val textBuilderLock = Any()
    private val textBuilder = StringBuilder(8192)

    // Single reused PriceFilterEngine — stateless, safe to share
    private val priceFilterEngine by lazy { PriceFilterEngine(this) }

    // Accelerometer / vibration detection
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
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
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            if (lastSampleTime > 0 && (now - lastSampleTime) >= 10) {
                val delta = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ)
                if (delta in 0.08f..4.5f) {
                    if (++vibrationSamples >= 3 && now - lastVibrationTriggerTime > 400L) {
                        lastVibrationTriggerTime = now; vibrationSamples = 0
                        onVibrationDetected()
                    }
                } else {
                    if (vibrationSamples > 0) vibrationSamples--
                }
            }
            lastX = x; lastY = y; lastZ = z; lastSampleTime = now
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun onVibrationDetected() {
        val config = ClickEngine.currentConfig ?: return
        RideAutomationLogger.log("📳 Vibration detected — forcing immediate screen evaluation")
        val screenText = getAllScreenText()
        if (screenText.isBlank()) return
        scheduleEvaluation(screenText, config)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let {
                sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST)
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "Accelerometer registration failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return

        // Never process our own app's events
        if (event.packageName?.toString() == packageName) return

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) return

        val config = ClickEngine.currentConfig ?: return
        if (!config.priceConfig.enabled) return

        // Bug 1 fix: only block during post-accept cooldown, not during processing
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) return

        val screenText = getAllScreenText()
        if (screenText.isBlank()) return
        lastScannedText.value = screenText

        // Bug 2 fix: cancel previous job, launch fresh — zero blocking, zero dropped events
        scheduleEvaluation(screenText, config)
    }

    private fun scheduleEvaluation(screenText: String, config: ClickConfig) {
        pendingEvalJob?.cancel()
        pendingEvalJob = serviceScope.launch {
            try {
                val accepted = priceFilterEngine.evaluateAndProcessScreen(
                    screenText, config.priceConfig, this@AutoClickService
                )
                if (accepted) lastAcceptTimeMs = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.e("AutoClickService", "Screen evaluation error", e)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        serviceScope.cancel()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    // ── Gesture helpers ──────────────────────────────────────────────────────

    fun clickAt(x: Float, y: Float, durationMs: Long = 1L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onComplete?.invoke() }
            override fun onCancelled(g: GestureDescription?) { onComplete?.invoke() }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float,
              durationMs: Long = 200L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onComplete?.invoke() }
            override fun onCancelled(g: GestureDescription?) { onComplete?.invoke() }
        }, null)
    }

    fun findAndClickElement(text: String?, viewId: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (!viewId.isNullOrBlank()) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    val best = nodes.filter { it.isVisibleToUser && boundsNonEmpty(it) }
                        .minWithOrNull(compareBy({ !isClickableOrHasClickableParent(it) }, { area(it) }))
                    if (best != null) { performDualClick(best); nodes.forEach { it.recycle() }; return true }
                    nodes.forEach { it.recycle() }
                }
            }
            if (!text.isNullOrBlank()) {
                val rawNodes = root.findAccessibilityNodeInfosByText(text)
                if (!rawNodes.isNullOrEmpty()) {
                    val best = rawNodes.filter {
                        it.isVisibleToUser && boundsNonEmpty(it) && isStrictMatch(nodeDisplayText(it), text)
                    }.minWithOrNull(compareBy({ !isClickableOrHasClickableParent(it) }, { area(it) }))
                    if (best != null) { performDualClick(best); rawNodes.forEach { it.recycle() }; return true }
                    rawNodes.forEach { it.recycle() }
                }
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "findAndClickElement error", e)
        } finally {
            root.recycle()
        }
        return false
    }

    // ── Screen text collection ────────────────────────────────────────────────
    // Bug 4 fix: hintText (API 26+) and tooltipText (API 28+) are now included

    fun getAllScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        return synchronized(textBuilderLock) {
            textBuilder.setLength(0)
            try { traverseAndCollectText(root, textBuilder) } finally { root.recycle() }
            textBuilder.toString()
        }
    }

    fun traverseAndCollectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        try {
            if (node.isVisibleToUser) {
                node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                // Bug 4 fix: hintText — API 26+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                }
                // Bug 4 fix: tooltipText — API 28+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    node.tooltipText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                traverseAndCollectText(child, sb)
                try { child.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ── Shared utilities ─────────────────────────────────────────────────────

    fun isStrictMatch(nodeText: String, term: String): Boolean {
        val c = nodeText.trim(); val t = term.trim()
        if (c.equals(t, ignoreCase = true)) return true
        if (t.length <= 4) return Regex("\\b${Regex.escape(t)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(c)
        return c.contains(t, ignoreCase = true)
    }

    fun isClickableOrHasClickableParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) { parent.recycle(); return true }
            val next = parent.parent; parent.recycle(); parent = next
        }
        return false
    }

    /** Returns the best human-visible label for a node across all text attributes. */
    fun nodeDisplayText(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    fun boundsNonEmpty(node: AccessibilityNodeInfo): Boolean {
        val r = Rect(); node.getBoundsInScreen(r); return r.width() > 0 && r.height() > 0
    }

    fun area(node: AccessibilityNodeInfo): Int {
        val r = Rect(); node.getBoundsInScreen(r); return r.width() * r.height()
    }

    /** Gesture tap + ACTION_CLICK on the node (or first clickable parent). */
    fun performDualClick(node: AccessibilityNodeInfo) {
        val rect = Rect(); node.getBoundsInScreen(rect)
        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle(); break
                    }
                    val next = parent.parent; parent.recycle(); parent = next
                }
            }
        } catch (e: Exception) {
            Log.e("AutoClickService", "performDualClick action failed", e)
        }
    }
}
