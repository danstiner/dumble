#include <gtest/gtest.h>
#include <cmath>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/Decimator.h"

using dumble::Decimator;

namespace {

std::vector<int16_t> tone(double hz, int samples, double amplitude = 0.5) {
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(std::sin(2.0 * M_PI * hz * i / dumble::kSampleRate) * amplitude * 32767);
    return pcm;
}

double rms(const float* x, int n) {
    double sum = 0;
    for (int i = 0; i < n; i++) sum += double(x[i]) * x[i];
    return std::sqrt(sum / n);
}

// Steady state: prime the FIR over several frames and measure only the last one.
double steadyStateRms(double hz) {
    Decimator d;
    const auto pcm = tone(hz, dumble::kFrameSamples * 8);
    std::vector<float> out(Decimator::kOutputSamples);
    for (int f = 0; f < 8; f++)
        d.decimate(pcm.data() + f * dumble::kFrameSamples, out.data());
    return rms(out.data(), int(out.size()));
}

}  // namespace

TEST(Decimator, ProducesOneThirdAsManySamples) {
    Decimator d;
    std::vector<int16_t> silence(dumble::kFrameSamples, 0);
    std::vector<float> out(Decimator::kOutputSamples, 1.0f);
    d.decimate(silence.data(), out.data());
    EXPECT_EQ(160, Decimator::kOutputSamples);
    for (float v : out) EXPECT_FLOAT_EQ(0.0f, v);
}

TEST(Decimator, PassesTheSpeechBand) {
    // A half-amplitude sine has RMS 0.5/sqrt(2) = 0.354; within 1 dB is 0.315..0.397.
    const double r = steadyStateRms(1000.0);
    EXPECT_GT(r, 0.315) << "1 kHz RMS " << r;
    EXPECT_LT(r, 0.397) << "1 kHz RMS " << r;
}

TEST(Decimator, RejectsEverythingThatWouldAlias) {
    // Everything above the 8 kHz output Nyquist folds back into the band the detector reads, so
    // the requirement is a floor across the whole fold band — not one tone. A single probe is
    // worthless here: stopband ripple is not monotonic in tap count, and 9 kHz alone measures
    // 30.7 dB at 33 taps but 70.2 dB at 41, so a lucky null can pass a filter that does not work.
    // Measuring the decimator's OUTPUT is what makes this an aliasing test and not a
    // frequency-response one: whatever energy appears below 8 kHz got there by folding.
    // 8300 is in the list because a dense sweep puts the true band minimum at ~8297 Hz; without
    // it the probes bottom out 1.7 dB above the real worst case and flatter the filter.
    const double passband = steadyStateRms(1000.0);
    for (double hz : {8200.0, 8300.0, 9000.0, 10000.0, 11000.0, 12000.0, 14000.0, 17000.0,
                      20000.0, 23000.0}) {
        const double attenuationDb = 20.0 * std::log10(passband / steadyStateRms(hz));
        EXPECT_GT(attenuationDb, 40.0) << hz << " Hz folded back at only " << attenuationDb
                                       << " dB down";
    }
}

TEST(Decimator, CarriesHistoryAcrossCallsAndResetClearsIt) {
    const auto pcm = tone(1000.0, dumble::kFrameSamples * 2);
    std::vector<float> a(320), b(320);

    Decimator whole;
    whole.decimate(pcm.data(), a.data());
    whole.decimate(pcm.data() + dumble::kFrameSamples, a.data() + 160);

    Decimator again;
    again.decimate(pcm.data(), b.data());
    again.reset();
    again.decimate(pcm.data(), b.data() + 160);

    // First frames identical; second frames differ, because only `whole` carried tap history.
    for (int i = 0; i < 160; i++) EXPECT_FLOAT_EQ(a[i], b[i]) << "i=" << i;
    for (int i = 0; i < 160; i++) EXPECT_FLOAT_EQ(b[i], b[i + 160]) << "i=" << i;
    bool anyDifferent = false;
    for (int i = 160; i < 320; i++) anyDifferent |= (a[i] != b[i]);
    EXPECT_TRUE(anyDifferent) << "history was not carried across calls";
}
