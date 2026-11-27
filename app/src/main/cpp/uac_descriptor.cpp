/**
 * uac_descriptor — Raw USB Audio Class (UAC 1.0 / 2.0 / 3.0) descriptor parser and topology resolver.
 *
 * @remarks
 * Walks raw concatenated USB descriptors sequentially by `bLength` because Android's
 * `UsbDeviceConnection.getRawDescriptors()` returns an unparsed byte stream that may
 * prepend the Device descriptor (`0x01`) or span multiple USB configurations on dual-mode
 * UAC1/UAC2 DACs. Resolves the playback signal graph backwards from Speaker/Headphone
 * Output Terminals (`0x03xx`) so Feature Unit hardware volume and Clock Source frequency
 * control transfers never target microphone capture paths on hybrid USB DAC/headset dongles.
 *
 * @see uac_descriptor.h
 * @see UsbAudioDriver
 * @see AudioDspPipeline
 */

// ═══ Includes ═══
#include "uac_descriptor.h"

#include <algorithm>
#include <cmath>
#include <set>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace {

// ═══ Constants ═══
constexpr uint8_t USB_DESC_DEVICE = 0x01u;
constexpr uint8_t USB_DESC_CONFIGURATION = 0x02u;
constexpr uint8_t USB_DESC_INTERFACE = 0x04u;
constexpr uint8_t USB_DESC_ENDPOINT = 0x05u;
constexpr uint8_t USB_DESC_IAD = 0x0Bu;
constexpr uint8_t USB_DESC_CS_INTERFACE = 0x24u;
constexpr uint8_t USB_DESC_CS_ENDPOINT = 0x25u;
constexpr uint8_t USB_DESC_SS_ENDPOINT_COMP = 0x30u;

constexpr uint8_t USB_CLASS_AUDIO = 0x01u;
constexpr uint8_t USB_SUBCLASS_AUDIOCONTROL = 0x01u;
constexpr uint8_t USB_SUBCLASS_AUDIOSTREAMING = 0x02u;

constexpr uint8_t USB_PROTO_UAC1 = 0x00u;
constexpr uint8_t USB_PROTO_UAC2 = 0x20u;
constexpr uint8_t USB_PROTO_UAC3 = 0x30u;

// AudioControl CS_INTERFACE subtypes
constexpr uint8_t AC_HEADER = 0x01u;
constexpr uint8_t AC_INPUT_TERMINAL = 0x02u;
constexpr uint8_t AC_OUTPUT_TERMINAL = 0x03u;
constexpr uint8_t AC_MIXER_UNIT = 0x04u;
constexpr uint8_t AC_SELECTOR_UNIT = 0x05u;
constexpr uint8_t AC_FEATURE_UNIT = 0x06u;
constexpr uint8_t AC_PROCESSING_UNIT = 0x07u;
constexpr uint8_t AC_EXTENSION_UNIT = 0x08u;
constexpr uint8_t AC_CLOCK_SOURCE = 0x0Au;
constexpr uint8_t AC_CLOCK_SELECTOR = 0x0Bu;
constexpr uint8_t AC_CLOCK_MULTIPLIER = 0x0Cu;

// AudioStreaming CS_INTERFACE & CS_ENDPOINT subtypes
constexpr uint8_t AS_GENERAL = 0x01u;
constexpr uint8_t AS_FORMAT_TYPE = 0x02u;
constexpr uint8_t EP_GENERAL = 0x01u;

// USB Audio Terminal Types
constexpr uint16_t TERMINAL_USB_STREAMING = 0x0101u;
constexpr uint16_t TERMINAL_OUTPUT_CLASS_MASK = 0xFF00u;
constexpr uint16_t TERMINAL_OUTPUT_SPEAKER_CLASS = 0x0300u;
constexpr uint16_t TERMINAL_BIDIR_HEADSET = 0x0402u;
constexpr uint16_t TERMINAL_EXT_ANALOG = 0x0601u;
constexpr uint16_t TERMINAL_EXT_SPDIF = 0x0605u;

// Standardized UAC 3.0 BADD fixed entity IDs (USB Audio 3.0 BADD spec section 3.3)
constexpr int UAC3_BADD_CLOCK_SOURCE_ID = 1;
constexpr int UAC3_BADD_PLAYBACK_IT_ID = 2;
constexpr int UAC3_BADD_PLAYBACK_FU_ID = 5;
constexpr int UAC3_BADD_DEFAULT_RATE_HZ = 48000;

// UAC Format Tags & Bitmasks
constexpr uint16_t UAC1_FORMAT_TAG_PCM = 0x0001u;
constexpr uint32_t UAC2_FORMAT_BIT_PCM = 0x00000001u;
constexpr uint32_t UAC2_FORMAT_BIT_RAW_DATA = 0x80000000u;

// UAC 2.0 / 3.0 2-bit control field values (0b11 = host programmable read/write)
constexpr uint32_t UAC2_CTRL_RW = 0x03u;
constexpr uint32_t UAC2_CTRL_MASK = 0x03u;

// Standard PCM sample rates for expanding continuous UAC1 frequency ranges
constexpr int STANDARD_SAMPLE_RATES[] = {
    44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
};

constexpr int DSD_BASE_RATE_HZ = 44100;
constexpr int STANDARD_DSD_MULTIPLIERS[] = { 1024, 512, 256, 128, 64 };

// ═══ Private Helpers ═══
uint16_t readU16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) |
           static_cast<uint16_t>(static_cast<uint16_t>(p[1]) << 8);
}

uint32_t readU24(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16);
}

uint32_t readU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint32_t readVarWidthControl(const uint8_t* p, uint8_t width) {
    uint32_t value = 0;
    const uint8_t clamped = std::min<uint8_t>(width, 4u);
    for (uint8_t i = 0; i < clamped; ++i) {
        value |= static_cast<uint32_t>(p[i]) << (i * 8u);
    }
    return value;
}

/**
 * Identifies known USB DAC bridges whose 32-bit Native DSD RAW_DATA endpoints expect
 * little-endian 32-bit word packing (`U32_LE`) rather than big-endian (`U32_BE`).
 * Matches Linux `snd-usb-audio` quirk definitions so Amanero/Denon/Marantz/NuPrime/T+A/etc.
 * never emit full-scale noise in Native DSD mode.
 */
