/**
 * usb_audio_driver — Top-level UAC 1.0/2.0/3.0 USB DAC driver orchestrator and RT ISO engine.
 *
 * @remarks
 * Coordinates raw UAC descriptor parsing, direct Linux `usbdevfs` URB transport, async clock
 * synchronization, pure C++17 Smart-Bypass DSP, and the lock-free SPSC ring buffer.
 * Locking is strictly partitioned to guarantee glitch-free playback:
 *   - `controlMutex_` guards device attach/detach, stream open/close, and Feature Unit USB
 *     control transfers (`setVolume`/`setMuted`).
 *   - `producerMutex_` guards `writeAudio()` and `dspPipeline_` state so slow I2C Feature Unit
 *     transfers never stall decoder audio feeding. Whenever both locks are needed, `controlMutex_`
 *     is always acquired before `producerMutex_` to prevent lock-order inversions.
 *   - `streamingLoop()` (`UsbDacIsoThread`) runs with `SCHED_FIFO` (falling back to `nice -20`),
 *     never acquires `controlMutex_` or `producerMutex_`, and performs zero heap allocations.
 *
 * Feature Unit volume and mute transfers strictly prioritize Master Channel `CN = 0` whenever
 * `bmaControls[0]` advertises support so Left and Right channels update atomically in a single
 * USB control transfer. Per-channel fallback (`CN = 1, 2, ...`) inserts a brief I2C pacing delay
 * between channels and verifies readback after unmute/stream-open transitions so USB-to-I2C bridge
 * DACs (such as CS43131 dongles) never drop Left-channel updates.
 *
 * @see usb_audio_driver.h
 * @see usb_transport.h
 * @see clock_sync.h
 * @see audio_dsp_pipeline.h
 * @see spsc_ring_buffer.h
 * @see usb_audio_jni.cpp
 */

// ═══ Includes ═══
#include "usb_audio_driver.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <limits>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <sstream>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

// ═══ Structured Off-RT Logging Macros (NEVER call from streamingLoop) ═══
#define DRV_LOGE(fmt, ...) PD_LOGE_SINK(PD_TAG_NATIVE_AUDIO, &UsbAudioDriver::nativeLogSinkCallback, this, fmt, ##__VA_ARGS__)
#define DRV_LOGW(fmt, ...) PD_LOGW_SINK(PD_TAG_NATIVE_AUDIO, &UsbAudioDriver::nativeLogSinkCallback, this, fmt, ##__VA_ARGS__)
#define DRV_LOGI(fmt, ...) PD_LOGI_SINK(PD_TAG_NATIVE_AUDIO, &UsbAudioDriver::nativeLogSinkCallback, this, fmt, ##__VA_ARGS__)
#define DRV_LOGD(fmt, ...) PD_LOGD_SINK(PD_TAG_NATIVE_AUDIO, &UsbAudioDriver::nativeLogSinkCallback, this, fmt, ##__VA_ARGS__)

namespace {

// ═══ Constants ═══
constexpr int PREBUFFER_MS_STANDARD = 200;             // Standard PCM (<=48 kHz / 16-bit) jitter cushion
constexpr int PREBUFFER_MS_HIRES = 260;                // Hi-Res PCM (>48 kHz or >=24-bit) jitter cushion
constexpr int DSD_PREBUFFER_MS = 320;                  // High-bitrate DSD64..DSD512 / DoP / DSD-to-PCM cushion
constexpr int REBUFFER_MS_STANDARD = 40;               // Standard PCM post-underrun recovery cushion
constexpr int REBUFFER_MS_HIRES = 60;                  // Hi-Res PCM post-underrun recovery cushion
constexpr int DSD_REBUFFER_AFTER_UNDERRUN_MS = 80;     // DSD post-underrun recovery cushion
constexpr int MAX_BUFFER_MS_STANDARD = 2500;           // 2.5 s headroom for Non-Hi-Res PCM (~441 KB @ 44.1/16)
constexpr int MAX_BUFFER_MS_HIRES = 3000;              // 3.0 s headroom for Hi-Res PCM & DSD (up to 32 MB ring)
constexpr int POLL_TIMEOUT_MS = 10;                    // Tight poll wake cadence for low-jitter ISO/feedback loop
constexpr int DRAIN_RETRY_COUNT = 20;
constexpr useconds_t DRAIN_STEP_US = 10000;
constexpr useconds_t MIN_PAUSE_SILENCE_DRAIN_US = 25000;   // Floor drain window for short HS queues
constexpr useconds_t MAX_PAUSE_SILENCE_DRAIN_US = 120000;  // Cap drain window for FS / high-bInterval queues
constexpr useconds_t DSD_HW_MUTE_SETTLE_US = 40000;        // 40 ms balanced 0x69 / DoP silence before unmuting DAC
constexpr useconds_t PCM_HW_MUTE_SETTLE_US = 30000;        // 30 ms settle window after PCM rate/altSetting switch
constexpr useconds_t FEATURE_UNIT_CMD_DELAY_US = 3000; // 3 ms I2C bridge pacing on Mute->Vol or retry
constexpr int64_t POST_START_VOLUME_SETTLE_MS = 60;    // Post-clock-lock settle window before volume sync
constexpr int64_t FEEDBACK_WARMUP_SETTLE_MS = 150;     // Let ISO OUT silence settle before priming Feedback IN during warm-up
constexpr int PCM_MICRO_FADE_IN_MS = 15;               // 15 ms linear ramp-up suppresses clicks after seek/Hot-Standby resume
constexpr int PCM_MICRO_FADE_OUT_MS = 15;              // 15 ms linear ramp-down prevents pops when entering Hot-Standby pause
constexpr int DSD_COLD_WARMUP_FLOOR_MS = 500;          // Minimum warm-up floor for DSD cold start / PCM<->DSD switches when warmUp > 0
constexpr int64_t STALL_THRESHOLD_MS = 250;            // Loop wake gap signaling OS freeze/throttle
constexpr int64_t JITTER_THRESHOLD_MS = 16;            // Sub-stall wake jitter (>60% of 26 ms MediaTek ISO pipe)
constexpr int64_t STALL_LOG_COOLDOWN_MS = 2000;
constexpr int64_t UNDERRUN_LOG_COOLDOWN_MS = 2000;
constexpr int64_t PRODUCER_WORK_FRESHNESS_MS = 250;
constexpr int MAX_PACKETS_PER_URB_STACK = (USB_PACKETS_PER_URB_HS_MAX > USB_PACKETS_PER_URB_FS)
    ? USB_PACKETS_PER_URB_HS_MAX
    : USB_PACKETS_PER_URB_FS;
constexpr int MEDIATEK_MUSB_MAX_INFLIGHT_HS_PACKETS = 216; // Stay safely under 224 MUSB QMU TX descriptor limit
constexpr int64_t OUT_URB_RETRY_BACKOFF_MS = 4;          // Retry cadence for transiently failed ISO OUT URB slots
constexpr size_t MAX_NATIVE_LOGS = 200;

constexpr uint8_t USB_REQ_TYPE_CLASS_IFACE_OUT = 0x21u;
constexpr uint8_t USB_REQ_TYPE_CLASS_IFACE_IN = 0xA1u;
constexpr uint8_t USB_REQ_TYPE_CLASS_EP_OUT = 0x22u;

constexpr uint8_t UAC_REQ_SET_CUR = 0x01u;
constexpr uint8_t UAC2_REQ_GET_CUR = 0x01u;
constexpr uint8_t UAC2_REQ_RANGE = 0x02u;
constexpr uint8_t UAC1_REQ_GET_CUR = 0x81u;
constexpr uint8_t UAC1_REQ_GET_MIN = 0x82u;
constexpr uint8_t UAC1_REQ_GET_MAX = 0x83u;
constexpr uint8_t UAC1_REQ_GET_RES = 0x84u;

constexpr uint16_t CS_SAM_FREQ_CONTROL = 0x0100u;
constexpr uint16_t CX_CLOCK_SELECTOR_CONTROL = 0x0100u;
constexpr uint16_t FU_MUTE_CONTROL = 0x0100u;
constexpr uint16_t FU_VOLUME_CONTROL = 0x0200u;

constexpr uint8_t DSD_SILENCE_BYTE = 0x69u; // 50% balanced 1-bit pattern avoids DC thump
constexpr uint8_t DOP_MARKER_EVEN = 0x05u;
constexpr uint8_t DOP_MARKER_ODD = 0xFAu;

constexpr int FALLBACK_UAC2_RATES[] = {
    44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000
};
constexpr int STANDARD_UAC2_RATES[] = {
    44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
};

// ═══ Private Helpers ═══
uint32_t readLeU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

int16_t readLeI16(const uint8_t* p) {
    return static_cast<int16_t>(
        static_cast<uint16_t>(p[0]) |
        static_cast<uint16_t>(static_cast<uint16_t>(p[1]) << 8)
    );
}

int64_t monotonicNowMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + static_cast<int64_t>(ts.tv_nsec / 1000000L);
}

int64_t monotonicNowNs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

bool isMediaTekPlatform() {
    static const bool detected = []() -> bool {
        char prop[PROP_VALUE_MAX]{};
        const int len = __system_property_get("ro.board.platform", prop);
        if (len >= 2) {
            const char c0 = static_cast<char>(prop[0] | 0x20);
            const char c1 = static_cast<char>(prop[1] | 0x20);
            if (c0 == 'm' && c1 == 't') return true;
        }
        char hw[PROP_VALUE_MAX]{};
        const int hwLen = __system_property_get("ro.hardware", hw);
        if (hwLen >= 2) {
            const char c0 = static_cast<char>(hw[0] | 0x20);
            const char c1 = static_cast<char>(hw[1] | 0x20);
            if (c0 == 'm' && c1 == 't') return true;
        }
        return false;
    }();
    return detected;
}

struct BigCoreTopology {
    cpu_set_t bigMask{};
    cpu_set_t allMask{};
    uint64_t bigMaskBits = 0;
    int bigCoreCount = 0;
    int totalCoreCount = 0;
    bool valid = false;
};

const BigCoreTopology& detectBigCoreTopology() {
    static const BigCoreTopology topo = []() -> BigCoreTopology {
        BigCoreTopology t;
        CPU_ZERO(&t.bigMask);
        CPU_ZERO(&t.allMask);

        const long nConf = sysconf(_SC_NPROCESSORS_CONF);
        const int maxCpus = static_cast<int>(std::clamp<long>(nConf > 0 ? nConf : 8L, 1L, 64L));
        std::vector<long> scores(static_cast<size_t>(maxCpus), -1L);
        long minScore = std::numeric_limits<long>::max();
        long maxScore = -1L;

        auto readSysfsLong = [](const char* path) -> long {
            FILE* f = std::fopen(path, "r");
            if (!f) return -1L;
            long val = -1L;
            const int scanned = std::fscanf(f, "%ld", &val);
            std::fclose(f);
            return (scanned == 1 && val > 0L) ? val : -1L;
        };

        for (int cpu = 0; cpu < maxCpus; ++cpu) {
            CPU_SET(cpu, &t.allMask);
            t.totalCoreCount++;
            char path[128]{};
            std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
            long score = readSysfsLong(path);
            if (score <= 0L) {
                std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
                score = readSysfsLong(path);
            }
            scores[static_cast<size_t>(cpu)] = score;
            if (score > 0L) {
                if (score < minScore) minScore = score;
                if (score > maxScore) maxScore = score;
            }
        }

        // Select every cluster strictly faster than the LITTLE cluster (captures both Prime + Big
        // on 1+3+4 SoCs and Big cores on 2+6 SoCs such as MediaTek MT6833 2.4 GHz vs 2.0 GHz).
        if (maxScore > minScore && minScore > 0L && t.totalCoreCount >= 2) {
            for (int cpu = 0; cpu < maxCpus; ++cpu) {
                if (scores[static_cast<size_t>(cpu)] > minScore) {
                    CPU_SET(cpu, &t.bigMask);
                    if (cpu < 64) {
                        t.bigMaskBits |= (1ULL << static_cast<unsigned>(cpu));
                    }
                    t.bigCoreCount++;
                }
            }
            t.valid = (t.bigCoreCount >= 1 && t.bigCoreCount < t.totalCoreCount);
        }
        return t;
    }();
    return topo;
}

bool pinCurrentThreadToBigCores(uint64_t& outMaskBits, int& outCoreCount) {
    outMaskBits = 0;
    outCoreCount = 0;
    const BigCoreTopology& topo = detectBigCoreTopology();
    if (!topo.valid) return false;
    if (sched_setaffinity(0, sizeof(cpu_set_t), &topo.bigMask) == 0) {
        outMaskBits = topo.bigMaskBits;
        outCoreCount = topo.bigCoreCount;
        return true;
    }
    return false;
}

void restoreCurrentThreadAllCores() {
    const BigCoreTopology& topo = detectBigCoreTopology();
    if (topo.totalCoreCount > 0) {
        sched_setaffinity(0, sizeof(cpu_set_t), &topo.allMask);
    }
}

bool isDisconnectErrno(int err) {
    return err == ENODEV || err == ESHUTDOWN || err == ENOENT || err == EBADF;
}

bool isUrbPoolLimitErrno(int err) {
    return err == ENOMEM || err == ENOSPC || err == EINVAL || err == EFBIG;
}

/**
 * Applies a short linear micro fade-in or fade-out directly to Little-Endian MSB-aligned PCM wire
 * frames (2/3/4-byte subslots) across Hot-Standby pause/resume and seek discontinuities.
 * Strictly bypassed once `fadeRem` reaches 0 and never invoked for Native DSD or DoP wire modes.
 */
