/**
 * AppScrollbars — Lightweight Material 3 scrollbar and thumb-and-teardrop fast scroller.
 *
 * @remarks
 * `Material3SystemScrollbar` draws via pure Compose `Canvas` to stay allocation-free and avoid
 * view-interop overhead while clipping cleanly above `bottomPadding` (MiniPlayer clearance).
 * `LibraryFastScroller` renders a sleek trailing-edge pill thumb with an optional Material You
 * right-pointing teardrop popup bubble during dragging, using draw-phase `graphicsLayer`
 * translation and conflated scroll dispatch for smooth 120Hz/144Hz scrubbing from 0% to 100%.
 *
 * @see LocalBottomContentPadding
 */
package com.quandot.musicplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

import com.quandot.musicplayer.ui.theme.LocalBottomContentPadding
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.paneHorizontalInsets

// ═══ Constants ═══
private const val SCROLLBAR_HIDE_DELAY_MS = 1000L
private const val FAST_SCROLLER_HIDE_DELAY_MS = 1600L
private const val SCROLLBAR_MIN_PROPORTION = 0.05f
private const val HEADER_SAMPLE_LIMIT = 6
private const val EDGE_START_THRESHOLD = 0.002f
private const val EDGE_END_THRESHOLD = 0.998f

private val THUMB_HEIGHT = 52.dp
private val THUMB_WIDTH_IDLE = 6.dp
private val THUMB_WIDTH_DRAGGING = 8.dp
private val THUMB_TRAILING_PADDING = 4.dp
private val TOUCH_STRIP_WIDTH = 24.dp
private val BUBBLE_SIZE = 62.dp
private val BUBBLE_END_OFFSET = 28.dp
private val BUBBLE_SAFE_PAD_H = 20.dp
private val BUBBLE_SAFE_PAD_V = 12.dp

/**
 * Square rotated -45° so the softly rounded `bottomEnd` corner points horizontally right (3 o'clock)
 * directly toward the fast-scroller thumb, matching Material You teardrop indicators.
 */
private val TeardropBubbleShape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomStartPercent = 50,
    bottomEndPercent = 12
)

// ═══ System Scrollbar ═══
/**
 * Lightweight pure-Compose Material 3 scrollbar that reveals on scroll, terminates above the
 * MiniPlayer via [bottomPadding], and auto-fades after 1 second of inactivity.
 */
@Composable
fun Material3SystemScrollbar(
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    gridState: LazyGridState? = null,
    bottomPadding: Dp = LocalBottomContentPadding.current
) {
    val totalItems by remember(listState, gridState) {
        derivedStateOf {
            listState?.layoutInfo?.totalItemsCount
                ?: gridState?.layoutInfo?.totalItemsCount
                ?: 0
        }
    }

    val visibleItems by remember(listState, gridState) {
        derivedStateOf {
            listState?.layoutInfo?.visibleItemsInfo?.size
                ?: gridState?.layoutInfo?.visibleItemsInfo?.size
                ?: 0
        }
    }

    if (totalItems <= 0 || visibleItems >= totalItems) return

    val firstVisibleIndexState = remember(listState, gridState) {
        derivedStateOf {
            listState?.firstVisibleItemIndex
                ?: gridState?.firstVisibleItemIndex
                ?: 0
        }
    }

    val isScrollInProgress by remember(listState, gridState) {
        derivedStateOf {
            listState?.isScrollInProgress == true || gridState?.isScrollInProgress == true
        }
    }

    var isScrollbarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress) {
            isScrollbarVisible = true
        } else {
            delay(SCROLLBAR_HIDE_DELAY_MS)
            isScrollbarVisible = false
        }
    }

    val scrollbarAlphaState = animateFloatAsState(
        targetValue = if (isScrollbarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 100, easing = M3Motion.Standard),
        label = "ScrollbarAlpha"
    )

    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val density = LocalDensity.current
    val minThumbPx = remember(density) { with(density) { 40.dp.toPx() } }
    val cornerRadiusPx = remember(density) { with(density) { 2.dp.toPx() } }

    Canvas(
        modifier = modifier
            .windowInsetsPadding(paneHorizontalInsets().only(WindowInsetsSides.End))
            .padding(bottom = bottomPadding)
            .fillMaxHeight()
            .width(8.dp)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .graphicsLayer { alpha = scrollbarAlphaState.value }
    ) {
        if (scrollbarAlphaState.value <= 0.001f) return@Canvas
        val maxHeightPx = size.height
        val proportion = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(SCROLLBAR_MIN_PROPORTION, 1f)
        val thumbHeightPx = (maxHeightPx * proportion).coerceIn(minThumbPx, maxHeightPx)
        val scrollableRangePx = (maxHeightPx - thumbHeightPx).coerceAtLeast(1f)
        val maxStartIndex = (totalItems - visibleItems).coerceAtLeast(1)
        val ratio = (firstVisibleIndexState.value.toFloat() / maxStartIndex.toFloat()).coerceIn(0f, 1f)
        val thumbOffsetY = ratio * scrollableRangePx

        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbOffsetY),
            size = Size(size.width, thumbHeightPx),
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
        )
    }
}

