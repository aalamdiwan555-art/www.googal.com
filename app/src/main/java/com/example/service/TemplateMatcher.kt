package com.example.service

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log

object TemplateMatcher {
    private const val TAG = "TemplateMatcher"

    /**
     * Finds the match of the template bitmap inside the screen bitmap using downscaled SAD (Sum of Absolute Differences).
     * Returns the Point representing the top-left coordinate of the match on the original screen, or null if no match is found.
     */
    fun findTemplateMatch(screen: Bitmap, template: Bitmap, threshold: Double = 0.82): Point? {
        val sWidth = screen.width
        val sHeight = screen.height
        val tWidth = template.width
        val tHeight = template.height

        if (tWidth > sWidth || tHeight > sHeight) {
            Log.w(TAG, "Template is larger than the screen. Template: ${tWidth}x${tHeight}, Screen: ${sWidth}x${sHeight}")
            return null
        }

        // Downsample factor to achieve super fast execution (<30ms)
        val step = 4 
        val subSWidth = sWidth / step
        val subSHeight = sHeight / step
        val subTWidth = tWidth / step
        val subTHeight = tHeight / step

        if (subTWidth <= 0 || subTHeight <= 0 || subSWidth < subTWidth || subSHeight < subTHeight) {
            return null
        }

        try {
            // Create downscaled bitmaps for scanning
            val scaledScreen = Bitmap.createScaledBitmap(screen, subSWidth, subSHeight, false)
            val scaledTemplate = Bitmap.createScaledBitmap(template, subTWidth, subTHeight, false)

            val screenPixels = IntArray(subSWidth * subSHeight)
            val templatePixels = IntArray(subTWidth * subTHeight)

            scaledScreen.getPixels(screenPixels, 0, subSWidth, 0, 0, subSWidth, subSHeight)
            scaledTemplate.getPixels(templatePixels, 0, subTWidth, 0, 0, subTWidth, subTHeight)

            var bestX = -1
            var bestY = -1
            var minDiff = Double.MAX_VALUE

            // Scan using step intervals for higher efficiency
            val scanStepX = maxOf(1, subTWidth / 6)
            val scanStepY = maxOf(1, subTHeight / 6)

            for (y in 0..(subSHeight - subTHeight) step scanStepY) {
                for (x in 0..(subSWidth - subTWidth) step scanStepX) {
                    var diff = 0.0
                    var count = 0
                    
                    // Compare pixels
                    for (ty in 0 until subTHeight step 3) {
                        for (tx in 0 until subTWidth step 3) {
                            val tp = templatePixels[ty * subTWidth + tx]
                            val sp = screenPixels[(y + ty) * subSWidth + (x + tx)]

                            val r1 = (tp shr 16) and 0xFF
                            val g1 = (tp shr 8) and 0xFF
                            val b1 = tp and 0xFF

                            val r2 = (sp shr 16) and 0xFF
                            val g2 = (sp shr 8) and 0xFF
                            val b2 = sp and 0xFF

                            diff += kotlin.math.abs(r1 - r2) + kotlin.math.abs(g1 - g2) + kotlin.math.abs(b1 - b2)
                            count++
                        }
                    }

                    val avgDiff = diff / (count * 255.0 * 3.0)
                    if (avgDiff < minDiff) {
                        minDiff = avgDiff
                        bestX = x
                        bestY = y
                    }
                }
            }

            scaledScreen.recycle()
            scaledTemplate.recycle()

            val maxAllowedDiff = 1.0 - threshold
            if (minDiff < maxAllowedDiff && bestX >= 0 && bestY >= 0) {
                // Map coordinates back
                val originalX = bestX * step
                val originalY = bestY * step
                Log.d(TAG, "Template matched! Match diff: $minDiff, Coordinates: ($originalX, $originalY)")
                return Point(originalX, originalY)
            } else {
                Log.d(TAG, "No match found. Best diff: $minDiff (Threshold threshold: $maxAllowedDiff)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in template matching", e)
        }

        return null
    }
}
