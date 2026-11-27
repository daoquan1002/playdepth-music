/**
 * spsc_ring_buffer — Header-only lock-free single-producer single-consumer byte ring buffer.
 *
 * @remarks
 * Separates producer (`head_`) and consumer (`tail_`) atomics onto distinct 64-byte cache
 * lines to avoid false sharing between the decoder/DSP thread and the real-time USB
 * Isochronous thread on ARM big.LITTLE cores. All memory is pre-allocated outside the
 * real-time audio loop via `allocate()`; `write()`, `read()`, and `requestFlush()` perform
 * zero heap allocations and acquire zero mutexes.
 *
 * Flush semantics preserve single-owner SPSC invariants: `requestFlush()` records the
 * producer's current write cursor as `flushTargetHead_` and signals `flushRequested_`,
 * allowing the consumer to advance `tail_` atomically without losing samples written after
 * the flush request.
 *
 * @see UsbAudioDriver
 */
#pragma once

// ═══ Includes ═══
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <sys/mman.h>

// ═══ Constants ═══
constexpr size_t SPSC_CACHE_LINE_BYTES = 64;
constexpr size_t SPSC_MIN_CAPACITY_BYTES = 4 * 1024 * 1024;      // 4 MB floor for PCM <= 192 kHz (~2.0 s+)
constexpr size_t SPSC_DEFAULT_CAPACITY_BYTES = 8 * 1024 * 1024;  // 8 MB default (>2.0 s up to 384 kHz / DSD256)
constexpr size_t SPSC_MAX_CAPACITY_BYTES = 32 * 1024 * 1024;     // 32 MB cap (>2.0 s at PCM 768 kHz & DSD512 DoP)

inline size_t computeRingBufferCapacityBytes(size_t targetBytes) {
    const size_t clamped = std::clamp(targetBytes, SPSC_MIN_CAPACITY_BYTES, SPSC_MAX_CAPACITY_BYTES);
    size_t cap = SPSC_MIN_CAPACITY_BYTES;
    while (cap < clamped && cap < SPSC_MAX_CAPACITY_BYTES) {
        cap <<= 1;
    }
    return cap;
}

// ═══ Lock-Free SPSC Ring Buffer ═══
class SpscRingBuffer {
public:
    SpscRingBuffer() = default;
    ~SpscRingBuffer() = default;

    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    // ─── Lifecycle (Control Thread Only, While Stream Thread Is Stopped) ───
    bool allocate(size_t powerOfTwoCapacity = SPSC_DEFAULT_CAPACITY_BYTES) {
        if (powerOfTwoCapacity < 4096 || (powerOfTwoCapacity & (powerOfTwoCapacity - 1)) != 0) {
            return false;
        }
        void* rawPtr = nullptr;
        if (posix_memalign(&rawPtr, SPSC_CACHE_LINE_BYTES, powerOfTwoCapacity) != 0 || rawPtr == nullptr) {
            capacity_.store(0, std::memory_order_release);
            storage_.reset();
            return false;
        }
        // Pre-fault every 4 KB page off the RT thread and advise kernel residency so the real-time
        // ISO loop never triggers demand-paging minor faults during Hi-Res / DSD playback.
        std::memset(rawPtr, 0, powerOfTwoCapacity);
        madvise(rawPtr, powerOfTwoCapacity, MADV_WILLNEED);
        capacity_.store(0, std::memory_order_release);
        storage_.reset(static_cast<uint8_t*>(rawPtr));
        resetImmediate();
        capacity_.store(powerOfTwoCapacity, std::memory_order_release);
        return true;
    }

    void release() {
        capacity_.store(0, std::memory_order_release);
        resetImmediate();
        storage_.reset();
    }

    void resetImmediate() {
        flushSeq_.store(0, std::memory_order_relaxed);
        appliedFlushSeq_.store(0, std::memory_order_relaxed);
        flushTargetHead_.store(0, std::memory_order_relaxed);
        head_.store(0, std::memory_order_release);
        tail_.store(0, std::memory_order_release);
    }

    bool isAllocated() const {
        return storage_ != nullptr && capacity_.load(std::memory_order_acquire) > 0;
    }

    size_t capacity() const {
        return capacity_.load(std::memory_order_acquire);
    }

    // ─── Inspection (Thread-Safe Lock-Free Queries) ───
    size_t availableBytes() const {
        const size_t cap = capacity_.load(std::memory_order_acquire);
        if (cap == 0) return 0;
        const size_t mask = cap - 1u;
        const size_t head = head_.load(std::memory_order_acquire);
        const bool flushPending =
            flushSeq_.load(std::memory_order_acquire) != appliedFlushSeq_.load(std::memory_order_acquire);
        const size_t tail = flushPending
            ? flushTargetHead_.load(std::memory_order_acquire)
            : tail_.load(std::memory_order_acquire);
        return (head - tail) & mask;
    }

