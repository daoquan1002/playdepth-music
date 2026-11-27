/**
 * audio_dsp_pipeline — Pure C++17 capability negotiation, Bit-Perfect/DSD framing, and Hi-Fi DSP.
 *
 * @remarks
 * Implements PlayDepth's self-contained DSP engine with zero external third-party library
 * dependencies so Android NDK CMake compiles cleanly out-of-the-box. Uses Smart Bypass
 * capability negotiation to leave matched PCM and DSD streams untouched (bit-exact or lossless
 * MSB-aligned zero-padded) while selectively enabling only the minimum DSP stages required for
 * mismatched DAC capabilities: ITU-R BS.775-3 downmixing, multi-stage FIR DSD-to-PCM decimation,
 * 64-bit double polyphase Kaiser-sinc resampling, 64-bit software volume with 0 dB hard-bypass,
 * and TPDF dithering prior to bit-depth reduction.
 *
 * All filter delay lines, polyphase tables, and working scratch buffers are pre-allocated during
 * `configure()`; `processToWire()` and all per-buffer processing methods perform zero heap
 * allocations and acquire zero mutexes.
 *
 * @see audio_dsp_pipeline.h
 * @see uac_descriptor.h
 * @see UsbAudioDriver
 */

// ═══ Includes ═══
#include "audio_dsp_pipeline.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <set>
#include <type_traits>

namespace {

// ═══ Constants ═══
constexpr double PI = 3.14159265358979323846;
constexpr double INV_SQRT_2 = 0.70710678118654752440; // -3.01 dB ITU-R BS.775-3 center/surround weight
constexpr double SW_VOLUME_BYPASS_THRESHOLD = 0.999999;

constexpr int CLOCK_FAMILY_44100 = 44100;
constexpr int CLOCK_FAMILY_48000 = 48000;
constexpr int DSD64_BIT_RATE_HZ = 2822400;

constexpr uint8_t DSD_SILENCE_BYTE = 0x69u; // 50% balanced 1-bit pattern avoids DC thump
constexpr uint8_t DOP_MARKER_EVEN = 0x05u;
constexpr uint8_t DOP_MARKER_ODD = 0xFAu;

// Fixed scratch capacities pre-allocated in configure() so processToWire() never allocates
constexpr size_t MAX_DSP_BLOCK_FRAMES = 2048;
constexpr size_t MAX_RESAMPLER_CHUNK_FRAMES = 2048;
constexpr size_t MAX_DECIMATOR_CHUNK_FRAMES = 2048;
constexpr size_t MAX_WIRE_SCRATCH_BYTES = 256 * 1024;
constexpr size_t MAX_DSD_UNPACK_BYTES = 131072; // 128 KB handles batched DSD256/DSD512 blocks in one pass

constexpr int RESAMPLER_NUM_PHASES = 256;
constexpr int DSD_STAGE1_RING_SIZE = 32;        // Power-of-2 ring (>= max stage1LutBytes_ = 20)
constexpr int DSD_STAGE1_RING_MASK = DSD_STAGE1_RING_SIZE - 1;
constexpr int DSD_HALFBAND_INTERM_TAPS = 31;
constexpr int DSD_HALFBAND_FINAL_TAPS = 63;

// ═══ Private Math & Helper Functions ═══
double besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double halfX = 0.5 * x;
    for (int k = 1; k <= 32; ++k) {
        const double ratio = halfX / static_cast<double>(k);
        term *= ratio * ratio;
        sum += term;
        if (term < sum * 1e-16) break;
    }
    return sum;
}

double sinc(double x) {
    if (std::fabs(x) < 1e-12) return 1.0;
    const double pix = PI * x;
    return std::sin(pix) / pix;
}

double kaiserWindow(double n, double lengthMinusOne, double beta, double invI0Beta) {
    if (lengthMinusOne <= 0.0) return 1.0;
    const double ratio = (2.0 * n / lengthMinusOne) - 1.0;
    const double arg = 1.0 - ratio * ratio;
    if (arg <= 0.0) return invI0Beta;
    return besselI0(beta * std::sqrt(arg)) * invI0Beta;
}

std::vector<double> designKaiserLowpassFir(int numTaps, double normalizedCutoff, double beta) {
    std::vector<double> coeffs(static_cast<size_t>(numTaps), 0.0);
    const double center = 0.5 * static_cast<double>(numTaps - 1);
    const double invI0 = 1.0 / besselI0(beta);
    double sum = 0.0;

    for (int i = 0; i < numTaps; ++i) {
        const double x = static_cast<double>(i) - center;
        const double h = 2.0 * normalizedCutoff * sinc(2.0 * normalizedCutoff * x);
        const double w = kaiserWindow(static_cast<double>(i), static_cast<double>(numTaps - 1), beta, invI0);
        coeffs[static_cast<size_t>(i)] = h * w;
        sum += coeffs[static_cast<size_t>(i)];
    }

    if (std::fabs(sum) > 1e-15) {
        const double invSum = 1.0 / sum;
        for (double& c : coeffs) {
            c *= invSum;
        }
    }
    return coeffs;
}

bool isPowerOfTwo(int v) {
    return v > 0 && (v & (v - 1)) == 0;
}

bool isSameClockFamily(int a, int b) {
    if (a <= 0 || b <= 0) return false;
    const bool a441 = (a % CLOCK_FAMILY_44100) == 0;
    const bool b441 = (b % CLOCK_FAMILY_44100) == 0;
    if (a441 && b441) return true;
    const bool a48 = (a % CLOCK_FAMILY_48000) == 0;
    const bool b48 = (b % CLOCK_FAMILY_48000) == 0;
    return a48 && b48;
}

int effectiveInputBitDepth(AudioInputEncoding encoding) {
    switch (encoding) {
        case AudioInputEncoding::PCM_16BIT: return 16;
        case AudioInputEncoding::PCM_24BIT: return 24;
        case AudioInputEncoding::PCM_32BIT: return 32;
        case AudioInputEncoding::PCM_FLOAT: return 32;
        case AudioInputEncoding::DSD_U8:
        case AudioInputEncoding::DSD_U32_BE:
        case AudioInputEncoding::DSD_U32_LE:
            return 1;
    }
    return 16;
}

double computeAltPacketsPerSecond(bool isHighSpeed, uint8_t interval) {
    const uint32_t intervalsPerSec = isHighSpeed ? 8000u : 1000u;
    const uint32_t shift = (interval > 0u)
        ? std::min<uint32_t>(static_cast<uint32_t>(interval - 1u), 15u)
        : 0u;
    return static_cast<double>(intervalsPerSec) / static_cast<double>(1u << shift);
}

/**
 * Verifies whether `alt.maxPacketSize` can transport `wireSampleRate` without truncating frames.
 * When `requireAsyncMargin` is true, Asynchronous endpoints require +/- 2% crystal/PI headroom.
 */
bool altFitsBandwidth(
    const UacDeviceDescriptorInfo& deviceInfo,
    const UacStreamAltSetting& alt,
    int wireSampleRate,
    bool requireAsyncMargin
) {
    if (alt.maxPacketSize == 0u || alt.channels <= 0 || alt.subslotSize <= 0 || wireSampleRate <= 0) {
        return true;
    }
    const double pps = computeAltPacketsPerSecond(deviceInfo.isHighSpeed, alt.interval);
    if (pps <= 0.0) return true;

    const double nominalFrames = static_cast<double>(wireSampleRate) / pps;
    const double margin = (requireAsyncMargin && alt.syncType == UacSyncType::ASYNCHRONOUS) ? 1.02 : 1.0;
    const size_t requiredFrames = static_cast<size_t>(std::ceil(nominalFrames * margin));
    const size_t frameBytes = static_cast<size_t>(alt.channels) * static_cast<size_t>(alt.subslotSize);
    return static_cast<size_t>(alt.maxPacketSize) >= (requiredFrames * frameBytes);
}

void collectClockSourceRatesForEntity(
    const UacDeviceDescriptorInfo& deviceInfo,
    int entityId,
    std::set<int>& outRates
) {
    if (entityId < 0) return;
    std::vector<int> stack;
    std::set<int> visited;
    stack.push_back(entityId);

    while (!stack.empty()) {
        int cur = stack.back();
        stack.pop_back();
        for (int depth = 0; depth < 4; ++depth) {
            bool unwrapped = false;
            for (const auto& mult : deviceInfo.clockMultipliers) {
                if (mult.multiplierId == cur && mult.sourceClockId != cur) {
                    cur = mult.sourceClockId;
                    unwrapped = true;
                    break;
                }
            }
            if (!unwrapped) break;
        }
        if (cur < 0 || !visited.insert(cur).second) continue;

        for (const auto& cs : deviceInfo.clockSources) {
            if (cs.clockId == cur) {
                for (int r : cs.supportedSampleRates) {
                    if (r > 0) outRates.insert(r);
                }
                break;
            }
        }
        for (const auto& sel : deviceInfo.clockSelectors) {
            if (sel.selectorId == cur) {
                for (int pinId : sel.inputPinIds) {
                    stack.push_back(pinId);
                }
                break;
            }
        }
    }
}

std::vector<int> getAltSupportedRates(
    const UacDeviceDescriptorInfo& deviceInfo,
    const UacStreamAltSetting& alt
) {
    std::vector<int> rawRates;
    if (deviceInfo.uacVersion < UAC_VERSION_2_0 && !alt.fixedSampleRates.empty()) {
        rawRates = alt.fixedSampleRates;
    } else {
        if (alt.clockEntityId >= 0) {
            std::set<int> clockRateSet;
            collectClockSourceRatesForEntity(deviceInfo, alt.clockEntityId, clockRateSet);
            if (!clockRateSet.empty()) {
                rawRates.assign(clockRateSet.begin(), clockRateSet.end());
            }
        }
        if (rawRates.empty()) {
            if (!alt.fixedSampleRates.empty() && deviceInfo.supportedSampleRates.empty()) {
                rawRates = alt.fixedSampleRates;
            } else {
                rawRates = deviceInfo.supportedSampleRates;
            }
        }
    }

    std::vector<int> filtered;
    filtered.reserve(rawRates.size());
    for (int r : rawRates) {
        if (r > 0 && altFitsBandwidth(deviceInfo, alt, r, false)) {
            filtered.push_back(r);
        }
    }
    return filtered;
}

bool altSupportsSampleRate(
    const UacDeviceDescriptorInfo& deviceInfo,
    const UacStreamAltSetting& alt,
    int sampleRate
) {
    if (!altFitsBandwidth(deviceInfo, alt, sampleRate, false)) {
        return false;
    }
    const auto rates = getAltSupportedRates(deviceInfo, alt);
    if (rates.empty()) {
        // Descriptor omitted explicit rate tables; allow only when wire bandwidth fits
        return alt.fixedSampleRates.empty() && deviceInfo.supportedSampleRates.empty();
    }
    return std::find(rates.begin(), rates.end(), sampleRate) != rates.end();
}

