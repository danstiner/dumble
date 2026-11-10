#pragma once
#include <gtest/gtest.h>
#include <cstdint>
#include <random>
#include <vector>
#include "core/AudioDecoder.h"
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"

namespace dumble::latencytest {

// Integer-only, so the same seed gives the same samples on every platform; std's distributions
// differ between standard libraries.
inline std::vector<int16_t> noise(int64_t n, int amplitude, uint32_t seed) {
    std::mt19937 rng(seed);
    std::vector<int16_t> out(n);
    for (auto& s : out) s = int16_t(int64_t(rng() % uint32_t(2 * amplitude + 1)) - amplitude);
    return out;
}

// Through Dumble's encoder and a fresh decoder, 20 ms at a time.
inline std::vector<int16_t> opusRoundTrip(const std::vector<int16_t>& in) {
    auto enc = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 32000);
    auto dec = dumble::playout::AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
    std::vector<int16_t> out;
    uint8_t packet[dumble::kMaxPacketBytes];
    int16_t frame[dumble::kTxPacketSamples];
    for (size_t at = 0; at + dumble::kTxPacketSamples <= in.size(); at += dumble::kTxPacketSamples) {
        const int bytes = enc->encode(in.data() + at, dumble::kTxPacketSamples, packet, sizeof packet);
        EXPECT_GT(bytes, 0);
        const int samples = dec->decode(packet, bytes, frame, dumble::kTxPacketSamples);
        EXPECT_EQ(samples, dumble::kTxPacketSamples);
        out.insert(out.end(), frame, frame + samples);
    }
    return out;
}

}  // namespace dumble::latencytest