void applyPcmMicroFade(
    uint8_t* dst,
    size_t byteCount,
    int channels,
    int subslot,
    int fadeTotal,
    int& fadeRem,
    bool fadeOut
) {
    if (!dst || byteCount == 0 || channels <= 0 || subslot < 2 || fadeTotal <= 0 || fadeRem <= 0) {
        return;
    }
    const size_t frameBytes = static_cast<size_t>(channels) * static_cast<size_t>(subslot);
    const size_t frames = byteCount / frameBytes;
    for (size_t f = 0; f < frames && fadeRem > 0; ++f) {
        const float gain = fadeOut
            ? (static_cast<float>(fadeRem - 1) / static_cast<float>(fadeTotal))
            : (static_cast<float>(fadeTotal - fadeRem) / static_cast<float>(fadeTotal));
        uint8_t* framePtr = dst + f * frameBytes;
        for (int c = 0; c < channels; ++c) {
            uint8_t* p = framePtr + static_cast<size_t>(c) * static_cast<size_t>(subslot);
            if (subslot == 2) {
                const int16_t v = static_cast<int16_t>(
                    static_cast<uint16_t>(p[0]) | (static_cast<uint16_t>(p[1]) << 8)
                );
                const int32_t scaled = std::clamp<int32_t>(
                    static_cast<int32_t>(std::lrintf(static_cast<float>(v) * gain)),
                    -32768,
                    32767
                );
                p[0] = static_cast<uint8_t>(scaled & 0xFF);
                p[1] = static_cast<uint8_t>((scaled >> 8) & 0xFF);
            } else if (subslot == 3) {
                const int32_t v = static_cast<int32_t>(
                    (static_cast<uint32_t>(p[0]) << 8) |
                    (static_cast<uint32_t>(p[1]) << 16) |
                    (static_cast<uint32_t>(p[2]) << 24)
                ) >> 8;
                const int32_t scaled = std::clamp<int32_t>(
                    static_cast<int32_t>(std::lrintf(static_cast<float>(v) * gain)),
                    -8388608,
                    8388607
                );
                const uint32_t u = static_cast<uint32_t>(scaled);
                p[0] = static_cast<uint8_t>(u & 0xFFu);
                p[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                p[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
            } else if (subslot == 4) {
                const int32_t v = static_cast<int32_t>(readLeU32(p));
                const int64_t scaled = std::clamp<int64_t>(
                    static_cast<int64_t>(std::llround(static_cast<double>(v) * static_cast<double>(gain))),
                    -2147483648LL,
                    2147483647LL
                );
                const uint32_t u = static_cast<uint32_t>(static_cast<int32_t>(scaled));
                p[0] = static_cast<uint8_t>(u & 0xFFu);
                p[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                p[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
                p[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
            }
        }
        fadeRem--;
    }
}

uint8_t getCurRequestCode(uint16_t uacVersion) {
    return (uacVersion >= UAC_VERSION_2_0) ? UAC2_REQ_GET_CUR : UAC1_REQ_GET_CUR;
}

const UacFeatureUnitInfo* findActiveFeatureUnit(const UacDeviceDescriptorInfo& descInfo) {
    if (descInfo.featureUnitId < 0) return nullptr;
    for (const auto& fu : descInfo.featureUnits) {
        if (fu.unitId == descInfo.featureUnitId) {
            return &fu;
        }
    }
    return nullptr;
}

std::vector<int> collectFeatureUnitChannels(
    const UacDeviceDescriptorInfo& descInfo,
    const UacFeatureUnitInfo* fu,
    bool forVolume
) {
    std::vector<int> channels;
    if (fu != nullptr && !fu->channelControls.empty()) {
        const bool isUac2Plus = (descInfo.uacVersion >= UAC_VERSION_2_0);
        for (size_t i = 0; i < fu->channelControls.size(); ++i) {
            const uint32_t ctrl = fu->channelControls[i];
            const bool supported = isUac2Plus
                ? (forVolume ? (((ctrl >> 2) & 0x03u) == 0x03u) : ((ctrl & 0x03u) == 0x03u))
                : (forVolume ? ((ctrl & 0x02u) != 0u) : ((ctrl & 0x01u) != 0u));
            if (supported) {
                channels.push_back(static_cast<int>(i + 1u));
            }
        }
    }
    if (channels.empty()) {
        const int fallbackCount = std::clamp(descInfo.maxSupportedChannels, 1, 8);
        for (int ch = 1; ch <= fallbackCount; ++ch) {
            channels.push_back(ch);
        }
    }
    return channels;
}

bool writeFeatureUnitChannels(
    UsbTransport& transport,
    uint16_t controlSelector,
    uint16_t wIndex,
    const std::vector<int>& channels,
    uint8_t* payload,
    uint16_t payloadLen
) {
    if (channels.empty()) return false;
    bool allOk = true;
    for (size_t idx = 0; idx < channels.size(); ++idx) {
        if (idx > 0u) {
            // Pace consecutive per-channel Feature Unit transfers so USB-to-I2C bridge MCUs
            // finish committing CN=1 (Left) before CN=2 (Right) arrives.
            usleep(FEATURE_UNIT_CMD_DELAY_US);
        }
        const int ch = channels[idx];
        const uint16_t wValue = static_cast<uint16_t>(controlSelector | (ch & 0xFF));
        int errCh = 0;
        int ret = transport.controlTransfer(
            USB_REQ_TYPE_CLASS_IFACE_OUT, UAC_REQ_SET_CUR, wValue, wIndex, payload, payloadLen, 200, errCh
        );
        if (ret < 0 && !isDisconnectErrno(errCh)) {
            usleep(FEATURE_UNIT_CMD_DELAY_US);
            ret = transport.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_OUT, UAC_REQ_SET_CUR, wValue, wIndex, payload, payloadLen, 200, errCh
            );
        }
        if (ret < 0) {
            allOk = false;
            if (isDisconnectErrno(errCh)) break;
        }
    }
    return allOk;
}

const char* outputModeName(OutputTransportMode mode) {
    switch (mode) {
        case OutputTransportMode::INACTIVE: return "INACTIVE";
        case OutputTransportMode::PCM_BIT_PERFECT: return "PCM_BIT_PERFECT";
        case OutputTransportMode::PCM_DSP: return "PCM_DSP";
        case OutputTransportMode::DOP: return "DOP";
        case OutputTransportMode::NATIVE_DSD: return "NATIVE_DSD";
        case OutputTransportMode::DSD_TO_PCM: return "DSD_TO_PCM";
    }
    return "UNKNOWN";
}

const char* clockSyncModeName(ClockSyncMode mode) {
    switch (mode) {
        case ClockSyncMode::NONE: return "NONE";
        case ClockSyncMode::ASYNC_FEEDBACK: return "ASYNC_FEEDBACK";
        case ClockSyncMode::ADAPTIVE: return "ADAPTIVE";
        case ClockSyncMode::SYNCHRONOUS: return "SYNCHRONOUS";
        case ClockSyncMode::WATCHDOG_FALLBACK: return "WATCHDOG_FALLBACK";
    }
    return "UNKNOWN";
}

bool isDsdWireMode(OutputTransportMode mode) {
    return mode == OutputTransportMode::NATIVE_DSD || mode == OutputTransportMode::DOP;
}

/**
 * Writes format-accurate silence directly into an ISO OUT packet payload without allocating.
 * Native DSD uses `0x69` (`0x69696969` per 32-bit slot) to maintain zero DC offset; DoP preserves
 * valid alternating `0x056969`/`0xFA6969` frames so the DAC never drops out of DoP lock during
 * warm-up, pause, seek, underrun, or prebuffering.
 */
void fillFormatSilence(
    uint8_t* dst,
    size_t byteCount,
    OutputTransportMode mode,
    int channels,
    int subslot,
    uint8_t& dopMarkerToggle
) {
    if (!dst || byteCount == 0) return;

    if (mode == OutputTransportMode::NATIVE_DSD) {
        std::memset(dst, DSD_SILENCE_BYTE, byteCount);
        return;
    }

    if (mode == OutputTransportMode::DOP && channels > 0 && subslot >= 3) {
        const size_t frameBytes = static_cast<size_t>(channels) * static_cast<size_t>(subslot);
        const size_t frames = byteCount / frameBytes;
        uint8_t* p = dst;
        for (size_t f = 0; f < frames; ++f) {
            const uint8_t marker = dopMarkerToggle;
            dopMarkerToggle = (marker == DOP_MARKER_EVEN) ? DOP_MARKER_ODD : DOP_MARKER_EVEN;
            for (int c = 0; c < channels; ++c) {
                if (subslot == 4) {
                    p[0] = 0x00u;
                    p[1] = DSD_SILENCE_BYTE;
                    p[2] = DSD_SILENCE_BYTE;
                    p[3] = marker;
                } else {
                    p[0] = DSD_SILENCE_BYTE;
                    p[1] = DSD_SILENCE_BYTE;
                    p[2] = marker;
                }
                p += subslot;
            }
        }
        const size_t written = frames * frameBytes;
        if (written < byteCount) {
            std::memset(dst + written, DSD_SILENCE_BYTE, byteCount - written);
        }
        return;
    }

    std::memset(dst, 0, byteCount);
}

} // namespace

// ═══ Device Lifecycle ═══
UsbAudioDriver::~UsbAudioDriver() {
    detachDevice();
}

bool UsbAudioDriver::attachDevice(
    int fd,
    const std::string& devicePath,
    const uint8_t* rawDescriptors,
    size_t descLen
) {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    detachDeviceLocked();

    if (fd < 0 || !rawDescriptors || descLen == 0) {
        DRV_LOGE("DAC_ATTACH_REJECT | fd=%d descLen=%zu", fd, descLen);
        return false;
    }

    if (!transport_.attach(fd, devicePath, 0)) {
        DRV_LOGE("DAC_ATTACH_ERR | fd=%d path=%s", fd, devicePath.c_str());
        descInfo_ = UacDeviceDescriptorInfo{};
        return false;
    }

    // Query the kernel's active USB configuration BEFORE parsing raw descriptors so multi-config
    // UAC1/UAC2 DACs parse the exact active bConfigurationValue instead of blindly taking Config #1.
    int activeCfg = -1;
    int cfgErr = 0;
    const bool gotActiveCfg = transport_.getActiveConfiguration(activeCfg, cfgErr);

    descInfo_ = parseUacDescriptors(rawDescriptors, descLen, gotActiveCfg ? activeCfg : -1);
    if (descInfo_.streamAltSettings.empty()) {
        DRV_LOGW("DAC_NO_STREAM_ALTS | descLen=%zu activeCfg=%d", descLen, activeCfg);
        transport_.detach();
        descInfo_ = UacDeviceDescriptorInfo{};
        return false;
    }

    transport_.applyUacSpeedFallbackHint(descInfo_.uacVersion);
    descInfo_.isHighSpeed = transport_.isHighSpeed();

    if (gotActiveCfg) {
        const bool matchedActive = (activeCfg > 0 && static_cast<int>(descInfo_.activeConfigValue) == activeCfg);
        DRV_LOGD("USB_CFG | active=%d parsed=%u src=%s",
                 activeCfg,
                 static_cast<unsigned int>(descInfo_.activeConfigValue),
                 matchedActive ? "active" : "fallback_first");
    }

    if (descInfo_.controlInterface >= 0) {
        int claimErr = 0;
        if (transport_.claimInterface(descInfo_.controlInterface, claimErr)) {
            DRV_LOGD("AC_IFACE_CLAIM | iface=%d", descInfo_.controlInterface);
        } else {
            DRV_LOGW("AC_IFACE_CLAIM_FAIL | iface=%d errno=%d err=%s",
                     descInfo_.controlInterface, claimErr, std::strerror(claimErr));
        }
    }

    if (descInfo_.uacVersion >= UAC_VERSION_2_0) {
        queryAllUac2ClockSampleRates();
    } else {
        finalizeUacCapabilities(descInfo_);
    }

    queryVolumeRange();

    if (!ringBuffer_.allocate(SPSC_DEFAULT_CAPACITY_BYTES)) {
        DRV_LOGE("RING_ALLOC_ERR | bytes=%zu", SPSC_DEFAULT_CAPACITY_BYTES);
        detachDeviceLocked();
        return false;
    }

    // Pre-initialize CPU big-core topology cache off the RT thread so UsbDacIsoThread
    // never performs sysfs file I/O or heap allocation on cold start.
    (void)detectBigCoreTopology();
    deviceDisconnected_.store(false, std::memory_order_release);

    DRV_LOGI("DAC_ATTACH | path=%s speed=%s uac=0x%04x%s ctrlIf=%d clkId=%d clkSel=%d fuId=%d hwVol=%d masterCh=%d alts=%zu rates=%zu maxCh=%d nativeDsd=%d dop=%d maxDsd=%d",
             devicePath.empty() ? "unknown" : devicePath.c_str(),
             transport_.isHighSpeed() ? "HS" : "FS",
             descInfo_.uacVersion,
             descInfo_.isUac3Badd ? "(BADD)" : "",
             descInfo_.controlInterface,
             descInfo_.clockSourceId,
             descInfo_.clockSelectorId,
             descInfo_.featureUnitId,
             hasHardwareVolume_.load(std::memory_order_relaxed) ? 1 : 0,
             volumeMasterChannel_ ? 1 : 0,
             descInfo_.streamAltSettings.size(),
             descInfo_.supportedSampleRates.size(),
             descInfo_.maxSupportedChannels,
             descInfo_.supportsNativeDsd ? 1 : 0,
             descInfo_.supportsDop ? 1 : 0,
             descInfo_.maxDsdRate);

    return true;
}

void UsbAudioDriver::detachDevice() {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    detachDeviceLocked();
}

void UsbAudioDriver::detachDeviceLocked() {
    const bool wasAttached = transport_.isAttached();
    const std::string detachedPath = wasAttached ? transport_.devicePath() : std::string();
    const bool wasDsdActive =
        streamOpen_.load(std::memory_order_acquire) && isDsdWireMode(activePlan_.outputMode);
    const bool shouldUnmuteForOsHandback =
        (wasDsdActive || hardwareMutedForTransition_) &&
        !explicitMuted_ &&
        lastNormalizedVolume_ > 0.001f &&
        wasAttached &&
        !deviceDisconnected_.load(std::memory_order_acquire);

    closeStreamLocked(false);

    if (shouldUnmuteForOsHandback && transport_.isAttached()) {
        // AudioStreaming interface is now safely at alt=0 (silent); clear DSD teardown mute
        // before handing the USB DAC back to Android Audio HAL / snd-usb-audio.
        if (setHardwareMuteDirectLocked(false)) {
            usleep(FEATURE_UNIT_CMD_DELAY_US);
        }
    }

    transport_.detach();
    ringBuffer_.release();
    rtTelemetry_.resetForNewStream();
    if (wasAttached) {
        DRV_LOGI("DAC_DETACH | path=%s", detachedPath.empty() ? "unknown" : detachedPath.c_str());
    }

    hasHardwareVolume_.store(false, std::memory_order_release);
    dspPipeline_.setHardwareVolumeActive(false);
    dspPipeline_.setSoftwareGainLinear(1.0);

    volumeMasterChannel_ = true;
    volumeMinDb256_ = -15360;
    volumeMaxDb256_ = 0;
    volumeResDb256_ = 256;
    lastMutedState_ = -1;
    lastVolumeDb256_ = INT32_MIN;
    explicitMuted_ = false;
    deviceDisconnected_.store(false, std::memory_order_release);
    softwareMuted_.store(false, std::memory_order_release);
    hardwareMutedForTransition_ = false;
    pendingPostStartVolumeSync_ = false;
    streamStartElapsedMs_ = 0;
    pausedAtElapsedMs_ = 0;
    fadeInFramesTotal_.store(0, std::memory_order_release);
    fadeInFramesRemaining_.store(0, std::memory_order_release);
    mtkQmuLimited_.store(false, std::memory_order_release);
    activePoolConfig_ = UsbIsoPoolConfig{};

    hasLastStreamRequest_ = false;
    lastStreamRequest_ = StreamNegotiationRequest{};
    lastStreamWarmUpMs_ = 0;
    lastOpenedSampleRate_ = 0;
    lastOpenedBitDepth_ = 0;
    lastOpenedOutputMode_ = OutputTransportMode::INACTIVE;
    activePlan_ = NegotiatedStreamPlan{};
    descInfo_ = UacDeviceDescriptorInfo{};

    // Reset all per-session RT telemetry & hardware counters so diagnostics after detach match Kotlin state
    clockSync_.clear();
    framesWritten_.store(0, std::memory_order_release);
    maxBufferedBytes_.store(0, std::memory_order_release);
    underrunCount_.store(0, std::memory_order_release);
    submitErrCount_.store(0, std::memory_order_release);
    lastSubmitErrno_.store(0, std::memory_order_release);
    stallCount_.store(0, std::memory_order_release);
    maxStallMs_.store(0, std::memory_order_release);
    jitterCount_.store(0, std::memory_order_release);
    maxUrbGapMs_.store(0, std::memory_order_release);
    pipelineLatencyUs_.store(0, std::memory_order_release);
    appliedNice_.store(0, std::memory_order_release);
    isoBigCoreMask_.store(0, std::memory_order_release);
    isoBigCoreCount_.store(0, std::memory_order_release);
    lastWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkTimeMs_.store(0, std::memory_order_release);
    warmUpMsPending_.store(0, std::memory_order_release);
    warmUpPacketsRemaining_.store(0, std::memory_order_release);
    paused_.store(true, std::memory_order_release);
    endOfStream_.store(false, std::memory_order_release);
    prebuffering_.store(true, std::memory_order_release);
}

UacDeviceDescriptorInfo UsbAudioDriver::getDescriptorInfo() const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    return descInfo_;
}

// ═══ UAC Hardware Queries & Clock Selection ═══
std::vector<int> UsbAudioDriver::queryUac2SampleRatesForClock(int clockId, bool isProgrammable) {
    std::vector<int> rates;
    if (clockId < 0 || descInfo_.controlInterface < 0) return rates;

    uint8_t buf[512]{};
    const uint16_t wIndex = static_cast<uint16_t>(
        (clockId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    int ctrlErr = 0;
    const int ret = transport_.controlTransfer(
        USB_REQ_TYPE_CLASS_IFACE_IN,
        UAC2_REQ_RANGE,
        CS_SAM_FREQ_CONTROL,
        wIndex,
        buf,
        static_cast<uint16_t>(sizeof(buf)),
        1000,
        ctrlErr
    );
    if (ret >= 2) {
        const uint16_t numRanges = static_cast<uint16_t>(buf[0]) | (static_cast<uint16_t>(buf[1]) << 8);
        for (uint16_t i = 0; i < numRanges && (2 + static_cast<int>(i + 1) * 12) <= ret; ++i) {
            const uint8_t* entry = buf + 2 + i * 12;
            const uint32_t dMin = readLeU32(entry);
            const uint32_t dMax = readLeU32(entry + 4);
            const uint32_t dRes = readLeU32(entry + 8);
            if (dMin == dMax || dRes == 0u) {
                if (dMin > 0u) rates.push_back(static_cast<int>(dMin));
            } else {
                for (int sr : STANDARD_UAC2_RATES) {
                    if (sr >= static_cast<int>(dMin) &&
                        sr <= static_cast<int>(dMax) &&
                        ((static_cast<uint32_t>(sr) - dMin) % dRes == 0u)) {
                        rates.push_back(sr);
                    }
                }
            }
        }
    } else if (isProgrammable) {
        DRV_LOGW("CLOCK_RANGE_ERR | clkId=%d ret=%d errno=%d", clockId, ret, ctrlErr);
    }

    // Read-only / fixed-crystal clocks often omit RANGE and only respond to GET_CUR(SAM_FREQ)
    if (rates.empty()) {
        uint8_t curBuf[4]{};
        int curErr = 0;
        const int curRet = transport_.controlTransfer(
            USB_REQ_TYPE_CLASS_IFACE_IN,
            UAC2_REQ_GET_CUR,
            CS_SAM_FREQ_CONTROL,
            wIndex,
            curBuf,
            4,
            500,
            curErr
        );
        if (curRet == 4) {
            const uint32_t curRate = readLeU32(curBuf);
            if (curRate >= 8000u && curRate <= 1536000u) {
                rates.push_back(static_cast<int>(curRate));
            }
        }
    }

    std::sort(rates.begin(), rates.end());
    rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
    return rates;
}

void UsbAudioDriver::queryAllUac2ClockSampleRates() {
    std::vector<int> mergedPlaybackRates;
    for (auto& cs : descInfo_.clockSources) {
        cs.supportedSampleRates = queryUac2SampleRatesForClock(cs.clockId, cs.isProgrammable);
        const bool isPlaybackClock = descInfo_.playbackClockSourceIds.empty() ||
            (std::find(descInfo_.playbackClockSourceIds.begin(),
                       descInfo_.playbackClockSourceIds.end(),
                       cs.clockId) != descInfo_.playbackClockSourceIds.end());
        if (isPlaybackClock) {
            for (int r : cs.supportedSampleRates) {
                mergedPlaybackRates.push_back(r);
            }
        }
        if (!cs.supportedSampleRates.empty()) {
            DRV_LOGD("CLOCK_SRC_CAPS | clkId=%d prog=%d count=%zu minHz=%d maxHz=%d",
                     cs.clockId, cs.isProgrammable ? 1 : 0,
                     cs.supportedSampleRates.size(),
                     cs.supportedSampleRates.front(),
                     cs.supportedSampleRates.back());
        }
    }

    if (!mergedPlaybackRates.empty()) {
        descInfo_.supportedSampleRates = std::move(mergedPlaybackRates);
    } else if (descInfo_.supportedSampleRates.empty()) {
        descInfo_.supportedSampleRates.assign(std::begin(FALLBACK_UAC2_RATES), std::end(FALLBACK_UAC2_RATES));
    }
    finalizeUacCapabilities(descInfo_);
}

bool UsbAudioDriver::selectClockSelectorPin(int selectorId, int targetClockId) {
    if (descInfo_.uacVersion < UAC_VERSION_2_0 ||
        selectorId < 0 ||
        targetClockId < 0 ||
        descInfo_.controlInterface < 0) {
        return true;
    }

    uint8_t pinIndex1Based = 0;
    for (const auto& sel : descInfo_.clockSelectors) {
        if (sel.selectorId != selectorId) continue;
        for (size_t i = 0; i < sel.inputPinIds.size(); ++i) {
            int pinEntity = sel.inputPinIds[i];
            for (int depth = 0; depth < 4; ++depth) {
                bool unwrapped = false;
                for (const auto& mult : descInfo_.clockMultipliers) {
                    if (mult.multiplierId == pinEntity && mult.sourceClockId != pinEntity) {
                        pinEntity = mult.sourceClockId;
                        unwrapped = true;
                        break;
                    }
                }
                if (!unwrapped) break;
            }
            if (pinEntity == targetClockId) {
                pinIndex1Based = static_cast<uint8_t>(i + 1u);
                break;
            }
        }
        break;
    }
    if (pinIndex1Based == 0) return true;

    const uint16_t wIndex = static_cast<uint16_t>(
        (selectorId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    int ctrlErr = 0;
    const int ret = transport_.controlTransfer(
        USB_REQ_TYPE_CLASS_IFACE_OUT,
        UAC_REQ_SET_CUR,
        CX_CLOCK_SELECTOR_CONTROL,
        wIndex,
        &pinIndex1Based,
        1,
        500,
        ctrlErr
    );
    if (ret < 0) {
        DRV_LOGW("CLOCK_SEL_ERR | selId=%d pin=%u clkId=%d errno=%d",
                 selectorId, static_cast<unsigned int>(pinIndex1Based), targetClockId, ctrlErr);
        return false;
    }
    return true;
}

bool UsbAudioDriver::setSampleRateUac2(int clockId, int sampleRate) {
    if (clockId < 0 || descInfo_.controlInterface < 0) return false;

    uint8_t rateBytes[4] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFF)
    };
    const uint16_t wIndex = static_cast<uint16_t>(
        (clockId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    int ctrlErr = 0;
    const int ret = transport_.controlTransfer(
        USB_REQ_TYPE_CLASS_IFACE_OUT,
        UAC_REQ_SET_CUR,
        CS_SAM_FREQ_CONTROL,
        wIndex,
        rateBytes,
        4,
        1000,
        ctrlErr
    );
    if (ret < 0) {
        DRV_LOGW("CLOCK_RATE_ERR | uac=2 clkId=%d rate=%d errno=%d",
                 clockId, sampleRate, ctrlErr);
        return false;
    }
    return true;
}

bool UsbAudioDriver::configureClockForStream(const NegotiatedStreamPlan& plan) {
    if (descInfo_.controlInterface < 0) return false;

    auto unwrapMultiplier = [&](int entityId) -> int {
        int cur = entityId;
        for (int depth = 0; depth < 4; ++depth) {
            bool unwrapped = false;
            for (const auto& mult : descInfo_.clockMultipliers) {
                if (mult.multiplierId == cur && mult.sourceClockId != cur) {
                    cur = mult.sourceClockId;
                    unwrapped = true;
                    break;
                }
            }
            if (!unwrapped) break;
        }
        return cur;
    };

    auto findClockSource = [&](int clkId) -> const UacClockSourceInfo* {
        for (const auto& cs : descInfo_.clockSources) {
            if (cs.clockId == clkId) return &cs;
        }
        return nullptr;
    };

    auto findClockSelector = [&](int selId) -> const UacClockSelectorInfo* {
        for (const auto& sel : descInfo_.clockSelectors) {
            if (sel.selectorId == selId) return &sel;
        }
        return nullptr;
    };

    int rootEntity = (plan.selectedAlt.clockEntityId >= 0)
        ? plan.selectedAlt.clockEntityId
        : ((descInfo_.clockSelectorId >= 0) ? descInfo_.clockSelectorId : descInfo_.clockSourceId);
    rootEntity = unwrapMultiplier(rootEntity);

    int chosenSelectorId = -1;
    int chosenClockId = -1;
    const UacClockSourceInfo* chosenCs = nullptr;

    const UacClockSelectorInfo* activeSel = findClockSelector(rootEntity);
    if (activeSel == nullptr && descInfo_.clockSelectorId >= 0 && findClockSource(rootEntity) == nullptr) {
        activeSel = findClockSelector(descInfo_.clockSelectorId);
    }

    if (activeSel != nullptr) {
        chosenSelectorId = activeSel->selectorId;
        const UacClockSourceInfo* exactProgMatch = nullptr;
        const UacClockSourceInfo* exactFixedMatch = nullptr;
        const UacClockSourceInfo* firstProg = nullptr;
        const UacClockSourceInfo* firstAny = nullptr;

        for (int rawPinId : activeSel->inputPinIds) {
            const int pinClkId = unwrapMultiplier(rawPinId);
            const UacClockSourceInfo* cs = findClockSource(pinClkId);
            if (!cs) continue;
            if (!firstAny) firstAny = cs;
            if (cs->isProgrammable && !firstProg) firstProg = cs;

            const bool supportsRate = std::find(
                cs->supportedSampleRates.begin(),
                cs->supportedSampleRates.end(),
                plan.wireSampleRate
            ) != cs->supportedSampleRates.end();
            if (supportsRate) {
                if (!cs->isProgrammable && !exactFixedMatch) {
                    exactFixedMatch = cs;
                } else if (cs->isProgrammable && !exactProgMatch) {
                    exactProgMatch = cs;
                }
            }
        }

        // Prefer exact-rate Clock Source (fixed dedicated crystal first, then programmable clock)
        chosenCs = exactFixedMatch ? exactFixedMatch
            : (exactProgMatch ? exactProgMatch : (firstProg ? firstProg : firstAny));
        if (chosenCs != nullptr) {
            chosenClockId = chosenCs->clockId;
        }
    } else {
        chosenCs = findClockSource(rootEntity);
        if (!chosenCs && descInfo_.clockSourceId >= 0) {
            chosenCs = findClockSource(descInfo_.clockSourceId);
        }
        if (chosenCs != nullptr) {
            chosenClockId = chosenCs->clockId;
        }
    }

    if (chosenClockId < 0) return false;

    if (chosenSelectorId >= 0) {
        selectClockSelectorPin(chosenSelectorId, chosenClockId);
    }
    descInfo_.clockSourceId = chosenClockId;

    // Skip CS_SAM_FREQ_CONTROL SET_CUR on read-only fixed-frequency crystals to avoid control STALL
    if (chosenCs != nullptr && !chosenCs->isProgrammable) {
        DRV_LOGD("CLOCK_FIXED_SKIP | clkId=%d selId=%d rate=%d",
                 chosenClockId, chosenSelectorId, plan.wireSampleRate);
        return true;
    }

    return setSampleRateUac2(chosenClockId, plan.wireSampleRate);
}

bool UsbAudioDriver::setSampleRateUac1(uint8_t outEndpoint, int sampleRate) {
    uint8_t rateBytes[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };
    int ctrlErr = 0;
    const int ret = transport_.controlTransfer(
        USB_REQ_TYPE_CLASS_EP_OUT,
        UAC_REQ_SET_CUR,
        CS_SAM_FREQ_CONTROL,
        outEndpoint,
        rateBytes,
        3,
        1000,
        ctrlErr
    );
    if (ret < 0) {
        DRV_LOGW("CLOCK_RATE_ERR | uac=1 ep=0x%02x rate=%d errno=%d",
                 static_cast<unsigned int>(outEndpoint), sampleRate, ctrlErr);
        return false;
    }
    return true;
}

// ═══ Stream Lifecycle & Configuration ═══
bool UsbAudioDriver::openStream(
    int sampleRate,
    int channels,
    int preferredBitDepth,
    AudioInputEncoding inputEncoding,
    int warmUpMs
) {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    return openStreamLocked(sampleRate, channels, preferredBitDepth, inputEncoding, warmUpMs);
}

bool UsbAudioDriver::openStreamLocked(
    int sampleRate,
    int channels,
    int preferredBitDepth,
    AudioInputEncoding inputEncoding,
    int warmUpMs
) {
    if (!transport_.isAttached() || descInfo_.streamAltSettings.empty()) {
        DRV_LOGE("STREAM_OPEN_REJECT | reason=not_attached");
        return false;
    }

    StreamNegotiationRequest req;
    req.inputSampleRate = sampleRate;
    req.inputChannels = channels;
    req.preferredBitDepth = preferredBitDepth;
    req.inputEncoding = inputEncoding;
    req.options = dspOptions_;

    lastStreamRequest_ = req;
    lastStreamWarmUpMs_ = warmUpMs;
    hasLastStreamRequest_ = true;

    // On DACs without Hardware Volume, route DSD through DSD_TO_PCM whenever software attenuation
    // (< 100%, including the 30% first-time safe cap) is active so 1-bit DSD never blasts at 0 dBFS.
    const bool hwVolAvailable = hasHardwareVolume_.load(std::memory_order_acquire);
    const bool needsDsdSwVolSafety = AudioDspPipeline::isDsdEncoding(inputEncoding) &&
                                     !hwVolAvailable &&
                                     !explicitMuted_ &&
                                     lastNormalizedVolume_ < 0.995f;
    if (needsDsdSwVolSafety && req.options.dsdMode != DsdPlaybackMode::PCM_DECIMATE) {
        req.options.dsdMode = DsdPlaybackMode::PCM_DECIMATE;
        DRV_LOGI("DSD_SW_VOL_GUARD | mode=DSD_TO_PCM volPct=%.0f",
                 lastNormalizedVolume_ * 100.0f);
    }

    const NegotiatedStreamPlan plan = AudioDspPipeline::negotiate(descInfo_, req);
    if (!plan.valid) {
        DRV_LOGE("STREAM_NEGOTIATE_ERR | rate=%d ch=%d enc=%d prefBits=%d",
                 sampleRate, channels, static_cast<int>(inputEncoding), preferredBitDepth);
        return false;
    }

    // Reuse active hardware interface & clock when wire parameters match (avoids relay pop on track transitions)
    if (streamOpen_.load(std::memory_order_acquire) &&
        ringBuffer_.isAllocated() &&
        activePlan_.wireSampleRate == plan.wireSampleRate &&
        activePlan_.wireChannels == plan.wireChannels &&
        activePlan_.wireSubslotSize == plan.wireSubslotSize &&
        activePlan_.outputMode == plan.outputMode &&
        activePlan_.selectedAlt.interfaceNumber == plan.selectedAlt.interfaceNumber &&
        activePlan_.selectedAlt.alternateSetting == plan.selectedAlt.alternateSetting) {
        if (!dspPipeline_.configure(req, plan)) {
            DRV_LOGE("DSP_RECONFIG_ERR | gen=%lld mode=%s",
                     static_cast<long long>(streamGeneration_.load(std::memory_order_relaxed)),
                     outputModeName(plan.outputMode));
            return false;
        }
        activePlan_ = plan;
        activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_release);
        endOfStream_.store(false, std::memory_order_release);
        DRV_LOGI("STREAM_REUSE | gen=%lld mode=%s wire=%dHz/%db/%dch ep=0x%02x alt=%d tailB=%d paused=%d dsp=0x%x",
                 static_cast<long long>(streamGeneration_.load(std::memory_order_relaxed)),
                 outputModeName(plan.outputMode),
                 plan.wireSampleRate,
                 plan.wireBitDepth,
                 plan.wireChannels,
                 static_cast<unsigned int>(plan.selectedAlt.outEndpoint),
                 plan.selectedAlt.alternateSetting,
                 getRingBufferAvailableBytes(),
                 paused_.load(std::memory_order_relaxed) ? 1 : 0,
                 activeDspFlags_.load(std::memory_order_relaxed));
        return true;
    }

    const bool wasStreamOpen = streamOpen_.load(std::memory_order_acquire);
    const bool wasRunning = running_.load(std::memory_order_acquire);

    // Drain tail samples cleanly before switching clock/alt-setting during gapless format changes
    if (wasStreamOpen && wasRunning && !paused_.load(std::memory_order_acquire)) {
        endOfStream_.store(true, std::memory_order_release);
        for (int i = 0;
             i < DRAIN_RETRY_COUNT &&
             running_.load(std::memory_order_acquire) &&
             !deviceDisconnected_.load(std::memory_order_acquire) &&
             ringBuffer_.availableBytes() > 0;
             ++i) {
            usleep(DRAIN_STEP_US);
        }
    }

    const bool enteringOrLeavingDsd = isDsdWireMode(plan.outputMode) || isDsdWireMode(activePlan_.outputMode);
    const bool switchingPcmFormat = wasStreamOpen &&
        ((activePlan_.wireSampleRate != plan.wireSampleRate) ||
         (activePlan_.selectedAlt.alternateSetting != plan.selectedAlt.alternateSetting) ||
         (activePlan_.selectedAlt.interfaceNumber != plan.selectedAlt.interfaceNumber));
    if (enteringOrLeavingDsd || switchingPcmFormat) {
        // Mute hardware Feature Unit (when supported) before switching alt-setting or clock rate
        // across both PCM and DSD transitions to suppress DAC relay and modulator pops.
        if (setHardwareMuteDirectLocked(true)) {
            hardwareMutedForTransition_ = true;
            usleep(FEATURE_UNIT_CMD_DELAY_US);
        }
    }

    closeStreamLocked(true);

    auto restoreMuteOnOpenFailure = [&]() {
        if (hardwareMutedForTransition_) {
            hardwareMutedForTransition_ = false;
            if (!explicitMuted_ && lastNormalizedVolume_ > 0.001f) {
                setHardwareMuteDirectLocked(false);
            }
        }
    };

    int usbErr = 0;
    if (!transport_.claimInterface(plan.selectedAlt.interfaceNumber, usbErr)) {
        DRV_LOGE("AS_IFACE_CLAIM_ERR | iface=%d errno=%d err=%s",
                 plan.selectedAlt.interfaceNumber, usbErr, std::strerror(usbErr));
        restoreMuteOnOpenFailure();
        return false;
    }
    if (!transport_.setInterfaceAltSetting(plan.selectedAlt.interfaceNumber, plan.selectedAlt.alternateSetting, usbErr)) {
        DRV_LOGE("AS_ALT_SET_ERR | iface=%d alt=%d errno=%d err=%s",
                 plan.selectedAlt.interfaceNumber, plan.selectedAlt.alternateSetting, usbErr, std::strerror(usbErr));
        restoreMuteOnOpenFailure();
        return false;
    }

    bool rateOk = false;
    if (descInfo_.uacVersion >= UAC_VERSION_2_0) {
        rateOk = configureClockForStream(plan);
    } else if (plan.selectedAlt.hasSamFreqControl) {
        rateOk = setSampleRateUac1(plan.selectedAlt.outEndpoint, plan.wireSampleRate);
    } else {
        DRV_LOGD("UAC1_NO_SAM_FREQ | ep=0x%02x rate=%d",
                 static_cast<unsigned int>(plan.selectedAlt.outEndpoint), plan.wireSampleRate);
        rateOk = true;
    }
    if (!rateOk) {
        DRV_LOGW("CLOCK_SET_FALLBACK | rate=%d reason=set_cur_failed", plan.wireSampleRate);
    }

    if (!dspPipeline_.configure(req, plan)) {
        DRV_LOGE("DSP_CONFIG_ERR | mode=%s wireRate=%d ch=%d",
                 outputModeName(plan.outputMode), plan.wireSampleRate, plan.wireChannels);
        int resetErr = 0;
        transport_.setInterfaceAltSetting(plan.selectedAlt.interfaceNumber, 0, resetErr);
        restoreMuteOnOpenFailure();
        return false;
    }
    dspPipeline_.setHardwareVolumeActive(hwVolAvailable);

    const bool fbSizeOk =
        (plan.selectedAlt.feedbackUsageType == 1u)
            ? (plan.selectedAlt.feedbackMaxPacketSize <= USB_MAX_EXPLICIT_FEEDBACK_BYTES)
            : (plan.selectedAlt.feedbackMaxPacketSize <= USB_MAX_FEEDBACK_BYTES);
    const bool hasExplicitFeedback =
        (plan.selectedAlt.syncType == UacSyncType::ASYNCHRONOUS) &&
        (plan.selectedAlt.feedbackEndpoint != 0) &&
        (plan.selectedAlt.feedbackUsageType != 2u) &&
        fbSizeOk;

    // Size continuous Feedback IN URB pool (4..8 parallel URBs) with batched packets on fast
    // HS bInterval endpoints so kernel RX queue never drains while keeping IRQ rate modest.
    const int numFbUrbs = transport_.isHighSpeed() ? USB_DEFAULT_FEEDBACK_URBS : USB_MIN_FEEDBACK_URBS;
    int packetsPerFbUrb = 1;
    if (hasExplicitFeedback && transport_.isHighSpeed()) {
        const uint8_t fbIntv = plan.selectedAlt.feedbackInterval;
        if (fbIntv <= 2u) {
            packetsPerFbUrb = 8; // 1..2 ms per URB
        } else if (fbIntv <= 4u) {
            packetsPerFbUrb = 4; // 2..4 ms per URB -> 6 URBs = 12..24 ms continuous window
        } else if (fbIntv == 5u) {
            packetsPerFbUrb = 2; // 4 ms per URB -> 6 URBs = 24 ms continuous window
        } else {
            packetsPerFbUrb = 1;
        }
    }

    // Flush any stale Async Feedback IN URBs before reconfiguring clock/pool
    transport_.discardAllFeedbackUrbs();
    feedbackResetRequested_.store(false, std::memory_order_release);
    accumulatorResetRequested_.store(false, std::memory_order_release);
    clockSync_.reset();

    ClockSyncConfig syncCfg;
    syncCfg.sampleRate = plan.wireSampleRate;
    syncCfg.isHighSpeed = transport_.isHighSpeed();
    syncCfg.outInterval = plan.selectedAlt.interval;
    syncCfg.syncType = static_cast<uint8_t>(plan.selectedAlt.syncType);
    syncCfg.hasExplicitFeedback = hasExplicitFeedback;
    syncCfg.feedbackInterval = plan.selectedAlt.feedbackInterval;
    syncCfg.feedbackRefresh = plan.selectedAlt.feedbackRefresh;
    syncCfg.packetsPerFeedbackUrb = packetsPerFbUrb;
    syncCfg.feedbackMaxPacketSize = plan.selectedAlt.feedbackMaxPacketSize;
    syncCfg.uacVersion = descInfo_.uacVersion;
    clockSync_.configure(syncCfg);

    const size_t frameBytes = static_cast<size_t>(plan.wireChannels) * static_cast<size_t>(plan.wireSubslotSize);

    // Dynamically size SPSC ring buffer with adaptive headroom for both Non-Hi-Res (2.5 s) and
    // Hi-Res PCM / DSD (3.0 s, 4 MB .. 32 MB power-of-2) to eliminate screen-off underruns.
    const bool isHiResOrDsd =
        isDsdWireMode(plan.outputMode) ||
        plan.outputMode == OutputTransportMode::DSD_TO_PCM ||
        plan.wireSampleRate > 48000 ||
        plan.wireBitDepth >= 24;
    const int targetBufferMs = isHiResOrDsd ? MAX_BUFFER_MS_HIRES : MAX_BUFFER_MS_STANDARD;
    const size_t targetBufferedBytes = static_cast<size_t>(
        (static_cast<int64_t>(plan.wireSampleRate) * targetBufferMs / 1000LL) * static_cast<int64_t>(frameBytes)
    );
    const size_t desiredRingCap = computeRingBufferCapacityBytes(targetBufferedBytes + frameBytes * 16u);
    if (!ringBuffer_.isAllocated() || ringBuffer_.capacity() != desiredRingCap) {
        if (!ringBuffer_.allocate(desiredRingCap)) {
            DRV_LOGW("RING_ALLOC_FALLBACK | reqB=%zu fallbackB=%zu",
                     desiredRingCap, SPSC_DEFAULT_CAPACITY_BYTES);
            if (!ringBuffer_.isAllocated() && !ringBuffer_.allocate(SPSC_DEFAULT_CAPACITY_BYTES)) {
                int resetErr = 0;
                transport_.setInterfaceAltSetting(plan.selectedAlt.interfaceNumber, 0, resetErr);
                restoreMuteOnOpenFailure();
                return false;
            }
        }
    }

    // Scale packetsPerUrb by actual packetsPerSecond (honoring bInterval > 1):
    // - Non-MediaTek xHCI/DWC3 HS: 16 URBs x 32 microframes (4 ms/URB) = 64 ms in-flight
    // - MediaTek HS (modern mtu3/xHCI): try 16 x 24 (48 ms) or 16 x 32 (64 ms for >=176.4k/DSD)
    //   and dynamically fall back to legacy MUSB-safe 13 x 16 (26 ms, 208 <= 224 QMU TX limit)
    //   if the host controller rejects the extended URB pipeline during priming.
    // - Full-Speed: 10 URBs x 10 frames (10 ms/URB) = 100 ms in-flight
    const double pps = clockSync_.packetsPerSecond();
    const bool isMt = isMediaTekPlatform();
    const bool mtkLimited = isMt && mtkQmuLimited_.load(std::memory_order_acquire);
    const bool isHighBandwidth =
        (plan.wireSampleRate >= 176400) ||
        isDsdWireMode(plan.outputMode) ||
        (plan.outputMode == OutputTransportMode::DSD_TO_PCM);
    int packetsPerUrb = USB_PACKETS_PER_URB_HS;
    int numOutUrbs = USB_DEFAULT_ISO_URBS;
    if (transport_.isHighSpeed()) {
        if (mtkLimited) {
            packetsPerUrb = std::clamp(
                static_cast<int>(std::llround((2.0 / 1000.0) * pps)),
                2,
                USB_PACKETS_PER_URB_HS
            );
            const int maxByQmu = std::max(USB_MIN_ISO_URBS, MEDIATEK_MUSB_MAX_INFLIGHT_HS_PACKETS / packetsPerUrb);
            numOutUrbs = std::clamp(maxByQmu, USB_MIN_ISO_URBS, USB_MEDIATEK_MAX_ISO_URBS);
        } else if (isMt) {
            const double targetUrbMs = isHighBandwidth ? 4.0 : 3.0;
            const int maxPkts = isHighBandwidth ? USB_PACKETS_PER_URB_HS_MAX : USB_PACKETS_PER_URB_HS_MED;
            packetsPerUrb = std::clamp(
                static_cast<int>(std::llround((targetUrbMs / 1000.0) * pps)),
                2,
                maxPkts
            );
            numOutUrbs = USB_MAX_ISO_URBS; // 16 * 3 ms = 48 ms (standard) or 16 * 4 ms = 64 ms (Hi-Res/DSD)
        } else {
            packetsPerUrb = std::clamp(
                static_cast<int>(std::llround((4.0 / 1000.0) * pps)),
                2,
                USB_PACKETS_PER_URB_HS_MAX
            );
            numOutUrbs = USB_MAX_ISO_URBS; // 16 URBs * ~4 ms = ~64 ms in-flight on xHCI/DWC3
        }
    } else {
        packetsPerUrb = std::clamp(
            static_cast<int>(std::llround((10.0 / 1000.0) * pps)),
            2,
            USB_PACKETS_PER_URB_FS
        );
        numOutUrbs = USB_DEFAULT_ISO_URBS; // 10 URBs * ~10 ms = ~100 ms in-flight on Full-Speed
    }

    const size_t safetyPacketBytes = static_cast<size_t>(
        std::ceil(clockSync_.nominalSamplesPerPacket()) + 4.0
    ) * frameBytes;
    const size_t epMaxPacket = static_cast<size_t>(plan.selectedAlt.maxPacketSize);
    const size_t allocPacketBytes = std::max(epMaxPacket, safetyPacketBytes);
    const size_t maxPacketBytes = (epMaxPacket >= frameBytes) ? epMaxPacket : allocPacketBytes;
    const uint16_t maxFbPacketBytes = (plan.selectedAlt.feedbackUsageType == 1u)
        ? static_cast<uint16_t>(USB_MAX_EXPLICIT_FEEDBACK_BYTES)
        : static_cast<uint16_t>(USB_MAX_FEEDBACK_BYTES);

    UsbIsoPoolConfig poolCfg;
    poolCfg.outEndpoint = plan.selectedAlt.outEndpoint;
    poolCfg.feedbackEndpoint = plan.selectedAlt.feedbackEndpoint;
    poolCfg.numOutUrbs = numOutUrbs;
    poolCfg.packetsPerOutUrb = packetsPerUrb;
    poolCfg.maxOutPacketBytes = maxPacketBytes;
    poolCfg.allocOutPacketBytes = allocPacketBytes;
    poolCfg.enableFeedback = hasExplicitFeedback;
    poolCfg.numFeedbackUrbs = numFbUrbs;
    poolCfg.packetsPerFeedbackUrb = packetsPerFbUrb;
    poolCfg.feedbackPacketBytes = (plan.selectedAlt.feedbackMaxPacketSize >= 3u &&
                                   plan.selectedAlt.feedbackMaxPacketSize <= maxFbPacketBytes)
        ? static_cast<int>(plan.selectedAlt.feedbackMaxPacketSize)
        : ((descInfo_.uacVersion >= UAC_VERSION_2_0) ? 4 : 3);

    if (!transport_.allocateIsoPool(poolCfg)) {
        DRV_LOGE("ISO_POOL_ALLOC_ERR | ep=0x%02x maxPkt=%zu",
                 static_cast<unsigned int>(poolCfg.outEndpoint), allocPacketBytes);
        int resetErr = 0;
        transport_.setInterfaceAltSetting(plan.selectedAlt.interfaceNumber, 0, resetErr);
        restoreMuteOnOpenFailure();
        return false;
    }
    activePoolConfig_ = poolCfg;

    const int64_t exactLatencyUs = (pps > 0.0)
        ? static_cast<int64_t>(std::llround(
            (static_cast<double>(transport_.numOutUrbs()) *
             static_cast<double>(transport_.packetsPerOutUrb()) / pps) * 1e6))
        : (transport_.isHighSpeed() ? 24000LL : 80000LL);
    pipelineLatencyUs_.store(std::max<int64_t>(1000LL, exactLatencyUs), std::memory_order_release);

    const bool formatChanged =
        (lastOpenedSampleRate_ != plan.wireSampleRate) ||
        (lastOpenedBitDepth_ != plan.wireBitDepth) ||
        (lastOpenedOutputMode_ != plan.outputMode);
    lastOpenedSampleRate_ = plan.wireSampleRate;
    lastOpenedBitDepth_ = plan.wireBitDepth;
    lastOpenedOutputMode_ = plan.outputMode;

    // Force hardware volume/mute resync after alt-setting or clock rate changes
    lastVolumeDb256_ = INT32_MIN;
    lastMutedState_ = hardwareMutedForTransition_ ? 1 : -1;
    pendingPostStartVolumeSync_ = true;

    activePlan_ = plan;
    activeSampleRate_.store(plan.wireSampleRate, std::memory_order_release);
    activeBitDepth_.store(plan.wireBitDepth, std::memory_order_release);
    activeSubslotSize_.store(plan.wireSubslotSize, std::memory_order_release);
    activeChannels_.store(plan.wireChannels, std::memory_order_release);
    activeOutputMode_.store(static_cast<int>(plan.outputMode), std::memory_order_release);
    activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_release);

    framesWritten_.store(0, std::memory_order_relaxed);
    underrunCount_.store(0, std::memory_order_relaxed);
    submitErrCount_.store(0, std::memory_order_relaxed);
    lastSubmitErrno_.store(0, std::memory_order_relaxed);
    stallCount_.store(0, std::memory_order_relaxed);
    maxStallMs_.store(0, std::memory_order_relaxed);
    jitterCount_.store(0, std::memory_order_relaxed);
    maxUrbGapMs_.store(0, std::memory_order_relaxed);
    rtTelemetry_.resetForNewStream();
    lastWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkTimeMs_.store(0, std::memory_order_release);

    ringBuffer_.resetImmediate();
    deviceDisconnected_.store(false, std::memory_order_release);
    endOfStream_.store(false, std::memory_order_release);
    prebuffering_.store(true, std::memory_order_release);

    const int sanitizedWarmUp = std::max(0, warmUpMs);
    configuredWarmUpMs_.store(sanitizedWarmUp, std::memory_order_release);
    // Smart Warm-Up: only run on (1) Cold Start from alt=0 (!wasStreamOpen) or (2) Format Switch
    // (sample rate / bit depth / PCM<->DSD change). Keep a 500 ms floor for DSD cold starts /
    // switches when warm-up is enabled so slow DSD relays unmute cleanly even with a 300 ms PCM default.
    const int effectiveColdWarmMs =
        (sanitizedWarmUp > 0 && isDsdWireMode(plan.outputMode))
            ? std::max(sanitizedWarmUp, DSD_COLD_WARMUP_FLOOR_MS)
            : sanitizedWarmUp;
    const int initialWarmMs = (!wasStreamOpen || formatChanged) ? effectiveColdWarmMs : 0;
    warmUpMsPending_.store(initialWarmMs, std::memory_order_release);
    pausedAtElapsedMs_ = 0;
    fadeInFramesTotal_.store(0, std::memory_order_release);
    fadeInFramesRemaining_.store(0, std::memory_order_release);
    fadeOutFramesTotal_.store(0, std::memory_order_release);
    fadeOutFramesRemaining_.store(0, std::memory_order_release);

    const size_t safeCap = (ringBuffer_.capacity() > frameBytes)
        ? (ringBuffer_.capacity() - frameBytes)
        : ringBuffer_.capacity();
    maxBufferedBytes_.store(std::min(safeCap, std::max(targetBufferedBytes, frameBytes * 64u)),
                            std::memory_order_release);

    const int logFbUrbs = transport_.numFeedbackUrbs();
    const int logFbPkts = transport_.packetsPerFeedbackUrb();
    const int logOutUrbs = transport_.numOutUrbs();
    const int logOutPkts = transport_.packetsPerOutUrb();
    const long long logLatUs = static_cast<long long>(pipelineLatencyUs_.load(std::memory_order_relaxed));
    const size_t logRingMb = ringBuffer_.capacity() / (1024u * 1024u);

    const int64_t openedGen = streamGeneration_.fetch_add(1, std::memory_order_acq_rel) + 1;
    streamOpen_.store(true, std::memory_order_release);

    if (wasRunning && !paused_.load(std::memory_order_acquire)) {
        if (streamThread_.joinable()) {
            streamThread_.join();
        }
        const int pendingWarmMs = warmUpMsPending_.exchange(0, std::memory_order_acq_rel);
        warmUpPacketsRemaining_.store(clockSync_.computeWarmUpPackets(pendingWarmMs), std::memory_order_release);
        streamingTid_.store(0, std::memory_order_release);
        streamStartElapsedMs_ = monotonicNowMs();
        running_.store(true, std::memory_order_release);
        streamThread_ = std::thread(&UsbAudioDriver::streamingLoop, this);
        if (hardwareMutedForTransition_) {
            usleep(isDsdWireMode(plan.outputMode) ? DSD_HW_MUTE_SETTLE_US : PCM_HW_MUTE_SETTLE_US);
            hardwareMutedForTransition_ = false;
            lastMutedState_ = -1;
            lastVolumeDb256_ = INT32_MIN;
            applyHardwareVolumeLocked(lastNormalizedVolume_, false, false);
        }
    } else {
        paused_.store(true, std::memory_order_release);
        if (hardwareMutedForTransition_ && !isDsdWireMode(plan.outputMode)) {
            usleep(PCM_HW_MUTE_SETTLE_US);
            hardwareMutedForTransition_ = false;
            lastMutedState_ = -1;
            lastVolumeDb256_ = INT32_MIN;
            applyHardwareVolumeLocked(lastNormalizedVolume_, false, false);
        }
    }

    DRV_LOGI("STREAM_OPEN | gen=%lld mode=%s in=%dHz/%dch/enc=%d wire=%dHz/%db(sub=%d)/%dch ep=0x%02x alt=%d sync=%s fbEp=0x%02x fbUrbs=%dx%d urbs=%dx%d latUs=%lld ringMb=%zu warmMs=%d dsp=0x%x",
             static_cast<long long>(openedGen),
             outputModeName(plan.outputMode),
             sampleRate, channels, static_cast<int>(inputEncoding),
             plan.wireSampleRate, plan.wireBitDepth, plan.wireSubslotSize, plan.wireChannels,
             static_cast<unsigned int>(plan.selectedAlt.outEndpoint),
             plan.selectedAlt.alternateSetting,
             clockSyncModeName(clockSync_.snapshot().activeMode),
             static_cast<unsigned int>(plan.selectedAlt.feedbackEndpoint),
             logFbUrbs, logFbPkts,
             logOutUrbs, logOutPkts,
             logLatUs,
             logRingMb,
             initialWarmMs,
             activeDspFlags_.load(std::memory_order_relaxed));

    return true;
}

void UsbAudioDriver::closeStream() {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    closeStreamLocked(false);
}

void UsbAudioDriver::closeStreamLocked(bool preserveTransitionMute) {
    const bool wasOpen = streamOpen_.exchange(false, std::memory_order_acq_rel);
    const bool wasDsd = isDsdWireMode(activePlan_.outputMode);
    if (wasOpen && wasDsd && transport_.isAttached()) {
        // Mute hardware Feature Unit before halting ISO URBs and dropping out of DSD alt-setting
        if (setHardwareMuteDirectLocked(true)) {
            usleep(FEATURE_UNIT_CMD_DELAY_US);
        }
    }
    running_.store(false, std::memory_order_release);

    if (streamThread_.joinable()) {
        streamThread_.join();
    }
    flushRtEventsToLogs();

    if (wasOpen) {
        const int64_t gen = streamGeneration_.load(std::memory_order_relaxed);
        const int64_t totalFrames = framesWritten_.load(std::memory_order_relaxed);
        const int64_t totalUnderruns = underrunCount_.load(std::memory_order_relaxed);
        const int64_t totalStalls = stallCount_.load(std::memory_order_relaxed);
        const int64_t maxStall = maxStallMs_.load(std::memory_order_relaxed);
        const int64_t totalJitter = jitterCount_.load(std::memory_order_relaxed);
        const int64_t maxGapMs = maxUrbGapMs_.load(std::memory_order_relaxed);
        const int64_t totalSubmitErrs = submitErrCount_.load(std::memory_order_relaxed);
        const int64_t totalRecoveries = rtTelemetry_.outUrbRecoveryCount.load(std::memory_order_relaxed);
        const ClockSyncSnapshot syncSnap = clockSync_.snapshot();
        const bool hasAnomalies =
            totalUnderruns > 0 || totalStalls > 0 || totalSubmitErrs > 0 || syncSnap.watchdogTrips > 0;
        if (hasAnomalies) {
            DRV_LOGW("STREAM_RT_SUMMARY | gen=%lld mode=%s rate=%d bits=%d frames=%lld underruns=%lld stalls=%lld maxStallMs=%lld jitter=%lld maxGapMs=%lld submitErr=%lld urbRecov=%lld fbValid=%lld fbErr=%lld fbOutliers=%lld wdTrips=%lld driftPpm=%+.1f fifo=%d nice=%d",
                     static_cast<long long>(gen),
                     outputModeName(activePlan_.outputMode),
                     activeSampleRate_.load(std::memory_order_relaxed),
                     activeBitDepth_.load(std::memory_order_relaxed),
                     static_cast<long long>(totalFrames),
                     static_cast<long long>(totalUnderruns),
                     static_cast<long long>(totalStalls),
                     static_cast<long long>(maxStall),
                     static_cast<long long>(totalJitter),
                     static_cast<long long>(maxGapMs),
                     static_cast<long long>(totalSubmitErrs),
                     static_cast<long long>(totalRecoveries),
                     static_cast<long long>(syncSnap.validFeedbackCount),
                     static_cast<long long>(syncSnap.errorFeedbackCount),
                     static_cast<long long>(syncSnap.outlierFeedbackCount),
                     static_cast<long long>(syncSnap.watchdogTrips),
                     syncSnap.driftPpmX10 / 10.0,
                     schedFifoActive_.load(std::memory_order_relaxed) ? 1 : 0,
                     appliedNice_.load(std::memory_order_relaxed));
        } else {
            DRV_LOGI("STREAM_RT_SUMMARY | gen=%lld mode=%s rate=%d bits=%d frames=%lld underruns=0 stalls=0 jitter=%lld maxGapMs=%lld fbValid=%lld fbOutliers=%lld driftPpm=%+.1f fifo=%d nice=%d",
                     static_cast<long long>(gen),
                     outputModeName(activePlan_.outputMode),
                     activeSampleRate_.load(std::memory_order_relaxed),
                     activeBitDepth_.load(std::memory_order_relaxed),
                     static_cast<long long>(totalFrames),
                     static_cast<long long>(totalJitter),
                     static_cast<long long>(maxGapMs),
                     static_cast<long long>(syncSnap.validFeedbackCount),
                     static_cast<long long>(syncSnap.outlierFeedbackCount),
                     syncSnap.driftPpmX10 / 10.0,
                     schedFifoActive_.load(std::memory_order_relaxed) ? 1 : 0,
                     appliedNice_.load(std::memory_order_relaxed));
        }
    }

    streamingTid_.store(0, std::memory_order_release);
    schedFifoActive_.store(false, std::memory_order_release);
    lastWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkDurationNs_.store(0, std::memory_order_release);
    lastProducerWorkTimeMs_.store(0, std::memory_order_release);

    transport_.discardAllInFlightUrbs();
    transport_.freeIsoPool();

    if (wasOpen && transport_.isAttached() && activePlan_.selectedAlt.interfaceNumber >= 0) {
        int resetErr = 0;
        if (!transport_.setInterfaceAltSetting(activePlan_.selectedAlt.interfaceNumber, 0, resetErr)) {
            if (!isDisconnectErrno(resetErr)) {
                DRV_LOGW("AS_ALT0_RESET_ERR | iface=%d errno=%d",
                         activePlan_.selectedAlt.interfaceNumber, resetErr);
            }
        }
    }
    if (!preserveTransitionMute) {
        hardwareMutedForTransition_ = false;
    }

    ringBuffer_.resetImmediate();
    clockSync_.clear();
    dspPipeline_.clearPlan();

    activeSampleRate_.store(0, std::memory_order_release);
    activeBitDepth_.store(0, std::memory_order_release);
    activeSubslotSize_.store(0, std::memory_order_release);
    activeChannels_.store(2, std::memory_order_release);
    activeOutputMode_.store(static_cast<int>(OutputTransportMode::INACTIVE), std::memory_order_release);
    activeDspFlags_.store(DSP_STAGE_NONE, std::memory_order_release);
    fadeInFramesTotal_.store(0, std::memory_order_release);
    fadeInFramesRemaining_.store(0, std::memory_order_release);
    fadeOutFramesTotal_.store(0, std::memory_order_release);
    fadeOutFramesRemaining_.store(0, std::memory_order_release);
    pausedAtElapsedMs_ = 0;
}

// ═══ Producer Audio Feeding & Playback State ═══
int UsbAudioDriver::writeAudio(const uint8_t* inputData, int inputBytes, AudioInputEncoding /* encoding */) {
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    if (!streamOpen_.load(std::memory_order_acquire) ||
        deviceDisconnected_.load(std::memory_order_acquire) ||
        !inputData ||
        inputBytes <= 0 ||
        !ringBuffer_.isAllocated()) {
        return 0;
    }

    const size_t availBytes = ringBuffer_.availableBytes();
    const size_t maxBuf = maxBufferedBytes_.load(std::memory_order_acquire);
    if (availBytes >= maxBuf) return 0;

    size_t remainingFree = std::min(ringBuffer_.freeBytes(), maxBuf - availBytes);
    if (remainingFree == 0) return 0;

    const size_t totalInput = static_cast<size_t>(inputBytes);
    size_t totalConsumed = 0;
    const int64_t workStartNs = monotonicNowNs();

    while (totalConsumed < totalInput && remainingFree > 0) {
        size_t stepConsumed = 0;
        const uint8_t* wirePtr = nullptr;
        const size_t wireBytes = dspPipeline_.processToWire(
            inputData + totalConsumed,
            totalInput - totalConsumed,
            remainingFree,
            stepConsumed,
            wirePtr
        );

        if (wireBytes > 0 && wirePtr != nullptr) {
            const size_t written = ringBuffer_.write(wirePtr, wireBytes);
            remainingFree = (written < remainingFree) ? (remainingFree - written) : 0;
        }

        if (stepConsumed == 0) {
            break;
        }
        totalConsumed += stepConsumed;
    }

    if (totalConsumed > 0) {
        const int64_t elapsedNs = std::max<int64_t>(1000LL, monotonicNowNs() - workStartNs);
        const int64_t prevNs = lastProducerWorkDurationNs_.load(std::memory_order_relaxed);
        const int64_t emaNs = (prevNs == 0) ? elapsedNs : ((prevNs * 3LL + elapsedNs) / 4LL);
        lastProducerWorkDurationNs_.store(emaNs, std::memory_order_relaxed);
        lastProducerWorkTimeMs_.store(workStartNs / 1000000LL, std::memory_order_relaxed);
    }

    activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_relaxed);
    return static_cast<int>(totalConsumed);
}

int UsbAudioDriver::writeFloatRestoredToWire32(
    const uint8_t* floatData,
    int inputBytes,
    AudioInputEncoding targetEncoding
) {
    std::lock_guard<std::mutex> prodLock(producerMutex_);
    if (!streamOpen_.load(std::memory_order_acquire) ||
        deviceDisconnected_.load(std::memory_order_acquire) ||
        !floatData ||
        inputBytes <= 0 ||
        !ringBuffer_.isAllocated()) {
        return 0;
    }

    // Eligible strictly for Bit-Perfect 32-bit wire subslots with software volume hard-bypassed
    // and integer target restoration (16/24/32-bit); return -1 to fall back to standard path otherwise.
    const NegotiatedStreamPlan& plan = dspPipeline_.plan();
    if (!plan.valid ||
        plan.outputMode != OutputTransportMode::PCM_BIT_PERFECT ||
        plan.wireSubslotSize != 4 ||
        plan.wireChannels <= 0 ||
        !dspPipeline_.isSoftwareVolumeBypassed() ||
        (targetEncoding != AudioInputEncoding::PCM_16BIT &&
         targetEncoding != AudioInputEncoding::PCM_24BIT &&
         targetEncoding != AudioInputEncoding::PCM_32BIT)) {
        return -1;
    }

    const size_t ch = static_cast<size_t>(plan.wireChannels);
    const size_t frameBytes = ch * 4u; // Input float32 and output 32-bit wire slot are both 4 bytes/sample
    const size_t availInFrames = static_cast<size_t>(inputBytes) / frameBytes;
    if (availInFrames == 0) return 0;

    const size_t availBytes = ringBuffer_.availableBytes();
    const size_t maxBuf = maxBufferedBytes_.load(std::memory_order_acquire);
    if (availBytes >= maxBuf) return 0;

    const size_t remainingFree = std::min(ringBuffer_.freeBytes(), maxBuf - availBytes);
    const size_t maxOutFrames = remainingFree / frameBytes;
    const size_t totalFrames = std::min(availInFrames, maxOutFrames);
    if (totalFrames == 0) return 0;

    const int64_t workStartNs = monotonicNowNs();

    // Fuse Float32 -> bit-exact integer -> MSB-aligned 32-bit wire in 2048-sample L1-resident chunks
    constexpr size_t kChunkSamples = 2048u;
    uint32_t wireChunk[kChunkSamples];
    const size_t maxChunkFrames = std::max<size_t>(1u, kChunkSamples / ch);

    size_t framesDone = 0;
    while (framesDone < totalFrames) {
        const size_t batchFrames = std::min(totalFrames - framesDone, maxChunkFrames);
        const size_t batchSamples = batchFrames * ch;
        const uint8_t* __restrict__ src = floatData + framesDone * frameBytes;
        uint32_t* __restrict__ dst = wireChunk;

        if (targetEncoding == AudioInputEncoding::PCM_24BIT) {
            for (size_t i = 0; i < batchSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, src + i * 4u, sizeof(float));
                const float c = std::isfinite(f) ? std::clamp(f, -1.0f, 1.0f) : 0.0f;
                const int32_t s24 = std::clamp<int32_t>(
                    static_cast<int32_t>(std::lrintf(c * 8388608.0f)),
                    -8388608,
                    8388607
                );
                dst[i] = static_cast<uint32_t>(s24) << 8;
            }
        } else if (targetEncoding == AudioInputEncoding::PCM_16BIT) {
            for (size_t i = 0; i < batchSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, src + i * 4u, sizeof(float));
                const float c = std::isfinite(f) ? std::clamp(f, -1.0f, 1.0f) : 0.0f;
                const int32_t s16 = std::clamp<int32_t>(
                    static_cast<int32_t>(std::lrintf(c * 32768.0f)),
                    -32768,
                    32767
                );
                dst[i] = static_cast<uint32_t>(s16) << 16;
            }
        } else {
            for (size_t i = 0; i < batchSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, src + i * 4u, sizeof(float));
                const double c = std::isfinite(f) ? std::clamp(static_cast<double>(f), -1.0, 1.0) : 0.0;
                const int64_t s64 = std::clamp<int64_t>(
                    static_cast<int64_t>(std::llround(c * 2147483648.0)),
                    -2147483648LL,
                    2147483647LL
                );
                dst[i] = static_cast<uint32_t>(static_cast<int32_t>(s64));
            }
        }

        const size_t batchBytes = batchFrames * frameBytes;
        const size_t written = ringBuffer_.write(reinterpret_cast<const uint8_t*>(wireChunk), batchBytes);
        framesDone += (written / frameBytes);
        if (written < batchBytes) {
            break;
        }
    }

    const size_t totalConsumed = framesDone * frameBytes;
    if (totalConsumed > 0) {
        const int64_t elapsedNs = std::max<int64_t>(1000LL, monotonicNowNs() - workStartNs);
        const int64_t prevNs = lastProducerWorkDurationNs_.load(std::memory_order_relaxed);
        const int64_t emaNs = (prevNs == 0) ? elapsedNs : ((prevNs * 3LL + elapsedNs) / 4LL);
        lastProducerWorkDurationNs_.store(emaNs, std::memory_order_relaxed);
        lastProducerWorkTimeMs_.store(workStartNs / 1000000LL, std::memory_order_relaxed);
    }

    activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_relaxed);
    return static_cast<int>(totalConsumed);
}

