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

constexpr int kFrame = 480;

// A real Opus packet spanning `tenMsUnits` * 10 ms — this side of the seam needs payloads libopus
// will actually accept, unlike PacketQueue, which never looks at the bytes.
std::vector<uint8_t> encode(int tenMsUnits) {
    return dumble::testtone::encodeTone(tenMsUnits * 480);
}

std::unique_ptr<SpeakerDecoder> newDecoder() {
    auto d = SpeakerDecoder::create(dumble::kSampleRate, kFrame);
    EXPECT_TRUE(d);
    return d;
}

void decode(SpeakerDecoder& d, const std::vector<uint8_t>& p) {
    d.decode(p.data(), int(p.size()));
}

}  // namespace

TEST(SpeakerDecoder, CreateBuildsADecoderUpFront) {
    EXPECT_TRUE(SpeakerDecoder::create(dumble::kSampleRate, kFrame));
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
    std::vector<int16_t> out(kFrame, 123);
    EXPECT_EQ(0, d->drain(out.data(), kFrame));
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
    std::vector<int16_t> out(kFrame);
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
    bool nonZero = false;
    for (int16_t s : out) nonZero = nonZero || s != 0;
    EXPECT_TRUE(nonZero);
}

TEST(SpeakerDecoder, AShortDrainZeroPadsAndReportsTheRealCount) {
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(2 * kFrame, 321);
    EXPECT_EQ(480, d->drain(out.data(), 2 * kFrame));
    for (int i = 480; i < 2 * kFrame; i++) EXPECT_EQ(0, out[i]) << "not zero-padded at " << i;
}

TEST(SpeakerDecoder, DrainConsumesWhatItReturns) {
    auto d = newDecoder();
    decode(*d, encode(2));
    std::vector<int16_t> out(kFrame);
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
    EXPECT_EQ(480, d->available());
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
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
    std::vector<int16_t> expected(kFrame);
    ASSERT_EQ(kFrame, fresh->drain(expected.data(), kFrame));

    auto reused = newDecoder();
    decode(*reused, other);
    std::vector<int16_t> discard(kFrame);
    reused->drain(discard.data(), kFrame);
    reused->reset();
    decode(*reused, first);
    std::vector<int16_t> got(kFrame);
    ASSERT_EQ(kFrame, reused->drain(got.data(), kFrame));

    EXPECT_EQ(expected, got);
}

TEST(SpeakerDecoder, ConcealRoundsUpToTheConcealmentGrid) {
    // The fifo carries the overshoot to the next call, which is why conceal reports what it wrote
    // rather than what was asked for.
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(kFrame);
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
    EXPECT_EQ(3 * pl::kConcealGridSamples, d->conceal(2 * pl::kConcealGridSamples + 1));
    EXPECT_EQ(3 * pl::kConcealGridSamples, d->available());
}

TEST(SpeakerDecoder, ConcealFillsAnExactRequestExactly) {
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> out(kFrame);
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
    EXPECT_EQ(kFrame, d->conceal(kFrame));
}

TEST(SpeakerDecoder, ConcealsTheWholeGapInOneRequest) {
    // Pins conceal's single decode call against a future grid loop: four 2.5 ms requests fade
    // below peak 100 inside the first frame, one 10 ms request holds ~2500 across it, and the
    // threshold sits between the two.
    auto d = newDecoder();
    decode(*d, encode(1));
    std::vector<int16_t> real(kFrame);
    ASSERT_EQ(kFrame, d->drain(real.data(), kFrame));
    ASSERT_EQ(kFrame, d->conceal(kFrame));
    std::vector<int16_t> concealed(kFrame);
    ASSERT_EQ(kFrame, d->drain(concealed.data(), kFrame));
    int tailPeak = 0;
    for (int i = kFrame / 2; i < kFrame; i++)
        tailPeak = std::max(tailPeak, std::abs(int(concealed[i])));
    EXPECT_GT(tailPeak, 500) << "the second half of the concealed frame had collapsed";
}

TEST(SpeakerDecoder, ConcealWithNoDecoderHistoryIsSilence) {
    // Unreachable through PlayoutEngine, but conceal is public, and libopus answering a
    // history-less request with silence rather than an error is what makes writing straight into
    // the fifo safe. Measured, not assumed.
    auto d = newDecoder();
    EXPECT_EQ(kFrame, d->conceal(kFrame));
    std::vector<int16_t> out(kFrame, 321);
    ASSERT_EQ(kFrame, d->drain(out.data(), kFrame));
    for (int i = 0; i < kFrame; i++) ASSERT_EQ(0, out[i]) << "not silence at " << i;
}

