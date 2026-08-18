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

void offer(PacketQueue& q, const Packet& p, bool terminator = false) {
    q.offer(p.bytes.data(), int(p.bytes.size()), p.samples, terminator);
}

// The gate is a function of a target the engine supplies, so every pop states one.
int popTagAt(PacketQueue& q, int target, bool catchUpAllowed = false) {
    uint8_t out[pl::kMaxPacketBytes];
    const int n = q.pop(out, int(sizeof out), target, catchUpAllowed);
    if (n <= 0) return -1;
    return out[0];
}

// Most gate tests want a fixed reference rather than an estimate, and ask for it by name.
int popTag(PacketQueue& q) { return popTagAt(q, kGateTarget); }

// Enough packets to clear kGateTarget, tagged from `first`.
void arm(PacketQueue& q, uint8_t first = 0) {
    const int needed = (kGateTarget + kPacket - 1) / kPacket;
    for (int i = 0; i < needed; i++) offer(q, packet(uint8_t(first + i)));
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
    int queued = 0;
    for (uint8_t i = 0; queued + kPacket < kGateTarget; i++) {
        offer(q, packet(i));
        queued += kPacket;
        EXPECT_EQ(-1, popTag(q)) << "gate opened at " << q.depthSamples() << " samples";
    }
    offer(q, packet(99));
    ASSERT_GE(q.depthSamples(), kGateTarget);
    EXPECT_GE(popTag(q), 0);
}

TEST(PacketQueue, PopReturnsPacketsInArrivalOrder) {
    PacketQueue q;
    for (uint8_t i = 0; i < 6; i++) offer(q, packet(uint8_t(10 + i)));
    for (int i = 0; i < 6; i++) EXPECT_EQ(10 + i, popTag(q));
}

TEST(PacketQueue, PopReportsTheStoredLength) {
    PacketQueue q;
    for (int i = 0; i < 8; i++) offer(q, packet(uint8_t(i), kPacket, 7 + i));
    uint8_t out[pl::kMaxPacketBytes];
    EXPECT_EQ(7, q.pop(out, int(sizeof out), kGateTarget, false));
    EXPECT_EQ(8, q.pop(out, int(sizeof out), kGateTarget, false));
}

TEST(PacketQueue, ATerminatorOpensTheGateBelowThePrebuffer) {
    PacketQueue q;
    offer(q, packet(3), /*terminator=*/true);
    ASSERT_LT(q.depthSamples(), kGateTarget);
    EXPECT_EQ(3, popTag(q));
}

TEST(PacketQueue, WithoutATerminatorAShortSpurtStaysClosed) {
    PacketQueue q;
    offer(q, packet(3));
    EXPECT_EQ(-1, popTag(q));
}

TEST(PacketQueue, TheGateStaysOpenAcrossAnEmptyPoolWhileTheSpurtRuns) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    ASSERT_TRUE(q.empty());
    // Still mid-spurt: the decoder is producing, so endFill must not re-arm the gate.
    q.endFill(/*decoderProduced=*/true);
    offer(q, packet(42));
    EXPECT_EQ(42, popTag(q));
}

TEST(PacketQueue, GoingIdleReArmsThePrebuffer) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    q.endFill(/*decoderProduced=*/false);
    offer(q, packet(42));
    EXPECT_EQ(-1, popTag(q)) << "gate stayed open into the next spurt";
}

TEST(PacketQueue, ReArmTrustsTheEmptinessSeenAtPopOverThePresent) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    // The next spurt's first packet lands between the caller's last pop and its endFill — the
    // engine decodes other speakers in between. The gate must still re-arm: that packet is a new
    // spurt, not licence to keep playing.
    offer(q, packet(42));
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(-1, popTag(q)) << "the late arrival rode the old spurt's gate past the prebuffer";
}

TEST(PacketQueue, ALateTerminatorLatchSurvivesTheReArm) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    // A complete short spurt — packet plus terminator — lands in the same window. Unlike a bare
    // packet, the terminator is an explicit verdict that the spurt is whole: the latch must beat
    // the re-arm, or the spurt sits below the prebuffer forever and is reset away at retirement.
    offer(q, packet(42), /*terminator=*/true);
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(42, popTag(q)) << "endFill closed the gate over the terminator latch";
}

