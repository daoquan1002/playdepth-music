/**
 * pd_log — Lightweight structured logging macros and lock-free RT telemetry for playdepth_usbdac.
 *
 * @remarks
 * Unifies native C++/JNI log tags under `PD:NativeAudio` and `PD:JniBridge` with compile-time
 * `NDEBUG` stripping for `PD_LOGD*`.
 *
 * Real-Time Audio Safety Contract:
 *   - `PD_LOG*` macros MUST NEVER be called inside `UsbAudioDriver::streamingLoop()`,
 *     `ClockSyncEngine` RT callbacks, `AudioDspPipeline` hot loops, or `UsbTransport` URB paths.
 *   - The RT ISO thread records anomalies and transitions exclusively through [PdRtTelemetry],
 *     using a non-blocking single-writer seqlock (`seq` incremented with `memory_order_release`
 *     before and after atomic payload writes; RT thread never spins or waits).
 *   - Only off-RT threads (`drainNativeLogs`, `closeStreamLocked`, `setPaused`) read snapshots
 *     and retry if `(seq1 & 1) != 0 || seq1 != seq2`.
 */
#pragma once

#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>

// ═══ Unified Native Tag Namespace ═══
#define PD_TAG_NATIVE_AUDIO "PD:NativeAudio"
#define PD_TAG_JNI_BRIDGE   "PD:JniBridge"

// Optional sink callback to mirror native log lines into UsbAudioDriver's bounded ring buffer.
using PdNativeLogSinkFn = void (*)(char level, const char* tag, const char* msg, void* ctx);

namespace pdlog {

inline void emit(
    int prio,
    char level,
    const char* tag,
    PdNativeLogSinkFn sink,
    void* ctx,
    const char* fmt,
    ...
) __attribute__((format(printf, 6, 7)));

inline void emit(
    int prio,
    char level,
    const char* tag,
    PdNativeLogSinkFn sink,
    void* ctx,
    const char* fmt,
    ...
) {
    char buf[384];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_print(prio, tag, "%s", buf);
    if (sink != nullptr) {
        sink(level, tag, buf, ctx);
    }
}

} // namespace pdlog

