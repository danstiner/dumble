#include <gtest/gtest.h>
#include <cstdint>
#include <vector>
#include "core/PacketQueue.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::playout::PacketQueue;
namespace pl = dumble::playout;

// 20 ms at 48 kHz — the span the tests count in.
constexpr int kPacket = 960;

// 60 ms — the fixed margin the adaptive target replaced. Kept here, and only here, so the gate
// tests measure against something that does not move.
constexpr int kGateTarget = 2880;

// Payload bytes are arbitrary here: nothing in PacketQueue reads them, so a fill byte doubles as
// an identity tag and the whole suite runs without libopus.
struct Packet {
    std::vector<uint8_t> bytes;
    int samples = 0;
};

Packet packet(uint8_t tag, int samples = kPacket, int len = 40) {
    return Packet{std::vector<uint8_t>(size_t(len), tag), samples};
}

// frame_number counts 10 ms frames, so a 20 ms packet advances it by 2.
constexpr int kFrameSamples = pl::kFrameNumberMillis * pl::kSamplesPerMilli;  // 480
constexpr uint64_t kFramesPerPacket = kPacket / kFrameSamples;                 // 2

// A sender's frame clock. Threaded explicitly so a test can skip frames (loss) or jump them
// (a pause) without arithmetic at every call site — the same shape PlayoutEngineTest already uses.
struct FrameClock {
    uint64_t frames = 0;
    uint64_t next(uint64_t skipFrames = 0) {
        frames += skipFrames;
        const uint64_t at = frames;
        frames += kFramesPerPacket;
        return at;
    }
};

void offer(PacketQueue& q, const Packet& p, uint64_t frame, bool terminator = false) {
    q.offer(p.bytes.data(), int(p.bytes.size()), p.samples, terminator, frame);
}

// pop() answers a hole with 0 and `*concealSamples == kFrameSamples`: conceal that, then pop again.
// Reported as its own value so no test can read a hole-pop as an empty queue.
constexpr int kHolePop = -2;

int popTagHole(PacketQueue& q, int target, int* concealSamples, bool catchUpAllowed = false) {
    uint8_t out[pl::kMaxPacketBytes];
    *concealSamples = -1;
    const int n = q.pop(out, int(sizeof out), target, catchUpAllowed, concealSamples);
    if (*concealSamples > 0) {
        EXPECT_EQ(0, n) << "a hole-pop carries no packet";
        return kHolePop;
    }
    EXPECT_EQ(0, *concealSamples) << "pop must always write concealSamples";
    if (n <= 0) return -1;
    return out[0];
}

// Pops through a hole: hole-pops are counted into `holeFrames`, each checked to ask for exactly
// one frame, and the first packet or empty pop is returned.
int popThroughHole(PacketQueue& q, int target, int* holeFrames) {
    *holeFrames = 0;
    for (int i = 0; i < 2 * pl::kHighWaterSamples / kFrameSamples; i++) {
        int conceal = 0;
        const int tag = popTagHole(q, target, &conceal);
        if (tag != kHolePop) return tag;
        EXPECT_EQ(kFrameSamples, conceal) << "a hole-pop asks for exactly one frame";
        (*holeFrames)++;
    }
    ADD_FAILURE() << "hole-pops never ended";
    return -1;
}

// Wraps popTagHole for tests that never hit a hole; the assertion catches one if it appears.
int popTagAt(PacketQueue& q, int target, bool catchUpAllowed = false) {
    int conceal = 0;
    const int tag = popTagHole(q, target, &conceal, catchUpAllowed);
    EXPECT_NE(kHolePop, tag) << "a legacy test met a hole-pop";
    return tag;
}

// Most gate tests want a fixed reference rather than an estimate, and ask for it by name.
int popTag(PacketQueue& q) { return popTagAt(q, kGateTarget); }

// Fills the gate with `n` contiguous packets, tagged from `firstTag`.
void fillGate(PacketQueue& q, FrameClock& clock, uint8_t firstTag, int n = 3) {
    for (int i = 0; i < n; i++) offer(q, packet(uint8_t(firstTag + i)), clock.next());
}

// Enough packets to clear kGateTarget, tagged from `first`. Takes the caller's clock so a test
// spanning multiple spurts (see GoingIdleClearsTheTerminatorForTheNextSpurt) keeps one continuous
// sender timeline across them.
void arm(PacketQueue& q, FrameClock& clock, uint8_t first = 0) {
    const int needed = (kGateTarget + kPacket - 1) / kPacket;
    for (int i = 0; i < needed; i++) offer(q, packet(uint8_t(first + i)), clock.next());
}

int drainCount(PacketQueue& q) {
    int n = 0;
    while (popTag(q) >= 0) n++;
    return n;
}

}  // namespace

TEST(PacketQueue, StartsEmpty) {
    PacketQueue q;
    EXPECT_TRUE(q.empty());
    EXPECT_EQ(0, q.depthSamples());
}

TEST(PacketQueue, PopIsClosedUntilThePrebufferIsMet) {
    PacketQueue q;
    FrameClock clock;
    int queued = 0;
    for (uint8_t i = 0; queued + kPacket < kGateTarget; i++) {
        offer(q, packet(i), clock.next());
        queued += kPacket;
        EXPECT_EQ(-1, popTag(q)) << "gate opened at " << q.depthSamples() << " samples";
    }
    offer(q, packet(99), clock.next());
    ASSERT_GE(q.depthSamples(), kGateTarget);
    EXPECT_GE(popTag(q), 0);
}

TEST(PacketQueue, PopReturnsPacketsInArrivalOrder) {
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < 6; i++) offer(q, packet(uint8_t(10 + i)), clock.next());
    for (int i = 0; i < 6; i++) EXPECT_EQ(10 + i, popTag(q));
}

TEST(PacketQueue, PopReportsTheStoredLength) {
    PacketQueue q;
    FrameClock clock;
    for (int i = 0; i < 8; i++) offer(q, packet(uint8_t(i), kPacket, 7 + i), clock.next());
    uint8_t out[pl::kMaxPacketBytes];
    int hole = 0;
    EXPECT_EQ(7, q.pop(out, int(sizeof out), kGateTarget, false, &hole));
    EXPECT_EQ(8, q.pop(out, int(sizeof out), kGateTarget, false, &hole));
}

