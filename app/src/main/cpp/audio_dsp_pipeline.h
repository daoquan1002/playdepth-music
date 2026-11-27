/**
 * audio_dsp_pipeline — Pure C++17 capability negotiation, Bit-Perfect/DSD framing, and Hi-Fi DSP.
 *
 * @remarks
 * Self-contained with zero third-party library dependencies so Android NDK CMake compiles
 * out-of-the-box. Implements Smart Bypass capability negotiation: matched PCM streams pass
 * through bit-exact (or lossless MSB-aligned zero-padded) while mismatched parameters engage
 * only the required DSP blocks:
 *   1. ITU-R BS.775-3 channel matrix / downmixer (Mono->Stereo, 5.1/7.1->2.0)
 *   2. Multi-stage symmetric FIR DSD-to-PCM decimator with configurable +6 dB gain compensation
 *   3. 64-bit double polyphase Kaiser-windowed sinc resampler (clock-family integer-ratio aware)
 *   4. 64-bit software volume with automatic hard-bypass at 0 dB (>= 0.999999) or when HW volume is active
 *   5. TPDF dithering prior to bit-depth reduction (e.g. 24/32-bit/float -> 16-bit)
 *   6. Lossless PCM zero-padding, DoP (0x05/0xFA) marker packing, and Native DSD framing
 *
 * All scratch buffers and filter delay lines are pre-allocated during `configure()`; `process()`
 * performs zero heap allocations.
 *
 * @see uac_descriptor.h
 * @see UsbAudioDriver
 * @see UsbDacNative
 */
#pragma once

// ═══ Includes ═══
#include "uac_descriptor.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

// ═══ ABI-Locked Enums & Flags (Must Match UsbDacNative.kt) ═══
enum class AudioInputEncoding : int {
    PCM_16BIT = 2,
    PCM_24BIT = 3,   // 3 bytes packed LE
    PCM_32BIT = 4,   // 4 bytes LE
    PCM_FLOAT = 5,   // IEEE-754 32-bit float [-1.0, 1.0]
    DSD_U8 = 10,     // 1-bit DSD bytes packed MSB-first, channel-interleaved per byte
    DSD_U32_BE = 11, // 32-bit big-endian DSD channel blocks
    DSD_U32_LE = 12  // 32-bit little-endian DSD channel blocks
};

enum class OutputTransportMode : int {
    INACTIVE = 0,
    PCM_BIT_PERFECT = 1,
    PCM_DSP = 2,
    DOP = 3,
    NATIVE_DSD = 4,
    DSD_TO_PCM = 5
};

enum class DsdPlaybackMode : int {
    AUTO = 0,         // Native DSD -> DoP -> DSD-to-PCM decimator
    NATIVE = 1,       // Prefer Native DSD, fall back to DoP / DSD-to-PCM if unsupported
    DOP = 2,          // Prefer DoP, fall back to DSD-to-PCM if unsupported
    PCM_DECIMATE = 3  // Force multi-stage FIR DSD-to-PCM decimation
};

enum class ResamplerQuality : int {
    MEDIUM = 0,       // 32 taps/phase, 96 dB stopband attenuation
    HIGH = 1,         // 64 taps/phase, 120 dB stopband attenuation
    ULTRA = 2         // 128 taps/phase, 144 dB stopband attenuation
};

enum DspStageFlags : uint32_t {
    DSP_STAGE_NONE = 0u,
    DSP_STAGE_RESAMPLER = 1u << 0,
    DSP_STAGE_DITHER_TPDF = 1u << 1,
    DSP_STAGE_DSD_TO_PCM = 1u << 2,
    DSP_STAGE_CHANNEL_DOWNMIX = 1u << 3,
    DSP_STAGE_CHANNEL_MONO_TO_STEREO = 1u << 4,
    DSP_STAGE_SW_VOLUME = 1u << 5,
    DSP_STAGE_DOP_PACKING = 1u << 6,
    DSP_STAGE_ZERO_PADDING = 1u << 7,
    DSP_STAGE_FLOAT_TO_PCM = 1u << 8
};

// ═══ Negotiation & Configuration Contracts ═══
struct DspUserOptions {
    DsdPlaybackMode dsdMode = DsdPlaybackMode::AUTO;
    float dsdGainCompensationDb = 6.0f; // Standard +6 dB compensation for 50% DSD modulation depth
    bool ditherEnabled = true;
    ResamplerQuality resamplerQuality = ResamplerQuality::HIGH;
};

