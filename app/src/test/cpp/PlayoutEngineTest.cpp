#include <gtest/gtest.h>
#include <algorithm>
#include <chrono>
#include <climits>
#include <atomic>
#include <random>
#include <thread>
#include <unordered_map>
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

// Packets of one quantum needed to clear a cold speaker's gate. Derived, not written as 8, so it
// cannot rot silently if kColdStartMillis moves.
constexpr int kColdStartPackets =
    (pl::kColdStartSamples + kFrame - 1) / kFrame;

// The engine's windows, as fills of one quantum: the unit the tests below count in.
constexpr int kConcealFills = pl::kConcealSamples / kFrame;
constexpr int kRetireIdleFills = pl::kRetireIdleSamples / kFrame;
constexpr int kStallIdleFills = pl::kStallIdleSamples / kFrame;

std::vector<uint8_t> encode(int tenMsUnits) {
    return dumble::testtone::encodeTone(tenMsUnits * 480);
}

std::unique_ptr<PlayoutEngine> newEngine() {
    auto e = PlayoutEngine::create(dumble::kSampleRate, kFrame);
    EXPECT_TRUE(e);
    return e;
}

// One 10 ms packet is one frame-number unit, so a contiguous run just increments. Per session,
// not one shared counter: a counter shared across senders advances on every offer() regardless of
// who it is for, so two interleaved senders would each see gaps in their own numbering and read as
// permanently discontinuous — exactly the condition the catch-up tests below need to tell apart
// from a genuine spurt opening.
std::unordered_map<int32_t, uint64_t> frameCursors;

uint64_t& frameFor(int32_t session) {
    return frameCursors.try_emplace(session, uint64_t(5000)).first->second;
}

void arm(PlayoutEngine& e, int32_t session, int count = kColdStartPackets) {
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < count; i++)
        EXPECT_EQ(pl::kOfferAccepted,
                  e.offer(session, p.data(), int(p.size()), frameFor(session)++, false));
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

// Arms one cold-start spurt for `session` and plays it out: the concealment tests below all start
// from a speaker that just went from producing to starved.
void playSpurt(PlayoutEngine& e, int32_t session, std::vector<int16_t>& pcm) {
    arm(e, session);
    for (int i = 0; i < kColdStartPackets; i++)
        ASSERT_EQ(1, producingThisFill(e, pcm)) << "real audio, fill " << i;
}

int64_t droppedOf(PlayoutEngine& e) { return e.stats().droppedPackets; }
int64_t concealedOf(PlayoutEngine& e) { return e.stats().concealedGaps; }

// One sender at 10 ms per packet, with its own contiguous frame-number cursor and a switchable
// signal. Silence is what the energy gate is waiting for, so a test that wants shrink to fire
// has to actually go quiet — a tone throughout is the control, not the setup.
class Talker {
public:
    explicit Talker(int32_t session) : session_(session) {}

    void sendTone(PlayoutEngine& e) { send(e, stream_.encode(dumble::testtone::tone(kFrame))); }
    void sendSilence(PlayoutEngine& e) { send(e, stream_.encode(dumble::testtone::silence(kFrame))); }

    /** Skips `units` frame-number units without sending, the way a sender's wall-clock counter
     *  runs on through a push-to-talk release. */
    void skip(int units) { frame_ += uint64_t(units); }

private:
    void send(PlayoutEngine& e, const std::vector<uint8_t>& p) {
        EXPECT_EQ(pl::kOfferAccepted, e.offer(session_, p.data(), int(p.size()), frame_++, false));
    }

    int32_t session_;
    uint64_t frame_ = 5000;
    dumble::testtone::Stream stream_;
};

int depthOf(PlayoutEngine& e) {
    const pl::PlayoutEngine::Stats stats = e.stats();
    return stats.speakers > 0 ? stats.depths[0] : 0;
}

// Holds depth constant: one packet in, one fill out, for `fills` fills.
void talkSteadily(PlayoutEngine& e, Talker& t, int fills, bool quiet) {
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < fills; i++) {
        quiet ? t.sendSilence(e) : t.sendTone(e);
        producingThisFill(e, pcm);
    }
}

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
    // produces nothing, and the loop must keep filling it.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, p.data(), int(p.size()), frameFor(3)++, false));
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers, -1);
    int32_t live = -1;
    EXPECT_EQ(0, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live));
    EXPECT_EQ(1, live);
}

