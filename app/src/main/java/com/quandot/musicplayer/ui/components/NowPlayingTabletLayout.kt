/**
 * NowPlayingTabletLayout — Adaptive tablet Now Playing presentations for all tablet aspect ratios.
 *
 * @remarks
 * Implements dual-interface parity (Modern ambient backdrop & Classic Material 3 surfaces) across
 * both tablet orientations:
 * - **Landscape (Apple Music TV style):** Framed rounded cover with centered badges/title/artist.
 *   Focus mode centers the hero; opening Lyrics or Queue splits cleanly into a 2-column stage.
 *   All playback controls live in an auto-hiding floating HUD (top album/action pills + bottom
 *   floating control card) revealed on tap and auto-hidden after 5 seconds when enabled.
 * - **Portrait (Full HUD + Inline Swap Slot):** Persistent top bar and full bottom control deck
 *   with a shared center slot that swaps smoothly between the framed rounded album cover, synced
 *   lyrics, and the drag-reorderable inline queue.
 *
 * All dimensions derive dynamically from [BoxWithConstraints] bounds so 16:10, 16:9, 4:3, 3:2,
 * unfolded foldables, and compact 8" tablets stay balanced without clipping or overlap.
 *
 * @see NowPlayingSheet
 */
package com.quandot.musicplayer.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.model.NowPlayingSeekbarStyle
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.ui.AudioOutputUiState
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.theme.LocalIsUiForeground
import com.quandot.musicplayer.ui.theme.LocalScreenVisible
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.PredictiveBackState
import com.quandot.musicplayer.ui.theme.predictiveBackInline
import com.quandot.musicplayer.ui.theme.systemSafeInsets
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName
import com.quandot.musicplayer.util.localizedQueueSource

// ═══ Constants ═══
private const val TABLET_HUD_AUTO_HIDE_MS = 5_000L
private const val TABLET_FAVORITE_HEART_HEX = 0xFFF5576CL
private const val TABLET_MARQUEE_REPEAT_DELAY_MS = 2000
private const val TABLET_MARQUEE_INITIAL_DELAY_MS = 1200
private val QUEUE_ROW_SHAPE = RoundedCornerShape(12.dp)
private val QUEUE_THUMB_SHAPE = RoundedCornerShape(8.dp)
private val TABLET_PILL_SHAPE = RoundedCornerShape(50)

private enum class TabletStageMode {
    FOCUS,
    LYRICS,
    QUEUE
}

@Immutable
internal data class LandscapeHeroInk(
    val focusPrimary: Color,
    val focusSecondary: Color,
    val splitPrimary: Color,
    val splitSecondary: Color
)

internal data class LandscapeHudColors(
    val containerColor: Color,
    val borderColor: Color,
    val palette: NowPlayingPalette
)

