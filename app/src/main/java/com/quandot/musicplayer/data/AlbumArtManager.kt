/**
 * AlbumArtManager — Unified album-level artwork pipeline with single-flight deduplication,
 * multi-source priority fetching, dual-resolution downsampling, and L1 RAM + L2 WebP disk caching.
 *
 * @remarks
 * Every artwork request is keyed by canonical album identity (`albumId` when valid, or a fixed
 * SHA-256 hash of `normalizedAlbumName + normalizedAlbumArtist`) paired with one of two resolution
 * buckets (`THUMBNAIL` at 224px for lists/grids/MiniPlayer, `HIGH_RES` at 960px for Now Playing).
 * Ref-counted in-flight deduplication ensures multiple visible songs from the same album trigger
 * exactly one disk/IPC decode, while scrolling all waiters off-screen cancels the shared job and
 * its `CancellationSignal` immediately. All I/O, downsampling, and WebP serialization execute
 * strictly on `Dispatchers.IO`.
 *
 * @see AlbumArtCache
 * @see AudioCoverFetcher
 * @see com.quandot.musicplayer.ui.components.AlbumArtImage
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.Player
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.debug.PDLog

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val DISK_CACHE_DIR_NAME = "album_art_cache"
private const val ALBUM_ART_BASE_URI = "content://media/external/audio/albumart"
private const val SYNTHETIC_ALBUM_ID_FLAG = 0x40000000_00000000L

private const val L1_HEAP_FRACTION = 0.13 // 13% of max available heap (within 12-15% target)
private const val L1_MIN_CACHE_KB = 8 * 1024 // 8 MB floor for low-RAM devices
private const val L1_MAX_CACHE_KB = 64 * 1024 // 64 MB ceiling to protect audio DSP & UI heap

private const val L2_DISK_CACHE_MAX_BYTES = 96L * 1024L * 1024L // 96 MB WebP disk cache cap (3 buckets)
private const val L2_DISK_CACHE_PRUNE_TARGET_BYTES = 76L * 1024L * 1024L
private const val L2_WEBP_QUALITY = 86
private const val L2_ATIME_TOUCH_INTERVAL_MS = 60L * 60L * 1000L
private const val L2_PRUNE_WRITE_INTERVAL = 24

private const val IO_DECODE_CONCURRENCY = 4
private const val MIN_SYSTEM_THUMB_RATIO = 0.75f
private const val MAX_FOLDER_CACHE_ENTRIES = 1024
private const val MAX_ALBUM_PROBE_TRACKS = 16
private const val BYTES_PER_KB = 1024
private const val HASH_HEX_LENGTH = 16

private val WHITESPACE_REGEX = Regex("\\s+")
private val ALBUM_ART_CONTENT_URI: Uri by lazy { Uri.parse(ALBUM_ART_BASE_URI) }

private val GENERIC_ALBUM_OR_FOLDER_NAMES = setOf(
    "download", "downloads", "music", "audio", "songs", "song",
    "recordings", "recording", "podcasts", "ringtones", "notifications",
    "alarms", "documents", "dcim", "pictures", "movies", "media",
    "bluetooth", "telegram", "whatsapp", "whatsapp audio", "zalo",
    "0", "sdcard", "internal storage", "external", "root",
    "<unknown>", "unknown", "unknown album", "album không tên", "album không xác định"
)

// ═══ Types & Contracts ═══

/**
 * Three-tier resolution buckets matching UI display densities:
 * - [THUMBNAIL] (224px) for compact 44-52dp rows, queue rows, and MiniPlayer.
 * - [CARD] (512px) for 110-185dp cards (Albums Grid, Songs Grid, Home Carousel, Detail Headers).
 * - [HIGH_RES] (1024px) for full-screen Now Playing artwork and Media3 notifications.
 */
enum class ArtResolutionBucket(
    val maxSizePx: Int,
    val diskTag: String
) {
    /** 224px (within 160-240px target) for compact song rows, queue rows, and MiniPlayer. */
    THUMBNAIL(maxSizePx = 224, diskTag = "thumb"),

    /** 512px for large cards (Albums Grid, Songs Grid, Home Album Carousel, Detail Headers). */
    CARD(maxSizePx = 512, diskTag = "card"),

    /** 1024px (within 800-1080px target) for Classic Now Playing artwork and Media3 notifications. */
    HIGH_RES(maxSizePx = 1024, diskTag = "hires"),

    /** 1500px ultra-crisp artwork for Modern edge-to-edge Now Playing cover display. */
    ULTRA_RES(maxSizePx = 1500, diskTag = "ultra")
}

/**
 * Immutable artwork request carrying the canonical deduplication key alongside local file and
 * MediaStore source hints. Tracks with an empty or generic album tag set [isTrackIsolated] so each
 * single resolves its own embedded art instead of sharing a synthetic folder-level album key.
 */
@Immutable
data class AlbumArtRequest(
    val albumKey: String,
    val albumId: Long = 0L,
    val albumName: String = "",
    val albumArtist: String = "",
    val songContentUri: Uri? = null,
    val albumArtUri: Uri? = null,
    val songPath: String? = null,
    val isTrackIsolated: Boolean = false
)

/**
 * Memory-resident entry pairing the underlying Android [Bitmap] with its lightweight Compose
 * [ImageBitmap] wrapper so Frame 0 rendering allocates zero extra pixel buffers.
 */
data class CachedArtEntry(
    val bitmap: Bitmap,
    val imageBitmap: ImageBitmap,
    val sizeKb: Int
)

private data class AlbumSourceHint(
    val albumId: Long,
    val albumArtUri: Uri?,
    val sampleSongUri: Uri?,
    val sampleSongPath: String?
)

private class InFlightDecode(
    val deferred: Deferred<CachedArtEntry?>,
    val waiters: AtomicInteger = AtomicInteger(1)
)

// ═══ Album Key Normalization & Model Extensions ═══

/**
 * Returns `true` when [name] is blank, a placeholder (`<unknown>`, `Album không tên`), or a
 * shared/generic media directory name (`Download`, `Music`, `Zalo`, etc.).
 */
fun isGenericAlbumOrFolderName(name: String?): Boolean {
    val normalized = name?.trim()?.lowercase(Locale.ROOT) ?: return true
    return normalized.isEmpty() || normalized in GENERIC_ALBUM_OR_FOLDER_NAMES
}

/**
 * Returns `true` when a track lacks a real album tag and must be keyed per track (`trk_*`) rather
 * than sharing an album-level cache slot with unrelated tracks in the same folder.
 */
