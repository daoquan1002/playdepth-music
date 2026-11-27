/**
 * LyricsHelper — Offline synced/static lyrics loader supporting external sidecars and embedded tags.
 *
 * @remarks
 * Resolves lyrics in priority order: external `.lrc` sidecar -> embedded container tags (FLAC/Ogg
 * Vorbis comments, ID3v2 `SYLT`/`USLT`/`TXXX` across MP3/AIFF/WAV, MP4/M4A `©lyr`) -> external
 * `.txt` sidecar. Uses bounded `FileChannel` seeks over `ContentResolver` descriptors so embedded
 * lyrics work on Android 11+ even without `MANAGE_EXTERNAL_STORAGE`, while providing permission
 * helpers to unlock direct `.lrc` sidecar discovery across arbitrary folders and SD cards.
 *
 * @see NowPlayingLyricsPanel
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Immutable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// Domain models & theme tokens
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val LYRICS_CACHE_CAPACITY = 64
private const val MAX_LYRICS_FILE_BYTES = 512 * 1024
private const val MAX_VORBIS_COMMENT_BYTES = 10 * 1024 * 1024
private const val MAX_OGG_SCAN_BYTES = 10 * 1024 * 1024
private const val MAX_ID3_FRAME_BYTES = 256 * 1024
private const val MAX_MP4_ATOM_BYTES = 256 * 1024
private const val MAX_CONTAINER_WALK_STEPS = 2048
private const val ID3_HEADER_BYTES = 10

private val LRC_TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val LRC_OFFSET_REGEX = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)
private val LRC_META_LINE_REGEX = Regex("""^\[(?:ar|ti|al|au|by|re|ve|length|tool|id|la):.*]$""", RegexOption.IGNORE_CASE)
private val ENHANCED_WORD_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

// ═══ Public Contracts ═══
@Immutable
data class LyricLine(
    val timeMs: Long?,
    val text: String,
    val translation: String? = null
)

enum class LyricsSource {
    NONE,
    EXTERNAL_LRC,
    EXTERNAL_TXT,
    EMBEDDED_SYNCED,
    EMBEDDED_PLAIN
}

@Immutable
data class SongLyrics(
    val songId: Long,
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val source: LyricsSource,
    val sourceDetail: String = ""
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

// ═══ Lyrics Loader & Parser ═══
object LyricsHelper {
    private val cache = LruCache<Long, SongLyrics>(LYRICS_CACHE_CAPACITY)
    private val cacheLock = Any()
    private val cacheGeneration = AtomicInteger(0)

    /**
     * Returns `true` when the app can read non-media sidecar files (`.lrc`, `.txt`) across
     * arbitrary external storage folders. Always `true` on Android 8–10 where legacy storage
     * permission grants direct filesystem access.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Builds the system settings intent to request `MANAGE_EXTERNAL_STORAGE` on Android 11+.
     */
    fun buildAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun clearCache() {
        cacheGeneration.incrementAndGet()
        synchronized(cacheLock) {
            cache.evictAll()
        }
    }

    fun getCacheGeneration(): Int = cacheGeneration.get()

    /**
     * Synchronously returns cached lyrics for [songId] if already loaded and valid for the
     * current storage-access state, or `null` when disk/tag resolution is still needed.
     */
    fun peekCachedLyrics(songId: Long): SongLyrics? {
        synchronized(cacheLock) {
            return cache.get(songId)?.takeIf { !it.isEmpty || hasAllFilesAccess() }
        }
    }

    /**
     * Loads synced or static lyrics for [song] from external sidecar files or embedded tags.
     * Safe to call on `Dispatchers.IO`.
     */
    fun loadLyrics(context: Context, song: Song): SongLyrics {
        val genBefore = cacheGeneration.get()
        synchronized(cacheLock) {
            cache.get(song.id)?.let { cached ->
                if (!cached.isEmpty || hasAllFilesAccess()) {
                    return cached
                }
            }
        }

        val resolved = resolveLyricsUncached(context, song)
        if (cacheGeneration.get() == genBefore) {
            synchronized(cacheLock) {
                cache.put(song.id, resolved)
            }
        }
        return resolved
    }

    // ═══ Source Resolution Pipeline ═══
    private fun resolveLyricsUncached(context: Context, song: Song): SongLyrics {
        // 1. External .lrc sidecar (direct filesystem or MediaStore fallback)
        findExternalSidecar(context, song.path, "lrc")?.let { (fileName, rawText) ->
            val parsed = parseLrcOrPlain(
                songId = song.id,
                rawText = rawText,
                syncedSource = LyricsSource.EXTERNAL_LRC,
                plainSource = LyricsSource.EXTERNAL_TXT,
                detail = fileName
            )
            if (!parsed.isEmpty) return parsed
        }

        // 2. Embedded lyrics inside the audio container (works without MANAGE_EXTERNAL_STORAGE)
        extractEmbeddedLyrics(context, song)?.let { embedded ->
            if (!embedded.isEmpty) return embedded
        }

        // 3. External .txt sidecar fallback
        findExternalSidecar(context, song.path, "txt")?.let { (fileName, rawText) ->
            val parsed = parseLrcOrPlain(
                songId = song.id,
                rawText = rawText,
                syncedSource = LyricsSource.EXTERNAL_LRC,
                plainSource = LyricsSource.EXTERNAL_TXT,
                detail = fileName
            )
            if (!parsed.isEmpty) return parsed
        }

        return SongLyrics(
            songId = song.id,
            lines = emptyList(),
            isSynced = false,
            source = LyricsSource.NONE
        )
    }

    // ═══ External Sidecar Discovery ═══
    private fun findExternalSidecar(
        context: Context,
        audioPath: String,
        extension: String
    ): Pair<String, String>? {
        if (audioPath.isBlank()) return null
        val audioFile = File(audioPath)
        val parentDir = audioFile.parentFile
        val baseName = audioFile.nameWithoutExtension
        if (baseName.isBlank()) return null

        // Direct filesystem candidates: same folder + Lyrics/ subfolder
        if (parentDir != null) {
            val directCandidates = buildList {
                add(File(parentDir, "$baseName.$extension"))
                add(File(parentDir, "$baseName.${extension.uppercase(Locale.ROOT)}"))
                add(File(File(parentDir, "Lyrics"), "$baseName.$extension"))
                add(File(File(parentDir, "lyrics"), "$baseName.$extension"))
            }
            for (candidate in directCandidates) {
                val text = readSmallTextFile(candidate)
                if (!text.isNullOrBlank()) {
                    return candidate.name to text
                }
            }

            // Case-insensitive directory scan for mixed-case filenames
            val dirEntries = runCatching { parentDir.listFiles() }.getOrNull()
            if (dirEntries != null) {
                val matched = dirEntries.firstOrNull { f ->
                    f.isFile &&
                        f.extension.equals(extension, ignoreCase = true) &&
                        f.nameWithoutExtension.equals(baseName, ignoreCase = true)
                }
                if (matched != null) {
                    val text = readSmallTextFile(matched)
                    if (!text.isNullOrBlank()) {
                        return matched.name to text
                    }
                }
            }
        }

        // MediaStore.Files fallback when scoped storage hides sibling files from File API
        return querySidecarViaMediaStore(context, audioPath, baseName, extension)
    }

    private fun readSmallTextFile(file: File): String? {
        return runCatching {
            if (!file.exists() || !file.isFile || !file.canRead()) return null
            val len = file.length()
            if (len <= 0L || len > MAX_LYRICS_FILE_BYTES) return null
            decodeTextBytes(file.readBytes())
        }.getOrNull()
    }

    private fun querySidecarViaMediaStore(
        context: Context,
        audioPath: String,
        baseName: String,
        extension: String
    ): Pair<String, String>? {
        return runCatching {
            val targetName = "$baseName.$extension"
            val parentPath = File(audioPath).parent?.trimEnd('/') ?: return null
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA
            )
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? COLLATE NOCASE"
            val selectionArgs = arrayOf(targetName)
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: targetName else targetName
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val sameFolder = dataPath != null && File(dataPath).parent?.trimEnd('/') == parentPath
                    if (sameFolder) {
                        val contentUri = ContentUris.withAppendedId(uri, id)
                        val text = readStreamText(context, contentUri)
                        if (!text.isNullOrBlank()) return name to text
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun readStreamText(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (total < MAX_LYRICS_FILE_BYTES) {
                    val n = stream.read(buf, 0, minOf(buf.size, MAX_LYRICS_FILE_BYTES - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                decodeTextBytes(out.toByteArray())
            }
        }.getOrNull()
    }

    // ═══ Embedded Container Tag Extraction ═══
    private fun extractEmbeddedLyrics(context: Context, song: Song): SongLyrics? {
        // Prefer ContentResolver PFD so embedded tags always work under Scoped Storage
        runCatching {
            context.contentResolver.openFileDescriptor(song.contentUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val parsed = extractFromChannel(fis.channel, song.id)
                    if (parsed != null && !parsed.isEmpty) return parsed
                }
            }
        }

        // Fallback to direct File path
        if (song.path.isNotBlank()) {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                return runCatching {
                    FileInputStream(file).use { fis ->
                        extractFromChannel(fis.channel, song.id)
                    }
                }.getOrNull()
            }
        }
        return null
    }

    private fun extractFromChannel(channel: FileChannel, songId: Long): SongLyrics? {
        val fileSize = channel.size()
        if (fileSize < 12L) return null
        val magic = readChannelBytes(channel, 0L, 12) ?: return null

        return when {
            isAscii(magic, 0, "fLaC") -> parseFlacLyrics(channel, 4L, fileSize, songId)
            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> {
                val id3Lyrics = parseId3v2Lyrics(channel, 0L, fileSize, songId)
                if (id3Lyrics != null && !id3Lyrics.isEmpty) {
                    id3Lyrics
                } else {
                    val id3TagSize = readSynchsafeInt(magic, 6)
                    val afterId3 = ID3_HEADER_BYTES + id3TagSize.toLong() +
                        if ((magic[3].toInt() and 0xFF) == 4 && (magic[5].toInt() and 0x10) != 0) 10L else 0L
                    if (afterId3 + 4L <= fileSize) {
                        val trailing = readChannelBytes(channel, afterId3, 4)
                        if (trailing != null && isAscii(trailing, 0, "fLaC")) {
                            parseFlacLyrics(channel, afterId3 + 4L, fileSize, songId)
                        } else null
                    } else null
                }
            }
            isAscii(magic, 0, "OggS") -> parseOggLyrics(channel, fileSize, songId)
            isAscii(magic, 4, "ftyp") -> parseMp4Lyrics(channel, fileSize, songId)
            isAscii(magic, 0, "FORM") && (isAscii(magic, 8, "AIFF") || isAscii(magic, 8, "AIFC")) -> {
                parseIffId3Lyrics(channel, fileSize, songId, bigEndianSizes = true)
            }
            isAscii(magic, 0, "RIFF") && isAscii(magic, 8, "WAVE") -> {
                parseIffId3Lyrics(channel, fileSize, songId, bigEndianSizes = false)
            }
            else -> null
        }
    }

    private fun parseFlacLyrics(channel: FileChannel, startOffset: Long, fileSize: Long, songId: Long): SongLyrics? {
        var offset = startOffset
        var steps = 0
        while (offset + 4L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val header = readChannelBytes(channel, offset, 4) ?: break
            val isLast = (header[0].toInt() and 0x80) != 0
            val blockType = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            val contentStart = offset + 4L

            if (blockType == 4 && length in 8..MAX_VORBIS_COMMENT_BYTES && contentStart + length <= fileSize) {
                val block = readChannelBytes(channel, contentStart, length) ?: return null
                return parseVorbisCommentLyrics(block, 0, songId)
            }

            offset = contentStart + length.toLong()
            if (isLast) break
        }
        return null
    }

    private fun parseOggLyrics(channel: FileChannel, fileSize: Long, songId: Long): SongLyrics? {
        val packet = ByteArrayOutputStream()
        var offset = 0L
        var foundTags = false
        var steps = 0
        val opusMagic = "OpusTags".toByteArray(Charsets.US_ASCII)
        val vorbisMagic = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())

        while (offset + 27L <= fileSize && offset < MAX_OGG_SCAN_BYTES && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val pageHeader = readChannelBytes(channel, offset, 27) ?: break
            if (!isAscii(pageHeader, 0, "OggS")) break
            val segCount = pageHeader[26].toInt() and 0xFF
            if (offset + 27L + segCount > fileSize) break
            val segTable = readChannelBytes(channel, offset + 27L, segCount) ?: break

            var dataPos = offset + 27L + segCount
            var pageDataSize = 0
            for (s in segTable) pageDataSize += (s.toInt() and 0xFF)
            if (dataPos + pageDataSize > fileSize) break

            for (i in 0 until segCount) {
                val segLen = segTable[i].toInt() and 0xFF
                if (segLen > 0) {
                    val segBytes = readChannelBytes(channel, dataPos, segLen) ?: return null
                    if (!foundTags) {
                        if (startsWithBytes(segBytes, opusMagic) || startsWithBytes(segBytes, vorbisMagic)) {
                            foundTags = true
                            packet.write(segBytes)
                        }
                    } else if (packet.size() < MAX_VORBIS_COMMENT_BYTES) {
                        packet.write(segBytes)
                    }
                    dataPos += segLen
                }
                if (foundTags && segLen < 255) {
                    val bytes = packet.toByteArray()
                    val bodyStart = if (startsWithBytes(bytes, opusMagic)) opusMagic.size else vorbisMagic.size
                    return parseVorbisCommentLyrics(bytes, bodyStart, songId)
                }
            }
            offset = dataPos
        }
        return null
    }

    private fun parseVorbisCommentLyrics(block: ByteArray, startOffset: Int, songId: Long): SongLyrics? {
        if (startOffset + 8 > block.size) return null
        val buf = ByteBuffer.wrap(block, startOffset, block.size - startOffset).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = buf.int
        if (vendorLen < 0 || vendorLen > buf.remaining()) return null
        buf.position(buf.position() + vendorLen)
        if (buf.remaining() < 4) return null
        val commentCount = buf.int
        if (commentCount <= 0 || commentCount > 2048) return null

        var bestSynced: SongLyrics? = null
        var bestPlain: SongLyrics? = null

        for (i in 0 until commentCount) {
            if (buf.remaining() < 4) break
            val len = buf.int
            if (len < 0 || len > buf.remaining()) break
            if (len == 0 || len > MAX_LYRICS_FILE_BYTES) {
                buf.position(buf.position() + len)
                continue
            }
            val bytes = ByteArray(len)
            buf.get(bytes)
            val entry = String(bytes, Charsets.UTF_8)
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim().uppercase(Locale.ROOT)
            val value = entry.substring(eqIdx + 1)
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS" || key == "LRC") {
                val parsed = parseLrcOrPlain(
                    songId = songId,
                    rawText = value,
                    syncedSource = LyricsSource.EMBEDDED_SYNCED,
                    plainSource = LyricsSource.EMBEDDED_PLAIN,
                    detail = key
                )
                if (parsed.isSynced && bestSynced == null) {
                    bestSynced = parsed
                } else if (!parsed.isEmpty && bestPlain == null) {
                    bestPlain = parsed
                }
            }
        }
        return bestSynced ?: bestPlain
    }

    private fun parseIffId3Lyrics(
        channel: FileChannel,
        fileSize: Long,
        songId: Long,
        bigEndianSizes: Boolean
    ): SongLyrics? {
        var offset = 12L
        var steps = 0
        while (offset + 8L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 8) ?: break
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = if (bigEndianSizes) {
                readUInt32BE(hdr, 4)
            } else {
                readUInt32LE(hdr, 4)
            }
            val dataOffset = offset + 8L
            if (id.equals("ID3 ", ignoreCase = true) && size >= ID3_HEADER_BYTES && dataOffset + size <= fileSize) {
                val parsed = parseId3v2Lyrics(channel, dataOffset, dataOffset + size, songId)
                if (parsed != null && !parsed.isEmpty) return parsed
            }
            val paddedSize = size + (size and 1L)
            if (paddedSize < 0L || dataOffset + paddedSize <= offset) break
            offset = dataOffset + paddedSize
        }
        return null
    }

    private fun parseId3v2Lyrics(
        channel: FileChannel,
        tagStart: Long,
        fileLimit: Long,
        songId: Long
    ): SongLyrics? {
        val header = readChannelBytes(channel, tagStart, ID3_HEADER_BYTES) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null
        }
        val versionMajor = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        if (versionMajor !in 3..4) return null

        val tagSize = readSynchsafeInt(header, 6)
        if (tagSize <= 0) return null
        val tagEnd = minOf(tagStart + ID3_HEADER_BYTES + tagSize.toLong(), fileLimit)

        var pos = tagStart + ID3_HEADER_BYTES
        if ((flags and 0x40) != 0 && pos + 4L <= tagEnd) {
            val ext = readChannelBytes(channel, pos, 4) ?: return null
            val extSize = if (versionMajor == 4) readSynchsafeInt(ext, 0) else readInt32BE(ext, 0)
            if (extSize > 0) {
                pos += if (versionMajor == 4) extSize.toLong() else extSize.toLong() + 4L
            }
        }

        val tagUnsync = (flags and 0x80) != 0
        var bestSynced: SongLyrics? = null
        var bestPlain: SongLyrics? = null
        var steps = 0

        while (pos + 10L <= tagEnd && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val fh = readChannelBytes(channel, pos, 10) ?: break
            if (fh[0].toInt() == 0 && fh[1].toInt() == 0 && fh[2].toInt() == 0 && fh[3].toInt() == 0) break
            val frameId = String(fh, 0, 4, Charsets.US_ASCII)
            val frameSize = if (versionMajor == 4) readSynchsafeInt(fh, 4) else readInt32BE(fh, 4)
            if (frameSize <= 0 || pos + 10L + frameSize > tagEnd) break
            val frameFlagsLo = fh[9].toInt() and 0xFF
            val frameUnsync = tagUnsync || (versionMajor == 4 && (frameFlagsLo and 0x02) != 0)
            val hasDataLengthIndicator = versionMajor == 4 && (frameFlagsLo and 0x01) != 0

            val bodyOffset = pos + 10L
            if (frameSize <= MAX_ID3_FRAME_BYTES && (frameId == "SYLT" || frameId == "USLT" || frameId == "TXXX")) {
                var raw = readChannelBytes(channel, bodyOffset, frameSize) ?: break
                if (frameUnsync) raw = removeId3Unsync(raw)
                val startIdx = if (hasDataLengthIndicator && raw.size > 4) 4 else 0

                when (frameId) {
                    "SYLT" -> {
                        val sylt = parseSyltFrame(raw, startIdx, songId)
                        if (sylt != null && !sylt.isEmpty) {
                            bestSynced = sylt
                            break
                        }
                    }
                    "USLT" -> {
                        val text = parseUsltFrame(raw, startIdx)
                        if (!text.isNullOrBlank()) {
                            val parsed = parseLrcOrPlain(
                                songId = songId,
                                rawText = text,
                                syncedSource = LyricsSource.EMBEDDED_SYNCED,
                                plainSource = LyricsSource.EMBEDDED_PLAIN,
                                detail = "USLT"
                            )
                            if (parsed.isSynced && bestSynced == null) bestSynced = parsed
                            else if (!parsed.isEmpty && bestPlain == null) bestPlain = parsed
                        }
                    }
                    "TXXX" -> {
                        val (desc, value) = parseTxxxFrame(raw, startIdx) ?: (null to null)
                        val key = desc?.trim()?.uppercase(Locale.ROOT).orEmpty()
                        if (!value.isNullOrBlank() && (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS")) {
                            val parsed = parseLrcOrPlain(
                                songId = songId,
                                rawText = value,
                                syncedSource = LyricsSource.EMBEDDED_SYNCED,
                                plainSource = LyricsSource.EMBEDDED_PLAIN,
                                detail = "TXXX:$key"
                            )
                            if (parsed.isSynced && bestSynced == null) bestSynced = parsed
                            else if (!parsed.isEmpty && bestPlain == null) bestPlain = parsed
                        }
                    }
                }
            }
            pos = bodyOffset + frameSize.toLong()
        }

        return bestSynced ?: bestPlain
    }

    private fun parseUsltFrame(data: ByteArray, startIdx: Int): String? {
        if (startIdx + 5 >= data.size) return null
        val encoding = data[startIdx].toInt() and 0xFF
        val descStart = startIdx + 4 // skip encoding(1) + language(3)
        val termPos = findId3StringTerminator(data, descStart, encoding)
        val textStart = termPos + if (encoding == 1 || encoding == 2) 2 else 1
        if (textStart >= data.size) return null
        return decodeId3String(data, textStart, data.size - textStart, encoding)
    }

    private fun parseTxxxFrame(data: ByteArray, startIdx: Int): Pair<String, String>? {
        if (startIdx + 2 >= data.size) return null
        val encoding = data[startIdx].toInt() and 0xFF
        val descStart = startIdx + 1
        val termPos = findId3StringTerminator(data, descStart, encoding)
        val desc = decodeId3String(data, descStart, termPos - descStart, encoding)
        val valStart = termPos + if (encoding == 1 || encoding == 2) 2 else 1
        if (valStart >= data.size) return null
        val value = decodeId3String(data, valStart, data.size - valStart, encoding)
        return desc to value
    }

    private fun parseSyltFrame(data: ByteArray, startIdx: Int, songId: Long): SongLyrics? {
        if (startIdx + 7 >= data.size) return null
        val encoding = data[startIdx].toInt() and 0xFF
        val timestampFormat = data[startIdx + 4].toInt() and 0xFF
        if (timestampFormat != 2) return null // 2 = milliseconds
        val descStart = startIdx + 6 // encoding(1) + lang(3) + tsFormat(1) + contentType(1)
        val termPos = findId3StringTerminator(data, descStart, encoding)
        var cursor = termPos + if (encoding == 1 || encoding == 2) 2 else 1

        val rawLines = mutableListOf<Pair<Long, String>>()
        val termWidth = if (encoding == 1 || encoding == 2) 2 else 1
        while (cursor < data.size) {
            val end = findId3StringTerminator(data, cursor, encoding)
            val tsPos = end + termWidth
            if (tsPos + 4 > data.size) break
            val lineText = decodeId3String(data, cursor, end - cursor, encoding).trim()
            val timeMs = readUInt32BE(data, tsPos)
            if (lineText.isNotEmpty()) {
                rawLines.add(timeMs to lineText)
            }
            cursor = tsPos + 4
        }
        if (rawLines.isEmpty()) return null
        val merged = mergeSyncedLines(rawLines)
        return SongLyrics(
            songId = songId,
            lines = merged,
            isSynced = true,
            source = LyricsSource.EMBEDDED_SYNCED,
            sourceDetail = "SYLT"
        )
    }

    private fun parseMp4Lyrics(channel: FileChannel, fileSize: Long, songId: Long): SongLyrics? {
        val text = findMp4LyricsAtom(channel, 0L, fileSize, depth = 0) ?: return null
        return parseLrcOrPlain(
            songId = songId,
            rawText = text,
            syncedSource = LyricsSource.EMBEDDED_SYNCED,
            plainSource = LyricsSource.EMBEDDED_PLAIN,
            detail = "©lyr"
        )
    }

    private fun findMp4LyricsAtom(channel: FileChannel, start: Long, end: Long, depth: Int): String? {
        if (depth > 6) return null
        var offset = start
        var steps = 0
        while (offset + 8L <= end && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 8) ?: break
            var atomSize = readUInt32BE(hdr, 0)
            var headerLen = 8L
            if (atomSize == 1L && offset + 16L <= end) {
                val ext = readChannelBytes(channel, offset + 8L, 8) ?: break
                atomSize = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                headerLen = 16L
            } else if (atomSize == 0L) {
                atomSize = end - offset
            }
            if (atomSize < headerLen || offset + atomSize > end) break
            val atomEnd = offset + atomSize
            val contentStart = offset + headerLen

            val b0 = hdr[4].toInt() and 0xFF
            val b1 = hdr[5].toInt() and 0xFF
            val b2 = hdr[6].toInt() and 0xFF
            val b3 = hdr[7].toInt() and 0xFF

            val isLyr = (b0 == 0xA9 && b1 == 'l'.code && b2 == 'y'.code && b3 == 'r'.code)
            if (isLyr) {
                return readMp4DataAtomText(channel, contentStart, atomEnd)
            }

            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            when (type) {
                "moov", "udta", "ilst" -> {
                    findMp4LyricsAtom(channel, contentStart, atomEnd, depth + 1)?.let { return it }
                }
                "meta" -> {
                    // MP4 `meta` is a FullBox with 4-byte version/flags before child boxes
                    val childStart = contentStart + 4L
                    if (childStart < atomEnd) {
                        findMp4LyricsAtom(channel, childStart, atomEnd, depth + 1)?.let { return it }
                    }
                }
            }
            offset = atomEnd
        }
        return null
    }

    private fun readMp4DataAtomText(channel: FileChannel, start: Long, end: Long): String? {
        var offset = start
        var steps = 0
        while (offset + 8L <= end && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 8) ?: break
            val size = readUInt32BE(hdr, 0)
            if (size < 8L || offset + size > end) break
            if (isAscii(hdr, 4, "data") && size >= 16L) {
                val metaHdr = readChannelBytes(channel, offset + 8L, 8) ?: return null
                val typeIndicator = readInt32BE(metaHdr, 0) and 0x00FFFFFF
                val textLen = (size - 16L).toInt()
                if (textLen in 1..MAX_MP4_ATOM_BYTES) {
                    val bytes = readChannelBytes(channel, offset + 16L, textLen) ?: return null
                    return if (typeIndicator == 2) {
                        String(bytes, Charsets.UTF_16BE).trimEnd('\u0000')
                    } else {
                        decodeTextBytes(bytes)
                    }
                }
            }
            offset += size
        }
        return null
    }

    // ═══ LRC & Plain Text Parsing ═══
    internal fun parseLrcOrPlain(
        songId: Long,
        rawText: String,
        syncedSource: LyricsSource,
        plainSource: LyricsSource,
        detail: String
    ): SongLyrics {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) {
            return SongLyrics(songId, emptyList(), isSynced = false, source = LyricsSource.NONE)
        }

        val syncedLines = parseLrcSyncedLines(cleaned)
        if (syncedLines.isNotEmpty()) {
            return SongLyrics(
                songId = songId,
                lines = syncedLines,
                isSynced = true,
                source = syncedSource,
                sourceDetail = detail
            )
        }

        val plainLines = parsePlainLines(cleaned)
        return SongLyrics(
            songId = songId,
            lines = plainLines,
            isSynced = false,
            source = if (plainLines.isNotEmpty()) plainSource else LyricsSource.NONE,
            sourceDetail = detail
        )
    }

    private fun parseLrcSyncedLines(text: String): List<LyricLine> {
        var offsetMs = 0L
        val entries = mutableListOf<Pair<Long, String>>()
        val lines = text.split('\n')

        for (rawLine in lines) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty()) continue

            val offsetMatch = LRC_OFFSET_REGEX.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            if (LRC_META_LINE_REGEX.matches(line)) continue

            val matches = LRC_TIME_TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) continue

            val strippedText = line
                .replace(LRC_TIME_TAG_REGEX, "")
                .replace(ENHANCED_WORD_TAG_REGEX, "")
                .trim()

            for (m in matches) {
                val mins = m.groupValues[1].toLongOrNull() ?: 0L
                val secs = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val ts = (mins * 60_000L + secs * 1_000L + millis - offsetMs).coerceAtLeast(0L)
                if (strippedText.isNotEmpty()) {
                    entries.add(ts to strippedText)
                }
            }
        }

        if (entries.isEmpty()) return emptyList()
        return mergeSyncedLines(entries)
    }

    private fun mergeSyncedLines(entries: List<Pair<Long, String>>): List<LyricLine> {
        val sorted = entries.sortedBy { it.first }
        val result = mutableListOf<LyricLine>()
        for ((ts, txt) in sorted) {
            val prev = result.lastOrNull()
            if (prev != null && prev.timeMs == ts && prev.translation == null && prev.text != txt) {
                result[result.lastIndex] = prev.copy(translation = txt)
            } else {
                result.add(LyricLine(timeMs = ts, text = txt))
            }
        }
        return result
    }

    private fun parsePlainLines(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        var lastBlank = true
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty()) {
                if (!lastBlank) {
                    out.add(LyricLine(timeMs = null, text = ""))
                    lastBlank = true
                }
            } else {
                out.add(LyricLine(timeMs = null, text = line))
                lastBlank = false
            }
        }
        while (out.isNotEmpty() && out.last().text.isEmpty()) {
            out.removeAt(out.lastIndex)
        }
        return out
    }

    // ═══ Low-Level Binary & Text Helpers ═══
    private fun decodeTextBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    private fun findId3StringTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
            return data.size
        }
        var i = start
        while (i < data.size) {
            if (data[i].toInt() == 0) return i
            i++
        }
        return data.size
    }

    private fun decodeId3String(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (offset < 0 || length <= 0 || offset + length > data.size) return ""
        return when (encoding) {
            0 -> String(data, offset, length, Charset.forName("ISO-8859-1"))
            1 -> {
                if (length >= 2 && data[offset] == 0xFF.toByte() && data[offset + 1] == 0xFE.toByte()) {
                    String(data, offset + 2, length - 2, Charsets.UTF_16LE)
                } else if (length >= 2 && data[offset] == 0xFE.toByte() && data[offset + 1] == 0xFF.toByte()) {
                    String(data, offset + 2, length - 2, Charsets.UTF_16BE)
                } else {
                    String(data, offset, length, Charsets.UTF_16)
                }
            }
            2 -> String(data, offset, length, Charsets.UTF_16BE)
            3 -> String(data, offset, length, Charsets.UTF_8)
            else -> String(data, offset, length, Charsets.UTF_8)
        }.trimEnd('\u0000')
    }

    private fun removeId3Unsync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt() and 0xFF)
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1].toInt() == 0) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun readChannelBytes(channel: FileChannel, position: Long, length: Int): ByteArray? {
        if (position < 0L || length <= 0) return null
        val buf = ByteBuffer.allocate(length)
        var total = 0
        while (total < length) {
            val read = channel.read(buf, position + total)
            if (read <= 0) break
            total += read
        }
        return if (total == length) buf.array() else null
    }

    private fun isAscii(bytes: ByteArray, offset: Int, expected: String): Boolean {
        if (offset + expected.length > bytes.size) return false
        for (i in expected.indices) {
            if (bytes[offset + i].toInt() != expected[i].code) return false
        }
        return true
    }

    private fun startsWithBytes(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }

    private fun readSynchsafeInt(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun readInt32BE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt32BE(b: ByteArray, offset: Int): Long {
        return readInt32BE(b, offset).toLong() and 0xFFFFFFFFL
    }

    private fun readUInt32LE(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
