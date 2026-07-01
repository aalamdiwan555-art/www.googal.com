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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoClickService? = null
            private set
        val lastScannedText = kotlinx.coroutines.flow.MutableStateFlow("")
        private const val ACCEPT_COOLDOWN_MS = 2500L
    }

    // ── Coroutine scope — only used for retry + template matching ────────────
    // UNDISPATCHED start means the coroutine runs inline until first suspension point
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var retryJob: Job? = null

    // ── State ────────────────────────────────────────────────────────────────
    @Volatile private var lastAcceptTimeMs = 0L

    // Pre-built text buffer — reused every event, main-thread only (no lock needed)
    private val textSb = StringBuilder(8192)

    private val priceFilterEngine by lazy { PriceFilterEngine(this) }

    // Cached accept-terms list — rebuilt only when config changes
    @Volatile private var cachedConfig: PriceConfig? = null
    @Volatile private var cachedAcceptTerms: List<String> = emptyList()

    // ── Vibration sensor ─────────────────────────────────────────────────────
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
            if (!config.priceConfig.enabled || !config.priceConfig.vibrationTriggerEnabled) return
            val now = System.currentTimeMillis()
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            if (lastSampleTime > 0 && now - lastSampleTime >= 10) {
                val delta = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ)
                if (delta in 0.08f..4.5f) {
                    if (++vibrationSamples >= 3 && now - lastVibrationTriggerTime > 400L) {
                        lastVibrationTriggerTime = now; vibrationSamples = 0
                        onVibrationDetected()
                    }
                } else { if (vibrationSamples > 0) vibrationSamples-- }
            }
            lastX = x; lastY = y; lastZ = z; lastSampleTime = now
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun onVibrationDetected() {
        val config = ClickEngine.currentConfig ?: return
        RideAutomationLogger.log("📳 Vibration — forcing immediate scan")
        handleScreenFast(config)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometer?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        } catch (e: Exception) { Log.e("ACS", "Accelerometer failed", e) }
    }

    // ── MAIN EVENT HANDLER — everything runs synchronously here ──────────────
    // Zero thread switching on the critical path.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        if (event.packageName?.toString() == packageName) return

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT) return

        val config = ClickEngine.currentConfig ?: return
        if (!config.priceConfig.enabled) return
        if (System.currentTimeMillis() - lastAcceptTimeMs < ACCEPT_COOLDOWN_MS) return

        handleScreenFast(config)
    }

    /**
     * THE FAST PATH — everything synchronous, no thread hop:
     *   1. Single-pass tree walk  →  collects screenText + best acceptNode together
     *   2. Filter evaluation      →  pure string math, O(text)
     *   3. Instant click          →  if acceptNode was found in the same pass
     *   4. Retry coroutine        →  only if button wasn't visible yet (rare)
     */
    private fun handleScreenFast(config: ClickConfig) {
        val priceConfig = config.priceConfig
        val acceptTerms = getOrBuildAcceptTerms(priceConfig)

        val root = rootInActiveWindow ?: return
        val scan = singlePassScan(root, acceptTerms) // recycles root internally
        if (scan.text.isBlank()) return

        lastScannedText.value = scan.text

        // ── Filter evaluation (pure CPU) ──────────────────────────────────────
        val price        = priceFilterEngine.extractPriceFromText(scan.text)
        val hasPrice     = price != null
        val hasKeyword   = scan.hasAcceptKeyword
        val hasSignal    = hasPrice || hasKeyword

        if (!hasSignal) {
            scan.acceptNode?.recycle()
            // Template matching is async — offload and return
            if (priceConfig.getTemplates().isNotEmpty()) {
                launchTemplateCheck(priceConfig)
            }
            return
        }

        val isPriceBelowMin = hasPrice && price!! < priceConfig.minPrice
        val isPriceAboveMax = hasPrice && priceConfig.maxPrice != Double.MAX_VALUE && price!! > priceConfig.maxPrice

        val (pickupDist, dropDist) = priceFilterEngine.extractDistancesFromText(scan.text)
        val distanceOk = checkDistance(pickupDist, dropDist, priceConfig)
        val keywordOk  = checkKeywords(scan.text, priceConfig)

        val shouldAccept = !isPriceBelowMin && !isPriceAboveMax && distanceOk && keywordOk

        if (!shouldAccept) {
            val reason = when {
                isPriceBelowMin -> "Price ${priceConfig.currencySymbol}$price below min"
                isPriceAboveMax -> "Price ${priceConfig.currencySymbol}$price above max"
                !distanceOk     -> "Distance filter mismatch"
                else            -> "Keyword filter mismatch"
            }
            RideAutomationLogger.log("🚫 REJECTING: $reason")
            RideAutomationLogger.recordReject(reason)
            scan.acceptNode?.recycle()
            launchReject(priceConfig)
            return
        }

        if (hasPrice) RideAutomationLogger.log("✅ RIDE MATCHED! ${priceConfig.currencySymbol}$price")
        else RideAutomationLogger.log("✅ RIDE MATCHED! (keyword/filter match)")

        if (scan.acceptNode != null) {
            // ── INSTANT CLICK — no thread switch, no scheduling delay ─────────
            val rect = Rect(); scan.acceptNode.getBoundsInScreen(rect)
            RideAutomationLogger.log("⚡ Instant click at (${rect.centerX()}, ${rect.centerY()})")
            performDualClick(scan.acceptNode)
            scan.acceptNode.recycle()
            lastAcceptTimeMs = System.currentTimeMillis()
            RideAutomationLogger.recordAccept(price ?: 0.0, priceConfig.currencySymbol)
        } else {
            // Button not visible yet — retry in coroutine (two-phase render case)
            RideAutomationLogger.log("🔄 Accept button not visible yet — starting retry loop")
            launchAcceptRetry(scan.text, priceConfig, price)
        }
    }

    // ── Single-pass tree scan ─────────────────────────────────────────────────
    // Collects ALL screen text AND finds the accept button in one traversal.
    // Root is recycled before returning.

    private data class ScanResult(
        val text: String,
        val acceptNode: AccessibilityNodeInfo?,  // caller must recycle
        val hasAcceptKeyword: Boolean
    )

    private fun singlePassScan(root: AccessibilityNodeInfo, acceptTerms: List<String>): ScanResult {
        textSb.setLength(0)
        var bestAccept: AccessibilityNodeInfo? = null
        var bestAcceptPriority = Int.MAX_VALUE // lower = better (prefer clickable, then smaller)
        var hasKeyword = false

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                if (!node.isVisibleToUser) {
                    for (i in 0 until node.childCount) {
                        val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                        traverse(c)
                        try { c.recycle() } catch (_: Exception) {}
                    }
                    return
                }

                // ── Collect all text attributes ──────────────────────────────
                val txt  = node.text
                val desc = node.contentDescription
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText else null
                val tip  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.tooltipText else null

                if (!txt.isNullOrBlank())  textSb.append(txt).append('\n')
                if (!desc.isNullOrBlank()) textSb.append(desc).append('\n')
                if (!hint.isNullOrBlank()) textSb.append(hint).append('\n')
                if (!tip.isNullOrBlank())  textSb.append(tip).append('\n')

                // ── Check if this node is an accept button ───────────────────
                val r = Rect(); node.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    val nodeLabel = listOfNotNull(
                        txt?.toString(), desc?.toString(), hint?.toString(), tip?.toString()
                    ).firstOrNull { it.isNotBlank() } ?: ""

                    for (term in acceptTerms) {
                        if (isStrictMatch(nodeLabel, term)) {
                            hasKeyword = true
                            // Score: clickable nodes win, then smallest area
                            val clickable = isClickableOrHasClickableParent(node)
                            val priority = (if (clickable) 0 else 100_000) + r.width() * r.height()
                            if (priority < bestAcceptPriority) {
                                bestAccept?.recycle()
                                // Keep a copy — the child will be recycled by the caller
                                bestAccept = node  // DON'T recycle this one
                                bestAcceptPriority = priority
                            }
                            break
                        }
                    }
                    // Also check if ANY text contains the keyword (for hasAcceptKeyword flag)
                    if (!hasKeyword) {
                        val allText = listOfNotNull(txt, desc, hint, tip).joinToString(" ")
                        if (acceptTerms.any { allText.contains(it, ignoreCase = true) }) hasKeyword = true
                    }
                }

                for (i in 0 until node.childCount) {
                    val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                    if (c == bestAccept) { /* don't recycle bestAccept */ continue }
                    traverse(c)
                    if (c != bestAccept) try { c.recycle() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        try { traverse(root) } finally { try { root.recycle() } catch (_: Exception) {} }
        return ScanResult(textSb.toString(), bestAccept, hasKeyword)
    }

    // ── Retry coroutine (fires only when button wasn't found on first scan) ──

    private fun launchAcceptRetry(screenText: String, config: PriceConfig, price: Double?) {
        retryJob?.cancel()
        // UNDISPATCHED = starts inline on the current thread, suspends only at delay/IO
        retryJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repeat(8) { attempt ->
                if (attempt > 0) kotlinx.coroutines.delay(40L) // one UI frame gap

                val root2 = rootInActiveWindow ?: return@repeat
                val acceptTerms = getOrBuildAcceptTerms(config)
                var found = false

                // Fast linear scan of all nodes for accept button
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                try {
                    for (term in acceptTerms) {
                        val nodes = root2.findAccessibilityNodeInfosByText(term) ?: continue
                        for (n in nodes) {
                            if (n.isVisibleToUser && boundsNonEmpty(n) && isAnyTextMatch(n, term)) {
                                candidates.add(n)
                            } else { n.recycle() }
                        }
                        if (candidates.isNotEmpty()) break
                    }
                    // Full tree walk if text search missed (Bug 5)
                    if (candidates.isEmpty()) {
                        collectFromTree(root2, acceptTerms, candidates)
                    }
                } catch (_: Exception) {}

                if (candidates.isNotEmpty()) {
                    val best = candidates.minWithOrNull(
                        compareBy({ !isClickableOrHasClickableParent(it) }, { area(it) })
                    ) ?: candidates[0]
                    val rect = Rect(); best.getBoundsInScreen(rect)
                    RideAutomationLogger.log("⚡ Retry #${attempt+1} found button — clicking (${rect.centerX()},${rect.centerY()})")
                    performDualClick(best)
                    lastAcceptTimeMs = System.currentTimeMillis()
                    RideAutomationLogger.recordAccept(price ?: 0.0, config.currencySymbol)
                    candidates.forEach { try { it.recycle() } catch (_: Exception) {} }
                    found = true
                }
                try { root2.recycle() } catch (_: Exception) {}
                if (found) return@launch
            }
            RideAutomationLogger.log("❌ Accept button not found after 8 attempts.")
        }
    }

    private fun launchTemplateCheck(config: PriceConfig) {
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            priceFilterEngine.evaluateAndProcessScreen("", config, this@AutoClickService)
        }
    }

    private fun launchReject(config: PriceConfig) {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            priceFilterEngine.triggerRejectPublic(this@AutoClickService, config)
        }
    }

    // ── Filter helpers (inline, no allocation) ───────────────────────────────

    private fun checkDistance(pickup: Double?, drop: Double?, c: PriceConfig): Boolean {
        if (pickup != null) {
            val bypass = c.minPickupDistance == 0.0 && c.maxPickupDistance == 0.0
            if (!bypass && (pickup < c.minPickupDistance || pickup > c.maxPickupDistance)) return false
        }
        if (drop != null) {
            val bypass = c.minDropDistance == 0.0 && c.maxDropDistance == 0.0
            if (!bypass && (drop < c.minDropDistance || drop > c.maxDropDistance)) return false
        }
        return true
    }

    private fun checkKeywords(text: String, c: PriceConfig): Boolean {
        if (c.pickupKeywords.isBlank() && c.dropKeywords.isBlank()) return true
        val pKws = c.pickupKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val dKws = c.dropKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (pKws.isNotEmpty() && !pKws.any { text.contains(it, ignoreCase = true) }) return false
        if (dKws.isNotEmpty() && dKws.any { text.contains(it, ignoreCase = true) }) return false
        return true
    }

    // ── Accept-terms cache ───────────────────────────────────────────────────

    private fun getOrBuildAcceptTerms(config: PriceConfig): List<String> {
        if (config === cachedConfig) return cachedAcceptTerms
        val terms = mutableListOf<String>()
        if (config.acceptButtonKeywords.isNotBlank()) {
            terms.addAll(config.acceptButtonKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (terms.isEmpty()) terms.addAll(PriceFilterEngine.DEFAULT_ACCEPT_TERMS)
        config.getTemplates().forEach { item ->
            if (item.buttonText.isNotBlank() && !terms.contains(item.buttonText)) terms.add(0, item.buttonText)
        }
        cachedConfig = config
        cachedAcceptTerms = terms
        return terms
    }

    // ── Tree-walk fallback (Bug 5: custom views with no text node) ───────────

    private fun collectFromTree(
        node: AccessibilityNodeInfo?,
        acceptTerms: List<String>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        try {
            if (node.isVisibleToUser && boundsNonEmpty(node)) {
                if (acceptTerms.any { isAnyTextMatch(node, it) }) { out.add(node); return }
            }
            for (i in 0 until node.childCount) {
                val c = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectFromTree(c, acceptTerms, out)
                if (!out.contains(c)) try { c.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ── Node text matching across all attributes ─────────────────────────────

    fun isAnyTextMatch(node: AccessibilityNodeInfo, term: String): Boolean {
        node.text?.toString()?.let { if (isStrictMatch(it, term)) return true }
        node.contentDescription?.toString()?.let { if (isStrictMatch(it, term)) return true }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            node.hintText?.toString()?.let { if (isStrictMatch(it, term)) return true }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            node.tooltipText?.toString()?.let { if (isStrictMatch(it, term)) return true }
        return false
    }

    // ── Gesture helpers ──────────────────────────────────────────────────────

    fun clickAt(x: Float, y: Float, durationMs: Long = 1L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { onComplete?.invoke() }
            override fun onCancelled(g: GestureDescription?) { onComplete?.invoke() }
        }, null)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float,
              durationMs: Long = 200L, onComplete: (() -> Unit)? = null) {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
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
        } catch (e: Exception) { Log.e("ACS", "findAndClickElement error", e) }
        finally { root.recycle() }
        return false
    }

    // ── Screen text (used by vibration path + PriceFilterEngine fallback) ────

    fun getAllScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        textSb.setLength(0)
        try { traverseAndCollectText(root, textSb) } finally { root.recycle() }
        return textSb.toString()
    }

    fun traverseAndCollectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        try {
            if (node.isVisibleToUser) {
                node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    node.hintText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    node.tooltipText?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                traverseAndCollectText(child, sb)
                try { child.recycle() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    fun isStrictMatch(nodeText: String, term: String): Boolean {
        val c = nodeText.trim(); val t = term.trim()
        if (c.equals(t, ignoreCase = true)) return true
        if (t.length <= 4) return Regex("\\b${Regex.escape(t)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(c)
        return c.contains(t, ignoreCase = true)
    }

    fun isClickableOrHasClickableParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var p = node.parent
        while (p != null) {
            if (p.isClickable) { p.recycle(); return true }
            val n = p.parent; p.recycle(); p = n
        }
        return false
    }

    fun nodeDisplayText(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            node.tooltipText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    fun boundsNonEmpty(node: AccessibilityNodeInfo): Boolean {
        val r = Rect(); node.getBoundsInScreen(r); return r.width() > 0 && r.height() > 0
    }

    fun area(node: AccessibilityNodeInfo): Int {
        val r = Rect(); node.getBoundsInScreen(r); return r.width() * r.height()
    }

    fun performDualClick(node: AccessibilityNodeInfo) {
        val rect = Rect(); node.getBoundsInScreen(rect)
        clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                var p = node.parent
                while (p != null) {
                    if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); break }
                    val n = p.parent; p.recycle(); p = n
                }
            }
        } catch (e: Exception) { Log.e("ACS", "performDualClick failed", e) }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        serviceScope.cancel(); instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try { sensorManager?.unregisterListener(sensorListener) } catch (_: Exception) {}
        serviceScope.cancel(); instance = null
        super.onDestroy()
    }
}
