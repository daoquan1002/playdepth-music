/**
 * DsdExtractor — Media3 Extractor for raw 1-bit DSD audio in DSF and DFF (DSDIFF) containers.
 *
 * @remarks
 * Media3's `DefaultExtractorsFactory` and bundled FFmpeg decoder do not demux or decode `.dsf` or
 * `.dff` streams. This extractor parses Sony DSF (`DSD ` / `fmt ` / `data`) and Philips DSDIFF
 * (`FRM8` / `DSD ` / `PROP` / `DSD `) containers and normalizes both into chronological MSB-first,
 * byte-interleaved `DSD_U8` (`audio/dsd`) frames expected by [UsbDacAudioSink] and the native
 * `AudioDspPipeline` (`AudioInputEncoding::DSD_U8`).
 *
 * For DSF, planar per-channel blocks (`blockSizePerChannel`, typically 4096 bytes) are interleaved
 * per byte, LSB-first bits (`bitsPerSample == 1`) are bit-reversed via a 256-entry LUT, and
 * emission halts strictly at `sampleCount / 8` byte-frames and before the trailing `metadataOffset`
 * ID3v2 chunk so zero-padded tail bytes never produce end-of-track DC clicks or pops. For DFF,
 * already MSB-first byte-interleaved `DSD ` payloads stream directly while rejecting unsupported
 * `DST ` lossless compression explicitly.
 *
 * @see WavFloatFixExtractorsFactory
 * @see UsbDacAudioSink
 * @see UsbDacNative
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

// ═══ Constants ═══
const val MIME_AUDIO_DSD = "audio/dsd"
const val DSD64_SAMPLE_RATE_HZ = 2_822_400
const val DSD128_SAMPLE_RATE_HZ = 5_644_800
const val DSD256_SAMPLE_RATE_HZ = 11_289_600
const val DSD512_SAMPLE_RATE_HZ = 22_579_200

private const val DSD_U8_CODEC_HINT = "dsd-u8"

private const val STATE_READING_HEADER = 0
private const val STATE_READING_SAMPLES = 1

private const val CONTAINER_UNKNOWN = 0
private const val CONTAINER_DSF = 1
private const val CONTAINER_DFF = 2

private const val SNIFF_BYTES = 16
private const val DSF_DSD_CHUNK_MIN_BYTES = 28L
private const val DSF_FMT_CHUNK_MIN_BYTES = 52L
private const val DSF_DATA_HEADER_BYTES = 12L
private const val DSF_MAX_HEADER_CHUNK_BYTES = 4096L
private const val DSF_FORMAT_VERSION_1 = 1
private const val DSF_FORMAT_ID_RAW = 0
private const val DSF_BITS_LSB_FIRST = 1
private const val DSF_BITS_MSB_FIRST = 8
private const val DSF_MAX_BLOCK_SIZE_BYTES = 1_048_576

private const val DFF_FORM_HEADER_BYTES = 16
private const val DFF_CHUNK_HEADER_BYTES = 12
private const val MAX_CHUNKS_WALKED = 512
private const val MAX_CHANNELS = 8
private const val MIN_DSD_SAMPLE_RATE_HZ = 1_000_000
private const val MAX_DSD_SAMPLE_RATE_HZ = 49_152_000

private const val TARGET_READ_BYTES = 128 * 1024 // 128 KB batched reads eliminate GC & syscall overhead at DSD256/DSD512
private const val DSF_BATCH_TARGET_BYTES = 128 * 1024
private const val MAX_EMIT_BYTES = 128 * 1024
private const val DFF_SEEK_WORD_FRAMES = 4L
private const val DSD_SILENCE_BYTE: Byte = 0x69
private const val NO_PENDING_SEEK_POSITION = -1L

// Precomputed 8-bit reversal LUT converting Sony DSF LSB-first bytes into chronological MSB-first DSD_U8
private val BIT_REVERSE_TABLE = ByteArray(256) { v ->
    var b = v
    var r = 0
    for (bit in 0 until 8) {
        r = (r shl 1) or (b and 1)
        b = b ushr 1
    }
    r.toByte()
}

// ═══ Extractor Implementation ═══
@OptIn(UnstableApi::class)
class DsdExtractor : Extractor {

    // ─── Pipeline State ───
    private var extractorOutput: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null

    private var state = STATE_READING_HEADER
    private var containerType = CONTAINER_UNKNOWN
    private var headersParsed = false
    private var formatAndSeekMapEmitted = false

    private var channels = 0
    private var sampleRate = 0
    private var byteFrameRate = 0L
    private var reverseBits = false
    private var blockSizePerChannel = 0
    private var superBlockBytes = 0
    private var dsfBlocksPerBatch = 1

    private var dataStartPosition = 0L
    private var dataEndPosition = 0L
    private var effectiveDataSize = 0L
    private var totalByteFrames = 0L
    private var streamDurationUs = C.TIME_UNSET
    private var currentByteFrame = 0L
    private var pendingSeekBytePosition = NO_PENDING_SEEK_POSITION

    private val sniffBuf = ByteArray(SNIFF_BYTES)
    private val chunkHdrBuf = ByteArray(DFF_CHUNK_HEADER_BYTES)
    private val dffReadBuf = ByteArray(TARGET_READ_BYTES + MAX_CHANNELS * DFF_SEEK_WORD_FRAMES.toInt())
    private var dsfBatchBuf = ByteArray(0)
    private var dsfInterleavedBuf = ByteArray(0)
    private val parsableData = ParsableByteArray()
    private var pendingDffBytes = 0

    // ═══ Extractor Lifecycle ═══
    override fun sniff(input: ExtractorInput): Boolean {
        if (!input.peekFully(sniffBuf, 0, SNIFF_BYTES, true)) return false
        if (isAscii(sniffBuf, 0, "DSD ")) {
            val dsdChunkSize = readLeU64(sniffBuf, 4)
            return dsdChunkSize in DSF_DSD_CHUNK_MIN_BYTES..DSF_MAX_HEADER_CHUNK_BYTES
        }
        return isAscii(sniffBuf, 0, "FRM8") && isAscii(sniffBuf, 12, "DSD ")
    }

    override fun init(output: ExtractorOutput) {
        extractorOutput = output
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (state == STATE_READING_HEADER) {
            if (!input.peekFully(sniffBuf, 0, SNIFF_BYTES, true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
            when {
                isAscii(sniffBuf, 0, "DSD ") -> {
                    containerType = CONTAINER_DSF
                    parseDsfContainer(input)
                }
                isAscii(sniffBuf, 0, "FRM8") && isAscii(sniffBuf, 12, "DSD ") -> {
                    containerType = CONTAINER_DFF
                    parseDffContainer(input)
                }
                else -> throw ParserException.createForMalformedContainer(
                    "Unrecognized DSD container header",
                    null
                )
            }

            headersParsed = true
            state = STATE_READING_SAMPLES
            currentByteFrame = 0L
            pendingDffBytes = 0
            pendingSeekBytePosition = NO_PENDING_SEEK_POSITION

            if (input.position != dataStartPosition) {
                seekPosition.position = dataStartPosition
                return Extractor.RESULT_SEEK
            }
        }

        if (!formatAndSeekMapEmitted) {
            emitFormatAndSeekMap()
            formatAndSeekMapEmitted = true
        }

        if (pendingSeekBytePosition != NO_PENDING_SEEK_POSITION) {
            val target = pendingSeekBytePosition
            pendingSeekBytePosition = NO_PENDING_SEEK_POSITION
            if (input.position != target) {
                seekPosition.position = target
                return Extractor.RESULT_SEEK
            }
        }

        if (input.position < dataStartPosition) {
            seekPosition.position = dataStartPosition
            return Extractor.RESULT_SEEK
        }

        return if (containerType == CONTAINER_DSF) {
            readDsfSamples(input)
        } else {
            readDffSamples(input)
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingDffBytes = 0
        if (!headersParsed) {
            state = STATE_READING_HEADER
            currentByteFrame = 0L
            pendingSeekBytePosition = NO_PENDING_SEEK_POSITION
            return
        }

        state = STATE_READING_SAMPLES
        if (containerType == CONTAINER_DSF && superBlockBytes > 0) {
            val relBytes = (position - dataStartPosition).coerceAtLeast(0L)
            val blockIndex = relBytes / superBlockBytes.toLong()
            val alignedBytePos = (dataStartPosition + blockIndex * superBlockBytes.toLong())
                .coerceAtMost(dataEndPosition)
            currentByteFrame = (blockIndex * blockSizePerChannel.toLong()).coerceAtMost(totalByteFrames)
            pendingSeekBytePosition = alignedBytePos
        } else if (channels > 0) {
            val clampedOffset = (position - dataStartPosition).coerceIn(0L, effectiveDataSize)
            val frameIndex = clampedOffset / channels.toLong()
            val alignedBytePos = dataStartPosition + frameIndex * channels.toLong()
            currentByteFrame = frameIndex.coerceAtMost(totalByteFrames)
            pendingSeekBytePosition = alignedBytePos
        } else {
            currentByteFrame = 0L
            pendingSeekBytePosition = dataStartPosition
        }
    }

    override fun release() {
        dsfBatchBuf = ByteArray(0)
        dsfInterleavedBuf = ByteArray(0)
    }

    // ═══ DSF Header Parser ═══
    private fun parseDsfContainer(input: ExtractorInput) {
        val dsdHdr = ByteArray(DSF_DSD_CHUNK_MIN_BYTES.toInt())
        input.readFully(dsdHdr, 0, dsdHdr.size)
        if (!isAscii(dsdHdr, 0, "DSD ")) {
            throw ParserException.createForMalformedContainer("Invalid DSF DSD chunk magic", null)
        }

        val dsdChunkSize = readLeU64(dsdHdr, 4)
        if (dsdChunkSize < DSF_DSD_CHUNK_MIN_BYTES || dsdChunkSize > DSF_MAX_HEADER_CHUNK_BYTES) {
            throw ParserException.createForMalformedContainer("Invalid DSF DSD chunk size: $dsdChunkSize", null)
        }
        val metadataOffset = readLeU64(dsdHdr, 20)
        if (dsdChunkSize > DSF_DSD_CHUNK_MIN_BYTES) {
            skipFullyLarge(input, dsdChunkSize - DSF_DSD_CHUNK_MIN_BYTES)
        }

        // Read "fmt " chunk (header + 40-byte payload)
        input.readFully(chunkHdrBuf, 0, DFF_CHUNK_HEADER_BYTES)
        if (!isAscii(chunkHdrBuf, 0, "fmt ")) {
            throw ParserException.createForMalformedContainer("Missing DSF fmt chunk", null)
        }
        val fmtChunkSize = readLeU64(chunkHdrBuf, 4)
        if (fmtChunkSize < DSF_FMT_CHUNK_MIN_BYTES || fmtChunkSize > DSF_MAX_HEADER_CHUNK_BYTES) {
            throw ParserException.createForMalformedContainer("Invalid DSF fmt chunk size: $fmtChunkSize", null)
        }
        val fmtBody = ByteArray(40)
        input.readFully(fmtBody, 0, fmtBody.size)
        val extraFmtBytes = fmtChunkSize - DSF_FMT_CHUNK_MIN_BYTES
        if (extraFmtBytes > 0L) {
            skipFullyLarge(input, extraFmtBytes)
        }

        val formatVersion = readLeU32(fmtBody, 0).toInt()
        val formatId = readLeU32(fmtBody, 4).toInt()
        channels = readLeU32(fmtBody, 12).toInt()
        sampleRate = readLeU32(fmtBody, 16).toInt()
        val bitsPerSample = readLeU32(fmtBody, 20).toInt()
        val sampleCount = readLeU64(fmtBody, 24)
        blockSizePerChannel = readLeU32(fmtBody, 32).toInt()

        if (formatVersion != DSF_FORMAT_VERSION_1 || formatId != DSF_FORMAT_ID_RAW) {
            throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported DSF format: version=$formatVersion, id=$formatId"
            )
        }
        validateDsdStreamParameters()

        reverseBits = when (bitsPerSample) {
            DSF_BITS_LSB_FIRST -> true
            DSF_BITS_MSB_FIRST -> false
            else -> throw ParserException.createForUnsupportedContainerFeature(
                "Unsupported DSF bits-per-sample: $bitsPerSample"
            )
        }
        if (blockSizePerChannel <= 0 || blockSizePerChannel > DSF_MAX_BLOCK_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer(
                "Invalid DSF block size per channel: $blockSizePerChannel",
                null
            )
        }

        // Read "data" chunk header
        input.readFully(chunkHdrBuf, 0, DFF_CHUNK_HEADER_BYTES)
        if (!isAscii(chunkHdrBuf, 0, "data")) {
            throw ParserException.createForMalformedContainer("Missing DSF data chunk", null)
        }
        val dataChunkSize = readLeU64(chunkHdrBuf, 4)
        if (dataChunkSize < DSF_DATA_HEADER_BYTES) {
            throw ParserException.createForMalformedContainer("Invalid DSF data chunk size: $dataChunkSize", null)
        }

        dataStartPosition = input.position
        var availablePayload = dataChunkSize - DSF_DATA_HEADER_BYTES
        if (metadataOffset > dataStartPosition) {
            availablePayload = minOf(availablePayload, metadataOffset - dataStartPosition)
        }
        val inputLength = input.length
        if (inputLength != C.LENGTH_UNSET.toLong() && inputLength > dataStartPosition) {
            availablePayload = minOf(availablePayload, inputLength - dataStartPosition)
        }
        availablePayload = availablePayload.coerceAtLeast(0L)

        superBlockBytes = channels * blockSizePerChannel
        val maxCompleteBlocks = availablePayload / superBlockBytes.toLong()
        val maxByteFramesFromPayload = maxCompleteBlocks * blockSizePerChannel.toLong()

        // Clamp strictly to valid sampleCount/8 byte-frames so trailing zero-padded block tails
        // and ID3v2 metadata bytes are never emitted to the DAC (prevents end-of-track DC pops).
        totalByteFrames = if (sampleCount > 0L) {
            minOf(sampleCount / 8L, maxByteFramesFromPayload)
        } else {
            maxByteFramesFromPayload
        }

        val requiredBlocks = if (totalByteFrames > 0L) {
            (totalByteFrames + blockSizePerChannel.toLong() - 1L) / blockSizePerChannel.toLong()
        } else {
            0L
        }
        effectiveDataSize = requiredBlocks * superBlockBytes.toLong()
        dataEndPosition = dataStartPosition + effectiveDataSize

        byteFrameRate = sampleRate / 8L
        streamDurationUs = if (totalByteFrames > 0L && byteFrameRate > 0L) {
            Util.scaleLargeTimestamp(totalByteFrames, C.MICROS_PER_SECOND, byteFrameRate)
        } else {
            C.TIME_UNSET
        }

        // Pre-allocate fixed 128 KB batch buffers once per track (+4 byte-frames slack for U32 EOF padding)
        dsfBlocksPerBatch = (DSF_BATCH_TARGET_BYTES / superBlockBytes).coerceAtLeast(1)
        val batchBytes = dsfBlocksPerBatch * superBlockBytes
        dsfBatchBuf = ByteArray(batchBytes)
        dsfInterleavedBuf = ByteArray(minOf(batchBytes, MAX_EMIT_BYTES) + channels * DFF_SEEK_WORD_FRAMES.toInt())
    }

    // ═══ DFF (DSDIFF) Header Parser ═══
    private fun parseDffContainer(input: ExtractorInput) {
        val formHdr = ByteArray(DFF_FORM_HEADER_BYTES)
        input.readFully(formHdr, 0, formHdr.size)
        if (!isAscii(formHdr, 0, "FRM8") || !isAscii(formHdr, 12, "DSD ")) {
            throw ParserException.createForMalformedContainer("Invalid DSDIFF FRM8 header", null)
        }

        var propParsed = false
        var dsdDataParsed = false
        var rawDsdDataSize = 0L
        var chunksWalked = 0

        while (chunksWalked < MAX_CHUNKS_WALKED && !(propParsed && dsdDataParsed)) {
            chunksWalked++
            if (!input.readFully(chunkHdrBuf, 0, DFF_CHUNK_HEADER_BYTES, true)) {
                break
            }
            val chunkId = String(chunkHdrBuf, 0, 4, Charsets.ISO_8859_1)
            val chunkSize = readBeU64(chunkHdrBuf, 4)
            if (chunkSize < 0L) {
                throw ParserException.createForMalformedContainer("Negative DSDIFF chunk size for $chunkId", null)
            }
            val padByte = chunkSize and 1L

            when (chunkId) {
                "PROP" -> {
                    if (chunkSize < 4L) {
                        throw ParserException.createForMalformedContainer("Truncated DSDIFF PROP chunk", null)
                    }
                    val propTypeBuf = ByteArray(4)
                    input.readFully(propTypeBuf, 0, 4)
                    val remainingProp = chunkSize - 4L
                    if (isAscii(propTypeBuf, 0, "SND ")) {
                        parseDffSoundProperties(input, remainingProp)
                        propParsed = true
                    } else if (remainingProp > 0L) {
                        skipFullyLarge(input, remainingProp)
                    }
                    if (padByte != 0L) skipFullyLarge(input, padByte)
                }
                "DSD " -> {
                    dataStartPosition = input.position
                    rawDsdDataSize = chunkSize
                    dsdDataParsed = true
                    if (!propParsed) {
                        skipFullyLarge(input, chunkSize + padByte)
                    }
                }
                "DST " -> {
                    throw ParserException.createForUnsupportedContainerFeature(
                        "DST-compressed DSDIFF streams are not supported"
                    )
                }
                else -> {
                    val skipTotal = chunkSize + padByte
                    if (skipTotal > 0L) {
                        skipFullyLarge(input, skipTotal)
                    }
                }
            }
        }

        if (!propParsed || !dsdDataParsed) {
            throw ParserException.createForMalformedContainer(
                "Incomplete DSDIFF container (prop=$propParsed, dsd=$dsdDataParsed)",
                null
            )
        }

        validateDsdStreamParameters()
        reverseBits = false

        val inputLength = input.length
        val boundedPayload = if (inputLength != C.LENGTH_UNSET.toLong() && inputLength > dataStartPosition) {
            minOf(rawDsdDataSize, inputLength - dataStartPosition)
        } else {
            rawDsdDataSize
        }.coerceAtLeast(0L)

        totalByteFrames = boundedPayload / channels.toLong()
        effectiveDataSize = totalByteFrames * channels.toLong()
        dataEndPosition = dataStartPosition + effectiveDataSize
        byteFrameRate = sampleRate / 8L
        streamDurationUs = if (totalByteFrames > 0L && byteFrameRate > 0L) {
            Util.scaleLargeTimestamp(totalByteFrames, C.MICROS_PER_SECOND, byteFrameRate)
        } else {
            C.TIME_UNSET
        }
    }

    private fun parseDffSoundProperties(input: ExtractorInput, propPayloadSize: Long) {
        val propEndPosition = input.position + propPayloadSize
        var subChunksWalked = 0

        while (input.position + DFF_CHUNK_HEADER_BYTES <= propEndPosition && subChunksWalked < 64) {
            subChunksWalked++
            input.readFully(chunkHdrBuf, 0, DFF_CHUNK_HEADER_BYTES)
            val subId = String(chunkHdrBuf, 0, 4, Charsets.ISO_8859_1)
            val subSize = readBeU64(chunkHdrBuf, 4)
            if (subSize < 0L || input.position + subSize > propEndPosition) {
                break
            }
            val subPad = subSize and 1L
            val subEnd = input.position + subSize + subPad

            when (subId) {
                "FS  " -> {
                    if (subSize < 4L) {
                        throw ParserException.createForMalformedContainer("Truncated DSDIFF FS chunk", null)
                    }
                    val fsBuf = ByteArray(4)
                    input.readFully(fsBuf, 0, 4)
                    sampleRate = readBeU32(fsBuf, 0).toInt()
                }
                "CHNL" -> {
                    if (subSize < 2L) {
                        throw ParserException.createForMalformedContainer("Truncated DSDIFF CHNL chunk", null)
                    }
                    val chBuf = ByteArray(2)
                    input.readFully(chBuf, 0, 2)
                    channels = readBeU16(chBuf, 0)
                }
                "CMPR" -> {
                    if (subSize >= 4L) {
                        val cmprBuf = ByteArray(4)
                        input.readFully(cmprBuf, 0, 4)
                        val cmprId = String(cmprBuf, 0, 4, Charsets.ISO_8859_1)
                        if (cmprId != "DSD ") {
                            throw ParserException.createForUnsupportedContainerFeature(
                                "Unsupported DSDIFF compression type: $cmprId"
                            )
                        }
                    }
                }
            }

            val remainingSub = minOf(subEnd, propEndPosition) - input.position
            if (remainingSub > 0L) {
                skipFullyLarge(input, remainingSub)
            }
        }

        val leftoverProp = propEndPosition - input.position
        if (leftoverProp > 0L) {
            skipFullyLarge(input, leftoverProp)
        }
    }

    private fun validateDsdStreamParameters() {
        if (channels !in 1..MAX_CHANNELS ||
            sampleRate !in MIN_DSD_SAMPLE_RATE_HZ..MAX_DSD_SAMPLE_RATE_HZ ||
            (sampleRate % 8) != 0
        ) {
            throw ParserException.createForMalformedContainer(
                "Unsupported DSD stream parameters: ch=$channels, rate=$sampleRate",
                null
            )
        }
    }

    // ═══ Format & SeekMap Emission ═══
    private fun emitFormatAndSeekMap() {
        val output = extractorOutput ?: return
        val track = trackOutput ?: return
        val bitrate = (sampleRate.toLong() * channels.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val maxInputSize = if (containerType == CONTAINER_DSF) {
            dsfInterleavedBuf.size
        } else {
            (dffReadBuf.size / channels.coerceAtLeast(1)) * channels
        }

        val format = Format.Builder()
            .setSampleMimeType(MIME_AUDIO_DSD)
            .setCodecs(DSD_U8_CODEC_HINT)
            .setChannelCount(channels)
            .setSampleRate(sampleRate)
            .setAverageBitrate(bitrate)
            .setPeakBitrate(bitrate)
            .setMaxInputSize(maxInputSize)
            .build()

        track.format(format)
        output.endTracks()
        output.seekMap(
            DsdSeekMap(
                streamDurationUs = streamDurationUs,
                totalByteFrames = totalByteFrames,
                byteFrameRate = byteFrameRate,
                dataStartPosition = dataStartPosition,
                channels = channels,
                isDsf = containerType == CONTAINER_DSF,
                blockSizePerChannel = blockSizePerChannel
            )
        )
    }

    // ═══ Sample Readers ═══
    private fun readDsfSamples(input: ExtractorInput): Int {
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val remainingFramesTotal = totalByteFrames - currentByteFrame
        if (remainingFramesTotal <= 0L || input.position >= dataEndPosition) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val remainingBlocksInFile = (dataEndPosition - input.position) / superBlockBytes.toLong()
        val neededBlocks = (remainingFramesTotal + blockSizePerChannel.toLong() - 1L) / blockSizePerChannel.toLong()
        val blocksToRead = minOf(dsfBlocksPerBatch.toLong(), remainingBlocksInFile, neededBlocks).toInt()
        if (blocksToRead <= 0) return Extractor.RESULT_END_OF_INPUT

        val bytesToRead = blocksToRead * superBlockBytes
        if (!input.readFully(dsfBatchBuf, 0, bytesToRead, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }

        // Fast path: entire batched read fits inside pre-allocated 128 KB interleaved buffer -> 1 TrackOutput emit
        val totalValidFramesInBatch = minOf(blocksToRead.toLong() * blockSizePerChannel.toLong(), remainingFramesTotal).toInt()
        val totalOutBytes = totalValidFramesInBatch * channels
        val wordAlignBytes = channels * DFF_SEEK_WORD_FRAMES.toInt()
        if (totalOutBytes + wordAlignBytes <= dsfInterleavedBuf.size) {
            var dstOffset = 0
            var framesLeft = totalValidFramesInBatch
            for (b in 0 until blocksToRead) {
                val blockFrames = minOf(blockSizePerChannel, framesLeft)
                if (blockFrames <= 0) break
                interleaveDsfSlice(
                    srcPlanar = dsfBatchBuf,
                    blockByteOffset = b * superBlockBytes,
                    dstInterleaved = dsfInterleavedBuf,
                    dstByteOffset = dstOffset,
                    startFrameInBlock = 0,
                    frameCount = blockFrames
                )
                dstOffset += blockFrames * channels
                framesLeft -= blockFrames
            }

            // Pad trailing partial 32-bit DSD word (< 4 byte-frames) with balanced 0x69 silence at EOF
            // so Native DSD U32_BE/LE never stalls on unaligned remainder bytes.
            val remBytes = dstOffset % wordAlignBytes
            if (remBytes != 0) {
                val padBytes = wordAlignBytes - remBytes
                dsfInterleavedBuf.fill(DSD_SILENCE_BYTE, dstOffset, dstOffset + padBytes)
                dstOffset += padBytes
            }

            val sampleTimeUs = Util.scaleLargeTimestamp(currentByteFrame, C.MICROS_PER_SECOND, byteFrameRate)
            currentByteFrame += totalValidFramesInBatch.toLong()

            parsableData.reset(dsfInterleavedBuf, dstOffset)
            track.sampleData(parsableData, dstOffset)
            track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, dstOffset, 0, null)
            return Extractor.RESULT_CONTINUE
        }

        // Fallback for exotic oversized DSF block sizes (>128 KB): slice each block without allocating
        val maxFramesPerSlice = ((dsfInterleavedBuf.size - wordAlignBytes) / channels)
            .let { (it / DFF_SEEK_WORD_FRAMES.toInt()) * DFF_SEEK_WORD_FRAMES.toInt() }
            .coerceAtLeast(DFF_SEEK_WORD_FRAMES.toInt())
        var framesLeft = totalValidFramesInBatch
        for (b in 0 until blocksToRead) {
            val validFrames = minOf(blockSizePerChannel, framesLeft)
            var frameOffset = 0
            while (frameOffset < validFrames) {
                val sliceFrames = minOf(validFrames - frameOffset, maxFramesPerSlice)
                var sliceBytes = sliceFrames * channels
                interleaveDsfSlice(
                    srcPlanar = dsfBatchBuf,
                    blockByteOffset = b * superBlockBytes,
                    dstInterleaved = dsfInterleavedBuf,
                    dstByteOffset = 0,
                    startFrameInBlock = frameOffset,
                    frameCount = sliceFrames
                )
                val remBytes = sliceBytes % wordAlignBytes
                if (remBytes != 0) {
                    val padBytes = wordAlignBytes - remBytes
                    dsfInterleavedBuf.fill(DSD_SILENCE_BYTE, sliceBytes, sliceBytes + padBytes)
                    sliceBytes += padBytes
                }

                val sampleTimeUs = Util.scaleLargeTimestamp(currentByteFrame, C.MICROS_PER_SECOND, byteFrameRate)
                currentByteFrame += sliceFrames.toLong()
                frameOffset += sliceFrames

                parsableData.reset(dsfInterleavedBuf, sliceBytes)
                track.sampleData(parsableData, sliceBytes)
                track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sliceBytes, 0, null)
            }
            framesLeft -= validFrames
        }

        return Extractor.RESULT_CONTINUE
    }

    private fun interleaveDsfSlice(
        srcPlanar: ByteArray,
        blockByteOffset: Int,
        dstInterleaved: ByteArray,
        dstByteOffset: Int,
        startFrameInBlock: Int,
        frameCount: Int
    ) {
        val stride = blockSizePerChannel
        var dst = dstByteOffset
        if (channels == 2) {
            val leftBase = blockByteOffset + startFrameInBlock
            val rightBase = leftBase + stride
            if (reverseBits) {
                val lut = BIT_REVERSE_TABLE
                for (i in 0 until frameCount) {
                    dstInterleaved[dst++] = lut[srcPlanar[leftBase + i].toInt() and 0xFF]
                    dstInterleaved[dst++] = lut[srcPlanar[rightBase + i].toInt() and 0xFF]
                }
            } else {
                for (i in 0 until frameCount) {
                    dstInterleaved[dst++] = srcPlanar[leftBase + i]
                    dstInterleaved[dst++] = srcPlanar[rightBase + i]
                }
            }
            return
        }

        val frameBase = blockByteOffset + startFrameInBlock
        if (reverseBits) {
            val lut = BIT_REVERSE_TABLE
            for (i in 0 until frameCount) {
                val idx = frameBase + i
                for (ch in 0 until channels) {
                    dstInterleaved[dst++] = lut[srcPlanar[ch * stride + idx].toInt() and 0xFF]
                }
            }
        } else {
            for (i in 0 until frameCount) {
                val idx = frameBase + i
                for (ch in 0 until channels) {
                    dstInterleaved[dst++] = srcPlanar[ch * stride + idx]
                }
            }
        }
    }

    private fun readDffSamples(input: ExtractorInput): Int {
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        val remainingInChunk = (dataEndPosition - input.position).coerceAtLeast(0L)
        if (remainingInChunk == 0L && pendingDffBytes < channels) {
            return Extractor.RESULT_END_OF_INPUT
        }

        if (remainingInChunk > 0L) {
            val maxCapacity = TARGET_READ_BYTES - pendingDffBytes
            val toRead = minOf(maxCapacity.toLong(), remainingInChunk).toInt()
            if (toRead > 0) {
                val read = input.read(dffReadBuf, pendingDffBytes, toRead)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (pendingDffBytes < channels) return Extractor.RESULT_END_OF_INPUT
                } else {
                    pendingDffBytes += read
                }
            }
        }

        // Keep emissions aligned to 4-byte-frame (32-bit DSD word) boundaries so DoP (2 bytes/ch)
        // and Native DSD U32 (4 bytes/ch) never stall on unaligned trailing EOF bytes.
        val isAtChunkEnd = (dataEndPosition - input.position) <= 0L
        val wordAlignBytes = channels * DFF_SEEK_WORD_FRAMES.toInt()
        val alignedBytes = (pendingDffBytes / wordAlignBytes) * wordAlignBytes
        val bytesConsumed = if (alignedBytes > 0) {
            alignedBytes
        } else if (isAtChunkEnd) {
            (pendingDffBytes / channels) * channels
        } else {
            0
        }

        if (bytesConsumed <= 0) {
            return if (isAtChunkEnd) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
        }

        val framesEmitted = (bytesConsumed / channels).toLong()
        val sampleTimeUs = Util.scaleLargeTimestamp(currentByteFrame, C.MICROS_PER_SECOND, byteFrameRate)
        currentByteFrame += framesEmitted

        val leftover = pendingDffBytes - bytesConsumed
        var emitBytes = bytesConsumed
        if (isAtChunkEnd && leftover == 0) {
            val remBytes = emitBytes % wordAlignBytes
            if (remBytes != 0) {
                val padBytes = wordAlignBytes - remBytes
                dffReadBuf.fill(DSD_SILENCE_BYTE, emitBytes, emitBytes + padBytes)
                emitBytes += padBytes
            }
        }

        parsableData.reset(dffReadBuf, emitBytes)
        track.sampleData(parsableData, emitBytes)
        track.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, emitBytes, 0, null)

        if (leftover > 0) {
            System.arraycopy(dffReadBuf, bytesConsumed, dffReadBuf, 0, leftover)
        }
        pendingDffBytes = leftover

        return Extractor.RESULT_CONTINUE
    }

    // ═══ SeekMap ═══
    private class DsdSeekMap(
        private val streamDurationUs: Long,
        private val totalByteFrames: Long,
        private val byteFrameRate: Long,
        private val dataStartPosition: Long,
        private val channels: Int,
        private val isDsf: Boolean,
        private val blockSizePerChannel: Int
    ) : SeekMap {
        override fun isSeekable(): Boolean = totalByteFrames > 0L && byteFrameRate > 0L

        override fun getDurationUs(): Long = streamDurationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (totalByteFrames <= 0L || byteFrameRate <= 0L) {
                return SeekMap.SeekPoints(SeekPoint(0L, dataStartPosition))
            }
            val maxTimeUs = if (streamDurationUs != C.TIME_UNSET) streamDurationUs else 0L
            val clampedTimeUs = timeUs.coerceIn(0L, maxTimeUs)
            val targetByteFrame = Util.scaleLargeTimestamp(clampedTimeUs, byteFrameRate, C.MICROS_PER_SECOND)
                .coerceIn(0L, maxOf(0L, totalByteFrames - 1L))

            val alignedByteFrame: Long
            val bytePos: Long
            if (isDsf && blockSizePerChannel > 0) {
                val blockIndex = targetByteFrame / blockSizePerChannel.toLong()
                alignedByteFrame = blockIndex * blockSizePerChannel.toLong()
                bytePos = dataStartPosition + blockIndex * channels.toLong() * blockSizePerChannel.toLong()
            } else {
                alignedByteFrame = (targetByteFrame / DFF_SEEK_WORD_FRAMES) * DFF_SEEK_WORD_FRAMES
                bytePos = dataStartPosition + alignedByteFrame * channels.toLong()
            }

            val actualTimeUs = Util.scaleLargeTimestamp(alignedByteFrame, C.MICROS_PER_SECOND, byteFrameRate)
            return SeekMap.SeekPoints(SeekPoint(actualTimeUs, bytePos))
        }
    }

    // ═══ Binary Helpers ═══
    private fun skipFullyLarge(input: ExtractorInput, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val step = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            input.skipFully(step)
            remaining -= step
        }
    }

    private fun isAscii(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun readBeU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)

    private fun readBeU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)

    private fun readBeU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    private fun readLeU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun readLeU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return value
    }
}