TEST(PacketQueue, ATerminatorOpensTheGateBelowThePrebuffer) {
    PacketQueue q;
    FrameClock clock;
    offer(q, packet(3), clock.next(), /*terminator=*/true);
    ASSERT_LT(q.depthSamples(), kGateTarget);
    EXPECT_EQ(3, popTag(q));
}

TEST(PacketQueue, WithoutATerminatorAShortSpurtStaysClosed) {
    PacketQueue q;
    FrameClock clock;
    offer(q, packet(3), clock.next());
    EXPECT_EQ(-1, popTag(q));
}

TEST(PacketQueue, TheGateStaysOpenAcrossAnEmptyPoolWhileTheSpurtRuns) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    EXPECT_GT(drainCount(q), 0);
    ASSERT_TRUE(q.empty());
    // Still mid-spurt: the decoder is producing, so endFill must not re-arm the gate.
    q.endFill(/*decoderProduced=*/true);
    offer(q, packet(42), clock.next());
    EXPECT_EQ(42, popTag(q));
}

TEST(PacketQueue, GoingIdleReArmsThePrebuffer) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    EXPECT_GT(drainCount(q), 0);
    q.endFill(/*decoderProduced=*/false);
    offer(q, packet(42), clock.next());
    EXPECT_EQ(-1, popTag(q)) << "gate stayed open into the next spurt";
}

TEST(PacketQueue, ReArmTrustsTheEmptinessSeenAtPopOverThePresent) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    EXPECT_GT(drainCount(q), 0);
    // The next spurt's first packet lands between the caller's last pop and its endFill — the
    // engine decodes other speakers in between. The gate must still re-arm: that packet is a new
    // spurt, not licence to keep playing.
    offer(q, packet(42), clock.next());
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(-1, popTag(q)) << "the late arrival rode the old spurt's gate past the prebuffer";
}

TEST(PacketQueue, ALateTerminatorLatchSurvivesTheReArm) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    EXPECT_GT(drainCount(q), 0);
    // A complete short spurt — packet plus terminator — lands in the same window. Unlike a bare
    // packet, the terminator is an explicit verdict that the spurt is whole: the latch must beat
    // the re-arm, or the spurt sits below the prebuffer forever and is reset away at retirement.
    offer(q, packet(42), clock.next(), /*terminator=*/true);
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(42, popTag(q)) << "endFill closed the gate over the terminator latch";
}

TEST(PacketQueue, EndFillDoesNotReArmWhilePacketsRemain) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock, 20);
    ASSERT_EQ(20, popTag(q));
    // Produced nothing this fill, but the pool is not drained — this is a spurt waiting on the
    // network, not one that ended.
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(21, popTag(q));
}

TEST(PacketQueue, TerminatorDoesNotReArmTheGateMidSpurt) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock, 30);
    ASSERT_EQ(30, popTag(q));
    offer(q, packet(90), clock.next(), /*terminator=*/true);
    q.endFill(/*decoderProduced=*/true);
    EXPECT_EQ(31, popTag(q));
}

TEST(PacketQueue, TheTailOfASpurtIsNotStranded) {
    PacketQueue q;
    FrameClock clock;
    offer(q, packet(1), clock.next());
    offer(q, packet(2), clock.next(), /*terminator=*/true);
    EXPECT_EQ(2, drainCount(q));
}

TEST(PacketQueue, HighWaterCapsTheQueueInSamples) {
    PacketQueue q;
    FrameClock clock;
    const int span = pl::kHighWaterSamples / 8;
    for (uint8_t i = 0; i < 20; i++) {
        offer(q, packet(i, span), clock.next());
        EXPECT_LE(q.depthSamples(), pl::kHighWaterSamples);
    }
    EXPECT_GT(q.droppedPackets(), 0) << "audio was thrown away with nothing to show for it";
}

TEST(PacketQueue, HighWaterDropsTheOldestFirst) {
    PacketQueue q;
    FrameClock clock;
    const int span = pl::kMaxPacketSamples;
    for (uint8_t i = 0; i < 8; i++) offer(q, packet(i, span), clock.next());
    // The survivors are the newest, so the first pop is not tag 0.
    EXPECT_GT(popTag(q), 0);
}

TEST(PacketQueue, SlotsCapTheQueueEvenBelowHighWater) {
    PacketQueue q;
    // Small spans so kHighWaterSamples cannot bind before kMaxQueuedPackets does.
    const int span = pl::kHighWaterSamples / (4 * pl::kMaxQueuedPackets);
    for (uint8_t i = 0; i < pl::kMaxQueuedPackets + 8; i++) offer(q, packet(i, span), uint64_t(i));
    EXPECT_EQ(8, q.droppedPackets());
    EXPECT_EQ(pl::kMaxQueuedPackets, drainCount(q));
}

#ifndef NDEBUG
// Slot narrows the span to uint16_t, and no caller can exceed the bound — PlayoutEngine measures
// with libopus, which cannot report past 120 ms at a rate it accepts. Debug builds assert the
// reasoning rather than branch on it; NDEBUG compiles the assert away and this test with it.
TEST(PacketQueueDeathTest, DebugBuildsRejectAPacketBreakingTheContract) {
    PacketQueue q;
    FrameClock clock;
    EXPECT_DEATH(offer(q, packet(5, pl::kMaxPacketSamples + 1), clock.next()),
                 "largest Opus packet");
    // Bytes without samples: PlayoutEngine sends an unmeasurable payload down as no payload.
    EXPECT_DEATH(offer(q, packet(5, /*samples=*/0), clock.next()), "bytes and samples, or neither");
}
#endif

// Unguarded, unlike the two above: this contract holds in release as well, being the only one
// whose consequence is corruption rather than a wrong answer.
TEST(PacketQueueDeathTest, AnOversizedPacketCrashesRatherThanOverrunningThePool) {
    PacketQueue q;
    const std::vector<uint8_t> big(pl::kMaxPacketBytes + 1, 7);
    EXPECT_DEATH(q.offer(big.data(), int(big.size()), kPacket, false, 0), "");
}