uint32_t readLeU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
           (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

void writeMsbAlignedSample(uint8_t* dst, int32_t s32, int subslotSize) {
    const uint32_t u = static_cast<uint32_t>(s32);
    if (subslotSize == 4) {
        dst[0] = static_cast<uint8_t>(u & 0xFFu);
        dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
        dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
        dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
    } else if (subslotSize == 3) {
        dst[0] = static_cast<uint8_t>((u >> 8) & 0xFFu);
        dst[1] = static_cast<uint8_t>((u >> 16) & 0xFFu);
        dst[2] = static_cast<uint8_t>((u >> 24) & 0xFFu);
    } else {
        dst[0] = static_cast<uint8_t>((u >> 16) & 0xFFu);
        dst[1] = static_cast<uint8_t>((u >> 24) & 0xFFu);
    }
}

void decodePcmBlockToDouble(
    const uint8_t* src,
    AudioInputEncoding encoding,
    size_t totalSamples,
    double* dst
) {
    switch (encoding) {
        case AudioInputEncoding::PCM_16BIT: {
            constexpr double invScale = 1.0 / 32768.0;
            for (size_t i = 0; i < totalSamples; ++i) {
                const int16_t v = static_cast<int16_t>(
                    static_cast<uint16_t>(src[i * 2]) |
                    (static_cast<uint16_t>(src[i * 2 + 1]) << 8)
                );
                dst[i] = static_cast<double>(v) * invScale;
            }
            break;
        }
        case AudioInputEncoding::PCM_24BIT: {
            constexpr double invScale = 1.0 / 2147483648.0;
            for (size_t i = 0; i < totalSamples; ++i) {
                const uint8_t* p = src + i * 3;
                const uint32_t raw = (static_cast<uint32_t>(p[0]) << 8) |
                                     (static_cast<uint32_t>(p[1]) << 16) |
                                     (static_cast<uint32_t>(p[2]) << 24);
                dst[i] = static_cast<double>(static_cast<int32_t>(raw)) * invScale;
            }
            break;
        }
        case AudioInputEncoding::PCM_32BIT: {
            constexpr double invScale = 1.0 / 2147483648.0;
            for (size_t i = 0; i < totalSamples; ++i) {
                const int32_t v = static_cast<int32_t>(readLeU32(src + i * 4));
                dst[i] = static_cast<double>(v) * invScale;
            }
            break;
        }
        case AudioInputEncoding::PCM_FLOAT: {
            for (size_t i = 0; i < totalSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, src + i * 4, sizeof(float));
                dst[i] = std::isfinite(f) ? static_cast<double>(f) : 0.0;
            }
            break;
        }
        default:
            std::memset(dst, 0, totalSamples * sizeof(double));
            break;
    }
}

/**
 * Unpacks DSD input (`DSD_U8`, `DSD_U32_BE`, or `DSD_U32_LE`) into chronological MSB-first
 * channel-interleaved `DSD_U8` byte frames (`1 byte per channel per 8 DSD bits`).
 * Returns number of `DSD_U8` byte frames written to `dstU8`.
 */
size_t unpackDsdToU8(
    const uint8_t* src,
    size_t srcBytes,
    AudioInputEncoding encoding,
    int channels,
    size_t maxDstU8Frames,
    size_t frameMultiple,
    uint8_t* dstU8,
    size_t& outSrcBytesConsumed
) {
    outSrcBytesConsumed = 0;
    if (!src || !dstU8 || channels <= 0 || maxDstU8Frames == 0 || frameMultiple == 0) return 0;

    const size_t ch = static_cast<size_t>(channels);
    if (encoding == AudioInputEncoding::DSD_U8) {
        const size_t availFrames = srcBytes / ch;
        size_t frames = std::min(availFrames, maxDstU8Frames);
        frames -= (frames % frameMultiple);
        if (frames == 0) return 0;
        const size_t bytes = frames * ch;
        std::memcpy(dstU8, src, bytes);
        outSrcBytesConsumed = bytes;
        return frames;
    }

    // DSD_U32_BE and DSD_U32_LE store 4 bytes per channel per block (= 4 DSD_U8 byte frames)
    const size_t blockBytes = 4u * ch;
    const size_t availBlocks = srcBytes / blockBytes;
    const size_t maxBlocksByDst = maxDstU8Frames / 4u;
    size_t blocks = std::min(availBlocks, maxBlocksByDst);
    if (frameMultiple > 4u) {
        const size_t blockMultiple = (frameMultiple + 3u) / 4u;
        blocks -= (blocks % blockMultiple);
    }
    if (blocks == 0) return 0;

    const bool isLe = (encoding == AudioInputEncoding::DSD_U32_LE);
    for (size_t b = 0; b < blocks; ++b) {
        const uint8_t* blkPtr = src + b * blockBytes;
        for (size_t sub = 0; sub < 4u; ++sub) {
            const size_t byteOffset = isLe ? (3u - sub) : sub;
            uint8_t* outRow = dstU8 + (b * 4u + sub) * ch;
            for (size_t c = 0; c < ch; ++c) {
                outRow[c] = blkPtr[c * 4u + byteOffset];
            }
        }
    }

    outSrcBytesConsumed = blocks * blockBytes;
    return blocks * 4u;
}

} // namespace

// ═══ TPDF Dither Generator ═══
TpdfDitherGenerator::TpdfDitherGenerator(uint64_t seed)
    : state_(seed != 0 ? seed : 0x9E3779B97F4A7C15ULL) {}

void TpdfDitherGenerator::reset(uint64_t seed) {
    state_ = (seed != 0) ? seed : 0x9E3779B97F4A7C15ULL;
}

double TpdfDitherGenerator::nextTriangular() {
    // Two independent XorShift64* uniform draws produce an exact 2-LSB peak-to-peak TPDF in (-1, +1)
    constexpr double inv2Pow53 = 1.0 / 9007199254740992.0;

    auto nextUniform = [&]() -> double {
        uint64_t x = state_;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        state_ = x;
        const uint64_t mixed = x * 2685821657736338717ULL;
        return static_cast<double>(mixed >> 11) * inv2Pow53;
    };

    return nextUniform() - nextUniform();
}

// ═══ 64-Bit Polyphase Kaiser-Sinc Resampler ═══
bool PolyphaseResampler64::configure(int inputRate, int outputRate, int channels, ResamplerQuality quality) {
    active_ = false;
    polyphaseKernel_.clear();
    history_.clear();
    workBuf_.clear();

    if (inputRate <= 0 || outputRate <= 0 || channels <= 0) {
        return false;
    }

    inputRate_ = inputRate;
    outputRate_ = outputRate;
    channels_ = channels;
    ratio_ = static_cast<double>(outputRate) / static_cast<double>(inputRate);
    step_ = static_cast<double>(inputRate) / static_cast<double>(outputRate);
    phaseAccum_ = 0.0;

    if (inputRate == outputRate) {
        active_ = false;
        return true;
    }

    double beta = 12.2;
    double passbandEdge = 0.465;
    switch (quality) {
        case ResamplerQuality::MEDIUM:
            tapsPerPhase_ = 32;
            beta = 9.5;
            passbandEdge = 0.450;
            break;
        case ResamplerQuality::HIGH:
            tapsPerPhase_ = 64;
            beta = 12.2;
            passbandEdge = 0.465;
            break;
        case ResamplerQuality::ULTRA:
            tapsPerPhase_ = 128;
            beta = 14.8;
            passbandEdge = 0.475;
            break;
    }

    numPhases_ = RESAMPLER_NUM_PHASES;
    const double cutoff = (outputRate < inputRate)
        ? (passbandEdge * ratio_)
        : passbandEdge;

    // Allocate numPhases_ + 1 rows so linear interpolation between adjacent phases never wraps out of bounds
    const size_t totalRows = static_cast<size_t>(numPhases_) + 1u;
    polyphaseKernel_.assign(totalRows * static_cast<size_t>(tapsPerPhase_), 0.0);

    const double invI0 = 1.0 / besselI0(beta);
    const int half = tapsPerPhase_ / 2;
    const double halfSpan = 0.5 * static_cast<double>(tapsPerPhase_);

    for (int p = 0; p <= numPhases_; ++p) {
        const double frac = static_cast<double>(p) / static_cast<double>(numPhases_);
        double* row = polyphaseKernel_.data() + static_cast<size_t>(p) * static_cast<size_t>(tapsPerPhase_);
        double sum = 0.0;

        for (int k = 0; k < tapsPerPhase_; ++k) {
            const double d = static_cast<double>(half - 1 - k) + frac;
            const double normPos = d / halfSpan;
            const double arg = 1.0 - normPos * normPos;
            const double win = (arg > 0.0) ? (besselI0(beta * std::sqrt(arg)) * invI0) : 0.0;
            const double coeff = 2.0 * cutoff * sinc(2.0 * cutoff * d) * win;
            row[k] = coeff;
            sum += coeff;
        }

        if (std::fabs(sum) > 1e-15) {
            const double invSum = 1.0 / sum;
            for (int k = 0; k < tapsPerPhase_; ++k) {
                row[k] *= invSum;
            }
        }
    }

    const size_t histFrames = static_cast<size_t>(tapsPerPhase_ - 1);
    history_.assign(histFrames * static_cast<size_t>(channels_), 0.0);
    workBuf_.assign((histFrames + MAX_RESAMPLER_CHUNK_FRAMES) * static_cast<size_t>(channels_), 0.0);
    active_ = true;
    return true;
}

void PolyphaseResampler64::reset() {
    phaseAccum_ = 0.0;
    std::fill(history_.begin(), history_.end(), 0.0);
}

size_t PolyphaseResampler64::maxOutputFramesForInput(size_t inputFrames) const {
    if (!active_) return inputFrames;
    return static_cast<size_t>(std::ceil(static_cast<double>(inputFrames) * ratio_)) + 4u;
}

size_t PolyphaseResampler64::maxInputFramesForOutput(size_t maxOutputFrames) const {
    if (!active_) return maxOutputFrames;
    if (maxOutputFrames <= 4u) return 0;
    const double usableOut = static_cast<double>(maxOutputFrames - 4u);
    return static_cast<size_t>(std::floor(usableOut / ratio_));
}

size_t PolyphaseResampler64::process(
    const double* inInterleaved,
    size_t inFrames,
    double* outInterleaved,
    size_t maxOutFrames
) {
    if (!inInterleaved || !outInterleaved || inFrames == 0 || maxOutFrames == 0) {
        return 0;
    }

    const size_t ch = static_cast<size_t>(channels_);
    if (!active_) {
        const size_t copyFrames = std::min(inFrames, maxOutFrames);
        std::memcpy(outInterleaved, inInterleaved, copyFrames * ch * sizeof(double));
        return copyFrames;
    }

    const size_t histFrames = static_cast<size_t>(tapsPerPhase_ - 1);
    const size_t taps = static_cast<size_t>(tapsPerPhase_);
    const size_t maxChunk = (workBuf_.size() / ch) - histFrames;
    if (maxChunk == 0) return 0;

    size_t inOffset = 0;
    size_t outProduced = 0;

    while (inOffset < inFrames && outProduced < maxOutFrames) {
        const size_t chunk = std::min(inFrames - inOffset, maxChunk);

        // Assemble contiguous [history | chunk] inside pre-allocated workBuf_ (zero heap allocations)
        std::memcpy(workBuf_.data(), history_.data(), histFrames * ch * sizeof(double));
        std::memcpy(
            workBuf_.data() + histFrames * ch,
            inInterleaved + inOffset * ch,
            chunk * ch * sizeof(double)
        );

        if (phaseAccum_ < 0.0) {
            phaseAccum_ = 0.0;
        }
        const double chunkLimit = static_cast<double>(chunk);
        while (phaseAccum_ < chunkLimit && outProduced < maxOutFrames) {
            const int idx = std::clamp(static_cast<int>(std::floor(phaseAccum_)), 0, static_cast<int>(chunk) - 1);
            const double frac = std::clamp(phaseAccum_ - static_cast<double>(idx), 0.0, 1.0 - 1e-12);
            const double phasePos = frac * static_cast<double>(numPhases_);
            const int phase0 = std::clamp(static_cast<int>(phasePos), 0, numPhases_ - 1);
            const double alpha = std::clamp(phasePos - static_cast<double>(phase0), 0.0, 1.0);

            const double* k0 = polyphaseKernel_.data() + static_cast<size_t>(phase0) * taps;
            const double* k1 = k0 + taps;
            const double* baseFrame = workBuf_.data() + static_cast<size_t>(idx) * ch;
            double* outFrame = outInterleaved + outProduced * ch;

            if (channels_ == 2) {
                double accL = 0.0;
                double accR = 0.0;
                if (alpha < 1e-9) {
                    for (size_t k = 0; k < taps; ++k) {
                        const double w = k0[k];
                        accL += baseFrame[k * 2u] * w;
                        accR += baseFrame[k * 2u + 1u] * w;
                    }
                } else {
                    const double oneMinusAlpha = 1.0 - alpha;
                    for (size_t k = 0; k < taps; ++k) {
                        const double w = oneMinusAlpha * k0[k] + alpha * k1[k];
                        accL += baseFrame[k * 2u] * w;
                        accR += baseFrame[k * 2u + 1u] * w;
                    }
                }
                outFrame[0] = accL;
                outFrame[1] = accR;
            } else {
                for (size_t c = 0; c < ch; ++c) {
                    outFrame[c] = 0.0;
                }
                const double oneMinusAlpha = 1.0 - alpha;
                for (size_t k = 0; k < taps; ++k) {
                    const double w = (alpha < 1e-9) ? k0[k] : (oneMinusAlpha * k0[k] + alpha * k1[k]);
                    const double* srcRow = baseFrame + k * ch;
                    for (size_t c = 0; c < ch; ++c) {
                        outFrame[c] += srcRow[c] * w;
                    }
                }
            }

            phaseAccum_ += step_;
            outProduced++;
        }

        if (phaseAccum_ >= chunkLimit) {
            phaseAccum_ -= chunkLimit;
            std::memcpy(history_.data(), workBuf_.data() + chunk * ch, histFrames * ch * sizeof(double));
            inOffset += chunk;
        } else {
            // maxOutFrames was reached before exhausting chunk: advance history only by consumed
            // input frames so phaseAccum_ never goes negative and no input samples are skipped.
            const size_t consumed = std::min(chunk, static_cast<size_t>(std::max(0.0, std::floor(phaseAccum_))));
            phaseAccum_ -= static_cast<double>(consumed);
            if (phaseAccum_ < 0.0) {
                phaseAccum_ = 0.0;
            }
            std::memcpy(history_.data(), workBuf_.data() + consumed * ch, histFrames * ch * sizeof(double));
            inOffset += consumed;
            break;
        }
    }

    return outProduced;
}

