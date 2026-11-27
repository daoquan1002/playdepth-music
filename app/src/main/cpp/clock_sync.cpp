/**
 * clock_sync — Asynchronous (10.14 / 16.16) feedback and Adaptive/Sync clock pacing engine.
 *
 * @remarks
 * Locks USB Isochronous OUT packet pacing to the DAC's master crystal via continuous Feedback IN
 * polling with median-of-5 spike rejection and time-normalized EMA smoothing so FIFO slip ticks
 * and phase-lag hunting are eliminated without inducing MediaTek MUSB RX QMU empty-queue IRQ
 * storms. Auto-normalizes common firmware fixed-point scaling quirks (per-microframe vs
 * per-1ms-frame and `bInterval > 1` multipliers), guards initial crystal lock within +/- 2,000 ppm
 * (with bounded pre-lock pull-in so +/- 5,000 ppm FIFO-fill rails settle cleanly), clamps
 * steady-state pacing to +/- 0.5% (tau ~ 350 ms, 1,500 ppm/s max slew) inside a +/- 0.75% outlier
 * gate, and probes gently (100 ms) in `WATCHDOG_FALLBACK` to self-heal back to `ASYNC_FEEDBACK`
 * after 5 consecutive valid median-of-5 packets.
 *
 * @see clock_sync.h
 * @see UsbAudioDriver
 * @see UsbTransport
 */

// ═══ Includes ═══
#include "clock_sync.h"

#include <algorithm>
#include <cmath>

namespace {

// ═══ Constants ═══
constexpr uint32_t USB_HS_MICROFRAMES_PER_SEC = 8000u;
constexpr uint32_t USB_FS_FRAMES_PER_SEC = 1000u;
constexpr uint32_t MAX_INTERVAL_SHIFT = 15u;

constexpr double FIXED_Q16_16_SCALE = 65536.0;
constexpr double FIXED_Q10_14_SCALE = 16384.0;
constexpr double HS_MICROFRAMES_PER_FRAME = 8.0;

constexpr int64_t FEEDBACK_ERROR_BACKOFF_MS = 32;          // Short backoff after burst of USB transport errors
constexpr int64_t FEEDBACK_WATCHDOG_PROBE_INTERVAL_MS = 100; // Gentle self-healing probe when pre-lock submit fails
constexpr int64_t FEEDBACK_HANG_TIMEOUT_MS = 2000;         // Wall-clock URB hang timeout when zero completions arrive
constexpr int64_t FEEDBACK_ZLP_ONLY_WATCHDOG_MS = 4000;    // Pre-lock ceiling for stub endpoints emitting only 0-byte ZLPs

constexpr int FEEDBACK_WARMUP_SKIP_MIN_PACKETS = 4;          // Minimum initial packets skipped on slow feedback pipes
constexpr int FEEDBACK_WARMUP_SKIP_MAX_PACKETS = 32;         // ~32 ms skip on 1 ms HS feedback pipes after clock switch
constexpr int64_t FEEDBACK_WARMUP_SKIP_TARGET_MS = 32;       // Target warm-up discard duration before median collection
constexpr int FEEDBACK_DIV8_CONFIRM_STREAK = 6;            // Require sustained div-8 packets so stale 8:1 transitions never lock
constexpr int FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD = 8;  // Back off briefly only after a burst of transport errors
constexpr int FEEDBACK_PRELOCK_MAX_ERRORS = 32;            // Pre-lock submit/endpoint failure ceiling before fallback

constexpr double FEEDBACK_EMA_ALPHA = 0.08;                     // Pre-lock pull-in EMA gain
constexpr double FEEDBACK_PRELOCK_TOLERANCE_RATIO = 0.01;       // +/- 1.0% capture window before initial lock
constexpr double FEEDBACK_LOCKED_TOLERANCE_RATIO = 0.0075;      // +/- 0.75% strict post-lock sanity gate (outliers rejected & held)
constexpr double FEEDBACK_PLL_CLAMP_RATIO = 0.005;              // +/- 0.5% (+/- 5,000 ppm) max locked pacing clamp
constexpr double FEEDBACK_INITIAL_LOCK_TOLERANCE_RATIO = 0.002; // +/- 0.2% (+/- 2,000 ppm) crystal window for clean initial lock
constexpr double FEEDBACK_PULLIN_CLAMP_RATIO = 0.002;           // +/- 0.2% (+/- 2,000 ppm) bounded pre-lock FIFO pull-in cap
constexpr double FEEDBACK_MEDIAN_SPREAD_RATIO = 0.0025;         // +/- 2,500 ppm max IQR across 5-sample median before initial lock
constexpr double FEEDBACK_MAX_SLEW_STEP_RATIO = 0.00025;        // +/- 250 ppm max per-update pre-lock pull-in step
constexpr double FEEDBACK_POSTLOCK_TAU_MS = 350.0;              // Steady-state PLL time constant (~13x USB pipe delay, prevents hunting)
constexpr double FEEDBACK_POSTLOCK_ALPHA_MIN = 0.002;
constexpr double FEEDBACK_POSTLOCK_ALPHA_MAX = 0.15;
constexpr double FEEDBACK_POSTLOCK_SLEW_PPM_PER_SEC = 1500.0;   // Smooth steady-state slew (~6 ppm per 4 ms update)
constexpr double FEEDBACK_POSTLOCK_SLEW_MIN_PPM = 2.0;
constexpr double FEEDBACK_POSTLOCK_SLEW_MAX_PPM = 100.0;
constexpr int64_t FEEDBACK_POSTLOCK_DT_MAX_MS = 250;
constexpr int64_t FEEDBACK_WIDE_LOCK_CONFIRM_MS = 500;          // Require >= 500 ms sustained rail before locking outside +/- 2,000 ppm
constexpr int FEEDBACK_WIDE_LOCK_MIN_PACKETS = 30;
constexpr int FEEDBACK_WIDE_LOCK_MAX_PACKETS = 500;

// ═══ Private Helpers ═══
uint32_t readLeU24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16);
}