TEST(PacketQueue, TheLargestLegalSpanFits) {
    // The top of the range Slot's uint16_t has to hold. Bounding it is the caller's job, since the
    // caller is what measures the packet — see PlayoutEngine::offer.
    PacketQueue q;
    FrameClock clock;
    offer(q, packet(6, pl::kMaxPacketSamples), clock.next());
    EXPECT_EQ(pl::kMaxPacketSamples, q.depthSamples());
}

TEST(PacketQueue, ATagOnlyFrameIsNotQueuedButItsTerminatorIsHonoured) {
    PacketQueue q;
    FrameClock clock;
    offer(q, packet(4), clock.next());
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    EXPECT_EQ(1, drainCount(q));
}

TEST(PacketQueue, PopRefusesAnOutputSmallerThanThePacket) {
    // The engine's scratch is kMaxPacketBytes, so this cannot happen in production — but a
    // truncating pop would corrupt audio silently rather than fail, so it is pinned.
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    uint8_t tiny[1];
    int hole = 0;
    EXPECT_LT(q.pop(tiny, 1, kGateTarget, false, &hole), 0);
}

TEST(PacketQueue, QueuedSamplesTracksOffersAndPops) {
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    const int armed = q.depthSamples();
    EXPECT_EQ(armed, q.depthSamples());
    ASSERT_GE(popTag(q), 0);
    EXPECT_EQ(armed - kPacket, q.depthSamples());
    EXPECT_EQ(0, drainCount(q) >= 0 ? q.depthSamples() : -1);
    EXPECT_TRUE(q.empty());
}

TEST(PacketQueue, ResetDropsEverythingQueuedAndRearmsTheGate) {
    // The state a slot must not carry to its next sender. Armed and mid-spurt, so both halves are
    // live: packets are queued and the gate is open.
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    ASSERT_GE(q.depthSamples(), kGateTarget);
    ASSERT_GE(popTag(q), 0) << "the gate should be open before reset";

    q.reset();

    EXPECT_TRUE(q.empty());
    EXPECT_EQ(0, q.depthSamples());
    // The gate is armed again, so one packet is not enough to play.
    offer(q, packet(9), clock.next());
    EXPECT_EQ(-1, popTag(q)) << "reset left the prebuffer gate open";
}

TEST(PacketQueue, ResetClearsTheDropTally) {
    // The engine harvests the tally into its own total at retirement, so a slot that kept it
    // would bill the next sender for the last one's losses on every stats() read.
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < pl::kMaxQueuedPackets + 4; i++) offer(q, packet(i, 480), clock.next());
    ASSERT_GT(q.droppedPackets(), 0);
    q.reset();
    EXPECT_EQ(0, q.droppedPackets());
}

TEST(PacketQueue, ResetClearsTheSendersTerminator) {
    // reset() restores the just-constructed state whole.
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    ASSERT_FALSE(q.speaking());
    q.reset();
    arm(q, clock);
    ASSERT_GE(popTag(q), 0);
    EXPECT_TRUE(q.speaking()) << "reset kept the last sender's terminator";
}

TEST(PacketQueue, SpeakingIsFalseUntilTheGateOpens) {
    // A spurt still building its prebuffer is quiet by design. Counting those polls as dropouts
    // would swamp the metric with the one silence that is always expected.
    PacketQueue q;
    FrameClock clock;
    EXPECT_FALSE(q.speaking());
    offer(q, packet(1), clock.next());
    EXPECT_FALSE(q.speaking()) << "one packet is below kGateTarget";
    arm(q, clock, 2);
    ASSERT_GE(popTag(q), 0) << "the gate should have opened";
    EXPECT_TRUE(q.speaking());
}

TEST(PacketQueue, ATerminatedSpurtIsNotSpeaking) {
    // The other half: speech that ended normally must not read as a dropout, or the metric fires
    // once per utterance and means nothing.
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    ASSERT_GE(popTag(q), 0);
    ASSERT_TRUE(q.speaking());
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    EXPECT_FALSE(q.speaking());
}

TEST(PacketQueue, GoingIdleClearsTheTerminatorForTheNextSpurt) {
    // Both flags re-arm together. A terminated_ left set would make the next spurt's stall
    // invisible for the life of the slot.
    PacketQueue q;
    FrameClock clock;
    arm(q, clock);
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    ASSERT_EQ(kGateTarget / kPacket, drainCount(q));
    q.endFill(/*decoderProduced=*/false);
    arm(q, clock);
    ASSERT_GE(popTag(q), 0);
    EXPECT_TRUE(q.speaking());
}

TEST(PacketQueue, TheGateOpensAtTheTargetItIsGiven) {
    PacketQueue q;
    FrameClock clock;
    // One 20 ms packet, against a 20 ms target: enough.
    offer(q, packet(7), clock.next());
    EXPECT_EQ(popTagAt(q, kPacket), 7);

    PacketQueue tighter;
    FrameClock clock2;
    offer(tighter, packet(9), clock2.next());
    // The same packet against a 100 ms target is not enough, and the gate stays shut.
    EXPECT_EQ(popTagAt(tighter, 5 * kPacket), -1);
}

TEST(PacketQueue, CanShrinkRefusesWhenTheRemainderWouldFallShort) {
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i), clock.next());  // 80 ms
    // Dropping the 20 ms head leaves 60, which clears a 60 ms floor exactly.
    EXPECT_TRUE(q.canShrink(3 * kPacket));
    // It does not clear 61 ms, so no drop is permitted at that floor.
    EXPECT_FALSE(q.canShrink(3 * kPacket + 1));
}

TEST(PacketQueue, CanShrinkNeverUndershootsForALongSender) {
    PacketQueue q;
    FrameClock clock;
    // A 60 ms sender: three packets is 180 ms, and dropping one costs 60 at a stroke.
    for (uint8_t i = 0; i < 3; i++) offer(q, packet(i, 3 * kPacket), clock.next());
    EXPECT_TRUE(q.canShrink(2 * 3 * kPacket));
    // A floor a fixed 40 ms deadband would have cleared, but this sender's packet cannot.
    EXPECT_FALSE(q.canShrink(2 * 3 * kPacket + 1));
}

TEST(PacketQueue, ShrinkIsNotCountedAsLoss) {
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i), clock.next());
    ASSERT_TRUE(q.canShrink(3 * kPacket));
    q.shrink();
    EXPECT_EQ(q.shrunkPackets(), 1);
    EXPECT_EQ(q.droppedPackets(), 0);
    // The oldest went, not the newest.
    EXPECT_EQ(popTagAt(q, kPacket), 1);
}

