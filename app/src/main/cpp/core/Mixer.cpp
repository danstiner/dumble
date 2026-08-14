#include "core/Mixer.h"
#include <cmath>
#include <cstdlib>

namespace dumble::playout {
namespace {

constexpr double kLimit = 32767.0;
// ~2 dB below full scale. Single-stream Opus speech essentially never peaks that hot, so the knee
// is inert in single-talk; it exists to shape the rare case of multiple hot speakers.
constexpr int32_t kThreshold = 26214;

}  // namespace

void mixAccumulate(int32_t* acc, const int16_t* src, int n) {
    for (int i = 0; i < n; i++) acc[i] += src[i];
}

void mixFinalize(const int32_t* acc, int16_t* dst, int n) {
    for (int i = 0; i < n; i++) {
        // int32 math is safe: |acc| <= kMaxSpeakers * 2^15 = 2^21, so the sum cannot overflow and
        // std::abs has no INT_MIN edge to hit. Below the knee the sample passes through exactly.
        const int32_t x = acc[i];
        const int32_t magnitude = std::abs(x);
        if (magnitude <= kThreshold) {
            dst[i] = int16_t(x);
            continue;
        }
        const double over = magnitude - kThreshold;
        const double compressed =
            kThreshold + (kLimit - kThreshold) * std::tanh(over / (kLimit - kThreshold));
        dst[i] = int16_t(x < 0 ? -compressed : compressed);
    }
}

}  // namespace dumble::playout
