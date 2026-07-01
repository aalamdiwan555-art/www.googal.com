package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.models.PriceConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PriceFilterEngine(private val context: Context) {

    companion object {
        private const val TAG = "PriceFilterEngine"

        // Max attempts to find the Accept button per trigger (Bug 3 fix)
        private const val ACCEPT_RETRY_ATTEMPTS = 6
        // Gap between retries in ms — minimal, just enough for the next UI frame
        private const val ACCEPT_RETRY_DELAY_MS = 50L

        // ── Compiled-once regexes ──────────────────────────────────────────────

        private val PRICE_REGEX = Regex(
            """(?:Price|Fare|Earn|Est\.|Total|Cost)?\s*[₹$€£]\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
        private val NUMBER_REGEX = Regex("""\b(\d+(?:\.\d+)?)\b""")
        private val DISTANCE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:[Kk]m[sS]?|[Mm]i(?:les?)?)""")
        private val DISTANCE_UNITS = arrayOf("km", "kms", "mi", "mile", "miles")

        val DEFAULT_ACCEPT_TERMS = listOf(
            "Accept Ride", "Tap to Accept", "Let's Go",
            "Accept", "Confirm", "Take", "Agree", "Book", "Start", "Go"
        )
        val DEFAULT_REJECT_TERMS = listOf(
            "Decline Ride", "No thanks", "Decline", "Reject", "Skip", "Cancel", "Ignore", "X"
        )

        private val wordBoundaryRegexCache = ConcurrentHashMap<String, Regex>()
        private fun wordBoundaryRegex(term: String): Regex =
            wordBoundaryRegexCache.getOrPut(term) {
                Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
            }
    }

    // ── Public extraction helpers ─────────────────────────────────────────────

    fun extractPriceFromText(text: String): Double? {
        val match = PRICE_REGEX.find(text)
        if (match != null) return match.groupValues[1].replace(",", "").toDoubleOrNull()
        for (num in NUMBER_REGEX.findAll(text)) {
            val d = num.groupValues[1].toDoubleOrNull() ?: continue
            if (d <= 1.0) continue
            val after = text.substring(num.range.last + 1).trimStart()
            if (DISTANCE_UNITS.any { after.startsWith(it, ignoreCase = true) }) continue
            return d
        }
        return null
    }

    fun extractDistancesFromText(text: String): Pair<Double?, Double?> {
        val matches = DISTANCE_REGEX.findAll(text).toList()
        return Pair(
            matches.getOrNull(0)?.groupValues?.get(1)?.toDoubleOrNull(),
            matches.getOrNull(1)?.groupValues?.get(1)?.toDoubleOrNull()
        )
    }

    fun shouldClick(screenText: String, config: PriceConfig): Boolean {
        if (!config.enabled) return true
        val price = extractPriceFromText(screenText) ?: return true
        return price >= config.minPrice && price <= config.maxPrice
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    suspend fun evaluateAndProcessScreen(
        screenText: String,
        config: PriceConfig,
        service: AutoClickService
    ): Boolean {
        if (!config.enabled) return false

        val acceptTerms = buildAcceptTerms(config)
        val hasAcceptKeyword = acceptTerms.any { screenText.contains(it, ignoreCase = true) }
        val price = extractPriceFromText(screenText)
        val hasPrice = price != null

        // Template match only when no text signal at all
        var hasTemplateMatch = false
        val templates = config.getTemplates()
        if (!hasPrice && !hasAcceptKeyword && templates.isNotEmpty()) {
            val screenshot = captureScreenshot(service, context)
            if (screenshot != null) {
                for (item in templates) {
                    val f = File(item.imagePath)
                    if (!f.exists()) continue
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                    val match = TemplateMatcher.findTemplateMatch(screenshot, bmp)
                    bmp.recycle()
                    if (match != null) { hasTemplateMatch = true; break }
                }
                screenshot.recycle()
            }
        }

        if (!hasPrice && !hasAcceptKeyword && !hasTemplateMatch) return false

        if (hasPrice) RideAutomationLogger.log("Detected Incoming Ride! 🏷️ Price: ${config.currencySymbol}$price")
        else RideAutomationLogger.log("Detected Incoming Ride! (Evaluating keywords & filters)")

        // ── Distance check ────────────────────────────────────────────────────
        val (pickupDist, dropDist) = extractDistancesFromText(screenText)
        var distanceMatched = true; var distanceRejectReason = ""

        if (pickupDist != null) {
            val bypass = config.minPickupDistance == 0.0 && config.maxPickupDistance == 0.0
            if (!bypass) {
                RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (Allowed: ${config.minPickupDistance}–${config.maxPickupDistance} Km)")
                if (pickupDist < config.minPickupDistance || pickupDist > config.maxPickupDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Pickup ${pickupDist} Km outside range (${config.minPickupDistance}–${config.maxPickupDistance} Km)"
                }
            } else RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (filter bypassed: 0.0–0.0)")
        } else RideAutomationLogger.log("Parsed Pickup Distance: None detected")

        if (dropDist != null && distanceMatched) {
            val bypass = config.minDropDistance == 0.0 && config.maxDropDistance == 0.0
            if (!bypass) {
                RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (Allowed: ${config.minDropDistance}–${config.maxDropDistance} Km)")
                if (dropDist < config.minDropDistance || dropDist > config.maxDropDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Drop ${dropDist} Km outside range (${config.minDropDistance}–${config.maxDropDistance} Km)"
                }
            } else RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (filter bypassed: 0.0–0.0)")
        } else if (dropDist == null) RideAutomationLogger.log("Parsed Drop Distance: None detected")

        // ── Keyword check ─────────────────────────────────────────────────────
        var keywordMatched = true
        if (config.pickupKeywords.isNotBlank() || config.dropKeywords.isNotBlank()) {
            val pickupKws = config.pickupKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val dropKws   = config.dropKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (pickupKws.isNotEmpty() && !pickupKws.any { screenText.contains(it, ignoreCase = true) }) {
                RideAutomationLogger.log("Location filter: Pickup does not match required keywords.")
                keywordMatched = false
            }
            if (dropKws.isNotEmpty() && dropKws.any { screenText.contains(it, ignoreCase = true) }) {
                RideAutomationLogger.log("Location filter: Avoided Drop keyword matched!")
                keywordMatched = false
            }
        }

        // ── Decision ──────────────────────────────────────────────────────────
        val isPriceBelowMin = hasPrice && price!! < config.minPrice
        val isPriceAboveMax = hasPrice && config.maxPrice != Double.MAX_VALUE && price!! > config.maxPrice
        val shouldAccept = !isPriceBelowMin && !isPriceAboveMax && keywordMatched && distanceMatched

        return if (shouldAccept) {
            if (hasPrice) RideAutomationLogger.log("✅ RIDE MATCHED! Price ${config.currencySymbol}$price meets criteria.")
            else RideAutomationLogger.log("✅ RIDE MATCHED! Location and Accept filters satisfied.")
            val accepted = triggerAccept(service, config, screenText)
            if (accepted) RideAutomationLogger.recordAccept(price ?: 0.0, config.currencySymbol)
            accepted
        } else {
            val reason = when {
                isPriceBelowMin  -> "Price ${config.currencySymbol}$price below min ${config.currencySymbol}${config.minPrice}"
                isPriceAboveMax  -> "Price ${config.currencySymbol}$price exceeds max ${config.currencySymbol}${config.maxPrice}"
                !distanceMatched -> distanceRejectReason
                !keywordMatched  -> "Location keywords did not match criteria"
                else             -> "Does not meet filters"
            }
            RideAutomationLogger.log("🚫 REJECTING RIDE: $reason")
            RideAutomationLogger.recordReject(reason)
            triggerReject(service, config)
        }
    }

    // ── Accept (Bugs 3 + 5 fixed) ─────────────────────────────────────────────

    private suspend fun triggerAccept(
        service: AutoClickService,
        config: PriceConfig,
        screenText: String
    ): Boolean {
        val templates = config.getTemplates()

        // 1. Template image matching (no retry needed — screenshot is instantaneous)
        if (templates.isNotEmpty()) {
            RideAutomationLogger.log("🔍 Scanning with ${templates.size} Accept Button template(s)...")
            val screenshot = captureScreenshot(service, context)
            if (screenshot != null) {
                for ((index, item) in templates.withIndex()) {
                    val f = File(item.imagePath); if (!f.exists()) continue
                    val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                    val matchPoint = TemplateMatcher.findTemplateMatch(screenshot, bmp)
                    if (matchPoint != null) {
                        if (item.buttonText.isNotBlank()) {
                            val onScreen = screenText.contains(item.buttonText, ignoreCase = true)
                            if (!onScreen) {
                                RideAutomationLogger.log("🔍 Template #${index+1} matched but text '${item.buttonText}' absent — skipping.")
                                bmp.recycle(); continue
                            }
                            RideAutomationLogger.log("✨ Template #${index+1} + text '${item.buttonText}' verified.")
                        } else {
                            RideAutomationLogger.log("🎯 Template #${index+1} matched (no text filter).")
                        }
                        val cx = (matchPoint.x + bmp.width  / 2).toFloat()
                        val cy = (matchPoint.y + bmp.height / 2).toFloat()
                        RideAutomationLogger.log("⚡ Clicking Accept template at ($cx, $cy)")
                        service.clickAt(cx, cy)
                        bmp.recycle(); screenshot.recycle()
                        return true
                    }
                    bmp.recycle()
                }
                screenshot.recycle()
            }
            RideAutomationLogger.log("⚠️ Template matching failed — falling back to accessibility click.")
        }

        // 2. Accessibility matching with retry (Bug 3 fix)
        //    Each attempt re-reads the live tree so it catches the button appearing late (Bug 1 fix)
        val acceptTerms = buildAcceptTerms(config)

        repeat(ACCEPT_RETRY_ATTEMPTS) { attempt ->
            // On retries, wait one UI frame before re-reading the tree
            if (attempt > 0) {
                kotlinx.coroutines.delay(ACCEPT_RETRY_DELAY_MS)
            }

            val root = service.rootInActiveWindow ?: run {
                Log.w(TAG, "rootInActiveWindow null on attempt ${attempt+1}")
                return@repeat
            }

            try {
                // ── Pass A: findAccessibilityNodeInfosByText for each accept term ──
                for (term in acceptTerms) {
                    val rawNodes = root.findAccessibilityNodeInfosByText(term) ?: continue
                    if (rawNodes.isEmpty()) continue

                    val best = rawNodes.filter { node ->
                        node.isVisibleToUser && service.boundsNonEmpty(node) &&
                        isAnyTextMatch(node, term)
                    }.minWithOrNull(
                        compareBy({ !service.isClickableOrHasClickableParent(it) }, { service.area(it) })
                    )

                    rawNodes.filter { it != best }.forEach { it.recycle() }

                    if (best != null) {
                        val rect = Rect(); best.getBoundsInScreen(rect)
                        RideAutomationLogger.log("⚡ Accept '$term' — dual-click at (${rect.centerX()}, ${rect.centerY()}) [attempt ${attempt+1}]")
                        service.performDualClick(best)
                        best.recycle()
                        root.recycle()
                        return true
                    }
                }

                // ── Pass B: Bug 5 fix — full tree walk for custom views ──────────
                //    Matches on contentDescription / hintText / tooltipText that
                //    findAccessibilityNodeInfosByText() would miss entirely
                val treeMatch = findAcceptNodeInTree(root, acceptTerms, service)
                if (treeMatch != null) {
                    val rect = Rect(); treeMatch.getBoundsInScreen(rect)
                    RideAutomationLogger.log("⚡ Accept (tree-walk) — dual-click at (${rect.centerX()}, ${rect.centerY()}) [attempt ${attempt+1}]")
                    service.performDualClick(treeMatch)
                    treeMatch.recycle()
                    root.recycle()
                    return true
                }

            } catch (e: Exception) {
                Log.e(TAG, "Accept attempt ${attempt+1} error", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }

            RideAutomationLogger.log("🔄 Accept button not found (attempt ${attempt+1}/$ACCEPT_RETRY_ATTEMPTS) — retrying...")
        }

        RideAutomationLogger.log("❌ Failed to click Accept after $ACCEPT_RETRY_ATTEMPTS attempts.")
        return false
    }

    /**
     * Bug 5 fix: full tree walk that checks ALL text attributes including
     * contentDescription, hintText, tooltipText — invisible to findAccessibilityNodeInfosByText().
     * Returns the best matching node (caller must recycle it).
     */
    private fun findAcceptNodeInTree(
        root: AccessibilityNodeInfo,
        acceptTerms: List<String>,
        service: AutoClickService
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAcceptCandidates(root, acceptTerms, service, candidates)
        if (candidates.isEmpty()) return null
        val best = candidates.minWithOrNull(
            compareBy({ !service.isClickableOrHasClickableParent(it) }, { service.area(it) })
        )
        candidates.filter { it != best }.forEach { try { it.recycle() } catch (_: Exception) {} }
        return best
    }

    private fun collectAcceptCandidates(
        node: AccessibilityNodeInfo?,
        acceptTerms: List<String>,
        service: AutoClickService,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        try {
            if (node.isVisibleToUser && service.boundsNonEmpty(node)) {
                val matched = acceptTerms.any { term -> isAnyTextMatch(node, term) }
                if (matched) {
                    out.add(node)
                    return // don't recurse into matched subtree
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAcceptCandidates(child, acceptTerms, service, out)
                // Note: don't recycle child here — it may be added to `out`
            }
        } catch (_: Exception) {}
    }

    /**
     * Checks ALL text attributes of a node against a term:
     * text, contentDescription, hintText (API 26+), tooltipText (API 28+).
     * This is what makes Bug 5 disappear.
     */
    private fun isAnyTextMatch(node: AccessibilityNodeInfo, term: String): Boolean {
        node.text?.toString()?.let { if (isStrictMatch(it, term)) return true }
        node.contentDescription?.toString()?.let { if (isStrictMatch(it, term)) return true }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.let { if (isStrictMatch(it, term)) return true }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.tooltipText?.toString()?.let { if (isStrictMatch(it, term)) return true }
        }
        return false
    }

    // ── Reject ────────────────────────────────────────────────────────────────

    private suspend fun triggerReject(service: AutoClickService, config: PriceConfig): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            for (term in DEFAULT_REJECT_TERMS) {
                val rawNodes = root.findAccessibilityNodeInfosByText(term) ?: continue
                if (rawNodes.isEmpty()) continue

                val best = rawNodes.filter { node ->
                    node.isVisibleToUser && service.boundsNonEmpty(node) &&
                    isAnyTextMatch(node, term)
                }.minWithOrNull(
                    compareBy({ !service.isClickableOrHasClickableParent(it) }, { service.area(it) })
                )

                rawNodes.filter { it != best }.forEach { it.recycle() }

                if (best != null) {
                    val rect = Rect(); best.getBoundsInScreen(rect)
                    RideAutomationLogger.log("🚫 Reject '$term' — clicking at (${rect.centerX()}, ${rect.centerY()})")
                    service.performDualClick(best)
                    best.recycle()
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reject matching error", e)
        } finally {
            root.recycle()
        }
        RideAutomationLogger.log("⚠️ Reject button not found — ride will expire naturally.")
        return false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildAcceptTerms(config: PriceConfig): List<String> {
        val terms = mutableListOf<String>()
        if (config.acceptButtonKeywords.isNotBlank()) {
            terms.addAll(config.acceptButtonKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (terms.isEmpty()) terms.addAll(DEFAULT_ACCEPT_TERMS)
        config.getTemplates().forEach { item ->
            if (item.buttonText.isNotBlank() && !terms.contains(item.buttonText)) terms.add(0, item.buttonText)
        }
        return terms
    }

    private fun isStrictMatch(nodeText: String, term: String): Boolean {
        val c = nodeText.trim(); val t = term.trim()
        if (c.equals(t, ignoreCase = true)) return true
        if (t.length <= 4) return wordBoundaryRegex(t).containsMatchIn(c)
        return c.contains(t, ignoreCase = true)
    }

    // ── Screenshot (Android R+) ───────────────────────────────────────────────

    private suspend fun captureScreenshot(service: AutoClickService, context: Context): Bitmap? =
        suspendCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    service.takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        context.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                buffer?.close()
                                continuation.resume(bitmap?.copy(Bitmap.Config.ARGB_8888, false).also { bitmap?.recycle() })
                            }
                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "Screenshot failed: $errorCode")
                                continuation.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot exception", e)
                    continuation.resume(null)
                }
            } else {
                continuation.resume(null)
            }
        }
}
