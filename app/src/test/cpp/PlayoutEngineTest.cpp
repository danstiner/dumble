#include <gtest/gtest.h>
#include <algorithm>
#include <climits>
#include <atomic>
#include <random>
#include <thread>
#include <vector>
#include "TestTone.h"
#include "core/AudioDecoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

namespace {

using dumble::playout::PlayoutEngine;
namespace pl = dumble::playout;

// AudioConstants.kt FRAME_SAMPLES — the quantum the production playback loop asks for.
constexpr int kFrame = 480;

std::vector<uint8_t> encode(int tenMsUnits) {
    return dumble::testtone::encodeTone(tenMsUnits * 480);
}

std::unique_ptr<PlayoutEngine> newEngine() {
    auto e = PlayoutEngine::create(dumble::kSampleRate, kFrame);
    EXPECT_TRUE(e);
    return e;
}

void arm(PlayoutEngine& e, int32_t session, int count = 6) {
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < count; i++)
        EXPECT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), false));
}

int producingThisFill(PlayoutEngine& e, std::vector<int16_t>& pcm) {
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    return e.fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
}

int liveThisFill(PlayoutEngine& e, std::vector<int16_t>& pcm) {
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    e.fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    return live;
}

// Fills until the engine stops producing, returning how many carried audio.
int drainSpurt(PlayoutEngine& e, int maxFills = 500) {
    std::vector<int16_t> pcm(kFrame);
    int fills = 0;
    for (int i = 0; i < maxFills; i++) {
        if (producingThisFill(e, pcm) == 0) break;
        fills++;
    }
    return fills;
}

// Arms one six-packet spurt for `session` and plays it out: the concealment tests below all start
// from a speaker that just went from producing to starved.
void playSpurt(PlayoutEngine& e, int32_t session, std::vector<int16_t>& pcm) {
    arm(e, session);
    for (int i = 0; i < 6; i++)
        ASSERT_EQ(1, producingThisFill(e, pcm)) << "real audio, fill " << i;
}

int64_t droppedOf(PlayoutEngine& e) { return e.stats().droppedPackets; }
int64_t concealedOf(PlayoutEngine& e) { return e.stats().concealedGaps; }

}  // namespace

TEST(PlayoutEngine, AnIdleEngineProducesNothing) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame, 999);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(0, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live));
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, ReportsTheProducingSessions) {
    auto e = newEngine();
    arm(*e, 7);
    arm(*e, 9);
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    const int producing = e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    EXPECT_EQ(2, producing);
    EXPECT_EQ(2, live);
    std::vector<int32_t> got(speaking.begin(), speaking.begin() + producing);
    std::sort(got.begin(), got.end());
    EXPECT_EQ(std::vector<int32_t>({7, 9}), got);
}

TEST(PlayoutEngine, ActiveCountIncludesASpeakerThatIsStillPrebuffering) {
    // This is what drives the Kotlin park's unbounded-versus-bounded choice: a speaker exists but
    // produces nothing, and the loop must keep calling it.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(0, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live));
    EXPECT_EQ(1, live);
}

TEST(PlayoutEngine, RefusesAFrameLargerThanTheEngineWasBuiltFor) {
    // maxQuantumSamples is this engine's alone, so no caller above can catch the mismatch. A 0
    // here would read as "nobody is speaking" and mute the app silently.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame * 2, 999);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(pl::kErrorBufferTooSmall,
              e->fillQuantum(pcm.data(), kFrame * 2, speaking.data(), &live));
    EXPECT_EQ(0, live);
    EXPECT_EQ(999, pcm[0]) << "a refused fill wrote into the caller's buffer";
}

TEST(PlayoutEngine, MixesTwoSpeakersLouderThanOne) {
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> one(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(one.data(), kFrame, speaking.data(), &live));

    auto both = newEngine();
    arm(*both, 1);
    arm(*both, 2);
    std::vector<int16_t> two(kFrame);
    ASSERT_EQ(2, both->fillQuantum(two.data(), kFrame, speaking.data(), &live));

    double energyOne = 0, energyTwo = 0;
    for (int i = 0; i < kFrame; i++) {
        energyOne += double(one[i]) * one[i];
        energyTwo += double(two[i]) * two[i];
    }
    EXPECT_GT(energyTwo, energyOne * 2.0);
}

