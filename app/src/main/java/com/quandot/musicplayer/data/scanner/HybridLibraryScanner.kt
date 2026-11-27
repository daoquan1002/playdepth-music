/**
 * HybridLibraryScanner — Two-tier incremental audio library scanner with bounded concurrency,
 * byte-level metadata extraction, and transactional SQLite batch persistence.
 *
 * @remarks
 * Combines Tier 1 multi-volume MediaStore discovery (without `IS_MUSIC` filtering) and Tier 2
 * direct filesystem / SAF traversal, deduplicates by normalized canonical path, and diffs against
 * an in-memory `HashMap<String, SongFingerprint>` snapshot from SQLite. Unchanged files skip all
 * file I/O so cold restarts and incremental syncs over thousands of tracks finish in sub-second
 * time. New and modified tracks are extracted in bounded parallelism (`Semaphore(4)`) and written
 * in 150-track SQLite transactions without `ON CONFLICT REPLACE` so Favorites, Playlists, and play
 * history are preserved unconditionally.
 *
 * @see MediaStoreAudioDataSource
 * @see DirectFileAudioDataSource
 * @see AudioMetadataParser
 * @see com.quandot.musicplayer.data.MusicDbHelper
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Internal modules
import com.quandot.musicplayer.data.MusicDbHelper
import com.quandot.musicplayer.data.computeSongArtworkKey
import com.quandot.musicplayer.data.isGenericAlbumOrFolderName
import com.quandot.musicplayer.debug.PDLog

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.LibraryScanState
import com.quandot.musicplayer.model.NATURAL_TEXT_COMPARATOR
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.util.isUnknownAlbumName

// ═══ Constants ═══
private const val ALBUM_ART_BASE_URI = "content://media/external/audio/albumart"
private const val METADATA_EXTRACT_CONCURRENCY = 4
private const val PROGRESS_EMIT_INTERVAL_MS = 120L
private const val UNKNOWN_TITLE_FALLBACK = "Không tên"
private const val UNKNOWN_ARTIST_FALLBACK = "Nghệ sĩ không xác định"
private const val UNKNOWN_ALBUM_FALLBACK = "Album không xác định"
private const val VARIOUS_ARTISTS_LABEL = "Nhiều nghệ sĩ"

private val GRADIENT_PAIRS = listOf(
    0xFFF093FB to 0xFFF5576C,
    0xFF4FACFE to 0xFF00F2FE,
    0xFFB12A5B to 0xFFFF8008,
    0xFF667EEA to 0xFF764BA2,
    0xFFFA709A to 0xFFFEE140,
    0xFF11998E to 0xFF38EF7D,
    0xFFFC466B to 0xFF3F5EFB,
    0xFF0BA360 to 0xFF3CBA92,
    0xFFFF512F to 0xFFDD2476,
    0xFF8E2DE2 to 0xFF4A00E0
)

// ═══ Pure Delta Diff Contracts (JVM-Testable) ═══

internal data class DeltaClassification(
    val unchangedKeys: List<String>,
    val toProcess: List<Pair<ScannedFileCandidate, SongFingerprint?>>,
    val deleted: List<SongFingerprint>
)

/**
 * Merges Tier 1 (MediaStore) and Tier 2 (Direct File / SAF) candidates, deduplicating by
 * normalized lookup key. Tier 1 records win for `contentUri` and `mediaStoreId` while adopting
 * fresher `fileSize` / `dateModifiedSec` from Tier 2 `BasicFileAttributes` when Tier 1 omits them.
 */
internal fun mergeTwoTierCandidates(
    tier1: List<ScannedFileCandidate>,
    tier2: List<ScannedFileCandidate>
): LinkedHashMap<String, ScannedFileCandidate> {
    val merged = LinkedHashMap<String, ScannedFileCandidate>(tier1.size + tier2.size)
    for (candidate in tier1) {
        if (candidate.lookupKey.isNotBlank()) {
            merged[candidate.lookupKey] = candidate
        }
    }
    for (candidate in tier2) {
        if (candidate.lookupKey.isBlank()) continue
        val existing = merged[candidate.lookupKey]
        if (existing == null) {
            merged[candidate.lookupKey] = candidate
        } else {
            val betterSize = if (existing.fileSize > 0L) existing.fileSize else candidate.fileSize
            val betterMod = if (existing.dateModifiedSec > 0L) existing.dateModifiedSec else candidate.dateModifiedSec
            if (betterSize != existing.fileSize || betterMod != existing.dateModifiedSec) {
                merged[candidate.lookupKey] = existing.copy(
                    fileSize = betterSize,
                    dateModifiedSec = betterMod
                )
            }
        }
    }
    return merged
}