bool isKnownDsdU32LeDevice(uint16_t vendorId, uint16_t productId) {
    switch (vendorId) {
        case 0x16D0u: // Amanero Combo384
            return productId == 0x071Au;
        case 0x0D8Cu: // C-Media / Hegel DSD_U32_LE bridge
            return productId == 0x0316u;
        case 0x154Eu: // Denon / Marantz Native DSD LE models
            return productId == 0x3005u || productId == 0x3006u || productId == 0x300Bu ||
                   productId == 0x500Eu || productId == 0x500Fu;
        case 0x16B0u: // NuPrime DAC-10
            return productId == 0x06B2u;
        case 0x25C6u: // ExaDevices
            return productId == 0x0002u;
        case 0x2AB6u: // T+A Elektroakustik
            return true;
        case 0x0451u: // TI / Burr-Brown U32_LE DSD bridge
            return true;
        default:
            return false;
    }
}

UacDsdFormat deduceDsdWireFormat(const UacStreamAltSetting& alt, uint16_t vendorId, uint16_t productId) {
    if (!alt.isDsd) return UacDsdFormat::NONE;
    if (alt.subslotSize == 4 || alt.bitResolution == 32) {
        return isKnownDsdU32LeDevice(vendorId, productId)
            ? UacDsdFormat::U32_LE
            : UacDsdFormat::U32_BE;
    }
    return UacDsdFormat::U8_MSB;
}

int roundDownToStandardDsdMultiplier(int64_t rawMultiplier) {
    for (int mult : STANDARD_DSD_MULTIPLIERS) {
        if (rawMultiplier >= mult) return mult;
    }
    return 0;
}

bool altFitsWireBandwidth(
    const UacDeviceDescriptorInfo& info,
    const UacStreamAltSetting& alt,
    int wireSampleRate
) {
    if (alt.maxPacketSize == 0u || alt.channels <= 0 || alt.subslotSize <= 0 || wireSampleRate <= 0) {
        return true;
    }
    const uint32_t intervalsPerSec = info.isHighSpeed ? 8000u : 1000u;
    const uint32_t shift = (alt.interval > 0u)
        ? std::min<uint32_t>(static_cast<uint32_t>(alt.interval - 1u), 15u)
        : 0u;
    const double pps = static_cast<double>(intervalsPerSec) / static_cast<double>(1u << shift);
    if (pps <= 0.0) return true;
    const size_t requiredFrames = static_cast<size_t>(std::ceil(static_cast<double>(wireSampleRate) / pps));
    const size_t frameBytes = static_cast<size_t>(alt.channels) * static_cast<size_t>(alt.subslotSize);
    return static_cast<size_t>(alt.maxPacketSize) >= (requiredFrames * frameBytes);
}

bool isPrimarySpeakerOutputTerminal(uint16_t terminalType) {
    return (terminalType & TERMINAL_OUTPUT_CLASS_MASK) == TERMINAL_OUTPUT_SPEAKER_CLASS;
}

bool isPlaybackOutputTerminal(uint16_t terminalType) {
    if (isPrimarySpeakerOutputTerminal(terminalType)) return true;
    if (terminalType == TERMINAL_BIDIR_HEADSET) return true;
    // Desktop DACs and USB-to-SPDIF DDCs commonly terminate playback at External Terminals (0x0601..0x0605)
    return terminalType >= TERMINAL_EXT_ANALOG && terminalType <= TERMINAL_EXT_SPDIF;
}

void recordUnitParents(
    std::unordered_map<int, std::vector<int>>& unitParents,
    int unitId,
    const std::vector<int>& sources
) {
    if (unitId < 0 || sources.empty()) return;
    auto& dst = unitParents[unitId];
    for (int src : sources) {
        if (src >= 0 && std::find(dst.begin(), dst.end(), src) == dst.end()) {
            dst.push_back(src);
        }
    }
}

const UacFeatureUnitInfo* findFeatureUnitById(
    const std::vector<UacFeatureUnitInfo>& featureUnits,
    int unitId
) {
    for (const auto& fu : featureUnits) {
        if (fu.unitId == unitId) return &fu;
    }
    return nullptr;
}

const UacClockSourceInfo* findClockSourceById(
    const std::vector<UacClockSourceInfo>& clockSources,
    int clockId
) {
    for (const auto& cs : clockSources) {
        if (cs.clockId == clockId) return &cs;
    }
    return nullptr;
}

/**
 * Walks backwards from `startEntityId` across Mixer/Selector/Processing/Extension/Feature
 * nodes to locate a playback-path Feature Unit, preferring one with volume control.
 */
const UacFeatureUnitInfo* traceUpstreamFeatureUnit(
    int startEntityId,
    const std::vector<UacFeatureUnitInfo>& featureUnits,
    const std::unordered_map<int, std::vector<int>>& unitParents
) {
    if (startEntityId < 0) return nullptr;
    std::vector<int> frontier;
    std::unordered_set<int> visited;
    frontier.push_back(startEntityId);
    visited.insert(startEntityId);

    const UacFeatureUnitInfo* fallbackFu = nullptr;
    for (int depth = 0; depth < 8 && !frontier.empty(); ++depth) {
        std::vector<int> nextFrontier;
        for (int entityId : frontier) {
            const UacFeatureUnitInfo* fu = findFeatureUnitById(featureUnits, entityId);
            if (fu != nullptr) {
                if (fu->hasMasterVolume || fu->hasChannelVolume) {
                    return fu;
                }
                if (fallbackFu == nullptr) {
                    fallbackFu = fu;
                }
            }
            const auto it = unitParents.find(entityId);
            if (it != unitParents.end()) {
                for (int parentId : it->second) {
                    if (parentId >= 0 && visited.insert(parentId).second) {
                        nextFrontier.push_back(parentId);
                    }
                }
            }
        }
        frontier = std::move(nextFrontier);
    }
    return fallbackFu;
}

void appendUniqueInt(std::vector<int>& dst, int value) {
    if (value >= 0 && std::find(dst.begin(), dst.end(), value) == dst.end()) {
        dst.push_back(value);
    }
}

