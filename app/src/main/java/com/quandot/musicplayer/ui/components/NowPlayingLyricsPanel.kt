/**
 * NowPlayingLyricsPanel — YouTube Music-style inline synced and static lyrics viewer.
 *
 * @remarks
 * Replaces the album cover slot inside [NowPlayingSheet] when toggled, collecting
 * [currentPositionMsFlow] only while visible and synced so closed lyrics incur zero playback
 * recomposition overhead. Uses `interactionSource.collectIsDraggedAsState()` so programmatic
 * centering never trips manual browsing mode; once the user scrolls manually, auto-scroll stays
 * detached indefinitely until the user taps the bottom-right Sync button or selects a lyric line.
 *
 * @see LyricsHelper
 * @see NowPlayingSheet
 */
package com.quandot.musicplayer.ui.components

// External SDK / Jetpack / third-party libraries
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.LyricLine
import com.quandot.musicplayer.data.LyricsHelper
import com.quandot.musicplayer.data.SongLyrics

// Domain models & theme tokens
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.theme.M3Motion

// ═══ Constants ═══
private const val SYNC_ANTICIPATION_MS = 160L
private const val ACTIVE_LINE_CENTER_FRACTION = 0.36f
private const val SMOOTH_SCROLL_DURATION_MS = 300
private const val EXPRESSIVE_SCROLL_DURATION_MS = 460
private const val FAR_JUMP_LINE_THRESHOLD = 8
private const val LYRIC_BLUR_MAX_DP = 2.8f
private const val LYRIC_BLUR_STEPS = 12
private val LYRIC_ROW_SHAPE_EXPRESSIVE = RoundedCornerShape(14.dp)
private val LYRIC_ROW_SHAPE_COMPACT = RoundedCornerShape(12.dp)