// ═══ Thumb & Teardrop Fast Scroller ═══
/**
 * Trailing-edge fast scroller with a pill thumb and an optional Material You teardrop popup bubble.
 *
 * @remarks
 * - Spans the full height of its parent container above [bottomPadding] (MiniPlayer clearance).
 * - Guarantees full 0%..100% scrolling from the very first item to the very bottom including
 *   `afterContentPadding`, for both homogeneous lists/grids and heterogeneous detail screens.
 * - Shows the teardrop indicator bubble only when [getBubbleText] is provided and the user is
 *   actively dragging the thumb.
 */
@Composable
fun LibraryFastScroller(
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    gridState: LazyGridState? = null,
    bottomPadding: Dp = LocalBottomContentPadding.current,
    getBubbleText: ((index: Int) -> String)? = null
) {
    val totalItems by remember(listState, gridState) {
        derivedStateOf {
            listState?.layoutInfo?.totalItemsCount
                ?: gridState?.layoutInfo?.totalItemsCount
                ?: 0
        }
    }

    val isScrollable by remember(listState, gridState) {
        derivedStateOf {
            listState?.canScrollForward == true ||
                listState?.canScrollBackward == true ||
                gridState?.canScrollForward == true ||
                gridState?.canScrollBackward == true
        }
    }

    if (totalItems <= 1 || !isScrollable) return

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thumbHeightPx = remember(density) { with(density) { THUMB_HEIGHT.toPx() } }
    val bubbleSizePx = remember(density) { with(density) { BUBBLE_SIZE.toPx() } }
    val bubbleSafePadVPx = remember(density) { with(density) { BUBBLE_SAFE_PAD_V.toPx() } }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    val observedHeaderSizes = remember(listState, gridState, totalItems) {
        HashMap<Int, Int>(HEADER_SAMPLE_LIMIT)
    }
    val cachedRegularItemSpan = remember(listState, gridState, totalItems) {
        mutableFloatStateOf(0f)
    }

    val isScrollInProgress by remember(listState, gridState) {
        derivedStateOf {
            listState?.isScrollInProgress == true || gridState?.isScrollInProgress == true
        }
    }

    val passiveFractionState = remember(listState, gridState) {
        derivedStateOf {
            computePassiveFraction(listState, gridState)
        }
    }

    var isThumbVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isScrollInProgress, isDragging) {
        if (isScrollInProgress || isDragging) {
            isThumbVisible = true
        } else {
            delay(FAST_SCROLLER_HIDE_DELAY_MS)
            isThumbVisible = false
        }
    }

    val thumbAlphaState = animateFloatAsState(
        targetValue = if (isThumbVisible || isDragging) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isThumbVisible || isDragging) 120 else 280,
            easing = M3Motion.Standard
        ),
        label = "FastScrollerThumbAlpha"
    )

    val thumbWidthState = animateDpAsState(
        targetValue = if (isDragging) THUMB_WIDTH_DRAGGING else THUMB_WIDTH_IDLE,
        animationSpec = tween(durationMillis = 120, easing = M3Motion.Standard),
        label = "FastScrollerThumbWidth"
    )

    val thumbColorState = animateColorAsState(
        targetValue = if (isDragging) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
        },
        animationSpec = tween(durationMillis = 120, easing = M3Motion.Standard),
        label = "FastScrollerThumbColor"
    )

    // Conflated channel keeps 120Hz/144Hz scrubbing allocation-free without cancelling scroll jobs mid-suspend
    val scrollChannel = remember(listState, gridState) {
        Channel<Float>(capacity = Channel.CONFLATED)
    }
    LaunchedEffect(listState, gridState, scrollChannel) {
        for (targetFraction in scrollChannel) {
            scrollToFraction(
                fraction = targetFraction,
                listState = listState,
                gridState = gridState,
                observedHeaderSizes = observedHeaderSizes,
                cachedRegularItemSpan = cachedRegularItemSpan
            )
        }
    }

    val currentGetBubbleText by rememberUpdatedState(getBubbleText)
    val currentTotalItems by rememberUpdatedState(totalItems)
    val hasBubbleProvider = getBubbleText != null

    val activeBubbleText by remember(hasBubbleProvider) {
        derivedStateOf {
            val provider = currentGetBubbleText ?: return@derivedStateOf ""
            if (!isDragging || currentTotalItems <= 0) return@derivedStateOf ""
            val idx = (dragFraction * (currentTotalItems - 1).coerceAtLeast(0))
                .roundToInt()
                .coerceIn(0, currentTotalItems - 1)
            provider(idx).trim()
        }
    }

    var lastHapticLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isDragging, activeBubbleText) {
        if (!isDragging) {
            lastHapticLabel = null
        } else if (currentGetBubbleText == null) {
            if (lastHapticLabel == null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastHapticLabel = ""
            }
        } else if (activeBubbleText.isNotEmpty() && activeBubbleText != lastHapticLabel) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastHapticLabel = activeBubbleText
        }
    }

    Box(
        modifier = modifier
            .windowInsetsPadding(paneHorizontalInsets().only(WindowInsetsSides.End))
            .padding(top = 4.dp, bottom = bottomPadding + 4.dp)
            .fillMaxHeight()
            .width(BUBBLE_SIZE + BUBBLE_END_OFFSET + BUBBLE_SAFE_PAD_H + 16.dp)
            .onSizeChanged { size ->
                trackHeightPx = size.height.toFloat().coerceAtLeast(1f)
            }
    ) {
        // Right-pointing Material You teardrop popup indicator (isolated so label updates never recompose the track)
        if (hasBubbleProvider) {
            FastScrollerTeardropBubble(
                isDragging = isDragging,
                activeBubbleText = activeBubbleText,
                dragFractionProvider = { dragFraction },
                trackHeightPxProvider = { trackHeightPx },
                thumbHeightPx = thumbHeightPx,
                bubbleSizePx = bubbleSizePx,
                bubbleSafePadVPx = bubbleSafePadVPx,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = (BUBBLE_END_OFFSET - BUBBLE_SAFE_PAD_H).coerceAtLeast(0.dp))
            )
        }

        // Trailing-edge touch hitbox + vertical pill thumb
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(TOUCH_STRIP_WIDTH)
                .fillMaxHeight()
                .pointerInput(listState, gridState, scrollChannel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        isThumbVisible = true

                        fun dispatchTouchY(y: Float) {
                            val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
                            val fraction = ((y - thumbHeightPx / 2f) / usableTrackPx).coerceIn(0f, 1f)
                            dragFraction = fraction
                            scrollChannel.trySend(fraction)
                        }

                        try {
                            dispatchTouchY(down.position.y)
                            down.consume()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                if (!change.pressed) break
                                dispatchTouchY(change.position.y)
                                change.consume()
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = THUMB_TRAILING_PADDING)
                    .width(THUMB_WIDTH_DRAGGING)
                    .height(THUMB_HEIGHT)
                    .graphicsLayer {
                        alpha = thumbAlphaState.value
                        val fraction = if (isDragging) dragFraction else passiveFractionState.value
                        val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
                        translationY = (fraction.coerceIn(0f, 1f) * usableTrackPx)
                    }
                    .drawBehind {
                        val wPx = thumbWidthState.value.toPx().coerceIn(1f, size.width)
                        val radius = wPx / 2f
                        drawRoundRect(
                            color = thumbColorState.value,
                            topLeft = Offset(size.width - wPx, 0f),
                            size = Size(wPx, size.height),
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    }
            )
        }
    }
}

