#include <gtest/gtest.h>
#include <algorithm>
#include <cmath>
#include <vector>
#include "LatencyFixture.h"
#include "core/CaptureConstants.h"
#include "core/LatencyProbe.h"

namespace {

using dumble::latency::find;
using dumble::latency::probe;
using dumble::latencytest::noise;
using dumble::latencytest::opusRoundTrip;

// Low enough that noise 20 dB above it still fits in 16 bits.
constexpr int16_t kPeak = 1500;
// Measured: 2 samples short of Opus's 312-sample (6.5 ms) delay at 48 kHz.
constexpr int64_t kCodecDelaySamples = 310;

// Uniform noise whose RMS sits `snrDb` below the probe's.
int amplitudeFor(double snrDb) {
    const double probeRms = kPeak / std::sqrt(2.0);
    return int(std::lround(std::sqrt(3.0) * probeRms / std::pow(10.0, snrDb / 20)));
}

std::vector<int16_t> withProbe(int64_t n, int64_t at, double snrDb, uint32_t seed, int sign = 1) {
    auto pcm = noise(n, amplitudeFor(snrDb), seed);
    const auto p = probe(kPeak);
    for (size_t i = 0; i < p.size(); i++) {
        pcm[at + i] = int16_t(std::clamp(pcm[at + i] + sign * p[i], -32768, 32767));
    }
    return pcm;
}

TEST(LatencyProbe, IsAChirpAtTheRequestedPeakWithSilentEnds) {
    const auto p = probe(kPeak);
    ASSERT_EQ(p.size(), size_t(dumble::kSampleRate / 4));
    EXPECT_EQ(p.front(), 0);
    EXPECT_EQ(p.back(), 0);
    int maxAbs = 0;
    for (int16_t s : p) maxAbs = std::max(maxAbs, std::abs(int(s)));
    EXPECT_GE(maxAbs, kPeak * 99 / 100);
    EXPECT_LE(maxAbs, kPeak);
}

TEST(LatencyProbe, FindsItToTheSampleInQuietNoise) {
    const auto pcm = withProbe(dumble::kSampleRate, 3001, 20, 1);
    const auto d = find(pcm.data(), int64_t(pcm.size()), probe(kPeak));
    EXPECT_EQ(d.offset, 3001);
    EXPECT_TRUE(d.found()) << d.peak << " over " << d.nextPeak;
}

TEST(LatencyProbe, FindsItInvertedAsWell) {
    const auto pcm = withProbe(dumble::kSampleRate, 3001, 20, 1, -1);
    const auto d = find(pcm.data(), int64_t(pcm.size()), probe(kPeak));
    EXPECT_EQ(d.offset, 3001);
    EXPECT_TRUE(d.found());
}

TEST(LatencyProbe, ThroughOpusItLandsOneCodecDelayLateAtEveryPhase) {
    for (int phase = 0; phase < dumble::kTxPacketSamples; phase += 193) {
        const int64_t at = 4 * dumble::kTxPacketSamples + phase;
        const auto out = opusRoundTrip(withProbe(dumble::kSampleRate / 2, at, 20, 2));
        const auto d = find(out.data(), int64_t(out.size()), probe(kPeak));
        EXPECT_EQ(d.offset - at, kCodecDelaySamples) << "phase " << phase;
        EXPECT_TRUE(d.found()) << "phase " << phase << ": " << d.peak << " over " << d.nextPeak;
    }
}

TEST(LatencyProbe, ThroughOpusItIsFoundAtMinus6dB) {
    for (uint32_t seed = 10; seed < 12; seed++) {
        const int64_t at = 9000 + seed * 37;
        const auto out = opusRoundTrip(withProbe(dumble::kSampleRate / 2, at, -6, seed));
        const auto d = find(out.data(), int64_t(out.size()), probe(kPeak));
        EXPECT_NEAR(double(d.offset - at), double(kCodecDelaySamples), 2) << "seed " << seed;
        EXPECT_TRUE(d.found()) << "seed " << seed << ": " << d.peak << " over " << d.nextPeak;
    }
}

TEST(LatencyProbe, AProbeFarBelowTheNoiseIsNotFound) {
    for (uint32_t seed = 30; seed < 32; seed++) {
        const auto out = opusRoundTrip(withProbe(dumble::kSampleRate / 2, 9000, -20, seed));
        const auto d = find(out.data(), int64_t(out.size()), probe(kPeak));
        EXPECT_FALSE(d.found()) << "seed " << seed << ": " << d.peak << " over " << d.nextPeak;
    }
}

TEST(LatencyProbe, TwoProbesInOneWindowAreNotFound) {
    // An echo, or a loop left ringing: neither copy stands out, so neither is a measurement.
    auto pcm = withProbe(dumble::kSampleRate, 1000, 20, 3);
    const auto p = probe(kPeak);
    for (size_t i = 0; i < p.size(); i++) pcm[20000 + i] = int16_t(pcm[20000 + i] + p[i]);
    const auto d = find(pcm.data(), int64_t(pcm.size()), p);
    EXPECT_FALSE(d.found()) << d.peak << " over " << d.nextPeak;
}

TEST(LatencyProbe, SilenceIsNotFoundEvenWithAClick) {
    std::vector<int16_t> pcm(dumble::kSampleRate / 2, 0);
    EXPECT_FALSE(find(pcm.data(), int64_t(pcm.size()), probe(kPeak)).found());
    pcm[7000] = 20000;
    const auto d = find(pcm.data(), int64_t(pcm.size()), probe(kPeak));
    EXPECT_FALSE(d.found()) << d.peak << " over " << d.nextPeak;
}

TEST(LatencyProbe, AWindowShorterThanTheProbeFindsNothing) {
    const auto p = probe(kPeak);
    const auto d = find(p.data(), int64_t(p.size()) - 1, p);
    EXPECT_EQ(d.offset, -1);
    EXPECT_FALSE(d.found());
}

}  // namespace
