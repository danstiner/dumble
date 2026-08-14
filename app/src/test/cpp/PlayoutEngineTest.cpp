#include <gtest/gtest.h>
#include <algorithm>
#include <climits>
#include <cmath>
#include <vector>
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/AudioDecoder.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

namespace {

using dumble::playout::PlayoutEngine;
namespace pl = dumble::playout;

// AudioConstants.kt:12 QUANTUM_SAMPLES — the production playback tick.
constexpr int kQuantum = 480;

std::vector<uint8_t> encodeSamples(int samples) {
    static auto enc =
        dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000).release();
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));
    std::vector<uint8_t> packet(pl::kMaxPacketBytes);
    const int n = enc->encode(pcm.data(), samples, packet.data(), int(packet.size()));
    EXPECT_GT(n, 0);
    packet.resize(n > 0 ? n : 0);
    return packet;
}

std::vector<uint8_t> encode(int tenMsFrames) { return encodeSamples(tenMsFrames * 480); }

std::unique_ptr<PlayoutEngine> newEngine() {
    auto e = PlayoutEngine::create(dumble::kSampleRate, kQuantum, pl::kMaxSpeakers);
    EXPECT_TRUE(e);
    return e;
}

void arm(PlayoutEngine& e, int32_t session, int count = 6) {
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < count; i++)
        EXPECT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), false));
}



}  // namespace

TEST(PlayoutEngine, CreateRejectsMoreSpeakersThanTheBitmaskHolds) {
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, kQuantum,
                                       pl::kMaxSpeakers + 1));
}

TEST(PlayoutEngine, AnIdleEngineProducesNothing) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kQuantum, 999);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(0, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, ReportsTheProducingSessions) {
    auto e = newEngine();
    arm(*e, 7);
    arm(*e, 9);
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    const int producing = e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    EXPECT_EQ(2, producing);
    EXPECT_EQ(2, live);
    std::vector<int32_t> got(speaking.begin(), speaking.begin() + producing);
    std::sort(got.begin(), got.end());
    EXPECT_EQ(std::vector<int32_t>({7, 9}), got);
}

TEST(PlayoutEngine, ActiveCountIncludesASpeakerThatIsStillPrebuffering) {
    // This is what drives the Kotlin park's unbounded-versus-bounded choice: a speaker exists but
    // produces nothing, and the loop must keep ticking it.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(0, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
    EXPECT_EQ(1, live);
}

TEST(PlayoutEngine, RefusesAQuantumLargerThanTheEngineWasBuiltFor) {
    // The engine is the only place this bound exists: playout_jni.cpp checks `frames` against
    // kMaxFrameSamples, the absolute ceiling, and never learns what maxQuantumSamples this engine
    // was created with. A 0 here would read as "nobody is speaking" and mute the app silently.
    auto e = newEngine();
    std::vector<int16_t> pcm(kQuantum * 2, 999);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(pl::kErrorBufferTooSmall, e->fillQuantum(pcm.data(), kQuantum * 2, speaking.data(), &live));
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, MixesTwoSpeakersLouderThanOne) {
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> one(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(one.data(), kQuantum, speaking.data(), &live));

    auto both = newEngine();
    arm(*both, 1);
    arm(*both, 2);
    std::vector<int16_t> two(kQuantum);
    ASSERT_EQ(2, both->fillQuantum(two.data(), kQuantum, speaking.data(), &live));

    double energyOne = 0, energyTwo = 0;
    for (int i = 0; i < kQuantum; i++) {
        energyOne += double(one[i]) * one[i];
        energyTwo += double(two[i]) * two[i];
    }
    EXPECT_GT(energyTwo, energyOne * 2.0);
}

TEST(PlayoutEngine, RetiresASilentSpeakerAndFreesItsSlot) {
    auto e = newEngine();
    arm(*e, 5);
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 6 + pl::kRetireIdleTicks + 1; i++)
        e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, ASessionReclaimsASlotAfterRetirement) {
    auto e = newEngine();
    arm(*e, 5);
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 6 + pl::kRetireIdleTicks + 1; i++)
        e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    ASSERT_EQ(0, live);
    arm(*e, 5);
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
}