@Composable
private fun FastScrollerTeardropBubble(
    isDragging: Boolean,
    activeBubbleText: String,
    dragFractionProvider: () -> Float,
    trackHeightPxProvider: () -> Float,
    thumbHeightPx: Float,
    bubbleSizePx: Float,
    bubbleSafePadVPx: Float,
    modifier: Modifier = Modifier
) {
    val lastNonEmptyLabelRef = remember { arrayOf("") }
    val displayLabel = activeBubbleText.ifEmpty { lastNonEmptyLabelRef[0] }
    SideEffect {
        if (activeBubbleText.isNotEmpty()) {
            lastNonEmptyLabelRef[0] = activeBubbleText
        }
    }

    AnimatedVisibility(
        visible = isDragging && activeBubbleText.isNotEmpty(),
        enter = M3Motion.simpleFadeIn(90),
        exit = M3Motion.simpleFadeOut(110),
        modifier = modifier.graphicsLayer {
            val trackHeightPx = trackHeightPxProvider()
            val usableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbCenterY = (dragFractionProvider() * usableTrackPx) + (thumbHeightPx / 2f)
            val visualTop = (thumbCenterY - bubbleSizePx / 2f)
                .coerceIn(0f, (trackHeightPx - bubbleSizePx).coerceAtLeast(0f))
            translationY = visualTop - bubbleSafePadVPx
        }
    ) {
        // Extra safe padding inside AnimatedVisibility prevents its alpha layer from clipping
        // the -45° rotated teardrop tip and drop shadow beyond BUBBLE_SIZE bounds.
        Box(
            modifier = Modifier
                .padding(horizontal = BUBBLE_SAFE_PAD_H, vertical = BUBBLE_SAFE_PAD_V)
                .size(BUBBLE_SIZE),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = -45f }
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = TeardropBubbleShape
                    )
            )
            Text(
                text = displayLabel,
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = if (displayLabel.length >= 4) 17.sp else 26.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ═══ Scroll Position Helpers ═══
private fun computePassiveFraction(
    listState: LazyListState?,
    gridState: LazyGridState?
): Float {
    if (listState != null) {
        if (!listState.canScrollBackward) return 0f
        if (!listState.canScrollForward) return 1f
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount <= 1) return 0f

        val spacing = info.mainAxisItemSpacing
        val first = visible.first()
        val last = visible.last()
        val firstSpan = (first.size + spacing).coerceAtLeast(1).toFloat()
        val lastSpan = (last.size + spacing).coerceAtLeast(1).toFloat()

        val itemsAbove = first.index + ((-first.offset).toFloat() / firstSpan).coerceIn(0f, 1f)
        val contentBottomEdge = info.viewportEndOffset - info.afterContentPadding
        val lastOverflow = ((last.offset + last.size) - contentBottomEdge).toFloat().coerceAtLeast(0f)
        val itemsBelow = (info.totalItemsCount - 1 - last.index).coerceAtLeast(0) +
            (lastOverflow / lastSpan).coerceAtLeast(0f)

        val totalScrollable = (itemsAbove + itemsBelow).coerceAtLeast(0.001f)
        return (itemsAbove / totalScrollable).coerceIn(0f, 1f)
    }

    if (gridState != null) {
        if (!gridState.canScrollBackward) return 0f
        if (!gridState.canScrollForward) return 1f
        val info = gridState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount <= 1) return 0f

        val spacing = info.mainAxisItemSpacing
        val columns = ((visible.maxOfOrNull { it.column } ?: 0) + 1).coerceAtLeast(1)
        val totalRows = ((info.totalItemsCount + columns - 1) / columns).coerceAtLeast(1)

        val first = visible.first()
        val last = visible.last()
        val firstRow = if (first.row >= 0) first.row else first.index / columns
        val lastRow = if (last.row >= 0) last.row else last.index / columns
        val firstSpan = (first.size.height + spacing).coerceAtLeast(1).toFloat()
        val lastSpan = (last.size.height + spacing).coerceAtLeast(1).toFloat()

        val rowsAbove = firstRow + ((-first.offset.y).toFloat() / firstSpan).coerceIn(0f, 1f)
        val contentBottomEdge = info.viewportEndOffset - info.afterContentPadding
        val lastOverflow = ((last.offset.y + last.size.height) - contentBottomEdge).toFloat().coerceAtLeast(0f)
        val rowsBelow = (totalRows - 1 - lastRow).coerceAtLeast(0) +
            (lastOverflow / lastSpan).coerceAtLeast(0f)

        val totalScrollable = (rowsAbove + rowsBelow).coerceAtLeast(0.001f)
        return (rowsAbove / totalScrollable).coerceIn(0f, 1f)
    }

    return 0f
}

