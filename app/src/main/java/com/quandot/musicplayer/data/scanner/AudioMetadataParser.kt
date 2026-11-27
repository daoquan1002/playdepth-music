/**
 * AudioMetadataParser — Single-open byte-level audio tag and Hi-Res technical spec extractor.
 *
 * @remarks
 * Opens each new or modified audio file at most once via `FileChannel` (falling back to
 * `ContentResolver.openFileDescriptor` for SAF/Scoped Storage URIs) to read standard metadata tags
 * (`Title`, `Artist`, `Album`, `AlbumArtist`, `Genre`, `Year`, `TrackNumber`, `DiscNumber`) and
 * Hi-Res stream specifications (`SampleRate`, `BitDepth`, `Bitrate`, `Codec`) together while
 * skipping embedded picture payloads by direct seek. Covers FLAC, MP3/ID3v2/ID3v1, Ogg Vorbis/Opus,
 * MP4/M4A/ALAC/AAC, WAV/RF64, AIFF/AIFC, DSF, DFF (DSDIFF), WavPack (`.wv`), and Monkey's Audio
 * (`.ape` + APEv2), with safe UTF-8 auto-detection for mis-tagged Vietnamese ID3v2 Latin-1 frames
 * and a bounded `MediaMetadataRetriever` fallback for exotic containers.
 *
 * @see HybridLibraryScanner
 * @see com.quandot.musicplayer.data.AiffMetadataHelper
 */
package com.quandot.musicplayer.data.scanner

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Internal modules
import com.quandot.musicplayer.data.AiffMetadataHelper

