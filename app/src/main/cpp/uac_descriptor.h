/**
 * uac_descriptor — USB Audio Class (UAC 1.0 / 2.0 / 3.0) raw descriptor parser contracts.
 *
 * @remarks
 * Parses raw configuration descriptor bytes directly because Android's Java USB host APIs
 * strip class-specific `CS_INTERFACE` descriptors, omitting UAC 2.0/3.0 clock source and
 * clock selector entities, Feature Unit control bitmaps, Native DSD `RAW_DATA` alternate
 * settings, PCM subslot/bit-resolution layouts, and UAC 1.0 discrete sample-rate tables.
 *
 * @see UsbAudioDriver
 * @see AudioDspPipeline
 * @see UsbDacNative
 */
#pragma once

// ═══ Includes ═══
#include <cstddef>
#include <cstdint>
#include <vector>

// ═══ Constants ═══
constexpr uint16_t UAC_VERSION_1_0 = 0x0100u;
constexpr uint16_t UAC_VERSION_2_0 = 0x0200u;
constexpr uint16_t UAC_VERSION_3_0 = 0x0300u;

// ═══ Types & State ═══
enum class UacSyncType : uint8_t {
    NONE = 0,
    ASYNCHRONOUS = 1,
    ADAPTIVE = 2,
    SYNCHRONOUS = 3
};

enum class UacDsdFormat : uint8_t {
    NONE = 0,
    U8_MSB = 1,   // 8-bit MSB-first packed RAW_DATA (standard UAC2/XMOS/Amanero)
    U32_BE = 2,   // 32-bit big-endian grouped DSD frames
    U32_LE = 3    // 32-bit little-endian grouped DSD frames
};

struct UacTerminalInfo {
    int terminalId = -1;
    uint16_t terminalType = 0;
    int assocTerminal = 0;
    int sourceId = -1;
    int clockSourceId = -1;
    int channels = 2;
    uint32_t channelConfig = 0;
    bool isInput = false;
};

struct UacFeatureUnitInfo {
    int unitId = -1;
    int sourceId = -1;
    uint32_t masterControls = 0;
    std::vector<uint32_t> channelControls;
    bool hasMasterMute = false;
    bool hasMasterVolume = false;
    bool hasChannelMute = false;
    bool hasChannelVolume = false;
};

struct UacClockSourceInfo {
    int clockId = -1;
    uint8_t attributes = 0;
    uint8_t controls = 0;
    int assocTerminal = 0;
    bool isProgrammable = false;
    std::vector<int> supportedSampleRates; // Populated at runtime via UAC 2.0/3.0 RANGE / GET_CUR
};

struct UacClockSelectorInfo {
    int selectorId = -1;
    std::vector<int> inputPinIds;
};

struct UacClockMultiplierInfo {
    int multiplierId = -1;
    int sourceClockId = -1;
};

struct UacStreamAltSetting {
    int interfaceNumber = -1;
    int alternateSetting = 0;
    int terminalLink = -1;
    int clockEntityId = -1;            // Clock Source/Selector/Multiplier resolved from terminalLink
    int channels = 2;
    int subslotSize = 2;        // Bytes per sample slot on USB wire (2, 3, 4)
    int bitResolution = 16;     // Valid sample bits (16, 24, 32)
    uint8_t formatType = 1;     // Type I = 1, Type III = 3, etc.
    uint32_t bmFormats = 0;     // UAC2/3 format bitmask (bit 0 = PCM, bit 31 = RAW_DATA)
    bool isPcm = true;
    bool isDsd = false;
    UacDsdFormat dsdFormat = UacDsdFormat::NONE;
    uint8_t outEndpoint = 0;
    uint16_t maxPacketSize = 0;
    uint8_t interval = 1;
    bool hasSamFreqControl = true;     // UAC 1.0 CS_ENDPOINT bmAttributes bit 0 (SAM_FREQ_CONTROL)
    UacSyncType syncType = UacSyncType::NONE;
    uint8_t feedbackEndpoint = 0;
    uint8_t feedbackInterval = 1;
    uint8_t feedbackRefresh = 0;       // UAC 1.0 endpoint bRefresh (2^bRefresh ms period)
    uint8_t feedbackUsageType = 0;     // 0 = Data/UAC1, 1 = Explicit Feedback, 2 = Implicit Feedback Data
    uint16_t feedbackMaxPacketSize = 0;
    std::vector<int> fixedSampleRates; // Populated for UAC1 or fixed-rate alt settings
};

struct UacDeviceDescriptorInfo {
    uint16_t vendorId = 0;             // Extracted from leading USB Device Descriptor (0x01)
    uint16_t productId = 0;
    uint8_t activeConfigValue = 1;     // bConfigurationValue of the selected USB configuration
    uint16_t uacVersion = 0;           // UAC_VERSION_1_0, UAC_VERSION_2_0, or UAC_VERSION_3_0
    bool isHighSpeed = true;           // Updated from kernel USBDEVFS_GET_SPEED on attach
    bool isUac3Badd = false;           // UAC 3.0 Basic Audio Device Definition profile
    uint8_t baddProfile = 0;
    int controlInterface = -1;
    int clockSourceId = -1;            // Primary playback clock source entity (UAC 2.0/3.0)
    int clockSelectorId = -1;
    int featureUnitId = -1;            // Playback path Feature Unit (wired to playback Output Terminal)
    bool hasHardwareVolume = false;
    bool hasHardwareMute = false;
    bool volumeMasterChannel = true;
    int volumeMinDb256 = 0;
    int volumeMaxDb256 = 0;
    int volumeResDb256 = 256;
    int maxSupportedChannels = 2;
    bool supportsNativeDsd = false;
    bool supportsDop = false;
    int maxDsdRate = 0;                // Highest supported DSD multiplier (64, 128, 256, 512, or 0)

    std::vector<int> playbackClockSourceIds; // All playback Clock Source IDs (including dual-crystal selector pins)
    std::vector<UacTerminalInfo> inputTerminals;
    std::vector<UacTerminalInfo> outputTerminals;
    std::vector<UacFeatureUnitInfo> featureUnits;
    std::vector<UacClockSourceInfo> clockSources;
    std::vector<UacClockSelectorInfo> clockSelectors;
    std::vector<UacClockMultiplierInfo> clockMultipliers;
    std::vector<UacStreamAltSetting> streamAltSettings;
    std::vector<int> supportedSampleRates; // Merged / queried PCM sample rates
};

// ═══ Public API ═══
UacDeviceDescriptorInfo parseUacDescriptors(
    const uint8_t* data,
    size_t length,
    int targetConfigValue = -1
);

/**
 * Recomputes derived capability flags (`maxSupportedChannels`, `supportsNativeDsd`,
 * `supportsDop`, `maxDsdRate`) after runtime sample-rate queries complete for UAC 2.0/3.0.
 */
void finalizeUacCapabilities(UacDeviceDescriptorInfo& info);