void UsbAudioDriver::flush(int warmUpMs) {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    std::lock_guard<std::mutex> prodLock(producerMutex_);

    const bool isRunning = running_.load(std::memory_order_acquire);
    // Cancel any in-progress pause fade-out before flushing the ring buffer.
    fadeOutFramesRemaining_.store(0, std::memory_order_release);
    if (isRunning) {
        // Queue the lock-free ring flush BEFORE setting prebuffering_ so the RT ISO thread's
        // per-packet applyPendingFlush() always advances tail_ before evaluating prebufferBytes.
        ringBuffer_.requestFlush(true);
        prebuffering_.store(true, std::memory_order_release);
        dspPipeline_.reset();
        // Preserve locked async crystal rate across same-rate seeks/flushes; only reset
        // fractional packet accumulator on the RT thread without discarding Feedback IN URBs.
        accumulatorResetRequested_.store(true, std::memory_order_release);
    } else {
        // Wait for any exiting RT ISO thread (e.g. after ENODEV/POLLHUP cleared running_)
        // before mutating transport_, clockSync_, or tail_ directly from the control thread.
        if (streamThread_.joinable()) {
            streamThread_.join();
            flushRtEventsToLogs();
        }
        prebuffering_.store(true, std::memory_order_release);
        ringBuffer_.requestFlush(false);
        dspPipeline_.reset();
        transport_.discardAllFeedbackUrbs();
        clockSync_.reset();
        feedbackResetRequested_.store(false, std::memory_order_release);
        accumulatorResetRequested_.store(false, std::memory_order_release);
        framesWritten_.store(0, std::memory_order_release);
    }

    endOfStream_.store(false, std::memory_order_release);

    const int effectiveWarmUp = std::max(0, warmUpMs);

    if (streamOpen_.load(std::memory_order_acquire)) {
        if (isRunning) {
            if (effectiveWarmUp > 0) {
                warmUpPacketsRemaining_.store(
                    clockSync_.computeWarmUpPackets(effectiveWarmUp),
                    std::memory_order_release
                );
            } else {
                warmUpPacketsRemaining_.store(0, std::memory_order_release);
                // Engage a brief 15 ms PCM micro fade-in across seek discontinuities without stalling 1 s
                if (!isDsdWireMode(activePlan_.outputMode) && activePlan_.wireSampleRate > 0) {
                    const int fadeFrames = std::max(64, (activePlan_.wireSampleRate * PCM_MICRO_FADE_IN_MS) / 1000);
                    fadeInFramesTotal_.store(fadeFrames, std::memory_order_release);
                    fadeInFramesRemaining_.store(fadeFrames, std::memory_order_release);
                }
            }
        } else if (effectiveWarmUp > 0) {
            warmUpMsPending_.store(effectiveWarmUp, std::memory_order_release);
        }
    }
}

