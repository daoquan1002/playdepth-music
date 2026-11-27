/**
 * MaterialYouColorEngine — Album-art seed extraction and Material You tonal palette generator.
 *
 * @remarks
 * Extracts the most visually prominent accent seed from a downsampled album-cover [Bitmap] using
 * perceptual OKLCH hue-bin scoring (70% population share + 30% chroma prominence, analogous to
 * Material Color Utilities `Score.score`) and synthesizes a full Material 3 `TonalSpot`
 * [ColorScheme] with Primary, Secondary, Tertiary (+60 deg hue rotation), Neutral, and
 * Neutral-Variant tonal scales. Gamut binary-search clamping preserves exact perceptual hue without
 * sRGB channel clipping, and works uniformly across all Android API levels (API 26+).
 *
 * @see MusicPlayerTheme
 */
package com.quandot.musicplayer.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object MaterialYouColorEngine {

    private const val SAMPLE_SIZE_PX = 48
    private const val HUE_BIN_COUNT = 72 // 5-degree bins around 360 deg
    private const val MIN_CHROMA_FOR_COLOR = 0.032f
    private const val MIN_LIGHTNESS_FOR_SEED = 0.08f
    private const val MAX_LIGHTNESS_FOR_SEED = 0.95f
    private const val ACHROMATIC_SENTINEL_ARGB = 0xFF808080.toInt()
    private const val SEED_CACHE_MAX_ENTRIES = 160
    private val seedColorCache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > SEED_CACHE_MAX_ENTRIES
    }

    /**
     * Thread-safe cached variant of [extractSeedColorArgb] keyed by artwork cache version + album key,
     * avoiding duplicate OKLCH pixel scans across [MusicViewModel] and [NowPlayingSheet].
     */
    fun getCachedSeedColorArgb(cacheKey: String): Int? = synchronized(seedColorCache) {
        seedColorCache[cacheKey]
    }

    fun extractSeedColorArgbCached(cacheKey: String, bitmap: Bitmap): Int? {
        getCachedSeedColorArgb(cacheKey)?.let { return it }
        val extracted = extractSeedColorArgb(bitmap) ?: return null
        synchronized(seedColorCache) {
            seedColorCache[cacheKey] = extracted
        }
        return extracted
    }

    /**
     * Derives a cohesive Material You seed color (ARGB) from a song's default fallback gradient
     * when no embedded or folder album artwork exists.
     */
    fun extractFallbackGradientSeedArgb(startColorLong: Long, endColorLong: Long): Int {
        val startArgb = startColorLong.toInt() or (0xFF shl 24)
        val endArgb = endColorLong.toInt() or (0xFF shl 24)
        return ColorUtils.blendARGB(startArgb, endArgb, 0.45f) or (0xFF shl 24)
    }

    /**
     * Extracts the most prominent Material You seed color (ARGB) from [bitmap].
     * Returns an achromatic neutral seed when the artwork is pure grayscale/monochrome so the
     * theme reflects black-and-white covers gracefully. Must be called off the main thread.
     */
    fun extractSeedColorArgb(bitmap: Bitmap): Int? {
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
                Bitmap.createScaledBitmap(readable, SAMPLE_SIZE_PX, SAMPLE_SIZE_PX, true).also {
                    if (it !== readable) scaled = it
                }
            } else {
                readable
            }

            val width = sampleBitmap.width
            val height = sampleBitmap.height
            val pixels = IntArray(width * height)
            sampleBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val binCounts = IntArray(HUE_BIN_COUNT)
            val binChromaSum = FloatArray(HUE_BIN_COUNT)
            val binLightnessSum = FloatArray(HUE_BIN_COUNT)
            val binSinSum = FloatArray(HUE_BIN_COUNT)
            val binCosSum = FloatArray(HUE_BIN_COUNT)
            var colorfulPixels = 0
            var opaquePixels = 0

            val lch = FloatArray(3)
            for (argb in pixels) {
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 180) continue
                opaquePixels++

                argbToOklch(argb, lch)
                val l = lch[0]
                val c = lch[1]
                val h = lch[2]

                if (c < MIN_CHROMA_FOR_COLOR || l < MIN_LIGHTNESS_FOR_SEED || l > MAX_LIGHTNESS_FOR_SEED) {
                    continue
                }

                val bin = (((h / 360f) * HUE_BIN_COUNT).toInt()).coerceIn(0, HUE_BIN_COUNT - 1)
                val rad = (h * PI / 180.0)
                binCounts[bin]++
                binChromaSum[bin] += c
                binLightnessSum[bin] += l
                binSinSum[bin] += sin(rad).toFloat()
                binCosSum[bin] += cos(rad).toFloat()
                colorfulPixels++
            }

            if (opaquePixels == 0) return null
            if (colorfulPixels < max(4, opaquePixels / 40)) {
                // Monochrome / grayscale cover artwork -> refined neutral monochrome seed
                return ACHROMATIC_SENTINEL_ARGB
            }

            // Smooth bin proportions across immediate neighbors (+/- 10 deg) like Material Color Utilities
            var bestBin = -1
            var bestScore = -1f
            for (b in 0 until HUE_BIN_COUNT) {
                if (binCounts[b] == 0) continue
                val prev2 = (b - 2 + HUE_BIN_COUNT) % HUE_BIN_COUNT
                val prev1 = (b - 1 + HUE_BIN_COUNT) % HUE_BIN_COUNT
                val next1 = (b + 1) % HUE_BIN_COUNT
                val next2 = (b + 2) % HUE_BIN_COUNT
                val neighborhoodCount =
                    binCounts[b] + (binCounts[prev1] + binCounts[next1]) * 0.65f +
                        (binCounts[prev2] + binCounts[next2]) * 0.25f
                val proportion = neighborhoodCount / colorfulPixels.toFloat()
                val avgChroma = binChromaSum[b] / binCounts[b].toFloat()
                val chromaFactor = (avgChroma / 0.18f).coerceIn(0f, 1.35f)
                val score = (proportion * 0.68f) + (chromaFactor * 0.32f)
                if (score > bestScore) {
                    bestScore = score
                    bestBin = b
                }
            }

            if (bestBin < 0) return ACHROMATIC_SENTINEL_ARGB

            val count = binCounts[bestBin].toFloat()
            val meanRad = atan2(binSinSum[bestBin].toDouble(), binCosSum[bestBin].toDouble())
            val meanHue = (((meanRad * 180.0 / PI) + 360.0) % 360.0).toFloat()
            val meanChroma = (binChromaSum[bestBin] / count).coerceIn(0.06f, 0.20f)
            val meanLightness = (binLightnessSum[bestBin] / count).coerceIn(0.45f, 0.72f)
            oklchToArgb(meanLightness, meanChroma, meanHue)
        } catch (_: Throwable) {
            null
        } finally {
            scaled?.let { if (!it.isRecycled) it.recycle() }
            softwareCopy?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    /**
     * Builds the Material 3 [ColorScheme] for Modern Now Playing controls matching Classic mode's
     * exact dynamic-color rules:
     * - Colorful album seed: uses [buildColorScheme] and only adjusts OKLCH lightness if contrast
     *   against a mid-tone ambient background falls below 3.0:1.
     * - Monochrome/grayscale album seed: returns clean monochrome [LIGHT_COLOR_SCHEME] or
     *   [DARK_COLOR_SCHEME] without inventing artificial chroma.
     * - Song dynamic color disabled (`seedColorArgb == null`): mirrors [MusicPlayerTheme] using
     *   wallpaper dynamic color (Android 12+) or default monochrome palette matched to [backgroundArgb].
     */
    fun buildModernControlColorScheme(
        seedColorArgb: Int?,
        backgroundArgb: Int,
        useWallpaperDynamic: Boolean,
        context: Context
    ): ColorScheme {
        val opaqueBgArgb = backgroundArgb or (0xFF shl 24)
        val bgLum = ColorUtils.calculateLuminance(opaqueBgArgb)
        val isLightBg = bgLum > 0.45

        if (seedColorArgb == null) {
            val baseFallback = when {
                useWallpaperDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (isLightBg) dynamicLightColorScheme(context) else dynamicDarkColorScheme(context)
                }
                isLightBg -> LIGHT_COLOR_SCHEME
                else -> DARK_COLOR_SCHEME
            }
            val fallbackContrast = ColorUtils.calculateContrast(baseFallback.primary.toArgb(), opaqueBgArgb)
            if (fallbackContrast >= 3.0) {
                return baseFallback
            }
            val fallbackLch = FloatArray(3)
            argbToOklch(baseFallback.primary.toArgb(), fallbackLch)
            if (fallbackLch[1] < 0.025f) {
                return baseFallback
            }
            val (adjPrimary, adjOnPrimary) = resolveContrastPrimaryInOklch(
                hue = fallbackLch[2],
                primaryChroma = fallbackLch[1].coerceIn(0.10f, 0.165f),
                backgroundArgb = opaqueBgArgb
            )
            return baseFallback.copy(primary = adjPrimary, onPrimary = adjOnPrimary)
        }

        val seedLch = FloatArray(3)
        argbToOklch(seedColorArgb, seedLch)
        if (seedLch[1] < 0.025f) {
            return if (isLightBg) LIGHT_COLOR_SCHEME else DARK_COLOR_SCHEME
        }

        val baseScheme = buildColorScheme(seedColorArgb, darkTheme = !isLightBg)
        val baseContrast = ColorUtils.calculateContrast(baseScheme.primary.toArgb(), opaqueBgArgb)
        if (baseContrast >= 3.0) {
            return baseScheme
        }

        // Mid-tone ambient background: adjust OKLCH lightness while keeping exact album hue & chroma
        val (adjPrimary, adjOnPrimary) = resolveContrastPrimaryInOklch(
            hue = seedLch[2],
            primaryChroma = seedLch[1].coerceIn(0.10f, 0.165f),
            backgroundArgb = opaqueBgArgb
        )
        return baseScheme.copy(
            primary = adjPrimary,
            onPrimary = adjOnPrimary
        )
    }

    private fun resolveContrastPrimaryInOklch(
        hue: Float,
        primaryChroma: Float,
        backgroundArgb: Int
    ): Pair<Color, Color> {
        val opaqueBgArgb = backgroundArgb or (0xFF shl 24)
        val darkLevels = floatArrayOf(0.42f, 0.37f, 0.32f, 0.27f)
        var bestDarkArgb = oklchToArgb(darkLevels.last(), primaryChroma, hue)
        var bestDarkContrast = ColorUtils.calculateContrast(bestDarkArgb, opaqueBgArgb)
        for (l in darkLevels) {
            val candidate = oklchToArgb(l, primaryChroma, hue)
            val contrast = ColorUtils.calculateContrast(candidate, opaqueBgArgb)
            if (contrast >= 3.2) {
                bestDarkArgb = candidate
                bestDarkContrast = contrast
                break
            }
            if (contrast > bestDarkContrast) {
                bestDarkArgb = candidate
                bestDarkContrast = contrast
            }
        }

        val lightLevels = floatArrayOf(0.82f, 0.85f)
        val lightChroma = primaryChroma * 0.85f
        var bestLightArgb = oklchToArgb(lightLevels.last(), lightChroma, hue)
        var bestLightContrast = ColorUtils.calculateContrast(bestLightArgb, opaqueBgArgb)
        for (l in lightLevels) {
            val candidate = oklchToArgb(l, lightChroma, hue)
            val contrast = ColorUtils.calculateContrast(candidate, opaqueBgArgb)
            if (contrast >= 3.2) {
                bestLightArgb = candidate
                bestLightContrast = contrast
                break
            }
            if (contrast > bestLightContrast) {
                bestLightArgb = candidate
                bestLightContrast = contrast
            }
        }

        val chosenArgb = if (bestDarkContrast >= bestLightContrast) bestDarkArgb else bestLightArgb
        val chosenOnPrimary = if (ColorUtils.calculateLuminance(chosenArgb) > 0.42) {
            oklchColor(0.24f, primaryChroma * 0.60f, hue)
        } else {
            Color.White
        }
        return Color(chosenArgb) to chosenOnPrimary
    }

    /**
     * Synthesizes a Material 3 `TonalSpot` [ColorScheme] from [seedColorArgb] for light or dark mode.
     */
    fun buildColorScheme(seedColorArgb: Int, darkTheme: Boolean): ColorScheme {
        val seedLch = FloatArray(3)
        argbToOklch(seedColorArgb, seedLch)
        val seedChroma = seedLch[1]
        val seedHue = seedLch[2]

        if (seedChroma < 0.025f) {
            return if (darkTheme) DARK_COLOR_SCHEME else LIGHT_COLOR_SCHEME
        }

        val primaryChroma = seedChroma.coerceIn(0.10f, 0.165f)
        val secondaryChroma = (primaryChroma * 0.36f).coerceIn(0.032f, 0.058f)
        val tertiaryHue = (seedHue + 60f) % 360f
        val tertiaryChroma = (primaryChroma * 0.52f).coerceIn(0.050f, 0.090f)
        val neutralChroma = 0.011f
        val neutralVarChroma = 0.022f

        return if (darkTheme) {
            darkColorScheme(
                primary = oklchColor(0.82f, primaryChroma * 0.85f, seedHue),
                onPrimary = oklchColor(0.24f, primaryChroma * 0.60f, seedHue),
                primaryContainer = oklchColor(0.35f, primaryChroma * 0.78f, seedHue),
                onPrimaryContainer = oklchColor(0.92f, primaryChroma * 0.45f, seedHue),
                secondary = oklchColor(0.80f, secondaryChroma, seedHue),
                onSecondary = oklchColor(0.24f, secondaryChroma, seedHue),
                secondaryContainer = oklchColor(0.33f, secondaryChroma * 0.95f, seedHue),
                onSecondaryContainer = oklchColor(0.90f, secondaryChroma * 0.70f, seedHue),
                tertiary = oklchColor(0.82f, tertiaryChroma * 0.85f, tertiaryHue),
                onTertiary = oklchColor(0.24f, tertiaryChroma * 0.65f, tertiaryHue),
                tertiaryContainer = oklchColor(0.35f, tertiaryChroma * 0.80f, tertiaryHue),
                onTertiaryContainer = oklchColor(0.92f, tertiaryChroma * 0.45f, tertiaryHue),
                background = oklchColor(0.145f, neutralChroma, seedHue),
                onBackground = oklchColor(0.905f, neutralChroma * 0.6f, seedHue),
                surface = oklchColor(0.145f, neutralChroma, seedHue),
                onSurface = oklchColor(0.905f, neutralChroma * 0.6f, seedHue),
                surfaceDim = oklchColor(0.125f, neutralChroma, seedHue),
                surfaceBright = oklchColor(0.27f, neutralChroma, seedHue),
                surfaceContainerLowest = oklchColor(0.105f, neutralChroma, seedHue),
                surfaceContainerLow = oklchColor(0.175f, neutralChroma, seedHue),
                surfaceContainer = oklchColor(0.205f, neutralChroma * 1.1f, seedHue),
                surfaceContainerHigh = oklchColor(0.245f, neutralChroma * 1.15f, seedHue),
                surfaceContainerHighest = oklchColor(0.29f, neutralChroma * 1.2f, seedHue),
                surfaceVariant = oklchColor(0.31f, neutralVarChroma, seedHue),
                onSurfaceVariant = oklchColor(0.80f, neutralVarChroma * 0.7f, seedHue),
                outline = oklchColor(0.60f, neutralVarChroma * 0.8f, seedHue),
                outlineVariant = oklchColor(0.32f, neutralVarChroma * 0.9f, seedHue)
            )
        } else {
            lightColorScheme(
                primary = oklchColor(0.47f, primaryChroma, seedHue),
                onPrimary = Color.White,
                primaryContainer = oklchColor(0.91f, primaryChroma * 0.45f, seedHue),
                onPrimaryContainer = oklchColor(0.20f, primaryChroma * 0.70f, seedHue),
                secondary = oklchColor(0.46f, secondaryChroma, seedHue),
                onSecondary = Color.White,
                secondaryContainer = oklchColor(0.90f, secondaryChroma * 0.70f, seedHue),
                onSecondaryContainer = oklchColor(0.21f, secondaryChroma, seedHue),
                tertiary = oklchColor(0.47f, tertiaryChroma, tertiaryHue),
                onTertiary = Color.White,
                tertiaryContainer = oklchColor(0.91f, tertiaryChroma * 0.45f, tertiaryHue),
                onTertiaryContainer = oklchColor(0.20f, tertiaryChroma * 0.70f, tertiaryHue),
                background = oklchColor(0.985f, neutralChroma * 0.65f, seedHue),
                onBackground = oklchColor(0.18f, neutralChroma, seedHue),
                surface = oklchColor(0.985f, neutralChroma * 0.65f, seedHue),
                onSurface = oklchColor(0.18f, neutralChroma, seedHue),
                surfaceDim = oklchColor(0.89f, neutralChroma * 0.9f, seedHue),
                surfaceBright = oklchColor(0.99f, neutralChroma * 0.5f, seedHue),
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = oklchColor(0.962f, neutralChroma * 0.8f, seedHue),
                surfaceContainer = oklchColor(0.942f, neutralChroma * 0.95f, seedHue),
                surfaceContainerHigh = oklchColor(0.918f, neutralChroma * 1.05f, seedHue),
                surfaceContainerHighest = oklchColor(0.892f, neutralChroma * 1.15f, seedHue),
                surfaceVariant = oklchColor(0.90f, neutralVarChroma * 0.8f, seedHue),
                onSurfaceVariant = oklchColor(0.36f, neutralVarChroma, seedHue),
                outline = oklchColor(0.52f, neutralVarChroma * 0.85f, seedHue),
                outlineVariant = oklchColor(0.80f, neutralVarChroma * 0.75f, seedHue)
            )
        }
    }

    // ═══ OKLab / OKLCH Color Space Math ═══
    private fun oklchColor(l: Float, c: Float, hDeg: Float): Color = Color(oklchToArgb(l, c, hDeg))

    private fun argbToOklch(argb: Int, outLch: FloatArray) {
        val r = srgbToLinear(((argb ushr 16) and 0xFF) / 255f)
        val g = srgbToLinear(((argb ushr 8) and 0xFF) / 255f)
        val b = srgbToLinear((argb and 0xFF) / 255f)

        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

        val lCbrt = cbrt(l)
        val mCbrt = cbrt(m)
        val sCbrt = cbrt(s)

        val okL = 0.2104542553 * lCbrt + 0.7936177850 * mCbrt - 0.0040720468 * sCbrt
        val okA = 1.9779984951 * lCbrt - 2.4285922050 * mCbrt + 0.4505937099 * sCbrt
        val okB = 0.0259040371 * lCbrt + 0.7827717662 * mCbrt - 0.8086757660 * sCbrt

        val chroma = sqrt(okA * okA + okB * okB)
        val hue = ((atan2(okB, okA) * 180.0 / PI) + 360.0) % 360.0

        outLch[0] = okL.toFloat()
        outLch[1] = chroma.toFloat()
        outLch[2] = hue.toFloat()
    }

    private fun oklchToArgb(l: Float, c: Float, hDeg: Float): Int {
        val clampedL = l.coerceIn(0f, 1f).toDouble()
        val rad = hDeg.toDouble() * PI / 180.0
        val cosH = cos(rad)
        val sinH = sin(rad)

        // Binary-search chroma into sRGB gamut to prevent hue shift on bright/dark tones
        var lo = 0.0
        var hi = c.coerceAtLeast(0f).toDouble()
        var targetC = hi
        if (!isOklabInGamut(clampedL, hi * cosH, hi * sinH)) {
            repeat(12) {
                val mid = (lo + hi) * 0.5
                if (isOklabInGamut(clampedL, mid * cosH, mid * sinH)) {
                    lo = mid
                } else {
                    hi = mid
                }
            }
            targetC = lo
        }

        val okA = targetC * cosH
        val okB = targetC * sinH
        val lCbrt = clampedL + 0.3963377774 * okA + 0.2158037573 * okB
        val mCbrt = clampedL - 0.1055613458 * okA - 0.0638541728 * okB
        val sCbrt = clampedL - 0.0894841775 * okA - 1.2914855480 * okB

        val lLin = lCbrt * lCbrt * lCbrt
        val mLin = mCbrt * mCbrt * mCbrt
        val sLin = sCbrt * sCbrt * sCbrt

        val rLin = +4.0767416621 * lLin - 3.3077115913 * mLin + 0.2309699292 * sLin
        val gLin = -1.2684380046 * lLin + 2.6097574011 * mLin - 0.3413193965 * sLin
        val bLin = -0.0041960863 * lLin - 0.7034186147 * mLin + 1.7076147010 * sLin

        val r = (linearToSrgb(rLin.toFloat()) * 255f).roundToInt().coerceIn(0, 255)
        val g = (linearToSrgb(gLin.toFloat()) * 255f).roundToInt().coerceIn(0, 255)
        val b = (linearToSrgb(bLin.toFloat()) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun isOklabInGamut(l: Double, a: Double, b: Double): Boolean {
        val lCbrt = l + 0.3963377774 * a + 0.2158037573 * b
        val mCbrt = l - 0.1055613458 * a - 0.0638541728 * b
        val sCbrt = l - 0.0894841775 * a - 1.2914855480 * b
        val lLin = lCbrt * lCbrt * lCbrt
        val mLin = mCbrt * mCbrt * mCbrt
        val sLin = sCbrt * sCbrt * sCbrt
        val r = +4.0767416621 * lLin - 3.3077115913 * mLin + 0.2309699292 * sLin
        val g = -1.2684380046 * lLin + 2.6097574011 * mLin - 0.3413193965 * sLin
        val bl = -0.0041960863 * lLin - 0.7034186147 * mLin + 1.7076147010 * sLin
        return r in -0.0005..1.0005 && g in -0.0005..1.0005 && bl in -0.0005..1.0005
    }

    private fun srgbToLinear(c: Float): Float {
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(c: Float): Float {
        val clamped = min(1f, max(0f, c))
        return if (clamped <= 0.0031308f) {
            clamped * 12.92f
        } else {
            (1.055f * clamped.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)
        }
    }
}