struct StreamNegotiationRequest {
    int inputSampleRate = 48000;        // PCM Hz, or 1-bit DSD Hz (2822400 = DSD64, 5644800 = DSD128, etc.)
    int inputChannels = 2;
    int preferredBitDepth = 24;
    AudioInputEncoding inputEncoding = AudioInputEncoding::PCM_16BIT;
    DspUserOptions options{};
};

struct NegotiatedStreamPlan {
    bool valid = false;
    UacStreamAltSetting selectedAlt{};
    int wireSampleRate = 0;
    int wireBitDepth = 0;
    int wireSubslotSize = 0;
    int wireChannels = 2;
    OutputTransportMode outputMode = OutputTransportMode::INACTIVE;
    uint32_t dspFlags = DSP_STAGE_NONE;
    int dsdDecimationFactor = 0;        // Total DSD->PCM decimation ratio (8, 16, 32, 64) when DSD_TO_PCM
};

// ═══ DSP Building Blocks (Pure C++17, Zero External Dependencies) ═══
class TpdfDitherGenerator {
public:
    explicit TpdfDitherGenerator(uint64_t seed = 0x9E3779B97F4A7C15ULL);
    void reset(uint64_t seed = 0x9E3779B97F4A7C15ULL);

    // Returns triangular-distribution noise in [-1.0, +1.0] LSB
    double nextTriangular();

private:
    uint64_t state_;
};

class PolyphaseResampler64 {
public:
    PolyphaseResampler64() = default;

    bool configure(int inputRate, int outputRate, int channels, ResamplerQuality quality);
    void reset();

    bool isActive() const { return active_; }
    double ratio() const { return ratio_; }
    size_t maxOutputFramesForInput(size_t inputFrames) const;
    size_t maxInputFramesForOutput(size_t maxOutputFrames) const;

    // Processes interleaved 64-bit double frames; returns output frames written
    size_t process(const double* inInterleaved, size_t inFrames, double* outInterleaved, size_t maxOutFrames);

private:
    bool active_ = false;
    int inputRate_ = 0;
    int outputRate_ = 0;
    int channels_ = 2;
    int tapsPerPhase_ = 64;
    int numPhases_ = 256;
    double ratio_ = 1.0;
    double step_ = 1.0;
    double phaseAccum_ = 0.0;

    std::vector<double> polyphaseKernel_; // [numPhases_ * tapsPerPhase_]
    std::vector<double> history_;         // Interleaved delay line [(tapsPerPhase_ - 1) * channels_]
    std::vector<double> workBuf_;         // Contiguous scratch for history + input block
};

class DsdToPcmDecimator {
public:
    DsdToPcmDecimator() = default;

    bool configure(int dsdBitRate, int targetPcmRate, int channels, float gainCompensationDb);
    void reset();

    bool isActive() const { return active_; }
    int decimationFactor() const { return totalDecimation_; }
    int dsdBytesPerOutputFramePerChannel() const { return totalDecimation_ / 8; }

    /**
     * Decimates packed DSD_U8 byte frames (1 byte per channel per 8 DSD bits, interleaved by channel)
     * into 64-bit interleaved PCM frames. Returns number of output PCM frames produced.
     */
    size_t processDsdU8(
        const uint8_t* dsdBytes,
        size_t dsdByteFrames,
        double* outInterleavedPcm,
        size_t maxOutFrames
    );

private:
    struct HalfBandStage {
        int taps = 0;
        double centerCoeff = 0.5;
        std::vector<double> oddCoeffs; // Non-zero symmetric half-band taps at offsets d = 1, 3, 5, ...
        std::vector<double> history;   // Interleaved delay line [(taps - 1) * channels_]
        std::vector<double> workBuf;   // Contiguous scratch [(taps - 1 + chunk) * channels_]
        bool emitPhase = false;
    };

    bool active_ = false;
    int channels_ = 2;
    int totalDecimation_ = 0;
    int stage1Decimation_ = 8;      // 8:1, 16:1 (DSD128/256), or 32:1 (DSD512) byte-LUT Stage 1
    int stage1BytesPerStep_ = 1;    // DSD_U8 bytes consumed per Stage-1 output (1, 2, or 4)
    int stage1LutBytes_ = 8;        // Number of DSD bytes spanning the Stage-1 FIR window (8, 16, or 20)
    int stage1HistPos_ = 0;         // Ring cursor inside 32-byte per-channel power-of-2 history
    double gainLinear_ = 2.0;       // +6 dB default compensation

