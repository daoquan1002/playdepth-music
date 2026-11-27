/**
 * usb_transport — Direct Linux usbdevfs ioctl transport and pre-allocated Isochronous URB pool.
 *
 * @remarks
 * Drives Android's kernel `usbdevfs` file descriptor directly via `ioctl` to detach
 * `snd-usb-audio`, claim UAC interfaces, execute control transfers, and pump overlapping
 * Isochronous URBs without third-party dependencies. Never closes the underlying file
 * descriptor because Android's `UsbDeviceConnection` retains FD ownership; closing it from
 * native code would trigger a double-close crash during teardown.
 *
 * All URB headers and contiguous payload buffers are allocated up front in `allocateIsoPool()`
 * so `submitOutUrb()`, `submitFeedbackUrb()`, `pollUrbs()`, and `reapUrbNonBlocking()` perform
 * zero heap allocations, acquire zero mutexes, and never block on I/O inside the real-time
 * audio thread.
 *
 * @see usb_transport.h
 * @see UsbAudioDriver
 * @see ClockSyncEngine
 */

// ═══ Includes ═══
#include "usb_transport.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

// ═══ Constants ═══
constexpr uintptr_t FEEDBACK_URB_TAG_BASE = 0x10000u;
constexpr int DEFAULT_CONTROL_TIMEOUT_MS = 1000;
constexpr int DISCARD_DRAIN_POLL_TIMEOUT_MS = 4;
constexpr int DISCARD_DRAIN_MAX_ROUNDS = 4;
constexpr int MAX_REAP_DRAIN_ITERATIONS = 96;
constexpr uint16_t UAC2_SPEED_HINT_THRESHOLD = 0x0200u;
constexpr uint8_t USB_REQ_TYPE_STD_DEV_IN = 0x80u;
constexpr uint8_t USB_STD_REQ_GET_CONFIGURATION = 0x08u;

// ═══ Private Helpers ═══
bool isDisconnectErrno(int err) {
    return err == ENODEV || err == ESHUTDOWN || err == EBADF || err == ENOENT;
}

int ioctlRetryEintr(int fd, unsigned long request, void* arg) {
    int ret = -1;
    do {
        ret = ioctl(fd, request, arg);
    } while (ret < 0 && errno == EINTR);
    return ret;
}

} // namespace

// ═══ Device Lifecycle ═══
UsbTransport::~UsbTransport() {
    detach();
}

bool UsbTransport::attach(int fd, const std::string& devicePath, uint16_t uacVersionHint) {
    detach();
    if (fd < 0) return false;

    fd_.store(fd, std::memory_order_release);
    devicePath_ = devicePath;

#ifdef USBDEVFS_GET_SPEED
    const int speed = ioctlRetryEintr(fd, USBDEVFS_GET_SPEED, nullptr);
    if (speed > 0) {
        usbSpeed_ = speed;
        isHighSpeed_ = (speed >= USB_SPEED_HIGH);
        speedFromKernel_ = true;
    } else {
        speedFromKernel_ = false;
        isHighSpeed_ = (uacVersionHint >= UAC2_SPEED_HINT_THRESHOLD);
        usbSpeed_ = isHighSpeed_ ? USB_SPEED_HIGH : USB_SPEED_FULL;
    }
#else
    speedFromKernel_ = false;
    isHighSpeed_ = (uacVersionHint >= UAC2_SPEED_HINT_THRESHOLD);
    usbSpeed_ = isHighSpeed_ ? USB_SPEED_HIGH : USB_SPEED_FULL;
#endif

    return true;
}

void UsbTransport::applyUacSpeedFallbackHint(uint16_t uacVersionHint) {
    if (speedFromKernel_ || fd_.load(std::memory_order_acquire) < 0) return;
    isHighSpeed_ = (uacVersionHint >= UAC2_SPEED_HINT_THRESHOLD);
    usbSpeed_ = isHighSpeed_ ? USB_SPEED_HIGH : USB_SPEED_FULL;
}

void UsbTransport::detach() {
    if (fd_.load(std::memory_order_acquire) >= 0) {
        discardAllInFlightUrbs();
        releaseAllInterfaces();
    }
    freeIsoPool();
    fd_.store(-1, std::memory_order_release);
    devicePath_.clear();
    usbSpeed_ = 0;
    isHighSpeed_ = false;
    speedFromKernel_ = false;
}