void UsbAudioDriver::setWarmUpMs(int warmUpMs) {
    configuredWarmUpMs_.store(std::max(0, warmUpMs), std::memory_order_release);
}

useconds_t UsbAudioDriver::computeSilenceDrainUs() const {
    const int64_t latUs = pipelineLatencyUs_.load(std::memory_order_acquire);
    const int numUrbs = std::max(1, transport_.numOutUrbs());
    const int64_t singleUrbUs = (latUs > 0) ? (latUs / numUrbs) : 4000LL;
    const int64_t totalDrainUs = latUs + singleUrbUs;
    return static_cast<useconds_t>(std::clamp<int64_t>(
        totalDrainUs,
        static_cast<int64_t>(MIN_PAUSE_SILENCE_DRAIN_US),
        static_cast<int64_t>(MAX_PAUSE_SILENCE_DRAIN_US)
    ));
}

void UsbAudioDriver::setPaused(bool paused) {
    std::lock_guard<std::mutex> ctrlLock(controlMutex_);
    if (!streamOpen_.load(std::memory_order_acquire) || !transport_.isAttached()) {
        paused_.store(paused, std::memory_order_release);
        return;
    }

    const bool wasPaused = paused_.load(std::memory_order_acquire);
    const bool isRunning = running_.load(std::memory_order_acquire);
    const bool isDsd = isDsdWireMode(activePlan_.outputMode);

    if (paused) {
        if (wasPaused) {
            return;
        }
        if (isRunning) {
            // True Hot-Standby: keep the ISO thread, alt-setting, and Async Feedback PLL alive
            // while smoothly ramping PCM down over 15 ms into format-accurate silence URBs.
            // Idle timeout in UsbDacManager will close the stream back to alt=0 after inactivity.
            fadeInFramesRemaining_.store(0, std::memory_order_release);
            if (!isDsd && activePlan_.wireSampleRate > 0) {
                const int fadeFrames = std::max(64, (activePlan_.wireSampleRate * PCM_MICRO_FADE_OUT_MS) / 1000);
                fadeOutFramesTotal_.store(fadeFrames, std::memory_order_release);
                fadeOutFramesRemaining_.store(fadeFrames, std::memory_order_release);
            } else {
                fadeOutFramesTotal_.store(0, std::memory_order_release);
                fadeOutFramesRemaining_.store(0, std::memory_order_release);
            }
            pausedAtElapsedMs_ = monotonicNowMs();
            paused_.store(true, std::memory_order_release);
            flushRtEventsToLogs();
            DRV_LOGI("HOT_STANDBY_ENTER | gen=%lld fadeOutMs=%d",
                     static_cast<long long>(streamGeneration_.load(std::memory_order_relaxed)),
                     (!isDsd && activePlan_.wireSampleRate > 0) ? PCM_MICRO_FADE_OUT_MS : 0);
        } else {
            paused_.store(true, std::memory_order_release);
            if (streamThread_.joinable()) {
                streamThread_.join();
                flushRtEventsToLogs();
            }
            if (pausedAtElapsedMs_ == 0) {
                pausedAtElapsedMs_ = monotonicNowMs();
            }
        }
        return;
    }

    // Resume or Cold Start
    if (isRunning) {
        if (!wasPaused) {
            return;
        }
        // Instant resume from Hot-Standby: 0 ms warm-up, PLL already locked, 15 ms PCM micro fade-in.
        const int64_t standbyGapMs = (pausedAtElapsedMs_ > 0)
            ? std::max<int64_t>(0, monotonicNowMs() - pausedAtElapsedMs_)
            : 0;
        pausedAtElapsedMs_ = 0;
        fadeOutFramesRemaining_.store(0, std::memory_order_release);
        warmUpMsPending_.store(0, std::memory_order_release);
        warmUpPacketsRemaining_.store(0, std::memory_order_release);
        if (!isDsd && activePlan_.wireSampleRate > 0) {
            const int fadeFrames = std::max(64, (activePlan_.wireSampleRate * PCM_MICRO_FADE_IN_MS) / 1000);
            fadeInFramesTotal_.store(fadeFrames, std::memory_order_release);
            fadeInFramesRemaining_.store(fadeFrames, std::memory_order_release);
        } else {
            fadeInFramesTotal_.store(0, std::memory_order_release);
            fadeInFramesRemaining_.store(0, std::memory_order_release);
        }
        paused_.store(false, std::memory_order_release);
        DRV_LOGI("HOT_STANDBY_RESUME | gen=%lld gapMs=%lld fadeInMs=%d fbLocked=%d",
                 static_cast<long long>(streamGeneration_.load(std::memory_order_relaxed)),
                 static_cast<long long>(standbyGapMs),
                 fadeInFramesRemaining_.load(std::memory_order_relaxed) > 0 ? PCM_MICRO_FADE_IN_MS : 0,
                 clockSync_.snapshot().feedbackLocked ? 1 : 0);
        return;
    }

    // Cold Start (from alt=0 or after format switch): start ISO thread with Smart Warm-Up.
    if (streamThread_.joinable()) {
        streamThread_.join();
    }
    if (isDsd && !hardwareMutedForTransition_) {
        if (setHardwareMuteDirectLocked(true)) {
            hardwareMutedForTransition_ = true;
        }
    }
    const int pendingWarmMs = warmUpMsPending_.exchange(0, std::memory_order_acq_rel);
    pausedAtElapsedMs_ = 0;
    fadeOutFramesTotal_.store(0, std::memory_order_release);
    fadeOutFramesRemaining_.store(0, std::memory_order_release);
    if (pendingWarmMs == 0 && !isDsd && activePlan_.wireSampleRate > 0) {
        const int fadeFrames = std::max(64, (activePlan_.wireSampleRate * PCM_MICRO_FADE_IN_MS) / 1000);
        fadeInFramesTotal_.store(fadeFrames, std::memory_order_release);
        fadeInFramesRemaining_.store(fadeFrames, std::memory_order_release);
    } else {
        fadeInFramesTotal_.store(0, std::memory_order_release);
        fadeInFramesRemaining_.store(0, std::memory_order_release);
    }
    const int warmPackets = clockSync_.computeWarmUpPackets(pendingWarmMs);
    warmUpPacketsRemaining_.store(warmPackets, std::memory_order_release);
    // Flush any stale Async Feedback URBs and reset clock-sync warmup before starting cold ISO loop
    transport_.discardAllFeedbackUrbs();
    feedbackResetRequested_.store(false, std::memory_order_release);
    accumulatorResetRequested_.store(false, std::memory_order_release);
    clockSync_.reset();
    prebuffering_.store(true, std::memory_order_release);
    streamingTid_.store(0, std::memory_order_release);
    streamStartElapsedMs_ = monotonicNowMs();
    paused_.store(false, std::memory_order_release);
    running_.store(true, std::memory_order_release);
    streamThread_ = std::thread(&UsbAudioDriver::streamingLoop, this);
    if (hardwareMutedForTransition_) {
        // Keep hardware muted until balanced silence URBs have settled on the DAC
        usleep(isDsd ? DSD_HW_MUTE_SETTLE_US : PCM_HW_MUTE_SETTLE_US);
        hardwareMutedForTransition_ = false;
        lastMutedState_ = -1;
        lastVolumeDb256_ = INT32_MIN;
        applyHardwareVolumeLocked(lastNormalizedVolume_, false, false);
    }
    DRV_LOGI("STREAM_COLD_START | gen=%lld warmMs=%d warmPkts=%d fadeInMs=%d",
             static_cast<long long>(streamGeneration_.load(std::memory_order_relaxed)),
             pendingWarmMs,
             warmPackets,
             fadeInFramesRemaining_.load(std::memory_order_relaxed) > 0 ? PCM_MICRO_FADE_IN_MS : 0);
}

