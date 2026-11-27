/**
 * MusicDbHelper — SQLite persistence for cached library metadata, playback history, and playlists.
 *
 * @remarks
 * Enables Write-Ahead Logging in `onConfigure` so concurrent Compose UI queries never block on
 * background MediaStore sync transactions or playback-history inserts. Schema upgrades below v6
 * apply additive DDL (`ALTER TABLE` / `CREATE TABLE IF NOT EXISTS`) to preserve user playlists,
 * favorites, and listening history across app updates.
 *
 * @see MusicRepository
 * @see Song
 * @see Album
 * @see Playlist
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Internal modules
import com.quandot.musicplayer.data.scanner.SongFingerprint
import com.quandot.musicplayer.data.scanner.SongUpsertEntry
import com.quandot.musicplayer.data.scanner.normalizePathKey
import com.quandot.musicplayer.debug.PDLog

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Album
import com.quandot.musicplayer.model.NATURAL_TEXT_COMPARATOR
import com.quandot.musicplayer.model.Playlist
import com.quandot.musicplayer.model.Song

class MusicDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    // ═══ Schema & Constants ═══
    companion object {
        const val DATABASE_NAME = "soopidoo_music.db"
        const val DATABASE_VERSION = 8

        const val TABLE_SONGS = "songs"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_ARTIST = "artist"
        const val COL_ALBUM = "album"
        const val COL_DURATION = "duration"
        const val COL_CONTENT_URI = "content_uri"
        const val COL_ALBUM_ART_URI = "album_art_uri"
        const val COL_GRAD_START = "grad_start"
        const val COL_GRAD_END = "grad_end"
        const val COL_PATH = "path"
        const val COL_DATE_ADDED = "date_added"
        const val COL_ALBUM_ID = "album_id"
        const val COL_TRACK_NUMBER = "track_number"
        const val COL_SONG_YEAR = "year"
        const val COL_GENRE = "genre"
        const val COL_SONG_ALBUM_ARTIST = "album_artist"
        const val COL_FILE_SIZE = "file_size"
        const val COL_DATE_MODIFIED = "date_modified"
        const val COL_GENERATION_MODIFIED = "generation_modified"
        const val COL_DISC_NUMBER = "disc_number"
        const val COL_SAMPLE_RATE = "sample_rate"
        const val COL_BIT_DEPTH = "bit_depth"
        const val COL_BITRATE = "bitrate"
        const val COL_CODEC = "codec"

        const val DB_BATCH_SIZE = 150

        const val TABLE_ALBUMS = "albums"
        const val COL_ALBUM_TITLE = "album_title"
        const val COL_ALBUM_ARTIST = "album_artist"
        const val COL_SONG_COUNT = "song_count"
        const val COL_COLOR_START = "color_start"
        const val COL_COLOR_END = "color_end"
        const val COL_ALBUM_YEAR = "year"

        // Playback history table
        const val TABLE_PLAYBACK_HISTORY = "playback_history"
        const val COL_HISTORY_ID = "id"
        const val COL_HISTORY_SONG_ID = "song_id"
        const val COL_HISTORY_PLAYED_AT = "played_at"

        // Playlists & playlist membership junction tables
        const val TABLE_PLAYLISTS = "playlists"
        const val COL_PLAYLIST_ID = "id"
        const val COL_PLAYLIST_NAME = "name"
        const val COL_PLAYLIST_IS_SYSTEM = "is_system"
        const val COL_PLAYLIST_CREATED_AT = "created_at"

        const val TABLE_PLAYLIST_SONGS = "playlist_songs"
        const val COL_PS_PLAYLIST_ID = "playlist_id"
        const val COL_PS_SONG_ID = "song_id"
        const val COL_PS_ADDED_AT = "added_at"

        const val FAVORITES_PLAYLIST_ID = 1L
        const val FAVORITES_PLAYLIST_NAME = "Bài hát yêu thích"
    }

    // ═══ SQLiteOpenHelper Lifecycle ═══
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SONGS (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_ARTIST TEXT NOT NULL,
                $COL_ALBUM TEXT NOT NULL,
                $COL_DURATION INTEGER NOT NULL,
                $COL_CONTENT_URI TEXT NOT NULL,
                $COL_ALBUM_ART_URI TEXT,
                $COL_GRAD_START INTEGER NOT NULL,
                $COL_GRAD_END INTEGER NOT NULL,
                $COL_PATH TEXT NOT NULL,
                $COL_DATE_ADDED INTEGER NOT NULL,
                $COL_ALBUM_ID INTEGER NOT NULL,
                $COL_TRACK_NUMBER INTEGER NOT NULL DEFAULT 0,
                $COL_SONG_YEAR INTEGER NOT NULL DEFAULT 0,
                $COL_GENRE TEXT NOT NULL DEFAULT '',
                $COL_SONG_ALBUM_ARTIST TEXT NOT NULL DEFAULT '',
                $COL_FILE_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_DATE_MODIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_GENERATION_MODIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_DISC_NUMBER INTEGER NOT NULL DEFAULT 0,
                $COL_SAMPLE_RATE INTEGER NOT NULL DEFAULT 0,
                $COL_BIT_DEPTH INTEGER NOT NULL DEFAULT 0,
                $COL_BITRATE INTEGER NOT NULL DEFAULT 0,
                $COL_CODEC TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_title ON $TABLE_SONGS ($COL_TITLE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_artist ON $TABLE_SONGS ($COL_ARTIST)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_album_id ON $TABLE_SONGS ($COL_ALBUM_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_date_added ON $TABLE_SONGS ($COL_DATE_ADDED DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_path ON $TABLE_SONGS ($COL_PATH)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUMS (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_ALBUM_TITLE TEXT NOT NULL,
                $COL_ALBUM_ARTIST TEXT NOT NULL,
                $COL_SONG_COUNT INTEGER NOT NULL,
                $COL_ALBUM_ART_URI TEXT,
                $COL_COLOR_START INTEGER NOT NULL,
                $COL_COLOR_END INTEGER NOT NULL,
                $COL_ALBUM_YEAR INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_albums_title ON $TABLE_ALBUMS ($COL_ALBUM_TITLE)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYBACK_HISTORY (
                $COL_HISTORY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_HISTORY_SONG_ID INTEGER NOT NULL,
                $COL_HISTORY_PLAYED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_song_id ON $TABLE_PLAYBACK_HISTORY ($COL_HISTORY_SONG_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_played_at ON $TABLE_PLAYBACK_HISTORY ($COL_HISTORY_PLAYED_AT DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                $COL_PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PLAYLIST_NAME TEXT NOT NULL,
                $COL_PLAYLIST_IS_SYSTEM INTEGER NOT NULL DEFAULT 0,
                $COL_PLAYLIST_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_SONGS (
                $COL_PS_PLAYLIST_ID INTEGER NOT NULL,
                $COL_PS_SONG_ID INTEGER NOT NULL,
                $COL_PS_ADDED_AT INTEGER NOT NULL,
                PRIMARY KEY ($COL_PS_PLAYLIST_ID, $COL_PS_SONG_ID)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_pid ON $TABLE_PLAYLIST_SONGS ($COL_PS_PLAYLIST_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_sid ON $TABLE_PLAYLIST_SONGS ($COL_PS_SONG_ID)")

        // Seed the default system Favorites playlist
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_PLAYLISTS ($COL_PLAYLIST_ID, $COL_PLAYLIST_NAME, $COL_PLAYLIST_IS_SYSTEM, $COL_PLAYLIST_CREATED_AT)
            VALUES ($FAVORITES_PLAYLIST_ID, '$FAVORITES_PLAYLIST_NAME', 1, ${System.currentTimeMillis()})
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 6) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SONGS ADD COLUMN $COL_GENRE TEXT NOT NULL DEFAULT ''")
            } catch (_: Exception) {}

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                    $COL_PLAYLIST_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_PLAYLIST_NAME TEXT NOT NULL,
                    $COL_PLAYLIST_IS_SYSTEM INTEGER NOT NULL DEFAULT 0,
                    $COL_PLAYLIST_CREATED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_SONGS (
                    $COL_PS_PLAYLIST_ID INTEGER NOT NULL,
                    $COL_PS_SONG_ID INTEGER NOT NULL,
                    $COL_PS_ADDED_AT INTEGER NOT NULL,
                    PRIMARY KEY ($COL_PS_PLAYLIST_ID, $COL_PS_SONG_ID)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_pid ON $TABLE_PLAYLIST_SONGS ($COL_PS_PLAYLIST_ID)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_sid ON $TABLE_PLAYLIST_SONGS ($COL_PS_SONG_ID)")

            db.execSQL(
                """
                INSERT OR IGNORE INTO $TABLE_PLAYLISTS ($COL_PLAYLIST_ID, $COL_PLAYLIST_NAME, $COL_PLAYLIST_IS_SYSTEM, $COL_PLAYLIST_CREATED_AT)
                VALUES ($FAVORITES_PLAYLIST_ID, '$FAVORITES_PLAYLIST_NAME', 1, ${System.currentTimeMillis()})
                """.trimIndent()
            )
        }
        if (oldVersion < 7) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SONGS ADD COLUMN $COL_SONG_ALBUM_ARTIST TEXT NOT NULL DEFAULT ''")
            } catch (_: Exception) {}
        }
        if (oldVersion < 8) {
            ensureV8Columns(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            ensureV8Columns(db)
        }
    }

    private fun ensureV8Columns(db: SQLiteDatabase) {
        val existingCols = HashSet<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info($TABLE_SONGS)", null).use { c ->
                val nameCol = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameCol >= 0) existingCols.add(c.getString(nameCol))
                }
            }
        }
        if (existingCols.isEmpty()) return

        val requiredCols = listOf(
            COL_TRACK_NUMBER to "$COL_TRACK_NUMBER INTEGER NOT NULL DEFAULT 0",
            COL_SONG_YEAR to "$COL_SONG_YEAR INTEGER NOT NULL DEFAULT 0",
            COL_GENRE to "$COL_GENRE TEXT NOT NULL DEFAULT ''",
            COL_SONG_ALBUM_ARTIST to "$COL_SONG_ALBUM_ARTIST TEXT NOT NULL DEFAULT ''",
            COL_FILE_SIZE to "$COL_FILE_SIZE INTEGER NOT NULL DEFAULT 0",
            COL_DATE_MODIFIED to "$COL_DATE_MODIFIED INTEGER NOT NULL DEFAULT 0",
            COL_GENERATION_MODIFIED to "$COL_GENERATION_MODIFIED INTEGER NOT NULL DEFAULT 0",
            COL_DISC_NUMBER to "$COL_DISC_NUMBER INTEGER NOT NULL DEFAULT 0",
            COL_SAMPLE_RATE to "$COL_SAMPLE_RATE INTEGER NOT NULL DEFAULT 0",
            COL_BIT_DEPTH to "$COL_BIT_DEPTH INTEGER NOT NULL DEFAULT 0",
            COL_BITRATE to "$COL_BITRATE INTEGER NOT NULL DEFAULT 0",
            COL_CODEC to "$COL_CODEC TEXT NOT NULL DEFAULT ''"
        )
        for ((colName, colDef) in requiredCols) {
            if (colName !in existingCols) {
                runCatching { db.execSQL("ALTER TABLE $TABLE_SONGS ADD COLUMN $colDef") }
            }
        }
        runCatching {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_path ON $TABLE_SONGS ($COL_PATH)")
        }
    }

    // ═══ Songs & Albums Cache ═══
    fun getAllSongs(): List<Song> {
        val list = mutableListOf<Song>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SONGS ORDER BY $COL_TITLE COLLATE NOCASE ASC", null)
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(COL_ID)
            val titleCol = c.getColumnIndexOrThrow(COL_TITLE)
            val artistCol = c.getColumnIndexOrThrow(COL_ARTIST)
            val albumCol = c.getColumnIndexOrThrow(COL_ALBUM)
            val durationCol = c.getColumnIndexOrThrow(COL_DURATION)
            val contentUriCol = c.getColumnIndexOrThrow(COL_CONTENT_URI)
            val albumArtUriCol = c.getColumnIndexOrThrow(COL_ALBUM_ART_URI)
            val gradStartCol = c.getColumnIndexOrThrow(COL_GRAD_START)
            val gradEndCol = c.getColumnIndexOrThrow(COL_GRAD_END)
            val pathCol = c.getColumnIndexOrThrow(COL_PATH)
            val dateAddedCol = c.getColumnIndexOrThrow(COL_DATE_ADDED)
            val albumIdCol = c.getColumnIndexOrThrow(COL_ALBUM_ID)
            val trackNumberCol = c.getColumnIndex(COL_TRACK_NUMBER)
            val yearCol = c.getColumnIndex(COL_SONG_YEAR)
            val genreCol = c.getColumnIndex(COL_GENRE)
            val albumArtistCol = c.getColumnIndex(COL_SONG_ALBUM_ARTIST)
            val fileSizeCol = c.getColumnIndex(COL_FILE_SIZE)
            val dateModifiedCol = c.getColumnIndex(COL_DATE_MODIFIED)
            val genModifiedCol = c.getColumnIndex(COL_GENERATION_MODIFIED)
            val discNumberCol = c.getColumnIndex(COL_DISC_NUMBER)
            val sampleRateCol = c.getColumnIndex(COL_SAMPLE_RATE)
            val bitDepthCol = c.getColumnIndex(COL_BIT_DEPTH)
            val bitrateCol = c.getColumnIndex(COL_BITRATE)
            val codecCol = c.getColumnIndex(COL_CODEC)

            while (c.moveToNext()) {
                val contentUri = Uri.parse(c.getString(contentUriCol))
                val storedArtUri = c.getString(albumArtUriCol)?.let { Uri.parse(it) }
                // Normalize legacy shared albumart URIs on read so tracks immediately resolve isolated artwork
                val resolvedArtUri = if (
                    contentUri != Uri.EMPTY &&
                    (storedArtUri == null || storedArtUri.toString().contains("/audio/albumart"))
                ) {
                    contentUri
                } else {
                    storedArtUri
                }
                list.add(
                    Song(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol),
                        artist = c.getString(artistCol),
                        album = c.getString(albumCol),
                        duration = c.getLong(durationCol),
                        contentUri = contentUri,
                        albumArtUri = resolvedArtUri,
                        albumGradientColorStart = c.getLong(gradStartCol),
                        albumGradientColorEnd = c.getLong(gradEndCol),
                        path = c.getString(pathCol),
                        dateAdded = c.getLong(dateAddedCol),
                        albumId = c.getLong(albumIdCol),
                        trackNumber = if (trackNumberCol >= 0) c.getInt(trackNumberCol) else 0,
                        year = if (yearCol >= 0) c.getInt(yearCol) else 0,
                        genre = if (genreCol >= 0) c.getString(genreCol) ?: "" else "",
                        albumArtist = if (albumArtistCol >= 0) c.getString(albumArtistCol) ?: "" else "",
                        discNumber = if (discNumberCol >= 0) c.getInt(discNumberCol) else 0,
                        fileSize = if (fileSizeCol >= 0) c.getLong(fileSizeCol) else 0L,
                        dateModified = if (dateModifiedCol >= 0) c.getLong(dateModifiedCol) else 0L,
                        generationModified = if (genModifiedCol >= 0) c.getLong(genModifiedCol) else 0L,
                        sampleRate = if (sampleRateCol >= 0) c.getInt(sampleRateCol) else 0,
                        bitDepth = if (bitDepthCol >= 0) c.getInt(bitDepthCol) else 0,
                        bitrate = if (bitrateCol >= 0) c.getInt(bitrateCol) else 0,
                        codec = if (codecCol >= 0) c.getString(codecCol) ?: "" else ""
                    )
                )
            }
        }
        list.sortWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.title })
        return list
    }

    fun getAllAlbums(): List<Album> {
        val list = mutableListOf<Album>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_ALBUMS ORDER BY $COL_ALBUM_TITLE COLLATE NOCASE ASC", null)
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(COL_ID)
            val titleCol = c.getColumnIndexOrThrow(COL_ALBUM_TITLE)
            val artistCol = c.getColumnIndexOrThrow(COL_ALBUM_ARTIST)
            val songCountCol = c.getColumnIndexOrThrow(COL_SONG_COUNT)
            val albumArtUriCol = c.getColumnIndexOrThrow(COL_ALBUM_ART_URI)
            val colorStartCol = c.getColumnIndexOrThrow(COL_COLOR_START)
            val colorEndCol = c.getColumnIndexOrThrow(COL_COLOR_END)
            val yearCol = c.getColumnIndex(COL_ALBUM_YEAR)

            while (c.moveToNext()) {
                list.add(
                    Album(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol),
                        artist = c.getString(artistCol),
                        songCount = c.getInt(songCountCol),
                        albumArtUri = c.getString(albumArtUriCol)?.let { Uri.parse(it) },
                        colorStart = c.getLong(colorStartCol),
                        colorEnd = c.getLong(colorEndCol),
                        year = if (yearCol >= 0) c.getInt(yearCol) else 0
                    )
                )
            }
        }
        list.sortWith(compareBy(NATURAL_TEXT_COMPARATOR) { it.title })
        return list
    }

    fun hasCachedSongs(): Boolean {
        return getSongCount() > 0
    }

    fun getSongCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SONGS", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }

    fun getMaxDateAdded(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT MAX($COL_DATE_ADDED) FROM $TABLE_SONGS", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return 0L
    }

    /**
     * Loads a lightweight `lookupKey -> SongFingerprint` map into RAM for O(1) incremental delta
     * diffing without hydrating full domain objects or artwork URIs.
     */
    fun getSongFingerprints(): HashMap<String, SongFingerprint> {
        val map = HashMap<String, SongFingerprint>()
        val db = readableDatabase
        val query = """
            SELECT $COL_ID, $COL_PATH, $COL_FILE_SIZE, $COL_DATE_MODIFIED, $COL_GENERATION_MODIFIED,
                   $COL_DURATION, $COL_ALBUM_ID, $COL_ALBUM, $COL_ARTIST, $COL_SONG_ALBUM_ARTIST,
                   $COL_CONTENT_URI, $COL_SAMPLE_RATE, $COL_BIT_DEPTH, $COL_BITRATE, $COL_CODEC
            FROM $TABLE_SONGS
        """.trimIndent()
        db.rawQuery(query, null).use { c ->
            while (c.moveToNext()) {
                val rawPath = c.getString(1) ?: ""
                val contentUriStr = c.getString(10) ?: ""
                val lookupKey = if (rawPath.isNotBlank()) {
                    normalizePathKey(rawPath)
                } else if (contentUriStr.isNotBlank()) {
                    "saf://$contentUriStr"
                } else {
                    continue
                }
                map[lookupKey] = SongFingerprint(
                    id = c.getLong(0),
                    path = rawPath,
                    fileSize = c.getLong(2),
                    dateModifiedSec = c.getLong(3),
                    generationModified = c.getLong(4),
                    durationMs = c.getLong(5),
                    albumId = c.getLong(6),
                    album = c.getString(7) ?: "",
                    artist = c.getString(8) ?: "",
                    albumArtist = c.getString(9) ?: "",
                    contentUriStr = contentUriStr,
                    sampleRate = c.getInt(11),
                    bitDepth = c.getInt(12),
                    bitrate = c.getInt(13),
                    codec = c.getString(14) ?: ""
                )
            }
        }
        return map
    }

    /**
     * Persists a batch of new or modified tracks in a single SQLite transaction.
     *
     * Never uses `INSERT OR REPLACE` on modified tracks: existing rows are updated in-place via
     * `UPDATE`, and when a track transitions between a synthetic direct-file ID and a MediaStore
     * ID, playlist and history references are re-keyed atomically to preserve Favorites, Playlists,
     * and play counts.
     */
    fun upsertSongsBatch(entries: List<SongUpsertEntry>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val insertSql = """
                INSERT OR IGNORE INTO $TABLE_SONGS (
                    $COL_ID, $COL_TITLE, $COL_ARTIST, $COL_ALBUM, $COL_DURATION, $COL_CONTENT_URI,
                    $COL_ALBUM_ART_URI, $COL_GRAD_START, $COL_GRAD_END, $COL_PATH, $COL_DATE_ADDED,
                    $COL_ALBUM_ID, $COL_TRACK_NUMBER, $COL_SONG_YEAR, $COL_GENRE, $COL_SONG_ALBUM_ARTIST,
                    $COL_FILE_SIZE, $COL_DATE_MODIFIED, $COL_GENERATION_MODIFIED, $COL_DISC_NUMBER,
                    $COL_SAMPLE_RATE, $COL_BIT_DEPTH, $COL_BITRATE, $COL_CODEC
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            val updateSql = """
                UPDATE $TABLE_SONGS SET
                    $COL_TITLE = ?, $COL_ARTIST = ?, $COL_ALBUM = ?, $COL_DURATION = ?,
                    $COL_CONTENT_URI = ?, $COL_ALBUM_ART_URI = ?, $COL_GRAD_START = ?, $COL_GRAD_END = ?,
                    $COL_PATH = ?, $COL_DATE_ADDED = ?, $COL_ALBUM_ID = ?, $COL_TRACK_NUMBER = ?,
                    $COL_SONG_YEAR = ?, $COL_GENRE = ?, $COL_SONG_ALBUM_ARTIST = ?,
                    $COL_FILE_SIZE = ?, $COL_DATE_MODIFIED = ?, $COL_GENERATION_MODIFIED = ?,
                    $COL_DISC_NUMBER = ?, $COL_SAMPLE_RATE = ?, $COL_BIT_DEPTH = ?,
                    $COL_BITRATE = ?, $COL_CODEC = ?
                WHERE $COL_ID = ?
            """.trimIndent()

            db.compileStatement(insertSql).use { insertStmt ->
                db.compileStatement(updateSql).use { updateStmt ->
                    for (entry in entries) {
                        val s = entry.song
                        val oldId = entry.remapFromSongId
                        if (oldId != null && oldId != s.id) {
                            remapSongReferencesLocked(db, oldId, s.id)
                            db.delete(TABLE_SONGS, "$COL_ID = ?", arrayOf(oldId.toString()))
                        }

                        if (entry.isUpdate && (oldId == null || oldId == s.id)) {
                            bindSongUpdateStatement(updateStmt, s)
                            val updatedRows = updateStmt.executeUpdateDelete()
                            if (updatedRows == 0) {
                                bindSongInsertStatement(insertStmt, s)
                                insertStmt.executeInsert()
                            }
                        } else {
                            bindSongInsertStatement(insertStmt, s)
                            val insertedRowId = insertStmt.executeInsert()
                            if (insertedRowId == -1L) {
                                bindSongUpdateStatement(updateStmt, s)
                                updateStmt.executeUpdateDelete()
                            }
                        }
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Deletes songs removed from storage in bounded transactions while keeping `playlist_songs`
     * and `playback_history` intact so temporarily unmounted SD/USB volumes retain user data.
     */
    fun deleteSongsBatch(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        val db = writableDatabase
        for (chunk in songIds.chunked(DB_BATCH_SIZE)) {
            db.beginTransaction()
            try {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                db.delete(TABLE_SONGS, "$COL_ID IN ($placeholders)", args)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * Reconciles the `albums` cache table with the active song set, updating existing album rows
     * in-place, inserting newly discovered albums, and pruning empty albums with zero songs.
     */
    fun syncAlbumsFromSongs(albums: List<Album>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val activeIds = HashSet<Long>(albums.size.coerceAtLeast(16))
            val updateSql = """
                UPDATE $TABLE_ALBUMS SET
                    $COL_ALBUM_TITLE = ?, $COL_ALBUM_ARTIST = ?, $COL_SONG_COUNT = ?,
                    $COL_ALBUM_ART_URI = ?, $COL_COLOR_START = ?, $COL_COLOR_END = ?,
                    $COL_ALBUM_YEAR = ?
                WHERE $COL_ID = ?
            """.trimIndent()
            val insertSql = """
                INSERT OR IGNORE INTO $TABLE_ALBUMS (
                    $COL_ID, $COL_ALBUM_TITLE, $COL_ALBUM_ARTIST, $COL_SONG_COUNT,
                    $COL_ALBUM_ART_URI, $COL_COLOR_START, $COL_COLOR_END, $COL_ALBUM_YEAR
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            db.compileStatement(updateSql).use { updateStmt ->
                db.compileStatement(insertSql).use { insertStmt ->
                    for (a in albums) {
                        activeIds.add(a.id)
                        updateStmt.bindString(1, a.title)
                        updateStmt.bindString(2, a.artist)
                        updateStmt.bindLong(3, a.songCount.toLong())
                        if (a.albumArtUri != null) {
                            updateStmt.bindString(4, a.albumArtUri.toString())
                        } else {
                            updateStmt.bindNull(4)
                        }
                        updateStmt.bindLong(5, a.colorStart)
                        updateStmt.bindLong(6, a.colorEnd)
                        updateStmt.bindLong(7, a.year.toLong())
                        updateStmt.bindLong(8, a.id)

                        if (updateStmt.executeUpdateDelete() == 0) {
                            insertStmt.bindLong(1, a.id)
                            insertStmt.bindString(2, a.title)
                            insertStmt.bindString(3, a.artist)
                            insertStmt.bindLong(4, a.songCount.toLong())
                            if (a.albumArtUri != null) {
                                insertStmt.bindString(5, a.albumArtUri.toString())
                            } else {
                                insertStmt.bindNull(5)
                            }
                            insertStmt.bindLong(6, a.colorStart)
                            insertStmt.bindLong(7, a.colorEnd)
                            insertStmt.bindLong(8, a.year.toLong())
                            insertStmt.executeInsert()
                        }
                    }
                }
            }

            // Prune orphaned empty albums that no longer have any tracks in `songs`
            val staleAlbumIds = ArrayList<Long>()
            db.rawQuery("SELECT $COL_ID FROM $TABLE_ALBUMS", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (!activeIds.contains(id)) {
                        staleAlbumIds.add(id)
                    }
                }
            }
            for (chunk in staleAlbumIds.chunked(DB_BATCH_SIZE)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val args = chunk.map { it.toString() }.toTypedArray()
                db.delete(TABLE_ALBUMS, "$COL_ID IN ($placeholders)", args)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun remapSongReferencesLocked(db: SQLiteDatabase, oldId: Long, newId: Long) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO $TABLE_PLAYLIST_SONGS ($COL_PS_PLAYLIST_ID, $COL_PS_SONG_ID, $COL_PS_ADDED_AT)
            SELECT $COL_PS_PLAYLIST_ID, ?, $COL_PS_ADDED_AT
            FROM $TABLE_PLAYLIST_SONGS
            WHERE $COL_PS_SONG_ID = ?
            """.trimIndent(),
            arrayOf<Any>(newId, oldId)
        )
        db.delete(TABLE_PLAYLIST_SONGS, "$COL_PS_SONG_ID = ?", arrayOf(oldId.toString()))
        db.execSQL(
            "UPDATE $TABLE_PLAYBACK_HISTORY SET $COL_HISTORY_SONG_ID = ? WHERE $COL_HISTORY_SONG_ID = ?",
            arrayOf<Any>(newId, oldId)
        )
    }

    private fun bindSongInsertStatement(stmt: android.database.sqlite.SQLiteStatement, s: Song) {
        stmt.clearBindings()
        stmt.bindLong(1, s.id)
        stmt.bindString(2, s.title)
        stmt.bindString(3, s.artist)
        stmt.bindString(4, s.album)
        stmt.bindLong(5, s.duration)
        stmt.bindString(6, s.contentUri.toString())
        if (s.albumArtUri != null) {
            stmt.bindString(7, s.albumArtUri.toString())
        } else {
            stmt.bindNull(7)
        }
        stmt.bindLong(8, s.albumGradientColorStart)
        stmt.bindLong(9, s.albumGradientColorEnd)
        stmt.bindString(10, s.path)
        stmt.bindLong(11, s.dateAdded)
        stmt.bindLong(12, s.albumId)
        stmt.bindLong(13, s.trackNumber.toLong())
        stmt.bindLong(14, s.year.toLong())
        stmt.bindString(15, s.genre)
        stmt.bindString(16, s.albumArtist)
        stmt.bindLong(17, s.fileSize)
        stmt.bindLong(18, s.dateModified)
        stmt.bindLong(19, s.generationModified)
        stmt.bindLong(20, s.discNumber.toLong())
        stmt.bindLong(21, s.sampleRate.toLong())
        stmt.bindLong(22, s.bitDepth.toLong())
        stmt.bindLong(23, s.bitrate.toLong())
        stmt.bindString(24, s.codec)
    }

    private fun bindSongUpdateStatement(stmt: android.database.sqlite.SQLiteStatement, s: Song) {
        stmt.clearBindings()
        stmt.bindString(1, s.title)
        stmt.bindString(2, s.artist)
        stmt.bindString(3, s.album)
        stmt.bindLong(4, s.duration)
        stmt.bindString(5, s.contentUri.toString())
        if (s.albumArtUri != null) {
            stmt.bindString(6, s.albumArtUri.toString())
        } else {
            stmt.bindNull(6)
        }
        stmt.bindLong(7, s.albumGradientColorStart)
        stmt.bindLong(8, s.albumGradientColorEnd)
        stmt.bindString(9, s.path)
        stmt.bindLong(10, s.dateAdded)
        stmt.bindLong(11, s.albumId)
        stmt.bindLong(12, s.trackNumber.toLong())
        stmt.bindLong(13, s.year.toLong())
        stmt.bindString(14, s.genre)
        stmt.bindString(15, s.albumArtist)
        stmt.bindLong(16, s.fileSize)
        stmt.bindLong(17, s.dateModified)
        stmt.bindLong(18, s.generationModified)
        stmt.bindLong(19, s.discNumber.toLong())
        stmt.bindLong(20, s.sampleRate.toLong())
        stmt.bindLong(21, s.bitDepth.toLong())
        stmt.bindLong(22, s.bitrate.toLong())
        stmt.bindString(23, s.codec)
        stmt.bindLong(24, s.id)
    }

    fun replaceAllSongsAndAlbums(songs: List<Song>, albums: List<Album>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_SONGS")
            db.compileStatement(
                """
                INSERT INTO $TABLE_SONGS (
                    $COL_ID, $COL_TITLE, $COL_ARTIST, $COL_ALBUM, $COL_DURATION, $COL_CONTENT_URI,
                    $COL_ALBUM_ART_URI, $COL_GRAD_START, $COL_GRAD_END, $COL_PATH, $COL_DATE_ADDED,
                    $COL_ALBUM_ID, $COL_TRACK_NUMBER, $COL_SONG_YEAR, $COL_GENRE, $COL_SONG_ALBUM_ARTIST,
                    $COL_FILE_SIZE, $COL_DATE_MODIFIED, $COL_GENERATION_MODIFIED, $COL_DISC_NUMBER,
                    $COL_SAMPLE_RATE, $COL_BIT_DEPTH, $COL_BITRATE, $COL_CODEC
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { songStmt ->
                for (s in songs) {
                    bindSongInsertStatement(songStmt, s)
                    songStmt.executeInsert()
                }
            }

            db.execSQL("DELETE FROM $TABLE_ALBUMS")
            db.compileStatement(
                "INSERT INTO $TABLE_ALBUMS ($COL_ID, $COL_ALBUM_TITLE, $COL_ALBUM_ARTIST, $COL_SONG_COUNT, $COL_ALBUM_ART_URI, $COL_COLOR_START, $COL_COLOR_END, $COL_ALBUM_YEAR) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { albumStmt ->
                for (a in albums) {
                    albumStmt.bindLong(1, a.id)
                    albumStmt.bindString(2, a.title)
                    albumStmt.bindString(3, a.artist)
                    albumStmt.bindLong(4, a.songCount.toLong())
                    if (a.albumArtUri != null) {
                        albumStmt.bindString(5, a.albumArtUri.toString())
                    } else {
                        albumStmt.bindNull(5)
                    }
                    albumStmt.bindLong(6, a.colorStart)
                    albumStmt.bindLong(7, a.colorEnd)
                    albumStmt.bindLong(8, a.year.toLong())
                    albumStmt.executeInsert()
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ═══ Playback History ═══

    /**
     * Appends a playback event timestamp for a song to the history table.
     */
    fun recordSongPlay(songId: Long) {
        try {
            val db = writableDatabase
            db.compileStatement(
                "INSERT INTO $TABLE_PLAYBACK_HISTORY ($COL_HISTORY_SONG_ID, $COL_HISTORY_PLAYED_AT) VALUES (?, ?)"
            ).use { stmt ->
                stmt.bindLong(1, songId)
                stmt.bindLong(2, System.currentTimeMillis())
                stmt.executeInsert()
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=recordSongPlay songId=$songId", e)
        }
    }

    /**
     * Returns the most frequently played `(songId, playCount)` pairs ordered by count and recency.
     */
    fun getTopPlayedSongIds(limit: Int = 30): List<Pair<Long, Int>> {
        val list = mutableListOf<Pair<Long, Int>>()
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_HISTORY_SONG_ID, COUNT(*) as play_count 
                FROM $TABLE_PLAYBACK_HISTORY 
                GROUP BY $COL_HISTORY_SONG_ID 
                ORDER BY play_count DESC, MAX($COL_HISTORY_PLAYED_AT) DESC 
                LIMIT ?
                """.trimIndent(),
                arrayOf(limit.toString())
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(c.getLong(0) to c.getInt(1))
                }
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=getTopPlayedSongIds limit=$limit", e)
        }
        return list
    }

    /**
     * Returns all distinct played song IDs ordered from most recently played to oldest.
     */
    fun getRecentlyPlayedSongIds(): List<Long> {
        val list = mutableListOf<Long>()
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                """
                SELECT $COL_HISTORY_SONG_ID 
                FROM $TABLE_PLAYBACK_HISTORY 
                GROUP BY $COL_HISTORY_SONG_ID 
                ORDER BY MAX($COL_HISTORY_PLAYED_AT) DESC
                """.trimIndent(),
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(c.getLong(0))
                }
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=getRecentlyPlayedSongIds", e)
        }
        return list
    }

    fun getPlaybackHistoryCount(): Int {
        try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_PLAYBACK_HISTORY", null)
            cursor.use {
                if (it.moveToFirst()) return it.getInt(0)
            }
        } catch (e: Exception) {
            PDLog.w(PDLog.SCANNER, "DB_ERR", "op=getPlaybackHistoryCount err=${e.message}")
        }
        return 0
    }

    fun clearPlaybackHistory() {
        try {
            writableDatabase.execSQL("DELETE FROM $TABLE_PLAYBACK_HISTORY")
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=clearPlaybackHistory", e)
        }
    }

    // ═══ Playlists & Favorites ═══

    fun getAllPlaylists(): List<Playlist> {
        val list = mutableListOf<Playlist>()
        val db = readableDatabase
        val query = """
            SELECT p.$COL_PLAYLIST_ID, p.$COL_PLAYLIST_NAME, p.$COL_PLAYLIST_IS_SYSTEM, p.$COL_PLAYLIST_CREATED_AT,
                   COUNT(s.$COL_ID) AS song_count
            FROM $TABLE_PLAYLISTS p
            LEFT JOIN $TABLE_PLAYLIST_SONGS ps ON p.$COL_PLAYLIST_ID = ps.$COL_PS_PLAYLIST_ID
            LEFT JOIN $TABLE_SONGS s ON ps.$COL_PS_SONG_ID = s.$COL_ID
            GROUP BY p.$COL_PLAYLIST_ID
            ORDER BY p.$COL_PLAYLIST_IS_SYSTEM DESC, p.$COL_PLAYLIST_CREATED_AT ASC
        """.trimIndent()

        try {
            val cursor = db.rawQuery(query, null)
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(COL_PLAYLIST_ID)
                val nameCol = c.getColumnIndexOrThrow(COL_PLAYLIST_NAME)
                val isSysCol = c.getColumnIndexOrThrow(COL_PLAYLIST_IS_SYSTEM)
                val createdCol = c.getColumnIndexOrThrow(COL_PLAYLIST_CREATED_AT)
                val countCol = c.getColumnIndexOrThrow("song_count")

                while (c.moveToNext()) {
                    list.add(
                        Playlist(
                            id = c.getLong(idCol),
                            name = c.getString(nameCol),
                            isSystem = c.getInt(isSysCol) == 1,
                            createdAt = c.getLong(createdCol),
                            songCount = c.getInt(countCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=getAllPlaylists", e)
        }
        return list
    }

    fun createPlaylist(name: String): Long {
        if (name.isBlank()) return -1L
        val db = writableDatabase
        return try {
            val values = ContentValues().apply {
                put(COL_PLAYLIST_NAME, name.trim())
                put(COL_PLAYLIST_IS_SYSTEM, 0)
                put(COL_PLAYLIST_CREATED_AT, System.currentTimeMillis())
            }
            db.insert(TABLE_PLAYLISTS, null, values)
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=createPlaylist", e)
            -1L
        }
    }

    fun deletePlaylist(playlistId: Long): Boolean {
        // Protect system Favorites playlist from deletion
        if (playlistId == FAVORITES_PLAYLIST_ID) return false
        val db = writableDatabase
        return try {
            db.beginTransaction()
            try {
                db.delete(TABLE_PLAYLIST_SONGS, "$COL_PS_PLAYLIST_ID = ?", arrayOf(playlistId.toString()))
                db.delete(TABLE_PLAYLISTS, "$COL_PLAYLIST_ID = ?", arrayOf(playlistId.toString()))
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=deletePlaylist id=$playlistId", e)
            false
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String): Boolean {
        if (playlistId == FAVORITES_PLAYLIST_ID || newName.isBlank()) return false
        val db = writableDatabase
        return try {
            val values = ContentValues().apply {
                put(COL_PLAYLIST_NAME, newName.trim())
            }
            db.update(TABLE_PLAYLISTS, values, "$COL_PLAYLIST_ID = ?", arrayOf(playlistId.toString())) > 0
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=renamePlaylist id=$playlistId", e)
            false
        }
    }

    fun getPlaylistSongIds(playlistId: Long): List<Long> {
        val list = mutableListOf<Long>()
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT $COL_PS_SONG_ID FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ? ORDER BY $COL_PS_ADDED_AT DESC",
                arrayOf(playlistId.toString())
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(c.getLong(0))
                }
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=getPlaylistSongIds id=$playlistId", e)
        }
        return list
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean {
        val db = writableDatabase
        return try {
            val values = ContentValues().apply {
                put(COL_PS_PLAYLIST_ID, playlistId)
                put(COL_PS_SONG_ID, songId)
                put(COL_PS_ADDED_AT, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=addSongToPlaylist pid=$playlistId sid=$songId", e)
            false
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>): Boolean {
        if (songIds.isEmpty()) return false
        val db = writableDatabase
        return try {
            db.beginTransaction()
            try {
                val baseTime = System.currentTimeMillis()
                // getPlaylistSongIds orders by added_at DESC, so decrementing timestamps preserves queue top-to-bottom order
                val total = songIds.size
                for (i in songIds.indices) {
                    val values = ContentValues().apply {
                        put(COL_PS_PLAYLIST_ID, playlistId)
                        put(COL_PS_SONG_ID, songIds[i])
                        put(COL_PS_ADDED_AT, baseTime + (total - i))
                    }
                    db.insertWithOnConflict(TABLE_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
                true
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=addSongsToPlaylist pid=$playlistId count=${songIds.size}", e)
            false
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long): Boolean {
        val db = writableDatabase
        return try {
            db.delete(
                TABLE_PLAYLIST_SONGS,
                "$COL_PS_PLAYLIST_ID = ? AND $COL_PS_SONG_ID = ?",
                arrayOf(playlistId.toString(), songId.toString())
            ) > 0
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=removeSongFromPlaylist pid=$playlistId sid=$songId", e)
            false
        }
    }

    fun getAllFavoriteSongIds(): Set<Long> {
        val set = mutableSetOf<Long>()
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT $COL_PS_SONG_ID FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ?",
                arrayOf(FAVORITES_PLAYLIST_ID.toString())
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    set.add(c.getLong(0))
                }
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=getAllFavoriteSongIds", e)
        }
        return set
    }

    fun isSongFavorite(songId: Long): Boolean {
        val db = readableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ? AND $COL_PS_SONG_ID = ? LIMIT 1",
                arrayOf(FAVORITES_PLAYLIST_ID.toString(), songId.toString())
            )
            cursor.use { c ->
                return c.moveToFirst()
            }
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=isSongFavorite sid=$songId", e)
        }
        return false
    }

    fun toggleFavorite(songId: Long): Boolean {
        return if (isSongFavorite(songId)) {
            removeSongFromPlaylist(FAVORITES_PLAYLIST_ID, songId)
            false
        } else {
            addSongToPlaylist(FAVORITES_PLAYLIST_ID, songId)
            true
        }
    }

    fun deleteSongById(songId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val idStr = songId.toString()
            var affectedAlbumId: Long? = null
            db.rawQuery(
                "SELECT $COL_ALBUM_ID FROM $TABLE_SONGS WHERE $COL_ID = ? LIMIT 1",
                arrayOf(idStr)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    affectedAlbumId = cursor.getLong(0)
                }
            }
            val deletedRows = db.delete(TABLE_SONGS, "$COL_ID = ?", arrayOf(idStr))
            db.delete(TABLE_PLAYLIST_SONGS, "$COL_PS_SONG_ID = ?", arrayOf(idStr))
            db.delete(TABLE_PLAYBACK_HISTORY, "$COL_HISTORY_SONG_ID = ?", arrayOf(idStr))
            if (deletedRows > 0 && affectedAlbumId != null) {
                val albumIdArgs = arrayOf<Any>(affectedAlbumId!!)
                db.execSQL(
                    "UPDATE $TABLE_ALBUMS SET $COL_SONG_COUNT = $COL_SONG_COUNT - 1 WHERE $COL_ID = ?",
                    albumIdArgs
                )
                db.execSQL(
                    "DELETE FROM $TABLE_ALBUMS WHERE $COL_ID = ? AND $COL_SONG_COUNT <= 0",
                    albumIdArgs
                )
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            PDLog.e(PDLog.SCANNER, "DB_ERR", "op=deleteSongById sid=$songId", e)
        } finally {
            db.endTransaction()
        }
    }

    // ═══ Backup & Restore ═══
    fun exportUserDataJson(): JSONObject {
        val root = JSONObject()
        val db = readableDatabase

        // Build songId -> (path, title, artist) lookup for cross-scan track matching
        val songLookup = HashMap<Long, Triple<String, String, String>>()
        runCatching {
            db.rawQuery(
                "SELECT $COL_ID, $COL_PATH, $COL_TITLE, $COL_ARTIST FROM $TABLE_SONGS",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    songLookup[c.getLong(0)] = Triple(
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        c.getString(3) ?: ""
                    )
                }
            }
        }

        fun buildSongEntry(songId: Long, timestamp: Long, timeKey: String): JSONObject {
            val meta = songLookup[songId]
            return JSONObject().apply {
                put("songId", songId)
                put("path", meta?.first ?: "")
                put("title", meta?.second ?: "")
                put("artist", meta?.third ?: "")
                put(timeKey, timestamp)
            }
        }

        // 1. Favorites
        val favArray = JSONArray()
        runCatching {
            db.rawQuery(
                "SELECT $COL_PS_SONG_ID, $COL_PS_ADDED_AT FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ? ORDER BY $COL_PS_ADDED_AT ASC",
                arrayOf(FAVORITES_PLAYLIST_ID.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    favArray.put(buildSongEntry(c.getLong(0), c.getLong(1), "addedAt"))
                }
            }
        }
        root.put("favorites", favArray)

        // 2. Custom playlists
        val playlistsArray = JSONArray()
        runCatching {
            db.rawQuery(
                "SELECT $COL_PLAYLIST_ID, $COL_PLAYLIST_NAME, $COL_PLAYLIST_CREATED_AT FROM $TABLE_PLAYLISTS WHERE $COL_PLAYLIST_IS_SYSTEM = 0 ORDER BY $COL_PLAYLIST_CREATED_AT ASC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val plId = c.getLong(0)
                    val plName = c.getString(1) ?: ""
                    val createdAt = c.getLong(2)
                    val songsArr = JSONArray()
                    db.rawQuery(
                        "SELECT $COL_PS_SONG_ID, $COL_PS_ADDED_AT FROM $TABLE_PLAYLIST_SONGS WHERE $COL_PS_PLAYLIST_ID = ? ORDER BY $COL_PS_ADDED_AT ASC",
                        arrayOf(plId.toString())
                    ).use { sc ->
                        while (sc.moveToNext()) {
                            songsArr.put(buildSongEntry(sc.getLong(0), sc.getLong(1), "addedAt"))
                        }
                    }
                    playlistsArray.put(
                        JSONObject().apply {
                            put("name", plName)
                            put("createdAt", createdAt)
                            put("songs", songsArr)
                        }
                    )
                }
            }
        }
        root.put("playlists", playlistsArray)

        // 3. Playback history (up to 500 most recent entries)
        val historyArray = JSONArray()
        runCatching {
            db.rawQuery(
                "SELECT $COL_HISTORY_SONG_ID, $COL_HISTORY_PLAYED_AT FROM $TABLE_PLAYBACK_HISTORY ORDER BY $COL_HISTORY_PLAYED_AT ASC LIMIT 500",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    historyArray.put(buildSongEntry(c.getLong(0), c.getLong(1), "playedAt"))
                }
            }
        }
        root.put("history", historyArray)

        return root
    }

    fun importUserDataJson(data: JSONObject): Pair<Int, Int> {
        val db = writableDatabase
        val idSet = HashSet<Long>()
        val pathMap = HashMap<String, Long>()
        val fileNameTitleMap = HashMap<String, Long>()
        val titleArtistMap = HashMap<String, Long>()

        runCatching {
            db.rawQuery(
                "SELECT $COL_ID, $COL_PATH, $COL_TITLE, $COL_ARTIST FROM $TABLE_SONGS",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(1) ?: ""
                    val title = c.getString(2) ?: ""
                    val artist = c.getString(3) ?: ""
                    idSet.add(id)
                    if (path.isNotBlank()) {
                        pathMap[path.lowercase()] = id
                        val fileName = File(path).name.lowercase()
                        if (fileName.isNotBlank()) {
                            fileNameTitleMap["$fileName|${title.lowercase()}"] = id
                        }
                    }
                    if (title.isNotBlank()) {
                        titleArtistMap["${title.trim().lowercase()}|${artist.trim().lowercase()}"] = id
                    }
                }
            }
        }

        fun resolveSongId(obj: JSONObject): Long? {
            val path = obj.optString("path", "")
            if (path.isNotBlank()) {
                pathMap[path.lowercase()]?.let { return it }
            }
            val rawId = obj.optLong("songId", -1L)
            if (rawId > 0L && idSet.contains(rawId)) {
                return rawId
            }
            val title = obj.optString("title", "")
            val artist = obj.optString("artist", "")
            if (path.isNotBlank() && title.isNotBlank()) {
                val fileName = File(path).name.lowercase()
                fileNameTitleMap["$fileName|${title.lowercase()}"]?.let { return it }
            }
            if (title.isNotBlank()) {
                titleArtistMap["${title.trim().lowercase()}|${artist.trim().lowercase()}"]?.let { return it }
            }
            return null
        }

        var restoredPlaylistsCount = 0
        var restoredFavoritesCount = 0

        db.beginTransaction()
        try {
            // 1. Restore favorites
            val favArray = data.optJSONArray("favorites")
            if (favArray != null) {
                for (i in 0 until favArray.length()) {
                    val item = favArray.optJSONObject(i) ?: continue
                    val resolvedId = resolveSongId(item) ?: continue
                    val addedAt = item.optLong("addedAt", System.currentTimeMillis())
                    val values = ContentValues().apply {
                        put(COL_PS_PLAYLIST_ID, FAVORITES_PLAYLIST_ID)
                        put(COL_PS_SONG_ID, resolvedId)
                        put(COL_PS_ADDED_AT, addedAt)
                    }
                    if (db.insertWithOnConflict(TABLE_PLAYLIST_SONGS, null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0) {
                        restoredFavoritesCount++
                    }
                }
            }

            // 2. Restore custom playlists (merge by name or create if missing)
            val existingPlaylistsByName = HashMap<String, Long>()
            db.rawQuery(
                "SELECT $COL_PLAYLIST_ID, $COL_PLAYLIST_NAME FROM $TABLE_PLAYLISTS WHERE $COL_PLAYLIST_IS_SYSTEM = 0",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    existingPlaylistsByName[(c.getString(1) ?: "").trim().lowercase()] = c.getLong(0)
                }
            }

            val playlistsArray = data.optJSONArray("playlists")
            if (playlistsArray != null) {
                for (i in 0 until playlistsArray.length()) {
                    val plObj = playlistsArray.optJSONObject(i) ?: continue
                    val name = plObj.optString("name", "").trim()
                    if (name.isBlank()) continue
                    val createdAt = plObj.optLong("createdAt", System.currentTimeMillis())
                    val existingId = existingPlaylistsByName[name.lowercase()]
                    val targetPlId = if (existingId != null && existingId > 0L) {
                        existingId
                    } else {
                        val plValues = ContentValues().apply {
                            put(COL_PLAYLIST_NAME, name)
                            put(COL_PLAYLIST_IS_SYSTEM, 0)
                            put(COL_PLAYLIST_CREATED_AT, createdAt)
                        }
                        val insertedId = db.insert(TABLE_PLAYLISTS, null, plValues)
                        if (insertedId > 0L) {
                            existingPlaylistsByName[name.lowercase()] = insertedId
                        }
                        insertedId
                    }
                    if (targetPlId <= 0L) continue
                    restoredPlaylistsCount++

                    val songsArr = plObj.optJSONArray("songs")
                    if (songsArr != null) {
                        for (j in 0 until songsArr.length()) {
                            val sObj = songsArr.optJSONObject(j) ?: continue
                            val resolvedId = resolveSongId(sObj) ?: continue
                            val addedAt = sObj.optLong("addedAt", System.currentTimeMillis())
                            val psValues = ContentValues().apply {
                                put(COL_PS_PLAYLIST_ID, targetPlId)
                                put(COL_PS_SONG_ID, resolvedId)
                                put(COL_PS_ADDED_AT, addedAt)
                            }
                            db.insertWithOnConflict(TABLE_PLAYLIST_SONGS, null, psValues, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                }
            }

            // 3. Restore playback history
            val historyArray = data.optJSONArray("history")
            if (historyArray != null && historyArray.length() > 0) {
                for (i in 0 until historyArray.length()) {
                    val hObj = historyArray.optJSONObject(i) ?: continue
                    val resolvedId = resolveSongId(hObj) ?: continue
                    val playedAt = hObj.optLong("playedAt", System.currentTimeMillis())
                    val hValues = ContentValues().apply {
                        put(COL_HISTORY_SONG_ID, resolvedId)
                        put(COL_HISTORY_PLAYED_AT, playedAt)
                    }
                    db.insert(TABLE_PLAYBACK_HISTORY, null, hValues)
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return restoredPlaylistsCount to restoredFavoritesCount
    }
}
