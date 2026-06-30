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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

class PriceFilterEngine(private val context: Context) {
    private val TAG = "PriceFilterEngine"
    
    // Multi-currency price extractor regex (matches currency symbols + numeric price)
    private val priceRegex = Regex("""(?:Price|Fare|Earn|Est\.|Total|Cost)?\s*[₹$€£]\s*([\d,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE)

    fun extractPriceFromText(text: String): Double? {
        val match = priceRegex.find(text)
        if (match != null) {
            return match.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        // Fallback search for bare numbers if they look like prices
        val numbers = Regex("""\b\d+(?:\.\d{2})?\b""").findAll(text)
        for (num in numbers) {
            val d = num.value.toDoubleOrNull()
            if (d != null && d > 1.0) {
                // Ensure this bare number is not part of a distance (like "5.2 km")
                val index = text.indexOf(num.value)
                if (index != -1) {
                    val afterSub = text.substring(index + num.value.length).trimStart()
                    if (afterSub.startsWith("km", ignoreCase = true) || afterSub.startsWith("kms", ignoreCase = true)) {
                        continue // Skip distance
                    }
                }
                return d
            }
        }
        return null
    }

    fun extractDistancesFromText(text: String): Pair<Double?, Double?> {
        val distanceRegex = Regex("""(\d+(?:\.\d+)?)\s*[Kk]m[sS]?""")
        val matches = distanceRegex.findAll(text).toList()
        
        val pickup = if (matches.isNotEmpty()) matches[0].groupValues[1].toDoubleOrNull() else null
        val drop = if (matches.size > 1) matches[1].groupValues[1].toDoubleOrNull() else null
        
        return Pair(pickup, drop)
    }

    fun shouldClick(screenText: String, config: PriceConfig): Boolean {
        if (!config.enabled) return true
        val price = extractPriceFromText(screenText) ?: return true
        return price >= config.minPrice && price <= config.maxPrice
    }

    /**
     * Executes the ride evaluation.
     * Returns true if a match was processed, false otherwise.
     */
    suspend fun evaluateAndProcessScreen(screenText: String, config: PriceConfig, service: AutoClickService): Boolean {
        if (!config.enabled) return false

        // Check if there is an active accept affordance on screen (either keyword or template)
        val acceptTerms = mutableListOf<String>()
        if (config.acceptButtonKeywords.isNotBlank()) {
            acceptTerms.addAll(config.acceptButtonKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (acceptTerms.isEmpty()) {
            acceptTerms.addAll(listOf(
                "Accept", "Accept Ride", "Confirm", "Take", "Go", "Agree", "Book", "Start", 
                "Tap to Accept", "Let's Go", "ACCEPT", "CONFIRM", "GO", "ACCEPT RIDE", "TAP TO ACCEPT"
            ))
        }
        
        val hasAcceptKeyword = acceptTerms.any { screenText.contains(it, ignoreCase = true) }
        
        // Check if there is a price or accept keyword on screen
        val price = extractPriceFromText(screenText)
        val hasPrice = (price != null)
        
        // If there's no price and no accept keyword on screen, we don't have a ride offer.
        // We can also double check templates if templates exist.
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
                    if (matchPoint != null) {
                        hasTemplateMatch = true
                        break
                    }
                }
                screenshot.recycle()
            }
        }

        val isRideOfferActive = hasPrice || hasAcceptKeyword || hasTemplateMatch
        if (!isRideOfferActive) {
            // Not a ride offer screen, skip to prevent false positive scans
            return false
        }

        if (hasPrice) {
            RideAutomationLogger.log("Detected Incoming Ride! 🏷️ Price: ${config.currencySymbol}$price")
        } else {
            RideAutomationLogger.log("Detected Incoming Ride! (Evaluating keywords & matching filters)")
        }

        // Parse and check pickup & drop distances
        val (pickupDist, dropDist) = extractDistancesFromText(screenText)
        var distanceMatched = true
        var distanceRejectReason = ""

        if (pickupDist != null) {
            val bypassPickup = (config.minPickupDistance == 0.0 && config.maxPickupDistance == 0.0)
            if (!bypassPickup) {
                RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (Allowed range: ${config.minPickupDistance} - ${config.maxPickupDistance} Km)")
                if (pickupDist < config.minPickupDistance || pickupDist > config.maxPickupDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Pickup distance ${pickupDist} Km is out of range (${config.minPickupDistance} - ${config.maxPickupDistance} Km)"
                }
            } else {
                RideAutomationLogger.log("Parsed Pickup Distance: ${pickupDist} Km (Pickup range filter bypassed: 0.0 - 0.0)")
            }
        } else {
            RideAutomationLogger.log("Parsed Pickup Distance: None detected")
        }

        if (dropDist != null && distanceMatched) {
            val bypassDrop = (config.minDropDistance == 0.0 && config.maxDropDistance == 0.0)
            if (!bypassDrop) {
                RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (Allowed range: ${config.minDropDistance} - ${config.maxDropDistance} Km)")
                if (dropDist < config.minDropDistance || dropDist > config.maxDropDistance) {
                    distanceMatched = false
                    distanceRejectReason = "Drop distance ${dropDist} Km is out of range (${config.minDropDistance} - ${config.maxDropDistance} Km)"
                }
            } else {
                RideAutomationLogger.log("Parsed Drop Distance: ${dropDist} Km (Drop range filter bypassed: 0.0 - 0.0)")
            }
        } else if (dropDist == null) {
            RideAutomationLogger.log("Parsed Drop Distance: None detected")
        }

        // Check pickup/drop location keywords
        var keywordMatched = true
        if (config.pickupKeywords.isNotBlank() || config.dropKeywords.isNotBlank()) {
            val pickupKeywordsList = config.pickupKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val dropKeywordsList = config.dropKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            if (pickupKeywordsList.isNotEmpty()) {
                val matchesPickup = pickupKeywordsList.any { screenText.contains(it, ignoreCase = true) }
                if (!matchesPickup) {
                    RideAutomationLogger.log("Location filter: Pickup does not match required keywords.")
                    keywordMatched = false
                }
            }
            if (dropKeywordsList.isNotEmpty()) {
                val matchesDrop = dropKeywordsList.any { screenText.contains(it, ignoreCase = true) }
                if (matchesDrop) {
                    RideAutomationLogger.log("Location filter: Avoided Drop keyword matched!")
                    keywordMatched = false
                }
            }
        }

        // Decisions:
        val isPriceBelowMin = hasPrice && (price!! < config.minPrice)

        val shouldAccept = !isPriceBelowMin && keywordMatched && distanceMatched

        if (shouldAccept) {
            if (hasPrice) {
                RideAutomationLogger.log("✅ RIDE MATCHED! Price ${config.currencySymbol}$price meets criteria.")
            } else {
                RideAutomationLogger.log("✅ RIDE MATCHED! Location and Accept filters satisfied.")
            }
            return triggerAccept(service, config, screenText)
        } else {
            val reason = when {
                isPriceBelowMin -> "Price ${config.currencySymbol}$price is below minimum ${config.currencySymbol}${config.minPrice}"
                !distanceMatched -> distanceRejectReason
                !keywordMatched -> "Location keywords did not match criteria"
                else -> "Does not meet filters"
            }
            RideAutomationLogger.log("🚫 REJECTING RIDE: $reason")
            return triggerReject(service, config)
        }
    }

    private suspend fun triggerAccept(service: AutoClickService, config: PriceConfig, screenText: String): Boolean {
        val templates = config.getTemplates()

        // Attempt Template Image Matching if templates uploaded (automatically on)
        if (templates.isNotEmpty()) {
            RideAutomationLogger.log("🔍 Scanning screen with ${templates.size} Accept Button template(s)...")
            val screenshot = captureScreenshot(service, context)
            if (screenshot != null) {
                for ((index, item) in templates.withIndex()) {
                    val templateFile = File(item.imagePath)
                    if (!templateFile.exists()) continue
                    val templateBitmap = BitmapFactory.decodeFile(templateFile.absolutePath) ?: continue

                    val matchPoint = TemplateMatcher.findTemplateMatch(screenshot, templateBitmap)
                    if (matchPoint != null) {
                        // Found template match visually. Now check if custom text is also specified.
                        if (item.buttonText.isNotBlank()) {
                            val textInScreen = screenText.contains(item.buttonText, ignoreCase = true)
                            val root = service.rootInActiveWindow
                            var hasTextNode = false
                            if (root != null) {
                                try {
                                    val textNodes = root.findAccessibilityNodeInfosByText(item.buttonText)
                                    hasTextNode = !textNodes.isNullOrEmpty()
                                    textNodes?.forEach { it.recycle() }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error matching template text node", e)
                                } finally {
                                    root.recycle()
                                }
                            }

                            if (!textInScreen && !hasTextNode) {
                                RideAutomationLogger.log("🔍 Template #${index + 1} matched visually, but text '${item.buttonText}' not found on screen. Skipping template.")
                                templateBitmap.recycle()
                                continue
                            } else {
                                RideAutomationLogger.log("✨ Perfect match! Template #${index + 1} image and text '${item.buttonText}' verified together.")
                            }
                        } else {
                            RideAutomationLogger.log("🎯 Match found! Template #${index + 1} image matched perfectly (no text filter).")
                        }

                        // Click inside template button bounds randomly
                        val clickX = matchPoint.x + Random.nextInt(maxOf(1, (templateBitmap.width * 0.8).toInt())) + (templateBitmap.width * 0.1).toInt()
                        val clickY = matchPoint.y + Random.nextInt(maxOf(1, (templateBitmap.height * 0.8).toInt())) + (templateBitmap.height * 0.1).toInt()

                        val delayMs = Random.nextLong(1, 101) // Anti-detection random delay between 1ms and 100ms
                        RideAutomationLogger.log("⚡ [Anti-Detection Delay] Delaying ${delayMs}ms before clicking Accept template at random offset ($clickX, $clickY)")
                        delay(delayMs)
                        service.clickAt(clickX.toFloat(), clickY.toFloat())
                        templateBitmap.recycle()
                        screenshot.recycle()
                        return true
                    }
                    templateBitmap.recycle()
                }
                screenshot.recycle()
            }
            RideAutomationLogger.log("⚠️ Template matching failed or templates not found. Falling back to accessibility click.")
        }

        // Fallback to text element matching using configurable keywords
        val acceptTerms = mutableListOf<String>()
        if (config.acceptButtonKeywords.isNotBlank()) {
            acceptTerms.addAll(config.acceptButtonKeywords.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }
        if (acceptTerms.isEmpty()) {
            acceptTerms.addAll(listOf(
                "Accept", "Accept Ride", "Confirm", "Take", "Go", "Agree", "Book", "Start", 
                "Tap to Accept", "Let's Go", "ACCEPT", "CONFIRM", "GO", "ACCEPT RIDE", "TAP TO ACCEPT"
            ))
        }
        // Prioritize any custom text added in templates
        templates.forEach {
            if (it.buttonText.isNotBlank() && !acceptTerms.contains(it.buttonText)) {
                acceptTerms.add(0, it.buttonText)
            }
        }

        val root = service.rootInActiveWindow
        if (root != null) {
            try {
                for (term in acceptTerms) {
                    val nodes = root.findAccessibilityNodeInfosByText(term)
                    if (!nodes.isNullOrEmpty()) {
                        // Prioritize clickable nodes or nodes with clickable parents
                        var bestNode = nodes.find { it.isClickable }
                        if (bestNode == null) {
                            bestNode = nodes.find {
                                var hasClickableParent = false
                                var parent = it.parent
                                while (parent != null) {
                                    if (parent.isClickable) {
                                        hasClickableParent = true
                                        parent.recycle()
                                        break
                                    }
                                    val temp = parent.parent
                                    parent.recycle()
                                    parent = temp
                                }
                                hasClickableParent
                            }
                        }
                        val node = bestNode ?: nodes[0]
                        val rect = Rect()
                        node.getBoundsInScreen(rect)

                        // Click at random point inside bounds to simulate human finger touch (different positions)
                        val clickX = rect.left + Random.nextInt(maxOf(1, (rect.width() * 0.8).toInt())) + (rect.width() * 0.1).toInt()
                        val clickY = rect.top + Random.nextInt(maxOf(1, (rect.height() * 0.8).toInt())) + (rect.height() * 0.1).toInt()

                        val delayMs = Random.nextLong(1, 101) // Anti-detection random delay between 1ms and 100ms
                        RideAutomationLogger.log("⚡ [Anti-Detection Delay] Accessibility element '$term' matched! Clicking Accept Button in ${delayMs}ms at random offset ($clickX, $clickY)")
                        delay(delayMs)
                        
                        // Dual-dispatch accept: physical tap + accessibility direct action click for 100% reliability
                        service.clickAt(clickX.toFloat(), clickY.toFloat())
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
                            Log.e(TAG, "Error performing direct node click action", e)
                        }

                        nodes.forEach { it.recycle() }
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in accessibility accept button matching", e)
            } finally {
                root.recycle()
            }
        }

        RideAutomationLogger.log("❌ Failed to click Accept: Button not found on screen.")
        return false
    }

    private suspend fun triggerReject(service: AutoClickService, config: PriceConfig): Boolean {
        val rejectTerms = listOf("Decline", "Reject", "Skip", "No thanks", "Decline ride", "Cancel", "X", "Ignore")
        val root = service.rootInActiveWindow
        if (root != null) {
            try {
                for (term in rejectTerms) {
                    val nodes = root.findAccessibilityNodeInfosByText(term)
                    if (!nodes.isNullOrEmpty()) {
                        // Prioritize clickable nodes or nodes with clickable parents
                        var bestNode = nodes.find { it.isClickable }
                        if (bestNode == null) {
                            bestNode = nodes.find {
                                var hasClickableParent = false
                                var parent = it.parent
                                while (parent != null) {
                                    if (parent.isClickable) {
                                        hasClickableParent = true
                                        parent.recycle()
                                        break
                                    }
                                    val temp = parent.parent
                                    parent.recycle()
                                    parent = temp
                                }
                                hasClickableParent
                            }
                        }
                        val node = bestNode ?: nodes[0]
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        
                        // Click at random point inside bounds
                        val clickX = rect.left + Random.nextInt(maxOf(1, (rect.width() * 0.8).toInt())) + (rect.width() * 0.1).toInt()
                        val clickY = rect.top + Random.nextInt(maxOf(1, (rect.height() * 0.8).toInt())) + (rect.height() * 0.1).toInt()
                        
                        val delayMs = Random.nextLong(config.randomClickDelayMinMs, config.randomClickDelayMaxMs)
                        RideAutomationLogger.log("🚫 Clicking Reject Button '$term' in ${delayMs}ms at random offset ($clickX, $clickY)")
                        delay(delayMs)
                        service.clickAt(clickX.toFloat(), clickY.toFloat())
                        
                        // Try direct accessibility action click on reject too for completeness
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
                            Log.e(TAG, "Error performing direct reject click action", e)
                        }

                        nodes.forEach { it.recycle() }
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in accessibility reject button matching", e)
            } finally {
                root.recycle()
            }
        }
        RideAutomationLogger.log("⚠️ Reject button not found on screen. Ride will expire naturally.")
        return false
    }

    private suspend fun captureScreenshot(service: AutoClickService, context: Context): Bitmap? = suspendCoroutine { continuation ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    context.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            val buffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                            buffer?.close()
                            if (bitmap != null) {
                                val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                continuation.resume(softwareBitmap)
                            } else {
                                continuation.resume(null)
                            }
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