// ═══ Constants ═══
private const val MIN_VALID_YEAR = 1900
private const val MAX_VALID_YEAR = 2100
private const val MAX_CONTAINER_WALK_STEPS = 2048
private const val MAX_ID3_FRAMES_WALKED = 512
private const val MAX_TEXT_FRAME_BYTES = 8192
private const val MAX_UNSYNC_ID3_TAG_BYTES = 16 * 1024 * 1024
private const val MAX_VORBIS_COMMENT_BYTES = 16 * 1024 * 1024
private const val MAX_OGG_SCAN_BYTES = 20 * 1024 * 1024
private const val MAX_APEV2_TAG_BYTES = 2 * 1024 * 1024
private const val OGG_TAIL_SEARCH_BYTES = 65_536
private const val MP3_SYNC_SEARCH_BYTES = 32_768
private const val ID3_HEADER_BYTES = 10
private const val FLAC_BLOCK_STREAMINFO = 0
private const val FLAC_BLOCK_VORBIS_COMMENT = 4
private const val WAVE_FORMAT_IEEE_FLOAT = 3
private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2}|2100)\b""")
private val ID3_GENRE_NUMERIC_REGEX = Regex("""^\((\d+)\)(.*)$""")

private val WAVPACK_SAMPLE_RATES = intArrayOf(
    6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000,
    32000, 44100, 48000, 64000, 88200, 96000, 192000, 0
)

private val MPEG1_L3_BITRATES = intArrayOf(
    0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
)
private val MPEG2_L3_BITRATES = intArrayOf(
    0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
)
private val MPEG1_L2_BITRATES = intArrayOf(
    0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0
)
private val MPEG1_SAMPLE_RATES = intArrayOf(44100, 48000, 32000, 0)
private val MPEG2_SAMPLE_RATES = intArrayOf(22050, 24000, 16000, 0)
private val MPEG25_SAMPLE_RATES = intArrayOf(11025, 12000, 8000, 0)

private val ID3V1_GENRES = arrayOf(
    "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
    "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock",
    "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack",
    "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
    "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
    "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop",
    "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic",
    "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
    "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret",
    "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal",
    "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin",
    "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
    "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus",
    "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music",
    "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam",
    "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul",
    "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House", "Dance Hall"
)

// ═══ Parser API ═══
object AudioMetadataParser {

    /**
     * Extracts combined tag and Hi-Res stream metadata for [candidate], preferring direct
     * byte-level `FileChannel` parsing and falling back to `MediaMetadataRetriever` only when
     * essential fields remain missing.
     */
    fun extract(context: Context, candidate: ScannedFileCandidate): ExtractedAudioMetadata {
        val ext = extractExtensionLower(candidate.path.ifBlank { candidate.displayName })
        var parsed = parseViaDirectChannel(context, candidate.path, candidate.contentUri, ext)

        val needsMmrFallback = parsed == null ||
            (parsed.durationMs <= 0L && candidate.durationMs <= 0L) ||
            (parsed.durationEstimated && candidate.durationMs <= 0L) ||
            (parsed.sampleRate <= 0 && parsed.bitrate <= 0) ||
            (parsed.title.isNullOrBlank() && parsed.artist.isNullOrBlank())

        if (needsMmrFallback) {
            val mmrMeta = extractWithMmr(context, candidate.path, candidate.contentUri, ext)
            if (mmrMeta != null) {
                parsed = mergeMetadata(primary = parsed, fallback = mmrMeta)
            }
        }

        return parsed ?: ExtractedAudioMetadata(codec = ext.uppercase(Locale.ROOT))
    }

    /**
     * Pure JVM-testable entrypoint parsing directly from a local [File] without Android `Context`.
     */
    fun parseFileDirect(file: File): ExtractedAudioMetadata? {
        if (!file.exists() || !file.isFile || file.length() <= 0L || !file.canRead()) return null
        val ext = file.extension.lowercase(Locale.ROOT)
        return runCatching {
            FileInputStream(file).use { fis ->
                parseChannel(fis.channel, ext, file.absolutePath)
            }
        }.getOrNull()
    }

    // ═══ Channel Dispatch ═══
    private fun parseViaDirectChannel(
        context: Context,
        path: String,
        contentUri: Uri?,
        ext: String
    ): ExtractedAudioMetadata? {
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 0L && file.canRead()) {
                val direct = runCatching {
                    FileInputStream(file).use { fis ->
                        parseChannel(fis.channel, ext, path)
                    }
                }.getOrNull()
                if (direct != null) return direct
            }
        }

        if (contentUri != null && contentUri != Uri.EMPTY) {
            return runCatching {
                context.contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        parseChannel(fis.channel, ext, path)
                    }
                }
            }.getOrNull()
        }
        return null
    }

    internal fun parseChannel(
        channel: FileChannel,
        extHint: String,
        pathHint: String = ""
    ): ExtractedAudioMetadata? {
        val fileSize = runCatching { channel.size() }.getOrDefault(0L)
        if (fileSize < 12L) return null

        val header = readChannelBytes(channel, 0L, minOf(fileSize, 16L).toInt()) ?: return null
        return when {
            isAscii(header, 0, "fLaC") -> parseFlac(channel, 4L, fileSize, id3Fallback = null)
            header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                parseId3PrefixedFile(channel, header, fileSize, extHint)
            }
            isAscii(header, 0, "OggS") -> parseOgg(channel, fileSize)
            header.size >= 8 && isAscii(header, 4, "ftyp") -> parseMp4(channel, fileSize)
            (isAscii(header, 0, "RIFF") || isAscii(header, 0, "RF64")) && isAscii(header, 8, "WAVE") -> {
                parseWav(channel, fileSize)
            }
            isAscii(header, 0, "FORM") && (isAscii(header, 8, "AIFF") || isAscii(header, 8, "AIFC")) -> {
                parseAiff(pathHint, channel)
            }
            isAscii(header, 0, "DSD ") -> parseDsf(channel, fileSize)
            header.size >= 16 && isAscii(header, 0, "FRM8") && isAscii(header, 12, "DSD ") -> {
                parseDff(channel, fileSize)
            }
            isAscii(header, 0, "wvpk") -> parseWavPack(channel, fileSize)
            isAscii(header, 0, "MAC ") -> parseMonkeysAudio(channel, fileSize)
            extHint == "mp3" -> parseMp3Audio(channel, 0L, fileSize, id3Meta = null)
            else -> null
        }
    }

    // ═══ FLAC Parser ═══
    private fun parseFlac(
        channel: FileChannel,
        startOffset: Long,
        fileSize: Long,
        id3Fallback: ExtractedAudioMetadata?
    ): ExtractedAudioMetadata? {
        var offset = startOffset
        var steps = 0
        var sampleRate = 0
        var bitDepth = 0
        var durationMs = 0L
        var vorbisMeta: ExtractedAudioMetadata? = null

        while (offset + 4L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 4) ?: break
            val isLast = (hdr[0].toInt() and 0x80) != 0
            val blockType = hdr[0].toInt() and 0x7F
            val length = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
            val contentStart = offset + 4L
            if (contentStart + length > fileSize) break

            when (blockType) {
                FLAC_BLOCK_STREAMINFO -> if (length >= 34) {
                    val info = readChannelBytes(channel, contentStart, 34)
                    if (info != null) {
                        var packed = 0L
                        for (i in 10..17) {
                            packed = (packed shl 8) or (info[i].toLong() and 0xFFL)
                        }
                        sampleRate = (packed ushr 44).toInt()
                        bitDepth = (((packed ushr 36) and 0x1FL) + 1L).toInt()
                        val totalSamples = packed and 0x0000000FFFFFFFFFL
                        if (sampleRate > 0 && totalSamples > 0L) {
                            durationMs = (totalSamples * 1_000L) / sampleRate
                        }
                    }
                }
                FLAC_BLOCK_VORBIS_COMMENT -> if (length in 8..MAX_VORBIS_COMMENT_BYTES) {
                    val commentBytes = readChannelBytes(channel, contentStart, length)
                    if (commentBytes != null) {
                        vorbisMeta = parseVorbisCommentBlock(commentBytes, 0)
                    }
                }
            }

            offset = contentStart + length.toLong()
            if (isLast) break
        }

        val audioBytes = (fileSize - offset).coerceAtLeast(0L)
        val bitrate = if (durationMs > 0L && audioBytes > 0L) {
            ((audioBytes * 8_000L) / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0
        val tags = mergeMetadata(vorbisMeta, id3Fallback)
        return tags.copy(
            durationMs = if (durationMs > 0L) durationMs else tags.durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = "FLAC"
        )
    }

    // ═══ ID3v2 / MP3 Parser ═══
    private fun parseId3PrefixedFile(
        channel: FileChannel,
        header: ByteArray,
        fileSize: Long,
        extHint: String
    ): ExtractedAudioMetadata? {
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = readSynchsafeInt(header, 6)
        val afterId3 = ID3_HEADER_BYTES + tagSize.toLong() +
            if (major == 4 && (flags and 0x10) != 0) 10L else 0L

        val id3Meta = parseId3v2At(channel, 0L, fileSize)

        if (afterId3 + 4L <= fileSize) {
            val trailing = readChannelBytes(channel, afterId3, 4)
            if (trailing != null && isAscii(trailing, 0, "fLaC")) {
                return parseFlac(channel, afterId3 + 4L, fileSize, id3Fallback = id3Meta)
            }
            if (trailing != null && isAscii(trailing, 0, "wvpk")) {
                val wv = parseWavPack(channel, fileSize, startOffset = afterId3)
                if (wv != null) return mergeMetadata(wv, id3Meta)
            }
        }

        return parseMp3Audio(channel, afterId3.coerceAtMost(fileSize), fileSize, id3Meta)
    }

    private fun parseMp3Audio(
        channel: FileChannel,
        audioStart: Long,
        fileSize: Long,
        id3Meta: ExtractedAudioMetadata?
    ): ExtractedAudioMetadata? {
        var tags = id3Meta
        var trailingTagBytes = 0L
        val apeFooterInfo = parseApeV2FooterWithSize(channel, fileSize)
        if (apeFooterInfo != null) {
            tags = mergeMetadata(tags, apeFooterInfo.first)
            trailingTagBytes += apeFooterInfo.second
        }
        val id3v1 = parseId3v1Footer(channel, fileSize)
        if (id3v1 != null) {
            tags = mergeMetadata(tags, id3v1)
            trailingTagBytes += 128L
        }

        var sampleRate = 0
        var bitrate = 0
        var durationMs = 0L
        var durationEstimated = false

        val probeLen = minOf((fileSize - audioStart).coerceAtLeast(0L), MP3_SYNC_SEARCH_BYTES.toLong()).toInt()
        if (probeLen >= 4) {
            val probe = readChannelBytes(channel, audioStart, probeLen)
            if (probe != null) {
                var fallbackMatch: Int = -1
                var confirmedMatch: Int = -1
                for (i in 0 until probe.size - 4) {
                    val hdr = parseMpegFrameHeader(probe, i) ?: continue
                    if (fallbackMatch < 0) fallbackMatch = i
                    val nextOffset = i + hdr.frameLengthBytes
                    if (nextOffset + 4 <= probe.size) {
                        val nextHdr = parseMpegFrameHeader(probe, nextOffset)
                        if (nextHdr != null &&
                            nextHdr.versionBits == hdr.versionBits &&
                            nextHdr.layerBits == hdr.layerBits &&
                            nextHdr.sampleRate == hdr.sampleRate
                        ) {
                            confirmedMatch = i
                            break
                        }
                    } else if (audioStart + nextOffset >= fileSize - trailingTagBytes - 4L) {
                        confirmedMatch = i
                        break
                    }
                }

                val matchIdx = if (confirmedMatch >= 0) confirmedMatch else fallbackMatch
                val hdr = if (matchIdx >= 0) parseMpegFrameHeader(probe, matchIdx) else null
                if (hdr != null) {
                    sampleRate = hdr.sampleRate
                    bitrate = hdr.bitrateKbps * 1000

                    // Check Xing / Info / VBRI VBR header for exact frame count
                    val xingOffset = matchIdx + 4 + if (hdr.versionBits == 3) {
                        if (hdr.isMono) 17 else 32
                    } else {
                        if (hdr.isMono) 9 else 17
                    }
                    var vbrFrames = 0L
                    if (xingOffset + 12 <= probe.size &&
                        (isAscii(probe, xingOffset, "Xing") || isAscii(probe, xingOffset, "Info"))
                    ) {
                        val xingFlags = readBeS32(probe, xingOffset + 4)
                        if ((xingFlags and 0x01) != 0) {
                            vbrFrames = readBeU32(probe, xingOffset + 8)
                        }
                    } else {
                        val vbriOffset = matchIdx + 36
                        if (vbriOffset + 18 <= probe.size && isAscii(probe, vbriOffset, "VBRI")) {
                            vbrFrames = readBeU32(probe, vbriOffset + 14)
                        }
                    }

                    val audioBytes = (fileSize - (audioStart + matchIdx) - trailingTagBytes).coerceAtLeast(0L)
                    if (vbrFrames > 0L) {
                        durationMs = (vbrFrames * hdr.samplesPerFrame * 1_000L) / sampleRate
                        if (durationMs > 0L && audioBytes > 0L) {
                            bitrate = ((audioBytes * 8_000L) / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        }
                    } else if (bitrate > 0 && audioBytes > 0L) {
                        // Without Xing/VBRI header, CBR-style estimate can misread VBR files whose first frame is 32 kbps
                        durationMs = (audioBytes * 8_000L) / bitrate
                        durationEstimated = true
                    }
                }
            }
        }

        if (tags == null && sampleRate <= 0 && durationMs <= 0L) return null
        return (tags ?: ExtractedAudioMetadata()).copy(
            durationMs = if (durationMs > 0L) durationMs else (tags?.durationMs ?: 0L),
            durationEstimated = durationEstimated,
            sampleRate = sampleRate,
            bitrate = bitrate,
            codec = "MP3"
        )
    }

    private data class MpegFrameHeader(
        val versionBits: Int,
        val layerBits: Int,
        val sampleRate: Int,
        val bitrateKbps: Int,
        val samplesPerFrame: Int,
        val frameLengthBytes: Int,
        val isMono: Boolean
    )

    private fun parseMpegFrameHeader(buf: ByteArray, offset: Int): MpegFrameHeader? {
        if (offset < 0 || offset + 4 > buf.size) return null
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val versionBits = (b1 ushr 3) and 0x03
        val layerBits = (b1 ushr 1) and 0x03
        if (versionBits == 1 || layerBits == 0) return null

        val b2 = buf[offset + 2].toInt() and 0xFF
        val bitrateIdx = (b2 ushr 4) and 0x0F
        val srIdx = (b2 ushr 2) and 0x03
        val padding = (b2 ushr 1) and 0x01
        if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) return null

        val sr = when (versionBits) {
            3 -> MPEG1_SAMPLE_RATES[srIdx]
            2 -> MPEG2_SAMPLE_RATES[srIdx]
            0 -> MPEG25_SAMPLE_RATES[srIdx]
            else -> 0
        }
        val brKbps = when {
            versionBits == 3 && layerBits == 1 -> MPEG1_L3_BITRATES[bitrateIdx]
            versionBits == 3 && layerBits == 2 -> MPEG1_L2_BITRATES[bitrateIdx]
            (versionBits == 2 || versionBits == 0) && layerBits == 1 -> MPEG2_L3_BITRATES[bitrateIdx]
            else -> 0
        }
        if (sr <= 0 || brKbps <= 0) return null

        val b3 = buf[offset + 3].toInt() and 0xFF
        val isMono = ((b3 ushr 6) and 0x03) == 3
        val samplesPerFrame = when {
            layerBits == 3 -> 384
            versionBits == 3 -> 1152
            layerBits == 2 -> 1152
            else -> 576
        }
        val frameLengthBytes = when {
            layerBits == 3 -> ((12 * brKbps * 1000) / sr + padding) * 4
            versionBits == 3 || layerBits == 2 -> (144 * brKbps * 1000) / sr + padding
            else -> (72 * brKbps * 1000) / sr + padding
        }
        if (frameLengthBytes < 16) return null

        return MpegFrameHeader(
            versionBits = versionBits,
            layerBits = layerBits,
            sampleRate = sr,
            bitrateKbps = brKbps,
            samplesPerFrame = samplesPerFrame,
            frameLengthBytes = frameLengthBytes,
            isMono = isMono
        )
    }

    internal fun parseId3v2At(
        channel: FileChannel,
        startOffset: Long,
        limitOffset: Long
    ): ExtractedAudioMetadata? {
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
        val tagUnsync = (flags and 0x80) != 0

        // In ID3v2.2/v2.3, tag-level unsynchronization applies to the entire tag body (including
        // extended headers and frame headers), so de-unsync into memory before walking frames.
        // In ID3v2.4 (or non-unsync tags), stream directly via FileChannel seeks to skip large APICs.
        val unsyncTagBody: ByteArray? = if (tagUnsync && major < 4) {
            val bodyLen = (tagEnd - (startOffset + ID3_HEADER_BYTES)).coerceAtMost(MAX_UNSYNC_ID3_TAG_BYTES.toLong()).toInt()
            if (bodyLen <= 0) return null
            val rawBody = readChannelBytes(channel, startOffset + ID3_HEADER_BYTES, bodyLen) ?: return null
            removeId3Unsynchronization(rawBody)
        } else {
            null
        }

        val effectiveEnd = unsyncTagBody?.size?.toLong() ?: tagEnd
        var offset = if (unsyncTagBody != null) 0L else (startOffset + ID3_HEADER_BYTES)

        fun readSlice(pos: Long, len: Int): ByteArray? {
            if (len <= 0 || pos < 0L || pos + len > effectiveEnd) return null
            return if (unsyncTagBody != null) {
                unsyncTagBody.copyOfRange(pos.toInt(), pos.toInt() + len)
            } else {
                readChannelBytes(channel, pos, len)
            }
        }

        if (major >= 3 && (flags and 0x40) != 0 && offset + 4L <= effectiveEnd) {
            val ext = readSlice(offset, 4) ?: return null
            val extSize = if (major == 4) readSynchsafeInt(ext, 0) else readBeS32(ext, 0) + 4
            if (extSize > 0 && offset + extSize <= effectiveEnd) {
                offset += extSize.toLong()
            }
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var tdrcYear = 0
        var tyerYear = 0
        var trackNumber = 0
        var discNumber = 0

        val frameHeaderLen = if (major == 2) 6 else 10
        var steps = 0

        while (offset + frameHeaderLen <= effectiveEnd && steps < MAX_ID3_FRAMES_WALKED) {
            steps++
            val fh = readSlice(offset, frameHeaderLen) ?: break
            if (fh[0] == 0.toByte()) break

            val frameId: String
            val frameSize: Int
            var frameFlagsLow = 0
            if (major == 2) {
                frameId = String(fh, 0, 3, Charsets.ISO_8859_1)
                frameSize = ((fh[3].toInt() and 0xFF) shl 16) or
                    ((fh[4].toInt() and 0xFF) shl 8) or
                    (fh[5].toInt() and 0xFF)
            } else {
                frameId = String(fh, 0, 4, Charsets.ISO_8859_1)
                frameSize = if (major == 4) {
                    val hasHighBit = ((fh[4].toInt() or fh[5].toInt() or fh[6].toInt() or fh[7].toInt()) and 0x80) != 0
                    if (hasHighBit) readBeS32(fh, 4) else readSynchsafeInt(fh, 4)
                } else {
                    readBeS32(fh, 4)
                }
                frameFlagsLow = fh[9].toInt() and 0xFF
            }

            val contentStart = offset + frameHeaderLen
            val resolvedFrameSize = if (major == 4) {
                val hasHighBit = ((fh[4].toInt() or fh[5].toInt() or fh[6].toInt() or fh[7].toInt()) and 0x80) != 0
                val synchSize = readSynchsafeInt(fh, 4)
                val beSize = readBeS32(fh, 4)
                when {
                    hasHighBit -> beSize
                    synchSize == beSize -> synchSize
                    else -> {
                        // Some taggers emit non-synchsafe BE sizes in ID3v2.4 frames (e.g., 256, 1024);
                        // score the next boundary so a real frame ID / tag end always beats mid-frame zeros.
                        val synchScore = scoreId3v24NextBoundary(contentStart + synchSize.toLong(), effectiveEnd, ::readSlice)
                        val beScore = if (beSize > 0 && contentStart + beSize <= effectiveEnd) {
                            scoreId3v24NextBoundary(contentStart + beSize.toLong(), effectiveEnd, ::readSlice)
                        } else 0
                        if (beScore > synchScore) beSize else synchSize
                    }
                }
            } else {
                frameSize
            }
            if (resolvedFrameSize <= 0 || contentStart + resolvedFrameSize > effectiveEnd) break

            val wanted = when (frameId) {
                "TIT2", "TT2", "TPE1", "TP1", "TPE2", "TP2", "TALB", "TAL",
                "TCON", "TCO", "TDRC", "TYER", "TYE", "TRCK", "TRK", "TPOS", "TPA" -> true
                else -> false
            }

            val compressedOrEncrypted = when (major) {
                4 -> (frameFlagsLow and 0x0C) != 0 // bit 3 = compression, bit 2 = encryption
                3 -> (frameFlagsLow and 0xC0) != 0 // bit 7 = compression, bit 6 = encryption
                else -> false
            }

            if (wanted && !compressedOrEncrypted && resolvedFrameSize <= MAX_TEXT_FRAME_BYTES) {
                var payload = readSlice(contentStart, resolvedFrameSize)
                if (payload != null && major == 4) {
                    // Remove ID3v2.4 per-frame unsynchronization before stripping optional header fields
                    if (tagUnsync || (frameFlagsLow and 0x02) != 0) {
                        payload = removeId3Unsynchronization(payload)
                    }
                    var strip = 0
                    if ((frameFlagsLow and 0x40) != 0) strip += 1 // Group Identifier byte
                    if ((frameFlagsLow and 0x01) != 0) strip += 4 // Data Length Indicator (4-byte synchsafe)
                    payload = if (strip > 0 && strip < payload.size) {
                        payload.copyOfRange(strip, payload.size)
                    } else if (strip >= payload.size) {
                        null
                    } else {
                        payload
                    }
                } else if (payload != null && major == 3 && (frameFlagsLow and 0x20) != 0) {
                    // ID3v2.3 bit 5 = Grouping Identity (1 extra group ID byte before the encoding byte)
                    payload = if (payload.size > 1) payload.copyOfRange(1, payload.size) else null
                }
                val text = payload?.let { decodeId3Text(it) }?.takeIf { it.isNotBlank() }
                if (text != null) {
                    when (frameId) {
                        "TIT2", "TT2" -> if (title == null) title = text
                        "TPE1", "TP1" -> if (artist == null) artist = text
                        "TPE2", "TP2" -> if (albumArtist == null) albumArtist = text
                        "TALB", "TAL" -> if (album == null) album = text
                        "TCON", "TCO" -> if (genre == null) genre = cleanId3Genre(text)
                        "TDRC" -> parseFourDigitYear(text).takeIf { it > 0 }?.let { tdrcYear = it }
                        "TYER", "TYE" -> if (tyerYear == 0) tyerYear = parseFourDigitYear(text)
                        "TRCK", "TRK" -> if (trackNumber == 0) trackNumber = parseSlashNumber(text)
                        "TPOS", "TPA" -> if (discNumber == 0) discNumber = parseSlashNumber(text)
                    }
                }
            }

            offset = contentStart + resolvedFrameSize.toLong()
        }

        val resolvedYear = if (tdrcYear > 0) tdrcYear else tyerYear
        if (title == null && artist == null && album == null && albumArtist == null &&
            genre == null && resolvedYear == 0 && trackNumber == 0 && discNumber == 0
        ) {
            return null
        }

        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = resolvedYear,
            trackNumber = trackNumber,
            discNumber = discNumber
        )
    }

    private fun parseId3v1Footer(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        if (fileSize < 128L) return null
        val tail = readChannelBytes(channel, fileSize - 128L, 128) ?: return null
        if (!isAscii(tail, 0, "TAG")) return null

        val title = decodeSmartBytes(tail, 3, 30).takeIf { it.isNotBlank() }
        val artist = decodeSmartBytes(tail, 33, 30).takeIf { it.isNotBlank() }
        val album = decodeSmartBytes(tail, 63, 30).takeIf { it.isNotBlank() }
        val year = parseFourDigitYear(String(tail, 93, 4, Charsets.ISO_8859_1))
        val track = if (tail[125] == 0.toByte() && tail[126] != 0.toByte()) tail[126].toInt() and 0xFF else 0
        val genreIdx = tail[127].toInt() and 0xFF
        val genre = ID3V1_GENRES.getOrNull(genreIdx)

        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = track
        )
    }

    // ═══ Ogg Vorbis / Opus Parser ═══
    private fun parseOgg(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        val opusHeadMagic = "OpusHead".toByteArray(Charsets.US_ASCII)
        val opusTagsMagic = "OpusTags".toByteArray(Charsets.US_ASCII)
        val vorbisIdMagic = byteArrayOf(0x01, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
        val vorbisTagsMagic = byteArrayOf(0x03, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())

        var offset = 0L
        var steps = 0
        var codec = "OGG"
        var sampleRate = 0
        var nominalBitrate = 0
        var preSkip = 0
        var foundTags = false
        val tagPacket = ByteArrayOutputStream()

        while (offset + 27L <= fileSize && offset < MAX_OGG_SCAN_BYTES && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val pageHdr = readChannelBytes(channel, offset, 27) ?: break
            if (!isAscii(pageHdr, 0, "OggS")) break
            val numSegs = pageHdr[26].toInt() and 0xFF
            val segTable = readChannelBytes(channel, offset + 27L, numSegs) ?: break
            var payloadLen = 0
            for (b in segTable) payloadLen += (b.toInt() and 0xFF)
            val payloadOffset = offset + 27L + numSegs
            if (payloadOffset + payloadLen > fileSize) break

            val payload = readChannelBytes(channel, payloadOffset, payloadLen) ?: break

            if (sampleRate == 0) {
                val opusIdx = indexOfBytes(payload, opusHeadMagic)
                if (opusIdx >= 0 && opusIdx + 16 <= payload.size) {
                    codec = "OPUS"
                    sampleRate = 48_000
                    preSkip = readLeU16(payload, opusIdx + 10)
                } else {
                    val vorbisIdx = indexOfBytes(payload, vorbisIdMagic)
                    if (vorbisIdx >= 0 && vorbisIdx + 28 <= payload.size) {
                        codec = "OGG"
                        sampleRate = readLeS32(payload, vorbisIdx + 12)
                        nominalBitrate = readLeS32(payload, vorbisIdx + 20).coerceAtLeast(0)
                    }
                }
            }

            if (!foundTags) {
                val otIdx = indexOfBytes(payload, opusTagsMagic)
                val vtIdx = if (otIdx == -1) indexOfBytes(payload, vorbisTagsMagic) else -1
                if (otIdx != -1 || vtIdx != -1) {
                    foundTags = true
                    val start = if (otIdx != -1) otIdx + opusTagsMagic.size else vtIdx + vorbisTagsMagic.size
                    if (start < payload.size) {
                        tagPacket.write(payload, start, payload.size - start)
                    }
                    if (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255) break
                }
            } else {
                tagPacket.write(payload, 0, payload.size)
                if (tagPacket.size() > MAX_VORBIS_COMMENT_BYTES ||
                    (segTable.isNotEmpty() && (segTable.last().toInt() and 0xFF) < 255)
                ) {
                    break
                }
            }

            offset = payloadOffset + payloadLen
        }

        // Compute duration from the final Ogg page granule position near EOF
        var durationMs = 0L
        if (sampleRate > 0) {
            val tailSize = minOf(fileSize, OGG_TAIL_SEARCH_BYTES.toLong()).toInt()
            val tailBytes = readChannelBytes(channel, fileSize - tailSize, tailSize)
            if (tailBytes != null) {
                var lastGranule = -1L
                for (i in (tailBytes.size - 27) downTo 0) {
                    if (tailBytes[i] == 'O'.code.toByte() &&
                        tailBytes[i + 1] == 'g'.code.toByte() &&
                        tailBytes[i + 2] == 'g'.code.toByte() &&
                        tailBytes[i + 3] == 'S'.code.toByte()
                    ) {
                        val granule = readLeS64(tailBytes, i + 6)
                        if (granule > 0L) {
                            lastGranule = granule
                            break
                        }
                    }
                }
                if (lastGranule > 0L) {
                    val effectiveSamples = (lastGranule - preSkip).coerceAtLeast(0L)
                    durationMs = (effectiveSamples * 1_000L) / sampleRate
                }
            }
        }

        val audioBytes = (fileSize - offset).coerceAtLeast(0L)
        val avgBitrate = if (nominalBitrate > 0) {
            nominalBitrate
        } else if (durationMs > 0L && audioBytes > 0L) {
            ((audioBytes * 8_000L) / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }

        val tags = if (tagPacket.size() >= 8) {
            parseVorbisCommentBlock(tagPacket.toByteArray(), 0)
        } else {
            ExtractedAudioMetadata()
        }

        return tags.copy(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitrate = avgBitrate,
            codec = codec
        )
    }

    private fun parseVorbisCommentBlock(block: ByteArray, startOffset: Int): ExtractedAudioMetadata {
        var offset = startOffset
        if (offset + 4 > block.size) return ExtractedAudioMetadata()
        val vendorLen = readLeS32(block, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > block.size) return ExtractedAudioMetadata()
        offset += vendorLen

        val count = readLeS32(block, offset)
        offset += 4
        if (count <= 0) return ExtractedAudioMetadata()

        var title: String? = null
        val artists = LinkedHashSet<String>(2)
        var album: String? = null
        var albumArtist: String? = null
        val genres = LinkedHashSet<String>(2)
        var dateYear = 0
        var yearTag = 0
        var trackNumber = 0
        var discNumber = 0

        for (i in 0 until minOf(count, 512)) {
            if (offset + 4 > block.size) break
            val len = readLeS32(block, offset)
            offset += 4
            if (len <= 0 || offset + len > block.size) break

            // Skip oversized fields (such as base64 METADATA_BLOCK_PICTURE) without allocating Strings
            if (len <= MAX_TEXT_FRAME_BYTES) {
                val entry = String(block, offset, len, Charsets.UTF_8)
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    val key = entry.substring(0, eq).trim().uppercase(Locale.ROOT)
                    val value = entry.substring(eq + 1).trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "TITLE" -> if (title == null) title = value
                            "ARTIST" -> artists.add(value)
                            "ALBUM" -> if (album == null) album = value
                            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> if (albumArtist == null) albumArtist = value
                            "GENRE" -> genres.add(value)
                            "DATE" -> parseFourDigitYear(value).takeIf { it > 0 }?.let { dateYear = it }
                            "YEAR" -> if (yearTag == 0) yearTag = parseFourDigitYear(value)
                            "TRACKNUMBER", "TRACK" -> if (trackNumber == 0) trackNumber = parseSlashNumber(value)
                            "DISCNUMBER", "DISC" -> if (discNumber == 0) discNumber = parseSlashNumber(value)
                        }
                    }
                }
            }
            offset += len
        }

        return ExtractedAudioMetadata(
            title = title,
            artist = if (artists.isNotEmpty()) artists.joinToString(", ") else null,
            album = album,
            albumArtist = albumArtist,
            genre = if (genres.isNotEmpty()) genres.joinToString(", ") else null,
            year = if (dateYear > 0) dateYear else yearTag,
            trackNumber = trackNumber,
            discNumber = discNumber
        )
    }

    // ═══ MP4 / M4A / ALAC / AAC Parser ═══
    private data class Mp4Box(val contentStart: Long, val end: Long)

    private fun parseMp4(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        val moov = findMp4Box(channel, 0L, fileSize, "moov") ?: return null

        // 1. Duration from mvhd
        var durationMs = 0L
        findMp4Box(channel, moov.contentStart, moov.end, "mvhd")?.let { mvhd ->
            val hdr = readChannelBytes(channel, mvhd.contentStart, minOf(32L, mvhd.end - mvhd.contentStart).toInt())
            if (hdr != null && hdr.size >= 20) {
                val version = hdr[0].toInt() and 0xFF
                if (version == 0) {
                    val timeScale = readBeU32(hdr, 12)
                    val dur = readBeU32(hdr, 16)
                    if (timeScale > 0L && dur > 0L) durationMs = (dur * 1_000L) / timeScale
                } else if (version == 1 && hdr.size >= 32) {
                    val timeScale = readBeU32(hdr, 20)
                    val dur = readBeS64(hdr, 24)
                    if (timeScale > 0L && dur > 0L) durationMs = (dur * 1_000L) / timeScale
                }
            }
        }

        // 2. Audio specs from trak -> mdia -> minf -> stbl -> stsd
        var codec = "AAC"
        var sampleRate = 0
        var bitDepth = 0
        findMp4AudioSpecs(channel, moov.contentStart, moov.end)?.let { (c, sr, bd) ->
            codec = c
            sampleRate = sr
            bitDepth = bd
        }

        // 3. Tags from udta -> meta -> ilst (or moov -> meta -> ilst)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year = 0
        var trackNumber = 0
        var discNumber = 0

        val udta = findMp4Box(channel, moov.contentStart, moov.end, "udta")
        val meta = if (udta != null) {
            findMp4Box(channel, udta.contentStart, udta.end, "meta")
                ?: findMp4Box(channel, moov.contentStart, moov.end, "meta")
        } else {
            findMp4Box(channel, moov.contentStart, moov.end, "meta")
        }

        if (meta != null) {
            val metaHdr = readChannelBytes(channel, meta.contentStart, 8)
            val startsWithChild = metaHdr != null &&
                (isAscii(metaHdr, 4, "hdlr") || isAscii(metaHdr, 4, "ilst"))
            val childStart = if (startsWithChild) meta.contentStart else meta.contentStart + 4L
            val ilst = if (childStart < meta.end) {
                findMp4Box(channel, childStart, meta.end, "ilst")
                    ?: if (!startsWithChild) findMp4Box(channel, meta.contentStart, meta.end, "ilst") else null
            } else null

            if (ilst != null) {
                var pos = ilst.contentStart
                var steps = 0
                while (pos + 8L <= ilst.end && steps < MAX_CONTAINER_WALK_STEPS) {
                    steps++
                    val atom = readMp4BoxAt(channel, pos, ilst.end) ?: break
                    val atomType = atom.first
                    val box = atom.second
                    if (atomType != "covr") {
                        val dataBox = findMp4Box(channel, box.contentStart, box.end, "data")
                        if (dataBox != null && dataBox.end - dataBox.contentStart > 8L) {
                            val payloadStart = dataBox.contentStart + 8L
                            val payloadLen = (dataBox.end - payloadStart).toInt()
                            if (payloadLen in 1..MAX_TEXT_FRAME_BYTES) {
                                val data = readChannelBytes(channel, payloadStart, payloadLen)
                                if (data != null) {
                                    when (atomType) {
                                        "\u00A9nam" -> title = String(data, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                        "\u00A9ART" -> artist = String(data, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                        "\u00A9alb" -> album = String(data, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                        "aART" -> albumArtist = String(data, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                        "\u00A9gen" -> genre = String(data, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                        "gnre" -> if (genre == null && data.size >= 2) {
                                            val idx = readBeU16(data, 0) - 1
                                            genre = ID3V1_GENRES.getOrNull(idx)
                                        }
                                        "\u00A9day" -> year = parseFourDigitYear(String(data, Charsets.UTF_8))
                                        "trkn" -> if (data.size >= 4) trackNumber = readBeU16(data, 2)
                                        "disk" -> if (data.size >= 4) discNumber = readBeU16(data, 2)
                                    }
                                }
                            }
                        }
                    }
                    if (box.end <= pos) break
                    pos = box.end
                }
            }
        }

        // Measure actual `mdat` payload size so embedded `covr` artwork does not inflate bitrate
        val mdatPayloadBytes = findMp4Box(channel, 0L, fileSize, "mdat")
            ?.let { (it.end - it.contentStart).coerceAtLeast(0L) }
            ?.takeIf { it > 0L }
            ?: fileSize
        val bitrate = if (durationMs > 0L && mdatPayloadBytes > 0L) {
            ((mdatPayloadBytes * 8_000L) / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0
        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = codec
        )
    }

    private fun findMp4AudioSpecs(channel: FileChannel, start: Long, end: Long): Triple<String, Int, Int>? {
        var pos = start
        var steps = 0
        var mdhdTimescale = 0
        while (pos + 8L <= end && steps < 128) {
            steps++
            val atom = readMp4BoxAt(channel, pos, end) ?: break
            val type = atom.first
            val box = atom.second
            when (type) {
                "mdhd" -> {
                    val hdr = readChannelBytes(channel, box.contentStart, minOf(24L, box.end - box.contentStart).toInt())
                    if (hdr != null && hdr.size >= 16) {
                        val ver = hdr[0].toInt() and 0xFF
                        val ts = if (ver == 1 && hdr.size >= 24) readBeU32(hdr, 20) else readBeU32(hdr, 12)
                        if (ts in 8_000L..768_000L) mdhdTimescale = ts.toInt()
                    }
                }
                "trak", "mdia", "minf", "stbl" -> {
                    findMp4AudioSpecs(channel, box.contentStart, box.end)?.let { (codec, sr, bd) ->
                        val effectiveSr = if (sr > 0 && (mdhdTimescale <= 65535 || sr > 65535)) {
                            sr
                        } else if (mdhdTimescale > 65535 && sr <= 65535 && codec != "OPUS") {
                            mdhdTimescale
                        } else {
                            if (sr > 0) sr else mdhdTimescale
                        }
                        return Triple(codec, effectiveSr, bd)
                    }
                }
                "stsd" -> {
                    parseMp4StsdAudio(channel, box.contentStart, box.end)?.let { (codec, sr, bd) ->
                        val effectiveSr = if (mdhdTimescale > 65535 && sr <= 65535 && codec != "OPUS") {
                            mdhdTimescale
                        } else {
                            if (sr > 0) sr else mdhdTimescale
                        }
                        return Triple(codec, effectiveSr, bd)
                    }
                }
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return null
    }

    private fun parseMp4StsdAudio(channel: FileChannel, start: Long, end: Long): Triple<String, Int, Int>? {
        if (start + 8L > end) return null
        val hdr = readChannelBytes(channel, start, 8) ?: return null
        val entryCount = readBeU32(hdr, 4).coerceAtMost(8L).toInt()
        var pos = start + 8L
        for (i in 0 until entryCount) {
            if (pos + 8L > end) break
            val atom = readMp4BoxAt(channel, pos, end) ?: break
            val type = atom.first
            val box = atom.second
            if (type == "alac" && box.contentStart + 28L <= box.end) {
                var childPos = box.contentStart + 28L
                for (c in 0 until 12) {
                    if (childPos + 8L > box.end) break
                    val child = readMp4BoxAt(channel, childPos, box.end) ?: break
                    val childBox = child.second
                    val payloadLen = childBox.end - childBox.contentStart
                    if (child.first == "alac" && payloadLen >= 24L) {
                        val cfgOffset = if (payloadLen >= 28L) childBox.contentStart + 4L else childBox.contentStart
                        val cfg = readChannelBytes(channel, cfgOffset, 24)
                        if (cfg != null) {
                            val bd = cfg[5].toInt() and 0xFF
                            val sr = readBeU32(cfg, 20).toInt()
                            if (sr in 8_000..768_000 && bd in 8..32) {
                                return Triple("ALAC", sr, bd)
                            }
                        }
                    }
                    if (childBox.end <= childPos) break
                    childPos = childBox.end
                }
            } else if (type == "fLaC" && box.contentStart + 28L <= box.end) {
                // Parse ISO BMFF `dfLa` child box (4-byte FullBox header + 4-byte FLAC block header + 34-byte STREAMINFO)
                var childPos = box.contentStart + 28L
                for (c in 0 until 12) {
                    if (childPos + 8L > box.end) break
                    val child = readMp4BoxAt(channel, childPos, box.end) ?: break
                    val childBox = child.second
                    if (child.first == "dfLa" && childBox.end - childBox.contentStart >= 42L) {
                        val dfla = readChannelBytes(channel, childBox.contentStart + 8L, 34)
                        if (dfla != null) {
                            var packed = 0L
                            for (b in 10..17) packed = (packed shl 8) or (dfla[b].toLong() and 0xFFL)
                            val sr = (packed ushr 44).toInt()
                            val bd = (((packed ushr 36) and 0x1FL) + 1L).toInt()
                            if (sr in 8_000..768_000 && bd in 8..32) {
                                return Triple("FLAC", sr, bd)
                            }
                        }
                    }
                    if (childBox.end <= childPos) break
                    childPos = childBox.end
                }
                val entry = readChannelBytes(channel, box.contentStart, 28)
                val sr = entry?.let { readBeU16(it, 24) } ?: 0
                if (sr > 0) return Triple("FLAC", sr, 0)
            } else if ((type == "mp4a" || type == "Opus") && box.contentStart + 28L <= box.end) {
                val entry = readChannelBytes(channel, box.contentStart, 28)
                if (entry != null) {
                    val sr = if (type == "Opus") 48_000 else readBeU16(entry, 24)
                    val codecName = if (type == "Opus") "OPUS" else "AAC"
                    if (sr > 0) return Triple(codecName, sr, 0)
                }
            }
            if (box.end <= pos) break
            pos = box.end
        }
        return null
    }

    private fun findMp4Box(channel: FileChannel, start: Long, end: Long, targetType: String): Mp4Box? {
        var pos = start
        var steps = 0
        while (pos + 8L <= end && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val atom = readMp4BoxAt(channel, pos, end) ?: break
            if (atom.first == targetType) return atom.second
            if (atom.second.end <= pos) break
            pos = atom.second.end
        }
        return null
    }

    private fun readMp4BoxAt(channel: FileChannel, offset: Long, limit: Long): Pair<String, Mp4Box>? {
        if (offset + 8L > limit) return null
        val hdr = readChannelBytes(channel, offset, 8) ?: return null
        val size32 = readBeU32(hdr, 0)
        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
        val boxSize: Long
        val contentStart: Long
        when {
            size32 == 1L -> {
                if (offset + 16L > limit) return null
                val ext = readChannelBytes(channel, offset + 8L, 8) ?: return null
                val large = readBeS64(ext, 0)
                if (large < 16L) return null
                boxSize = large
                contentStart = offset + 16L
            }
            size32 == 0L -> {
                boxSize = limit - offset
                contentStart = offset + 8L
            }
            size32 < 8L -> return null
            else -> {
                boxSize = size32
                contentStart = offset + 8L
            }
        }
        val boxEnd = minOf(limit, offset + boxSize)
        if (contentStart > boxEnd) return null
        return type to Mp4Box(contentStart, boxEnd)
    }

    // ═══ WAV / RF64 Parser ═══
    private fun parseWav(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        var offset = 12L
        var steps = 0
        var sampleRate = 0
        var bitDepth = 0
        var byteRate = 0L
        var dataSize = 0L
        var rf64DataSize = 0L
        var isFloat = false
        var infoMeta: ExtractedAudioMetadata? = null
        var id3Meta: ExtractedAudioMetadata? = null

        while (offset + 8L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 8) ?: break
            val chunkId = String(hdr, 0, 4, Charsets.ISO_8859_1)
            var chunkSize = readLeU32(hdr, 4)
            val contentStart = offset + 8L

            if (chunkId == "data" && chunkSize == 0xFFFFFFFFL && rf64DataSize > 0L) {
                chunkSize = rf64DataSize
            }

            when (chunkId) {
                "ds64" -> if (chunkSize >= 24L && contentStart + 24L <= fileSize) {
                    val ds64 = readChannelBytes(channel, contentStart, 24)
                    if (ds64 != null) rf64DataSize = readLeS64(ds64, 8)
                }
                "fmt " -> if (chunkSize >= 16L && contentStart + 16L <= fileSize) {
                    val toRead = minOf(chunkSize, 40L).toInt()
                    val fmt = readChannelBytes(channel, contentStart, toRead)
                    if (fmt != null) {
                        val formatTag = readLeU16(fmt, 0)
                        sampleRate = readLeU32(fmt, 4).toInt()
                        byteRate = readLeU32(fmt, 8)
                        bitDepth = readLeU16(fmt, 14)
                        isFloat = formatTag == WAVE_FORMAT_IEEE_FLOAT
                        if (formatTag == WAVE_FORMAT_EXTENSIBLE && toRead >= 40) {
                            val validBits = readLeU16(fmt, 18)
                            if (validBits in 8..32) bitDepth = validBits
                            isFloat = readLeU16(fmt, 24) == WAVE_FORMAT_IEEE_FLOAT
                        }
                    }
                }
                "data" -> dataSize = chunkSize
                "LIST" -> if (chunkSize in 12L..262_144L && contentStart + chunkSize <= fileSize) {
                    val listType = readChannelBytes(channel, contentStart, 4)
                    if (listType != null && isAscii(listType, 0, "INFO")) {
                        val infoData = readChannelBytes(channel, contentStart + 4L, (chunkSize - 4L).toInt())
                        if (infoData != null) infoMeta = parseRiffInfoBlock(infoData)
                    }
                }
                "ID3 ", "id3 " -> if (chunkSize >= ID3_HEADER_BYTES && contentStart < fileSize) {
                    id3Meta = parseId3v2At(channel, contentStart, minOf(fileSize, contentStart + chunkSize))
                }
            }

            val padded = chunkSize + (chunkSize and 1L)
            val next = contentStart + padded
            if (next <= offset) break
            offset = next
        }

        val durationMs = if (byteRate > 0L && dataSize > 0L) (dataSize * 1_000L) / byteRate else 0L
        val bitrate = (byteRate * 8L).toInt().coerceAtLeast(0)
        val mergedTags = mergeMetadata(id3Meta, infoMeta)
        return mergedTags.copy(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = "WAV",
            isFloat = isFloat
        )
    }

    private fun parseRiffInfoBlock(data: ByteArray): ExtractedAudioMetadata {
        var offset = 0
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var year = 0
        var track = 0

        while (offset + 8 <= data.size) {
            val id = String(data, offset, 4, Charsets.ISO_8859_1)
            val size = readLeU32(data, offset + 4).toInt()
            val start = offset + 8
            if (size <= 0 || start + size > data.size) break
            val value = decodeSmartBytes(data, start, size).takeIf { it.isNotBlank() }
            if (value != null) {
                when (id) {
                    "INAM" -> title = value
                    "IART" -> artist = value
                    "IPRD" -> album = value
                    "IGNR" -> genre = value
                    "ICRD" -> year = parseFourDigitYear(value)
                    "ITRK", "IPRT" -> track = parseSlashNumber(value)
                }
            }
            offset = start + size + (size and 1)
        }

        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = track
        )
    }

    // ═══ AIFF Parser (delegates to AiffMetadataHelper) ═══
    private fun parseAiff(pathHint: String, channel: FileChannel): ExtractedAudioMetadata? {
        val meta = if (pathHint.isNotBlank()) AiffMetadataHelper.readMetadata(pathHint) else null
        if (meta != null) {
            val bitrate = if (meta.sampleRate > 0 && meta.bitDepth > 0 && meta.channels > 0) {
                meta.sampleRate * meta.bitDepth * meta.channels
            } else 0
            return ExtractedAudioMetadata(
                title = meta.title,
                artist = meta.artist,
                album = meta.album,
                albumArtist = meta.albumArtist,
                genre = meta.genre,
                year = meta.year,
                trackNumber = meta.trackNumber,
                discNumber = meta.discNumber,
                durationMs = meta.durationMs,
                sampleRate = meta.sampleRate,
                bitDepth = meta.bitDepth,
                bitrate = bitrate,
                codec = "AIFF",
                isFloat = meta.isFloat
            )
        }
        return null
    }

    // ═══ DSF (DSD Stream File) Parser ═══
    private fun parseDsf(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        if (fileSize < 80L) return null
        val dsdHdr = readChannelBytes(channel, 0L, 28) ?: return null
        val metaOffset = readLeS64(dsdHdr, 20)

        val fmtHdr = readChannelBytes(channel, 28L, 52) ?: return null
        if (!isAscii(fmtHdr, 0, "fmt ")) return null

        val channels = readLeS32(fmtHdr, 24)
        val sampleRate = readLeS32(fmtHdr, 28)
        // Sony DSF encodes 1-bit DSD with bitsPerSample = 1 (LSB-first) or 8 (MSB-first); audio depth is always 1-bit.
        val bitDepth = 1
        val sampleCount = readLeS64(fmtHdr, 36)
        val durationMs = if (sampleRate > 0 && sampleCount > 0L) (sampleCount * 1_000L) / sampleRate else 0L
        val bitrate = if (sampleRate > 0 && channels > 0) {
            (sampleRate.toLong() * channels.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else 0

        val id3Meta = if (metaOffset in 80L until fileSize) {
            parseId3v2At(channel, metaOffset, fileSize)
        } else null

        return (id3Meta ?: ExtractedAudioMetadata()).copy(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = resolveDsdCodecName(sampleRate)
        )
    }

    // ═══ DFF (DSDIFF) Parser ═══
    private fun parseDff(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        var offset = 16L
        var steps = 0
        var sampleRate = 0
        var channels = 0
        var dsdDataSize = 0L
        var diinTitle: String? = null
        var diinArtist: String? = null
        var id3Meta: ExtractedAudioMetadata? = null

        while (offset + 12L <= fileSize && steps < MAX_CONTAINER_WALK_STEPS) {
            steps++
            val hdr = readChannelBytes(channel, offset, 12) ?: break
            val chunkId = String(hdr, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = readBeS64(hdr, 4)
            val contentStart = offset + 12L
            if (chunkSize < 0L || contentStart > fileSize) break
            val chunkEnd = minOf(fileSize, contentStart + chunkSize)

            when (chunkId) {
                "PROP" -> if (contentStart + 4L <= chunkEnd) {
                    var subPos = contentStart + 4L // skip "SND "
                    var subSteps = 0
                    while (subPos + 12L <= chunkEnd && subSteps < 64) {
                        subSteps++
                        val sh = readChannelBytes(channel, subPos, 12) ?: break
                        val sid = String(sh, 0, 4, Charsets.ISO_8859_1)
                        val ssize = readBeS64(sh, 4)
                        val scStart = subPos + 12L
                        if (sid == "FS  " && ssize >= 4L && scStart + 4L <= chunkEnd) {
                            readChannelBytes(channel, scStart, 4)?.let { sampleRate = readBeU32(it, 0).toInt() }
                        } else if (sid == "CHNL" && ssize >= 2L && scStart + 2L <= chunkEnd) {
                            readChannelBytes(channel, scStart, 2)?.let { channels = readBeU16(it, 0) }
                        }
                        val nextSub = scStart + ssize + (ssize and 1L)
                        if (nextSub <= subPos) break
                        subPos = nextSub
                    }
                }
                "DSD " -> dsdDataSize = chunkSize
                "DIIN" -> {
                    var subPos = contentStart
                    var subSteps = 0
                    while (subPos + 12L <= chunkEnd && subSteps < 64) {
                        subSteps++
                        val sh = readChannelBytes(channel, subPos, 12) ?: break
                        val sid = String(sh, 0, 4, Charsets.ISO_8859_1)
                        val ssize = readBeS64(sh, 4)
                        val scStart = subPos + 12L
                        if ((sid == "DIAR" || sid == "DITI") && ssize in 5L..4096L && scStart + ssize <= chunkEnd) {
                            val bytes = readChannelBytes(channel, scStart, ssize.toInt())
                            if (bytes != null && bytes.size > 4) {
                                val textLen = readBeU32(bytes, 0).toInt().coerceIn(0, bytes.size - 4)
                                val text = decodeSmartBytes(bytes, 4, textLen).takeIf { it.isNotBlank() }
                                if (sid == "DIAR") diinArtist = text else diinTitle = text
                            }
                        }
                        val nextSub = scStart + ssize + (ssize and 1L)
                        if (nextSub <= subPos) break
                        subPos = nextSub
                    }
                }
                "ID3 " -> if (chunkSize >= ID3_HEADER_BYTES) {
                    id3Meta = parseId3v2At(channel, contentStart, chunkEnd)
                }
            }

            val next = contentStart + chunkSize + (chunkSize and 1L)
            if (next <= offset) break
            offset = next
        }

        val durationMs = if (sampleRate > 0 && channels > 0 && dsdDataSize > 0L) {
            val totalSamples = (dsdDataSize * 8L) / channels
            (totalSamples * 1_000L) / sampleRate
        } else 0L
        val bitrate = if (sampleRate > 0 && channels > 0) sampleRate * channels else 0

        val base = id3Meta ?: ExtractedAudioMetadata()
        return base.copy(
            title = base.title ?: diinTitle,
            artist = base.artist ?: diinArtist,
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = 1,
            bitrate = bitrate,
            codec = resolveDsdCodecName(sampleRate)
        )
    }

    private fun resolveDsdCodecName(sampleRate: Int): String = when {
        sampleRate >= 22_000_000 -> "DSD512"
        sampleRate >= 11_000_000 -> "DSD256"
        sampleRate >= 5_500_000 -> "DSD128"
        sampleRate >= 2_800_000 -> "DSD64"
        else -> "DSD"
    }

    // ═══ WavPack (.wv) Parser ═══
    private fun parseWavPack(
        channel: FileChannel,
        fileSize: Long,
        startOffset: Long = 0L
    ): ExtractedAudioMetadata? {
        if (startOffset + 32L > fileSize) return null
        val hdr = readChannelBytes(channel, startOffset, 32) ?: return null
        if (!isAscii(hdr, 0, "wvpk")) return null

        val totalSamples = readLeU32(hdr, 12)
        val flags = readLeS32(hdr, 24)
        val bytesPerSample = (flags and 0x03) + 1
        val bitDepth = bytesPerSample * 8
        val isFloat = (flags and 0x80) != 0
        val srIdx = (flags ushr 23) and 0x0F
        val sampleRate = WAVPACK_SAMPLE_RATES.getOrElse(srIdx) { 0 }
        val durationMs = if (sampleRate > 0 && totalSamples > 0L && totalSamples != 0xFFFFFFFFL) {
            (totalSamples * 1_000L) / sampleRate
        } else 0L
        val bitrate = if (durationMs > 0L) ((fileSize * 8_000L) / durationMs).toInt() else 0

        val apeTags = parseApeV2Footer(channel, fileSize) ?: ExtractedAudioMetadata()
        return apeTags.copy(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = "WV",
            isFloat = isFloat
        )
    }

    // ═══ Monkey's Audio (.ape) Parser ═══
    private fun parseMonkeysAudio(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        if (fileSize < 76L) return null
        val head = readChannelBytes(channel, 0L, 76) ?: return null
        if (!isAscii(head, 0, "MAC ")) return null

        val version = readLeU16(head, 4)
        var sampleRate = 0
        var bitDepth = 16
        var durationMs = 0L

        if (version >= 3980) {
            val descriptorLen = readLeU32(head, 8).toInt().coerceIn(52, 256)
            val hdr = if (descriptorLen + 24 <= head.size) {
                head.copyOfRange(descriptorLen, descriptorLen + 24)
            } else {
                readChannelBytes(channel, descriptorLen.toLong(), 24)
            }
            if (hdr != null && hdr.size >= 24) {
                val blocksPerFrame = readLeU32(hdr, 4)
                val finalFrameBlocks = readLeU32(hdr, 8)
                val totalFrames = readLeU32(hdr, 12)
                bitDepth = readLeU16(hdr, 16)
                sampleRate = readLeU32(hdr, 20).toInt()
                if (sampleRate > 0 && totalFrames > 0L) {
                    val totalSamples = (totalFrames - 1L) * blocksPerFrame + finalFrameBlocks
                    durationMs = (totalSamples * 1_000L) / sampleRate
                }
            }
        } else {
            val formatFlags = readLeU16(head, 8)
            bitDepth = when {
                (formatFlags and 0x01) != 0 -> 8
                (formatFlags and 0x08) != 0 -> 24
                else -> 16
            }
            sampleRate = readLeU32(head, 12).toInt()
            val totalFrames = readLeU32(head, 24)
            val finalFrameBlocks = readLeU32(head, 28)
            val blocksPerFrame = if (version >= 3950) 73728L * 4L else 73728L
            if (sampleRate > 0 && totalFrames > 0L) {
                val totalSamples = (totalFrames - 1L) * blocksPerFrame + finalFrameBlocks
                durationMs = (totalSamples * 1_000L) / sampleRate
            }
        }

        val bitrate = if (durationMs > 0L) ((fileSize * 8_000L) / durationMs).toInt() else 0
        val apeTags = parseApeV2Footer(channel, fileSize) ?: ExtractedAudioMetadata()
        return apeTags.copy(
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bitrate = bitrate,
            codec = "APE"
        )
    }

    // ═══ APEv2 Footer Parser (shared by .wv, .ape, and APE-tagged .mp3) ═══
    private fun parseApeV2Footer(channel: FileChannel, fileSize: Long): ExtractedAudioMetadata? {
        return parseApeV2FooterWithSize(channel, fileSize)?.first
    }

    private fun parseApeV2FooterWithSize(
        channel: FileChannel,
        fileSize: Long
    ): Pair<ExtractedAudioMetadata, Long>? {
        val candidateOffsets = longArrayOf(fileSize - 32L, fileSize - 160L)
        var footerOffset = -1L
        var footerBytes: ByteArray? = null

        for (off in candidateOffsets) {
            if (off < 0L) continue
            val buf = readChannelBytes(channel, off, 32) ?: continue
            if (isAscii(buf, 0, "APETAGEX")) {
                footerOffset = off
                footerBytes = buf
                break
            }
        }
        val footer = footerBytes ?: return null

        val tagSize = readLeU32(footer, 12) // includes 32-byte footer, excludes optional header
        val itemCount = readLeU32(footer, 16).coerceAtMost(256L).toInt()
        val footerFlags = readLeS32(footer, 20)
        val hasHeader = (footerFlags and (1 shl 31)) != 0
        if (tagSize <= 32L || tagSize > MAX_APEV2_TAG_BYTES) return null

        val bodyLen = (tagSize - 32L).toInt()
        val bodyStart = footerOffset - bodyLen
        if (bodyStart < 0L) return null
        val body = readChannelBytes(channel, bodyStart, bodyLen) ?: return null

        var offset = 0
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var year = 0
        var trackNumber = 0
        var discNumber = 0

        for (i in 0 until itemCount) {
            if (offset + 8 > body.size) break
            val valLen = readLeS32(body, offset)
            val flags = readLeS32(body, offset + 4)
            offset += 8

            var keyEnd = offset
            while (keyEnd < body.size && body[keyEnd] != 0.toByte()) keyEnd++
            if (keyEnd >= body.size) break
            val key = String(body, offset, keyEnd - offset, Charsets.US_ASCII).trim().uppercase(Locale.ROOT)
            offset = keyEnd + 1

            if (valLen < 0 || offset + valLen > body.size) break
            val isBinary = ((flags ushr 1) and 0x03) == 1
            if (!isBinary && valLen in 1..MAX_TEXT_FRAME_BYTES) {
                val value = String(body, offset, valLen, Charsets.UTF_8)
                    .replace('\u0000', '/')
                    .trim()
                if (value.isNotEmpty()) {
                    when (key) {
                        "TITLE" -> if (title == null) title = value
                        "ARTIST" -> if (artist == null) artist = value
                        "ALBUM" -> if (album == null) album = value
                        "ALBUM ARTIST", "ALBUMARTIST" -> if (albumArtist == null) albumArtist = value
                        "GENRE" -> if (genre == null) genre = value
                        "YEAR", "DATE" -> if (year == 0) year = parseFourDigitYear(value)
                        "TRACK" -> if (trackNumber == 0) trackNumber = parseSlashNumber(value)
                        "DISC" -> if (discNumber == 0) discNumber = parseSlashNumber(value)
                    }
                }
            }
            offset += valLen
        }

        val totalApeBytes = tagSize + if (hasHeader) 32L else 0L
        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber
        ) to totalApeBytes
    }

    // ═══ MediaMetadataRetriever Fallback ═══
    private fun extractWithMmr(
        context: Context,
        path: String,
        contentUri: Uri?,
        ext: String
    ): ExtractedAudioMetadata? {
        val mmr = MediaMetadataRetriever()
        return try {
            var opened = false
            if (path.isNotBlank()) {
                val f = File(path)
                if (f.exists() && f.isFile && f.length() > 0L && f.canRead()) {
                    runCatching {
                        mmr.setDataSource(path)
                        opened = true
                    }
                }
            }
            if (!opened && contentUri != null && contentUri != Uri.EMPTY) {
                mmr.setDataSource(context, contentUri)
                opened = true
            }
            if (!opened) return null

            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
            val albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()?.takeIf { it.isNotEmpty() }
            val genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.trim()?.takeIf { it.isNotEmpty() }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val trackNumber = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.let { parseSlashNumber(it) } ?: 0
            val discNumber = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                ?.let { parseSlashNumber(it) } ?: 0
            val year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.let { parseFourDigitYear(it) }
                ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.let { parseFourDigitYear(it) }
                ?: 0
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()

            ExtractedAudioMetadata(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                genre = genre,
                year = year,
                trackNumber = trackNumber,
                discNumber = discNumber,
                durationMs = durationMs,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                bitrate = bitrate,
                codec = resolveCodecFromMimeOrExt(mime, ext)
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    // ═══ Merge & Encoding Helpers ═══
    private fun mergeMetadata(
        primary: ExtractedAudioMetadata?,
        fallback: ExtractedAudioMetadata?
    ): ExtractedAudioMetadata {
        if (primary == null) return fallback ?: ExtractedAudioMetadata()
        if (fallback == null) return primary
        val resolvedDuration = when {
            primary.durationMs > 0L && (!primary.durationEstimated || fallback.durationMs <= 0L) -> primary.durationMs
            fallback.durationMs > 0L -> fallback.durationMs
            else -> primary.durationMs
        }
        val resolvedDurationEstimated = if (resolvedDuration == fallback.durationMs && fallback.durationMs > 0L) {
            fallback.durationEstimated
        } else {
            primary.durationEstimated
        }
        return ExtractedAudioMetadata(
            title = primary.title ?: fallback.title,
            artist = primary.artist ?: fallback.artist,
            album = primary.album ?: fallback.album,
            albumArtist = primary.albumArtist ?: fallback.albumArtist,
            genre = primary.genre ?: fallback.genre,
            year = if (primary.year > 0) primary.year else fallback.year,
            trackNumber = if (primary.trackNumber > 0) primary.trackNumber else fallback.trackNumber,
            discNumber = if (primary.discNumber > 0) primary.discNumber else fallback.discNumber,
            durationMs = resolvedDuration,
            durationEstimated = resolvedDurationEstimated,
            sampleRate = if (primary.sampleRate > 0) primary.sampleRate else fallback.sampleRate,
            bitDepth = if (primary.bitDepth > 0) primary.bitDepth else fallback.bitDepth,
            bitrate = if (primary.bitrate > 0) primary.bitrate else fallback.bitrate,
            codec = primary.codec.ifBlank { fallback.codec },
            isFloat = primary.isFloat || fallback.isFloat
        )
    }

    private fun scoreId3v24NextBoundary(
        nextOffset: Long,
        effectiveEnd: Long,
        readSlice: (Long, Int) -> ByteArray?
    ): Int {
        if (nextOffset == effectiveEnd) return 2
        if (nextOffset < 0L || nextOffset > effectiveEnd) return 0
        val remaining = (effectiveEnd - nextOffset).toInt()
        if (remaining < 4) {
            val rem = readSlice(nextOffset, remaining) ?: return 0
            return if (rem.all { it == 0.toByte() }) 1 else 0
        }
        val probe = readSlice(nextOffset, 4) ?: return 0
        if (probe[0] == 0.toByte()) {
            // Only score as trailing tag padding if all bytes in the lookahead window are zero
            val padWindowLen = minOf(remaining, 64)
            val padSlice = readSlice(nextOffset, padWindowLen) ?: return 0
            return if (padSlice.all { it == 0.toByte() }) 1 else 0
        }
        for (b in probe) {
            val c = b.toInt() and 0xFF
            val isUpperAscii = c in 'A'.code..'Z'.code
            val isDigit = c in '0'.code..'9'.code
            if (!isUpperAscii && !isDigit) return 0
        }
        return 2
    }

    /**
     * Removes ID3v2 unsynchronization (`0xFF 0x00` -> `0xFF`) without allocating when absent.
     */
    private fun removeId3Unsynchronization(src: ByteArray): ByteArray {
        var unsyncCount = 0
        for (i in 0 until src.size - 1) {
            if (src[i] == 0xFF.toByte() && src[i + 1] == 0x00.toByte()) {
                unsyncCount++
            }
        }
        if (unsyncCount == 0) return src
        val dst = ByteArray(src.size - unsyncCount)
        var outIdx = 0
        var i = 0
        while (i < src.size) {
            dst[outIdx++] = src[i]
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return dst
    }

    private fun decodeId3Text(frameBytes: ByteArray): String {
        if (frameBytes.size <= 1) return ""
        val encoding = frameBytes[0].toInt() and 0xFF
        val payloadLen = frameBytes.size - 1
        val raw = when (encoding) {
            1 -> decodeUtf16Smart(frameBytes, 1, payloadLen)
            2 -> String(frameBytes, 1, payloadLen, Charsets.UTF_16BE)
            3 -> String(frameBytes, 1, payloadLen, Charsets.UTF_8)
            else -> decodeSmartBytesMulti(frameBytes, 1, payloadLen)
        }
        val parts = raw.split('\u0000')
            .map { it.replace("\uFEFF", "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return parts.joinToString(", ")
    }

    /**
     * Decodes ID3v2 UTF-16 (`encoding == 1`) with explicit BOM detection and automatic LE/BE
     * heuristic fallback when broken taggers omit the BOM (preventing CJK mojibake on UTF-16LE).
     */
    private fun decodeUtf16Smart(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = if (length >= 2) bytes[offset + 1].toInt() and 0xFF else 0
        return when {
            length >= 2 && b0 == 0xFF && b1 == 0xFE -> {
                val evenLen = (length - 2) and 0x7FFFFFFE
                if (evenLen <= 0) "" else String(bytes, offset + 2, evenLen, Charsets.UTF_16LE)
            }
            length >= 2 && b0 == 0xFE && b1 == 0xFF -> {
                val evenLen = (length - 2) and 0x7FFFFFFE
                if (evenLen <= 0) "" else String(bytes, offset + 2, evenLen, Charsets.UTF_16BE)
            }
            else -> {
                val evenLen = length and 0x7FFFFFFE
                if (evenLen <= 0) return ""
                var evenZeros = 0
                var oddZeros = 0
                var i = 0
                while (i + 1 < evenLen) {
                    if (bytes[offset + i] == 0.toByte()) evenZeros++
                    if (bytes[offset + i + 1] == 0.toByte()) oddZeros++
                    i += 2
                }
                val charset = if (oddZeros >= evenZeros) Charsets.UTF_16LE else Charsets.UTF_16BE
                String(bytes, offset, evenLen, charset)
            }
        }
    }

    private fun decodeSmartBytesMulti(bytes: ByteArray, offset: Int, length: Int): String {
        val end = minOf(bytes.size, offset + length)
        var start = offset
        val sb = StringBuilder()
        while (start < end) {
            var segEnd = start
            while (segEnd < end && bytes[segEnd] != 0.toByte()) segEnd++
            if (segEnd > start) {
                val seg = decodeSmartBytes(bytes, start, segEnd - start)
                if (seg.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\u0000')
                    sb.append(seg)
                }
            }
            start = segEnd + 1
        }
        return sb.toString()
    }

    /**
     * Decodes a byte slice as UTF-8 whenever it contains valid multi-byte UTF-8 sequences (fixing
     * broken Vietnamese ID3v2 tags mistakenly flagged as ISO-8859-1), otherwise falls back to
     * ISO-8859-1.
     */
    private fun decodeSmartBytes(bytes: ByteArray, offset: Int, length: Int): String {
        val end = minOf(bytes.size, offset + length)
        var actualEnd = offset
        while (actualEnd < end && bytes[actualEnd] != 0.toByte()) actualEnd++
        val sliceLen = actualEnd - offset
        if (sliceLen <= 0) return ""

        var hasHighByte = false
        for (i in offset until actualEnd) {
            if ((bytes[i].toInt() and 0x80) != 0) {
                hasHighByte = true
                break
            }
        }
        if (!hasHighByte) {
            return String(bytes, offset, sliceLen, Charsets.US_ASCII).trim()
        }

        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            utf8Decoder.decode(ByteBuffer.wrap(bytes, offset, sliceLen)).toString().trim()
        }.getOrElse {
            String(bytes, offset, sliceLen, Charset.forName("ISO-8859-1")).trim()
        }
    }

    private fun cleanId3Genre(raw: String): String {
        val trimmed = raw.trim()
        val match = ID3_GENRE_NUMERIC_REGEX.matchEntire(trimmed)
        if (match != null) {
            val rest = match.groupValues[2].trim()
            if (rest.isNotEmpty()) return rest
            val idx = match.groupValues[1].toIntOrNull()
            if (idx != null) {
                return ID3V1_GENRES.getOrNull(idx) ?: trimmed
            }
        }
        trimmed.toIntOrNull()?.let { numericIdx ->
            ID3V1_GENRES.getOrNull(numericIdx)?.let { return it }
        }
        return trimmed
    }

    private fun parseSlashNumber(raw: String): Int {
        return raw.substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun parseFourDigitYear(raw: String): Int {
        val match = YEAR_REGEX.find(raw.trim()) ?: return 0
        val year = match.groupValues[1].toIntOrNull() ?: return 0
        return if (year in MIN_VALID_YEAR..MAX_VALID_YEAR) year else 0
    }

    private fun resolveCodecFromMimeOrExt(mime: String, ext: String): String = when {
        mime.contains("flac", true) || ext == "flac" -> "FLAC"
        mime.contains("alac", true) || ext == "alac" -> "ALAC"
        mime.contains("opus", true) || ext == "opus" -> "OPUS"
        mime.contains("vorbis", true) || ext == "ogg" -> "OGG"
        mime.contains("mpeg", true) || ext == "mp3" -> "MP3"
        mime.contains("aac", true) || mime.contains("mp4", true) || ext == "m4a" || ext == "aac" -> "AAC"
        mime.contains("wav", true) || ext == "wav" -> "WAV"
        mime.contains("aiff", true) || ext == "aiff" || ext == "aif" || ext == "aifc" -> "AIFF"
        mime.contains("dsd", true) || mime.contains("dsf", true) || mime.contains("dff", true) ||
            ext == "dsf" || ext == "dff" -> "DSD"
        ext.isNotBlank() -> ext.uppercase(Locale.ROOT)
        else -> ""
    }

    // ═══ Binary I/O Helpers ═══
    private fun readChannelBytes(channel: FileChannel, offset: Long, length: Int): ByteArray? {
        if (offset < 0L || length <= 0) return null
        val out = ByteArray(length)
        val bb = ByteBuffer.wrap(out)
        var pos = offset
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n <= 0) return null
            pos += n
        }
        return out
    }

    private fun isAscii(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
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

    private fun readSynchsafeInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or
            ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or
            (b[o + 3].toInt() and 0x7F)

    private fun readBeU16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readBeS32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun readBeU32(b: ByteArray, o: Int): Long =
        readBeS32(b, o).toLong() and 0xFFFFFFFFL

    private fun readBeS64(b: ByteArray, o: Int): Long =
        ByteBuffer.wrap(b, o, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun readLeU16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readLeS32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun readLeU32(b: ByteArray, o: Int): Long =
        readLeS32(b, o).toLong() and 0xFFFFFFFFL

    private fun readLeS64(b: ByteArray, o: Int): Long =
        ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