// ═══ Interface & Control Transfers ═══
bool UsbTransport::getActiveConfiguration(int& outConfigValue, int& outErrno) {
    outConfigValue = -1;
    outErrno = 0;
    uint8_t cfg = 0;
    const int ret = controlTransfer(
        USB_REQ_TYPE_STD_DEV_IN,
        USB_STD_REQ_GET_CONFIGURATION,
        0,
        0,
        &cfg,
        1,
        500,
        outErrno
    );
    if (ret == 1) {
        outConfigValue = static_cast<int>(cfg);
        return true;
    }
    return false;
}

bool UsbTransport::claimInterface(int iface, int& outErrno) {
    outErrno = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 || iface < 0) {
        outErrno = EINVAL;
        return false;
    }

    if (std::find(claimedInterfaces_.begin(), claimedInterfaces_.end(), iface) != claimedInterfaces_.end()) {
        return true;
    }

    // Atomic disconnect + claim avoids a race window where snd-usb-audio rebinds before claim
    struct usbdevfs_disconnect_claim dc{};
    dc.interface = static_cast<unsigned int>(iface);
    dc.flags = 0; // Unconditionally detach any active kernel driver before claiming
    if (ioctlRetryEintr(fd, USBDEVFS_DISCONNECT_CLAIM, &dc) == 0) {
        claimedInterfaces_.push_back(iface);
        return true;
    }

    // Fallback for kernels or USB bridges where USBDEVFS_DISCONNECT_CLAIM is unsupported
    struct usbdevfs_ioctl disconnectCmd{};
    disconnectCmd.ifno = iface;
    disconnectCmd.ioctl_code = USBDEVFS_DISCONNECT;
    disconnectCmd.data = nullptr;
    (void)ioctlRetryEintr(fd, USBDEVFS_IOCTL, &disconnectCmd);

    unsigned int ifno = static_cast<unsigned int>(iface);
    if (ioctlRetryEintr(fd, USBDEVFS_CLAIMINTERFACE, &ifno) == 0) {
        claimedInterfaces_.push_back(iface);
        return true;
    }

    outErrno = errno;
    return false;
}

void UsbTransport::releaseInterface(int iface) {
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd >= 0 && iface >= 0) {
        unsigned int ifno = static_cast<unsigned int>(iface);
        (void)ioctlRetryEintr(fd, USBDEVFS_RELEASEINTERFACE, &ifno);

        // Re-attach kernel snd-usb-audio so Android Audio HAL can immediately reclaim the DAC
        struct usbdevfs_ioctl connectCmd{};
        connectCmd.ifno = iface;
        connectCmd.ioctl_code = USBDEVFS_CONNECT;
        connectCmd.data = nullptr;
        (void)ioctlRetryEintr(fd, USBDEVFS_IOCTL, &connectCmd);
    }

    claimedInterfaces_.erase(
        std::remove(claimedInterfaces_.begin(), claimedInterfaces_.end(), iface),
        claimedInterfaces_.end()
    );
}

void UsbTransport::releaseAllInterfaces() {
    // Release in reverse claim order (AudioStreaming interfaces before AudioControl)
    while (!claimedInterfaces_.empty()) {
        const int iface = claimedInterfaces_.back();
        releaseInterface(iface);
    }
}

bool UsbTransport::setInterfaceAltSetting(int iface, int altSetting, int& outErrno) {
    outErrno = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 || iface < 0 || altSetting < 0) {
        outErrno = EINVAL;
        return false;
    }

    struct usbdevfs_setinterface setIf{};
    setIf.interface = static_cast<unsigned int>(iface);
    setIf.altsetting = static_cast<unsigned int>(altSetting);
    if (ioctlRetryEintr(fd, USBDEVFS_SETINTERFACE, &setIf) != 0) {
        outErrno = errno;
        return false;
    }
    return true;
}