// ═══ Non-RT Structured Logging Macros (NEVER call from RT ISO thread) ═══
#define PD_LOGE_SINK(tag, sink, ctx, fmt, ...) \
    ::pdlog::emit(ANDROID_LOG_ERROR, 'E', tag, sink, ctx, fmt, ##__VA_ARGS__)
#define PD_LOGW_SINK(tag, sink, ctx, fmt, ...) \
    ::pdlog::emit(ANDROID_LOG_WARN,  'W', tag, sink, ctx, fmt, ##__VA_ARGS__)
#define PD_LOGI_SINK(tag, sink, ctx, fmt, ...) \
    ::pdlog::emit(ANDROID_LOG_INFO,  'I', tag, sink, ctx, fmt, ##__VA_ARGS__)

#ifndef NDEBUG
#define PD_LOGD_SINK(tag, sink, ctx, fmt, ...) \
    ::pdlog::emit(ANDROID_LOG_DEBUG, 'D', tag, sink, ctx, fmt, ##__VA_ARGS__)
#else
#define PD_LOGD_SINK(tag, sink, ctx, fmt, ...) ((void)0)
#endif

// Convenience macros for modules without a ring-buffer sink (e.g., JNI boundary)
#define PD_LOGE(tag, fmt, ...) PD_LOGE_SINK(tag, nullptr, nullptr, fmt, ##__VA_ARGS__)
#define PD_LOGW(tag, fmt, ...) PD_LOGW_SINK(tag, nullptr, nullptr, fmt, ##__VA_ARGS__)
#define PD_LOGI(tag, fmt, ...) PD_LOGI_SINK(tag, nullptr, nullptr, fmt, ##__VA_ARGS__)
#define PD_LOGD(tag, fmt, ...) PD_LOGD_SINK(tag, nullptr, nullptr, fmt, ##__VA_ARGS__)

// ═══ Lock-Free RT Telemetry & Non-Blocking Seqlock Event Latch ═══
struct PdRtEventSnapshot {
    int64_t fbLockNominalU6 = 0;
    int64_t fbLockActualU6 = 0;
    int fbLockDriftPpmX10 = 0;

    int watchdogReason = 0; // 1=submitErr, 2=pktErr, 3=timeout
    int watchdogCodeA = 0;
    int watchdogCodeB = 0;
    uint32_t watchdogLen = 0;

    int disconnectSource = 0; // 1=submit, 2=poll, 3=reap
    int disconnectErrno = 0;

    int64_t lastStallGapMs = 0;
    size_t lastStallRingAvail = 0;

    int submitErrUrb = 0;
    int submitErrCode = 0;

    int64_t lastUnderrunTotal = 0;
    size_t lastUnderrunCopied = 0;
    size_t lastUnderrunExpected = 0;

    int mtkFallbackErrno = 0;
    int mtkFallbackUrbs = 0;
    int mtkFallbackPkts = 0;
    int64_t mtkFallbackLatUs = 0;
};

struct PdRtTelemetry {
    static_assert(std::atomic<int64_t>::is_always_lock_free, "PdRtTelemetry int64_t must be lock-free");
    static_assert(std::atomic<uint32_t>::is_always_lock_free, "PdRtTelemetry uint32_t must be lock-free");
    static_assert(std::atomic<size_t>::is_always_lock_free, "PdRtTelemetry size_t must be lock-free");

    static constexpr uint32_t EVENT_FB_LOCK      = 1u << 0;
    static constexpr uint32_t EVENT_WATCHDOG     = 1u << 1;
    static constexpr uint32_t EVENT_STALL        = 1u << 2;
    static constexpr uint32_t EVENT_UNDERRUN     = 1u << 3;
    static constexpr uint32_t EVENT_MTK_FALLBACK = 1u << 4;
    static constexpr uint32_t EVENT_SUBMIT_ERR   = 1u << 5;
    static constexpr uint32_t EVENT_DISCONNECT   = 1u << 6;

    // Seqlock sequence counter: even = stable, odd = RT write in progress.
    std::atomic<uint32_t> seq{0u};
    std::atomic<uint32_t> eventMask{0u};

    // Monotonic RT counters (reset on stream open)
    std::atomic<int64_t> outUrbRecoveryCount{0};
    std::atomic<int64_t> fbTimeoutCount{0};
    std::atomic<int64_t> fbSubmitErrCount{0};
    std::atomic<int64_t> dopMarkerResyncCount{0};

    // Atomic payload fields (written only by RT ISO thread inside beginWrite/endWrite)
    std::atomic<int64_t> fbLockNominalU6{0};
    std::atomic<int64_t> fbLockActualU6{0};
    std::atomic<int> fbLockDriftPpmX10{0};

    std::atomic<int> watchdogReason{0};
    std::atomic<int> watchdogCodeA{0};
    std::atomic<int> watchdogCodeB{0};
    std::atomic<uint32_t> watchdogLen{0u};

    std::atomic<int> disconnectSource{0};
    std::atomic<int> disconnectErrno{0};

    std::atomic<int64_t> lastStallGapMs{0};
    std::atomic<size_t> lastStallRingAvail{0u};

    std::atomic<int> submitErrUrb{0};
    std::atomic<int> submitErrCode{0};

    std::atomic<int64_t> lastUnderrunTotal{0};
    std::atomic<size_t> lastUnderrunCopied{0u};
    std::atomic<size_t> lastUnderrunExpected{0u};

    std::atomic<int> mtkFallbackErrno{0};
    std::atomic<int> mtkFallbackUrbs{0};
    std::atomic<int> mtkFallbackPkts{0};
    std::atomic<int64_t> mtkFallbackLatUs{0};

    void resetForNewStream() {
        eventMask.store(0u, std::memory_order_release);
        outUrbRecoveryCount.store(0, std::memory_order_relaxed);
        fbTimeoutCount.store(0, std::memory_order_relaxed);
        fbSubmitErrCount.store(0, std::memory_order_relaxed);
        dopMarkerResyncCount.store(0, std::memory_order_relaxed);
    }

    // ─── Non-Blocking RT Writer Helpers (NEVER spin or wait) ───
    uint32_t beginRtWrite() noexcept {
        const uint32_t s = seq.load(std::memory_order_relaxed);
        seq.store(s + 1u, std::memory_order_release);
        return s;
    }

    void endRtWrite(uint32_t baseSeq, uint32_t eventBit) noexcept {
        seq.store(baseSeq + 2u, std::memory_order_release);
        eventMask.fetch_or(eventBit, std::memory_order_release);
    }

    void recordFbLockRt(int64_t nominalU6, int64_t actualU6, int driftPpmX10) noexcept {
        const uint32_t s = beginRtWrite();
        fbLockNominalU6.store(nominalU6, std::memory_order_relaxed);
        fbLockActualU6.store(actualU6, std::memory_order_relaxed);
        fbLockDriftPpmX10.store(driftPpmX10, std::memory_order_relaxed);
        endRtWrite(s, EVENT_FB_LOCK);
    }

    void recordWatchdogRt(int reason, int codeA, int codeB, uint32_t len) noexcept {
        const uint32_t s = beginRtWrite();
        watchdogReason.store(reason, std::memory_order_relaxed);
        watchdogCodeA.store(codeA, std::memory_order_relaxed);
        watchdogCodeB.store(codeB, std::memory_order_relaxed);
        watchdogLen.store(len, std::memory_order_relaxed);
        endRtWrite(s, EVENT_WATCHDOG);
    }

    void recordStallRt(int64_t gapMs, size_t ringAvail) noexcept {
        const uint32_t s = beginRtWrite();
        lastStallGapMs.store(gapMs, std::memory_order_relaxed);
        lastStallRingAvail.store(ringAvail, std::memory_order_relaxed);
        endRtWrite(s, EVENT_STALL);
    }

    void recordUnderrunRt(int64_t total, size_t copied, size_t expected) noexcept {
        const uint32_t s = beginRtWrite();
        lastUnderrunTotal.store(total, std::memory_order_relaxed);
        lastUnderrunCopied.store(copied, std::memory_order_relaxed);
        lastUnderrunExpected.store(expected, std::memory_order_relaxed);
        endRtWrite(s, EVENT_UNDERRUN);
    }

    void recordMtkFallbackRt(int err, int urbs, int pkts, int64_t latUs) noexcept {
        const uint32_t s = beginRtWrite();
        mtkFallbackErrno.store(err, std::memory_order_relaxed);
        mtkFallbackUrbs.store(urbs, std::memory_order_relaxed);
        mtkFallbackPkts.store(pkts, std::memory_order_relaxed);
        mtkFallbackLatUs.store(latUs, std::memory_order_relaxed);
        // Clear any transient submit error bit superseded by successful MTK fallback
        eventMask.fetch_and(~EVENT_SUBMIT_ERR, std::memory_order_acq_rel);
        endRtWrite(s, EVENT_MTK_FALLBACK);
    }

    void recordSubmitErrRt(int urbIdx, int err) noexcept {
        const uint32_t s = beginRtWrite();
        submitErrUrb.store(urbIdx, std::memory_order_relaxed);
        submitErrCode.store(err, std::memory_order_relaxed);
        endRtWrite(s, EVENT_SUBMIT_ERR);
    }

    void recordDisconnectRt(int source, int errOrRevents) noexcept {
        const uint32_t s = beginRtWrite();
        disconnectSource.store(source, std::memory_order_relaxed);
        disconnectErrno.store(errOrRevents, std::memory_order_relaxed);
        endRtWrite(s, EVENT_DISCONNECT);
    }

    // ─── Off-RT Lock-Free Seqlock Reader ───
    bool hasPendingEvents() const noexcept {
        return eventMask.load(std::memory_order_acquire) != 0u;
    }

    uint32_t consumeEventsAndSnapshot(PdRtEventSnapshot& out) noexcept {
        const uint32_t mask = eventMask.exchange(0u, std::memory_order_acq_rel);
        if (mask == 0u) return 0u;

        // Bounded retry on torn reads; falls back to best-effort snapshot if RT is continuously bursting.
        for (int attempt = 0; attempt < 8; ++attempt) {
            const uint32_t seq1 = seq.load(std::memory_order_acquire);
            if ((seq1 & 1u) != 0u) continue;

            out.fbLockNominalU6 = fbLockNominalU6.load(std::memory_order_relaxed);
            out.fbLockActualU6 = fbLockActualU6.load(std::memory_order_relaxed);
            out.fbLockDriftPpmX10 = fbLockDriftPpmX10.load(std::memory_order_relaxed);

            out.watchdogReason = watchdogReason.load(std::memory_order_relaxed);
            out.watchdogCodeA = watchdogCodeA.load(std::memory_order_relaxed);
            out.watchdogCodeB = watchdogCodeB.load(std::memory_order_relaxed);
            out.watchdogLen = watchdogLen.load(std::memory_order_relaxed);

            out.disconnectSource = disconnectSource.load(std::memory_order_relaxed);
            out.disconnectErrno = disconnectErrno.load(std::memory_order_relaxed);

            out.lastStallGapMs = lastStallGapMs.load(std::memory_order_relaxed);
            out.lastStallRingAvail = lastStallRingAvail.load(std::memory_order_relaxed);

            out.submitErrUrb = submitErrUrb.load(std::memory_order_relaxed);
            out.submitErrCode = submitErrCode.load(std::memory_order_relaxed);

            out.lastUnderrunTotal = lastUnderrunTotal.load(std::memory_order_relaxed);
            out.lastUnderrunCopied = lastUnderrunCopied.load(std::memory_order_relaxed);
            out.lastUnderrunExpected = lastUnderrunExpected.load(std::memory_order_relaxed);

            out.mtkFallbackErrno = mtkFallbackErrno.load(std::memory_order_relaxed);
            out.mtkFallbackUrbs = mtkFallbackUrbs.load(std::memory_order_relaxed);
            out.mtkFallbackPkts = mtkFallbackPkts.load(std::memory_order_relaxed);
            out.mtkFallbackLatUs = mtkFallbackLatUs.load(std::memory_order_relaxed);

            std::atomic_thread_fence(std::memory_order_acquire);
            const uint32_t seq2 = seq.load(std::memory_order_acquire);
            if (seq1 == seq2) {
                break;
            }
        }
        return mask;
    }
};
