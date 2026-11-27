/**
 * AudioTagYearHelper — Direct container release-year parser for local audio files.
 *
 * @remarks
 * Android's `MediaStore.Audio.Media.YEAR` frequently prefers non-standard `YEAR` or
 * `ORIGINALYEAR` tags (such as streaming re-release dates written by downloaders) over the
 * canonical release-year tag (`DATE` in FLAC/Ogg Vorbis comments, `TDRC`/`TYER` in ID3v2, or
 * `©day` in MP4/M4A). Reading those container tags directly via bounded `FileChannel` seeks
 * ensures album and track years match user-edited metadata while skipping large artwork blocks.
 *
 * @see MusicRepository
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Locale

// ═══ Constants ═══
private const val MIN_VALID_YEAR = 1900
private const val MAX_VALID_YEAR = 2100
private const val FLAC_BLOCK_VORBIS_COMMENT = 4
private const val MAX_VORBIS_COMMENT_BYTES = 10 * 1024 * 1024
private const val MAX_OGG_SCAN_BYTES = 10 * 1024 * 1024
private const val MAX_ID3_TEXT_FRAME_BYTES = 4096
private const val MAX_CONTAINER_WALK_STEPS = 2048
private const val ID3_HEADER_BYTES = 10
private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2}|2100)\b""")

// ═══ Release Year Extractor ═══
object AudioTagYearHelper {

    /**
     * Extracts the canonical 4-digit release year from [path], returning `0` when unavailable.
     */
    fun extractReleaseYear(path: String): Int {
        if (path.isBlank()) return 0
        val file = File(path)
        if (!file.exists() || !file.canRead()) return 0

        return runCatching {
            FileInputStream(file).use { fis ->
                val channel = fis.channel
                val fileSize = channel.size()
                if (fileSize < 12L) return 0

                val magic = readChannelBytes(channel, 0L, 12) ?: return 0
                when {
                    isAscii(magic, 0, "fLaC") -> parseFlacYear(channel, 4L, fileSize)
                    magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() -> {
                        val id3TagSize = readSynchsafeInt(magic, 6)
                        val afterId3 = ID3_HEADER_BYTES + id3TagSize.toLong() +
                            if ((magic[3].toInt() and 0xFF) == 4 && (magic[5].toInt() and 0x10) != 0) 10L else 0L
                        val id3Year = parseId3v2Year(channel, 0L, fileSize)
                        if (id3Year > 0) {
                            id3Year
                        } else if (afterId3 + 4L <= fileSize) {
                            val trailing = readChannelBytes(channel, afterId3, 4)
                            if (trailing != null && isAscii(trailing, 0, "fLaC")) {
                                parseFlacYear(channel, afterId3 + 4L, fileSize)
                            } else {
                                0
                            }
                        } else {
                            0
                        }
                    }
                    isAscii(magic, 0, "OggS") -> parseOggYear(channel, fileSize)
                    isAscii(magic, 4, "ftyp") -> parseMp4Year(channel, fileSize)
                    isAscii(magic, 0, "FORM") && (isAscii(magic, 8, "AIFF") || isAscii(magic, 8, "AIFC")) -> {
                        AiffMetadataHelper.readMetadata(path)?.year ?: 0
                    }
                    else -> 0
                }
            }
        }.getOrDefault(0)
    }

    // ═══ Container Parsers ═══
    private fun parseFlacYear(channel: FileChannel, startOffset: Long, fileSize: Long): Int {
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

            if (blockType == FLAC_BLOCK_VORBIS_COMMENT &&
                length in 8..MAX_VORBIS_COMMENT_BYTES &&
                contentStart + length <= fileSize
            ) {
                val commentBlock = readChannelBytes(channel, contentStart, length) ?: return 0
                return parseVorbisCommentBlockYear(commentBlock, startOffset = 0)
            }

            offset = contentStart + length.toLong()
            if (isLast) break
        }
        return 0
    }

    private fun parseOggYear(channel: FileChannel, fileSize: Long): Int {
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
            val numSegments = pageHeader[26].toInt() and 0xFF
            val segTable = readChannelBytes(channel, offset + 27L, numSegments) ?: break
            var payloadLen = 0
            for (b in segTable) payloadLen += (b.toInt() and 0xFF)
            val payloadOffset = offset + 27L + numSegments
            if (payloadOffset + payloadLen > fileSize) break

            val payload = readChannelBytes(channel, payloadOffset, payloadLen) ?: break
            if (!foundTags) {
                val opusIdx = indexOfBytes(payload, opusMagic)
                val vorbisIdx = if (opusIdx == -1) indexOfBytes(payload, vorbisMagic) else -1
                if (opusIdx != -1 || vorbisIdx != -1) {
                    foundTags = true
                    val tagStart = if (opusIdx != -1) opusIdx + opusMagic.size else vorbisIdx + vorbisMagic.size
                    if (tagStart < payload.size) {
                        packet.write(payload, tagStart, payload.size - tagStart)
                    }
                    if (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255) break
                }
            } else {
                packet.write(payload, 0, payload.size)
                if (packet.size() > MAX_VORBIS_COMMENT_BYTES ||
                    (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255)
                ) {
                    break
                }
            }
            offset = payloadOffset + payloadLen
        }

        val bytes = packet.toByteArray()
        return if (bytes.size >= 8) parseVorbisCommentBlockYear(bytes, startOffset = 0) else 0
    }

    private fun parseVorbisCommentBlockYear(block: ByteArray, startOffset: Int): Int {
        var offset = startOffset
        if (offset + 4 > block.size) return 0
        val vendorLen = readInt32LE(block, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > block.size) return 0
        offset += vendorLen

        val count = readInt32LE(block, offset)
        offset += 4
        if (count <= 0) return 0

        var dateYear = 0
        var yearTag = 0
        for (i in 0 until count) {
            if (offset + 4 > block.size) break
            val commentLen = readInt32LE(block, offset)
            offset += 4
            if (commentLen <= 0 || offset + commentLen > block.size) break

            if (commentLen <= 512) {
                val entry = String(block, offset, commentLen, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).trim().uppercase(Locale.ROOT)
                    val value = entry.substring(eq + 1)
                    if (key == "DATE") {
                        val y = parseFourDigitYear(value)
                        if (y > 0) dateYear = y
                    } else if (key == "YEAR" && yearTag == 0) {
                        yearTag = parseFourDigitYear(value)
                    }
                }
            }
            offset += commentLen
        }
        return if (dateYear > 0) dateYear else yearTag
    }

    private fun parseId3v2Year(channel: FileChannel, startOffset: Long, fileSize: Long): Int {
        if (startOffset + ID3_HEADER_BYTES > fileSize) return 0
        val header = readChannelBytes(channel, startOffset, ID3_HEADER_BYTES) ?: return 0
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(header, 6)
        if (tagSize <= 0) return 0

        val tagEnd = minOf(fileSize, startOffset + ID3_HEADER_BYTES + tagSize.toLong())
        var offset = startOffset + ID3_HEADER_BYTES
        if ((flags and 0x40) != 0 && offset + 4L <= tagEnd) {
            val extBytes = readChannelBytes(channel, offset, 4) ?: return 0
            val extSize = if (major == 4) readSynchsafeInt(extBytes, 0) else readBigEndianInt(extBytes, 0) + 4
            if (extSize > 0 && offset + extSize <= tagEnd) {
                offset += extSize.toLong()
            }
        }

        var tdrcYear = 0
        var tyerYear = 0
        var steps = 0
        while (offset + 10L <= tagEnd && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val frameHdr = readChannelBytes(channel, offset, 10) ?: break
            if (frameHdr[0] == 0.toByte()) break
            val frameId = String(frameHdr, 0, 4, Charsets.ISO_8859_1)
            val frameSize = if (major == 4) {
                val hasHighBit = ((frameHdr[4].toInt() or frameHdr[5].toInt() or frameHdr[6].toInt() or frameHdr[7].toInt()) and 0x80) != 0
                if (hasHighBit) readBigEndianInt(frameHdr, 4) else readSynchsafeInt(frameHdr, 4)
            } else {
                readBigEndianInt(frameHdr, 4)
            }
            val contentStart = offset + 10L
            if (frameSize <= 0 || contentStart + frameSize > tagEnd) break

            if ((frameId == "TDRC" || frameId == "TYER") && frameSize <= MAX_ID3_TEXT_FRAME_BYTES) {
                val frameBytes = readChannelBytes(channel, contentStart, frameSize)
                val parsed = frameBytes?.let { parseFourDigitYear(decodeId3Text(it)) } ?: 0
                if (parsed > 0) {
                    if (frameId == "TDRC") tdrcYear = parsed else if (tyerYear == 0) tyerYear = parsed
                }
            }
            offset = contentStart + frameSize.toLong()
        }
        return if (tdrcYear > 0) tdrcYear else tyerYear
    }

    private fun parseMp4Year(channel: FileChannel, fileSize: Long): Int {
        val moov = findMp4Box(channel, 0L, fileSize, "moov") ?: return 0
        val udta = findMp4Box(channel, moov.first, moov.second, "udta")
        val meta = if (udta != null) {
            findMp4Box(channel, udta.first, udta.second, "meta")
                ?: findMp4Box(channel, moov.first, moov.second, "meta")
        } else {
            findMp4Box(channel, moov.first, moov.second, "meta")
        } ?: return 0

        val metaHdr = readChannelBytes(channel, meta.first, 8) ?: return 0
        val startsWithChild = isAscii(metaHdr, 4, "hdlr") || isAscii(metaHdr, 4, "ilst")
        val childrenStart = if (startsWithChild) meta.first else meta.first + 4L
        if (childrenStart >= meta.second) return 0

        val ilst = findMp4Box(channel, childrenStart, meta.second, "ilst")
            ?: (if (!startsWithChild) findMp4Box(channel, meta.first, meta.second, "ilst") else null)
            ?: return 0
        val day = findMp4Box(channel, ilst.first, ilst.second, "\u00A9day") ?: return 0
        val dataBox = findMp4Box(channel, day.first, day.second, "data") ?: return 0
        val textStart = dataBox.first + 8L
        val textLen = (dataBox.second - textStart).toInt()
        if (textLen !in 4..256) return 0
        val bytes = readChannelBytes(channel, textStart, textLen) ?: return 0
        return parseFourDigitYear(String(bytes, Charsets.UTF_8))
    }

    private fun findMp4Box(
        channel: FileChannel,
        start: Long,
        end: Long,
        targetType: String
    ): Pair<Long, Long>? {
        var offset = start
        var steps = 0
        while (offset + 8L <= end && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 8) ?: break
            val size32 = readBigEndianInt(hdr, 0).toLong() and 0xFFFFFFFFL
            val boxType = String(hdr, 4, 4, Charsets.ISO_8859_1)
            val boxSize: Long
            val contentStart: Long
            when {
                size32 == 1L -> {
                    if (offset + 16L > end) break
                    val ext = readChannelBytes(channel, offset + 8L, 8) ?: break
                    val large = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
                    if (large < 16L) break
                    boxSize = large
                    contentStart = offset + 16L
                }
                size32 == 0L -> {
                    boxSize = end - offset
                    contentStart = offset + 8L
                }
                size32 < 8L -> break
                else -> {
                    boxSize = size32
                    contentStart = offset + 8L
                }
            }
            val boxEnd = minOf(end, offset + boxSize)
            if (boxType == targetType && contentStart <= boxEnd) {
                return contentStart to boxEnd
            }
            val next = offset + boxSize
            if (next <= offset) break
            offset = next
        }
        return null
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
        return String(frameBytes, 1, frameBytes.size - 1, charset).trim('\u0000', ' ')
    }

    private fun parseFourDigitYear(raw: String): Int {
        val match = YEAR_REGEX.find(raw.trim()) ?: return 0
        val year = match.groupValues[1].toIntOrNull() ?: return 0
        return if (year in MIN_VALID_YEAR..MAX_VALID_YEAR) year else 0
    }

    private fun readChannelBytes(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val data = ByteArray(length)
        val buffer = ByteBuffer.wrap(data)
        var pos = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, pos)
            if (read <= 0) return null
            pos += read
        }
        return data
    }

    private fun isAscii(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in 0..(source.size - target.size)) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