// ═══ Multi-Stage FIR DSD-to-PCM Decimator ═══
bool DsdToPcmDecimator::configure(int dsdBitRate, int targetPcmRate, int channels, float gainCompensationDb) {
    active_ = false;
    halfBandStages_.clear();
    stage1Lut_.clear();
    stage1History_.clear();
    stageScratchA_.clear();
    stageScratchB_.clear();

    if (dsdBitRate <= 0 || targetPcmRate <= 0 || channels <= 0 || (dsdBitRate % targetPcmRate) != 0) {
        return false;
    }

    const int decimation = dsdBitRate / targetPcmRate;
    if (decimation < 8 || !isPowerOfTwo(decimation)) {
        return false;
    }

    channels_ = channels;
    totalDecimation_ = decimation;
    const double clampedGainDb = std::clamp(static_cast<double>(gainCompensationDb), -6.0, 12.0);
    gainLinear_ = std::pow(10.0, clampedGainDb / 20.0);

    // Choose Stage-1 byte-LUT decimation ratio so DSD256/DSD512 collapse high-rate stages directly
    // inside the O(1) 8-bit-per-lookup LUT (8x fewer operations than bit-by-bit MACs).
    double stage1Cutoff = 0.42 / 8.0;
    if (dsdBitRate >= 16000000 && decimation >= 32) {
        stage1Decimation_ = 32;
        stage1BytesPerStep_ = 4;
        stage1LutBytes_ = 20; // 160-tap symmetric Kaiser FIR for DSD512 (>96 dB rejection)
        stage1Cutoff = 0.28 / 32.0;
    } else if (decimation >= 16) {
        stage1Decimation_ = 16;
        stage1BytesPerStep_ = 2;
        stage1LutBytes_ = 16; // 128-tap symmetric Kaiser FIR for DSD128/DSD256/DSD64->176.4k
        stage1Cutoff = 0.35 / 16.0;
    } else {
        stage1Decimation_ = 8;
        stage1BytesPerStep_ = 1;
        stage1LutBytes_ = 8;  // 64-tap symmetric Kaiser FIR for 8:1 decimation
        stage1Cutoff = 0.42 / 8.0;
    }

    const int stage1Taps = stage1LutBytes_ * 8;
    const auto stage1Coeffs = designKaiserLowpassFir(stage1Taps, stage1Cutoff, 9.5);
    stage1Lut_.assign(static_cast<size_t>(stage1LutBytes_) * 256u, 0.0);
    for (int byteIdx = 0; byteIdx < stage1LutBytes_; ++byteIdx) {
        double* const lutRow = stage1Lut_.data() + static_cast<size_t>(byteIdx) * 256u;
        for (int val = 0; val < 256; ++val) {
            double acc = 0.0;
            for (int bit = 0; bit < 8; ++bit) {
                // MSB-first chronological order: bit 7 is earliest sample in the byte
                const bool bitHigh = ((val >> (7 - bit)) & 0x01) != 0;
                const double sample = bitHigh ? 1.0 : -1.0;
                acc += sample * stage1Coeffs[static_cast<size_t>(byteIdx * 8 + bit)];
            }
            lutRow[static_cast<size_t>(val)] = acc;
        }
    }

    stage1History_.assign(static_cast<size_t>(DSD_STAGE1_RING_SIZE) * static_cast<size_t>(channels_), DSD_SILENCE_BYTE);
    stage1HistPos_ = 0;

    // Cascaded 2:1 exact half-band FIR stages (symmetric folded pairs, zero even-offset taps skipped)
    int rem = decimation / stage1Decimation_;
    int numStages = 0;
    while (rem > 1) {
        numStages++;
        rem >>= 1;
    }

    halfBandStages_.resize(static_cast<size_t>(numStages));
    for (int s = 0; s < numStages; ++s) {
        const bool isFinalStage = (s == numStages - 1);
        const int taps = isFinalStage ? DSD_HALFBAND_FINAL_TAPS : DSD_HALFBAND_INTERM_TAPS;
        const double beta = isFinalStage ? 11.5 : 9.5;
        const auto fullCoeffs = designKaiserLowpassFir(taps, 0.25, beta);
        const int half = taps / 2;

        HalfBandStage& st = halfBandStages_[static_cast<size_t>(s)];
        st.taps = taps;
        st.centerCoeff = fullCoeffs[static_cast<size_t>(half)];
        st.oddCoeffs.clear();
        for (int d = 1; d <= half; d += 2) {
            st.oddCoeffs.push_back(fullCoeffs[static_cast<size_t>(half - d)]);
        }
        const size_t histFrames = static_cast<size_t>(taps - 1);
        st.history.assign(histFrames * static_cast<size_t>(channels_), 0.0);
        st.workBuf.assign((histFrames + MAX_DECIMATOR_CHUNK_FRAMES) * static_cast<size_t>(channels_), 0.0);
        st.emitPhase = false;
    }

    stageScratchA_.assign(MAX_DECIMATOR_CHUNK_FRAMES * static_cast<size_t>(channels_), 0.0);
    stageScratchB_.assign(MAX_DECIMATOR_CHUNK_FRAMES * static_cast<size_t>(channels_), 0.0);
    active_ = true;
    return true;
}

void DsdToPcmDecimator::reset() {
    std::fill(stage1History_.begin(), stage1History_.end(), DSD_SILENCE_BYTE);
    stage1HistPos_ = 0;
    for (auto& st : halfBandStages_) {
        std::fill(st.history.begin(), st.history.end(), 0.0);
        st.emitPhase = false;
    }
}

size_t DsdToPcmDecimator::processDsdU8(
    const uint8_t* dsdBytes,
    size_t dsdByteFrames,
    double* outInterleavedPcm,
    size_t maxOutFrames
) {
    if (!active_ || !dsdBytes || !outInterleavedPcm || dsdByteFrames == 0 || maxOutFrames == 0) {
        return 0;
    }

    const size_t ch = static_cast<size_t>(channels_);
    const size_t stepBytes = static_cast<size_t>(stage1BytesPerStep_);
    const int lutBytes = stage1LutBytes_;
    const double* const lutData = stage1Lut_.data();
    const size_t totalSteps = dsdByteFrames / stepBytes;
    if (totalSteps == 0) return 0;

    size_t inStepOffset = 0;
    size_t totalOutFrames = 0;

    while (inStepOffset < totalSteps && totalOutFrames < maxOutFrames) {
        const size_t chunkSteps = std::min(totalSteps - inStepOffset, MAX_DECIMATOR_CHUNK_FRAMES);

        // Stage 1: Byte-LUT FIR decimation (8:1, 16:1, or 32:1 — processes 8 DSD bits per table lookup)
        double* const curBuf = halfBandStages_.empty()
            ? (outInterleavedPcm + totalOutFrames * ch)
            : stageScratchA_.data();
        const size_t stage1Frames = halfBandStages_.empty()
            ? std::min(chunkSteps, maxOutFrames - totalOutFrames)
            : chunkSteps;

        const bool applyGainStage1 = halfBandStages_.empty();
        if (ch == 2u) {
            uint8_t* const histL = stage1History_.data();
            uint8_t* const histR = histL + DSD_STAGE1_RING_SIZE;
            int writePos = stage1HistPos_;
            for (size_t f = 0; f < stage1Frames; ++f) {
                const uint8_t* const stepPtr = dsdBytes + ((inStepOffset + f) * stepBytes) * 2u;
                for (size_t s = 0; s < stepBytes; ++s) {
                    histL[writePos] = stepPtr[s * 2u];
                    histR[writePos] = stepPtr[s * 2u + 1u];
                    writePos = (writePos + 1) & DSD_STAGE1_RING_MASK;
                }
                int readIdx = (writePos - lutBytes) & DSD_STAGE1_RING_MASK;
                double accL = 0.0;
                double accR = 0.0;
                for (int b = 0; b < lutBytes; ++b) {
                    const double* const row = lutData + static_cast<size_t>(b) * 256u;
                    accL += row[histL[readIdx]];
                    accR += row[histR[readIdx]];
                    readIdx = (readIdx + 1) & DSD_STAGE1_RING_MASK;
                }
                double* const outRow = curBuf + f * 2u;
                outRow[0] = applyGainStage1 ? (accL * gainLinear_) : accL;
                outRow[1] = applyGainStage1 ? (accR * gainLinear_) : accR;
            }
            stage1HistPos_ = writePos;
        } else {
            int writePos = stage1HistPos_;
            for (size_t f = 0; f < stage1Frames; ++f) {
                const uint8_t* const stepPtr = dsdBytes + ((inStepOffset + f) * stepBytes) * ch;
                for (size_t s = 0; s < stepBytes; ++s) {
                    const uint8_t* const inRow = stepPtr + s * ch;
                    for (size_t c = 0; c < ch; ++c) {
                        stage1History_[c * DSD_STAGE1_RING_SIZE + static_cast<size_t>(writePos)] = inRow[c];
                    }
                    writePos = (writePos + 1) & DSD_STAGE1_RING_MASK;
                }
                const int startRead = (writePos - lutBytes) & DSD_STAGE1_RING_MASK;
                double* const outRow = curBuf + f * ch;
                for (size_t c = 0; c < ch; ++c) {
                    const uint8_t* const histC = stage1History_.data() + c * DSD_STAGE1_RING_SIZE;
                    int readIdx = startRead;
                    double acc = 0.0;
                    for (int b = 0; b < lutBytes; ++b) {
                        acc += lutData[static_cast<size_t>(b) * 256u + histC[readIdx]];
                        readIdx = (readIdx + 1) & DSD_STAGE1_RING_MASK;
                    }
                    outRow[c] = applyGainStage1 ? (acc * gainLinear_) : acc;
                }
            }
            stage1HistPos_ = writePos;
        }

        if (halfBandStages_.empty()) {
            totalOutFrames += stage1Frames;
            inStepOffset += stage1Frames;
            continue;
        }

        // Cascaded 2:1 symmetric half-band stages with contiguous workBuf (zero modulo branches)
        size_t curFrames = stage1Frames;
        double* srcBuf = stageScratchA_.data();
        double* dstBuf = stageScratchB_.data();

        for (size_t s = 0; s < halfBandStages_.size(); ++s) {
            HalfBandStage& st = halfBandStages_[s];
            const size_t taps = static_cast<size_t>(st.taps);
            const size_t histFrames = taps - 1u;
            const size_t half = taps / 2u;
            const size_t oddCount = st.oddCoeffs.size();
            const double* const oddCoeffs = st.oddCoeffs.data();
            const double centerCoeff = st.centerCoeff;
            const bool isLast = (s + 1u == halfBandStages_.size());
            double* const writeTarget = isLast ? (outInterleavedPcm + totalOutFrames * ch) : dstBuf;
            const size_t maxWrite = isLast ? (maxOutFrames - totalOutFrames) : MAX_DECIMATOR_CHUNK_FRAMES;

            std::memcpy(st.workBuf.data(), st.history.data(), histFrames * ch * sizeof(double));
            std::memcpy(st.workBuf.data() + histFrames * ch, srcBuf, curFrames * ch * sizeof(double));

            const double* const work = st.workBuf.data();
            size_t outCount = 0;

            if (ch == 2u) {
                for (size_t f = 0; f < curFrames; ++f) {
                    if (st.emitPhase && outCount < maxWrite) {
                        const size_t center = f + half;
                        double accL = centerCoeff * work[center * 2u];
                        double accR = centerCoeff * work[center * 2u + 1u];
                        for (size_t j = 0; j < oddCount; ++j) {
                            const size_t d = j * 2u + 1u;
                            const double w = oddCoeffs[j];
                            const size_t lo = (center - d) * 2u;
                            const size_t hi = (center + d) * 2u;
                            accL += w * (work[lo] + work[hi]);
                            accR += w * (work[lo + 1u] + work[hi + 1u]);
                        }
                        double* const outRow = writeTarget + outCount * 2u;
                        outRow[0] = isLast ? (accL * gainLinear_) : accL;
                        outRow[1] = isLast ? (accR * gainLinear_) : accR;
                        outCount++;
                    }
                    st.emitPhase = !st.emitPhase;
                }
            } else {
                for (size_t f = 0; f < curFrames; ++f) {
                    if (st.emitPhase && outCount < maxWrite) {
                        const size_t center = f + half;
                        double* const outRow = writeTarget + outCount * ch;
                        const double* const cRow = work + center * ch;
                        for (size_t c = 0; c < ch; ++c) {
                            outRow[c] = centerCoeff * cRow[c];
                        }
                        for (size_t j = 0; j < oddCount; ++j) {
                            const size_t d = j * 2u + 1u;
                            const double w = oddCoeffs[j];
                            const double* const loRow = work + (center - d) * ch;
                            const double* const hiRow = work + (center + d) * ch;
                            for (size_t c = 0; c < ch; ++c) {
                                outRow[c] += w * (loRow[c] + hiRow[c]);
                            }
                        }
                        if (isLast) {
                            for (size_t c = 0; c < ch; ++c) {
                                outRow[c] *= gainLinear_;
                            }
                        }
                        outCount++;
                    }
                    st.emitPhase = !st.emitPhase;
                }
            }

            std::memcpy(st.history.data(), work + curFrames * ch, histFrames * ch * sizeof(double));
            curFrames = outCount;
            if (!isLast) {
                std::swap(srcBuf, dstBuf);
            }
        }

        totalOutFrames += curFrames;
        inStepOffset += chunkSteps;
    }

    return totalOutFrames;
}