void UsbAudioDriver::setEndOfStream(bool eos) {
    endOfStream_.store(eos, std::memory_order_release);
}

// ═══ DSD & Fallback DSP Preferences ═══
void UsbAudioDriver::setDsdMode(DsdPlaybackMode mode) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    dspOptions_.dsdMode = mode;
}

void UsbAudioDriver::setDsdGainCompensationDb(float gainDb) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    dspOptions_.dsdGainCompensationDb = std::clamp(gainDb, -6.0f, 12.0f);
}

void UsbAudioDriver::setDitherEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    dspOptions_.ditherEnabled = enabled;
}

void UsbAudioDriver::setResamplerQuality(ResamplerQuality quality) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    dspOptions_.resamplerQuality = quality;
}

// ═══ Hardware & 64-Bit Software Volume Control ═══
void UsbAudioDriver::queryVolumeRange() {
    hasHardwareVolume_.store(false, std::memory_order_release);
    dspPipeline_.setHardwareVolumeActive(false);

    if (!transport_.isAttached() || descInfo_.featureUnitId < 0 || descInfo_.controlInterface < 0) {
        return;
    }

    const UacFeatureUnitInfo* activeFu = findActiveFeatureUnit(descInfo_);
    const bool masterVolumeAdvertised = (activeFu != nullptr)
        ? activeFu->hasMasterVolume
        : descInfo_.volumeMasterChannel;
    const std::vector<int> volChannels = collectFeatureUnitChannels(descInfo_, activeFu, true);

    // Probe Master Channel (CN = 0) first whenever bmaControls[0] advertises Volume Control so
    // the DAC updates L/R atomically; probe CN = 1..N first when Master Volume is not advertised.
    std::vector<int> probeChannels;
    if (masterVolumeAdvertised) {
        probeChannels.push_back(0);
        for (int ch : volChannels) {
            if (ch != 0) probeChannels.push_back(ch);
        }
    } else {
        for (int ch : volChannels) {
            if (ch != 0) probeChannels.push_back(ch);
        }
        probeChannels.push_back(0);
    }

    const uint16_t wIndex = static_cast<uint16_t>(
        (descInfo_.featureUnitId << 8) | (descInfo_.controlInterface & 0xFF)
    );

    if (descInfo_.uacVersion >= UAC_VERSION_2_0) {
        uint8_t buf[128]{};
        for (int ch : probeChannels) {
            const uint16_t wValue = static_cast<uint16_t>(FU_VOLUME_CONTROL | (ch & 0xFF));
            int ctrlErr = 0;
            const int ret = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_IN,
                UAC2_REQ_RANGE,
                wValue,
                wIndex,
                buf,
                static_cast<uint16_t>(sizeof(buf)),
                500,
                ctrlErr
            );
            if (ret >= 8) {
                const uint16_t numRanges = static_cast<uint16_t>(buf[0]) | (static_cast<uint16_t>(buf[1]) << 8);
                if (numRanges >= 1u) {
                    // Aggregate across all UAC 2.0/3.0 volume sub-ranges so piecewise curves span full dB.
                    // Ignore degenerate single-point sub-ranges (e.g. [0x8000..0x8000, res=0] silence point)
                    // when computing step resolution so aggRes never collapses down to 1 (1/256 dB).
                    int aggMin = readLeI16(buf + 2);
                    int aggMax = readLeI16(buf + 4);
                    const int rawRes0 = readLeI16(buf + 6);
                    int aggRes = (aggMin < aggMax && rawRes0 > 0) ? rawRes0 : 0;
                    for (uint16_t r = 1u; r < numRanges && (2 + static_cast<int>(r + 1) * 6) <= ret; ++r) {
                        const uint8_t* entry = buf + 2 + r * 6;
                        const int subMin = readLeI16(entry);
                        const int subMax = readLeI16(entry + 2);
                        const int rawSubRes = readLeI16(entry + 4);
                        aggMin = std::min(aggMin, subMin);
                        aggMax = std::max(aggMax, subMax);
                        if (subMin < subMax && rawSubRes > 0) {
                            aggRes = (aggRes == 0) ? rawSubRes : std::min(aggRes, rawSubRes);
                        }
                    }
                    if (aggRes <= 0) {
                        aggRes = 256;
                    }
                    volumeMinDb256_ = aggMin;
                    volumeMaxDb256_ = aggMax;
                    volumeResDb256_ = aggRes;
                    const bool hwVol = (volumeMaxDb256_ > volumeMinDb256_);
                    volumeMasterChannel_ = (ch == 0);
                    descInfo_.hasHardwareVolume = hwVol;
                    descInfo_.volumeMasterChannel = volumeMasterChannel_;
                    descInfo_.volumeMinDb256 = volumeMinDb256_;
                    descInfo_.volumeMaxDb256 = volumeMaxDb256_;
                    descInfo_.volumeResDb256 = volumeResDb256_;
                    hasHardwareVolume_.store(hwVol, std::memory_order_release);
                    dspPipeline_.setHardwareVolumeActive(hwVol);

                    // Probe current hardware mute state (GET_CUR MUTE) on the actual mute channel
                    // so redundant MUTE=0 writes are skipped without pulsing sensitive DACs.
                    uint8_t curMute = 0;
                    int muteErr = 0;
                    const int muteProbeCh = (activeFu != nullptr && activeFu->hasMasterMute) ? 0 : ch;
                    const uint16_t muteVal = static_cast<uint16_t>(FU_MUTE_CONTROL | (muteProbeCh & 0xFF));
                    if (transport_.controlTransfer(
                            USB_REQ_TYPE_CLASS_IFACE_IN,
                            getCurRequestCode(descInfo_.uacVersion),
                            muteVal,
                            wIndex,
                            &curMute,
                            1,
                            300,
                            muteErr
                        ) == 1) {
                        lastMutedState_ = (curMute != 0) ? 1 : 0;
                    }
                    DRV_LOGI("HW_VOL_CAPS | uac=2 fu=%d ch=%d master=%d masterAdv=%d muteCur=%d subRanges=%u min=%d max=%d step=%d",
                             descInfo_.featureUnitId, ch, volumeMasterChannel_ ? 1 : 0,
                             masterVolumeAdvertised ? 1 : 0,
                             lastMutedState_,
                             static_cast<unsigned int>(numRanges),
                             volumeMinDb256_, volumeMaxDb256_, volumeResDb256_);
                    if (hwVol) {
                        normalizeFeatureUnitChannelBalanceLocked();
                        return;
                    }
                }
            }
        }
    } else {
        for (int ch : probeChannels) {
            uint8_t minB[2]{}, maxB[2]{}, resB[2]{};
            const uint16_t wValue = static_cast<uint16_t>(FU_VOLUME_CONTROL | (ch & 0xFF));
            int err1 = 0, err2 = 0, err3 = 0;
            const int r1 = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_IN, UAC1_REQ_GET_MIN, wValue, wIndex, minB, 2, 500, err1
            );
            const int r2 = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_IN, UAC1_REQ_GET_MAX, wValue, wIndex, maxB, 2, 500, err2
            );
            const int r3 = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_IN, UAC1_REQ_GET_RES, wValue, wIndex, resB, 2, 500, err3
            );
            if (r1 == 2 && r2 == 2) {
                volumeMinDb256_ = readLeI16(minB);
                volumeMaxDb256_ = readLeI16(maxB);
                volumeResDb256_ = (r3 == 2) ? std::max<int>(1, readLeI16(resB)) : 256;
                const bool hwVol = (volumeMaxDb256_ > volumeMinDb256_);
                volumeMasterChannel_ = (ch == 0);
                descInfo_.hasHardwareVolume = hwVol;
                descInfo_.volumeMasterChannel = volumeMasterChannel_;
                descInfo_.volumeMinDb256 = volumeMinDb256_;
                descInfo_.volumeMaxDb256 = volumeMaxDb256_;
                descInfo_.volumeResDb256 = volumeResDb256_;
                hasHardwareVolume_.store(hwVol, std::memory_order_release);
                dspPipeline_.setHardwareVolumeActive(hwVol);

                uint8_t curMute = 0;
                int muteErr = 0;
                const int muteProbeCh = (activeFu != nullptr && activeFu->hasMasterMute) ? 0 : ch;
                const uint16_t muteVal = static_cast<uint16_t>(FU_MUTE_CONTROL | (muteProbeCh & 0xFF));
                if (transport_.controlTransfer(
                        USB_REQ_TYPE_CLASS_IFACE_IN,
                        UAC1_REQ_GET_CUR,
                        muteVal,
                        wIndex,
                        &curMute,
                        1,
                        300,
                        muteErr
                    ) == 1) {
                    lastMutedState_ = (curMute != 0) ? 1 : 0;
                }
                DRV_LOGI("HW_VOL_CAPS | uac=1 fu=%d ch=%d master=%d masterAdv=%d muteCur=%d min=%d max=%d step=%d",
                         descInfo_.featureUnitId, ch, volumeMasterChannel_ ? 1 : 0,
                         masterVolumeAdvertised ? 1 : 0,
                         lastMutedState_,
                         volumeMinDb256_, volumeMaxDb256_, volumeResDb256_);
                if (hwVol) {
                    normalizeFeatureUnitChannelBalanceLocked();
                    return;
                }
            }
        }
    }
}

void UsbAudioDriver::setVolume(float normalized01) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    applyHardwareVolumeLocked(normalized01, false, false);
}

void UsbAudioDriver::setMuted(bool muted) {
    std::lock_guard<std::mutex> lock(controlMutex_);
    explicitMuted_ = muted;
    applyHardwareVolumeLocked(lastNormalizedVolume_, true, muted);
}

void UsbAudioDriver::getHardwareVolumeRangeDb256(int& outMin, int& outMax, int& outRes) const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    if (hasHardwareVolume_.load(std::memory_order_acquire)) {
        outMin = volumeMinDb256_;
        outMax = volumeMaxDb256_;
        outRes = volumeResDb256_;
    } else {
        outMin = 0;
        outMax = 0;
        outRes = 256;
    }
}