int UsbTransport::controlTransfer(
    uint8_t requestType,
    uint8_t request,
    uint16_t value,
    uint16_t index,
    uint8_t* data,
    uint16_t length,
    int timeoutMs,
    int& outErrno
) {
    outErrno = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0) {
        outErrno = EBADF;
        return -1;
    }

    struct usbdevfs_ctrltransfer ctrl{};
    ctrl.bRequestType = requestType;
    ctrl.bRequest = request;
    ctrl.wValue = value;
    ctrl.wIndex = index;
    ctrl.wLength = length;
    ctrl.timeout = static_cast<unsigned int>(timeoutMs > 0 ? timeoutMs : DEFAULT_CONTROL_TIMEOUT_MS);
    ctrl.data = data;

    const int ret = ioctlRetryEintr(fd, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        outErrno = errno;
    }
    return ret;
}

// ═══ Pre-Allocated Isochronous URB Pool ═══
bool UsbTransport::allocateIsoPool(const UsbIsoPoolConfig& config) {
    freeIsoPool();

    if (config.outEndpoint == 0 || config.packetsPerOutUrb <= 0 || config.allocOutPacketBytes == 0) {
        return false;
    }

    UsbIsoPoolConfig sanitized = config;
    sanitized.numOutUrbs = std::clamp(sanitized.numOutUrbs, USB_MIN_ISO_URBS, USB_MAX_ISO_URBS);
    if (sanitized.maxOutPacketBytes == 0 || sanitized.maxOutPacketBytes > sanitized.allocOutPacketBytes) {
        sanitized.maxOutPacketBytes = sanitized.allocOutPacketBytes;
    }
    if (sanitized.enableFeedback) {
        if (sanitized.feedbackEndpoint == 0) {
            sanitized.enableFeedback = false;
        } else {
            sanitized.numFeedbackUrbs = std::clamp(
                sanitized.numFeedbackUrbs, USB_MIN_FEEDBACK_URBS, USB_MAX_FEEDBACK_URBS
            );
            sanitized.packetsPerFeedbackUrb = std::clamp(
                sanitized.packetsPerFeedbackUrb, 1, USB_MAX_FEEDBACK_PACKETS_PER_URB
            );
            sanitized.feedbackPacketBytes = std::clamp(
                sanitized.feedbackPacketBytes, 3, USB_MAX_EXPLICIT_FEEDBACK_BYTES
            );
        }
    }

    const size_t urbHeaderBytes = sizeof(struct usbdevfs_urb) +
                                  static_cast<size_t>(sanitized.packetsPerOutUrb) *
                                  sizeof(struct usbdevfs_iso_packet_desc);
    const size_t urbPayloadBytes = static_cast<size_t>(sanitized.packetsPerOutUrb) *
                                   sanitized.allocOutPacketBytes;

    outUrbHeaders_.resize(static_cast<size_t>(sanitized.numOutUrbs));
    outUrbPayloads_.resize(static_cast<size_t>(sanitized.numOutUrbs));

    for (int i = 0; i < sanitized.numOutUrbs; ++i) {
        const size_t idx = static_cast<size_t>(i);
        outUrbHeaders_[idx].assign(urbHeaderBytes, 0);
        outUrbPayloads_[idx].assign(urbPayloadBytes, 0);
        // Best-effort pin URB headers and DMA payloads in RAM so memory pressure never pages them out
        mlock(outUrbHeaders_[idx].data(), outUrbHeaders_[idx].size());
        mlock(outUrbPayloads_[idx].data(), outUrbPayloads_[idx].size());

        auto* urb = reinterpret_cast<struct usbdevfs_urb*>(outUrbHeaders_[idx].data());
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = sanitized.outEndpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = outUrbPayloads_[idx].data();
        urb->buffer_length = static_cast<int>(urbPayloadBytes);
        urb->number_of_packets = sanitized.packetsPerOutUrb;
        urb->usercontext = reinterpret_cast<void*>(static_cast<uintptr_t>(i));
    }

    std::memset(outSlotInFlight_, 0, sizeof(outSlotInFlight_));
    outInFlightCount_ = 0;
    std::memset(fbSlotInFlight_, 0, sizeof(fbSlotInFlight_));
    fbInFlightCount_ = 0;

    if (sanitized.enableFeedback) {
        const size_t fbHeaderBytes = sizeof(struct usbdevfs_urb) +
                                     static_cast<size_t>(sanitized.packetsPerFeedbackUrb) *
                                     sizeof(struct usbdevfs_iso_packet_desc);
        const size_t fbPayloadBytes = static_cast<size_t>(sanitized.packetsPerFeedbackUrb) *
                                      static_cast<size_t>(sanitized.feedbackPacketBytes);
        fbUrbHeaders_.resize(static_cast<size_t>(sanitized.numFeedbackUrbs));
        fbUrbPayloads_.resize(static_cast<size_t>(sanitized.numFeedbackUrbs));

        for (int i = 0; i < sanitized.numFeedbackUrbs; ++i) {
            const size_t idx = static_cast<size_t>(i);
            fbUrbHeaders_[idx].assign(fbHeaderBytes, 0);
            fbUrbPayloads_[idx].assign(fbPayloadBytes, 0);
            mlock(fbUrbHeaders_[idx].data(), fbUrbHeaders_[idx].size());
            mlock(fbUrbPayloads_[idx].data(), fbUrbPayloads_[idx].size());

            auto* fbUrb = reinterpret_cast<struct usbdevfs_urb*>(fbUrbHeaders_[idx].data());
            fbUrb->type = USBDEVFS_URB_TYPE_ISO;
            fbUrb->endpoint = sanitized.feedbackEndpoint;
            fbUrb->flags = USBDEVFS_URB_ISO_ASAP;
            fbUrb->buffer = fbUrbPayloads_[idx].data();
            fbUrb->buffer_length = static_cast<int>(fbPayloadBytes);
            fbUrb->number_of_packets = sanitized.packetsPerFeedbackUrb;
            fbUrb->usercontext = reinterpret_cast<void*>(FEEDBACK_URB_TAG_BASE + static_cast<uintptr_t>(i));
        }
    }

    poolConfig_ = sanitized;
    return true;
}