fun isStandaloneTrackAlbum(albumName: String?, songPath: String? = null): Boolean {
    if (isGenericAlbumOrFolderName(albumName)) return true
    if (!songPath.isNullOrBlank()) {
        val lastSlash = songPath.lastIndexOf('/')
        val parentName = if (lastSlash > 0) {
            val prevSlash = songPath.lastIndexOf('/', lastSlash - 1)
            songPath.substring(prevSlash + 1, lastSlash).trim()
        } else {
            ""
        }
        if (parentName.isNotEmpty() &&
            isGenericAlbumOrFolderName(parentName) &&
            albumName.equals(parentName, ignoreCase = true)
        ) {
            return true
        }
    }
    return false
}

/**
 * Resolves a stable per-track key for standalone tracks whose album tag is blank/generic.
 */
fun computeTrackArtKey(
    songId: Long,
    songPath: String? = null,
    contentUriStr: String? = null
): String {
    if (songId > 0L) {
        return "trk_$songId"
    }
    val seed = normalizeMetadataToken(songPath?.takeIf { it.isNotBlank() } ?: contentUriStr)
        .ifEmpty { "unknown_track" }
    return "trk_h_${stableShortSha256(seed)}"
}

/**
 * Resolves the canonical artwork cache key for a song: isolated `trk_*` when its album tag is
 * blank/generic, or deduplicated `alb_*` for tracks belonging to a real album.
 */
fun computeSongArtworkKey(
    songId: Long,
    albumId: Long,
    albumName: String?,
    albumArtist: String?,
    songPath: String? = null,
    contentUriStr: String? = null
): String {
    if (isStandaloneTrackAlbum(albumName, songPath)) {
        return computeTrackArtKey(songId = songId, songPath = songPath, contentUriStr = contentUriStr)
    }
    return computeAlbumArtKey(
        albumId = albumId,
        albumName = albumName,
        albumArtist = albumArtist,
        fallbackUriOrPath = songPath?.takeIf { it.isNotBlank() } ?: contentUriStr
    )
}

/**
 * Resolves a stable album-level deduplication key from [albumId], falling back to a deterministic
 * SHA-256 hash of `normalizedAlbumName + normalizedAlbumArtist` when [albumId] is missing or invalid.
 */
fun computeAlbumArtKey(
    albumId: Long,
    albumName: String?,
    albumArtist: String?,
    fallbackUriOrPath: String? = null
): String {
    if (albumId > 0L) {
        return "alb_$albumId"
    }
    val normAlbum = normalizeMetadataToken(albumName)
    val normArtist = normalizeMetadataToken(albumArtist)
    val seed = if (normAlbum.isNotEmpty() || normArtist.isNotEmpty()) {
        "$normAlbum|$normArtist"
    } else {
        normalizeMetadataToken(fallbackUriOrPath).ifEmpty { "unknown_album" }
    }
    return "alb_h_${stableShortSha256(seed)}"
}

fun Song.toAlbumArtRequest(): AlbumArtRequest {
    val effectiveArtist = albumArtist.ifBlank { artist }
    val isolated = isStandaloneTrackAlbum(album, path)
    if (isolated) {
        val trackKey = computeTrackArtKey(
            songId = id,
            songPath = path,
            contentUriStr = contentUri.takeIf { it != Uri.EMPTY }?.toString()
        )
        return AlbumArtRequest(
            albumKey = trackKey,
            albumId = 0L,
            albumName = album,
            albumArtist = effectiveArtist,
            songContentUri = contentUri.takeIf { it != Uri.EMPTY },
            albumArtUri = null,
            songPath = path.takeIf { it.isNotBlank() },
            isTrackIsolated = true
        )
    }

    val validMediaStoreAlbumId = albumId > 0L && (albumId and SYNTHETIC_ALBUM_ID_FLAG) == 0L
    val mediaStoreAlbumArtUri = if (validMediaStoreAlbumId) {
        ContentUris.withAppendedId(ALBUM_ART_CONTENT_URI, albumId)
    } else {
        albumArtUri
    }
    val key = computeAlbumArtKey(
        albumId = albumId,
        albumName = album,
        albumArtist = effectiveArtist,
        fallbackUriOrPath = path.ifBlank { contentUri.toString() }
    )
    return AlbumArtRequest(
        albumKey = key,
        albumId = albumId,
        albumName = album,
        albumArtist = effectiveArtist,
        songContentUri = contentUri.takeIf { it != Uri.EMPTY },
        albumArtUri = mediaStoreAlbumArtUri,
        songPath = path.takeIf { it.isNotBlank() },
        isTrackIsolated = false
    )
}

fun Album.toAlbumArtRequest(): AlbumArtRequest {
    val genericAlbum = isGenericAlbumOrFolderName(title)
    val validMediaStoreAlbumId = !genericAlbum && id > 0L && (id and SYNTHETIC_ALBUM_ID_FLAG) == 0L
    val mediaStoreAlbumArtUri = if (genericAlbum) {
        null
    } else {
        albumArtUri ?: if (validMediaStoreAlbumId) {
            ContentUris.withAppendedId(ALBUM_ART_CONTENT_URI, id)
        } else {
            null
        }
    }
    val key = computeAlbumArtKey(
        albumId = id,
        albumName = title,
        albumArtist = artist,
        fallbackUriOrPath = sampleSongPath.ifBlank { sampleSongUri?.toString() }
    )
    return AlbumArtRequest(
        albumKey = key,
        albumId = if (genericAlbum) 0L else id,
        albumName = title,
        albumArtist = artist,
        songContentUri = sampleSongUri?.takeIf { it != Uri.EMPTY },
        albumArtUri = mediaStoreAlbumArtUri,
        songPath = sampleSongPath.takeIf { it.isNotBlank() },
        isTrackIsolated = genericAlbum
    )
}

// ═══ Album Art Manager ═══

class AlbumArtManager private constructor(private val appContext: Context) {

