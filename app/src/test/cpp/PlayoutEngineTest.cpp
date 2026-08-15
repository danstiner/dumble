#include <gtest/gtest.h>
#include <algorithm>
#include <climits>
#include <vector>
#include "TestTone.h"
#include "core/AudioDecoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

namespace {

using dumble::playout::PlayoutEngine;
namespace pl = dumble::playout;

// AudioConstants.kt QUANTUM_SAMPLES — the production playback tick.
constexpr int kQuantum = 480;

std::vector<uint8_t> encode(int tenMsUnits) {
    return dumble::testtone::encodeTone(tenMsUnits * 480);
}

std::unique_ptr<PlayoutEngine> newEngine() {
    auto e = PlayoutEngine::create(dumble::kSampleRate, kQuantum);
    EXPECT_TRUE(e);
    return e;
}

void arm(PlayoutEngine& e, int32_t session, int count = 6) {
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < count; i++)
        EXPECT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), false));
}

}  // namespace

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
    // maxQuantumSamples is this engine's alone, so no caller above can catch the mismatch. A 0
    // here would read as "nobody is speaking" and mute the app silently.
    auto e = newEngine();
    std::vector<int16_t> pcm(kQuantum * 2, 999);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(pl::kErrorBufferTooSmall,
              e->fillQuantum(pcm.data(), kQuantum * 2, speaking.data(), &live));
    EXPECT_EQ(0, live);
    EXPECT_EQ(999, pcm[0]) << "a refused tick wrote into the caller's buffer";
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
    // A code-3 packet claiming zero frames. libopus measures it at zero samples, so it can
    // neither be scheduled nor given a span — what a truncated or hostile payload looks like.
    // Reported rather than folded into kOfferAccepted: a peer sending only these would otherwise
    // be indistinguishable from one whose audio is simply overflowing the queue bounds.
    const uint8_t malformed[2] = {0x03, 0x00};
    ASSERT_LE(pl::AudioDecoder::packetSamples(malformed, 2, dumble::kSampleRate), 0)
        << "the fixture stopped being malformed";
    auto e = newEngine();
    EXPECT_EQ(pl::kOfferMalformedPacket, e->offer(1, malformed, 2, false));
    // A refused payload without a terminator claims nothing: a sender of pure garbage must not
    // occupy a speaker slot others could use.
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = -1;
    e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    EXPECT_EQ(0, live) << "a refused payload claimed a slot";
    // The next good packet from this session claims one normally and plays.
    const std::vector<uint8_t> good = encode(1);
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, good.data(), int(good.size()), false));
}

TEST(PlayoutEngine, AMalformedFinalPacketStillEndsItsSpurt) {
    // is_terminator is a protobuf field, not part of opus_data, so a corrupt last packet still
    // ends the spurt. Lose the flag with the payload and this tail — one 10 ms packet, far below
    // kPrebufferSamples — waits out kStallIdleTicks instead, and is never heard.
    auto e = newEngine();
    const std::vector<uint8_t> good = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, good.data(), int(good.size()), false));
    const uint8_t malformed[2] = {0x03, 0x00};
    ASSERT_EQ(pl::kOfferMalformedPacket, e->offer(3, malformed, 2, /*terminator=*/true));

    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live))
        << "the terminator was discarded along with the payload";
}

TEST(PlayoutEngine, AcceptsAPayloadFreeTerminator) {
    // len == 0 carries no samples by definition, so it must not read as malformed: this is the
    // latch that releases a talk spurt's tail when it sits below the prebuffer gate.
    auto e = newEngine();
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
}

TEST(PlayoutEngine, CreateRefusesAQuantumItsBuffersCannotHold) {
    // Not fussiness: SpeakerDecoder sizes its fifo from bit_ceil(maxQuantumSamples +
    // kMaxPacketSamples), so the top of int is undefined behaviour rather than a big number, and
    // maxQuantumSamples crosses the JNI boundary from Kotlin.
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, pl::kMaxPacketSamples + 1));
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, 0));
    EXPECT_FALSE(PlayoutEngine::create(dumble::kSampleRate, INT_MAX));
    EXPECT_TRUE(PlayoutEngine::create(dumble::kSampleRate, pl::kMaxPacketSamples));
}

TEST(PlayoutEngine, RefusesAnOversizedPacket) {
    auto e = newEngine();
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    EXPECT_EQ(pl::kOfferPacketTooLarge, e->offer(1, huge.data(), int(huge.size()), false));
}

TEST(PlayoutEngine, AnOversizedFinalPacketStillEndsItsSpurt) {
    // Same reasoning as the malformed case above: the payload cannot be queued, but the
    // terminator is a protobuf field, not part of it, and must still release the tail.
    auto e = newEngine();
    const std::vector<uint8_t> good = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, good.data(), int(good.size()), false));
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    ASSERT_EQ(pl::kOfferPacketTooLarge,
              e->offer(3, huge.data(), int(huge.size()), /*terminator=*/true));

    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live))
        << "the terminator was discarded along with the payload";
}

