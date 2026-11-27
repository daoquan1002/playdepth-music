/**
 * AiffExtractor — Media3 Extractor for AIFF, AIF, and uncompressed/float AIFF-C containers.
 *
 * @remarks
 * Media3's `DefaultExtractorsFactory` lacks EA IFF-85 AIFF demuxing. This extractor parses `FORM`
 * (`AIFF`/`AIFC`), walks `COMM` (80-bit IEEE extended sample rate) and `SSND` chunks while obeying
 * 2-byte IFF padding, stops cleanly at the end of `SSND` so trailing `ID3 ` chunks and cover art
 * never leak as static, swaps big-endian PCM samples into standard little-endian `MimeTypes.AUDIO_RAW`
 * for native compatibility with both `DefaultAudioSink` and `UsbDacAudioSink`, and exposes a
 * frame-aligned `SeekMap` so 24-bit and multi-channel seeks remain phase-aligned.
 *
 * @see WavFloatFixExtractorsFactory
 * @see com.quandot.musicplayer.data.AiffMetadataHelper
 * @see UsbDacAudioSink
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

// Internal modules
import com.quandot.musicplayer.data.AiffMetadataHelper

// ═══ Constants ═══
private const val STATE_READING_HEADER = 0
private const val STATE_SCANNING_CHUNKS = 1
private const val STATE_READING_SAMPLES = 2

private const val IFF_HEADER_BYTES = 12
private const val CHUNK_HEADER_BYTES = 8
private const val COMM_MIN_BYTES = 18
private const val COMM_AIFC_MIN_BYTES = 22
private const val MAX_CHUNKS_WALKED = 512
private const val TARGET_READ_BYTES = 32 * 1024

// ═══ Extractor Implementation ═══
@OptIn(UnstableApi::class)
class AiffExtractor : Extractor {

    // ─── Pipeline State ───
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var state = STATE_READING_HEADER
    private var formType = ""
    private var commParsed = false
    private var ssndParsed = false
    private var formatAndSeekMapEmitted = false

    private var channels = 0
    private var numSampleFrames = 0L
    private var sampleSize = 0
    private var sampleRate = 0
    private var isBigEndian = true
    private var isFloat = false
    private var isSigned8BitTo16 = false
    private var inputBytesPerSample = 0
    private var blockAlign = 0
    private var outputPcmEncoding = C.ENCODING_PCM_16BIT

    private var dataStartPosition = 0L
    private var ssndDataSize = 0L
    private var totalFrames = 0L
    private var streamDurationUs = C.TIME_UNSET
    private var effectiveDataSize = 0L
    private var dataEndPosition = 0L
    private var currentFrameIndex = 0L

    private val headerBuf = ByteArray(IFF_HEADER_BYTES)
    private val chunkHdrBuf = ByteArray(CHUNK_HEADER_BYTES)
    private val readBuffer = ByteArray(TARGET_READ_BYTES)
    private val expandedBuffer = ByteArray(TARGET_READ_BYTES * 2)
    private val parsableData = ParsableByteArray()
    private var pendingBytes = 0

    // ═══ Extractor Lifecycle ═══
    override fun sniff(input: ExtractorInput): Boolean {
        val magic = ByteArray(IFF_HEADER_BYTES)
        if (!input.peekFully(magic, 0, IFF_HEADER_BYTES, true)) return false
        val containerId = String(magic, 0, 4, Charsets.ISO_8859_1)
        val type = String(magic, 8, 4, Charsets.ISO_8859_1)
        return containerId == "FORM" && (type == "AIFF" || type == "AIFC")
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (state == STATE_READING_HEADER) {
            if (!input.readFully(headerBuf, 0, IFF_HEADER_BYTES, true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            val containerId = String(headerBuf, 0, 4, Charsets.ISO_8859_1)
            formType = String(headerBuf, 8, 4, Charsets.ISO_8859_1)
            if (containerId != "FORM" || (formType != "AIFF" && formType != "AIFC")) {
                throw ParserException.createForMalformedContainer("Invalid AIFF container header", null)
            }
            state = STATE_SCANNING_CHUNKS
        }

        if (state == STATE_SCANNING_CHUNKS) {
            var chunksWalked = 0
            while (chunksWalked < MAX_CHUNKS_WALKED && !(commParsed && ssndParsed)) {
                chunksWalked++
                if (!input.readFully(chunkHdrBuf, 0, CHUNK_HEADER_BYTES, true)) {
                    break
                }
                val chunkId = String(chunkHdrBuf, 0, 4, Charsets.ISO_8859_1)
                val chunkSize = readBeU32(chunkHdrBuf, 4)
                val paddingByte = chunkSize and 1L

                when (chunkId) {
                    "COMM" -> {
                        if (chunkSize < COMM_MIN_BYTES || chunkSize > 4096L) {
                            throw ParserException.createForMalformedContainer("Invalid AIFF COMM chunk size: $chunkSize", null)
                        }
                        val comm = ByteArray(chunkSize.toInt())
                        input.readFully(comm, 0, comm.size)
                        if (paddingByte != 0L) skipFullyLarge(input, paddingByte)
                        parseCommChunk(comm)
                        commParsed = true
                    }
                    "SSND" -> {
                        if (chunkSize < 8L) {
                            throw ParserException.createForMalformedContainer("Invalid AIFF SSND chunk size: $chunkSize", null)
                        }
                        val ssndHdr = ByteArray(8)
                        input.readFully(ssndHdr, 0, 8)
                        val ssndOffset = readBeU32(ssndHdr, 0)
                        if (ssndOffset > 0L) skipFullyLarge(input, ssndOffset)
                        dataStartPosition = input.position
                        ssndDataSize = (chunkSize - 8L - ssndOffset).coerceAtLeast(0L)
                        ssndParsed = true

                        if (!commParsed) {
                            skipFullyLarge(input, ssndDataSize + paddingByte)
                        }
                    }
                    else -> {
                        val skipTotal = chunkSize + paddingByte
                        if (skipTotal > 0L) {
                            skipFullyLarge(input, skipTotal)
                        }
                    }
                }
            }

            if (!commParsed || !ssndParsed) {
                return Extractor.RESULT_END_OF_INPUT
            }

            finalizeAudioGeometry()
            state = STATE_READING_SAMPLES
            currentFrameIndex = 0L
            pendingBytes = 0

            if (input.position != dataStartPosition) {
                seekPosition.position = dataStartPosition
                return Extractor.RESULT_SEEK
            }
        }

        if (!formatAndSeekMapEmitted) {
            emitFormatAndSeekMap()
            formatAndSeekMapEmitted = true
        }

        if (input.position < dataStartPosition) {
            seekPosition.position = dataStartPosition
            return Extractor.RESULT_SEEK
        }

        return readAudioSamples(input)
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingBytes = 0
        if (!commParsed || !ssndParsed) {
            state = STATE_READING_HEADER
            currentFrameIndex = 0L
            return
        }
        state = STATE_READING_SAMPLES
        val clampedOffset = (position - dataStartPosition).coerceIn(0L, effectiveDataSize)
        currentFrameIndex = if (blockAlign > 0) clampedOffset / blockAlign else 0L
    }

    override fun release() {
        // Stateless with respect to OS handles; owned by ExtractorInput lifecycle
    }

    // ═══ Chunk & Sample Processing ═══
    private fun parseCommChunk(comm: ByteArray) {
        channels = readBeU16(comm, 0)
        numSampleFrames = readBeU32(comm, 2)
        sampleSize = readBeU16(comm, 6)
        sampleRate = AiffMetadataHelper.decodeIeee80BitFloat(comm, 8)

        if (channels !in 1..8 || sampleRate <= 0) {
            throw ParserException.createForMalformedContainer(
                "Unsupported AIFF stream parameters: ch=$channels, rate=$sampleRate",
                null
            )
        }

        isBigEndian = true
        isFloat = false
        if (formType == "AIFC" && comm.size >= COMM_AIFC_MIN_BYTES) {
            val compression = String(comm, 18, 4, Charsets.ISO_8859_1)
            when (compression) {
                "NONE" -> isBigEndian = true
                "sowt" -> isBigEndian = false
                "fl32", "FL32" -> {
                    isBigEndian = true
                    isFloat = true
                }
                else -> throw ParserException.createForUnsupportedContainerFeature(
                    "Unsupported AIFF-C compression type: $compression"
                )
            }
        }

        inputBytesPerSample = (sampleSize + 7) / 8
        isSigned8BitTo16 = (inputBytesPerSample == 1 && !isFloat)

        outputPcmEncoding = when {
            isFloat && inputBytesPerSample == 4 -> C.ENCODING_PCM_FLOAT
            inputBytesPerSample == 1 -> C.ENCODING_PCM_16BIT
            inputBytesPerSample == 2 -> C.ENCODING_PCM_16BIT
            inputBytesPerSample == 3 -> C.ENCODING_PCM_24BIT
            inputBytesPerSample == 4 -> C.ENCODING_PCM_32BIT
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported AIFF sample bit depth: $sampleSize"
            )
        }
        blockAlign = channels * inputBytesPerSample
    }

    private fun finalizeAudioGeometry() {
        val framesInSsnd = if (blockAlign > 0) ssndDataSize / blockAlign else 0L
        totalFrames = if (numSampleFrames > 0L) minOf(numSampleFrames, framesInSsnd) else framesInSsnd
        effectiveDataSize = totalFrames * blockAlign
        dataEndPosition = dataStartPosition + effectiveDataSize
        streamDurationUs = if (totalFrames > 0L && sampleRate > 0) {
            (totalFrames * C.MICROS_PER_SECOND) / sampleRate
        } else {
            C.TIME_UNSET
        }
    }

    private fun emitFormatAndSeekMap() {
        val output = extractorOutput ?: return
        val track = trackOutput ?: return
        val outputBytesPerSample = if (isSigned8BitTo16) 2 else inputBytesPerSample
        val bitrate = sampleRate * channels * sampleSize

        val formatBuilder = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setPcmEncoding(outputPcmEncoding)
            .setAverageBitrate(bitrate)
            .setPeakBitrate(bitrate)
            .setMaxInputSize((readBuffer.size / blockAlign.coerceAtLeast(1)) * channels * outputBytesPerSample)
        if (outputPcmEncoding == C.ENCODING_PCM_FLOAT) {
            formatBuilder.setCustomData(RawPcmFloatFormatMarker)
        }
        val format = formatBuilder.build()

        track.format(format)
        output.endTracks()
        output.seekMap(
            AiffSeekMap(
                streamDurationUs = streamDurationUs,
                totalFrames = totalFrames,
                sampleRate = sampleRate,
                dataStartPosition = dataStartPosition,
                blockAlign = blockAlign
            )
        )
    }

    private fun readAudioSamples(input: ExtractorInput): Int {
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val remainingInChunk = (dataEndPosition - input.position).coerceAtLeast(0L)
        if (remainingInChunk == 0L && pendingBytes < blockAlign) {
            return Extractor.RESULT_END_OF_INPUT
        }

        if (remainingInChunk > 0L) {
            val maxCapacity = readBuffer.size - pendingBytes
            val toRead = minOf(maxCapacity.toLong(), remainingInChunk).toInt()
            if (toRead > 0) {
                val read = input.read(readBuffer, pendingBytes, toRead)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (pendingBytes < blockAlign) return Extractor.RESULT_END_OF_INPUT
                } else {
                    pendingBytes += read
                }
            }
        }

        val completeFrames = pendingBytes / blockAlign
        if (completeFrames <= 0) {
            return if (remainingInChunk == 0L) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
        }

        val completeInputBytes = completeFrames * blockAlign
        val sampleTimeUs = (currentFrameIndex * C.MICROS_PER_SECOND) / sampleRate
        currentFrameIndex += completeFrames

        if (isSigned8BitTo16) {
            val outBytes = completeInputBytes * 2
            var dst = 0
            for (i in 0 until completeInputBytes) {
                expandedBuffer[dst++] = 0
                expandedBuffer[dst++] = readBuffer[i]
            }
            parsableData.reset(expandedBuffer, outBytes)
            track.sampleData(parsableData, outBytes)
            track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, outBytes, 0, null)
        } else {
            if (isBigEndian) {
                swapBigEndianToLittleEndian(readBuffer, completeInputBytes, inputBytesPerSample)
            }
            parsableData.reset(readBuffer, completeInputBytes)
            track.sampleData(parsableData, completeInputBytes)
            track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, completeInputBytes, 0, null)
        }

        val leftover = pendingBytes - completeInputBytes
        if (leftover > 0) {
            System.arraycopy(readBuffer, completeInputBytes, readBuffer, 0, leftover)
        }
        pendingBytes = leftover

        return Extractor.RESULT_CONTINUE
    }

    private fun swapBigEndianToLittleEndian(buffer: ByteArray, length: Int, bytesPerSample: Int) {
        when (bytesPerSample) {
            2 -> {
                var i = 0
                while (i + 1 < length) {
                    val tmp = buffer[i]
                    buffer[i] = buffer[i + 1]
                    buffer[i + 1] = tmp
                    i += 2
                }
            }
            3 -> {
                var i = 0
                while (i + 2 < length) {
                    val tmp = buffer[i]
                    buffer[i] = buffer[i + 2]
                    buffer[i + 2] = tmp
                    i += 3
                }
            }
            4 -> {
                var i = 0
                while (i + 3 < length) {
                    val b0 = buffer[i]
                    val b1 = buffer[i + 1]
                    buffer[i] = buffer[i + 3]
                    buffer[i + 1] = buffer[i + 2]
                    buffer[i + 2] = b1
                    buffer[i + 3] = b0
                    i += 4
                }
            }
        }
    }

    private fun skipFullyLarge(input: ExtractorInput, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val step = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            remaining -= step
        }
    }

    // ═══ SeekMap ═══
    private class AiffSeekMap(
        private val streamDurationUs: Long,
        private val totalFrames: Long,
        private val sampleRate: Int,
        private val dataStartPosition: Long,
        private val blockAlign: Int
    ) : SeekMap {
        override fun isSeekable(): Boolean = totalFrames > 0L && sampleRate > 0

        override fun getDurationUs(): Long = streamDurationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (totalFrames <= 0L || sampleRate <= 0) {
                return SeekMap.SeekPoints(SeekPoint(0L, dataStartPosition))
            }
            val clampedTimeUs = timeUs.coerceIn(0L, if (streamDurationUs != C.TIME_UNSET) streamDurationUs else 0L)
            val targetFrame = ((clampedTimeUs * sampleRate) / C.MICROS_PER_SECOND)
                .coerceIn(0L, maxOf(0L, totalFrames - 1L))
            val bytePos = dataStartPosition + (targetFrame * blockAlign)
            val actualTimeUs = (targetFrame * C.MICROS_PER_SECOND) / sampleRate
            return SeekMap.SeekPoints(SeekPoint(actualTimeUs, bytePos))
        }
    }

    // ═══ Binary Helpers ═══
    private fun readBeU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readBeU32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }
}