void UsbTransport::freeIsoPool() {
    for (auto& buf : outUrbHeaders_) {
        if (!buf.empty()) munlock(buf.data(), buf.size());
    }
    for (auto& buf : outUrbPayloads_) {
        if (!buf.empty()) munlock(buf.data(), buf.size());
    }
    for (auto& buf : fbUrbHeaders_) {
        if (!buf.empty()) munlock(buf.data(), buf.size());
    }
    for (auto& buf : fbUrbPayloads_) {
        if (!buf.empty()) munlock(buf.data(), buf.size());
    }
    outUrbHeaders_.clear();
    outUrbPayloads_.clear();
    fbUrbHeaders_.clear();
    fbUrbPayloads_.clear();
    std::memset(outSlotInFlight_, 0, sizeof(outSlotInFlight_));
    outInFlightCount_ = 0;
    std::memset(fbSlotInFlight_, 0, sizeof(fbSlotInFlight_));
    fbInFlightCount_ = 0;
    poolConfig_ = UsbIsoPoolConfig{};
}

uint8_t* UsbTransport::getOutUrbPayload(int urbIndex) {
    if (urbIndex < 0 || static_cast<size_t>(urbIndex) >= outUrbPayloads_.size()) {
        return nullptr;
    }
    return outUrbPayloads_[static_cast<size_t>(urbIndex)].data();
}

size_t UsbTransport::getOutUrbPayloadCapacity() const {
    if (outUrbPayloads_.empty()) return 0;
    return outUrbPayloads_.front().size();
}

