/**
 * NowPlayingSheet — Full-screen playback controller, adaptive portrait/landscape layout, and queue overlay.
 *
 * @remarks
 * Queue composition is deferred past the sheet's slide-in transition and animated solely via GPU
 * translations (`translationY`/`translationX`) rather than `alpha` fades so opening the player
 * never drops frames or allocates `saveLayerAlpha` render targets. When hidden or fully covered by
 * the queue overlay, background layers shift offscreen and freeze flow collection to prevent
 * wasted marquee ticks and seekbar recompositions.
 *
 * @see MiniPlayer
 */
package com.quandot.musicplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.R
import com.quandot.musicplayer.audio.UsbDacManager
import com.quandot.musicplayer.audio.UsbDacStatus
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.data.LyricsHelper
import com.quandot.musicplayer.data.toAlbumArtRequest
import com.quandot.musicplayer.service.PlaybackService
import com.quandot.musicplayer.ui.AudioOutputUiState
import com.quandot.musicplayer.ui.MusicViewModel
import com.quandot.musicplayer.ui.buildAudioOutputUiState
import com.quandot.musicplayer.util.localizedAlbumName
import com.quandot.musicplayer.util.localizedArtistName

import com.quandot.musicplayer.model.NowPlayingBlendStyle
import com.quandot.musicplayer.model.NowPlayingSeekbarStyle
import com.quandot.musicplayer.model.NowPlayingStyle
import com.quandot.musicplayer.model.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import com.quandot.musicplayer.ui.theme.ArtworkEdgeColorSampler
import com.quandot.musicplayer.ui.theme.ArtworkEdgePalette
import com.quandot.musicplayer.ui.theme.LocalIsUiForeground
import com.quandot.musicplayer.ui.theme.M3Motion
import com.quandot.musicplayer.ui.theme.MaterialYouColorEngine
import com.quandot.musicplayer.ui.theme.PredictiveBackEffect
import com.quandot.musicplayer.ui.theme.predictiveBackInline
import com.quandot.musicplayer.ui.theme.rememberAdaptiveLayoutInfo
import com.quandot.musicplayer.ui.theme.rememberPredictiveBackState
import com.quandot.musicplayer.ui.theme.systemSafeInsets

// ═══ Constants ═══
private const val ART_PREFETCH_DELAY_MS = 80L
private const val DISMISS_THRESHOLD_DP = 110
private const val MARQUEE_REPEAT_DELAY_MS = 2000
private const val MARQUEE_INITIAL_DELAY_MS = 1200
private const val FAVORITE_HEART_HEX = 0xFFF5576CL
private const val AMBIENT_LUMINANCE_THRESHOLD = 0.45
private const val LANDSCAPE_VERT_WASH_ALPHA = 0.40f
private const val LANDSCAPE_VERT_TOP_STOP = 0.36f
private const val LANDSCAPE_VERT_BOTTOM_STOP = 0.64f

private fun sampleLandscapeBackdropArgb(
    edges: ArtworkEdgePalette,
    xFraction: Float,
    yFraction: Float
): Int {
    val horizBase = when {
        xFraction <= 0.42f -> ColorUtils.blendARGB(
            edges.leftArgb,
            edges.unifiedArgb,
            (xFraction / 0.42f).coerceIn(0f, 1f)
        )
        xFraction <= 0.72f -> ColorUtils.blendARGB(
            edges.unifiedArgb,
            edges.rightArgb,
            ((xFraction - 0.42f) / 0.30f).coerceIn(0f, 1f)
        )
        else -> edges.rightArgb
    }
    val vertOverlayAlpha = when {
        yFraction < LANDSCAPE_VERT_TOP_STOP ->
            ((LANDSCAPE_VERT_TOP_STOP - yFraction) / LANDSCAPE_VERT_TOP_STOP).coerceIn(0f, 1f) * LANDSCAPE_VERT_WASH_ALPHA
        yFraction > LANDSCAPE_VERT_BOTTOM_STOP ->
            ((yFraction - LANDSCAPE_VERT_BOTTOM_STOP) / (1f - LANDSCAPE_VERT_BOTTOM_STOP)).coerceIn(0f, 1f) * LANDSCAPE_VERT_WASH_ALPHA
        else -> 0f
    }
    val blended = if (vertOverlayAlpha > 0.001f) {
        val overlayColor = if (yFraction < LANDSCAPE_VERT_TOP_STOP) edges.topArgb else edges.bottomArgb
        ColorUtils.blendARGB(horizBase, overlayColor, vertOverlayAlpha)
    } else {
        horizBase
    }
    // ColorUtils.blendARGB truncates float alpha (255*(1-r)+255*r = 254.99998 -> 0xFE),
    // which causes ColorUtils.calculateContrast to throw IllegalArgumentException unless forced opaque.
    return blended or (0xFF shl 24)
}

private fun chooseMonochromeInkForRegion(bgTopArgb: Int, bgBottomArgb: Int): Pair<Color, Color> {
    val darkInkArgb = 0xFF141416.toInt()
    val opaqueTop = bgTopArgb or (0xFF shl 24)
    val opaqueBottom = bgBottomArgb or (0xFF shl 24)
    val whiteMinContrast = minOf(
        ColorUtils.calculateContrast(android.graphics.Color.WHITE, opaqueTop),
        ColorUtils.calculateContrast(android.graphics.Color.WHITE, opaqueBottom)
    )
    val darkMinContrast = minOf(
        ColorUtils.calculateContrast(darkInkArgb, opaqueTop),
        ColorUtils.calculateContrast(darkInkArgb, opaqueBottom)
    )
    val useDarkInk = darkMinContrast > whiteMinContrast * 1.12
    val primaryInk = if (useDarkInk) Color(darkInkArgb) else Color.White
    val secondaryInk = primaryInk.copy(alpha = if (useDarkInk) 0.76f else 0.82f)
    return primaryInk to secondaryInk
}

private enum class PortraitCenterSlot {
    COVER,
    LYRICS,
    QUEUE
}

@Immutable
private data class ModernArtworkColors(
    val edges: ArtworkEdgePalette?,
    val seedArgb: Int?
)

private object ModernArtworkColorCache {
    private const val MAX_ENTRIES = 160
    private val map = object : LinkedHashMap<String, ModernArtworkColors>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ModernArtworkColors>?): Boolean =
            size > MAX_ENTRIES
    }

    fun get(key: String): ModernArtworkColors? = synchronized(map) { map[key] }

    fun put(key: String, value: ModernArtworkColors) {
        synchronized(map) { map[key] = value }
    }
}

private fun buildModernColorKey(cacheVersion: Long, song: Song, albumKey: String): String =
    "${cacheVersion}_${albumKey}_${song.albumGradientColorStart}_${song.albumGradientColorEnd}"

private suspend fun resolveAndCacheModernColors(
    artManager: AlbumArtManager,
    song: Song,
    cacheVersion: Long
): ModernArtworkColors {
    val request = song.toAlbumArtRequest()
    val key = buildModernColorKey(cacheVersion, song, request.albumKey)
    ModernArtworkColorCache.get(key)?.let { return it }
    val extracted = withContext(Dispatchers.IO) {
        val bitmap = artManager.loadBitmap(request, ArtResolutionBucket.THUMBNAIL)
        if (bitmap != null) {
            ModernArtworkColors(
                edges = ArtworkEdgeColorSampler.extractFourEdgeColors(bitmap),
                seedArgb = MaterialYouColorEngine.extractSeedColorArgbCached(
                    cacheKey = "${cacheVersion}_${request.albumKey}",
                    bitmap = bitmap
                )
            )
        } else {
            ModernArtworkColors(
                edges = ArtworkEdgeColorSampler.fromFallbackGradient(
                    song.albumGradientColorStart,
                    song.albumGradientColorEnd
                ),
                seedArgb = MaterialYouColorEngine.extractFallbackGradientSeedArgb(
                    song.albumGradientColorStart,
                    song.albumGradientColorEnd
                )
            )
        }
    }
    ModernArtworkColorCache.put(key, extracted)
    return extracted
}

@Immutable
internal data class NowPlayingPalette(
    val primaryContentColor: Color,
    val secondaryContentColor: Color,
    val accentColor: Color,
    val onAccentColor: Color,
    val inactiveTrackColor: Color,
    val chipContainerColor: Color,
    val chipContentColor: Color,
    val activePillContainerColor: Color
)

