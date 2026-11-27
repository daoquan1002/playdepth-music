/**
 * AlbumArtImage — Lifecycle-aware Compose artwork renderer backed by [AlbumArtManager].
 *
 * @remarks
 * Resolves L1 RAM hits synchronously inside `remember` for 0 ms Frame 0 display. For `HIGH_RES`
 * (Now Playing), immediately renders any L1-cached `THUMBNAIL` as a zero-flash backdrop and
 * crossfades via GPU `graphicsLayer { alpha }` once `HIGH_RES` finishes decoding on `Dispatchers.IO`.
 * During high-velocity list/grid flings ([LocalFastScrollingState]), non-cached loads pause until
 * scroll velocity settles and crossfade is disabled; items leaving the viewport cancel their
 * `LaunchedEffect` immediately, propagating cancellation down to [AlbumArtManager]'s single-flight
 * decode jobs and `CancellationSignal`.
 *
 * @see AlbumArtManager
 * @see ArtResolutionBucket
 */
package com.quandot.musicplayer.ui.components

// External SDK / Jetpack / third-party libraries
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.first

// Internal modules
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.data.AlbumArtRequest
import com.quandot.musicplayer.data.ArtResolutionBucket
import com.quandot.musicplayer.data.toAlbumArtRequest

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val HIGH_RES_CROSSFADE_MS = 180
private const val THUMB_CROSSFADE_MS = 120
private const val FAST_FLING_INDEX_JUMP_THRESHOLD = 3
private const val FAST_FLING_WINDOW_MS = 65L
private const val SLOW_SCROLL_SETTLE_MS = 90L
private const val FULL_OPAQUE_THRESHOLD = 0.99f

// ═══ Fast-Scroll Velocity Tracking ═══

/**
 * Provides a stable [State] reference reporting whether an enclosing lazy list/grid is currently
 * undergoing a high-velocity fling or fast-scroller scrub. Passing a `State<Boolean>` wrapper via
 * `staticCompositionLocalOf` avoids recomposing the entire list subtree whenever scroll velocity
 * flips.
 */
val LocalFastScrollingState = staticCompositionLocalOf<State<Boolean>> {
    mutableStateOf(false)
}

/**
 * Root-hoisted [AlbumArtManager] instance provided once by `MainActivity` so lazy list items
 * never query `LocalContext.current` or `AlbumArtManager.getInstance(...)` per row.
 */
val LocalAlbumArtManager = staticCompositionLocalOf<AlbumArtManager?> { null }

/**
 * Root-hoisted [AlbumArtManager.cacheVersion] state provided once by `MainActivity` so scrolling
 * lists share a single `StateFlow` collector instead of spawning one subscription per visible row.
 */
val LocalAlbumArtCacheVersion = staticCompositionLocalOf<State<Long>?> { null }

@Composable
fun rememberIsFastScrolling(listState: LazyListState): State<Boolean> {
    val fastState = remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var lastIdx = listState.firstVisibleItemIndex
        var lastTimeMs = SystemClock.uptimeMillis()
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, idx) ->
                if (!scrolling) {
                    fastState.value = false
                    lastIdx = idx
                    lastTimeMs = SystemClock.uptimeMillis()
                } else {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastTimeMs).coerceAtLeast(1L)
                    val delta = abs(idx - lastIdx)
                    if (delta >= FAST_FLING_INDEX_JUMP_THRESHOLD && dt <= FAST_FLING_WINDOW_MS) {
                        fastState.value = true
                        lastIdx = idx
                        lastTimeMs = now
                    } else if (delta > 0) {
                        if (dt >= SLOW_SCROLL_SETTLE_MS && delta <= 1) {
                            fastState.value = false
                        }
                        lastIdx = idx
                        lastTimeMs = now
                    }
                }
            }
    }
    return fastState
}

@Composable
fun rememberIsFastScrolling(gridState: LazyGridState): State<Boolean> {
    val fastState = remember(gridState) { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        var lastIdx = gridState.firstVisibleItemIndex
        var lastTimeMs = SystemClock.uptimeMillis()
        snapshotFlow { gridState.isScrollInProgress to gridState.firstVisibleItemIndex }
            .collect { (scrolling, idx) ->
                if (!scrolling) {
                    fastState.value = false
                    lastIdx = idx
                    lastTimeMs = SystemClock.uptimeMillis()
                } else {
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastTimeMs).coerceAtLeast(1L)
                    val delta = abs(idx - lastIdx)
                    if (delta >= FAST_FLING_INDEX_JUMP_THRESHOLD && dt <= FAST_FLING_WINDOW_MS) {
                        fastState.value = true
                        lastIdx = idx
                        lastTimeMs = now
                    } else if (delta > 0) {
                        if (dt >= SLOW_SCROLL_SETTLE_MS && delta <= 1) {
                            fastState.value = false
                        }
                        lastIdx = idx
                        lastTimeMs = now
                    }
                }
            }
    }
    return fastState
}

// ═══ Public Composables ═══

