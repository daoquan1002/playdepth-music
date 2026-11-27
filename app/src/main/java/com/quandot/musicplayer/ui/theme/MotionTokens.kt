/**
 * MotionTokens — Lightweight motion tokens, simple fade transitions, and keep-alive containers.
 *
 * @remarks
 * Prioritizes consistent 60fps performance on low-end Android devices by replacing heavy spring
 * physics, multi-axis slide/scale composites, and per-frame layout-reflow (`expandVertically`)
 * animations with short, GPU-cheap fades (90–130ms). `M3KeepAliveSlot` uses
 * `CompositingStrategy.ModulateAlpha` plus offscreen translation so heavy library lists preserve
 * scroll position across tab switches without detaching from composition.
 *
 * @see M3Motion
 */
package com.quandot.musicplayer.ui.theme

// External SDK / Jetpack / third-party libraries
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ═══ Constants ═══
private const val OFFSCREEN_TRANSLATION_X = 100000f
private const val MIN_VISIBLE_ALPHA = 0.01f
private const val PREDICTIVE_BACK_EPSILON = 0.001f
private const val PREDICTIVE_BACK_CANCEL_MS = 180
private const val PREDICTIVE_BACK_SCRIM_MAX_ALPHA = 0.24f
private val PREDICTIVE_BACK_CORNER_SHAPE = RoundedCornerShape(28.dp)

// ═══ Motion Tokens ═══
/**
 * Low-overhead motion specs and simple fade transition builders tuned for budget GPUs/CPUs.
 */
object M3Motion {
    const val SIMPLE_ENTER_MS = 120
    const val SIMPLE_EXIT_MS = 90
    const val OVERLAY_ENTER_MS = 130
    const val OVERLAY_EXIT_MS = 95
    const val FLYOUT_ENTER_MS = 220
    const val FLYOUT_EXIT_MS = 170
    const val QUEUE_ENTER_MS = 200
    const val QUEUE_EXIT_MS = 160

    val EmphasizedDecelerate: Easing = LinearOutSlowInEasing
    val EmphasizedAccelerate: Easing = FastOutLinearInEasing
    val SheetDecelerate: Easing = LinearOutSlowInEasing
    val SheetAccelerate: Easing = FastOutLinearInEasing
    val Standard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val StandardDecelerate: Easing = LinearOutSlowInEasing
    val StandardAccelerate: Easing = FastOutLinearInEasing