// ═══ ITU-R BS.775-3 Channel Matrix / Downmixer ═══
void ChannelMatrixProcessor::configure(int inputChannels, int outputChannels) {
    inputChannels_ = std::max(1, inputChannels);
    outputChannels_ = std::max(1, outputChannels);
}

void ChannelMatrixProcessor::process(const double* inInterleaved, double* outInterleaved, size_t frames) const {
    if (!inInterleaved || !outInterleaved || frames == 0) return;

    const size_t inCh = static_cast<size_t>(inputChannels_);
    const size_t outCh = static_cast<size_t>(outputChannels_);

    if (inCh == outCh) {
        if (inInterleaved != outInterleaved) {
            std::memcpy(outInterleaved, inInterleaved, frames * outCh * sizeof(double));
        }
        return;
    }

    // Mono -> Stereo exact duplication
    if (inCh == 1u && outCh == 2u) {
        for (size_t f = 0; f < frames; ++f) {
            const double m = inInterleaved[f];
            outInterleaved[f * 2u] = m;
            outInterleaved[f * 2u + 1u] = m;
        }
        return;
    }

    // Stereo -> Mono (-6 dB sum prevents full-scale correlated clipping)
    if (inCh == 2u && outCh == 1u) {
        for (size_t f = 0; f < frames; ++f) {
            outInterleaved[f] = 0.5 * (inInterleaved[f * 2u] + inInterleaved[f * 2u + 1u]);
        }
        return;
    }

    // Multichannel -> Stereo downmix per ITU-R BS.775-3
    if (outCh == 2u) {
        if (inCh == 3u) {
            // 3.0 (L, R, C)
            constexpr double norm = 1.0 / (1.0 + INV_SQRT_2);
            for (size_t f = 0; f < frames; ++f) {
                const double* s = inInterleaved + f * 3u;
                outInterleaved[f * 2u] = (s[0] + INV_SQRT_2 * s[2]) * norm;
                outInterleaved[f * 2u + 1u] = (s[1] + INV_SQRT_2 * s[2]) * norm;
            }
            return;
        }
        if (inCh == 4u) {
            // 4.0 Quad (L, R, Ls, Rs)
            constexpr double norm = 1.0 / (1.0 + INV_SQRT_2);
            for (size_t f = 0; f < frames; ++f) {
                const double* s = inInterleaved + f * 4u;
                outInterleaved[f * 2u] = (s[0] + INV_SQRT_2 * s[2]) * norm;
                outInterleaved[f * 2u + 1u] = (s[1] + INV_SQRT_2 * s[3]) * norm;
            }
            return;
        }
        if (inCh == 5u) {
            // 5.0 (L, R, C, Ls, Rs)
            constexpr double norm = 1.0 / (1.0 + 2.0 * INV_SQRT_2);
            for (size_t f = 0; f < frames; ++f) {
                const double* s = inInterleaved + f * 5u;
                outInterleaved[f * 2u] = (s[0] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[3]) * norm;
                outInterleaved[f * 2u + 1u] = (s[1] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[4]) * norm;
            }
            return;
        }
        if (inCh == 6u) {
            // 5.1 (L, R, C, LFE, Ls, Rs) — LFE omitted per ITU-R BS.775-3
            constexpr double norm = 1.0 / (1.0 + 2.0 * INV_SQRT_2);
            for (size_t f = 0; f < frames; ++f) {
                const double* s = inInterleaved + f * 6u;
                outInterleaved[f * 2u] = (s[0] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[4]) * norm;
                outInterleaved[f * 2u + 1u] = (s[1] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[5]) * norm;
            }
            return;
        }
        if (inCh == 8u) {
            // 7.1 (L, R, C, LFE, Lb, Rb, Ls, Rs) — SMPTE/Android Media3 layout
            constexpr double norm = 1.0 / (1.0 + 3.0 * INV_SQRT_2);
            for (size_t f = 0; f < frames; ++f) {
                const double* s = inInterleaved + f * 8u;
                outInterleaved[f * 2u] =
                    (s[0] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[4] + INV_SQRT_2 * s[6]) * norm;
                outInterleaved[f * 2u + 1u] =
                    (s[1] + INV_SQRT_2 * s[2] + INV_SQRT_2 * s[5] + INV_SQRT_2 * s[7]) * norm;
            }
            return;
        }
    }

    // Generic N -> M fallback: copy shared channels, fold surplus into L/R or zero-pad extra outputs
    const size_t shared = std::min(inCh, outCh);
    for (size_t f = 0; f < frames; ++f) {
        const double* src = inInterleaved + f * inCh;
        double* dst = outInterleaved + f * outCh;
        for (size_t c = 0; c < shared; ++c) {
            dst[c] = src[c];
        }
        for (size_t c = shared; c < outCh; ++c) {
            dst[c] = (inCh == 1u && c == 1u) ? src[0] : 0.0;
        }
        if (inCh > outCh && outCh == 2u) {
            double extraL = 0.0;
            double extraR = 0.0;
            int countL = 1;
            int countR = 1;
            for (size_t c = 2u; c < inCh; ++c) {
                if ((c & 1u) == 0u) {
                    extraL += INV_SQRT_2 * src[c];
                    countL++;
                } else {
                    extraR += INV_SQRT_2 * src[c];
                    countR++;
                }
            }
            dst[0] = (dst[0] + extraL) / (1.0 + static_cast<double>(countL - 1) * INV_SQRT_2);
            dst[1] = (dst[1] + extraR) / (1.0 + static_cast<double>(countR - 1) * INV_SQRT_2);
        } else if (inCh > outCh && outCh == 1u) {
            double sum = 0.0;
            for (size_t c = 0; c < inCh; ++c) {
                sum += src[c];
            }
            dst[0] = sum / static_cast<double>(inCh);
        }
    }
}

// ═══ Capability Negotiation & Smart Bypass ═══
bool AudioDspPipeline::isDsdEncoding(AudioInputEncoding encoding) {
    return encoding == AudioInputEncoding::DSD_U8 ||
           encoding == AudioInputEncoding::DSD_U32_BE ||
           encoding == AudioInputEncoding::DSD_U32_LE;
}

int AudioDspPipeline::inputBytesPerSampleOrBlock(AudioInputEncoding encoding) {
    switch (encoding) {
        case AudioInputEncoding::PCM_16BIT: return 2;
        case AudioInputEncoding::PCM_24BIT: return 3;
        case AudioInputEncoding::PCM_32BIT: return 4;
        case AudioInputEncoding::PCM_FLOAT: return 4;
        case AudioInputEncoding::DSD_U8: return 1;
        case AudioInputEncoding::DSD_U32_BE: return 4;
        case AudioInputEncoding::DSD_U32_LE: return 4;
    }
    return 2;
}

int AudioDspPipeline::selectBestSampleRate(int sourceRate, const std::vector<int>& supportedRates) {
    if (sourceRate <= 0 || supportedRates.empty()) return sourceRate;

    // 1. Exact match (Smart Bypass — zero resampling)
    if (std::find(supportedRates.begin(), supportedRates.end(), sourceRate) != supportedRates.end()) {
        return sourceRate;
    }

    // 2. Integer upsampling within the same clock family (smallest multiple >= sourceRate)
    int bestIntUp = 0;
    for (int r : supportedRates) {
        if (r > sourceRate && (r % sourceRate) == 0) {
            if (bestIntUp == 0 || r < bestIntUp) bestIntUp = r;
        }
    }
    if (bestIntUp > 0) return bestIntUp;

    // 3. Integer downsampling within the same clock family (largest divisor <= sourceRate)
    int bestIntDown = 0;
    for (int r : supportedRates) {
        if (r > 0 && r < sourceRate && (sourceRate % r) == 0) {
            if (r > bestIntDown) bestIntDown = r;
        }
    }
    if (bestIntDown > 0) return bestIntDown;

    // 4. Same clock family (44.1 kHz or 48 kHz): prefer smallest rate >= sourceRate, else largest < sourceRate
    int bestFamilyUp = 0;
    int bestFamilyDown = 0;
    for (int r : supportedRates) {
        if (r <= 0 || !isSameClockFamily(sourceRate, r)) continue;
        if (r > sourceRate) {
            if (bestFamilyUp == 0 || r < bestFamilyUp) bestFamilyUp = r;
        } else {
            if (r > bestFamilyDown) bestFamilyDown = r;
        }
    }
    if (bestFamilyUp > 0) return bestFamilyUp;
    if (bestFamilyDown > 0) return bestFamilyDown;

    // 5. Cross-family fallback: prefer smallest rate >= sourceRate (no bandwidth loss), else highest available
    int bestAnyUp = 0;
    int bestAnyDown = 0;
    for (int r : supportedRates) {
        if (r <= 0) continue;
        if (r > sourceRate) {
            if (bestAnyUp == 0 || r < bestAnyUp) bestAnyUp = r;
        } else {
            if (r > bestAnyDown) bestAnyDown = r;
        }
    }
    return (bestAnyUp > 0) ? bestAnyUp : bestAnyDown;
}