TEST(PlayoutEngine, RetiresASilentSpeakerAndFreesItsSlot) {
    auto e = newEngine();
    arm(*e, 5);
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 6 + pl::kConcealQuanta + pl::kRetireIdlePolls + 1; i++)
        e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, ASessionReclaimsASlotAfterRetirement) {
    auto e = newEngine();
    arm(*e, 5);
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 6 + pl::kConcealQuanta + pl::kRetireIdlePolls + 1; i++)
        e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    ASSERT_EQ(0, live);
    arm(*e, 5);
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live));
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
    // occupy a speaker slot others could use. Nor is it a drop — kOfferMalformedPacket already
    // reported it.
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = -1;
    e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    EXPECT_EQ(0, live) << "a refused payload claimed a slot";
    EXPECT_EQ(0, droppedOf(*e)) << "a refused payload was double-reported as a drop";
    // The next good packet from this session claims one normally and plays.
    const std::vector<uint8_t> good = encode(1);
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, good.data(), int(good.size()), false));
}

TEST(PlayoutEngine, AMalformedFinalPacketStillEndsItsSpurt) {
    // is_terminator is a protobuf field, not part of opus_data, so a corrupt last packet still
    // ends the spurt. Lose the flag with the payload and this tail — one 10 ms packet, far below
    // kPrebufferSamples — waits out kStallIdlePolls instead, and is never heard.
    auto e = newEngine();
    const std::vector<uint8_t> good = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, good.data(), int(good.size()), false));
    const uint8_t malformed[2] = {0x03, 0x00};
    ASSERT_EQ(pl::kOfferMalformedPacket, e->offer(3, malformed, 2, /*terminator=*/true));

    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live))
        << "the terminator was discarded along with the payload";
}

TEST(PlayoutEngine, AcceptsAPayloadFreeTerminator) {
    // len == 0 carries no samples by definition, so it must not read as malformed: this is the
    // latch that releases a talk spurt's tail when it sits below the prebuffer gate.
    auto e = newEngine();
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
}

TEST(PlayoutEngine, CreateRefusesAFrameItsBuffersCannotHold) {
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
    EXPECT_EQ(0, droppedOf(*e)) << "a refused payload was double-reported as a drop";
}

TEST(PlayoutEngine, CountsAPacketTheSpeakerCapRefused) {
    // No queue exists to charge it to, so the engine's own tally is the only place a capped
    // session's lost audio can show up at all.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    for (int32_t s = 0; s < pl::kMaxSpeakers; s++)
        ASSERT_EQ(pl::kOfferAccepted, e->offer(s, p.data(), int(p.size()), false));
    ASSERT_EQ(0, droppedOf(*e));
    ASSERT_EQ(pl::kOfferSpeakerCap, e->offer(9999, p.data(), int(p.size()), false));
    EXPECT_EQ(1, droppedOf(*e));
}

TEST(PlayoutEngine, ReportsEachSpeakersBufferedDepth) {
    auto e = newEngine();
    arm(*e, 4, 3);
    arm(*e, 8, 6);
    const PlayoutEngine::Stats stats = e->stats();
    ASSERT_EQ(2, stats.speakers);
    for (int i = 0; i < stats.speakers; i++) {
        if (stats.sessions[i] == 4) EXPECT_EQ(3 * 480, stats.depths[i]);
        if (stats.sessions[i] == 8) EXPECT_EQ(6 * 480, stats.depths[i]);
    }
}

TEST(PlayoutEngine, ARetiredSpeakersDropsSurviveItsSlot) {
    // The tally lives on the queue, which retirement resets. Harvested first, or a channel that
    // dropped audio all session reports zero the moment the speaker goes quiet.
    auto e = newEngine();
    // One past kMaxQueuedPackets, so the queue drops exactly one for backlog. Well under
    // kHighWaterSamples at this span, so the packet-count bound is the one that binds.
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < pl::kMaxQueuedPackets + 1; i++)
        ASSERT_EQ(pl::kOfferAccepted, e->offer(5, p.data(), int(p.size()), false));
    ASSERT_EQ(1, droppedOf(*e));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < pl::kStallIdlePolls + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    ASSERT_EQ(0, live) << "the speaker never retired";
    EXPECT_EQ(1, droppedOf(*e)) << "retirement lost the tally, or counted it twice";
}

