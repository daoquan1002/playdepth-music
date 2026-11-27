/**
 * MediaStoreAudioDataSource — Tier 1 multi-volume MediaStore discovery without `IS_MUSIC` filtering.
 *
 * @remarks
 * Queries every mounted external volume reported by `MediaStore.getExternalVolumeNames(context)`
 * (internal `external_primary`, SD cards, and USB OTG) and deliberately omits `IS_MUSIC != 0` and
 * complex SQL `WHERE` clauses so strict OEM `MediaProvider` implementations never reject the query
 * or drop lossless/unindexed tracks. Uses a two-tier projection fallback: attempts extended API
 * 29/30+ columns first and transparently falls back to universally supported API 26+ core columns
 * (and `EXTERNAL_CONTENT_URI`) if an OEM ROM rejects optional columns such as `generation_modified`.
 *
 * @see DirectFileAudioDataSource
 * @see HybridLibraryScanner
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Internal modules
import com.quandot.musicplayer.debug.PDLog

// ═══ Constants ═══
private const val TRACK_DISC_MODULO = 1000

// ═══ Result Contract ═══

/**
 * Wraps discovered Tier 1 candidates alongside [querySucceeded] so callers never mistake a failed
 * MediaStore query (security error, dead provider, or OEM schema rejection) for an empty device.
 */
data class MediaStoreQueryResult(
    val candidates: List<ScannedFileCandidate>,
    val querySucceeded: Boolean
)

// ═══ Tier 1 Data Source ═══
class MediaStoreAudioDataSource(private val context: Context) {

    /**
     * Queries all mounted MediaStore audio volumes and emits normalized [ScannedFileCandidate]
     * entries filtered in Kotlin by extension/MIME, size, duration, and excluded folders.
     */
    suspend fun queryAllVolumes(
        minDurationMs: Long,
        normalizedExcludedFolders: List<String>
    ): MediaStoreQueryResult {
        val candidates = ArrayList<ScannedFileCandidate>(512)
        val seenIds = HashSet<Long>()
        val nomediaCache = HashMap<String, Boolean>(256)
        val volumePathMap = resolvePublicVolumePathMap()

        val coreProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.MIME_TYPE
        )

        val extendedProjectionList = coreProjection.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            extendedProjectionList.add(MediaStore.Audio.Media.RELATIVE_PATH)
            extendedProjectionList.add(MediaStore.Audio.Media.VOLUME_NAME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            extendedProjectionList.add(MediaStore.Audio.Media.GENRE)
            extendedProjectionList.add(MediaStore.Audio.Media.ALBUM_ARTIST)
            extendedProjectionList.add(MediaStore.Audio.Media.GENERATION_MODIFIED)
        }
        val extendedProjection = extendedProjectionList.toTypedArray()

        var anyQuerySucceeded = false
        var rawRowCount = 0

        val volumeUris = resolveAudioVolumeUris()
        for (volumeUri in volumeUris) {
            currentCoroutineContext().ensureActive()
            val cursor = queryWithFallback(
                uri = volumeUri,
                extendedProjection = extendedProjection,
                coreProjection = coreProjection
            )
            if (cursor != null) {
                anyQuerySucceeded = true
                cursor.use { c ->
                    rawRowCount += drainCursor(
                        cursor = c,
                        volumeUri = volumeUri,
                        minDurationMs = minDurationMs,
                        normalizedExcludedFolders = normalizedExcludedFolders,
                        nomediaCache = nomediaCache,
                        volumePathMap = volumePathMap,
                        seenIds = seenIds,
                        out = candidates
                    )
                }
            }
        }

        // Fallback to EXTERNAL_CONTENT_URI if per-volume URIs failed or returned zero rows on OEM ROMs
        val externalUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        if ((!anyQuerySucceeded || candidates.isEmpty()) && volumeUris.none { it == externalUri }) {
            currentCoroutineContext().ensureActive()
            val fallbackCursor = queryWithFallback(
                uri = externalUri,
                extendedProjection = extendedProjection,
                coreProjection = coreProjection
            )
            if (fallbackCursor != null) {
                anyQuerySucceeded = true
                fallbackCursor.use { c ->
                    rawRowCount += drainCursor(
                        cursor = c,
                        volumeUri = externalUri,
                        minDurationMs = minDurationMs,
                        normalizedExcludedFolders = normalizedExcludedFolders,
                        nomediaCache = nomediaCache,
                        volumePathMap = volumePathMap,
                        seenIds = seenIds,
                        out = candidates
                    )
                }
            }
        }

        PDLog.i(
            PDLog.SCANNER,
            "MEDIASTORE_QUERY_DONE",
            "volumes=${volumeUris.size} ok=$anyQuerySucceeded rawRows=$rawRowCount candidates=${candidates.size}"
        )