    fun simpleFadeIn(durationMillis: Int = SIMPLE_ENTER_MS): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = durationMillis, easing = StandardDecelerate))

    fun simpleFadeOut(durationMillis: Int = SIMPLE_EXIT_MS): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = durationMillis, easing = StandardAccelerate))

    fun sharedAxisXEnter(): EnterTransition = simpleFadeIn(SIMPLE_ENTER_MS)

    fun sharedAxisXExit(): ExitTransition = simpleFadeOut(SIMPLE_EXIT_MS)

    fun sheetEnter(): EnterTransition = simpleFadeIn(OVERLAY_ENTER_MS)

    fun sheetExit(): ExitTransition = simpleFadeOut(OVERLAY_EXIT_MS)

    fun miniPlayerEnter(): EnterTransition = simpleFadeIn(110)

    fun miniPlayerExit(): ExitTransition = simpleFadeOut(85)

    fun hudEnter(): EnterTransition = simpleFadeIn(110)

    fun hudExit(): ExitTransition = simpleFadeOut(85)

    fun searchHeaderTransform(): ContentTransform =
        simpleFadeIn(100) togetherWith simpleFadeOut(80)

    fun trackArtTransform(@Suppress("UNUSED_PARAMETER") direction: Int = 1): ContentTransform =
        simpleFadeIn(110) togetherWith simpleFadeOut(85)

    fun trackTextTransform(@Suppress("UNUSED_PARAMETER") direction: Int = 1): ContentTransform =
        simpleFadeIn(100) togetherWith simpleFadeOut(80)

    fun iconMorphTransform(): ContentTransform =
        simpleFadeIn(80) togetherWith simpleFadeOut(60)

    /**
     * Smooth Material 3 shared-axis Z + gentle vertical lift transition for opening/closing
     * the Now Playing lyrics panel over the album artwork slot.
     */
    fun lyricsPanelTransform(openingLyrics: Boolean): ContentTransform = if (openingLyrics) {
        ContentTransform(
            targetContentEnter = fadeIn(
                animationSpec = tween(
                    durationMillis = 320,
                    delayMillis = 55,
                    easing = EmphasizedDecelerate
                )
            ) + slideInVertically(
                animationSpec = tween(
                    durationMillis = 360,
                    easing = EmphasizedDecelerate
                ),
                initialOffsetY = { (it * 0.08f).toInt() }
            ) + scaleIn(
                animationSpec = tween(
                    durationMillis = 360,
                    easing = EmphasizedDecelerate
                ),
                initialScale = 0.94f
            ),
            initialContentExit = fadeOut(
                animationSpec = tween(
                    durationMillis = 210,
                    easing = EmphasizedAccelerate
                )
            ) + scaleOut(
                animationSpec = tween(
                    durationMillis = 250,
                    easing = EmphasizedAccelerate
                ),
                targetScale = 0.91f
            ),
            sizeTransform = NoClipSizeTransform
        )
    } else {
        ContentTransform(
            targetContentEnter = fadeIn(
                animationSpec = tween(
                    durationMillis = 310,
                    delayMillis = 45,
                    easing = EmphasizedDecelerate
                )
            ) + scaleIn(
                animationSpec = tween(
                    durationMillis = 340,
                    easing = EmphasizedDecelerate
                ),
                initialScale = 0.92f
            ),
            initialContentExit = fadeOut(
                animationSpec = tween(
                    durationMillis = 190,
                    easing = EmphasizedAccelerate
                )
            ) + slideOutVertically(
                animationSpec = tween(
                    durationMillis = 230,
                    easing = EmphasizedAccelerate
                ),
                targetOffsetY = { (it * 0.07f).toInt() }
            ) + scaleOut(
                animationSpec = tween(
                    durationMillis = 230,
                    easing = EmphasizedAccelerate
                ),
                targetScale = 0.95f
            ),
            sizeTransform = NoClipSizeTransform
        )
    }

    val ExpressiveSpringFloat = tween<Float>(durationMillis = 120, easing = Standard)

    val SnappySpringFloat = tween<Float>(durationMillis = 100, easing = Standard)

    val NoClipSizeTransform = SizeTransform(clip = false)
}

// ═══ Modifier Extensions & Transition Slots ═══
/**
 * Zero-overhead pass-through kept for API compatibility; Material ripple handles touch feedback
 * directly without allocating per-button spring animators or RenderNode scale layers.
 */
fun Modifier.m3PressScale(
    @Suppress("UNUSED_PARAMETER") interactionSource: InteractionSource,
    @Suppress("UNUSED_PARAMETER") pressedScale: Float = 0.88f
): Modifier = this

// ═══ Predictive Back Support ═══
/**
 * Holds GPU-only predictive back progress and swipe edge so back-gesture frames update inside
 * `graphicsLayer` without triggering per-frame recompositions across heavy Compose subtrees.
 */
@Stable
class PredictiveBackState {
    internal val progressAnim = Animatable(0f)
    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set

    val progress: Float
        get() = progressAnim.value

    suspend fun reset() {
        progressAnim.snapTo(0f)
    }
}

@Composable
fun rememberPredictiveBackState(): PredictiveBackState = remember { PredictiveBackState() }

/**
 * Intercepts system predictive back gestures, drives [state] 1:1 with finger progress, eases back
 * to zero on cancellation, and preserves the final shrunken progress across exit transitions so
 * dismissed overlays never flash back to 100% scale before disappearing.
 */
@Composable
fun PredictiveBackEffect(
    enabled: Boolean,
    state: PredictiveBackState,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = enabled) { progressFlow ->
        try {
            progressFlow.collect { event ->
                state.swipeEdge = event.swipeEdge
                state.progressAnim.snapTo(event.progress.coerceIn(0f, 1f))
            }
            onBack()
        } catch (cancel: CancellationException) {
            // PredictiveBackHandler's coroutine is cancelled on gesture abort, so animate back
            // on the composition scope instead of the already-cancelled handler coroutine.
            scope.launch {
                state.progressAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = PREDICTIVE_BACK_CANCEL_MS,
                        easing = M3Motion.EmphasizedDecelerate
                    )
                )
            }
            throw cancel
        }
    }
}

/**
 * Material 3 predictive back surface transform for full-screen overlays and detail screens:
 * scales smoothly to 90%, shifts away from the active swipe edge, and clips to 28dp corners.
 */
