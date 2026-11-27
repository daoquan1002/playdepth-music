/**
 * LibraryScannerModels — Data-layer contracts for PlayDepth's two-tier incremental library scanner.
 *
 * @remarks
 * Separates lightweight filesystem/MediaStore discovery candidates ([ScannedFileCandidate]) and
 * persisted SQLite change-detection fingerprints ([SongFingerprint]) from immutable UI domain
 * models so unchanged tracks are skipped in RAM with zero per-file I/O.
 *
 * @see HybridLibraryScanner
 * @see MediaStoreAudioDataSource
 * @see DirectFileAudioDataSource
 * @see AudioMetadataParser
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import android.net.Uri
import java.io.File
import java.util.Locale

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══

/** Audio file extensions accepted across Tier 1 (MediaStore) and Tier 2 (Direct File / SAF). */
val SUPPORTED_AUDIO_EXTENSIONS: Set<String> = setOf(
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "alac",
    "ape", "wv", "dsf", "dff", "aiff", "aif", "aifc", "wma"
)

const val DEFAULT_MIN_DURATION_MS: Long = 10_000L
const val SYNTHETIC_SONG_ID_FLAG: Long = 0x40000000L
const val SYNTHETIC_ALBUM_ID_FLAG: Long = 0x40000000_00000000L

// ═══ Scanner Contracts ═══

enum class ScanSourceTier {
    MEDIA_STORE,
    DIRECT_FILE,
    SAF_DOCUMENT
}

/**
 * Lightweight persisted snapshot loaded into RAM (`HashMap<String, SongFingerprint>`) for O(1)
 * incremental delta diffing against discovered audio files.
 */
data class SongFingerprint(
    val id: Long,
    val path: String,
    val fileSize: Long,
    val dateModifiedSec: Long,
    val generationModified: Long,
    val durationMs: Long,
    val albumId: Long,
    val album: String,
    val artist: String,
    val albumArtist: String,
    val contentUriStr: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val bitrate: Int,
    val codec: String
)

/**
 * Unified candidate emitted by Tier 1 ([MediaStoreAudioDataSource]) or Tier 2
 * ([DirectFileAudioDataSource]) prior to deduplication and delta classification.
 */
data class ScannedFileCandidate(
    val lookupKey: String,
    val path: String,
    val contentUri: Uri? = null,
    val mediaStoreId: Long?,
    val displayName: String,
    val fileSize: Long,
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val generationModified: Long,
    val durationMs: Long,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val albumId: Long? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val sourceTier: ScanSourceTier = ScanSourceTier.MEDIA_STORE
)

/**
 * Combined tag and Hi-Res technical metadata extracted in a single file open by
 * [AudioMetadataParser].
 */
data class ExtractedAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int = 0,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val durationMs: Long = 0L,
    val durationEstimated: Boolean = false,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Int = 0,
    val codec: String = "",
    val isFloat: Boolean = false
)

/**
 * Upsert instruction submitted in batches to [com.quandot.musicplayer.data.MusicDbHelper].
 *
 * When [remapFromSongId] is non-null and differs from `song.id` (e.g., a track previously scanned
 * via Direct File later acquires a MediaStore row ID), playlist and history references are
 * re-keyed atomically inside the same SQLite transaction so user data is never lost.
 */
data class SongUpsertEntry(
    val song: Song,
    val isUpdate: Boolean,
    val remapFromSongId: Long? = null
)

/**
 * Configuration passed into each incremental or full scan run.
 */
data class LibraryScanConfig(
    val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
    val excludedFolders: Set<String> = emptySet(),
    val customScanFolders: Set<String> = emptySet(),
    val safTreeUris: Set<String> = emptySet(),
    val forceFullMetadataRefresh: Boolean = false,
    val clearArtCache: Boolean = false
)

/**
 * Summary returned after a scan finishes, carrying the synchronized library snapshot and the
 * exact set of album keys whose artwork cache should be refreshed.
 */
data class ScanDeltaResult(
    val songs: List<Song>,
    val albums: List<Album>,
    val addedCount: Int,
    val updatedCount: Int,
    val removedCount: Int,
    val unchangedCount: Int,
    val affectedAlbumKeys: Set<String>,
    val newlyAddedOrUpdatedAlbumKeys: Set<String>,
    val durationMs: Long
)

// ═══ Path & Extension Helpers ═══

fun normalizePathKey(rawPath: String): String {
    if (rawPath.isBlank()) return ""
    return rawPath.replace('\\', '/').trimEnd('/')
}

