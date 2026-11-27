/**
 * ArtworkEdgeColorSampler — Full-gamut 4-edge border color extractor for Modern Now Playing.
 *
 * @remarks
 * Operates completely independently of [MaterialYouColorEngine] without hue-bin quantization,
 * chroma clamping, or tonal snapping. Samples each of the four outer border strips (top, bottom,
 * left, right) plus top/bottom left-right corner zones in linearized RGB (IEC 61966-2-1) using a
 * median-anchored outlier-trimmed average so corner logos or text near an edge cannot muddy the
 * true border color, allowing frameless feathered album artwork to dissolve invisibly into the
 * full-bleed background.
 */
package com.quandot.musicplayer.ui.theme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

@Immutable
data class ArtworkEdgePalette(
    val topArgb: Int,
    val bottomArgb: Int,
    val leftArgb: Int,
    val rightArgb: Int,
    val topLeftArgb: Int,
    val topRightArgb: Int,
    val bottomLeftArgb: Int,
    val bottomRightArgb: Int,
    val horizontalMidArgb: Int,
    val unifiedArgb: Int
)

object ArtworkEdgeColorSampler {

    private const val SAMPLE_SIZE_PX = 96
    private const val EDGE_STRIP_FRACTION = 0.065f
    private const val MIN_EDGE_STRIP_PX = 3
    private const val MIN_OPAQUE_ALPHA = 180
    private const val INLIER_KEEP_FRACTION = 0.78f

    /**
     * Synthesizes an exact 4-edge [ArtworkEdgePalette] matching `FallbackGradient`'s diagonal
     * `Brush.linearGradient(startColor -> endColor)` (top-left to bottom-right) so songs without
     * embedded artwork blend seamlessly into the Modern ambient background as well.
     */
    fun fromFallbackGradient(startColorLong: Long, endColorLong: Long): ArtworkEdgePalette {
        val startArgb = startColorLong.toInt()
        val endArgb = endColorLong.toInt()
        val sLin = floatArrayOf(
            srgbByteToLinear((startArgb ushr 16) and 0xFF),
            srgbByteToLinear((startArgb ushr 8) and 0xFF),
            srgbByteToLinear(startArgb and 0xFF)
        )
        val eLin = floatArrayOf(
            srgbByteToLinear((endArgb ushr 16) and 0xFF),
            srgbByteToLinear((endArgb ushr 8) and 0xFF),
            srgbByteToLinear(endArgb and 0xFF)
        )
        val topLin = blendLinear(sLin, eLin, 0.25f)
        val bottomLin = blendLinear(sLin, eLin, 0.75f)
        val midLin = blendLinear(sLin, eLin, 0.50f)

        val topArgb = linearRgbToArgb(topLin[0], topLin[1], topLin[2])
        val bottomArgb = linearRgbToArgb(bottomLin[0], bottomLin[1], bottomLin[2])
        val midArgb = linearRgbToArgb(midLin[0], midLin[1], midLin[2])

        return ArtworkEdgePalette(
            topArgb = topArgb,
            bottomArgb = bottomArgb,
            leftArgb = topArgb,
            rightArgb = bottomArgb,
            topLeftArgb = startArgb or (0xFF shl 24),
            topRightArgb = midArgb,
            bottomLeftArgb = midArgb,
            bottomRightArgb = endArgb or (0xFF shl 24),
            horizontalMidArgb = midArgb,
            unifiedArgb = midArgb
        )
    }

    /**
     * Extracts full-gamut 24-bit ARGB colors independently for all four outer border edges and
     * corner segments of [bitmap]. Must be called off the main thread.
     */
    fun extractFourEdgeColors(bitmap: Bitmap): ArtworkEdgePalette? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null

