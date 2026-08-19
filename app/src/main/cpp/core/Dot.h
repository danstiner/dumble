#pragma once

namespace dumble {

/**
 * Inner product of two length-n float arrays. Sixteen accumulators, not one: a single accumulator
 * serialises the reduction on FMA latency, and float addition is not associative so the compiler
 * may not split it without fast-math, which this project does not enable. Sixteen is four NEON
 * registers — four independent chains, enough to keep the pipeline fed.
 *
 * Shared by the decimator's FIR, the encoder's conv layers, and the LSTM's gate dot products — one
 * kernel so tuning it once tunes all three, instead of three copies silently drifting apart.
 */
inline float dot(const float* a, const float* b, int n) {
    float acc[16] = {};
    int i = 0;
    for (; i + 16 <= n; i += 16)
        for (int j = 0; j < 16; ++j) acc[j] += a[i + j] * b[i + j];
    float tail = 0.0f;
    for (; i < n; ++i) tail += a[i] * b[i];
    for (int j = 1; j < 16; ++j) acc[0] += acc[j];
    return acc[0] + tail;
}

}  // namespace dumble
