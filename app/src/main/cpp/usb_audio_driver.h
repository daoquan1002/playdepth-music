/**
 * usb_audio_driver — Orchestrator for direct UAC 1.0/2.0/3.0 USB DAC playback and RT streaming.
 *
 * @remarks
 * Combines raw UAC descriptor parsing, Linux `usbdevfs` Isochronous URB transport, async
 * clock synchronization, pure C++17 Hi-Fi fallback DSP, and a lock-free SPSC ring buffer.
 * Locking is strictly partitioned so the real-time `UsbDacIsoThread` never acquires any
 * mutex and never allocates heap memory:
 *   - `controlMutex_` guards device attach/detach, stream open/close, and Feature Unit USB
 *     control transfers (`setVolume`/`setMuted`).
 *   - `producerMutex_` guards `writeAudio()` and `dspPipeline_` reconfiguration so slow I2C
 *     Feature Unit volume writes never block decoder PCM/DSD feeding.
 *   - `streamingLoop()` communicates exclusively through `SpscRingBuffer` and `std::atomic`.
 *
 * @see uac_descriptor.h
 * @see usb_transport.h
 * @see clock_sync.h
 * @see audio_dsp_pipeline.h
 * @see spsc_ring_buffer.h
 * @see UsbDacNative
 */
#pragma once

// ═══ Includes ═══
#include "audio_dsp_pipeline.h"
#include "clock_sync.h"
#include "pd_log.h"
#include "spsc_ring_buffer.h"
#include "uac_descriptor.h"
#include "usb_transport.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// ═══ Driver Interface ═══
class UsbAudioDriver {
public:
    UsbAudioDriver() = default;
    ~UsbAudioDriver();

    UsbAudioDriver(const UsbAudioDriver&) = delete;
    UsbAudioDriver& operator=(const UsbAudioDriver&) = delete;

    // ─── Device Lifecycle ───
    bool attachDevice(int fd, const std::string& devicePath, const uint8_t* rawDescriptors, size_t descLen);
    void detachDevice();

    bool isAttached() const { return transport_.isAttached(); }
    UacDeviceDescriptorInfo getDescriptorInfo() const;

    // ─── Stream Lifecycle & Transport ───
    bool openStream(
        int sampleRate,
        int channels,
        int preferredBitDepth,
        AudioInputEncoding inputEncoding,
        int warmUpMs = 500
    );
    void closeStream();

    // Processes input PCM/DSD bytes through Smart-Bypass DSP into the SPSC ring buffer.
    // Returns exact number of bytes consumed from `inputData`.
    int writeAudio(const uint8_t* inputData, int inputBytes, AudioInputEncoding encoding);

    // Fused Float32 -> MSB-aligned 32-bit wire fast path (returns input float bytes consumed,
    // or -1 if stream is not eligible so caller falls back to standard restore + writeAudio).
    int writeFloatRestoredToWire32(const uint8_t* floatData, int inputBytes, AudioInputEncoding targetEncoding);

    void flush(int warmUpMs = 0);
    void setWarmUpMs(int warmUpMs);
    void setPaused(bool paused);
    void setEndOfStream(bool eos);

    // ─── DSD & Fallback DSP Preferences ───
    void setDsdMode(DsdPlaybackMode mode);
    void setDsdGainCompensationDb(float gainDb);
    void setDitherEnabled(bool enabled);
    void setResamplerQuality(ResamplerQuality quality);

    // ─── Hardware & 64-bit Software Volume Control ───
    void setVolume(float normalized01);
    void setMuted(bool muted);
    bool hasHardwareVolume() const { return hasHardwareVolume_.load(std::memory_order_acquire); }
    bool isSoftwareVolumeBypassed() const { return dspPipeline_.isSoftwareVolumeBypassed(); }
    void getHardwareVolumeRangeDb256(int& outMin, int& outMax, int& outRes) const;

