/**
 * MusicApplication — Application entrypoint configuring diagnostics, [AlbumArtManager], and Coil.
 *
 * @remarks
 * Initializes `AppLogManager` and [AlbumArtManager] before any Activity or `MediaSessionService`
 * starts so cold-start diagnostics and the dedicated 13% heap L1 + WebP L2 artwork cache are
 * ready immediately. Coil's own memory cache is kept minimal (4%) to prevent duplicate RAM
 * allocation alongside [AlbumArtManager]'s album-keyed L1 cache.
 *
 * @see AlbumArtManager
 * @see AudioCoverFetcher
 * @see AppLogManager
 */
package com.quandot.musicplayer

// External SDK / Jetpack / third-party libraries
import android.app.Application
import android.content.ComponentCallbacks2
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers

// Internal modules
import com.quandot.musicplayer.data.AlbumArtManager
import com.quandot.musicplayer.data.AudioCoverFetcher
import com.quandot.musicplayer.data.AudioSpecsHelper
import com.quandot.musicplayer.data.LyricsHelper
import com.quandot.musicplayer.debug.AppLogManager

// ═══ Constants ═══
private const val COIL_MEMORY_CACHE_MAX_SIZE_PERCENT = 0.04
private const val COIL_DISK_CACHE_MAX_BYTES = 16L * 1024L * 1024L
private const val IMAGE_CACHE_DIR_NAME = "image_cache"

// ═══ Application ═══
class MusicApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppLogManager.init(this)
        AlbumArtManager.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(AudioCoverFetcher.Factory(this@MusicApplication))
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(COIL_MEMORY_CACHE_MAX_SIZE_PERCENT)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIR_NAME))
                    .maxSizeBytes(COIL_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .dispatcher(Dispatchers.IO)
            .crossfade(false)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            AlbumArtManager.getInstance(this).onTrimMemory(level)
            when {
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                    Coil.imageLoader(this).memoryCache?.clear()
                    AudioSpecsHelper.clearCache()
                    LyricsHelper.clearCache()
                }
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                    Coil.imageLoader(this).memoryCache?.trimMemory(level)
                }
            }
        } catch (_: Exception) {
        }
    }
}
