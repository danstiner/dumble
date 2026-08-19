#include "core/SileroVad.h"
#include <cmath>
#include <cstring>
#include "core/Dot.h"

namespace dumble {
namespace {

constexpr int kFft = SileroVad::kFft;
constexpr int kHop = 128;
constexpr int kBins = kFft / 2 + 1;             // 129
constexpr int kReflect = 64;                    // right-hand reflect pad, upstream's
constexpr int kHidden = SileroVad::kHidden;
constexpr int kUnpadded = SileroVad::kContext + SileroVad::kWindow;   // 576
constexpr int kPadded = kUnpadded + kReflect;                         // 640
constexpr int kStftSteps = (kPadded - kFft) / kHop + 1;               // 4

struct Shape { int in, out, stride; };
constexpr Shape kEncoder[4] = {{kBins, 128, 1}, {128, 64, 2}, {64, 64, 2}, {64, 128, 1}};

/** conv1d, kernel 3, padding 1: out = floor((in + 2 - 3) / stride) + 1. */
constexpr int convSteps(int inSteps, int stride) { return (inSteps - 1) / stride + 1; }

/** Floats the constructor consumes, so kWeightFloats is pinned to this layout, not to a memory. */
constexpr size_t weightFloats() {
    size_t n = size_t(2 * kBins) * kFft;                                  // dense STFT basis
    for (const Shape& s : kEncoder) n += size_t(s.out) * s.in * 3 + s.out;
    n += 2 * (size_t(4 * kHidden) * kHidden) + 2 * size_t(4 * kHidden);   // LSTM
    return n + kHidden + 1;                                               // head
}
static_assert(weightFloats() == SileroVad::kWeightFloats,
              "the blob layout and kWeightFloats disagree");

/** The widest encoder activation, which is what the ping-pong buffers must hold. */
constexpr int activationFloats() {
    int steps = kStftSteps, widest = 0;
    for (const Shape& s : kEncoder) {
        steps = convSteps(steps, s.stride);
        widest = steps * s.out > widest ? steps * s.out : widest;
    }
    return widest;
}

inline float sigmoid(float v) { return 1.0f / (1.0f + std::exp(-v)); }

// Radix-2 Cooley-Tukey, N = 256, twiddles built once. The model's STFT basis is exactly
// window * cos / -window * sin (verified to 5.7e-8 against the shipped blob), so the magnitude
// of this transform is the magnitude that dense basis produced — 264,192 MAC per window replaced
// by four 256-point transforms, an order of magnitude fewer operations.
struct Fft {
    int rev[kFft];
    float cosT[kFft / 2], sinT[kFft / 2];
    Fft() {
        for (int i = 0; i < kFft; i++) {
            int r = 0;
            for (int b = 1, s = kFft >> 1; b < kFft; b <<= 1, s >>= 1) if (i & b) r |= s;
            rev[i] = r;
        }
        for (int i = 0; i < kFft / 2; i++) {
            cosT[i] = std::cos(-2.0 * M_PI * i / kFft);
            sinT[i] = std::sin(-2.0 * M_PI * i / kFft);
        }
    }
    void run(float* re, float* im) const {
        for (int i = 0; i < kFft; i++)
            if (rev[i] > i) { std::swap(re[i], re[rev[i]]); std::swap(im[i], im[rev[i]]); }
        for (int len = 2; len <= kFft; len <<= 1) {
            const int half = len >> 1, step = kFft / len;
            for (int base = 0; base < kFft; base += len)
                for (int j = 0; j < half; j++) {
                    const float wr = cosT[j * step], wi = sinT[j * step];
                    const int a = base + j, b = a + half;
                    const float xr = re[b] * wr - im[b] * wi;
                    const float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr;        im[a] += xi;
                }
        }
    }
};
const Fft& fftPlan() { static const Fft plan; return plan; }

}  // namespace

int SileroVad::Conv::apply(const float* in, int inSteps, float* out) const {
    const int outSteps = convSteps(inSteps, stride);
    for (int t = 0; t < outSteps; ++t) {
        float* o = out + size_t(t) * outChannels;
        for (int oc = 0; oc < outChannels; ++oc) o[oc] = bias[oc];
        for (int k = 0; k < 3; ++k) {
            const int ti = t * stride + k - 1;
            if (ti < 0 || ti >= inSteps) continue;   // the zero padding
            const float* x = in + size_t(ti) * inChannels;
            for (int oc = 0; oc < outChannels; ++oc)
                o[oc] += dot(&weight[(size_t(oc) * 3 + k) * inChannels], x, inChannels);
        }
        for (int oc = 0; oc < outChannels; ++oc) if (o[oc] < 0.0f) o[oc] = 0.0f;
    }
    return outSteps;
}

std::unique_ptr<SileroVad> SileroVad::create(const void* weights, size_t bytes) {
    if (bytes != kWeightFloats * sizeof(float)) return nullptr;
    return std::unique_ptr<SileroVad>(new SileroVad(weights));
}

SileroVad::SileroVad(const void* weights) {
    // create() takes a const void* because PR 2's asset-loading path can hand this a pointer into
    // a mapped APK, which lands on an arbitrary zip-entry byte offset — not necessarily a multiple
    // of 4. Iterator-constructing a vector<float> from a reinterpreted float* would dereference
    // that pointer as a float, undefined on a misaligned address; memcpy has no such requirement.
    std::vector<float> raw(kWeightFloats);
    std::memcpy(raw.data(), weights, kWeightFloats * sizeof(float));

    // Row 0 of the dense basis is window * cos(0) — the window itself.
    std::memcpy(window_, raw.data(), sizeof(window_));

    const float* p = raw.data() + size_t(2 * kBins) * kFft;   // past the now-dead basis
    auto take = [&p](size_t n) { const float* q = p; p += n; return q; };
    auto vec = [&](size_t n) { const float* q = take(n); return std::vector<float>(q, q + n); };

    for (int i = 0; i < 4; ++i) {
        Conv& layer = conv_[i];
        const int ic = layer.inChannels = kEncoder[i].in;
        const int oc = layer.outChannels = kEncoder[i].out;
        layer.stride = kEncoder[i].stride;

        const float* src = take(size_t(oc) * ic * 3);
        layer.weight.resize(size_t(oc) * ic * 3);
        for (int o = 0; o < oc; ++o)
            for (int k = 0; k < 3; ++k)
                for (int c = 0; c < ic; ++c)
                    layer.weight[(size_t(o) * 3 + k) * ic + c] = src[(size_t(o) * ic + c) * 3 + k];
        layer.bias = vec(oc);
    }

    gateInput_ = vec(size_t(4 * kHidden) * kHidden);
    gateHidden_ = vec(size_t(4 * kHidden) * kHidden);
    gateBias_ = vec(4 * kHidden);
    const float* hiddenBias = take(4 * kHidden);
    for (int r = 0; r < 4 * kHidden; ++r) gateBias_[r] += hiddenBias[r];
    head_ = vec(kHidden);
    headBias_ = *take(1);
}

void SileroVad::reset() {
    std::memset(context_, 0, sizeof(context_));
    std::memset(hidden_, 0, sizeof(hidden_));
    std::memset(cell_, 0, sizeof(cell_));
}

float SileroVad::process(const float* window) {
    float padded[kPadded];
    std::memcpy(padded, context_, sizeof(context_));
    std::memcpy(padded + kContext, window, sizeof(float) * kWindow);
    for (int i = 0; i < kReflect; ++i) padded[kUnpadded + i] = padded[kUnpadded - 2 - i];
    std::memcpy(context_, window + kWindow - kContext, sizeof(context_));

    // [step][bin], so the first conv's channel accumulation is contiguous.
    float magnitude[kStftSteps * kBins];
    for (int t = 0; t < kStftSteps; ++t) {
        float re[kFft], im[kFft] = {};
        const float* frame = padded + t * kHop;
        for (int i = 0; i < kFft; ++i) re[i] = window_[i] * frame[i];
        fftPlan().run(re, im);
        float* m = magnitude + size_t(t) * kBins;
        for (int bin = 0; bin < kBins; ++bin) m[bin] = std::sqrt(re[bin] * re[bin] + im[bin] * im[bin]);
    }

    float buffer[2][activationFloats()];
    const float* in = magnitude;
    int steps = kStftSteps;
    for (int i = 0; i < 4; ++i) {
        steps = conv_[i].apply(in, steps, buffer[i % 2]);
        in = buffer[i % 2];
    }

    float gates[4 * kHidden];
    for (int r = 0; r < 4 * kHidden; ++r) {
        // Two sequential dot()s, not one interleaved pass — identical, because the two accumulator
        // sets never interact. kHidden is 128, a whole number of blocks, so neither has a tail.
        const float ai = dot(&gateInput_[size_t(r) * kHidden], in, kHidden);
        const float ah = dot(&gateHidden_[size_t(r) * kHidden], hidden_, kHidden);
        gates[r] = gateBias_[r] + (ai + ah);
    }
    for (int i = 0; i < kHidden; ++i) {
        const float inputGate = sigmoid(gates[i]);
        const float forgetGate = sigmoid(gates[kHidden + i]);
        const float candidate = std::tanh(gates[2 * kHidden + i]);
        const float outputGate = sigmoid(gates[3 * kHidden + i]);
        cell_[i] = forgetGate * cell_[i] + inputGate * candidate;
        hidden_[i] = outputGate * std::tanh(cell_[i]);
    }

    float acc = headBias_;
    for (int i = 0; i < kHidden; ++i) acc += head_[i] * (hidden_[i] > 0.0f ? hidden_[i] : 0.0f);
    return sigmoid(acc);
}

}  // namespace dumble