    // ─── Active Format & Real-Time Telemetry ───
    int getActiveSampleRate() const { return activeSampleRate_.load(std::memory_order_acquire); }
    int getActiveBitDepth() const { return activeBitDepth_.load(std::memory_order_acquire); }
    int getActiveSubslotSize() const { return activeSubslotSize_.load(std::memory_order_acquire); }
    int getActiveChannels() const { return activeChannels_.load(std::memory_order_acquire); }
    bool isHighSpeed() const { return transport_.isHighSpeed(); }
    bool isDeviceDisconnected() const { return deviceDisconnected_.load(std::memory_order_acquire); }
    bool isWarmingUp() const {
        return running_.load(std::memory_order_acquire) &&
               (warmUpPacketsRemaining_.load(std::memory_order_acquire) > 0 ||
                prebuffering_.load(std::memory_order_acquire));
    }
    int64_t getPipelineLatencyUs() const { return pipelineLatencyUs_.load(std::memory_order_acquire); }
    int64_t getLastWorkDurationNs() const;
    OutputTransportMode getActiveOutputMode() const {
        return static_cast<OutputTransportMode>(activeOutputMode_.load(std::memory_order_acquire));
    }
    uint32_t getActiveDspFlags() const { return activeDspFlags_.load(std::memory_order_acquire); }
    ClockSyncMode getClockSyncMode() const { return clockSync_.snapshot().activeMode; }
    bool isFeedbackLocked() const { return clockSync_.snapshot().feedbackLocked; }
    int getClockDriftPpmX10() const { return clockSync_.snapshot().driftPpmX10; }
    int64_t getFramesWritten() const { return framesWritten_.load(std::memory_order_acquire); }
    int64_t getStreamGeneration() const { return streamGeneration_.load(std::memory_order_acquire); }
    int64_t getUnderrunCount() const { return underrunCount_.load(std::memory_order_acquire); }
    int64_t getStallCount() const { return stallCount_.load(std::memory_order_acquire); }
    int64_t getMaxStallMs() const { return maxStallMs_.load(std::memory_order_acquire); }
    int64_t getJitterCount() const { return jitterCount_.load(std::memory_order_acquire); }
    int64_t getMaxUrbGapMs() const { return maxUrbGapMs_.load(std::memory_order_acquire); }
    int getStreamingTid() const { return streamingTid_.load(std::memory_order_acquire); }
    int getRingBufferAvailableBytes() const;
    int getRingBufferFreeBytes() const;

    // ─── Diagnostics & Thread Affinity ───
    static bool setCallingThreadHighPerf(bool enabled);
    bool hasPendingRtEvents() const { return rtTelemetry_.hasPendingEvents(); }
    std::vector<std::string> drainNativeLogs();
    std::string getDiagnosticsSummary() const;

private:
    bool openStreamLocked(
        int sampleRate,
        int channels,
        int preferredBitDepth,
        AudioInputEncoding inputEncoding,
        int warmUpMs
    );
    void detachDeviceLocked();
    void closeStreamLocked(bool preserveTransitionMute = false);
    static void nativeLogSinkCallback(char level, const char* tag, const char* msg, void* ctx);
    void appendLogLine(char level, const char* tag, const char* msg);
    void flushRtEventsToLogs();
    useconds_t computeSilenceDrainUs() const;

    std::vector<int> queryUac2SampleRatesForClock(int clockId, bool isProgrammable);
    void queryAllUac2ClockSampleRates();
    void queryVolumeRange();
    bool setSampleRateUac2(int clockId, int sampleRate);
    bool setSampleRateUac1(uint8_t outEndpoint, int sampleRate);
    bool selectClockSelectorPin(int selectorId, int targetClockId);
    bool configureClockForStream(const NegotiatedStreamPlan& plan);
    bool setHardwareMuteDirectLocked(bool muted);
    void normalizeFeatureUnitChannelBalanceLocked();
    void verifyAndRepairFeatureUnitVolumeLocked(
        uint16_t wIndex,
        const std::vector<int>& channels,
        int targetDb256
    );
    void applyHardwareVolumeLocked(float normalized01, bool forceMuteOverride, bool muteOverrideValue);

    void promoteRealtimeThreadPriority();
    void streamingLoop();

    // ─── Subsystems ───
    UsbTransport transport_;
    UacDeviceDescriptorInfo descInfo_;
    ClockSyncEngine clockSync_;
    AudioDspPipeline dspPipeline_;
    SpscRingBuffer ringBuffer_;

