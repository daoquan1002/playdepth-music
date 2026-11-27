/**
 * AiffMetadataHelper — Direct AIFF/AIF/AIFC (`FORM` EA IFF-85) metadata and audio-spec parser.
 *
 * @remarks
 * Android's built-in `MediaStore` scanner omits duration, audio parameters, and trailing `ID3 `
 * tags for `.aiff`/`.aif`/`.aifc` tracks. Seeking directly across top-level EA IFF chunks reads
 * the `COMM` header (sample rate via 80-bit IEEE-754 extended float, bit-depth, channels, AIFC
 * compression, duration) and extracts `TIT2`/`TPE1`/`TALB`/`TRCK`/`TDRC`/`TYER`/`TCON` frames
 * from `ID3 `/`id3 ` chunks in O(chunks) without buffering compressed audio or `APIC` artwork.
 *
 * @see com.quandot.musicplayer.audio.AiffExtractor
 * @see AudioSpecsHelper
 * @see MusicRepository
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.roundToInt

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val IFF_CONTAINER_HEADER_BYTES = 12
private const val IFF_CHUNK_HEADER_BYTES = 8
private const val COMM_MIN_BYTES = 18
private const val COMM_AIFC_MIN_BYTES = 22
private const val MAX_CHUNKS_WALKED = 512
private const val MAX_ID3_FRAMES_WALKED = 512
private const val MAX_TEXT_FRAME_BYTES = 4096
private const val ID3_HEADER_BYTES = 10
private const val MIN_VALID_YEAR = 1900
private const val MAX_VALID_YEAR = 2100
private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2}|2100)\b""")
private val ID3_GENRE_PREFIX_REGEX = Regex("""^\(\d+\)\s*(.+)$""")

// ═══ Models ═══
data class AiffTrackMetadata(
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val isFloat: Boolean = false,
    val isSupportedPcm: Boolean = true,
    val durationMs: Long = 0L,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
    val genre: String? = null
)

// ═══ Parser API ═══
object AiffMetadataHelper {

    fun isAiffFile(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".aiff") || lower.endsWith(".aif") || lower.endsWith(".aifc")
    }

    fun isAiffExtensionOrMime(path: String?, mimeType: String?): Boolean {
        if (isAiffFile(path)) return true
        return mimeType?.contains("aiff", ignoreCase = true) == true
    }

    fun readMetadata(path: String): AiffTrackMetadata? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                parseFromChannel(fis.channel)
            }
        }.getOrNull()
    }

    fun readMetadata(context: Context, song: Song): AiffTrackMetadata? {
        if (song.path.isNotBlank()) {
            readMetadata(song.path)?.let { return it }
        }
        return readMetadataFromUri(context, song.contentUri)
    }

    fun readMetadataFromUri(context: Context, uri: Uri): AiffTrackMetadata? {
        if (uri == Uri.EMPTY) return null
        if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            readMetadata(uri.path!!)?.let { return it }
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    parseFromChannel(fis.channel)
                }
            }
        }.getOrNull()
    }

    /**
     * Decodes an 80-bit big-endian IEEE-754 extended float (AIFF `COMM` sample rate).
     */
    fun decodeIeee80BitFloat(buf: ByteArray, offset: Int): Int {
        if (offset + 10 > buf.size) return 0
        val exponent = ((buf[offset].toInt() and 0x7F) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        var mantissaHi = 0L
        for (i in 2..5) {
            mantissaHi = (mantissaHi shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        var mantissaLo = 0L
        for (i in 6..9) {
            mantissaLo = (mantissaLo shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        if (exponent == 0 && mantissaHi == 0L && mantissaLo == 0L) return 0
        if (exponent == 0x7FFF) return 0

        val unsignedMantissa = mantissaHi.toDouble() * 4294967296.0 + mantissaLo.toDouble()
        val value = Math.scalb(unsignedMantissa, exponent - 16383 - 63)
        return if (value.isFinite() && value > 0.0 && value <= 2_000_000.0) {
            value.roundToInt()
        } else {
            0
        }
    }

    // ═══ Container Parsing ═══
    private fun parseFromChannel(channel: FileChannel): AiffTrackMetadata? {
        val fileSize = channel.size()
        if (fileSize < IFF_CONTAINER_HEADER_BYTES.toLong()) return null

        val header = readChannelBytes(channel, 0L, IFF_CONTAINER_HEADER_BYTES) ?: return null
        val containerId = String(header, 0, 4, Charsets.ISO_8859_1)
        val formType = String(header, 8, 4, Charsets.ISO_8859_1)
        if (containerId != "FORM" || (formType != "AIFF" && formType != "AIFC")) {
            return null
        }

        var channels = 0
        var numSampleFrames = 0L
        var bitDepth = 0
        var sampleRate = 0
        var isFloat = false
        var isSupportedPcm = true
        var ssndPayloadBytes = 0L

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber = 0
        var discNumber = 0
        var year = 0
        var genre: String? = null

        var offset = IFF_CONTAINER_HEADER_BYTES.toLong()
        var steps = 0
        while (offset + IFF_CHUNK_HEADER_BYTES <= fileSize && steps < MAX_CHUNKS_WALKED) {
            steps++
            val chunkHdr = readChannelBytes(channel, offset, IFF_CHUNK_HEADER_BYTES) ?: break
            val chunkId = String(chunkHdr, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = readBeU32(chunkHdr, 4)
            val contentStart = offset + IFF_CHUNK_HEADER_BYTES
            val safeEnd = minOf(fileSize, contentStart + chunkSize)

            when (chunkId) {
                "COMM" -> {
                    val commLen = minOf(chunkSize, 32L).toInt()
                    if (commLen >= COMM_MIN_BYTES && contentStart + commLen <= fileSize) {
                        val comm = readChannelBytes(channel, contentStart, commLen)
                        if (comm != null) {
                            channels = readBeU16(comm, 0)
                            numSampleFrames = readBeU32(comm, 2)
                            bitDepth = readBeU16(comm, 6)
                            sampleRate = decodeIeee80BitFloat(comm, 8)
                            if (formType == "AIFC" && comm.size >= COMM_AIFC_MIN_BYTES) {
                                val compression = String(comm, 18, 4, Charsets.ISO_8859_1)
                                when (compression) {
                                    "NONE", "sowt" -> isFloat = false
                                    "fl32", "FL32" -> isFloat = true
                                    else -> isSupportedPcm = false
                                }
                            }
                        }
                    }
                }
                "SSND" -> {
                    if (chunkSize >= 8L && contentStart + 8L <= fileSize) {
                        val ssndHdr = readChannelBytes(channel, contentStart, 8)
                        if (ssndHdr != null) {
                            val ssndOffset = readBeU32(ssndHdr, 0)
                            ssndPayloadBytes = (chunkSize - 8L - ssndOffset).coerceAtLeast(0L)
                        }
                    }
                }
                "ID3 ", "id3 " -> {
                    if (chunkSize >= ID3_HEADER_BYTES.toLong() && contentStart < fileSize) {
                        val id3Meta = parseId3v2Chunk(channel, contentStart, safeEnd)
                        if (id3Meta != null) {
                            title = id3Meta.title
                            artist = id3Meta.artist
                            album = id3Meta.album
                            albumArtist = id3Meta.albumArtist
                            trackNumber = id3Meta.trackNumber
                            discNumber = id3Meta.discNumber
                            year = id3Meta.year
                            genre = id3Meta.genre
                        }
                    }
                }
            }

            val paddedSize = chunkSize + (chunkSize and 1L)
            val next = contentStart + paddedSize
            if (next <= offset) break
            offset = next
        }

        if (sampleRate <= 0 && title == null && artist == null && album == null) {
            return null
        }

        val durationMs = computeDurationMs(
            sampleRate = sampleRate,
            numSampleFrames = numSampleFrames,
            channels = channels,
            bitDepth = bitDepth,
            ssndPayloadBytes = ssndPayloadBytes,
            isSupportedPcm = isSupportedPcm
        )

        return AiffTrackMetadata(
            channels = channels,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            isFloat = isFloat,
            isSupportedPcm = isSupportedPcm,
            durationMs = durationMs,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = year,
            genre = genre
        )
    }

    private fun computeDurationMs(
        sampleRate: Int,
        numSampleFrames: Long,
        channels: Int,
        bitDepth: Int,
        ssndPayloadBytes: Long,
        isSupportedPcm: Boolean
    ): Long {
        if (sampleRate <= 0) return 0L
        if (numSampleFrames > 0L) {
            return (numSampleFrames * 1_000L) / sampleRate
        }
        val bytesPerSample = (bitDepth + 7) / 8
        val frameBytes = channels.toLong() * bytesPerSample.toLong()
        if (isSupportedPcm && frameBytes > 0L && ssndPayloadBytes > 0L) {
            val framesFromSsnd = ssndPayloadBytes / frameBytes
            return (framesFromSsnd * 1_000L) / sampleRate
        }
        return 0L
    }

    private data class Id3Fields(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val trackNumber: Int = 0,
        val discNumber: Int = 0,
        val year: Int = 0,
        val genre: String? = null
    )

    private fun parseId3v2Chunk(
        channel: FileChannel,
        startOffset: Long,
        limitOffset: Long
    ): Id3Fields? {
        if (startOffset + ID3_HEADER_BYTES > limitOffset) return null
        val hdr = readChannelBytes(channel, startOffset, ID3_HEADER_BYTES) ?: return null
        if (hdr[0] != 'I'.code.toByte() || hdr[1] != 'D'.code.toByte() || hdr[2] != '3'.code.toByte()) {
            return null
        }

        val major = hdr[3].toInt() and 0xFF
        val flags = hdr[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(hdr, 6)
        if (tagSize <= 0) return null

        val tagEnd = minOf(limitOffset, startOffset + ID3_HEADER_BYTES + tagSize.toLong())
        var offset = startOffset + ID3_HEADER_BYTES
        if ((flags and 0x40) != 0 && offset + 4L <= tagEnd) {
            val ext = readChannelBytes(channel, offset, 4) ?: return null
            val extSize = if (major == 4) readSynchsafeInt(ext, 0) else readBeS32(ext, 0) + 4
            if (extSize > 0 && offset + extSize <= tagEnd) {
                offset += extSize.toLong()
            }
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var trackNumber = 0
        var discNumber = 0
        var tdrcYear = 0
        var tyerYear = 0
        var genre: String? = null

        var steps = 0
        while (offset + 10L <= tagEnd && steps < MAX_ID3_FRAMES_WALKED) {
            steps++
            val frameHdr = readChannelBytes(channel, offset, 10) ?: break
            if (frameHdr[0] == 0.toByte()) break

            val frameId = String(frameHdr, 0, 4, Charsets.ISO_8859_1)
            val frameSize = if (major == 4) {
                val hasHighBit = ((frameHdr[4].toInt() or frameHdr[5].toInt() or frameHdr[6].toInt() or frameHdr[7].toInt()) and 0x80) != 0
                if (hasHighBit) readBeS32(frameHdr, 4) else readSynchsafeInt(frameHdr, 4)
            } else {
                readBeS32(frameHdr, 4)
            }
            val contentStart = offset + 10L
            if (frameSize <= 0 || contentStart + frameSize > tagEnd) break

            val isWantedTextFrame = frameId == "TIT2" || frameId == "TPE1" || frameId == "TPE2" ||
                frameId == "TALB" || frameId == "TRCK" || frameId == "TPOS" ||
                frameId == "TDRC" || frameId == "TYER" || frameId == "TCON"

            if (isWantedTextFrame && frameSize <= MAX_TEXT_FRAME_BYTES) {
                val payload = readChannelBytes(channel, contentStart, frameSize)
                val text = payload?.let { decodeId3Text(it) }?.takeIf { it.isNotBlank() }
                if (text != null) {
                    when (frameId) {
                        "TIT2" -> if (title == null) title = text
                        "TPE1" -> if (artist == null) artist = text
                        "TPE2" -> if (albumArtist == null) albumArtist = text
                        "TALB" -> if (album == null) album = text
                        "TRCK" -> if (trackNumber == 0) {
                            trackNumber = text.substringBefore('/').trim().toIntOrNull() ?: 0
                        }
                        "TPOS" -> if (discNumber == 0) {
                            discNumber = text.substringBefore('/').trim().toIntOrNull() ?: 0
                        }
                        "TDRC" -> {
                            val y = parseFourDigitYear(text)
                            if (y > 0) tdrcYear = y
                        }
                        "TYER" -> if (tyerYear == 0) {
                            tyerYear = parseFourDigitYear(text)
                        }
                        "TCON" -> if (genre == null) {
                            genre = cleanGenreText(text)
                        }
                    }
                }
            }

            // Advance past APIC / binary frames directly by offset without allocating image buffers
            offset = contentStart + frameSize.toLong()
        }

        val resolvedYear = if (tdrcYear > 0) tdrcYear else tyerYear
        return Id3Fields(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            trackNumber = trackNumber,
            discNumber = discNumber,
            year = resolvedYear,
            genre = genre
        )
    }

    // ═══ Binary & Text Helpers ═══
    private fun decodeId3Text(frameBytes: ByteArray): String {
        if (frameBytes.size <= 1) return ""
        val encoding = frameBytes[0].toInt() and 0xFF
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(frameBytes, 1, frameBytes.size - 1, charset)
            .trim('\u0000', ' ')
            .substringBefore('\u0000')
            .trim()
    }

    private fun cleanGenreText(raw: String): String {
        val trimmed = raw.trim()
        val match = ID3_GENRE_PREFIX_REGEX.matchEntire(trimmed)
        return match?.groupValues?.get(1)?.trim() ?: trimmed
    }

    private fun parseFourDigitYear(raw: String): Int {
        val match = YEAR_REGEX.find(raw.trim()) ?: return 0
        val year = match.groupValues[1].toIntOrNull() ?: return 0
        return if (year in MIN_VALID_YEAR..MAX_VALID_YEAR) year else 0
    }

    private fun readChannelBytes(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val out = ByteArray(length)
        val bb = ByteBuffer.wrap(out)
        var pos = offset
        while (bb.hasRemaining()) {
            val read = channel.read(bb, pos)
            if (read <= 0) return null
            pos += read
        }
        return out
    }

    private fun readBeU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readBeS32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readBeU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }
}
