#include <gtest/gtest.h>
#include <cmath>
#include <opus.h>
#include <vector>
#include "core/AudioDecoder.h"
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::playout::AudioDecoder;

// A 440 Hz tone `samples` long, loud enough that a decode producing silence is unmistakable.
std::vector<int16_t> tone(int samples) {
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));
    return pcm;
}

// One packet of `frameSamples` duration, encoded at a typical Mumble bitrate.
std::vector<uint8_t> encodePacket(int frameSamples) {
    auto enc = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000);
    EXPECT_TRUE(enc);
    const std::vector<int16_t> pcm = tone(frameSamples);
    std::vector<uint8_t> packet(dumble::playout::kMaxPacketBytes);
    const int bytes = enc->encode(pcm.data(), frameSamples, packet.data(), int(packet.size()));
    EXPECT_GT(bytes, 0);
    packet.resize(bytes > 0 ? bytes : 0);
    return packet;
}

double meanEnergy(const int16_t* pcm, int n) {
    double energy = 0;
    for (int i = 0; i < n; i++) energy += double(pcm[i]) * pcm[i];
    return energy / n;
}

}  // namespace

TEST(AudioDecoder, CreateSucceedsForMumblesFormat) {
    EXPECT_TRUE(AudioDecoder::create(dumble::kSampleRate, dumble::kChannels));
}

TEST(AudioDecoder, CreateRejectsAnImpossibleRate) {
    EXPECT_FALSE(AudioDecoder::create(44100, 1));
}

// Every packet duration a Mumble sender may choose. The 10 ms case alone would not catch a decode
// whose output capacity was mistakenly set to one quantum.
TEST(AudioDecoder, RoundTripsEveryPacketDuration) {
    for (const int frameSamples : {480, 960, 1920, 2880}) {
        const std::vector<uint8_t> packet = encodePacket(frameSamples);
        auto dec = AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
        ASSERT_TRUE(dec);
        std::vector<int16_t> out(dumble::playout::kMaxFrameSamples);
        const int n = dec->decode(packet.data(), int(packet.size()), out.data(),
                                  dumble::playout::kMaxFrameSamples);
        EXPECT_EQ(frameSamples, n) << "at " << frameSamples << " samples";
        EXPECT_GT(meanEnergy(out.data(), n), 1000.0) << "decoded silence at " << frameSamples;
    }
}

TEST(AudioDecoder, PacketSamplesAgreesWithTheDecodedLength) {
    for (const int frameSamples : {480, 960, 1920, 2880}) {
        const std::vector<uint8_t> packet = encodePacket(frameSamples);
        EXPECT_EQ(frameSamples, AudioDecoder::packetSamples(packet.data(), int(packet.size()),
                                                            dumble::kSampleRate));
    }
}

TEST(AudioDecoder, PacketSamplesReadsOnlyTheHeader) {
    // The count is fully determined by the TOC byte plus the code-3 frame-count byte, so a
    // two-byte prefix must answer identically. This is what lets the engine avoid copying the
    // payload before it has decided to admit it.
    const std::vector<uint8_t> packet = encodePacket(2880);
    ASSERT_GE(packet.size(), 2u);
    EXPECT_EQ(2880, AudioDecoder::packetSamples(packet.data(), 2, dumble::kSampleRate));
}

TEST(AudioDecoder, PacketSamplesRejectsAnEmptyPacket) {
    const uint8_t nothing = 0;
    EXPECT_LT(AudioDecoder::packetSamples(&nothing, 0, dumble::kSampleRate), 0);
}

TEST(AudioDecoder, DecodeRefusesAnOutputTooSmallForThePacket) {
    // libopus does no bounds checking of its own; outCap is the only thing standing between a
    // 60 ms packet and a write past a one-quantum buffer.
    const std::vector<uint8_t> packet = encodePacket(2880);
    auto dec = AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
    ASSERT_TRUE(dec);
    std::vector<int16_t> out(480);
    EXPECT_LT(dec->decode(packet.data(), int(packet.size()), out.data(), 480), 0);
}

TEST(AudioDecoder, DecodeRejectsGarbage) {
    const std::vector<uint8_t> garbage(40, 0xFF);
    auto dec = AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
    ASSERT_TRUE(dec);
    std::vector<int16_t> out(dumble::playout::kMaxFrameSamples);
    EXPECT_LE(dec->decode(garbage.data(), int(garbage.size()), out.data(),
                          dumble::playout::kMaxFrameSamples), 0);
}
