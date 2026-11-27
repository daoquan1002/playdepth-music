/**
 * AudioCoverFetcher — Coil `Fetcher` adapter delegating local audio artwork URIs to [AlbumArtManager].
 *
 * @remarks
 * Keeps Coil consumers (such as external URI loaders or legacy call sites) unified on PlayDepth's
 * album-level deduplicated L1 RAM + L2 WebP disk cache and multi-source priority fetcher
 * (Folder Art -> System Thumbnail -> Embedded Metadata) running strictly on `Dispatchers.IO`.
 *
 * @see AlbumArtManager
 * @see AlbumArtCache
 * @see com.quandot.musicplayer.MusicApplication
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse

// ═══ Constants ═══
private const val DEFAULT_REQUEST_SIZE_PX = 224
private const val CARD_THRESHOLD_PX = 280
private const val HIGH_RES_THRESHOLD_PX = 680

// ═══ Coil Fetcher Adapter ═══
class AudioCoverFetcher(
    private val context: Context,
    private val data: Uri,
    private val options: Options,
    private val artManager: AlbumArtManager
) : Fetcher {

    companion object {
        fun clearNegativeCache(context: Context? = null) {
            context?.let { AlbumArtManager.getInstance(it).clearAll() }
        }
    }

    override suspend fun fetch(): FetchResult? {
        val targetW = options.size.width.pxOrElse { DEFAULT_REQUEST_SIZE_PX }
        val targetH = options.size.height.pxOrElse { DEFAULT_REQUEST_SIZE_PX }
        val maxDim = maxOf(targetW, targetH)
        val bucket = when {
            maxDim >= HIGH_RES_THRESHOLD_PX -> ArtResolutionBucket.HIGH_RES
            maxDim > CARD_THRESHOLD_PX -> ArtResolutionBucket.CARD
            else -> ArtResolutionBucket.THUMBNAIL
        }

        val bitmap = artManager.loadBitmapForUri(data, bucket) ?: return null
        return DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    // ═══ Coil Factory ═══
    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        private val artManager: AlbumArtManager by lazy { AlbumArtManager.getInstance(context) }

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE) {
                return AudioCoverFetcher(context, data, options, artManager)
            }
            return null
        }
    }
}