// ═══ Real-Time Isochronous I/O ═══
bool UsbTransport::submitOutUrb(int urbIndex, const uint32_t* packetLengths, int packetCount, int& outErrno) {
    outErrno = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 ||
        urbIndex < 0 ||
        static_cast<size_t>(urbIndex) >= outUrbHeaders_.size() ||
        packetLengths == nullptr ||
        packetCount <= 0 ||
        packetCount > poolConfig_.packetsPerOutUrb) {
        outErrno = EINVAL;
        return false;
    }

    const size_t idx = static_cast<size_t>(urbIndex);
    const size_t payloadCap = outUrbPayloads_[idx].size();
    auto* urb = reinterpret_cast<struct usbdevfs_urb*>(outUrbHeaders_[idx].data());

    urb->status = 0;
    urb->actual_length = 0;
    urb->start_frame = 0;
    urb->error_count = 0;
    urb->number_of_packets = packetCount;

    size_t totalBytes = 0;
    for (int p = 0; p < packetCount; ++p) {
        const uint32_t pktLen = packetLengths[p];
        if (pktLen > poolConfig_.allocOutPacketBytes || totalBytes + pktLen > payloadCap) {
            outErrno = EINVAL;
            return false;
        }
        urb->iso_frame_desc[p].length = pktLen;
        urb->iso_frame_desc[p].actual_length = 0;
        urb->iso_frame_desc[p].status = 0;
        totalBytes += pktLen;
    }

    urb->buffer_length = static_cast<int>(totalBytes);

    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) != 0) {
        outErrno = errno;
        return false;
    }
    if (urbIndex >= 0 && urbIndex < USB_MAX_ISO_URBS && !outSlotInFlight_[urbIndex]) {
        outSlotInFlight_[urbIndex] = true;
        outInFlightCount_++;
    }
    return true;
}

bool UsbTransport::isFeedbackSlotInFlight(int fbIndex) const {
    if (fbIndex < 0 || fbIndex >= USB_MAX_FEEDBACK_URBS) return false;
    return fbSlotInFlight_[fbIndex];
}

bool UsbTransport::submitFeedbackUrb(int fbIndex, int& outErrno) {
    outErrno = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 ||
        !poolConfig_.enableFeedback ||
        fbIndex < 0 ||
        fbIndex >= USB_MAX_FEEDBACK_URBS ||
        static_cast<size_t>(fbIndex) >= fbUrbHeaders_.size() ||
        fbUrbHeaders_[static_cast<size_t>(fbIndex)].empty() ||
        fbSlotInFlight_[fbIndex]) {
        outErrno = EINVAL;
        return false;
    }

    const size_t idx = static_cast<size_t>(fbIndex);
    auto& payload = fbUrbPayloads_[idx];
    std::memset(payload.data(), 0, payload.size());

    auto* fbUrb = reinterpret_cast<struct usbdevfs_urb*>(fbUrbHeaders_[idx].data());
    fbUrb->status = 0;
    fbUrb->actual_length = 0;
    fbUrb->start_frame = 0;
    fbUrb->error_count = 0;
    fbUrb->buffer_length = static_cast<int>(payload.size());
    fbUrb->number_of_packets = poolConfig_.packetsPerFeedbackUrb;
    for (int p = 0; p < poolConfig_.packetsPerFeedbackUrb; ++p) {
        fbUrb->iso_frame_desc[p].length = static_cast<unsigned int>(poolConfig_.feedbackPacketBytes);
        fbUrb->iso_frame_desc[p].actual_length = 0;
        fbUrb->iso_frame_desc[p].status = 0;
    }

    if (ioctl(fd, USBDEVFS_SUBMITURB, fbUrb) != 0) {
        outErrno = errno;
        return false;
    }

    fbSlotInFlight_[fbIndex] = true;
    fbInFlightCount_++;
    return true;
}

bool UsbTransport::discardFeedbackUrb(int fbIndex) {
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 ||
        fbIndex < 0 ||
        fbIndex >= USB_MAX_FEEDBACK_URBS ||
        static_cast<size_t>(fbIndex) >= fbUrbHeaders_.size() ||
        fbUrbHeaders_[static_cast<size_t>(fbIndex)].empty() ||
        !fbSlotInFlight_[fbIndex]) {
        return false;
    }

    auto* fbUrb = reinterpret_cast<struct usbdevfs_urb*>(fbUrbHeaders_[static_cast<size_t>(fbIndex)].data());
    if (ioctl(fd, USBDEVFS_DISCARDURB, fbUrb) != 0) {
        const int err = errno;
        if (isDisconnectErrno(err)) {
            fbSlotInFlight_[fbIndex] = false;
            if (fbInFlightCount_ > 0) fbInFlightCount_--;
        }
        return false;
    }
    return true;
}

void UsbTransport::discardAllFeedbackUrbs() {
    for (int i = 0; i < static_cast<int>(fbUrbHeaders_.size()); ++i) {
        if (fbSlotInFlight_[i]) {
            discardFeedbackUrb(i);
        }
    }
}