uint32_t readLeU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

ClockSyncMode resolveInitialMode(const ClockSyncConfig& cfg, bool feedbackEnabled) {
    if (feedbackEnabled) {
        return ClockSyncMode::ASYNC_FEEDBACK;
    }
    if (cfg.syncType == 3u) {
        return ClockSyncMode::SYNCHRONOUS;
    }
    // Adaptive endpoints and Async endpoints without an explicit Feedback IN pipe pace nominally
    return ClockSyncMode::ADAPTIVE;
}

} // namespace

// ═══ Configuration & Lifecycle ═══
void ClockSyncEngine::configure(const ClockSyncConfig& config) {
    config_ = config;
    packetsPerSecond_ = computePacketsPerSecond(config.isHighSpeed, config.outInterval);
    nominalSamplesPerPacket_ = (config.sampleRate > 0 && packetsPerSecond_ > 0.0)
        ? (static_cast<double>(config.sampleRate) / packetsPerSecond_)
        : 0.0;

    // Derive DAC feedback URB callback period from UAC 1.0 bRefresh or UAC 2.0/3.0 bInterval
    // scaled by packetsPerFeedbackUrb (reapUrbNonBlocking emits one feedback update per reaped URB).
    const int pktsPerFbUrb = std::max(1, config.packetsPerFeedbackUrb);
    int64_t periodMs = 1;
    if (config.uacVersion < 0x0200u && config.feedbackRefresh >= 1u && config.feedbackRefresh <= 9u) {
        periodMs = static_cast<int64_t>(1u << config.feedbackRefresh) * static_cast<int64_t>(pktsPerFbUrb);
    } else if (config.feedbackInterval > 0u) {
        const double fbPps = computePacketsPerSecond(config.isHighSpeed, config.feedbackInterval);
        if (fbPps > 0.0) {
            periodMs = std::max<int64_t>(
                1,
                static_cast<int64_t>(std::ceil((1000.0 * static_cast<double>(pktsPerFbUrb)) / fbPps))
            );
        }
    }
    feedbackPeriodMs_ = periodMs;
    hangTimeoutMs_ = std::max<int64_t>(FEEDBACK_HANG_TIMEOUT_MS, periodMs * 8);
    warmupSkipTarget_ = std::clamp<int>(
        static_cast<int>((FEEDBACK_WARMUP_SKIP_TARGET_MS + periodMs - 1) / periodMs),
        FEEDBACK_WARMUP_SKIP_MIN_PACKETS,
        FEEDBACK_WARMUP_SKIP_MAX_PACKETS
    );
    wideLockConfirmTarget_ = std::clamp<int>(
        static_cast<int>((FEEDBACK_WIDE_LOCK_CONFIRM_MS + periodMs - 1) / periodMs),
        FEEDBACK_WIDE_LOCK_MIN_PACKETS,
        FEEDBACK_WIDE_LOCK_MAX_PACKETS
    );

    reset();
}

