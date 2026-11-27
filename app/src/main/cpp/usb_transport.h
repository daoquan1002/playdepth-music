/**
 * usb_transport — Direct Linux usbdevfs ioctl transport and pre-allocated Isochronous URB pool.
 *
 * @remarks
 * Interfaces directly with Android's kernel `usbdevfs` file descriptor (`USBDEVFS_DISCONNECT_CLAIM`,
 * `USBDEVFS_CONTROL`, `USBDEVFS_SETINTERFACE`, `USBDEVFS_SUBMITURB`, `USBDEVFS_REAPURBNDELAY`,
 * `USBDEVFS_DISCARDURB`) without external third-party libraries. Pre-allocates a pool of 4–9
 * Isochronous OUT URBs (keeping total in-flight HS packets <= 224 to respect MediaTek MUSB QMU
 * TX descriptor limits) plus an optional Feedback IN URB before entering the real-time loop so
 * streaming runs with zero heap allocations.
 *
 * @see UsbAudioDriver
 * @see ClockSyncEngine
 */
#pragma once

// ═══ Includes ═══
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <linux/usb/ch9.h>
#include <linux/usbdevice_fs.h>
#include <string>
#include <vector>

// ═══ Constants ═══
constexpr int USB_MIN_ISO_URBS = 4;
constexpr int USB_DEFAULT_ISO_URBS = 10;      // 10 * 10 ms = 100 ms on Full-Speed UAC 1.0
constexpr int USB_MEDIATEK_MAX_ISO_URBS = 13; // Safe fallback: 13 * 16 = 208 HS packets (26 ms) <= 224 legacy MUSB QMU limit
constexpr int USB_MAX_ISO_URBS = 16;          // 16 * 24..32 = 48..64 ms on xHCI/DWC3 and modern MTK mtu3/xHCI
constexpr int USB_PACKETS_PER_URB_HS = 16;    // 16 * 125 us = 2 ms per URB (legacy MediaTek MUSB-safe)
constexpr int USB_PACKETS_PER_URB_HS_MED = 24; // 24 * 125 us = 3 ms per URB (16 * 24 = 48 ms)
constexpr int USB_PACKETS_PER_URB_HS_MAX = 32; // 32 * 125 us = 4 ms per URB (16 * 32 = 64 ms)
constexpr int USB_PACKETS_PER_URB_FS = 10;    // 10 * 1 ms = 10 ms per URB
constexpr int USB_MIN_FEEDBACK_URBS = 4;
constexpr int USB_DEFAULT_FEEDBACK_URBS = 6;  // Continuous 4..8 in-flight Feedback IN URBs on ep_fb
constexpr int USB_MAX_FEEDBACK_URBS = 8;
constexpr int USB_MAX_FEEDBACK_PACKETS_PER_URB = 8;
constexpr int USB_MAX_FEEDBACK_BYTES = 8;
constexpr int USB_MAX_EXPLICIT_FEEDBACK_BYTES = 64; // Quirky UAC2 DACs with usageType=1 and wMaxPacketSize=16..64

// ═══ Types & State ═══
struct UsbIsoPoolConfig {
    uint8_t outEndpoint = 0;
    uint8_t feedbackEndpoint = 0;
    int numOutUrbs = USB_DEFAULT_ISO_URBS;
    int packetsPerOutUrb = USB_PACKETS_PER_URB_HS;
    size_t maxOutPacketBytes = 0;
    size_t allocOutPacketBytes = 0;
    bool enableFeedback = false;
    int numFeedbackUrbs = USB_DEFAULT_FEEDBACK_URBS;
    int packetsPerFeedbackUrb = 1;
    int feedbackPacketBytes = 4;
};

struct UsbReapedUrb {
    bool isFeedback = false;
    int urbIndex = -1;
    int status = 0;
    int packet0Status = 0;
    uint32_t packet0ActualLength = 0;
    const uint8_t* feedbackData = nullptr;
};

// ═══ USB Transport Interface ═══
class UsbTransport {
public:
    UsbTransport() = default;
    ~UsbTransport();