TEST(PacketQueue, AnOrdinaryGateOpenTrimsNothing) {
    PacketQueue q;
    FrameClock clock;
    // Target plus one packet — the normal state at a gate-open.
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i), clock.next());
    EXPECT_EQ(popTagAt(q, 3 * kPacket, true), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, AnOverThresholdGateOpenTrimsToTheTarget) {
    PacketQueue q;
    FrameClock clock;
    // 320 ms queued against a 40 ms target: a stall's backlog.
    for (uint8_t i = 0; i < 16; i++) offer(q, packet(i), clock.next());
    const int target = 2 * kPacket;
    EXPECT_EQ(popTagAt(q, target, true), 14);
    // Everything but the newest two packets was discarded, and none of it counted as loss.
    EXPECT_EQ(q.catchUpPackets(), 14);
    EXPECT_EQ(q.droppedPackets(), 0);
    EXPECT_EQ(q.shrunkPackets(), 0);
}

TEST(PacketQueue, AFreshSpurtBurstKeepsItsOpening) {
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < 16; i++) offer(q, packet(i), clock.next());
    // catchUpAllowed false: the engine saw the sender's frame numbers jump, so this burst is a
    // spurt opening and its oldest packet is the first syllable.
    EXPECT_EQ(popTagAt(q, 2 * kPacket, false), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, TheTrimNeverUndershootsTheTarget) {
    PacketQueue q;
    FrameClock clock;
    // A 60 ms sender again, six packets, against a 40 ms target with a 100 ms threshold.
    for (uint8_t i = 0; i < 6; i++) offer(q, packet(i, 3 * kPacket), clock.next());
    const int target = 2 * kPacket;
    ASSERT_EQ(popTagAt(q, target, true), 5);
    // Only the last packet is left: dropping it would have left nothing to clear the target.
    EXPECT_EQ(popTagAt(q, target, true), -1);
}

TEST(PacketQueue, ResetClearsTheNewCounters) {
    PacketQueue q;
    FrameClock clock;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i), clock.next());
    q.shrink();
    q.reset();
    EXPECT_EQ(q.shrunkPackets(), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, AFullRingOpensTheGateEvenBelowTheTarget) {
    PacketQueue q;
    FrameClock clock;
    // A 10 ms sender against a target its ring cannot reach: 32 packets is 320 ms, the target is
    // 450. Without the full-ring check the gate never opens and every further packet is dropped as
    // overflow, which is silence for as long as the target stays high.
    const int tenMs = 480;
    for (int i = 0; i < pl::kMaxQueuedPackets; i++) offer(q, packet(uint8_t(i), tenMs), clock.next());
    ASSERT_LT(q.depthSamples(), pl::kMaxTargetMillis * pl::kSamplesPerMilli);
    EXPECT_EQ(popTagAt(q, pl::kMaxTargetMillis * pl::kSamplesPerMilli), 0);
}

TEST(PacketQueue, InOrderReportsNoHole) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    EXPECT_EQ(0, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole);
    EXPECT_EQ(1, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole);
    EXPECT_EQ(0, q.lostSamples());
}

TEST(PacketQueue, MissingPacketBetweenTwoQueuedOnesIsConcealedAFrameAtATime) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    // The sender's next packet is lost: skip one packet's worth of frames.
    offer(q, packet(2), clock.next(kFramesPerPacket));
    offer(q, packet(3), clock.next());
    offer(q, packet(4), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(0, frames);
    EXPECT_EQ(2, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(int(kFramesPerPacket), frames) << "one 20 ms packet is missing: two frames, then the packet";
    EXPECT_EQ(kPacket, q.lostSamples());
}

// The regression that matters most: frame_number is a wall clock that runs through silence.
TEST(PacketQueue, NewSpurtAfterAPauseIsNotLoss) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    offer(q, packet(9), clock.next(), /*terminator=*/true);
    int hole = -1;
    while (popTagHole(q, kGateTarget, &hole) >= 0) EXPECT_EQ(0, hole);
    q.endFill(/*decoderProduced=*/false);   // gate re-arms: the spurt is over
    clock.frames += 100 * kFramesPerPacket;   // two seconds of silence
    fillGate(q, clock, 20);
    EXPECT_EQ(20, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "a pause is not a gap to conceal";
    EXPECT_EQ(0, q.lostSamples());
}

// The engine's stall path already concealed this gap; concealing it again would double it.
TEST(PacketQueue, GapAcrossAnEmptyPopIsNotReported) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    EXPECT_EQ(-1, popTagHole(q, kGateTarget, &hole)) << "queue drained";
    clock.frames += 5 * kFramesPerPacket;     // the stall the engine concealed
    fillGate(q, clock, 10);
    EXPECT_EQ(10, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole);
}

TEST(PacketQueue, ShrinkLeavesNoHoleBehind) {
    PacketQueue q; FrameClock clock;
    for (int i = 0; i < 6; i++) offer(q, packet(uint8_t(i)), clock.next());
    int hole = -1;
    EXPECT_EQ(0, popTagHole(q, kGateTarget, &hole));
    q.shrink();                              // packet 1 is deliberately discarded
    EXPECT_EQ(2, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "shedding delay must not re-inject it as concealment";
    EXPECT_EQ(0, q.lostSamples());
}

// A hole beside a deliberate discard is still the network's: tallied, or a lossy link would read
// clean whenever shrink landed next to the loss. Not concealed, since a discard is not a splice.
TEST(PacketQueue, AHoleBeforeAShrunkPacketIsCountedNotConcealed) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(1), clock.next(kFramesPerPacket));   // one packet lost before it
    offer(q, packet(2), clock.next());
    offer(q, packet(3), clock.next());
    int hole = -1;
    EXPECT_EQ(0, popTagHole(q, kGateTarget, &hole));
    q.shrink();                                          // packet 1 discarded, the hole with it
    EXPECT_EQ(kPacket, q.lostSamples()) << "the hole before the discarded packet";
    EXPECT_EQ(2, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "nothing to conceal: a discard is not a splice";
}