void ClockSyncEngine::reset() {
    resetFeedbackLoopState(false);
}

void ClockSyncEngine::clear() {
    config_ = ClockSyncConfig{};
    packetsPerSecond_ = 0.0;
    nominalSamplesPerPacket_ = 0.0;
    resetFeedbackLoopState(false);
    activeMode_.store(static_cast<int>(ClockSyncMode::NONE), std::memory_order_release);
}

void ClockSyncEngine::resetAfterDiscard() {
    resetFeedbackLoopState(true);
}

void ClockSyncEngine::resetAccumulatorOnly() {
    // Preserve locked crystal rate across same-rate seeks/flushes to prevent unlock/relock pops
    sampleAccumulator_ = 0.0;
}

void ClockSyncEngine::resetFeedbackLoopState(bool keepCanceling) {
    currentSamplesPerPacket_ = nominalSamplesPerPacket_;
    sampleAccumulator_ = 0.0;

    fbEnabled_ = (config_.syncType == 1u) && config_.hasExplicitFeedback;
    fbWatchdogActive_ = false;
    fbCanceling_ = keepCanceling && fbEnabled_;
    fbDirectSeen_ = false;
    fbDiv8Streak_ = 0;
    fbWarmupSkipped_ = 0;
    fbWideLockStreak_ = 0;
    fbMedianCount_ = 0;
    for (double& v : fbMedianBuf_) {
        v = 0.0;
    }
    fbValidLocal_ = 0;
    fbErrorStreak_ = 0;
    lastFeedbackActivityMs_ = 0;
    lastFeedbackUpdateMs_ = 0;
    nextFeedbackSubmitMs_ = 0;
    fbZlpOnlySinceMs_ = 0;

    const ClockSyncMode initialMode = resolveInitialMode(config_, fbEnabled_);
    activeMode_.store(static_cast<int>(initialMode), std::memory_order_release);
    feedbackLocked_.store(false, std::memory_order_release);
    driftPpmX10_.store(0, std::memory_order_relaxed);
    validFeedbackCount_.store(0, std::memory_order_relaxed);
    errorFeedbackCount_.store(0, std::memory_order_relaxed);
    outlierFeedbackCount_.store(0, std::memory_order_relaxed);
    watchdogTrips_.store(0, std::memory_order_relaxed);
}

int ClockSyncEngine::computeWarmUpPackets(int warmUpMs) const {
    if (warmUpMs <= 0 || packetsPerSecond_ <= 0.0) return 0;
    return static_cast<int>(std::llround((static_cast<double>(warmUpMs) / 1000.0) * packetsPerSecond_));
}

// ═══ Real-Time Packet Pacing & Continuous Multi-URB Feedback ═══
int ClockSyncEngine::nextPacketFrameCount(int maxFrames) {
    if (maxFrames <= 0 || !std::isfinite(currentSamplesPerPacket_) || currentSamplesPerPacket_ <= 0.0) {
        return 0;
    }

    sampleAccumulator_ += currentSamplesPerPacket_;
    int packetFrames = static_cast<int>(sampleAccumulator_);
    if (packetFrames < 0) {
        packetFrames = 0;
        sampleAccumulator_ = 0.0;
    } else if (packetFrames > maxFrames) {
        packetFrames = maxFrames;
        sampleAccumulator_ -= static_cast<double>(packetFrames);
        // Bound residual strictly to [0.0, 1.0) so tight wMaxPacketSize endpoints never
        // accumulate multi-packet integrator windup after transient positive feedback bursts.
        if (sampleAccumulator_ >= 1.0) {
            sampleAccumulator_ -= std::floor(sampleAccumulator_);
        }
        if (sampleAccumulator_ < 0.0) {
            sampleAccumulator_ = 0.0;
        }
    } else {
        sampleAccumulator_ -= static_cast<double>(packetFrames);
    }
    return packetFrames;
}

bool ClockSyncEngine::shouldSubmitFeedback(int64_t nowMs, bool paused) const {
    // Keep Async Feedback IN active during Hot-Standby (paused == true while ISO OUT pumps
    // silence) so the DAC crystal PLL remains locked across pauses without re-acquisition delay.
    (void)paused;
    return fbEnabled_ && nowMs >= nextFeedbackSubmitMs_;
}

void ClockSyncEngine::onFeedbackSubmitted(int64_t nowMs) {
    if (lastFeedbackActivityMs_ == 0) {
        lastFeedbackActivityMs_ = nowMs;
    }
    fbCanceling_ = false;
}

