/**
 * MusicRepository — Unified library repository coordinating SQLite persistence, two-tier
 * incremental scanning, reactive MediaStore observation, playlists, history, and file deletion.
 *
 * @remarks
 * Delegates two-tier audio discovery, RAM fingerprint diffing, bounded-concurrency byte-level
 * metadata extraction, and transactional batch writes to [HybridLibraryScanner], while exposing a
 * debounced `ContentObserver` stream via [MediaStoreChangeObserver] so background file changes
 * synchronize without full rescans or artwork cache wipes. Device deletion handles Android 11+
 * Scoped Storage `createDeleteRequest` and API 29 `RecoverableSecurityException` flows seamlessly.
 *
 * @see HybridLibraryScanner
 * @see MediaStoreChangeObserver
 * @see MusicDbHelper
 * @see AlbumArtManager
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Internal modules
import com.quandot.musicplayer.data.scanner.HybridLibraryScanner
import com.quandot.musicplayer.data.scanner.LibraryScanConfig
import com.quandot.musicplayer.data.scanner.MediaStoreChangeObserver
import com.quandot.musicplayer.data.scanner.ScanDeltaResult
import com.quandot.musicplayer.util.isUnknownArtistName
import com.quandot.musicplayer.util.isUnknownGenreName

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.Artist
import com.quandot.musicplayer.model.Folder
import com.quandot.musicplayer.model.Genre
import com.quandot.musicplayer.model.LibraryScanState
import com.quandot.musicplayer.model.NATURAL_TEXT_COMPARATOR
import com.quandot.musicplayer.model.Playlist
import com.quandot.musicplayer.model.Song
import com.quandot.musicplayer.model.YearGroup

// ═══ Music Repository ═══
class MusicRepository(private val context: Context) {

    // ═══ Dependencies ═══
    private val db = MusicDbHelper(context)
    private val scanner = HybridLibraryScanner(context, db)
    private val changeObserver = MediaStoreChangeObserver(context)
    val artCache = AlbumArtCache(context)

    val scanState: StateFlow<LibraryScanState> = scanner.scanState

    // ═══ Library Cache & Hybrid Incremental Scanning ═══

    /**
     * Reads all cached songs from the local SQLite snapshot for instant cold-start rendering.
     */
    suspend fun getCachedSongs(): List<Song> = withContext(Dispatchers.IO) {
        db.getAllSongs()
    }

    /**
     * Reads all cached albums from the local SQLite snapshot.
     */
    suspend fun getCachedAlbums(): List<Album> = withContext(Dispatchers.IO) {
        db.getAllAlbums()
    }

    suspend fun hasCachedData(): Boolean = withContext(Dispatchers.IO) {
        db.hasCachedSongs()
    }

    /**
     * Runs the two-tier incremental library scanner, skipping unchanged files with zero per-file
     * I/O and returning the synchronized library snapshot plus affected album keys.
     */
    suspend fun runIncrementalScan(
        config: LibraryScanConfig,
        onProgress: ((scannedCount: Int, totalCount: Int, currentFolder: String) -> Unit)? = null
    ): ScanDeltaResult {
        if (config.clearArtCache) {
            withContext(Dispatchers.IO) { artCache.clearCache() }
        }
        return scanner.scan(config = config, onProgress = onProgress)
    }

    /**
     * Returns a debounced flow emitting whenever `MediaStore.Audio` changes or removable storage
     * volumes mount/unmount.
     */
    fun observeLibraryChanges(): Flow<Unit> = changeObserver.observeChanges()

    // ═══ Library Aggregations ═══
    fun extractAlbums(songs: List<Song>): List<Album> = scanner.extractAlbums(songs)

    fun extractArtists(songs: List<Song>): List<Artist> {
        return songs.groupBy {
            if (isUnknownArtistName(it.artist)) "Nghệ sĩ không xác định" else it.artist.trim()
        }.toList().mapIndexed { index, (artistName, songList) ->
            val albumCount = songList.map { it.album }.distinct().size
            Artist(
                id = (artistName.hashCode().toLong() and 0x7FFFFFFF) + index,
                name = artistName,
                songCount = songList.size,
                albumCount = albumCount
            )
        }.sortedWith(
            compareBy<Artist> { if (isUnknownArtistName(it.name)) 0 else 1 }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
        )
    }

    fun extractFolders(songs: List<Song>): List<Folder> {
        return songs.groupBy {
            val file = File(it.path)
            file.parentFile?.absolutePath ?: "/Bộ nhớ"
        }.map { (folderPath, songList) ->
            val folderName = File(folderPath).name
            Folder(
                name = if (folderName.isBlank()) "Nhạc" else folderName,
                path = folderPath,
                songCount = songList.size
            )
        }.sortedWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.name })
    }

    fun extractGenres(songs: List<Song>): List<Genre> {
        return songs.groupBy {
            if (isUnknownGenreName(it.genre)) "Thể loại không xác định" else it.genre.trim()
        }.map { (genreName, songList) ->
            Genre(name = genreName, songCount = songList.size)
        }.sortedWith(
            compareBy<Genre> { if (isUnknownGenreName(it.name)) 0 else 1 }
                .thenBy(NATURAL_TEXT_COMPARATOR) { it.name }
        )
    }

    fun extractYears(songs: List<Song>): List<YearGroup> {
        return songs.groupBy { if (it.year <= 0) 0 else it.year }.map { (year, songList) ->
            YearGroup(year = year, songCount = songList.size)
        }.sortedWith(
            compareBy<YearGroup> { if (it.year <= 0) 0 else 1 }
                .thenByDescending { it.year }
        )
    }

    // ═══ Playback History ═══
    suspend fun recordPlay(songId: Long) = withContext(Dispatchers.IO) {
        db.recordSongPlay(songId)
    }

    suspend fun getTopPlayedSongIds(limit: Int = 30): List<Pair<Long, Int>> = withContext(Dispatchers.IO) {
        db.getTopPlayedSongIds(limit)
    }

    suspend fun getRecentlyPlayedSongIds(): List<Long> = withContext(Dispatchers.IO) {
        db.getRecentlyPlayedSongIds()
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        db.clearPlaybackHistory()
    }

    // ═══ Playlists & Favorites ═══
    suspend fun getAllPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        db.getAllPlaylists()
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        db.createPlaylist(name)
    }

    suspend fun deletePlaylist(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        db.deletePlaylist(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        db.renamePlaylist(playlistId, newName)
    }

    suspend fun getPlaylistSongIds(playlistId: Long): List<Long> = withContext(Dispatchers.IO) {
        db.getPlaylistSongIds(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean = withContext(Dispatchers.IO) {
        db.addSongToPlaylist(playlistId, songId)
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        db.addSongsToPlaylist(playlistId, songIds)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long): Boolean = withContext(Dispatchers.IO) {
        db.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun getAllFavoriteSongIds(): Set<Long> = withContext(Dispatchers.IO) {
        db.getAllFavoriteSongIds()
    }

    suspend fun toggleFavorite(songId: Long): Boolean = withContext(Dispatchers.IO) {
        db.toggleFavorite(songId)
    }

    suspend fun removeSongFromLocalDb(songId: Long) = withContext(Dispatchers.IO) {
        db.deleteSongById(songId)
    }

    // ═══ Backup & Restore ═══
    suspend fun exportUserDataJson(): JSONObject = withContext(Dispatchers.IO) {
        db.exportUserDataJson()
    }

    suspend fun importUserDataJson(data: JSONObject): Pair<Int, Int> = withContext(Dispatchers.IO) {
        db.importUserDataJson(data)
    }

    // ═══ File Deletion ═══

    /**
     * Permanently removes a track from device storage and SQLite, returning `null` if deleted
     * immediately or an `IntentSender` when Android Scoped Storage requires user confirmation.
     */
    suspend fun deleteSongFromDevice(song: Song): IntentSender? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val file = if (song.path.isNotBlank()) File(song.path) else null

        if (file != null && !file.exists()) {
            if (song.contentUri != Uri.EMPTY) {
                runCatching { resolver.delete(song.contentUri, null, null) }
            }
            db.deleteSongById(song.id)
            return@withContext null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (file != null && file.exists() && file.delete()) {
                    if (song.contentUri != Uri.EMPTY) {
                        runCatching { resolver.delete(song.contentUri, null, null) }
                    }
                    db.deleteSongById(song.id)
                    return@withContext null
                }
                if (song.contentUri != Uri.EMPTY) {
                    val deletedRows = resolver.delete(song.contentUri, null, null)
                    if (deletedRows > 0) {
                        if (file != null && file.exists()) runCatching { file.delete() }
                        db.deleteSongById(song.id)
                        return@withContext null
                    }
                }
            } catch (_: SecurityException) {
                // Scoped Storage requires explicit user confirmation via createDeleteRequest
            }

            if (song.contentUri != Uri.EMPTY) {
                val pendingIntent = MediaStore.createDeleteRequest(resolver, listOf(song.contentUri))
                return@withContext pendingIntent.intentSender
            }
            throw IllegalStateException("Không thể xóa bài hát khỏi thiết bị")
        } else {
            try {
                var deleted = false
                if (song.contentUri != Uri.EMPTY) {
                    deleted = resolver.delete(song.contentUri, null, null) > 0
                }
                if (file != null && (!file.exists() || file.delete())) {
                    deleted = true
                }
                if (deleted) {
                    db.deleteSongById(song.id)
                    return@withContext null
                }
                throw IllegalStateException("Không thể xóa bài hát khỏi thiết bị")
            } catch (sec: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sec is RecoverableSecurityException) {
                    return@withContext sec.userAction.actionIntent.intentSender
                }
                throw sec
            }
        }
    }
}