TEST(PlayoutEngine, AFillLargerThanTheQuantumIsServedInWholeChunks) {
    // maxQuantumSamples sizes the engine's scratch, not the caller's request: a device callback
    // can ask for more than the engine was built for, and it is served as consecutive whole fills.
    auto e = newEngine();
    std::vector<int16_t> pcm(3 * kFrame, int16_t(0x7777));
    std::vector<int32_t> sessions(pl::kMaxSpeakers);
    int32_t live = 0;
    arm(*e, 1);
    // Terminated, so what is left of the spurt is exactly the packets not yet played.
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    // Three quanta in one call: every chunk must be written (the fill pattern is gone) and the
    // spurt must have advanced three quanta, not one.
    ASSERT_EQ(1, e->fillQuantum(pcm.data(), 3 * kFrame, sessions.data(), &live));
    for (int i = 0; i < 3 * kFrame; i += kFrame)
        EXPECT_NE(int16_t(0x7777), pcm[i]) << "chunk " << i / kFrame;
    EXPECT_EQ(1, sessions[0]);
    EXPECT_EQ(1, live);
    EXPECT_EQ(kColdStartPackets - 3, drainSpurt(*e));
}

TEST(PlayoutEngine, AFillOfZeroSamplesIsStillRefused) {
    // A 0 would read as "nobody is speaking" and mute a caller that sized its frame wrong.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame, int16_t(0x7777));
    std::vector<int32_t> sessions(pl::kMaxSpeakers);
    int32_t live = 7;
    EXPECT_EQ(pl::kErrorBufferTooSmall, e->fillQuantum(pcm.data(), 0, sessions.data(), &live));
    EXPECT_EQ(int16_t(0x7777), pcm[0]) << "a refused fill wrote into the caller's buffer";
    EXPECT_EQ(0, live);
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
    for (int i = 0; i < kColdStartPackets + kConcealFills + kRetireIdleFills + 1; i++)
        e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, ASessionReclaimsASlotAfterRetirement) {
    auto e = newEngine();
    arm(*e, 5);
    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < kColdStartPackets + kConcealFills + kRetireIdleFills + 1; i++)
        e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live);
    ASSERT_EQ(0, live);
    arm(*e, 5);
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live));
}

TEST(PlayoutEngine, CapsConcurrentSpeakers) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    for (int32_t s = 0; s < pl::kMaxSpeakers; s++)
        EXPECT_EQ(pl::kOfferAccepted, e->offer(s, p.data(), int(p.size()), frameFor(s)++, false));
    EXPECT_EQ(pl::kOfferSpeakerCap, e->offer(9999, p.data(), int(p.size()), frameFor(9999)++, false));
    // A capped session must not displace a live one.
    EXPECT_EQ(pl::kOfferAccepted, e->offer(0, p.data(), int(p.size()), frameFor(0)++, false));
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
    EXPECT_EQ(pl::kOfferMalformedPacket, e->offer(1, malformed, 2, frameFor(1)++, false));
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
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, good.data(), int(good.size()), frameFor(1)++, false));
}

TEST(PlayoutEngine, AMalformedFinalPacketStillEndsItsSpurt) {
    // is_terminator is a protobuf field, not part of opus_data, so a corrupt last packet still
    // ends the spurt. Lose the flag with the payload and this tail — one 10 ms packet, far below
    // the target — waits out kStallIdleSamples instead, and is never heard.
    auto e = newEngine();
    const std::vector<uint8_t> good = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, good.data(), int(good.size()), frameFor(3)++, false));
    const uint8_t malformed[2] = {0x03, 0x00};
    ASSERT_EQ(pl::kOfferMalformedPacket, e->offer(3, malformed, 2, frameFor(3)++, /*terminator=*/true));

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
    EXPECT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
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
    EXPECT_EQ(pl::kOfferPacketTooLarge, e->offer(1, huge.data(), int(huge.size()), frameFor(1)++, false));
    EXPECT_EQ(0, droppedOf(*e)) << "a refused payload was double-reported as a drop";
}

TEST(PlayoutEngine, CountsAPacketTheSpeakerCapRefused) {
    // No queue exists to charge it to, so the engine's own tally is the only place a capped
    // session's lost audio can show up at all.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    for (int32_t s = 0; s < pl::kMaxSpeakers; s++)
        ASSERT_EQ(pl::kOfferAccepted, e->offer(s, p.data(), int(p.size()), frameFor(s)++, false));
    ASSERT_EQ(0, droppedOf(*e));
    ASSERT_EQ(pl::kOfferSpeakerCap, e->offer(9999, p.data(), int(p.size()), frameFor(9999)++, false));
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
        ASSERT_EQ(pl::kOfferAccepted, e->offer(5, p.data(), int(p.size()), frameFor(5)++, false));
    ASSERT_EQ(1, droppedOf(*e));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < kStallIdleFills + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    ASSERT_EQ(0, live) << "the speaker never retired";
    EXPECT_EQ(1, droppedOf(*e)) << "retirement lost the tally, or counted it twice";
}

TEST(PlayoutEngine, CountsAPartialQuantumAsConcealment) {
    // A terminator opens the prebuffer gate immediately, which is what lets one packet play at
    // all here. Sub-quantum on purpose: 240 samples is a legal 5 ms Opus frame that decodes to
    // half the 480-sample quantum, so the rest is drain's zero padding — speech spliced with
    // silence.
    auto e = newEngine();
    const std::vector<uint8_t> p = dumble::testtone::encodeTone(240);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(2, p.data(), int(p.size()), frameFor(2)++, true));
    std::vector<int16_t> pcm(kFrame);
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(1, concealedOf(*e));
}