bool UsbAudioDriver::setHardwareMuteDirectLocked(bool muted) {
    if (!transport_.isAttached() || descInfo_.featureUnitId < 0 || descInfo_.controlInterface < 0) {
        return false;
    }
    const UacFeatureUnitInfo* activeFu = findActiveFeatureUnit(descInfo_);
    const bool hasAnyMute = descInfo_.hasHardwareMute ||
        (activeFu != nullptr && (activeFu->hasMasterMute || activeFu->hasChannelMute));
    if (!hasAnyMute) {
        return false;
    }

    const uint16_t wIndex = static_cast<uint16_t>(
        (descInfo_.featureUnitId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    uint8_t muteByte = muted ? 1u : 0u;
    const bool useMasterMute = (activeFu != nullptr)
        ? activeFu->hasMasterMute
        : volumeMasterChannel_;

    bool muteOk = false;
    if (useMasterMute) {
        int err0 = 0;
        const int r0 = transport_.controlTransfer(
            USB_REQ_TYPE_CLASS_IFACE_OUT,
            UAC_REQ_SET_CUR,
            static_cast<uint16_t>(FU_MUTE_CONTROL | 0x00u),
            wIndex,
            &muteByte,
            1,
            200,
            err0
        );
        muteOk = (r0 >= 0);
    }
    if (!muteOk) {
        const std::vector<int> muteChannels = collectFeatureUnitChannels(descInfo_, activeFu, false);
        muteOk = writeFeatureUnitChannels(transport_, FU_MUTE_CONTROL, wIndex, muteChannels, &muteByte, 1);
    }
    if (muteOk) {
        lastMutedState_ = muted ? 1 : 0;
    }
    return muteOk;
}

void UsbAudioDriver::normalizeFeatureUnitChannelBalanceLocked() {
    if (!transport_.isAttached() || descInfo_.featureUnitId < 0 || descInfo_.controlInterface < 0) {
        return;
    }
    const UacFeatureUnitInfo* activeFu = findActiveFeatureUnit(descInfo_);
    if (activeFu == nullptr) return;

    const uint16_t wIndex = static_cast<uint16_t>(
        (descInfo_.featureUnitId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    const uint8_t getCurReq = getCurRequestCode(descInfo_.uacVersion);
    const int neutralDb256 = std::clamp(0, volumeMinDb256_, volumeMaxDb256_);
    const int toleranceDb256 = std::max(volumeResDb256_, 256);

    // Clear any stale per-channel mute left by the OS when Master Mute is the active mute control.
    if (activeFu->hasMasterMute && activeFu->hasChannelMute) {
        const std::vector<int> muteChannels = collectFeatureUnitChannels(descInfo_, activeFu, false);
        bool anyChannelMuted = false;
        for (int ch : muteChannels) {
            uint8_t curMute = 0;
            int err = 0;
            const uint16_t wValue = static_cast<uint16_t>(FU_MUTE_CONTROL | (ch & 0xFF));
            if (transport_.controlTransfer(
                    USB_REQ_TYPE_CLASS_IFACE_IN, getCurReq, wValue, wIndex, &curMute, 1, 200, err
                ) == 1) {
                if (curMute != 0) {
                    anyChannelMuted = true;
                    break;
                }
            } else if (isDisconnectErrno(err)) {
                return;
            }
        }
        if (anyChannelMuted) {
            uint8_t unmuteByte = 0u;
            writeFeatureUnitChannels(transport_, FU_MUTE_CONTROL, wIndex, muteChannels, &unmuteByte, 1);
            DRV_LOGD("FU_MUTE_CLEAR | fu=%d uac=0x%04x",
                     descInfo_.featureUnitId, descInfo_.uacVersion);
        }
    }

    // For UAC 1.0/2.0/3.0 DACs advertising BOTH Master (CN=0) and Per-Channel (CN=1..N) volume:
    // unify channels into a single master volume by neutralizing any pre-existing L/R imbalance or
    // independent attenuation left by the Android Audio HAL before PlayDepth claims the device.
    if (!hasHardwareVolume_.load(std::memory_order_acquire)) return;

    if (volumeMasterChannel_ && activeFu->hasChannelVolume) {
        int masterCurDb256 = 0;
        bool hasMasterCur = false;
        {
            uint8_t mBuf[2]{};
            int mErr = 0;
            if (transport_.controlTransfer(
                    USB_REQ_TYPE_CLASS_IFACE_IN,
                    getCurReq,
                    static_cast<uint16_t>(FU_VOLUME_CONTROL | 0x00u),
                    wIndex,
                    mBuf,
                    2,
                    200,
                    mErr
                ) == 2) {
                masterCurDb256 = static_cast<int>(readLeI16(mBuf));
                hasMasterCur = true;
            }
        }

        const std::vector<int> volChannels = collectFeatureUnitChannels(descInfo_, activeFu, true);
        std::vector<int> readValues;
        readValues.reserve(volChannels.size());
        for (int ch : volChannels) {
            uint8_t cBuf[2]{};
            int cErr = 0;
            const uint16_t wValue = static_cast<uint16_t>(FU_VOLUME_CONTROL | (ch & 0xFF));
            if (transport_.controlTransfer(
                    USB_REQ_TYPE_CLASS_IFACE_IN, getCurReq, wValue, wIndex, cBuf, 2, 200, cErr
                ) == 2) {
                readValues.push_back(static_cast<int>(readLeI16(cBuf)));
            } else if (isDisconnectErrno(cErr)) {
                return;
            }
        }

        if (!readValues.empty()) {
            bool channelsImbalanced = false;
            bool independentTrimOffset = false;
            const int firstVal = readValues.front();
            for (int v : readValues) {
                if (std::abs(v - firstVal) > toleranceDb256) {
                    channelsImbalanced = true;
                }
                const bool matchesMaster = hasMasterCur && (std::abs(v - masterCurDb256) <= toleranceDb256);
                const bool matchesNeutral = (std::abs(v - neutralDb256) <= toleranceDb256);
                if (!matchesMaster && !matchesNeutral) {
                    independentTrimOffset = true;
                }
            }
            if (channelsImbalanced || independentTrimOffset) {
                uint8_t neutralBytes[2] = {
                    static_cast<uint8_t>(neutralDb256 & 0xFF),
                    static_cast<uint8_t>((neutralDb256 >> 8) & 0xFF)
                };
                if (writeFeatureUnitChannels(transport_, FU_VOLUME_CONTROL, wIndex, volChannels, neutralBytes, 2)) {
                    verifyAndRepairFeatureUnitVolumeLocked(wIndex, volChannels, neutralDb256);
                    lastVolumeDb256_ = INT32_MIN;
                    DRV_LOGD("FU_NORM_CH | chCount=%zu db256=%d fu=%d uac=0x%04x",
                             volChannels.size(), neutralDb256, descInfo_.featureUnitId, descInfo_.uacVersion);
                }
            }
        }
    } else if (!volumeMasterChannel_ && activeFu->hasMasterVolume) {
        // When operating in unified per-channel mode (CN=1..N) on a DAC that also exposes CN=0,
        // neutralize Master CN=0 to 0 dB if it was left independently attenuated by the OS.
        uint8_t mBuf[2]{};
        int mErr = 0;
        if (transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_IN,
                getCurReq,
                static_cast<uint16_t>(FU_VOLUME_CONTROL | 0x00u),
                wIndex,
                mBuf,
                2,
                200,
                mErr
            ) == 2) {
            const int masterCurDb256 = static_cast<int>(readLeI16(mBuf));
            if (std::abs(masterCurDb256 - neutralDb256) > toleranceDb256) {
                uint8_t neutralBytes[2] = {
                    static_cast<uint8_t>(neutralDb256 & 0xFF),
                    static_cast<uint8_t>((neutralDb256 >> 8) & 0xFF)
                };
                int setErr = 0;
                if (transport_.controlTransfer(
                        USB_REQ_TYPE_CLASS_IFACE_OUT,
                        UAC_REQ_SET_CUR,
                        static_cast<uint16_t>(FU_VOLUME_CONTROL | 0x00u),
                        wIndex,
                        neutralBytes,
                        2,
                        200,
                        setErr
                    ) >= 0) {
                    lastVolumeDb256_ = INT32_MIN;
                    DRV_LOGD("FU_NORM_MASTER | fromDb256=%d toDb256=%d fu=%d uac=0x%04x",
                             masterCurDb256, neutralDb256, descInfo_.featureUnitId, descInfo_.uacVersion);
                }
            }
        }
    }
}

void UsbAudioDriver::verifyAndRepairFeatureUnitVolumeLocked(
    uint16_t wIndex,
    const std::vector<int>& channels,
    int targetDb256
) {
    if (channels.size() <= 1u || !transport_.isAttached()) return;
    usleep(FEATURE_UNIT_CMD_DELAY_US);
    const uint8_t getCurReq = getCurRequestCode(descInfo_.uacVersion);
    const int toleranceDb256 = std::max(volumeResDb256_, 256);
    uint8_t targetBytes[2] = {
        static_cast<uint8_t>(targetDb256 & 0xFF),
        static_cast<uint8_t>((targetDb256 >> 8) & 0xFF)
    };
    for (int ch : channels) {
        const uint16_t wValue = static_cast<uint16_t>(FU_VOLUME_CONTROL | (ch & 0xFF));
        uint8_t readBuf[2]{};
        int readErr = 0;
        const int r = transport_.controlTransfer(
            USB_REQ_TYPE_CLASS_IFACE_IN, getCurReq, wValue, wIndex, readBuf, 2, 200, readErr
        );
        if (r == 2) {
            const int actualDb256 = static_cast<int>(readLeI16(readBuf));
            if (std::abs(actualDb256 - targetDb256) > toleranceDb256) {
                DRV_LOGW("FU_VOL_REPAIR | ch=%d got=%d want=%d", ch, actualDb256, targetDb256);
                usleep(FEATURE_UNIT_CMD_DELAY_US);
                int repairErr = 0;
                transport_.controlTransfer(
                    USB_REQ_TYPE_CLASS_IFACE_OUT, UAC_REQ_SET_CUR, wValue, wIndex, targetBytes, 2, 200, repairErr
                );
            }
        } else if (isDisconnectErrno(readErr)) {
            break;
        }
    }
}

void UsbAudioDriver::applyHardwareVolumeLocked(
    float normalized01,
    bool forceMuteOverride,
    bool muteOverrideValue
) {
    const float clamped = std::clamp(normalized01, 0.0f, 1.0f);
    lastNormalizedVolume_ = clamped;
    const bool effectiveMuted = hardwareMutedForTransition_
        ? true
        : (forceMuteOverride ? muteOverrideValue : (explicitMuted_ || clamped <= 0.001f));

    if (!hasHardwareVolume_.load(std::memory_order_acquire) ||
        !transport_.isAttached() ||
        descInfo_.featureUnitId < 0 ||
        descInfo_.controlInterface < 0) {
        // 64-bit software volume fallback with automatic 0 dB Hard-Bypass at 100%
        double linearGain = 1.0;
        if (effectiveMuted) {
            linearGain = 0.0;
        } else if (clamped >= 0.995f) {
            linearGain = 1.0;
        } else {
            const double db = -64.0 * std::pow(1.0 - static_cast<double>(clamped), 1.65);
            linearGain = std::pow(10.0, db / 20.0);
        }
        softwareMuted_.store(effectiveMuted, std::memory_order_release);
        dspPipeline_.setHardwareVolumeActive(false);
        dspPipeline_.setSoftwareGainLinear(linearGain);
        if (streamOpen_.load(std::memory_order_acquire)) {
            activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_release);
        }

        // If Native DSD / DoP is actively open on a DAC without Hardware Volume and the user
        // lowers volume below 100% (not muted), seamlessly switch to DSD_TO_PCM so 64-bit software
        // attenuation takes effect immediately rather than blasting 0 dBFS.
        if (!effectiveMuted &&
            clamped < 0.995f &&
            streamOpen_.load(std::memory_order_acquire) &&
            isDsdWireMode(activePlan_.outputMode) &&
            hasLastStreamRequest_) {
            std::lock_guard<std::mutex> prodLock(producerMutex_);
            openStreamLocked(
                lastStreamRequest_.inputSampleRate,
                lastStreamRequest_.inputChannels,
                lastStreamRequest_.preferredBitDepth,
                lastStreamRequest_.inputEncoding,
                lastStreamWarmUpMs_
            );
        }
        return;
    }

    // Keep digital stream 100% Bit-Perfect (hard-bypass 64-bit software volume)
    softwareMuted_.store(false, std::memory_order_release);
    dspPipeline_.setHardwareVolumeActive(true);
    dspPipeline_.setSoftwareGainLinear(1.0);
    if (streamOpen_.load(std::memory_order_acquire)) {
        activeDspFlags_.store(dspPipeline_.activeDspFlags(), std::memory_order_release);
    }

    const UacFeatureUnitInfo* activeFu = findActiveFeatureUnit(descInfo_);
    const bool hasAnyMute = descInfo_.hasHardwareMute ||
        (activeFu != nullptr && (activeFu->hasMasterMute || activeFu->hasChannelMute));

    // Never smash per-channel DAC volume registers down to minDb (-63 dB) while a transition mute
    // is active; keep lastNormalizedVolume_ updated and defer volume writes until transition unmute.
    if (hardwareMutedForTransition_) {
        if (hasAnyMute && lastMutedState_ != 1) {
            setHardwareMuteDirectLocked(true);
        }
        return;
    }

    const uint16_t wIndex = static_cast<uint16_t>(
        (descInfo_.featureUnitId << 8) | (descInfo_.controlInterface & 0xFF)
    );
    const int mutedState = effectiveMuted ? 1 : 0;

    // Power-dB perceptual curve covering ~66 dB so low steps stay gentle on sensitive IEMs
    const double maxDb = static_cast<double>(volumeMaxDb256_) / 256.0;
    const double minDb = static_cast<double>(volumeMinDb256_) / 256.0;
    const double effectiveMinDb = std::max(minDb, maxDb - 66.0);
    double db = maxDb;
    if (mutedState) {
        db = minDb;
    } else if (clamped < 0.995f) {
        db = maxDb - (maxDb - effectiveMinDb) * std::pow(1.0 - static_cast<double>(clamped), 1.65);
    }

    int targetDb256 = static_cast<int>(std::llround(db * 256.0));
    targetDb256 = std::clamp(targetDb256, volumeMinDb256_, volumeMaxDb256_);
    if (volumeResDb256_ > 1) {
        const long long steps = std::llround(
            static_cast<double>(targetDb256 - volumeMinDb256_) / static_cast<double>(volumeResDb256_)
        );
        targetDb256 = std::clamp(
            volumeMinDb256_ + static_cast<int>(steps) * volumeResDb256_,
            volumeMinDb256_,
            volumeMaxDb256_
        );
    }

    const bool forcePostStartRefresh =
        pendingPostStartVolumeSync_ &&
        running_.load(std::memory_order_acquire) &&
        (monotonicNowMs() - streamStartElapsedMs_ >= POST_START_VOLUME_SETTLE_MS);

    const bool useMasterMute = (activeFu != nullptr)
        ? activeFu->hasMasterMute
        : volumeMasterChannel_;
    const bool useMasterVolume = volumeMasterChannel_ &&
        ((activeFu == nullptr) || activeFu->hasMasterVolume || !activeFu->hasChannelVolume);

    bool muteSent = false;
    bool muteOk = true;
    // Only send SET_CUR(MUTE) when mute state actually changed (or unknown) so post-start refresh
    // never re-triggers a mute relay/ramp tick on sensitive DACs like FIIO KA11.
    if (hasAnyMute && (lastMutedState_ != mutedState)) {
        uint8_t muteByte = static_cast<uint8_t>(mutedState);
        if (useMasterMute) {
            int err0 = 0;
            const int r0 = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_OUT,
                UAC_REQ_SET_CUR,
                static_cast<uint16_t>(FU_MUTE_CONTROL | 0x00u),
                wIndex,
                &muteByte,
                1,
                200,
                err0
            );
            muteOk = (r0 >= 0);
            muteSent = true;
        }
        if (!useMasterMute || !muteOk) {
            const std::vector<int> muteChannels = collectFeatureUnitChannels(descInfo_, activeFu, false);
            muteOk = writeFeatureUnitChannels(transport_, FU_MUTE_CONTROL, wIndex, muteChannels, &muteByte, 1);
            muteSent = true;
        }
        if (muteOk) {
            lastMutedState_ = mutedState;
        }
    } else if (!hasAnyMute) {
        lastMutedState_ = mutedState;
    }

    // Pace Mute -> Volume transition only when Mute actually updated so I2C bridges settle cleanly
    if (muteSent && (lastVolumeDb256_ != targetDb256 || forcePostStartRefresh)) {
        usleep(FEATURE_UNIT_CMD_DELAY_US);
    }

    if (forcePostStartRefresh && useMasterVolume && activeFu != nullptr && activeFu->hasChannelVolume) {
        normalizeFeatureUnitChannelBalanceLocked();
    }

    const int prevVolumeDb256 = lastVolumeDb256_;
    bool volOk = true;
    if (prevVolumeDb256 != targetDb256 || forcePostStartRefresh) {
        uint8_t volBytes[2] = {
            static_cast<uint8_t>(targetDb256 & 0xFF),
            static_cast<uint8_t>((targetDb256 >> 8) & 0xFF)
        };
        if (useMasterVolume) {
            // Single atomic Master Channel (CN = 0) transfer updates L/R simultaneously
            int err0 = 0;
            const int ret = transport_.controlTransfer(
                USB_REQ_TYPE_CLASS_IFACE_OUT,
                UAC_REQ_SET_CUR,
                static_cast<uint16_t>(FU_VOLUME_CONTROL | 0x00u),
                wIndex,
                volBytes,
                2,
                200,
                err0
            );
            volOk = (ret >= 0);
            if (!volOk && !isDisconnectErrno(err0)) {
                // Fallback to per-channel CN = 1..N if device firmware rejects CN = 0 SET_CUR
                const std::vector<int> volChannels = collectFeatureUnitChannels(descInfo_, activeFu, true);
                if (writeFeatureUnitChannels(transport_, FU_VOLUME_CONTROL, wIndex, volChannels, volBytes, 2)) {
                    volOk = true;
                    verifyAndRepairFeatureUnitVolumeLocked(wIndex, volChannels, targetDb256);
                    if (err0 == EPIPE) {
                        volumeMasterChannel_ = false;
                        descInfo_.volumeMasterChannel = false;
                        DRV_LOGW("FU_CN0_FALLBACK | errno=%d mode=per_channel", err0);
                    }
                }
            }
        } else {
            // Per-channel fallback (CN = 1, 2, ...) when Master Channel 0 does not support Volume Control
            const std::vector<int> volChannels = collectFeatureUnitChannels(descInfo_, activeFu, true);
            volOk = writeFeatureUnitChannels(transport_, FU_VOLUME_CONTROL, wIndex, volChannels, volBytes, 2);
            if (volOk && (muteSent || forcePostStartRefresh || prevVolumeDb256 == INT32_MIN)) {
                verifyAndRepairFeatureUnitVolumeLocked(wIndex, volChannels, targetDb256);
            }
        }
        if (volOk) {
            lastVolumeDb256_ = targetDb256;
        }
    }

    if (forcePostStartRefresh && muteOk && volOk) {
        pendingPostStartVolumeSync_ = false;
    }
}

// ═══ Ring Buffer & Work Telemetry ═══
int64_t UsbAudioDriver::getLastWorkDurationNs() const {
    const int64_t isoNs = lastWorkDurationNs_.load(std::memory_order_acquire);
    const int64_t prodNs = lastProducerWorkDurationNs_.load(std::memory_order_relaxed);
    if (prodNs <= isoNs) {
        return isoNs;
    }
    const int64_t prodMs = lastProducerWorkTimeMs_.load(std::memory_order_relaxed);
    if (prodMs > 0 && (monotonicNowMs() - prodMs) <= PRODUCER_WORK_FRESHNESS_MS) {
        return prodNs;
    }
    return isoNs;
}

int UsbAudioDriver::getRingBufferAvailableBytes() const {
    return static_cast<int>(ringBuffer_.availableBytes());
}

int UsbAudioDriver::getRingBufferFreeBytes() const {
    return static_cast<int>(ringBuffer_.freeBytes());
}

// ═══ Real-Time Thread Scheduling & Isochronous Loop ═══
bool UsbAudioDriver::setCallingThreadHighPerf(bool enabled) {
    if (enabled) {
        const BigCoreTopology& topo = detectBigCoreTopology();
        bool pinned = false;
        // Only pin the decoder/producer thread onto big cores when >= 4 big/prime cores exist
        // (e.g. 1+3+4 or 4+4 SoCs). On 2+6 SoCs such as MediaTek MT6833, keeping the decoder on
        // all cores (while still elevating nice) avoids starving UsbDacIsoThread on the 2 big
        // cores during UI Touch Boost / RenderThread bursts.
        if (topo.valid && topo.bigCoreCount >= 4) {
            uint64_t maskBits = 0;
            int coreCount = 0;
            pinned = pinCurrentThreadToBigCores(maskBits, coreCount);
        } else {
            restoreCurrentThreadAllCores();
        }
        bool niceApplied = false;
        static constexpr int DECODER_NICE_LEVELS[] = { -19, -16, -10 };
        for (int level : DECODER_NICE_LEVELS) {
            if (setpriority(PRIO_PROCESS, 0, level) == 0) {
                niceApplied = true;
                break;
            }
        }
        return pinned || niceApplied;
    }
    restoreCurrentThreadAllCores();
    return true;
}

void UsbAudioDriver::promoteRealtimeThreadPriority() {
    pthread_setname_np(pthread_self(), "UsbDacIsoThread");

    bool gotSchedFifo = false;
    const int minPrio = sched_get_priority_min(SCHED_FIFO);
    const int maxPrio = sched_get_priority_max(SCHED_FIFO);
    if (minPrio >= 0 && maxPrio >= minPrio) {
        struct sched_param schedParam{};
        schedParam.sched_priority = std::clamp(minPrio + 2, minPrio, maxPrio);
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &schedParam) == 0) {
            gotSchedFifo = true;
            schedFifoActive_.store(true, std::memory_order_release);
            appliedNice_.store(-20, std::memory_order_release);
        }
    }

    if (!gotSchedFifo) {
        // Fallback when unprivileged Android SELinux policy restricts CAP_SYS_NICE:
        // step down from -20 -> -19 (ANDROID_PRIORITY_URGENT_AUDIO) -> -16 (AUDIO) -> -10
        // so ROMs capping RLIMIT_NICE at 39 (-19) never fail silently and leave the thread at nice 0.
        schedFifoActive_.store(false, std::memory_order_release);
        static constexpr int NICE_LEVELS[] = { -20, -19, -16, -10 };
        int applied = 0;
        for (int level : NICE_LEVELS) {
            if (setpriority(PRIO_PROCESS, 0, level) == 0) {
                applied = level;
                break;
            }
        }
        appliedNice_.store(applied, std::memory_order_release);
    }

    // Only pin UsbDacIsoThread onto big cores when we hold SCHED_FIFO or the SoC has >= 4
    // big/prime cores. On 2+6 / 2+4 SoCs without SCHED_FIFO (e.g. msm8952, mt6833), hard-pinning
    // to 2 big cores forces CFS contention with UI/RenderThread/ART JIT; leaving all cores
    // eligible at nice -20 avoids 16-33 ms scheduling queues.
    const BigCoreTopology& topo = detectBigCoreTopology();
    if (topo.valid && (gotSchedFifo || topo.bigCoreCount >= 4)) {
        uint64_t maskBits = 0;
        int coreCount = 0;
        if (pinCurrentThreadToBigCores(maskBits, coreCount)) {
            isoBigCoreMask_.store(maskBits, std::memory_order_release);
            isoBigCoreCount_.store(coreCount, std::memory_order_release);
            return;
        }
    }
    restoreCurrentThreadAllCores();
    isoBigCoreMask_.store(0, std::memory_order_release);
    isoBigCoreCount_.store(0, std::memory_order_release);
}