// ═══ Lyrics Panel ═══
@Composable
fun NowPlayingLyricsPanel(
    song: Song,
    currentPositionMsFlow: StateFlow<Long>,
    isActive: Boolean,
    isCompact: Boolean,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isTabletExpanded: Boolean = false,
    expressiveMotion: Boolean = false,
    syncButtonBottomPadding: Dp = 10.dp,
    syncButtonOffsetYPxProvider: (() -> Float)? = null,
    fontSizeMode: String = "medium",
    alignmentMode: String = "left",
    autoScrollEnabled: Boolean = true,
    syncDelayMs: Int = 0,
    primaryContentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    chipContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    chipContentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAllFilesAccess by remember { mutableStateOf(LyricsHelper.hasAllFilesAccess()) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    // Re-check All Files Access when returning from Android Settings so .lrc files load immediately
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowGranted = LyricsHelper.hasAllFilesAccess()
                if (nowGranted != hasAllFilesAccess) {
                    hasAllFilesAccess = nowGranted
                    if (nowGranted) {
                        LyricsHelper.clearCache()
                        reloadNonce++
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val initialCached = remember(song.id, reloadNonce) { LyricsHelper.peekCachedLyrics(song.id) }
    var lyrics by remember(song.id, reloadNonce) { mutableStateOf(initialCached) }
    var isLoading by remember(song.id, reloadNonce) { mutableStateOf(initialCached == null) }

    LaunchedEffect(song.id, song.path, reloadNonce) {
        if (lyrics == null) {
            isLoading = true
            val loaded = withContext(Dispatchers.IO) {
                LyricsHelper.loadLyrics(context, song)
            }
            lyrics = loaded
            isLoading = false
        }
    }

    val panelState = when {
        isLoading -> 0
        lyrics == null || lyrics!!.isEmpty -> 1
        lyrics!!.isSynced -> 2
        else -> 3
    }

    AnimatedContent(
        targetState = panelState,
        transitionSpec = {
            fadeIn(tween(durationMillis = 220, easing = M3Motion.EmphasizedDecelerate)) togetherWith
                fadeOut(tween(durationMillis = 150, easing = M3Motion.EmphasizedAccelerate))
        },
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        label = "LyricsContentState"
    ) { state ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                0 -> {
                    ExpressiveLoadingIndicator(
                        modifier = Modifier.size(36.dp),
                        color = accentColor
                    )
                }
                1 -> {
                    EmptyLyricsState(
                        hasAllFilesAccess = hasAllFilesAccess,
                        isCompact = isCompact,
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                        chipContainerColor = chipContainerColor,
                        chipContentColor = chipContentColor,
                        onRequestFileAccess = {
                            runCatching {
                                context.startActivity(LyricsHelper.buildAllFilesAccessIntent(context))
                            }
                        }
                    )
                }
                2 -> {
                    val resolved = lyrics
                    if (resolved != null) {
                        SyncedLyricsList(
                            lyrics = resolved,
                            currentPositionMsFlow = currentPositionMsFlow,
                            isActive = isActive,
                            isCompact = isCompact,
                            isTabletExpanded = isTabletExpanded,
                            expressiveMotion = expressiveMotion,
                            syncButtonBottomPadding = syncButtonBottomPadding,
                            syncButtonOffsetYPxProvider = syncButtonOffsetYPxProvider,
                            fontSizeMode = fontSizeMode,
                            alignmentMode = alignmentMode,
                            autoScrollEnabled = autoScrollEnabled,
                            syncDelayMs = syncDelayMs,
                            primaryContentColor = primaryContentColor,
                            chipContainerColor = chipContainerColor,
                            chipContentColor = chipContentColor,
                            onSeekToMs = onSeekToMs
                        )
                    }
                }
                else -> {
                    val resolved = lyrics
                    if (resolved != null) {
                        StaticLyricsList(
                            lyrics = resolved,
                            isCompact = isCompact,
                            isTabletExpanded = isTabletExpanded,
                            fontSizeMode = fontSizeMode,
                            alignmentMode = alignmentMode,
                            primaryContentColor = primaryContentColor
                        )
                    }
                }
            }
        }
    }
}

// ═══ Synced (Dynamic) Lyrics View ═══
@Composable
private fun SyncedLyricsList(
    lyrics: SongLyrics,
    currentPositionMsFlow: StateFlow<Long>,
    isActive: Boolean,
    isCompact: Boolean,
    isTabletExpanded: Boolean,
    expressiveMotion: Boolean,
    syncButtonBottomPadding: Dp,
    syncButtonOffsetYPxProvider: (() -> Float)?,
    fontSizeMode: String,
    alignmentMode: String,
    autoScrollEnabled: Boolean,
    syncDelayMs: Int,
    primaryContentColor: Color,
    chipContainerColor: Color,
    chipContentColor: Color,
    onSeekToMs: (Long) -> Unit
) {
    val effectiveAnticipationMs = SYNC_ANTICIPATION_MS - syncDelayMs.toLong()
    val activeIndex by remember(isActive, currentPositionMsFlow, lyrics.lines, effectiveAnticipationMs) {
        val posFlow = if (isActive) currentPositionMsFlow else MutableStateFlow(currentPositionMsFlow.value)
        posFlow
            .map { pos -> findActiveLyricIndex(lyrics.lines, pos + effectiveAnticipationMs) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = findActiveLyricIndex(lyrics.lines, currentPositionMsFlow.value + effectiveAnticipationMs)
    )

    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var isManualBrowsing by remember(lyrics.songId) { mutableStateOf(false) }
    var syncRequestNonce by remember(lyrics.songId) { mutableIntStateOf(0) }
    var handledSyncNonce by remember(lyrics.songId) { mutableIntStateOf(0) }
    var hasInitialSnapped by remember(lyrics.songId) { mutableStateOf(false) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // Hoist density-derived metrics and quantized BlurEffect lookup table so rows allocate zero
    // BlurEffect objects during active-line transitions.
    val activeLiftPx = remember(density) { with(density) { (-2).dp.toPx() } }
    val blurMaxPx = remember(density) { with(density) { LYRIC_BLUR_MAX_DP.dp.toPx() } }
    val blurDist1Px = remember(density) { with(density) { 1.2.dp.toPx() } }
    val blurDist2Px = remember(density) { with(density) { 2.1.dp.toPx() } }
    val blurEffectsTable: List<BlurEffect?> = remember(density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            List(LYRIC_BLUR_STEPS + 1) { step ->
                if (step == 0) {
                    null
                } else {
                    val radiusPx = with(density) {
                        (LYRIC_BLUR_MAX_DP * (step.toFloat() / LYRIC_BLUR_STEPS)).dp.toPx()
                    }.coerceAtLeast(0.5f)
                    BlurEffect(radiusPx, radiusPx, TileMode.Decal)
                }
            }
        } else {
            emptyList()
        }
    }

    // Detach auto-scroll permanently on real user drag until the user explicitly taps Sync or a line
    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            isManualBrowsing = true
        }
    }

    val isActiveLineVisible by remember(activeIndex) {
        derivedStateOf {
            if (activeIndex < 0) return@derivedStateOf true
            listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
        }
    }

    val showSyncButton = lyrics.lines.isNotEmpty() && (
        isManualBrowsing || (!autoScrollEnabled && activeIndex >= 0 && !isActiveLineVisible)
    )

    // Measure viewport height via BoxWithConstraints on frame 0 so top/bottom centering padding
    // and initial active-line snap are exact immediately without an onSizeChanged backwards write.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeightPx = constraints.maxHeight
        val viewportHeightDp = maxHeight
        val topPad = if (viewportHeightPx > 0) (viewportHeightDp * 0.28f).coerceIn(28.dp, 130.dp) else 36.dp
        val minBottomPad = if (isCompact) 72.dp else 140.dp
        val bottomPad = if (viewportHeightPx > 0) (viewportHeightDp * 0.66f).coerceIn(minBottomPad, 420.dp) else if (isCompact) 110.dp else 200.dp

        // Smoothly lock the active lyric line's vertical center at the upper-center focal anchor
        LaunchedEffect(
            activeIndex,
            isActive,
            isManualBrowsing,
            syncRequestNonce,
            autoScrollEnabled,
            viewportHeightPx,
            fontSizeMode,
            lyrics.songId
        ) {
            val isExplicitSync = syncRequestNonce != handledSyncNonce
            if (!isActive || viewportHeightPx <= 0 || lyrics.lines.isEmpty()) {
                return@LaunchedEffect
            }
            if (isExplicitSync) {
                handledSyncNonce = syncRequestNonce
            } else if (!autoScrollEnabled || isManualBrowsing) {
                return@LaunchedEffect
            }

            val targetIdx = activeIndex.coerceIn(0, lyrics.lines.lastIndex)
            val layoutInfo = listState.layoutInfo
            val beforePadPx = layoutInfo.beforeContentPadding
            val desiredVisualCenterPx = viewportHeightPx * ACTIVE_LINE_CENTER_FRACTION
            val estimatedHalfRowPx = with(density) { (if (isCompact) 18.dp else 24.dp).toPx() }
            // In LazyColumn, scrollToItem(index, offset) places visualTop = beforePadPx - offset
            val desiredScrollOffset = (beforePadPx - (desiredVisualCenterPx - estimatedHalfRowPx)).toInt()

            if (!hasInitialSnapped) {
                hasInitialSnapped = true
                listState.scrollToItem(index = targetIdx, scrollOffset = desiredScrollOffset)
                val snappedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }
                if (snappedItem != null) {
                    val actualCenter = snappedItem.offset + listState.layoutInfo.beforeContentPadding + snappedItem.size / 2f
                    val adjust = actualCenter - desiredVisualCenterPx
                    if (abs(adjust) > 2f) {
                        listState.scrollBy(adjust)
                    }
                }
                return@LaunchedEffect
            }

            val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }
            if (visibleItem != null) {
                val actualVisualCenterPx = visibleItem.offset + beforePadPx + visibleItem.size / 2f
                val delta = actualVisualCenterPx - desiredVisualCenterPx
                if (abs(delta) > 2f) {
                    listState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(
                            durationMillis = if (expressiveMotion) EXPRESSIVE_SCROLL_DURATION_MS else SMOOTH_SCROLL_DURATION_MS,
                            easing = if (expressiveMotion) M3Motion.EmphasizedDecelerate else M3Motion.StandardDecelerate
                        )
                    )
                }
            } else {
                val firstVisible = listState.firstVisibleItemIndex
                if (abs(targetIdx - firstVisible) <= FAR_JUMP_LINE_THRESHOLD) {
                    listState.animateScrollToItem(index = targetIdx, scrollOffset = desiredScrollOffset)
                } else {
                    listState.scrollToItem(index = targetIdx, scrollOffset = desiredScrollOffset)
                }
                val landedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }
                if (landedItem != null) {
                    val actualCenter = landedItem.offset + listState.layoutInfo.beforeContentPadding + landedItem.size / 2f
                    val adjust = actualCenter - desiredVisualCenterPx
                    if (abs(adjust) > 4f) {
                        listState.animateScrollBy(
                            value = adjust,
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = M3Motion.StandardDecelerate
                            )
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingVerticalEdges(),
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
            verticalArrangement = Arrangement.spacedBy(
                when {
                    isCompact -> 12.dp
                    isTabletExpanded -> 22.dp
                    else -> 18.dp
                }
            )
        ) {
            itemsIndexed(
                items = lyrics.lines,
                key = { idx, line -> "${lyrics.songId}_${line.timeMs ?: -1L}_$idx" },
                contentType = { _, _ -> "synced_lyric_line" }
            ) { idx, line ->
                val isCurrent = idx == activeIndex
                val isPast = activeIndex >= 0 && idx < activeIndex
                val distanceFromActive = if (activeIndex >= 0) abs(idx - activeIndex).coerceAtMost(3) else 0
                SyncedLyricRow(
                    line = line,
                    isCurrent = isCurrent,
                    isPast = isPast,
                    distanceFromActive = distanceFromActive,
                    isManualBrowsing = isManualBrowsing,
                    isCompact = isCompact,
                    isTabletExpanded = isTabletExpanded,
                    expressiveMotion = expressiveMotion,
                    activeLiftPx = activeLiftPx,
                    blurDist1Px = blurDist1Px,
                    blurDist2Px = blurDist2Px,
                    blurMaxPx = blurMaxPx,
                    blurEffectsTable = blurEffectsTable,
                    fontSizeMode = fontSizeMode,
                    alignmentMode = alignmentMode,
                    primaryContentColor = primaryContentColor,
                    onClick = {
                        val targetMs = line.timeMs ?: return@SyncedLyricRow
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isManualBrowsing = false
                        syncRequestNonce++
                        onSeekToMs(targetMs)
                    }
                )
            }
        }

        // Bottom-right Sync pill shown when user scrolls manually away from live lyric tracking
        AnimatedVisibility(
            visible = showSyncButton,
            enter = if (expressiveMotion) {
                fadeIn(tween(200, easing = M3Motion.EmphasizedDecelerate)) +
                    slideInVertically(tween(240, easing = M3Motion.EmphasizedDecelerate)) { it / 2 } +
                    scaleIn(tween(220, easing = M3Motion.EmphasizedDecelerate), initialScale = 0.90f)
            } else {
                M3Motion.simpleFadeIn(110)
            },
            exit = if (expressiveMotion) {
                fadeOut(tween(150, easing = M3Motion.EmphasizedAccelerate)) +
                    slideOutVertically(tween(180, easing = M3Motion.EmphasizedAccelerate)) { it / 2 } +
                    scaleOut(tween(160, easing = M3Motion.EmphasizedAccelerate), targetScale = 0.92f)
            } else {
                M3Motion.simpleFadeOut(85)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = syncButtonBottomPadding)
                .then(
                    if (syncButtonOffsetYPxProvider != null) {
                        Modifier.offset { IntOffset(0, syncButtonOffsetYPxProvider().roundToInt()) }
                    } else {
                        Modifier
                    }
                )
        ) {
            Surface(
                onClick = {
                    isManualBrowsing = false
                    syncRequestNonce++
                },
                shape = RoundedCornerShape(50),
                color = chipContainerColor,
                contentColor = chipContentColor,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, primaryContentColor.copy(alpha = 0.16f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.lyrics_sync_action),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricRow(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    isManualBrowsing: Boolean,
    isCompact: Boolean,
    isTabletExpanded: Boolean,
    expressiveMotion: Boolean,
    activeLiftPx: Float,
    blurDist1Px: Float,
    blurDist2Px: Float,
    blurMaxPx: Float,
    blurEffectsTable: List<BlurEffect?>,
    fontSizeMode: String,
    alignmentMode: String,
    primaryContentColor: Color,
    onClick: () -> Unit
) {
    val isCentered = alignmentMode == "center"
    val baseSizeSp = when (fontSizeMode) {
        "small" -> when {
            isCompact -> 16f
            isTabletExpanded -> 23f
            else -> 19f
        }
        "large" -> when {
            isCompact -> 21f
            isTabletExpanded -> 31f
            else -> 26f
        }
        else -> when {
            isCompact -> 18f
            isTabletExpanded -> 27f
            else -> 22f
        }
    }
    val lineHeightSp = baseSizeSp * 1.35f
    val subSizeSp = (baseSizeSp * 0.68f).coerceAtLeast(12f)
    val subLineHeightSp = subSizeSp * 1.38f

    val interaction = remember { MutableInteractionSource() }

    if (expressiveMotion) {
        // Apple Music TV–style GPU-accelerated spring scale, depth opacity, lift, and quantized API 31+ depth blur
        val targetScale = when {
            isCurrent -> 1.032f
            isManualBrowsing -> 0.99f
            distanceFromActive == 1 -> 0.982f
            else -> 0.962f
        }
        val targetAlpha = when {
            isCurrent -> 1f
            isManualBrowsing -> 0.78f
            isPast -> if (distanceFromActive >= 3) 0.24f else 0.34f
            distanceFromActive == 1 -> 0.56f
            distanceFromActive == 2 -> 0.42f
            else -> 0.30f
        }
        val targetLiftPx = if (isCurrent) activeLiftPx else 0f
        val targetBlurPx = if (blurEffectsTable.isNotEmpty() && !isCurrent && !isManualBrowsing) {
            when {
                distanceFromActive <= 1 -> blurDist1Px
                distanceFromActive == 2 -> blurDist2Px
                else -> blurMaxPx
            }
        } else {
            0f
        }

        val animatedScaleState = animateFloatAsState(
            targetValue = targetScale,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "LyricRowScale"
        )
        val animatedAlphaState = animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(durationMillis = 320, easing = M3Motion.EmphasizedDecelerate),
            label = "LyricRowAlpha"
        )
        val animatedLiftPxState = animateFloatAsState(
            targetValue = targetLiftPx,
            animationSpec = spring(
                dampingRatio = 0.80f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "LyricRowLift"
        )
        val animatedBlurPxState = animateFloatAsState(
            targetValue = targetBlurPx,
            animationSpec = tween(durationMillis = 300, easing = M3Motion.Standard),
            label = "LyricRowBlur"
        )
        val pivotX = if (isCentered) 0.5f else 0f
        val activeGlowShadow = remember(isCurrent, primaryContentColor) {
            if (isCurrent) {
                Shadow(
                    color = primaryContentColor.copy(alpha = 0.24f),
                    offset = Offset.Zero,
                    blurRadius = 16f
                )
            } else {
                null
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val s = animatedScaleState.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(pivotX, 0.5f)
                    alpha = animatedAlphaState.value
                    translationY = animatedLiftPxState.value
                    val blurPx = animatedBlurPxState.value
                    renderEffect = if (blurEffectsTable.isNotEmpty() && blurPx > 0.35f && blurMaxPx > 0f) {
                        val bucket = ((blurPx / blurMaxPx) * LYRIC_BLUR_STEPS)
                            .toInt()
                            .coerceIn(1, LYRIC_BLUR_STEPS)
                        blurEffectsTable[bucket]
                    } else {
                        null
                    }
                }
                .clip(LYRIC_ROW_SHAPE_EXPRESSIVE)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = if (isCentered) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = baseSizeSp.sp,
                    lineHeight = lineHeightSp.sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                    shadow = activeGlowShadow
                ),
                textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                color = primaryContentColor,
                modifier = Modifier.fillMaxWidth()
            )
            if (!line.translation.isNullOrBlank()) {
                Text(
                    text = line.translation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = subSizeSp.sp,
                        lineHeight = subLineHeightSp.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                    color = primaryContentColor.copy(alpha = if (isCurrent) 0.80f else 0.74f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        return
    }

    // Direct color selection avoids allocating per-row color/scale animators during scrolling on compact portrait
    val textColor = when {
        isCurrent -> primaryContentColor
        isPast -> primaryContentColor.copy(alpha = 0.36f)
        else -> primaryContentColor.copy(alpha = 0.48f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LYRIC_ROW_SHAPE_COMPACT)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = if (isCentered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = baseSizeSp.sp,
                lineHeight = lineHeightSp.sp,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold
            ),
            textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
            color = textColor,
            modifier = Modifier.fillMaxWidth()
        )
        if (!line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = subSizeSp.sp,
                    lineHeight = subLineHeightSp.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium
                ),
                textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                color = textColor.copy(alpha = if (isCurrent) 0.78f else 0.72f),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══ Static (Plain) Lyrics View ═══
@Composable
private fun StaticLyricsList(
    lyrics: SongLyrics,
    isCompact: Boolean,
    isTabletExpanded: Boolean,
    fontSizeMode: String,
    alignmentMode: String,
    primaryContentColor: Color
) {
    val isCentered = alignmentMode == "center"
    val baseSizeSp = when (fontSizeMode) {
        "small" -> when {
            isCompact -> 14f
            isTabletExpanded -> 18f
            else -> 16f
        }
        "large" -> when {
            isCompact -> 19f
            isTabletExpanded -> 25f
            else -> 22f
        }
        else -> when {
            isCompact -> 16f
            isTabletExpanded -> 21f
            else -> 19f
        }
    }
    val lineHeightSp = baseSizeSp * 1.45f
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .fadingVerticalEdges(),
        contentPadding = PaddingValues(
            top = if (isCompact) 14.dp else 24.dp,
            bottom = if (isCompact) 36.dp else 64.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 11.dp)
    ) {
        itemsIndexed(
            items = lyrics.lines,
            key = { idx, _ -> "${lyrics.songId}_plain_$idx" },
            contentType = { _, line -> if (line.text.isEmpty()) "plain_lyric_spacer" else "plain_lyric_line" }
        ) { _, line ->
            if (line.text.isEmpty()) {
                Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            } else {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = baseSizeSp.sp,
                        lineHeight = lineHeightSp.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    textAlign = if (isCentered) TextAlign.Center else TextAlign.Start,
                    color = primaryContentColor.copy(alpha = 0.88f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}

// ═══ Empty State ═══
@Composable
private fun EmptyLyricsState(
    hasAllFilesAccess: Boolean,
    isCompact: Boolean,
    primaryContentColor: Color,
    secondaryContentColor: Color,
    chipContainerColor: Color,
    chipContentColor: Color,
    onRequestFileAccess: () -> Unit
) {
    val needsAllFilesPrompt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 10.dp else 14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = chipContainerColor,
            modifier = Modifier.size(if (isCompact) 46.dp else 56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = secondaryContentColor,
                    modifier = Modifier.size(if (isCompact) 24.dp else 28.dp)
                )
            }
        }

        Text(
            text = stringResource(R.string.lyrics_empty_title),
            style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = primaryContentColor,
            textAlign = TextAlign.Center
        )

        if (needsAllFilesPrompt) {
            FilledTonalButton(
                onClick = onRequestFileAccess,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = chipContainerColor,
                    contentColor = chipContentColor
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lyrics_grant_files_action),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ═══ Helpers ═══
private fun findActiveLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.lastIndex
    var best = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val ts = lines[mid].timeMs ?: Long.MAX_VALUE
        if (ts <= positionMs) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun Modifier.fadingVerticalEdges(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache {
        val fadePx = minOf(size.height * 0.14f, 44.dp.toPx())
        val hasFade = fadePx > 0f && size.height > fadePx * 2f
        val topBrush = if (hasFade) {
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = fadePx
            )
        } else {
            null
        }
        val bottomBrush = if (hasFade) {
            Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fadePx,
                endY = size.height
            )
        } else {
            null
        }
        onDrawWithContent {
            drawContent()
            if (topBrush != null && bottomBrush != null) {
                drawRect(
                    brush = topBrush,
                    topLeft = Offset.Zero,
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn
                )
                drawRect(
                    brush = bottomBrush,
                    topLeft = Offset(0f, size.height - fadePx),
                    size = Size(size.width, fadePx),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }
