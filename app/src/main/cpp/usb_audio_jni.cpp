/**
 * usb_audio_jni — JNI bridge binding UsbDacNative entrypoints to the singleton UsbAudioDriver.
 *
 * @remarks
 * Maintains a single process-wide `UsbAudioDriver` matching Android's single active USB DAC
 * lifecycle. Validates buffer bounds and enum codes at the JNI boundary to prevent UB from
 * malformed calls, and releases pinned byte arrays with `JNI_ABORT` on write paths to eliminate
 * redundant copy-back overhead on the playback thread.
 *
 * @see UsbDacNative
 * @see UsbAudioDriver
 */

// ═══ Includes ═══
#include "pd_log.h"
#include "usb_audio_driver.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

// ═══ Native State & Validation Helpers ═══
namespace {

UsbAudioDriver gDriver;

// Isolated native DSD-to-PCM FIR decimator for Android AudioSink fallback (speaker/Bluetooth/unplug)
struct FallbackDsdBridge {
    std::mutex mutex;
    bool active = false;
    int inputChannels = 2;
    int outputChannels = 2;
    bool outputFloat = true;
    DsdToPcmDecimator decimator;
    ChannelMatrixProcessor channelMatrix;
    std::vector<uint8_t> residualBuf;
    size_t residualCount = 0;
    std::vector<uint8_t> rawScratch;
    std::vector<uint8_t> u8Scratch;
    std::vector<double> pcmBufA;
    std::vector<double> pcmBufB;
};

FallbackDsdBridge gFallbackDsd;
constexpr size_t FALLBACK_DSD_MAX_OUT_FRAMES = 2048;
constexpr size_t FALLBACK_DSD_MAX_RAW_BYTES = 131072; // 128 KB handles batched DSD256/DSD512 blocks

bool isValidInputEncoding(jint raw) {
    switch (static_cast<AudioInputEncoding>(raw)) {
        case AudioInputEncoding::PCM_16BIT:
        case AudioInputEncoding::PCM_24BIT:
        case AudioInputEncoding::PCM_32BIT:
        case AudioInputEncoding::PCM_FLOAT:
        case AudioInputEncoding::DSD_U8:
        case AudioInputEncoding::DSD_U32_BE:
        case AudioInputEncoding::DSD_U32_LE:
            return true;
        default:
            return false;
    }
}

DsdPlaybackMode sanitizeDsdMode(jint raw) {
    switch (static_cast<DsdPlaybackMode>(raw)) {
        case DsdPlaybackMode::AUTO:
        case DsdPlaybackMode::NATIVE:
        case DsdPlaybackMode::DOP:
        case DsdPlaybackMode::PCM_DECIMATE:
            return static_cast<DsdPlaybackMode>(raw);
        default:
            return DsdPlaybackMode::AUTO;
    }
}

ResamplerQuality sanitizeResamplerQuality(jint raw) {
    switch (static_cast<ResamplerQuality>(raw)) {
        case ResamplerQuality::MEDIUM:
        case ResamplerQuality::HIGH:
        case ResamplerQuality::ULTRA:
            return static_cast<ResamplerQuality>(raw);
        default:
            return ResamplerQuality::HIGH;
    }
}

jintArray makeIntArray(JNIEnv* env, const int* values, size_t count) {
    jintArray arr = env->NewIntArray(static_cast<jsize>(count));
    if (!arr) return nullptr;
    if (count > 0 && values != nullptr) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(count), reinterpret_cast<const jint*>(values));
    }
    return arr;
}

} // namespace

// ═══ JNI Entrypoints ═══
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    return JNI_VERSION_1_6;
}

