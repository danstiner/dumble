#pragma once
#include <gtest/gtest.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"

// The one test signal: a 440 Hz tone loud enough that a decode producing silence is unmistakable,
// and the encoders that turn it into payloads libopus will actually accept — needed by every test
// downstream of the codec seam. PacketQueue alone tests on synthetic bytes and takes none of this.
namespace dumble::testtone {

inline std::vector<int16_t> tone(int samples) {
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));
    return pcm;
}

// One packet spanning `samples`, from a shared encoder. The encoder is a leaked static and
// predicts across calls, so every packet after the first is a mid-spurt payload; use
// encodeToneAlone for a spurt opener cut from fresh state.
inline std::vector<uint8_t> encodeTone(int samples) {
    static auto enc =
        dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000).release();
    const std::vector<int16_t> pcm = tone(samples);
    std::vector<uint8_t> packet(dumble::playout::kMaxPacketBytes);
    const int n = enc->encode(pcm.data(), samples, packet.data(), int(packet.size()));
    EXPECT_GT(n, 0);
    packet.resize(n > 0 ? n : 0);
    return packet;
}

// One standalone packet from a fresh encoder — no prediction history from earlier calls.
inline std::vector<uint8_t> encodeToneAlone(int samples, int bitrate = 40000) {
    auto enc = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, bitrate);
    EXPECT_TRUE(enc);
    if (!enc) return {};
    const std::vector<int16_t> pcm = tone(samples);
    std::vector<uint8_t> packet(dumble::playout::kMaxPacketBytes);
    const int n = enc->encode(pcm.data(), samples, packet.data(), int(packet.size()));
    EXPECT_GT(n, 0);
    packet.resize(n > 0 ? n : 0);
    return packet;
}

inline double meanEnergy(const int16_t* pcm, int n) {
    double energy = 0;
    for (int i = 0; i < n; i++) energy += double(pcm[i]) * pcm[i];
    return energy / n;
}

inline std::vector<int16_t> silence(int samples) {
    return std::vector<int16_t>(size_t(samples), 0);
}

// One encoder held across calls, so a test can build a run whose prediction state is continuous.
// The existing helpers cannot express an onset: encodeTone shares a hidden static across every
// test in the binary, and encodeToneAlone starts cold every time. An attack — silence, then tone,
// through one encoder — is the signal the energy gate's blind spot is made of, so it needs a
// fixture that can produce one.
class Stream {
public:
    explicit Stream(int bitrate = 40000)
        : enc_(dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, bitrate)) {
        EXPECT_TRUE(enc_);
    }

    std::vector<uint8_t> encode(const std::vector<int16_t>& pcm) {
        std::vector<uint8_t> packet(dumble::playout::kMaxPacketBytes);
        const int n = enc_->encode(pcm.data(), int(pcm.size()), packet.data(), int(packet.size()));
        EXPECT_GT(n, 0);
        packet.resize(n > 0 ? n : 0);
        return packet;
    }

private:
    std::unique_ptr<dumble::AudioEncoder> enc_;
};

}  // namespace dumble::testtone