NegotiatedStreamPlan AudioDspPipeline::negotiate(
    const UacDeviceDescriptorInfo& deviceInfo,
    const StreamNegotiationRequest& request
) {
    NegotiatedStreamPlan plan;
    if (deviceInfo.streamAltSettings.empty() ||
        request.inputSampleRate <= 0 ||
        request.inputChannels <= 0) {
        return plan;
    }

    auto pickBestPcmAlt = [&](int targetRate, int preferredChannels, int minBits, int preferredBits) -> const UacStreamAltSetting* {
        const UacStreamAltSetting* bestAlt = nullptr;
        int bestScore = std::numeric_limits<int>::min();

        for (const auto& alt : deviceInfo.streamAltSettings) {
            if (!alt.isPcm || alt.outEndpoint == 0) continue;
            if (alt.bitResolution < minBits || alt.subslotSize <= 0) continue;
            if (targetRate > 0 && !altSupportsSampleRate(deviceInfo, alt, targetRate)) continue;

            int score = 0;
            if (targetRate > 0 && altFitsBandwidth(deviceInfo, alt, targetRate, true)) {
                score += 50000; // Prefer endpoints with full +/- 2% async packet headroom
            }
            if (alt.channels == preferredChannels) {
                score += 100000;
            } else if (alt.channels == 2) {
                score += 50000;
            } else {
                score -= std::abs(alt.channels - preferredChannels) * 1000;
            }

            if (alt.bitResolution >= preferredBits) {
                score += 10000;
            }
            if (alt.bitResolution == preferredBits) {
                score += 200;
            }
            score += alt.bitResolution * 10 + alt.subslotSize * 2;
            if (alt.syncType == UacSyncType::ASYNCHRONOUS) {
                score += 5;
            }

            if (score > bestScore) {
                bestScore = score;
                bestAlt = &alt;
            }
        }
        return bestAlt;
    };

    // ─── DSD Input Negotiation (Native DSD -> DoP -> DSD-to-PCM Decimator) ───
    if (isDsdEncoding(request.inputEncoding)) {
        const int dsdBitRate = request.inputSampleRate;
        const DsdPlaybackMode mode = request.options.dsdMode;

        // 1. Try Native DSD RAW_DATA alt setting
        if (mode == DsdPlaybackMode::AUTO || mode == DsdPlaybackMode::NATIVE) {
            const UacStreamAltSetting* bestDsdAlt = nullptr;
            int bestDsdWireRate = 0;
            bool bestDsdHasMargin = false;
            for (const auto& alt : deviceInfo.streamAltSettings) {
                if (!alt.isDsd || alt.outEndpoint == 0 || alt.subslotSize <= 0 || alt.channels != request.inputChannels) continue;
                const int slotBits = (alt.subslotSize > 0) ? (alt.subslotSize * 8) : 32;
                if (slotBits <= 0 || (dsdBitRate % slotBits) != 0) continue;
                const int wireRate = dsdBitRate / slotBits;
                if (!altSupportsSampleRate(deviceInfo, alt, wireRate)) continue;
                const bool hasMargin = altFitsBandwidth(deviceInfo, alt, wireRate, true);

                // Prefer endpoints with full +/- 2% async headroom, then 32-bit grouped DSD (XMOS/Amanero)
                const bool better = (bestDsdAlt == nullptr) ||
                    (hasMargin && !bestDsdHasMargin) ||
                    (hasMargin == bestDsdHasMargin && alt.subslotSize > bestDsdAlt->subslotSize);
                if (better) {
                    bestDsdAlt = &alt;
                    bestDsdWireRate = wireRate;
                    bestDsdHasMargin = hasMargin;
                }
            }

            if (bestDsdAlt != nullptr) {
                plan.valid = true;
                plan.selectedAlt = *bestDsdAlt;
                plan.wireSampleRate = bestDsdWireRate;
                plan.wireBitDepth = bestDsdAlt->bitResolution;
                plan.wireSubslotSize = bestDsdAlt->subslotSize;
                plan.wireChannels = bestDsdAlt->channels;
                plan.outputMode = OutputTransportMode::NATIVE_DSD;
                plan.dspFlags = DSP_STAGE_NONE;
                return plan;
            }
        }

        // 2. Try DoP v1.1 (16 DSD bits + 8-bit 0x05/0xFA marker inside 24-bit PCM container)
        if ((mode == DsdPlaybackMode::AUTO || mode == DsdPlaybackMode::NATIVE || mode == DsdPlaybackMode::DOP) &&
            (dsdBitRate % 16) == 0) {
            const int dopPcmRate = dsdBitRate / 16;
            const UacStreamAltSetting* dopAlt = nullptr;
            bool bestDopHasMargin = false;
            for (const auto& alt : deviceInfo.streamAltSettings) {
                if (!alt.isPcm || alt.outEndpoint == 0) continue;
                if (alt.channels != request.inputChannels) continue;
                if (alt.bitResolution < 24 || alt.subslotSize < 3) continue;
                if (!altSupportsSampleRate(deviceInfo, alt, dopPcmRate)) continue;
                const bool hasMargin = altFitsBandwidth(deviceInfo, alt, dopPcmRate, true);

                const bool better = (dopAlt == nullptr) ||
                    (hasMargin && !bestDopHasMargin) ||
                    (hasMargin == bestDopHasMargin && alt.subslotSize > dopAlt->subslotSize);
                if (better) {
                    dopAlt = &alt;
                    bestDopHasMargin = hasMargin;
                }
            }

            if (dopAlt != nullptr) {
                plan.valid = true;
                plan.selectedAlt = *dopAlt;
                plan.wireSampleRate = dopPcmRate;
                plan.wireBitDepth = dopAlt->bitResolution;
                plan.wireSubslotSize = dopAlt->subslotSize;
                plan.wireChannels = dopAlt->channels;
                plan.outputMode = OutputTransportMode::DOP;
                plan.dspFlags = DSP_STAGE_DOP_PACKING;
                return plan;
            }
        }

        // 3. Fallback: Multi-stage FIR DSD-to-PCM Decimator (+ optional resampler if DAC lacks 44.1k family)
        std::set<int> pcmRatesSet;
        for (const auto& alt : deviceInfo.streamAltSettings) {
            if (!alt.isPcm || alt.outEndpoint == 0) continue;
            for (int r : getAltSupportedRates(deviceInfo, alt)) {
                if (r > 0) pcmRatesSet.insert(r);
            }
        }
        std::vector<int> pcmRates(pcmRatesSet.begin(), pcmRatesSet.end());

        // Prefer exact power-of-2 decimation matching a supported DAC PCM rate (up to 352.8 kHz)
        int chosenDecimation = 0;
        int chosenIntermRate = 0;
        int chosenWireRate = 0;
        for (int dec = 8; dec <= 512; dec <<= 1) {
            if ((dsdBitRate % dec) != 0) continue;
            const int candRate = dsdBitRate / dec;
            if (candRate > 384000) continue;
            if (candRate < 44100) break;
            if (pcmRates.empty() || std::find(pcmRates.begin(), pcmRates.end(), candRate) != pcmRates.end()) {
                chosenDecimation = dec;
                chosenIntermRate = candRate;
                chosenWireRate = candRate;
                break;
            }
        }

        // If the DAC only exposes 48 kHz-family rates, decimate into [88.2k..176.4k] and engage 64-bit resampler
        if (chosenDecimation == 0) {
            for (int dec = 8; dec <= 512; dec <<= 1) {
                if ((dsdBitRate % dec) != 0) continue;
                const int candRate = dsdBitRate / dec;
                if (candRate <= 176400 && candRate >= 44100) {
                    chosenDecimation = dec;
                    chosenIntermRate = candRate;
                    break;
                }
            }
            if (chosenDecimation == 0) {
                chosenDecimation = std::max(8, dsdBitRate / (DSD64_BIT_RATE_HZ / 32));
                if (!isPowerOfTwo(chosenDecimation)) chosenDecimation = 32;
                chosenIntermRate = dsdBitRate / chosenDecimation;
            }
            chosenWireRate = selectBestSampleRate(chosenIntermRate, pcmRates);
        }

        const UacStreamAltSetting* pcmAlt = pickBestPcmAlt(
            chosenWireRate,
            request.inputChannels,
            16,
            std::max(24, request.preferredBitDepth)
        );
        if (!pcmAlt) return plan;

        uint32_t flags = DSP_STAGE_DSD_TO_PCM;
        if (chosenWireRate != chosenIntermRate) {
            flags |= DSP_STAGE_RESAMPLER;
        }
        if (request.inputChannels == 1 && pcmAlt->channels == 2) {
            flags |= DSP_STAGE_CHANNEL_MONO_TO_STEREO;
        } else if (request.inputChannels != pcmAlt->channels) {
            flags |= DSP_STAGE_CHANNEL_DOWNMIX;
        }
        if (request.options.ditherEnabled && pcmAlt->bitResolution <= 24) {
            flags |= DSP_STAGE_DITHER_TPDF;
        }

        plan.valid = true;
        plan.selectedAlt = *pcmAlt;
        plan.wireSampleRate = chosenWireRate;
        plan.wireBitDepth = pcmAlt->bitResolution;
        plan.wireSubslotSize = pcmAlt->subslotSize;
        plan.wireChannels = pcmAlt->channels;
        plan.outputMode = OutputTransportMode::DSD_TO_PCM;
        plan.dspFlags = flags;
        plan.dsdDecimationFactor = chosenDecimation;
        return plan;
    }

    // ─── PCM Input Negotiation (Smart Bypass vs Selective Hi-Fi Fallback DSP) ───
    std::vector<const UacStreamAltSetting*> pcmAlts;
    for (const auto& alt : deviceInfo.streamAltSettings) {
        if (alt.isPcm && alt.outEndpoint != 0 && alt.subslotSize > 0) {
            pcmAlts.push_back(&alt);
        }
    }
    if (pcmAlts.empty()) return plan;

    // Determine target wire channel count (exact match -> stereo -> closest available)
    int targetChannels = 0;
    for (const auto* alt : pcmAlts) {
        if (alt->channels == request.inputChannels) {
            targetChannels = alt->channels;
            break;
        }
    }
    if (targetChannels == 0) {
        for (const auto* alt : pcmAlts) {
            if (alt->channels == 2) {
                targetChannels = 2;
                break;
            }
        }
    }
    if (targetChannels == 0) {
        targetChannels = pcmAlts.front()->channels;
        for (const auto* alt : pcmAlts) {
            if (std::abs(alt->channels - request.inputChannels) <
                std::abs(targetChannels - request.inputChannels)) {
                targetChannels = alt->channels;
            }
        }
    }

    // Collect rates supported by alt-settings matching targetChannels
    std::set<int> candRateSet;
    for (const auto* alt : pcmAlts) {
        if (alt->channels != targetChannels) continue;
        for (int r : getAltSupportedRates(deviceInfo, *alt)) {
            if (r > 0) candRateSet.insert(r);
        }
    }
    std::vector<int> candRates(candRateSet.begin(), candRateSet.end());
    const int wireRate = selectBestSampleRate(request.inputSampleRate, candRates);

    const int effInputBits = effectiveInputBitDepth(request.inputEncoding);
    const int desiredBits = std::max(effInputBits, request.preferredBitDepth);

    const UacStreamAltSetting* bestAlt = nullptr;
    int bestScore = std::numeric_limits<int>::min();
    for (const auto* alt : pcmAlts) {
        if (alt->channels != targetChannels) continue;
        if (!altSupportsSampleRate(deviceInfo, *alt, wireRate)) continue;

        int score = 0;
        if (altFitsBandwidth(deviceInfo, *alt, wireRate, true)) {
            score += 50000; // Prefer endpoints with full +/- 2% async packet headroom
        }
        if (alt->bitResolution >= effInputBits) {
            score += 20000; // Lossless bit preservation strongly preferred over bit-depth truncation
        }
        if (alt->bitResolution >= desiredBits) {
            score += 2000;
        }
        if (alt->bitResolution == effInputBits && request.preferredBitDepth == effInputBits) {
            score += 400;
        }
        score += alt->bitResolution * 10 + alt->subslotSize * 2;
        if (alt->syncType == UacSyncType::ASYNCHRONOUS) {
            score += 5;
        }

        if (score > bestScore) {
            bestScore = score;
            bestAlt = alt;
        }
    }

    if (!bestAlt) return plan;

    const bool needsResample = (wireRate != request.inputSampleRate);
    const bool needsChannelMatrix = (bestAlt->channels != request.inputChannels);
    const bool isFloatInput = (request.inputEncoding == AudioInputEncoding::PCM_FLOAT);
    const bool needsBitReduction = (effInputBits > bestAlt->bitResolution);
    const int inBytesPerSample = inputBytesPerSampleOrBlock(request.inputEncoding);
    const bool needsZeroPadding = !needsResample && !needsChannelMatrix && !isFloatInput && !needsBitReduction &&
                                  (bestAlt->subslotSize > inBytesPerSample || bestAlt->bitResolution > effInputBits);

    uint32_t flags = DSP_STAGE_NONE;
    if (needsResample) {
        flags |= DSP_STAGE_RESAMPLER;
    }
    if (needsChannelMatrix) {
        if (request.inputChannels == 1 && bestAlt->channels == 2) {
            flags |= DSP_STAGE_CHANNEL_MONO_TO_STEREO;
        } else {
            flags |= DSP_STAGE_CHANNEL_DOWNMIX;
        }
    }
    if (needsZeroPadding) {
        flags |= DSP_STAGE_ZERO_PADDING;
    }
    if (isFloatInput) {
        flags |= DSP_STAGE_FLOAT_TO_PCM;
    }

    const bool dspActive = needsResample || needsChannelMatrix || isFloatInput || needsBitReduction;
    if (dspActive && request.options.ditherEnabled &&
        (needsBitReduction || bestAlt->bitResolution <= 24)) {
        flags |= DSP_STAGE_DITHER_TPDF;
    }

    plan.valid = true;
    plan.selectedAlt = *bestAlt;
    plan.wireSampleRate = wireRate;
    plan.wireBitDepth = bestAlt->bitResolution;
    plan.wireSubslotSize = bestAlt->subslotSize;
    plan.wireChannels = bestAlt->channels;
    plan.outputMode = dspActive ? OutputTransportMode::PCM_DSP : OutputTransportMode::PCM_BIT_PERFECT;
    plan.dspFlags = flags;
    return plan;
}