@Composable
fun AlbumArtImage(
    song: Song,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MusicNote,
    bucket: ArtResolutionBucket = ArtResolutionBucket.THUMBNAIL,
    shape: Shape = RoundedCornerShape(8.dp),
    enableCrossfade: Boolean = true
) {
    val request = remember(
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
    AlbumArtImage(
        request = request,
        fallbackGradientStart = song.albumGradientColorStart,
        fallbackGradientEnd = song.albumGradientColorEnd,
        modifier = modifier,
        icon = icon,
        bucket = bucket,
        shape = shape,
        enableCrossfade = enableCrossfade
    )
}

@Composable
fun AlbumArtImage(
    album: Album,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Album,
    bucket: ArtResolutionBucket = ArtResolutionBucket.THUMBNAIL,
    shape: Shape = RoundedCornerShape(12.dp),
    enableCrossfade: Boolean = true
) {
    val request = remember(
        album.id,
        album.title,
        album.artist,
        album.albumArtUri,
        album.sampleSongUri,
        album.sampleSongPath
    ) {
        album.toAlbumArtRequest()
    }
    AlbumArtImage(
        request = request,
        fallbackGradientStart = album.colorStart,
        fallbackGradientEnd = album.colorEnd,
        modifier = modifier,
        icon = icon,
        bucket = bucket,
        shape = shape,
        enableCrossfade = enableCrossfade
    )
}

@Composable
fun AlbumArtImage(
    request: AlbumArtRequest?,
    fallbackGradientStart: Long,
    fallbackGradientEnd: Long,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.MusicNote,
    bucket: ArtResolutionBucket = ArtResolutionBucket.THUMBNAIL,
    shape: Shape = RoundedCornerShape(8.dp),
    enableCrossfade: Boolean = true
) {
    Box(modifier = modifier.clip(shape)) {
        if (request == null) {
            FallbackGradient(fallbackGradientStart, fallbackGradientEnd, icon)
            return@Box
        }

        val providedManager = LocalAlbumArtManager.current
        val artManager = if (providedManager != null) {
            providedManager
        } else {
            val context = LocalContext.current
            remember(context) { AlbumArtManager.getInstance(context) }
        }
        val providedCacheVersion = LocalAlbumArtCacheVersion.current
        val cacheVersion = if (providedCacheVersion != null) {
            providedCacheVersion.value
        } else {
            artManager.cacheVersion.collectAsState().value
        }
        val fastScrollingState = LocalFastScrollingState.current

        // 1. Frame 0 synchronous L1 RAM lookup (0 ms, zero flicker even during fast fling)
        val syncTargetBitmap = remember(request.albumKey, bucket, cacheVersion) {
            artManager.getCachedImageBitmap(request.albumKey, bucket)
        }

        // 2. Progressive 0 ms L1 backdrop: ULTRA_RES -> HIGH_RES -> CARD -> THUMBNAIL
        val syncBackdrop = remember(request.albumKey, bucket, syncTargetBitmap, cacheVersion) {
            if (syncTargetBitmap != null) {
                null
            } else {
                when (bucket) {
                    ArtResolutionBucket.ULTRA_RES -> {
                        artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.HIGH_RES)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.CARD)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.THUMBNAIL)
                    }
                    ArtResolutionBucket.HIGH_RES -> {
                        artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.ULTRA_RES)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.CARD)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.THUMBNAIL)
                    }
                    ArtResolutionBucket.CARD -> {
                        artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.HIGH_RES)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.ULTRA_RES)
                            ?: artManager.getCachedImageBitmap(request.albumKey, ArtResolutionBucket.THUMBNAIL)
                    }
                    ArtResolutionBucket.THUMBNAIL -> null
                }
            }
        }

        if (syncTargetBitmap != null) {
            Image(
                bitmap = syncTargetBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            return@Box
        }

        val isKnownMissing = remember(request.albumKey, cacheVersion) {
            artManager.isKnownMissing(request.albumKey)
        }

        if (isKnownMissing && syncBackdrop == null) {
            FallbackGradient(fallbackGradientStart, fallbackGradientEnd, icon)
            return@Box
        }

        var loadedBitmap by remember(request.albumKey, bucket, cacheVersion) {
            mutableStateOf<ImageBitmap?>(null)
        }
        var isCrossfadeComplete by remember(request.albumKey, bucket, cacheVersion) {
            mutableStateOf(false)
        }
        val revealAlpha = remember(request.albumKey, bucket, cacheVersion) {
            Animatable(0f)
        }

        // Lifecycle-bound coroutine: leaving viewport cancels LaunchedEffect and in-flight I/O immediately
        LaunchedEffect(request.albumKey, bucket, cacheVersion) {
            // During fast fling on list/grid thumbnails & cards, wait for velocity to settle before touching disk
            val isNowPlayingBucket = bucket == ArtResolutionBucket.HIGH_RES || bucket == ArtResolutionBucket.ULTRA_RES
            if (!isNowPlayingBucket && fastScrollingState.value) {
                snapshotFlow { fastScrollingState.value }.first { !it }
            }

            val resolved = artManager.loadImageBitmap(request, bucket)
            if (resolved != null) {
                val shouldAnimate = enableCrossfade && !fastScrollingState.value
                loadedBitmap = resolved
                if (shouldAnimate) {
                    val duration = if (isNowPlayingBucket) {
                        HIGH_RES_CROSSFADE_MS
                    } else {
                        THUMB_CROSSFADE_MS
                    }
                    revealAlpha.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = duration)
                    )
                } else {
                    revealAlpha.snapTo(1f)
                }
                isCrossfadeComplete = true
            }
        }

        val finalBitmap = loadedBitmap
        if (finalBitmap == null || !isCrossfadeComplete) {
            if (syncBackdrop != null) {
                Image(
                    bitmap = syncBackdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FallbackGradient(fallbackGradientStart, fallbackGradientEnd, icon)
            }
        }

        if (finalBitmap != null) {
            Image(
                bitmap = finalBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = revealAlpha.value }
            )
        }
    }
}

// ═══ Sub-components ═══
@Composable
private fun FallbackGradient(
    startColor: Long,
    endColor: Long,
    icon: ImageVector
) {
    val brush = remember(startColor, endColor) {
        Brush.linearGradient(
            listOf(Color(startColor), Color(endColor))
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}