        var softwareCopy: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
            ) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it } ?: return null
            } else {
                bitmap
            }

            val sampleBitmap = if (readable.width > SAMPLE_SIZE_PX || readable.height > SAMPLE_SIZE_PX) {
                val scale = SAMPLE_SIZE_PX.toFloat() / max(readable.width, readable.height).toFloat()
                val dstW = (readable.width * scale).roundToInt().coerceAtLeast(1)
                val dstH = (readable.height * scale).roundToInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(readable, dstW, dstH, true).also {
                    if (it !== readable) scaled = it
                }
            } else {
                readable
            }

            val w = sampleBitmap.width
            val h = sampleBitmap.height
            val pixels = IntArray(w * h)
            sampleBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val bandH = (h * EDGE_STRIP_FRACTION).roundToInt().coerceIn(MIN_EDGE_STRIP_PX.coerceAtMost(h), h)
            val bandW = (w * EDGE_STRIP_FRACTION).roundToInt().coerceIn(MIN_EDGE_STRIP_PX.coerceAtMost(w), w)
            val cornerSpanW = (w * 0.42f).roundToInt().coerceIn(1, w)

            val topLin = sampleHorizontalStrip(pixels, w, startX = 0, endXExclusive = w, startY = 0, endYExclusive = bandH, outerAtStart = true)
                ?: return null
            val bottomLin = sampleHorizontalStrip(pixels, w, startX = 0, endXExclusive = w, startY = h - bandH, endYExclusive = h, outerAtStart = false)
                ?: topLin
            val leftLin = sampleVerticalStrip(pixels, w, h, startX = 0, endXExclusive = bandW, outerAtStart = true)
                ?: blendLinear(topLin, bottomLin, 0.5f)
            val rightLin = sampleVerticalStrip(pixels, w, h, startX = w - bandW, endXExclusive = w, outerAtStart = false)
                ?: leftLin

            val topLeftLin = sampleHorizontalStrip(pixels, w, startX = 0, endXExclusive = cornerSpanW, startY = 0, endYExclusive = bandH, outerAtStart = true)
                ?: topLin
            val topRightLin = sampleHorizontalStrip(pixels, w, startX = w - cornerSpanW, endXExclusive = w, startY = 0, endYExclusive = bandH, outerAtStart = true)
                ?: topLin
            val bottomLeftLin = sampleHorizontalStrip(pixels, w, startX = 0, endXExclusive = cornerSpanW, startY = h - bandH, endYExclusive = h, outerAtStart = false)
                ?: bottomLin
            val bottomRightLin = sampleHorizontalStrip(pixels, w, startX = w - cornerSpanW, endXExclusive = w, startY = h - bandH, endYExclusive = h, outerAtStart = false)
                ?: bottomLin

            val midLR = blendLinear(leftLin, rightLin, 0.5f)
            val unified = FloatArray(3) { ch ->
                topLin[ch] * 0.30f + bottomLin[ch] * 0.30f + leftLin[ch] * 0.20f + rightLin[ch] * 0.20f
            }

            ArtworkEdgePalette(
                topArgb = linearRgbToArgb(topLin[0], topLin[1], topLin[2]),
                bottomArgb = linearRgbToArgb(bottomLin[0], bottomLin[1], bottomLin[2]),
                leftArgb = linearRgbToArgb(leftLin[0], leftLin[1], leftLin[2]),
                rightArgb = linearRgbToArgb(rightLin[0], rightLin[1], rightLin[2]),
                topLeftArgb = linearRgbToArgb(topLeftLin[0], topLeftLin[1], topLeftLin[2]),
                topRightArgb = linearRgbToArgb(topRightLin[0], topRightLin[1], topRightLin[2]),
                bottomLeftArgb = linearRgbToArgb(bottomLeftLin[0], bottomLeftLin[1], bottomLeftLin[2]),
                bottomRightArgb = linearRgbToArgb(bottomRightLin[0], bottomRightLin[1], bottomRightLin[2]),
                horizontalMidArgb = linearRgbToArgb(midLR[0], midLR[1], midLR[2]),
                unifiedArgb = linearRgbToArgb(unified[0], unified[1], unified[2])
            )
        } catch (_: Throwable) {
            null
        } finally {
            scaled?.let { if (!it.isRecycled) it.recycle() }
            softwareCopy?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun sampleHorizontalStrip(
        pixels: IntArray,
        width: Int,
        startX: Int,
        endXExclusive: Int,
        startY: Int,
        endYExclusive: Int,
        outerAtStart: Boolean
    ): FloatArray? {
        val rows = (endYExclusive - startY).coerceAtLeast(1)
        val cols = (endXExclusive - startX).coerceAtLeast(1)
        val capacity = rows * cols
        val rBuf = FloatArray(capacity)
        val gBuf = FloatArray(capacity)
        val bBuf = FloatArray(capacity)
        val wBuf = FloatArray(capacity)
        var count = 0

        for (y in startY until endYExclusive) {
            val distFromOuter = if (outerAtStart) (y - startY) else (endYExclusive - 1 - y)
            val proxWeight = 1.0f - (distFromOuter.toFloat() / rows.toFloat()) * 0.72f
            val rowOffset = y * width
            for (x in startX until endXExclusive) {
                val argb = pixels[rowOffset + x]
                if (((argb ushr 24) and 0xFF) < MIN_OPAQUE_ALPHA) continue
                rBuf[count] = srgbByteToLinear((argb ushr 16) and 0xFF)
                gBuf[count] = srgbByteToLinear((argb ushr 8) and 0xFF)
                bBuf[count] = srgbByteToLinear(argb and 0xFF)
                wBuf[count] = proxWeight
                count++
            }
        }
        return computeTrimmedLinearCentroid(rBuf, gBuf, bBuf, wBuf, count)
    }

    private fun sampleVerticalStrip(
        pixels: IntArray,
        width: Int,
        height: Int,
        startX: Int,
        endXExclusive: Int,
        outerAtStart: Boolean
    ): FloatArray? {
        val cols = (endXExclusive - startX).coerceAtLeast(1)
        val capacity = cols * height
        val rBuf = FloatArray(capacity)
        val gBuf = FloatArray(capacity)
        val bBuf = FloatArray(capacity)
        val wBuf = FloatArray(capacity)
        var count = 0

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in startX until endXExclusive) {
                val argb = pixels[rowOffset + x]
                if (((argb ushr 24) and 0xFF) < MIN_OPAQUE_ALPHA) continue
                val distFromOuter = if (outerAtStart) (x - startX) else (endXExclusive - 1 - x)
                val proxWeight = 1.0f - (distFromOuter.toFloat() / cols.toFloat()) * 0.72f
                rBuf[count] = srgbByteToLinear((argb ushr 16) and 0xFF)
                gBuf[count] = srgbByteToLinear((argb ushr 8) and 0xFF)
                bBuf[count] = srgbByteToLinear(argb and 0xFF)
                wBuf[count] = proxWeight
                count++
            }
        }
        return computeTrimmedLinearCentroid(rBuf, gBuf, bBuf, wBuf, count)
    }

    /**
     * Computes a median-anchored trimmed weighted mean in linear RGB space.
     * Rejects extreme outlier pixels (e.g., corner text, labels, watermarks)
     * so solid and gradient borders resolve to their true full-gamut color.
     */
    private fun computeTrimmedLinearCentroid(
        rBuf: FloatArray,
        gBuf: FloatArray,
        bBuf: FloatArray,
        wBuf: FloatArray,
        count: Int
    ): FloatArray? {
        if (count == 0) return null

        val medR = medianOf(rBuf, count)
        val medG = medianOf(gBuf, count)
        val medB = medianOf(bBuf, count)

        val dists = FloatArray(count)
        for (i in 0 until count) {
            val dr = rBuf[i] - medR
            val dg = gBuf[i] - medG
            val db = bBuf[i] - medB
            dists[i] = 0.299f * dr * dr + 0.587f * dg * dg + 0.114f * db * db
        }

        val sortedDists = dists.copyOf()
        sortedDists.sort()
        val thresholdIdx = ((count - 1) * INLIER_KEEP_FRACTION).toInt().coerceIn(0, count - 1)
        val maxInlierDist = sortedDists[thresholdIdx]

        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var wSum = 0.0
        for (i in 0 until count) {
            if (dists[i] <= maxInlierDist) {
                val w = wBuf[i].toDouble()
                rSum += rBuf[i] * w
                gSum += gBuf[i] * w
                bSum += bBuf[i] * w
                wSum += w
            }
        }
        if (wSum <= 0.0) {
            return floatArrayOf(medR, medG, medB)
        }
        return floatArrayOf(
            (rSum / wSum).toFloat().coerceIn(0f, 1f),
            (gSum / wSum).toFloat().coerceIn(0f, 1f),
            (bSum / wSum).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun medianOf(values: FloatArray, count: Int): Float {
        val copy = values.copyOf(count)
        copy.sort()
        val mid = count / 2
        return if (count % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) * 0.5f
    }

    private fun blendLinear(a: FloatArray, b: FloatArray, fraction: Float): FloatArray {
        val inv = 1f - fraction
        return floatArrayOf(
            a[0] * inv + b[0] * fraction,
            a[1] * inv + b[1] * fraction,
            a[2] * inv + b[2] * fraction
        )
    }

    private fun srgbByteToLinear(channelByte: Int): Float {
        val v = channelByte / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private fun linearToSrgbByte(linear: Float): Int {
        val clamped = linear.coerceIn(0f, 1f)
        val srgb = if (clamped <= 0.0031308f) {
            12.92f * clamped
        } else {
            (1.055 * clamped.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()
        }
        return (srgb * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun linearRgbToArgb(rLin: Float, gLin: Float, bLin: Float): Int {
        val r = linearToSrgbByte(rLin)
        val g = linearToSrgbByte(gLin)
        val b = linearToSrgbByte(bLin)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