// ═══ Pipeline Configuration & Control ═══
bool AudioDspPipeline::configure(const StreamNegotiationRequest& request, const NegotiatedStreamPlan& plan) {
    request_ = request;
    plan_ = plan;
    dopMarkerToggle_ = DOP_MARKER_EVEN;
    dither_.reset();

    if (!plan.valid || plan.wireChannels <= 0 || plan.wireSubslotSize <= 0 || plan.wireSampleRate <= 0) {
        return false;
    }

    channelMatrix_.configure(request.inputChannels, plan.wireChannels);

    const bool isPcmBitPerfect = (plan.outputMode == OutputTransportMode::PCM_BIT_PERFECT);
    if (isPcmBitPerfect) {
        // Isolate PCM_BIT_PERFECT from DSD & resampler: keep stageBufA/B only for optional SW volume fallback
        dsdUnpackScratch_.clear();
        resampler_.configure(plan.wireSampleRate, plan.wireSampleRate, plan.wireChannels, request.options.resamplerQuality);

        const size_t maxChannels = static_cast<size_t>(std::max(request.inputChannels, plan.wireChannels));
        const size_t stageCapacitySamples = MAX_DSP_BLOCK_FRAMES * maxChannels;
        stageBufA_.assign(stageCapacitySamples, 0.0);
        stageBufB_.assign(stageCapacitySamples, 0.0);

        const size_t wireFrameBytes = static_cast<size_t>(plan.wireChannels) * static_cast<size_t>(plan.wireSubslotSize);
        const size_t requiredWireBytes = std::max<size_t>(MAX_WIRE_SCRATCH_BYTES, MAX_DSP_BLOCK_FRAMES * wireFrameBytes);
        wireOutScratch_.assign(requiredWireBytes, 0u);
        return true;
    }

    int resamplerInputRate = request.inputSampleRate;
    if (plan.outputMode == OutputTransportMode::DSD_TO_PCM) {
        if (plan.dsdDecimationFactor < 8 || (request.inputSampleRate % plan.dsdDecimationFactor) != 0) {
            return false;
        }
        const int intermRate = request.inputSampleRate / plan.dsdDecimationFactor;
        if (!dsdDecimator_.configure(
                request.inputSampleRate,
                intermRate,
                request.inputChannels,
                request.options.dsdGainCompensationDb)) {
            return false;
        }
        resamplerInputRate = intermRate;
    } else if (isDsdEncoding(request.inputEncoding)) {
        dsdDecimator_.reset();
    }

    if ((plan.dspFlags & DSP_STAGE_RESAMPLER) != 0u) {
        if (!resampler_.configure(
                resamplerInputRate,
                plan.wireSampleRate,
                plan.wireChannels,
                request.options.resamplerQuality)) {
            return false;
        }
    } else {
        resampler_.configure(plan.wireSampleRate, plan.wireSampleRate, plan.wireChannels, request.options.resamplerQuality);
    }

    // Pre-allocate all working scratch buffers so processToWire() executes with zero allocations
    const size_t maxChannels = static_cast<size_t>(std::max(request.inputChannels, plan.wireChannels));
    const size_t maxResampledFrames = std::max<size_t>(
        MAX_DSP_BLOCK_FRAMES,
        resampler_.maxOutputFramesForInput(MAX_DSP_BLOCK_FRAMES)
    );
    const size_t stageCapacitySamples = std::max(MAX_DSP_BLOCK_FRAMES, maxResampledFrames) * maxChannels;

    stageBufA_.assign(stageCapacitySamples, 0.0);
    stageBufB_.assign(stageCapacitySamples, 0.0);

    const size_t wireFrameBytes = static_cast<size_t>(plan.wireChannels) * static_cast<size_t>(plan.wireSubslotSize);
    const size_t requiredWireBytes = std::max<size_t>(
        MAX_WIRE_SCRATCH_BYTES,
        maxResampledFrames * wireFrameBytes
    );
    const uint8_t wireInitByte = (plan.outputMode == OutputTransportMode::NATIVE_DSD) ? DSD_SILENCE_BYTE : 0u;
    wireOutScratch_.assign(requiredWireBytes, wireInitByte);

    if (isDsdEncoding(request.inputEncoding)) {
        dsdUnpackScratch_.assign(MAX_DSD_UNPACK_BYTES, DSD_SILENCE_BYTE);
    } else {
        dsdUnpackScratch_.clear();
    }

    return true;
}

void AudioDspPipeline::reset() {
    dopMarkerToggle_ = DOP_MARKER_EVEN;
    dither_.reset();
    resampler_.reset();
    dsdDecimator_.reset();
}

void AudioDspPipeline::clearPlan() {
    reset();
    plan_ = NegotiatedStreamPlan{};
}

void AudioDspPipeline::setHardwareVolumeActive(bool hwVolActive) {
    hwVolumeActive_.store(hwVolActive, std::memory_order_release);
}

void AudioDspPipeline::setSoftwareGainLinear(double linearGain) {
    const double clamped = std::clamp(linearGain, 0.0, 1.0);
    softwareGainLinear_.store(clamped, std::memory_order_release);
}

bool AudioDspPipeline::isSoftwareVolumeBypassed() const {
    if (hwVolumeActive_.load(std::memory_order_acquire)) {
        return true;
    }
    return softwareGainLinear_.load(std::memory_order_acquire) >= SW_VOLUME_BYPASS_THRESHOLD;
}

uint32_t AudioDspPipeline::activeDspFlags() const {
    uint32_t flags = plan_.dspFlags;
    const bool supportsSwVol = (plan_.outputMode == OutputTransportMode::PCM_BIT_PERFECT) ||
                               (plan_.outputMode == OutputTransportMode::PCM_DSP) ||
                               (plan_.outputMode == OutputTransportMode::DSD_TO_PCM);
    if (supportsSwVol && !isSoftwareVolumeBypassed()) {
        flags |= DSP_STAGE_SW_VOLUME;
        if (request_.options.ditherEnabled && plan_.wireBitDepth <= 24) {
            flags |= DSP_STAGE_DITHER_TPDF;
        }
    }
    return flags;
}

// ═══ Per-Buffer Audio Processing (Zero Allocations, Zero Mutexes) ═══
size_t AudioDspPipeline::processToWire(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    outInputBytesConsumed = 0;
    outWireData = nullptr;

    if (!plan_.valid || !inputData || inputBytes == 0 || maxWireBytes == 0) {
        return 0;
    }

    switch (plan_.outputMode) {
        case OutputTransportMode::PCM_BIT_PERFECT:
            if (isSoftwareVolumeBypassed()) {
                return processPcmFastPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
            }
            return processPcmDspPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
        case OutputTransportMode::PCM_DSP:
            return processPcmDspPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
        case OutputTransportMode::DOP:
            return processDopPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
        case OutputTransportMode::NATIVE_DSD:
            return processNativeDsdPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
        case OutputTransportMode::DSD_TO_PCM:
            return processDsdToPcmPath(inputData, inputBytes, maxWireBytes, outInputBytesConsumed, outWireData);
        case OutputTransportMode::INACTIVE:
            return 0;
    }
    return 0;
}

size_t AudioDspPipeline::processPcmFastPath(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    const size_t ch = static_cast<size_t>(plan_.wireChannels);
    const size_t inBytesPerSample = static_cast<size_t>(inputBytesPerSampleOrBlock(request_.inputEncoding));
    const size_t outSubslot = static_cast<size_t>(plan_.wireSubslotSize);
    const size_t inFrameBytes = inBytesPerSample * ch;
    const size_t outFrameBytes = outSubslot * ch;

    const size_t availInFrames = inputBytes / inFrameBytes;
    const size_t maxOutFrames = maxWireBytes / outFrameBytes;
    const size_t frames = std::min(availInFrames, maxOutFrames);
    if (frames == 0) return 0;

    // Zero-copy direct wire pass-through when input byte layout exactly matches USB wire slot
    const bool directMatch =
        (request_.inputEncoding == AudioInputEncoding::PCM_16BIT && outSubslot == 2u) ||
        (request_.inputEncoding == AudioInputEncoding::PCM_24BIT && outSubslot == 3u) ||
        (request_.inputEncoding == AudioInputEncoding::PCM_32BIT && outSubslot == 4u);

    if (directMatch) {
        const size_t bytes = frames * outFrameBytes;
        outInputBytesConsumed = bytes;
        outWireData = inputData;
        return bytes;
    }

    // Lossless MSB-aligned Little-Endian zero-padding (e.g., 16->24, 16->32, 24->32)
    const size_t maxScratchFrames = wireOutScratch_.size() / outFrameBytes;
    const size_t packFrames = std::min(frames, maxScratchFrames);
    if (packFrames == 0) return 0;

    const size_t totalSamples = packFrames * ch;
    const uint8_t* __restrict__ src = inputData;
    uint8_t* __restrict__ dst = wireOutScratch_.data();

    if (request_.inputEncoding == AudioInputEncoding::PCM_16BIT && outSubslot == 3u) {
        for (size_t i = 0; i < totalSamples; ++i) {
            dst[0] = 0x00u;
            dst[1] = src[i * 2u];
            dst[2] = src[i * 2u + 1u];
            dst += 3;
        }
    } else if (request_.inputEncoding == AudioInputEncoding::PCM_16BIT && outSubslot == 4u) {
        for (size_t i = 0; i < totalSamples; ++i) {
            const uint32_t w =
                (static_cast<uint32_t>(src[i * 2u]) << 16) |
                (static_cast<uint32_t>(src[i * 2u + 1u]) << 24);
            std::memcpy(dst + i * 4u, &w, sizeof(uint32_t));
        }
    } else if (request_.inputEncoding == AudioInputEncoding::PCM_24BIT && outSubslot == 4u) {
        for (size_t i = 0; i < totalSamples; ++i) {
            const uint32_t w =
                (static_cast<uint32_t>(src[i * 3u]) << 8) |
                (static_cast<uint32_t>(src[i * 3u + 1u]) << 16) |
                (static_cast<uint32_t>(src[i * 3u + 2u]) << 24);
            std::memcpy(dst + i * 4u, &w, sizeof(uint32_t));
        }
    } else {
        // Fallback through exact 32-bit MSB-aligned packing
        for (size_t i = 0; i < totalSamples; ++i) {
            int32_t s32 = 0;
            if (request_.inputEncoding == AudioInputEncoding::PCM_16BIT) {
                const uint16_t lo = src[i * 2u];
                const uint16_t hi = src[i * 2u + 1u];
                s32 = static_cast<int32_t>(static_cast<uint32_t>(static_cast<int16_t>(lo | (hi << 8))) << 16);
            } else if (request_.inputEncoding == AudioInputEncoding::PCM_24BIT) {
                const uint32_t raw = (static_cast<uint32_t>(src[i * 3u]) << 8) |
                                     (static_cast<uint32_t>(src[i * 3u + 1u]) << 16) |
                                     (static_cast<uint32_t>(src[i * 3u + 2u]) << 24);
                s32 = static_cast<int32_t>(raw);
            } else {
                s32 = static_cast<int32_t>(readLeU32(src + i * 4u));
            }
            writeMsbAlignedSample(dst, s32, static_cast<int>(outSubslot));
            dst += outSubslot;
        }
    }

    outInputBytesConsumed = packFrames * inFrameBytes;
    outWireData = wireOutScratch_.data();
    return packFrames * outFrameBytes;
}

