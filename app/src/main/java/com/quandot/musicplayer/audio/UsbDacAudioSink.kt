/**
 * UsbDacAudioSink — Media3 AudioSink router for direct USB DAC PCM/DSD playback and HAL fallback.
 *
 * @remarks
 * Claims native 16/24/32-bit PCM, IEEE-754 float PCM, and DSD streams directly from ExoPlayer
 * whenever a USB DAC is ready, disabling Android compressed offload so lossless/lossy codecs
 * decode to raw PCM without premature OS resampling or bit-depth truncation. Keeps
 * `C.ENCODING_PCM_FLOAT` advertised so `FfmpegAudioRenderer` decodes 24-bit and lossy tracks at
 * full float32 precision, then restores non-float sources back to bit-exact integer PCM
 * (`PCM_16BIT`, 3-byte packed `PCM_24BIT`, or `PCM_32BIT`) via a reusable direct `ByteBuffer` so
 * the native C++ driver engages `PCM_BIT_PERFECT` rather than float DSP.
 *
 * When playing DSD without a ready USB DAC (speaker, Bluetooth, wired headset, USB Direct disabled,
 * or mid-stream USB unplug), engages an internal multi-stage Kaiser FIR DSD-to-PCM decimator so
 * raw `audio/dsd` streams from [DsdExtractor] fall back smoothly into `DefaultAudioSink` without
 * requiring an external FFmpeg DSD decoder. Presentation timestamps are derived from wire frames
 * emitted at the negotiated DAC clock rate so clock progression stays exact across restored
 * integer PCM, resampled PCM, DoP, Native DSD, and DSD-to-PCM decimation modes.
 *
 * @see DsdExtractor
 * @see UsbDacManager
 * @see UsbDacNative
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Internal modules
import com.quandot.musicplayer.debug.AppLogManager
import com.quandot.musicplayer.debug.PDLog

// ═══ Constants ═══
private const val MICROS_PER_SECOND = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val HAL_HIRES_SAMPLE_RATE_THRESHOLD_HZ = 48_000
private const val HS_ISO_PIPELINE_LATENCY_US = 24_000L // Fallback when native latency is unavailable
private const val FS_ISO_PIPELINE_LATENCY_US = 80_000L
private const val UNDERRUN_REPORT_COOLDOWN_MS = 500L

private const val PCM_16_SCALE = 32_768.0f
private const val PCM_16_MIN = -32_768
private const val PCM_16_MAX = 32_767
private const val PCM_24_SCALE = 8_388_608.0f
private const val PCM_24_MIN = -8_388_608
private const val PCM_24_MAX = 8_388_607
private const val PCM_32_SCALE = 2_147_483_648.0
private const val PCM_32_MIN = Int.MIN_VALUE.toLong()
private const val PCM_32_MAX = Int.MAX_VALUE.toLong()
private const val INITIAL_FLOAT_CONVERT_BUFFER_BYTES = 16_384
private const val CONVERT_BUFFER_ALIGN_BYTES = 4_096

private const val MIME_AUDIO_X_DSD = "audio/x-dsd"
private const val MIME_AUDIO_DSF = "audio/dsf"
private const val MIME_AUDIO_DFF = "audio/dff"
private const val MIME_AUDIO_DSD_U8 = "audio/dsd-u8"
private const val MIME_AUDIO_DSD_U32_BE = "audio/dsd-u32be"
private const val MIME_AUDIO_X_DSD_U32_BE = "audio/x-dsd-u32be"
private const val MIME_AUDIO_DSD_U32_LE = "audio/dsd-u32le"
private const val MIME_AUDIO_X_DSD_U32_LE = "audio/x-dsd-u32le"

private const val FALLBACK_DSD_MAX_OUT_FRAMES = 2_048
private const val FALLBACK_DSD_STAGING_BYTES = 131_072
private const val TARGET_BUFFER_DURATION_MS = 2_500L // 2.5-3.0 s adaptive headroom for Non-Hi-Res & Hi-Res/DSD
private const val MIN_BUFFER_DURATION_MS = 2_000L
private const val MAX_BUFFER_DURATION_MS = 3_000L
private const val HAL_FALLBACK_MAX_BUFFER_BYTES = 960 * 1024
private val FALLBACK_DSD_PREFERRED_RATES = intArrayOf(88_200, 96_000, 44_100, 48_000, 176_400, 192_000)

// ═══ State & Contracts ═══
private data class SourceFormatHint(
    val mediaId: String? = null,
    val sourceBitsPerSample: Int = 0,
    val isSourceLossless: Boolean = false,
    val isSourceFloat: Boolean = false
)

private data class InBandSourceFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val hint: SourceFormatHint,
    val sourceLabel: String
)

private data class PendingUsbDirectConfig(
    val inputFormat: Format,
    val specifiedBufferSize: Int,
    val outputChannels: IntArray?,
    val rawEncoding: Int,
    val targetEncoding: Int,
    val preferredBits: Int,
    val shouldRestoreFloat: Boolean,
    val encLabel: String,
    val fromInBandHint: Boolean = false
)

// ═══ AudioSink Router ═══
@UnstableApi
class UsbDacAudioSink(
    private val fallbackSink: AudioSink
) : AudioSink {

    // ═══ State ═══
    private var listener: AudioSink.Listener? = null
    @Volatile
    var onWarmUpCompleted: (() -> Unit)? = null
    @Volatile
    var onUsbDacUnpluggedDuringPlayback: (() -> Unit)? = null
    @Volatile
    private var usingUsbDirect = false
    @Volatile
    private var playing = false
    @Volatile
    private var fallbackConfigured = false
    @Volatile
    private var suppressFallbackUntilNextPlay = false
    @Volatile
    private var pendingUsbDirectReset = false
    @Volatile
    private var sourceFormatHint = SourceFormatHint()
    @Volatile
    private var nextSourceFormatHint: SourceFormatHint? = null
    @Volatile
    private var inBandSourceFormat: InBandSourceFormat? = null
    @Volatile
    private var configuredFromInBandHint = false
    @Volatile
    private var pendingSourceReconfigure = false

    private var activeWireSampleRate = 0
    private var activeWireBitDepth = 0
    private var activeWireSubslotSize = 0
    private var activeWireChannels = 2
    private var activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE
    private var activeStreamGeneration = 0L
    private var activeNativeEncoding = UsbDacNative.ENCODING_PCM_16BIT
    private var activePreferredBitDepth = 16
    private var activeInputSampleRate = 0
    private var activeInputChannels = 2
    private var activeInputPcmFrameSize = 0
    private var activeOutputPcmFrameSize = 0
    private var restoringFromFloat = false
    private var estimatedPipelineLatencyUs = HS_ISO_PIPELINE_LATENCY_US

    private var startPresentationTimeUs = C.TIME_UNSET
    private var lastReportedPositionUs = C.TIME_UNSET
    private var baseFramesWritten = 0L
    private var pendingDiscontinuitySync = false
    private var pendingUsbConfig: PendingUsbDirectConfig? = null
    private var pendingDrainCompleteRealtimeMs = 0L
    private var eosDrainCompleteRealtimeMs = 0L
    private var wasWarmingUp = false
    private var handledEndOfStream = false
    private var loggedEndOfStream = false
    private var lastFeedRealtimeMs = 0L
    private var lastUnderrunReportMs = 0L
    private var lastObservedUnderrunCount = 0L

    private var playbackParameters = PlaybackParameters.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var skipSilenceEnabled = false

    // Reusable buffers avoid per-buffer GC churn on the ExoPlayer render thread
    private var scratchCopyBuffer = ByteArray(0)
    private var floatConvertBuffer: ByteBuffer? = null
    private var floatConvertPendingOffset = 0
    private var floatConvertPendingBytes = 0

    // Native C++ FIR DSD-to-PCM fallback state for speaker/Bluetooth/non-USB output or mid-stream unplug
    @Volatile
    private var fallbackDsdActive = false
    private var fallbackDsdInputEncoding = 0
    private var fallbackDsdOutputSampleRate = 0
    private var fallbackDsdOutputFrameBytes = 0
    private var fallbackDsdStagingBuffer: ByteBuffer? = null
    private var fallbackDsdOutBuffer: ByteBuffer? = null
    private var fallbackDsdOutPtsUs = C.TIME_UNSET
    private var lastFallbackDsdInputPtsUs = C.TIME_UNSET
    private var fallbackDsdNextPtsUs = C.TIME_UNSET

    @Volatile
    private var lastConfiguredFormat: Format? = null
    @Volatile
    private var lastSpecifiedBufferSize: Int = 0
    @Volatile
    private var lastOutputChannels: IntArray? = null

    private val routingListener: () -> Unit = {
        listener?.onAudioCapabilitiesChanged()
    }

    init {
        UsbDacManager.addRoutingListener(routingListener)
    }

    // ═══ Source Format Metadata ═══
    /**
     * Receives the exact container/extractor `Format` synchronously on ExoPlayer's Playback Thread
     * from [SourceFormatTrackingAudioRenderer] right before the decoder opens and calls [configure].
     * Eliminates gapless bit-depth races against `onMediaItemTransition()` and async header probes.
     */
    fun onDecoderInputFormat(format: Format) {
        val parsed = parseDecoderInputFormat(format)
        inBandSourceFormat = parsed
        if (parsed != null) {
            PDLog.d(PDLog.USB_DAC, "SINK_INBAND_FMT") {
                "src=${parsed.sourceLabel} rate=${parsed.sampleRate} ch=${parsed.channelCount} " +
                    "bits=${parsed.hint.sourceBitsPerSample} lossless=${parsed.hint.isSourceLossless} " +
                    "float=${parsed.hint.isSourceFloat}"
            }
        }
    }

    /**
     * Supplies container-level source bit-depth, lossless flag, and true float32 flag for the active
     * track so `FfmpegAudioRenderer`'s `C.ENCODING_PCM_FLOAT` stream can be restored bit-exactly to
     * integer PCM (`PCM_16BIT` / 3-byte `PCM_24BIT` / `PCM_32BIT`) for native Bit-Perfect output.
     */
    fun setSourceAudioFormat(
        sourceBitsPerSample: Int,
        isSourceLossless: Boolean,
        isSourceFloat: Boolean,
        mediaId: String? = null
    ) {
        val next = SourceFormatHint(
            mediaId = mediaId,
            sourceBitsPerSample = sourceBitsPerSample,
            isSourceLossless = isSourceLossless,
            isSourceFloat = isSourceFloat
        )
        sourceFormatHint = next
        if (mediaId != null && nextSourceFormatHint?.mediaId == mediaId) {
            nextSourceFormatHint = null
        }
        // When the active stream was already configured from an exact in-band Extractor Format on
        // the Playback Thread, never allow a delayed/out-of-band Main Thread hint to trigger a
        // redundant mid-track reconfigure.
        if (configuredFromInBandHint) {
            return
        }
        val fmt = lastConfiguredFormat
        if (usingUsbDirect && fmt != null && mapToNativeEncoding(fmt) == UsbDacNative.ENCODING_PCM_FLOAT) {
            val (desiredEnc, desiredBits) = resolveFloatRestorationTarget(next)
            if (desiredEnc != activeNativeEncoding || desiredBits != activePreferredBitDepth) {
                pendingSourceReconfigure = true
            }
        }
    }

    /**
     * Stages the prefetched source format for the upcoming queue track so gapless transitions
     * invoking [configure] on ExoPlayer's playback thread ahead of `onMediaItemTransition` open
     * the USB DAC stream once with the exact bit depth.
     */
    fun setNextSourceAudioFormat(
        mediaId: String?,
        sourceBitsPerSample: Int,
        isSourceLossless: Boolean,
        isSourceFloat: Boolean
    ) {
        nextSourceFormatHint = SourceFormatHint(
            mediaId = mediaId,
            sourceBitsPerSample = sourceBitsPerSample,
            isSourceLossless = isSourceLossless,
            isSourceFloat = isSourceFloat
        )
    }

    fun clearNextSourceAudioFormat() {
        nextSourceFormatHint = null
    }

    /**
     * Clears source format hints on track stop/reset so stale metadata never leaks across tracks.
     */
    fun resetSourceAudioFormat() {
        sourceFormatHint = SourceFormatHint()
        nextSourceFormatHint = null
        inBandSourceFormat = null
        configuredFromInBandHint = false
        pendingSourceReconfigure = false
    }

    // ═══ Configuration & Format Support ═══
    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        fallbackSink.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        fallbackSink.setPlayerId(playerId)
    }

    override fun setClock(clock: Clock) {
        fallbackSink.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (isRawPcmFormat(format)) {
            if (canHandleUsbDirectPcm(format)) return true
            return fallbackSink.supportsFormat(format)
        }
        if (canHandleUsbDirectDsd(format)) return true
        if (canHandleFallbackDsd(format)) return true
        return fallbackSink.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        if (isRawPcmFormat(format)) {
            if (canHandleUsbDirectPcm(format)) return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            return fallbackSink.getFormatSupport(format)
        }
        if (canHandleUsbDirectDsd(format)) return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        if (canHandleFallbackDsd(format)) return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        return fallbackSink.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (UsbDacManager.isDirectReady()) {
            // Force ExoPlayer to decode compressed formats (FLAC/ALAC/MP3/Opus) to raw PCM
            // rather than offloading them to Android's Audio HAL mixer.
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        if (!isRawPcmFormat(format) && isDsdFormat(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return fallbackSink.getFormatOffloadSupport(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        loggedEndOfStream = false
        lastConfiguredFormat = inputFormat
        lastSpecifiedBufferSize = specifiedBufferSize
        lastOutputChannels = outputChannels

        if (canHandleUsbDirect(inputFormat)) {
            suppressFallbackUntilNextPlay = false
            val rawEncoding = mapToNativeEncoding(inputFormat)
            val isDirectRawFloat =
                rawEncoding == UsbDacNative.ENCODING_PCM_FLOAT &&
                    inputFormat.customData === RawPcmFloatFormatMarker
            val matchedInBand = if (rawEncoding == UsbDacNative.ENCODING_PCM_FLOAT && !isDirectRawFloat) {
                inBandSourceFormat?.takeIf { ib ->
                    (ib.sampleRate <= 0 || ib.sampleRate == inputFormat.sampleRate) &&
                        (ib.channelCount <= 0 || ib.channelCount == inputFormat.channelCount)
                }
            } else {
                null
            }
            val resolvedFromInBand = isDirectRawFloat || matchedInBand != null
            val (targetEncoding, preferredBits) = when {
                isDirectRawFloat -> {
                    UsbDacNative.ENCODING_PCM_FLOAT to 32
                }
                matchedInBand != null -> {
                    resolveFloatRestorationTarget(matchedInBand.hint)
                }
                rawEncoding == UsbDacNative.ENCODING_PCM_FLOAT -> {
                    val pendingNext = nextSourceFormatHint
                    val isGaplessWhileDraining =
                        usingUsbDirect && playing && UsbDacNative.nativeGetRingBufferAvailableBytes() > 0
                    if ((handledEndOfStream || isGaplessWhileDraining) &&
                        pendingNext != null &&
                        pendingNext.mediaId != sourceFormatHint.mediaId
                    ) {
                        sourceFormatHint = pendingNext
                        nextSourceFormatHint = null
                    }
                    resolveFloatRestorationTarget(sourceFormatHint)
                }
                else -> {
                    rawEncoding to preferredBitDepthForFormat(inputFormat, rawEncoding)
                }
            }
            val shouldRestoreFloat =
                rawEncoding == UsbDacNative.ENCODING_PCM_FLOAT &&
                    targetEncoding != UsbDacNative.ENCODING_PCM_FLOAT
            val encLabel = if (shouldRestoreFloat) {
                "PCM_FLOAT->${inputEncodingName(inputFormat, targetEncoding)}"
            } else {
                inputEncodingName(inputFormat, targetEncoding)
            }
            val config = PendingUsbDirectConfig(
                inputFormat = inputFormat,
                specifiedBufferSize = specifiedBufferSize,
                outputChannels = outputChannels,
                rawEncoding = rawEncoding,
                targetEncoding = targetEncoding,
                preferredBits = preferredBits,
                shouldRestoreFloat = shouldRestoreFloat,
                encLabel = encLabel,
                fromInBandHint = resolvedFromInBand
            )

            val wireFrameBytes = (activeWireChannels * activeWireSubslotSize).coerceAtLeast(1)
            val pendingRingBytes = if (usingUsbDirect) UsbDacNative.nativeGetRingBufferAvailableBytes() else 0
            val isSameInputStreamFormat =
                usingUsbDirect &&
                    inputFormat.sampleRate == activeInputSampleRate &&
                    inputFormat.channelCount == activeInputChannels &&
                    preferredBits == activePreferredBitDepth &&
                    targetEncoding == activeNativeEncoding

            // Cross-format / cross-rate gapless switch (e.g. DSD64 -> DSD128 -> DSD256 or PCM rate
            // switch): never discard the up to 2.0 s tail still queued in SpscRingBuffer inside
            // configure(). Defer openStream() until handleBuffer() observes the tail fully drained.
            if (usingUsbDirect && playing && !isSameInputStreamFormat && pendingRingBytes >= wireFrameBytes) {
                pendingUsbConfig = config
                pendingDrainCompleteRealtimeMs = 0L
                UsbDacNative.nativeSetEndOfStream(true)
                PDLog.i(
                    PDLog.USB_DAC,
                    "SINK_DEFER_SWITCH",
                    "tailB=$pendingRingBytes nextMime=${inputFormat.sampleMimeType} " +
                        "rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount} " +
                        "enc=$encLabel prefBits=$preferredBits"
                )
                return
            }

            pendingUsbConfig = null
            pendingDrainCompleteRealtimeMs = 0L
            eosDrainCompleteRealtimeMs = 0L
            if (applyUsbDirectConfig(config)) {
                return
            }
        }

        pendingUsbConfig = null
        pendingDrainCompleteRealtimeMs = 0L
        eosDrainCompleteRealtimeMs = 0L
        pendingDiscontinuitySync = false
        wasWarmingUp = false
        val wasUsingUsbDirect = usingUsbDirect
        val isHotUnplug = wasUsingUsbDirect &&
            UsbDacManager.state.value.directEnabled &&
            !UsbDacManager.isDirectReady()
        if (wasUsingUsbDirect) {
            UsbDacManager.closeStream()
            applyPlaybackThreadHighPerf(false)
            usingUsbDirect = false
            restoringFromFloat = false
            floatConvertPendingOffset = 0
            floatConvertPendingBytes = 0
            activeInputSampleRate = 0
            activeInputPcmFrameSize = 0
            activeOutputPcmFrameSize = 0
            activeStreamGeneration = 0L
            configuredFromInBandHint = false
            pendingSourceReconfigure = false
        }
        if (isHotUnplug) {
            // DAC physically unplugged while USB Direct was active: never spin up a speaker
            // AudioTrack before Pause takes effect, and never build a throwaway AudioTrack when
            // already paused. Fallback will configure lazily on the user's next explicit play().
            val wasPlaying = playing
            suppressFallbackUntilNextPlay = true
            fallbackConfigured = false
            if (wasPlaying) {
                playing = false
                PDLog.w(PDLog.USB_DAC, "SINK_UNPLUG_PAUSE", "phase=configure action=suppress_fallback")
                onUsbDacUnpluggedDuringPlayback?.invoke()
            } else {
                PDLog.d(PDLog.USB_DAC, "SINK_UNPLUG_PAUSED") { "phase=configure action=skip_fallback" }
            }
            UsbDacManager.setHalHiResPerfHint(false)
            return
        }
        if (suppressFallbackUntilNextPlay && !playing) {
            fallbackConfigured = false
            UsbDacManager.setHalHiResPerfHint(false)
            return
        }
        val fallbackReason = describeFallbackReason(inputFormat)
        configureFallbackSink(inputFormat, specifiedBufferSize, outputChannels, fallbackReason)
        val effectiveRate = if (fallbackDsdActive) fallbackDsdOutputSampleRate else inputFormat.sampleRate
        if (effectiveRate <= HAL_HIRES_SAMPLE_RATE_THRESHOLD_HZ) {
            UsbDacManager.setHalHiResPerfHint(false)
        }
    }

    private fun applyUsbDirectConfig(config: PendingUsbDirectConfig): Boolean {
        val inputFormat = config.inputFormat
        PDLog.d(PDLog.USB_DAC, "SINK_CFG_DIRECT") {
            "mime=${inputFormat.sampleMimeType} rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount} " +
                "enc=${config.encLabel} prefBits=${config.preferredBits} src=${if (config.fromInBandHint) "in_band" else "hint"}"
        }
        val wasUsingUsbDirect = usingUsbDirect
        val prevWireSampleRate = activeWireSampleRate
        val prevStartPtsUs = startPresentationTimeUs
        val prevLastPosUs = lastReportedPositionUs
        val prevBaseFrames = baseFramesWritten

        if (config.targetEncoding != 0 &&
            UsbDacManager.openStream(
                inputFormat.sampleRate,
                inputFormat.channelCount,
                config.preferredBits,
                config.targetEncoding
            )
        ) {
            fallbackSink.reset()
            fallbackConfigured = false
            clearFallbackDsdState(releaseDecimator = true)
            usingUsbDirect = true
            configuredFromInBandHint = config.fromInBandHint
            pendingSourceReconfigure = false
            activeNativeEncoding = config.targetEncoding
            activePreferredBitDepth = config.preferredBits
            activeInputSampleRate = inputFormat.sampleRate
            activeInputChannels = inputFormat.channelCount.coerceAtLeast(1)
            restoringFromFloat = config.shouldRestoreFloat
            activeInputPcmFrameSize = activeInputChannels * bytesPerSampleForNativeEncoding(config.rawEncoding)
            activeOutputPcmFrameSize = activeInputChannels * bytesPerSampleForNativeEncoding(config.targetEncoding)
            if (restoringFromFloat) {
                ensureFloatConvertBuffer(INITIAL_FLOAT_CONVERT_BUFFER_BYTES)
            }

            activeWireSampleRate = UsbDacNative.nativeGetActiveSampleRate()
                .takeIf { it > 0 } ?: inputFormat.sampleRate
            activeWireBitDepth = UsbDacNative.nativeGetActiveBitDepth()
            activeWireSubslotSize = UsbDacNative.nativeGetActiveSubslotSize()
                .takeIf { it > 0 } ?: ((activeWireBitDepth.coerceAtLeast(16) + 7) / 8)
            activeWireChannels = UsbDacNative.nativeGetActiveChannels()
                .takeIf { it > 0 } ?: inputFormat.channelCount
            activeOutputMode = UsbDacNative.nativeGetActiveOutputMode()
            activeStreamGeneration = UsbDacNative.nativeGetStreamGeneration()
            val activeDspFlags = UsbDacNative.nativeGetActiveDspFlags()
            estimatedPipelineLatencyUs = UsbDacNative.nativeGetPipelineLatencyUs()
                .takeIf { it > 0L }
                ?: if (UsbDacNative.nativeIsHighSpeed()) {
                    HS_ISO_PIPELINE_LATENCY_US
                } else {
                    FS_ISO_PIPELINE_LATENCY_US
                }

            val pendingRingBytes = UsbDacNative.nativeGetRingBufferAvailableBytes()
            val isGaplessStreamReuse =
                wasUsingUsbDirect &&
                    pendingRingBytes > 0 &&
                    prevWireSampleRate > 0 &&
                    prevWireSampleRate == activeWireSampleRate &&
                    prevStartPtsUs != C.TIME_UNSET
            if (isGaplessStreamReuse) {
                // Preserve the draining track's monotonic clock until the new track's first
                // buffer arrives so ExoPlayer never fires onMediaItemTransition 2.5-3.0 s early.
                startPresentationTimeUs = prevStartPtsUs
                lastReportedPositionUs = prevLastPosUs
                baseFramesWritten = prevBaseFrames
                pendingDiscontinuitySync = true
            } else {
                startPresentationTimeUs = C.TIME_UNSET
                lastReportedPositionUs = C.TIME_UNSET
                baseFramesWritten = captureCurrentPlaybackBaseFrames()
                pendingDiscontinuitySync = false
            }
            handledEndOfStream = false
            eosDrainCompleteRealtimeMs = 0L
            floatConvertPendingOffset = 0
            floatConvertPendingBytes = 0
            lastObservedUnderrunCount = UsbDacNative.nativeGetUnderrunCount()
            if (playing && !isGaplessStreamReuse) {
                UsbDacManager.setHalHiResPerfHint(false)
                applyPlaybackThreadHighPerf(true)
                UsbDacNative.nativeSetWarmUpMs(UsbDacManager.getWarmUpMs())
                UsbDacNative.nativeSetPaused(false)
                UsbDacManager.setStreaming(true)
                lastFeedRealtimeMs = SystemClock.elapsedRealtime()
            } else {
                lastFeedRealtimeMs = 0L
            }
            wasWarmingUp = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
            lastUnderrunReportMs = 0L

            val modeHeader = formatDirectModeHeader(activeOutputMode)
            val dspDetail = formatDspFlagsDetail(activeDspFlags)
            AppLogManager.updateSinkState(
                mode = modeHeader,
                directDetail = "${activeWireSampleRate}Hz / ${activeWireBitDepth}-bit / ${activeWireChannels}ch " +
                    "(in=${inputFormat.sampleRate}Hz ${config.encLabel}$dspDetail)"
            )
            return true
        }
        PDLog.w(PDLog.USB_DAC, "SINK_OPEN_FAIL", "action=fallback_hal")
        return false
    }

    // ═══ Playback & Buffer Transport ═══
    override fun play() {
        playing = true
        suppressFallbackUntilNextPlay = false
        if (usingUsbDirect &&
            UsbDacManager.isDirectReady() &&
            UsbDacNative.nativeGetActiveSampleRate() <= 0
        ) {
            // Native stream was closed to alt=0 during a long pause idle timeout; clear flag so
            // switchToUsbDirectIfNeeded() cleanly re-opens the stream from alt=0 with cold warm-up.
            usingUsbDirect = false
        }
        switchToUsbDirectIfNeeded()
        if (usingUsbDirect) {
            UsbDacManager.setHalHiResPerfHint(false)
            applyPlaybackThreadHighPerf(true)
            UsbDacNative.nativeSetWarmUpMs(UsbDacManager.getWarmUpMs())
            UsbDacNative.nativeSetPaused(false)
            wasWarmingUp = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
            UsbDacManager.setStreaming(true)
            lastFeedRealtimeMs = SystemClock.elapsedRealtime()
        } else {
            if (lastConfiguredFormat == null && UsbDacManager.isDirectReady()) {
                // Waiting for DecoderAudioRenderer/MediaCodecAudioRenderer to invoke configure()
                // for the incoming track; applyUsbDirectConfig() will start streaming immediately.
                return
            }
            applyPlaybackThreadHighPerf(false)
            ensureFallbackConfigured()
            val rate = if (fallbackDsdActive) {
                fallbackDsdOutputSampleRate
            } else {
                lastConfiguredFormat?.sampleRate ?: 0
            }
            if (rate > HAL_HIRES_SAMPLE_RATE_THRESHOLD_HZ) {
                UsbDacManager.setHalHiResPerfHint(true, Process.myTid(), rate)
            } else {
                UsbDacManager.setHalHiResPerfHint(false)
            }
            fallbackSink.play()
        }
    }

    override fun pause() {
        playing = false
        UsbDacManager.setHalHiResPerfHint(false)
        if (usingUsbDirect) {
            wasWarmingUp = false
            UsbDacNative.nativeSetWarmUpMs(UsbDacManager.getWarmUpMs())
            UsbDacNative.nativeSetPaused(true)
            UsbDacManager.setStreaming(false)
            applyPlaybackThreadHighPerf(false)
        } else {
            fallbackSink.pause()
        }
    }

    override fun handleDiscontinuity() {
        if (usingUsbDirect) {
            // Mirror DefaultAudioSink: flag the discontinuity for sync on the next accepted buffer
            // without wiping startPresentationTimeUs/lastReportedPositionUs while the prior track's
            // 2.5-3.0 s ring-buffer tail is still draining (otherwise getCurrentPositionUs() returns
            // CURRENT_POSITION_NOT_SET and freezes the UI clock during the last few seconds).
            pendingDiscontinuitySync = true
        } else {
            resetFallbackDsdBuffers()
            fallbackSink.handleDiscontinuity()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        switchToUsbDirectIfNeeded()
        if (!usingUsbDirect) {
            if (suppressFallbackUntilNextPlay && !playing) {
                // Waiting for player.pause() after a hot USB DAC unplug or paused after unplug:
                // hold off creating/starting a speaker AudioTrack until the user explicitly plays.
                return false
            }
            val wasConfigured = fallbackConfigured
            if (!ensureFallbackConfigured()) return false
            if (!wasConfigured && playing) {
                fallbackSink.play()
            }
            return if (fallbackDsdActive) {
                handleFallbackDsdBuffer(buffer, presentationTimeUs)
            } else {
                fallbackSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }

        val nativeDisconnected = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsDeviceDisconnected()
        if (nativeDisconnected || !UsbDacManager.isDirectReady() || UsbDacNative.nativeGetActiveSampleRate() <= 0) {
            val wasPlaying = playing
            val isHotUnplug = nativeDisconnected || UsbDacManager.state.value.directEnabled
            if (nativeDisconnected) {
                UsbDacManager.handleNativeDeviceDisconnected()
            } else {
                UsbDacManager.closeStream()
            }
            applyPlaybackThreadHighPerf(false)
            UsbDacManager.setHalHiResPerfHint(false)
            usingUsbDirect = false
            restoringFromFloat = false
            floatConvertPendingOffset = 0
            floatConvertPendingBytes = 0
            activeInputSampleRate = 0
            activeInputPcmFrameSize = 0
            activeOutputPcmFrameSize = 0
            activeStreamGeneration = 0L
            configuredFromInBandHint = false
            pendingSourceReconfigure = false
            pendingUsbConfig = null
            pendingDrainCompleteRealtimeMs = 0L
            eosDrainCompleteRealtimeMs = 0L
            pendingDiscontinuitySync = false
            wasWarmingUp = false
            if (isHotUnplug) {
                // Physical USB DAC unplug: suppress speaker fallback immediately and trigger an
                // early pause ahead of Android's delayed ACTION_AUDIO_BECOMING_NOISY broadcast so
                // zero audio leaks to the phone speaker, and skip creating a throwaway AudioTrack
                // if playback was already paused.
                suppressFallbackUntilNextPlay = true
                fallbackConfigured = false
                if (wasPlaying) {
                    playing = false
                    PDLog.w(
                        PDLog.USB_DAC,
                        "SINK_UNPLUG_PAUSE",
                        "phase=handle_buffer src=${if (nativeDisconnected) "native_iso" else "usb_host"} action=suppress_fallback"
                    )
                    onUsbDacUnpluggedDuringPlayback?.invoke()
                } else {
                    PDLog.d(PDLog.USB_DAC, "SINK_UNPLUG_PAUSED") { "phase=handle_buffer action=skip_fallback" }
                }
                return false
            }
            // User explicitly disabled USB Direct in Settings: fall back seamlessly to Android HAL
            PDLog.i(PDLog.USB_DAC, "SINK_ROUTE_FALLBACK", "reason=usb_direct_disabled")
            if (!ensureFallbackConfigured(fallbackReason = "usb_direct_disabled")) return false
            fallbackSink.playbackParameters = playbackParameters
            if (playing) {
                fallbackSink.play()
            }
            return if (fallbackDsdActive) {
                handleFallbackDsdBuffer(buffer, presentationTimeUs)
            } else {
                fallbackSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }

        syncIfStreamReopenedAsync()

        val deferredConfig = pendingUsbConfig
        if (deferredConfig != null) {
            // Drain the previous track's remaining SpscRingBuffer tail + in-flight USB ISO URBs
            // non-blockingly before switching the DAC hardware clock/alt-setting to the new format.
            val wireFrameBytes = (activeWireChannels * activeWireSubslotSize).coerceAtLeast(1)
            val ringAvail = UsbDacNative.nativeGetRingBufferAvailableBytes()
            if (ringAvail >= wireFrameBytes) {
                pendingDrainCompleteRealtimeMs = 0L
                return false
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (pendingDrainCompleteRealtimeMs == 0L && playing) {
                val pipeMs = (estimatedPipelineLatencyUs / MILLIS_PER_SECOND).coerceIn(20L, 100L)
                pendingDrainCompleteRealtimeMs = nowMs + pipeMs
                return false
            }
            if (pendingDrainCompleteRealtimeMs > 0L && nowMs < pendingDrainCompleteRealtimeMs) {
                return false
            }
            pendingUsbConfig = null
            pendingDrainCompleteRealtimeMs = 0L
            if (!applyUsbDirectConfig(deferredConfig)) {
                UsbDacManager.closeStream()
                usingUsbDirect = false
                restoringFromFloat = false
                activeInputSampleRate = 0
                activeInputPcmFrameSize = 0
                activeOutputPcmFrameSize = 0
                activeStreamGeneration = 0L
                pendingSourceReconfigure = false
                eosDrainCompleteRealtimeMs = 0L
                pendingDiscontinuitySync = false
                wasWarmingUp = false
                val fallbackReason = describeFallbackReason(deferredConfig.inputFormat)
                configureFallbackSink(
                    deferredConfig.inputFormat,
                    deferredConfig.specifiedBufferSize,
                    deferredConfig.outputChannels,
                    fallbackReason
                )
                if (playing) {
                    fallbackSink.play()
                }
                return if (fallbackDsdActive) {
                    handleFallbackDsdBuffer(buffer, presentationTimeUs)
                } else {
                    fallbackSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                }
            }
        }

        if (pendingSourceReconfigure) {
            // Gapless transition updated source metadata after configure() ran on the playback thread.
            pendingSourceReconfigure = false
            val fmt = lastConfiguredFormat
            if (fmt != null && canHandleUsbDirect(fmt)) {
                val savedLastPosUs = lastReportedPositionUs
                val wasPlaying = playing
                configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
                lastReportedPositionUs = savedLastPosUs
                if (usingUsbDirect && wasPlaying) {
                    UsbDacNative.nativeSetPaused(false)
                    wasWarmingUp = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
                    UsbDacManager.setStreaming(true)
                }
            }
        }

        if (startPresentationTimeUs == C.TIME_UNSET || pendingDiscontinuitySync) {
            val writtenNow = UsbDacNative.nativeGetFramesWritten()
            val anchoredBaseFrames = captureCurrentPlaybackBaseFrames()
            val hasQueuedPriorTail = anchoredBaseFrames > writtenNow
            startPresentationTimeUs = presentationTimeUs
            // Account for any previous-track wire frames still queued in SpscRingBuffer when a
            // gapless transition reuses the active USB stream, preventing a 2.5-3.0 s clock jump.
            baseFramesWritten = anchoredBaseFrames
            pendingDiscontinuitySync = false
            if (!hasQueuedPriorTail) {
                // Fresh start, post-flush seek, or post-drain format switch: pin the monotonic floor
                // to the exact buffer PTS so pipeline latency subtraction never pulls the clock back.
                lastReportedPositionUs = presentationTimeUs
            } else if (lastReportedPositionUs == C.TIME_UNSET && activeWireSampleRate > 0) {
                val signedDeltaUs =
                    ((writtenNow - anchoredBaseFrames) * MICROS_PER_SECOND) / activeWireSampleRate
                lastReportedPositionUs = (presentationTimeUs + signedDeltaUs).coerceAtLeast(0L)
            }
        }

        maybeReportUnderrun()

        // Restore FFmpeg float32 back to bit-exact integer PCM into a reusable direct ByteBuffer,
        // caching any unwritten remainder across backpressure passes so ExoPlayer's 10 ms render
        // retries never re-convert the same float32 samples repeatedly.
        if (restoringFromFloat) {
            val inFrameSize = activeInputPcmFrameSize
            val outFrameSize = activeOutputPcmFrameSize
            if (inFrameSize <= 0 || outFrameSize <= 0) return true

            if (floatConvertPendingBytes > 0) {
                val convBuf = floatConvertBuffer
                if (convBuf != null) {
                    val drained = UsbDacNative.nativeWritePcm(
                        convBuf,
                        floatConvertPendingOffset,
                        floatConvertPendingBytes,
                        activeNativeEncoding
                    )
                    if (drained > 0) {
                        floatConvertPendingOffset += drained
                        floatConvertPendingBytes -= drained
                        lastFeedRealtimeMs = SystemClock.elapsedRealtime()
                    }
                    if (floatConvertPendingBytes > 0) {
                        return false
                    }
                }
                floatConvertPendingOffset = 0
                floatConvertPendingBytes = 0
            }

            val remaining = buffer.remaining()
            if (remaining == 0) return true

            // Fused single-pass Float32 -> MSB-aligned 32-bit wire path when DAC is 32-bit Bit-Perfect
            // with SW volume bypassed: skips intermediate floatConvertBuffer and wireOutScratch_ passes.
            if (buffer.isDirect &&
                buffer.order() == ByteOrder.LITTLE_ENDIAN &&
                activeWireSubslotSize == 4 &&
                activeOutputMode == UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT &&
                UsbDacNative.isLibraryLoaded
            ) {
                val fusedConsumed = UsbDacNative.nativeWriteFloatRestoredPcm(
                    buffer,
                    buffer.position(),
                    remaining,
                    activeNativeEncoding
                )
                if (fusedConsumed >= 0) {
                    if (fusedConsumed > 0) {
                        buffer.position(buffer.position() + fusedConsumed)
                        lastFeedRealtimeMs = SystemClock.elapsedRealtime()
                    }
                    return !buffer.hasRemaining()
                }
            }

            val availFrames = remaining / inFrameSize
            if (availFrames == 0) return true

            val totalSamples = availFrames * activeInputChannels
            val outBytes = availFrames * outFrameSize
            val convBuf = ensureFloatConvertBuffer(outBytes)
            convertFloatBufferToIntegerPcm(
                src = buffer,
                srcOffset = buffer.position(),
                sampleCount = totalSamples,
                targetEncoding = activeNativeEncoding,
                dst = convBuf
            )
            buffer.position(buffer.position() + availFrames * inFrameSize)
            val writtenOutBytes = UsbDacNative.nativeWritePcm(convBuf, 0, outBytes, activeNativeEncoding)
            if (writtenOutBytes > 0) {
                lastFeedRealtimeMs = SystemClock.elapsedRealtime()
            }
            if (writtenOutBytes < outBytes) {
                floatConvertPendingOffset = writtenOutBytes
                floatConvertPendingBytes = outBytes - writtenOutBytes
                return false
            }
            return !buffer.hasRemaining()
        }

        val remaining = buffer.remaining()
        if (remaining == 0) return true

        // Non-blocking handoff into the C++ DSP + lock-free SPSC ring buffer; returns false on
        // backpressure so ExoPlayer retries on its next render pass without stalling the thread.
        val written = if (buffer.isDirect) {
            UsbDacNative.nativeWritePcm(buffer, buffer.position(), remaining, activeNativeEncoding)
        } else if (buffer.hasArray()) {
            UsbDacNative.nativeWritePcmArray(
                buffer.array(),
                buffer.arrayOffset() + buffer.position(),
                remaining,
                activeNativeEncoding
            )
        } else {
            if (scratchCopyBuffer.size < remaining) {
                scratchCopyBuffer = ByteArray(remaining)
            }
            val pos = buffer.position()
            buffer.get(scratchCopyBuffer, 0, remaining)
            buffer.position(pos)
            UsbDacNative.nativeWritePcmArray(scratchCopyBuffer, 0, remaining, activeNativeEncoding)
        }

        if (written > 0) {
            buffer.position(buffer.position() + written)
            lastFeedRealtimeMs = SystemClock.elapsedRealtime()
        }
        return !buffer.hasRemaining()
    }

    override fun playToEndOfStream() {
        if (usingUsbDirect) {
            if (!handledEndOfStream) {
                PDLog.d(PDLog.USB_DAC, "SINK_EOS_START") {
                    "frames=${UsbDacNative.nativeGetFramesWritten()} ringAvailB=${UsbDacNative.nativeGetRingBufferAvailableBytes()}"
                }
            }
            handledEndOfStream = true
            UsbDacNative.nativeSetEndOfStream(true)
        } else {
            fallbackSink.playToEndOfStream()
        }
    }

    override fun isEnded(): Boolean {
        if (usingUsbDirect) {
            val ended = handledEndOfStream && !hasPendingData()
            if (ended && !loggedEndOfStream) {
                loggedEndOfStream = true
                PDLog.d(PDLog.USB_DAC, "SINK_EOS_DONE") {
                    "frames=${UsbDacNative.nativeGetFramesWritten()} posUs=${getCurrentPositionUs(true)}"
                }
            }
            return ended
        }
        if (!fallbackConfigured) return false
        return (fallbackDsdOutBuffer?.hasRemaining() != true) && fallbackSink.isEnded
    }

    override fun hasPendingData(): Boolean {
        if (usingUsbDirect) {
            if (restoringFromFloat && floatConvertPendingBytes > 0) {
                return true
            }
            val wireFrameBytes = (activeWireChannels * activeWireSubslotSize).coerceAtLeast(1)
            if (UsbDacNative.nativeGetRingBufferAvailableBytes() >= wireFrameBytes) {
                eosDrainCompleteRealtimeMs = 0L
                return true
            }
            if (pendingUsbConfig != null) {
                if (pendingDrainCompleteRealtimeMs > 0L &&
                    SystemClock.elapsedRealtime() < pendingDrainCompleteRealtimeMs
                ) {
                    return true
                }
                if (handledEndOfStream) {
                    pendingUsbConfig = null
                    pendingDrainCompleteRealtimeMs = 0L
                }
            }
            if (handledEndOfStream && playing) {
                // Allow the final in-flight USB Isochronous URB queue (~26 ms) to finish exiting
                // the DAC before reporting ended so the last milliseconds never clip and the clock
                // reaches the exact final second of the track.
                val nowMs = SystemClock.elapsedRealtime()
                if (eosDrainCompleteRealtimeMs == 0L) {
                    val pipeMs = (estimatedPipelineLatencyUs / MILLIS_PER_SECOND).coerceIn(20L, 100L)
                    eosDrainCompleteRealtimeMs = nowMs + pipeMs
                    return true
                }
                if (nowMs < eosDrainCompleteRealtimeMs) {
                    return true
                }
            }
            return false
        }
        if (!fallbackConfigured) return false
        return (fallbackDsdOutBuffer?.hasRemaining() == true) || fallbackSink.hasPendingData()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!usingUsbDirect) {
            if (!fallbackConfigured) return AudioSink.CURRENT_POSITION_NOT_SET
            return fallbackSink.getCurrentPositionUs(sourceEnded)
        }
        syncIfStreamReopenedAsync()
        if (startPresentationTimeUs == C.TIME_UNSET || activeWireSampleRate <= 0) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }

        // Keep signedWireFrames signed so gapless stream reuse with up to 3.0 s of previous-track
        // audio still queued in SpscRingBuffer tracks the true draining analog position rather than
        // clamping negative deltas to 0 and jumping ahead to the next track prematurely.
        val signedWireFrames = UsbDacNative.nativeGetFramesWritten() - baseFramesWritten
        val submittedUs = (signedWireFrames * MICROS_PER_SECOND) / activeWireSampleRate

        // Subtract in-flight USB Isochronous URB latency while actively streaming so the clock
        // matches analog DAC output, and ramp it smoothly to 0 during the final ~26 ms URB drain
        // at end-of-track or cross-format switch so the clock reaches the exact final second.
        val activeDrainDeadlineMs = when {
            pendingDrainCompleteRealtimeMs > 0L -> pendingDrainCompleteRealtimeMs
            eosDrainCompleteRealtimeMs > 0L -> eosDrainCompleteRealtimeMs
            else -> 0L
        }
        val effectivePipelineLatencyUs = when {
            sourceEnded && !hasPendingData() -> 0L
            activeDrainDeadlineMs > 0L -> {
                val remainingMs = (activeDrainDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                (remainingMs * MILLIS_PER_SECOND).coerceAtMost(estimatedPipelineLatencyUs)
            }
            else -> estimatedPipelineLatencyUs
        }
        val effectiveElapsedUs = submittedUs - effectivePipelineLatencyUs

        val candidateUs = (startPresentationTimeUs + effectiveElapsedUs).coerceAtLeast(0L)
        val monotonicUs = if (lastReportedPositionUs != C.TIME_UNSET) {
            maxOf(lastReportedPositionUs, candidateUs)
        } else {
            candidateUs
        }
        lastReportedPositionUs = monotonicUs

        // Notify Media3 (AudioSink.Listener + MediaSession) once DAC warm-up & prebuffer finish
        // so MediaController and system notification seekbars re-anchor to the exact hardware
        // position with zero +warmUpMs forward extrapolation drift.
        val warmingNow = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
        if (wasWarmingUp && !warmingNow) {
            wasWarmingUp = false
            listener?.onPositionAdvancing(System.currentTimeMillis())
            onWarmUpCompleted?.invoke()
        } else if (warmingNow) {
            wasWarmingUp = true
        }
        return monotonicUs
    }

    // ═══ Audio Attributes & Session State ═══
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        if (!usingUsbDirect) {
            fallbackSink.playbackParameters = playbackParameters
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return if (usingUsbDirect) PlaybackParameters.DEFAULT else fallbackSink.playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
        fallbackSink.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return if (usingUsbDirect) false else fallbackSink.skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        fallbackSink.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes {
        return fallbackSink.getAudioAttributes() ?: audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        fallbackSink.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        fallbackSink.setAuxEffectInfo(auxEffectInfo)
    }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        PDLog.d(PDLog.USB_DAC, "SINK_PREF_DEV") {
            audioDeviceInfo?.let {
                "type=${AppLogManager.audioDeviceTypeName(it.type)} id=${it.id} name=${it.productName}"
            } ?: "dev=auto"
        }
        fallbackSink.setPreferredDevice(audioDeviceInfo)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        fallbackSink.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun enableTunnelingV21() {
        if (!usingUsbDirect) fallbackSink.enableTunnelingV21()
    }

    override fun disableTunneling() {
        fallbackSink.disableTunneling()
    }

    override fun setOffloadMode(offloadMode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fallbackSink.setOffloadMode(offloadMode)
        }
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fallbackSink.setOffloadDelayPadding(delayInFrames, paddingInFrames)
        }
    }

    override fun setVolume(volume: Float) {
        // USB Direct manages gain via UAC Feature Unit hardware volume (or 64-bit SW fallback in
        // UsbDacManager), keeping ExoPlayer's digital PCM stream untouched at full scale (0 dB).
        if (!usingUsbDirect) {
            fallbackSink.setVolume(volume)
        }
    }

    // ═══ Flush, Reset & Release ═══
    override fun flush() {
        if (pendingUsbDirectReset) {
            pendingUsbDirectReset = false
            if (!usingUsbDirect) {
                val wasPlaying = playing
                resetInternal(clearConfiguredFormat = false)
                playing = wasPlaying
                if (wasPlaying) {
                    switchToUsbDirectIfNeeded()
                }
                return
            }
        }
        if (usingUsbDirect) {
            val ringAvail = UsbDacNative.nativeGetRingBufferAvailableBytes()
            val alreadyFlushedIdle =
                !playing &&
                    ringAvail == 0 &&
                    floatConvertPendingBytes == 0 &&
                    startPresentationTimeUs == C.TIME_UNSET &&
                    !handledEndOfStream &&
                    pendingUsbConfig == null
            if (alreadyFlushedIdle) {
                return
            }
            // Same-format seek/flush keeps DAC alt-setting and clock locked: pass warmUp=0ms so
            // native flush engages a 15 ms PCM micro fade-in instead of stalling for 1000 ms.
            PDLog.d(PDLog.USB_DAC, "SINK_FLUSH") { "ringAvailB=$ringAvail warmMs=0" }
            UsbDacNative.nativeFlush(0)
            floatConvertPendingOffset = 0
            floatConvertPendingBytes = 0
            startPresentationTimeUs = C.TIME_UNSET
            lastReportedPositionUs = C.TIME_UNSET
            pendingDiscontinuitySync = false
            pendingUsbConfig = null
            pendingDrainCompleteRealtimeMs = 0L
            eosDrainCompleteRealtimeMs = 0L
            baseFramesWritten = UsbDacNative.nativeGetFramesWritten()
            handledEndOfStream = false
            loggedEndOfStream = false
            wasWarmingUp = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
            lastFeedRealtimeMs = 0L
            lastUnderrunReportMs = 0L
        } else {
            resetFallbackDsdBuffers()
            fallbackSink.flush()
        }
    }

    override fun reset() {
        resetInternal(clearConfiguredFormat = true)
    }

    private fun resetInternal(clearConfiguredFormat: Boolean) {
        playing = false
        UsbDacManager.setHalHiResPerfHint(false)
        if (usingUsbDirect) {
            UsbDacManager.setStreaming(false)
            UsbDacManager.closeStream()
            applyPlaybackThreadHighPerf(false)
            usingUsbDirect = false
            restoringFromFloat = false
            floatConvertPendingOffset = 0
            floatConvertPendingBytes = 0
            activeInputSampleRate = 0
            activeInputPcmFrameSize = 0
            activeOutputPcmFrameSize = 0
            pendingSourceReconfigure = false
            pendingUsbConfig = null
            pendingDrainCompleteRealtimeMs = 0L
            eosDrainCompleteRealtimeMs = 0L
            wasWarmingUp = false
            activeWireSampleRate = 0
            activeWireBitDepth = 0
            activeWireSubslotSize = 0
            activeOutputMode = UsbDacNative.OUTPUT_MODE_INACTIVE
            activeStreamGeneration = 0L
            startPresentationTimeUs = C.TIME_UNSET
            lastReportedPositionUs = C.TIME_UNSET
            pendingDiscontinuitySync = false
            handledEndOfStream = false
            loggedEndOfStream = false
            lastFeedRealtimeMs = 0L
            lastUnderrunReportMs = 0L
            lastObservedUnderrunCount = 0L
        }
        floatConvertPendingOffset = 0
        floatConvertPendingBytes = 0
        if (clearConfiguredFormat) {
            lastConfiguredFormat = null
            lastSpecifiedBufferSize = 0
            lastOutputChannels = null
            inBandSourceFormat = null
            configuredFromInBandHint = false
            suppressFallbackUntilNextPlay = false
        }
        clearFallbackDsdState(releaseDecimator = true)
        fallbackSink.reset()
        fallbackConfigured = false
    }

    /**
     * Marks any active fallback/offload audio buffers for reset on the ExoPlayer playback thread
     * when USB DAC permission is granted mid-playback, avoiding cross-thread races with DefaultAudioSink.
     */
    fun resetForUsbDirectSwitch() {
        PDLog.i(PDLog.USB_DAC, "SINK_RESET_DIRECT_SWITCH", "wasUsbDirect=$usingUsbDirect")
        pendingUsbDirectReset = true
    }

    override fun release() {
        playing = false
        UsbDacManager.setHalHiResPerfHint(false)
        UsbDacManager.removeRoutingListener(routingListener)
        if (usingUsbDirect) {
            UsbDacManager.closeStream()
            usingUsbDirect = false
        }
        restoringFromFloat = false
        floatConvertPendingOffset = 0
        floatConvertPendingBytes = 0
        lastObservedUnderrunCount = 0L
        lastConfiguredFormat = null
        lastSpecifiedBufferSize = 0
        lastOutputChannels = null
        activeInputSampleRate = 0
        activeInputPcmFrameSize = 0
        activeOutputPcmFrameSize = 0
        activeStreamGeneration = 0L
        pendingUsbConfig = null
        pendingDrainCompleteRealtimeMs = 0L
        eosDrainCompleteRealtimeMs = 0L
        pendingDiscontinuitySync = false
        wasWarmingUp = false
        suppressFallbackUntilNextPlay = false
        onWarmUpCompleted = null
        onUsbDacUnpluggedDuringPlayback = null
        resetSourceAudioFormat()
        clearFallbackDsdState(releaseDecimator = true)
        fallbackConfigured = false
        scratchCopyBuffer = ByteArray(0)
        floatConvertBuffer = null
        fallbackSink.release()
    }

    // ═══ Float32-to-Integer Bit-Exact Restoration Helpers ═══
    private fun parseDecoderInputFormat(format: Format): InBandSourceFormat? {
        val mime = format.sampleMimeType?.lowercase() ?: return null
        val rate = format.sampleRate
        val ch = format.channelCount

        if (mime == MimeTypes.AUDIO_RAW) {
            val hint = when (format.pcmEncoding) {
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN ->
                    SourceFormatHint(sourceBitsPerSample = 16, isSourceLossless = true, isSourceFloat = false)
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN ->
                    SourceFormatHint(sourceBitsPerSample = 24, isSourceLossless = true, isSourceFloat = false)
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN ->
                    SourceFormatHint(sourceBitsPerSample = 32, isSourceLossless = true, isSourceFloat = false)
                C.ENCODING_PCM_FLOAT ->
                    SourceFormatHint(sourceBitsPerSample = 32, isSourceLossless = true, isSourceFloat = true)
                else -> return null
            }
            return InBandSourceFormat(
                sampleRate = rate,
                channelCount = ch,
                hint = hint,
                sourceLabel = "raw/${pcmEncodingName(format.pcmEncoding)}"
            )
        }

        if (mime == MimeTypes.AUDIO_FLAC) {
            val bits = bitsPerSampleFromPcmEncoding(format.pcmEncoding)
                .takeIf { it > 0 }
                ?: parseFlacBitsPerSampleFromInitData(format.initializationData)
            if (bits in 8..32) {
                return InBandSourceFormat(
                    sampleRate = rate,
                    channelCount = ch,
                    hint = SourceFormatHint(sourceBitsPerSample = bits, isSourceLossless = true, isSourceFloat = false),
                    sourceLabel = "flac/${bits}bit"
                )
            }
            return null
        }

        if (mime == MimeTypes.AUDIO_ALAC) {
            val bits = bitsPerSampleFromPcmEncoding(format.pcmEncoding)
                .takeIf { it > 0 }
                ?: parseAlacBitsPerSampleFromInitData(format.initializationData)
            if (bits in 8..32) {
                return InBandSourceFormat(
                    sampleRate = rate,
                    channelCount = ch,
                    hint = SourceFormatHint(sourceBitsPerSample = bits, isSourceLossless = true, isSourceFloat = false),
                    sourceLabel = "alac/${bits}bit"
                )
            }
            return null
        }

        if (isDsdFormat(format)) {
            return InBandSourceFormat(
                sampleRate = rate,
                channelCount = ch,
                hint = SourceFormatHint(sourceBitsPerSample = 1, isSourceLossless = true, isSourceFloat = false),
                sourceLabel = "dsd"
            )
        }

        if (mime == MimeTypes.AUDIO_MPEG ||
            mime == MimeTypes.AUDIO_MPEG_L1 ||
            mime == MimeTypes.AUDIO_MPEG_L2 ||
            mime == MimeTypes.AUDIO_AAC ||
            mime == MimeTypes.AUDIO_OPUS ||
            mime == MimeTypes.AUDIO_VORBIS ||
            mime == MimeTypes.AUDIO_AC3 ||
            mime == MimeTypes.AUDIO_E_AC3 ||
            mime == MimeTypes.AUDIO_E_AC3_JOC ||
            mime == MimeTypes.AUDIO_AC4 ||
            mime == MimeTypes.AUDIO_DTS ||
            mime == MimeTypes.AUDIO_DTS_HD ||
            mime == MimeTypes.AUDIO_AMR_NB ||
            mime == MimeTypes.AUDIO_AMR_WB
        ) {
            return InBandSourceFormat(
                sampleRate = rate,
                channelCount = ch,
                hint = SourceFormatHint(sourceBitsPerSample = 0, isSourceLossless = false, isSourceFloat = false),
                sourceLabel = "lossy/$mime"
            )
        }

        return null
    }

    private fun bitsPerSampleFromPcmEncoding(pcmEncoding: Int): Int = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        else -> 0
    }

    private fun parseFlacBitsPerSampleFromInitData(initData: List<ByteArray>): Int {
        for (csd in initData) {
            val streamInfoOffset = when {
                csd.size >= 42 &&
                    csd[0] == 'f'.code.toByte() &&
                    csd[1] == 'L'.code.toByte() &&
                    csd[2] == 'a'.code.toByte() &&
                    csd[3] == 'C'.code.toByte() -> 8
                csd.size >= 34 -> 0
                else -> -1
            }
            if (streamInfoOffset >= 0 && streamInfoOffset + 14 <= csd.size) {
                val b12 = csd[streamInfoOffset + 12].toInt() and 0xFF
                val b13 = csd[streamInfoOffset + 13].toInt() and 0xFF
                val bps = (((b12 and 0x01) shl 4) or ((b13 ushr 4) and 0x0F)) + 1
                if (bps in 8..32) return bps
            }
        }
        return 0
    }

    private fun parseAlacBitsPerSampleFromInitData(initData: List<ByteArray>): Int {
        for (csd in initData) {
            // Standard 24-byte ALACSpecificConfig puts bitDepth at offset 5;
            // FullBox-wrapped 28-byte config (4-byte version/flags prefix) puts it at offset 9.
            if (csd.size >= 28 &&
                csd[0].toInt() == 0 && csd[1].toInt() == 0 &&
                csd[2].toInt() == 0 && csd[3].toInt() == 0
            ) {
                val bps = csd[9].toInt() and 0xFF
                if (bps in 8..32) return bps
            }
            if (csd.size >= 24) {
                val bps = csd[5].toInt() and 0xFF
                if (bps in 8..32) return bps
            }
        }
        return 0
    }

    private fun resolveFloatRestorationTarget(source: SourceFormatHint): Pair<Int, Int> {
        if (source.isSourceFloat) {
            return UsbDacNative.ENCODING_PCM_FLOAT to 32
        }
        if (source.isSourceLossless) {
            return when {
                source.sourceBitsPerSample in 1..16 -> UsbDacNative.ENCODING_PCM_16BIT to 16
                source.sourceBitsPerSample in 17..24 -> UsbDacNative.ENCODING_PCM_24BIT to 24
                source.sourceBitsPerSample >= 32 -> UsbDacNative.ENCODING_PCM_32BIT to 32
                else -> selectDacIntegerTargetForLossy()
            }
        }
        return selectDacIntegerTargetForLossy()
    }

    private fun selectDacIntegerTargetForLossy(): Pair<Int, Int> {
        val depths = UsbDacManager.state.value.supportedBitDepths.ifEmpty {
            if (UsbDacNative.isLibraryLoaded) {
                UsbDacNative.nativeGetSupportedBitDepths().toList()
            } else {
                emptyList()
            }
        }
        return if (depths.any { it >= 24 }) {
            UsbDacNative.ENCODING_PCM_24BIT to 24
        } else {
            UsbDacNative.ENCODING_PCM_16BIT to 16
        }
    }

    private fun bytesPerSampleForNativeEncoding(nativeEncoding: Int): Int = when (nativeEncoding) {
        UsbDacNative.ENCODING_PCM_16BIT -> 2
        UsbDacNative.ENCODING_PCM_24BIT -> 3
        UsbDacNative.ENCODING_PCM_32BIT, UsbDacNative.ENCODING_PCM_FLOAT -> 4
        UsbDacNative.ENCODING_DSD_U8 -> 1
        UsbDacNative.ENCODING_DSD_U32_BE, UsbDacNative.ENCODING_DSD_U32_LE -> 4
        else -> 2
    }

    private fun ensureFloatConvertBuffer(minBytes: Int): ByteBuffer {
        val existing = floatConvertBuffer
        if (existing != null && existing.capacity() >= minBytes) {
            return existing
        }
        val alignedCap = maxOf(
            INITIAL_FLOAT_CONVERT_BUFFER_BYTES,
            ((minBytes + CONVERT_BUFFER_ALIGN_BYTES - 1) / CONVERT_BUFFER_ALIGN_BYTES) * CONVERT_BUFFER_ALIGN_BYTES
        )
        return ByteBuffer.allocateDirect(alignedCap).order(ByteOrder.LITTLE_ENDIAN).also {
            floatConvertBuffer = it
        }
    }

    private fun convertFloatBufferToIntegerPcm(
        src: ByteBuffer,
        srcOffset: Int,
        sampleCount: Int,
        targetEncoding: Int,
        dst: ByteBuffer
    ) {
        dst.clear()
        val isLittleEndian = src.order() == ByteOrder.LITTLE_ENDIAN
        if (src.isDirect && dst.isDirect && isLittleEndian && UsbDacNative.isLibraryLoaded) {
            val producedBytes = UsbDacNative.nativeConvertFloatToPcm(
                srcDirectBuffer = src,
                srcOffset = srcOffset,
                sampleCount = sampleCount,
                targetEncoding = targetEncoding,
                dstDirectBuffer = dst
            )
            if (producedBytes > 0) {
                dst.position(0)
                dst.limit(producedBytes)
                return
            }
        }
        var readPos = srcOffset
        when (targetEncoding) {
            UsbDacNative.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = clampFiniteUnitFloat(readFloatLe(src, readPos, isLittleEndian))
                    readPos += 4
                    val s16 = (clamped * PCM_16_SCALE).roundToInt().coerceIn(PCM_16_MIN, PCM_16_MAX)
                    dst.putShort(s16.toShort())
                }
            }
            UsbDacNative.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = clampFiniteUnitFloat(readFloatLe(src, readPos, isLittleEndian))
                    readPos += 4
                    val s24 = (clamped * PCM_24_SCALE).roundToInt().coerceIn(PCM_24_MIN, PCM_24_MAX)
                    // 3-byte packed Little-Endian matches AudioInputEncoding::PCM_24BIT in usb_audio_jni.cpp
                    dst.put((s24 and 0xFF).toByte())
                    dst.put(((s24 ushr 8) and 0xFF).toByte())
                    dst.put(((s24 ushr 16) and 0xFF).toByte())
                }
            }
            UsbDacNative.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = clampFiniteUnitFloat(readFloatLe(src, readPos, isLittleEndian))
                    readPos += 4
                    val s32 = (clamped.toDouble() * PCM_32_SCALE)
                        .roundToLong()
                        .coerceIn(PCM_32_MIN, PCM_32_MAX)
                        .toInt()
                    dst.putInt(s32)
                }
            }
        }
        dst.flip()
    }

    private fun readFloatLe(src: ByteBuffer, offset: Int, isLittleEndian: Boolean): Float {
        if (isLittleEndian) return src.getFloat(offset)
        val bits = (src.get(offset).toInt() and 0xFF) or
            ((src.get(offset + 1).toInt() and 0xFF) shl 8) or
            ((src.get(offset + 2).toInt() and 0xFF) shl 16) or
            ((src.get(offset + 3).toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    private fun clampFiniteUnitFloat(sample: Float): Float = when {
        sample >= 1.0f -> 1.0f
        sample <= -1.0f -> -1.0f
        sample.isNaN() -> 0.0f
        else -> sample
    }

    // ═══ Private Helpers ═══
    /**
     * Detects asynchronous native stream reopens (e.g., DSD -> DSD_TO_PCM safety switch triggered
     * by software volume attenuation on a DAC without hardware volume), refreshing cached wire
     * parameters and re-anchoring `startPresentationTimeUs` / `baseFramesWritten` so the clock
     * never freezes when native `framesWritten_` resets to 0.
     */
    private fun syncIfStreamReopenedAsync() {
        if (!usingUsbDirect || !UsbDacNative.isLibraryLoaded) return
        val currentGen = UsbDacNative.nativeGetStreamGeneration()
        if (currentGen <= 0L || currentGen == activeStreamGeneration) return

        activeStreamGeneration = currentGen
        val newWireRate = UsbDacNative.nativeGetActiveSampleRate()
        if (newWireRate > 0) {
            activeWireSampleRate = newWireRate
        }
        activeWireBitDepth = UsbDacNative.nativeGetActiveBitDepth()
        activeWireSubslotSize = UsbDacNative.nativeGetActiveSubslotSize()
            .takeIf { it > 0 } ?: ((activeWireBitDepth.coerceAtLeast(16) + 7) / 8)
        activeWireChannels = UsbDacNative.nativeGetActiveChannels()
            .takeIf { it > 0 } ?: activeInputChannels
        activeOutputMode = UsbDacNative.nativeGetActiveOutputMode()
        estimatedPipelineLatencyUs = UsbDacNative.nativeGetPipelineLatencyUs()
            .takeIf { it > 0L }
            ?: if (UsbDacNative.nativeIsHighSpeed()) {
                HS_ISO_PIPELINE_LATENCY_US
            } else {
                FS_ISO_PIPELINE_LATENCY_US
            }
        lastObservedUnderrunCount = UsbDacNative.nativeGetUnderrunCount()

        if (lastReportedPositionUs != C.TIME_UNSET) {
            startPresentationTimeUs = lastReportedPositionUs
            baseFramesWritten = captureCurrentPlaybackBaseFrames()
            pendingDiscontinuitySync = false
        } else if (startPresentationTimeUs != C.TIME_UNSET) {
            baseFramesWritten = captureCurrentPlaybackBaseFrames()
        }

        val activeDspFlags = UsbDacNative.nativeGetActiveDspFlags()
        PDLog.i(
            PDLog.USB_DAC,
            "SINK_GEN_REANCHOR",
            "gen=$currentGen wire=${activeWireSampleRate}Hz/${activeWireBitDepth}b/${activeWireChannels}ch " +
                "mode=${formatDirectModeHeader(activeOutputMode)} dsp=0x${activeDspFlags.toString(16)}"
        )
    }

    /**
     * Computes the wire-frame baseline for the newly starting track by adding any previous-track
     * frames still queued in the native `SpscRingBuffer` to `nativeGetFramesWritten()`. On fresh
     * opens or post-flush seeks `availableBytes` is 0; on gapless stream reuse this prevents the
     * playback clock and synced lyrics from jumping 1.5–2.0 s ahead while the prior tail drains.
     */
    private fun captureCurrentPlaybackBaseFrames(): Long {
        val writtenNow = UsbDacNative.nativeGetFramesWritten()
        val wireFrameBytes = activeWireChannels * activeWireSubslotSize
        if (wireFrameBytes <= 0) return writtenNow
        val pendingRingFrames =
            UsbDacNative.nativeGetRingBufferAvailableBytes().toLong().coerceAtLeast(0L) /
                wireFrameBytes.toLong()
        return writtenNow + pendingRingFrames
    }

    private fun maybeReportUnderrun() {
        if (!playing || handledEndOfStream || activeWireSampleRate <= 0) return
        val writtenSinceStart = UsbDacNative.nativeGetFramesWritten() - baseFramesWritten
        if (writtenSinceStart <= 0L) return

        val nativeUnderruns = UsbDacNative.nativeGetUnderrunCount()
        val hasNativeUnderrunDelta = nativeUnderruns > lastObservedUnderrunCount
        if (hasNativeUnderrunDelta) {
            lastObservedUnderrunCount = nativeUnderruns
            UsbDacManager.drainNativeLogsAsync()
        } else {
            val availBytes = UsbDacNative.nativeGetRingBufferAvailableBytes()
            if (availBytes > 0) return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (lastFeedRealtimeMs <= 0L || (nowMs - lastUnderrunReportMs) < UNDERRUN_REPORT_COOLDOWN_MS) {
            return
        }
        val elapsedSinceFeedMs = (nowMs - lastFeedRealtimeMs).coerceAtLeast(1L)

        lastUnderrunReportMs = nowMs
        val bytesPerSec = computeActiveWireByteRate()
        val ringCapacityBytes = (UsbDacNative.nativeGetRingBufferAvailableBytes() +
            UsbDacNative.nativeGetRingBufferFreeBytes()).coerceAtLeast(1)
        val bufferSizeMs = if (bytesPerSec > 0L) (ringCapacityBytes * MILLIS_PER_SECOND) / bytesPerSec else 0L
        listener?.onUnderrun(ringCapacityBytes, bufferSizeMs, elapsedSinceFeedMs)
    }

    /**
     * Computes target audio buffer size (1,500–2,000 ms, centered at 1,800 ms) from the stream's
     * actual output wire byte rate (`wireSampleRate * bytesPerFrame` when USB Direct is active, or
     * `(sampleRate * channelCount) / 8` for 1-bit DSD and `sampleRate * frameBytes` for PCM).
     */
    fun getBufferSizeInBytes(
        format: Format,
        targetDurationMs: Long = TARGET_BUFFER_DURATION_MS
    ): Int {
        val clampedMs = targetDurationMs.coerceIn(MIN_BUFFER_DURATION_MS, MAX_BUFFER_DURATION_MS)
        if (usingUsbDirect && activeWireSampleRate > 0 && activeWireChannels > 0) {
            val wireSubslot = activeWireSubslotSize.takeIf { it > 0 }
                ?: ((activeWireBitDepth.coerceAtLeast(16) + 7) / 8)
            val wireFrameBytes = activeWireChannels * wireSubslot
            val wireByteRate = activeWireSampleRate.toLong() * wireFrameBytes.toLong()
            val rawBytes = (wireByteRate * clampedMs) / MILLIS_PER_SECOND
            val aligned = ((rawBytes + wireFrameBytes - 1L) / wireFrameBytes) * wireFrameBytes
            return aligned.coerceIn(wireFrameBytes.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }

        val channels = format.channelCount.coerceAtLeast(1)
        val rate = format.sampleRate.coerceAtLeast(1)
        if (!isRawPcmFormat(format) && isDsdFormat(format)) {
            val dsdByteRate = (rate.toLong() * channels.toLong()) / 8L
            val rawBytes = (dsdByteRate * clampedMs) / MILLIS_PER_SECOND
            val align = channels * 4
            val aligned = ((rawBytes + align - 1L) / align) * align
            return aligned.coerceIn(align.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }

        val bytesPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val frameBytes = channels * bytesPerSample
        val pcmByteRate = rate.toLong() * frameBytes.toLong()
        val rawBytes = (pcmByteRate * clampedMs) / MILLIS_PER_SECOND
        val aligned = ((rawBytes + frameBytes - 1L) / frameBytes) * frameBytes
        return aligned.coerceIn(frameBytes.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun computeActiveWireByteRate(): Long {
        val subslotBytes = activeWireSubslotSize.takeIf { it > 0 }
            ?: ((activeWireBitDepth.coerceAtLeast(16) + 7) / 8)
        if (activeOutputMode == UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT ||
            activeOutputMode == UsbDacNative.OUTPUT_MODE_PCM_DSP
        ) {
            return activeWireSampleRate.toLong() * activeWireChannels.toLong() * subslotBytes.toLong()
        }
        if (activeOutputMode == UsbDacNative.OUTPUT_MODE_NATIVE_DSD) {
            val fmt = lastConfiguredFormat
            if (fmt != null && !isRawPcmFormat(fmt) && isDsdFormat(fmt) && fmt.sampleRate > 0 && fmt.channelCount > 0) {
                return (fmt.sampleRate.toLong() * fmt.channelCount.toLong()) / 8L
            }
        }
        return activeWireSampleRate.toLong() * activeWireChannels.toLong() * subslotBytes.toLong()
    }

    private fun ensureFallbackConfigured(fallbackReason: String? = null): Boolean {
        if (fallbackConfigured) return true
        val fmt = lastConfiguredFormat ?: return false
        configureFallbackSink(
            inputFormat = fmt,
            specifiedBufferSize = lastSpecifiedBufferSize,
            outputChannels = lastOutputChannels,
            fallbackReason = fallbackReason ?: describeFallbackReason(fmt)
        )
        return true
    }

    private fun configureFallbackSink(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
        fallbackReason: String
    ) {
        if (isDsdFormat(inputFormat)) {
            val dsdPlan = resolveFallbackDsdPlan(inputFormat)
                ?: throw AudioSink.ConfigurationException(
                    "Unsupported DSD fallback format: rate=${inputFormat.sampleRate}Hz ch=${inputFormat.channelCount}",
                    inputFormat
                )
            val gainDb = UsbDacManager.state.value.dsdGainCompensationDb
            val outputFloat = dsdPlan.pcmFormat.pcmEncoding == C.ENCODING_PCM_FLOAT
            val configured = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeFallbackDsdConfigure(
                dsdBitRate = inputFormat.sampleRate,
                targetPcmRate = dsdPlan.pcmFormat.sampleRate,
                inputChannels = inputFormat.channelCount,
                outputChannels = dsdPlan.pcmFormat.channelCount,
                outputFloat = outputFloat,
                gainDb = gainDb
            )
            if (!configured) {
                throw AudioSink.ConfigurationException(
                    "Failed to initialize native DSD-to-PCM FIR fallback: rate=${inputFormat.sampleRate}Hz ch=${inputFormat.channelCount}",
                    inputFormat
                )
            }
            val sampleBytes = if (outputFloat) 4 else 2
            val outFrameBytes = dsdPlan.pcmFormat.channelCount * sampleBytes
            fallbackDsdInputEncoding = dsdPlan.nativeDsdEncoding
            fallbackDsdOutputSampleRate = dsdPlan.pcmFormat.sampleRate
            fallbackDsdOutputFrameBytes = outFrameBytes
            fallbackDsdOutBuffer = ByteBuffer.allocateDirect(FALLBACK_DSD_MAX_OUT_FRAMES * outFrameBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { limit(0) }
            fallbackDsdOutPtsUs = C.TIME_UNSET
            lastFallbackDsdInputPtsUs = C.TIME_UNSET
            fallbackDsdNextPtsUs = C.TIME_UNSET
            fallbackDsdActive = true

            val dsdSourceBufBytes = getBufferSizeInBytes(inputFormat)
            val pcmFallbackBufBytes = minOf(
                getBufferSizeInBytes(dsdPlan.pcmFormat),
                HAL_FALLBACK_MAX_BUFFER_BYTES
            )
            val decFactor = inputFormat.sampleRate / dsdPlan.pcmFormat.sampleRate
            PDLog.i(
                PDLog.USB_DAC,
                "SINK_FALLBACK_DSD_PCM",
                "reason=$fallbackReason in=${inputFormat.sampleRate}Hz/${inputFormat.channelCount}ch " +
                    "out=${dsdPlan.pcmFormat.sampleRate}Hz/${dsdPlan.pcmFormat.channelCount}ch/" +
                    "${pcmEncodingName(dsdPlan.pcmFormat.pcmEncoding)} dec=${decFactor}:1 " +
                    "dsdBufB=$dsdSourceBufBytes pcmBufB=$pcmFallbackBufBytes"
            )
            AppLogManager.updateSinkState(
                mode = "ANDROID_FALLBACK (Native DSD->PCM FIR / $fallbackReason)",
                directDetail = "${dsdPlan.pcmFormat.sampleRate}Hz / ${pcmEncodingName(dsdPlan.pcmFormat.pcmEncoding)} " +
                    "(in=${inputFormat.sampleRate}Hz DSD)"
            )
            fallbackSink.configure(dsdPlan.pcmFormat, pcmFallbackBufBytes, outputChannels)
            fallbackConfigured = true
            return
        }

        clearFallbackDsdState(releaseDecimator = true)
        PDLog.i(
            PDLog.USB_DAC,
            "SINK_FALLBACK_HAL",
            "reason=$fallbackReason mime=${inputFormat.sampleMimeType} rate=${inputFormat.sampleRate} " +
                "ch=${inputFormat.channelCount} enc=${pcmEncodingName(inputFormat.pcmEncoding)} bufB=$specifiedBufferSize"
        )
        AppLogManager.updateSinkState(
            mode = "ANDROID_FALLBACK ($fallbackReason)",
            directDetail = "Inactive (${inputFormat.sampleRate}Hz / ${pcmEncodingName(inputFormat.pcmEncoding)})"
        )
        fallbackSink.configure(inputFormat, specifiedBufferSize, outputChannels)
        fallbackConfigured = true
    }

    private fun handleFallbackDsdBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long
    ): Boolean {
        val outBuf = fallbackDsdOutBuffer ?: return false
        val frameBytes = fallbackDsdOutputFrameBytes
        if (!UsbDacNative.isLibraryLoaded || frameBytes <= 0) return false

        // Drain any pending decimated PCM bytes left over from an earlier backpressure return first.
        if (outBuf.hasRemaining()) {
            val drained = fallbackSink.handleBuffer(outBuf, fallbackDsdOutPtsUs, 1)
            if (!drained || outBuf.hasRemaining()) return false
        }

        val remaining = buffer.remaining()
        if (remaining == 0) return true

        if (presentationTimeUs != lastFallbackDsdInputPtsUs) {
            lastFallbackDsdInputPtsUs = presentationTimeUs
            fallbackDsdNextPtsUs = presentationTimeUs
        }

        while (buffer.hasRemaining()) {
            val chunkPtsUs = fallbackDsdNextPtsUs
            val avail = buffer.remaining()
            val packedResult: Long
            if (buffer.isDirect) {
                packedResult = UsbDacNative.nativeFallbackDsdDecimate(
                    srcBuffer = buffer,
                    srcOffset = buffer.position(),
                    srcBytes = avail,
                    inputEncoding = fallbackDsdInputEncoding,
                    dstBuffer = outBuf,
                    dstCapacityBytes = outBuf.capacity()
                )
            } else {
                val staging = ensureFallbackDsdStagingBuffer(minOf(avail, FALLBACK_DSD_STAGING_BYTES))
                val copyLen = minOf(avail, staging.capacity())
                staging.clear()
                val savedPos = buffer.position()
                val savedLim = buffer.limit()
                buffer.limit(savedPos + copyLen)
                staging.put(buffer)
                buffer.limit(savedLim)
                buffer.position(savedPos)
                staging.flip()
                packedResult = UsbDacNative.nativeFallbackDsdDecimate(
                    srcBuffer = staging,
                    srcOffset = 0,
                    srcBytes = copyLen,
                    inputEncoding = fallbackDsdInputEncoding,
                    dstBuffer = outBuf,
                    dstCapacityBytes = outBuf.capacity()
                )
            }

            val consumedSrc = (packedResult ushr 32).toInt()
            val producedDst = (packedResult and 0xFFFFFFFFL).toInt()
            if (consumedSrc > 0) {
                buffer.position(buffer.position() + consumedSrc)
            }
            if (producedDst <= 0) {
                // Input bytes were absorbed into native residual staging or zero progress possible
                break
            }

            outBuf.position(0)
            outBuf.limit(producedDst)
            val producedFrames = producedDst / frameBytes
            fallbackDsdOutPtsUs = chunkPtsUs
            if (fallbackDsdOutputSampleRate > 0) {
                fallbackDsdNextPtsUs = chunkPtsUs + (producedFrames.toLong() * MICROS_PER_SECOND) / fallbackDsdOutputSampleRate
            }
            val handled = fallbackSink.handleBuffer(outBuf, fallbackDsdOutPtsUs, 1)
            if (!handled || outBuf.hasRemaining()) {
                return false
            }
        }

        return !buffer.hasRemaining() && !outBuf.hasRemaining()
    }

    private fun ensureFallbackDsdStagingBuffer(minBytes: Int): ByteBuffer {
        val existing = fallbackDsdStagingBuffer
        if (existing != null && existing.capacity() >= minBytes) {
            return existing
        }
        val cap = maxOf(minBytes, FALLBACK_DSD_STAGING_BYTES)
        return ByteBuffer.allocateDirect(cap).order(ByteOrder.LITTLE_ENDIAN).also {
            fallbackDsdStagingBuffer = it
        }
    }

    private fun resetFallbackDsdBuffers() {
        if (UsbDacNative.isLibraryLoaded) {
            UsbDacNative.nativeFallbackDsdReset()
        }
        fallbackDsdOutBuffer?.let {
            it.clear()
            it.limit(0)
        }
        fallbackDsdOutPtsUs = C.TIME_UNSET
        lastFallbackDsdInputPtsUs = C.TIME_UNSET
        fallbackDsdNextPtsUs = C.TIME_UNSET
    }

    private fun clearFallbackDsdState(releaseDecimator: Boolean) {
        fallbackDsdActive = false
        fallbackDsdInputEncoding = 0
        fallbackDsdOutputSampleRate = 0
        fallbackDsdOutputFrameBytes = 0
        fallbackDsdOutPtsUs = C.TIME_UNSET
        lastFallbackDsdInputPtsUs = C.TIME_UNSET
        fallbackDsdNextPtsUs = C.TIME_UNSET
        if (releaseDecimator) {
            if (UsbDacNative.isLibraryLoaded) {
                UsbDacNative.nativeFallbackDsdRelease()
            }
            fallbackDsdStagingBuffer = null
            fallbackDsdOutBuffer = null
        } else {
            resetFallbackDsdBuffers()
        }
    }

    private fun isRawPcmFormat(format: Format): Boolean =
        format.sampleMimeType.equals(MimeTypes.AUDIO_RAW, ignoreCase = true)

    private fun canHandleUsbDirectPcm(format: Format): Boolean {
        if (!UsbDacManager.isDirectReady()) return false
        if (format.sampleRate <= 0 || format.channelCount <= 0) return false
        return mapPcmToNativeEncoding(format) != 0
    }

    private fun canHandleUsbDirectDsd(format: Format): Boolean {
        if (!UsbDacManager.isDirectReady()) return false
        if (format.sampleRate <= 0 || format.channelCount <= 0) return false
        return mapDsdToNativeEncoding(format) != 0
    }

    private fun canHandleFallbackDsd(format: Format): Boolean {
        if (isRawPcmFormat(format) || !UsbDacNative.isLibraryLoaded) return false
        return isDsdFormat(format) && resolveFallbackDsdPlan(format) != null
    }

    private fun isDsdFormat(format: Format): Boolean {
        if (isRawPcmFormat(format)) return false
        return isNativeDsdEncoding(mapDsdToNativeEncoding(format))
    }

    private fun isNativeDsdEncoding(nativeEncoding: Int): Boolean =
        nativeEncoding == UsbDacNative.ENCODING_DSD_U8 ||
            nativeEncoding == UsbDacNative.ENCODING_DSD_U32_BE ||
            nativeEncoding == UsbDacNative.ENCODING_DSD_U32_LE

    private fun resolveFallbackDsdPlan(format: Format): FallbackDsdPlan? {
        if (isRawPcmFormat(format)) return null
        val nativeEnc = mapDsdToNativeEncoding(format)
        if (!isNativeDsdEncoding(nativeEnc)) return null
        val dsdRate = format.sampleRate
        val inChannels = format.channelCount
        if (dsdRate <= 0 || inChannels !in 1..8) return null

        val candidateChannelCounts = if (inChannels <= 2) {
            intArrayOf(inChannels)
        } else {
            intArrayOf(inChannels, 2)
        }
        val candidateEncodings = intArrayOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)

        for (targetRate in FALLBACK_DSD_PREFERRED_RATES) {
            if (dsdRate % targetRate != 0) continue
            val decimation = dsdRate / targetRate
            if (decimation < 8 || (decimation and (decimation - 1)) != 0) continue

            for (outCh in candidateChannelCounts) {
                for (pcmEnc in candidateEncodings) {
                    val candidate = Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_RAW)
                        .setSampleRate(targetRate)
                        .setChannelCount(outCh)
                        .setPcmEncoding(pcmEnc)
                        .build()
                    if (fallbackSink.supportsFormat(candidate)) {
                        return FallbackDsdPlan(
                            nativeDsdEncoding = nativeEnc,
                            pcmFormat = candidate
                        )
                    }
                }
            }
        }
        return null
    }

    private fun switchToUsbDirectIfNeeded() {
        val wasPlaying = playing
        if (pendingUsbDirectReset) {
            pendingUsbDirectReset = false
            resetInternal(clearConfiguredFormat = false)
            playing = wasPlaying
        }
        if (usingUsbDirect) return
        val fmt = lastConfiguredFormat ?: return
        if (canHandleUsbDirect(fmt)) {
            configure(fmt, lastSpecifiedBufferSize, lastOutputChannels)
            if (usingUsbDirect && playing) {
                UsbDacManager.setHalHiResPerfHint(false)
                applyPlaybackThreadHighPerf(true)
                UsbDacNative.nativeSetWarmUpMs(UsbDacManager.getWarmUpMs())
                UsbDacNative.nativeSetPaused(false)
                wasWarmingUp = UsbDacNative.isLibraryLoaded && UsbDacNative.nativeIsWarmingUp()
                UsbDacManager.setStreaming(true)
                lastFeedRealtimeMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * Elevates ExoPlayer's playback/DSP thread to `THREAD_PRIORITY_URGENT_AUDIO` and pins it onto
     * big/prime CPU cores while USB Direct PCM/DSD streaming is active, restoring normal scheduling
     * when paused or idle so battery consumption stays minimal.
     */
    private fun applyPlaybackThreadHighPerf(enabled: Boolean) {
        runCatching {
            Process.setThreadPriority(
                if (enabled) Process.THREAD_PRIORITY_URGENT_AUDIO else Process.THREAD_PRIORITY_AUDIO
            )
        }
        if (UsbDacNative.isLibraryLoaded) {
            runCatching { UsbDacNative.nativeSetPlaybackThreadHighPerf(enabled) }
        }
    }

    private fun canHandleUsbDirect(format: Format): Boolean {
        return if (isRawPcmFormat(format)) {
            canHandleUsbDirectPcm(format)
        } else {
            canHandleUsbDirectDsd(format)
        }
    }

    private fun mapToNativeEncoding(format: Format): Int {
        return if (isRawPcmFormat(format)) {
            mapPcmToNativeEncoding(format)
        } else {
            mapDsdToNativeEncoding(format)
        }
    }

    private fun mapPcmToNativeEncoding(format: Format): Int = when (format.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> UsbDacNative.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> UsbDacNative.ENCODING_PCM_24BIT
        C.ENCODING_PCM_32BIT -> UsbDacNative.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> UsbDacNative.ENCODING_PCM_FLOAT
        else -> 0
    }

    private fun mapDsdToNativeEncoding(format: Format): Int {
        val mime = format.sampleMimeType?.lowercase() ?: return 0
        return when (mime) {
            MIME_AUDIO_DSD_U32_BE, MIME_AUDIO_X_DSD_U32_BE -> UsbDacNative.ENCODING_DSD_U32_BE
            MIME_AUDIO_DSD_U32_LE, MIME_AUDIO_X_DSD_U32_LE -> UsbDacNative.ENCODING_DSD_U32_LE
            MIME_AUDIO_DSD, MIME_AUDIO_X_DSD, MIME_AUDIO_DSF, MIME_AUDIO_DFF, MIME_AUDIO_DSD_U8 -> {
                val codecHint = format.codecs?.lowercase().orEmpty()
                when {
                    codecHint.contains("u32be") -> UsbDacNative.ENCODING_DSD_U32_BE
                    codecHint.contains("u32le") -> UsbDacNative.ENCODING_DSD_U32_LE
                    else -> UsbDacNative.ENCODING_DSD_U8
                }
            }
            else -> 0
        }
    }

    private fun preferredBitDepthForFormat(format: Format, nativeEncoding: Int): Int = when (nativeEncoding) {
        UsbDacNative.ENCODING_PCM_16BIT -> 16
        UsbDacNative.ENCODING_PCM_24BIT -> 24
        UsbDacNative.ENCODING_PCM_32BIT, UsbDacNative.ENCODING_PCM_FLOAT -> 32
        UsbDacNative.ENCODING_DSD_U8,
        UsbDacNative.ENCODING_DSD_U32_BE,
        UsbDacNative.ENCODING_DSD_U32_LE -> 32
        else -> when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }
    }

    private fun describeFallbackReason(format: Format): String {
        val st = UsbDacManager.state.value
        return when {
            !st.directEnabled -> "usb_direct_disabled"
            !UsbDacManager.isDirectReady() -> "usb_status_${st.status}"
            mapToNativeEncoding(format) == 0 -> "unsupported_mime_${format.sampleMimeType ?: "null"}_enc_${format.pcmEncoding}"
            format.sampleRate <= 0 -> "invalid_rate_${format.sampleRate}"
            format.channelCount <= 0 -> "invalid_ch_${format.channelCount}"
            else -> "open_stream_rejected"
        }
    }

    private fun formatDirectModeHeader(outputMode: Int): String = when (outputMode) {
        UsbDacNative.OUTPUT_MODE_PCM_BIT_PERFECT -> "USB_DAC_DIRECT (Bit-Perfect)"
        UsbDacNative.OUTPUT_MODE_PCM_DSP -> "USB_DAC_DIRECT (Hi-Fi DSP)"
        UsbDacNative.OUTPUT_MODE_DOP -> "USB_DAC_DIRECT (DoP)"
        UsbDacNative.OUTPUT_MODE_NATIVE_DSD -> "USB_DAC_DIRECT (Native DSD)"
        UsbDacNative.OUTPUT_MODE_DSD_TO_PCM -> "USB_DAC_DIRECT (DSD->PCM FIR)"
        else -> "USB_DAC_DIRECT"
    }

    private fun formatDspFlagsDetail(flags: Int): String {
        if (flags == UsbDacNative.DSP_FLAG_NONE) return ""
        val parts = mutableListOf<String>()
        if ((flags and UsbDacNative.DSP_FLAG_DSD_TO_PCM) != 0) parts.add("DSD->PCM")
        if ((flags and UsbDacNative.DSP_FLAG_DOP_PACKING) != 0) parts.add("DoP")
        if ((flags and UsbDacNative.DSP_FLAG_RESAMPLER) != 0) parts.add("SRC64")
        if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_DOWNMIX) != 0) parts.add("Downmix")
        if ((flags and UsbDacNative.DSP_FLAG_CHANNEL_MONO_TO_STEREO) != 0) parts.add("Mono->2.0")
        if ((flags and UsbDacNative.DSP_FLAG_SW_VOLUME) != 0) parts.add("SWVol64")
        if ((flags and UsbDacNative.DSP_FLAG_DITHER_TPDF) != 0) parts.add("TPDF")
        if ((flags and UsbDacNative.DSP_FLAG_ZERO_PADDING) != 0) parts.add("ZeroPad")
        if ((flags and UsbDacNative.DSP_FLAG_FLOAT_TO_PCM) != 0) parts.add("Float->PCM")
        return if (parts.isEmpty()) "" else ", dsp=${parts.joinToString("+")}"
    }

    private fun inputEncodingName(format: Format, nativeEncoding: Int): String = when (nativeEncoding) {
        UsbDacNative.ENCODING_PCM_16BIT -> "PCM_16BIT"
        UsbDacNative.ENCODING_PCM_24BIT -> "PCM_24BIT"
        UsbDacNative.ENCODING_PCM_32BIT -> "PCM_32BIT"
        UsbDacNative.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        UsbDacNative.ENCODING_DSD_U8 -> "DSD_U8"
        UsbDacNative.ENCODING_DSD_U32_BE -> "DSD_U32_BE"
        UsbDacNative.ENCODING_DSD_U32_LE -> "DSD_U32_LE"
        else -> pcmEncodingName(format.pcmEncoding)
    }

    private fun pcmEncodingName(pcmEncoding: Int): String = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> "PCM_16BIT"
        C.ENCODING_PCM_24BIT -> "PCM_24BIT"
        C.ENCODING_PCM_32BIT -> "PCM_32BIT"
        C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        else -> "ENC($pcmEncoding)"
    }

    // ═══ Fallback DSD-to-PCM Plan ═══
    private data class FallbackDsdPlan(
        val nativeDsdEncoding: Int,
        val pcmFormat: Format
    )
}