// Past the fade, at target: the break has been heard and silence buys nothing, so the spurt
// re-anchors — the cursor jumps to the head and the rest of the gap is tallied, not concealed.
TEST(PacketQueue, AHolePastTheFadeJumpsWhenTheQueueIsAtTarget) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    const uint64_t skip = 30;                              // 300 ms lost
    offer(q, packet(1), clock.next(skip));
    offer(q, packet(2), clock.next());
    offer(q, packet(3), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kPacket, &frames));    // three packets queued: at target
    EXPECT_EQ(1, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames) << "the fade, then the jump";
    EXPECT_EQ(int64_t(skip) * kFrameSamples, q.lostSamples()) << "concealed and skipped, both counted";
}

// Below target every concealed frame lets a frame of new audio arrive, buying back the margin
// the loss took, so the hole is concealed to the head.
TEST(PacketQueue, AHolePastTheFadeKeepsConcealingBelowTarget) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    const uint64_t skip = 30;
    offer(q, packet(1), clock.next(skip));
    offer(q, packet(2), clock.next());
    offer(q, packet(3), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kGateTarget, &frames));  // gate opens at kGateTarget, latches
    EXPECT_EQ(1, popThroughHole(q, 10 * kPacket, &frames)); // two packets queued, target far above
    EXPECT_EQ(int(skip), frames) << "the whole gap, one frame per pop";
    EXPECT_EQ(int64_t(skip) * kFrameSamples, q.lostSamples());
}

// Arrivals during a hole can lift the queue to target mid-way; from then on the budget is the
// fade, already spent, so the next pop jumps.
TEST(PacketQueue, TheBudgetFlipsToTheFadeWhenArrivalsCrossTarget) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(1), clock.next(30));
    offer(q, packet(2), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kGateTarget, &frames));
    int conceal = 0;
    const int target = 4 * kPacket;                        // two packets queued: below it
    for (int i = 0; i < 12; i++) EXPECT_EQ(kHolePop, popTagHole(q, target, &conceal)) << "frame " << i;
    offer(q, packet(3), clock.next());
    offer(q, packet(4), clock.next());                     // four packets queued: at target
    EXPECT_EQ(1, popTagHole(q, target, &conceal)) << "twelve frames exceed the fade: jump";
    EXPECT_EQ(int64_t(30) * kFrameSamples, q.lostSamples());
}

// A hostile sender can jump its counter by anything; the tally must not follow it there, and a
// slot must not sit in concealment forever: below target the hole is concealed for the ring's
// capacity at most, then the cursor jumps.
TEST(PacketQueue, LossPerGapIsBoundedByTheBufferBelowTarget) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    offer(q, packet(9), clock.next(1u << 30));
    int frames = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popThroughHole(q, kGateTarget, &frames), 0);
    EXPECT_EQ(9, popThroughHole(q, kGateTarget, &frames)) << "played, not refused: it is ahead";
    EXPECT_EQ(pl::kHighWaterSamples / kFrameSamples, frames) << "the ring's capacity, then the jump";
    EXPECT_EQ(pl::kHighWaterSamples, q.lostSamples()) << "concealed frames and skipped remainder together";
}

TEST(PacketQueue, LossPerGapIsBoundedByTheBufferAtTarget) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    offer(q, packet(9), clock.next(1u << 30));
    int frames = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popThroughHole(q, /*target=*/0, &frames), 0);
    EXPECT_EQ(9, popThroughHole(q, /*target=*/0, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames) << "the fade, then the jump";
    EXPECT_EQ(pl::kHighWaterSamples, q.lostSamples());
}

// The counter is per hole: a packet pop resets it, so the second hole in a spurt gets its own fade.
TEST(PacketQueue, TwoHolesInOneSpurtEachGetTheFade) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(1), clock.next(30));
    offer(q, packet(2), clock.next());
    offer(q, packet(3), clock.next(30));
    offer(q, packet(4), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(1, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames);
    EXPECT_EQ(2, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(0, frames);
    EXPECT_EQ(3, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames) << "a fresh fade for the second hole";
}

// A discard mid-hole does not reset the counter: the fade already played, so a jump right after
// still has it in front. Shrink can land here because quiet() is frozen through a hole.
TEST(PacketQueue, HoleFramesSurviveADiscard) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(1), clock.next(30));
    offer(q, packet(2), clock.next(30));
    offer(q, packet(3), clock.next());
    offer(q, packet(4), clock.next());
    int conceal = 0;
    EXPECT_EQ(0, popTagHole(q, kPacket, &conceal));
    for (int i = 0; i < 5; i++) EXPECT_EQ(kHolePop, popTagHole(q, kPacket, &conceal));
    q.shrink();                              // discards packet 1, the one the hole was heading for
    int frames = -1;
    EXPECT_EQ(2, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples - 5, frames)
        << "the five frames before the discard count against this hole's fade";
    EXPECT_EQ(pl::kHighWaterSamples, q.lostSamples())
        << "5 + 25 + 5 + 25 frames: concealed, discarded gap, concealed, skipped";
}

// A hole in front of the sender's terminator is concealed to it; the terminator then ends
// continuity as it always did, so the next spurt's restart is not a hole.
TEST(PacketQueue, AHoleInFrontOfATerminatorIsConcealedThenContinuityEnds) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int frames = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popThroughHole(q, kGateTarget, &frames), 0);
    clock.next();                            // lost
    offer(q, packet(9), clock.next(), /*terminator=*/true);
    EXPECT_EQ(9, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(int(kFramesPerPacket), frames);
    FrameClock restarted;
    fillGate(q, restarted, 20);
    EXPECT_EQ(20, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(0, frames) << "continuity ended at the terminator";
}

// A hole-pop is not an empty pop: the engine's endFill after one must not re-arm the gate, or a
// hole would cost a second prebuffer.
TEST(PacketQueue, AHolePopLeavesTheGateOpen) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int frames = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popThroughHole(q, kGateTarget, &frames), 0);
    clock.next();                            // lost
    offer(q, packet(9), clock.next());
    offer(q, packet(10), clock.next());
    int conceal = 0;
    EXPECT_EQ(kHolePop, popTagHole(q, kGateTarget, &conceal));
    q.endFill(/*decoderProduced=*/false);
    EXPECT_TRUE(q.gateOpen()) << "a hole-pop must not read as the queue having drained";
    EXPECT_EQ(9, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(1, frames) << "one frame was already concealed before endFill";
}