TEST(PlayoutEngine, CountsAMidSpurtStallAsConcealment) {
    // The gap a partial quantum cannot express: a speaker mid-sentence whose packets stop
    // arriving. Every fill of it produces nothing, so a count that only fires on a short fill
    // reports zero for the loudest dropout there is.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kFrame);
    ASSERT_EQ(kColdStartPackets + kConcealFills, drainSpurt(*e))
        << "the cold-start packets, then the hold";
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
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 12; i++) producingThisFill(*e, pcm);
    EXPECT_EQ(0, concealedOf(*e));
}

TEST(PlayoutEngine, DoesNotCountASpeakerStillPrebuffering) {
    // Quiet by design, not by accident: the gate is closed until the spurt reaches its target,
    // and the loop fills throughout. Charging those would swamp every real gap.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
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
        ASSERT_EQ(pl::kOfferAccepted, e->offer(session, p.data(), int(p.size()), frameFor(session)++, true));
        producingThisFill(*e, pcm);
        const int64_t now = concealedOf(*e);
        EXPECT_GE(now, previous);
        previous = now;
    }
    EXPECT_EQ(3, previous);
}

TEST(PlayoutEngine, ConcealsAMidSpurtStallForABoundedSpan) {
    // A speaker mid-sentence whose packets stop arriving: concealment keeps the fill producing,
    // bounded by kConcealSamples (the why lives on the constant).
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    for (int i = 0; i < kConcealFills; i++)
        EXPECT_EQ(1, producingThisFill(*e, pcm)) << "the hold ended early, at fill " << i;
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "the hold outlived kConcealSamples";
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
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(0, e->stats().depths[0]) << "the delayed packet waited out a second prebuffer";
}

TEST(PlayoutEngine, RealAudioReArmsTheHold) {
    // A spurt that survives a stall gets a full hold for the next one — the stallSamples_ invariant.
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    ASSERT_NO_FATAL_FAILURE(playSpurt(*e, 1, pcm));
    for (int i = 0; i < kConcealFills - 1; i++) ASSERT_EQ(1, producingThisFill(*e, pcm));
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
    ASSERT_EQ(1, producingThisFill(*e, pcm)) << "the resumed packet did not play";
    for (int i = 0; i < kConcealFills; i++)
        EXPECT_EQ(1, producingThisFill(*e, pcm)) << "the second hold was short at fill " << i;
    EXPECT_EQ(0, producingThisFill(*e, pcm));
}

TEST(PlayoutEngine, DoesNotConcealForASpeakerStillPrebuffering) {
    // A spurt is silent until it reaches its target by design. Concealing there would invent
    // audio before any arrived and hold a gate that was never open.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < kConcealFills + 2; i++)
        EXPECT_EQ(0, producingThisFill(*e, pcm)) << "concealed while prebuffering, fill " << i;
}

TEST(PlayoutEngine, DoesNotConcealPastASpurtItsSenderClosed) {
    // Speech that ended normally is not a dropout. Concealing here would add a fade to the end of
    // every utterance.
    auto e = newEngine();
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < kColdStartPackets; i++) ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "concealed past the terminator";
}

TEST(PlayoutEngine, AnExpiredHoldChargesItsGapOnce) {
    // stallSamples_ keeps counting past kConcealSamples, so the fill that gives up is not a fresh gap.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < kColdStartPackets + kConcealFills; i++)
        ASSERT_EQ(1, producingThisFill(*e, pcm));
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
    for (int i = 0; i < kColdStartPackets + kConcealFills; i++)
        ASSERT_EQ(1, liveThisFill(*e, pcm));
    // Exact, not slack: the loop above ends on the last concealed fill, so the idle window starts
    // on the next one and retirement lands on its kRetireIdleSamples'th.
    for (int i = 0; i < kRetireIdleFills - 1; i++)
        ASSERT_EQ(1, liveThisFill(*e, pcm)) << "retired during its hold, fill " << i;
    EXPECT_EQ(0, liveThisFill(*e, pcm));
}

TEST(PlayoutEngine, AnOversizedFinalPacketStillEndsItsSpurt) {
    // Same reasoning as the malformed case above: the payload cannot be queued, but the
    // terminator is a protobuf field, not part of it, and must still release the tail.
    auto e = newEngine();
    const std::vector<uint8_t> good = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(3, good.data(), int(good.size()), frameFor(3)++, false));
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    ASSERT_EQ(pl::kOfferPacketTooLarge,
              e->offer(3, huge.data(), int(huge.size()), frameFor(3)++, /*terminator=*/true));

    std::vector<int16_t> pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    EXPECT_EQ(1, e->fillQuantum(pcm.data(), kFrame, speaking.data(), &live))
        << "the terminator was discarded along with the payload";
}