    UsbTransport(const UsbTransport&) = delete;
    UsbTransport& operator=(const UsbTransport&) = delete;

    // ─── Device Lifecycle ───
    bool attach(int fd, const std::string& devicePath, uint16_t uacVersionHint);
    void applyUacSpeedFallbackHint(uint16_t uacVersionHint);
    void detach();

    bool isAttached() const { return fd_.load(std::memory_order_acquire) >= 0; }
    int fd() const { return fd_.load(std::memory_order_acquire); }
    const std::string& devicePath() const { return devicePath_; }
    bool isHighSpeed() const { return isHighSpeed_; }
    int usbSpeed() const { return usbSpeed_; }

    // ─── Interface & Control Transfers ───
    bool getActiveConfiguration(int& outConfigValue, int& outErrno);
    bool claimInterface(int iface, int& outErrno);
    void releaseInterface(int iface);
    void releaseAllInterfaces();
    bool setInterfaceAltSetting(int iface, int altSetting, int& outErrno);

    int controlTransfer(
        uint8_t requestType,
        uint8_t request,
        uint16_t value,
        uint16_t index,
        uint8_t* data,
        uint16_t length,
        int timeoutMs,
        int& outErrno
    );

    // ─── Pre-Allocated Isochronous URB Pool (Alloc Outside RT Loop) ───
    bool allocateIsoPool(const UsbIsoPoolConfig& config);
    void freeIsoPool();

    int numOutUrbs() const { return poolConfig_.numOutUrbs; }
    int packetsPerOutUrb() const { return poolConfig_.packetsPerOutUrb; }
    size_t maxOutPacketBytes() const { return poolConfig_.maxOutPacketBytes; }
    int numFeedbackUrbs() const { return poolConfig_.enableFeedback ? poolConfig_.numFeedbackUrbs : 0; }
    int packetsPerFeedbackUrb() const { return poolConfig_.enableFeedback ? poolConfig_.packetsPerFeedbackUrb : 0; }
    uint8_t* getOutUrbPayload(int urbIndex);
    size_t getOutUrbPayloadCapacity() const;

    // ─── Real-Time Isochronous I/O (Zero Heap Allocations, Zero Mutexes) ───
    bool submitOutUrb(int urbIndex, const uint32_t* packetLengths, int packetCount, int& outErrno);
    bool submitFeedbackUrb(int fbIndex, int& outErrno);
    bool discardFeedbackUrb(int fbIndex);
    void discardAllFeedbackUrbs();
    void discardAllInFlightUrbs();

    bool isFeedbackInFlight() const { return fbInFlightCount_ > 0; }
    bool isFeedbackSlotInFlight(int fbIndex) const;
    int feedbackInFlightCount() const { return fbInFlightCount_; }

    int pollUrbs(bool watchFeedbackIn, int timeoutMs, short& outRevents);

    /**
     * Reaps one completed URB without blocking.
     * Returns `1` if a URB was reaped into `outEvent`, `0` if none ready (`EAGAIN`),
     * or `-1` on fatal device disconnect/error (`outErrno` set).
     */
    int reapUrbNonBlocking(UsbReapedUrb& outEvent, int& outErrno);

private:
    std::atomic<int> fd_{-1};
    std::string devicePath_;
    int usbSpeed_ = 0;
    bool isHighSpeed_ = true;
    bool speedFromKernel_ = false;
    std::vector<int> claimedInterfaces_;

    UsbIsoPoolConfig poolConfig_{};
    std::vector<std::vector<uint8_t>> outUrbHeaders_;
    std::vector<std::vector<uint8_t>> outUrbPayloads_;
    std::vector<std::vector<uint8_t>> fbUrbHeaders_;
    std::vector<std::vector<uint8_t>> fbUrbPayloads_;
    bool outSlotInFlight_[USB_MAX_ISO_URBS]{};
    int outInFlightCount_ = 0;
    bool fbSlotInFlight_[USB_MAX_FEEDBACK_URBS]{};
    int fbInFlightCount_ = 0;
};