TEST(PacketQueue, PacketBehindTheCursorIsDroppedAndCounted) {
    PacketQueue q; FrameClock clock;
    const uint64_t f0 = clock.next();
    const uint64_t f1 = clock.next();
    offer(q, packet(0), f0);
    offer(q, packet(2), clock.next());
    offer(q, packet(3), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kGateTarget, &frames));
    offer(q, packet(1), f1);                 // the straggler, now behind the packet queued last
    EXPECT_EQ(1, q.outOfOrderPackets());
    EXPECT_EQ(2, popThroughHole(q, kGateTarget, &frames)) << "the straggler must not be replayed";
    EXPECT_EQ(int(kFramesPerPacket), frames) << "its slot is a hole, concealed";
}

// Unlike the test above, nothing is left queued to catch this via the tail: the ring drains
// through delivery (not an empty pop, so the cursor stays live), and only the cursor check itself
// can reject a straggler arriving after that.
TEST(PacketQueue, StragglerOnADrainedRingIsCaughtByTheCursor) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    // Ring is empty, cursor is live at frame 6 (three 2-frame packets delivered from frame 0).
    offer(q, packet(9), 2);                  // long-lost straggler, inside the spurt, behind the cursor
    EXPECT_EQ(1, q.outOfOrderPackets());
    offer(q, packet(8), 4);                  // a replay of the packet just played: ends exactly at the cursor
    EXPECT_EQ(2, q.outOfOrderPackets());
}

// Humla pads a terminator to a whole packet without advancing its counter, so the terminator's
// frame number starts inside the packet before it. Real speech: it plays, and reports no hole.
TEST(PacketQueue, ATerminatorOverlappingThePacketBeforeItStillPlays) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    offer(q, packet(9), clock.frames - 1, /*terminator=*/true);
    EXPECT_EQ(0, q.outOfOrderPackets());
    EXPECT_EQ(9, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole);
    EXPECT_EQ(0, q.lostSamples());
}

TEST(PacketQueue, DuplicateFrameNumberIsDropped) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    const uint64_t f1 = clock.next();
    offer(q, packet(1), f1);
    offer(q, packet(9), f1);                 // stamped like the packet before it
    EXPECT_EQ(1, q.outOfOrderPackets());
    offer(q, packet(2), clock.next());
    int hole = -1;
    EXPECT_EQ(0, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(1, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(2, popTagHole(q, kGateTarget, &hole)) << "the duplicate was never queued";
}

// The commonest loss there is: at the ~30 ms target the queue holds about two packets, so an
// isolated loss drains it. The cursor must survive the ring going empty without a pop seeing it.
TEST(PacketQueue, LossAcrossAMomentarilyEmptyRingIsStillReported) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    // Ring is empty but no pop has *found* it empty, so continuity is intact.
    clock.next();                            // this packet is lost
    offer(q, packet(9), clock.next());
    offer(q, packet(10), clock.next());
    offer(q, packet(11), clock.next());
    int frames = -1;
    EXPECT_EQ(9, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(int(kFramesPerPacket), frames);
    EXPECT_EQ(kPacket, q.lostSamples());
}

// The frame is kept as 32 bits and compared by signed difference, so a sender's counter crossing
// 2^32 is an ordinary advance.
TEST(PacketQueue, TheFrameClockWrapsWithoutRefusingOrReportingAHole) {
    PacketQueue q; FrameClock clock;
    clock.frames = uint64_t(UINT32_MAX) - 3;
    int hole = -1;
    for (int i = 0; i < 8; i++) {
        offer(q, packet(uint8_t(i)), clock.next());
        EXPECT_EQ(i, popTagHole(q, /*target=*/0, &hole));
        EXPECT_EQ(0, hole) << "at packet " << i;
    }
    EXPECT_EQ(0, q.outOfOrderPackets());
    EXPECT_EQ(0, q.lostSamples());
}

// The pop loop can exit on a satisfied decoder rather than an empty queue, so a re-key inside the
// drain window reaches the next pop without an intervening empty pop.
TEST(PacketQueue, QuickReKeyAfterATerminatorIsNotLoss) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    offer(q, packet(3), clock.next(), /*terminator=*/true);
    int hole = -1;
    for (int i = 0; i < 4; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    // No empty pop, no endFill: the next spurt starts while the queue merely happens to be empty.
    clock.frames += 10 * kFramesPerPacket;     // 200 ms of push-to-talk pause
    fillGate(q, clock, 20);
    EXPECT_EQ(20, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "the terminator ended continuity; the pause is not a gap";
    EXPECT_EQ(0, q.lostSamples());
}

// The terminator's own entry is still queued -- behind packet 3, not drained -- when the next
// spurt's packet lands, so no empty pop ever fires. Continuity ends where the flag is, not where
// the ring happens to drain.
TEST(PacketQueue, TerminatorStillQueuedBehindTheNextSpurtIsNotLoss) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0, 4);                                 // 4 contiguous packets
    offer(q, packet(4), clock.next(), /*terminator=*/true);   // terminator, carries its own audio
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);  // drain 3 of 5
    EXPECT_EQ(0, hole);
    // 80 ms sender pause (8 frames) before the next spurt's first packet -- offered
    // while packet 3 and the terminator are both still queued.
    offer(q, packet(20), clock.next(8));
    EXPECT_EQ(3, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "contiguous with the run so far";
    EXPECT_EQ(4, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "the terminator itself, still contiguous";
    EXPECT_EQ(20, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "the terminator ended continuity even though its own entry outlived "
                          "the pop that delivered it";
    EXPECT_EQ(0, q.lostSamples());
}

// mumble-web restarts its counter at 0 every spurt and ends each with a payload-free terminator.
// With the old spurt still queued, the new one is behind everything by frame number and must
// play anyway: the terminator ended ordering.
TEST(PacketQueue, ASpurtRestartingAtZeroBehindATerminatorPlays) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    FrameClock restarted;
    fillGate(q, restarted, 20);
    EXPECT_EQ(0, q.outOfOrderPackets());
    int hole = -1;
    for (int tag : {0, 1, 2, 20, 21, 22}) {
        EXPECT_EQ(tag, popTagHole(q, kGateTarget, &hole));
        EXPECT_EQ(0, hole) << "at packet " << tag;
    }
    EXPECT_EQ(0, q.lostSamples());
}

// The case above never popped before the terminator, so the cursor was never live. Here playout
// is under way -- some of the old spurt already delivered, its cursor live -- when the terminator
// lands and the restart arrives behind it, still queued. Every restart packet is behind the live
// cursor by frame number; none may be refused, and none may charge a hole. This is the sequence a
// mumble-web spurt starting within a queue depth of the last one produces, and the reason the
// ordering checks stand down while a terminator is queued rather than only when the tail is one.
TEST(PacketQueue, ARestartBehindALiveCursorAndAQueuedTerminatorPlaysWhole) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0, 5);                                 // frames 0,2,4,6,8 queued
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);  // cursor now live
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());   // flags the frame-8 tail
    FrameClock restarted;
    for (uint8_t t = 20; t < 24; t++) offer(q, packet(t), restarted.next());
    EXPECT_EQ(0, q.outOfOrderPackets()) << "not one restart packet may be refused";
    for (int tag : {3, 4, 20, 21, 22, 23}) {
        EXPECT_EQ(tag, popTagHole(q, kGateTarget, &hole));
        EXPECT_EQ(0, hole) << "no hole at packet " << tag;
    }
    EXPECT_EQ(0, q.lostSamples());
}