void UsbTransport::discardAllInFlightUrbs() {
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0 || (outInFlightCount_ <= 0 && fbInFlightCount_ <= 0)) {
        std::memset(outSlotInFlight_, 0, sizeof(outSlotInFlight_));
        outInFlightCount_ = 0;
        std::memset(fbSlotInFlight_, 0, sizeof(fbSlotInFlight_));
        fbInFlightCount_ = 0;
        return;
    }

    bool anyDiscarded = false;
    bool disconnected = false;

    for (size_t i = 0; i < outUrbHeaders_.size(); ++i) {
        if (i >= static_cast<size_t>(USB_MAX_ISO_URBS) || !outSlotInFlight_[i]) continue;
        auto& hdr = outUrbHeaders_[i];
        if (hdr.empty()) continue;
        auto* urb = reinterpret_cast<struct usbdevfs_urb*>(hdr.data());
        if (ioctl(fd, USBDEVFS_DISCARDURB, urb) == 0) {
            anyDiscarded = true;
        } else if (isDisconnectErrno(errno)) {
            disconnected = true;
            break;
        }
    }

    if (!disconnected) {
        for (size_t i = 0; i < fbUrbHeaders_.size(); ++i) {
            if (i >= static_cast<size_t>(USB_MAX_FEEDBACK_URBS) || !fbSlotInFlight_[i]) continue;
            auto& hdr = fbUrbHeaders_[i];
            if (hdr.empty()) continue;
            auto* fbUrb = reinterpret_cast<struct usbdevfs_urb*>(hdr.data());
            if (ioctl(fd, USBDEVFS_DISCARDURB, fbUrb) == 0) {
                anyDiscarded = true;
            } else if (isDisconnectErrno(errno)) {
                disconnected = true;
                break;
            }
        }
    }

    // Drain completed and asynchronously unlinked URBs before releasing or reusing pool memory
    int drainIterations = 0;
    for (int round = 0; round < DISCARD_DRAIN_MAX_ROUNDS && drainIterations < MAX_REAP_DRAIN_ITERATIONS; ++round) {
        bool reapedAny = false;
        while (drainIterations < MAX_REAP_DRAIN_ITERATIONS) {
            struct usbdevfs_urb* reaped = nullptr;
            if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped) != 0) {
                const int err = errno;
                if (isDisconnectErrno(err)) {
                    disconnected = true;
                }
                break;
            }
            if (reaped == nullptr) break;
            reapedAny = true;
            drainIterations++;
            const uintptr_t tag = reinterpret_cast<uintptr_t>(reaped->usercontext);
            if (tag >= FEEDBACK_URB_TAG_BASE) {
                const int fbIdx = static_cast<int>(tag - FEEDBACK_URB_TAG_BASE);
                if (fbIdx >= 0 && fbIdx < USB_MAX_FEEDBACK_URBS && fbSlotInFlight_[fbIdx]) {
                    fbSlotInFlight_[fbIdx] = false;
                    if (fbInFlightCount_ > 0) fbInFlightCount_--;
                }
            } else {
                const int outIdx = static_cast<int>(tag);
                if (outIdx >= 0 && outIdx < USB_MAX_ISO_URBS && outSlotInFlight_[outIdx]) {
                    outSlotInFlight_[outIdx] = false;
                    if (outInFlightCount_ > 0) outInFlightCount_--;
                }
            }
        }

        if (disconnected || (outInFlightCount_ <= 0 && fbInFlightCount_ <= 0) || (!anyDiscarded && !reapedAny)) {
            break;
        }

        // Wait briefly for asynchronous kernel usb_unlink_urb completions to surface in the reap queue
        struct pollfd pfd{};
        pfd.fd = fd;
        pfd.events = POLLOUT | POLLIN;
        const int pollRet = poll(&pfd, 1, DISCARD_DRAIN_POLL_TIMEOUT_MS);
        if (pollRet <= 0 || (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            break;
        }
        anyDiscarded = false;
    }

    std::memset(outSlotInFlight_, 0, sizeof(outSlotInFlight_));
    outInFlightCount_ = 0;
    std::memset(fbSlotInFlight_, 0, sizeof(fbSlotInFlight_));
    fbInFlightCount_ = 0;
}