TEST(PlayoutEngine, CapsConcurrentSpeakers) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    for (int32_t s = 0; s < pl::kMaxSpeakers; s++)
        EXPECT_EQ(pl::kOfferAccepted, e->offer(s, p.data(), int(p.size()), false));
    EXPECT_EQ(pl::kOfferSpeakerCap, e->offer(9999, p.data(), int(p.size()), false));
    // A capped session must not displace a live one.
    EXPECT_EQ(pl::kOfferAccepted, e->offer(0, p.data(), int(p.size()), false));
}

TEST(PlayoutEngine, ReportsAPayloadLibopusCannotParse) {
    // A code-3 packet claiming zero frames. libopus prices it at zero samples, so it can neither
    // be scheduled nor charged a span — which is what a truncated or hostile payload looks like.
    // Reported rather than folded into kOfferAccepted: a peer sending only these would otherwise
    // be indistinguishable from one whose audio is simply overflowing the queue bounds.
    const uint8_t malformed[2] = {0x03, 0x00};
    ASSERT_LE(pl::AudioDecoder::packetSamples(malformed, 2, dumble::kSampleRate), 0)
        << "the fixture stopped being malformed";
    auto e = newEngine();
    EXPECT_EQ(pl::kOfferMalformedPacket, e->offer(1, malformed, 2, false));
    // The speaker still exists — the slot was claimed before the payload was judged, so the next
    // good packet from this session plays.
    const std::vector<uint8_t> good = encode(1);
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, good.data(), int(good.size()), false));
}

TEST(PlayoutEngine, AcceptsAPayloadFreeTerminator) {
    // len == 0 prices to nothing by definition, so it must not read as malformed: this is the
    // latch that releases a talk spurt's tail when it sits below the prebuffer gate.
    auto e = newEngine();
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
}

TEST(PlayoutEngine, RefusesAQuantumLargerThanTheLargestOpusFrame) {
    // Not fussiness: SpeakerQueue sizes its fifo from bit_ceil(maxQuantumSamples +
    // kMaxFrameSamples), so the top of int is undefined behaviour rather than a big number, and
    // maxQuantumSamples crosses the JNI boundary from Kotlin.
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, pl::kMaxFrameSamples + 1,
                                       pl::kMaxSpeakers));
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, INT_MAX, pl::kMaxSpeakers));
    // The ceiling itself is legal — it is exactly what playout_jni.cpp allows for `frames`.
    EXPECT_TRUE(PlayoutEngine::create(dumble::kSampleRate, pl::kMaxFrameSamples, pl::kMaxSpeakers));
}

TEST(PlayoutEngine, RefusesAnOversizedPacket) {
    auto e = newEngine();
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    EXPECT_EQ(pl::kOfferPacketTooLarge, e->offer(1, huge.data(), int(huge.size()), false));
}









TEST(PlayoutEngine, FillsALargerQuantumFromMultiplePacketsInOneTick) {
    // Every other test runs at the production 480-sample quantum, where a 480-sample packet
    // fills it in fillQuantum's decode loop's single iteration — pop one, decode, land exactly on
    // pcmAvailable() == frames, exit. fillQuantum takes its frame count per call, so a caller
    // asking for more is legal too; this covers that shape, where the loop pops and decodes twice
    // to fill one tick — the "no more than one packet ahead" contract's other live path.
    constexpr int kLargeQuantum = 960;
    auto e = PlayoutEngine::create(dumble::kSampleRate, kLargeQuantum,
                                   pl::kMaxSpeakers);
    ASSERT_TRUE(e);
    arm(*e, 1);
    std::vector<int16_t> pcm(kLargeQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(pcm.data(), kLargeQuantum, speaking.data(), &live));

}


// offer() is fed straight off the network, so its bytes are whatever a peer chose to send. Seeded

// The shape a lossy or hostile transport actually produces, which random bytes rarely reach: a
// real Opus header with the payload cut short, including the zero-length tag-only frame.