TEST(PlayoutEngine, CountsAPartialFrameAsConcealment) {
    // A terminator opens the prebuffer gate immediately, which is what lets one packet play at
    // all here. Sub-frame on purpose: 240 samples is a legal 5 ms Opus frame that decodes to
    // half the 480-sample quantum, so the rest is drain's zero padding — speech spliced with
    // silence.
    auto e = newEngine();
    const std::vector<uint8_t> p = dumble::testtone::encodeTone(240);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(2, p.data(), int(p.size()), true));
    std::vector<int16_t> pcm(kFrame);
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(1, concealedOf(*e));
}

TEST(PlayoutEngine, CountsAMidSpurtStallAsConcealment) {
    // The gap a partial frame cannot express: a speaker mid-sentence whose packets stop
    // arriving. Every fill of it produces nothing, so a count that only fires on a short fill
    // reports zero for the loudest dropout there is.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kFrame);
    ASSERT_EQ(6 + pl::kConcealQuanta, drainSpurt(*e)) << "six real frames, then the hold";
    // The charge landed on the first concealed fill — the leading edge of the gap — and the fills
    // after it are the same gap being held, not new ones.
    EXPECT_EQ(1, concealedOf(*e));
    // Charged on the leading edge only — past the re-arm a stalled speaker and a silent one are
    // the same state, so the following fills must not keep billing.
    for (int i = 0; i < 5; i++) producingThisFill(*e, pcm);
    EXPECT_EQ(1, concealedOf(*e)) << "a stall must be counted once, not per fill";
}

TEST(PlayoutEngine, DoesNotCountASpurtItsSenderClosed) {
    // The other half of the stall rule, and why it needs the terminator at all: speech ending
    // normally must not read as a dropout, or the metric fires once per utterance.
    auto e = newEngine();
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 12; i++) producingThisFill(*e, pcm);
    EXPECT_EQ(0, concealedOf(*e));
}

TEST(PlayoutEngine, DoesNotCountASpeakerStillPrebuffering) {
    // Quiet by design, not by accident: the gate is closed for the spurt's first
    // kPrebufferSamples and the loop fills throughout. Charging those would swamp every real gap.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 4; i++) ASSERT_EQ(0, producingThisFill(*e, pcm));
    EXPECT_EQ(0, concealedOf(*e));
}

TEST(PlayoutEngine, ConcealedGapsAreMonotonic) {
    // Kotlin subtracts a spurt-start baseline, exactly as it does for the platform's underrun
    // counter, so this must never reset on its own — not even as speakers come and go.
    auto e = newEngine();
    const std::vector<uint8_t> p = dumble::testtone::encodeTone(240);
    std::vector<int16_t> pcm(kFrame);
    int64_t previous = 0;
    for (int32_t session = 20; session < 23; session++) {
        ASSERT_EQ(pl::kOfferAccepted, e->offer(session, p.data(), int(p.size()), true));
        producingThisFill(*e, pcm);
        const int64_t now = concealedOf(*e);
        EXPECT_GE(now, previous);
        previous = now;
    }
    EXPECT_EQ(3, previous);
}

TEST(PlayoutEngine, ConcealsAMidSpurtStallForABoundedNumberOfQuanta) {
    // A speaker mid-sentence whose packets stop arriving: concealment keeps the fill producing,
    // bounded by kConcealQuanta (the why lives on the constant).
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    for (int i = 0; i < pl::kConcealQuanta; i++)
        EXPECT_EQ(1, producingThisFill(*e, pcm)) << "the hold ended early, at fill " << i;
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "the hold outlived kConcealQuanta";
}

TEST(PlayoutEngine, ConcealedAudioIsNotSilence) {
    // Concealment counting as production is only honest if there is something in the buffer. A
    // silent fill would satisfy every other test in this file.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    std::fill(pcm.begin(), pcm.end(), 0);
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    bool nonZero = false;
    for (int16_t s : pcm) nonZero = nonZero || s != 0;
    EXPECT_TRUE(nonZero) << "the first concealed fill was silence";
}

