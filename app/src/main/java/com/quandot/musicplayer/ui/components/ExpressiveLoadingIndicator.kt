/**
 * ExpressiveLoadingIndicator — Material 3 Expressive morphing-polygon indeterminate loading indicator.
 *
 * @remarks
 * Implements the Material 3 Expressive `LoadingIndicator` choreography (continuous morphing across
 * 7 signature Material shapes — SoftBurst, Cookie9Sided, Pentagon, Pill, Sunny, Cookie4Sided, Oval
 * — paired with a spring-driven 90° twist per 650 ms beat and continuous clockwise rotation).
 * All polygon radii and trigonometric tables are precomputed once globally, and per-frame rendering
 * reuses a single [Path] and coordinate buffers for zero-allocation 60/120 fps drawing.
 */
package com.quandot.musicplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ═══ Constants & Precomputed Material 3 Expressive Shape Tables ═══
private const val SAMPLE_COUNT = 72
private const val MORPH_INTERVAL_MS = 650L
private const val CONTINUOUS_ROTATION_DEG_PER_SEC = 50f / (MORPH_INTERVAL_MS / 1000f)
private const val MORPH_TWIST_DEGREES = 90f
private const val MAX_NORMALIZED_RADIUS = 0.92f
private val DEFAULT_INDICATOR_SIZE = 48.dp

private object ExpressiveShapeTables {
    val cosTable = FloatArray(SAMPLE_COUNT)
    val sinTable = FloatArray(SAMPLE_COUNT)
    val shapes: Array<FloatArray>

    init {
        val twoPi = 2.0 * PI
        for (i in 0 until SAMPLE_COUNT) {
            val theta = twoPi * i / SAMPLE_COUNT
            cosTable[i] = cos(theta).toFloat()
            sinTable[i] = sin(theta).toFloat()
        }

        // Exact 7-shape Material 3 Expressive LoadingIndicator sequence:
        // SoftBurst -> Cookie9Sided -> Pentagon -> Pill -> Sunny -> Cookie4Sided -> Oval
        shapes = arrayOf(
            normalize(buildSoftBurst()),
            normalize(buildCookie9Sided()),
            normalize(buildRoundedPentagon()),
            normalize(buildPill()),
            normalize(buildSunny()),
            normalize(buildCookie4Sided()),
            normalize(buildOval())
        )
    }

    private fun normalize(radii: FloatArray): FloatArray {
        var maxR = 0.0001f
        for (r in radii) {
            if (r > maxR) maxR = r
        }
        val scale = MAX_NORMALIZED_RADIUS / maxR
        for (i in radii.indices) {
            radii[i] *= scale
        }
        return radii
    }

    private fun buildSoftBurst(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        (0.84 + 0.145 * cos(10.0 * theta) - 0.018 * cos(20.0 * theta)).toFloat()
    }

    private fun buildCookie9Sided(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        (0.89 + 0.11 * cos(9.0 * theta) - 0.024 * cos(18.0 * theta)).toFloat()
    }

    private fun buildRoundedPentagon(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        val p = 8.0
        var sum = 0.0
        for (j in 0 until 5) {
            val normalAngle = 2.0 * PI * j / 5.0 - PI / 2.0
            val proj = max(0.0, cos(theta - normalAngle))
            sum += proj.pow(p)
        }
        val support = sum.pow(1.0 / p).coerceAtLeast(0.001)
        (1.0 / support).toFloat()
    }

    private fun buildPill(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        val cosT = abs(cos(theta))
        val sinT = abs(sin(theta))
        val halfStraight = 0.34
        val capRadius = 0.66
        val r = if (sinT > 1e-6 && (capRadius / sinT) * cosT <= halfStraight) {
            capRadius / sinT
        } else {
            val b = halfStraight * cosT
            val disc = (b * b - (halfStraight * halfStraight - capRadius * capRadius)).coerceAtLeast(0.0)
            b + sqrt(disc)
        }
        r.toFloat()
    }

    private fun buildSunny(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        (0.845 + 0.155 * cos(8.0 * theta) - 0.024 * cos(16.0 * theta)).toFloat()
    }