private suspend fun scrollToFraction(
    fraction: Float,
    listState: LazyListState?,
    gridState: LazyGridState?,
    observedHeaderSizes: HashMap<Int, Int>,
    cachedRegularItemSpan: MutableFloatState
) {
    val clamped = fraction.coerceIn(0f, 1f)

    if (listState != null) {
        val info = listState.layoutInfo
        val totalItems = info.totalItemsCount
        if (totalItems <= 0) return

        if (clamped <= EDGE_START_THRESHOLD) {
            listState.scrollToItem(0, 0)
            return
        }
        if (clamped >= EDGE_END_THRESHOLD) {
            listState.scrollToItem(totalItems - 1, 0)
            val pushPx = (info.viewportSize.height.coerceAtLeast(1) + info.afterContentPadding).toFloat() * 2f
            listState.scrollBy(pushPx)
            return
        }

        val visible = info.visibleItemsInfo
        for (item in visible) {
            if (item.index < HEADER_SAMPLE_LIMIT && item.size > 0) {
                observedHeaderSizes[item.index] = item.size
            }
        }

        val sampleSizes = visible
            .filter { it.size > 0 && (totalItems <= HEADER_SAMPLE_LIMIT || it.index >= 4) }
            .ifEmpty { visible.filter { it.size > 0 } }
            .map { it.size }
            .sorted()
        val medianItemSize = sampleSizes.getOrNull(sampleSizes.size / 2)?.toFloat()
            ?: cachedRegularItemSpan.floatValue.takeIf { it > 0f }
            ?: 1f
        if (medianItemSize > 0f) {
            cachedRegularItemSpan.floatValue = medianItemSize
        }

        val spacing = info.mainAxisItemSpacing
        val regularSpan = (medianItemSize + spacing).coerceAtLeast(1f)
        val headerCount = minOf(HEADER_SAMPLE_LIMIT, totalItems)
        var headerBlockPx = 0f
        for (i in 0 until headerCount) {
            val h = observedHeaderSizes[i]?.toFloat() ?: medianItemSize
            headerBlockPx += h + spacing
        }
        val remainingItems = (totalItems - headerCount).coerceAtLeast(0)
        val totalContentPx = headerBlockPx + remainingItems * regularSpan - spacing +
            info.beforeContentPadding + info.afterContentPadding
        val viewportHeightPx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1).toFloat()
        val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(1f)
        var targetPx = clamped * maxScrollPx

        var targetIndex = 0
        var offsetPx = 0
        var matchedHeader = false
        for (i in 0 until headerCount) {
            val itemSpan = (observedHeaderSizes[i]?.toFloat() ?: medianItemSize) + spacing
            if (targetPx < itemSpan) {
                targetIndex = i
                offsetPx = targetPx.roundToInt().coerceAtLeast(0)
                matchedHeader = true
                break
            }
            targetPx -= itemSpan
        }
        if (!matchedHeader) {
            val extraIdx = (targetPx / regularSpan).toInt()
            targetIndex = (headerCount + extraIdx).coerceIn(0, totalItems - 1)
            offsetPx = (targetPx - extraIdx * regularSpan).roundToInt().coerceAtLeast(0)
        }
        listState.scrollToItem(targetIndex, offsetPx)
        return
    }

    if (gridState != null) {
        val info = gridState.layoutInfo
        val totalItems = info.totalItemsCount
        if (totalItems <= 0) return

        if (clamped <= EDGE_START_THRESHOLD) {
            gridState.scrollToItem(0, 0)
            return
        }
        if (clamped >= EDGE_END_THRESHOLD) {
            gridState.scrollToItem(totalItems - 1, 0)
            val pushPx = (info.viewportSize.height.coerceAtLeast(1) + info.afterContentPadding).toFloat() * 2f
            gridState.scrollBy(pushPx)
            return
        }

        val visible = info.visibleItemsInfo
        val columns = ((visible.maxOfOrNull { it.column } ?: 0) + 1).coerceAtLeast(1)
        val totalRows = ((totalItems + columns - 1) / columns).coerceAtLeast(1)
        val spacing = info.mainAxisItemSpacing
        val rowHeight = visible.firstOrNull { it.size.height > 0 }?.size?.height?.toFloat()
            ?: cachedRegularItemSpan.floatValue.takeIf { it > 0f }
            ?: 1f
        if (rowHeight > 0f) {
            cachedRegularItemSpan.floatValue = rowHeight
        }

        val rowSpan = (rowHeight + spacing).coerceAtLeast(1f)
        val totalContentPx = totalRows * rowSpan - spacing +
            info.beforeContentPadding + info.afterContentPadding
        val viewportHeightPx = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1).toFloat()
        val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(1f)
        val targetPx = clamped * maxScrollPx

        val targetRow = (targetPx / rowSpan).toInt().coerceIn(0, totalRows - 1)
        val offsetPx = (targetPx - targetRow * rowSpan).roundToInt().coerceAtLeast(0)
        val targetItemIndex = (targetRow * columns).coerceIn(0, totalItems - 1)
        gridState.scrollToItem(targetItemIndex, offsetPx)
    }
}