// ═══ Landscape TV-Style Player (Tablet Landscape + Phone Landscape with Auto-Hiding Floating HUD) ═══
@Composable
internal fun TabletLandscapeNowPlaying(
    song: Song,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    currentPositionMsFlow: StateFlow<Long>,
    isLiked: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    queue: List<Song>,
    queueSource: String,
    displaySpecs: String,
    audioOutputState: AudioOutputUiState,
    isModernStyle: Boolean,
    isLightAmbientBg: Boolean,
    isPhoneLandscape: Boolean,
    nowPlayingSeekbarStyle: NowPlayingSeekbarStyle,
    showLyrics: Boolean,
    showQueue: Boolean,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSecFlow: StateFlow<Long>?,
    lyricsFontSize: String,
    lyricsAlignment: String,
    lyricsAutoScroll: Boolean,
    activeLyricsDelayMs: Int,
    marqueeIterations: Int,
    isSheetActive: Boolean,
    palette: NowPlayingPalette,
    heroInk: LandscapeHeroInk,
    hudColors: LandscapeHudColors,
    lyricsPrimaryColor: Color,
    lyricsSecondaryColor: Color,
    lyricsBackState: PredictiveBackState,
    queueBackState: PredictiveBackState,
    viewModel: MusicViewModel?,
    onProgressChange: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onSelectSongFromQueue: (Song) -> Unit,
    onReorderQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Song) -> Unit,
    onShowSleepTimer: (() -> Unit)?,
    onPathBadgeClick: () -> Unit,
    onOpenSongAlbum: (() -> Unit)?,
    onMoreClick: (() -> Unit)?,
    onDismissRequest: () -> Unit
) {
    val stageMode = when {
        showLyrics -> TabletStageMode.LYRICS
        showQueue -> TabletStageMode.QUEUE
        else -> TabletStageMode.FOCUS
    }

    var isHudVisible by remember { mutableStateOf(false) }
    var hudInteractionNonce by remember { mutableIntStateOf(0) }
    var isUserSeekingHud by remember { mutableStateOf(false) }

    LaunchedEffect(isSheetActive) {
        if (!isSheetActive) {
            isHudVisible = false
            isUserSeekingHud = false
        }
    }

    LaunchedEffect(isHudVisible, hudInteractionNonce, isUserSeekingHud, isSheetActive) {
        if (isSheetActive && isHudVisible && !isUserSeekingHud) {
            delay(TABLET_HUD_AUTO_HIDE_MS)
            isHudVisible = false
        }
    }

    val bumpHudTimer: () -> Unit = {
        isHudVisible = true
        hudInteractionNonce++
    }

    val hudCardContainerColor = hudColors.containerColor
    val hudCardBorderColor = hudColors.borderColor
    val hudPalette = hudColors.palette

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Fullscreen immersive landscape bypasses system bars and display cutout edge-to-edge
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        isHudVisible = !isHudVisible
                        if (isHudVisible) hudInteractionNonce++
                    }
                )
            }
    ) {
        val usableW = maxWidth
        val usableH = maxHeight
        val isPhoneShort = isPhoneLandscape || usableH < 450.dp
        val isCompactLandscape = isPhoneShort || usableH < 560.dp
        val isVeryShortPhone = usableH < 365.dp
        val isSquareish = usableW < usableH * 1.25f
        val isUltraWide = usableW >= usableH * 1.80f

        val stageHorizontalPad = if (isPhoneShort) {
            (usableW * 0.028f).coerceIn(14.dp, 28.dp)
        } else {
            (usableW * 0.036f).coerceIn(18.dp, 48.dp)
        }
        val stageVerticalPad = if (isPhoneShort) {
            (usableH * 0.025f).coerceIn(4.dp, 12.dp)
        } else {
            (usableH * 0.035f).coerceIn(10.dp, 28.dp)
        }
        val columnGap = if (isPhoneShort) {
            (usableW * 0.03f).coerceIn(14.dp, 28.dp)
        } else {
            (usableW * 0.036f).coerceIn(18.dp, 46.dp)
        }

        val focusCoverSize = if (isPhoneShort) {
            minOf(usableH * 0.54f, usableW * 0.34f, 220.dp).coerceAtLeast(124.dp)
        } else {
            minOf(usableH * 0.54f, usableW * 0.44f, 380.dp).coerceAtLeast(156.dp)
        }
        val splitCoverSize = if (isPhoneShort) {
            minOf(usableH * 0.50f, usableW * 0.25f, 192.dp).coerceAtLeast(108.dp)
        } else {
            minOf(usableH * 0.48f, usableW * 0.34f, 324.dp).coerceAtLeast(136.dp)
        }
        val coverCorner = if (isPhoneShort) 14.dp else if (isCompactLandscape) 16.dp else 20.dp

        val isSplit = stageMode != TabletStageMode.FOCUS
        var lastRightMode by remember { mutableStateOf(TabletStageMode.LYRICS) }
        if (isSplit) {
            lastRightMode = stageMode
        }

        val splitProgressState = animateFloatAsState(
            targetValue = if (isSplit) 1f else 0f,
            animationSpec = tween(durationMillis = 360, easing = M3Motion.EmphasizedDecelerate),
            label = "LandscapeHeroSplitGlide"
        )
        val hudLiftDp = when {
            !isHudVisible -> 0.dp
            isPhoneLandscape -> 0.dp
            isCompactLandscape -> (-24).dp
            else -> (-32).dp
        }
        val animatedHeroLiftState = animateFloatAsState(
            targetValue = with(density) { hudLiftDp.toPx() },
            animationSpec = tween(durationMillis = 300, easing = M3Motion.EmphasizedDecelerate),
            label = "TabletHeroHudLift"
        )
        val baseSyncBottomPad = if (isPhoneShort) 8.dp else 14.dp
        val targetSyncBottomPad = if (isHudVisible) {
            when {
                isPhoneLandscape -> 16.dp
                isCompactLandscape -> 118.dp
                else -> 136.dp
            }
        } else {
            baseSyncBottomPad
        }
        val animatedSyncOffsetYPxState = animateFloatAsState(
            targetValue = with(density) { (baseSyncBottomPad - targetSyncBottomPad).toPx() },
            animationSpec = tween(durationMillis = 240, easing = M3Motion.Standard),
            label = "TabletLyricsSyncClearance"
        )

        // ── Main Content Stage: Persistent Hero glides smoothly center <-> left; Lyrics/Queue fades cleanly ──
        val stageW = (usableW - stageHorizontalPad * 2).coerceAtLeast(160.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = stageHorizontalPad, vertical = stageVerticalPad)
        ) {
            val leftWeight = when {
                isPhoneShort && isUltraWide -> 0.37f
                isPhoneShort -> 0.41f
                isSquareish -> 0.43f
                else -> 0.40f
            }
            val splitLeftWidth = ((stageW - columnGap) * leftWeight).coerceAtLeast(136.dp)
            val rightPaneWidth = (stageW - splitLeftWidth - columnGap).coerceAtLeast(160.dp)
            val splitScale = (splitCoverSize / focusCoverSize).coerceIn(0.74f, 0.94f)
            val heroBaseWidth = minOf(
                splitLeftWidth / splitScale,
                stageW * 0.76f,
                560.dp
            ).coerceAtLeast(focusCoverSize)
            val splitShiftXPx = with(density) { (splitLeftWidth / 2f - stageW / 2f).toPx() }

            // Right Pane (Lyrics / Queue) with pure fade open/close and smooth crossfade between modes
            AnimatedVisibility(
                visible = isSplit,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 280,
                        delayMillis = 70,
                        easing = M3Motion.EmphasizedDecelerate
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = M3Motion.EmphasizedAccelerate
                    )
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(rightPaneWidth)
                    .fillMaxHeight()
            ) {
                AnimatedContent(
                    targetState = lastRightMode,
                    transitionSpec = {
                        fadeIn(tween(220, easing = M3Motion.EmphasizedDecelerate)) togetherWith
                            fadeOut(tween(160, easing = M3Motion.EmphasizedAccelerate))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "TabletLandscapeRightPaneFade"
                ) { activeRight ->
                    when (activeRight) {
                        TabletStageMode.LYRICS -> {
                            NowPlayingLyricsPanel(
                                song = song,
                                currentPositionMsFlow = currentPositionMsFlow,
                                isActive = isSheetActive && showLyrics,
                                isCompact = isCompactLandscape,
                                isTabletExpanded = !isCompactLandscape,
                                expressiveMotion = true,
                                syncButtonBottomPadding = baseSyncBottomPad,
                                syncButtonOffsetYPxProvider = { animatedSyncOffsetYPxState.value },
                                onSeekToMs = onSeekToMs,
                                fontSizeMode = lyricsFontSize,
                                alignmentMode = lyricsAlignment,
                                autoScrollEnabled = lyricsAutoScroll,
                                syncDelayMs = activeLyricsDelayMs,
                                primaryContentColor = lyricsPrimaryColor,
                                secondaryContentColor = lyricsSecondaryColor,
                                accentColor = palette.accentColor,
                                chipContainerColor = palette.chipContainerColor,
                                chipContentColor = palette.chipContentColor,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 4.dp)
                                    .predictiveBackInline(lyricsBackState)
                            )
                        }
                        TabletStageMode.QUEUE -> {
                            TabletInlineQueuePanel(
                                song = song,
                                isPlaying = isPlaying,
                                queue = queue,
                                queueSource = queueSource,
                                isActive = isSheetActive && showQueue,
                                isShuffle = isShuffle,
                                isModernStyle = isModernStyle,
                                isLightAmbientBg = isLightAmbientBg,
                                isCompactRows = isPhoneShort,
                                palette = palette,
                                viewModel = viewModel,
                                bottomExtraPadding = if (isHudVisible && !isPhoneLandscape) {
                                    124.dp
                                } else {
                                    12.dp
                                },
                                onSelectSongFromQueue = onSelectSongFromQueue,
                                onReorderQueue = onReorderQueue,
                                onRemoveFromQueue = onRemoveFromQueue,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .predictiveBackInline(queueBackState)
                            )
                        }
                        TabletStageMode.FOCUS -> Unit
                    }
                }
            }

            // Persistent Hero Block (Cover + Badges + Title + Artist) glides smoothly via GPU graphicsLayer
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(heroBaseWidth)
                    .graphicsLayer {
                        val p = splitProgressState.value
                        val s = 1f + (splitScale - 1f) * p
                        translationX = splitShiftXPx * p
                        translationY = animatedHeroLiftState.value
                        scaleX = s
                        scaleY = s
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    when {
                        isVeryShortPhone -> 6.dp
                        isPhoneShort -> 9.dp
                        isCompactLandscape -> 12.dp
                        else -> 18.dp
                    }
                )
            ) {
                val heroPrimaryColor by animateColorAsState(
                    targetValue = if (isSplit) heroInk.splitPrimary else heroInk.focusPrimary,
                    animationSpec = tween(durationMillis = 240, easing = M3Motion.Standard),
                    label = "LandscapeHeroPrimaryInk"
                )
                val heroSecondaryColor by animateColorAsState(
                    targetValue = if (isSplit) heroInk.splitSecondary else heroInk.focusSecondary,
                    animationSpec = tween(durationMillis = 240, easing = M3Motion.Standard),
                    label = "LandscapeHeroSecondaryInk"
                )
                val heroPalette = palette.copy(
                    primaryContentColor = heroPrimaryColor,
                    secondaryContentColor = heroSecondaryColor
                )

                TabletFramedCoverCard(
                    song = song,
                    isPlaying = isPlaying,
                    size = focusCoverSize,
                    cornerRadius = coverCorner,
                    isModernStyle = isModernStyle,
                    palette = heroPalette
                )

                TabletCenteredHeroInfo(
                    song = song,
                    displaySpecs = displaySpecs,
                    audioOutputState = audioOutputState,
                    palette = heroPalette,
                    marqueeIterations = marqueeIterations,
                    titleFontSp = when {
                        isVeryShortPhone -> 18f
                        isPhoneShort -> 20.5f
                        isCompactLandscape -> 26f
                        else -> 30f
                    },
                    artistFontSp = when {
                        isVeryShortPhone -> 12.5f
                        isPhoneShort -> 14f
                        isCompactLandscape -> 15.5f
                        else -> 17.5f
                    },
                    isCompactHeight = isCompactLandscape,
                    onPathBadgeClick = onPathBadgeClick,
                    onMetaClick = null
                )
            }
        }

        // ── Floating Top HUD Pills (Collapse | Album Title Only | More) ──
        val topBtnSize = if (isPhoneShort) 36.dp else 44.dp
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(tween(220, easing = M3Motion.EmphasizedDecelerate)) +
                slideInVertically(tween(260, easing = M3Motion.EmphasizedDecelerate)) { -it / 2 },
            exit = fadeOut(tween(180, easing = M3Motion.EmphasizedAccelerate)) +
                slideOutVertically(tween(200, easing = M3Motion.EmphasizedAccelerate)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(
                    horizontal = if (isPhoneShort) 14.dp else 20.dp,
                    vertical = if (isPhoneShort) 6.dp else 12.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = hudCardContainerColor,
                    border = BorderStroke(1.dp, hudCardBorderColor),
                    shadowElevation = 0.dp
                ) {
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(topBtnSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.cd_collapse),
                            tint = hudPalette.primaryContentColor,
                            modifier = Modifier.size(if (isPhoneShort) 22.dp else 26.dp)
                        )
                    }
                }

                // Album title only (no DAC name) — tap to open the current song's album
                Surface(
                    shape = CircleShape,
                    color = hudCardContainerColor,
                    border = BorderStroke(1.dp, hudCardBorderColor),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .widthIn(max = minOf(usableW * 0.54f, 420.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .then(
                                if (onOpenSongAlbum != null) {
                                    Modifier.clickable(onClick = onOpenSongAlbum)
                                } else {
                                    Modifier
                                }
                            )
                            .padding(
                                horizontal = if (isPhoneShort) 14.dp else 20.dp,
                                vertical = if (isPhoneShort) 6.dp else 10.dp
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = localizedAlbumName(song.album),
                            style = if (isPhoneShort) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = hudPalette.primaryContentColor,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.basicMarquee(
                                iterations = marqueeIterations,
                                repeatDelayMillis = TABLET_MARQUEE_REPEAT_DELAY_MS,
                                initialDelayMillis = TABLET_MARQUEE_INITIAL_DELAY_MS
                            )
                        )
                    }
                }

                if (onMoreClick != null) {
                    Surface(
                        shape = CircleShape,
                        color = hudCardContainerColor,
                        border = BorderStroke(1.dp, hudCardBorderColor),
                        shadowElevation = 0.dp
                    ) {
                        IconButton(
                            onClick = {
                                bumpHudTimer()
                                onMoreClick()
                            },
                            modifier = Modifier.size(topBtnSize)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.action_more),
                                tint = hudPalette.secondaryContentColor,
                                modifier = Modifier.size(if (isPhoneShort) 19.dp else 22.dp)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(topBtnSize))
                }
            }
        }

        // ── Floating Bottom Control Card ──
        // Phone Landscape: larger 3-row floating card matching Phone Portrait HUD layout (Seekbar -> Transport -> Utility)
        // Tablet Landscape: sleek 2-row wide horizontal floating card (Inline Seekbar -> 3-zone Controls)
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(tween(220, easing = M3Motion.EmphasizedDecelerate)) +
                slideInVertically(tween(280, easing = M3Motion.EmphasizedDecelerate)) { it / 2 },
            exit = fadeOut(tween(180, easing = M3Motion.EmphasizedAccelerate)) +
                slideOutVertically(tween(200, easing = M3Motion.EmphasizedAccelerate)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    horizontal = if (isPhoneLandscape) 16.dp else 20.dp,
                    vertical = if (isPhoneLandscape) 10.dp else if (isCompactLandscape) 12.dp else 18.dp
                )
        ) {
            if (isPhoneLandscape) {
                val phoneCardWidth = minOf(usableW - 36.dp, 420.dp)

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = hudCardContainerColor,
                    border = BorderStroke(1.dp, hudCardBorderColor),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .width(phoneCardWidth)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { bumpHudTimer() })
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Row 1: Compact Portrait-style Seekbar + timestamps underneath
                        NowPlayingSeekbar(
                            songId = song.id,
                            instanceId = song.instanceId,
                            progressFlow = progressFlow,
                            currentPositionMsFlow = currentPositionMsFlow,
                            durationMs = song.duration,
                            isActive = isSheetActive && isHudVisible,
                            isPlaying = isPlaying,
                            seekbarStyle = nowPlayingSeekbarStyle,
                            formattedDuration = song.formattedDuration,
                            palette = hudPalette,
                            onProgressChange = { progress ->
                                onProgressChange(progress)
                                bumpHudTimer()
                            },
                            isCompact = true,
                            inlineTimestamps = false,
                            slimVertical = true,
                            onSeekingChanged = { seeking ->
                                isUserSeekingHud = seeking
                                if (!seeking) bumpHudTimer()
                            }
                        )

                        // Row 2: Portrait-style Transport Controls (compact height for sleek card)
                        NowPlayingTransportControls(
                            isPlaying = isPlaying,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            palette = hudPalette,
                            onToggleShuffle = {
                                onToggleShuffle()
                                bumpHudTimer()
                            },
                            onPrevious = {
                                onPrevious()
                                bumpHudTimer()
                            },
                            onTogglePlay = {
                                onTogglePlay()
                                bumpHudTimer()
                            },
                            onStopAndReset = {
                                onStopAndReset()
                                bumpHudTimer()
                            },
                            onNext = {
                                onNext()
                                bumpHudTimer()
                            },
                            onToggleRepeat = {
                                onToggleRepeat()
                                bumpHudTimer()
                            },
                            isCompact = true
                        )

                        // Row 3: Portrait-style 4-icon Utility Row (Lyrics, Favorite, Sleep Timer, Queue)
                        NowPlayingUtilityButtons(
                            isLiked = isLiked,
                            showLyrics = showLyrics,
                            showQueueSheet = showQueue,
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                            isSheetActive = isSheetActive && isHudVisible,
                            palette = hudPalette,
                            onToggleLyrics = {
                                onToggleLyrics()
                                bumpHudTimer()
                            },
                            onToggleLike = {
                                onToggleLike()
                                bumpHudTimer()
                            },
                            onShowSleepTimer = onShowSleepTimer?.let { cb ->
                                {
                                    cb()
                                    bumpHudTimer()
                                }
                            },
                            onShowQueue = {
                                onToggleQueue()
                                bumpHudTimer()
                            },
                            isCompact = true
                        )
                    }
                }
            } else {
                val cardWidth = minOf(usableW - 40.dp, 740.dp)
                val showPillTextLabels = cardWidth >= 520.dp

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = hudCardContainerColor,
                    border = BorderStroke(1.dp, hudCardBorderColor),
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .width(cardWidth)
                        .pointerInput(Unit) {
                            // Consume taps inside the card so tapping empty card space resets the 5s timer instead of hiding HUD
                            detectTapGestures(onTap = { bumpHudTimer() })
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = if (isCompactLandscape) 16.dp else 22.dp,
                                vertical = if (isCompactLandscape) 8.dp else 12.dp
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (isCompactLandscape) 2.dp else 6.dp)
                    ) {
                        // Row 1: Inline Elapsed • M3 Expressive Seekbar (Wavy/Slider) • Total
                        NowPlayingSeekbar(
                            songId = song.id,
                            instanceId = song.instanceId,
                            progressFlow = progressFlow,
                            currentPositionMsFlow = currentPositionMsFlow,
                            durationMs = song.duration,
                            isActive = isSheetActive && isHudVisible,
                            isPlaying = isPlaying,
                            seekbarStyle = nowPlayingSeekbarStyle,
                            formattedDuration = song.formattedDuration,
                            palette = hudPalette,
                            onProgressChange = { progress ->
                                onProgressChange(progress)
                                bumpHudTimer()
                            },
                            isCompact = false,
                            inlineTimestamps = true,
                            onSeekingChanged = { seeking ->
                                isUserSeekingHud = seeking
                                if (!seeking) bumpHudTimer()
                            }
                        )

                        // Row 2: Left Utilities • Center Transport • Right Utilities
                        TabletFloatingControlsRow(
                            isPlaying = isPlaying,
                            isShuffle = isShuffle,
                            repeatMode = repeatMode,
                            isLiked = isLiked,
                            showLyrics = showLyrics,
                            showQueue = showQueue,
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                            isActive = isSheetActive && isHudVisible,
                            palette = hudPalette,
                            isPhoneCompact = false,
                            isCompact = isCompactLandscape,
                            showPillTextLabels = showPillTextLabels,
                            onToggleLyrics = {
                                onToggleLyrics()
                                bumpHudTimer()
                            },
                            onToggleLike = {
                                onToggleLike()
                                bumpHudTimer()
                            },
                            onToggleShuffle = {
                                onToggleShuffle()
                                bumpHudTimer()
                            },
                            onPrevious = {
                                onPrevious()
                                bumpHudTimer()
                            },
                            onTogglePlay = {
                                onTogglePlay()
                                bumpHudTimer()
                            },
                            onStopAndReset = {
                                onStopAndReset()
                                bumpHudTimer()
                            },
                            onNext = {
                                onNext()
                                bumpHudTimer()
                            },
                            onToggleRepeat = {
                                onToggleRepeat()
                                bumpHudTimer()
                            },
                            onShowSleepTimer = onShowSleepTimer?.let { cb ->
                                {
                                    cb()
                                    bumpHudTimer()
                                }
                            },
                            onToggleQueue = {
                                onToggleQueue()
                                bumpHudTimer()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ═══ Tablet Portrait (Full Persistent HUD + Inline Cover / Lyrics / Queue Swap Slot) ═══
@Composable
internal fun TabletPortraitNowPlaying(
    song: Song,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    currentPositionMsFlow: StateFlow<Long>,
    isLiked: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    queue: List<Song>,
    queueSource: String,
    displaySpecs: String,
    audioOutputState: AudioOutputUiState,
    isModernStyle: Boolean,
    isLightAmbientBg: Boolean,
    nowPlayingSeekbarStyle: NowPlayingSeekbarStyle,
    showLyrics: Boolean,
    showQueue: Boolean,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSecFlow: StateFlow<Long>?,
    lyricsFontSize: String,
    lyricsAlignment: String,
    lyricsAutoScroll: Boolean,
    activeLyricsDelayMs: Int,
    marqueeIterations: Int,
    isSheetActive: Boolean,
    palette: NowPlayingPalette,
    topBarPalette: NowPlayingPalette,
    lyricsPrimaryColor: Color,
    lyricsSecondaryColor: Color,
    lyricsBackState: PredictiveBackState,
    queueBackState: PredictiveBackState,
    viewModel: MusicViewModel?,
    onProgressChange: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onSelectSongFromQueue: (Song) -> Unit,
    onReorderQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Song) -> Unit,
    onShowSleepTimer: (() -> Unit)?,
    onPathBadgeClick: () -> Unit,
    onOpenSongAlbum: (() -> Unit)?,
    onMoreClick: (() -> Unit)?,
    onDismissRequest: () -> Unit
) {
    val safeInsets = systemSafeInsets()
    val activeSlot = when {
        showLyrics -> TabletStageMode.LYRICS
        showQueue -> TabletStageMode.QUEUE
        else -> TabletStageMode.FOCUS
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(safeInsets)
    ) {
        val usableW = maxWidth
        val usableH = maxHeight
        val isCompactPortrait = usableH < 760.dp
        val horizontalPad = (usableW * 0.055f).coerceIn(22.dp, 48.dp)
        val maxContentWidth = minOf(usableW - horizontalPad * 2, 580.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPad)
                .padding(top = 4.dp, bottom = if (isCompactPortrait) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Persistent Top Bar
            NowPlayingTopBar(
                albumTitle = localizedAlbumName(song.album),
                marqueeIterations = marqueeIterations,
                palette = topBarPalette,
                onDismissRequest = onDismissRequest,
                onMoreClick = onMoreClick,
                compact = isCompactPortrait
            )

            // Shared Center Swap Slot: Framed Rounded Cover ↔ Synced Lyrics ↔ Inline Queue
            Box(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = if (isCompactPortrait) 8.dp else 16.dp),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = activeSlot,
                    transitionSpec = {
                        M3Motion.lyricsPanelTransform(openingLyrics = targetState != TabletStageMode.FOCUS)
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    label = "TabletPortraitCenterSwap"
                ) { slot ->
                    when (slot) {
                        TabletStageMode.FOCUS -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                TabletFramedCoverCard(
                                    song = song,
                                    isPlaying = isPlaying,
                                    size = null,
                                    cornerRadius = 20.dp,
                                    isModernStyle = isModernStyle,
                                    palette = palette
                                )
                            }
                        }
                        TabletStageMode.LYRICS -> {
                            val lyricsContent: @Composable () -> Unit = {
                                NowPlayingLyricsPanel(
                                    song = song,
                                    currentPositionMsFlow = currentPositionMsFlow,
                                    isActive = isSheetActive,
                                    isCompact = isCompactPortrait,
                                    isTabletExpanded = !isCompactPortrait,
                                    onSeekToMs = onSeekToMs,
                                    fontSizeMode = lyricsFontSize,
                                    alignmentMode = lyricsAlignment,
                                    autoScrollEnabled = lyricsAutoScroll,
                                    syncDelayMs = activeLyricsDelayMs,
                                    primaryContentColor = lyricsPrimaryColor,
                                    secondaryContentColor = lyricsSecondaryColor,
                                    accentColor = palette.accentColor,
                                    chipContainerColor = palette.chipContainerColor,
                                    chipContentColor = palette.chipContentColor,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = if (isModernStyle) 4.dp else 14.dp)
                                        .predictiveBackInline(lyricsBackState)
                                )
                            }
                            if (isModernStyle) {
                                lyricsContent()
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(24.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    lyricsContent()
                                }
                            }
                        }
                        TabletStageMode.QUEUE -> {
                            val portraitQueuePalette = if (isModernStyle) {
                                palette.copy(
                                    primaryContentColor = lyricsPrimaryColor,
                                    secondaryContentColor = lyricsSecondaryColor
                                )
                            } else {
                                palette
                            }
                            TabletInlineQueuePanel(
                                song = song,
                                isPlaying = isPlaying,
                                queue = queue,
                                queueSource = queueSource,
                                isActive = isSheetActive && showQueue,
                                isShuffle = isShuffle,
                                isModernStyle = isModernStyle,
                                isLightAmbientBg = isLightAmbientBg,
                                palette = portraitQueuePalette,
                                viewModel = viewModel,
                                bottomExtraPadding = 12.dp,
                                onSelectSongFromQueue = onSelectSongFromQueue,
                                onReorderQueue = onReorderQueue,
                                onRemoveFromQueue = onRemoveFromQueue,
                                borderless = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .predictiveBackInline(queueBackState)
                            )
                        }
                    }
                }
            }

            // Full Persistent Bottom Control HUD
            Column(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TabletCenteredHeroInfo(
                    song = song,
                    displaySpecs = displaySpecs,
                    audioOutputState = audioOutputState,
                    palette = palette,
                    marqueeIterations = marqueeIterations,
                    titleFontSp = if (isCompactPortrait) 22f else 26f,
                    artistFontSp = if (isCompactPortrait) 14.5f else 16.5f,
                    isCompactHeight = isCompactPortrait,
                    onPathBadgeClick = onPathBadgeClick,
                    onMetaClick = onOpenSongAlbum
                )

                NowPlayingSeekbar(
                    songId = song.id,
                    instanceId = song.instanceId,
                    progressFlow = progressFlow,
                    currentPositionMsFlow = currentPositionMsFlow,
                    durationMs = song.duration,
                    isActive = isSheetActive,
                    isPlaying = isPlaying,
                    seekbarStyle = nowPlayingSeekbarStyle,
                    formattedDuration = song.formattedDuration,
                    palette = palette,
                    onProgressChange = onProgressChange,
                    isCompact = isCompactPortrait
                )

                NowPlayingTransportControls(
                    isPlaying = isPlaying,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    palette = palette,
                    onToggleShuffle = onToggleShuffle,
                    onPrevious = onPrevious,
                    onTogglePlay = onTogglePlay,
                    onStopAndReset = onStopAndReset,
                    onNext = onNext,
                    onToggleRepeat = onToggleRepeat,
                    isCompact = isCompactPortrait
                )

                NowPlayingUtilityButtons(
                    isLiked = isLiked,
                    showLyrics = showLyrics,
                    showQueueSheet = showQueue,
                    sleepTimerMinutes = sleepTimerMinutes,
                    sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                    isSheetActive = isSheetActive,
                    palette = palette,
                    onToggleLyrics = onToggleLyrics,
                    onToggleLike = onToggleLike,
                    onShowSleepTimer = onShowSleepTimer,
                    onShowQueue = onToggleQueue,
                    isCompact = isCompactPortrait,
                    showModeLabels = true
                )
            }
        }
    }
}