TEST(PlayoutEngine, FillsALargerQuantumFromMultiplePacketsInOneTick) {
    // Every other test runs at the production 480-sample quantum, where a 480-sample packet
    // fills it in fillQuantum's decode loop's single iteration — pop one, decode, land exactly on
    // available() == samples, exit. fillQuantum takes its sample count per call, so a caller
    // asking for more is legal too; this covers that shape, where the loop pops and decodes twice
    // to fill one tick — the "no more than one packet ahead" contract's other live path.
    constexpr int kLargeQuantum = 960;
    auto e = PlayoutEngine::create(dumble::kSampleRate, kLargeQuantum);
    ASSERT_TRUE(e);
    arm(*e, 1);
    std::vector<int16_t> pcm(kLargeQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(pcm.data(), kLargeQuantum, speaking.data(), &live));
}

// The tick, its prebuffer re-arm and its two retirement windows are the engine's.
namespace {

int producingThisTick(PlayoutEngine& e, std::vector<int16_t>& pcm) {
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    return e.fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
}

int liveThisTick(PlayoutEngine& e, std::vector<int16_t>& pcm) {
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    e.fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    return live;
}

// Ticks until the engine stops producing, returning how many ticks carried audio.
int drainSpurt(PlayoutEngine& e, int maxTicks = 500) {
    std::vector<int16_t> pcm(kQuantum);
    int ticks = 0;
    for (int i = 0; i < maxTicks; i++) {
        if (producingThisTick(e, pcm) == 0) break;
        ticks++;
    }
    return ticks;
}

}  // namespace

TEST(PlayoutEngine, TenMillisecondSenderDrainsOneQuantumPerTick) {
    auto e = newEngine();
    // Six 10 ms packets is exactly kPrebufferSamples, and the quantum is 10 ms, so the spurt must
    // come back out as six full ticks — no packet stranded, none played twice.
    arm(*e, 7, 6);
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, SixtyMillisecondSenderDrainsOneQuantumPerTick) {
    auto e = newEngine();
    // The same 60 ms as one packet: a tick must take a fraction of a packet and keep the rest.
    const std::vector<uint8_t> p = encode(6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, GoingIdleReArmsThePrebufferForTheNextSpurt) {
    auto e = newEngine();
    arm(*e, 7, 6);
    ASSERT_EQ(6, drainSpurt(*e));
    // drainSpurt's last tick found the queue drained and produced nothing, which is what re-arms
    // the gate — well inside kRetireIdleTicks, so the slot is still this speaker's.
    std::vector<int16_t> pcm(kQuantum);
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    EXPECT_EQ(0, producingThisTick(*e, pcm)) << "the next spurt played without prebuffering";
}

TEST(PlayoutEngine, PrebufferingDoesNotCountAsTheShortIdleWindow) {
    auto e = newEngine();
    // One packet, no terminator: produces nothing, but packets remain, so the long window applies
    // and the speaker must keep its slot while it waits for the rest of its spurt.
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kQuantum);
    for (int i = 0; i < pl::kRetireIdleTicks + 5; i++)
        EXPECT_EQ(1, liveThisTick(*e, pcm)) << "retired while prebuffering at tick " << i;
}

TEST(PlayoutEngine, ASpurtStalledBelowThePrebufferEventuallyReleasesItsSlot) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kQuantum);
    int live = 1;
    for (int i = 0; i < pl::kStallIdleTicks + 2 && live != 0; i++) live = liveThisTick(*e, pcm);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, AReclaimedSlotDoesNotInheritStrandedPackets) {
    // The stall window is the one retirement path that fires with packets still queued, so it is
    // what makes the reset load-bearing: without it the next sender to land on this slot inherits
    // a stranded packet and plays a syllable of someone else's voice.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kQuantum);
    int live = 1;
    for (int i = 0; i < pl::kStallIdleTicks + 2 && live != 0; i++) live = liveThisTick(*e, pcm);
    ASSERT_EQ(0, live) << "the stalled speaker never released its slot";

    // Exactly kPrebufferSamples from a new sender, which must come back as exactly six ticks. A
    // seventh would be session 7's stranded packet.
    arm(*e, 8, 6);
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, AReclaimedSlotDecodesLikeAFreshOne) {
    // The decoder half of the same contract. libopus predicts across packets, so a slot handed
    // from one sender to the next must start over — otherwise session 8's first quantum carries
    // the tail of session 7's voice. Compared against a fresh engine, which is the definition.
    //
    // One payload, offered to both: encode()'s encoder is a static and predicts across calls, so
    // re-encoding the same PCM for the second engine would compare two different packets.
    const std::vector<uint8_t> p = encode(1);
    const auto armWith = [&p](PlayoutEngine& e, int32_t session) {
        for (int i = 0; i < 6; i++)
            ASSERT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), false));
    };

    std::vector<int16_t> reused(kQuantum), fresh(kQuantum), pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;

    auto e = newEngine();
    armWith(*e, 7);
    ASSERT_EQ(6, drainSpurt(*e));
    for (int i = 0; i < pl::kRetireIdleTicks + 1; i++) liveThisTick(*e, pcm);
    ASSERT_EQ(0, liveThisTick(*e, pcm)) << "session 7 never released its slot";
    armWith(*e, 8);
    ASSERT_EQ(1, e->fillQuantum(reused.data(), kQuantum, speaking.data(), &live));

    auto clean = newEngine();
    armWith(*clean, 8);
    ASSERT_EQ(1, clean->fillQuantum(fresh.data(), kQuantum, speaking.data(), &live));

    EXPECT_EQ(fresh, reused);
}