TEST(PacketQueue, EndTickDoesNotReArmWhilePacketsRemain) {
    PacketQueue q;
    arm(q, 20);
    ASSERT_EQ(20, popTag(q));
    // Produced nothing this tick, but the pool is not drained — this is a spurt waiting on the
    // network, not one that ended.
    q.endFill(/*decoderProduced=*/false);
    EXPECT_EQ(21, popTag(q));
}

TEST(PacketQueue, TerminatorDoesNotReArmTheGateMidSpurt) {
    PacketQueue q;
    arm(q, 30);
    ASSERT_EQ(30, popTag(q));
    offer(q, packet(90), /*terminator=*/true);
    q.endFill(/*decoderProduced=*/true);
    EXPECT_EQ(31, popTag(q));
}

TEST(PacketQueue, TheTailOfASpurtIsNotStranded) {
    PacketQueue q;
    offer(q, packet(1));
    offer(q, packet(2), /*terminator=*/true);
    EXPECT_EQ(2, drainCount(q));
}

TEST(PacketQueue, HighWaterCapsTheQueueInSamples) {
    PacketQueue q;
    const int span = pl::kHighWaterSamples / 8;
    for (uint8_t i = 0; i < 20; i++) {
        offer(q, packet(i, span));
        EXPECT_LE(q.depthSamples(), pl::kHighWaterSamples);
    }
    EXPECT_GT(q.droppedPackets(), 0) << "audio was thrown away with nothing to show for it";
}

TEST(PacketQueue, HighWaterDropsTheOldestFirst) {
    PacketQueue q;
    const int span = pl::kMaxPacketSamples;
    for (uint8_t i = 0; i < 8; i++) offer(q, packet(i, span));
    // The survivors are the newest, so the first pop is not tag 0.
    EXPECT_GT(popTag(q), 0);
}

TEST(PacketQueue, SlotsCapTheQueueEvenBelowHighWater) {
    PacketQueue q;
    // Small spans so kHighWaterSamples cannot bind before kMaxQueuedPackets does.
    const int span = pl::kHighWaterSamples / (4 * pl::kMaxQueuedPackets);
    for (uint8_t i = 0; i < pl::kMaxQueuedPackets + 8; i++) offer(q, packet(i, span));
    EXPECT_EQ(8, q.droppedPackets());
    EXPECT_EQ(pl::kMaxQueuedPackets, drainCount(q));
}

#ifndef NDEBUG
// Slot narrows the span to uint16_t, and no caller can exceed the bound — PlayoutEngine measures
// with libopus, which cannot report past 120 ms at a rate it accepts. Debug builds assert the
// reasoning rather than branch on it; NDEBUG compiles the assert away and this test with it.
TEST(PacketQueueDeathTest, DebugBuildsRejectAPacketBreakingTheContract) {
    PacketQueue q;
    EXPECT_DEATH(offer(q, packet(5, pl::kMaxPacketSamples + 1)), "largest Opus packet");
    // Bytes without samples: PlayoutEngine sends an unmeasurable payload down as no payload.
    EXPECT_DEATH(offer(q, packet(5, /*samples=*/0)), "bytes and samples, or neither");
}
#endif

// Unguarded, unlike the two above: this contract holds in release as well, being the only one
// whose consequence is corruption rather than a wrong answer.
TEST(PacketQueueDeathTest, AnOversizedPacketCrashesRatherThanOverrunningThePool) {
    PacketQueue q;
    const std::vector<uint8_t> big(pl::kMaxPacketBytes + 1, 7);
    EXPECT_DEATH(q.offer(big.data(), int(big.size()), kPacket, false), "");
}

TEST(PacketQueue, TheLargestLegalSpanFits) {
    // The top of the range Slot's uint16_t has to hold. Bounding it is the caller's job, since the
    // caller is what measures the packet — see PlayoutEngine::offer.
    PacketQueue q;
    offer(q, packet(6, pl::kMaxPacketSamples));
    EXPECT_EQ(pl::kMaxPacketSamples, q.depthSamples());
}

TEST(PacketQueue, ATagOnlyFrameIsNotQueuedButItsTerminatorIsHonoured) {
    PacketQueue q;
    offer(q, packet(4));
    q.offer(nullptr, 0, 0, /*terminator=*/true);
    EXPECT_EQ(1, drainCount(q));
}