int unwrapClockMultiplier(
    int entityId,
    const std::unordered_map<int, int>& clockMultiplierSource
) {
    int cur = entityId;
    for (int depth = 0; depth < 4; ++depth) {
        auto it = clockMultiplierSource.find(cur);
        if (it == clockMultiplierSource.end() || it->second == cur) break;
        cur = it->second;
    }
    return cur;
}

/**
 * Collects all Clock Source IDs reachable from `entityId` (unwrapping Clock Multipliers and
 * traversing all pins of any Clock Selector so dual-crystal 44.1 kHz / 48 kHz DACs retain
 * every clock source instead of discarding secondary selector pins).
 */
void collectClockEntitySources(
    int entityId,
    UacDeviceDescriptorInfo& info,
    const std::unordered_map<int, int>& clockMultiplierSource,
    std::vector<int>& outClockIds
) {
    if (entityId < 0) return;

    std::vector<int> stack;
    std::unordered_set<int> visited;
    stack.push_back(entityId);

    while (!stack.empty()) {
        int cur = unwrapClockMultiplier(stack.back(), clockMultiplierSource);
        stack.pop_back();
        if (cur < 0 || !visited.insert(cur).second) continue;

        if (findClockSourceById(info.clockSources, cur) != nullptr) {
            appendUniqueInt(outClockIds, cur);
            continue;
        }

        for (const auto& sel : info.clockSelectors) {
            if (sel.selectorId == cur) {
                if (info.clockSelectorId < 0) {
                    info.clockSelectorId = sel.selectorId;
                }
                for (int pinId : sel.inputPinIds) {
                    stack.push_back(pinId);
                }
                break;
            }
        }
    }
}

void parseFeatureUnit(
    const uint8_t* desc,
    uint8_t bLength,
    uint16_t uacVersion,
    UacDeviceDescriptorInfo& info,
    std::unordered_map<int, std::vector<int>>& unitParents
) {
    if (bLength < 6) return;

    UacFeatureUnitInfo fu;
    fu.unitId = desc[3];
    fu.sourceId = desc[4];
    recordUnitParents(unitParents, fu.unitId, { fu.sourceId });

    if (uacVersion >= UAC_VERSION_2_0) {
        // UAC 2.0: 1-byte iFeature trailer; UAC 3.0: 2-byte wFeatureDescrStr trailer
        const size_t trailerBytes = (uacVersion >= UAC_VERSION_3_0) ? 2u : 1u;
        if (bLength >= 5u + 4u + trailerBytes) {
            const size_t ctrlBytes = static_cast<size_t>(bLength) - 5u - trailerBytes;
            const size_t numEntries = ctrlBytes / 4u;
            if (numEntries >= 1u) {
                fu.masterControls = readU32(desc + 5);
                fu.hasMasterMute = ((fu.masterControls & UAC2_CTRL_MASK) == UAC2_CTRL_RW);
                fu.hasMasterVolume = (((fu.masterControls >> 2) & UAC2_CTRL_MASK) == UAC2_CTRL_RW);

                for (size_t ch = 1; ch < numEntries; ++ch) {
                    const uint32_t chCtrl = readU32(desc + 5 + ch * 4u);
                    fu.channelControls.push_back(chCtrl);
                    if ((chCtrl & UAC2_CTRL_MASK) == UAC2_CTRL_RW) {
                        fu.hasChannelMute = true;
                    }
                    if (((chCtrl >> 2) & UAC2_CTRL_MASK) == UAC2_CTRL_RW) {
                        fu.hasChannelVolume = true;
                    }
                }
            }
        }
    } else if (bLength >= 7) {
        // UAC 1.0: bControlSize at offset 5, variable-width bmaControls starting at offset 6
        const uint8_t controlSize = desc[5];
        if (controlSize > 0 && bLength >= 6u + controlSize) {
            const size_t availBytes = static_cast<size_t>(bLength) - 6u;
            // Account for optional trailing iFeature string index byte
            const size_t numEntries = (availBytes > controlSize)
                ? (availBytes - 1u) / controlSize
                : (availBytes / controlSize);
            if (numEntries >= 1u) {
                fu.masterControls = readVarWidthControl(desc + 6, controlSize);
                fu.hasMasterMute = (fu.masterControls & 0x01u) != 0;
                fu.hasMasterVolume = (fu.masterControls & 0x02u) != 0;

                for (size_t ch = 1; ch < numEntries; ++ch) {
                    const uint32_t chCtrl = readVarWidthControl(desc + 6 + ch * controlSize, controlSize);
                    fu.channelControls.push_back(chCtrl);
                    if ((chCtrl & 0x01u) != 0) fu.hasChannelMute = true;
                    if ((chCtrl & 0x02u) != 0) fu.hasChannelVolume = true;
                }
            }
        }
    }

    info.featureUnits.push_back(std::move(fu));
}

} // namespace

