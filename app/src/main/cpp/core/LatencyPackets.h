#pragma once
#include <cstdint>
#include <vector>
#include "core/LatencyProbe.h"

// The probe as voice packets, and the search over packets: what the on-device latency check
// sends and what it reads back, on the sender's clock rather than a recording's.
namespace dumble::latency {

/** One Opus packet at its place on the sender's clock, in 10 ms frame-number units. */
struct TimedPacket {
    uint64_t frameNumber;
    std::vector<uint8_t> opus;
};

/** `pcm` as Dumble's capture encodes a spurt: a fresh encoder at `bitrate`, 20 ms packets, frame
 *  numbers from `firstFrame` in steps of two. A partial last packet is padded with silence. */
std::vector<TimedPacket> encode(const std::vector<int16_t>& pcm, uint64_t firstFrame, int bitrate);

/** Opus's delay at `bitrate`, measured on the probe the way the harness measures it. */
int64_t codecDelay(const std::vector<int16_t>& probe, int bitrate);

/** Decodes `packets`, in frame order whatever order they come in, onto one timeline starting at
 *  the earliest frame (a missing frame is silence), and finds `probe` in it. `offset` is in
 *  samples from that frame's first sample, less `codecDelaySamples`, so it names where the probe
 *  sat in the audio that was encoded. */
Detection locate(std::vector<TimedPacket> packets, const std::vector<int16_t>& probe,
                 int64_t codecDelaySamples);

}  // namespace dumble::latency
