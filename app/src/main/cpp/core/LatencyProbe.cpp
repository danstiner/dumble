#include "core/LatencyProbe.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include "core/CaptureConstants.h"

namespace dumble::latency {

namespace {
constexpr int kProbeSamples = kSampleRate / 4;
constexpr int kEdgeSamples = kSampleRate / 100;
constexpr int kCoarseLag = 4;
constexpr int kCoarseSample = 2;
constexpr int64_t kGuardSamples = kSampleRate / 200;

// |sum x*p| / sqrt(sum x^2 * sum p^2) over every `step`th sample of the probe.
double correlate(const int16_t* x, const std::vector<int16_t>& p, int step) {
    double cross = 0, energy = 0, probeEnergy = 0;
    for (size_t k = 0; k < p.size(); k += step) {
        const double v = x[k];
        cross += v * p[k];
        energy += v * v;
        probeEnergy += double(p[k]) * p[k];
    }
    if (energy <= 0 || probeEnergy <= 0) return 0;
    return std::fabs(cross) / std::sqrt(energy * probeEnergy);
}
}  // namespace

std::vector<int16_t> probe(int16_t peak) {
    std::vector<int16_t> out(kProbeSamples);
    const double f0 = 300, f1 = 3400, length = double(kProbeSamples) / kSampleRate;
    for (int i = 0; i < kProbeSamples; i++) {
        const double t = double(i) / kSampleRate;
        const int edge = std::min(i, kProbeSamples - 1 - i);
        const double window =
            edge >= kEdgeSamples ? 1.0 : 0.5 - 0.5 * std::cos(M_PI * edge / kEdgeSamples);
        const double phase = 2 * M_PI * (f0 * t + (f1 - f0) * t * t / (2 * length));
        out[i] = int16_t(std::lround(peak * window * std::sin(phase)));
    }
    return out;
}

Detection find(const int16_t* pcm, int64_t n, const std::vector<int16_t>& probe) {
    const int64_t lags = n - int64_t(probe.size()) + 1;
    // With fewer lags every coarse one can sit inside the guard, and the ratio compares to nothing.
    if (lags <= 2 * kGuardSamples + kCoarseLag) return {-1, 0, 0};
    std::vector<float> coarse((lags + kCoarseLag - 1) / kCoarseLag);
    for (size_t j = 0; j < coarse.size(); j++) {
        coarse[j] = float(correlate(pcm + j * kCoarseLag, probe, kCoarseSample));
    }
    const auto best = std::max_element(coarse.begin(), coarse.end()) - coarse.begin();

    const int64_t center = best * kCoarseLag;
    int64_t offset = center;
    double peak = 0;
    for (int64_t i = std::max<int64_t>(0, center - kCoarseLag);
         i <= std::min<int64_t>(lags - 1, center + kCoarseLag); i++) {
        const double c = correlate(pcm + i, probe, 1);
        if (c > peak) {
            peak = c;
            offset = i;
        }
    }
    // From the coarse pass, the measure kFoundRatio was measured against.
    float next = 0;
    for (size_t j = 0; j < coarse.size(); j++) {
        if (std::llabs(int64_t(j) * kCoarseLag - offset) > kGuardSamples) {
            next = std::max(next, coarse[j]);
        }
    }
    return {offset, float(peak), next};
}

}  // namespace dumble::latency
