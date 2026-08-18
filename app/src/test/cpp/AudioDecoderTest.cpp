#include <gtest/gtest.h>
#include <opus.h>
#include <vector>
#include "TestTone.h"
#include "core/AudioDecoder.h"
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::playout::AudioDecoder;
using dumble::testtone::meanEnergy;
using dumble::testtone::tone;

// One packet of `frameSamples` duration — fresh encoder, so each is a spurt opener.
std::vector<uint8_t> encodePacket(int frameSamples) {
    return dumble::testtone::encodeToneAlone(frameSamples);
}

}  // namespace

TEST(AudioDecoder, CreateSucceedsForMumblesFormat) {
    EXPECT_TRUE(AudioDecoder::create(dumble::kSampleRate, dumble::kChannels));
}

TEST(AudioDecoder, CreateRejectsAnImpossibleRate) {
    EXPECT_FALSE(AudioDecoder::create(44100, 1));
}

// Every packet duration a Mumble sender may choose. The 10 ms case alone would not catch a decode
// whose output capacity was mistakenly set to one frame.
TEST(AudioDecoder, RoundTripsEveryPacketDuration) {
    for (const int frameSamples : {480, 960, 1920, 2880}) {
        const std::vector<uint8_t> packet = encodePacket(frameSamples);
        auto dec = AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
        ASSERT_TRUE(dec);
        std::vector<int16_t> out(dumble::playout::kMaxPacketSamples);
        const int n = dec->decode(packet.data(), int(packet.size()), out.data(),
                                  dumble::playout::kMaxPacketSamples);
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
    // 60 ms packet and a write past a one-frame buffer.
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
    std::vector<int16_t> out(dumble::playout::kMaxPacketSamples);
    EXPECT_LE(dec->decode(garbage.data(), int(garbage.size()), out.data(),
                          dumble::playout::kMaxPacketSamples), 0);
}
