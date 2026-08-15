#pragma once
#include <cstdint>

namespace dumble::playout {

/**
 * Sums mono PCM16 streams with a soft-knee limiter. Accumulate in int32 so no intermediate
 * clipping occurs, then soft-limit only above the threshold — normal levels stay at unity gain,
 * and double-talk compresses instead of collapsing toward the louder speaker.
 *
 * Call pattern, once per playback quantum: zero `acc`, mixAccumulate each active speaker's PCM
 * into it, then mixFinalize once into the buffer handed to the output. The accumulator cannot
 * overflow: worst case is kMaxSpeakers full-scale streams, kMaxSpeakers * 2^15, which Mixer.cpp
 * static_asserts against int32 so the bound follows the constant.
 *
 * Desktop Mumble hard-clips its mix; we round the corner with tanh instead to reduce distortion
 * when multiple speakers peak at the same instant. A broadcast-style envelope limiter would add
 * less distortion for this edge case, but we are avoiding the added per-mixer state and
 * attack/release tuning.
 */
void mixAccumulate(int32_t* acc, const int16_t* src, int n);
void mixFinalize(const int32_t* acc, int16_t* dst, int n);

}  // namespace dumble::playout
