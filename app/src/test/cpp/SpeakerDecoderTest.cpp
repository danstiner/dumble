#include <gtest/gtest.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>
#include "TestTone.h"
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
    return dumble::testtone::encodeTone(tenMsUnits * 480);
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

TEST(SpeakerDecoder, ResetDropsBufferedAudio) {
    auto d = newDecoder();
    decode(*d, encode(2));
    ASSERT_GT(d->available(), 0);
    d->reset();
    EXPECT_EQ(0, d->available());
}

TEST(SpeakerDecoder, ResetDropsTheDecodersHistory) {
    // The reason reset() exists: a decoder is handed from sender to sender, and libopus predicts
    // across packets. A reused decoder must produce what a fresh one would, or the new speaker's
    // first packet carries the previous speaker's tail.
    //
    // One payload decoded by both: encode()'s encoder is a static and predicts across calls, so
    // re-encoding the same PCM for the second decoder would compare two different packets.
    const std::vector<uint8_t> other = encode(2);
    const std::vector<uint8_t> first = encode(1);

    auto fresh = newDecoder();
    decode(*fresh, first);
    std::vector<int16_t> expected(kQuantum);
    ASSERT_EQ(kQuantum, fresh->drain(expected.data(), kQuantum));

    auto reused = newDecoder();
    decode(*reused, other);
    std::vector<int16_t> discard(kQuantum);
    reused->drain(discard.data(), kQuantum);
    reused->reset();
    decode(*reused, first);
    std::vector<int16_t> got(kQuantum);
    ASSERT_EQ(kQuantum, reused->drain(got.data(), kQuantum));

    EXPECT_EQ(expected, got);
}

TEST(SpeakerDecoder, ConcealRoundsUpToTheConcealmentGrid) {
    // The fifo carries the overshoot to the next tick, which is why conceal reports what it wrote
    // rather than what was asked for.
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    EXPECT_EQ(3 * pl::kConcealGridSamples, d->conceal(2 * pl::kConcealGridSamples + 1));
    EXPECT_EQ(3 * pl::kConcealGridSamples, d->available());
}

TEST(SpeakerDecoder, ConcealFillsAnExactRequestExactly) {
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    EXPECT_EQ(kQuantum, d->conceal(kQuantum));
}

TEST(SpeakerDecoder, ConcealsTheWholeGapInOneRequest) {
    // Pins conceal's single decode call against a future grid loop: four 2.5 ms requests fade
    // below peak 100 inside the first quantum, one 10 ms request holds ~2500 across it, and the
    // threshold sits between the two.
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> real(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(real.data(), kQuantum));
    ASSERT_EQ(kQuantum, d->conceal(kQuantum));
    std::vector<int16_t> concealed(kQuantum);
    ASSERT_EQ(kQuantum, d->drain(concealed.data(), kQuantum));
    int tailPeak = 0;
    for (int i = kQuantum / 2; i < kQuantum; i++)
        tailPeak = std::max(tailPeak, std::abs(int(concealed[i])));
    EXPECT_GT(tailPeak, 500) << "the second half of the concealed quantum had collapsed";
}

TEST(SpeakerDecoder, ConcealWithNoDecoderHistoryIsSilence) {
    // Unreachable through PlayoutEngine, but conceal is public, and libopus answering a
    // history-less request with silence rather than an error is what makes writing straight into
    // the fifo safe. Measured, not assumed.
    auto d = newDecoder();
    EXPECT_EQ(kQuantum, d->conceal(kQuantum));
    std::vector<int16_t> out(kQuantum, 321);
    ASSERT_EQ(kQuantum, d->drain(out.data(), kQuantum));
    for (int i = 0; i < kQuantum; i++) ASSERT_EQ(0, out[i]) << "not silence at " << i;
}

TEST(SpeakerDecoder, ResetDropsConcealedAudioToo) {
    auto d = newDecoder();
    decode(*d, encode(1));
    ASSERT_GT(d->conceal(kQuantum), 0);
    d->reset();
    EXPECT_EQ(0, d->available());
}