    // ─── Hardware Volume & Device Control State ───
    std::atomic<bool> hasHardwareVolume_{false};
    bool volumeMasterChannel_ = true;
    int volumeMinDb256_ = -15360;
    int volumeMaxDb256_ = 0;
    int volumeResDb256_ = 256;
    int lastMutedState_ = -1;
    int lastVolumeDb256_ = INT32_MIN;
    float lastNormalizedVolume_ = 1.0f;
    bool explicitMuted_ = false;
    bool hardwareMutedForTransition_ = false;
    bool pendingPostStartVolumeSync_ = false;
    int64_t streamStartElapsedMs_ = 0;
    int64_t pausedAtElapsedMs_ = 0;

    // ─── Negotiated Stream & DSP Preferences ───
    DspUserOptions dspOptions_{};
    NegotiatedStreamPlan activePlan_{};
    UsbIsoPoolConfig activePoolConfig_{};
    StreamNegotiationRequest lastStreamRequest_{};
    int lastStreamWarmUpMs_ = 0;
    bool hasLastStreamRequest_ = false;
    int lastOpenedSampleRate_ = 0;
    int lastOpenedBitDepth_ = 0;
    OutputTransportMode lastOpenedOutputMode_ = OutputTransportMode::INACTIVE;
    std::atomic<bool> streamOpen_{false};

    // ─── Streaming & RT Telemetry Atomics ───
    std::atomic<int> activeSampleRate_{0};
    std::atomic<int> activeBitDepth_{0};
    std::atomic<int> activeSubslotSize_{0};
    std::atomic<int> activeChannels_{2};
    std::atomic<int> activeOutputMode_{static_cast<int>(OutputTransportMode::INACTIVE)};
    std::atomic<uint32_t> activeDspFlags_{DSP_STAGE_NONE};
    std::atomic<int64_t> framesWritten_{0};
    std::atomic<int64_t> streamGeneration_{0};
    std::atomic<int> streamingTid_{0};
    std::atomic<bool> schedFifoActive_{false};
    std::atomic<int> appliedNice_{0};
    std::atomic<uint64_t> isoBigCoreMask_{0};
    std::atomic<int> isoBigCoreCount_{0};
    std::atomic<int64_t> pipelineLatencyUs_{24000};
    std::atomic<int64_t> lastWorkDurationNs_{0};
    std::atomic<int64_t> lastProducerWorkDurationNs_{0};
    std::atomic<int64_t> lastProducerWorkTimeMs_{0};
    std::atomic<int64_t> underrunCount_{0};
    std::atomic<int64_t> submitErrCount_{0};
    std::atomic<int> lastSubmitErrno_{0};
    std::atomic<int64_t> stallCount_{0};
    std::atomic<int64_t> maxStallMs_{0};
    std::atomic<int64_t> jitterCount_{0};
    std::atomic<int64_t> maxUrbGapMs_{0};
    std::atomic<bool> running_{false};
    std::atomic<bool> paused_{true};
    std::atomic<bool> deviceDisconnected_{false};
    std::atomic<bool> softwareMuted_{false};
    std::atomic<bool> endOfStream_{false};
    std::atomic<int> warmUpMsPending_{0};
    std::atomic<int> configuredWarmUpMs_{0};
    std::atomic<int> warmUpPacketsRemaining_{0};
    std::atomic<int> fadeInFramesTotal_{0};
    std::atomic<int> fadeInFramesRemaining_{0};
    std::atomic<int> fadeOutFramesTotal_{0};
    std::atomic<int> fadeOutFramesRemaining_{0};
    std::atomic<bool> mtkQmuLimited_{false};
    std::atomic<bool> prebuffering_{true};
    std::atomic<bool> feedbackResetRequested_{false};
    std::atomic<bool> accumulatorResetRequested_{false};
    std::atomic<size_t> maxBufferedBytes_{512 * 1024};

    // ─── Lock-Free RT Telemetry & Non-Blocking Seqlock Event Latch ───
    PdRtTelemetry rtTelemetry_;

    // ─── Threading & Synchronization (Never Acquired by RT ISO Thread) ───
    std::thread streamThread_;
    mutable std::mutex controlMutex_;
    mutable std::mutex producerMutex_;
    mutable std::mutex logMutex_;
    std::deque<std::string> nativeLogRing_;
};