        return MediaStoreQueryResult(
            candidates = candidates,
            querySucceeded = anyQuerySucceeded
        )
    }

    /**
     * Loads legacy `MediaStore.Audio.Genres` mappings on API < 30 only when new/modified tracks
     * require genre enrichment.
     */
    fun loadLegacyGenreMap(): Map<Long, String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return emptyMap()
        val map = HashMap<Long, String>()
        try {
            val uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME)
            context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
                while (cursor.moveToNext()) {
                    val genreId = cursor.getLong(idCol)
                    val genreName = cursor.getString(nameCol)?.trim().orEmpty()
                    if (genreName.isBlank() || genreId == 0L) continue
                    try {
                        val membersUri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                        context.contentResolver.query(
                            membersUri,
                            arrayOf(MediaStore.Audio.Genres.Members._ID),
                            null,
                            null,
                            null
                        )?.use { mCursor ->
                            val mIdCol = mCursor.getColumnIndex(MediaStore.Audio.Genres.Members._ID)
                            if (mIdCol >= 0) {
                                while (mCursor.moveToNext()) {
                                    map[mCursor.getLong(mIdCol)] = genreName
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            PDLog.w(PDLog.SCANNER, "MEDIASTORE_GENRE_ERR", "err=${t.message}")
        }
        return map
    }

    // ═══ Cursor Query & Extraction Helpers ═══

    /**
     * Queries [uri] with `selection = null` (avoiding OEM strict SQL failures) using
     * [extendedProjection], automatically falling back to [coreProjection] if optional API 29/30+
     * columns are unsupported by the device's `MediaProvider`.
     */
    private fun queryWithFallback(
        uri: Uri,
        extendedProjection: Array<String>,
        coreProjection: Array<String>
    ): Cursor? {
        try {
            return context.contentResolver.query(uri, extendedProjection, null, null, null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            PDLog.w(
                PDLog.SCANNER,
                "MEDIASTORE_PROJ_FALLBACK",
                "uri=$uri err=${t.javaClass.simpleName}:${t.message}"
            )
        }

        return try {
            context.contentResolver.query(uri, coreProjection, null, null, null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            PDLog.e(PDLog.SCANNER, "MEDIASTORE_QUERY_ERR", "uri=$uri", t)
            null
        }
    }

    private suspend fun drainCursor(
        cursor: Cursor,
        volumeUri: Uri,
        minDurationMs: Long,
        normalizedExcludedFolders: List<String>,
        nomediaCache: MutableMap<String, Boolean>,
        volumePathMap: Map<String, String>,
        seenIds: HashSet<Long>,
        out: MutableList<ScannedFileCandidate>
    ): Int {
        val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
        if (idCol < 0) return 0

        val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
        val displayNameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
        val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
        val dateModCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
        val dateAddedCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
        val trackCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
        val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
        val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
        val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
        } else -1
        val volNameCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
        } else -1
        val genModCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.GENERATION_MODIFIED)
        } else -1
        val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
        } else -1
        val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
        } else -1

        var rows = 0
        while (cursor.moveToNext()) {
            rows++
            if (rows and 0x7F == 0) {
                currentCoroutineContext().ensureActive()
            }

            val id = cursor.getLong(idCol)
            if (!seenIds.add(id)) continue

            // Reject explicitly zero-byte entries while tolerating NULL size rows
            val hasSize = sizeCol >= 0 && !cursor.isNull(sizeCol)
            val fileSize = if (hasSize) cursor.getLong(sizeCol) else 0L
            if (hasSize && fileSize <= 0L) continue

            val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol).orEmpty() else ""
            val rawDataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
            val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty() else ""
            val volName = if (volNameCol >= 0) cursor.getString(volNameCol).orEmpty() else ""
            val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""

            val resolvedPath = resolveNormalizedPath(
                rawDataPath = rawDataPath,
                relativePath = relPath,
                displayName = displayName,
                volumeName = volName,
                volumePathMap = volumePathMap
            )

            val nameForExt = resolvedPath.ifBlank { displayName }
            val extMatches = isSupportedAudioName(nameForExt)
            val mimeFallbackMatches = !extMatches &&
                extractExtensionLower(nameForExt).isBlank() &&
                mimeType.startsWith("audio/", ignoreCase = true) &&
                !mimeType.contains("midi", ignoreCase = true)
            if (!extMatches && !mimeFallbackMatches) continue

            if (resolvedPath.isNotBlank() &&
                (isPathExcluded(resolvedPath, normalizedExcludedFolders) ||
                    isPathInNomediaDirectory(resolvedPath, nomediaCache))
            ) {
                continue
            }

            // Allow duration <= 0 / NULL through so AIFF/DSF/DFF/APE/WV are evaluated by AudioMetadataParser
            val durationMs = if (durationCol >= 0 && !cursor.isNull(durationCol)) cursor.getLong(durationCol) else 0L
            if (minDurationMs > 0L && durationMs > 0L && durationMs < minDurationMs) continue

            val contentUri = ContentUris.withAppendedId(volumeUri, id)
            val lookupKey = if (resolvedPath.isNotBlank()) {
                resolvedPath
            } else {
                "saf://$contentUri"
            }

            val rawTrack = if (trackCol >= 0 && !cursor.isNull(trackCol)) cursor.getInt(trackCol) else 0
            val discNumber = if (rawTrack >= TRACK_DISC_MODULO) rawTrack / TRACK_DISC_MODULO else 0
            val trackNumber = if (rawTrack >= TRACK_DISC_MODULO) rawTrack % TRACK_DISC_MODULO else rawTrack

            out.add(
                ScannedFileCandidate(
                    lookupKey = lookupKey,
                    path = resolvedPath,
                    contentUri = contentUri,
                    mediaStoreId = id,
                    displayName = displayName.ifBlank { File(resolvedPath).name },
                    fileSize = fileSize,
                    dateModifiedSec = if (dateModCol >= 0 && !cursor.isNull(dateModCol)) cursor.getLong(dateModCol) else 0L,
                    dateAddedSec = if (dateAddedCol >= 0 && !cursor.isNull(dateAddedCol)) cursor.getLong(dateAddedCol) else 0L,
                    generationModified = if (genModCol >= 0 && !cursor.isNull(genModCol)) cursor.getLong(genModCol) else 0L,
                    durationMs = durationMs,
                    title = if (titleCol >= 0) cursor.getString(titleCol)?.trim() else null,
                    artist = if (artistCol >= 0) cursor.getString(artistCol)?.trim() else null,
                    album = if (albumCol >= 0) cursor.getString(albumCol)?.trim() else null,
                    albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol)?.trim() else null,
                    albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) cursor.getLong(albumIdCol) else null,
                    trackNumber = trackNumber,
                    discNumber = discNumber,
                    year = if (yearCol >= 0 && !cursor.isNull(yearCol)) cursor.getInt(yearCol) else 0,
                    genre = if (genreCol >= 0) cursor.getString(genreCol)?.trim() else null,
                    sourceTier = ScanSourceTier.MEDIA_STORE
                )
            )
        }
        return rows
    }

    // ═══ Volume Resolution Helpers ═══
    private fun resolveAudioVolumeUris(): List<Uri> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volumeNames = runCatching { MediaStore.getExternalVolumeNames(context) }.getOrNull()
            if (!volumeNames.isNullOrEmpty()) {
                return volumeNames.mapNotNull { name ->
                    runCatching { MediaStore.Audio.Media.getContentUri(name) }.getOrNull()
                }.ifEmpty { listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) }
            }
        }
        return listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
    }

    internal fun resolvePublicVolumePathMap(): Map<String, String> {
        val map = HashMap<String, String>()
        val primaryRoot = runCatching {
            Environment.getExternalStorageDirectory().absolutePath
        }.getOrDefault("/storage/emulated/0")
        map[MediaStore.VOLUME_EXTERNAL_PRIMARY.lowercase(Locale.ROOT)] = normalizePathKey(primaryRoot)

        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return@runCatching
            for (vol in sm.storageVolumes) {
                val uuid = vol.uuid?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    vol.directory
                } else {
                    runCatching {
                        val getPath = vol.javaClass.getMethod("getPath")
                        (getPath.invoke(vol) as? String)?.let { File(it) }
                    }.getOrNull()
                }
                val pathStr = dir?.absolutePath?.let(::normalizePathKey) ?: continue
                val publicPath = if (pathStr.startsWith("/mnt/media_rw/")) {
                    val candidate = "/storage/$uuid"
                    if (File(candidate).exists()) candidate else pathStr
                } else {
                    pathStr
                }
                map[uuid.lowercase(Locale.ROOT)] = publicPath
            }
        }
        return map
    }

    private fun resolveNormalizedPath(
        rawDataPath: String,
        relativePath: String,
        displayName: String,
        volumeName: String,
        volumePathMap: Map<String, String>
    ): String {
        if (rawDataPath.isNotBlank()) {
            val norm = normalizePathKey(rawDataPath)
            if (norm.startsWith("/mnt/media_rw/")) {
                val after = norm.removePrefix("/mnt/media_rw/")
                val uuid = after.substringBefore('/')
                val rel = after.substringAfter('/', "")
                val publicRoot = volumePathMap[uuid.lowercase(Locale.ROOT)] ?: "/storage/$uuid"
                return if (rel.isEmpty()) publicRoot else "$publicRoot/$rel"
            }
            return norm
        }
        if (displayName.isNotBlank()) {
            val root = volumePathMap[volumeName.lowercase(Locale.ROOT)]
                ?: volumePathMap[MediaStore.VOLUME_EXTERNAL_PRIMARY]
                ?: "/storage/emulated/0"
            val rel = normalizePathKey(relativePath).trimStart('/')
            return if (rel.isEmpty()) "$root/$displayName" else "$root/$rel/$displayName"
        }
        return ""
    }
}