TEST(PacketQueue, PopRefusesAnOutputSmallerThanThePacket) {
    // The engine's scratch is kMaxPacketBytes, so this cannot happen in production — but a
    // truncating pop would corrupt audio silently rather than fail, so it is pinned.
    PacketQueue q;
    arm(q);
    uint8_t tiny[1];
    EXPECT_LT(q.pop(tiny, 1, kGateTarget, false), 0);
}

TEST(PacketQueue, QueuedSamplesTracksOffersAndPops) {
    PacketQueue q;
    arm(q);
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
    arm(q);
    ASSERT_GE(q.depthSamples(), kGateTarget);
    ASSERT_GE(popTag(q), 0) << "the gate should be open before reset";

    q.reset();

    EXPECT_TRUE(q.empty());
    EXPECT_EQ(0, q.depthSamples());
    // The gate is armed again, so one packet is not enough to play.
    offer(q, packet(9));
    EXPECT_EQ(-1, popTag(q)) << "reset left the prebuffer gate open";
}

TEST(PacketQueue, ResetClearsTheDropTally) {
    // The engine harvests the tally into its own total at retirement, so a slot that kept it
    // would bill the next sender for the last one's losses on every stats() read.
    PacketQueue q;
    for (uint8_t i = 0; i < pl::kMaxQueuedPackets + 4; i++) offer(q, packet(i, 480));
    ASSERT_GT(q.droppedPackets(), 0);
    q.reset();
    EXPECT_EQ(0, q.droppedPackets());
}

TEST(PacketQueue, ResetClearsTheSendersTerminator) {
    // reset() restores the just-constructed state whole.
    PacketQueue q;
    arm(q);
    q.offer(nullptr, 0, 0, /*terminator=*/true);
    ASSERT_FALSE(q.speaking());
    q.reset();
    arm(q);
    ASSERT_GE(popTag(q), 0);
    EXPECT_TRUE(q.speaking()) << "reset kept the last sender's terminator";
}

TEST(PacketQueue, SpeakingIsFalseUntilTheGateOpens) {
    // A spurt still building its prebuffer is quiet by design. Counting those ticks as dropouts
    // would swamp the metric with the one silence that is always expected.
    PacketQueue q;
    EXPECT_FALSE(q.speaking());
    offer(q, packet(1));
    EXPECT_FALSE(q.speaking()) << "one packet is below kGateTarget";
    arm(q, 2);
    ASSERT_GE(popTag(q), 0) << "the gate should have opened";
    EXPECT_TRUE(q.speaking());
}

TEST(PacketQueue, ATerminatedSpurtIsNotSpeaking) {
    // The other half: speech that ended normally must not read as a dropout, or the metric fires
    // once per utterance and means nothing.
    PacketQueue q;
    arm(q);
    ASSERT_GE(popTag(q), 0);
    ASSERT_TRUE(q.speaking());
    q.offer(nullptr, 0, 0, /*terminator=*/true);
    EXPECT_FALSE(q.speaking());
}

TEST(PacketQueue, GoingIdleClearsTheTerminatorForTheNextSpurt) {
    // Both flags re-arm together. A terminated_ left set would make the next spurt's stall
    // invisible for the life of the slot.
    PacketQueue q;
    arm(q);
    q.offer(nullptr, 0, 0, /*terminator=*/true);
    ASSERT_EQ(kGateTarget / kPacket, drainCount(q));
    q.endFill(/*decoderProduced=*/false);
    arm(q);
    ASSERT_GE(popTag(q), 0);
    EXPECT_TRUE(q.speaking());
}

TEST(PacketQueue, TheGateOpensAtTheTargetItIsGiven) {
    PacketQueue q;
    // One 20 ms packet, against a 20 ms target: enough.
    offer(q, packet(7));
    EXPECT_EQ(popTagAt(q, kPacket), 7);

    PacketQueue tighter;
    offer(tighter, packet(9));
    // The same packet against a 100 ms target is not enough, and the gate stays shut.
    EXPECT_EQ(popTagAt(tighter, 5 * kPacket), -1);
}