// Two terminators queued at once: spurt A's, then spurt B's restart and terminator behind it.
// Popping A's terminator must not wake the checks while B's is still queued, or spurt C's restart
// at zero is refused behind B's live cursor. Two short mumble-web spurts inside one queue depth.
TEST(PacketQueue, PoppingOneTerminatorDoesNotWakeTheChecksWhileAnotherIsQueued) {
    PacketQueue q; FrameClock a;
    fillGate(q, a, 0);                                        // A: frames 0,2,4
    offer(q, packet(3), a.next(), /*terminator=*/true);       // A's terminator, with audio
    int hole = -1;
    for (int i = 0; i < 2; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);   // A0, A1
    FrameClock b;
    fillGate(q, b, 20);                                       // B restarts at 0: frames 0,2,4
    q.offer(nullptr, 0, 0, /*terminator=*/true, b.next());    // B's terminator stamps its tail
    for (int tag : {2, 3, 20, 21}) EXPECT_EQ(tag, popTagHole(q, kGateTarget, &hole));   // cursor live in B
    FrameClock c;
    fillGate(q, c, 40);                                       // C restarts at 0, behind B's cursor
    EXPECT_EQ(0, q.outOfOrderPackets()) << "C's head was refused behind B's cursor";
    for (int tag : {22, 40, 41, 42}) {
        EXPECT_EQ(tag, popTagHole(q, kGateTarget, &hole));
        EXPECT_EQ(0, hole) << "no hole at packet " << tag;
    }
    EXPECT_EQ(0, q.lostSamples());
}

// mumble-web's empty terminator can be lost on UDP. Its restart then lands behind a tail that is
// still queued, and cannot be a straggler of that spurt: a straggler comes after the frame the spurt
// began on, and this packet is stamped with it. Old spurt partly played, cursor live.
TEST(PacketQueue, ARestartAfterAShortSpurtWithItsTerminatorLostPlaysWhole) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);                                    // A: frames 0,2,4, no terminator
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kGateTarget, &frames));    // cursor live at 2
    FrameClock restarted;
    fillGate(q, restarted, 20);                               // B restarts at 0
    EXPECT_EQ(0, q.outOfOrderPackets()) << "the restart was refused as a straggler";
    for (int tag : {1, 2, 20, 21, 22}) {
        EXPECT_EQ(tag, popThroughHole(q, kGateTarget, &frames));
        EXPECT_EQ(0, frames) << "no hole at packet " << tag;
    }
    EXPECT_EQ(0, q.lostSamples());
}

// Same, with the old spurt not played at all: nothing but the tail says where it ends.
TEST(PacketQueue, ARestartAfterAnUnplayedSpurtWithItsTerminatorLostPlaysWhole) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    FrameClock restarted;
    fillGate(q, restarted, 20);
    EXPECT_EQ(0, q.outOfOrderPackets());
    int frames = -1;
    for (int tag : {0, 1, 2, 20, 21, 22}) EXPECT_EQ(tag, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(0, q.lostSamples());
}

// Same, with the ring drained by delivery: only the live cursor says the spurt is still open, and
// the restart must clear it rather than be refused behind it.
TEST(PacketQueue, ARestartOntoARingDrainedByDeliveryPlays) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int frames = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popThroughHole(q, kGateTarget, &frames), 0);
    FrameClock restarted;
    fillGate(q, restarted, 20);                               // B at 0: behind the cursor, on the start
    EXPECT_EQ(0, q.outOfOrderPackets());
    for (int tag : {20, 21, 22}) {
        EXPECT_EQ(tag, popThroughHole(q, kGateTarget, &frames));
        EXPECT_EQ(0, frames) << "no hole at packet " << tag;
    }
    EXPECT_EQ(0, q.lostSamples());
}

// A straggler of the spurt in progress is refused however far behind the tail it lands: the spurt
// began before it, so it is no restart. The gap in front of the packet it fell out of is a hole.
TEST(PacketQueue, AStragglerInsideTheSpurtIsStillRefusedHoweverLate) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(1), clock.next());
    int frames = -1;
    EXPECT_EQ(0, popThroughHole(q, kPacket, &frames));
    offer(q, packet(2), clock.next(70));                      // the sender is 700 ms on
    offer(q, packet(9), 4);                                   // the packet lost after 1, 700 ms late
    EXPECT_EQ(1, q.outOfOrderPackets());
    EXPECT_EQ(1, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(2, popThroughHole(q, kPacket, &frames)) << "the straggler was not stored";
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames) << "a hole before packet 2, not a spurt boundary";
}