void UsbAudioDriver::streamingLoop() {
    promoteRealtimeThreadPriority();
    streamingTid_.store(static_cast<int>(syscall(SYS_gettid)), std::memory_order_release);

    if (!transport_.isAttached()) {
        running_.store(false, std::memory_order_release);
        streamingTid_.store(0, std::memory_order_release);
        return;
    }

    const int sampleRate = activeSampleRate_.load(std::memory_order_acquire);
    const int channels = activeChannels_.load(std::memory_order_acquire);
    const int subslot = activeSubslotSize_.load(std::memory_order_acquire);
    const OutputTransportMode outMode = static_cast<OutputTransportMode>(
        activeOutputMode_.load(std::memory_order_acquire)
    );
    const size_t frameBytes = static_cast<size_t>(channels) * static_cast<size_t>(subslot);
    if (sampleRate <= 0 || frameBytes == 0) {
        running_.store(false, std::memory_order_release);
        streamingTid_.store(0, std::memory_order_release);
        return;
    }

    int numOutUrbs = transport_.numOutUrbs();
    const int numFbUrbs = transport_.numFeedbackUrbs();
    int packetsPerUrb = std::min(transport_.packetsPerOutUrb(), MAX_PACKETS_PER_URB_STACK);
    const size_t maxPacketBytes = transport_.maxOutPacketBytes();
    const int maxPacketFrames = static_cast<int>(maxPacketBytes / frameBytes);
    const bool isDsdWire = isDsdWireMode(outMode);
    const bool isDsdFamily = isDsdWire || (outMode == OutputTransportMode::DSD_TO_PCM);
    const bool isHiResPcm = !isDsdFamily && (sampleRate > 48000 || activeBitDepth_.load(std::memory_order_acquire) >= 24);
    const int prebufMs = isDsdFamily
        ? DSD_PREBUFFER_MS
        : (isHiResPcm ? PREBUFFER_MS_HIRES : PREBUFFER_MS_STANDARD);
    const int rebufMs = isDsdFamily
        ? DSD_REBUFFER_AFTER_UNDERRUN_MS
        : (isHiResPcm ? REBUFFER_MS_HIRES : REBUFFER_MS_STANDARD);
    const size_t prebufferBytes = std::min<size_t>(
        ringBuffer_.capacity() / 2u,
        static_cast<size_t>((static_cast<int64_t>(sampleRate) * prebufMs / 1000LL) *
                            static_cast<int64_t>(frameBytes))
    );
    const size_t rebufferBytes = std::min<size_t>(
        prebufferBytes,
        static_cast<size_t>((static_cast<int64_t>(sampleRate) * rebufMs / 1000LL) *
                            static_cast<int64_t>(frameBytes))
    );
    bool rebufferingAfterUnderrun = false;

    // Pre-fill allocated URB payload buffers cleanly: 0x00 for pure PCM, 0x69 strictly for Native DSD
    {
        const size_t cap = transport_.getOutUrbPayloadCapacity();
        const uint8_t initByte = (outMode == OutputTransportMode::NATIVE_DSD) ? DSD_SILENCE_BYTE : 0u;
        for (int i = 0; i < numOutUrbs; ++i) {
            uint8_t* p = transport_.getOutUrbPayload(i);
            if (p && cap > 0) {
                std::memset(p, initByte, cap);
            }
        }
    }

    uint8_t dopMarkerNext = DOP_MARKER_EVEN;
    uint32_t packetLengths[MAX_PACKETS_PER_URB_STACK]{};
    int64_t lastUnderrunLogMs = 0;

    auto writeSilence = [&](uint8_t* dst, size_t bytes) {
        if (!isDsdWire) {
            // Isolated pure PCM silence path: zero DSD/DoP logic or 0x69 bytes ever touched
            std::memset(dst, 0, bytes);
        } else {
            fillFormatSilence(dst, bytes, outMode, channels, subslot, dopMarkerNext);
        }
    };

    auto fillAndSubmitOutUrb = [&](int urbIdx) -> bool {
        uint8_t* const payload = transport_.getOutUrbPayload(urbIdx);
        if (!payload) return false;

        const bool isPaused = paused_.load(std::memory_order_acquire);
        const bool isSwMuted = softwareMuted_.load(std::memory_order_acquire);
        size_t bufOffset = 0;

        for (int p = 0; p < packetsPerUrb; ++p) {
            // Apply any queued producer flush per packet BEFORE evaluating prebufferBytes so
            // pre-seek ring bytes can never falsely clear prebuffering_ or bleed into wire URBs.
            if (ringBuffer_.applyPendingFlush()) {
                prebuffering_.store(true, std::memory_order_release);
                rebufferingAfterUnderrun = false;
            }
            const int packetFrames = clockSync_.nextPacketFrameCount(maxPacketFrames);
            const size_t packetBytes = static_cast<size_t>(packetFrames) * frameBytes;
            packetLengths[p] = static_cast<uint32_t>(packetBytes);

            uint8_t* const dst = payload + bufOffset;
            const int warmRem = warmUpPacketsRemaining_.load(std::memory_order_relaxed);
            if (warmRem > 0) {
                warmUpPacketsRemaining_.store(warmRem - 1, std::memory_order_relaxed);
                writeSilence(dst, packetBytes);
            } else if (isPaused) {
                int fadeOutRem = (!isDsdWire) ? fadeOutFramesRemaining_.load(std::memory_order_relaxed) : 0;
                if (fadeOutRem > 0 && !isSwMuted && !prebuffering_.load(std::memory_order_acquire)) {
                    const size_t maxFadeBytes = static_cast<size_t>(fadeOutRem) * frameBytes;
                    const size_t wantBytes = std::min(packetBytes, maxFadeBytes);
                    const size_t copied = ringBuffer_.readAligned(dst, wantBytes, frameBytes);
                    if (copied > 0) {
                        framesWritten_.fetch_add(
                            static_cast<int64_t>(copied / frameBytes),
                            std::memory_order_relaxed
                        );
                        const int fadeTot = fadeOutFramesTotal_.load(std::memory_order_relaxed);
                        applyPcmMicroFade(dst, copied, channels, subslot, fadeTot, fadeOutRem, true);
                        fadeOutFramesRemaining_.store(fadeOutRem, std::memory_order_relaxed);
                        if (copied < packetBytes) {
                            writeSilence(dst + copied, packetBytes - copied);
                            if (copied < wantBytes) {
                                fadeOutFramesRemaining_.store(0, std::memory_order_relaxed);
                            }
                        }
                    } else {
                        fadeOutFramesRemaining_.store(0, std::memory_order_relaxed);
                        writeSilence(dst, packetBytes);
                    }
                } else {
                    if (fadeOutRem > 0) {
                        fadeOutFramesRemaining_.store(0, std::memory_order_relaxed);
                    }
                    writeSilence(dst, packetBytes);
                }
            } else {
                bool isPrebufferingNow = prebuffering_.load(std::memory_order_acquire);
                if (isPrebufferingNow) {
                    rebufferingAfterUnderrun = false;
                    const size_t avail = ringBuffer_.availableBytesConsumer();
                    if (avail >= prebufferBytes || endOfStream_.load(std::memory_order_acquire)) {
                        prebuffering_.store(false, std::memory_order_release);
                        isPrebufferingNow = false;
                    }
                } else if (rebufferingAfterUnderrun) {
                    const size_t avail = ringBuffer_.availableBytesConsumer();
                    if (avail >= rebufferBytes || endOfStream_.load(std::memory_order_acquire)) {
                        rebufferingAfterUnderrun = false;
                    }
                }

                size_t copied = 0;
                if (!isPrebufferingNow && !rebufferingAfterUnderrun) {
                    copied = ringBuffer_.readAligned(dst, packetBytes, frameBytes);
                    if (copied > 0) {
                        framesWritten_.fetch_add(
                            static_cast<int64_t>(copied / frameBytes),
                            std::memory_order_relaxed
                        );
                    }
                }

                if (isSwMuted) {
                    // Software-muted path (no HW volume): advance ringBuffer/framesWritten normally
                    // while emitting format-accurate silence (0x69 / DoP / 0x00) on the USB wire.
                    writeSilence(dst, packetBytes);
                } else {
                    if (copied > 0 && !isDsdWire) {
                        int fadeRem = fadeInFramesRemaining_.load(std::memory_order_relaxed);
                        if (fadeRem > 0) {
                            const int fadeTot = fadeInFramesTotal_.load(std::memory_order_relaxed);
                            applyPcmMicroFade(dst, copied, channels, subslot, fadeTot, fadeRem, false);
                            fadeInFramesRemaining_.store(fadeRem, std::memory_order_relaxed);
                        }
                    }

                    if (copied > 0 && outMode == OutputTransportMode::DOP && copied >= frameBytes && subslot >= 3) {
                        // Synchronize DoP 0x05/0xFA marker continuity across silence/underrun -> audio
                        // transitions so two consecutive identical markers never break DAC DoP lock.
                        const size_t readFrames = copied / frameBytes;
                        const size_t markerOff = static_cast<size_t>(subslot - 1);
                        for (size_t f = 0; f < readFrames; ++f) {
                            uint8_t* framePtr = dst + f * frameBytes;
                            const uint8_t expected = dopMarkerNext;
                            dopMarkerNext = (expected == DOP_MARKER_EVEN) ? DOP_MARKER_ODD : DOP_MARKER_EVEN;
                            if (framePtr[markerOff] != expected) {
                                rtTelemetry_.dopMarkerResyncCount.fetch_add(1, std::memory_order_relaxed);
                                for (int c = 0; c < channels; ++c) {
                                    framePtr[static_cast<size_t>(c) * static_cast<size_t>(subslot) + markerOff] = expected;
                                }
                            }
                        }
                    }

                    if (copied < packetBytes) {
                        if (!isPrebufferingNow && !rebufferingAfterUnderrun && !endOfStream_.load(std::memory_order_acquire)) {
                            const int64_t totalUnderruns = underrunCount_.fetch_add(1, std::memory_order_relaxed) + 1;
                            const int64_t nowUms = monotonicNowMs();
                            if (nowUms - lastUnderrunLogMs >= UNDERRUN_LOG_COOLDOWN_MS) {
                                lastUnderrunLogMs = nowUms;
                                rtTelemetry_.recordUnderrunRt(totalUnderruns, copied, packetBytes);
                            }
                            // Engage brief rebuffer cushion after an underrun so background recovery
                            // doesn't produce fragmented microframe-by-microframe crackling.
                            if (rebufferBytes > packetBytes) {
                                rebufferingAfterUnderrun = true;
                            }
                        }
                        writeSilence(dst + copied, packetBytes - copied);
                    }
                }
            }
            bufOffset += packetBytes;
        }

        int submitErr = 0;
        if (!transport_.submitOutUrb(urbIdx, packetLengths, packetsPerUrb, submitErr)) {
            lastSubmitErrno_.store(submitErr, std::memory_order_relaxed);
            submitErrCount_.fetch_add(1, std::memory_order_relaxed);
            if (isDisconnectErrno(submitErr)) {
                deviceDisconnected_.store(true, std::memory_order_release);
                if (running_.exchange(false, std::memory_order_acq_rel)) {
                    rtTelemetry_.recordDisconnectRt(1, submitErr);
                }
            } else {
                rtTelemetry_.recordSubmitErrRt(urbIdx, submitErr);
            }
            return false;
        }
        return true;
    };

    auto applyPendingFeedbackReset = [&]() {
        if (accumulatorResetRequested_.exchange(false, std::memory_order_acq_rel)) {
            clockSync_.resetAccumulatorOnly();
        }
        if (feedbackResetRequested_.exchange(false, std::memory_order_acq_rel)) {
            if (transport_.isFeedbackInFlight()) {
                transport_.discardAllFeedbackUrbs();
                if (transport_.isFeedbackInFlight()) {
                    clockSync_.resetAfterDiscard();
                } else {
                    clockSync_.reset();
                }
            } else {
                clockSync_.reset();
            }
        }
    };

    auto submitFeedbackSlot = [&](int fbSlot, int64_t nowMs) -> bool {
        if (fbSlot < 0 || fbSlot >= numFbUrbs || transport_.isFeedbackSlotInFlight(fbSlot)) {
            return false;
        }
        int fbErr = 0;
        if (transport_.submitFeedbackUrb(fbSlot, fbErr)) {
            clockSync_.onFeedbackSubmitted(nowMs);
            return true;
        }
        rtTelemetry_.fbSubmitErrCount.fetch_add(1, std::memory_order_relaxed);
        if (clockSync_.onFeedbackSubmitError(nowMs, fbErr)) {
            rtTelemetry_.recordWatchdogRt(1, fbErr, 0, 0u);
        }
        return false;
    };

    int64_t loopStartMs = 0;
    auto fillFeedbackPoolIfReady = [&](int64_t nowMs) {
        applyPendingFeedbackReset();
        if (numFbUrbs <= 0) return;
        const int warmRem = warmUpPacketsRemaining_.load(std::memory_order_relaxed);
        if (warmRem > 0) {
            // Let ISO OUT silence wake the DAC clock/FIFO first, then prime Feedback IN during
            // the remainder of warm-up so any +/- 5,000 ppm FIFO pull-in settles silently.
            if (loopStartMs == 0 || (nowMs - loopStartMs) < FEEDBACK_WARMUP_SETTLE_MS) {
                return;
            }
        } else if (prebuffering_.load(std::memory_order_relaxed)) {
            return;
        }
        if (!clockSync_.shouldSubmitFeedback(nowMs, paused_.load(std::memory_order_relaxed))) {
            return;
        }
        // Keep all 4..8 Feedback IN URBs continuously in-flight on ep_fb so the kernel never
        // misses a polling slot on MediaTek/MIUI.
        for (int s = 0; s < numFbUrbs; ++s) {
            if (!transport_.isFeedbackSlotInFlight(s)) {
                if (!submitFeedbackSlot(s, nowMs)) {
                    break;
                }
            }
        }
    };

    // Prime overlapping Isochronous OUT URB pipeline.
    // On MediaTek platforms where we optimistically requested a 48-64 ms pipeline (384-512 HS pkts),
    // dynamically shrink the active subset of the pre-allocated URB pool to legacy MUSB-safe 13x16
    // (26 ms, 208 <= 224 QMU TX limit) without heap allocation, pool mutation, or RT logging.
    uint32_t outSlotInFlightMask = 0u;
    int activeOutUrbs = 0;
    for (int i = 0; i < numOutUrbs; ++i) {
        if (fillAndSubmitOutUrb(i)) {
            outSlotInFlightMask |= (1u << static_cast<unsigned>(i));
            activeOutUrbs++;
        } else {
            const int primeErr = lastSubmitErrno_.load(std::memory_order_relaxed);
            const bool canFallbackMtk =
                isMediaTekPlatform() &&
                transport_.isHighSpeed() &&
                !mtkQmuLimited_.load(std::memory_order_acquire) &&
                isUrbPoolLimitErrno(primeErr) &&
                (numOutUrbs * packetsPerUrb > MEDIATEK_MUSB_MAX_INFLIGHT_HS_PACKETS);
            if (canFallbackMtk) {
                transport_.discardAllInFlightUrbs();
                mtkQmuLimited_.store(true, std::memory_order_release);
                const double pps = clockSync_.packetsPerSecond();
                const int safePkts = std::clamp(
                    static_cast<int>(std::llround((2.0 / 1000.0) * pps)),
                    2,
                    USB_PACKETS_PER_URB_HS
                );
                const int maxByQmu = std::max(USB_MIN_ISO_URBS, MEDIATEK_MUSB_MAX_INFLIGHT_HS_PACKETS / safePkts);
                const int safeUrbs = std::clamp(maxByQmu, USB_MIN_ISO_URBS, USB_MEDIATEK_MAX_ISO_URBS);
                // Reuse the already pre-allocated URB headers & payloads (safeUrbs <= numOutUrbs,
                // safePkts <= packetsPerUrb) so zero heap allocation or poolConfig_ mutation occurs on RT thread.
                numOutUrbs = std::min(numOutUrbs, safeUrbs);
                packetsPerUrb = std::min(packetsPerUrb, safePkts);
                const int64_t newLatUs = (pps > 0.0)
                    ? static_cast<int64_t>(std::llround(
                        (static_cast<double>(numOutUrbs) * static_cast<double>(packetsPerUrb) / pps) * 1e6))
                    : 26000LL;
                pipelineLatencyUs_.store(std::max<int64_t>(1000LL, newLatUs), std::memory_order_release);
                const size_t cap = transport_.getOutUrbPayloadCapacity();
                const uint8_t initByte = (outMode == OutputTransportMode::NATIVE_DSD) ? DSD_SILENCE_BYTE : 0u;
                for (int u = 0; u < numOutUrbs; ++u) {
                    uint8_t* p = transport_.getOutUrbPayload(u);
                    if (p && cap > 0) std::memset(p, initByte, cap);
                }
                submitErrCount_.store(0, std::memory_order_relaxed);
                lastSubmitErrno_.store(0, std::memory_order_relaxed);
                rtTelemetry_.recordMtkFallbackRt(primeErr, numOutUrbs, packetsPerUrb, newLatUs);
                outSlotInFlightMask = 0u;
                activeOutUrbs = 0;
                for (int r = 0; r < numOutUrbs; ++r) {
                    if (fillAndSubmitOutUrb(r)) {
                        outSlotInFlightMask |= (1u << static_cast<unsigned>(r));
                        activeOutUrbs++;
                    } else {
                        break;
                    }
                }
            }
            break;
        }
    }

    int64_t lastLoopTickMs = monotonicNowMs();
    loopStartMs = lastLoopTickMs;
    int64_t lastStallLogMs = 0;
    int64_t nextOutSlotRetryMs = 0;
    int64_t emaWorkNs = 0;
    bool hasActiveStreamingTick = false;
    fillFeedbackPoolIfReady(lastLoopTickMs);

    while (running_.load(std::memory_order_acquire) && activeOutUrbs > 0) {
        applyPendingFeedbackReset();
        short revents = 0;
        const int pollRet = transport_.pollUrbs(transport_.isFeedbackInFlight(), POLL_TIMEOUT_MS, revents);

        const int64_t workStartNs = monotonicNowNs();
        const int64_t nowMs = workStartNs / 1000000LL;
        const int64_t gapMs = nowMs - lastLoopTickMs;
        lastLoopTickMs = nowMs;

        const bool isPausedNow = paused_.load(std::memory_order_relaxed);
        const bool isPrebufferingNow = prebuffering_.load(std::memory_order_relaxed);
        if (!isPausedNow && !isPrebufferingNow) {
            if (hasActiveStreamingTick && gapMs >= 0) {
                int64_t prevMaxGap = maxUrbGapMs_.load(std::memory_order_relaxed);
                while (gapMs > prevMaxGap &&
                       !maxUrbGapMs_.compare_exchange_weak(prevMaxGap, gapMs, std::memory_order_relaxed)) {}
                if (gapMs >= JITTER_THRESHOLD_MS) {
                    jitterCount_.fetch_add(1, std::memory_order_relaxed);
                }
            }
            hasActiveStreamingTick = true;
        } else {
            hasActiveStreamingTick = false;
        }

        if (gapMs >= STALL_THRESHOLD_MS && !isPausedNow) {
            stallCount_.fetch_add(1, std::memory_order_relaxed);
            int64_t prevMax = maxStallMs_.load(std::memory_order_relaxed);
            while (gapMs > prevMax &&
                   !maxStallMs_.compare_exchange_weak(prevMax, gapMs, std::memory_order_relaxed)) {}
            if (nowMs - lastStallLogMs >= STALL_LOG_COOLDOWN_MS) {
                lastStallLogMs = nowMs;
                rtTelemetry_.recordStallRt(gapMs, ringBuffer_.availableBytesConsumer());
            }
        }

        if (pollRet < 0) {
            if (errno == EINTR) continue;
            deviceDisconnected_.store(true, std::memory_order_release);
            if (running_.exchange(false, std::memory_order_acq_rel)) {
                rtTelemetry_.recordDisconnectRt(2, errno);
            }
            break;
        }

        if ((revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            deviceDisconnected_.store(true, std::memory_order_release);
            if (running_.exchange(false, std::memory_order_acq_rel)) {
                rtTelemetry_.recordDisconnectRt(2, static_cast<int>(revents));
            }
            break;
        }

        bool processedUrb = false;
        if (pollRet > 0) {
            bool deviceGone = false;
            while (true) {
                UsbReapedUrb event;
                int reapErr = 0;
                const int reapStatus = transport_.reapUrbNonBlocking(event, reapErr);
                if (reapStatus == 0) break;
                if (reapStatus < 0) {
                    if (isDisconnectErrno(reapErr)) {
                        deviceDisconnected_.store(true, std::memory_order_release);
                        if (running_.exchange(false, std::memory_order_acq_rel)) {
                            rtTelemetry_.recordDisconnectRt(3, reapErr);
                        }
                        deviceGone = true;
                    }
                    break;
                }

                processedUrb = true;
                if (event.isFeedback) {
                    applyPendingFeedbackReset();
                    bool firstLock = false;
                    bool watchdogTripped = false;
                    bool relocked = false;
                    clockSync_.onFeedbackPacketReceived(
                        event.feedbackData,
                        event.packet0ActualLength,
                        event.status,
                        event.packet0Status,
                        nowMs,
                        firstLock,
                        watchdogTripped,
                        &relocked
                    );
                    if (firstLock || relocked) {
                        const auto snap = clockSync_.snapshot();
                        rtTelemetry_.recordFbLockRt(
                            static_cast<int64_t>(std::llround(clockSync_.nominalSamplesPerPacket() * 1e6)),
                            static_cast<int64_t>(std::llround(clockSync_.currentSamplesPerPacket() * 1e6)),
                            snap.driftPpmX10
                        );
                    } else if (watchdogTripped) {
                        rtTelemetry_.recordWatchdogRt(
                            2, event.status, event.packet0Status, event.packet0ActualLength
                        );
                    }
                    // Immediately resubmit reaped Feedback IN slot to keep 4..8 URBs continuously in-flight
                    if (running_.load(std::memory_order_acquire) &&
                        clockSync_.shouldSubmitFeedback(nowMs, paused_.load(std::memory_order_relaxed))) {
                        submitFeedbackSlot(event.urbIndex, nowMs);
                    }
                } else {
                    const bool validSlot = (event.urbIndex >= 0 && event.urbIndex < numOutUrbs);
                    const uint32_t slotBit = validSlot ? (1u << static_cast<unsigned>(event.urbIndex)) : 0u;
                    if (slotBit != 0u && (outSlotInFlightMask & slotBit) != 0u) {
                        outSlotInFlightMask &= ~slotBit;
                        if (activeOutUrbs > 0) activeOutUrbs--;
                    } else if (activeOutUrbs > 0) {
                        activeOutUrbs--;
                    }
                    if (running_.load(std::memory_order_acquire) && validSlot) {
                        if (fillAndSubmitOutUrb(event.urbIndex)) {
                            outSlotInFlightMask |= slotBit;
                            activeOutUrbs++;
                            nextOutSlotRetryMs = 0;
                        } else {
                            nextOutSlotRetryMs = nowMs + OUT_URB_RETRY_BACKOFF_MS;
                        }
                    }
                }
            }
            if (deviceGone) break;
        }

        // Recover any ISO OUT URB slots that previously failed with a transient non-disconnect
        // submit error so the in-flight pipeline never shrinks permanently down to underruns.
        if (running_.load(std::memory_order_acquire) &&
            activeOutUrbs > 0 &&
            activeOutUrbs < numOutUrbs &&
            nowMs >= nextOutSlotRetryMs) {
            for (int u = 0; u < numOutUrbs && running_.load(std::memory_order_acquire); ++u) {
                const uint32_t slotBit = 1u << static_cast<unsigned>(u);
                if ((outSlotInFlightMask & slotBit) != 0u) continue;
                if (fillAndSubmitOutUrb(u)) {
                    outSlotInFlightMask |= slotBit;
                    activeOutUrbs++;
                    rtTelemetry_.outUrbRecoveryCount.fetch_add(1, std::memory_order_relaxed);
                } else {
                    nextOutSlotRetryMs = nowMs + OUT_URB_RETRY_BACKOFF_MS;
                    break;
                }
            }
        }

        // Guard against completely hung Feedback IN pipe while holding locked crystal rate (flywheel)
        if (clockSync_.isFeedbackTimedOut(nowMs, transport_.feedbackInFlightCount())) {
            rtTelemetry_.fbTimeoutCount.fetch_add(1, std::memory_order_relaxed);
            transport_.discardAllFeedbackUrbs();
            if (clockSync_.onFeedbackTimeout(nowMs, transport_.isFeedbackInFlight())) {
                rtTelemetry_.recordWatchdogRt(3, 0, 0, 0u);
            }
        }

        fillFeedbackPoolIfReady(nowMs);

        // Record actual RT refill cycle duration (ns) for ADPF PowerHAL without locks or allocations
        if (processedUrb) {
            const int64_t cycleNs = std::max<int64_t>(1000LL, monotonicNowNs() - workStartNs);
            emaWorkNs = (emaWorkNs == 0) ? cycleNs : ((emaWorkNs * 3LL + cycleNs) / 4LL);
            lastWorkDurationNs_.store(emaWorkNs, std::memory_order_relaxed);
        }
    }

    if (activeOutUrbs <= 0 && running_.load(std::memory_order_acquire)) {
        // All URBs failed to submit/resubmit while still expected to run; flag disconnect to prevent hang
        deviceDisconnected_.store(true, std::memory_order_release);
        running_.store(false, std::memory_order_release);
    }

    transport_.discardAllInFlightUrbs();
    streamingTid_.store(0, std::memory_order_release);
    schedFifoActive_.store(false, std::memory_order_release);
    lastWorkDurationNs_.store(0, std::memory_order_release);
}

// ═══ Diagnostics & Native Logs ═══
void UsbAudioDriver::nativeLogSinkCallback(char level, const char* tag, const char* msg, void* ctx) {
    if (ctx != nullptr) {
        static_cast<UsbAudioDriver*>(ctx)->appendLogLine(level, tag, msg);
    }
}

void UsbAudioDriver::flushRtEventsToLogs() {
    PdRtEventSnapshot ev{};
    const uint32_t mask = rtTelemetry_.consumeEventsAndSnapshot(ev);
    if (mask == 0u) return;

    const int64_t gen = streamGeneration_.load(std::memory_order_relaxed);
    const bool watchdogPending = (mask & PdRtTelemetry::EVENT_WATCHDOG) != 0u;
    const bool lockPending = (mask & PdRtTelemetry::EVENT_FB_LOCK) != 0u;
    const ClockSyncSnapshot syncSnap = (watchdogPending || lockPending) ? clockSync_.snapshot() : ClockSyncSnapshot{};

    auto logWatchdogEvent = [&]() {
        if (ev.watchdogReason == 1) {
            DRV_LOGW("FB_WD_TRIP | gen=%lld reason=submit_err errno=%d",
                     static_cast<long long>(gen), ev.watchdogCodeA);
        } else if (ev.watchdogReason == 2) {
            DRV_LOGW("FB_WD_TRIP | gen=%lld reason=pkt_err urbSt=%d pktSt=%d len=%u",
                     static_cast<long long>(gen), ev.watchdogCodeA, ev.watchdogCodeB, ev.watchdogLen);
        } else if (ev.watchdogReason == 3) {
            DRV_LOGW("FB_WD_TRIP | gen=%lld reason=timeout", static_cast<long long>(gen));
        }
    };

    auto logLockEvent = [&](bool isRelock) {
        const double nominalSpp = static_cast<double>(ev.fbLockNominalU6) * 1e-6;
        const double actualSpp = static_cast<double>(ev.fbLockActualU6) * 1e-6;
        if (isRelock) {
            DRV_LOGI("FB_RELOCK | gen=%lld trips=%lld nom=%.6f act=%.6f driftPpm=%+.1f",
                     static_cast<long long>(gen),
                     static_cast<long long>(syncSnap.watchdogTrips),
                     nominalSpp,
                     actualSpp,
                     ev.fbLockDriftPpmX10 / 10.0);
        } else {
            DRV_LOGI("FB_LOCK | gen=%lld nom=%.6f act=%.6f driftPpm=%+.1f",
                     static_cast<long long>(gen),
                     nominalSpp,
                     actualSpp,
                     ev.fbLockDriftPpmX10 / 10.0);
        }
    };

    if (watchdogPending && lockPending) {
        if (syncSnap.feedbackLocked) {
            logWatchdogEvent();
            logLockEvent(true);
        } else {
            logLockEvent(false);
            logWatchdogEvent();
        }
    } else if (lockPending) {
        logLockEvent(syncSnap.watchdogTrips > 0);
    } else if (watchdogPending) {
        logWatchdogEvent();
    }
    if ((mask & PdRtTelemetry::EVENT_STALL) != 0u) {
        DRV_LOGW("ISO_STALL | gen=%lld gapMs=%lld ringAvailB=%zu",
                 static_cast<long long>(gen),
                 static_cast<long long>(ev.lastStallGapMs),
                 ev.lastStallRingAvail);
    }
    if ((mask & PdRtTelemetry::EVENT_UNDERRUN) != 0u) {
        DRV_LOGW("ISO_UNDERRUN | gen=%lld total=%lld copied=%zu expected=%zu ringAvailB=%d",
                 static_cast<long long>(gen),
                 static_cast<long long>(ev.lastUnderrunTotal),
                 ev.lastUnderrunCopied,
                 ev.lastUnderrunExpected,
                 getRingBufferAvailableBytes());
    }
    if ((mask & PdRtTelemetry::EVENT_MTK_FALLBACK) != 0u) {
        DRV_LOGW("MTK_QMU_FALLBACK | gen=%lld errno=%d urbs=%d pkts=%d latUs=%lld",
                 static_cast<long long>(gen),
                 ev.mtkFallbackErrno,
                 ev.mtkFallbackUrbs,
                 ev.mtkFallbackPkts,
                 static_cast<long long>(ev.mtkFallbackLatUs));
    }
    if ((mask & PdRtTelemetry::EVENT_SUBMIT_ERR) != 0u) {
        DRV_LOGE("ISO_SUBMIT_ERR | gen=%lld urb=%d errno=%d err=%s",
                 static_cast<long long>(gen),
                 ev.submitErrUrb, ev.submitErrCode, std::strerror(ev.submitErrCode));
    }
    if ((mask & PdRtTelemetry::EVENT_DISCONNECT) != 0u) {
        if (ev.disconnectSource == 1) {
            DRV_LOGW("DAC_DISCONNECT | gen=%lld src=submit errno=%d err=%s",
                     static_cast<long long>(gen), ev.disconnectErrno, std::strerror(ev.disconnectErrno));
        } else if (ev.disconnectSource == 2) {
            DRV_LOGW("DAC_DISCONNECT | gen=%lld src=poll revents=0x%x",
                     static_cast<long long>(gen), static_cast<unsigned int>(ev.disconnectErrno));
        } else if (ev.disconnectSource == 3) {
            DRV_LOGW("DAC_DISCONNECT | gen=%lld src=reap errno=%d err=%s",
                     static_cast<long long>(gen), ev.disconnectErrno, std::strerror(ev.disconnectErrno));
        }
    }
}

void UsbAudioDriver::appendLogLine(char level, const char* tag, const char* msg) {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tmNow{};
    localtime_r(&ts.tv_sec, &tmNow);

    char line[448];
    std::snprintf(
        line,
        sizeof(line),
        "%04d-%02d-%02d %02d:%02d:%02d.%03ld %c/%s: %s",
        tmNow.tm_year + 1900,
        tmNow.tm_mon + 1,
        tmNow.tm_mday,
        tmNow.tm_hour,
        tmNow.tm_min,
        tmNow.tm_sec,
        ts.tv_nsec / 1000000L,
        level,
        tag ? tag : PD_TAG_NATIVE_AUDIO,
        msg ? msg : ""
    );

    std::lock_guard<std::mutex> lock(logMutex_);
    if (nativeLogRing_.size() >= MAX_NATIVE_LOGS) {
        nativeLogRing_.pop_front();
    }
    nativeLogRing_.emplace_back(line);
}

std::vector<std::string> UsbAudioDriver::drainNativeLogs() {
    flushRtEventsToLogs();
    std::lock_guard<std::mutex> lock(logMutex_);
    std::vector<std::string> out;
    out.reserve(nativeLogRing_.size());
    while (!nativeLogRing_.empty()) {
        out.push_back(std::move(nativeLogRing_.front()));
        nativeLogRing_.pop_front();
    }
    return out;
}

std::string UsbAudioDriver::getDiagnosticsSummary() const {
    std::lock_guard<std::mutex> lock(controlMutex_);
    const ClockSyncSnapshot syncSnap = clockSync_.snapshot();
    const UacFeatureUnitInfo* activeFu = findActiveFeatureUnit(descInfo_);

    std::ostringstream oss;
    const bool attached = transport_.isAttached();
    const char* speedLabel = !attached
        ? "UNKNOWN"
        : (transport_.isHighSpeed() ? "HS(480M)" : "FS(12M)");
    oss << "attached=" << (attached ? "true" : "false")
        << ", path=" << (transport_.devicePath().empty() ? "none" : transport_.devicePath())
        << ", usbSpeed=" << speedLabel
        << ", uacVersion=0x" << std::hex << descInfo_.uacVersion << std::dec
        << (descInfo_.isUac3Badd ? "(BADD)" : "") << "\n";

    oss << "ctrlIface=" << descInfo_.controlInterface
        << ", clockId=" << descInfo_.clockSourceId
        << ", clockSel=" << descInfo_.clockSelectorId
        << ", featureUnitId=" << descInfo_.featureUnitId
        << ", maxCh=" << descInfo_.maxSupportedChannels
        << ", nativeDsd=" << (descInfo_.supportsNativeDsd ? "true" : "false")
        << ", dop=" << (descInfo_.supportsDop ? "true" : "false")
        << ", maxDsd=DSD" << descInfo_.maxDsdRate << "\n";

    oss << "hwVolume=" << (hasHardwareVolume_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", masterCh=" << (volumeMasterChannel_ ? "true" : "false")
        << ", masterVolAdv=" << ((activeFu && activeFu->hasMasterVolume) ? "true" : "false")
        << ", masterMuteAdv=" << ((activeFu && activeFu->hasMasterMute) ? "true" : "false")
        << ", rangeDb256=[" << volumeMinDb256_ << ".." << volumeMaxDb256_ << " step=" << volumeResDb256_ << "]"
        << " (" << (volumeMinDb256_ / 256.0) << ".." << (volumeMaxDb256_ / 256.0) << " dB)"
        << ", lastMuted=" << lastMutedState_
        << ", lastVolDb256=" << lastVolumeDb256_
        << ", swVolBypass=" << (dspPipeline_.isSoftwareVolumeBypassed() ? "true" : "false") << "\n";

    oss << "streamOpen=" << (streamOpen_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", running=" << (running_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", paused=" << (paused_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", disconnected=" << (deviceDisconnected_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", eos=" << (endOfStream_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", tid=" << streamingTid_.load(std::memory_order_relaxed)
        << ", schedFifo=" << (schedFifoActive_.load(std::memory_order_relaxed) ? "true" : "false")
        << ", nice=" << appliedNice_.load(std::memory_order_relaxed)
        << ", bigCores=0x" << std::hex << isoBigCoreMask_.load(std::memory_order_relaxed) << std::dec
        << "(" << isoBigCoreCount_.load(std::memory_order_relaxed) << ")"
        << ", pipeLatUs=" << pipelineLatencyUs_.load(std::memory_order_relaxed) << "\n";

    oss << "activeFormat=" << activeSampleRate_.load(std::memory_order_relaxed) << " Hz / "
        << activeBitDepth_.load(std::memory_order_relaxed) << "-bit (subslot="
        << activeSubslotSize_.load(std::memory_order_relaxed) << "B) / "
        << activeChannels_.load(std::memory_order_relaxed) << " ch"
        << ", mode=" << outputModeName(static_cast<OutputTransportMode>(activeOutputMode_.load(std::memory_order_relaxed)))
        << ", dspFlags=0x" << std::hex << activeDspFlags_.load(std::memory_order_relaxed) << std::dec
        << ", activeIface=" << activePlan_.selectedAlt.interfaceNumber
        << ", activeAlt=" << activePlan_.selectedAlt.alternateSetting
        << ", activeEp=0x" << std::hex << static_cast<int>(activePlan_.selectedAlt.outEndpoint) << std::dec << "\n";

    oss << "clockSync=" << clockSyncModeName(syncSnap.activeMode)
        << ", fbLocked=" << (syncSnap.feedbackLocked ? "true" : "false")
        << ", fbValid=" << syncSnap.validFeedbackCount
        << ", fbErr=" << syncSnap.errorFeedbackCount
        << ", fbOutliers=" << syncSnap.outlierFeedbackCount
        << ", watchdogTrips=" << syncSnap.watchdogTrips
        << ", fbDriftPpm=" << (syncSnap.driftPpmX10 / 10.0) << "\n";

    oss << "framesWritten=" << framesWritten_.load(std::memory_order_relaxed)
        << ", ringAvail=" << getRingBufferAvailableBytes() << " B"
        << ", ringFree=" << getRingBufferFreeBytes() << " B"
        << ", ringCap=" << ringBuffer_.capacity() << " B"
        << ", maxBuffered=" << maxBufferedBytes_.load(std::memory_order_relaxed) << " B"
        << ", underruns=" << underrunCount_.load(std::memory_order_relaxed)
        << ", stalls=" << stallCount_.load(std::memory_order_relaxed)
        << " (max=" << maxStallMs_.load(std::memory_order_relaxed) << "ms)"
        << ", jitter>=" << JITTER_THRESHOLD_MS << "ms=" << jitterCount_.load(std::memory_order_relaxed)
        << " (maxGap=" << maxUrbGapMs_.load(std::memory_order_relaxed) << "ms)"
        << ", submitErrors=" << submitErrCount_.load(std::memory_order_relaxed)
        << " (lastErrno=" << lastSubmitErrno_.load(std::memory_order_relaxed) << ")\n";

    oss << "supportedRates=[";
    for (size_t i = 0; i < descInfo_.supportedSampleRates.size(); ++i) {
        if (i > 0) oss << ", ";
        oss << descInfo_.supportedSampleRates[i];
    }
    oss << "]\n";

    oss << "altSettings(" << descInfo_.streamAltSettings.size() << "):\n";
    for (size_t i = 0; i < descInfo_.streamAltSettings.size(); ++i) {
        const auto& a = descInfo_.streamAltSettings[i];
        oss << "  [" << i << "] iface=" << a.interfaceNumber
            << " alt=" << a.alternateSetting
            << " type=" << (a.isDsd ? "DSD" : (a.isPcm ? "PCM" : "OTHER"))
            << " ch=" << a.channels
            << " bits=" << a.bitResolution
            << " subslot=" << a.subslotSize
            << " ep=0x" << std::hex << static_cast<int>(a.outEndpoint) << std::dec
            << " maxPkt=" << a.maxPacketSize
            << " intv=" << static_cast<int>(a.interval)
            << " sync=" << static_cast<int>(a.syncType)
            << " fbEp=0x" << std::hex << static_cast<int>(a.feedbackEndpoint) << std::dec
            << " fbUsage=" << static_cast<int>(a.feedbackUsageType)
            << " fbIntv=" << static_cast<int>(a.feedbackInterval)
            << " fbRef=" << static_cast<int>(a.feedbackRefresh)
            << " fbMaxPkt=" << static_cast<unsigned int>(a.feedbackMaxPacketSize)
            << " samFreqCtrl=" << (a.hasSamFreqControl ? 1 : 0);
        if (!a.fixedSampleRates.empty()) {
            oss << " fixedRates=[";
            for (size_t j = 0; j < a.fixedSampleRates.size(); ++j) {
                if (j > 0) oss << ",";
                oss << a.fixedSampleRates[j];
            }
            oss << "]";
        }
        oss << "\n";
    }
    return oss.str();
}