TEST(PlayoutEngine, APacketDelayedByAStallPlaysWithoutRebuffering) {
    // The point of the change: a delayed packet plays on the fill it arrives, not after a second
    // prebuffer. Depth is the assertion, not the producing count — while the hold runs, producing
    // is 1 either way, and only an emptied queue proves the packet played.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    for (int i = 0; i < 2; i++) ASSERT_EQ(1, producingThisFill(*e, pcm)) << "the hold ended early";
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(0, e->stats().depths[0]) << "the delayed packet waited out a second prebuffer";
}

TEST(PlayoutEngine, RealAudioReArmsTheHold) {
    // A spurt that survives a stall gets a full hold for the next one — the stallQuanta_ invariant.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    for (int i = 0; i < pl::kConcealQuanta - 1; i++) ASSERT_EQ(1, producingThisFill(*e, pcm));
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    ASSERT_EQ(1, producingThisFill(*e, pcm)) << "the resumed packet did not play";
    for (int i = 0; i < pl::kConcealQuanta; i++)
        EXPECT_EQ(1, producingThisFill(*e, pcm)) << "the second hold was short at fill " << i;
    EXPECT_EQ(0, producingThisFill(*e, pcm));
}

TEST(PlayoutEngine, DoesNotConcealForASpeakerStillPrebuffering) {
    // A spurt is silent for its first kPrebufferSamples by design. Concealing there would invent
    // audio before any arrived and hold a gate that was never open.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < pl::kConcealQuanta + 2; i++)
        EXPECT_EQ(0, producingThisFill(*e, pcm)) << "concealed while prebuffering, fill " << i;
}

TEST(PlayoutEngine, DoesNotConcealPastASpurtItsSenderClosed) {
    // Speech that ended normally is not a dropout. Concealing here would add a fade to the end of
    // every utterance.
    auto e = newEngine();
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 6; i++) ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "concealed past the terminator";
}

TEST(PlayoutEngine, AnExpiredHoldChargesItsGapOnce) {
    // stallQuanta_ keeps counting past kConcealQuanta, so the fill that gives up is not a
    // fresh gap.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 6 + pl::kConcealQuanta; i++) ASSERT_EQ(1, producingThisFill(*e, pcm));
    ASSERT_EQ(1, concealedOf(*e));
    for (int i = 0; i < 6; i++) producingThisFill(*e, pcm);
    EXPECT_EQ(1, concealedOf(*e)) << "the expired hold charged a second gap";
}

TEST(PlayoutEngine, RetiresAStalledSpeakerAfterItsHold) {
    // The retire clock is held at zero while concealment plays, so a sender that died mid-spurt
    // costs its slot the hold plus the short window, and no more.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 6 + pl::kConcealQuanta; i++) ASSERT_EQ(1, liveThisFill(*e, pcm));
    // Exact, not slack: the loop above ends on the last concealed fill, so the idle window starts
    // on the next one and retirement lands on its kRetireIdlePolls'th.
    for (int i = 0; i < pl::kRetireIdlePolls - 1; i++)
        ASSERT_EQ(1, liveThisFill(*e, pcm)) << "retired during its hold, fill " << i;
    EXPECT_EQ(0, liveThisFill(*e, pcm));
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

    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live))
        << "the terminator was discarded along with the payload";
}

TEST(PlayoutEngine, FillsALargerFrameFromMultiplePacketsInOneFill) {
    // Every other test runs at the production 480-sample frame, where a 480-sample packet
    // fills it in fillQuantum's decode loop's single iteration — pop one, decode, land exactly on
    // available() == samples, exit. fillQuantum takes its sample count per call, so a caller
    // asking for more is legal too; this covers that shape, where the loop pops and decodes twice
    // to fill one quantum — the "no more than one packet ahead" contract's other live path.
    constexpr int kTwoFrames = 960;
    auto e = PlayoutEngine::create(dumble::kSampleRate, kTwoFrames);
    ASSERT_TRUE(e);
    arm(*e, 1);
    std::vector<int16_t> pcm(kTwoFrames);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(pcm.data(), kTwoFrames, speaking.data(), &live));
}