TEST(PlayoutEngine, FillsALargerQuantumFromMultiplePacketsInOneFill) {
    // Every other test runs at the production 480-sample quantum, where a 480-sample packet
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
    // Six 10 ms packets, deliberately below the cold-start target: the terminator opens the gate
    // regardless, and the quantum is one frame, so the spurt must come back out as six full fills — no
    // packet stranded, none played twice. Terminated so the count stays exactly six: an
    // unterminated spurt earns kConcealSamples of concealment after its last packet, and a stranded
    // seventh packet would hide inside that.
    arm(*e, 7, 6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, nullptr, 0, frameFor(7)++, true));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, SixtyMillisecondSenderDrainsOneFramePerFill) {
    auto e = newEngine();
    // The same 60 ms as one packet: a fill must take a fraction of a packet and keep the rest.
    // Terminated for the same reason as the 10 ms case above.
    const std::vector<uint8_t> p = encode(6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), frameFor(7)++, false));
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, nullptr, 0, frameFor(7)++, true));
    EXPECT_EQ(6, drainSpurt(*e));
}

TEST(PlayoutEngine, GoingIdleReArmsThePrebufferForTheNextSpurt) {
    auto e = newEngine();
    arm(*e, 7, kColdStartPackets);
    ASSERT_EQ(kColdStartPackets + kConcealFills, drainSpurt(*e));
    // The gate re-arms on the fill after the hold expires: that is the first one to produce
    // nothing. Still well inside kRetireIdleSamples, so the slot is this speaker's.
    std::vector<int16_t> pcm(kFrame);
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), frameFor(7)++, false));
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "the next spurt played without prebuffering";
}

TEST(PlayoutEngine, PrebufferingDoesNotCountAsTheShortIdleWindow) {
    auto e = newEngine();
    // One packet, no terminator: produces nothing, but packets remain, so the long window applies
    // and the speaker must keep its slot while it waits for the rest of its spurt.
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), frameFor(7)++, false));
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < kRetireIdleFills + 5; i++)
        EXPECT_EQ(1, liveThisFill(*e, pcm)) << "retired while prebuffering at fill " << i;
}

TEST(PlayoutEngine, ASpurtStalledBelowThePrebufferEventuallyReleasesItsSlot) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), frameFor(7)++, false));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < kStallIdleFills + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    EXPECT_EQ(0, live);
}

TEST(PlayoutEngine, AReclaimedSlotDoesNotInheritStrandedPackets) {
    // The stall window is the one retirement path that fires with packets still queued, so it is
    // what makes the reset load-bearing: without it the next sender to land on this slot inherits
    // a stranded packet and plays a syllable of someone else's voice.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(7, p.data(), int(p.size()), frameFor(7)++, false));
    std::vector<int16_t> pcm(kFrame);
    int live = 1;
    for (int i = 0; i < kStallIdleFills + 2 && live != 0; i++) live = liveThisFill(*e, pcm);
    ASSERT_EQ(0, live) << "the stalled speaker never released its slot";

    // Below the cold-start target from a new sender, opened early by the terminator, which must
    // come back as exactly six fills. A seventh would be session 7's stranded packet. Terminated
    // so the hold cannot hide it.
    arm(*e, 8, 6);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(8, nullptr, 0, frameFor(8)++, true));
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
        for (int i = 0; i < kColdStartPackets; i++)
            ASSERT_EQ(pl::kOfferAccepted, e.offer(session, p.data(), int(p.size()), frameFor(session)++, false));
    };

    std::vector<int16_t> reused(kFrame), fresh(kFrame), pcm(kFrame);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;

    auto e = newEngine();
    armWith(*e, 7);
    ASSERT_EQ(kColdStartPackets + kConcealFills, drainSpurt(*e));
    for (int i = 0; i < kRetireIdleFills + 1; i++) liveThisFill(*e, pcm);
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
        for (int i = 0; !stop.load(std::memory_order_relaxed); i++) {
            const int32_t session = int32_t(i % kSessions);
            e->offer(session, p.data(), int(p.size()), frameFor(session)++, i % 32 == 31);
        }
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
        const int32_t session = int32_t(rng() % 80);
        e->offer(session, buf.data(), len, frameFor(session)++, (rng() & 7) == 0);
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
        e->offer(7, p.data(), cut, frameFor(7)++, false);
        ASSERT_GE(e->fillQuantum(out.data(), kFrame, speaking.data(), &live), 0) << "cut=" << cut;
    }
}