    // Stage 1: Byte-LUT-accelerated symmetric Kaiser FIR (8 bits per lookup, 8x fewer ops than per-bit)
    std::vector<double> stage1Lut_;     // [stage1LutBytes_ * 256]
    std::vector<uint8_t> stage1History_; // [32 * channels_] power-of-2 ring history per channel
    std::vector<HalfBandStage> halfBandStages_;
    std::vector<double> stageScratchA_;
    std::vector<double> stageScratchB_;
};

class ChannelMatrixProcessor {
public:
    ChannelMatrixProcessor() = default;

    void configure(int inputChannels, int outputChannels);
    bool isActive() const { return inputChannels_ != outputChannels_; }
    int inputChannels() const { return inputChannels_; }
    int outputChannels() const { return outputChannels_; }

    // Converts interleaved double frames using ITU-R BS.775-3 coefficients
    void process(const double* inInterleaved, double* outInterleaved, size_t frames) const;

private:
    int inputChannels_ = 2;
    int outputChannels_ = 2;
};

// ═══ Unified Pipeline Orchestrator ═══
class AudioDspPipeline {
public:
    AudioDspPipeline() = default;

    // ─── Capability Negotiation & Smart Bypass ───
    static NegotiatedStreamPlan negotiate(
        const UacDeviceDescriptorInfo& deviceInfo,
        const StreamNegotiationRequest& request
    );

    static int selectBestSampleRate(int sourceRate, const std::vector<int>& supportedRates);
    static bool isDsdEncoding(AudioInputEncoding encoding);
    static int inputBytesPerSampleOrBlock(AudioInputEncoding encoding);

    // ─── Stream Configuration (Producer/Control Thread) ───
    bool configure(const StreamNegotiationRequest& request, const NegotiatedStreamPlan& plan);
    void reset();
    void clearPlan();

    // ─── 64-bit Software Volume With 0 dB Hard-Bypass ───
    void setHardwareVolumeActive(bool hwVolActive);
    void setSoftwareGainLinear(double linearGain);
    bool isSoftwareVolumeBypassed() const;

    const NegotiatedStreamPlan& plan() const { return plan_; }
    uint32_t activeDspFlags() const;

    /**
     * Converts input PCM or DSD bytes into the active USB wire format.
     *
     * @param inputData Input byte buffer from Media3 / decoder.
     * @param inputBytes Input byte count available.
     * @param maxWireBytes Upper bound on wire bytes that fit in the SPSC ring buffer.
     * @param outInputBytesConsumed Exact bytes consumed from `inputData`.
     * @param outWireData Pointer to produced wire bytes (may alias `inputData` on bit-perfect zero-copy fast path).
     * @return Number of valid wire bytes pointed to by `outWireData`.
     */
    size_t processToWire(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

private:
    size_t processPcmFastPath(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

    size_t processPcmDspPath(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

    size_t processDopPath(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

    size_t processNativeDsdPath(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

    size_t processDsdToPcmPath(
        const uint8_t* inputData,
        size_t inputBytes,
        size_t maxWireBytes,
        size_t& outInputBytesConsumed,
        const uint8_t*& outWireData
    );

    void quantizeAndPackWire(const double* srcInterleaved, size_t frames, uint8_t* dstWire);

    StreamNegotiationRequest request_{};
    NegotiatedStreamPlan plan_{};

    std::atomic<bool> hwVolumeActive_{false};
    std::atomic<double> softwareGainLinear_{1.0};

    TpdfDitherGenerator dither_;
    PolyphaseResampler64 resampler_;
    DsdToPcmDecimator dsdDecimator_;
    ChannelMatrixProcessor channelMatrix_;

    uint8_t dopMarkerToggle_ = 0x05u; // Alternates 0x05 <-> 0xFA per DoP v1.1 spec

    // Pre-allocated scratch buffers (never resized inside processToWire)
    std::vector<uint8_t> dsdUnpackScratch_;
    std::vector<double> stageBufA_;
    std::vector<double> stageBufB_;
    std::vector<uint8_t> wireOutScratch_;
};