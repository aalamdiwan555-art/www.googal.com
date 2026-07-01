package com.example.service

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log

object TemplateMatcher {
    private const val TAG = "TemplateMatcher"

    /**
     * Finds the best match of [template] inside [screen] using a downscaled
     * Sum-of-Absolute-Differences scan with early-exit pruning.
     *
     * Performance targets:
     *  – Downscale factor 4 → ~1/16 of original pixel count
     *  – Inner pixel step 3 → samples 1/9 of template pixels
     *  – Early-exit: abandon candidate positions that already exceed current best
     *  – Returns null if best match score is below [threshold]
     */
    fun findTemplateMatch(
        screen: Bitmap,
        template: Bitmap,
        threshold: Double = 0.82
    ): Point? {
        val sW = screen.width;   val sH = screen.height
        val tW = template.width; val tH = template.height

        if (tW > sW || tH > sH) {
            Log.w(TAG, "Template ($tW×$tH) larger than screen ($sW×$sH)")
            return null
        }

        // Downsample factor — keeps scan under ~30 ms on typical devices
        val step = 4
        val ssW = sW / step;  val ssH = sH / step
        val stW = tW / step;  val stH = tH / step

        if (stW <= 0 || stH <= 0 || ssW < stW || ssH < stH) return null

        return try {
            val scaledScreen    = Bitmap.createScaledBitmap(screen,   ssW, ssH, false)
            val scaledTemplate  = Bitmap.createScaledBitmap(template, stW, stH, false)

            val sPx = IntArray(ssW * ssH)
            val tPx = IntArray(stW * stH)
            scaledScreen.getPixels(sPx,  0, ssW, 0, 0, ssW, ssH)
            scaledTemplate.getPixels(tPx, 0, stW, 0, 0, stW, stH)
            scaledScreen.recycle()
            scaledTemplate.recycle()

            // Stride for candidate position scanning
            val scanStepX = (stW / 6).coerceAtLeast(1)
            val scanStepY = (stH / 6).coerceAtLeast(1)

            // Max allowed SAD per pixel-channel for a match
            val maxAllowedAvg = 1.0 - threshold          // e.g. 0.18 at threshold=0.82
            // total weight = stW/3 * stH/3 pixel samples × 255×3 channels
            val samplesPerPos  = ((stW + 2) / 3) * ((stH + 2) / 3)
            val maxAllowedSum  = maxAllowedAvg * samplesPerPos * 255.0 * 3.0

            var bestX = -1;  var bestY = -1
            var minDiff = Double.MAX_VALUE

            for (y in 0..(ssH - stH) step scanStepY) {
                val baseRow = y * ssW
                for (x in 0..(ssW - stW) step scanStepX) {
                    var diff = 0.0

                    // Early-exit inner loop: stop comparing this candidate once it can't beat best
                    outerLoop@ for (ty in 0 until stH step 3) {
                        val tRowOffset = ty * stW
                        val sRowOffset = (y + ty) * ssW + x
                        for (tx in 0 until stW step 3) {
                            val tp = tPx[tRowOffset + tx]
                            val sp = sPx[sRowOffset + tx]

                            diff += (((tp shr 16) and 0xFF) - ((sp shr 16) and 0xFF)).let { if (it < 0) -it else it }.toDouble() +
                                    (((tp shr  8) and 0xFF) - ((sp shr  8) and 0xFF)).let { if (it < 0) -it else it }.toDouble() +
                                    ( (tp         and 0xFF) - ( sp         and 0xFF)).let { if (it < 0) -it else it }.toDouble()

                            // Early exit: already worse than best known match
                            if (diff >= minDiff) break@outerLoop
                        }
                    }

                    if (diff < minDiff) {
                        minDiff = diff
                        bestX = x; bestY = y
                    }
                }
            }

            if (bestX >= 0 && bestY >= 0 && minDiff < maxAllowedSum) {
                val origX = bestX * step
                val origY = bestY * step
                Log.d(TAG, "Template matched at ($origX, $origY), SAD=$minDiff (max=$maxAllowedSum)")
                Point(origX, origY)
            } else {
                Log.d(TAG, "No match. Best SAD=$minDiff (max=$maxAllowedSum)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Template matching error", e)
            null
        }
    }
}