bool ClockSyncEngine::isFeedbackTimedOut(int64_t nowMs, int inFlightCount) const {
    return fbEnabled_ && inFlightCount > 0 && !fbCanceling_ &&
           lastFeedbackActivityMs_ > 0 &&
           (nowMs - lastFeedbackActivityMs_) >= hangTimeoutMs_;
}

bool ClockSyncEngine::onFeedbackTimeout(int64_t nowMs, bool expectCancelCompletion) {
    fbCanceling_ = expectCancelCompletion;
    lastFeedbackActivityMs_ = nowMs;
    errorFeedbackCount_.fetch_add(1, std::memory_order_relaxed);

    // If already locked, hold the last good crystal rate (flywheel) instead of snapping to nominal
    if (fbValidLocal_ > 0) {
        nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
        return false;
    }

    if (fbWatchdogActive_) {
        fbMedianCount_ = 0;
        nextFeedbackSubmitMs_ = nowMs + FEEDBACK_WATCHDOG_PROBE_INTERVAL_MS;
        return false;
    }

    fbErrorStreak_ += FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD;
    nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
    if (fbErrorStreak_ >= FEEDBACK_PRELOCK_MAX_ERRORS) {
        tripWatchdog(nowMs);
        return true;
    }
    return false;
}

bool ClockSyncEngine::onFeedbackSubmitError(int64_t nowMs, int /* errCode */) {
    fbErrorStreak_++;
    errorFeedbackCount_.fetch_add(1, std::memory_order_relaxed);

    if (fbValidLocal_ > 0) {
        // Hold locked crystal rate across transient submit errors
        if (fbErrorStreak_ >= FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD) {
            nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
        }
        return false;
    }

    if (fbWatchdogActive_) {
        fbMedianCount_ = 0;
        nextFeedbackSubmitMs_ = nowMs + FEEDBACK_WATCHDOG_PROBE_INTERVAL_MS;
        return false;
    }

    if (fbErrorStreak_ >= FEEDBACK_PRELOCK_MAX_ERRORS) {
        tripWatchdog(nowMs);
        return true;
    }
    if (fbErrorStreak_ >= FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD) {
        nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
    }
    return false;
}