    companion object {
        @Volatile
        private var instance: AlbumArtManager? = null

        fun init(context: Context): AlbumArtManager {
            return getInstance(context)
        }

        fun getInstance(context: Context): AlbumArtManager {
            return instance ?: synchronized(this) {
                instance ?: AlbumArtManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ═══ Internal State & Caches ═══
    private val artExtractor = AlbumArtCache(appContext)
    private val diskCacheDir = File(appContext.cacheDir, DISK_CACHE_DIR_NAME)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioDecodeDispatcher = Dispatchers.IO.limitedParallelism(IO_DECODE_CONCURRENCY)
    private val managerScope = CoroutineScope(SupervisorJob() + ioDecodeDispatcher)

    private val l1MaxSizeKb: Int = computeL1CacheSizeKb()
    private val l1Cache = object : LruCache<String, CachedArtEntry>(l1MaxSizeKb) {
        override fun sizeOf(key: String, value: CachedArtEntry): Int = value.sizeKb
    }

    // Negative cache of albumKeys confirmed to have no artwork across all three priority sources
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()

    // Per-directory folder-cover lookup cache to avoid repeated filesystem probes during scrolling
    private val folderCoverCache = ConcurrentHashMap<String, Optional<File>>()

    // O(1) in-memory map from albumKey -> representative song URI/path hydrated from library snapshots
    private val albumSourceIndex = ConcurrentHashMap<String, AlbumSourceHint>()

    // Single-flight deduplication table keyed by "${albumKey}_${bucket.name}"
    private val inFlightDecodes = ConcurrentHashMap<String, InFlightDecode>()
    private val inFlightLock = Any()

    // Pinned entries for the currently playing track so closing & reopening NowPlayingSheet
    // (after library scrolling or background TRIM_MEMORY_UI_HIDDEN) always hits L1 on Frame 0
    private val pinnedLock = Any()
    @Volatile
    private var pinnedAlbumKey: String? = null
    private val pinnedEntries = ConcurrentHashMap<ArtResolutionBucket, CachedArtEntry>()

    // Cached ambient colors derived exclusively from L1 THUMBNAIL bitmaps (never extra decodes)
    private val ambientColorCache = ConcurrentHashMap<String, Color>()

    private val diskWriteCounter = AtomicInteger(0)
    private val versionCounter = AtomicLong(0L)
    private val _cacheVersion = MutableStateFlow(0L)
    val cacheVersion: StateFlow<Long> = _cacheVersion.asStateFlow()

    @Volatile
    private var queuePreloadJob: Job? = null

    // ═══ Library Source Indexing ═══

    /**
     * Populates O(1) representative track hints per album so album-grid cards resolve local folder
     * artwork (Priority 1) without issuing MediaStore queries while scrolling.
     */
    fun indexLibrarySources(songs: List<Song>, albums: List<Album>) {
        val nextIndex = HashMap<String, AlbumSourceHint>(albums.size.coerceAtLeast(16))
        for (song in songs) {
            val req = song.toAlbumArtRequest()
            if (!nextIndex.containsKey(req.albumKey)) {
                nextIndex[req.albumKey] = AlbumSourceHint(
                    albumId = song.albumId,
                    albumArtUri = req.albumArtUri,
                    sampleSongUri = req.songContentUri,
                    sampleSongPath = req.songPath
                )
            }
        }
        for (album in albums) {
            val req = album.toAlbumArtRequest()
            if (!nextIndex.containsKey(req.albumKey)) {
                nextIndex[req.albumKey] = AlbumSourceHint(
                    albumId = album.id,
                    albumArtUri = req.albumArtUri,
                    sampleSongUri = req.songContentUri,
                    sampleSongPath = req.songPath
                )
            }
        }
        albumSourceIndex.clear()
        albumSourceIndex.putAll(nextIndex)
    }

    // ═══ Synchronous L1 Reads (Safe for Main Thread Frame 0) ═══

    /**
     * Pins cached artwork buckets for the currently playing [albumKey] so LRU eviction while
     * browsing the library or `TRIM_MEMORY_UI_HIDDEN` never causes Now Playing to flash its
     * fallback gradient when reopened.
     */
    fun setPinnedAlbumKey(albumKey: String?) {
        synchronized(pinnedLock) {
            if (pinnedAlbumKey == albumKey) return
            pinnedAlbumKey = albumKey
            pinnedEntries.clear()
            if (albumKey != null) {
                for (bucket in ArtResolutionBucket.entries) {
                    val existing = l1Cache.get(compositeKey(albumKey, bucket))
                    if (existing != null && !existing.bitmap.isRecycled) {
                        pinnedEntries[bucket] = existing
                    }
                }
            }
        }
    }

    fun getFromL1(albumKey: String, bucket: ArtResolutionBucket): CachedArtEntry? {
        val compKey = compositeKey(albumKey, bucket)
        val entry = l1Cache.get(compKey)
        if (entry != null) {
            if (entry.bitmap.isRecycled) {
                l1Cache.remove(compKey)
                return null
            }
            return entry
        }
        if (albumKey == pinnedAlbumKey) {
            val pinned = synchronized(pinnedLock) {
                if (albumKey == pinnedAlbumKey) pinnedEntries[bucket] else null
            }
            if (pinned != null) {
                if (pinned.bitmap.isRecycled) {
                    synchronized(pinnedLock) {
                        if (albumKey == pinnedAlbumKey) pinnedEntries.remove(bucket)
                    }
                    return null
                }
                l1Cache.put(compKey, pinned)
                return pinned
            }
        }
        return null
    }

    fun getCachedImageBitmap(albumKey: String, bucket: ArtResolutionBucket): ImageBitmap? {
        return getFromL1(albumKey, bucket)?.imageBitmap
    }

    /**
     * Returns the compact L1 `THUMBNAIL` [Bitmap] for [albumKey] so blurred backgrounds or color
     * extractors reuse already-decoded RAM art instead of decoding an extra bitmap.
     */
    fun getCachedThumbnailBitmap(albumKey: String): Bitmap? {
        return getFromL1(albumKey, ArtResolutionBucket.THUMBNAIL)?.bitmap
    }

    fun isKnownMissing(albumKey: String): Boolean = negativeCache.contains(albumKey)

    // ═══ Asynchronous Deduplicated Load Pipeline ═══

    /**
     * Loads and caches album artwork for [request] at [bucket] resolution on `Dispatchers.IO`.
     *
     * Concurrent requests sharing `(request.albumKey, bucket)` attach to a single in-flight job.
     * Cancelling the caller decrements the waiter count and cancels the underlying I/O job once
     * no active callers remain.
     */
    suspend fun loadEntry(
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): CachedArtEntry? {
        val compKey = compositeKey(request.albumKey, bucket)

        // 1. L1 RAM fast path
        getFromL1(request.albumKey, bucket)?.let { return it }

        // 2. Negative cache fast path
        if (negativeCache.contains(request.albumKey)) {
            return null
        }

        // 3. Single-flight in-flight deduplication with ref-counted cancellation
        val enriched = enrichRequest(request)
        val inFlight = acquireOrCreateInFlight(compKey, enriched, bucket)

        return try {
            inFlight.deferred.await()
        } catch (ce: CancellationException) {
            releaseInFlightOnCancel(compKey, inFlight)
            throw ce
        }
    }

    suspend fun loadImageBitmap(
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): ImageBitmap? = loadEntry(request, bucket)?.imageBitmap

    suspend fun loadBitmap(
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): Bitmap? = loadEntry(request, bucket)?.bitmap

    /**
     * Resolves a raw `Uri` (e.g., from Coil or Media3 notifications) through the unified pipeline.
     */
    suspend fun loadBitmapForUri(
        uri: Uri,
        bucket: ArtResolutionBucket,
        albumIdHint: Long = 0L,
        albumNameHint: String = "",
        albumArtistHint: String = "",
        songPathHint: String? = null
    ): Bitmap? = withContext(ioDecodeDispatcher) {
        val request = buildRequestFromUri(
            uri = uri,
            albumIdHint = albumIdHint,
            albumNameHint = albumNameHint,
            albumArtistHint = albumArtistHint,
            songPathHint = songPathHint
        )
        loadBitmap(request, bucket)
    }

    // ═══ Preloading & Ambient Color Reuse ═══

    /**
     * Fire-and-forget background preload into L1 + L2 cache on `Dispatchers.IO`.
     */
    fun preload(request: AlbumArtRequest, bucket: ArtResolutionBucket): Job? {
        if (getFromL1(request.albumKey, bucket) != null || negativeCache.contains(request.albumKey)) {
            return null
        }
        return managerScope.launch {
            try {
                loadEntry(request, bucket)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Proactively preloads `HIGH_RES` artwork for immediate `nextTrack` and `previousTrack` in
     * [queue] into L1 RAM so track skips and swipes in Now Playing render at 0 ms.
     */
    fun preloadQueueNeighbors(
        queue: List<Song>,
        currentIndex: Int,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        bucket: ArtResolutionBucket = ArtResolutionBucket.HIGH_RES
    ) {
        queuePreloadJob?.cancel()
        if (queue.size <= 1 || currentIndex !in queue.indices) return

        val nextIdx = when {
            currentIndex + 1 < queue.size -> currentIndex + 1
            repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> -1
        }
        val prevIdx = when {
            currentIndex - 1 >= 0 -> currentIndex - 1
            repeatMode == Player.REPEAT_MODE_ALL -> queue.lastIndex
            else -> -1
        }

        val targets = LinkedHashMap<String, AlbumArtRequest>(2)
        queue.getOrNull(nextIdx)?.toAlbumArtRequest()?.let { targets[it.albumKey] = it }
        queue.getOrNull(prevIdx)?.toAlbumArtRequest()?.let { targets.putIfAbsent(it.albumKey, it) }
        if (targets.isEmpty()) return

        queuePreloadJob = managerScope.launch {
            for (req in targets.values) {
                ensureActive()
                if (getFromL1(req.albumKey, bucket) == null &&
                    !negativeCache.contains(req.albumKey)
                ) {
                    try {
                        loadEntry(req, bucket)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * Extracts a representative dominant color directly from the compact L1 `THUMBNAIL` bitmap on
     * `Dispatchers.Default` (loading `THUMBNAIL` only if absent), avoiding any duplicate decode.
     */
    suspend fun extractDominantColorFromThumbnail(request: AlbumArtRequest): Color? {
        ambientColorCache[request.albumKey]?.let { return it }
        val thumbBitmap = getCachedThumbnailBitmap(request.albumKey)
            ?: loadBitmap(request, ArtResolutionBucket.THUMBNAIL)
            ?: return null

        return withContext(Dispatchers.Default) {
            if (thumbBitmap.isRecycled) return@withContext null
            val color = sampleDominantColor(thumbBitmap)
            if (color != null) {
                ambientColorCache[request.albumKey] = color
            }
            color
        }
    }

    // ═══ Cache Invalidation & Lifecycle Management ═══

    /**
     * Actively invalidates a single album's L1 RAM entries, L2 WebP disk files, negative-cache
     * marker, and ambient color entry (e.g., after editing tags or deleting/updating a track).
     */
    fun invalidateAlbumArt(albumKey: String) {
        for (bucket in ArtResolutionBucket.entries) {
            l1Cache.remove(compositeKey(albumKey, bucket))
        }
        synchronized(pinnedLock) {
            if (pinnedAlbumKey == albumKey) {
                pinnedEntries.clear()
            }
        }
        negativeCache.remove(albumKey)
        ambientColorCache.remove(albumKey)
        folderCoverCache.clear()

        val deleteDiskAndBump = {
            for (bucket in ArtResolutionBucket.entries) {
                val file = diskCacheFile(albumKey, bucket)
                if (file.exists()) {
                    runCatching { file.delete() }
                }
            }
            bumpCacheVersion()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            managerScope.launch { deleteDiskAndBump() }
        } else {
            deleteDiskAndBump()
        }
    }

    fun invalidateAlbumArt(
        albumId: Long,
        albumName: String? = null,
        albumArtist: String? = null
    ) {
        invalidateAlbumArt(computeAlbumArtKey(albumId, albumName, albumArtist))
    }

    /**
     * Refreshes album art caches when the user triggers a library rescan so updated embedded tags
     * or newly added folder covers appear immediately.
     */
    fun invalidateAllForRescan() {
        synchronized(pinnedLock) {
            pinnedEntries.clear()
        }
        negativeCache.clear()
        folderCoverCache.clear()
        ambientColorCache.clear()
        l1Cache.evictAll()

        val purgeDiskAndBump = {
            runCatching {
                if (diskCacheDir.exists()) {
                    diskCacheDir.listFiles()?.forEach { it.delete() }
                }
            }
            bumpCacheVersion()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            managerScope.launch { purgeDiskAndBump() }
        } else {
            purgeDiskAndBump()
        }
    }

    fun clearAll() {
        queuePreloadJob?.cancel()
        invalidateAllForRescan()
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        // Note: TRIM_MEMORY_UI_HIDDEN (20) > TRIM_MEMORY_RUNNING_CRITICAL (15), so handle
        // UI_HIDDEN explicitly instead of wiping L1 every time the app goes to background.
        when {
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                l1Cache.evictAll()
                ambientColorCache.clear()
                folderCoverCache.clear()
                // Under severe memory pressure, release large pinned buckets while keeping the
                // lightweight THUMBNAIL (or CARD fallback) so reopening Now Playing still has a Frame 0 backdrop.
                synchronized(pinnedLock) {
                    val keepThumb = pinnedEntries[ArtResolutionBucket.THUMBNAIL]
                        ?.takeIf { !it.bitmap.isRecycled }
                    val keepCard = if (keepThumb == null) {
                        pinnedEntries[ArtResolutionBucket.CARD]?.takeIf { !it.bitmap.isRecycled }
                    } else null
                    pinnedEntries.clear()
                    if (keepThumb != null) {
                        pinnedEntries[ArtResolutionBucket.THUMBNAIL] = keepThumb
                    } else if (keepCard != null) {
                        pinnedEntries[ArtResolutionBucket.CARD] = keepCard
                    }
                }
            }
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                l1Cache.trimToSize(l1MaxSizeKb / 2)
            }
        }
    }

    fun getDiskCacheSizeBytes(): Long {
        if (!diskCacheDir.exists()) return 0L
        var total = 0L
        runCatching {
            diskCacheDir.listFiles()?.forEach { file ->
                if (file.isFile) total += file.length()
            }
        }
        return total
    }

    fun formatDiskCacheSize(): String = artExtractor.formatCacheSize(getDiskCacheSizeBytes())

    // ═══ Core Fetch & Decode Pipeline (Dispatchers.IO) ═══

    private fun acquireOrCreateInFlight(
        compKey: String,
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): InFlightDecode {
        synchronized(inFlightLock) {
            val existing = inFlightDecodes[compKey]
            if (existing != null && !existing.deferred.isCancelled && !existing.deferred.isCompleted) {
                existing.waiters.incrementAndGet()
                return existing
            }
            val deferred = managerScope.async {
                executeFetchAndDecode(request, bucket)
            }
            val created = InFlightDecode(deferred)
            inFlightDecodes[compKey] = created
            deferred.invokeOnCompletion {
                synchronized(inFlightLock) {
                    if (inFlightDecodes[compKey] === created) {
                        inFlightDecodes.remove(compKey)
                    }
                }
            }
            return created
        }
    }

    private fun releaseInFlightOnCancel(compKey: String, inFlight: InFlightDecode) {
        synchronized(inFlightLock) {
            if (inFlight.waiters.decrementAndGet() <= 0) {
                inFlight.deferred.cancel()
                if (inFlightDecodes[compKey] === inFlight) {
                    inFlightDecodes.remove(compKey)
                }
            }
        }
    }

    private suspend fun executeFetchAndDecode(
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): CachedArtEntry? = withContext(ioDecodeDispatcher) {
        val compKey = compositeKey(request.albumKey, bucket)

        // Double-check L1 after acquiring slot
        getFromL1(request.albumKey, bucket)?.let { return@withContext it }
        if (negativeCache.contains(request.albumKey)) return@withContext null

        currentCoroutineContext().ensureActive()

        // 1. L2 Disk Cache (<5ms pre-downsampled WebP read, no source file open required)
        decodeFromL2DiskCache(request.albumKey, bucket)?.let { diskBitmap ->
            val entry = putIntoL1(request.albumKey, bucket, diskBitmap)
            return@withContext entry
        }

        currentCoroutineContext().ensureActive()

        // 2. Multi-Source Priority Fetcher (Folder Art -> System Thumbnail -> Embedded Metadata)
        val decodedBitmap = fetchFromMultiSourcePriority(request, bucket)
        currentCoroutineContext().ensureActive()

        if (decodedBitmap == null) {
            negativeCache.add(request.albumKey)
            return@withContext null
        }

        // Populate L1 immediately, then persist compressed WebP into L2 cacheDir/album_art_cache
        val entry = putIntoL1(request.albumKey, bucket, decodedBitmap)
        writeToL2DiskCache(request.albumKey, bucket, decodedBitmap)

        // If CARD or HIGH_RES was decoded and THUMBNAIL is not yet in L1, derive a lightweight
        // thumbnail in memory so transitioning to Compact Lists / MiniPlayer stays 0ms with zero I/O
        if (bucket != ArtResolutionBucket.THUMBNAIL &&
            getFromL1(request.albumKey, ArtResolutionBucket.THUMBNAIL) == null
        ) {
            runCatching {
                val derivedThumb = scaleDownIfNeeded(decodedBitmap, ArtResolutionBucket.THUMBNAIL.maxSizePx, recycleSource = false)
                if (derivedThumb !== decodedBitmap) {
                    putIntoL1(request.albumKey, ArtResolutionBucket.THUMBNAIL, derivedThumb)
                    writeToL2DiskCache(request.albumKey, ArtResolutionBucket.THUMBNAIL, derivedThumb)
                }
            }
        }

        entry
    }

    /**
     * Multi-source priority fetcher executed strictly on `Dispatchers.IO`:
     * - For normal albums: Folder Art -> System Thumbnail (when >= 75% of bucket target) -> Embedded Picture.
     * - For standalone/empty-album tracks ([AlbumArtRequest.isTrackIsolated]): Embedded Picture from
     *   this exact file -> Song-URI-only System Thumbnail -> Standard Folder Art (never MediaStore
     *   folder-level `albumart/{albumId}` or sibling tracks).
     */
    private suspend fun fetchFromMultiSourcePriority(
        request: AlbumArtRequest,
        bucket: ArtResolutionBucket
    ): Bitmap? {
        val targetPx = bucket.maxSizePx
        val minAcceptableSystemPx = (targetPx * MIN_SYSTEM_THUMB_RATIO).roundToInt()
        val resolvedPath = resolveSongPath(request)
        val resolvedSongUri = resolveSongUri(request)

        if (request.isTrackIsolated) {
            // ── Isolated Track Priority 1: Embedded Picture strictly from this file ──
            currentCoroutineContext().ensureActive()
            if (resolvedSongUri != null || !resolvedPath.isNullOrBlank()) {
                val embeddedBytes = runCatching {
                    artExtractor.extractEmbeddedBytes(resolvedSongUri, resolvedPath)
                }.getOrNull()
                if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    decodeByteArrayDownsampled(embeddedBytes, targetPx)?.let { return it }
                }
            }

            // ── Isolated Track Priority 2: System Thumbnail for this song URI only ──
            currentCoroutineContext().ensureActive()
            fetchSystemThumbnail(request, resolvedSongUri, targetPx, songUriOnly = true)?.let { bmp ->
                return scaleDownIfNeeded(bmp, targetPx, recycleSource = true)
            }

            // ── Isolated Track Priority 3: Standard cover.* in non-generic parent folder ──
            currentCoroutineContext().ensureActive()
            if (!resolvedPath.isNullOrBlank()) {
                findFolderCoverCached(resolvedPath)?.let { coverFile ->
                    decodeFileDownsampled(coverFile, targetPx)?.let { return it }
                }
            }
            return null
        }

        // ── Priority 1: Folder Art ──
        currentCoroutineContext().ensureActive()
        if (!resolvedPath.isNullOrBlank()) {
            findFolderCoverCached(resolvedPath)?.let { coverFile ->
                decodeFileDownsampled(coverFile, targetPx)?.let { return it }
            }
        }

        // ── Priority 2: System Thumbnail (ContentResolver.loadThumbnail on API 29+) ──
        currentCoroutineContext().ensureActive()
        var systemThumbFallback: Bitmap? = null
        val systemThumb = fetchSystemThumbnail(request, resolvedSongUri, targetPx, songUriOnly = false)
        if (systemThumb != null) {
            if (bucket == ArtResolutionBucket.THUMBNAIL ||
                max(systemThumb.width, systemThumb.height) >= minAcceptableSystemPx
            ) {
                return scaleDownIfNeeded(systemThumb, targetPx, recycleSource = true)
            }
            // Many OEM MediaStore providers cap loadThumbnail at 256-384px; prefer crisp embedded
            // art for CARD (512px) and HIGH_RES (1024px) before falling back to this thumbnail.
            systemThumbFallback = systemThumb
        }

        // ── Priority 3: Embedded Picture (MediaMetadataRetriever + safe container fallback) ──
        currentCoroutineContext().ensureActive()
        val embeddedBytes = extractEmbeddedBytesSafely(resolvedSongUri, resolvedPath, request.albumId)
        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            decodeByteArrayDownsampled(embeddedBytes, targetPx)?.let { decoded ->
                if (systemThumbFallback != null && !systemThumbFallback.isRecycled) {
                    systemThumbFallback.recycle()
                }
                return decoded
            }
        }

        // Fall back to the smaller system thumbnail if embedded extraction yielded nothing
        return systemThumbFallback?.let { scaleDownIfNeeded(it, targetPx, recycleSource = true) }
    }

    // ═══ Priority Source Helpers ═══

    private fun findFolderCoverCached(songPath: String): File? {
        val parentPath = File(songPath).parent ?: return null
        folderCoverCache[parentPath]?.let { cached ->
            val file = cached.orElse(null)
            if (file == null || (file.exists() && file.length() > 0L)) {
                return file
            }
            folderCoverCache.remove(parentPath)
        }
        val found = artExtractor.findFolderCoverFile(songPath)
        if (folderCoverCache.size >= MAX_FOLDER_CACHE_ENTRIES) {
            folderCoverCache.clear()
        }
        folderCoverCache[parentPath] = Optional.ofNullable(found)
        return found
    }

    private suspend fun fetchSystemThumbnail(
        request: AlbumArtRequest,
        resolvedSongUri: Uri?,
        targetPx: Int,
        songUriOnly: Boolean
    ): Bitmap? {
        val resolver = appContext.contentResolver
        val candidateUris = ArrayList<Uri>(3)
        if (!songUriOnly) {
            request.albumArtUri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }?.let { candidateUris.add(it) }
            if (request.albumId > 0L && (request.albumId and SYNTHETIC_ALBUM_ID_FLAG) == 0L) {
                val albumsTableUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    request.albumId
                )
                if (albumsTableUri !in candidateUris) candidateUris.add(albumsTableUri)
            }
        }
        resolvedSongUri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }?.let {
            if (it !in candidateUris) candidateUris.add(it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val job = currentCoroutineContext()[Job]
            for (uri in candidateUris) {
                currentCoroutineContext().ensureActive()
                val signal = CancellationSignal()
                val cancelHandle = job?.invokeOnCompletion { signal.cancel() }
                try {
                    val bmp = resolver.loadThumbnail(uri, Size(targetPx, targetPx), signal)
                    if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                        return ensureSoftwareRgb565IfOpaque(bmp)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Missing thumbnail or unsupported provider; continue fallback chain
                } finally {
                    cancelHandle?.dispose()
                }
            }
        } else if (!songUriOnly) {
            // API 26-28 fallback: read MediaStore albumart stream and downsample with inJustDecodeBounds
            val albumUri = request.albumArtUri?.takeIf {
                it.scheme == ContentResolver.SCHEME_CONTENT && it.toString().contains("albumart")
            }
            if (albumUri != null) {
                val bytes = runCatching {
                    resolver.openInputStream(albumUri)?.use { it.readBytes() }
                }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    decodeByteArrayDownsampled(bytes, targetPx)?.let { return it }
                }
            }
        }
        return null
    }

    private suspend fun extractEmbeddedBytesSafely(
        songUri: Uri?,
        songPath: String?,
        albumId: Long
    ): ByteArray? {
        if (songUri != null || !songPath.isNullOrBlank()) {
            runCatching { artExtractor.extractEmbeddedBytes(songUri, songPath) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }

        // Last-resort probe of representative tracks for an album when the initial track had no art
        if (albumId > 0L && (albumId and SYNTHETIC_ALBUM_ID_FLAG) == 0L) {
            val resolver = appContext.contentResolver
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA)
            val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ? AND ${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val selectionArgs = arrayOf(albumId.toString())
            val candidates = ArrayList<Pair<Long, String?>>(MAX_ALBUM_PROBE_TRACKS)
            runCatching {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext() && candidates.size < MAX_ALBUM_PROBE_TRACKS) {
                        candidates.add(cursor.getLong(0) to cursor.getString(1))
                    }
                }
            }
            for ((candId, candPath) in candidates) {
                currentCoroutineContext().ensureActive()
                if (candPath == songPath) continue
                val candUri = if (candId > 0L) {
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, candId)
                } else null
                runCatching { artExtractor.extractEmbeddedBytes(candUri, candPath) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return null
    }

    // ═══ Smart Downsampling (inJustDecodeBounds + inSampleSize) ═══

    private fun decodeFileDownsampled(file: File, maxSizePx: Int): Bitmap? {
        if (!file.exists() || !file.canRead() || file.length() <= 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            scaleDownIfNeeded(decoded, maxSizePx, recycleSource = true)
        } catch (_: OutOfMemoryError) {
            PDLog.w(PDLog.ART_CACHE, "ART_OOM_TRIM", "src=file targetKb=${l1MaxSizeKb / 2}")
            l1Cache.trimToSize(l1MaxSizeKb / 2)
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeByteArrayDownsampled(bytes: ByteArray, maxSizePx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxSizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
            scaleDownIfNeeded(decoded, maxSizePx, recycleSource = true)
        } catch (_: OutOfMemoryError) {
            PDLog.w(PDLog.ART_CACHE, "ART_OOM_TRIM", "src=bytes len=${bytes.size} targetKb=${l1MaxSizeKb / 2}")
            l1Cache.trimToSize(l1MaxSizeKb / 2)
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqSizePx: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqSizePx || srcWidth > reqSizePx) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqSizePx &&
                (halfWidth / inSampleSize) >= reqSizePx
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaleDownIfNeeded(
        source: Bitmap,
        maxSizePx: Int,
        recycleSource: Boolean
    ): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim <= maxSizePx || maxDim <= 0) return source
        val ratio = maxSizePx.toFloat() / maxDim.toFloat()
        val dstW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val dstH = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(source, dstW, dstH, true)
            if (recycleSource && scaled !== source) {
                source.recycle()
            }
            scaled
        } catch (_: OutOfMemoryError) {
            source
        }
    }

    private fun ensureSoftwareRgb565IfOpaque(bitmap: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return runCatching {
                val copy = bitmap.copy(Bitmap.Config.RGB_565, false)
                bitmap.recycle()
                copy
            }.getOrDefault(bitmap)
        }
        return bitmap
    }

    // ═══ L1 & L2 WebP Disk Cache Helpers ═══

    private fun putIntoL1(albumKey: String, bucket: ArtResolutionBucket, bitmap: Bitmap): CachedArtEntry {
        val sizeKb = (bitmap.allocationByteCount / BYTES_PER_KB).coerceAtLeast(1)
        val entry = CachedArtEntry(
            bitmap = bitmap,
            imageBitmap = bitmap.asImageBitmap(),
            sizeKb = sizeKb
        )
        l1Cache.put(compositeKey(albumKey, bucket), entry)
        if (albumKey == pinnedAlbumKey) {
            synchronized(pinnedLock) {
                if (albumKey == pinnedAlbumKey) {
                    pinnedEntries[bucket] = entry
                }
            }
        }
        return entry
    }

    private fun diskCacheFile(albumKey: String, bucket: ArtResolutionBucket): File {
        return File(diskCacheDir, "${albumKey}_${bucket.diskTag}.webp")
    }

    private fun decodeFromL2DiskCache(albumKey: String, bucket: ArtResolutionBucket): Bitmap? {
        val file = diskCacheFile(albumKey, bucket)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                val now = System.currentTimeMillis()
                if (now - file.lastModified() > L2_ATIME_TOUCH_INTERVAL_MS) {
                    runCatching { file.setLastModified(now) }
                }
                bitmap
            } else {
                // Evict corrupt partial cache entry
                runCatching { file.delete() }
                null
            }
        } catch (_: OutOfMemoryError) {
            PDLog.w(PDLog.ART_CACHE, "ART_OOM_TRIM", "src=l2_disk bucket=${bucket.diskTag} targetKb=${l1MaxSizeKb / 2}")
            l1Cache.trimToSize(l1MaxSizeKb / 2)
            null
        } catch (_: Throwable) {
            runCatching { file.delete() }
            null
        }
    }

    private fun writeToL2DiskCache(albumKey: String, bucket: ArtResolutionBucket, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        try {
            if (!diskCacheDir.exists()) {
                diskCacheDir.mkdirs()
            }
            val targetFile = diskCacheFile(albumKey, bucket)
            val tmpFile = File(diskCacheDir, "${targetFile.name}.${Thread.currentThread().id}.tmp")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val compressed = FileOutputStream(tmpFile).use { out ->
                bitmap.compress(format, L2_WEBP_QUALITY, out)
            }
            if (compressed && tmpFile.length() > 0L) {
                if (targetFile.exists()) targetFile.delete()
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.delete()
                }
            } else {
                tmpFile.delete()
            }

            if (diskWriteCounter.incrementAndGet() % L2_PRUNE_WRITE_INTERVAL == 0) {
                pruneL2DiskCacheIfNeeded()
            }
        } catch (_: Throwable) {
            // Disk cache write is best-effort; never fail UI artwork rendering on low storage
        }
    }