/**
 * Classifies merged file candidates against the persisted SQLite fingerprint map into:
 * 1. Unchanged (skipped with zero file I/O)
 * 2. New / Modified (queued for parallel byte-level metadata extraction)
 * 3. Deleted (removed from storage or excluded)
 */
internal fun classifyScanDelta(
    mergedCandidates: Map<String, ScannedFileCandidate>,
    existingFingerprints: Map<String, SongFingerprint>,
    minDurationMs: Long,
    forceFullMetadataRefresh: Boolean
): DeltaClassification {
    val unchanged = ArrayList<String>(mergedCandidates.size)
    val toProcess = ArrayList<Pair<ScannedFileCandidate, SongFingerprint?>>()

    for ((key, candidate) in mergedCandidates) {
        val existing = existingFingerprints[key]
        val isUnchanged = existing != null &&
            !forceFullMetadataRefresh &&
            existing.fileSize > 0L &&
            candidate.fileSize > 0L &&
            existing.fileSize == candidate.fileSize &&
            existing.dateModifiedSec == candidate.dateModifiedSec &&
            (candidate.generationModified == 0L ||
                existing.generationModified == 0L ||
                existing.generationModified == candidate.generationModified) &&
            existing.durationMs >= minDurationMs &&
            (candidate.mediaStoreId == null || candidate.mediaStoreId == existing.id)

        if (isUnchanged) {
            unchanged.add(key)
        } else {
            toProcess.add(candidate to existing)
        }
    }

    val deleted = ArrayList<SongFingerprint>()
    for ((key, existing) in existingFingerprints) {
        if (!mergedCandidates.containsKey(key)) {
            deleted.add(existing)
        }
    }

    return DeltaClassification(
        unchangedKeys = unchanged,
        toProcess = toProcess,
        deleted = deleted
    )
}