TEST(PlayoutEngine, AColdSpeakerWaitsOutTheColdStartTarget) {
    auto engine = newEngine();
    ASSERT_TRUE(engine);
    int32_t sessions[pl::kMaxSpeakers];
    int32_t live = 0;
    std::vector<int16_t> out(kFrame);
    const auto payload = dumble::testtone::encodeToneAlone(kFrame);

    // 80 ms of cold-start target, in 10 ms packets: the first seven produce nothing.
    for (int i = 0; i < 7; i++) {
        engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
        EXPECT_EQ(engine->fillQuantum(out.data(), kFrame, sessions, &live), 0)
            << "the gate opened before the cold-start target at packet " << i;
    }
    engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    EXPECT_EQ(engine->fillQuantum(out.data(), kFrame, sessions, &live), 1);
}

TEST(PlayoutEngine, TheWriteAheadIsAddedToEveryTarget) {
    auto engine = newEngine();
    ASSERT_TRUE(engine);
    // Two frames sit in the output sink ahead of playout, so the gate must hold two packets
    // longer than the cold-start target for the queue itself to hold that target once they drain.
    engine->setWriteAheadSamples(2 * kFrame);
    int32_t sessions[pl::kMaxSpeakers];
    int32_t live = 0;
    std::vector<int16_t> out(kFrame);
    const auto payload = dumble::testtone::encodeToneAlone(kFrame);
    for (int i = 0; i < kColdStartPackets + 1; i++) {
        engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
        EXPECT_EQ(engine->fillQuantum(out.data(), kFrame, sessions, &live), 0)
            << "the gate opened before target + write-ahead at packet " << i;
    }
    engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    EXPECT_EQ(engine->fillQuantum(out.data(), kFrame, sessions, &live), 1);
    EXPECT_EQ(engine->stats().targets[0], pl::kColdStartSamples + 2 * kFrame);
}

TEST(PlayoutEngine, TheTargetIsReportedPerSpeaker) {
    auto engine = newEngine();
    ASSERT_TRUE(engine);
    const auto payload = dumble::testtone::encodeToneAlone(kFrame);
    engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    const pl::PlayoutEngine::Stats stats = engine->stats();
    ASSERT_EQ(stats.speakers, 1);
    EXPECT_EQ(stats.targets[0], pl::kColdStartSamples);
}

TEST(PlayoutEngine, StatsReportWhoWasAudibleInTheLastFill) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    arm(*e, 1);
    const std::vector<uint8_t> p = encode(1);
    e->offer(2, p.data(), int(p.size()), frameFor(2)++, false);  // one packet in: still gated
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    const auto s = e->stats();
    ASSERT_EQ(2, s.speakers);
    for (int n = 0; n < s.speakers; n++)
        EXPECT_EQ(s.sessions[n] == 1, s.audible[n]) << "session " << s.sessions[n];
}

TEST(PlayoutEngine, ATerminatedSpeakerStaysAudibleWhileItsTailPlays) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    EXPECT_TRUE(e->stats().audible[0]);
    drainSpurt(*e);
    ASSERT_EQ(0, producingThisFill(*e, pcm));
    const auto s = e->stats();
    ASSERT_EQ(1, s.speakers) << "the slot is held through the idle window";
    EXPECT_FALSE(s.audible[0]);
}

TEST(PlayoutEngine, AReclaimedSlotStartsInaudible) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    drainSpurt(*e);
    for (int i = 0; i < kRetireIdleFills + 1; i++) producingThisFill(*e, pcm);
    ASSERT_EQ(0, e->stats().speakers) << "session 1 never retired";
    const std::vector<uint8_t> p = encode(1);
    e->offer(2, p.data(), int(p.size()), frameFor(2)++, false);
    const auto s = e->stats();
    ASSERT_EQ(1, s.speakers);
    EXPECT_FALSE(s.audible[0]);
}

TEST(PlayoutEngine, OutputDownAbandonsEveryQueueAndKeepsEveryEstimate) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    const auto payload = dumble::testtone::encodeToneAlone(kFrame);
    // Earn a target above the cold constant — two stalled bursts 600 ms apart, as in
    // ARetiredSpeakerKeepsItsEstimate — so a kept estimate is distinguishable from a fresh one.
    e->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++) e->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++) e->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    const int32_t earned = e->stats().targets[0];
    ASSERT_GT(earned, pl::kColdStartSamples) << "no histogram update landed";
    // Overflow the queue so there is a tally to harvest.
    for (int i = 0; i < 10; i++) e->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    const int64_t droppedBefore = droppedOf(*e);
    ASSERT_GT(droppedBefore, 0);

    e->setOutputDown(true);
    const auto down = e->stats();
    EXPECT_EQ(0, down.speakers);
    EXPECT_EQ(droppedBefore, down.droppedPackets) << "the tally must survive the reset";

    e->setOutputDown(false);
    e->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    const auto back = e->stats();
    ASSERT_EQ(1, back.speakers);
    EXPECT_EQ(earned, back.targets[0]) << "the estimate was not kept";
    EXPECT_EQ(0, producingThisFill(*e, pcm)) << "the old backlog played instead of a fresh prebuffer";
}

