/**
 * WavFloatFixExtractorsFactory — Custom Media3 ExtractorsFactory for float WAV, AIFF, and DSD.
 *
 * @remarks
 * Media3 1.5.0's `WavUtil.getPcmEncodingForType(0xFFFE, 32)` treats `WAVE_FORMAT_EXTENSIBLE`
 * identically to integer PCM (`C.ENCODING_PCM_32BIT`) without inspecting the `fmt ` chunk's
 * `SubFormat` GUID. When a 32-bit IEEE float WAV uses `0xFFFE` (common in FFmpeg, Audacity,
 * and DAW exports), downstream sinks interpret raw IEEE-754 float bytes as signed 32-bit integers,
 * producing full-scale tearing noise. This factory wraps `WavExtractor` to inspect the `fmt `
 * `SubFormat` GUID and rewrite `C.ENCODING_PCM_32BIT` to `C.ENCODING_PCM_FLOAT` when needed.
 *
 * Also registers [DsdExtractor] ahead of Media3's default extractors (preventing `Mp3Extractor`
 * from falsely matching MPEG sync patterns inside raw `.dsf`/`.dff` payloads) and [AiffExtractor]
 * for EA IFF-85 AIFF/AIFF-C containers.
 *
 * @see DsdExtractor
 * @see AiffExtractor
 * @see UsbDacAudioSink
 * @see PlaybackService
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor

// ═══ Constants ═══
internal object RawPcmFloatFormatMarker
private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
private const val FMT_EXTENSIBLE_MIN_SIZE = 40
private const val MAX_HEADER_SCAN_BYTES = 65_536

// KSDATAFORMAT_SUBTYPE_IEEE_FLOAT: 00000003-0000-0010-8000-00aa00389b71 (mixed-endian GUID)
private val SUBFORMAT_IEEE_FLOAT = byteArrayOf(
    0x03, 0x00, 0x00, 0x00,
    0x00, 0x00,
    0x10, 0x00,
    0x80.toByte(), 0x00,
    0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71
)

// ═══ ExtractorsFactory ═══
@OptIn(UnstableApi::class)
class WavFloatFixExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        wrapExtractors(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        wrapExtractors(delegate.createExtractors(uri, responseHeaders))

    private fun wrapExtractors(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size + 2) { i ->
            when (i) {
                0 -> DsdExtractor()
                extractors.size + 1 -> AiffExtractor()
                else -> {
                    val extractor = extractors[i - 1]
                    if (extractor is WavExtractor) WavFloatFixExtractor(extractor) else extractor
                }
            }
        }
}

// ═══ WavExtractor Wrapper ═══
@OptIn(UnstableApi::class)
private class WavFloatFixExtractor(
    private val delegate: WavExtractor
) : Extractor {

    private var isExtensibleFloat32 = false
    private var headerChecked = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(FloatFixExtractorOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerChecked && input.position == 0L) {
            headerChecked = true
            isExtensibleFloat32 = detectExtensibleFloat32(input)
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        delegate.seek(position, timeUs)
    }

    override fun release() {
        delegate.release()
    }

    // ─── Header Inspection ───
    private fun detectExtensibleFloat32(input: ExtractorInput): Boolean {
        try {
            val riffHeader = ByteArray(12)
            if (!input.peekFully(riffHeader, 0, 12, true)) return false

            val isRiff = (riffHeader[0] == 'R'.code.toByte() &&
                riffHeader[1] == 'I'.code.toByte() &&
                riffHeader[2] == 'F'.code.toByte() &&
                riffHeader[3] == 'F'.code.toByte()) ||
                (riffHeader[0] == 'R'.code.toByte() &&
                    riffHeader[1] == 'F'.code.toByte() &&
                    riffHeader[2] == '6'.code.toByte() &&
                    riffHeader[3] == '4'.code.toByte())
            val isWave = riffHeader[8] == 'W'.code.toByte() &&
                riffHeader[9] == 'A'.code.toByte() &&
                riffHeader[10] == 'V'.code.toByte() &&
                riffHeader[11] == 'E'.code.toByte()
            if (!isRiff || !isWave) return false

            var scannedBytes = 12L
            val chunkHeader = ByteArray(8)
            while (scannedBytes + 8 <= MAX_HEADER_SCAN_BYTES) {
                if (!input.peekFully(chunkHeader, 0, 8, true)) return false
                scannedBytes += 8

                val chunkSize = readLeU32(chunkHeader, 4)
                val isFmt = chunkHeader[0] == 'f'.code.toByte() &&
                    chunkHeader[1] == 'm'.code.toByte() &&
                    chunkHeader[2] == 't'.code.toByte() &&
                    chunkHeader[3] == ' '.code.toByte()
                val isData = chunkHeader[0] == 'd'.code.toByte() &&
                    chunkHeader[1] == 'a'.code.toByte() &&
                    chunkHeader[2] == 't'.code.toByte() &&
                    chunkHeader[3] == 'a'.code.toByte()

                if (isFmt) {
                    if (chunkSize < FMT_EXTENSIBLE_MIN_SIZE || chunkSize > 4096L) return false
                    val fmtData = ByteArray(FMT_EXTENSIBLE_MIN_SIZE)
                    if (!input.peekFully(fmtData, 0, FMT_EXTENSIBLE_MIN_SIZE, true)) return false

                    val formatTag = readLeU16(fmtData, 0)
                    val bitsPerSample = readLeU16(fmtData, 14)
                    if (formatTag != WAVE_FORMAT_EXTENSIBLE || bitsPerSample != 32) return false

                    for (i in SUBFORMAT_IEEE_FLOAT.indices) {
                        if (fmtData[24 + i] != SUBFORMAT_IEEE_FLOAT[i]) return false
                    }
                    return true
                }

                if (isData) return false

                // RIFF chunks are 2-byte aligned; advance peek position past this chunk
                val paddedSize = chunkSize + (chunkSize and 1L)
                if (paddedSize <= 0L || scannedBytes + paddedSize > MAX_HEADER_SCAN_BYTES) return false
                input.advancePeekPosition(paddedSize.toInt())
                scannedBytes += paddedSize
            }
            return false
        } catch (_: Throwable) {
            return false
        } finally {
            input.resetPeekPosition()
        }
    }

    private fun readLeU16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readLeU32(buf: ByteArray, offset: Int): Long =
        (buf[offset].toInt() and 0xFF).toLong() or
            ((buf[offset + 1].toInt() and 0xFF).toLong() shl 8) or
            ((buf[offset + 2].toInt() and 0xFF).toLong() shl 16) or
            ((buf[offset + 3].toInt() and 0xFF).toLong() shl 24)

    // ─── Output Interception ───
    private inner class FloatFixExtractorOutput(
        private val delegate: ExtractorOutput
    ) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput =
            FloatFixTrackOutput(delegate.track(id, type))

        override fun endTracks() = delegate.endTracks()

        override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
    }

    private inner class FloatFixTrackOutput(
        private val delegate: TrackOutput
    ) : TrackOutput {
        override fun format(format: Format) {
            val fixedFormat = when {
                isExtensibleFloat32 &&
                    format.sampleMimeType == MimeTypes.AUDIO_RAW &&
                    format.pcmEncoding == C.ENCODING_PCM_32BIT -> {
                    format.buildUpon()
                        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                        .setCustomData(RawPcmFloatFormatMarker)
                        .build()
                }
                format.sampleMimeType == MimeTypes.AUDIO_RAW &&
                    format.pcmEncoding == C.ENCODING_PCM_FLOAT -> {
                    format.buildUpon()
                        .setCustomData(RawPcmFloatFormatMarker)
                        .build()
                }
                else -> format
            }
            delegate.format(fixedFormat)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int
        ) = delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) = delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}