// ═══ Shared Tablet Sub-components ═══

/**
 * Framed rounded album cover card used across BOTH Modern and Classic tablet modes
 * (no edge feathering/blending on tablets). Scales down gently when paused via GPU transform.
 */
@Composable
private fun TabletFramedCoverCard(
    song: Song,
    isPlaying: Boolean,
    size: Dp?,
    cornerRadius: Dp,
    isModernStyle: Boolean,
    palette: NowPlayingPalette
) {
    val coverScaleState = animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.94f,
        animationSpec = tween(durationMillis = 320, easing = M3Motion.EmphasizedDecelerate),
        label = "TabletCoverPlayScale"
    )
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val borderColor = if (isModernStyle) {
        palette.primaryContentColor.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val sizeMod = if (size != null) {
        Modifier.size(size)
    } else {
        Modifier
            .sizeIn(maxWidth = 420.dp, maxHeight = 420.dp)
            .aspectRatio(1f)
    }
    val resolutionBucket = if (isModernStyle && (size == null || size >= 260.dp)) {
        ArtResolutionBucket.ULTRA_RES
    } else {
        ArtResolutionBucket.HIGH_RES
    }

    Surface(
        modifier = sizeMod.graphicsLayer {
            val s = coverScaleState.value
            scaleX = s
            scaleY = s
        },
        shape = shape,
        shadowElevation = if (isModernStyle) 10.dp else 6.dp,
        border = BorderStroke(1.dp, borderColor),
        color = Color.Transparent
    ) {
        AlbumArtImage(
            song = song,
            icon = Icons.Default.Album,
            bucket = resolutionBucket,
            shape = RectangleShape,
            enableCrossfade = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Centered audio quality badges, song title, and artist name beneath the tablet cover artwork.
 */
@Composable
private fun TabletCenteredHeroInfo(
    song: Song,
    displaySpecs: String,
    audioOutputState: AudioOutputUiState,
    palette: NowPlayingPalette,
    marqueeIterations: Int,
    titleFontSp: Float,
    artistFontSp: Float,
    isCompactHeight: Boolean,
    onPathBadgeClick: () -> Unit,
    onMetaClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 3.dp else 5.dp)
    ) {
        NowPlayingAudioBadgesRow(
            displaySpecs = displaySpecs,
            audioOutputState = audioOutputState,
            isCompactHeight = isCompactHeight,
            palette = palette,
            onPathBadgeClick = onPathBadgeClick,
            centerAligned = true
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .then(if (onMetaClick != null) Modifier.clickable(onClick = onMetaClick) else Modifier)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Keep Text unconstrained by fillMaxWidth() before basicMarquee(): basicMarquee measures
            // children with Infinity maxWidth and places short text at x=0 of its own container,
            // so wrapping in a centered Box ensures short titles/artists stay dead-center across rotations.
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = titleFontSp.sp,
                        lineHeight = (titleFontSp * 1.24f).sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = palette.primaryContentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        iterations = marqueeIterations,
                        repeatDelayMillis = TABLET_MARQUEE_REPEAT_DELAY_MS,
                        initialDelayMillis = TABLET_MARQUEE_INITIAL_DELAY_MS
                    )
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = localizedArtistName(song.artist),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = artistFontSp.sp,
                        lineHeight = (artistFontSp * 1.32f).sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = palette.secondaryContentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(
                        iterations = marqueeIterations,
                        repeatDelayMillis = TABLET_MARQUEE_REPEAT_DELAY_MS,
                        initialDelayMillis = TABLET_MARQUEE_INITIAL_DELAY_MS
                    )
                )
            }
        }
    }
}

