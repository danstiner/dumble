#include <gtest/gtest.h>
#include <cmath>
#include <memory>
#include <vector>
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"
#include "core/SpeakerDecoder.h"

namespace {

using dumble::playout::SpeakerDecoder;
namespace pl = dumble::playout;

constexpr int kQuantum = 480;

// A real Opus packet spanning `tenMsUnits` * 10 ms — this side of the seam needs payloads libopus
// will actually accept, unlike PacketQueue, which never looks at the bytes.
std::vector<uint8_t> encode(int tenMsUnits) {
    static auto enc =
        dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000).release();
    const int samples = tenMsUnits * 480;
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));
    std::vector<uint8_t> bytes(pl::kMaxPacketBytes);
    const int n = enc->encode(pcm.data(), samples, bytes.data(), int(bytes.size()));
    EXPECT_GT(n, 0);
    bytes.resize(n > 0 ? n : 0);
    return bytes;
}

std::unique_ptr<SpeakerDecoder> newDecoder() {
    auto d = SpeakerDecoder::create(dumble::kSampleRate, kQuantum);
    EXPECT_TRUE(d);
    return d;
}

void decode(SpeakerDecoder& d, const std::vector<uint8_t>& p) {
    d.decode(p.data(), int(p.size()));
}

}  // namespace

TEST(SpeakerDecoder, CreateBuildsADecoderUpFront) {
    EXPECT_TRUE(SpeakerDecoder::create(dumble::kSampleRate, kQuantum));
}

TEST(SpeakerDecoder, CreateRefusesAQuantumTheFifoCannotBeSizedFrom) {
    EXPECT_FALSE(SpeakerDecoder::create(dumble::kSampleRate, 0));
    EXPECT_FALSE(SpeakerDecoder::create(dumble::kSampleRate, -1));
    EXPECT_FALSE(SpeakerDecoder::create(dumble::kSampleRate, pl::kMaxPacketSamples + 1));
    EXPECT_TRUE(SpeakerDecoder::create(dumble::kSampleRate, pl::kMaxPacketSamples));
}

TEST(SpeakerDecoder, StartsWithNothingBuffered) {
    auto d = newDecoder();
    EXPECT_EQ(0, d->available());
    std::vector<int16_t> out(kQuantum, 123);
    EXPECT_EQ(0, d->drain(out.data(), kQuantum));
    EXPECT_EQ(0, out[0]) << "drain left the caller's buffer untouched";
}

TEST(SpeakerDecoder, AvailableTracksDecodedSamples) {
    auto d = newDecoder();
    decode(*d, encode(1));
    EXPECT_EQ(480, d->available());
    decode(*d, encode(2));
    EXPECT_EQ(480 + 960, d->available());
}

TEST(SpeakerDecoder, DecodedAudioIsNotSilence) {
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    bool nonZero = false;
    for (int16_t s : out) nonZero = nonZero || s != 0;
    EXPECT_TRUE(nonZero);
}

TEST(SpeakerDecoder, AShortDrainZeroPadsAndReportsTheRealCount) {
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(2 * kQuantum, 321);
    EXPECT_EQ(480, d->drain(out.data(), 2 * kQuantum));
    for (int i = 480; i < 2 * kQuantum; i++) EXPECT_EQ(0, out[i]) << "not zero-padded at " << i;
}

TEST(SpeakerDecoder, DrainConsumesWhatItReturns) {
    auto d = newDecoder();
    decode(*d, encode(2));
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    EXPECT_EQ(480, d->available());
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    EXPECT_EQ(0, d->available());
}

TEST(SpeakerDecoder, APayloadLibopusRefusesDecodesToNothing) {
    auto d = newDecoder();
    const std::vector<uint8_t> junk(3, 0xFF);
    decode(*d, junk);
    EXPECT_EQ(0, d->available());
}

TEST(SpeakerDecoder, TheLargestLegalPacketFits) {
    // kMaxPacketSamples is 120 ms, the largest Opus packet — the decode scratch is sized for
    // exactly this and nothing bounds-checks libopus but that number.
    auto d = newDecoder();
    decode(*d, encode(12));
    EXPECT_EQ(pl::kMaxPacketSamples, d->available());
}