TEST(PlayoutEngine, StatsReportFillTimeSinceTheLastRead) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    arm(*e, 1);
    for (int i = 0; i < 4; i++) producingThisFill(*e, pcm);
    const auto s = e->stats();
    EXPECT_GT(s.fillMicrosMean, 0u);
    EXPECT_GE(s.fillMicrosMax, s.fillMicrosMean);
    EXPECT_EQ(0u, e->stats().fillMicrosMax) << "a read resets the window";
}

TEST(PlayoutEngine, ARealtimeFillThatFindsTheMutexHeldFallsSilentAndCountsIt) {
    auto e = newEngine();
    e->setRealtime(true);
    std::vector<int16_t> pcm(kFrame, int16_t(0x7777));
    std::vector<int32_t> s(pl::kMaxSpeakers);
    int32_t live = 0;
    arm(*e, 1);
    ASSERT_EQ(1, producingThisFill(*e, pcm));
    const int depthBefore = depthOf(*e);
    // Hold the mutex from another thread across one fill, the way a preempted offer() would.
    std::atomic<bool> release{false};
    std::thread holder([&] {
        e->holdMutexForTest([&] { while (!release.load()) std::this_thread::yield(); });
    });
    while (!e->mutexHeldForTest()) std::this_thread::yield();
    const auto t0 = std::chrono::steady_clock::now();
    const int producing = e->fillQuantum(pcm.data(), kFrame, s.data(), &live);
    const double us =
        std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count();
    release = true;
    holder.join();
    EXPECT_EQ(0, producing);
    EXPECT_LT(us, 1000.0);
    for (int i = 0; i < kFrame; i++) ASSERT_EQ(0, pcm[i]) << "sample " << i;
    EXPECT_EQ(1, live);
    EXPECT_EQ(1u, e->stats().contendedFills);
    EXPECT_EQ(depthBefore, depthOf(*e)) << "a contended fill popped a packet";
}

TEST(PlayoutEngine, ARealtimeFillWithTheMutexFreeMatchesBlockingMode) {
    auto a = newEngine(), b = newEngine();
    b->setRealtime(true);
    std::vector<int16_t> pa(kFrame), pb(kFrame);
    // One payload for both: encode()'s encoder predicts across calls.
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < kColdStartPackets; i++) {
        ASSERT_EQ(pl::kOfferAccepted, a->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
        ASSERT_EQ(pl::kOfferAccepted, b->offer(1, p.data(), int(p.size()), frameFor(1)++, false));
    }
    for (int i = 0; i < kColdStartPackets; i++) {
        ASSERT_EQ(producingThisFill(*a, pa), producingThisFill(*b, pb));
        ASSERT_EQ(pa, pb) << "fill " << i;
    }
    EXPECT_EQ(0u, b->stats().contendedFills);
}

TEST(PlayoutEngine, ASlotSurvivesItsEstimatorBeingEvicted) {
    auto e = newEngine();
    std::vector<int16_t> pcm(kFrame);
    const std::vector<uint8_t> p = encode(1);

    // Claimed while the downlink estimate is still empty, so this speaker's entry is cold.
    arm(*e, 1);
    ASSERT_GT(producingThisFill(*e, pcm), 0);

    // Give the downlink a target, so that anything claimed from here on seeds well above the cold
    // constant — that difference is what makes the assertion below able to fail.
    e->offer(2, p.data(), int(p.size()), frameFor(2)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++) e->offer(2, p.data(), int(p.size()), frameFor(2)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++) e->offer(2, p.data(), int(p.size()), frameFor(2)++, false);

    // Speaker 1's slot is still live and draining, but every arriving sender claims a table entry
    // before the speaker cap is consulted, and the table is only kEstimatorSlots deep. Enough
    // distinct senders and its estimate is evicted, leaving the slot's cached index on a stranger —
    // one that, unlike the evicted entry, carries a seeded target.
    for (int32_t s = 1000; s < 1000 + pl::kEstimatorSlots; s++)
        e->offer(s, p.data(), int(p.size()), frameFor(s)++, false);

    const pl::PlayoutEngine::Stats stats = e->stats();
    bool seen = false;
    for (int i = 0; i < stats.speakers; i++) {
        if (stats.sessions[i] != 1) continue;
        seen = true;
        // The index alone would resolve to the stranger's seeded estimate; the session check is
        // what turns that into a miss and the cold constant.
        EXPECT_EQ(stats.targets[i], pl::kColdStartSamples) << "read a stranger's estimate";
    }
    EXPECT_TRUE(seen) << "the speaker retired before the eviction could be observed";
    EXPECT_GE(producingThisFill(*e, pcm), 0);
}