bool ClockSyncEngine::onFeedbackPacketReceived(
    const uint8_t* data,
    uint32_t actualLength,
    int urbStatus,
    int pktStatus,
    int64_t nowMs,
    bool& outFirstLock,
    bool& outWatchdogTripped,
    bool* outRelocked
) {
    outFirstLock = false;
    outWatchdogTripped = false;
    if (outRelocked) {
        *outRelocked = false;
    }

    if (!fbEnabled_) {
        return false;
    }

    lastFeedbackActivityMs_ = nowMs;

    // Silently ignore unlink/cancel completions (-ENOENT = -2, -ECONNRESET = -104)
    if (urbStatus == -2 || urbStatus == -104) {
        return false;
    }

    if (urbStatus != 0 || pktStatus != 0) {
        fbErrorStreak_++;
        errorFeedbackCount_.fetch_add(1, std::memory_order_relaxed);

        // Once locked, never drop lock or snap back to nominal on transient USB jitter/errors
        if (fbValidLocal_ > 0) {
            if (fbErrorStreak_ >= FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD) {
                nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
            }
            return false;
        }

        if (fbWatchdogActive_) {
            fbMedianCount_ = 0;
            nextFeedbackSubmitMs_ = nowMs + FEEDBACK_WATCHDOG_PROBE_INTERVAL_MS;
            return false;
        }

        if (fbErrorStreak_ >= FEEDBACK_PRELOCK_MAX_ERRORS) {
            tripWatchdog(nowMs);
            outWatchdogTripped = true;
        } else if (fbErrorStreak_ >= FEEDBACK_ERROR_BURST_BACKOFF_THRESHOLD) {
            nextFeedbackSubmitMs_ = nowMs + FEEDBACK_ERROR_BACKOFF_MS;
        }
        return false;
    }

    // Zero-Length Packet (ZLP): standard on UAC2 async endpoints between refresh frames.
    // Never count interleaved ZLPs as errors, but guard pre-lock against stub/broken pipes that
    // emit 100% ZLPs with zero real feedback payloads for an extended window.
    if (actualLength == 0u) {
        fbErrorStreak_ = 0;
        if (fbValidLocal_ == 0 && !fbWatchdogActive_) {
            if (fbZlpOnlySinceMs_ == 0) {
                fbZlpOnlySinceMs_ = nowMs;
            } else if ((nowMs - fbZlpOnlySinceMs_) >= std::max<int64_t>(FEEDBACK_ZLP_ONLY_WATCHDOG_MS, hangTimeoutMs_ * 2)) {
                tripWatchdog(nowMs);
                outWatchdogTripped = true;
            }
        }
        return false;
    }

    fbZlpOnlySinceMs_ = 0;

    const bool allowDiv8 = !fbDirectSeen_;
    const double sanityTolerance = (fbValidLocal_ > 0)
        ? FEEDBACK_LOCKED_TOLERANCE_RATIO
        : FEEDBACK_PRELOCK_TOLERANCE_RATIO;

    double fbSamples = 0.0;
    FeedbackDecodeKind decodeKind = FeedbackDecodeKind::INVALID;
    if (!decodeFeedbackSamples(
            data,
            actualLength,
            config_.isHighSpeed,
            config_.outInterval,
            nominalSamplesPerPacket_,
            fbSamples,
            allowDiv8,
            sanityTolerance,
            &decodeKind)) {
        // Out-of-range jitter glitch (> +/- 0.75% locked or > +/- 1.0% pre-lock):
        // reject packet completely and hold the previous filtered rate instead of dropping lock.
        outlierFeedbackCount_.fetch_add(1, std::memory_order_relaxed);
        if (fbWatchdogActive_) {
            fbMedianCount_ = 0;
        }
        return false;
    }

    // Valid in-range feedback packet
    fbErrorStreak_ = 0;
    nextFeedbackSubmitMs_ = 0;

    // Discard initial transient packets right after stream start or sample-rate switch BEFORE
    // latching fbDirectSeen_ so stale transitional packets cannot permanently disable DIV8_QUIRK.
    if (!fbWatchdogActive_ && fbWarmupSkipped_ < warmupSkipTarget_) {
        fbWarmupSkipped_++;
        currentSamplesPerPacket_ = nominalSamplesPerPacket_;
        return false;
    }

    // Guard against 8:1 stale feedback bleed (e.g. 352.8 kHz -> 44.1 kHz or 384 kHz -> 48 kHz)
    if (decodeKind == FeedbackDecodeKind::DIV8_QUIRK) {
        fbDiv8Streak_++;
        if (fbDiv8Streak_ < FEEDBACK_DIV8_CONFIRM_STREAK) {
            return false;
        }
    } else {
        if (!fbDirectSeen_ && fbDiv8Streak_ > 0) {
            fbMedianCount_ = 0;
        }
        fbDirectSeen_ = true;
        fbDiv8Streak_ = 0;
    }

    const double minLocked = nominalSamplesPerPacket_ * (1.0 - FEEDBACK_PLL_CLAMP_RATIO);
    const double maxLocked = nominalSamplesPerPacket_ * (1.0 + FEEDBACK_PLL_CLAMP_RATIO);

    if (fbValidLocal_ == 0) {
        // Collect a 5-sample median window and verify stability before committing initial lock
        if (fbMedianCount_ < MEDIAN_WINDOW_SIZE) {
            fbMedianBuf_[fbMedianCount_++] = fbSamples;
        } else {
            for (int i = 1; i < MEDIAN_WINDOW_SIZE; ++i) {
                fbMedianBuf_[i - 1] = fbMedianBuf_[i];
            }
            fbMedianBuf_[MEDIAN_WINDOW_SIZE - 1] = fbSamples;
        }

        if (fbMedianCount_ < MEDIAN_WINDOW_SIZE) {
            return false;
        }

        double sorted[MEDIAN_WINDOW_SIZE];
        for (int i = 0; i < MEDIAN_WINDOW_SIZE; ++i) {
            sorted[i] = fbMedianBuf_[i];
        }
        std::sort(sorted, sorted + MEDIAN_WINDOW_SIZE);
        const double median = sorted[MEDIAN_WINDOW_SIZE / 2];
        const double iqrSpread = sorted[MEDIAN_WINDOW_SIZE - 2] - sorted[1];
        const double maxLockedDelta = nominalSamplesPerPacket_ * FEEDBACK_LOCKED_TOLERANCE_RATIO;
        const double maxInitialLockDelta = nominalSamplesPerPacket_ * FEEDBACK_INITIAL_LOCK_TOLERANCE_RATIO;
        const double maxIqrSpread = nominalSamplesPerPacket_ * FEEDBACK_MEDIAN_SPREAD_RATIO;

        if (std::fabs(median - nominalSamplesPerPacket_) > maxLockedDelta || iqrSpread > maxIqrSpread) {
            fbWideLockStreak_ = 0;
            return false;
        }

        // Guard against locking onto a transient +/- 0.5% (+/- 5,000 ppm) DAC FIFO-fill rail right
        // after stream start or alt-setting switch: gently nudge pacing within +/- 2,000 ppm so
        // the DAC FIFO can refill/drain back to its watermark and settle onto its true crystal rate.
        if (std::fabs(median - nominalSamplesPerPacket_) > maxInitialLockDelta) {
            fbWideLockStreak_++;
            if (fbWideLockStreak_ < wideLockConfirmTarget_) {
                const double minPullIn = nominalSamplesPerPacket_ * (1.0 - FEEDBACK_PULLIN_CLAMP_RATIO);
                const double maxPullIn = nominalSamplesPerPacket_ * (1.0 + FEEDBACK_PULLIN_CLAMP_RATIO);
                const double maxStep = nominalSamplesPerPacket_ * FEEDBACK_MAX_SLEW_STEP_RATIO;
                const double pullTarget = std::clamp(median, minPullIn, maxPullIn);
                const double step = std::clamp(
                    FEEDBACK_EMA_ALPHA * (pullTarget - currentSamplesPerPacket_),
                    -maxStep,
                    maxStep
                );
                currentSamplesPerPacket_ = std::clamp(currentSamplesPerPacket_ + step, minPullIn, maxPullIn);
                return false;
            }
        } else {
            fbWideLockStreak_ = 0;
        }

        const bool wasWatchdog = fbWatchdogActive_;
        fbWatchdogActive_ = false;
        fbWideLockStreak_ = 0;
        fbValidLocal_ = 1;
        lastFeedbackUpdateMs_ = nowMs;
        currentSamplesPerPacket_ = std::clamp(median, minLocked, maxLocked);
        outFirstLock = !wasWatchdog;
        if (outRelocked) {
            *outRelocked = wasWatchdog;
        }
    } else {
        fbValidLocal_++;
        // Rolling 5-sample median rejects single/double-packet USB microframe jitter spikes
        // before they enter the PLL integrator.
        if (fbMedianCount_ < MEDIAN_WINDOW_SIZE) {
            fbMedianBuf_[fbMedianCount_++] = fbSamples;
        } else {
            for (int i = 1; i < MEDIAN_WINDOW_SIZE; ++i) {
                fbMedianBuf_[i - 1] = fbMedianBuf_[i];
            }
            fbMedianBuf_[MEDIAN_WINDOW_SIZE - 1] = fbSamples;
        }

        double filteredFb = fbSamples;
        if (fbMedianCount_ >= MEDIAN_WINDOW_SIZE) {
            double sorted[MEDIAN_WINDOW_SIZE];
            for (int i = 0; i < MEDIAN_WINDOW_SIZE; ++i) {
                sorted[i] = fbMedianBuf_[i];
            }
            std::sort(sorted, sorted + MEDIAN_WINDOW_SIZE);
            filteredFb = sorted[MEDIAN_WINDOW_SIZE / 2];
        }

        // Normalize EMA time constant (~350 ms) and slew limit (1,500 ppm/s) to actual elapsed dt
        // so high-rate 250 Hz UAC2 feedback never hunts against the 26+ ms USB URB flight delay.
        const int64_t dtMs = (lastFeedbackUpdateMs_ > 0 && nowMs > lastFeedbackUpdateMs_)
            ? std::clamp<int64_t>(nowMs - lastFeedbackUpdateMs_, 1, FEEDBACK_POSTLOCK_DT_MAX_MS)
            : ((lastFeedbackUpdateMs_ > 0 && nowMs == lastFeedbackUpdateMs_)
                ? 1LL
                : std::clamp<int64_t>(feedbackPeriodMs_, 1, FEEDBACK_POSTLOCK_DT_MAX_MS));
        lastFeedbackUpdateMs_ = nowMs;

        const double dt = static_cast<double>(dtMs);
        const double alpha = std::clamp(
            dt / (FEEDBACK_POSTLOCK_TAU_MS + dt),
            FEEDBACK_POSTLOCK_ALPHA_MIN,
            FEEDBACK_POSTLOCK_ALPHA_MAX
        );
        const double maxSlewPpm = std::clamp(
            (FEEDBACK_POSTLOCK_SLEW_PPM_PER_SEC * dt) / 1000.0,
            FEEDBACK_POSTLOCK_SLEW_MIN_PPM,
            FEEDBACK_POSTLOCK_SLEW_MAX_PPM
        );
        const double maxStep = nominalSamplesPerPacket_ * (maxSlewPpm * 1e-6);
        const double rawStep = alpha * (filteredFb - currentSamplesPerPacket_);
        const double clampedStep = std::clamp(rawStep, -maxStep, maxStep);
        currentSamplesPerPacket_ = std::clamp(
            currentSamplesPerPacket_ + clampedStep,
            minLocked,
            maxLocked
        );
    }

    const double driftPpm = (nominalSamplesPerPacket_ > 0.0)
        ? (((currentSamplesPerPacket_ - nominalSamplesPerPacket_) / nominalSamplesPerPacket_) * 1e6)
        : 0.0;

    feedbackLocked_.store(true, std::memory_order_release);
    activeMode_.store(static_cast<int>(ClockSyncMode::ASYNC_FEEDBACK), std::memory_order_release);
    validFeedbackCount_.fetch_add(1, std::memory_order_relaxed);
    driftPpmX10_.store(static_cast<int>(std::llround(driftPpm * 10.0)), std::memory_order_relaxed);

    return true;
}

