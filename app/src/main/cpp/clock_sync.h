/**
 * clock_sync — Asynchronous (10.14 / 16.16) feedback and Adaptive/Sync clock pacing engine.
 *
 * @remarks
 * Tracks the USB DAC's internal master crystal via continuous Feedback IN polling
 * with median-of-5 spike rejection and time-normalized EMA smoothing to eliminate periodic FIFO
 * slip ticks and phase-lag hunting without triggering MediaTek MUSB RX QMU empty-queue IRQ storms.
 * Uses a tight +/- 2,000 ppm crystal window for clean initial lock (with bounded gentle pull-in if
 * the DAC FIFO starts on a +/- 5,000 ppm rail), clamps steady-state pacing to +/- 0.5% inside a
 * +/- 0.75% outlier gate (tau ~ 350 ms, 1,500 ppm/s max slew), and probes gently (100 ms) in
 * `WATCHDOG_FALLBACK` to self-heal back to `ASYNC_FEEDBACK` once 5 consecutive valid packets pass
 * the median-of-5 filter.
 * All RT hot-path methods are lock-free and allocation-free.
 *
 * @see UsbAudioDriver
 * @see UsbTransport
 */
#pragma once

// ═══ Includes ═══
#include <atomic>
#include <cstddef>
#include <cstdint>

// ═══ Types & State ═══
enum class ClockSyncMode : int {
    NONE = 0,
    ASYNC_FEEDBACK = 1,
    ADAPTIVE = 2,
    SYNCHRONOUS = 3,
    WATCHDOG_FALLBACK = 4
};

enum class FeedbackDecodeKind : uint8_t {
    INVALID = 0,
    DIRECT = 1,
    INTERVAL_SCALED = 2,
    DIV8_QUIRK = 3,
    Q14_COMPAT = 4
};

struct ClockSyncConfig {
    int sampleRate = 48000;
    bool isHighSpeed = true;
    uint8_t outInterval = 1;
    uint8_t syncType = 0;           // 1 = Async, 2 = Adaptive, 3 = Sync
    bool hasExplicitFeedback = false;
    uint8_t feedbackInterval = 1;   // UAC 2.0/3.0 Feedback IN bInterval
    uint8_t feedbackRefresh = 0;    // UAC 1.0 Feedback IN bRefresh (2^bRefresh ms)
    int packetsPerFeedbackUrb = 1;  // ISO IN packet descriptors per reaped Feedback URB
    uint16_t feedbackMaxPacketSize = 0;
    uint16_t uacVersion = 0x0200u;
};

struct ClockSyncSnapshot {
    ClockSyncMode activeMode = ClockSyncMode::NONE;
    bool feedbackLocked = false;
    int driftPpmX10 = 0;
    int64_t validFeedbackCount = 0;
    int64_t errorFeedbackCount = 0;
    int64_t outlierFeedbackCount = 0;
    int64_t watchdogTrips = 0;
};

// ═══ Clock Synchronization Engine ═══
class ClockSyncEngine {
public:
    ClockSyncEngine() = default;

    // ─── Configuration (Control Thread Before RT Loop) ───
    void configure(const ClockSyncConfig& config);
    void reset();
    void clear();
    void resetAfterDiscard();
    void resetAccumulatorOnly();

    double packetsPerSecond() const { return packetsPerSecond_; }
    double nominalSamplesPerPacket() const { return nominalSamplesPerPacket_; }
    double currentSamplesPerPacket() const { return currentSamplesPerPacket_; }
    int computeWarmUpPackets(int warmUpMs) const;
    bool isFeedbackEnabled() const { return fbEnabled_; }

    // ─── Real-Time Audio Thread API (Zero Locks, Zero Allocations) ───
    int nextPacketFrameCount(int maxFrames);

    bool shouldSubmitFeedback(int64_t nowMs, bool paused) const;
    void onFeedbackSubmitted(int64_t nowMs);
    bool isFeedbackTimedOut(int64_t nowMs, int inFlightCount) const;
    bool onFeedbackTimeout(int64_t nowMs, bool expectCancelCompletion = true);
    bool onFeedbackSubmitError(int64_t nowMs, int errCode);
    bool isWatchdogFallbackActive() const { return fbWatchdogActive_; }

    /**
     * Processes a reaped Feedback IN packet. Sets `outFirstLock` on initial crystal lock,
     * `outWatchdogTripped` when entering `WATCHDOG_FALLBACK`, and optionally `outRelocked`
     * when self-healing back to `ASYNC_FEEDBACK` from `WATCHDOG_FALLBACK`.
     * Returns `true` when the packet updated `currentSamplesPerPacket_`.
     */
    bool onFeedbackPacketReceived(
        const uint8_t* data,
        uint32_t actualLength,
        int urbStatus,
        int pktStatus,
        int64_t nowMs,
        bool& outFirstLock,
        bool& outWatchdogTripped,
        bool* outRelocked = nullptr
    );

    // ─── Thread-Safe Telemetry Snapshot ───
    ClockSyncSnapshot snapshot() const;

    // ─── Pure Fixed-Point Decoder (10.14 UAC1 & 16.16 UAC2/3) ───
    static bool decodeFeedbackSamples(
        const uint8_t* data,
        uint32_t actualLength,
        bool isHighSpeed,
        uint8_t outInterval,
        double nominalSamplesPerPacket,
        double& outSamplesPerPacket,
        bool allowDiv8Quirk = true,
        double toleranceRatio = 0.01,
        FeedbackDecodeKind* outKind = nullptr
    );

    static double computePacketsPerSecond(bool isHighSpeed, uint8_t interval);

private:
    static constexpr int MEDIAN_WINDOW_SIZE = 5;

    void resetFeedbackLoopState(bool keepCanceling);
    void tripWatchdog(int64_t nowMs);

    ClockSyncConfig config_{};
    double packetsPerSecond_ = 8000.0;
    double nominalSamplesPerPacket_ = 6.0;
    double currentSamplesPerPacket_ = 6.0;
    double sampleAccumulator_ = 0.0;

    int64_t feedbackPeriodMs_ = 1;
    int64_t hangTimeoutMs_ = 2000;
    int warmupSkipTarget_ = 12;
    int wideLockConfirmTarget_ = 500;

    bool fbEnabled_ = false;
    bool fbWatchdogActive_ = false;
    bool fbCanceling_ = false;
    bool fbDirectSeen_ = false;
    int fbDiv8Streak_ = 0;
    int fbWarmupSkipped_ = 0;
    int fbWideLockStreak_ = 0;
    double fbMedianBuf_[MEDIAN_WINDOW_SIZE]{};
    int fbMedianCount_ = 0;
    int64_t fbValidLocal_ = 0;
    int fbErrorStreak_ = 0;
    int64_t lastFeedbackActivityMs_ = 0;
    int64_t lastFeedbackUpdateMs_ = 0;
    int64_t nextFeedbackSubmitMs_ = 0;
    int64_t fbZlpOnlySinceMs_ = 0;

    // Lock-free telemetry atomics readable from UI/JNI threads at any time
    std::atomic<int> activeMode_{static_cast<int>(ClockSyncMode::NONE)};
    std::atomic<bool> feedbackLocked_{false};
    std::atomic<int> driftPpmX10_{0};
    std::atomic<int64_t> validFeedbackCount_{0};
    std::atomic<int64_t> errorFeedbackCount_{0};
    std::atomic<int64_t> outlierFeedbackCount_{0};
    std::atomic<int64_t> watchdogTrips_{0};
};