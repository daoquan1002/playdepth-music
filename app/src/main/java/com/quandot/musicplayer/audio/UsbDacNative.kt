/**
 * UsbDacNative — JNI bridge declarations and native library loader for direct USB DAC streaming.
 *
 * @remarks
 * Isolates `System.loadLibrary` failures behind [isLibraryLoaded] so devices without matching
 * native ABIs or encountering linker errors cleanly fall back to Android AudioTrack.
 * All `native*` entrypoints and integer enum/bitmask constants are ABI-locked to
 * `usb_audio_jni.cpp`, `audio_dsp_pipeline.h`, and `clock_sync.h`.
 *
 * @see UsbDacManager
 * @see UsbDacAudioSink
 */
package com.quandot.musicplayer.audio

// External SDK / Jetpack / third-party libraries
import java.nio.ByteBuffer

// Internal modules
import com.quandot.musicplayer.debug.PDLog

// ═══ Constants ═══
private const val NATIVE_LIBRARY_NAME = "playdepth_usbdac"

// ═══ JNI Bridge ═══
object UsbDacNative {
    // ─── AudioInputEncoding (ABI-Locked to audio_dsp_pipeline.h) ───
    const val ENCODING_PCM_16BIT = 2
    const val ENCODING_PCM_24BIT = 3
    const val ENCODING_PCM_32BIT = 4
    const val ENCODING_PCM_FLOAT = 5
    const val ENCODING_DSD_U8 = 10
    const val ENCODING_DSD_U32_BE = 11
    const val ENCODING_DSD_U32_LE = 12

    // ─── OutputTransportMode (ABI-Locked to audio_dsp_pipeline.h) ───
    const val OUTPUT_MODE_INACTIVE = 0
    const val OUTPUT_MODE_PCM_BIT_PERFECT = 1
    const val OUTPUT_MODE_PCM_DSP = 2
    const val OUTPUT_MODE_DOP = 3
    const val OUTPUT_MODE_NATIVE_DSD = 4
    const val OUTPUT_MODE_DSD_TO_PCM = 5

    // ─── DsdPlaybackMode (ABI-Locked to audio_dsp_pipeline.h) ───
    const val DSD_MODE_AUTO = 0
    const val DSD_MODE_NATIVE = 1
    const val DSD_MODE_DOP = 2
    const val DSD_MODE_PCM_DECIMATE = 3

    // ─── ResamplerQuality (ABI-Locked to audio_dsp_pipeline.h) ───
    const val RESAMPLER_QUALITY_MEDIUM = 0
    const val RESAMPLER_QUALITY_HIGH = 1
    const val RESAMPLER_QUALITY_ULTRA = 2

    // ─── DspStageFlags Bitmask (ABI-Locked to audio_dsp_pipeline.h) ───
    const val DSP_FLAG_NONE = 0
    const val DSP_FLAG_RESAMPLER = 1 shl 0
    const val DSP_FLAG_DITHER_TPDF = 1 shl 1
    const val DSP_FLAG_DSD_TO_PCM = 1 shl 2
    const val DSP_FLAG_CHANNEL_DOWNMIX = 1 shl 3
    const val DSP_FLAG_CHANNEL_MONO_TO_STEREO = 1 shl 4
    const val DSP_FLAG_SW_VOLUME = 1 shl 5
    const val DSP_FLAG_DOP_PACKING = 1 shl 6
    const val DSP_FLAG_ZERO_PADDING = 1 shl 7
    const val DSP_FLAG_FLOAT_TO_PCM = 1 shl 8

    // ─── UAC Protocol Versions (ABI-Locked to uac_descriptor.h) ───
    const val UAC_VERSION_1_0 = 0x0100
    const val UAC_VERSION_2_0 = 0x0200
    const val UAC_VERSION_3_0 = 0x0300

    // ─── ClockSyncMode (ABI-Locked to clock_sync.h) ───
    const val SYNC_MODE_NONE = 0
    const val SYNC_MODE_ASYNC_FEEDBACK = 1
    const val SYNC_MODE_ADAPTIVE = 2
    const val SYNC_MODE_SYNCHRONOUS = 3
    const val SYNC_MODE_WATCHDOG_FALLBACK = 4