    private fun buildCookie4Sided(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        (0.82 + 0.185 * cos(4.0 * theta) - 0.036 * cos(8.0 * theta)).toFloat()
    }

    private fun buildOval(): FloatArray = FloatArray(SAMPLE_COUNT) { i ->
        val theta = 2.0 * PI * i / SAMPLE_COUNT
        val a = 1.0
        val b = 0.72
        val cosT = cos(theta)
        val sinT = sin(theta)
        ((a * b) / sqrt((b * cosT) * (b * cosT) + (a * sinT) * (a * sinT))).toFloat()
    }
}

// ═══ Public Composables ═══
/**
 * Material 3 Expressive indeterminate loading indicator that smoothly morphs across organic
 * geometric shapes with expressive spring choreography.
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val shapes = ExpressiveShapeTables.shapes
    val shapeCount = shapes.size

    var currentShapeIndex by remember { mutableIntStateOf(0) }
    var steppedRotation by remember { mutableFloatStateOf(0f) }
    val continuousRotation = remember { mutableFloatStateOf(0f) }
    val morphAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            var lastNanos = withFrameNanos { it }
            while (true) {
                val nowNanos = withFrameNanos { it }
                val dtSec = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastNanos = nowNanos
                continuousRotation.floatValue =
                    (continuousRotation.floatValue + dtSec * CONTINUOUS_ROTATION_DEG_PER_SEC) % 360f
            }
        }

        val morphSpring = spring<Float>(
            dampingRatio = 0.6f,
            stiffness = 200f,
            visibilityThreshold = 0.005f
        )
        while (true) {
            val animJob = launch {
                morphAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = morphSpring
                )
            }
            delay(MORPH_INTERVAL_MS)
            animJob.cancel()
            steppedRotation = (steppedRotation + MORPH_TWIST_DEGREES * morphAnim.value) % 360f
            currentShapeIndex = (currentShapeIndex + 1) % shapeCount
            morphAnim.snapTo(0f)
        }
    }

    val path = remember { Path() }
    val xs = remember { FloatArray(SAMPLE_COUNT) }
    val ys = remember { FloatArray(SAMPLE_COUNT) }

    Canvas(
        modifier = modifier
            .size(DEFAULT_INDICATOR_SIZE)
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            }
    ) {
        val fromRadii = shapes[currentShapeIndex]
        val toRadii = shapes[(currentShapeIndex + 1) % shapeCount]
        val morph = morphAnim.value
        val rotationDeg = (continuousRotation.floatValue + steppedRotation + MORPH_TWIST_DEGREES * morph) % 360f

        val halfSide = min(size.width, size.height) * 0.5f
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val cosTable = ExpressiveShapeTables.cosTable
        val sinTable = ExpressiveShapeTables.sinTable

        for (i in 0 until SAMPLE_COUNT) {
            val r = (fromRadii[i] + (toRadii[i] - fromRadii[i]) * morph) * halfSide
            xs[i] = cx + cosTable[i] * r
            ys[i] = cy + sinTable[i] * r
        }

        path.rewind()
        val last = SAMPLE_COUNT - 1
        path.moveTo((xs[last] + xs[0]) * 0.5f, (ys[last] + ys[0]) * 0.5f)
        for (i in 0 until SAMPLE_COUNT) {
            val next = if (i == last) 0 else i + 1
            path.quadraticTo(
                xs[i],
                ys[i],
                (xs[i] + xs[next]) * 0.5f,
                (ys[i] + ys[next]) * 0.5f
            )
        }
        path.close()

        rotate(degrees = rotationDeg) {
            drawPath(path = path, color = color)
        }
    }
}

/**
 * Contained Material 3 Expressive loading indicator rendered inside a circular tonal container.
 */
@Composable
fun ContainedExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = modifier
            .size(DEFAULT_INDICATOR_SIZE)
            .clip(CircleShape)
            .background(containerColor)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        ExpressiveLoadingIndicator(
            modifier = Modifier.fillMaxSize(),
            color = indicatorColor
        )
    }
}
