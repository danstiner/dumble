#include "core/Decimator.h"
#include <cmath>
#include <cstring>
#include "core/CaptureConstants.h"
#include "core/Dot.h"

namespace dumble {

Decimator::Decimator() {
    constexpr double kCutoffHz = 7000.0;
    const double fc = kCutoffHz / double(kSampleRate);
    const double mid = (kTaps - 1) / 2.0;
    double sum = 0.0;
    float taps[kTaps];
    for (int k = 0; k < kTaps; k++) {
        const double x = k - mid;
        const double sinc = (x == 0.0) ? 2 * fc : std::sin(2 * M_PI * fc * x) / (M_PI * x);
        const double hann = 0.5 - 0.5 * std::cos(2 * M_PI * k / (kTaps - 1));
        const double v = sinc * hann;
        taps[k] = float(v);
        sum += v;
    }
    // Reversed once here so decimate() never indexes backwards. out[j] = sum_k taps[k]*x[3j-k]
    // becomes sum_m reversed_[m]*buf[3j+m] with reversed_[m] = taps[kTaps-1-m].
    for (int k = 0; k < kTaps; k++) reversed_[kTaps - 1 - k] = float(taps[k] / sum);
}

void Decimator::decimate(const int16_t* frame, float* out) {
    constexpr int kCarry = kTaps - 1;
    float history[kCarry + kFrameSamples];
    std::memcpy(history, carry_, sizeof(carry_));
    for (int i = 0; i < kFrameSamples; i++) history[kCarry + i] = frame[i] / 32768.0f;

    for (int j = 0; j < kOutputSamples; j++) out[j] = dot(reversed_, history + j * kRatio, kTaps);
    std::memcpy(carry_, history + kFrameSamples, sizeof(carry_));
}

void Decimator::reset() {
    std::memset(carry_, 0, sizeof(carry_));
}

}  // namespace dumble