TEST(PlayoutEngine, TenMillisecondSenderDrainsOneFramePerFill) {
    auto e = newEngine();
    // Six 10 ms packets is exactly kPrebufferSamples, and a frame is 10 ms, so the spurt must
    // come back out as six full fills — no packet stranded, none played twice. Terminated so the
    // count stays exactly six: an unterminated spurt earns kConcealQuanta of concealment after its
    // last packet, and a stranded seventh packet would hide inside that.
    arm(*e, 7, 6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, nullptr, 0, true));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, SixtyMillisecondSenderDrainsOneFramePerFill) {
    auto e = newEngine();
    // The same 60 ms as one packet: a fill must take a fraction of a packet and keep the rest.
    // Terminated for the same reason as the 10 ms case above.
    const std::vector<uint8_t> p = encode(6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, nullptr, 0, true));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, GoingIdleReArmsThePrebufferForTheNextSpurt) {
    auto e = newEngine();
    arm(*e, 7, 6);
    ASSERT_EQ(6 + pl::kConcealQuanta, drainSpurt(*e));
    // The gate re-arms on the fill after the hold expires: that is the first one to produce
    // nothing. Still well inside kRetireIdlePolls, so the slot is this speaker's.
    std::vector<int16_t> pcm(kFrame);
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "the next spurt played without prebuffering";
}

TEST(PlayoutEngine, PrebufferingDoesNotCountAsTheShortIdleWindow) {
    auto e = newEngine();
    // One packet, no terminator: produces nothing, but packets remain, so the long window applies
    // and the speaker must keep its slot while it waits for the rest of its spurt.
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < pl::kRetireIdlePolls + 5; i++)
        EXPECT_EQ(1, liveThisFill(*e, pcm)) << "retired while prebuffering at fill " << i;
}

TEST(PlayoutEngine, ASpurtStalledBelowThePrebufferEventuallyReleasesItsSlot) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < pl::kStallIdlePolls + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, AReclaimedSlotDoesNotInheritStrandedPackets) {
    // The stall window is the one retirement path that fires with packets still queued, so it is
    // what makes the reset load-bearing: without it the next sender to land on this slot inherits
    // a stranded packet and plays a syllable of someone else's voice.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < pl::kStallIdlePolls + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    ASSERT_EQ(0, live) << "the stalled speaker never released its slot";

    // Exactly kPrebufferSamples from a new sender, which must come back as exactly six fills. A
    // seventh would be session 7's stranded packet. Terminated so the hold cannot hide it.
    arm(*e, 8, 6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(8, nullptr, 0, true));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, AReclaimedSlotDecodesLikeAFreshOne) {
    // The decoder half of the same contract. libopus predicts across packets, so a slot handed
    // from one sender to the next must start over — otherwise session 8's first frame carries
    // the tail of session 7's voice. Compared against a fresh engine, which is the definition.
    //
    // One payload, offered to both: encode()'s encoder is a static and predicts across calls, so
    // re-encoding the same PCM for the second engine would compare two different packets.
    const std::vector<uint8_t> p = encode(1);
    const auto armWith = [&p](PlayoutEngine& e, int32_t session) {
        for (int i = 0; i < 6; i++)
            ASSERT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), false));
    };

    std::vector<int16_t> reused(kFrame), fresh(kFrame), pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;

    auto e = newEngine();
    armWith(*e, 7);
    ASSERT_EQ(6 + pl::kConcealQuanta, drainSpurt(*e));
    for (int i = 0; i < pl::kRetireIdlePolls + 1; i++) liveThisFill(*e, pcm);
    ASSERT_EQ(0, liveThisFill(*e, pcm)) << "session 7 never released its slot";
    armWith(*e, 8);
    ASSERT_EQ(1, e->fillQuantum(reused.data(), kFrame, speaking.data(), &live));

    auto clean = newEngine();
    armWith(*clean, 8);
    ASSERT_EQ(1, clean->fillQuantum(fresh.data(), kFrame, speaking.data(), &live));

    EXPECT_EQ(fresh, reused);
}

