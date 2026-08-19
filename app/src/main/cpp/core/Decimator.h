#pragma once
#include <cstdint>
#include "core/CaptureConstants.h"

namespace dumble {

/**
 * 3:1 decimator, 48 kHz to 16 kHz, stateful across calls. A windowed-sinc low-pass at ~7 kHz runs
 * before take-every-third, so broadband high-frequency noise — keyboards, hiss, fans — cannot
 * alias down into the speech band and inflate the detector's false positives. Single-thread.
 */
class Decimator {
public:
    static constexpr int kRatio = 3;
    static constexpr int kOutputSamples = kFrameSamples / kRatio;   // 160

    Decimator();

    /** Decimates one capture frame into `out`, kOutputSamples floats normalised to [-1, 1]. */
    void decimate(const int16_t* frame, float* out);

    /** Capture discontinuity: drop the tap history so the next call is a cold start. */
    void reset();

private:
    // Hann's stopband floor is ~44 dB however long the filter is; length buys transition width,
    // not depth. 33 taps — the prototype's Kotlin value — leaves 8-9 kHz inside the transition
    // band, measured at only 30.7 dB, so content there aliases straight into the speech band.
    // 75 taps puts the fold band past the transition: 43.9 dB worst case over 8.2-23 kHz. Costs
    // 0.77 ms of group delay and 1.2 MMAC/s against a detector spending ≈11 MMAC/s. See docs/vad.md
    // for the sweep, and for why the guarantee starts at 8.2 rather than 8.0 kHz.
    static constexpr int kTaps = 75;

    // Taps stored reversed, and history kept linear rather than circular, so the inner product
    // walks both arrays forward and contiguously. The circular buffer it replaces indexed
    // backwards with a wraparound branch, which cannot vectorise.
    float reversed_[kTaps];
    float carry_[kTaps - 1] = {};
};

}  // namespace dumble