/**
 * Compact 3-zone controls row inside the landscape floating HUD card:
 * Left utilities (Lyrics, Favorite) • Center Transport (Shuffle, Prev, Play/Pause, Next, Repeat) •
 * Right utilities (Sleep Timer, Queue).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletFloatingControlsRow(
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    isLiked: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSecFlow: StateFlow<Long>?,
    isActive: Boolean,
    palette: NowPlayingPalette,
    isPhoneCompact: Boolean,
    isCompact: Boolean,
    showPillTextLabels: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    onShowSleepTimer: (() -> Unit)?,
    onToggleQueue: () -> Unit
) {
    val pillHeight = when {
        isPhoneCompact -> 34.dp
        isCompact -> 36.dp
        else -> 40.dp
    }
    val secondaryBtnSize = when {
        isPhoneCompact -> 34.dp
        isCompact -> 38.dp
        else -> 42.dp
    }
    val skipBtnSize = when {
        isPhoneCompact -> 38.dp
        isCompact -> 42.dp
        else -> 46.dp
    }
    val playBtnWidth = when {
        isPhoneCompact -> 52.dp
        isCompact -> 62.dp
        else -> 68.dp
    }
    val playBtnHeight = when {
        isPhoneCompact -> 40.dp
        isCompact -> 48.dp
        else -> 54.dp
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Utilities: Lyrics + Favorite
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isPhoneCompact) 2.dp else 4.dp)
        ) {
            TabletModePillButton(
                icon = Icons.AutoMirrored.Filled.Article,
                label = if (showPillTextLabels) stringResource(R.string.cd_lyrics_short) else null,
                contentDescription = stringResource(R.string.cd_lyrics),
                selected = showLyrics,
                palette = palette,
                height = pillHeight,
                onClick = onToggleLyrics
            )

            IconButton(
                onClick = onToggleLike,
                modifier = Modifier.size(pillHeight)
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(R.string.cd_favorite),
                    tint = if (isLiked) Color(TABLET_FAVORITE_HEART_HEX) else palette.secondaryContentColor,
                    modifier = Modifier.size(if (isPhoneCompact) 19.dp else 21.dp)
                )
            }
        }

        // Center Transport Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isPhoneCompact) 2.dp else if (isCompact) 4.dp else 8.dp)
        ) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.size(secondaryBtnSize)
            ) {
                Icon(
                    painter = painterResource(if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle),
                    contentDescription = stringResource(R.string.cd_shuffle),
                    tint = if (isShuffle) palette.accentColor else palette.secondaryContentColor,
                    modifier = Modifier.size(if (isPhoneCompact) 18.dp else 20.dp)
                )
            }

            IconButton(
                onClick = onPrevious,
                modifier = Modifier.size(skipBtnSize)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_previous),
                    tint = palette.primaryContentColor,
                    modifier = Modifier.size(if (isPhoneCompact) 24.dp else if (isCompact) 28.dp else 30.dp)
                )
            }

            TabletPlayMorphButton(
                isPlaying = isPlaying,
                isPhoneCompact = isPhoneCompact,
                isCompact = isCompact,
                width = playBtnWidth,
                height = playBtnHeight,
                palette = palette,
                onTogglePlay = onTogglePlay,
                onStopAndReset = onStopAndReset
            )

            IconButton(
                onClick = onNext,
                modifier = Modifier.size(skipBtnSize)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.cd_next),
                    tint = palette.primaryContentColor,
                    modifier = Modifier.size(if (isPhoneCompact) 24.dp else if (isCompact) 28.dp else 30.dp)
                )
            }

            val repeatIconRes = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
                else -> R.drawable.ic_repeat
            }
            IconButton(
                onClick = onToggleRepeat,
                modifier = Modifier.size(secondaryBtnSize)
            ) {
                Icon(
                    painter = painterResource(repeatIconRes),
                    contentDescription = stringResource(R.string.cd_repeat),
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) palette.secondaryContentColor else palette.accentColor,
                    modifier = Modifier.size(if (isPhoneCompact) 18.dp else 20.dp)
                )
            }
        }

        // Right Utilities: Sleep Timer + Queue
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isPhoneCompact) 2.dp else 4.dp, Alignment.End)
        ) {
            if (onShowSleepTimer != null) {
                SleepTimerUtilityControl(
                    sleepTimerMinutes = sleepTimerMinutes,
                    sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                    isActive = isActive,
                    palette = palette,
                    onShowSleepTimer = onShowSleepTimer,
                    buttonSize = pillHeight,
                    pillHeight = pillHeight,
                    pillIconSize = if (isPhoneCompact) 16.dp else 18.dp,
                    buttonIconSize = if (isPhoneCompact) 18.dp else 20.dp,
                    pillHorizontalPadding = if (isPhoneCompact) 8.dp else 10.dp
                )
            }

            TabletModePillButton(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = if (showPillTextLabels) stringResource(R.string.cd_queue_short) else null,
                contentDescription = stringResource(R.string.cd_queue),
                selected = showQueue,
                palette = palette,
                height = pillHeight,
                onClick = onToggleQueue
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabletPlayMorphButton(
    isPlaying: Boolean,
    isPhoneCompact: Boolean,
    isCompact: Boolean,
    width: Dp,
    height: Dp,
    palette: NowPlayingPalette,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val playInteraction = remember { MutableInteractionSource() }
    val playRadius by animateDpAsState(
        targetValue = if (isPlaying) {
            if (isPhoneCompact) 14.dp else 18.dp
        } else {
            if (isPhoneCompact) 22.dp else 28.dp
        },
        animationSpec = tween(durationMillis = 240, easing = M3Motion.Standard),
        label = "TabletPlayMorphRadius"
    )
    Surface(
        shape = RoundedCornerShape(playRadius),
        color = palette.accentColor,
        contentColor = palette.onAccentColor,
        modifier = Modifier
            .width(width)
            .height(height)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = playInteraction,
                    indication = ripple(),
                    onClick = onTogglePlay,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStopAndReset()
                    }
                )
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.cd_pause else R.string.cd_play),
                modifier = Modifier.size(if (isPhoneCompact) 24.dp else if (isCompact) 28.dp else 32.dp)
            )
        }
    }
}

@Composable
internal fun TabletModePillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String?,
    contentDescription: String,
    selected: Boolean,
    palette: NowPlayingPalette,
    height: Dp,
    onClick: () -> Unit
) {
    val activeFractionState = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = M3Motion.Standard),
        label = "TabletPillActive"
    )
    val contentColor = if (selected) palette.accentColor else palette.secondaryContentColor

    Box(
        modifier = Modifier
            .height(height)
            .clip(TABLET_PILL_SHAPE)
            .drawBehind {
                val frac = activeFractionState.value
                if (frac > 0.001f) {
                    drawRect(color = lerp(Color.Transparent, palette.activePillContainerColor, frac))
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = if (label != null) 12.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(21.dp)
            )
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Inline drag-reorderable queue panel used across Landscape (framed right column) and
 * Portrait (borderless center swap slot replacing the album cover just like Lyrics).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabletInlineQueuePanel(
    song: Song,
    isPlaying: Boolean,
    queue: List<Song>,
    queueSource: String,
    isActive: Boolean,
    isShuffle: Boolean,
    isModernStyle: Boolean,
    isLightAmbientBg: Boolean,
    palette: NowPlayingPalette,
    viewModel: MusicViewModel?,
    bottomExtraPadding: Dp,
    onSelectSongFromQueue: (Song) -> Unit,
    onReorderQueue: (Int, Int) -> Unit,
    onRemoveFromQueue: (Song) -> Unit,
    modifier: Modifier = Modifier,
    isCompactRows: Boolean = false,
    borderless: Boolean = false
) {
    val currentQueueIdx = viewModel?.currentQueueIndex?.collectAsStateWithLifecycle()?.value ?: 0
    val isUiForeground = LocalIsUiForeground.current.value
    val currentSongIndex = remember(queue, song, currentQueueIdx) {
        if (currentQueueIdx in queue.indices &&
            (queue[currentQueueIdx].id == song.id ||
                (song.instanceId > 0L && queue[currentQueueIdx].instanceId == song.instanceId))
        ) {
            currentQueueIdx
        } else {
            val targetInstId = song.instanceId
            val targetId = song.id
            if (targetInstId > 0L) queue.indexOfFirst { it.instanceId == targetInstId }
            else queue.indexOfFirst { it.id == targetId }
        }
    }

    val queueListState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentSongIndex.coerceAtLeast(0)
    )
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var showCreatePlaylistFromQueueDialog by remember { mutableStateOf(false) }

    val reorderableLazyListState = rememberReorderableLazyListState(queueListState) { from, to ->
        onReorderQueue(from.index, to.index)
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LaunchedEffect(isActive) {
        if (isActive && currentSongIndex in queue.indices) {
            val visibleIndices = queueListState.layoutInfo.visibleItemsInfo.map { it.index }
            if (currentSongIndex !in visibleIndices) {
                queueListState.scrollToItem(currentSongIndex)
            }
        }
    }

    var prevShuffleState by remember { mutableStateOf(isShuffle) }
    LaunchedEffect(isShuffle) {
        if (prevShuffleState != isShuffle) {
            prevShuffleState = isShuffle
            if (isActive && currentSongIndex in queue.indices) {
                queueListState.scrollToItem(currentSongIndex)
            }
        }
    }

    val panelBg = when {
        borderless -> Color.Transparent
        isModernStyle -> palette.primaryContentColor.copy(alpha = if (isLightAmbientBg) 0.06f else 0.09f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val headerBg = when {
        borderless || isModernStyle -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val panelBorder = if (isModernStyle) {
        palette.primaryContentColor.copy(alpha = if (isLightAmbientBg) 0.13f else 0.15f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val currentRowBg = if (isModernStyle) {
        palette.primaryContentColor.copy(alpha = if (isLightAmbientBg) 0.12f else 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val draggingRowBg = if (isModernStyle) {
        palette.primaryContentColor.copy(alpha = if (isLightAmbientBg) 0.18f else 0.24f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val rowPrimaryInk = if (isModernStyle) palette.primaryContentColor else MaterialTheme.colorScheme.onSurface
    val rowSecondaryInk = if (isModernStyle) palette.secondaryContentColor else MaterialTheme.colorScheme.onSurfaceVariant
    val rowAccentInk = if (isModernStyle) palette.accentColor else MaterialTheme.colorScheme.primary

    val queueContent: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            // Queue Source Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(
                        horizontal = when {
                            borderless && isCompactRows -> 16.dp
                            borderless -> 18.dp
                            isCompactRows -> 14.dp
                            else -> 20.dp
                        },
                        vertical = when {
                            borderless && isCompactRows -> 4.dp
                            borderless -> 8.dp
                            isCompactRows -> 8.dp
                            else -> 14.dp
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.queue_playing_from),
                        style = MaterialTheme.typography.labelSmall,
                        color = rowSecondaryInk
                    )
                    Spacer(modifier = Modifier.height(if (isCompactRows) 0.dp else 2.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.queue_header,
                            queue.size,
                            localizedQueueSource(queueSource),
                            queue.size
                        ),
                        style = if (isCompactRows) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = rowPrimaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isActive && isUiForeground && isPlaying) {
                                    Modifier.basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        repeatDelayMillis = TABLET_MARQUEE_REPEAT_DELAY_MS,
                                        initialDelayMillis = TABLET_MARQUEE_INITIAL_DELAY_MS
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                if (viewModel != null && queue.isNotEmpty()) {
                    IconButton(
                        onClick = { showCreatePlaylistFromQueueDialog = true },
                        modifier = Modifier.size(if (isCompactRows) 34.dp else 38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = stringResource(R.string.dialog_create_playlist_title),
                            tint = rowSecondaryInk,
                            modifier = Modifier.size(if (isCompactRows) 20.dp else 22.dp)
                        )
                    }
                }
            }

            if (!borderless) {
                HorizontalDivider(color = panelBorder.copy(alpha = 0.7f))
            }

            // Drag-Reorderable Inline Queue List
            val isQueueFastScrolling = rememberIsFastScrolling(queueListState)
            val thumbSize = if (isCompactRows) 38.dp else 44.dp
            CompositionLocalProvider(LocalFastScrollingState provides isQueueFastScrolling) {
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = if (borderless) 8.dp else if (isCompactRows) 8.dp else 12.dp,
                        end = if (borderless) 8.dp else if (isCompactRows) 8.dp else 12.dp,
                        top = if (borderless) 6.dp else if (isCompactRows) 6.dp else 8.dp,
                        bottom = bottomExtraPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(if (isCompactRows) 3.dp else 5.dp)
                ) {
                    itemsIndexed(
                        items = queue,
                        key = { index, it -> if (it.instanceId > 0L) it.instanceId else "${it.id}_$index" },
                        contentType = { _, _ -> "tablet_queue_song" }
                    ) { index, queueSong ->
                        val songKey = if (queueSong.instanceId > 0L) queueSong.instanceId else "${queueSong.id}_$index"
                        val isCurrent = index == currentSongIndex

                        ReorderableItem(
                            state = reorderableLazyListState,
                            key = songKey
                        ) { isDragging ->
                            val targetRowBg = when {
                                isDragging -> draggingRowBg
                                isCurrent -> currentRowBg
                                else -> Color.Transparent
                            }
                            val animatedRowBgState = animateColorAsState(
                                targetValue = targetRowBg,
                                animationSpec = tween(durationMillis = 200, easing = M3Motion.Standard),
                                label = "QueueRowBg"
                            )
                            val titleColor = if (isCurrent) rowAccentInk else rowPrimaryInk
                            val dragScaleState = animateFloatAsState(
                                targetValue = if (isDragging) 1.025f else 1f,
                                animationSpec = tween(durationMillis = 150, easing = M3Motion.Standard),
                                label = "QueueDragScale"
                            )
                            Row(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(durationMillis = 220, easing = M3Motion.StandardDecelerate),
                                        fadeOutSpec = tween(durationMillis = 160, easing = M3Motion.StandardAccelerate),
                                        placementSpec = tween(durationMillis = 260, easing = M3Motion.EmphasizedDecelerate)
                                    )
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        val s = dragScaleState.value
                                        scaleX = s
                                        scaleY = s
                                    }
                                    .then(
                                        if (isDragging) {
                                            Modifier.border(
                                                width = 1.5.dp,
                                                color = rowAccentInk,
                                                shape = QUEUE_ROW_SHAPE
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clip(QUEUE_ROW_SHAPE)
                                    .drawBehind {
                                        val bg = animatedRowBgState.value
                                        if (bg.alpha > 0f) {
                                            drawRect(color = bg)
                                        }
                                    }
                                    .clickable {
                                        if (viewModel != null) {
                                            viewModel.playSongFromQueue(index, queueSong)
                                        } else {
                                            onSelectSongFromQueue(queueSong)
                                        }
                                    }
                                    .padding(
                                        horizontal = if (isCompactRows) 8.dp else 10.dp,
                                        vertical = if (isCompactRows) 5.dp else 8.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(thumbSize)
                                        .clip(QUEUE_THUMB_SHAPE),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AlbumArtImage(
                                        song = queueSong,
                                        icon = Icons.Default.MusicNote,
                                        bucket = ArtResolutionBucket.THUMBNAIL,
                                        shape = QUEUE_THUMB_SHAPE,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.44f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            QueueEqualizerIndicator(
                                                isPlaying = isPlaying && isActive,
                                                barColor = Color.White,
                                                modifier = Modifier.size(if (isCompactRows) 16.dp else 18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = queueSong.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        color = titleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${localizedArtistName(queueSong.artist)} • ${queueSong.formattedDuration}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = rowSecondaryInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = {
                                        if (viewModel != null) {
                                            viewModel.removeFromQueue(index)
                                        } else {
                                            onRemoveFromQueue(queueSong)
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.cd_remove_from_queue),
                                        tint = rowSecondaryInk.copy(alpha = 0.65f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        ),
                                    onClick = {}
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragHandle,
                                        contentDescription = stringResource(R.string.cd_drag_reorder),
                                        tint = if (isDragging || isCurrent) rowAccentInk else rowSecondaryInk.copy(alpha = 0.65f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (borderless) {
        Box(modifier = modifier) {
            queueContent()
        }
    } else {
        Surface(
            shape = RoundedCornerShape(if (isCompactRows) 20.dp else 24.dp),
            color = panelBg,
            border = BorderStroke(1.dp, panelBorder),
            modifier = modifier
        ) {
            queueContent()
        }
    }

    if (showCreatePlaylistFromQueueDialog && viewModel != null) {
        var playlistName by remember { mutableStateOf("") }
        SyncDialogSystemBars()
        AlertDialog(
            onDismissRequest = { showCreatePlaylistFromQueueDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            title = {
                Text(
                    text = stringResource(R.string.dialog_create_playlist_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text(stringResource(R.string.dialog_playlist_name_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = playlistName.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.createPlaylistWithSongs(trimmed, queue) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_created_playlist, trimmed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            showCreatePlaylistFromQueueDialog = false
                        }
                    },
                    enabled = playlistName.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistFromQueueDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Lightweight 3-bar equalizer pulse rendered on the active track thumbnail or track slot.
 * Allocates zero animation loops when paused or off-screen.
 */
