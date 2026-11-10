#include "core/LatencyPackets.h"

#include <algorithm>
#include "core/AudioDecoder.h"
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"

namespace dumble::latency {

// A stray wall-clock frame number must not size the timeline: 60 s is far past any check.
constexpr uint64_t kMaxSpanFrames = 6000;

std::vector<TimedPacket> encode(const std::vector<int16_t>& pcm, uint64_t firstFrame, int bitrate) {
    auto encoder = AudioEncoder::create(kSampleRate, kChannels, bitrate);
    if (!encoder) return {};
    std::vector<TimedPacket> out;
    std::vector<int16_t> frame(kTxPacketSamples);
    uint8_t bytes[kMaxPacketBytes];
    for (size_t at = 0; at < pcm.size(); at += kTxPacketSamples) {
        const size_t n = std::min(pcm.size() - at, size_t(kTxPacketSamples));
        std::fill(std::copy_n(pcm.begin() + at, n, frame.begin()), frame.end(), int16_t(0));
        const int len = encoder->encode(frame.data(), kTxPacketSamples, bytes, sizeof bytes);
        if (len <= 0) return {};
        out.push_back({firstFrame + out.size() * kFramesPerPacket, {bytes, bytes + len}});
    }
    return out;
}

int64_t codecDelay(const std::vector<int16_t>& probe, int bitrate) {
    const int64_t at = 4 * kTxPacketSamples;
    std::vector<int16_t> pcm(at + probe.size() + 4 * kTxPacketSamples, 0);
    std::copy(probe.begin(), probe.end(), pcm.begin() + at);
    const auto d = locate(encode(pcm, 0, bitrate), probe, 0);
    return d.found() ? d.offset - at : -1;
}

Detection locate(std::vector<TimedPacket> packets, const std::vector<int16_t>& probe,
                 int64_t codecDelaySamples) {
    if (packets.empty()) return {-1, 0, 0};
    auto decoder = playout::AudioDecoder::create(kSampleRate, kChannels);
    if (!decoder) return {-1, 0, 0};
    // A decoder predicts from the packet before, so it must see them in the sender's order.
    std::stable_sort(packets.begin(), packets.end(),
                     [](const TimedPacket& a, const TimedPacket& b) { return a.frameNumber < b.frameNumber; });
    const uint64_t first = packets.front().frameNumber;
    std::vector<int16_t> timeline;
    std::vector<int16_t> frame(kTxPacketSamples);
    for (const auto& p : packets) {
        if (p.opus.empty()) {
            continue;
        }
        const int n = decoder->decode(p.opus.data(), int(p.opus.size()), frame.data(), kTxPacketSamples);
        if (n <= 0) {
            continue;
        }
        const uint64_t behind = p.frameNumber - first;
        if (behind > kMaxSpanFrames) {
            continue;
        }
        const size_t start = size_t(behind) * kFrameSamples;
        if (timeline.size() < start + size_t(n)) timeline.resize(start + size_t(n), 0);
        std::copy_n(frame.begin(), n, timeline.begin() + start);
    }
    Detection d = find(timeline.data(), int64_t(timeline.size()), probe);
    if (d.offset >= 0) d.offset -= codecDelaySamples;
    return d;
}

}  // namespace dumble::latency