TEST(PacketQueue, CanShrinkRefusesWhenTheRemainderWouldFallShort) {
    PacketQueue q;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i));  // 80 ms
    // Dropping the 20 ms head leaves 60, which clears a 60 ms floor exactly.
    EXPECT_TRUE(q.canShrink(3 * kPacket));
    // It does not clear 61 ms, so no drop is permitted at that floor.
    EXPECT_FALSE(q.canShrink(3 * kPacket + 1));
}

TEST(PacketQueue, CanShrinkNeverUndershootsForALongSender) {
    PacketQueue q;
    // A 60 ms sender: three packets is 180 ms, and dropping one costs 60 at a stroke.
    for (uint8_t i = 0; i < 3; i++) offer(q, packet(i, 3 * kPacket));
    EXPECT_TRUE(q.canShrink(2 * 3 * kPacket));
    // A floor a fixed 40 ms deadband would have cleared, but this sender's packet cannot.
    EXPECT_FALSE(q.canShrink(2 * 3 * kPacket + 1));
}

TEST(PacketQueue, ShrinkIsNotCountedAsLoss) {
    PacketQueue q;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i));
    ASSERT_TRUE(q.canShrink(3 * kPacket));
    q.shrink();
    EXPECT_EQ(q.shrunkPackets(), 1);
    EXPECT_EQ(q.droppedPackets(), 0);
    // The oldest went, not the newest.
    EXPECT_EQ(popTagAt(q, kPacket), 1);
}

TEST(PacketQueue, AnOrdinaryGateOpenTrimsNothing) {
    PacketQueue q;
    // Target plus one packet — the normal state at a gate-open.
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i));
    EXPECT_EQ(popTagAt(q, 3 * kPacket, true), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, AnOverThresholdGateOpenTrimsToTheTarget) {
    PacketQueue q;
    // 320 ms queued against a 40 ms target: a stall's backlog.
    for (uint8_t i = 0; i < 16; i++) offer(q, packet(i));
    const int target = 2 * kPacket;
    EXPECT_EQ(popTagAt(q, target, true), 14);
    // Everything but the newest two packets was discarded, and none of it counted as loss.
    EXPECT_EQ(q.catchUpPackets(), 14);
    EXPECT_EQ(q.droppedPackets(), 0);
    EXPECT_EQ(q.shrunkPackets(), 0);
}

TEST(PacketQueue, AFreshSpurtBurstKeepsItsOpening) {
    PacketQueue q;
    for (uint8_t i = 0; i < 16; i++) offer(q, packet(i));
    // catchUpAllowed false: the engine saw the sender's frame numbers jump, so this burst is a
    // spurt opening and its oldest packet is the first syllable.
    EXPECT_EQ(popTagAt(q, 2 * kPacket, false), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, TheTrimNeverUndershootsTheTarget) {
    PacketQueue q;
    // A 60 ms sender again, six packets, against a 40 ms target with a 100 ms threshold.
    for (uint8_t i = 0; i < 6; i++) offer(q, packet(i, 3 * kPacket));
    const int target = 2 * kPacket;
    ASSERT_EQ(popTagAt(q, target, true), 5);
    // Only the last packet is left: dropping it would have left nothing to clear the target.
    EXPECT_EQ(popTagAt(q, target, true), -1);
}

TEST(PacketQueue, ResetClearsTheNewCounters) {
    PacketQueue q;
    for (uint8_t i = 0; i < 4; i++) offer(q, packet(i));
    q.shrink();
    q.reset();
    EXPECT_EQ(q.shrunkPackets(), 0);
    EXPECT_EQ(q.catchUpPackets(), 0);
}

TEST(PacketQueue, AFullRingOpensTheGateEvenBelowTheTarget) {
    PacketQueue q;
    // A 10 ms sender against a target its ring cannot reach: 32 packets is 320 ms, the target is
    // 450. Without the full-ring check the gate never opens and every further packet is dropped as
    // overflow, which is silence for as long as the target stays high.
    const int tenMs = 480;
    for (int i = 0; i < pl::kMaxQueuedPackets; i++) offer(q, packet(uint8_t(i), tenMs));
    ASSERT_LT(q.depthSamples(), pl::kMaxTargetMillis * pl::kSamplesPerMilli);
    EXPECT_EQ(popTagAt(q, pl::kMaxTargetMillis * pl::kSamplesPerMilli), 0);
}