@Composable
internal fun QueueEqualizerIndicator(
    isPlaying: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    val shouldAnimate = isPlaying && LocalScreenVisible.current && LocalIsUiForeground.current.value
    if (!shouldAnimate) {
        Canvas(modifier = modifier) {
            val barW = size.width * 0.22f
            val gap = (size.width - barW * 3f) / 2f
            val corner = CornerRadius(barW / 2f, barW / 2f)
            val h0 = size.height * 0.36f
            val h1 = size.height * 0.62f
            val h2 = size.height * 0.44f
            drawRoundRect(barColor, Offset(0f, size.height - h0), Size(barW, h0), corner)
            drawRoundRect(barColor, Offset(barW + gap, size.height - h1), Size(barW, h1), corner)
            drawRoundRect(barColor, Offset(2f * (barW + gap), size.height - h2), Size(barW, h2), corner)
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "QueueEqPulse")
    val bar0State = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 460, easing = M3Motion.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "QueueEqBar0"
    )
    val bar1State = transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 390, easing = M3Motion.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "QueueEqBar1"
    )
    val bar2State = transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = M3Motion.Standard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "QueueEqBar2"
    )

    Canvas(modifier = modifier) {
        val barW = size.width * 0.22f
        val gap = (size.width - barW * 3f) / 2f
        val corner = CornerRadius(barW / 2f, barW / 2f)
        val h0 = size.height * bar0State.value
        val h1 = size.height * bar1State.value
        val h2 = size.height * bar2State.value
        drawRoundRect(barColor, Offset(0f, size.height - h0), Size(barW, h0), corner)
        drawRoundRect(barColor, Offset(barW + gap, size.height - h1), Size(barW, h1), corner)
        drawRoundRect(barColor, Offset(2f * (barW + gap), size.height - h2), Size(barW, h2), corner)
    }
}