// ═══ Scanner Engine ═══
class HybridLibraryScanner(
    private val context: Context,
    private val db: MusicDbHelper,
    private val mediaStoreSource: MediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
    private val directFileSource: DirectFileAudioDataSource = DirectFileAudioDataSource(context)
) {

    private val _scanState = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

    private val extractSemaphore = Semaphore(permits = METADATA_EXTRACT_CONCURRENCY)
    private val activeScanToken = AtomicLong(0L)

    /**
     * Executes a cancellable two-tier incremental scan, updating [scanState] throughout and
     * returning the synchronized library snapshot alongside the affected album keys for selective
     * artwork invalidation.
     */
    suspend fun scan(
        config: LibraryScanConfig,
        onProgress: ((scannedCount: Int, totalCount: Int, currentFolder: String) -> Unit)? = null
    ): ScanDeltaResult = withContext(Dispatchers.IO) {
        val myToken = activeScanToken.incrementAndGet()
        val startMs = SystemClock.elapsedRealtime()
        _scanState.value = LibraryScanState.Scanning(scannedCount = 0, totalCount = 0, currentFolder = "")

        try {
            currentCoroutineContext().ensureActive()

            val normalizedExcluded = config.excludedFolders
                .map(::normalizePathKey)
                .filter { it.isNotBlank() }

            // 1. Load lightweight fingerprint map from SQLite into RAM
            val existingFingerprints = db.getSongFingerprints()
            currentCoroutineContext().ensureActive()

            // 2. Run Tier 1 (MediaStore) and Tier 2 (Direct File / SAF) discovery concurrently
            val (tier1Result, tier2Candidates) = coroutineScope {
                val t1 = async {
                    mediaStoreSource.queryAllVolumes(
                        minDurationMs = config.minDurationMs,
                        normalizedExcludedFolders = normalizedExcluded
                    )
                }
                val t2 = async {
                    directFileSource.traverse(
                        normalizedExcludedFolders = normalizedExcluded,
                        customScanFolders = config.customScanFolders,
                        safTreeUris = config.safTreeUris
                    )
                }
                t1.await() to t2.await()
            }
            currentCoroutineContext().ensureActive()

            // Guard against catastrophic DB wipe if Tier 1 MediaStore failed completely
            if (!tier1Result.querySucceeded &&
                tier1Result.candidates.isEmpty() &&
                tier2Candidates.isEmpty() &&
                existingFingerprints.isNotEmpty()
            ) {
                val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
                PDLog.w(
                    PDLog.SCANNER,
                    "SCAN_GUARD_PRESERVE",
                    "reason=tier1_fail_tier2_empty preserved=${existingFingerprints.size}"
                )
                val preservedSongs = db.getAllSongs()
                val preservedAlbums = db.getAllAlbums().ifEmpty { extractAlbums(preservedSongs) }
                if (activeScanToken.get() == myToken) {
                    _scanState.value = LibraryScanState.Completed(
                        added = 0,
                        updated = 0,
                        removed = 0,
                        durationMs = elapsedMs
                    )
                }
                return@withContext ScanDeltaResult(
                    songs = preservedSongs,
                    albums = preservedAlbums,
                    addedCount = 0,
                    updatedCount = 0,
                    removedCount = 0,
                    unchangedCount = preservedSongs.size,
                    affectedAlbumKeys = emptySet(),
                    newlyAddedOrUpdatedAlbumKeys = emptySet(),
                    durationMs = elapsedMs
                )
            }

            // 3. Deduplicate by normalized path & classify delta against SQLite snapshot
            val merged = mergeTwoTierCandidates(tier1Result.candidates, tier2Candidates)
            val delta = classifyScanDelta(
                mergedCandidates = merged,
                existingFingerprints = existingFingerprints,
                minDurationMs = config.minDurationMs,
                forceFullMetadataRefresh = config.forceFullMetadataRefresh
            )

            PDLog.i(
                PDLog.SCANNER,
                "SCAN_DELTA",
                "t1=${tier1Result.candidates.size} t1Ok=${tier1Result.querySucceeded} " +
                    "t2=${tier2Candidates.size} merged=${merged.size} unchanged=${delta.unchangedKeys.size} " +
                    "toProcess=${delta.toProcess.size} deleted=${delta.deleted.size}"
            )

            val totalToProcess = delta.toProcess.size
            val totalLibraryEstimate = delta.unchangedKeys.size + totalToProcess
            val scannedCounter = AtomicInteger(delta.unchangedKeys.size)
            val lastEmitMs = AtomicLong(0L)

            if (totalLibraryEstimate > 0) {
                emitProgress(
                    scanned = scannedCounter.get(),
                    total = totalLibraryEstimate,
                    folder = "",
                    force = true,
                    myToken = myToken,
                    lastEmitMs = lastEmitMs,
                    onProgress = onProgress
                )
            }

            // 4. Bounded-concurrency metadata extraction + 150-row SQLite batch transactions
            val affectedAlbumKeys = HashSet<String>()
            val addedOrUpdatedAlbumKeys = HashSet<String>()
            val extraIdsToDelete = ArrayList<Long>()
            var addedCount = 0
            var updatedCount = 0

            if (delta.toProcess.isNotEmpty()) {
                val legacyGenreMap = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                    delta.toProcess.any { it.first.sourceTier == ScanSourceTier.MEDIA_STORE }
                ) {
                    mediaStoreSource.loadLegacyGenreMap()
                } else {
                    emptyMap()
                }

                for (batch in delta.toProcess.chunked(MusicDbHelper.DB_BATCH_SIZE)) {
                    currentCoroutineContext().ensureActive()

                    val processedBatch = coroutineScope {
                        batch.map { (candidate, existingFp) ->
                            async(Dispatchers.IO) {
                                extractSemaphore.withPermit {
                                    currentCoroutineContext().ensureActive()
                                    val result = buildUpsertOrReject(
                                        candidate = candidate,
                                        existing = existingFp,
                                        minDurationMs = config.minDurationMs,
                                        legacyGenreMap = legacyGenreMap
                                    )
                                    val doneCount = scannedCounter.incrementAndGet()
                                    val folderLabel = extractFolderLabel(candidate.path)
                                    emitProgress(
                                        scanned = doneCount,
                                        total = totalLibraryEstimate,
                                        folder = folderLabel,
                                        force = false,
                                        myToken = myToken,
                                        lastEmitMs = lastEmitMs,
                                        onProgress = onProgress
                                    )
                                    Triple(result, existingFp, candidate)
                                }
                            }
                        }.awaitAll()
                    }

                    currentCoroutineContext().ensureActive()

                    val batchUpserts = ArrayList<SongUpsertEntry>(processedBatch.size)
                    for ((entry, existingFp, _) in processedBatch) {
                        if (entry != null) {
                            batchUpserts.add(entry)
                            if (existingFp == null) {
                                addedCount++
                            } else {
                                updatedCount++
                                affectedAlbumKeys.add(
                                    computeSongArtworkKey(
                                        songId = existingFp.id,
                                        albumId = existingFp.albumId,
                                        albumName = existingFp.album,
                                        albumArtist = existingFp.albumArtist.ifBlank { existingFp.artist },
                                        songPath = existingFp.path,
                                        contentUriStr = existingFp.contentUriStr
                                    )
                                )
                            }
                            val newKey = computeSongArtworkKey(
                                songId = entry.song.id,
                                albumId = entry.song.albumId,
                                albumName = entry.song.album,
                                albumArtist = entry.song.albumArtist.ifBlank { entry.song.artist },
                                songPath = entry.song.path,
                                contentUriStr = entry.song.contentUri.toString()
                            )
                            affectedAlbumKeys.add(newKey)
                            addedOrUpdatedAlbumKeys.add(newKey)
                        } else if (existingFp != null) {
                            // Modified file is now below minDurationMs or unreadable -> prune from DB
                            extraIdsToDelete.add(existingFp.id)
                            affectedAlbumKeys.add(
                                computeSongArtworkKey(
                                    songId = existingFp.id,
                                    albumId = existingFp.albumId,
                                    albumName = existingFp.album,
                                    albumArtist = existingFp.albumArtist.ifBlank { existingFp.artist },
                                    songPath = existingFp.path,
                                    contentUriStr = existingFp.contentUriStr
                                )
                            )
                        }
                    }

                    if (batchUpserts.isNotEmpty()) {
                        db.upsertSongsBatch(batchUpserts)
                    }
                }
            }

            // 5. Batch-delete removed tracks only when Tier 1 MediaStore discovery succeeded
            val deleteIds = ArrayList<Long>(delta.deleted.size + extraIdsToDelete.size)
            if (tier1Result.querySucceeded) {
                for (fp in delta.deleted) {
                    deleteIds.add(fp.id)
                    affectedAlbumKeys.add(
                        computeSongArtworkKey(
                            songId = fp.id,
                            albumId = fp.albumId,
                            albumName = fp.album,
                            albumArtist = fp.albumArtist.ifBlank { fp.artist },
                            songPath = fp.path,
                            contentUriStr = fp.contentUriStr
                        )
                    )
                }
            }
            deleteIds.addAll(extraIdsToDelete)
            if (deleteIds.isNotEmpty()) {
                db.deleteSongsBatch(deleteIds)
            }

            currentCoroutineContext().ensureActive()

            // 6. Hydrate synchronized snapshot & reconcile albums table (pruning empty albums)
            val finalSongs = db.getAllSongs()
            val finalAlbums = extractAlbums(finalSongs)
            if (addedCount > 0 || updatedCount > 0 || deleteIds.isNotEmpty() || db.getAllAlbums().isEmpty()) {
                db.syncAlbumsFromSongs(finalAlbums)
            }

            val elapsedMs = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(0L)
            if (activeScanToken.get() == myToken) {
                _scanState.value = LibraryScanState.Completed(
                    added = addedCount,
                    updated = updatedCount,
                    removed = deleteIds.size,
                    durationMs = elapsedMs
                )
            }

            ScanDeltaResult(
                songs = finalSongs,
                albums = finalAlbums,
                addedCount = addedCount,
                updatedCount = updatedCount,
                removedCount = deleteIds.size,
                unchangedCount = delta.unchangedKeys.size,
                affectedAlbumKeys = affectedAlbumKeys,
                newlyAddedOrUpdatedAlbumKeys = addedOrUpdatedAlbumKeys,
                durationMs = elapsedMs
            )
        } catch (ce: CancellationException) {
            if (activeScanToken.get() == myToken) {
                _scanState.value = LibraryScanState.Idle
            }
            throw ce
        } catch (t: Throwable) {
            if (activeScanToken.get() == myToken) {
                _scanState.value = LibraryScanState.Idle
            }
            throw t
        }
    }

    // ═══ Per-Track Extraction & Song Synthesis ═══
    private fun buildUpsertOrReject(
        candidate: ScannedFileCandidate,
        existing: SongFingerprint?,
        minDurationMs: Long,
        legacyGenreMap: Map<Long, String>
    ): SongUpsertEntry? {
        return try {
            // Isolate every file in its own try/catch so corrupted headers or 0-byte files never abort the scan
            val extracted = AudioMetadataParser.extract(context, candidate)

            val resolvedDuration = when {
                extracted.durationMs > 0L && (!extracted.durationEstimated || candidate.durationMs <= 0L) -> extracted.durationMs
                candidate.durationMs > 0L -> candidate.durationMs
                extracted.durationMs > 0L -> extracted.durationMs
                else -> 0L
            }
            if (minDurationMs > 0L && resolvedDuration < minDurationMs) {
                return null
            }

            val fallbackFileName = candidate.displayName
                .substringBeforeLast('.')
                .trim()
                .ifBlank { UNKNOWN_TITLE_FALLBACK }
            val parentFolderName = if (candidate.path.isNotBlank()) {
                File(candidate.path).parentFile?.name?.trim().orEmpty()
            } else ""

            val title = extracted.title?.takeIf { it.isNotBlank() }
                ?: candidate.title?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: fallbackFileName

            val artist = extracted.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: candidate.artist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: UNKNOWN_ARTIST_FALLBACK

            // Do not fabricate album names from parent folders (e.g. Download/Music) or MediaStore's
            // folder-placeholder ALBUM value when a track intentionally has an empty ALBUM tag.
            val extractedAlbumClean = extracted.album?.takeIf {
                it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true)
            }
            val candidateAlbumClean = candidate.album?.takeIf {
                it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true)
            }
            val isCandidateFolderPlaceholder = candidateAlbumClean == null ||
                isGenericAlbumOrFolderName(candidateAlbumClean) ||
                (parentFolderName.isNotBlank() && candidateAlbumClean.equals(parentFolderName, ignoreCase = true))

            val album = extractedAlbumClean
                ?: candidateAlbumClean?.takeUnless { isCandidateFolderPlaceholder }
                ?: UNKNOWN_ALBUM_FALLBACK

            // Leave albumArtist blank when omitted in tags so multi-artist compilation albums stay unified
            val albumArtist = extracted.albumArtist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: candidate.albumArtist?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: ""

            val genre = extracted.genre?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: candidate.mediaStoreId?.let { legacyGenreMap[it] }?.takeIf { it.isNotBlank() }
                ?: candidate.genre?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                ?: ""

            val year = when {
                extracted.year > 0 -> extracted.year
                candidate.year > 0 -> candidate.year
                else -> 0
            }

            val trackNumber = when {
                extracted.trackNumber > 0 -> extracted.trackNumber
                candidate.trackNumber > 0 -> candidate.trackNumber
                else -> 0
            }

            val discNumber = when {
                extracted.discNumber > 0 -> extracted.discNumber
                candidate.discNumber > 0 -> candidate.discNumber
                else -> 0
            }

            // Preserve existing ID when possible; remap atomically when a synthetic track acquires a MediaStore ID
            val targetSongId: Long
            val remapFromId: Long?
            when {
                candidate.mediaStoreId != null && candidate.mediaStoreId > 0L -> {
                    targetSongId = candidate.mediaStoreId
                    remapFromId = if (existing != null && existing.id != targetSongId) existing.id else null
                }
                existing != null -> {
                    targetSongId = existing.id
                    remapFromId = null
                }
                else -> {
                    targetSongId = computeStableSyntheticSongId(candidate.lookupKey)
                    remapFromId = null
                }
            }

            val albumId = when {
                album == UNKNOWN_ALBUM_FALLBACK -> computeSyntheticAlbumId(UNKNOWN_ALBUM_FALLBACK, "")
                candidate.albumId != null && candidate.albumId > 0L &&
                    (extractedAlbumClean == null || extractedAlbumClean.equals(candidateAlbumClean, ignoreCase = true)) -> {
                    candidate.albumId
                }
                else -> computeSyntheticAlbumId(album, albumArtist)
            }

            val effectiveFileSize = when {
                candidate.fileSize > 0L -> candidate.fileSize
                candidate.path.isNotBlank() -> runCatching { File(candidate.path).length() }.getOrDefault(0L)
                else -> 0L
            }
            val effectiveDateMod = when {
                candidate.dateModifiedSec > 0L -> candidate.dateModifiedSec
                candidate.path.isNotBlank() -> runCatching { File(candidate.path).lastModified() / 1_000L }.getOrDefault(0L)
                else -> 0L
            }
            val effectiveDateAdded = if (candidate.dateAddedSec > 0L) candidate.dateAddedSec else effectiveDateMod

            val gradientIndex = (targetSongId.hashCode() and 0x7FFFFFFF) % GRADIENT_PAIRS.size
            val (gradStart, gradEnd) = GRADIENT_PAIRS[gradientIndex]

            val codec = extracted.codec.ifBlank {
                extractExtensionLower(candidate.path.ifBlank { candidate.displayName }).uppercase(Locale.ROOT)
            }

            val resolvedContentUri = candidate.contentUri ?: Uri.EMPTY
            val song = Song(
                id = targetSongId,
                title = title,
                artist = artist,
                album = album,
                duration = resolvedDuration,
                contentUri = resolvedContentUri,
                albumArtUri = resolvedContentUri,
                albumGradientColorStart = gradStart,
                albumGradientColorEnd = gradEnd,
                path = candidate.path,
                dateAdded = effectiveDateAdded,
                albumId = albumId,
                trackNumber = trackNumber,
                year = year,
                genre = genre,
                albumArtist = albumArtist,
                discNumber = discNumber,
                fileSize = effectiveFileSize,
                dateModified = effectiveDateMod,
                generationModified = candidate.generationModified,
                sampleRate = extracted.sampleRate,
                bitDepth = extracted.bitDepth,
                bitrate = extracted.bitrate,
                codec = codec
            )

            SongUpsertEntry(
                song = song,
                isUpdate = existing != null,
                remapFromSongId = remapFromId
            )
        } catch (_: Throwable) {
            null
        }
    }

    // ═══ Album Aggregation & Progress Helpers ═══
    fun extractAlbums(songs: List<Song>): List<Album> {
        val unknownAlbumSyntheticId = computeSyntheticAlbumId(UNKNOWN_ALBUM_FALLBACK, "")
        return songs.groupBy { song ->
            if (isUnknownAlbumName(song.album)) unknownAlbumSyntheticId else song.albumId
        }.map { (albumId, songList) ->
            val first = songList.first()
            val isUnknownAlbum = albumId == unknownAlbumSyntheticId || isUnknownAlbumName(first.album)
            val albumArt = if ((albumId and SYNTHETIC_ALBUM_ID_FLAG) != 0L) {
                songList.firstOrNull { it.albumArtUri != null }?.albumArtUri
            } else {
                ContentUris.withAppendedId(Uri.parse(ALBUM_ART_BASE_URI), albumId)
            }
            val gradientIndex = (albumId.hashCode() and 0x7FFFFFFF) % GRADIENT_PAIRS.size
            val (gradStart, gradEnd) = GRADIENT_PAIRS[gradientIndex]
            val distinctAlbumArtists = songList.map { it.albumArtist.ifBlank { it.artist } }.distinct()
            val artistName = if (distinctAlbumArtists.size > 1) VARIOUS_ARTISTS_LABEL else distinctAlbumArtists.first()
            val albumYear = songList.map { it.year }.filter { it > 0 }.maxOrNull() ?: 0
            val representativeSong = songList.firstOrNull { it.path.isNotBlank() } ?: first
            Album(
                id = albumId,
                title = if (isUnknownAlbum) UNKNOWN_ALBUM_FALLBACK else first.album,
                artist = artistName,
                songCount = songList.size,
                albumArtUri = albumArt,
                colorStart = gradStart,
                colorEnd = gradEnd,
                year = albumYear,
                sampleSongUri = representativeSong.contentUri.takeIf { it != Uri.EMPTY },
                sampleSongPath = representativeSong.path
            )
        }.sortedWith(
            compareBy<Album> { if (isUnknownAlbumName(it.title)) 0 else 1 }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.title }
        )
    }

    private fun extractFolderLabel(path: String): String {
        if (path.isBlank()) return ""
        return File(path).parentFile?.name.orEmpty()
    }

    private fun emitProgress(
        scanned: Int,
        total: Int,
        folder: String,
        force: Boolean,
        myToken: Long,
        lastEmitMs: AtomicLong,
        onProgress: ((scannedCount: Int, totalCount: Int, currentFolder: String) -> Unit)?
    ) {
        if (activeScanToken.get() != myToken) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastEmitMs.get()
        if (force || now - prev >= PROGRESS_EMIT_INTERVAL_MS || scanned >= total) {
            if (force || lastEmitMs.compareAndSet(prev, now)) {
                if (activeScanToken.get() != myToken) return
                _scanState.value = LibraryScanState.Scanning(
                    scannedCount = scanned,
                    totalCount = total,
                    currentFolder = folder
                )
                onProgress?.invoke(scanned, total, folder)
            }
        }
    }
}