TEST(PlayoutEngine, ARetiredSpeakerKeepsItsEstimate) {
    auto engine = newEngine();
    ASSERT_TRUE(engine);
    std::vector<int16_t> pcm(kFrame);
    const auto payload = dumble::testtone::encodeToneAlone(kFrame);

    // The engine stamps arrivals off CLOCK_BOOTTIME, so earning a target above the cold start
    // needs real elapsed time — two stalled bursts, 600 ms apart. The first sets the peak-hold
    // window, the second closes it. Contiguous frame numbers against a held baseline is exactly
    // what a delay spike looks like, which is the point: 1.2 s of sleep is what it costs to prove
    // the table survives retirement with a target that is measurably not the cold-start constant.
    engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++)
        engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    std::this_thread::sleep_for(std::chrono::milliseconds(600));
    for (int i = 0; i < 12; i++)
        engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);

    const int32_t earned = engine->stats().targets[0];
    ASSERT_GT(earned, pl::kColdStartSamples)
        << "no histogram update landed, so this test would pass on a cold estimator";

    // Drain, then go quiet until the slot retires and reset()s.
    for (int i = 0; i < 200; i++) producingThisFill(*engine, pcm);
    ASSERT_EQ(engine->stats().speakers, 0) << "the slot did not retire";

    // The same speaker returns. Their estimate is table state, not slot state — this is the
    // prototype's dropout, pinned.
    frameFor(1) += 3000;
    engine->offer(1, payload.data(), int(payload.size()), frameFor(1)++, false);
    EXPECT_EQ(engine->stats().targets[0], earned);
}

TEST(PlayoutEngine, DepthIsShedWhileTheSpeakerIsQuiet) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);

    // Open a spurt, then hand it a backlog on top of the target.
    for (int i = 0; i < 12; i++) t.sendTone(*engine);
    for (int i = 0; i < 12; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);
    const int opened = depthOf(*engine);
    ASSERT_GT(opened, 0);

    // Long enough for several cooldowns. Silence, so the energy gate opens.
    talkSteadily(*engine, t, 900, /*quiet=*/true);
    EXPECT_LT(depthOf(*engine), opened);
    // One shrink per kShrinkCooldownSamples of produced audio, and the gate-open fill counts: the
    // releases land at talk-fill 200, 401, 602 and 803. Asserted exactly, because "more than zero"
    // passes just as well with a cooldown of one, which would shed on every quiet fill.
    EXPECT_EQ(engine->stats().shrunkPackets, 4);
    EXPECT_EQ(engine->stats().droppedPackets, 0);
}

TEST(PlayoutEngine, NothingIsShedWhileTheSpeakerIsTalking) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 24; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);
    const int opened = depthOf(*engine);

    talkSteadily(*engine, t, 900, /*quiet=*/false);
    // The gate held: discarding a packet mid-speech is an audible splice, and libopus decodes the
    // frame after a discard against a stale predictor.
    EXPECT_EQ(engine->stats().shrunkPackets, 0);
    EXPECT_GE(depthOf(*engine), opened);
}

TEST(PlayoutEngine, AStallPastTheConcealHoldReturnsToTargetAtOnce) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 200; i++) {
        t.sendTone(*engine);
        producingThisFill(*engine, pcm);
    }
    const int32_t target = engine->stats().targets[0];

    // 300 ms of link stall: the conceal hold expires, the queue stays empty, and the slot retires.
    for (int i = 0; i < 40; i++) producingThisFill(*engine, pcm);

    // The burst. Frame numbers are contiguous across the stall, because the sender never stopped —
    // which is what marks this as stale continuation rather than a spurt opening.
    for (int i = 0; i < 30; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);

    EXPECT_LE(depthOf(*engine), target + pl::kCatchUpThresholdSamples);
    EXPECT_GT(engine->stats().catchUpPackets, 0);
    // Discarded on purpose, so it is not charged as loss.
    EXPECT_EQ(engine->stats().droppedPackets, 0);
}

TEST(PlayoutEngine, AFreshSpurtBurstKeepsItsOpening) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 200; i++) {
        t.sendTone(*engine);
        producingThisFill(*engine, pcm);
    }
    for (int i = 0; i < 40; i++) producingThisFill(*engine, pcm);

    // The sender was silent, so its wall-clock counter ran on. The burst that follows is the start
    // of a sentence and its oldest packet is the first syllable.
    t.skip(300);
    for (int i = 0; i < 30; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);

    EXPECT_EQ(engine->stats().catchUpPackets, 0);
    EXPECT_GT(depthOf(*engine), pl::kCatchUpThresholdSamples);
}

TEST(PlayoutEngine, AStallInsideTheConcealHoldKeepsItsStandingDelay) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);
    for (int i = 0; i < 200; i++) {
        t.sendTone(*engine);
        producingThisFill(*engine, pcm);
    }
    const int before = depthOf(*engine);

    // Fifteen fills with nothing arriving: the first seven drain what the gate had queued, then
    // eight starve and conceal — inside kConcealSamples, so the gate never closes and there is no
    // gate-open for the catch-up drop to fire at. The delay this leaves behind is real and comes
    // back only through shrink, at about 10 ms per second of quiet speech.
    for (int i = 0; i < 15; i++) producingThisFill(*engine, pcm);
    for (int i = 0; i < 15; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);

    EXPECT_EQ(engine->stats().catchUpPackets, 0);
    // Measured against where the spurt was sitting, not against the target: the stall's whole
    // duration is still in the queue, and nothing in this band can take it back out.
    EXPECT_GT(depthOf(*engine), before);
}