// Run under ThreadSanitizer: this is the only test that exercises the reader/playback split.
TEST(PlayoutEngine, SurvivesConcurrentOfferAndFill) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    std::atomic<bool> stop{false};
    // More sessions than kMaxSpeakers, so claim, refusal at the cap and retirement all churn
    // against the fill loop rather than the slot set settling and staying put.
    constexpr int kSessions = 3 * pl::kMaxSpeakers;
    std::thread reader([&] {
        for (int i = 0; !stop.load(std::memory_order_relaxed); i++)
            e->offer(int32_t(i % kSessions), p.data(), int(p.size()), i % 32 == 31);
    });
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    int64_t previousConcealed = 0;
    for (int i = 0; i < 5000; i++) {
        const int producing = e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
        // The invariants that must hold however the two threads interleave. A torn read of the
        // slot set shows up here as a producing count above the live count, or above the cap.
        ASSERT_GE(producing, 0);
        ASSERT_LE(producing, pl::kMaxSpeakers);
        ASSERT_GE(live, 0);
        ASSERT_LE(live, pl::kMaxSpeakers);
        for (int n = 0; n < producing; n++) {
            ASSERT_GE(speaking[n], 0);
            ASSERT_LT(speaking[n], kSessions) << "session outside the range the reader offers";
        }
        if (i % 100 == 0) {
            const PlayoutEngine::Stats stats = e->stats();
            ASSERT_GE(stats.speakers, 0);
            ASSERT_LE(stats.speakers, pl::kMaxSpeakers);
            for (int n = 0; n < stats.speakers; n++) ASSERT_GE(stats.depths[n], 0);
            ASSERT_GE(stats.concealedGaps, previousConcealed);
            previousConcealed = stats.concealedGaps;
        }
    }
    stop.store(true, std::memory_order_relaxed);
    reader.join();
}

// offer() is fed straight off the network, so its bytes are whatever a peer chose to send. Seeded
// rather than random so a failure reproduces: this is a regression guard, not a fuzzer.
TEST(PlayoutEngine, SurvivesArbitraryGarbageOnTheWire) {
    auto e = newEngine();
    std::mt19937 rng(0xD00Du);
    std::vector<uint8_t> buf(pl::kMaxPacketBytes + 64);
    std::vector<int16_t> out(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    int64_t lastDropped = 0, lastConcealed = 0;

    for (int i = 0; i < 8000; i++) {
        const int len = int(rng() % (pl::kMaxPacketBytes + 64));
        for (int b = 0; b < len; b++) buf[b] = uint8_t(rng());
        // Sessions past kMaxSpeakers so the cap refusal is on the hot path too.
        e->offer(int32_t(rng() % 80), buf.data(), len, (rng() & 7) == 0);
        if ((i & 15) == 0) {
            ASSERT_GE(e->fillQuantum(out.data(), kFrame, speaking.data(), &live), 0);
            ASSERT_GE(live, 0);
            ASSERT_LE(live, pl::kMaxSpeakers);
        }
        if ((i & 255) == 0) {
            const PlayoutEngine::Stats stats = e->stats();
            ASSERT_GE(stats.speakers, 0);
            ASSERT_LE(stats.speakers, pl::kMaxSpeakers);
            for (int s = 0; s < stats.speakers; s++)
                ASSERT_GE(stats.depths[s], 0) << "negative depth at " << s;
            ASSERT_GE(stats.droppedPackets, lastDropped);
            ASSERT_GE(stats.concealedGaps, lastConcealed);
            lastDropped = stats.droppedPackets;
            lastConcealed = stats.concealedGaps;
        }
    }
}

// The shape a lossy or hostile transport actually produces, which random bytes rarely reach: a
// real Opus header with the payload cut short, including the zero-length tag-only frame.
TEST(PlayoutEngine, SurvivesEveryTruncationOfARealPacket) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(2);
    std::vector<int16_t> out(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int cut = 0; cut <= int(p.size()); cut++) {
        e->offer(7, p.data(), cut, false);
        ASSERT_GE(e->fillQuantum(out.data(), kFrame, speaking.data(), &live), 0) << "cut=" << cut;
    }
}
