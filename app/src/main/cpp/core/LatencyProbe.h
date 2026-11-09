#pragma once
#include <cstdint>
#include <vector>

// A known sound to time through the voice path, and the search that finds it again. Shared by the
// host harness and, through JNI, the on-device latency check.
namespace dumble::latency {

/** How many times the best match must stand above the next one more than 5 ms away. Through
 *  Opus in white noise it passes down to -14 dB and fails from -17 dB; a phone whose echo
 *  canceller stayed on measured 2.8x, and good matches through a phone's speaker 8x. */
constexpr float kFoundRatio = 5.0f;
/** Only for silence, where nothing competes: a muted microphone's zeros, or a lone click, which
 *  correlates near 0.01. Any match the ratio accepts in noise peaks well above it (0.17 at
 *  -14 dB), since normalized correlation peaks near sqrt(snr / (1 + snr)). */
constexpr float kFoundFloor = 0.1f;

/** 250 ms linear chirp, 300–3400 Hz, raised-cosine edges of 10 ms, 48 kHz mono, peaking at
 *  `peak`. */
std::vector<int16_t> probe(int16_t peak);

struct Detection {
    int64_t offset;   // where the probe starts in the searched audio, in samples; -1 when too short
    float peak;       // normalized cross-correlation there, 0..1
    float nextPeak;   // the best match more than 5 ms from `offset`
    bool found() const {
        return offset >= 0 && peak >= kFoundFloor && peak >= kFoundRatio * nextPeak;
    }
};

/** Normalized cross-correlation of `probe` over pcm[0, n): every 4th lag on every 2nd sample,
 *  then refined to the sample at full resolution. Callers pass a window, not a whole recording:
 *  the cost is lags times probe length. Polarity is ignored, since a speaker and microphone may
 *  invert. */
Detection find(const int16_t* pcm, int64_t n, const std::vector<int16_t>& probe);

}  // namespace dumble::latency