// ─── Device Lifecycle & UAC Capability Queries ───
JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeAttach(
        JNIEnv* env, jobject /* thiz */, jint fd, jstring devicePath, jbyteArray rawDescriptors) {
    if (fd < 0 || !rawDescriptors) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "JNI_ATTACH_REJECT | reason=invalid_args fd=%d hasDesc=%d", fd, rawDescriptors ? 1 : 0);
        return JNI_FALSE;
    }

    const jsize len = env->GetArrayLength(rawDescriptors);
    if (len <= 0) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "JNI_ATTACH_REJECT | reason=empty_desc fd=%d", fd);
        return JNI_FALSE;
    }

    std::string pathStr;
    if (devicePath != nullptr) {
        const char* chars = env->GetStringUTFChars(devicePath, nullptr);
        if (chars != nullptr) {
            pathStr.assign(chars);
            env->ReleaseStringUTFChars(devicePath, chars);
        }
    }

    jbyte* bytes = env->GetByteArrayElements(rawDescriptors, nullptr);
    if (!bytes) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "JNI_ATTACH_REJECT | reason=array_pin_fail fd=%d len=%d", fd, static_cast<int>(len));
        return JNI_FALSE;
    }

    const bool ok = gDriver.attachDevice(
        static_cast<int>(fd),
        pathStr,
        reinterpret_cast<const uint8_t*>(bytes),
        static_cast<size_t>(len)
    );
    env->ReleaseByteArrayElements(rawDescriptors, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeDetach(JNIEnv* /* env */, jobject /* thiz */) {
    gDriver.detachDevice();
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetUacVersion(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getDescriptorInfo().uacVersion);
}

JNIEXPORT jintArray JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetSupportedSampleRates(JNIEnv* env, jobject /* thiz */) {
    const auto info = gDriver.getDescriptorInfo();
    return makeIntArray(env, info.supportedSampleRates.data(), info.supportedSampleRates.size());
}

JNIEXPORT jintArray JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetSupportedBitDepths(JNIEnv* env, jobject /* thiz */) {
    const auto info = gDriver.getDescriptorInfo();
    std::vector<int> depths;
    for (const auto& alt : info.streamAltSettings) {
        if (alt.isPcm && std::find(depths.begin(), depths.end(), alt.bitResolution) == depths.end()) {
            depths.push_back(alt.bitResolution);
        }
    }
    std::sort(depths.begin(), depths.end());
    return makeIntArray(env, depths.data(), depths.size());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetMaxSupportedChannels(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getDescriptorInfo().maxSupportedChannels);
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSupportsNativeDsd(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.getDescriptorInfo().supportsNativeDsd ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSupportsDop(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.getDescriptorInfo().supportsDop ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetMaxDsdRate(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getDescriptorInfo().maxDsdRate);
}

// ─── Stream Configuration & Audio I/O ───
JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeOpenStream(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint sampleRate,
        jint channels,
        jint preferredBitDepth,
        jint inputEncoding,
        jint warmUpMs) {
    if (sampleRate <= 0 || channels <= 0 || !isValidInputEncoding(inputEncoding)) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "JNI_OPEN_REJECT | rate=%d ch=%d enc=%d prefBits=%d",
                static_cast<int>(sampleRate), static_cast<int>(channels),
                static_cast<int>(inputEncoding), static_cast<int>(preferredBitDepth));
        return JNI_FALSE;
    }
    const bool ok = gDriver.openStream(
        static_cast<int>(sampleRate),
        static_cast<int>(channels),
        static_cast<int>(preferredBitDepth),
        static_cast<AudioInputEncoding>(inputEncoding),
        static_cast<int>(warmUpMs)
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeCloseStream(JNIEnv* /* env */, jobject /* thiz */) {
    gDriver.closeStream();
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeWritePcm(
        JNIEnv* env, jobject /* thiz */, jobject directBuffer, jint offset, jint size, jint encoding) {
    if (!directBuffer || offset < 0 || size <= 0 || !isValidInputEncoding(encoding)) return 0;
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (capacity < 0 || static_cast<jlong>(offset) + static_cast<jlong>(size) > capacity) return 0;

    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!base) return 0;

    return static_cast<jint>(gDriver.writeAudio(
        base + offset,
        static_cast<int>(size),
        static_cast<AudioInputEncoding>(encoding)
    ));
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeWriteFloatRestoredPcm(
        JNIEnv* env, jobject /* thiz */, jobject directBuffer, jint offset, jint size, jint targetEncoding) {
    if (!directBuffer || offset < 0 || size <= 0 || !isValidInputEncoding(targetEncoding)) return -1;
    const jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (capacity < 0 || static_cast<jlong>(offset) + static_cast<jlong>(size) > capacity) return -1;

    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!base) return -1;

    return static_cast<jint>(gDriver.writeFloatRestoredToWire32(
        base + offset,
        static_cast<int>(size),
        static_cast<AudioInputEncoding>(targetEncoding)
    ));
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeWritePcmArray(
        JNIEnv* env, jobject /* thiz */, jbyteArray byteArray, jint offset, jint size, jint encoding) {
    if (!byteArray || offset < 0 || size <= 0 || !isValidInputEncoding(encoding)) return 0;
    const jsize arrLen = env->GetArrayLength(byteArray);
    if (static_cast<jlong>(offset) + static_cast<jlong>(size) > static_cast<jlong>(arrLen)) return 0;

    jbyte* ptr = env->GetByteArrayElements(byteArray, nullptr);
    if (!ptr) return 0;

    const int written = gDriver.writeAudio(
        reinterpret_cast<const uint8_t*>(ptr) + offset,
        static_cast<int>(size),
        static_cast<AudioInputEncoding>(encoding)
    );
    env->ReleaseByteArrayElements(byteArray, ptr, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeConvertFloatToPcm(
        JNIEnv* env,
        jobject /* thiz */,
        jobject srcDirectBuffer,
        jint srcOffset,
        jint sampleCount,
        jint targetEncoding,
        jobject dstDirectBuffer) {
    if (!srcDirectBuffer || !dstDirectBuffer || srcOffset < 0 || sampleCount <= 0) return 0;
    const auto enc = static_cast<AudioInputEncoding>(targetEncoding);
    int outBytesPerSample = 0;
    switch (enc) {
        case AudioInputEncoding::PCM_16BIT: outBytesPerSample = 2; break;
        case AudioInputEncoding::PCM_24BIT: outBytesPerSample = 3; break;
        case AudioInputEncoding::PCM_32BIT: outBytesPerSample = 4; break;
        default: return 0;
    }

    const jlong srcCap = env->GetDirectBufferCapacity(srcDirectBuffer);
    const jlong dstCap = env->GetDirectBufferCapacity(dstDirectBuffer);
    const jlong srcNeed = static_cast<jlong>(srcOffset) + static_cast<jlong>(sampleCount) * 4LL;
    const jlong dstNeed = static_cast<jlong>(sampleCount) * static_cast<jlong>(outBytesPerSample);
    if (srcCap < 0 || dstCap < 0 || srcNeed > srcCap || dstNeed > dstCap) return 0;

    const auto* srcBase = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcDirectBuffer));
    auto* dstBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstDirectBuffer));
    if (!srcBase || !dstBase) return 0;

    const uint8_t* src = srcBase + srcOffset;
    uint8_t* dst = dstBase;
    const size_t n = static_cast<size_t>(sampleCount);

    // Exact power-of-two scaling (32768 / 8388608 / 2147483648) with zero dithering preserves
    // 16-bit and 24-bit integer FLAC/ALAC samples bit-exactly through FFmpeg's float32 output.
    if (enc == AudioInputEncoding::PCM_16BIT) {
        for (size_t i = 0; i < n; ++i) {
            float f = 0.0f;
            std::memcpy(&f, src + i * 4u, sizeof(float));
            const float c = std::isfinite(f) ? std::clamp(f, -1.0f, 1.0f) : 0.0f;
            const int32_t s16 = std::clamp<int32_t>(
                static_cast<int32_t>(std::lrintf(c * 32768.0f)),
                -32768,
                32767
            );
            dst[0] = static_cast<uint8_t>(s16 & 0xFF);
            dst[1] = static_cast<uint8_t>((s16 >> 8) & 0xFF);
            dst += 2;
        }
    } else if (enc == AudioInputEncoding::PCM_24BIT) {
        for (size_t i = 0; i < n; ++i) {
            float f = 0.0f;
            std::memcpy(&f, src + i * 4u, sizeof(float));
            const float c = std::isfinite(f) ? std::clamp(f, -1.0f, 1.0f) : 0.0f;
            const int32_t s24 = std::clamp<int32_t>(
                static_cast<int32_t>(std::lrintf(c * 8388608.0f)),
                -8388608,
                8388607
            );
            const uint32_t u = static_cast<uint32_t>(s24);
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst += 3;
        }
    } else {
        for (size_t i = 0; i < n; ++i) {
            float f = 0.0f;
            std::memcpy(&f, src + i * 4u, sizeof(float));
            const double c = std::isfinite(f) ? std::clamp(static_cast<double>(f), -1.0, 1.0) : 0.0;
            const int64_t s64 = std::clamp<int64_t>(
                static_cast<int64_t>(std::llround(c * 2147483648.0)),
                -2147483648LL,
                2147483647LL
            );
            const uint32_t u = static_cast<uint32_t>(static_cast<int32_t>(s64));
            dst[0] = static_cast<uint8_t>(u & 0xFFu);
            dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
            dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            dst += 4;
        }
    }
    return static_cast<jint>(dstNeed);
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeFlush(JNIEnv* /* env */, jobject /* thiz */, jint warmUpMs) {
    gDriver.flush(static_cast<int>(warmUpMs));
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetWarmUpMs(JNIEnv* /* env */, jobject /* thiz */, jint warmUpMs) {
    gDriver.setWarmUpMs(static_cast<int>(warmUpMs));
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetPaused(JNIEnv* /* env */, jobject /* thiz */, jboolean paused) {
    gDriver.setPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetEndOfStream(JNIEnv* /* env */, jobject /* thiz */, jboolean eos) {
    gDriver.setEndOfStream(eos == JNI_TRUE);
}

// ─── DSD & Fallback DSP Configuration ───
JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetDsdMode(JNIEnv* /* env */, jobject /* thiz */, jint dsdMode) {
    gDriver.setDsdMode(sanitizeDsdMode(dsdMode));
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetDsdGainCompensationDb(
        JNIEnv* /* env */, jobject /* thiz */, jfloat gainDb) {
    gDriver.setDsdGainCompensationDb(static_cast<float>(gainDb));
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetDitherEnabled(
        JNIEnv* /* env */, jobject /* thiz */, jboolean enabled) {
    gDriver.setDitherEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetResamplerQuality(
        JNIEnv* /* env */, jobject /* thiz */, jint quality) {
    gDriver.setResamplerQuality(sanitizeResamplerQuality(quality));
}

// ─── Hardware & 64-bit Software Volume Control ───
JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetVolume(
        JNIEnv* /* env */, jobject /* thiz */, jfloat normalized01) {
    gDriver.setVolume(static_cast<float>(normalized01));
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetMuted(
        JNIEnv* /* env */, jobject /* thiz */, jboolean muted) {
    gDriver.setMuted(muted == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeHasHardwareVolume(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.hasHardwareVolume() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetHardwareVolumeRangeDb256(JNIEnv* env, jobject /* thiz */) {
    int range[3]{0, 0, 256};
    gDriver.getHardwareVolumeRangeDb256(range[0], range[1], range[2]);
    return makeIntArray(env, range, 3);
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeIsSoftwareVolumeBypassed(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.isSoftwareVolumeBypassed() ? JNI_TRUE : JNI_FALSE;
}

// ─── Active Format, Clock Sync & Diagnostics Telemetry ───
JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveSampleRate(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveSampleRate());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveBitDepth(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveBitDepth());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveSubslotSize(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveSubslotSize());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveChannels(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveChannels());
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeIsHighSpeed(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.isHighSpeed() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeIsDeviceDisconnected(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.isDeviceDisconnected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeIsWarmingUp(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.isWarmingUp() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeSetPlaybackThreadHighPerf(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jboolean enabled
) {
    return UsbAudioDriver::setCallingThreadHighPerf(enabled == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetPipelineLatencyUs(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getPipelineLatencyUs());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetLastWorkDurationNs(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getLastWorkDurationNs());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveOutputMode(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveOutputMode());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetActiveDspFlags(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getActiveDspFlags());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetClockSyncMode(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getClockSyncMode());
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeIsFeedbackLocked(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.isFeedbackLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetClockDriftPpmX10(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getClockDriftPpmX10());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetFramesWritten(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getFramesWritten());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetStreamGeneration(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getStreamGeneration());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetUnderrunCount(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getUnderrunCount());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetStallCount(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getStallCount());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetMaxStallMs(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getMaxStallMs());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetJitterCount(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getJitterCount());
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetMaxUrbGapMs(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jlong>(gDriver.getMaxUrbGapMs());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetStreamingTid(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getStreamingTid());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetRingBufferFreeBytes(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getRingBufferFreeBytes());
}

JNIEXPORT jint JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetRingBufferAvailableBytes(JNIEnv* /* env */, jobject /* thiz */) {
    return static_cast<jint>(gDriver.getRingBufferAvailableBytes());
}

JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeHasPendingLogs(JNIEnv* /* env */, jobject /* thiz */) {
    return gDriver.hasPendingRtEvents() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeDrainLogs(JNIEnv* env, jobject /* thiz */) {
    const auto logs = gDriver.drainNativeLogs();
    jclass strCls = env->FindClass("java/lang/String");
    if (!strCls) return nullptr;

    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(logs.size()), strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;

    for (size_t i = 0; i < logs.size(); ++i) {
        jstring s = env->NewStringUTF(logs[i].c_str());
        if (s) {
            env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
            env->DeleteLocalRef(s);
        }
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeGetDiagnosticsSummary(JNIEnv* env, jobject /* thiz */) {
    const std::string summary = gDriver.getDiagnosticsSummary();
    return env->NewStringUTF(summary.c_str());
}

// ─── Fallback DSD-to-PCM C++ FIR Bridge ───
JNIEXPORT jboolean JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeFallbackDsdConfigure(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jint dsdBitRate,
        jint targetPcmRate,
        jint inputChannels,
        jint outputChannels,
        jboolean outputFloat,
        jfloat gainDb) {
    std::lock_guard<std::mutex> lock(gFallbackDsd.mutex);
    gFallbackDsd.active = false;
    gFallbackDsd.residualCount = 0;

    if (dsdBitRate <= 0 || targetPcmRate <= 0 ||
        inputChannels <= 0 || inputChannels > 8 ||
        outputChannels <= 0 || outputChannels > 8) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "FALLBACK_DSD_CFG_REJECT | dsdRate=%d pcmRate=%d inCh=%d outCh=%d",
                static_cast<int>(dsdBitRate), static_cast<int>(targetPcmRate),
                static_cast<int>(inputChannels), static_cast<int>(outputChannels));
        return JNI_FALSE;
    }

    if (!gFallbackDsd.decimator.configure(
            static_cast<int>(dsdBitRate),
            static_cast<int>(targetPcmRate),
            static_cast<int>(inputChannels),
            static_cast<float>(gainDb))) {
        PD_LOGE(PD_TAG_JNI_BRIDGE, "FALLBACK_DSD_CFG_ERR | dsdRate=%d pcmRate=%d inCh=%d gainDb=%.1f",
                static_cast<int>(dsdBitRate), static_cast<int>(targetPcmRate),
                static_cast<int>(inputChannels), static_cast<float>(gainDb));
        return JNI_FALSE;
    }

    gFallbackDsd.inputChannels = static_cast<int>(inputChannels);
    gFallbackDsd.outputChannels = static_cast<int>(outputChannels);
    gFallbackDsd.outputFloat = (outputFloat == JNI_TRUE);
    gFallbackDsd.channelMatrix.configure(gFallbackDsd.inputChannels, gFallbackDsd.outputChannels);

    const size_t dsdBytesPerOutFrame = static_cast<size_t>(
        std::max(1, gFallbackDsd.decimator.dsdBytesPerOutputFramePerChannel())
    );
    const size_t maxUnitBytes = std::max<size_t>(4u, dsdBytesPerOutFrame) *
                                static_cast<size_t>(gFallbackDsd.inputChannels) * 4u;
    gFallbackDsd.residualBuf.assign(maxUnitBytes, 0x69u);
    gFallbackDsd.rawScratch.assign(FALLBACK_DSD_MAX_RAW_BYTES, 0x69u);
    gFallbackDsd.u8Scratch.assign(FALLBACK_DSD_MAX_RAW_BYTES, 0x69u);

    const size_t maxCh = static_cast<size_t>(std::max(gFallbackDsd.inputChannels, gFallbackDsd.outputChannels));
    gFallbackDsd.pcmBufA.assign(FALLBACK_DSD_MAX_OUT_FRAMES * maxCh, 0.0);
    gFallbackDsd.pcmBufB.assign(FALLBACK_DSD_MAX_OUT_FRAMES * maxCh, 0.0);
    gFallbackDsd.active = true;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeFallbackDsdReset(JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gFallbackDsd.mutex);
    gFallbackDsd.residualCount = 0;
    gFallbackDsd.decimator.reset();
}

JNIEXPORT void JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeFallbackDsdRelease(JNIEnv* /* env */, jobject /* thiz */) {
    std::lock_guard<std::mutex> lock(gFallbackDsd.mutex);
    gFallbackDsd.active = false;
    gFallbackDsd.residualCount = 0;
    gFallbackDsd.residualBuf.clear();
    gFallbackDsd.rawScratch.clear();
    gFallbackDsd.u8Scratch.clear();
    gFallbackDsd.pcmBufA.clear();
    gFallbackDsd.pcmBufB.clear();
}

JNIEXPORT jlong JNICALL
Java_com_quandot_musicplayer_audio_UsbDacNative_nativeFallbackDsdDecimate(
        JNIEnv* env,
        jobject /* thiz */,
        jobject srcBuffer,
        jint srcOffset,
        jint srcBytes,
        jint inputEncoding,
        jobject dstBuffer,
        jint dstCapacityBytes) {
    if (!srcBuffer || !dstBuffer || srcOffset < 0 || srcBytes <= 0 || dstCapacityBytes <= 0) {
        return 0;
    }
    if (!isValidInputEncoding(inputEncoding)) return 0;
    const auto enc = static_cast<AudioInputEncoding>(inputEncoding);
    if (!AudioDspPipeline::isDsdEncoding(enc)) return 0;

    const jlong srcCap = env->GetDirectBufferCapacity(srcBuffer);
    const jlong dstCap = env->GetDirectBufferCapacity(dstBuffer);
    if (srcCap < 0 || dstCap < 0 ||
        static_cast<jlong>(srcOffset) + static_cast<jlong>(srcBytes) > srcCap ||
        static_cast<jlong>(dstCapacityBytes) > dstCap) {
        return 0;
    }

    const auto* srcBase = static_cast<const uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dstBase = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));
    if (!srcBase || !dstBase) return 0;

    std::lock_guard<std::mutex> lock(gFallbackDsd.mutex);
    if (!gFallbackDsd.active) return 0;

    const uint8_t* srcPtr = srcBase + srcOffset;
    const size_t availSrc = static_cast<size_t>(srcBytes);
    const size_t inCh = static_cast<size_t>(gFallbackDsd.inputChannels);
    const size_t outCh = static_cast<size_t>(gFallbackDsd.outputChannels);
    const size_t outSampleBytes = gFallbackDsd.outputFloat ? 4u : 2u;
    const size_t outFrameBytes = outCh * outSampleBytes;
    const size_t maxDstFrames = std::min(
        static_cast<size_t>(dstCapacityBytes) / outFrameBytes,
        FALLBACK_DSD_MAX_OUT_FRAMES
    );
    if (maxDstFrames == 0) return 0;

    const size_t dsdBytesPerOutFrame = static_cast<size_t>(
        std::max(1, gFallbackDsd.decimator.dsdBytesPerOutputFramePerChannel())
    );
    const size_t unitBytes = (enc == AudioInputEncoding::DSD_U8)
        ? (dsdBytesPerOutFrame * inCh)
        : (((dsdBytesPerOutFrame + 3u) / 4u) * 4u * inCh);
    if (unitBytes == 0 || unitBytes > gFallbackDsd.rawScratch.size()) return 0;

    const size_t totalAvail = gFallbackDsd.residualCount + availSrc;
    if (totalAvail < unitBytes) {
        if (gFallbackDsd.residualCount + availSrc <= gFallbackDsd.residualBuf.size()) {
            std::memcpy(gFallbackDsd.residualBuf.data() + gFallbackDsd.residualCount, srcPtr, availSrc);
            gFallbackDsd.residualCount += availSrc;
            return static_cast<jlong>(static_cast<uint64_t>(availSrc) << 32);
        }
        return 0;
    }

    const size_t maxUnitsByRaw = gFallbackDsd.rawScratch.size() / unitBytes;
    const size_t maxUnitsByDst = (enc == AudioInputEncoding::DSD_U8)
        ? maxDstFrames
        : (maxDstFrames / std::max<size_t>(1u, (((dsdBytesPerOutFrame + 3u) / 4u) * 4u) / dsdBytesPerOutFrame));
    const size_t unitsToProcess = std::min({ totalAvail / unitBytes, maxUnitsByRaw, std::max<size_t>(1u, maxUnitsByDst) });
    const size_t bytesToProcess = unitsToProcess * unitBytes;
    if (bytesToProcess == 0 || bytesToProcess > gFallbackDsd.rawScratch.size()) return 0;

    const size_t fromResidual = std::min(gFallbackDsd.residualCount, bytesToProcess);
    if (fromResidual > 0) {
        std::memcpy(gFallbackDsd.rawScratch.data(), gFallbackDsd.residualBuf.data(), fromResidual);
        const size_t leftover = gFallbackDsd.residualCount - fromResidual;
        if (leftover > 0) {
            std::memmove(gFallbackDsd.residualBuf.data(), gFallbackDsd.residualBuf.data() + fromResidual, leftover);
        }
        gFallbackDsd.residualCount = leftover;
    }
    const size_t fromInput = bytesToProcess - fromResidual;
    if (fromInput > 0) {
        std::memcpy(gFallbackDsd.rawScratch.data() + fromResidual, srcPtr, fromInput);
    }

    const uint8_t* u8Ptr = gFallbackDsd.rawScratch.data();
    size_t u8Frames = bytesToProcess / inCh;
    if (enc != AudioInputEncoding::DSD_U8) {
        const bool isLe = (enc == AudioInputEncoding::DSD_U32_LE);
        const size_t blockBytes = 4u * inCh;
        const size_t blocks = bytesToProcess / blockBytes;
        for (size_t b = 0; b < blocks; ++b) {
            const uint8_t* blkPtr = gFallbackDsd.rawScratch.data() + b * blockBytes;
            for (size_t sub = 0; sub < 4u; ++sub) {
                const size_t byteOff = isLe ? (3u - sub) : sub;
                uint8_t* outRow = gFallbackDsd.u8Scratch.data() + (b * 4u + sub) * inCh;
                for (size_t c = 0; c < inCh; ++c) {
                    outRow[c] = blkPtr[c * 4u + byteOff];
                }
            }
        }
        u8Ptr = gFallbackDsd.u8Scratch.data();
        u8Frames = blocks * 4u;
    }

    const size_t pcmFrames = gFallbackDsd.decimator.processDsdU8(
        u8Ptr,
        u8Frames,
        gFallbackDsd.pcmBufA.data(),
        maxDstFrames
    );

    const double* activePcm = gFallbackDsd.pcmBufA.data();
    if (pcmFrames > 0 && gFallbackDsd.channelMatrix.isActive()) {
        gFallbackDsd.channelMatrix.process(activePcm, gFallbackDsd.pcmBufB.data(), pcmFrames);
        activePcm = gFallbackDsd.pcmBufB.data();
    }

    const size_t totalSamples = pcmFrames * outCh;
    if (gFallbackDsd.outputFloat) {
        uint8_t* dst = dstBase;
        for (size_t i = 0; i < totalSamples; ++i) {
            const double d = activePcm[i];
            const float f = std::isfinite(d) ? static_cast<float>(std::clamp(d, -1.0, 1.0)) : 0.0f;
            std::memcpy(dst, &f, sizeof(float));
            dst += sizeof(float);
        }
    } else {
        uint8_t* dst = dstBase;
        for (size_t i = 0; i < totalSamples; ++i) {
            const double d = activePcm[i];
            const double clamped = std::isfinite(d) ? std::clamp(d, -1.0, 1.0) : 0.0;
            const int32_t i16 = std::clamp<int32_t>(
                static_cast<int32_t>(std::llround(clamped * 32767.0)),
                -32768,
                32767
            );
            dst[0] = static_cast<uint8_t>(i16 & 0xFF);
            dst[1] = static_cast<uint8_t>((i16 >> 8) & 0xFF);
            dst += 2;
        }
    }

    const size_t producedBytes = pcmFrames * outFrameBytes;
    const uint64_t packed = (static_cast<uint64_t>(fromInput) << 32) |
                            (static_cast<uint64_t>(producedBytes) & 0xFFFFFFFFULL);
    return static_cast<jlong>(packed);
}

} // extern "C"