fun resolveCanonicalPathKey(file: File): String {
    val resolved = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    return normalizePathKey(resolved)
}

fun isSupportedAudioName(nameOrPath: String?): Boolean {
    if (nameOrPath.isNullOrBlank()) return false
    val dot = nameOrPath.lastIndexOf('.')
    if (dot < 0 || dot >= nameOrPath.length - 1) return false
    val ext = nameOrPath.substring(dot + 1).lowercase(Locale.ROOT)
    return ext in SUPPORTED_AUDIO_EXTENSIONS
}

fun extractExtensionLower(nameOrPath: String?): String {
    if (nameOrPath.isNullOrBlank()) return ""
    val dot = nameOrPath.lastIndexOf('.')
    if (dot < 0 || dot >= nameOrPath.length - 1) return ""
    return nameOrPath.substring(dot + 1).lowercase(Locale.ROOT)
}

fun isPathExcluded(filePath: String, normalizedExcludedFolders: List<String>): Boolean {
    if (filePath.isBlank() || normalizedExcludedFolders.isEmpty()) return false
    val normPath = normalizePathKey(filePath)
    return normalizedExcludedFolders.any { ex ->
        normPath.equals(ex, ignoreCase = true) ||
            (normPath.length > ex.length &&
                normPath.startsWith(ex, ignoreCase = true) &&
                normPath[ex.length] == '/')
    }
}

private const val NOMEDIA_FILE_NAME = ".nomedia"

/**
 * Returns `true` if [dirPath] or any of its ancestor directories (up to the storage volume root)
 * contains a `.nomedia` marker file. Results are memoized per directory path in [cache] so
 * scanning thousands of tracks incurs at most one `stat` check per unique directory.
 */
fun isDirectoryHiddenByNomedia(
    dirPath: String,
    cache: MutableMap<String, Boolean>
): Boolean {
    val norm = normalizePathKey(dirPath)
    if (norm.isBlank() ||
        norm == "/" ||
        norm.equals("/storage", ignoreCase = true) ||
        norm.equals("/storage/emulated", ignoreCase = true) ||
        norm.equals("/mnt", ignoreCase = true) ||
        norm.equals("/mnt/media_rw", ignoreCase = true)
    ) {
        return false
    }
    cache[norm]?.let { return it }

    val hasDirectNomedia = runCatching { File(norm, NOMEDIA_FILE_NAME).exists() }.getOrDefault(false)
    if (hasDirectNomedia) {
        cache[norm] = true
        return true
    }

    val parent = norm.substringBeforeLast('/', "")
    val hiddenByParent = if (parent.isBlank() || parent == norm) {
        false
    } else {
        isDirectoryHiddenByNomedia(parent, cache)
    }
    cache[norm] = hiddenByParent
    return hiddenByParent
}

/**
 * Returns `true` if the parent directory of [filePath] (or any ancestor directory) contains `.nomedia`.
 */
fun isPathInNomediaDirectory(
    filePath: String,
    cache: MutableMap<String, Boolean>
): Boolean {
    val norm = normalizePathKey(filePath)
    val parent = norm.substringBeforeLast('/', "")
    if (parent.isBlank() || parent == norm) return false
    return isDirectoryHiddenByNomedia(parent, cache)
}

fun computeStableSyntheticSongId(pathOrUriKey: String): Long {
    // 61-bit FNV-1a style positive hash flagged with SYNTHETIC_SONG_ID_FLAG to minimize collisions
    var hash = 0xcbf29ce484222325UL.toLong()
    for (ch in pathOrUriKey) {
        hash = (hash xor ch.code.toLong()) * 0x100000001b3L
    }
    val positive = (hash and 0x3FFFFFFF_FFFFFFFFL).coerceAtLeast(1L)
    return (SYNTHETIC_SONG_ID_FLAG shl 30) or positive
}

fun computeSyntheticAlbumId(albumName: String, albumArtist: String = ""): Long {
    val key = "${albumName.trim().lowercase(Locale.ROOT)}|${albumArtist.trim().lowercase(Locale.ROOT)}"
    var hash = 0xcbf29ce484222325UL.toLong()
    for (ch in key) {
        hash = (hash xor ch.code.toLong()) * 0x100000001b3L
    }
    val positive = (hash and 0x3FFFFFFF_FFFFFFFFL).coerceAtLeast(1L)
    return SYNTHETIC_ALBUM_ID_FLAG or positive
}