static UacDeviceDescriptorInfo parseUacDescriptorsForConfig(
    const uint8_t* data,
    size_t length,
    int targetConfigValue
) {
    UacDeviceDescriptorInfo info;
    if (!data || length == 0) return info;

    int currentIfaceClass = -1;
    int currentIfaceSubClass = -1;
    int currentIfaceProtocol = -1;
    int currentIfaceNum = -1;
    int currentAltSetting = 0;

    bool hasPendingStream = false;
    UacStreamAltSetting pendingStream;
    uint8_t pendingSynchAddress = 0;
    uint8_t lastEndpointAddr = 0;
    int configCount = 0;
    bool inSelectedConfig = (targetConfigValue <= 0);

    std::unordered_map<int, std::vector<int>> unitParents;
    std::unordered_map<int, int> clockMultiplierSource;

    uint16_t parsedVendorId = 0;
    uint16_t parsedProductId = 0;

    auto flushPending = [&]() {
        if (hasPendingStream && pendingStream.outEndpoint != 0) {
            pendingStream.dsdFormat = deduceDsdWireFormat(pendingStream, parsedVendorId, parsedProductId);
            info.streamAltSettings.push_back(std::move(pendingStream));
        }
        hasPendingStream = false;
        pendingStream = UacStreamAltSetting{};
        pendingSynchAddress = 0;
        lastEndpointAddr = 0;
    };

    // Unrecognized leading descriptors safely advance by bLength; capture VID/PID from Device descriptor (0x01)
    size_t offset = 0;
    while (offset + 2 <= length) {
        const uint8_t bLength = data[offset];
        const uint8_t bDescType = data[offset + 1];
        if (bLength < 2 || offset + bLength > length) break;

        const uint8_t* desc = data + offset;

        if (bDescType == USB_DESC_DEVICE && bLength >= 12) {
            parsedVendorId = readU16(desc + 8);
            parsedProductId = readU16(desc + 10);
            info.vendorId = parsedVendorId;
            info.productId = parsedProductId;
        } else if (bDescType == USB_DESC_CONFIGURATION) {
            flushPending();
            configCount++;
            const uint8_t cfgVal = (bLength >= 6) ? desc[5] : 1u;
            if (targetConfigValue > 0) {
                if (inSelectedConfig) {
                    // Finished parsing the kernel's active configuration; ignore subsequent configurations.
                    break;
                }
                if (static_cast<int>(cfgVal) == targetConfigValue) {
                    inSelectedConfig = true;
                    info.activeConfigValue = cfgVal;
                } else {
                    inSelectedConfig = false;
                }
                currentIfaceClass = -1;
                currentIfaceSubClass = -1;
                currentIfaceProtocol = -1;
                currentIfaceNum = -1;
                currentAltSetting = 0;
            } else {
                // Fallback when GET_CONFIGURATION is unavailable: stay within the first configuration
                // that yields AudioStreaming alt-settings so dual-config DACs never mix configs.
                if (configCount > 1) {
                    if (!info.streamAltSettings.empty()) break;
                    info = UacDeviceDescriptorInfo{};
                    info.vendorId = parsedVendorId;
                    info.productId = parsedProductId;
                    unitParents.clear();
                    clockMultiplierSource.clear();
                    currentIfaceClass = -1;
                    currentIfaceSubClass = -1;
                    currentIfaceProtocol = -1;
                    currentIfaceNum = -1;
                    currentAltSetting = 0;
                }
                info.activeConfigValue = cfgVal;
            }
        } else if (!inSelectedConfig) {
            offset += bLength;
            continue;
        } else if (bDescType == USB_DESC_IAD && bLength >= 8) {
            const uint8_t fnClass = desc[4];
            const uint8_t fnSubClass = desc[5];
            const uint8_t fnProto = desc[6];
            if (fnClass == USB_CLASS_AUDIO) {
                if (fnProto == USB_PROTO_UAC3) {
                    info.uacVersion = UAC_VERSION_3_0;
                    if (fnSubClass != 0) {
                        info.isUac3Badd = true;
                        info.baddProfile = fnSubClass;
                    }
                } else if (fnProto == USB_PROTO_UAC2 && info.uacVersion == 0) {
                    info.uacVersion = UAC_VERSION_2_0;
                }
            }
        } else if (bDescType == USB_DESC_INTERFACE && bLength >= 9) {
            flushPending();
            currentIfaceNum = desc[2];
            currentAltSetting = desc[3];
            currentIfaceClass = desc[5];
            currentIfaceSubClass = desc[6];
            currentIfaceProtocol = desc[7];

            if (currentIfaceClass == USB_CLASS_AUDIO) {
                if (currentIfaceProtocol == USB_PROTO_UAC3) {
                    info.uacVersion = UAC_VERSION_3_0;
                } else if (currentIfaceProtocol == USB_PROTO_UAC2 && info.uacVersion == 0) {
                    info.uacVersion = UAC_VERSION_2_0;
                } else if (currentIfaceProtocol == USB_PROTO_UAC1 && info.uacVersion == 0) {
                    info.uacVersion = UAC_VERSION_1_0;
                }

                if (currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                    if (info.controlInterface < 0) {
                        info.controlInterface = currentIfaceNum;
                    }
                } else if (currentIfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING) {
                    // Alt 0 is typically zero-bandwidth idle (bNumEndpoints == 0), but allow alt 0
                    // when bNumEndpoints > 0 for simple UAC 1.0 devices; flushPending() drops alt 0
                    // automatically if no ISO OUT endpoint is present.
                    const uint8_t numEndpoints = desc[4];
                    if (currentAltSetting > 0 || numEndpoints > 0u) {
                        hasPendingStream = true;
                        pendingStream = UacStreamAltSetting{};
                        pendingStream.interfaceNumber = currentIfaceNum;
                        pendingStream.alternateSetting = currentAltSetting;
                    }
                }
            }
        } else if (bDescType == USB_DESC_CS_INTERFACE && currentIfaceClass == USB_CLASS_AUDIO && bLength >= 3) {
            const uint8_t subType = desc[2];
            const uint16_t effUac = (currentIfaceProtocol == USB_PROTO_UAC3)
                ? UAC_VERSION_3_0
                : ((currentIfaceProtocol == USB_PROTO_UAC2)
                    ? UAC_VERSION_2_0
                    : (info.uacVersion != 0 ? info.uacVersion : UAC_VERSION_1_0));

            if (currentIfaceSubClass == USB_SUBCLASS_AUDIOCONTROL) {
                if (subType == AC_HEADER) {
                    // UAC 3.0 AC_HEADER omits bcdADC (offset 3 is bCategory); preserve 0x0300
                    if (effUac < UAC_VERSION_3_0 && bLength >= 5) {
                        const uint16_t bcdAdc = readU16(desc + 3);
                        if (bcdAdc >= UAC_VERSION_1_0) {
                            info.uacVersion = bcdAdc;
                        }
                    } else if (effUac >= UAC_VERSION_3_0) {
                        info.uacVersion = UAC_VERSION_3_0;
                    }
                } else if (subType == AC_CLOCK_SOURCE) {
                    if (effUac >= UAC_VERSION_3_0 && bLength >= 10) {
                        UacClockSourceInfo cs;
                        cs.clockId = desc[3];
                        cs.attributes = desc[4];
                        const uint32_t controls32 = readU32(desc + 5);
                        cs.controls = static_cast<uint8_t>(controls32 & 0xFFu);
                        cs.assocTerminal = desc[9];
                        cs.isProgrammable = ((controls32 & UAC2_CTRL_MASK) == UAC2_CTRL_RW);
                        info.clockSources.push_back(cs);
                    } else if (bLength >= 8) {
                        UacClockSourceInfo cs;
                        cs.clockId = desc[3];
                        cs.attributes = desc[4];
                        cs.controls = desc[5];
                        cs.assocTerminal = desc[6];
                        cs.isProgrammable = ((cs.controls & UAC2_CTRL_MASK) == UAC2_CTRL_RW);
                        info.clockSources.push_back(cs);
                    }
                } else if (subType == AC_CLOCK_SELECTOR && bLength >= 6) {
                    UacClockSelectorInfo sel;
                    sel.selectorId = desc[3];
                    const uint8_t nrInPins = desc[4];
                    for (uint8_t i = 0; i < nrInPins && (5u + i) < bLength; ++i) {
                        sel.inputPinIds.push_back(desc[5 + i]);
                    }
                    info.clockSelectors.push_back(std::move(sel));
                } else if (subType == AC_CLOCK_MULTIPLIER && bLength >= 6) {
                    const int multId = desc[3];
                    const int srcClockId = desc[4];
                    clockMultiplierSource[multId] = srcClockId;
                } else if (subType == AC_INPUT_TERMINAL && bLength >= 8) {
                    UacTerminalInfo it;
                    it.isInput = true;
                    it.terminalId = desc[3];
                    it.terminalType = readU16(desc + 4);
                    it.assocTerminal = desc[6];
                    if (effUac >= UAC_VERSION_3_0 && bLength >= 14) {
                        it.clockSourceId = desc[7];
                    } else if (effUac >= UAC_VERSION_2_0 && bLength >= 17) {
                        it.clockSourceId = desc[7];
                        it.channels = desc[8];
                        it.channelConfig = readU32(desc + 9);
                    } else if (bLength >= 10) {
                        it.channels = desc[7];
                        it.channelConfig = readU16(desc + 8);
                    }
                    info.inputTerminals.push_back(it);
                } else if (subType == AC_OUTPUT_TERMINAL && bLength >= 8) {
                    UacTerminalInfo ot;
                    ot.isInput = false;
                    ot.terminalId = desc[3];
                    ot.terminalType = readU16(desc + 4);
                    ot.assocTerminal = desc[6];
                    ot.sourceId = desc[7];
                    if (effUac >= UAC_VERSION_2_0 && bLength >= 12) {
                        ot.clockSourceId = desc[8];
                    }
                    info.outputTerminals.push_back(ot);
                } else if (subType == AC_FEATURE_UNIT) {
                    parseFeatureUnit(desc, bLength, effUac, info, unitParents);
                } else if ((subType == AC_MIXER_UNIT || subType == AC_SELECTOR_UNIT ||
                            subType == AC_PROCESSING_UNIT || subType == AC_EXTENSION_UNIT) &&
                           bLength >= 6) {
                    // Track intermediate topology nodes so playback Feature Units stay reachable
                    const int unitId = desc[3];
                    const uint8_t pinOffset = (subType == AC_PROCESSING_UNIT || subType == AC_EXTENSION_UNIT) ? 6u : 4u;
                    if (pinOffset < bLength) {
                        const uint8_t nrInPins = desc[pinOffset];
                        std::vector<int> pins;
                        for (uint8_t i = 0; i < nrInPins && (pinOffset + 1u + i) < bLength; ++i) {
                            pins.push_back(desc[pinOffset + 1u + i]);
                        }
                        recordUnitParents(unitParents, unitId, pins);
                    }
                }
            } else if (currentIfaceSubClass == USB_SUBCLASS_AUDIOSTREAMING && hasPendingStream) {
                if (subType == AS_GENERAL) {
                    if (effUac >= UAC_VERSION_3_0 && bLength >= 16) {
                        // UAC 3.0 Spec Table 4-49: bmFormats(10..13), bSubslotSize(14), bBitResolution(15)
                        pendingStream.terminalLink = desc[3];
                        pendingStream.formatType = 1;
                        pendingStream.bmFormats = readU32(desc + 10);
                        if (desc[14] >= 1u && desc[14] <= 4u) {
                            pendingStream.subslotSize = desc[14];
                        }
                        if (desc[15] >= 8u && desc[15] <= 32u) {
                            pendingStream.bitResolution = desc[15];
                        }
                        pendingStream.isPcm = (pendingStream.bmFormats & UAC2_FORMAT_BIT_PCM) != 0;
                        pendingStream.isDsd = (pendingStream.bmFormats & UAC2_FORMAT_BIT_RAW_DATA) != 0;
                    } else if (effUac >= UAC_VERSION_2_0 && bLength >= 16) {
                        pendingStream.terminalLink = desc[3];
                        pendingStream.formatType = desc[5];
                        pendingStream.bmFormats = readU32(desc + 6);
                        pendingStream.channels = desc[10];
                        pendingStream.isPcm = (pendingStream.formatType == 1) &&
                                              ((pendingStream.bmFormats & UAC2_FORMAT_BIT_PCM) != 0);
                        pendingStream.isDsd = (pendingStream.formatType == 1) &&
                                              ((pendingStream.bmFormats & UAC2_FORMAT_BIT_RAW_DATA) != 0);
                    } else if (bLength >= 7) {
                        const uint16_t formatTag = readU16(desc + 5);
                        pendingStream.terminalLink = desc[3];
                        pendingStream.bmFormats = formatTag;
                        pendingStream.isPcm = (formatTag == UAC1_FORMAT_TAG_PCM);
                        pendingStream.isDsd = false;
                    }
                } else if (subType == AS_FORMAT_TYPE && effUac < UAC_VERSION_3_0 && bLength >= 6) {
                    // UAC 3.0 reuses CS_INTERFACE subtype 0x02 for AS_ENCODER (subslot/bitResolution
                    // are already carried in AS_GENERAL); only parse Type I (bFormatType == 1) layouts.
                    const uint8_t fmtType = desc[3];
                    pendingStream.formatType = fmtType;
                    if (fmtType == 1u) {
                        if (effUac >= UAC_VERSION_2_0) {
                            // UAC 2.0 Type I: bFormatType(3), bSubslotSize(4), bBitResolution(5)
                            pendingStream.subslotSize = desc[4];
                            pendingStream.bitResolution = desc[5];
                        } else if (bLength >= 8) {
                            // UAC 1.0 Type I: bFormatType(3), bNrChannels(4), bSubframeSize(5),
                            // bBitResolution(6), bSamFreqType(7)
                            pendingStream.channels = desc[4];
                            pendingStream.subslotSize = desc[5];
                            pendingStream.bitResolution = desc[6];
                            const uint8_t samFreqType = desc[7];
                            if (samFreqType == 0 && bLength >= 14) {
                                const uint32_t minRate = readU24(desc + 8);
                                const uint32_t maxRate = readU24(desc + 11);
                                for (int r : STANDARD_SAMPLE_RATES) {
                                    if (r >= static_cast<int>(minRate) && r <= static_cast<int>(maxRate)) {
                                        pendingStream.fixedSampleRates.push_back(r);
                                    }
                                }
                                if (pendingStream.fixedSampleRates.empty() && minRate > 0) {
                                    pendingStream.fixedSampleRates.push_back(static_cast<int>(minRate));
                                    if (maxRate > minRate) {
                                        pendingStream.fixedSampleRates.push_back(static_cast<int>(maxRate));
                                    }
                                }
                            } else {
                                for (uint8_t i = 0; i < samFreqType && (8u + (i + 1u) * 3u) <= bLength; ++i) {
                                    const int r = static_cast<int>(readU24(desc + 8 + i * 3u));
                                    if (r > 0) {
                                        pendingStream.fixedSampleRates.push_back(r);
                                    }
                                }
                            }
                        }
                    } else {
                        pendingStream.isPcm = false;
                        pendingStream.isDsd = false;
                    }
                }
            }
        } else if (bDescType == USB_DESC_ENDPOINT && hasPendingStream && bLength >= 7) {
            const uint8_t epAddr = desc[2];
            const uint8_t bmAttr = desc[3];
            const uint16_t rawMaxPacket = readU16(desc + 4);
            const uint16_t maxPacket = static_cast<uint16_t>(
                (rawMaxPacket & 0x07FFu) * (1u + ((rawMaxPacket >> 11) & 0x03u))
            );
            const uint8_t interval = (desc[6] == 0) ? 1u : desc[6];
            lastEndpointAddr = epAddr;

            const bool isIso = (bmAttr & 0x03u) == 0x01u;
            const bool isOut = (epAddr & 0x80u) == 0x00u;
            if (isIso && isOut) {
                pendingStream.outEndpoint = epAddr;
                pendingStream.maxPacketSize = maxPacket;
                pendingStream.interval = interval;
                pendingStream.syncType = static_cast<UacSyncType>((bmAttr >> 2) & 0x03u);
                if (bLength >= 9 && desc[8] != 0) {
                    pendingSynchAddress = desc[8];
                }
            } else if (isIso && !isOut) {
                // Prefer explicit Feedback IN endpoints (usageType=1 up to 64 B, or UAC1 usageType=0 <= 8 B)
                // over implicit capture data endpoints (usageType=2).
                const uint8_t usageType = (bmAttr >> 4) & 0x03u;
                const uint8_t refresh = (bLength >= 8) ? desc[7] : 0u;
                const bool matchesSynchHint = (pendingSynchAddress != 0) && (epAddr == pendingSynchAddress);
                const bool isExplicitCandidate =
                    (usageType == 1u && maxPacket <= 64u) ||
                    (usageType == 0u && maxPacket <= 8u);
                const bool existingIsExplicit =
                    (pendingStream.feedbackEndpoint != 0) &&
                    ((pendingStream.feedbackUsageType == 1u && pendingStream.feedbackMaxPacketSize <= 64u) ||
                     (pendingStream.feedbackUsageType == 0u && pendingStream.feedbackMaxPacketSize <= 8u));

                if (pendingStream.feedbackEndpoint == 0 ||
                    matchesSynchHint ||
                    (isExplicitCandidate && !existingIsExplicit)) {
                    pendingStream.feedbackEndpoint = epAddr;
                    pendingStream.feedbackInterval = interval;
                    pendingStream.feedbackRefresh = refresh;
                    pendingStream.feedbackUsageType = usageType;
                    pendingStream.feedbackMaxPacketSize = maxPacket;
                }
            }
        } else if (bDescType == USB_DESC_CS_ENDPOINT && hasPendingStream && bLength >= 4) {
            // UAC 1.0 CS_ENDPOINT EP_GENERAL bmAttributes bit 0 advertises SAM_FREQ_CONTROL support
            const uint16_t effUac = (currentIfaceProtocol == USB_PROTO_UAC3)
                ? UAC_VERSION_3_0
                : ((currentIfaceProtocol == USB_PROTO_UAC2)
                    ? UAC_VERSION_2_0
                    : (info.uacVersion != 0 ? info.uacVersion : UAC_VERSION_1_0));
            if (effUac < UAC_VERSION_2_0 &&
                desc[2] == EP_GENERAL &&
                lastEndpointAddr != 0 &&
                lastEndpointAddr == pendingStream.outEndpoint) {
                pendingStream.hasSamFreqControl = (desc[3] & 0x01u) != 0u;
            }
        } else if (bDescType == USB_DESC_SS_ENDPOINT_COMP && hasPendingStream && bLength >= 6) {
            // USB 3.x SuperSpeed Endpoint Companion Descriptor (Table 9-22):
            // wBytesPerInterval (bytes 4..5) carries the true per-service-interval ISO bandwidth (>1024 B).
            const uint16_t ssBytesPerInterval = readU16(desc + 4);
            if (ssBytesPerInterval > 0u && lastEndpointAddr != 0) {
                if (lastEndpointAddr == pendingStream.outEndpoint &&
                    ssBytesPerInterval > pendingStream.maxPacketSize) {
                    pendingStream.maxPacketSize = ssBytesPerInterval;
                } else if (lastEndpointAddr == pendingStream.feedbackEndpoint &&
                           ssBytesPerInterval > pendingStream.feedbackMaxPacketSize) {
                    pendingStream.feedbackMaxPacketSize = ssBytesPerInterval;
                }
            }
        }

        offset += bLength;
    }

    flushPending();

    info.isHighSpeed = (info.uacVersion >= UAC_VERSION_2_0);

    // Synthesize standardized UAC 3.0 BADD baseline topology when CS_INTERFACE descriptors are omitted
    if (info.isUac3Badd) {
        if (info.clockSources.empty()) {
            UacClockSourceInfo baddClock;
            baddClock.clockId = UAC3_BADD_CLOCK_SOURCE_ID;
            baddClock.attributes = 0x01u; // Internal fixed/programmable clock
            baddClock.controls = 0x03u;
            baddClock.isProgrammable = true;
            info.clockSources.push_back(std::move(baddClock));
        }
        if (info.featureUnits.empty()) {
            UacFeatureUnitInfo baddFu;
            baddFu.unitId = UAC3_BADD_PLAYBACK_FU_ID;
            baddFu.sourceId = UAC3_BADD_PLAYBACK_IT_ID;
            baddFu.hasMasterMute = true;
            baddFu.hasMasterVolume = true;
            baddFu.hasChannelMute = true;
            baddFu.hasChannelVolume = true;
            info.featureUnits.push_back(std::move(baddFu));
        }
        for (auto& alt : info.streamAltSettings) {
            if (alt.terminalLink < 0) {
                alt.terminalLink = UAC3_BADD_PLAYBACK_IT_ID;
            }
            if (alt.bmFormats == 0u) {
                alt.isPcm = true;
                alt.isDsd = false;
                alt.formatType = 1;
                alt.bmFormats = UAC2_FORMAT_BIT_PCM;
                alt.channels = (alt.channels > 0) ? alt.channels : 2;
                // BADD profiles define alt 1 as 16-bit/48 kHz and alt 2+ as 24-bit (3- or 4-byte subslot)
                if (alt.alternateSetting >= 2) {
                    alt.bitResolution = 24;
                    alt.subslotSize = (alt.maxPacketSize >= 48u && (alt.maxPacketSize % 8u == 0u)) ? 4 : 3;
                } else {
                    alt.bitResolution = 16;
                    alt.subslotSize = 2;
                }
                if (alt.fixedSampleRates.empty()) {
                    alt.fixedSampleRates.push_back(UAC3_BADD_DEFAULT_RATE_HZ);
                }
            }
            if (alt.clockEntityId < 0) {
                alt.clockEntityId = UAC3_BADD_CLOCK_SOURCE_ID;
            }
        }
    }

    for (const auto& entry : clockMultiplierSource) {
        UacClockMultiplierInfo mult;
        mult.multiplierId = entry.first;
        mult.sourceClockId = entry.second;
        info.clockMultipliers.push_back(mult);
    }

    // Link stream alt-settings back to their Input Terminal for channel counts and per-stream clock entity
    for (auto& alt : info.streamAltSettings) {
        if (alt.terminalLink >= 0) {
            for (const auto& it : info.inputTerminals) {
                if (it.terminalId == alt.terminalLink) {
                    if (alt.channels <= 0 && it.channels > 0) {
                        alt.channels = it.channels;
                    }
                    if (alt.clockEntityId < 0 && it.clockSourceId >= 0) {
                        alt.clockEntityId = it.clockSourceId;
                    }
                    break;
                }
            }
        }
        if (alt.channels <= 0) {
            alt.channels = 2;
        }
    }

    // Resolve playback Clock Sources (preserving all dual-crystal Clock Selector pins) and Feature Unit
    // Prefer Speaker/Headphone (0x03xx) terminals first, then Bi-dir Headset (0x0402) and External (0x0601..0x0605)
    const UacFeatureUnitInfo* selectedFu = nullptr;
    for (int pass = 0; pass < 2; ++pass) {
        for (const auto& ot : info.outputTerminals) {
            const bool matchPass = (pass == 0)
                ? isPrimarySpeakerOutputTerminal(ot.terminalType)
                : (isPlaybackOutputTerminal(ot.terminalType) && !isPrimarySpeakerOutputTerminal(ot.terminalType));
            if (!matchPass) continue;

            if (ot.clockSourceId >= 0) {
                collectClockEntitySources(ot.clockSourceId, info, clockMultiplierSource, info.playbackClockSourceIds);
            }
            if (selectedFu == nullptr && ot.sourceId >= 0) {
                selectedFu = traceUpstreamFeatureUnit(ot.sourceId, info.featureUnits, unitParents);
            }
        }
    }

    // Collect Clock Sources referenced by playback USB Streaming Input Terminals (0x0101) and alt-settings
    for (const auto& alt : info.streamAltSettings) {
        if (alt.clockEntityId >= 0) {
            collectClockEntitySources(alt.clockEntityId, info, clockMultiplierSource, info.playbackClockSourceIds);
        }
    }
    for (const auto& it : info.inputTerminals) {
        if (it.terminalType == TERMINAL_USB_STREAMING && it.clockSourceId >= 0) {
            collectClockEntitySources(it.clockSourceId, info, clockMultiplierSource, info.playbackClockSourceIds);
        }
    }

    if (info.playbackClockSourceIds.empty()) {
        for (const auto& cs : info.clockSources) {
            appendUniqueInt(info.playbackClockSourceIds, cs.clockId);
        }
    }

    // Pick primary clockSourceId (preferring first host-programmable playback clock source)
    for (int clkId : info.playbackClockSourceIds) {
        const auto* cs = findClockSourceById(info.clockSources, clkId);
        if (cs != nullptr && cs->isProgrammable) {
            info.clockSourceId = clkId;
            break;
        }
    }
    if (info.clockSourceId < 0 && !info.playbackClockSourceIds.empty()) {
        info.clockSourceId = info.playbackClockSourceIds.front();
    }
    if (info.clockSelectorId < 0 && !info.clockSelectors.empty()) {
        info.clockSelectorId = info.clockSelectors.front().selectorId;
    }

    if (selectedFu == nullptr && !info.featureUnits.empty()) {
        // Fallback: Prefer a Feature Unit exposing volume control before taking the first entry
        for (const auto& fu : info.featureUnits) {
            if (fu.hasMasterVolume || fu.hasChannelVolume) {
                selectedFu = &fu;
                break;
            }
        }
        if (selectedFu == nullptr) {
            selectedFu = &info.featureUnits.front();
        }
    }

    if (selectedFu != nullptr) {
        info.featureUnitId = selectedFu->unitId;
        info.hasHardwareVolume = selectedFu->hasMasterVolume || selectedFu->hasChannelVolume;
        info.hasHardwareMute = selectedFu->hasMasterMute || selectedFu->hasChannelMute;
        info.volumeMasterChannel = selectedFu->hasMasterVolume || !selectedFu->hasChannelVolume;
    }

    // Merge UAC1 / fixed-rate alt-setting sample rates into top-level supportedSampleRates
    std::set<int> rateSet;
    for (const auto& s : info.streamAltSettings) {
        for (int r : s.fixedSampleRates) {
            if (r > 0) rateSet.insert(r);
        }
    }
    info.supportedSampleRates.assign(rateSet.begin(), rateSet.end());

    finalizeUacCapabilities(info);
    return info;
}

