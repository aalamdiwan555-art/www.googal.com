package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.models.PriceConfig
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class PriceFilterEngine(private val context: Context) {

    companion object {
        private const val TAG = "PriceFilterEngine"

        // ── Compiled once, reused forever across all calls/instances ──

        // Matches: optional label + currency symbol + price number
        private val PRICE_REGEX = Regex(
            """(?:Price|Fare|Earn|Est\.|Total|Cost)?\s*[₹$€£]\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )

        // Matches bare decimal/integer numbers
        private val NUMBER_REGEX = Regex("""\b(\d+(?:\.\d+)?)\b""")

        // Matches distances like "5.2 km", "3 kms", "2 mi", "1.5 miles"
        private val DISTANCE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:[Kk]m[sS]?|[Mm]i(?:les?)?)""")

        // Distance unit prefixes (checked after bare numbers)
        private val DISTANCE_UNITS = arrayOf("km", "kms", "mi", "mile", "miles")

        // Default accept keywords — deduplicated, longest-first for early match
        val DEFAULT_ACCEPT_TERMS = listOf(
            "Accept Ride", "Tap to Accept", "Let's Go",
            "Accept", "Confirm", "Take", "Agree", "Book", "Start", "Go"
        )

        // Default reject keywords
        val DEFAULT_REJECT_TERMS = listOf(
            "Decline Ride", "No thanks", "Decline", "Reject", "Skip", "Cancel", "Ignore", "X"
        )

        // Word-boundary regex cache for short terms (≤ 4 chars) — avoids recompiling per call
        private val wordBoundaryRegexCache = ConcurrentHashMap<String, Regex>()

        private fun wordBoundaryRegex(term: String): Regex =
            wordBoundaryRegexCache.getOrPut(term) {
                Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
            }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Public extraction helpers
    // ──────────────────────────────────────────────────────────────────────────

    fun extractPriceFromText(text: String): Double? {
        // Primary: currency-symbol match
        val match = PRICE_REGEX.find(text)
        if (match != null) {
            return match.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        // Fallback: first bare number that isn't a distance
        for (num in NUMBER_REGEX.findAll(text)) {
            val d = num.groupValues[1].toDoubleOrNull() ?: continue
            if (d <= 1.0) continue
            val afterSub = text.substring(num.range.last + 1).trimStart()
            if (DISTANCE_UNITS.any { afterSub.startsWith(it, ignoreCase = true) }) continue
            return d
        }
        return null
    }

    fun extractDistancesFromText(text: String): Pair<Double?, Double?> {
        val matches = DISTANCE_REGEX.findAll(text).toList()
        val pickup = matches.getOrNull(0)?.groupValues?.get(1)?.toDoubleOrNull()
        val drop   = matches.getOrNull(1)?.groupValues?.get(1)?.toDoubleOrNull()
        return Pair(pickup, drop)
    }

    fun shouldClick(screenText: String, config: PriceConfig): Boolean {
        if (!config.enabled) return true
        val price = extractPriceFromText(screenText) ?: return true
        return price >= config.minPrice && price <= config.maxPrice
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Core evaluation — called on every accessibility event
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun evaluateAndProcessScreen(
        screenText: String,
        config: PriceConfig,
        service: AutoClickService
    ): Boolean {
        if (!config.enabled) return false

        // Build accept term list once (custom > default)
        val acceptTerms = buildAcceptTerms(config)

        val hasAcceptKeyword = acceptTerms.any { screenText.contains(it, ignoreCase = true) }
        val price   = extractPriceFromText(screenText)
        val hasPrice = price != null

        // Template match only if no text signal at all
        var hasTemplateMatch = false
        val templates = config.getTemplates()
        if (!hasPrice && !hasAcceptKeyword && templates.isNotEmpty()) {
            val screenshot = captureScreenshot(service, context)
            if (screenshot != null) {
                for (item in templates) {
                    val templateFile = File(item.imagePath)
                    if (!templateFile.exists()) continue
                    val templateBitmap = BitmapFactory.decodeFile(templateFile.absolutePath) ?: continue
                    val matchPoint = TemplateMatcher.findTemplateMatch(screenshot, templateBitmap)
                    templateBitmap.recycle()
                    if (matchPoint != null) { hasTemplateMatch = true; break }
                }
                screenshot.recycle()
            }
        }

        if (!hasPrice && !hasAcceptKeyword && !hasTemplateMatch) return false

        // Log detection
        if (hasPrice) {
            RideAutomationLogger.log("Detected Incoming Ride! 🏷️ Price: ${config.currencySymbol}$price")
        } else {
            RideAutomationLogger.log("Detected Incoming Ride! (Evaluating keywords & matching filters)")
        }

        // ── Distance check ──
        val (pickupDist, dropDist) = extractDistancesFromText(screenText)
        var distanceMatched = true
        var distanceRejectReason = ""

        if (pickupDist != null) {
            val bypass = config.minPickupDistance == 0.0 && config.maxPickupDistance == 0.0
            if (!bypass) {
                RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (Allowed: ${config.minPickupDistance}–${config.maxPickupDistance} Km)")
                if (pickupDist < config.minPickupDistance || pickupDist > config.maxPickupDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Pickup ${pickupDist} Km outside range (${config.minPickupDistance}–${config.maxPickupDistance} Km)"
                }
            } else {
                RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (filter bypassed: 0.0–0.0)")
            }
        } else {
            RideAutomationLogger.log("Parsed Pickup Distance: None detected")
        }

        if (dropDist != null && distanceMatched) {
            val bypass = config.minDropDistance == 0.0 && config.maxDropDistance == 0.0
            if (!bypass) {
                RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (Allowed: ${config.minDropDistance}–${config.maxDropDistance} Km)")
                if (dropDist < config.minDropDistance || dropDist > config.maxDropDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Drop ${dropDist} Km outside range (${config.minDropDistance}–${config.maxDropDistance} Km)"
                }
            } else {
                RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (filter bypassed: 0.0–0.0)")
            }
        } else if (dropDist == null) {
            RideAutomationLogger.log("Parsed Drop Distance: None detected")
        }

        // ── Keyword check ──
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

        // ── Decision ──
        val isPriceBelowMin  = hasPrice && price!! < config.minPrice
        val isPriceAboveMax  = hasPrice && config.maxPrice != Double.MAX_VALUE && price!! > config.maxPrice
        val shouldAccept     = !isPriceBelowMin && !isPriceAboveMax && keywordMatched && distanceMatched

        return if (shouldAccept) {
            if (hasPrice) {
                RideAutomationLogger.log("✅ RIDE MATCHED! Price ${config.currencySymbol}$price meets criteria.")
            } else {
                RideAutomationLogger.log("✅ RIDE MATCHED! Location and Accept filters satisfied.")
            }
            val accepted = triggerAccept(service, config, screenText)
            if (accepted) RideAutomationLogger.recordAccept(price ?: 0.0, config.currencySymbol)
            accepted
        } else {
            val reason = when {
                isPriceBelowMin -> "Price ${config.currencySymbol}$price below min ${config.currencySymbol}${config.minPrice}"
                isPriceAboveMax -> "Price ${config.currencySymbol}$price exceeds max ${config.currencySymbol}${config.maxPrice}"
                !distanceMatched -> distanceRejectReason
                !keywordMatched  -> "Location keywords did not match criteria"
                else             -> "Does not meet filters"
            }
            RideAutomationLogger.log("🚫 REJECTING RIDE: $reason")
            RideAutomationLogger.recordReject(reason)
            triggerReject(service, config)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Accept
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun triggerAccept(
        service: AutoClickService,
        config: PriceConfig,
        screenText: String
    ): Boolean {
        val templates = config.getTemplates()

        // 1. Template image matching
        if (templates.isNotEmpty()) {
            RideAutomationLogger.log("🔍 Scanning with ${templates.size} Accept Button template(s)...")
            val screenshot = captureScreenshot(service, context)
            if (screenshot != null) {
                for ((index, item) in templates.withIndex()) {
                    val templateFile = File(item.imagePath)
                    if (!templateFile.exists()) continue
                    val templateBitmap = BitmapFactory.decodeFile(templateFile.absolutePath) ?: continue
                    val matchPoint = TemplateMatcher.findTemplateMatch(screenshot, templateBitmap)
                    if (matchPoint != null) {
                        // Optional: verify button text is also on screen
                        if (item.buttonText.isNotBlank()) {
                            val textOnScreen = screenText.contains(item.buttonText, ignoreCase = true)
                            var hasTextNode = false
                            val root = service.rootInActiveWindow
                            if (root != null) {
                                try {
                                    val nodes = root.findAccessibilityNodeInfosByText(item.buttonText)
                                    hasTextNode = !nodes.isNullOrEmpty()
                                    nodes?.forEach { it.recycle() }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Template text node check failed", e)
                                } finally {
                                    root.recycle()
                                }
                            }
                            if (!textOnScreen && !hasTextNode) {
                                RideAutomationLogger.log("🔍 Template #${index + 1} image matched but text '${item.buttonText}' not on screen — skipping.")
                                templateBitmap.recycle()
                                continue
                            }
                            RideAutomationLogger.log("✨ Perfect match! Template #${index + 1} image + text '${item.buttonText}' verified.")
                        } else {
                            RideAutomationLogger.log("🎯 Match! Template #${index + 1} image matched (no text filter).")
                        }

                        val clickX = (matchPoint.x + templateBitmap.width  / 2).toFloat()
                        val clickY = (matchPoint.y + templateBitmap.height / 2).toFloat()
                        RideAutomationLogger.log("⚡ Clicking Accept template at ($clickX, $clickY)")
                        service.clickAt(clickX, clickY)
                        templateBitmap.recycle()
                        screenshot.recycle()
                        return true
                    }
                    templateBitmap.recycle()
                }
                screenshot.recycle()
            }
            RideAutomationLogger.log("⚠️ Template matching failed — falling back to accessibility click.")
        }

        // 2. Accessibility text matching
        val acceptTerms = buildAcceptTerms(config)
        val root = service.rootInActiveWindow ?: run {
            RideAutomationLogger.log("❌ Failed to click Accept: root window unavailable.")
            return false
        }
        try {
            for (term in acceptTerms) {
                val rawNodes = root.findAccessibilityNodeInfosByText(term) ?: continue
                if (rawNodes.isEmpty()) continue

                val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
                val rejectedNodes = mutableListOf<AccessibilityNodeInfo>()

                for (node in rawNodes) {
                    if (!node.isVisibleToUser) { rejectedNodes.add(node); continue }
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() <= 0 || r.height() <= 0) { rejectedNodes.add(node); continue }
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isStrictMatch(nodeText, term)) matchingNodes.add(node) else rejectedNodes.add(node)
                }
                rejectedNodes.forEach { it.recycle() }

                if (matchingNodes.isEmpty()) continue

                // Pick best node: prefer clickable, then smallest area (exact button vs container)
                val best = matchingNodes.minWithOrNull(
                    compareBy({ !isClickableOrHasClickableParent(it) }, {
                        val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height()
                    })
                ) ?: matchingNodes[0]

                val rect = Rect()
                best.getBoundsInScreen(rect)
                val cx = rect.centerX().toFloat()
                val cy = rect.centerY().toFloat()

                RideAutomationLogger.log("⚡ Accept '$term' found — dual-dispatching click at ($cx, $cy)")
                service.clickAt(cx, cy)
                try {
                    if (best.isClickable) {
                        best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        var parent = best.parent
                        while (parent != null) {
                            if (parent.isClickable) {
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                parent.recycle(); break
                            }
                            val next = parent.parent
                            parent.recycle(); parent = next
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Direct node ACTION_CLICK failed", e)
                }
                matchingNodes.forEach { it.recycle() }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in accessibility accept matching", e)
        } finally {
            root.recycle()
        }

        RideAutomationLogger.log("❌ Failed to click Accept: button not found on screen.")
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Reject
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun triggerReject(service: AutoClickService, config: PriceConfig): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            for (term in DEFAULT_REJECT_TERMS) {
                val rawNodes = root.findAccessibilityNodeInfosByText(term) ?: continue
                if (rawNodes.isEmpty()) continue

                val matchingNodes = mutableListOf<AccessibilityNodeInfo>()
                val rejectedNodes = mutableListOf<AccessibilityNodeInfo>()

                for (node in rawNodes) {
                    if (!node.isVisibleToUser) { rejectedNodes.add(node); continue }
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() <= 0 || r.height() <= 0) { rejectedNodes.add(node); continue }
                    val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                    if (isStrictMatch(nodeText, term)) matchingNodes.add(node) else rejectedNodes.add(node)
                }
                rejectedNodes.forEach { it.recycle() }

                if (matchingNodes.isEmpty()) continue

                val best = matchingNodes.minWithOrNull(
                    compareBy({ !isClickableOrHasClickableParent(it) }, {
                        val r = Rect(); it.getBoundsInScreen(r); r.width() * r.height()
                    })
                ) ?: matchingNodes[0]

                val rect = Rect()
                best.getBoundsInScreen(rect)
                val w = rect.width().coerceAtLeast(1)
                val h = rect.height().coerceAtLeast(1)
                val clickX = rect.left + (w * 0.1f + Random.nextInt((w * 0.8f).toInt().coerceAtLeast(1))).toInt()
                val clickY = rect.top  + (h * 0.1f + Random.nextInt((h * 0.8f).toInt().coerceAtLeast(1))).toInt()

                val delayMs = Random.nextLong(config.randomClickDelayMinMs, config.randomClickDelayMaxMs + 1)
                RideAutomationLogger.log("🚫 Reject '$term' in ${delayMs}ms at ($clickX, $clickY)")
                delay(delayMs)
                service.clickAt(clickX.toFloat(), clickY.toFloat())
                try {
                    if (best.isClickable) {
                        best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        var parent = best.parent
                        while (parent != null) {
                            if (parent.isClickable) {
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                parent.recycle(); break
                            }
                            val next = parent.parent
                            parent.recycle(); parent = next
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Direct node ACTION_CLICK on reject failed", e)
                }
                matchingNodes.forEach { it.recycle() }
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in accessibility reject matching", e)
        } finally {
            root.recycle()
        }
        RideAutomationLogger.log("⚠️ Reject button not found — ride will expire naturally.")
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun buildAcceptTerms(config: PriceConfig): List<String> {
        val terms = mutableListOf<String>()
        if (config.acceptButtonKeywords.isNotBlank()) {
            terms.addAll(config.acceptButtonKeywords.splitToSequence(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (terms.isEmpty()) terms.addAll(DEFAULT_ACCEPT_TERMS)
        // Prepend any template button text (highest priority)
        config.getTemplates().forEach { item ->
            if (item.buttonText.isNotBlank() && !terms.contains(item.buttonText)) {
                terms.add(0, item.buttonText)
            }
        }
        return terms
    }

    private fun isStrictMatch(nodeText: String, term: String): Boolean {
        val clean = nodeText.trim()
        val t     = term.trim()
        if (clean.equals(t, ignoreCase = true)) return true
        if (t.length <= 4) return wordBoundaryRegex(t).containsMatchIn(clean)
        return clean.contains(t, ignoreCase = true)
    }

    private fun isClickableOrHasClickableParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) { parent.recycle(); return true }
            val next = parent.parent
            parent.recycle(); parent = next
        }
        return false
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Screenshot capture (Android R+)
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun captureScreenshot(service: AutoClickService, context: Context): Bitmap? =
        suspendCoroutine { continuation ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
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
                                Log.e(TAG, "Screenshot capture failed: $errorCode")
                                continuation.resume(null)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking screenshot", e)
                    continuation.resume(null)
                }
            } else {
                continuation.resume(null)
            }
        }
}