// ═══ Public Composable ═══
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    song: Song,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    currentPositionMsFlow: StateFlow<Long>,
    isLiked: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    queue: List<Song>,
    onProgressChange: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit = {},
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit,
    onSelectSongFromQueue: (Song) -> Unit,
    onReorderQueue: (Int, Int) -> Unit = { _, _ -> },
    onRemoveFromQueue: (Song) -> Unit = {},
    onDismissRequest: () -> Unit,
    onDragDismissFractionChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    queueSource: String = "Mặc định",
    audioSpecsFlow: StateFlow<String>? = null,
    viewModel: MusicViewModel? = null,
    onNavigateToAlbum: ((Long) -> Unit)? = null,
    onNavigateToArtist: ((String) -> Unit)? = null,
    onSystemBarsDarkOverrideChanged: (Boolean?, Boolean?) -> Unit = { _, _ -> },
    trackDirection: Int = 1,
    isSheetActive: Boolean = true
) {
    val currentAudioSpecs = audioSpecsFlow?.collectAsStateWithLifecycle()?.value
    val displaySpecs = if (!currentAudioSpecs.isNullOrBlank()) {
        currentAudioSpecs
    } else {
        remember(song.id) { AudioSpecsHelper.getFastFallbackSpecs(song) }
    }

    val layout = rememberAdaptiveLayoutInfo()
    val isTabletMode = layout.isTabletOrExpanded
    val isCompactHeight = layout.isCompactHeight

    var showQueueSheet by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showSongActionBottomSheet by remember { mutableStateOf(false) }
    var showUsbDacStatsSheet by remember { mutableStateOf(false) }
    val lyricsBackState = rememberPredictiveBackState()
    val queueBackState = rememberPredictiveBackState()

    // Vertical swipe-to-dismiss gesture with spring snap-back below threshold
    val dragOffsetY = remember { Animatable(0f) }
    val dragScope = rememberCoroutineScope()

    LaunchedEffect(isSheetActive) {
        if (!isSheetActive) {
            showQueueSheet = false
            showLyrics = false
            showSleepTimerDialog = false
            showUsbDacStatsSheet = false
            lyricsBackState.reset()
            queueBackState.reset()
            dragOffsetY.snapTo(0f)
            onDragDismissFractionChanged(0f)
        }
    }
    LaunchedEffect(showLyrics) {
        if (showLyrics) {
            lyricsBackState.reset()
        } else {
            delay(240L)
            lyricsBackState.reset()
        }
    }
    LaunchedEffect(showQueueSheet) {
        if (showQueueSheet) {
            queueBackState.reset()
        } else {
            delay(240L)
            queueBackState.reset()
        }
    }

    // Preload high/ultra-res covers for immediate nextTrack & previousTrack into L1 RAM for 0 ms track skips
    val nowPlayingStyle = viewModel?.nowPlayingStyle?.collectAsStateWithLifecycle()?.value ?: NowPlayingStyle.MODERN
    val nowPlayingBlendStyle = viewModel?.nowPlayingBlendStyle?.collectAsStateWithLifecycle()?.value ?: NowPlayingBlendStyle.LINEAR
    val nowPlayingSeekbarStyle = viewModel?.nowPlayingSeekbarStyle?.collectAsStateWithLifecycle()?.value ?: NowPlayingSeekbarStyle.SLIDER
    val isModernStyle = nowPlayingStyle == NowPlayingStyle.MODERN
    val appContext = LocalContext.current.applicationContext
    val artManager = LocalAlbumArtManager.current ?: remember(appContext) { AlbumArtManager.getInstance(appContext) }
    val hoistedArtCacheVersion = LocalAlbumArtCacheVersion.current
    val artCacheVersion = if (hoistedArtCacheVersion != null) {
        hoistedArtCacheVersion.value
    } else {
        artManager.cacheVersion.collectAsStateWithLifecycle().value
    }

    // Warm the current track's lyrics cache while Now Playing is open so opening lyrics animates immediately
    LaunchedEffect(isSheetActive, song.id, song.path) {
        if (isSheetActive && LyricsHelper.peekCachedLyrics(song.id) == null) {
            delay(180L)
            withContext(Dispatchers.IO) {
                LyricsHelper.loadLyrics(appContext, song)
            }
        }
    }

    val lyricsBackdropProgressState = animateFloatAsState(
        targetValue = if (showLyrics || showQueueSheet) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = M3Motion.EmphasizedDecelerate),
        label = "LyricsBackdropProgress"
    )
    LaunchedEffect(isSheetActive, isModernStyle, song.id, song.instanceId, queue, repeatMode, artCacheVersion) {
        if (isSheetActive && queue.isNotEmpty()) {
            delay(ART_PREFETCH_DELAY_MS)
            val idx = if (song.instanceId > 0L) {
                queue.indexOfFirst { it.instanceId == song.instanceId }
            } else {
                queue.indexOfFirst { it.id == song.id }
            }.takeIf { it >= 0 } ?: queue.indexOfFirst { it.id == song.id }
            if (idx >= 0) {
                artManager.preloadQueueNeighbors(
                    queue = queue,
                    currentIndex = idx,
                    repeatMode = repeatMode,
                    bucket = if (isModernStyle) ArtResolutionBucket.ULTRA_RES else ArtResolutionBucket.HIGH_RES
                )
                if (isModernStyle && queue.size > 1) {
                    val offsets = intArrayOf(1, -1, 2, -2)
                    val neighborSongs = LinkedHashMap<String, Song>(4)
                    for (offset in offsets) {
                        val rawIdx = idx + offset
                        val targetIdx = when {
                            rawIdx in queue.indices -> rawIdx
                            repeatMode == Player.REPEAT_MODE_ALL -> ((rawIdx % queue.size) + queue.size) % queue.size
                            else -> -1
                        }
                        if (targetIdx in queue.indices && targetIdx != idx) {
                            val neighbor = queue[targetIdx]
                            val req = neighbor.toAlbumArtRequest()
                            val nKey = buildModernColorKey(artCacheVersion, neighbor, req.albumKey)
                            if (ModernArtworkColorCache.get(nKey) == null) {
                                neighborSongs.putIfAbsent(nKey, neighbor)
                            }
                        }
                    }
                    for (neighbor in neighborSongs.values) {
                        resolveAndCacheModernColors(artManager, neighbor, artCacheVersion)
                    }
                }
            }
        }
    }
    val density = LocalDensity.current
    val dismissThresholdPx = remember(density) { with(density) { DISMISS_THRESHOLD_DP.dp.toPx() } }

    val isUiForeground = LocalIsUiForeground.current.value
    val marqueeIterations = if (isSheetActive && isUiForeground && isPlaying) Int.MAX_VALUE else 0
    val lyricsFontSize = viewModel?.lyricsFontSize?.collectAsStateWithLifecycle()?.value ?: "medium"
    val lyricsAlignment = viewModel?.lyricsAlignment?.collectAsStateWithLifecycle()?.value ?: "left"
    val lyricsAutoScroll = viewModel?.lyricsAutoScroll?.collectAsStateWithLifecycle()?.value ?: true
    val isPlayerVisible = isSheetActive
    val lyricsDelayNormalMs = viewModel?.lyricsDelayNormalMs?.collectAsStateWithLifecycle()?.value ?: 0
    val lyricsDelayBluetoothMs = viewModel?.lyricsDelayBluetoothMs?.collectAsStateWithLifecycle()?.value ?: 0
    val lyricsDelayBitPerfectMs = viewModel?.lyricsDelayBitPerfectMs?.collectAsStateWithLifecycle()?.value ?: 0
    val audioOutputState by remember(isPlayerVisible, viewModel) {
        if (viewModel != null) {
            if (isPlayerVisible) viewModel.audioOutputState else MutableStateFlow(viewModel.audioOutputState.value)
        } else {
            MutableStateFlow(buildAudioOutputUiState(displaySpecs, UsbDacManager.state.value))
        }
    }.collectAsStateWithLifecycle()
    val isBtForLyrics by remember(isPlayerVisible) {
        if (isPlayerVisible) PlaybackService.isBluetoothOutputActive else MutableStateFlow(PlaybackService.isBluetoothOutputActive.value)
    }.collectAsStateWithLifecycle()
    val activeLyricsDelayMs = when {
        audioOutputState.directEnabled &&
            (audioOutputState.dacStatus == UsbDacStatus.READY ||
                audioOutputState.dacStatus == UsbDacStatus.PAUSED ||
                audioOutputState.dacStatus == UsbDacStatus.STREAMING) -> lyricsDelayBitPerfectMs
        isBtForLyrics -> lyricsDelayBluetoothMs
        else -> lyricsDelayNormalMs
    }
    val sleepTimerMinutes by remember(isPlayerVisible, viewModel) {
        if (viewModel == null) MutableStateFlow(0)
        else if (isPlayerVisible) viewModel.sleepTimerMinutes
        else MutableStateFlow(viewModel.sleepTimerMinutes.value)
    }.collectAsStateWithLifecycle()
    val sleepTimerRemainingSecFlow = viewModel?.sleepTimerRemainingSec
    val onOpenSongAlbum: (() -> Unit)? = if (onNavigateToAlbum != null && song.albumId > 0L) {
        {
            onDismissRequest()
            onNavigateToAlbum.invoke(song.albumId)
        }
    } else {
        null
    }

    val onSeekToMs: (Long) -> Unit = { targetMs ->
        if (viewModel != null) {
            viewModel.seekToPositionMs(targetMs)
        } else if (song.duration > 0L) {
            onProgressChange((targetMs.toFloat() / song.duration.toFloat()).coerceIn(0f, 1f))
        }
    }
    val verticalDismissDragModifier = Modifier.pointerInput(dismissThresholdPx) {
        val snapBackSpec = tween<Float>(
            durationMillis = 120,
            easing = M3Motion.StandardDecelerate
        )
        fun snapBack() {
            dragScope.launch {
                dragOffsetY.animateTo(targetValue = 0f, animationSpec = snapBackSpec)
                onDragDismissFractionChanged(0f)
            }
        }
        detectVerticalDragGestures(
            onDragEnd = {
                if (dragOffsetY.value > dismissThresholdPx) {
                    onDismissRequest()
                } else {
                    snapBack()
                }
            },
            onDragCancel = { snapBack() },
            onVerticalDrag = { change, dragAmount ->
                if (!change.isConsumed && (dragAmount > 0f || dragOffsetY.value > 0f)) {
                    change.consume()
                    val next = (dragOffsetY.value + dragAmount * 0.9f).coerceAtLeast(0f)
                    onDragDismissFractionChanged((next / (dismissThresholdPx * 2.5f)).coerceIn(0f, 1f))
                    dragScope.launch { dragOffsetY.snapTo(next) }
                }
            }
        )
    }

    val dynamicColor = viewModel?.dynamicColor?.collectAsStateWithLifecycle()?.value ?: true
    val songDynamicColor = viewModel?.songDynamicColor?.collectAsStateWithLifecycle()?.value ?: true
    val viewModelSongSeedArgb = viewModel?.songSeedColorArgb?.collectAsStateWithLifecycle()?.value
    val songArtRequest = remember(
        song.id,
        song.albumId,
        song.album,
        song.albumArtist,
        song.artist,
        song.contentUri,
        song.albumArtUri,
        song.path
    ) {
        song.toAlbumArtRequest()
    }
    val songColorKey = buildModernColorKey(artCacheVersion, song, songArtRequest.albumKey)
    val syncCachedColors = remember(songColorKey) {
        ModernArtworkColorCache.get(songColorKey) ?: if (artManager.isKnownMissing(songArtRequest.albumKey)) {
            ModernArtworkColors(
                edges = ArtworkEdgeColorSampler.fromFallbackGradient(
                    song.albumGradientColorStart,
                    song.albumGradientColorEnd
                ),
                seedArgb = MaterialYouColorEngine.extractFallbackGradientSeedArgb(
                    song.albumGradientColorStart,
                    song.albumGradientColorEnd
                )
            ).also { ModernArtworkColorCache.put(songColorKey, it) }
        } else {
            null
        }
    }
    var asyncModernColors by remember { mutableStateOf<Pair<String, ModernArtworkColors>?>(null) }
    var lastValidModernColors by remember { mutableStateOf<ModernArtworkColors?>(null) }

    val extractedModernColors = syncCachedColors
        ?: asyncModernColors?.takeIf { it.first == songColorKey }?.second
        ?: lastValidModernColors

    SideEffect {
        if (syncCachedColors != null && lastValidModernColors !== syncCachedColors) {
            lastValidModernColors = syncCachedColors
        }
    }

    LaunchedEffect(isModernStyle, isSheetActive, songColorKey) {
        if (!isModernStyle || !isSheetActive) return@LaunchedEffect
        val cached = ModernArtworkColorCache.get(songColorKey)
        if (cached != null) {
            asyncModernColors = songColorKey to cached
            lastValidModernColors = cached
            return@LaunchedEffect
        }
        val extracted = resolveAndCacheModernColors(artManager, song, artCacheVersion)
        asyncModernColors = songColorKey to extracted
        lastValidModernColors = extracted
    }

    val colorScheme = MaterialTheme.colorScheme
    val edgePalette = extractedModernColors?.edges
    val hasAmbientColor = isModernStyle && edgePalette != null

    val topEdgeArgb = edgePalette?.topArgb
    val bottomEdgeArgb = edgePalette?.bottomArgb
    val controlsBgArgb = when {
        edgePalette == null -> null
        layout.isLandscape -> edgePalette.rightArgb
        else -> edgePalette.bottomArgb
    }
    val lyricsBgArgb = edgePalette?.unifiedArgb

    val isTopStatusBarDark = remember(hasAmbientColor, topEdgeArgb) {
        if (!hasAmbientColor || topEdgeArgb == null) null
        else ColorUtils.calculateLuminance(topEdgeArgb) <= AMBIENT_LUMINANCE_THRESHOLD
    }
    val isBottomNavBarDark = remember(hasAmbientColor, bottomEdgeArgb) {
        if (!hasAmbientColor || bottomEdgeArgb == null) null
        else ColorUtils.calculateLuminance(bottomEdgeArgb) <= AMBIENT_LUMINANCE_THRESHOLD
    }
    val lastSysBarOverrideRef = remember { arrayOfNulls<Any>(5) }
    SideEffect {
        val activeTopOverride = if (isSheetActive && isModernStyle && isTopStatusBarDark != null) isTopStatusBarDark else null
        val activeBottomOverride = if (isSheetActive && isModernStyle && isTopStatusBarDark != null) isBottomNavBarDark else null
        val initialized = lastSysBarOverrideRef[0] == true
        if (!initialized ||
            lastSysBarOverrideRef[1] != activeTopOverride ||
            lastSysBarOverrideRef[2] != activeBottomOverride
        ) {
            lastSysBarOverrideRef[0] = true
            lastSysBarOverrideRef[1] = activeTopOverride
            lastSysBarOverrideRef[2] = activeBottomOverride
            onSystemBarsDarkOverrideChanged(activeTopOverride, activeBottomOverride)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onSystemBarsDarkOverrideChanged(null, null)
        }
    }

    val palette = remember(
        isModernStyle,
        hasAmbientColor,
        controlsBgArgb,
        extractedModernColors,
        viewModelSongSeedArgb,
        songDynamicColor,
        dynamicColor,
        colorScheme,
        appContext
    ) {
        if (!isModernStyle || !hasAmbientColor || controlsBgArgb == null) {
            NowPlayingPalette(
                primaryContentColor = colorScheme.onSurface,
                secondaryContentColor = colorScheme.onSurfaceVariant,
                accentColor = colorScheme.primary,
                onAccentColor = colorScheme.onPrimary,
                inactiveTrackColor = colorScheme.surfaceVariant,
                chipContainerColor = colorScheme.secondaryContainer.copy(alpha = 0.7f),
                chipContentColor = colorScheme.onSecondaryContainer,
                activePillContainerColor = colorScheme.primaryContainer.copy(alpha = 0.55f)
            )
        } else {
            val isLightBg = ColorUtils.calculateLuminance(controlsBgArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val seedArgb = if (songDynamicColor) {
                extractedModernColors?.seedArgb ?: viewModelSongSeedArgb
            } else {
                null
            }
            val controlScheme = MaterialYouColorEngine.buildModernControlColorScheme(
                seedColorArgb = seedArgb,
                backgroundArgb = controlsBgArgb,
                useWallpaperDynamic = dynamicColor,
                context = appContext
            )
            val primaryInk = if (isLightBg) Color(0xFF141416) else Color.White
            val secondaryInk = primaryInk.copy(alpha = if (isLightBg) 0.70f else 0.74f)
            NowPlayingPalette(
                primaryContentColor = primaryInk,
                secondaryContentColor = secondaryInk,
                accentColor = controlScheme.primary,
                onAccentColor = controlScheme.onPrimary,
                inactiveTrackColor = primaryInk.copy(alpha = if (isLightBg) 0.18f else 0.24f),
                chipContainerColor = controlScheme.secondaryContainer.copy(alpha = 0.72f),
                chipContentColor = controlScheme.onSecondaryContainer,
                activePillContainerColor = controlScheme.primary.copy(alpha = if (isLightBg) 0.14f else 0.22f)
            )
        }
    }
    val topBarPalette = remember(palette, isModernStyle, hasAmbientColor, topEdgeArgb) {
        if (!isModernStyle || !hasAmbientColor || topEdgeArgb == null) {
            palette
        } else {
            val isTopLight = ColorUtils.calculateLuminance(topEdgeArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val topPrimaryInk = if (isTopLight) Color(0xFF141416) else Color.White
            palette.copy(
                primaryContentColor = topPrimaryInk,
                secondaryContentColor = topPrimaryInk.copy(alpha = if (isTopLight) 0.70f else 0.74f)
            )
        }
    }
    val landscapeHudColors = remember(
        isModernStyle,
        hasAmbientColor,
        controlsBgArgb,
        extractedModernColors,
        viewModelSongSeedArgb,
        songDynamicColor,
        dynamicColor,
        colorScheme,
        palette,
        appContext
    ) {
        if (!isModernStyle || !hasAmbientColor || controlsBgArgb == null) {
            LandscapeHudColors(
                containerColor = colorScheme.surfaceContainerHigh,
                borderColor = colorScheme.outlineVariant,
                palette = palette
            )
        } else {
            val isLightHud = ColorUtils.calculateLuminance(controlsBgArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val seedArgb = if (songDynamicColor) {
                extractedModernColors?.seedArgb ?: viewModelSongSeedArgb
            } else {
                null
            }
            val refBgArgb = if (isLightHud) 0xFFF5F3F7.toInt() else 0xFF1A1720.toInt()
            val hudScheme = MaterialYouColorEngine.buildModernControlColorScheme(
                seedColorArgb = seedArgb,
                backgroundArgb = refBgArgb,
                useWallpaperDynamic = dynamicColor,
                context = appContext
            )
            val hudOpaqueBase = if (isLightHud) hudScheme.surfaceContainerLow else hudScheme.surfaceContainer
            val hudOpaqueArgb = hudOpaqueBase.toArgb() or (0xFF shl 24)
            val hudPrimaryInk = if (isLightHud) Color(0xFF141416) else Color.White
            val hudSecondaryInk = hudPrimaryInk.copy(alpha = if (isLightHud) 0.72f else 0.78f)
            val rawAccent = hudScheme.primary
            val accentContrast = ColorUtils.calculateContrast(rawAccent.toArgb(), hudOpaqueArgb)
            val safeAccent = if (accentContrast >= 4.5) rawAccent else hudPrimaryInk
            val safeOnAccent = if (accentContrast >= 4.5) hudScheme.onPrimary else hudOpaqueBase
            LandscapeHudColors(
                containerColor = hudOpaqueBase.copy(alpha = if (isLightHud) 0.92f else 0.88f),
                borderColor = hudPrimaryInk.copy(alpha = if (isLightHud) 0.14f else 0.16f),
                palette = NowPlayingPalette(
                    primaryContentColor = hudPrimaryInk,
                    secondaryContentColor = hudSecondaryInk,
                    accentColor = safeAccent,
                    onAccentColor = safeOnAccent,
                    inactiveTrackColor = hudPrimaryInk.copy(alpha = if (isLightHud) 0.20f else 0.28f),
                    chipContainerColor = hudScheme.secondaryContainer.copy(alpha = 0.85f),
                    chipContentColor = hudScheme.onSecondaryContainer,
                    activePillContainerColor = safeAccent.copy(alpha = if (isLightHud) 0.16f else 0.24f)
                )
            )
        }
    }
    val landscapeHeroInk = remember(palette, isModernStyle, edgePalette) {
        if (!isModernStyle || edgePalette == null) {
            LandscapeHeroInk(
                focusPrimary = palette.primaryContentColor,
                focusSecondary = palette.secondaryContentColor,
                splitPrimary = palette.primaryContentColor,
                splitSecondary = palette.secondaryContentColor
            )
        } else {
            val focusTitleBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.50f, yFraction = 0.78f)
            val focusArtistBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.50f, yFraction = 0.88f)
            val (focusPrimary, focusSecondary) = chooseMonochromeInkForRegion(focusTitleBg, focusArtistBg)

            val splitTitleBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.20f, yFraction = 0.78f)
            val splitArtistBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.20f, yFraction = 0.88f)
            val (splitPrimary, splitSecondary) = chooseMonochromeInkForRegion(splitTitleBg, splitArtistBg)

            LandscapeHeroInk(
                focusPrimary = focusPrimary,
                focusSecondary = focusSecondary,
                splitPrimary = splitPrimary,
                splitSecondary = splitSecondary
            )
        }
    }
    val (lyricsPrimaryColor, lyricsSecondaryColor) = remember(
        palette,
        isModernStyle,
        lyricsBgArgb,
        edgePalette,
        layout.isLandscape
    ) {
        if (!isModernStyle || edgePalette == null || lyricsBgArgb == null) {
            palette.primaryContentColor to palette.secondaryContentColor
        } else if (layout.isLandscape) {
            val rightTopBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.72f, yFraction = 0.30f)
            val rightBotBg = sampleLandscapeBackdropArgb(edgePalette, xFraction = 0.72f, yFraction = 0.72f)
            chooseMonochromeInkForRegion(rightTopBg, rightBotBg)
        } else {
            val isLyricsLight = ColorUtils.calculateLuminance(lyricsBgArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val primary = if (isLyricsLight) Color(0xFF141416) else Color.White
            primary to primary.copy(alpha = if (isLyricsLight) 0.70f else 0.74f)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .then(verticalDismissDragModifier)
            .graphicsLayer {
                translationY = dragOffsetY.value
            },
        color = if (isModernStyle) Color.Transparent else colorScheme.surface
    ) {
        val safeInsets = systemSafeInsets()
        val rootCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
        val coverSlotCoordsRef = remember { arrayOfNulls<LayoutCoordinates>(1) }
        var coverTopFraction by remember { mutableFloatStateOf(0.13f) }
        var coverBottomFraction by remember { mutableFloatStateOf(0.58f) }
        val updateCoverFractions: () -> Unit = {
            val rootCoords = rootCoordsRef[0]
            val slotCoords = coverSlotCoordsRef[0]
            if (rootCoords != null && slotCoords != null && rootCoords.isAttached && slotCoords.isAttached) {
                val rootHeight = rootCoords.size.height.toFloat()
                if (rootHeight > 1f) {
                    // Measure the untransformed cover slot in root-local space so parent sheet
                    // translations and AnimatedContent enter/exit scales never cause gradient drift.
                    val slotBounds = rootCoords.localBoundingBoxOf(slotCoords, clipBounds = false)
                    val coverSide = minOf(slotBounds.width, slotBounds.height)
                    val coverTop = slotBounds.top + (slotBounds.height - coverSide) * 0.5f
                    val coverBottom = coverTop + coverSide
                    val nextTop = (coverTop / rootHeight).coerceIn(0.05f, 0.35f)
                    val nextBottom = (coverBottom / rootHeight).coerceIn(nextTop + 0.18f, 0.88f)
                    if (abs(nextTop - coverTopFraction) > 0.002f) {
                        coverTopFraction = nextTop
                    }
                    if (abs(nextBottom - coverBottomFraction) > 0.002f) {
                        coverBottomFraction = nextBottom
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPlaced { coords ->
                    rootCoordsRef[0] = coords
                    updateCoverFractions()
                }
                .then(
                    if (isModernStyle) {
                        if (edgePalette != null) {
                            val topColor = Color(edgePalette.topArgb)
                            val bottomColor = Color(edgePalette.bottomArgb)
                            val leftColor = Color(edgePalette.leftArgb)
                            val rightColor = Color(edgePalette.rightArgb)
                            val topLeftColor = Color(edgePalette.topLeftArgb)
                            val topRightColor = Color(edgePalette.topRightArgb)
                            val bottomLeftColor = Color(edgePalette.bottomLeftArgb)
                            val bottomRightColor = Color(edgePalette.bottomRightArgb)
                            val midLRColor = Color(edgePalette.horizontalMidArgb)
                            val unifiedColor = Color(edgePalette.unifiedArgb)
                            Modifier.drawWithCache {
                                if (layout.isLandscape) {
                                    // 4-edge 2D blend tailored for landscape: left cover pane -> right controls pane
                                    val horizBrush = Brush.horizontalGradient(
                                        0.0f to leftColor,
                                        0.42f to unifiedColor,
                                        0.72f to rightColor,
                                        1.0f to rightColor
                                    )
                                    val vertBrush = Brush.verticalGradient(
                                        0.0f to topColor.copy(alpha = LANDSCAPE_VERT_WASH_ALPHA),
                                        LANDSCAPE_VERT_TOP_STOP to Color.Transparent,
                                        LANDSCAPE_VERT_BOTTOM_STOP to Color.Transparent,
                                        1.0f to bottomColor.copy(alpha = LANDSCAPE_VERT_WASH_ALPHA)
                                    )
                                    onDrawBehind {
                                        drawRect(brush = horizBrush)
                                        drawRect(brush = vertBrush)
                                    }
                                } else {
                                    // Portrait 4-edge blend aligned to exact local cover bounds with
                                    // cached Brushes; lyricsBackdropProgressState is read inside onDrawBehind.
                                    val cTop = coverTopFraction.coerceIn(0.05f, 0.35f)
                                    val cBot = coverBottomFraction.coerceIn(cTop + 0.18f, 0.85f)
                                    val cSpan = cBot - cTop
                                    val topHoldStop = (cTop + cSpan * 0.16f).coerceIn(0.06f, 0.45f)
                                    val midStop = (cTop + cSpan * 0.50f).coerceIn(topHoldStop + 0.05f, 0.75f)
                                    val bottomHoldStop = (cBot - cSpan * 0.16f).coerceIn(midStop + 0.05f, 0.92f)

                                    val portraitBaseBrush = Brush.verticalGradient(
                                        0.0f to topColor,
                                        topHoldStop to topColor,
                                        midStop to midLRColor,
                                        bottomHoldStop to bottomColor,
                                        1.0f to bottomColor
                                    )
                                    val cornerRadius = (size.width * 0.72f).coerceAtLeast(1f)
                                    val topAnchorY = size.height * (cTop + cSpan * 0.06f)
                                    val bottomAnchorY = size.height * (cBot - cSpan * 0.06f)
                                    val topLeftBrush = Brush.radialGradient(
                                        colors = listOf(topLeftColor.copy(alpha = 0.65f), Color.Transparent),
                                        center = Offset(0f, topAnchorY),
                                        radius = cornerRadius
                                    )
                                    val topRightBrush = Brush.radialGradient(
                                        colors = listOf(topRightColor.copy(alpha = 0.65f), Color.Transparent),
                                        center = Offset(size.width, topAnchorY),
                                        radius = cornerRadius
                                    )
                                    val bottomLeftBrush = Brush.radialGradient(
                                        colors = listOf(bottomLeftColor.copy(alpha = 0.65f), Color.Transparent),
                                        center = Offset(0f, bottomAnchorY),
                                        radius = cornerRadius
                                    )
                                    val bottomRightBrush = Brush.radialGradient(
                                        colors = listOf(bottomRightColor.copy(alpha = 0.65f), Color.Transparent),
                                        center = Offset(size.width, bottomAnchorY),
                                        radius = cornerRadius
                                    )
                                    val lyricsBrush = Brush.verticalGradient(
                                        0.0f to topColor,
                                        0.18f to unifiedColor,
                                        0.62f to unifiedColor,
                                        0.78f to bottomColor,
                                        1.0f to bottomColor
                                    )
                                    onDrawBehind {
                                        val lyricsBlend = lyricsBackdropProgressState.value.coerceIn(0f, 1f)
                                        if (lyricsBlend < 0.999f) {
                                            drawRect(brush = portraitBaseBrush)
                                            drawRect(brush = topLeftBrush)
                                            drawRect(brush = topRightBrush)
                                            drawRect(brush = bottomLeftBrush)
                                            drawRect(brush = bottomRightBrush)
                                        }
                                        if (lyricsBlend > 0.001f) {
                                            drawRect(brush = lyricsBrush, alpha = lyricsBlend)
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier.background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        colorScheme.surfaceContainerHigh,
                                        colorScheme.surface
                                    )
                                )
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
        if (layout.isLandscape || isTabletMode) {
            val isLightAmbientBg = isModernStyle && controlsBgArgb != null &&
                ColorUtils.calculateLuminance(controlsBgArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val onToggleInlineLyrics: () -> Unit = {
                val next = !showLyrics
                showLyrics = next
                if (next) showQueueSheet = false
            }
            val onToggleInlineQueue: () -> Unit = {
                val next = !showQueueSheet
                showQueueSheet = next
                if (next) showLyrics = false
            }
            val onInlineMoreClick: (() -> Unit)? = if (viewModel != null) {
                { showSongActionBottomSheet = true }
            } else {
                null
            }
            val onInlineSleepTimer: (() -> Unit)? = if (viewModel != null) {
                { showSleepTimerDialog = true }
            } else {
                null
            }

            if (layout.isLandscape) {
                TabletLandscapeNowPlaying(
                    song = song,
                    isPlaying = isPlaying,
                    progressFlow = progressFlow,
                    currentPositionMsFlow = currentPositionMsFlow,
                    isLiked = isLiked,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    queue = queue,
                    queueSource = queueSource,
                    displaySpecs = displaySpecs,
                    audioOutputState = audioOutputState,
                    isModernStyle = isModernStyle,
                    isLightAmbientBg = isLightAmbientBg,
                    isPhoneLandscape = !isTabletMode,
                    nowPlayingSeekbarStyle = nowPlayingSeekbarStyle,
                    showLyrics = showLyrics,
                    showQueue = showQueueSheet,
                    sleepTimerMinutes = sleepTimerMinutes,
                    sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                    lyricsFontSize = lyricsFontSize,
                    lyricsAlignment = lyricsAlignment,
                    lyricsAutoScroll = lyricsAutoScroll,
                    activeLyricsDelayMs = activeLyricsDelayMs,
                    marqueeIterations = marqueeIterations,
                    isSheetActive = isSheetActive,
                    palette = palette,
                    heroInk = landscapeHeroInk,
                    hudColors = landscapeHudColors,
                    lyricsPrimaryColor = lyricsPrimaryColor,
                    lyricsSecondaryColor = lyricsSecondaryColor,
                    lyricsBackState = lyricsBackState,
                    queueBackState = queueBackState,
                    viewModel = viewModel,
                    onProgressChange = onProgressChange,
                    onSeekToMs = onSeekToMs,
                    onTogglePlay = onTogglePlay,
                    onStopAndReset = onStopAndReset,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onToggleLike = onToggleLike,
                    onToggleShuffle = onToggleShuffle,
                    onToggleRepeat = onToggleRepeat,
                    onToggleLyrics = onToggleInlineLyrics,
                    onToggleQueue = onToggleInlineQueue,
                    onSelectSongFromQueue = onSelectSongFromQueue,
                    onReorderQueue = onReorderQueue,
                    onRemoveFromQueue = onRemoveFromQueue,
                    onShowSleepTimer = onInlineSleepTimer,
                    onPathBadgeClick = { showUsbDacStatsSheet = true },
                    onOpenSongAlbum = onOpenSongAlbum,
                    onMoreClick = onInlineMoreClick,
                    onDismissRequest = onDismissRequest
                )
            } else {
                TabletPortraitNowPlaying(
                    song = song,
                    isPlaying = isPlaying,
                    progressFlow = progressFlow,
                    currentPositionMsFlow = currentPositionMsFlow,
                    isLiked = isLiked,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    queue = queue,
                    queueSource = queueSource,
                    displaySpecs = displaySpecs,
                    audioOutputState = audioOutputState,
                    isModernStyle = isModernStyle,
                    isLightAmbientBg = isLightAmbientBg,
                    nowPlayingSeekbarStyle = nowPlayingSeekbarStyle,
                    showLyrics = showLyrics,
                    showQueue = showQueueSheet,
                    sleepTimerMinutes = sleepTimerMinutes,
                    sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                    lyricsFontSize = lyricsFontSize,
                    lyricsAlignment = lyricsAlignment,
                    lyricsAutoScroll = lyricsAutoScroll,
                    activeLyricsDelayMs = activeLyricsDelayMs,
                    marqueeIterations = marqueeIterations,
                    isSheetActive = isSheetActive,
                    palette = palette,
                    topBarPalette = topBarPalette,
                    lyricsPrimaryColor = lyricsPrimaryColor,
                    lyricsSecondaryColor = lyricsSecondaryColor,
                    lyricsBackState = lyricsBackState,
                    queueBackState = queueBackState,
                    viewModel = viewModel,
                    onProgressChange = onProgressChange,
                    onSeekToMs = onSeekToMs,
                    onTogglePlay = onTogglePlay,
                    onStopAndReset = onStopAndReset,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onToggleLike = onToggleLike,
                    onToggleShuffle = onToggleShuffle,
                    onToggleRepeat = onToggleRepeat,
                    onToggleLyrics = onToggleInlineLyrics,
                    onToggleQueue = onToggleInlineQueue,
                    onSelectSongFromQueue = onSelectSongFromQueue,
                    onReorderQueue = onReorderQueue,
                    onRemoveFromQueue = onRemoveFromQueue,
                    onShowSleepTimer = onInlineSleepTimer,
                    onPathBadgeClick = { showUsbDacStatsSheet = true },
                    onOpenSongAlbum = onOpenSongAlbum,
                    onMoreClick = onInlineMoreClick,
                    onDismissRequest = onDismissRequest
                )
            }
        } else {
            val horizontalPad = if (isCompactHeight) 20.dp else 24.dp
            val portraitQueueOuterPad = (horizontalPad - if (isCompactHeight) 16.dp else 18.dp).coerceAtLeast(4.dp)
            val activePortraitSlot = when {
                showLyrics -> PortraitCenterSlot.LYRICS
                showQueueSheet -> PortraitCenterSlot.QUEUE
                else -> PortraitCenterSlot.COVER
            }
            val isLightLyricsBg = isModernStyle && lyricsBgArgb != null &&
                ColorUtils.calculateLuminance(lyricsBgArgb) > AMBIENT_LUMINANCE_THRESHOLD
            val portraitQueuePalette = if (isModernStyle) {
                palette.copy(
                    primaryContentColor = lyricsPrimaryColor,
                    secondaryContentColor = lyricsSecondaryColor
                )
            } else {
                palette
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isModernStyle) {
                            Modifier
                        } else {
                            Modifier.windowInsetsPadding(safeInsets)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxSize()
                        .then(
                            if (isModernStyle) {
                                Modifier.padding(bottom = if (isCompactHeight) 8.dp else 12.dp)
                            } else {
                                Modifier.padding(top = 2.dp, bottom = if (isCompactHeight) 8.dp else 12.dp)
                            }
                        ),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isModernStyle) {
                        NowPlayingTopBar(
                            albumTitle = localizedAlbumName(song.album),
                            marqueeIterations = marqueeIterations,
                            palette = topBarPalette,
                            onDismissRequest = onDismissRequest,
                            onMoreClick = if (viewModel != null) { { showSongActionBottomSheet = true } } else null,
                            compact = false,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(start = horizontalPad, end = horizontalPad, top = 2.dp)
                        )

                        AnimatedContent(
                            targetState = activePortraitSlot,
                            transitionSpec = {
                                M3Motion.lyricsPanelTransform(openingLyrics = targetState != PortraitCenterSlot.COVER)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .onPlaced { coords ->
                                    coverSlotCoordsRef[0] = coords
                                    updateCoverFractions()
                                },
                            label = "PortraitModernCoverLyricsQueueSwap"
                        ) { slot ->
                            when (slot) {
                                PortraitCenterSlot.LYRICS -> {
                                    NowPlayingLyricsPanel(
                                        song = song,
                                        currentPositionMsFlow = currentPositionMsFlow,
                                        isActive = isSheetActive,
                                        isCompact = isCompactHeight,
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
                                            .padding(horizontal = horizontalPad)
                                            .predictiveBackInline(lyricsBackState)
                                    )
                                }
                                PortraitCenterSlot.QUEUE -> {
                                    TabletInlineQueuePanel(
                                        song = song,
                                        isPlaying = isPlaying,
                                        queue = queue,
                                        queueSource = queueSource,
                                        isActive = isSheetActive && showQueueSheet,
                                        isShuffle = isShuffle,
                                        isModernStyle = true,
                                        isLightAmbientBg = isLightLyricsBg,
                                        palette = portraitQueuePalette,
                                        viewModel = viewModel,
                                        bottomExtraPadding = 8.dp,
                                        onSelectSongFromQueue = onSelectSongFromQueue,
                                        onReorderQueue = onReorderQueue,
                                        onRemoveFromQueue = onRemoveFromQueue,
                                        isCompactRows = isCompactHeight,
                                        borderless = true,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(
                                                horizontal = portraitQueueOuterPad,
                                                vertical = if (isCompactHeight) 4.dp else 12.dp
                                            )
                                            .predictiveBackInline(queueBackState)
                                    )
                                }
                                PortraitCenterSlot.COVER -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        NowPlayingModernCover(
                                            song = song,
                                            featherHorizontalEdges = false,
                                            blendStyle = nowPlayingBlendStyle,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        NowPlayingTopBar(
                            albumTitle = localizedAlbumName(song.album),
                            marqueeIterations = marqueeIterations,
                            palette = palette,
                            onDismissRequest = onDismissRequest,
                            onMoreClick = if (viewModel != null) { { showSongActionBottomSheet = true } } else null,
                            compact = false,
                            modifier = Modifier.padding(horizontal = horizontalPad)
                        )

                        AnimatedContent(
                            targetState = activePortraitSlot,
                            transitionSpec = {
                                M3Motion.lyricsPanelTransform(openingLyrics = targetState != PortraitCenterSlot.COVER)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = if (isCompactHeight) 4.dp else 12.dp),
                            label = "PortraitCoverLyricsQueueSwap"
                        ) { slot ->
                            when (slot) {
                                PortraitCenterSlot.LYRICS -> {
                                    NowPlayingLyricsPanel(
                                        song = song,
                                        currentPositionMsFlow = currentPositionMsFlow,
                                        isActive = isSheetActive,
                                        isCompact = isCompactHeight,
                                        onSeekToMs = onSeekToMs,
                                        fontSizeMode = lyricsFontSize,
                                        alignmentMode = lyricsAlignment,
                                        autoScrollEnabled = lyricsAutoScroll,
                                        syncDelayMs = activeLyricsDelayMs,
                                        primaryContentColor = palette.primaryContentColor,
                                        secondaryContentColor = palette.secondaryContentColor,
                                        accentColor = palette.accentColor,
                                        chipContainerColor = palette.chipContainerColor,
                                        chipContentColor = palette.chipContentColor,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = horizontalPad)
                                            .predictiveBackInline(lyricsBackState)
                                    )
                                }
                                PortraitCenterSlot.QUEUE -> {
                                    TabletInlineQueuePanel(
                                        song = song,
                                        isPlaying = isPlaying,
                                        queue = queue,
                                        queueSource = queueSource,
                                        isActive = isSheetActive && showQueueSheet,
                                        isShuffle = isShuffle,
                                        isModernStyle = false,
                                        isLightAmbientBg = false,
                                        palette = palette,
                                        viewModel = viewModel,
                                        bottomExtraPadding = 8.dp,
                                        onSelectSongFromQueue = onSelectSongFromQueue,
                                        onReorderQueue = onReorderQueue,
                                        onRemoveFromQueue = onRemoveFromQueue,
                                        isCompactRows = isCompactHeight,
                                        borderless = true,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = portraitQueueOuterPad)
                                            .predictiveBackInline(queueBackState)
                                    )
                                }
                                PortraitCenterSlot.COVER -> {
                                    NowPlayingCoverCard(
                                        song = song,
                                        isPlaying = isPlaying,
                                        isCompact = isCompactHeight,
                                        isLandscape = false,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = horizontalPad)
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPad)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            NowPlayingAudioBadgesRow(
                                displaySpecs = displaySpecs,
                                audioOutputState = audioOutputState,
                                isCompactHeight = isCompactHeight,
                                palette = palette,
                                onPathBadgeClick = { showUsbDacStatsSheet = true }
                            )
                        }

                        NowPlayingMetadataBlock(
                            song = song,
                            marqueeIterations = marqueeIterations,
                            isCompact = isCompactHeight,
                            palette = palette,
                            onClick = onOpenSongAlbum
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
                            isCompact = isCompactHeight
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
                            isCompact = isCompactHeight
                        )

                        NowPlayingUtilityButtons(
                            isLiked = isLiked,
                            showLyrics = showLyrics,
                            showQueueSheet = showQueueSheet,
                            sleepTimerMinutes = sleepTimerMinutes,
                            sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                            isSheetActive = isSheetActive,
                            palette = palette,
                            onToggleLyrics = {
                                val next = !showLyrics
                                showLyrics = next
                                if (next) showQueueSheet = false
                            },
                            onToggleLike = onToggleLike,
                            onShowSleepTimer = if (viewModel != null) { { showSleepTimerDialog = true } } else null,
                            onShowQueue = {
                                val next = !showQueueSheet
                                showQueueSheet = next
                                if (next) showLyrics = false
                            },
                            isCompact = isCompactHeight,
                            modifier = if (isModernStyle) Modifier.navigationBarsPadding() else Modifier
                        )
                    }
                }
            }
        }
        }
    }

    PredictiveBackEffect(
        enabled = isSheetActive && showLyrics && !showQueueSheet,
        state = lyricsBackState,
        onBack = { showLyrics = false }
    )

    // Inline queue intercepts Back before dismissing NowPlayingSheet
    PredictiveBackEffect(
        enabled = isSheetActive && showQueueSheet,
        state = queueBackState,
        onBack = { showQueueSheet = false }
    )

    if (showSleepTimerDialog && viewModel != null) {
        SleepTimerClockDialog(
            viewModel = viewModel,
            onDismissRequest = { showSleepTimerDialog = false }
        )
    }

    if (showUsbDacStatsSheet) {
        UsbDacStatisticsBottomSheet(
            audioOutputState = audioOutputState,
            onRefresh = {
                viewModel?.refreshUsbDacTelemetry() ?: UsbDacManager.refreshStreamTelemetry()
            },
            onDismissRequest = { showUsbDacStatsSheet = false }
        )
    }

    if (showSongActionBottomSheet && viewModel != null) {
        SongActionBottomSheet(
            song = song,
            viewModel = viewModel,
            onDismissRequest = { showSongActionBottomSheet = false },
            onNavigateToAlbum = { albumId ->
                showSongActionBottomSheet = false
                onDismissRequest()
                onNavigateToAlbum?.invoke(albumId)
            },
            onNavigateToArtist = { artistName ->
                showSongActionBottomSheet = false
                onDismissRequest()
                onNavigateToArtist?.invoke(artistName)
            },
            showPlayNow = false,
            showPlayNext = false,
            showAddToQueue = false
        )
    }
    }
}

// ═══ Seekbar & Audio Badges ═══

/**
 * Isolated playback seekbar preventing 60Hz progress updates from recomposing [NowPlayingSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingSeekbar(
    songId: Long,
    instanceId: Long,
    progressFlow: StateFlow<Float>,
    currentPositionMsFlow: StateFlow<Long>,
    durationMs: Long,
    isActive: Boolean,
    isPlaying: Boolean,
    seekbarStyle: NowPlayingSeekbarStyle,
    formattedDuration: String,
    palette: NowPlayingPalette,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    inlineTimestamps: Boolean = false,
    slimVertical: Boolean = false,
    onSeekingChanged: ((Boolean) -> Unit)? = null
) {
    val activeProgressFlow = remember(isActive, progressFlow) {
        if (isActive) progressFlow else MutableStateFlow(progressFlow.value)
    }
    val activePosFlow = remember(isActive, currentPositionMsFlow) {
        if (isActive) currentPositionMsFlow else MutableStateFlow(currentPositionMsFlow.value)
    }
    val progressState = activeProgressFlow.collectAsStateWithLifecycle()
    val progress = progressState.value
    val currentPositionMs by activePosFlow.collectAsStateWithLifecycle()

    var isUserSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }

    // Smooth rewind-to-start animation when a track reaches its end and resets / advances / loops
    val rewindAnim = remember { Animatable(0f) }
    val rewindScope = rememberCoroutineScope()
    var rewindJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isRewinding by remember { mutableStateOf(false) }
    var hasSnappedRewind by remember { mutableStateOf(false) }
    var rewindAnchorProgress by remember { mutableFloatStateOf(0f) }
    val committedProgressRef = remember { floatArrayOf(progress) }
    val committedActiveRef = remember { booleanArrayOf(isActive) }
    val lastUserSeekEndMsRef = remember { longArrayOf(0L) }

    val committedProgress = committedProgressRef[0]
    val wasActive = committedActiveRef[0]
    val recentlyUserSeeked = (android.os.SystemClock.uptimeMillis() - lastUserSeekEndMsRef[0]) < 600L
    val shouldStartRewind = isActive &&
        wasActive &&
        !isUserSeeking &&
        !isRewinding &&
        !recentlyUserSeeked &&
        committedProgress >= 0.80f &&
        progress <= 0.08f

    SideEffect {
        committedProgressRef[0] = progress
        committedActiveRef[0] = isActive
    }

    LaunchedEffect(isActive, isUserSeeking) {
        if (!isActive || isUserSeeking) {
            rewindJob?.cancel()
            rewindJob = null
            isRewinding = false
            hasSnappedRewind = false
        }
    }

    if (shouldStartRewind) {
        val startFrom = committedProgress.coerceIn(0f, 1f)
        LaunchedEffect(songId, instanceId, startFrom) {
            rewindJob?.cancel()
            rewindAnchorProgress = startFrom
            hasSnappedRewind = false
            isRewinding = true
            rewindJob = rewindScope.launch {
                try {
                    rewindAnim.snapTo(startFrom)
                    hasSnappedRewind = true
                    rewindAnim.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = 380,
                            easing = M3Motion.EmphasizedDecelerate
                        )
                    )
                } finally {
                    isRewinding = false
                    hasSnappedRewind = false
                }
            }
        }
    }

    // Only observe progress while a rewind animation is actively running (zero 10Hz effect churn)
    LaunchedEffect(isRewinding) {
        if (!isRewinding) return@LaunchedEffect
        snapshotFlow { progressState.value }.first { it > 0.12f }
        rewindJob?.cancel()
        rewindJob = null
        isRewinding = false
        hasSnappedRewind = false
    }

    val displayProgress = when {
        isUserSeeking -> seekProgress
        isRewinding -> if (hasSnappedRewind) rewindAnim.value else rewindAnchorProgress
        shouldStartRewind -> committedProgress
        else -> progress
    }

    // M3 Expressive Wavy Seekbar wave phase (draw-phase only, 0 CPU when paused/hidden/backgrounded/slider mode)
    val isUiForeground = LocalIsUiForeground.current.value
    val shouldAnimateWave = seekbarStyle == NowPlayingSeekbarStyle.WAVY &&
        isActive && isUiForeground && isPlaying && !isUserSeeking && !isRewinding
    val waveAmplitudeFraction by animateFloatAsState(
        targetValue = if (shouldAnimateWave) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = M3Motion.Standard),
        label = "WavySeekbarAmplitude"
    )
    val wavePhaseState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shouldAnimateWave) {
        if (!shouldAnimateWave) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val dtSec = ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNanos = nowNanos
            wavePhaseState.floatValue = (wavePhaseState.floatValue + dtSec * 3.4f) % (2f * PI.toFloat())
        }
    }
    val wavePath = remember { Path() }
    val density = LocalDensity.current
    val wavyStroke = remember(density) {
        Stroke(
            width = with(density) { 4.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    }

    val displayMs = if (isUserSeeking && durationMs > 0L) {
        (seekProgress * durationMs).toLong().coerceIn(0L, durationMs)
    } else {
        currentPositionMs
    }
    val totalSec = (displayMs / 1000L).coerceAtLeast(0L)
    val elapsedText = remember(totalSec) { formatElapsedMmSs(totalSec) }

    val sliderComposable: @Composable (Modifier) -> Unit = { sliderMod ->
        Slider(
            value = displayProgress.coerceIn(0f, 1f),
            onValueChange = {
                if (isRewinding) {
                    rewindJob?.cancel()
                    rewindJob = null
                    isRewinding = false
                    hasSnappedRewind = false
                }
                if (!isUserSeeking) {
                    onSeekingChanged?.invoke(true)
                }
                isUserSeeking = true
                seekProgress = it
            },
            onValueChangeFinished = {
                lastUserSeekEndMsRef[0] = android.os.SystemClock.uptimeMillis()
                onProgressChange(seekProgress)
                isUserSeeking = false
                onSeekingChanged?.invoke(false)
            },
            modifier = sliderMod
                .layout { measurable, constraints ->
                    // Compensate for Material 3 Slider's 6dp horizontal thumb inset so the track aligns flush with page margins
                    val overExtension = 6.dp.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(
                            maxWidth = constraints.maxWidth + (overExtension * 2),
                            minWidth = constraints.minWidth + (overExtension * 2)
                        )
                    )
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeRelative(-overExtension, 0)
                    }
                },
            thumb = {
                // Static capsule thumb avoids touch ripple and resize animations during scrubbing
                val thumbHeight = when {
                    inlineTimestamps && isCompact -> 22.dp
                    slimVertical -> 24.dp
                    inlineTimestamps -> 30.dp
                    else -> 44.dp
                }
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = thumbHeight)
                        .background(palette.accentColor, RoundedCornerShape(50))
                )
            },
            track = { sliderState ->
                if (seekbarStyle == NowPlayingSeekbarStyle.WAVY) {
                    val activeFraction = sliderState.value.coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    ) {
                        val centerY = size.height / 2f
                        val strokePx = 4.dp.toPx()
                        val halfStroke = strokePx / 2f
                        val thumbGapPx = 7.dp.toPx()
                        val trackStart = halfStroke
                        val trackEnd = (size.width - halfStroke).coerceAtLeast(trackStart)
                        val thumbX = trackStart + (trackEnd - trackStart) * activeFraction

                        val activeEnd = (thumbX - thumbGapPx).coerceAtLeast(trackStart)
                        val inactiveStart = (thumbX + thumbGapPx).coerceAtMost(trackEnd)

                        // Straight rounded inactive track (right of thumb)
                        if (trackEnd - inactiveStart > 1f) {
                            drawLine(
                                color = palette.inactiveTrackColor,
                                start = Offset(inactiveStart, centerY),
                                end = Offset(trackEnd, centerY),
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Expressive wavy active track (left of thumb)
                        val activeLen = activeEnd - trackStart
                        if (activeLen > 1f) {
                            val maxAmpPx = 3.2.dp.toPx() * waveAmplitudeFraction
                            if (maxAmpPx <= 0.15f) {
                                drawLine(
                                    color = palette.accentColor,
                                    start = Offset(trackStart, centerY),
                                    end = Offset(activeEnd, centerY),
                                    strokeWidth = strokePx,
                                    cap = StrokeCap.Round
                                )
                            } else {
                                val wavelengthPx = 28.dp.toPx()
                                val phase = wavePhaseState.floatValue
                                val stepPx = 2.dp.toPx().coerceAtLeast(2f)
                                val taperPx = 10.dp.toPx().coerceAtMost(activeLen * 0.35f)

                                wavePath.rewind()
                                var x = trackStart
                                var first = true
                                while (true) {
                                    val clampedX = if (x > activeEnd) activeEnd else x
                                    val distStart = clampedX - trackStart
                                    val distEnd = activeEnd - clampedX
                                    val edgeFade = if (taperPx > 0.5f) {
                                        minOf(distStart / taperPx, distEnd / taperPx, 1f).coerceIn(0f, 1f)
                                    } else {
                                        1f
                                    }
                                    val angle = ((clampedX - trackStart) / wavelengthPx) * (2f * PI.toFloat()) - phase
                                    val y = centerY + sin(angle) * maxAmpPx * edgeFade
                                    if (first) {
                                        wavePath.moveTo(clampedX, y)
                                        first = false
                                    } else {
                                        wavePath.lineTo(clampedX, y)
                                    }
                                    if (clampedX >= activeEnd) break
                                    x += stepPx
                                }
                                drawPath(
                                    path = wavePath,
                                    color = palette.accentColor,
                                    style = wavyStroke
                                )
                            }
                        }
                    }
                } else {
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        drawStopIndicator = null,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.accentColor,
                            activeTrackColor = palette.accentColor,
                            inactiveTrackColor = palette.inactiveTrackColor
                        )
                    )
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = palette.accentColor,
                activeTrackColor = palette.accentColor,
                inactiveTrackColor = palette.inactiveTrackColor
            )
        )
    }

    if (inlineTimestamps) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
        ) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.secondaryContentColor
            )
            sliderComposable(
                if (isCompact) Modifier.weight(1f).height(30.dp) else Modifier.weight(1f)
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.secondaryContentColor
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = when {
                    slimVertical -> 0.dp
                    isCompact -> 4.dp
                    else -> 10.dp
                })
        ) {
            sliderComposable(
                if (slimVertical) Modifier.fillMaxWidth().height(28.dp) else Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = elapsedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondaryContentColor
                )
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.secondaryContentColor
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NowPlayingAudioBadgesRow(
    displaySpecs: String,
    audioOutputState: AudioOutputUiState,
    isCompactHeight: Boolean,
    palette: NowPlayingPalette,
    onPathBadgeClick: (() -> Unit)? = null,
    centerAligned: Boolean = false
) {
    val pathBadge = audioOutputState.audioPathBadge
    val showPathBadge = audioOutputState.isDirectStreaming && pathBadge.isNotBlank()
    val isHiRes = remember(displaySpecs) { AudioSpecsHelper.isHiRes(displaySpecs) }

    if (displaySpecs.isBlank() && !showPathBadge) return

    FlowRow(
        modifier = Modifier.padding(bottom = if (isCompactHeight) 2.dp else 6.dp),
        horizontalArrangement = Arrangement.spacedBy(
            space = 6.dp,
            alignment = if (centerAligned) Alignment.CenterHorizontally else Alignment.Start
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (displaySpecs.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = palette.chipContainerColor
            ) {
                Text(
                    text = displaySpecs,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.chipContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    letterSpacing = 0.3.sp
                )
            }
        }

        if (showPathBadge) {
            val isBitExact = audioOutputState.isBitExactRoute
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (isBitExact) {
                    palette.accentColor
                } else {
                    palette.chipContainerColor
                },
                border = if (isBitExact) {
                    null
                } else {
                    BorderStroke(
                        1.dp,
                        palette.secondaryContentColor.copy(alpha = 0.35f)
                    )
                },
                modifier = if (onPathBadgeClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onPathBadgeClick)
                } else {
                    Modifier
                }
            ) {
                Text(
                    text = pathBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isBitExact) {
                        palette.onAccentColor
                    } else {
                        palette.chipContentColor
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    letterSpacing = 0.35.sp
                )
            }
        }

        if (isHiRes) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFFFFD54F)
            ) {
                Text(
                    text = "Hi-Res",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

// ═══ Player Sub-components ═══
@Composable
internal fun NowPlayingTopBar(
    albumTitle: String,
    marqueeIterations: Int,
    palette: NowPlayingPalette,
    onDismissRequest: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) 0.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onDismissRequest,
            modifier = Modifier.size(if (compact) 40.dp else 48.dp)
        ) {
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = stringResource(R.string.cd_collapse),
                tint = palette.primaryContentColor,
                modifier = Modifier.size(if (compact) 26.dp else 30.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = albumTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.secondaryContentColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.basicMarquee(
                    iterations = marqueeIterations,
                    repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                    initialDelayMillis = MARQUEE_INITIAL_DELAY_MS
                )
            )
        }

        if (onMoreClick != null) {
            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(if (compact) 40.dp else 48.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more),
                    tint = palette.secondaryContentColor,
                    modifier = Modifier.size(if (compact) 22.dp else 24.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(if (compact) 40.dp else 48.dp))
        }
    }
}

@Composable
private fun NowPlayingModernCover(
    song: Song,
    featherHorizontalEdges: Boolean,
    blendStyle: NowPlayingBlendStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithCache {
                val verticalBrush = when (blendStyle) {
                    NowPlayingBlendStyle.LINEAR -> Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.10f to Color.Black,
                        0.90f to Color.Black,
                        1.00f to Color.Transparent
                    )
                    NowPlayingBlendStyle.CURVED -> Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.03f to Color.Black.copy(alpha = 0.03f),
                        0.07f to Color.Black.copy(alpha = 0.14f),
                        0.12f to Color.Black.copy(alpha = 0.38f),
                        0.17f to Color.Black.copy(alpha = 0.68f),
                        0.21f to Color.Black.copy(alpha = 0.90f),
                        0.26f to Color.Black,
                        0.74f to Color.Black,
                        0.79f to Color.Black.copy(alpha = 0.90f),
                        0.83f to Color.Black.copy(alpha = 0.68f),
                        0.88f to Color.Black.copy(alpha = 0.38f),
                        0.93f to Color.Black.copy(alpha = 0.14f),
                        0.97f to Color.Black.copy(alpha = 0.03f),
                        1.00f to Color.Transparent
                    )
                }
                val horizontalBrush = if (featherHorizontalEdges) {
                    when (blendStyle) {
                        NowPlayingBlendStyle.LINEAR -> Brush.horizontalGradient(
                            0.00f to Color.Transparent,
                            0.10f to Color.Black,
                            0.90f to Color.Black,
                            1.00f to Color.Transparent
                        )
                        NowPlayingBlendStyle.CURVED -> Brush.horizontalGradient(
                            0.00f to Color.Transparent,
                            0.03f to Color.Black.copy(alpha = 0.03f),
                            0.07f to Color.Black.copy(alpha = 0.15f),
                            0.12f to Color.Black.copy(alpha = 0.42f),
                            0.17f to Color.Black.copy(alpha = 0.74f),
                            0.22f to Color.Black,
                            0.78f to Color.Black,
                            0.83f to Color.Black.copy(alpha = 0.74f),
                            0.88f to Color.Black.copy(alpha = 0.42f),
                            0.93f to Color.Black.copy(alpha = 0.15f),
                            0.97f to Color.Black.copy(alpha = 0.03f),
                            1.00f to Color.Transparent
                        )
                    }
                } else {
                    null
                }
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = verticalBrush, blendMode = BlendMode.DstIn)
                    if (horizontalBrush != null) {
                        drawRect(brush = horizontalBrush, blendMode = BlendMode.DstIn)
                    }
                }
            }
    ) {
        AlbumArtImage(
            song = song,
            icon = Icons.Default.Album,
            bucket = ArtResolutionBucket.ULTRA_RES,
            shape = RectangleShape,
            enableCrossfade = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun NowPlayingCoverCard(
    song: Song,
    @Suppress("UNUSED_PARAMETER") isPlaying: Boolean,
    isCompact: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val coverShape = remember { RoundedCornerShape(16.dp) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val sizeMod = if (isLandscape) {
            Modifier
                .fillMaxHeight(if (isCompact) 0.94f else 0.86f)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .widthIn(max = 420.dp)
        } else {
            Modifier
                .fillMaxWidth(if (isCompact) 0.76f else 0.82f)
                .aspectRatio(1f)
        }

        // Static clipped cover avoids real-time shadow + full-res scaling overhead on low-end GPUs
        Surface(
            modifier = sizeMod,
            shape = coverShape,
            shadowElevation = 4.dp,
            color = Color.Transparent
        ) {
            AlbumArtImage(
                song = song,
                icon = Icons.Default.Album,
                bucket = ArtResolutionBucket.HIGH_RES,
                shape = RectangleShape,
                enableCrossfade = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NowPlayingMetadataBlock(
    song: Song,
    marqueeIterations: Int,
    isCompact: Boolean,
    palette: NowPlayingPalette,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
    ) {
        Text(
            text = song.title,
            style = if (isCompact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = palette.primaryContentColor,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = marqueeIterations,
                    repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                    initialDelayMillis = MARQUEE_INITIAL_DELAY_MS
                )
        )
        Spacer(modifier = Modifier.height(if (isCompact) 1.dp else 2.dp))
        Text(
            text = localizedArtistName(song.artist),
            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            color = palette.secondaryContentColor,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = marqueeIterations,
                    repeatDelayMillis = MARQUEE_REPEAT_DELAY_MS,
                    initialDelayMillis = MARQUEE_INITIAL_DELAY_MS
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingTransportControls(
    isPlaying: Boolean,
    isShuffle: Boolean,
    repeatMode: Int,
    palette: NowPlayingPalette,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopAndReset: () -> Unit,
    onNext: () -> Unit,
    onToggleRepeat: () -> Unit,
    isCompact: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (isCompact) 2.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onToggleShuffle,
            modifier = Modifier.size(if (isCompact) 42.dp else 48.dp)
        ) {
            Icon(
                painter = painterResource(if (isShuffle) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle),
                contentDescription = stringResource(R.string.cd_shuffle),
                tint = if (isShuffle) palette.accentColor else palette.secondaryContentColor,
                modifier = Modifier.size(if (isCompact) 21.dp else 24.dp)
            )
        }

        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(if (isCompact) 46.dp else 56.dp)
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = stringResource(R.string.cd_previous),
                tint = palette.primaryContentColor,
                modifier = Modifier.size(if (isCompact) 30.dp else 36.dp)
            )
        }

        val playInteraction = remember { MutableInteractionSource() }
        val playButtonRadius = if (isPlaying) {
            if (isCompact) 18.dp else 24.dp
        } else {
            if (isCompact) 29.dp else 36.dp
        }
        val haptic = LocalHapticFeedback.current
        Surface(
            shape = RoundedCornerShape(playButtonRadius),
            color = palette.accentColor,
            contentColor = palette.onAccentColor,
            modifier = Modifier
                .width(if (isCompact) 66.dp else 78.dp)
                .height(if (isCompact) 58.dp else 72.dp)
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
                    contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                    modifier = Modifier.size(if (isCompact) 32.dp else 40.dp)
                )
            }
        }

        IconButton(
            onClick = onNext,
            modifier = Modifier.size(if (isCompact) 46.dp else 56.dp)
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = stringResource(R.string.cd_next),
                tint = palette.primaryContentColor,
                modifier = Modifier.size(if (isCompact) 30.dp else 36.dp)
            )
        }

        val repeatIconRes = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
            Player.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
            else -> R.drawable.ic_repeat
        }
        val repeatTint = if (repeatMode == Player.REPEAT_MODE_OFF) {
            palette.secondaryContentColor
        } else {
            palette.accentColor
        }
        IconButton(
            onClick = onToggleRepeat,
            modifier = Modifier.size(if (isCompact) 42.dp else 48.dp)
        ) {
            Icon(
                painter = painterResource(repeatIconRes),
                contentDescription = stringResource(R.string.cd_repeat),
                tint = repeatTint,
                modifier = Modifier.size(if (isCompact) 21.dp else 24.dp)
            )
        }
    }
}

@Composable
internal fun NowPlayingUtilityButtons(
    isLiked: Boolean,
    showLyrics: Boolean,
    showQueueSheet: Boolean,
    sleepTimerMinutes: Int,
    sleepTimerRemainingSecFlow: StateFlow<Long>?,
    isSheetActive: Boolean,
    palette: NowPlayingPalette,
    onToggleLyrics: () -> Unit,
    onToggleLike: () -> Unit,
    onShowSleepTimer: (() -> Unit)?,
    onShowQueue: () -> Unit,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    showModeLabels: Boolean = false
) {
    val buttonSize = if (isCompact) 40.dp else 48.dp
    val pillHeight = if (isCompact) 38.dp else 44.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (isCompact) 0.dp else 4.dp, bottom = if (isCompact) 0.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showModeLabels) {
            TabletModePillButton(
                icon = Icons.AutoMirrored.Filled.Article,
                label = stringResource(R.string.cd_lyrics_short),
                contentDescription = stringResource(R.string.cd_lyrics),
                selected = showLyrics,
                palette = palette,
                height = pillHeight,
                onClick = onToggleLyrics
            )
        } else {
            UtilityToggleIconButton(
                icon = Icons.AutoMirrored.Filled.Article,
                contentDescription = stringResource(R.string.cd_lyrics),
                selected = showLyrics,
                palette = palette,
                buttonSize = buttonSize,
                onClick = onToggleLyrics
            )
        }

        IconButton(
            onClick = onToggleLike,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = stringResource(R.string.cd_favorite),
                tint = if (isLiked) Color(FAVORITE_HEART_HEX) else palette.secondaryContentColor
            )
        }

        if (onShowSleepTimer != null) {
            SleepTimerUtilityControl(
                sleepTimerMinutes = sleepTimerMinutes,
                sleepTimerRemainingSecFlow = sleepTimerRemainingSecFlow,
                isActive = isSheetActive,
                palette = palette,
                onShowSleepTimer = onShowSleepTimer,
                buttonSize = buttonSize,
                pillHeight = if (isCompact) 36.dp else 40.dp,
                pillIconSize = if (isCompact) 18.dp else 20.dp,
                buttonIconSize = 24.dp
            )
        }

        if (showModeLabels) {
            TabletModePillButton(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                label = stringResource(R.string.cd_queue_short),
                contentDescription = stringResource(R.string.cd_queue),
                selected = showQueueSheet,
                palette = palette,
                height = pillHeight,
                onClick = onShowQueue
            )
        } else {
            UtilityToggleIconButton(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = stringResource(R.string.cd_queue),
                selected = showQueueSheet,
                palette = palette,
                buttonSize = buttonSize,
                onClick = onShowQueue
            )
        }
    }
}

@Composable
private fun UtilityToggleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    palette: NowPlayingPalette,
    buttonSize: Dp,
    onClick: () -> Unit
) {
    val activeFractionState = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = M3Motion.Standard),
        label = "UtilityToggleActive"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .drawBehind {
                val frac = activeFractionState.value
                if (frac > 0.001f) {
                    drawCircle(color = lerp(Color.Transparent, palette.activePillContainerColor, frac))
                }
            }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = lerp(palette.secondaryContentColor, palette.accentColor, activeFractionState.value)
        )
    }
}

/**
 * Isolated sleep-timer control that subscribes to the 1 Hz countdown flow only while a timed
 * countdown is active, preventing root-level recompositions in [NowPlayingSheet].
 */
@Composable
internal fun SleepTimerUtilityControl(
    sleepTimerMinutes: Int,
    sleepTimerRemainingSecFlow: StateFlow<Long>?,
    isActive: Boolean,
    palette: NowPlayingPalette,
    onShowSleepTimer: () -> Unit,
    buttonSize: Dp,
    pillHeight: Dp,
    pillIconSize: Dp,
    buttonIconSize: Dp,
    pillHorizontalPadding: Dp = 10.dp
) {
    if (sleepTimerMinutes > 0 && sleepTimerRemainingSecFlow != null) {
        val activeFlow = remember(isActive, sleepTimerRemainingSecFlow) {
            if (isActive) sleepTimerRemainingSecFlow else MutableStateFlow(sleepTimerRemainingSecFlow.value)
        }
        val remainingSec by activeFlow.collectAsStateWithLifecycle()
        if (remainingSec > 0L) {
            Surface(
                onClick = onShowSleepTimer,
                shape = RoundedCornerShape(50),
                color = palette.activePillContainerColor,
                contentColor = palette.accentColor,
                modifier = Modifier.height(pillHeight)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = pillHorizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = stringResource(R.string.cd_sleep_timer),
                        modifier = Modifier.size(pillIconSize)
                    )
                    Text(
                        text = formatSleepCountdown(remainingSec),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            return
        }
    }

    val isEndOfTrackActive = sleepTimerMinutes == -1
    IconButton(
        onClick = onShowSleepTimer,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (isEndOfTrackActive) palette.activePillContainerColor else Color.Transparent,
            contentColor = if (isEndOfTrackActive) palette.accentColor else palette.secondaryContentColor
        ),
        modifier = Modifier.size(buttonSize)
    ) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = stringResource(R.string.cd_sleep_timer),
            tint = if (isEndOfTrackActive) palette.accentColor else palette.secondaryContentColor,
            modifier = Modifier.size(buttonIconSize)
        )
    }
}

private fun formatElapsedMmSs(totalSec: Long): String {
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return buildString(5) {
        if (min < 10L) append('0')
        append(min)
        append(':')
        if (sec < 10L) append('0')
        append(sec)
    }
}
