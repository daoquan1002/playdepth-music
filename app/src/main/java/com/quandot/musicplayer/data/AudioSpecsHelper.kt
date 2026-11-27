/**
 * AudioSpecsHelper — High-resolution audio format extraction and Hi-Res classification.
 *
 * @remarks
 * Parses raw FLAC `STREAMINFO` (skipping prepended ID3v2 tags), RIFF/RF64 WAVE `fmt ` chunks, and
 * MP4/M4A `alac` `ALACSpecificConfig` atoms directly via `FileInputStream` or `ContentResolver`
 * fallback so scoped-storage / SD-card permissions on Android 10+ never drop bit-depth metadata.
 * Merges incomplete live [ExoAudioFormatSnapshot] fields with `MediaExtractor` and
 * `MediaMetadataRetriever` rather than letting bitless/bitrateless player formats short-circuit
 * extraction, and estimates average lossy bitrate from file size + duration only when all
 * container tags omit it.
 *
 * @see Song
 * @see com.quandot.musicplayer.service.PlaybackService
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

// Domain models & theme tokens (last)
import com.quandot.musicplayer.model.Song

// ═══ Constants ═══
private const val HI_RES_MIN_BIT_DEPTH = 24
private const val HI_RES_MAX_STANDARD_KHZ = 48.0
private const val FLAC_STREAMINFO_BYTES = 38
private const val ID3_HEADER_SIZE = 10
private const val MAX_ID3_SKIP_BYTES = 16 * 1024 * 1024L
private const val RIFF_HEADER_SIZE = 12
private const val CHUNK_HEADER_SIZE = 8
private const val WAV_FMT_MIN_BYTES = 16
private const val WAV_FMT_MAX_READ_BYTES = 40
private const val MAX_WAV_CHUNKS_WALKED = 64
private const val MAX_WAV_BYTES_SKIPPED = 512 * 1024L
private const val WAVE_FORMAT_IEEE_FLOAT = 3
private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
private const val MP4_BOX_HEADER_SIZE = 8
private const val MP4_EXTENDED_HEADER_SIZE = 16
private const val MP4_MAX_TOP_BOXES = 64
private const val MP4_MAX_CONTAINER_BOXES = 128
private const val MP4_MAX_DEPTH = 8
private const val MP4_STSD_HEADER_SIZE = 8
private const val MP4_AUDIO_SAMPLE_ENTRY_BYTES = 28
private const val ALAC_SPECIFIC_CONFIG_BYTES = 24
private const val ALAC_FULLBOX_VERSION_BYTES = 4
private const val ALAC_COMPATIBLE_VERSION = 0
private const val MIN_VALID_SAMPLE_RATE_HZ = 8_000
private const val MAX_VALID_SAMPLE_RATE_HZ = 768_000
private const val MIN_VALID_BIT_DEPTH = 8
private const val MAX_VALID_BIT_DEPTH = 32
private const val MIN_DURATION_FOR_BITRATE_EST_MS = 5_000L
private const val MIN_ESTIMATED_LOSSY_BITRATE_BPS = 16_000
private const val MAX_ESTIMATED_LOSSY_BITRATE_BPS = 1_024_000
private const val DSD_HEADER_PROBE_BYTES = 80
private const val DFF_CHUNK_HEADER_BYTES = 12
private const val MAX_DFF_CHUNKS_WALKED = 64
private const val MAX_DFF_BYTES_SKIPPED = 512 * 1024L
private const val DSD64_THRESHOLD_HZ = 2_800_000
private const val DSD128_THRESHOLD_HZ = 5_500_000
private const val DSD256_THRESHOLD_HZ = 11_000_000
private const val DSD512_THRESHOLD_HZ = 22_000_000
private const val APE_NEW_FORMAT_MIN_VERSION = 3980
private const val APE_HEADER_PROBE_BYTES = 16
private const val APE_DESCRIPTOR_HEADER_MIN_BYTES = 52
private const val APE_MAX_DESCRIPTOR_BYTES = 4096
private const val APE_AUDIO_HEADER_MIN_BYTES = 24
private const val WAVPACK_HEADER_BYTES = 32
private const val WAVPACK_BYTES_PER_SAMPLE_MASK = 0x03

// ═══ State & Contracts ═══
data class SourceAudioFormat(
    val sourceBitsPerSample: Int = 0,
    val isSourceLossless: Boolean = false,
    val isSourceFloat: Boolean = false
)

data class ExoAudioFormatSnapshot(
    val songId: Long,
    val mimeType: String?,
    val sampleRate: Int,
    val pcmEncoding: Int,
    val bitrate: Int
)

private data class ParsedHeaderInfo(
    val codec: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val isFloat: Boolean = false
)

private data class RawAudioMeta(
    val mime: String = "",
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val bitrate: Int = 0,
    val isFloat: Boolean = false
)

private data class SpecsExtractionResult(
    val specs: String,
    val confirmedExact: Boolean
)

private data class Mp4BoxHeader(
    val size: Long,
    val headerLen: Int,
    val type: String
)

// ═══ Format Extractor ═══
object AudioSpecsHelper {

    private val BIT_DEPTH_REGEX = Regex("(\\d+)[\\s-]*bit", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_KHZ_REGEX = Regex("([\\d.,]+)\\s*kHz")
    private const val MAX_SPECS_CACHE_ENTRIES = 512
    private val specsCache = ConcurrentHashMap<Long, String>()
    private val exactSpecsIds = ConcurrentHashMap.newKeySet<Long>()
    private val sourceFormatCache = ConcurrentHashMap<String, SourceAudioFormat>()

    // ═══ Public API ═══
    fun clearCache() {
        specsCache.clear()
        exactSpecsIds.clear()
        sourceFormatCache.clear()
    }

    /**
     * Returns a previously resolved [SourceAudioFormat] from the in-memory cache with zero I/O.
     */
    fun getCachedSourceAudioFormat(uri: Uri?, path: String?): SourceAudioFormat? {
        val cleanPath = path?.trim().orEmpty()
        val cacheKey = cleanPath.ifBlank { uri?.toString().orEmpty() }
        if (cacheKey.isEmpty()) return null
        return sourceFormatCache[cacheKey]
    }

    /**
     * Derives an immediate non-blocking [SourceAudioFormat] from in-memory metadata and file
     * extension without touching storage or Binder IPC. Returns `Pair(format, isExact)` where
     * `isExact == false` indicates an async header probe is still recommended (e.g. WAV/AIFF float
     * detection, M4A ALAC vs AAC disambiguation, or missing lossless bit depth).
     */
    fun inferFastSourceAudioFormat(
        uri: Uri?,
        path: String?,
        persistedBitDepth: Int = 0,
        persistedCodec: String = "",
        mimeHint: String? = null
    ): Pair<SourceAudioFormat, Boolean> {
        getCachedSourceAudioFormat(uri, path)?.let { return it to true }

        val cleanPath = path?.trim().orEmpty()
        val ext = when {
            cleanPath.isNotBlank() -> File(cleanPath).extension.lowercase(Locale.ROOT)
            else -> uri?.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
                .orEmpty()
        }
        val codecUpper = persistedCodec.trim().uppercase(Locale.ROOT)
        val validBits = persistedBitDepth.takeIf { it in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH } ?: 0

        if (ext == "dsf" || ext == "dff" || codecUpper.startsWith("DSD")) {
            return SourceAudioFormat(sourceBitsPerSample = 1, isSourceLossless = true, isSourceFloat = false) to true
        }
        if (ext in setOf("mp3", "aac", "ogg", "oga", "opus", "wma", "eac3", "ec3", "ac3", "m4b", "amr", "3gp") ||
            codecUpper in setOf("MP3", "AAC", "OGG", "OPUS", "WMA", "EAC3", "AC3")
        ) {
            return SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false) to true
        }
        if (ext in setOf("flac", "alac", "ape", "wv") || codecUpper in setOf("FLAC", "ALAC", "APE", "WV")) {
            if (validBits > 0) {
                return SourceAudioFormat(sourceBitsPerSample = validBits, isSourceLossless = true, isSourceFloat = false) to true
            }
            return SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = true, isSourceFloat = false) to false
        }
        if (ext in setOf("wav", "aiff", "aif", "aifc") || codecUpper in setOf("WAV", "AIFF")) {
            // WAV/AIFF can contain IEEE-754 float32; seed persisted bit depth immediately and verify async
            return SourceAudioFormat(sourceBitsPerSample = validBits, isSourceLossless = true, isSourceFloat = false) to false
        }
        if (ext == "m4a" || ext == "mp4") {
            return SourceAudioFormat(sourceBitsPerSample = validBits, isSourceLossless = false, isSourceFloat = false) to false
        }
        val lowerMime = mimeHint?.lowercase(Locale.ROOT).orEmpty()
        if (lowerMime.contains("mpeg") || lowerMime.contains("opus") || lowerMime.contains("vorbis") ||
            lowerMime.contains("eac3") || lowerMime.contains("ac3")
        ) {
            return SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false) to true
        }
        return SourceAudioFormat(sourceBitsPerSample = validBits, isSourceLossless = false, isSourceFloat = false) to false
    }

    /**
     * Inspects container headers to determine true source bit-depth, lossless status, and
     * native floating-point PCM flag so [com.quandot.musicplayer.audio.UsbDacAudioSink] can
     * restore FFmpeg's `PCM_FLOAT` output back to bit-exact integer PCM for Bit-Perfect routing.
     */
    fun resolveSourceAudioFormat(
        context: Context,
        uri: Uri?,
        path: String?,
        mimeHint: String? = null
    ): SourceAudioFormat {
        val cleanPath = path?.trim().orEmpty()
        val cacheKey = cleanPath.ifBlank { uri?.toString().orEmpty() }
        if (cacheKey.isNotEmpty()) {
            sourceFormatCache[cacheKey]?.let { return it }
        }

        val resolved = extractSourceAudioFormat(context, uri, cleanPath, mimeHint)
        if (cacheKey.isNotEmpty()) {
            if (sourceFormatCache.size >= MAX_SPECS_CACHE_ENTRIES) {
                sourceFormatCache.clear()
            }
            sourceFormatCache[cacheKey] = resolved
        }
        return resolved
    }

    fun getCachedSpecs(songId: Long): String? = specsCache[songId]

    fun hasExactCachedSpecs(songId: Long): Boolean = exactSpecsIds.contains(songId)

    /**
     * Returns true when specs indicate DSD or PCM exceeding standard Red Book / 48 kHz limits
     * (>= 24-bit or > 48 kHz).
     */
    fun isHiRes(specs: String): Boolean {
        if (specs.isBlank()) return false
        if (specs.contains("DSD", ignoreCase = true)) return true

        val bitMatch = BIT_DEPTH_REGEX.find(specs)
        if (bitMatch != null) {
            val bits = bitMatch.groupValues[1].toIntOrNull() ?: 0
            if (bits >= HI_RES_MIN_BIT_DEPTH) return true
        }

        val khzMatch = SAMPLE_RATE_KHZ_REGEX.find(specs)
        if (khzMatch != null) {
            val khzStr = khzMatch.groupValues[1].replace(',', '.')
            val khz = khzStr.toDoubleOrNull() ?: 0.0
            if (khz > HI_RES_MAX_STANDARD_KHZ) return true
        }

        return false
    }

    /**
     * Infers instant non-fabricated placeholder specs from cached entries or file extension on
     * frame 0 without fabricating bit-depth or sample-rate numbers before extraction finishes.
     */
    fun getFastFallbackSpecs(song: Song): String {
        specsCache[song.id]?.let { return it }

        formatPersistedSongSpecs(song)?.let { persisted ->
            return persisted
        }

        val ext = if (song.path.isNotBlank()) {
            File(song.path).extension.uppercase(Locale.ROOT)
        } else ""

        return when (ext) {
            "FLAC", "WAV", "AIFF", "AIF", "AIFC", "MP3", "AAC", "M4A", "OPUS", "OGG", "ALAC", "APE", "WV", "WMA", "DSF", "DFF" -> ext
            else -> ext.ifBlank { "AUDIO" }
        }
    }

    private fun formatPersistedSongSpecs(song: Song): String? {
        if (song.sampleRate <= 0 && song.bitrate <= 0) return null
        val codec = song.codec.trim().uppercase(Locale.ROOT)
        if (codec.isBlank()) return null
        if (codec.startsWith("DSD") || codec == "DSF" || codec == "DFF") {
            val label = if (song.sampleRate > 0) resolveDsdLabel(song.sampleRate) else codec
            val mhzStr = if (song.sampleRate > 0) formatMhz(song.sampleRate) else ""
            return if (mhzStr.isNotEmpty()) "$label • 1-bit/$mhzStr" else label
        }
        val khz = if (song.sampleRate > 0) formatKhz(song.sampleRate) else ""
        val depthPrefix = if (song.bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
            "${song.bitDepth}-bit/"
        } else ""
        val kbpsStr = if (song.bitrate > 0) "${song.bitrate / 1000} kbps" else ""
        return when (codec) {
            "FLAC", "WAV", "ALAC", "AIFF", "APE", "WV" -> {
                if (khz.isNotEmpty()) "$codec • $depthPrefix$khz" else codec
            }
            "MP3", "AAC", "OGG", "OPUS", "WMA" -> listOf(codec, kbpsStr, khz)
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
            else -> listOf(codec, "$depthPrefix$khz".trimEnd('/'))
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
        }
    }

    /**
     * Resolves track audio specs via direct container parsing, merged live [exoFormat], or
     * platform extractors, caching confirmed exact results and allowing partial entries to upgrade.
     */
    fun getAudioSpecs(
        context: Context,
        song: Song,
        exoFormat: ExoAudioFormatSnapshot? = null
    ): String {
        if (exactSpecsIds.contains(song.id)) {
            specsCache[song.id]?.let { return it }
        }

        val result = extractSpecs(context, song, exoFormat)
        if (result.specs.isNotBlank()) {
            if (specsCache.size >= MAX_SPECS_CACHE_ENTRIES) {
                specsCache.clear()
                exactSpecsIds.clear()
            }
            if (result.confirmedExact) {
                specsCache[song.id] = result.specs
                exactSpecsIds.add(song.id)
            } else if (!exactSpecsIds.contains(song.id)) {
                specsCache[song.id] = result.specs
            }
            return result.specs
        }

        return specsCache[song.id] ?: getFastFallbackSpecs(song)
    }

    /**
     * Builds display specs directly from an active ExoPlayer `Format` snapshot without Binder IPC.
     */
    fun buildSpecsFromExoFormat(song: Song, exoFormat: ExoAudioFormatSnapshot): String? {
        if (exoFormat.sampleRate <= 0) return null
        if (!isMimeCompatibleWithPath(exoFormat.mimeType, song.path)) return null
        val (bitDepth, isFloat) = decodeExoPcmEncoding(exoFormat.pcmEncoding)
        return formatSpecs(
            mime = exoFormat.mimeType.orEmpty(),
            sampleRate = exoFormat.sampleRate,
            bitDepth = bitDepth,
            bitrate = exoFormat.bitrate.coerceAtLeast(0),
            path = song.path,
            isFloat = isFloat
        )
    }

    // ═══ Container & Header Parsers ═══
    private fun extractSpecs(
        context: Context,
        song: Song,
        exoFormat: ExoAudioFormatSnapshot?
    ): SpecsExtractionResult {
        val ext = resolveExtension(context, song)

        // 1. Direct container header read via File or ContentResolver (zero Binder IPC)
        when (ext) {
            "flac" -> openAudioInputStream(context, song)?.use { stream ->
                parseFlacHeader(stream)?.let { return SpecsExtractionResult(it, confirmedExact = true) }
            }
            "wav" -> openAudioInputStream(context, song)?.use { stream ->
                parseWavHeader(stream)?.let { return SpecsExtractionResult(it, confirmedExact = true) }
            }
            "aiff", "aif", "aifc" -> {
                AiffMetadataHelper.readMetadata(context, song)?.let { meta ->
                    if (meta.sampleRate > 0 && meta.bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                        val khz = formatKhz(meta.sampleRate)
                        val specs = if (meta.isFloat) {
                            "AIFF • ${meta.bitDepth}-bit float/$khz"
                        } else {
                            "AIFF • ${meta.bitDepth}-bit/$khz"
                        }
                        return SpecsExtractionResult(specs, confirmedExact = true)
                    }
                }
            }
            "m4a", "mp4", "alac" -> {
                parseMp4AlacHeader(context, song)?.let {
                    return SpecsExtractionResult(it, confirmedExact = true)
                }
            }
            "dsf", "dff" -> openAudioInputStream(context, song)?.use { stream ->
                parseDsdHeader(stream)?.let { return SpecsExtractionResult(it, confirmedExact = true) }
            }
        }

        // 2. Seed with live ExoPlayer Format when valid and compatible with the file extension
        val validExo = exoFormat?.takeIf {
            it.songId == song.id &&
                it.sampleRate > 0 &&
                isMimeCompatibleWithPath(it.mimeType, song.path)
        }
        var merged = if (validExo != null) {
            val (exoDepth, exoFloat) = decodeExoPcmEncoding(validExo.pcmEncoding)
            RawAudioMeta(
                mime = validExo.mimeType.orEmpty(),
                sampleRate = validExo.sampleRate,
                bitDepth = exoDepth,
                bitrate = validExo.bitrate.coerceAtLeast(0),
                isFloat = exoFloat
            )
        } else {
            RawAudioMeta()
        }

        if (isMetaComplete(merged, song.path)) {
            return SpecsExtractionResult(
                specs = formatSpecs(merged.mime, merged.sampleRate, merged.bitDepth, merged.bitrate, song.path, merged.isFloat),
                confirmedExact = true
            )
        }

        // 3. Merge missing fields (bit-depth for lossless / bitrate for lossy) from MediaExtractor
        extractWithMediaExtractor(context, song)?.let { extractorMeta ->
            merged = mergeAudioMeta(merged, extractorMeta)
            if (isMetaComplete(merged, song.path)) {
                return SpecsExtractionResult(
                    specs = formatSpecs(merged.mime, merged.sampleRate, merged.bitDepth, merged.bitrate, song.path, merged.isFloat),
                    confirmedExact = true
                )
            }
        }

        // 4. Merge missing fields from MediaMetadataRetriever (API 31+ exposes BITS_PER_SAMPLE)
        extractWithMetadataRetriever(context, song)?.let { mmrMeta ->
            merged = mergeAudioMeta(merged, mmrMeta)
            if (isMetaComplete(merged, song.path)) {
                return SpecsExtractionResult(
                    specs = formatSpecs(merged.mime, merged.sampleRate, merged.bitDepth, merged.bitrate, song.path, merged.isFloat),
                    confirmedExact = true
                )
            }
        }

        // 5. Average bitrate fallback for lossy streams (e.g. Opus/VBR OGG) when containers omit bitrate
        val resolvedCodec = resolveCodecName(merged.mime, song.path)
        if (!isLosslessCodec(resolvedCodec) && merged.bitrate <= 0 && merged.sampleRate > 0) {
            val estBitrate = estimateAverageBitrateBps(context, song)
            if (estBitrate > 0) {
                merged = merged.copy(bitrate = estBitrate)
            }
        }

        if (merged.sampleRate > 0 || merged.bitrate > 0 || merged.mime.isNotBlank()) {
            val formatted = formatSpecs(
                mime = merged.mime,
                sampleRate = merged.sampleRate,
                bitDepth = merged.bitDepth,
                bitrate = merged.bitrate,
                path = song.path,
                isFloat = merged.isFloat
            )
            // Only lock cache as exact once complete, or when all extractors finished with sample rate
            val exact = isMetaComplete(merged, song.path) || merged.sampleRate > 0
            return SpecsExtractionResult(formatted, confirmedExact = exact)
        }

        return SpecsExtractionResult("", confirmedExact = false)
    }

    private fun extractWithMediaExtractor(context: Context, song: Song): RawAudioMeta? {
        val extractor = MediaExtractor()
        return try {
            var sourceSet = false
            if (song.path.isNotBlank()) {
                val file = File(song.path)
                if (file.exists() && file.canRead()) {
                    try {
                        extractor.setDataSource(song.path)
                        sourceSet = true
                    } catch (_: Exception) {
                    }
                }
            }
            if (!sourceSet) {
                extractor.setDataSource(context, song.contentUri, null)
            }
            val trackCount = extractor.trackCount
            if (trackCount <= 0) return null

            var audioFormat: MediaFormat? = null
            for (i in 0 until trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val candidateMime = if (candidate.containsKey(MediaFormat.KEY_MIME)) {
                    candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                } else ""
                if (candidateMime.startsWith("audio/", ignoreCase = true)) {
                    audioFormat = candidate
                    break
                }
            }
            val format = audioFormat ?: extractor.getTrackFormat(0)
            val mime = if (format.containsKey(MediaFormat.KEY_MIME)) format.getString(MediaFormat.KEY_MIME).orEmpty() else ""
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
            val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) format.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            val bitDepth = if (format.containsKey("bits-per-sample")) format.getInteger("bits-per-sample") else 0

            if (sampleRate <= 0 && bitrate <= 0 && mime.isBlank() && bitDepth <= 0) return null
            RawAudioMeta(mime = mime, sampleRate = sampleRate, bitDepth = bitDepth, bitrate = bitrate)
        } catch (_: Exception) {
            null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun extractWithMetadataRetriever(context: Context, song: Song): RawAudioMeta? {
        val mmr = MediaMetadataRetriever()
        return try {
            var sourceSet = false
            if (song.path.isNotBlank()) {
                val file = File(song.path)
                if (file.exists() && file.canRead()) {
                    try {
                        mmr.setDataSource(song.path)
                        sourceSet = true
                    } catch (_: Exception) {
                    }
                }
            }
            if (!sourceSet) {
                mmr.setDataSource(context, song.contentUri)
            }

            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val bitDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0

            if (sampleRate <= 0 && bitrate <= 0 && mime.isBlank() && bitDepth <= 0) return null
            RawAudioMeta(mime = mime, sampleRate = sampleRate, bitDepth = bitDepth, bitrate = bitrate)
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeExoPcmEncoding(pcmEncoding: Int): Pair<Int, Boolean> = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16 to false
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24 to false
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32 to false
        C.ENCODING_PCM_FLOAT -> 32 to true
        else -> 0 to false
    }

    private fun mergeAudioMeta(base: RawAudioMeta, extra: RawAudioMeta): RawAudioMeta {
        // Prefer richer lossless/specific MIME (e.g., audio/alac over generic audio/mp4)
        val mergedMime = when {
            base.mime.isBlank() -> extra.mime
            extra.mime.contains("alac", ignoreCase = true) -> extra.mime
            base.mime == "audio/mp4" && extra.mime.isNotBlank() -> extra.mime
            else -> base.mime
        }
        return RawAudioMeta(
            mime = mergedMime,
            sampleRate = if (base.sampleRate > 0) base.sampleRate else extra.sampleRate,
            bitDepth = if (base.bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) base.bitDepth else extra.bitDepth,
            bitrate = if (base.bitrate > 0) base.bitrate else extra.bitrate,
            isFloat = base.isFloat || extra.isFloat
        )
    }

    private fun isMetaComplete(meta: RawAudioMeta, path: String): Boolean {
        if (meta.sampleRate <= 0) return false
        val codecName = resolveCodecName(meta.mime, path, meta.sampleRate)
        if (codecName.startsWith("DSD")) return true
        return if (isLosslessCodec(codecName)) {
            meta.bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH
        } else {
            meta.bitrate > 0
        }
    }

    private fun isLosslessCodec(codecName: String): Boolean {
        return codecName.startsWith("DSD") ||
            codecName in setOf("FLAC", "WAV", "ALAC", "APE", "WV", "PCM", "AIFF")
    }

    private fun isMimeCompatibleWithPath(mime: String?, path: String): Boolean {
        if (mime.isNullOrBlank() || path.isBlank()) return true
        val ext = File(path).extension.lowercase(Locale.ROOT)
        if (ext.isBlank()) return true
        return when (ext) {
            "mp3" -> mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true)
            "flac" -> mime.contains("flac", ignoreCase = true)
            "wav" -> mime.contains("raw", ignoreCase = true) || mime.contains("wav", ignoreCase = true) || mime.contains("pcm", ignoreCase = true)
            "aiff", "aif", "aifc" -> mime.contains("raw", ignoreCase = true) || mime.contains("aiff", ignoreCase = true) || mime.contains("pcm", ignoreCase = true)
            "dsf", "dff" -> mime.contains("dsd", ignoreCase = true) || mime.contains("dsf", ignoreCase = true) || mime.contains("dff", ignoreCase = true)
            "opus" -> mime.contains("opus", ignoreCase = true)
            "ogg", "oga" -> mime.contains("ogg", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true) || mime.contains("opus", ignoreCase = true)
            "m4a", "mp4", "aac" -> mime.contains("mp4", ignoreCase = true) || mime.contains("aac", ignoreCase = true) || mime.contains("alac", ignoreCase = true)
            "alac" -> mime.contains("alac", ignoreCase = true) || mime.contains("mp4", ignoreCase = true)
            else -> true
        }
    }

    private fun estimateAverageBitrateBps(context: Context, song: Song): Int {
        if (song.duration < MIN_DURATION_FOR_BITRATE_EST_MS) return 0
        val fileBytes = resolveFileSizeBytes(context, song)
        if (fileBytes <= 0L) return 0
        val bps = (fileBytes * 8_000L) / song.duration
        return if (bps in MIN_ESTIMATED_LOSSY_BITRATE_BPS..MAX_ESTIMATED_LOSSY_BITRATE_BPS) {
            bps.toInt()
        } else {
            0
        }
    }

    private fun resolveFileSizeBytes(context: Context, song: Song): Long {
        if (song.path.isNotBlank()) {
            val file = File(song.path)
            if (file.exists() && file.canRead()) {
                val len = file.length()
                if (len > 0L) return len
            }
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(song.contentUri, "r")?.use { afd ->
                afd.length.takeIf { it > 0L } ?: 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun extractSourceAudioFormat(
        context: Context,
        uri: Uri?,
        path: String,
        mimeHint: String?
    ): SourceAudioFormat {
        val ext = resolveExtensionFromPathOrUri(context, uri, path, mimeHint)
        return when (ext) {
            "flac" -> {
                val bits = openAudioInputStream(context, uri, path)?.use { parseFlacHeaderInfo(it)?.bitDepth } ?: 0
                SourceAudioFormat(
                    sourceBitsPerSample = bits.takeIf { it in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH }
                        ?: queryBitsPerSampleFallback(context, uri, path),
                    isSourceLossless = true,
                    isSourceFloat = false
                )
            }
            "wav" -> {
                val info = openAudioInputStream(context, uri, path)?.use { parseWavHeaderInfo(it) }
                if (info != null) {
                    SourceAudioFormat(
                        sourceBitsPerSample = info.bitDepth,
                        isSourceLossless = true,
                        isSourceFloat = info.isFloat
                    )
                } else {
                    SourceAudioFormat(
                        sourceBitsPerSample = queryBitsPerSampleFallback(context, uri, path),
                        isSourceLossless = true,
                        isSourceFloat = false
                    )
                }
            }
            "aiff", "aif", "aifc" -> {
                val meta = if (path.isNotBlank()) {
                    AiffMetadataHelper.readMetadata(path)
                        ?: uri?.let { AiffMetadataHelper.readMetadataFromUri(context, it) }
                } else {
                    uri?.let { AiffMetadataHelper.readMetadataFromUri(context, it) }
                }
                if (meta != null && meta.bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                    SourceAudioFormat(
                        sourceBitsPerSample = meta.bitDepth,
                        isSourceLossless = true,
                        isSourceFloat = meta.isFloat
                    )
                } else {
                    SourceAudioFormat(
                        sourceBitsPerSample = queryBitsPerSampleFallback(context, uri, path),
                        isSourceLossless = true,
                        isSourceFloat = false
                    )
                }
            }
            "alac" -> {
                val info = parseMp4AlacHeaderInfo(context, uri, path)
                SourceAudioFormat(
                    sourceBitsPerSample = info?.bitDepth
                        ?: queryBitsPerSampleFallback(context, uri, path),
                    isSourceLossless = true,
                    isSourceFloat = false
                )
            }
            "m4a", "mp4" -> {
                // Distinguish lossless ALAC from lossy AAC inside ISO BMFF (.m4a/.mp4) containers
                val alacInfo = parseMp4AlacHeaderInfo(context, uri, path)
                if (alacInfo != null) {
                    SourceAudioFormat(
                        sourceBitsPerSample = alacInfo.bitDepth,
                        isSourceLossless = true,
                        isSourceFloat = false
                    )
                } else {
                    SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false)
                }
            }
            "ape" -> {
                val bits = openAudioInputStream(context, uri, path)?.use { parseApeHeaderInfo(it)?.bitDepth } ?: 0
                SourceAudioFormat(
                    sourceBitsPerSample = bits.takeIf { it in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH }
                        ?: queryBitsPerSampleFallback(context, uri, path),
                    isSourceLossless = true,
                    isSourceFloat = false
                )
            }
            "wv" -> {
                val bits = openAudioInputStream(context, uri, path)?.use { parseWavPackHeaderInfo(it)?.bitDepth } ?: 0
                SourceAudioFormat(
                    sourceBitsPerSample = bits.takeIf { it in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH }
                        ?: queryBitsPerSampleFallback(context, uri, path),
                    isSourceLossless = true,
                    isSourceFloat = false
                )
            }
            "dsf", "dff" -> SourceAudioFormat(sourceBitsPerSample = 1, isSourceLossless = true, isSourceFloat = false)
            "mp3", "aac", "ogg", "oga", "opus", "wma", "eac3", "ec3", "ac3", "m4b", "amr", "3gp" ->
                SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false)
            else -> {
                val lowerMime = mimeHint?.lowercase(Locale.ROOT).orEmpty()
                val losslessByMime = lowerMime.contains("flac") ||
                    lowerMime.contains("alac") ||
                    lowerMime.contains("wav") ||
                    lowerMime.contains("aiff") ||
                    lowerMime.contains("ape") ||
                    lowerMime.contains("wavpack")
                if (losslessByMime) {
                    SourceAudioFormat(
                        sourceBitsPerSample = queryBitsPerSampleFallback(context, uri, path),
                        isSourceLossless = true,
                        isSourceFloat = false
                    )
                } else {
                    SourceAudioFormat(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false)
                }
            }
        }
    }

    private fun openAudioInputStream(context: Context, song: Song): InputStream? =
        openAudioInputStream(context, song.contentUri, song.path)

    private fun openAudioInputStream(context: Context, uri: Uri?, path: String): InputStream? {
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    return FileInputStream(file)
                } catch (_: Exception) {
                }
            }
        }
        if (uri == null || uri == Uri.EMPTY) return null
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveExtension(context: Context, song: Song): String =
        resolveExtensionFromPathOrUri(context, song.contentUri, song.path, mimeHint = null)

    private fun resolveExtensionFromPathOrUri(
        context: Context,
        uri: Uri?,
        path: String,
        mimeHint: String?
    ): String {
        val fromPath = if (path.isNotBlank()) File(path).extension.lowercase(Locale.ROOT) else ""
        if (fromPath.isNotBlank()) return fromPath
        val uriPathExt = uri?.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            .orEmpty()
        if (uriPathExt.isNotBlank()) return uriPathExt

        val mime = mimeHint?.takeIf { it.isNotBlank() } ?: try {
            if (uri != null && uri != Uri.EMPTY) context.contentResolver.getType(uri).orEmpty() else ""
        } catch (_: Exception) {
            ""
        }
        return when {
            mime.contains("flac", ignoreCase = true) -> "flac"
            mime.contains("wavpack", ignoreCase = true) -> "wv"
            mime.contains("wav", ignoreCase = true) -> "wav"
            mime.contains("aiff", ignoreCase = true) -> "aiff"
            mime.contains("ape", ignoreCase = true) || mime.contains("monkeys", ignoreCase = true) -> "ape"
            mime.contains("dsf", ignoreCase = true) -> "dsf"
            mime.contains("dff", ignoreCase = true) -> "dff"
            mime.contains("dsd", ignoreCase = true) -> "dsf"
            mime.contains("alac", ignoreCase = true) -> "alac"
            mime.contains("mp4", ignoreCase = true) -> "m4a"
            mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "mp3"
            mime.contains("opus", ignoreCase = true) -> "opus"
            mime.contains("ogg", ignoreCase = true) || mime.contains("vorbis", ignoreCase = true) -> "ogg"
            mime.contains("aac", ignoreCase = true) -> "aac"
            mime.contains("eac3", ignoreCase = true) || mime.contains("ec-3", ignoreCase = true) -> "eac3"
            else -> ""
        }
    }

    private fun queryBitsPerSampleFallback(context: Context, uri: Uri?, path: String): Int {
        val extractor = MediaExtractor()
        try {
            var sourceSet = false
            if (path.isNotBlank()) {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    try {
                        extractor.setDataSource(path)
                        sourceSet = true
                    } catch (_: Exception) {
                    }
                }
            }
            if (!sourceSet && uri != null && uri != Uri.EMPTY) {
                extractor.setDataSource(context, uri, null)
                sourceSet = true
            }
            if (sourceSet && extractor.trackCount > 0) {
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = if (fmt.containsKey(MediaFormat.KEY_MIME)) fmt.getString(MediaFormat.KEY_MIME).orEmpty() else ""
                    if (mime.startsWith("audio/", ignoreCase = true) && fmt.containsKey("bits-per-sample")) {
                        val bits = fmt.getInteger("bits-per-sample")
                        if (bits in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) return bits
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mmr = MediaMetadataRetriever()
            try {
                var sourceSet = false
                if (path.isNotBlank()) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        try {
                            mmr.setDataSource(path)
                            sourceSet = true
                        } catch (_: Exception) {
                        }
                    }
                }
                if (!sourceSet && uri != null && uri != Uri.EMPTY) {
                    mmr.setDataSource(context, uri)
                    sourceSet = true
                }
                if (sourceSet) {
                    val bits = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
                    if (bits in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) return bits
                }
            } catch (_: Exception) {
            } finally {
                runCatching { mmr.release() }
            }
        }
        return 0
    }

    private fun parseFlacHeader(stream: InputStream): String? {
        val info = parseFlacHeaderInfo(stream) ?: return null
        return "${info.codec} • ${info.bitDepth}-bit/${formatKhz(info.sampleRate)}"
    }

    private fun parseFlacHeaderInfo(stream: InputStream): ParsedHeaderInfo? {
        return try {
            val marker = ByteArray(4)
            if (!readFully(stream, marker, 4)) return null

            // Skip optional prepended ID3v2 tag before locating the "fLaC" magic + STREAMINFO block
            if (marker[0] == 'I'.code.toByte() && marker[1] == 'D'.code.toByte() && marker[2] == '3'.code.toByte()) {
                val restId3 = ByteArray(ID3_HEADER_SIZE - 4)
                if (!readFully(stream, restId3, restId3.size)) return null
                val b6 = restId3[2].toLong() and 0x7F
                val b7 = restId3[3].toLong() and 0x7F
                val b8 = restId3[4].toLong() and 0x7F
                val b9 = restId3[5].toLong() and 0x7F
                val id3Size = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
                if (id3Size < 0 || id3Size > MAX_ID3_SKIP_BYTES) return null
                if (!skipFully(stream, id3Size)) return null
                if (!readFully(stream, marker, 4)) return null
            }

            if (marker[0] != 'f'.code.toByte() || marker[1] != 'L'.code.toByte() ||
                marker[2] != 'a'.code.toByte() || marker[3] != 'C'.code.toByte()
            ) {
                return null
            }

            val streamInfo = ByteArray(FLAC_STREAMINFO_BYTES)
            if (!readFully(stream, streamInfo, FLAC_STREAMINFO_BYTES)) return null

            // Bytes 14..21 of STREAMINFO (bytes 18..25 from file start) pack 20-bit sampleRate & 5-bit bitsPerSample
            var value = 0L
            for (i in 14..21) {
                value = (value shl 8) or (streamInfo[i].toLong() and 0xFF)
            }
            val sampleRate = (value ushr 44).toInt()
            val bitsPerSample = (((value ushr 36) and 0x1F) + 1).toInt()
            if (sampleRate > 0 && bitsPerSample in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                ParsedHeaderInfo(codec = "FLAC", sampleRate = sampleRate, bitDepth = bitsPerSample, isFloat = false)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseWavHeader(stream: InputStream): String? {
        val info = parseWavHeaderInfo(stream) ?: return null
        val khz = formatKhz(info.sampleRate)
        return if (info.isFloat) {
            "WAV • ${info.bitDepth}-bit float/$khz"
        } else {
            "WAV • ${info.bitDepth}-bit/$khz"
        }
    }

    private fun parseWavHeaderInfo(stream: InputStream): ParsedHeaderInfo? {
        return try {
            val riffHeader = ByteArray(RIFF_HEADER_SIZE)
            if (!readFully(stream, riffHeader, RIFF_HEADER_SIZE)) return null

            val containerTag = String(riffHeader, 0, 4, Charsets.US_ASCII)
            val waveTag = String(riffHeader, 8, 4, Charsets.US_ASCII)
            if ((containerTag != "RIFF" && containerTag != "RF64") || waveTag != "WAVE") {
                return null
            }

            val chunkHeader = ByteArray(CHUNK_HEADER_SIZE)
            var bytesSkipped = 0L
            for (chunkIndex in 0 until MAX_WAV_CHUNKS_WALKED) {
                if (!readFully(stream, chunkHeader, CHUNK_HEADER_SIZE)) return null
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = readLeU32(chunkHeader, 4)

                if (chunkId == "fmt " && chunkSize >= WAV_FMT_MIN_BYTES) {
                    val toRead = minOf(chunkSize, WAV_FMT_MAX_READ_BYTES.toLong()).toInt()
                    val fmtBuf = ByteArray(toRead)
                    if (!readFully(stream, fmtBuf, toRead)) return null

                    val audioFormat = readLeU16(fmtBuf, 0)
                    val sampleRate = readLeU32(fmtBuf, 4).toInt()
                    var bitsPerSample = readLeU16(fmtBuf, 14)
                    var isFloat = audioFormat == WAVE_FORMAT_IEEE_FLOAT

                    if (audioFormat == WAVE_FORMAT_EXTENSIBLE && toRead >= WAV_FMT_MAX_READ_BYTES) {
                        val validBits = readLeU16(fmtBuf, 18)
                        if (validBits in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                            bitsPerSample = validBits
                        }
                        val subFormatTag = readLeU16(fmtBuf, 24)
                        isFloat = subFormatTag == WAVE_FORMAT_IEEE_FLOAT
                    }

                    if (sampleRate > 0 && bitsPerSample in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                        return ParsedHeaderInfo(
                            codec = "WAV",
                            sampleRate = sampleRate,
                            bitDepth = bitsPerSample,
                            isFloat = isFloat
                        )
                    }
                    return null
                }

                // RIFF chunks are word-aligned (padded with 1 byte when chunkSize is odd)
                val paddedSkip = chunkSize + (chunkSize and 1L)
                if (paddedSkip < 0L || bytesSkipped + paddedSkip > MAX_WAV_BYTES_SKIPPED) {
                    return null
                }
                if (paddedSkip > 0L) {
                    if (!skipFully(stream, paddedSkip)) return null
                    bytesSkipped += paddedSkip
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses Monkey's Audio (`MAC `) headers (both v3.98+ descriptor and legacy pre-3.98 formats)
     * to read native lossless bit-depth without loading audio frames.
     */
    private fun parseApeHeaderInfo(stream: InputStream): ParsedHeaderInfo? {
        return try {
            val head = ByteArray(APE_HEADER_PROBE_BYTES)
            if (!readFully(stream, head, head.size)) return null
            if (String(head, 0, 4, Charsets.US_ASCII) != "MAC ") return null

            val version = readLeU16(head, 4)
            if (version >= APE_NEW_FORMAT_MIN_VERSION) {
                val descriptorBytes = readLeU32(head, 8).toInt()
                if (descriptorBytes < APE_DESCRIPTOR_HEADER_MIN_BYTES || descriptorBytes > APE_MAX_DESCRIPTOR_BYTES) {
                    return null
                }
                val skipRest = (descriptorBytes - head.size).toLong()
                if (skipRest > 0L && !skipFully(stream, skipRest)) return null
                val audioHdr = ByteArray(APE_AUDIO_HEADER_MIN_BYTES)
                if (!readFully(stream, audioHdr, audioHdr.size)) return null
                val bitsPerSample = readLeU16(audioHdr, 16)
                val sampleRate = readLeU32(audioHdr, 20).toInt()
                if (bitsPerSample in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                    ParsedHeaderInfo(codec = "APE", sampleRate = sampleRate, bitDepth = bitsPerSample, isFloat = false)
                } else {
                    null
                }
            } else {
                val formatFlags = readLeU16(head, 8)
                val sampleRate = readLeU32(head, 12).toInt()
                val bitsPerSample = when {
                    (formatFlags and 0x01) != 0 -> 8
                    (formatFlags and 0x08) != 0 -> 24
                    else -> 16
                }
                ParsedHeaderInfo(codec = "APE", sampleRate = sampleRate, bitDepth = bitsPerSample, isFloat = false)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the 32-byte WavPack (`wvpk`) block header to extract exact source bit-depth.
     */
    private fun parseWavPackHeaderInfo(stream: InputStream): ParsedHeaderInfo? {
        return try {
            val hdr = ByteArray(WAVPACK_HEADER_BYTES)
            if (!readFully(stream, hdr, hdr.size)) return null
            if (String(hdr, 0, 4, Charsets.US_ASCII) != "wvpk") return null

            val flags = readLeU32(hdr, 24).toInt()
            val bytesPerSample = (flags and WAVPACK_BYTES_PER_SAMPLE_MASK) + 1
            val bitsPerSample = bytesPerSample * 8
            if (bitsPerSample in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
                ParsedHeaderInfo(codec = "WV", sampleRate = 0, bitDepth = bitsPerSample, isFloat = false)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Seeks directly to MP4 `moov` → `trak` → `mdia` → `minf` → `stbl` → `stsd` → `alac` so
     * `ALACSpecificConfig` bit-depth and sample rate resolve in O(1) without scanning `mdat`.
     */
    private fun parseMp4AlacHeader(context: Context, song: Song): String? {
        val info = parseMp4AlacHeaderInfo(context, song.contentUri, song.path) ?: return null
        return "ALAC • ${info.bitDepth}-bit/${formatKhz(info.sampleRate)}"
    }

    private fun parseMp4AlacHeaderInfo(context: Context, uri: Uri?, path: String): ParsedHeaderInfo? {
        if (path.isNotBlank()) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    FileInputStream(file).use { fis ->
                        return parseMp4AlacFromChannel(fis.channel)
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (uri == null || uri == Uri.EMPTY) return null
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    parseMp4AlacFromChannel(fis.channel)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMp4AlacFromChannel(channel: FileChannel): ParsedHeaderInfo? {
        val fileSize = channel.size()
        var pos = 0L
        for (i in 0 until MP4_MAX_TOP_BOXES) {
            if (pos + MP4_BOX_HEADER_SIZE > fileSize) break
            val box = readMp4BoxHeader(channel, pos, fileSize) ?: break
            val boxEnd = pos + box.size
            if (box.type == "moov") {
                return findAlacInContainer(channel, pos + box.headerLen, boxEnd, depth = 0)
            }
            pos = boxEnd
        }
        return null
    }

    private fun findAlacInContainer(channel: FileChannel, start: Long, end: Long, depth: Int): ParsedHeaderInfo? {
        if (depth > MP4_MAX_DEPTH || start >= end) return null
        var pos = start
        for (i in 0 until MP4_MAX_CONTAINER_BOXES) {
            if (pos + MP4_BOX_HEADER_SIZE > end) break
            val box = readMp4BoxHeader(channel, pos, end) ?: break
            val boxEnd = pos + box.size
            val payloadStart = pos + box.headerLen
            when (box.type) {
                "trak", "mdia", "minf", "stbl" -> {
                    findAlacInContainer(channel, payloadStart, boxEnd, depth + 1)?.let { return it }
                }
                "stsd" -> {
                    parseStsdForAlac(channel, payloadStart, boxEnd)?.let { return it }
                }
            }
            pos = boxEnd
        }
        return null
    }

    private fun parseStsdForAlac(channel: FileChannel, start: Long, end: Long): ParsedHeaderInfo? {
        if (start + MP4_STSD_HEADER_SIZE > end) return null
        val header = readChannelBytes(channel, start, MP4_STSD_HEADER_SIZE) ?: return null
        val entryCount = readBeU32(header, 4).coerceAtMost(16L).toInt()
        var pos = start + MP4_STSD_HEADER_SIZE
        for (idx in 0 until entryCount) {
            if (pos + MP4_BOX_HEADER_SIZE > end) break
            val entry = readMp4BoxHeader(channel, pos, end) ?: break
            val entryEnd = pos + entry.size
            if (entry.type == "alac") {
                var childPos = pos + entry.headerLen + MP4_AUDIO_SAMPLE_ENTRY_BYTES
                for (c in 0 until 16) {
                    if (childPos + MP4_BOX_HEADER_SIZE > entryEnd) break
                    val child = readMp4BoxHeader(channel, childPos, entryEnd) ?: break
                    val childEnd = childPos + child.size
                    val childPayloadStart = childPos + child.headerLen
                    val childPayloadLen = childEnd - childPayloadStart
                    if (child.type == "alac" && childPayloadLen >= ALAC_SPECIFIC_CONFIG_BYTES) {
                        // Standard ISO BMFF ALAC atom wraps a 4-byte FullBox version/flags prefix;
                        // older encoders occasionally emit raw 24-byte ALACSpecificConfig directly.
                        val cfgOffset = if (childPayloadLen >= ALAC_FULLBOX_VERSION_BYTES + ALAC_SPECIFIC_CONFIG_BYTES) {
                            childPayloadStart + ALAC_FULLBOX_VERSION_BYTES
                        } else {
                            childPayloadStart
                        }
                        val cfg = readChannelBytes(channel, cfgOffset, ALAC_SPECIFIC_CONFIG_BYTES) ?: break
                        val compatVer = cfg[4].toInt() and 0xFF
                        val bitDepth = cfg[5].toInt() and 0xFF
                        val sampleRate = readBeU32(cfg, 20)
                        if (compatVer == ALAC_COMPATIBLE_VERSION &&
                            bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH &&
                            sampleRate in MIN_VALID_SAMPLE_RATE_HZ.toLong()..MAX_VALID_SAMPLE_RATE_HZ.toLong()
                        ) {
                            return ParsedHeaderInfo(
                                codec = "ALAC",
                                sampleRate = sampleRate.toInt(),
                                bitDepth = bitDepth,
                                isFloat = false
                            )
                        }
                    }
                    childPos = childEnd
                }
            }
            pos = entryEnd
        }
        return null
    }

    private fun readMp4BoxHeader(channel: FileChannel, offset: Long, limit: Long): Mp4BoxHeader? {
        if (offset + MP4_BOX_HEADER_SIZE > limit) return null
        val buf = readChannelBytes(channel, offset, MP4_BOX_HEADER_SIZE) ?: return null
        val size32 = readBeU32(buf, 0)
        val type = String(buf, 4, 4, Charsets.ISO_8859_1)
        val (size, headerLen) = when (size32) {
            1L -> {
                if (offset + MP4_EXTENDED_HEADER_SIZE > limit) return null
                val extBuf = readChannelBytes(channel, offset + MP4_BOX_HEADER_SIZE, 8) ?: return null
                val extSize = (readBeU32(extBuf, 0) shl 32) or readBeU32(extBuf, 4)
                extSize to MP4_EXTENDED_HEADER_SIZE
            }
            0L -> (limit - offset) to MP4_BOX_HEADER_SIZE
            else -> size32 to MP4_BOX_HEADER_SIZE
        }
        if (size < headerLen || offset + size > limit) return null
        return Mp4BoxHeader(size = size, headerLen = headerLen, type = type)
    }

    private fun readChannelBytes(channel: FileChannel, offset: Long, length: Int): ByteArray? {
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

    // ═══ Stream & Formatting Helpers ═══
    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, total, length - total)
            if (read <= 0) return false
            total += read
        }
        return true
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (read <= 0) return false
                remaining -= read
            }
        }
        return true
    }

    private fun readLeU16(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readLeU32(buf: ByteArray, offset: Int): Long {
        return (buf[offset].toLong() and 0xFF) or
            ((buf[offset + 1].toLong() and 0xFF) shl 8) or
            ((buf[offset + 2].toLong() and 0xFF) shl 16) or
            ((buf[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readBeU32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
    }

    /**
     * Parses raw Sony DSF (`DSD ` / `fmt `) and Philips DSDIFF (`FRM8` / `DSD ` / `PROP` / `FS  `)
     * headers to resolve exact `DSD64` / `DSD128` / `DSD256` / `DSD512` display labels without Binder IPC.
     */
    private fun parseDsdHeader(stream: InputStream): String? {
        return try {
            val head = ByteArray(16)
            if (!readFully(stream, head, head.size)) return null
            val magic = String(head, 0, 4, Charsets.US_ASCII)

            val sampleRate = when {
                magic == "DSD " -> {
                    val rest = ByteArray(DSD_HEADER_PROBE_BYTES - head.size)
                    if (!readFully(stream, rest, rest.size)) return null
                    val fmtId = String(rest, 12, 4, Charsets.US_ASCII)
                    if (fmtId != "fmt ") return null
                    readLeU32(rest, 12 + 28).toInt()
                }
                magic == "FRM8" && String(head, 12, 4, Charsets.US_ASCII) == "DSD " -> {
                    parseDffSampleRate(stream)
                }
                else -> 0
            }

            if (sampleRate > 0) {
                "${resolveDsdLabel(sampleRate)} • 1-bit/${formatMhz(sampleRate)}"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDffSampleRate(stream: InputStream): Int {
        val chunkHeader = ByteArray(DFF_CHUNK_HEADER_BYTES)
        var bytesSkipped = 0L
        for (i in 0 until MAX_DFF_CHUNKS_WALKED) {
            if (!readFully(stream, chunkHeader, DFF_CHUNK_HEADER_BYTES)) return 0
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = readBeU64(chunkHeader, 4)
            if (chunkSize < 0L) return 0
            val padded = chunkSize + (chunkSize and 1L)

            if (chunkId == "PROP" && chunkSize >= 4L) {
                val propType = ByteArray(4)
                if (!readFully(stream, propType, 4)) return 0
                var remaining = chunkSize - 4L
                if (String(propType, 0, 4, Charsets.US_ASCII) == "SND ") {
                    while (remaining >= DFF_CHUNK_HEADER_BYTES) {
                        if (!readFully(stream, chunkHeader, DFF_CHUNK_HEADER_BYTES)) return 0
                        remaining -= DFF_CHUNK_HEADER_BYTES
                        val subId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                        val subSize = readBeU64(chunkHeader, 4)
                        if (subSize < 0L || subSize > remaining) return 0
                        val subPadded = subSize + (subSize and 1L)
                        if (subId == "FS  " && subSize >= 4L) {
                            val fsBuf = ByteArray(4)
                            if (!readFully(stream, fsBuf, 4)) return 0
                            return readBeU32(fsBuf, 0).toInt()
                        }
                        if (subPadded > 0L) {
                            if (!skipFully(stream, minOf(subPadded, remaining))) return 0
                            remaining -= minOf(subPadded, remaining)
                        }
                    }
                    return 0
                }
                val skipLeft = remaining + (chunkSize and 1L)
                if (skipLeft > 0L) {
                    if (bytesSkipped + skipLeft > MAX_DFF_BYTES_SKIPPED) return 0
                    if (!skipFully(stream, skipLeft)) return 0
                    bytesSkipped += skipLeft
                }
            } else {
                if (chunkId == "DSD " || padded > MAX_DFF_BYTES_SKIPPED - bytesSkipped) return 0
                if (padded > 0L) {
                    if (!skipFully(stream, padded)) return 0
                    bytesSkipped += padded
                }
            }
        }
        return 0
    }

    private fun readBeU64(buf: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        return value
    }

    private fun resolveDsdLabel(sampleRate: Int): String = when {
        sampleRate >= DSD512_THRESHOLD_HZ -> "DSD512"
        sampleRate >= DSD256_THRESHOLD_HZ -> "DSD256"
        sampleRate >= DSD128_THRESHOLD_HZ -> "DSD128"
        sampleRate >= DSD64_THRESHOLD_HZ -> "DSD64"
        else -> "DSD"
    }

    private fun resolveCodecName(mime: String, path: String, sampleRate: Int = 0): String {
        val ext = if (path.isNotBlank()) File(path).extension.uppercase(Locale.ROOT) else ""
        return when {
            mime.contains("dsd", true) || mime.contains("dsf", true) || mime.contains("dff", true) ||
                ext == "DSF" || ext == "DFF" -> resolveDsdLabel(sampleRate)
            mime.contains("flac", true) || ext == "FLAC" -> "FLAC"
            mime.contains("wav", true) || ext == "WAV" -> "WAV"
            mime.contains("aiff", true) || ext == "AIFF" || ext == "AIF" || ext == "AIFC" -> "AIFF"
            mime.contains("mpeg", true) || ext == "MP3" -> "MP3"
            mime.contains("alac", true) || ext == "ALAC" -> "ALAC"
            mime.contains("mp4a", true) || mime.contains("aac", true) || ext == "AAC" || ext == "M4A" -> "AAC"
            mime.contains("opus", true) || ext == "OPUS" -> "OPUS"
            mime.contains("ogg", true) || mime.contains("vorbis", true) || ext == "OGG" -> "OGG"
            ext.isNotBlank() -> ext
            else -> "AUDIO"
        }
    }

    private fun formatSpecs(
        mime: String,
        sampleRate: Int,
        bitDepth: Int,
        bitrate: Int,
        path: String,
        isFloat: Boolean = false
    ): String {
        val codecName = resolveCodecName(mime, path, sampleRate)
        if (codecName.startsWith("DSD")) {
            return if (sampleRate > 0) "$codecName • 1-bit/${formatMhz(sampleRate)}" else codecName
        }

        val khz = if (sampleRate > 0) formatKhz(sampleRate) else ""
        val depthPrefix = if (bitDepth in MIN_VALID_BIT_DEPTH..MAX_VALID_BIT_DEPTH) {
            if (isFloat) "$bitDepth-bit float/" else "$bitDepth-bit/"
        } else {
            ""
        }
        val kbpsStr = if (bitrate > 0) "${bitrate / 1000} kbps" else ""

        return when (codecName) {
            "FLAC", "WAV", "ALAC", "AIFF" -> {
                if (khz.isNotEmpty()) "$codecName • $depthPrefix$khz"
                else if (kbpsStr.isNotEmpty()) "$codecName • $kbpsStr"
                else codecName
            }
            "MP3", "AAC", "OGG", "OPUS" -> listOf(codecName, kbpsStr, khz)
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
            else -> listOf(codecName, "$depthPrefix$khz".trimEnd('/'))
                .filter { it.isNotEmpty() }
                .joinToString(" • ")
        }
    }

    fun formatKhz(sampleRate: Int): String {
        if (sampleRate <= 0) return ""
        val roundedTenths = round((sampleRate / 1000.0) * 10.0).toLong()
        return if (roundedTenths % 10L == 0L) {
            "${roundedTenths / 10L} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", roundedTenths / 10.0)
        }
    }

    fun formatMhz(sampleRate: Int): String {
        if (sampleRate <= 0) return ""
        val roundedTenths = round((sampleRate / 1_000_000.0) * 10.0).toLong()
        return if (roundedTenths % 10L == 0L) {
            "${roundedTenths / 10L} MHz"
        } else {
            String.format(Locale.US, "%.1f MHz", roundedTenths / 10.0)
        }
    }
}