int UsbTransport::pollUrbs(bool watchFeedbackIn, int timeoutMs, short& outRevents) {
    outRevents = 0;
    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0) {
        outRevents = POLLNVAL;
        return -1;
    }

    struct pollfd pfd{};
    pfd.fd = fd;
    pfd.events = watchFeedbackIn ? (POLLOUT | POLLIN) : POLLOUT;

    const int ret = poll(&pfd, 1, timeoutMs);
    outRevents = pfd.revents;
    return ret;
}

int UsbTransport::reapUrbNonBlocking(UsbReapedUrb& outEvent, int& outErrno) {
    outEvent = UsbReapedUrb{};
    outErrno = 0;

    const int fd = fd_.load(std::memory_order_acquire);
    if (fd < 0) {
        outErrno = EBADF;
        return -1;
    }

    struct usbdevfs_urb* reaped = nullptr;
    if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &reaped) != 0) {
        const int err = errno;
        outErrno = err;
        if (err == EAGAIN || err == EWOULDBLOCK || err == EINTR) {
            return 0;
        }
        return -1;
    }

    if (reaped == nullptr) {
        return 0;
    }

    outEvent.status = reaped->status;
    const uintptr_t tag = reinterpret_cast<uintptr_t>(reaped->usercontext);
    if (tag >= FEEDBACK_URB_TAG_BASE) {
        const int fbIdx = static_cast<int>(tag - FEEDBACK_URB_TAG_BASE);
        if (fbIdx >= 0 && fbIdx < USB_MAX_FEEDBACK_URBS && fbSlotInFlight_[fbIdx]) {
            fbSlotInFlight_[fbIdx] = false;
            if (fbInFlightCount_ > 0) fbInFlightCount_--;
        }
        outEvent.isFeedback = true;
        outEvent.urbIndex = fbIdx;

        // Scan ISO IN packet descriptors from newest to oldest to pick the freshest valid 3/4-byte
        // feedback payload while ignoring interleaved ZLPs (actual_length == 0).
        const int pktCount = std::min(reaped->number_of_packets, poolConfig_.packetsPerFeedbackUrb);
        const bool validSlot = (fbIdx >= 0 && static_cast<size_t>(fbIdx) < fbUrbPayloads_.size());
        const uint8_t* baseBuf = validSlot ? fbUrbPayloads_[static_cast<size_t>(fbIdx)].data() : nullptr;
        const size_t stride = static_cast<size_t>(std::max(1, poolConfig_.feedbackPacketBytes));

        bool foundPayload = false;
        int firstErrStatus = 0;
        for (int p = pktCount - 1; p >= 0; --p) {
            const int st = reaped->iso_frame_desc[p].status;
            const uint32_t len = reaped->iso_frame_desc[p].actual_length;
            if (st == 0 && (len == 4u || len == 3u) && baseBuf != nullptr) {
                outEvent.packet0Status = 0;
                outEvent.packet0ActualLength = len;
                outEvent.feedbackData = baseBuf + static_cast<size_t>(p) * stride;
                foundPayload = true;
                break;
            }
            if (st != 0 && firstErrStatus == 0) {
                firstErrStatus = st;
            }
        }
        if (!foundPayload) {
            outEvent.packet0Status = firstErrStatus;
            outEvent.packet0ActualLength = 0;
            outEvent.feedbackData = baseBuf;
        }
    } else {
        const int outIdx = static_cast<int>(tag);
        if (outIdx >= 0 && outIdx < USB_MAX_ISO_URBS && outSlotInFlight_[outIdx]) {
            outSlotInFlight_[outIdx] = false;
            if (outInFlightCount_ > 0) outInFlightCount_--;
        }
        outEvent.isFeedback = false;
        outEvent.urbIndex = outIdx;
        if (reaped->number_of_packets > 0) {
            outEvent.packet0Status = reaped->iso_frame_desc[0].status;
            outEvent.packet0ActualLength = reaped->iso_frame_desc[0].actual_length;
        }
    }

    return 1;
}