TEST(SpeakerDecoder, ResetDropsConcealedAudioToo) {
    auto d = newDecoder();
    decode(*d, encode(1));
    ASSERT_GT(d->conceal(kFrame), 0);
    d->reset();
    EXPECT_EQ(0, d->available());
}

TEST(SpeakerDecoder, IsNotQuietBeforeAnyAudio) {
    auto decoder = newDecoder();
    // No envelope yet, so nothing may be judged against it. Shrink reads this, and a fresh
    // decoder answering "quiet" would let a speaker's opening packet be discarded.
    EXPECT_FALSE(decoder->quiet());
}

TEST(SpeakerDecoder, IsNotQuietDuringTheTone) {
    auto decoder = newDecoder();
    dumble::testtone::Stream stream;
    for (int i = 0; i < 10; i++) {
        const auto packet = stream.encode(dumble::testtone::tone(kFrame));
        decoder->decode(packet.data(), int(packet.size()));
    }
    EXPECT_FALSE(decoder->quiet());
}

TEST(SpeakerDecoder, IsQuietInTheSilenceAfterSpeech) {
    auto decoder = newDecoder();
    dumble::testtone::Stream stream;
    for (int i = 0; i < 10; i++) {
        const auto packet = stream.encode(dumble::testtone::tone(kFrame));
        decoder->decode(packet.data(), int(packet.size()));
    }
    for (int i = 0; i < 5; i++) {
        const auto packet = stream.encode(dumble::testtone::silence(kFrame));
        decoder->decode(packet.data(), int(packet.size()));
    }
    EXPECT_TRUE(decoder->quiet());
}

TEST(SpeakerDecoder, TheAttackEndsQuietImmediately) {
    auto decoder = newDecoder();
    dumble::testtone::Stream stream;
    for (int i = 0; i < 10; i++) {
        const auto loud = stream.encode(dumble::testtone::tone(kFrame));
        decoder->decode(loud.data(), int(loud.size()));
    }
    for (int i = 0; i < 5; i++) {
        const auto hush = stream.encode(dumble::testtone::silence(kFrame));
        decoder->decode(hush.data(), int(hush.size()));
    }
    ASSERT_TRUE(decoder->quiet());
    const auto attack = stream.encode(dumble::testtone::tone(kFrame));
    decoder->decode(attack.data(), int(attack.size()));
    EXPECT_FALSE(decoder->quiet());
}

TEST(SpeakerDecoder, SoftSpeechIsNotQuiet) {
    auto decoder = newDecoder();
    dumble::testtone::Stream stream;
    for (int i = 0; i < 10; i++) {
        const auto loud = stream.encode(dumble::testtone::tone(kFrame));
        decoder->decode(loud.data(), int(loud.size()));
    }
    // ~25 dB below the peak: amplitude 448 against 8000, a ratio of 0.056. Mumble's gate is
    // amplitude < 0.01 of peak, so this is speech and must not be shrinkable. Squaring the
    // envelope would make the ratio 0.0032, inside a 1% gate, and shrink would splice here.
    // Digital silence passes either definition, which is why the other tests cannot see this.
    for (int i = 0; i < 5; i++) {
        const auto soft = stream.encode(dumble::testtone::tone(kFrame, 448.0));
        decoder->decode(soft.data(), int(soft.size()));
    }
    EXPECT_FALSE(decoder->quiet());
}

TEST(SpeakerDecoder, ResetClearsTheEnvelope) {
    auto decoder = newDecoder();
    dumble::testtone::Stream stream;
    for (int i = 0; i < 10; i++) {
        const auto loud = stream.encode(dumble::testtone::tone(kFrame));
        decoder->decode(loud.data(), int(loud.size()));
    }
    for (int i = 0; i < 5; i++) {
        const auto hush = stream.encode(dumble::testtone::silence(kFrame));
        decoder->decode(hush.data(), int(hush.size()));
    }
    ASSERT_TRUE(decoder->quiet());
    // A slot about to serve a different sender must not carry the previous one's dynamic range.
    decoder->reset();
    EXPECT_FALSE(decoder->quiet());
}
