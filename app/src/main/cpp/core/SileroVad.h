#pragma once
#include <cstddef>
#include <memory>
#include <vector>

namespace dumble {

/**
 * Silero VAD v6, forward pass only: reflect-pad, STFT magnitude, four strided conv layers, one
 * LSTM step, sigmoid. One speech probability per 512-sample window at 16 kHz. The 64 samples of
 * carried context and the LSTM state live across calls, so windows must be fed in order.
 * Single-thread. The voice activity section of docs/capture.md walks the pipeline and what it
 * does differently from upstream.
 */
class SileroVad {
public:
    static constexpr int kWindow = 512;
    static constexpr int kContext = 64;
    static constexpr int kHidden = 128;
    /** STFT size. Public because the .cpp aliases it rather than defining a second 256 — two
     *  independent definitions could diverge, and a shrinking one would overread window_. */
    static constexpr int kFft = 256;
    /** Element count of the weight blob, checked by create() and pinned to the layout in the .cpp. */
    static constexpr size_t kWeightFloats = 309633;

    /** Null unless the blob holds exactly kWeightFloats little-endian floats in export order. */
    static std::unique_ptr<SileroVad> create(const void* weights, size_t bytes);

    /** kWindow samples at 16 kHz in [-1, 1]. Returns the window's speech probability. */
    float process(const float* window);

    /** Capture discontinuity: drop the carried context and the recurrent state. */
    void reset();

private:
    /** One encoder layer: conv1d with kernel 3 and zero padding 1, then ReLU. */
    struct Conv {
        // [out][tap][in], repacked from the blob's [out][in][tap] so apply()'s accumulation over
        // input channels walks both operands contiguously and vectorises.
        std::vector<float> weight;
        std::vector<float> bias;
        int inChannels = 0, outChannels = 0, stride = 0;

        /** Reads inSteps × inChannels, writes the returned step count × outChannels. */
        int apply(const float* in, int inSteps, float* out) const;
    };

    explicit SileroVad(const void* weights);

    Conv conv_[4];
    // The LSTM's two gate matrices, each [4 * kHidden][kHidden], and their two bias vectors summed
    // into one: the forward pass only ever adds them together.
    std::vector<float> gateInput_, gateHidden_, gateBias_;
    std::vector<float> head_;
    float headBias_ = 0.0f;

    // The STFT window. It is row 0 of the blob's dense DFT basis (window * cos(0)); the rest of
    // that basis is dead once the FFT in the .cpp replaces the matmul it existed for, so the ~258 KB
    // it occupies is read at construction and never stored.
    float window_[kFft] = {};
    float context_[kContext] = {};
    float hidden_[kHidden] = {};
    float cell_[kHidden] = {};
};

}  // namespace dumble