    size_t freeBytes() const {
        const size_t cap = capacity_.load(std::memory_order_acquire);
        if (cap == 0) return 0;
        const size_t mask = cap - 1u;
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t physicalUsed = (head - tail) & mask;
        return (cap - 1u) - physicalUsed;
    }

    // ─── Producer Operations (Single Producer Thread Only) ───
    size_t write(const uint8_t* src, size_t bytes) {
        const size_t cap = capacity_.load(std::memory_order_acquire);
        if (!storage_ || !src || bytes == 0 || cap == 0) return 0;
        const size_t mask = cap - 1u;

        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t physicalUsed = (head - tail) & mask;
        const size_t freeSpace = (cap - 1u) - physicalUsed;
        const size_t toWrite = std::min(bytes, freeSpace);
        if (toWrite == 0) return 0;

        uint8_t* const buf = storage_.get();
        const size_t firstPart = std::min(toWrite, cap - head);
        std::memcpy(buf + head, src, firstPart);
        if (toWrite > firstPart) {
            std::memcpy(buf, src + firstPart, toWrite - firstPart);
        }

        head_.store((head + toWrite) & mask, std::memory_order_release);
        return toWrite;
    }

    void requestFlush(bool consumerRunning) {
        const size_t head = head_.load(std::memory_order_acquire);
        if (consumerRunning) {
            flushTargetHead_.store(head, std::memory_order_release);
            flushSeq_.fetch_add(1u, std::memory_order_release);
        } else {
            tail_.store(head, std::memory_order_release);
            appliedFlushSeq_.store(flushSeq_.load(std::memory_order_acquire), std::memory_order_release);
        }
    }

    // ─── Consumer Operations (Single RT Consumer Thread Only) ───
    bool applyPendingFlush() {
        const uint64_t reqSeq = flushSeq_.load(std::memory_order_acquire);
        if (reqSeq == appliedFlushSeq_.load(std::memory_order_relaxed)) {
            return false;
        }
        const size_t target = flushTargetHead_.load(std::memory_order_acquire);
        // Commit tail_ BEFORE publishing appliedFlushSeq_ so concurrent availableBytes() readers
        // never observe flushPending == false alongside a stale pre-flush tail_.
        tail_.store(target, std::memory_order_release);
        appliedFlushSeq_.store(reqSeq, std::memory_order_release);
        return true;
    }

    size_t availableBytesConsumer() const {
        const size_t cap = capacity_.load(std::memory_order_acquire);
        if (cap == 0) return 0;
        const size_t mask = cap - 1u;
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t tail = tail_.load(std::memory_order_relaxed);
        return (head - tail) & mask;
    }

    size_t readAligned(uint8_t* dst, size_t requestedBytes, size_t frameAlignment) {
        const size_t cap = capacity_.load(std::memory_order_acquire);
        if (!storage_ || !dst || requestedBytes == 0 || frameAlignment == 0 || cap == 0) return 0;
        const size_t mask = cap - 1u;

        const size_t head = head_.load(std::memory_order_acquire);
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t avail = (head - tail) & mask;
        const size_t availAligned = avail - (avail % frameAlignment);
        const size_t toRead = std::min(requestedBytes, availAligned);
        if (toRead == 0) return 0;

        const uint8_t* const buf = storage_.get();
        const size_t firstPart = std::min(toRead, cap - tail);
        std::memcpy(dst, buf + tail, firstPart);
        if (toRead > firstPart) {
            std::memcpy(dst + firstPart, buf, toRead - firstPart);
        }

        tail_.store((tail + toRead) & mask, std::memory_order_release);
        return toRead;
    }

private:
    struct AlignedFreeDeleter {
        void operator()(uint8_t* ptr) const { std::free(ptr); }
    };

    std::unique_ptr<uint8_t, AlignedFreeDeleter> storage_;
    std::atomic<size_t> capacity_{0};

    alignas(SPSC_CACHE_LINE_BYTES) std::atomic<size_t> head_{0};
    alignas(SPSC_CACHE_LINE_BYTES) std::atomic<size_t> tail_{0};
    alignas(SPSC_CACHE_LINE_BYTES) std::atomic<uint64_t> flushSeq_{0};
    std::atomic<size_t> flushTargetHead_{0};
    alignas(SPSC_CACHE_LINE_BYTES) std::atomic<uint64_t> appliedFlushSeq_{0};
};