size_t AudioDspPipeline::processPcmDspPath(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    const size_t inCh = static_cast<size_t>(request_.inputChannels);
    const size_t outCh = static_cast<size_t>(plan_.wireChannels);
    const size_t inBytesPerSample = static_cast<size_t>(inputBytesPerSampleOrBlock(request_.inputEncoding));
    const size_t inFrameBytes = inBytesPerSample * inCh;
    const size_t outFrameBytes = static_cast<size_t>(plan_.wireSubslotSize) * outCh;

    const size_t availInFrames = inputBytes / inFrameBytes;
    const size_t maxWireOutFrames = std::min(maxWireBytes, wireOutScratch_.size()) / outFrameBytes;
    const size_t maxStageOutFrames = stageBufB_.size() / outCh;
    const size_t maxOutFrames = std::min(maxWireOutFrames, maxStageOutFrames);
    if (availInFrames == 0 || maxOutFrames == 0) return 0;

    // Direct float32 -> MSB-aligned LE integer fast-path when no resampling, downmixing,
    // software volume, or TPDF dithering is active (e.g. 192-384 kHz 32-bit float WAV on a
    // 32-bit DAC). Bypasses the 64-bit double stage buffers to cut memory bandwidth and jitter.
    const bool applyDitherNow =
        ((activeDspFlags() & DSP_STAGE_DITHER_TPDF) != 0u) && (plan_.wireBitDepth <= 24);
    if (request_.inputEncoding == AudioInputEncoding::PCM_FLOAT &&
        !channelMatrix_.isActive() &&
        !resampler_.isActive() &&
        isSoftwareVolumeBypassed() &&
        !applyDitherNow) {
        const size_t frames = std::min(availInFrames, maxWireOutFrames);
        if (frames == 0) return 0;
        const size_t totalSamples = frames * outCh;
        const int bitDepth = std::clamp(plan_.wireBitDepth, 16, 32);
        const int subslot = plan_.wireSubslotSize;
        uint8_t* dst = wireOutScratch_.data();

        if (bitDepth == 32 && subslot == 4) {
            constexpr double scale32 = 2147483648.0;
            constexpr int64_t min32 = -2147483648LL;
            constexpr int64_t max32 = 2147483647LL;
            for (size_t i = 0; i < totalSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, inputData + i * 4u, sizeof(float));
                const double clamped = std::isfinite(f)
                    ? std::clamp(static_cast<double>(f), -1.0, 1.0)
                    : 0.0;
                const int64_t rounded = static_cast<int64_t>(std::llround(clamped * scale32));
                const int32_t s32 = static_cast<int32_t>(std::clamp(rounded, min32, max32));
                const uint32_t u = static_cast<uint32_t>(s32);
                dst[0] = static_cast<uint8_t>(u & 0xFFu);
                dst[1] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                dst[2] = static_cast<uint8_t>((u >> 16) & 0xFFu);
                dst[3] = static_cast<uint8_t>((u >> 24) & 0xFFu);
                dst += 4;
            }
        } else {
            const double scale = std::ldexp(1.0, bitDepth - 1);
            const int64_t minInt = -(1LL << (bitDepth - 1));
            const int64_t maxInt = (1LL << (bitDepth - 1)) - 1LL;
            const unsigned int msbShift = static_cast<unsigned int>(32 - bitDepth);
            for (size_t i = 0; i < totalSamples; ++i) {
                float f = 0.0f;
                std::memcpy(&f, inputData + i * 4u, sizeof(float));
                const double clamped = std::isfinite(f)
                    ? std::clamp(static_cast<double>(f), -1.0, 1.0)
                    : 0.0;
                const int64_t rounded = static_cast<int64_t>(std::llround(clamped * scale));
                const int32_t s32 = static_cast<int32_t>(
                    static_cast<uint32_t>(static_cast<int32_t>(std::clamp(rounded, minInt, maxInt))) << msbShift
                );
                writeMsbAlignedSample(dst, s32, subslot);
                dst += subslot;
            }
        }

        outInputBytesConsumed = frames * inFrameBytes;
        outWireData = wireOutScratch_.data();
        return frames * outFrameBytes;
    }

    size_t maxInByOut = resampler_.isActive()
        ? resampler_.maxInputFramesForOutput(maxOutFrames)
        : maxOutFrames;
    const size_t maxStageInFrames = stageBufA_.size() / std::max(inCh, outCh);
    const size_t inFrames = std::min({ availInFrames, MAX_DSP_BLOCK_FRAMES, maxInByOut, maxStageInFrames });
    if (inFrames == 0) return 0;

    // 1. Decode input PCM into 64-bit double [-1.0, +1.0]
    decodePcmBlockToDouble(inputData, request_.inputEncoding, inFrames * inCh, stageBufA_.data());

    // 2. ITU-R BS.775-3 channel matrix / downmix (if inputChannels != wireChannels)
    const double* activeBuf = stageBufA_.data();
    double* altBuf = stageBufB_.data();
    size_t activeFrames = inFrames;

    if (channelMatrix_.isActive()) {
        channelMatrix_.process(activeBuf, altBuf, activeFrames);
        std::swap(activeBuf, const_cast<const double*&>(altBuf));
    }

    // 3. 64-bit polyphase Kaiser-sinc resampler (if inputSampleRate != wireSampleRate)
    if (resampler_.isActive()) {
        double* dstResamp = (activeBuf == stageBufA_.data()) ? stageBufB_.data() : stageBufA_.data();
        activeFrames = resampler_.process(activeBuf, activeFrames, dstResamp, maxOutFrames);
        activeBuf = dstResamp;
    }

    // 4. 64-bit software volume (hard-bypassed when HW volume is active or gain >= 0 dB)
    double* mutableOut = (activeBuf == stageBufA_.data()) ? stageBufA_.data() : stageBufB_.data();
    if (!isSoftwareVolumeBypassed()) {
        const double gain = softwareGainLinear_.load(std::memory_order_acquire);
        const size_t totalSamples = activeFrames * outCh;
        for (size_t i = 0; i < totalSamples; ++i) {
            mutableOut[i] *= gain;
        }
    }

    // 5. Quantize with optional TPDF dither and pack into Little-Endian MSB-aligned wire slots
    quantizeAndPackWire(mutableOut, activeFrames, wireOutScratch_.data());

    outInputBytesConsumed = inFrames * inFrameBytes;
    outWireData = wireOutScratch_.data();
    return activeFrames * outFrameBytes;
}

size_t AudioDspPipeline::processDopPath(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    const size_t ch = static_cast<size_t>(plan_.wireChannels);
    const size_t outSubslot = static_cast<size_t>(plan_.wireSubslotSize);
    const size_t outFrameBytes = outSubslot * ch;
    const size_t maxOutFrames = std::min(maxWireBytes, wireOutScratch_.size()) / outFrameBytes;
    if (maxOutFrames == 0) return 0;

    // Each DoP frame consumes 2 chronological DSD_U8 byte frames (16 DSD bits per channel).
    // Read inputData directly when already DSD_U8 to eliminate a redundant 128 KB scratch memcpy.
    size_t consumed = 0;
    size_t u8Frames = 0;
    const uint8_t* srcU8 = nullptr;
    if (request_.inputEncoding == AudioInputEncoding::DSD_U8) {
        const size_t availU8Frames = inputBytes / ch;
        u8Frames = std::min(availU8Frames, maxOutFrames * 2u);
        u8Frames -= (u8Frames % 2u);
        consumed = u8Frames * ch;
        srcU8 = inputData;
    } else {
        if (dsdUnpackScratch_.empty()) return 0;
        const size_t maxU8FramesByOut = maxOutFrames * 2u;
        const size_t maxU8FramesByScratch = dsdUnpackScratch_.size() / ch;
        const size_t maxU8Frames = std::min(maxU8FramesByOut, maxU8FramesByScratch);
        u8Frames = unpackDsdToU8(
            inputData,
            inputBytes,
            request_.inputEncoding,
            plan_.wireChannels,
            maxU8Frames,
            2u,
            dsdUnpackScratch_.data(),
            consumed
        );
        srcU8 = dsdUnpackScratch_.data();
    }
    const size_t dopFrames = u8Frames / 2u;
    if (dopFrames == 0) return 0;

    uint8_t* dst = wireOutScratch_.data();

    for (size_t f = 0; f < dopFrames; ++f) {
        const uint8_t marker = dopMarkerToggle_;
        dopMarkerToggle_ = (marker == DOP_MARKER_EVEN) ? DOP_MARKER_ODD : DOP_MARKER_EVEN;

        const uint8_t* row0 = srcU8 + (f * 2u) * ch;
        const uint8_t* row1 = row0 + ch;
        for (size_t c = 0; c < ch; ++c) {
            if (outSubslot == 4u) {
                dst[0] = 0x00u;
                dst[1] = row1[c];
                dst[2] = row0[c];
                dst[3] = marker;
                dst += 4;
            } else {
                dst[0] = row1[c];
                dst[1] = row0[c];
                dst[2] = marker;
                dst += 3;
            }
        }
    }

    outInputBytesConsumed = consumed;
    outWireData = wireOutScratch_.data();
    return dopFrames * outFrameBytes;
}