// A straggler of an earlier spurt lands behind the current spurt's start and is refused however
// late: only a packet stamped with the start itself is a restart.
TEST(PacketQueue, AStragglerFromAnEarlierSpurtIsRefusedHoweverLate) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);                                    // A: 0,2,4
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    offer(q, packet(20), clock.next(52));                     // B from frame 60
    offer(q, packet(21), clock.next());
    offer(q, packet(22), clock.next());
    offer(q, packet(9), 2);                                   // A's packet 1 again, 600 ms late
    EXPECT_EQ(1, q.outOfOrderPackets());
    int frames = -1;
    for (int tag : {0, 1, 2, 20, 21, 22}) {
        EXPECT_EQ(tag, popThroughHole(q, kGateTarget, &frames));
        EXPECT_EQ(0, frames) << "no hole at packet " << tag;
    }
}

// The residual: a spurt whose leading packets were lost began, for the queue, at the first that
// arrived, so the next restart is refused for as many packets as that spurt lost at its head.
TEST(PacketQueue, ARestartAfterASpurtWithLeadingLossLosesOnlyThoseFrames) {
    PacketQueue q; FrameClock clock;
    clock.next();                                             // A's first packet, lost
    fillGate(q, clock, 1);                                    // A: frames 2,4,6, no terminator
    FrameClock restarted;
    fillGate(q, restarted, 20);                               // B restarts at 0
    EXPECT_EQ(1, q.outOfOrderPackets()) << "B's packet at 0 is behind A's start of 2";
    int frames = -1;
    for (int tag : {1, 2, 3, 21, 22}) EXPECT_EQ(tag, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(0, q.lostSamples());
}

// Humla pads its terminator without advancing the counter; on a one-frame spurt it lands on the
// spurt's own start frame. The flag says it ends this spurt, so it is no restart.
TEST(PacketQueue, APaddedTerminatorOnAOneFrameSpurtIsNotARestart) {
    PacketQueue q;
    offer(q, packet(7, kFrameSamples), 10);                   // a 10 ms packet at frame 10
    offer(q, packet(8, kFrameSamples), 10, /*terminator=*/true);   // the pad, stamped alike
    EXPECT_EQ(1, q.outOfOrderPackets()) << "the pad duplicates the spurt, it does not restart it";
    offer(q, packet(9, kFrameSamples), 11);                   // the next spurt
    EXPECT_EQ(1, q.outOfOrderPackets());
    for (int tag : {7, 9}) EXPECT_EQ(tag, popTagAt(q, kFrameSamples));
}

// Same sender, ring already drained by delivery when its terminator arrives: nothing is queued to
// carry the flag, so continuity must end on the spot, or the restart is refused as a straggler.
TEST(PacketQueue, APayloadFreeTerminatorOnADrainedRingEndsContinuityNow) {
    PacketQueue q; FrameClock clock;
    fillGate(q, clock, 0);
    int hole = -1;
    for (int i = 0; i < 3; i++) EXPECT_GE(popTagHole(q, kGateTarget, &hole), 0);
    q.offer(nullptr, 0, 0, /*terminator=*/true, clock.next());
    FrameClock restarted;
    fillGate(q, restarted, 20);
    EXPECT_EQ(0, q.outOfOrderPackets());
    EXPECT_EQ(20, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole);
}

// A payload-carrying terminator refused as a duplicate must still end the spurt: the gate latch
// happens before offer() judges the packet, not after.
TEST(PacketQueue, ATerminatorRefusedAsADuplicateStillOpensTheGate) {
    PacketQueue q; FrameClock clock;
    const uint64_t frame = clock.next();
    offer(q, packet(0), frame);                          // queued, below the prebuffer target
    offer(q, packet(9), frame, /*terminator=*/true);      // same frame: refused as already queued
    EXPECT_EQ(1, q.outOfOrderPackets());
    EXPECT_EQ(0, popTag(q)) << "the refused packet's terminator flag must still latch the gate";
}

// And end continuity: a sender pause behind a refused terminator is not loss either.
TEST(PacketQueue, ATerminatorRefusedAsADuplicateStillEndsContinuity) {
    PacketQueue q; FrameClock clock;
    const uint64_t frame = clock.next();
    offer(q, packet(0), frame);                          // queued, not yet popped
    offer(q, packet(9), frame, /*terminator=*/true);      // same frame: refused as already queued
    EXPECT_EQ(1, q.outOfOrderPackets());
    int hole = -1;
    EXPECT_EQ(0, popTagHole(q, kGateTarget, &hole));      // gate opened by the refused terminator
    EXPECT_EQ(0, hole);
    // 80 ms sender pause (8 frames), then the next spurt's first packet.
    offer(q, packet(20), clock.next(8));
    EXPECT_EQ(20, popTagHole(q, kGateTarget, &hole));
    EXPECT_EQ(0, hole) << "the refused terminator still ended continuity at its own frame";
    EXPECT_EQ(0, q.lostSamples());
}

TEST(PacketQueue, ResetClearsFrameState) {
    PacketQueue q; FrameClock clock;
    offer(q, packet(0), clock.next());
    offer(q, packet(2), clock.next(30));     // 300 ms lost before it
    offer(q, packet(3), clock.next());
    offer(q, packet(4), clock.next());
    int conceal = 0;
    EXPECT_EQ(0, popTagHole(q, kPacket, &conceal));
    for (int i = 0; i < 4; i++) EXPECT_EQ(kHolePop, popTagHole(q, kPacket, &conceal));   // hole in progress
    ASSERT_GT(q.lostSamples(), 0);
    offer(q, packet(9), 2);                  // straggler behind the cursor: outOfOrderPackets()
    ASSERT_GT(q.outOfOrderPackets(), 0);

    q.reset();

    EXPECT_EQ(0, q.lostSamples());
    EXPECT_EQ(0, q.outOfOrderPackets());
    FrameClock fresh;
    fillGate(q, fresh, 5);
    int frames = -1;
    EXPECT_EQ(5, popThroughHole(q, kGateTarget, &frames));
    EXPECT_EQ(0, frames) << "a reset slot starts a fresh sender, not a continuation";
    for (int i = 0; i < 2; i++) EXPECT_GE(popThroughHole(q, kGateTarget, &frames), 0);
    offer(q, packet(8), fresh.next(30));
    offer(q, packet(9), fresh.next());
    offer(q, packet(10), fresh.next());
    EXPECT_EQ(8, popThroughHole(q, kPacket, &frames));
    EXPECT_EQ(pl::kConcealSamples / kFrameSamples, frames)
        << "the hole counter was reset with the rest: a full fade, not what the old hole left";
}