fun Modifier.predictiveBackScreen(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    if (p > PREDICTIVE_BACK_EPSILON) {
        val eased = M3Motion.StandardDecelerate.transform(p)
        val scale = 1f - eased * 0.10f
        val maxShiftPx = (size.width * 0.06f).coerceAtMost(28.dp.toPx())
        val dir = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        scaleX = scale
        scaleY = scale
        translationX = dir * eased * maxShiftPx
        shape = PREDICTIVE_BACK_CORNER_SHAPE
        clip = true
        shadowElevation = 12.dp.toPx() * eased
    }
}

/**
 * Material 3 predictive back transform for vertical flyout sheets (Now Playing & Queue):
 * pulls gently downward while scaling to 92% and rounding corners to preview the screen beneath.
 */
fun Modifier.predictiveBackSheet(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    if (p > PREDICTIVE_BACK_EPSILON) {
        val eased = M3Motion.StandardDecelerate.transform(p)
        val scale = 1f - eased * 0.08f
        val dir = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        scaleX = scale
        scaleY = scale
        translationY = eased * size.height * 0.08f
        translationX = dir * eased * size.width * 0.03f
        shape = PREDICTIVE_BACK_CORNER_SHAPE
        clip = true
        shadowElevation = 16.dp.toPx() * eased
    }
}

/**
 * Subtle predictive back feedback for in-place panels (Lyrics, Search bar, File browser).
 */
fun Modifier.predictiveBackInline(state: PredictiveBackState): Modifier = graphicsLayer {
    val p = state.progress
    if (p > PREDICTIVE_BACK_EPSILON) {
        val eased = M3Motion.StandardDecelerate.transform(p)
        val scale = 1f - eased * 0.05f
        val dir = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        scaleX = scale
        scaleY = scale
        translationX = dir * eased * 14.dp.toPx()
        alpha = 1f - eased * 0.20f
    }
}

/**
 * Transitions a nullable detail/overlay payload using a lightweight fade while retaining the last
 * non-null [target] through the brief exit fade and releasing it once settled. When [onBack] is
 * provided, automatically wires Material 3 Predictive Back gesture preview and scrim reveal.
 */
@Composable
fun <T : Any> AnimatedDetailSlot(
    target: T?,
    modifier: Modifier = Modifier,
    enter: EnterTransition = M3Motion.simpleFadeIn(),
    exit: ExitTransition = M3Motion.simpleFadeOut(),
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    content: @Composable (T) -> Unit
) {
    val transition = updateTransition(targetState = target != null, label = "AnimatedDetailSlot")
    var cachedTarget by remember { mutableStateOf(target) }
    val backState = rememberPredictiveBackState()
    if (target != null) {
        cachedTarget = target
    } else if (!transition.currentState && !transition.targetState) {
        cachedTarget = null
    }
    LaunchedEffect(target != null, transition.currentState, transition.targetState) {
        if (target != null || (!transition.currentState && !transition.targetState)) {
            backState.reset()
        }
    }
    transition.AnimatedVisibility(
        visible = { it },
        modifier = modifier,
        enter = enter,
        exit = exit
    ) {
        val active = target ?: cachedTarget
        if (active != null) {
            if (onBack != null) {
                PredictiveBackEffect(
                    enabled = target != null && backEnabled,
                    state = backState,
                    onBack = onBack
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = backState.progress
                                alpha = if (p > PREDICTIVE_BACK_EPSILON) {
                                    PREDICTIVE_BACK_SCRIM_MAX_ALPHA * (1f - p)
                                } else {
                                    0f
                                }
                            }
                            .background(Color.Black)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .predictiveBackScreen(backState)
                    ) {
                        content(active)
                    }
                }
            } else {
                content(active)
            }
        }
    }
}

/**
 * Keeps heavy LazyColumn / LazyVerticalGrid tabs mounted in composition so scroll state
 * is never lost while cross-fading via ModulateAlpha (no offscreen saveLayer texture).
 */
@Composable
fun M3KeepAliveSlot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    durationEnterMs: Int = 90,
    durationExitMs: Int = 70,
    content: @Composable BoxScope.() -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) durationEnterMs else durationExitMs,
            easing = M3Motion.Standard
        ),
        label = "KeepAliveAlpha"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(if (visible) 1f else 0f)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.ModulateAlpha
                this.alpha = alpha
                translationX = if (visible || alpha > MIN_VISIBLE_ALPHA) 0f else OFFSCREEN_TRANSLATION_X
            },
        content = content
    )
}