size_t AudioDspPipeline::processNativeDsdPath(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    const size_t ch = static_cast<size_t>(plan_.wireChannels);
    const size_t outSubslot = static_cast<size_t>(plan_.wireSubslotSize);
    const size_t outFrameBytes = outSubslot * ch;
    const UacDsdFormat targetFmt = plan_.selectedAlt.dsdFormat;

    // Zero-copy pass-through when input DSD layout already matches the USB RAW_DATA alt setting
    const bool directMatch =
        (request_.inputEncoding == AudioInputEncoding::DSD_U8 && targetFmt == UacDsdFormat::U8_MSB && outSubslot == 1u) ||
        (request_.inputEncoding == AudioInputEncoding::DSD_U32_BE && targetFmt == UacDsdFormat::U32_BE && outSubslot == 4u) ||
        (request_.inputEncoding == AudioInputEncoding::DSD_U32_LE && targetFmt == UacDsdFormat::U32_LE && outSubslot == 4u);

    if (directMatch) {
        const size_t frames = std::min(inputBytes, maxWireBytes) / outFrameBytes;
        if (frames == 0) return 0;
        const size_t bytes = frames * outFrameBytes;
        outInputBytesConsumed = bytes;
        outWireData = inputData;
        return bytes;
    }

    const size_t maxWireFrames = std::min(maxWireBytes, wireOutScratch_.size()) / outFrameBytes;
    if (maxWireFrames == 0) return 0;

    const size_t u8PerWireFrame = outSubslot; // 1 byte/ch for U8_MSB, 4 bytes/ch for U32_BE/LE
    size_t consumed = 0;
    size_t u8Frames = 0;
    const uint8_t* srcU8 = nullptr;
    if (request_.inputEncoding == AudioInputEncoding::DSD_U8) {
        // Bypass dsdUnpackScratch_ memcpy when input is already chronological DSD_U8
        const size_t availU8Frames = inputBytes / ch;
        u8Frames = std::min(availU8Frames, maxWireFrames * u8PerWireFrame);
        u8Frames -= (u8Frames % u8PerWireFrame);
        consumed = u8Frames * ch;
        srcU8 = inputData;
    } else {
        if (dsdUnpackScratch_.empty()) return 0;
        const size_t maxU8Frames = std::min(maxWireFrames * u8PerWireFrame, dsdUnpackScratch_.size() / ch);
        u8Frames = unpackDsdToU8(
            inputData,
            inputBytes,
            request_.inputEncoding,
            plan_.wireChannels,
            maxU8Frames,
            u8PerWireFrame,
            dsdUnpackScratch_.data(),
            consumed
        );
        srcU8 = dsdUnpackScratch_.data();
    }
    const size_t wireFrames = u8Frames / u8PerWireFrame;
    if (wireFrames == 0) return 0;

    uint8_t* dst = wireOutScratch_.data();

    if (outSubslot == 1u) {
        std::memcpy(dst, srcU8, wireFrames * ch);
    } else if (ch == 2u && outSubslot == 4u) {
        // Stereo 32-bit word-packing fast path (XMOS / Amanero / CS43131 / ESS Native DSD)
        const uint8_t* __restrict__ s = srcU8;
        uint8_t* __restrict__ d = dst;
        if (targetFmt == UacDsdFormat::U32_LE) {
            for (size_t f = 0; f < wireFrames; ++f) {
                const uint32_t wL =
                    static_cast<uint32_t>(s[6]) |
                    (static_cast<uint32_t>(s[4]) << 8) |
                    (static_cast<uint32_t>(s[2]) << 16) |
                    (static_cast<uint32_t>(s[0]) << 24);
                const uint32_t wR =
                    static_cast<uint32_t>(s[7]) |
                    (static_cast<uint32_t>(s[5]) << 8) |
                    (static_cast<uint32_t>(s[3]) << 16) |
                    (static_cast<uint32_t>(s[1]) << 24);
                std::memcpy(d, &wL, sizeof(uint32_t));
                std::memcpy(d + 4, &wR, sizeof(uint32_t));
                s += 8;
                d += 8;
            }
        } else {
            for (size_t f = 0; f < wireFrames; ++f) {
                const uint32_t wL =
                    static_cast<uint32_t>(s[0]) |
                    (static_cast<uint32_t>(s[2]) << 8) |
                    (static_cast<uint32_t>(s[4]) << 16) |
                    (static_cast<uint32_t>(s[6]) << 24);
                const uint32_t wR =
                    static_cast<uint32_t>(s[1]) |
                    (static_cast<uint32_t>(s[3]) << 8) |
                    (static_cast<uint32_t>(s[5]) << 16) |
                    (static_cast<uint32_t>(s[7]) << 24);
                std::memcpy(d, &wL, sizeof(uint32_t));
                std::memcpy(d + 4, &wR, sizeof(uint32_t));
                s += 8;
                d += 8;
            }
        }
    } else {
        const bool packLe = (targetFmt == UacDsdFormat::U32_LE);
        for (size_t f = 0; f < wireFrames; ++f) {
            const uint8_t* blkRows = srcU8 + (f * u8PerWireFrame) * ch;
            for (size_t c = 0; c < ch; ++c) {
                for (size_t b = 0; b < u8PerWireFrame; ++b) {
                    const size_t rowIdx = packLe ? (u8PerWireFrame - 1u - b) : b;
                    dst[b] = blkRows[rowIdx * ch + c];
                }
                dst += u8PerWireFrame;
            }
        }
    }

    outInputBytesConsumed = consumed;
    outWireData = wireOutScratch_.data();
    return wireFrames * outFrameBytes;
}

size_t AudioDspPipeline::processDsdToPcmPath(
    const uint8_t* inputData,
    size_t inputBytes,
    size_t maxWireBytes,
    size_t& outInputBytesConsumed,
    const uint8_t*& outWireData
) {
    const size_t inCh = static_cast<size_t>(request_.inputChannels);
    const size_t outCh = static_cast<size_t>(plan_.wireChannels);
    const size_t outFrameBytes = static_cast<size_t>(plan_.wireSubslotSize) * outCh;
    const size_t maxWireOutFrames = std::min(maxWireBytes, wireOutScratch_.size()) / outFrameBytes;
    const size_t maxStageOutFrames = stageBufB_.size() / outCh;
    const size_t maxOutFrames = std::min(maxWireOutFrames, maxStageOutFrames);
    if (maxOutFrames == 0) return 0;

    // Limit intermediate decimated PCM frames so optional resampling never overflows maxOutFrames
    const size_t maxDecimOutByResamp = resampler_.isActive()
        ? resampler_.maxInputFramesForOutput(maxOutFrames)
        : maxOutFrames;
    const size_t maxDecimOutFrames = std::min({
        maxDecimOutByResamp,
        MAX_DSP_BLOCK_FRAMES,
        stageBufA_.size() / std::max(inCh, outCh)
    });
    if (maxDecimOutFrames == 0) return 0;

    const size_t dsdBytesPerOutFrame = static_cast<size_t>(std::max(1, dsdDecimator_.dsdBytesPerOutputFramePerChannel()));

    size_t consumed = 0;
    size_t u8Frames = 0;
    const uint8_t* srcU8 = nullptr;
    if (request_.inputEncoding == AudioInputEncoding::DSD_U8) {
        const size_t availU8Frames = inputBytes / inCh;
        u8Frames = std::min(availU8Frames, maxDecimOutFrames * dsdBytesPerOutFrame);
        u8Frames -= (u8Frames % dsdBytesPerOutFrame);
        consumed = u8Frames * inCh;
        srcU8 = inputData;
    } else {
        if (dsdUnpackScratch_.empty()) return 0;
        const size_t maxU8Frames = std::min(
            maxDecimOutFrames * dsdBytesPerOutFrame,
            dsdUnpackScratch_.size() / inCh
        );
        u8Frames = unpackDsdToU8(
            inputData,
            inputBytes,
            request_.inputEncoding,
            request_.inputChannels,
            maxU8Frames,
            dsdBytesPerOutFrame,
            dsdUnpackScratch_.data(),
            consumed
        );
        srcU8 = dsdUnpackScratch_.data();
    }
    if (u8Frames == 0) return 0;

    // 1. Multi-stage FIR low-pass DSD-to-PCM decimation (with configured gain compensation)
    size_t pcmFrames = dsdDecimator_.processDsdU8(
        srcU8,
        u8Frames,
        stageBufA_.data(),
        maxDecimOutFrames
    );
    if (pcmFrames == 0) {
        outInputBytesConsumed = consumed;
        return 0;
    }

    // 2. Optional ITU-R BS.775-3 channel matrix
    const double* activeBuf = stageBufA_.data();
    if (channelMatrix_.isActive()) {
        channelMatrix_.process(activeBuf, stageBufB_.data(), pcmFrames);
        activeBuf = stageBufB_.data();
    }

    // 3. Optional 64-bit polyphase resampler (when DAC lacks 44.1 kHz-family rates)
    if (resampler_.isActive()) {
        double* dstResamp = (activeBuf == stageBufA_.data()) ? stageBufB_.data() : stageBufA_.data();
        pcmFrames = resampler_.process(activeBuf, pcmFrames, dstResamp, maxOutFrames);
        activeBuf = dstResamp;
    }

    // 4. Optional 64-bit software volume (hard-bypassed at 0 dB or with hardware volume)
    double* mutableOut = (activeBuf == stageBufA_.data()) ? stageBufA_.data() : stageBufB_.data();
    if (!isSoftwareVolumeBypassed()) {
        const double gain = softwareGainLinear_.load(std::memory_order_acquire);
        const size_t totalSamples = pcmFrames * outCh;
        for (size_t i = 0; i < totalSamples; ++i) {
            mutableOut[i] *= gain;
        }
    }

    // 5. Quantize with TPDF dither and pack to wire format
    quantizeAndPackWire(mutableOut, pcmFrames, wireOutScratch_.data());

    outInputBytesConsumed = consumed;
    outWireData = wireOutScratch_.data();
    return pcmFrames * outFrameBytes;
}

void AudioDspPipeline::quantizeAndPackWire(const double* srcInterleaved, size_t frames, uint8_t* dstWire) {
    const size_t totalSamples = frames * static_cast<size_t>(plan_.wireChannels);
    const int bitDepth = std::clamp(plan_.wireBitDepth, 16, 32);
    const int subslot = plan_.wireSubslotSize;
    const bool applyDither = ((activeDspFlags() & DSP_STAGE_DITHER_TPDF) != 0u) && (bitDepth <= 24);

    const double scale = std::ldexp(1.0, bitDepth - 1);
    const int64_t minInt = -(1LL << (bitDepth - 1));
    const int64_t maxInt = (1LL << (bitDepth - 1)) - 1LL;
    const unsigned int msbShift = static_cast<unsigned int>(32 - bitDepth);

    const double* __restrict__ src = srcInterleaved;
    uint8_t* __restrict__ dst = dstWire;

    // Hoist applyDither and subslot branches out of the per-sample loop to unlock auto-vectorization
    // while keeping bit-exact rounding, clamping, and TPDF dithering math unchanged.
    auto runSpecialized = [&](auto subslotTag, auto ditherTag) {
        constexpr int kSubslot = decltype(subslotTag)::value;
        constexpr bool kDither = decltype(ditherTag)::value;
        uint8_t* __restrict__ out = dst;
        for (size_t i = 0; i < totalSamples; ++i) {
            double s = src[i];
            if (!std::isfinite(s)) {
                s = 0.0;
            } else {
                s = std::clamp(s, -1.0, 1.0);
            }

            double scaled = s * scale;
            if constexpr (kDither) {
                scaled += dither_.nextTriangular();
            }

            const int64_t rounded = static_cast<int64_t>(std::llround(scaled));
            const int64_t clamped = std::clamp(rounded, minInt, maxInt);
            const uint32_t u = static_cast<uint32_t>(static_cast<int32_t>(clamped)) << msbShift;
            if constexpr (kSubslot == 4) {
                std::memcpy(out, &u, sizeof(uint32_t));
                out += 4;
            } else if constexpr (kSubslot == 3) {
                out[0] = static_cast<uint8_t>((u >> 8) & 0xFFu);
                out[1] = static_cast<uint8_t>((u >> 16) & 0xFFu);
                out[2] = static_cast<uint8_t>((u >> 24) & 0xFFu);
                out += 3;
            } else {
                const uint16_t u16 = static_cast<uint16_t>(u >> 16);
                std::memcpy(out, &u16, sizeof(uint16_t));
                out += 2;
            }
        }
    };

    if (subslot == 4) {
        if (applyDither) {
            runSpecialized(std::integral_constant<int, 4>{}, std::true_type{});
        } else {
            runSpecialized(std::integral_constant<int, 4>{}, std::false_type{});
        }
    } else if (subslot == 3) {
        if (applyDither) {
            runSpecialized(std::integral_constant<int, 3>{}, std::true_type{});
        } else {
            runSpecialized(std::integral_constant<int, 3>{}, std::false_type{});
        }
    } else {
        if (applyDither) {
            runSpecialized(std::integral_constant<int, 2>{}, std::true_type{});
        } else {
            runSpecialized(std::integral_constant<int, 2>{}, std::false_type{});
        }
    }
}