TEST(PlayoutEngine, DeliberateDiscardCountsSurviveRetirement) {
    auto engine = newEngine();
    Talker t(1);
    std::vector<int16_t> pcm(kFrame);

    // A backlog, then quiet speech long enough for the cooldown to release one shrink.
    for (int i = 0; i < 24; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);
    talkSteadily(*engine, t, 250, /*quiet=*/true);
    const int64_t shrunk = engine->stats().shrunkPackets;
    ASSERT_GT(shrunk, 0);

    // Retirement resets the queue, so the tally has to have moved to the engine before it does.
    for (int i = 0; i < 60; i++) producingThisFill(*engine, pcm);
    ASSERT_EQ(engine->stats().speakers, 0) << "the slot did not retire";
    EXPECT_EQ(engine->stats().shrunkPackets, shrunk) << "a retiring queue's shrink tally was lost";

    // The same for the catch-up tally. Frame numbers are contiguous across the stall, so the burst
    // is a resumption and the gate-open trims it.
    for (int i = 0; i < 30; i++) t.sendTone(*engine);
    ASSERT_GT(producingThisFill(*engine, pcm), 0);
    const int64_t caughtUp = engine->stats().catchUpPackets;
    ASSERT_GT(caughtUp, 0);
    for (int i = 0; i < 60; i++) producingThisFill(*engine, pcm);
    ASSERT_EQ(engine->stats().speakers, 0) << "the slot did not retire the second time";
    EXPECT_EQ(engine->stats().catchUpPackets, caughtUp)
        << "a retiring queue's catch-up tally was lost";
    EXPECT_EQ(engine->stats().shrunkPackets, shrunk);
}

// The engine's limits are sample counts, so a fill of one device burst — 128 samples on a Pixel
// 7a, 1088 on the emulator — must reach the same wall-clock outcome as today's 480, to within a
// fill. The engine is sized for 480 throughout: 1088 exercises the chunked path.
class PlayoutEngineQuantum : public ::testing::TestWithParam<int> {
protected:
    int quantum() const { return GetParam(); }
    std::unique_ptr<PlayoutEngine> engine() {
        auto e = PlayoutEngine::create(dumble::kSampleRate, kFrame);
        EXPECT_TRUE(e);
        return e;
    }
    int fill(PlayoutEngine& e, std::vector<int16_t>& pcm) {
        std::vector<int32_t> s(pl::kMaxSpeakers);
        return e.fillQuantum(pcm.data(), quantum(), s.data(), &live_);
    }
    int32_t live_ = 0;
};

TEST_P(PlayoutEngineQuantum, AStalledSpeakerIsConcealedForAboutOneHundredMilliseconds) {
    auto e = engine();
    std::vector<int16_t> pcm(quantum());
    arm(*e, 1);
    // Starved mid-spurt: the real audio plays, then concealment holds the gate for
    // kConcealSamples, then the speaker stops producing. Concealment is cut on libopus's grid, so
    // up to one grid of it can still be buffered when the hold expires, and plays out.
    int producingFills = 0;
    while (fill(*e, pcm) == 1 && producingFills < 100) producingFills++;
    const int real = kColdStartPackets * kFrame;
    EXPECT_GE(producingFills * quantum(), real + pl::kConcealSamples - quantum());
    EXPECT_LE(producingFills * quantum(),
              real + pl::kConcealSamples + quantum() + pl::kConcealGridSamples);
}

TEST_P(PlayoutEngineQuantum, ADrainedSpeakerRetiresAfterAboutOneHundredMilliseconds) {
    auto e = engine();
    std::vector<int16_t> pcm(quantum());
    arm(*e, 1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, frameFor(1)++, true));
    // The fill that first produces nothing opens the idle window; the slot goes with the fill
    // that reaches kRetireIdleSamples of it. Two fills of slack: the spurt ends somewhere inside
    // the first of those fills, and the window closes somewhere inside the last.
    while (fill(*e, pcm) == 1) {}
    int idleFills = 1;
    while (live_ > 0 && idleFills < 100) { fill(*e, pcm); idleFills++; }
    EXPECT_GE(idleFills * quantum(), pl::kRetireIdleSamples);
    EXPECT_LE(idleFills * quantum(), pl::kRetireIdleSamples + 2 * quantum());
}

INSTANTIATE_TEST_SUITE_P(Quantum, PlayoutEngineQuantum, ::testing::Values(128, 480, 1088));