void ClockSyncEngine::tripWatchdog(int64_t nowMs) {
    // Only entered pre-lock when the feedback endpoint persistently fails; keeps probing gently.
    fbWatchdogActive_ = true;
    fbValidLocal_ = 0;
    fbWideLockStreak_ = 0;
    fbMedianCount_ = 0;
    for (double& v : fbMedianBuf_) {
        v = 0.0;
    }
    fbErrorStreak_ = 0;
    lastFeedbackUpdateMs_ = 0;
    fbZlpOnlySinceMs_ = 0;
    currentSamplesPerPacket_ = nominalSamplesPerPacket_;
    nextFeedbackSubmitMs_ = nowMs + FEEDBACK_WATCHDOG_PROBE_INTERVAL_MS;

    feedbackLocked_.store(false, std::memory_order_release);
    driftPpmX10_.store(0, std::memory_order_relaxed);
    activeMode_.store(static_cast<int>(ClockSyncMode::WATCHDOG_FALLBACK), std::memory_order_release);
    watchdogTrips_.fetch_add(1, std::memory_order_relaxed);
}

// ═══ Telemetry Snapshot ═══
ClockSyncSnapshot ClockSyncEngine::snapshot() const {
    ClockSyncSnapshot snap;
    snap.activeMode = static_cast<ClockSyncMode>(activeMode_.load(std::memory_order_acquire));
    snap.feedbackLocked = feedbackLocked_.load(std::memory_order_acquire);
    snap.driftPpmX10 = driftPpmX10_.load(std::memory_order_relaxed);
    snap.validFeedbackCount = validFeedbackCount_.load(std::memory_order_relaxed);
    snap.errorFeedbackCount = errorFeedbackCount_.load(std::memory_order_relaxed);
    snap.outlierFeedbackCount = outlierFeedbackCount_.load(std::memory_order_relaxed);
    snap.watchdogTrips = watchdogTrips_.load(std::memory_order_relaxed);
    return snap;
}