// ═══ Implementation ═══
UacDeviceDescriptorInfo parseUacDescriptors(
    const uint8_t* data,
    size_t length,
    int targetConfigValue
) {
    if (targetConfigValue > 0) {
        UacDeviceDescriptorInfo targeted = parseUacDescriptorsForConfig(data, length, targetConfigValue);
        if (!targeted.streamAltSettings.empty()) {
            return targeted;
        }
    }
    return parseUacDescriptorsForConfig(data, length, -1);
}

void finalizeUacCapabilities(UacDeviceDescriptorInfo& info) {
    // Keep supportedSampleRates strictly sorted and deduplicated
    std::sort(info.supportedSampleRates.begin(), info.supportedSampleRates.end());
    info.supportedSampleRates.erase(
        std::unique(info.supportedSampleRates.begin(), info.supportedSampleRates.end()),
        info.supportedSampleRates.end()
    );

    int maxChannels = 0;
    bool hasNativeDsdAlt = false;
    int maxNativeDsdSlotBits = 0;
    bool hasDopCapablePcmAlt = false;

    for (const auto& alt : info.streamAltSettings) {
        if (alt.outEndpoint == 0) continue;
        maxChannels = std::max(maxChannels, alt.channels);
        if (alt.isDsd) {
            hasNativeDsdAlt = true;
            const int slotBits = (alt.subslotSize > 0) ? (alt.subslotSize * 8) : 32;
            maxNativeDsdSlotBits = std::max(maxNativeDsdSlotBits, slotBits);
        }
        if (alt.isPcm && alt.bitResolution >= 24 && alt.subslotSize >= 3) {
            hasDopCapablePcmAlt = true;
        }
    }

    info.maxSupportedChannels = (maxChannels > 0) ? maxChannels : 2;
    info.supportsNativeDsd = hasNativeDsdAlt;

    const int maxPcmRate = info.supportedSampleRates.empty() ? 0 : info.supportedSampleRates.back();

    // DoP v1.1 packs 16 DSD bits per 24-bit PCM frame (DSD64 = 176.4 kHz, DSD128 = 352.8 kHz, DSD256 = 705.6 kHz)
    bool hasDopRate = false;
    int maxDopDsdMult = 0;
    if (hasDopCapablePcmAlt) {
        for (int r : info.supportedSampleRates) {
            if (r >= 176400 && (r % DSD_BASE_RATE_HZ == 0)) {
                bool fitsAnyDopAlt = false;
                for (const auto& alt : info.streamAltSettings) {
                    if (alt.isPcm && alt.outEndpoint != 0 && alt.bitResolution >= 24 && alt.subslotSize >= 3 &&
                        altFitsWireBandwidth(info, alt, r)) {
                        fitsAnyDopAlt = true;
                        break;
                    }
                }
                if (fitsAnyDopAlt) {
                    hasDopRate = true;
                    const int64_t rawMult = (static_cast<int64_t>(r) * 16LL) / DSD_BASE_RATE_HZ;
                    maxDopDsdMult = std::max(maxDopDsdMult, roundDownToStandardDsdMultiplier(rawMult));
                }
            }
        }
    }
    info.supportsDop = hasDopRate;

    int maxNativeDsdMult = 0;
    if (hasNativeDsdAlt && maxPcmRate > 0 && maxNativeDsdSlotBits > 0) {
        // Validate Native DSD multiplier against each RAW_DATA alt-setting's actual wMaxPacketSize bandwidth
        bool any441Rate = false;
        for (int r : info.supportedSampleRates) {
            if (r % DSD_BASE_RATE_HZ == 0) {
                any441Rate = true;
                for (const auto& alt : info.streamAltSettings) {
                    if (!alt.isDsd || alt.outEndpoint == 0 || alt.subslotSize <= 0) continue;
                    if (!altFitsWireBandwidth(info, alt, r)) continue;
                    const int64_t slotBits = static_cast<int64_t>(alt.subslotSize) * 8LL;
                    const int64_t rawMult = (static_cast<int64_t>(r) * slotBits) / DSD_BASE_RATE_HZ;
                    maxNativeDsdMult = std::max(maxNativeDsdMult, roundDownToStandardDsdMultiplier(rawMult));
                }
            }
        }
        if (!any441Rate) {
            for (const auto& alt : info.streamAltSettings) {
                if (!alt.isDsd || alt.outEndpoint == 0 || alt.subslotSize <= 0) continue;
                if (!altFitsWireBandwidth(info, alt, maxPcmRate)) continue;
                const int64_t slotBits = static_cast<int64_t>(alt.subslotSize) * 8LL;
                const int64_t rawMult = (static_cast<int64_t>(maxPcmRate) * slotBits) / DSD_BASE_RATE_HZ;
                maxNativeDsdMult = std::max(maxNativeDsdMult, roundDownToStandardDsdMultiplier(rawMult));
            }
        }
    }

    info.maxDsdRate = std::max(maxNativeDsdMult, maxDopDsdMult);
}