    private fun pruneL2DiskCacheIfNeeded() {
        runCatching {
            val files = diskCacheDir.listFiles()?.filter { it.isFile } ?: return
            var totalBytes = 0L
            for (f in files) totalBytes += f.length()
            if (totalBytes <= L2_DISK_CACHE_MAX_BYTES) return

            val sortedByOldest = files.sortedBy { it.lastModified() }
            for (f in sortedByOldest) {
                if (totalBytes <= L2_DISK_CACHE_PRUNE_TARGET_BYTES) break
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                }
            }
        }
    }

    // ═══ Request Enrichment & Resolution Helpers ═══

    private fun enrichRequest(request: AlbumArtRequest): AlbumArtRequest {
        val hint = albumSourceIndex[request.albumKey] ?: return request
        if (!request.songPath.isNullOrBlank() &&
            request.songContentUri != null &&
            (request.isTrackIsolated || request.albumArtUri != null)
        ) {
            return request
        }
        return request.copy(
            albumId = if (request.isTrackIsolated) 0L else if (request.albumId > 0L) request.albumId else hint.albumId,
            albumArtUri = if (request.isTrackIsolated) null else (request.albumArtUri ?: hint.albumArtUri),
            songContentUri = request.songContentUri ?: hint.sampleSongUri,
            songPath = request.songPath?.takeIf { it.isNotBlank() } ?: hint.sampleSongPath
        )
    }

    private fun resolveSongPath(request: AlbumArtRequest): String? {
        if (!request.songPath.isNullOrBlank()) return request.songPath
        albumSourceIndex[request.albumKey]?.sampleSongPath?.takeIf { it.isNotBlank() }?.let { return it }
        val songUri = request.songContentUri ?: return null
        if (songUri.scheme == ContentResolver.SCHEME_FILE) return songUri.path
        return querySongPathFromMediaStore(songUri)
    }

    private fun resolveSongUri(request: AlbumArtRequest): Uri? {
        if (request.songContentUri != null && request.songContentUri != Uri.EMPTY) {
            return request.songContentUri
        }
        return albumSourceIndex[request.albumKey]?.sampleSongUri
    }

    private fun buildRequestFromUri(
        uri: Uri,
        albumIdHint: Long,
        albumNameHint: String,
        albumArtistHint: String,
        songPathHint: String?
    ): AlbumArtRequest {
        val cleanUri = if (!uri.query.isNullOrEmpty()) uri.buildUpon().clearQuery().build() else uri
        val uriStr = cleanUri.toString()
        val isSongMediaUri = uriStr.contains("/audio/media/") || cleanUri.scheme == ContentResolver.SCHEME_FILE
        val resolvedHintPath = songPathHint ?: if (cleanUri.scheme == ContentResolver.SCHEME_FILE) cleanUri.path else null

        // Isolate tracks with empty/generic album metadata so notifications/Coil never bleed
        // MediaStore's folder-level albumart across unrelated singles.
        if (isSongMediaUri && albumNameHint.isNotBlank() && isStandaloneTrackAlbum(albumNameHint, resolvedHintPath)) {
            val parsedSongId = if (uriStr.contains("/audio/media/")) {
                runCatching { ContentUris.parseId(cleanUri) }.getOrDefault(0L)
            } else 0L
            val trackKey = computeTrackArtKey(parsedSongId, resolvedHintPath, uriStr)
            return enrichRequest(
                AlbumArtRequest(
                    albumKey = trackKey,
                    albumId = 0L,
                    albumName = albumNameHint,
                    albumArtist = albumArtistHint,
                    songContentUri = cleanUri,
                    albumArtUri = null,
                    songPath = resolvedHintPath,
                    isTrackIsolated = true
                )
            )
        }

        if (albumIdHint > 0L) {
            val isSynthetic = (albumIdHint and SYNTHETIC_ALBUM_ID_FLAG) != 0L
            val key = computeAlbumArtKey(albumIdHint, albumNameHint, albumArtistHint, uriStr)
            val resolvedAlbumArtUri = when {
                uriStr.contains("albumart") -> cleanUri
                !isSynthetic -> ContentUris.withAppendedId(ALBUM_ART_CONTENT_URI, albumIdHint)
                else -> null
            }
            return enrichRequest(
                AlbumArtRequest(
                    albumKey = key,
                    albumId = albumIdHint,
                    albumName = albumNameHint,
                    albumArtist = albumArtistHint,
                    songContentUri = if (isSongMediaUri) cleanUri else null,
                    albumArtUri = resolvedAlbumArtUri,
                    songPath = resolvedHintPath
                )
            )
        }

        if (uriStr.contains("albumart")) {
            val parsedAlbumId = runCatching { ContentUris.parseId(cleanUri) }.getOrDefault(0L)
            val key = computeAlbumArtKey(parsedAlbumId, albumNameHint, albumArtistHint, uriStr)
            return enrichRequest(
                AlbumArtRequest(
                    albumKey = key,
                    albumId = parsedAlbumId,
                    albumName = albumNameHint,
                    albumArtist = albumArtistHint,
                    albumArtUri = cleanUri,
                    songPath = resolvedHintPath
                )
            )
        }

        // Resolve MediaStore song URI -> albumId for strict album-level deduplication (unless standalone)
        if (cleanUri.scheme == ContentResolver.SCHEME_CONTENT && uriStr.contains("/audio/media/")) {
            val meta = querySongMetaFromMediaStore(cleanUri)
            if (meta != null) {
                val effectivePath = resolvedHintPath ?: meta.path
                val effectiveAlbum = albumNameHint.ifBlank { meta.albumName.orEmpty() }
                if (isStandaloneTrackAlbum(effectiveAlbum, effectivePath)) {
                    val parsedSongId = runCatching { ContentUris.parseId(cleanUri) }.getOrDefault(0L)
                    val trackKey = computeTrackArtKey(parsedSongId, effectivePath, uriStr)
                    return enrichRequest(
                        AlbumArtRequest(
                            albumKey = trackKey,
                            albumId = 0L,
                            albumName = effectiveAlbum,
                            albumArtist = albumArtistHint.ifBlank { meta.artist.orEmpty() },
                            songContentUri = cleanUri,
                            albumArtUri = null,
                            songPath = effectivePath,
                            isTrackIsolated = true
                        )
                    )
                }
                val key = computeAlbumArtKey(meta.albumId, effectiveAlbum, meta.artist, uriStr)
                val albumArtUri = if (meta.albumId > 0L) {
                    ContentUris.withAppendedId(ALBUM_ART_CONTENT_URI, meta.albumId)
                } else null
                return enrichRequest(
                    AlbumArtRequest(
                        albumKey = key,
                        albumId = meta.albumId,
                        albumName = effectiveAlbum,
                        albumArtist = albumArtistHint.ifBlank { meta.artist.orEmpty() },
                        songContentUri = cleanUri,
                        albumArtUri = albumArtUri,
                        songPath = effectivePath
                    )
                )
            }
        }

        val standaloneFallback = isSongMediaUri && isStandaloneTrackAlbum(albumNameHint, resolvedHintPath)
        val key = if (standaloneFallback) {
            computeTrackArtKey(0L, resolvedHintPath, uriStr)
        } else {
            computeAlbumArtKey(0L, albumNameHint, albumArtistHint, uriStr)
        }
        return AlbumArtRequest(
            albumKey = key,
            albumId = 0L,
            albumName = albumNameHint,
            albumArtist = albumArtistHint,
            songContentUri = cleanUri,
            albumArtUri = if (standaloneFallback) null else cleanUri,
            songPath = resolvedHintPath,
            isTrackIsolated = standaloneFallback
        )
    }

    private data class QueriedSongMeta(
        val albumId: Long,
        val albumName: String?,
        val artist: String?,
        val path: String?
    )

    private fun querySongMetaFromMediaStore(songUri: Uri): QueriedSongMeta? {
        val projection = arrayOf(
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA
        )
        return runCatching {
            appContext.contentResolver.query(songUri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    QueriedSongMeta(
                        albumId = c.getLong(0),
                        albumName = c.getString(1),
                        artist = c.getString(2),
                        path = c.getString(3)
                    )
                } else null
            }
        }.getOrNull()
    }

    private fun querySongPathFromMediaStore(songUri: Uri): String? {
        if (songUri.scheme != ContentResolver.SCHEME_CONTENT ||
            !songUri.toString().contains("/audio/media/")
        ) {
            return null
        }
        return runCatching {
            appContext.contentResolver.query(
                songUri,
                arrayOf(MediaStore.Audio.Media.DATA),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sampleDominantColor(bitmap: Bitmap): Color? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null
        val stepX = max(1, w / 16)
        val stepY = max(1, h / 16)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                rSum += (pixel shr 16) and 0xFF
                gSum += (pixel shr 8) and 0xFF
                bSum += pixel and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0L) return null
        return Color(
            red = (rSum / count).toInt().coerceIn(0, 255),
            green = (gSum / count).toInt().coerceIn(0, 255),
            blue = (bSum / count).toInt().coerceIn(0, 255)
        )
    }

    private fun computeL1CacheSizeKb(): Int {
        val maxHeapKb = (Runtime.getRuntime().maxMemory() / BYTES_PER_KB).toInt()
        val targetKb = (maxHeapKb * L1_HEAP_FRACTION).toInt()
        return min(L1_MAX_CACHE_KB, max(L1_MIN_CACHE_KB, targetKb))
    }

    private fun compositeKey(albumKey: String, bucket: ArtResolutionBucket): String {
        return "${albumKey}_${bucket.name}"
    }

    private fun bumpCacheVersion() {
        _cacheVersion.value = versionCounter.incrementAndGet()
    }
}

// ═══ Key Normalization Utilities ═══

private fun normalizeMetadataToken(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.trim().lowercase(Locale.ROOT).replace(WHITESPACE_REGEX, " ")
}

private fun stableShortSha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val chars = CharArray(HASH_HEX_LENGTH)
    val hex = "0123456789abcdef"
    for (i in 0 until HASH_HEX_LENGTH / 2) {
        val b = digest[i].toInt() and 0xFF
        chars[i * 2] = hex[b ushr 4]
        chars[i * 2 + 1] = hex[b and 0x0F]
    }
    return String(chars)
}