// ═══ Fixed-Point Feedback Decoders (10.14 & 16.16) ═══
double ClockSyncEngine::computePacketsPerSecond(bool isHighSpeed, uint8_t interval) {
    const uint32_t intervalsPerSec = isHighSpeed ? USB_HS_MICROFRAMES_PER_SEC : USB_FS_FRAMES_PER_SEC;
    const uint32_t shift = (interval > 0)
        ? std::min<uint32_t>(static_cast<uint32_t>(interval - 1u), MAX_INTERVAL_SHIFT)
        : 0u;
    return static_cast<double>(intervalsPerSec) / static_cast<double>(1u << shift);
}

bool ClockSyncEngine::decodeFeedbackSamples(
    const uint8_t* data,
    uint32_t actualLength,
    bool isHighSpeed,
    uint8_t outInterval,
    double nominalSamplesPerPacket,
    double& outSamplesPerPacket,
    bool allowDiv8Quirk,
    double toleranceRatio,
    FeedbackDecodeKind* outKind
) {
    if (outKind) {
        *outKind = FeedbackDecodeKind::INVALID;
    }
    if (!data || !std::isfinite(nominalSamplesPerPacket) || nominalSamplesPerPacket <= 0.0) {
        return false;
    }

    const double safeTol = (std::isfinite(toleranceRatio) && toleranceRatio > 0.0)
        ? toleranceRatio
        : FEEDBACK_PRELOCK_TOLERANCE_RATIO;
    const double minValid = nominalSamplesPerPacket * (1.0 - safeTol);
    const double maxValid = nominalSamplesPerPacket * (1.0 + safeTol);
    auto inRange = [&](double candidate) -> bool {
        return std::isfinite(candidate) && candidate >= minValid && candidate <= maxValid;
    };

    const uint32_t outShift = (outInterval > 0)
        ? std::min<uint32_t>(static_cast<uint32_t>(outInterval - 1u), MAX_INTERVAL_SHIFT)
        : 0u;
    const double intervalMult = static_cast<double>(1u << outShift);

    if (actualLength == 4u) {
        // UAC 2.0 / 3.0 standard 16.16 unsigned fixed-point samples per microframe/frame
        const double q16 = static_cast<double>(readLeU32(data)) / FIXED_Q16_16_SCALE;
        if (inRange(q16)) {
            outSamplesPerPacket = q16;
            if (outKind) *outKind = FeedbackDecodeKind::DIRECT;
            return true;
        }
        if (isHighSpeed && outInterval > 1u && inRange(q16 * intervalMult)) {
            outSamplesPerPacket = q16 * intervalMult;
            if (outKind) *outKind = FeedbackDecodeKind::INTERVAL_SCALED;
            return true;
        }
        // Only permit per-1ms-frame div-8 quirk when explicitly allowed so stale 352.8->44.1 kHz
        // (8:1) feedback packets cannot masquerade as valid lower-rate packets. Never multiply by 8.
        if (allowDiv8Quirk && isHighSpeed && inRange(q16 / HS_MICROFRAMES_PER_FRAME)) {
            outSamplesPerPacket = q16 / HS_MICROFRAMES_PER_FRAME;
            if (outKind) *outKind = FeedbackDecodeKind::DIV8_QUIRK;
            return true;
        }
        if (allowDiv8Quirk && isHighSpeed && outInterval > 1u &&
            inRange((q16 / HS_MICROFRAMES_PER_FRAME) * intervalMult)) {
            outSamplesPerPacket = (q16 / HS_MICROFRAMES_PER_FRAME) * intervalMult;
            if (outKind) *outKind = FeedbackDecodeKind::DIV8_QUIRK;
            return true;
        }
        // Quirk fallback: Full-Speed bridge packing a 10.14 value into a 4-byte feedback packet
        if (!isHighSpeed && data[3] == 0u) {
            const double q14 = static_cast<double>(readLeU24(data)) / FIXED_Q10_14_SCALE;
            if (inRange(q14)) {
                outSamplesPerPacket = q14;
                if (outKind) *outKind = FeedbackDecodeKind::Q14_COMPAT;
                return true;
            }
            if (outInterval > 1u && inRange(q14 * intervalMult)) {
                outSamplesPerPacket = q14 * intervalMult;
                if (outKind) *outKind = FeedbackDecodeKind::INTERVAL_SCALED;
                return true;
            }
        }
    } else if (actualLength == 3u) {
        // UAC 1.0 standard 10.14 unsigned fixed-point samples per 1 ms frame
        const double q14 = static_cast<double>(readLeU24(data)) / FIXED_Q10_14_SCALE;
        if (inRange(q14)) {
            outSamplesPerPacket = q14;
            if (outKind) *outKind = FeedbackDecodeKind::DIRECT;
            return true;
        }
        if (allowDiv8Quirk && isHighSpeed && inRange(q14 / HS_MICROFRAMES_PER_FRAME)) {
            outSamplesPerPacket = q14 / HS_MICROFRAMES_PER_FRAME;
            if (outKind) *outKind = FeedbackDecodeKind::DIV8_QUIRK;
            return true;
        }
        if (outInterval > 1u && inRange(q14 * intervalMult)) {
            outSamplesPerPacket = q14 * intervalMult;
            if (outKind) *outKind = FeedbackDecodeKind::INTERVAL_SCALED;
            return true;
        }
        if (allowDiv8Quirk && isHighSpeed && outInterval > 1u &&
            inRange((q14 / HS_MICROFRAMES_PER_FRAME) * intervalMult)) {
            outSamplesPerPacket = (q14 / HS_MICROFRAMES_PER_FRAME) * intervalMult;
            if (outKind) *outKind = FeedbackDecodeKind::DIV8_QUIRK;
            return true;
        }
    }

    return false;
}