    var isLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary(NATIVE_LIBRARY_NAME)
            isLibraryLoaded = true
        } catch (t: Throwable) {
            isLibraryLoaded = false
            PDLog.e(PDLog.JNI_BRIDGE, "NATIVE_LIB_LOAD_ERR", "lib=$NATIVE_LIBRARY_NAME err=${t.message}", t)
        }
    }

    // ─── Device Lifecycle & UAC Capability Queries ───
    external fun nativeAttach(fd: Int, devicePath: String?, rawDescriptors: ByteArray): Boolean
    external fun nativeDetach()
    external fun nativeGetUacVersion(): Int
    external fun nativeGetSupportedSampleRates(): IntArray
    external fun nativeGetSupportedBitDepths(): IntArray
    external fun nativeGetMaxSupportedChannels(): Int
    external fun nativeSupportsNativeDsd(): Boolean
    external fun nativeSupportsDop(): Boolean
    external fun nativeGetMaxDsdRate(): Int

    // ─── Stream Configuration & Audio I/O ───
    external fun nativeOpenStream(
        sampleRate: Int,
        channels: Int,
        preferredBitDepth: Int,
        inputEncoding: Int,
        warmUpMs: Int
    ): Boolean

    external fun nativeCloseStream()
    external fun nativeWritePcm(directBuffer: ByteBuffer, offset: Int, size: Int, encoding: Int): Int
    external fun nativeWriteFloatRestoredPcm(
        directBuffer: ByteBuffer,
        offset: Int,
        size: Int,
        targetEncoding: Int
    ): Int
    external fun nativeWritePcmArray(byteArray: ByteArray, offset: Int, size: Int, encoding: Int): Int
    external fun nativeConvertFloatToPcm(
        srcDirectBuffer: ByteBuffer,
        srcOffset: Int,
        sampleCount: Int,
        targetEncoding: Int,
        dstDirectBuffer: ByteBuffer
    ): Int
    external fun nativeFlush(warmUpMs: Int)
    external fun nativeSetWarmUpMs(warmUpMs: Int)
    external fun nativeSetPaused(paused: Boolean)
    external fun nativeSetEndOfStream(eos: Boolean)

    // ─── DSD & Fallback DSP Configuration ───
    external fun nativeSetDsdMode(dsdMode: Int)
    external fun nativeSetDsdGainCompensationDb(gainDb: Float)
    external fun nativeSetDitherEnabled(enabled: Boolean)
    external fun nativeSetResamplerQuality(quality: Int)

    // ─── Hardware & 64-bit Software Volume Control ───
    external fun nativeSetVolume(normalized01: Float)
    external fun nativeSetMuted(muted: Boolean)
    external fun nativeHasHardwareVolume(): Boolean
    external fun nativeGetHardwareVolumeRangeDb256(): IntArray
    external fun nativeIsSoftwareVolumeBypassed(): Boolean

    // ─── Active Format, Clock Sync & Diagnostics Telemetry ───
    external fun nativeGetActiveSampleRate(): Int
    external fun nativeGetActiveBitDepth(): Int
    external fun nativeGetActiveSubslotSize(): Int
    external fun nativeGetActiveChannels(): Int
    external fun nativeIsHighSpeed(): Boolean
    external fun nativeIsDeviceDisconnected(): Boolean
    external fun nativeIsWarmingUp(): Boolean
    external fun nativeSetPlaybackThreadHighPerf(enabled: Boolean): Boolean
    external fun nativeGetPipelineLatencyUs(): Long
    external fun nativeGetLastWorkDurationNs(): Long
    external fun nativeGetActiveOutputMode(): Int
    external fun nativeGetActiveDspFlags(): Int
    external fun nativeGetClockSyncMode(): Int
    external fun nativeIsFeedbackLocked(): Boolean
    external fun nativeGetClockDriftPpmX10(): Int
    external fun nativeGetFramesWritten(): Long
    external fun nativeGetStreamGeneration(): Long
    external fun nativeGetUnderrunCount(): Long
    external fun nativeGetStallCount(): Long
    external fun nativeGetMaxStallMs(): Long
    external fun nativeGetJitterCount(): Long
    external fun nativeGetMaxUrbGapMs(): Long
    external fun nativeGetStreamingTid(): Int
    external fun nativeGetRingBufferFreeBytes(): Int
    external fun nativeGetRingBufferAvailableBytes(): Int
    external fun nativeHasPendingLogs(): Boolean
    external fun nativeDrainLogs(): Array<String>
    external fun nativeGetDiagnosticsSummary(): String

    // ─── Fallback DSD-to-PCM C++ FIR Bridge (Speaker / Bluetooth / Unplug Fallback) ───
    external fun nativeFallbackDsdConfigure(
        dsdBitRate: Int,
        targetPcmRate: Int,
        inputChannels: Int,
        outputChannels: Int,
        outputFloat: Boolean,
        gainDb: Float
    ): Boolean

    external fun nativeFallbackDsdReset()
    external fun nativeFallbackDsdRelease()

    /**
     * Decimates packed DSD bytes from [srcBuffer] into [dstBuffer] using the native C++ Byte-LUT
     * and symmetric Half-Band FIR decimator. Returns packed `(consumedSrcBytes << 32) | producedDstBytes`.
     */
    external fun nativeFallbackDsdDecimate(
        srcBuffer: ByteBuffer,
        srcOffset: Int,
        srcBytes: Int,
        inputEncoding: Int,
        dstBuffer: ByteBuffer,
        dstCapacityBytes: Int
    ): Long
}