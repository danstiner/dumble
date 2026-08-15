#include <gtest/gtest.h>
#include <cstdint>
#include <vector>
#include "core/PacketQueue.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::playout::PacketQueue;
namespace pl = dumble::playout;

// 20 ms at 48 kHz — the span the tests count in.
constexpr int kFrame = 960;

// Payload bytes are arbitrary here: nothing in PacketQueue reads them, so a fill byte doubles as
// an identity tag and the whole suite runs without libopus.
struct Packet {
    std::vector<uint8_t> bytes;
    int samples = 0;
};

Packet packet(uint8_t tag, int samples = kFrame, int len = 40) {
    return Packet{std::vector<uint8_t>(size_t(len), tag), samples};
}

void offer(PacketQueue& q, const Packet& p, bool terminator = false) {
    q.offer(p.bytes.data(), int(p.bytes.size()), p.samples, terminator);
}

// Pops one packet, returning its tag, or -1 when the gate is closed and the pool empty.
int popTag(PacketQueue& q) {
    uint8_t out[pl::kMaxPacketBytes];
    const int n = q.pop(out, int(sizeof out));
    if (n <= 0) return -1;
    return out[0];
}

// Enough packets to clear kPrebufferSamples, tagged from `first`.
void arm(PacketQueue& q, uint8_t first = 0) {
    const int needed = (pl::kPrebufferSamples + kFrame - 1) / kFrame;
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
    for (uint8_t i = 0; queued + kFrame < pl::kPrebufferSamples; i++) {
        offer(q, packet(i));
        queued += kFrame;
        EXPECT_EQ(-1, popTag(q)) << "gate opened at " << q.depthSamples() << " samples";
    }
    offer(q, packet(99));
    ASSERT_GE(q.depthSamples(), pl::kPrebufferSamples);
    EXPECT_GE(popTag(q), 0);
}

TEST(PacketQueue, PopReturnsPacketsInArrivalOrder) {
    PacketQueue q;
    for (uint8_t i = 0; i < 6; i++) offer(q, packet(uint8_t(10 + i)));
    for (int i = 0; i < 6; i++) EXPECT_EQ(10 + i, popTag(q));
}

TEST(PacketQueue, PopReportsTheStoredLength) {
    PacketQueue q;
    for (int i = 0; i < 8; i++) offer(q, packet(uint8_t(i), kFrame, 7 + i));
    uint8_t out[pl::kMaxPacketBytes];
    EXPECT_EQ(7, q.pop(out, int(sizeof out)));
    EXPECT_EQ(8, q.pop(out, int(sizeof out)));
}

TEST(PacketQueue, ATerminatorOpensTheGateBelowThePrebuffer) {
    PacketQueue q;
    offer(q, packet(3), /*terminator=*/true);
    ASSERT_LT(q.depthSamples(), pl::kPrebufferSamples);
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
    // Still mid-spurt: the decoder is producing, so endTick must not re-arm the gate.
    q.endTick(/*decoderProduced=*/true);
    offer(q, packet(42));
    EXPECT_EQ(42, popTag(q));
}

TEST(PacketQueue, GoingIdleReArmsThePrebuffer) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    q.endTick(/*decoderProduced=*/false);
    offer(q, packet(42));
    EXPECT_EQ(-1, popTag(q)) << "gate stayed open into the next spurt";
}

TEST(PacketQueue, ReArmTrustsTheEmptinessSeenAtPopOverThePresent) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    // The next spurt's first packet lands between the caller's last pop and its endTick — the
    // engine decodes other speakers in between. The gate must still re-arm: that packet is a new
    // spurt, not licence to keep playing.
    offer(q, packet(42));
    q.endTick(/*decoderProduced=*/false);
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
    q.endTick(/*decoderProduced=*/false);
    EXPECT_EQ(42, popTag(q)) << "endTick closed the gate over the terminator latch";
}

TEST(PacketQueue, EndTickDoesNotReArmWhilePacketsRemain) {
    PacketQueue q;
    arm(q, 20);
    ASSERT_EQ(20, popTag(q));
    // Produced nothing this tick, but the pool is not drained — this is a spurt waiting on the
    // network, not one that ended.
    q.endTick(/*decoderProduced=*/false);
    EXPECT_EQ(21, popTag(q));
}

TEST(PacketQueue, TerminatorDoesNotReArmTheGateMidSpurt) {
    PacketQueue q;
    arm(q, 30);
    ASSERT_EQ(30, popTag(q));
    offer(q, packet(90), /*terminator=*/true);
    q.endTick(/*decoderProduced=*/true);
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
    EXPECT_DEATH(q.offer(big.data(), int(big.size()), kFrame, false), "");
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
    EXPECT_LT(q.pop(tiny, 1), 0);
}

TEST(PacketQueue, QueuedSamplesTracksOffersAndPops) {
    PacketQueue q;
    arm(q);
    const int armed = q.depthSamples();
    EXPECT_EQ(armed, q.depthSamples());
    ASSERT_GE(popTag(q), 0);
    EXPECT_EQ(armed - kFrame, q.depthSamples());
    EXPECT_EQ(0, drainCount(q) >= 0 ? q.depthSamples() : -1);
    EXPECT_TRUE(q.empty());
}

TEST(PacketQueue, ResetDropsEverythingQueuedAndRearmsTheGate) {
    // The state a slot must not carry to its next sender. Armed and mid-spurt, so both halves are
    // live: packets are queued and the gate is open.
    PacketQueue q;
    arm(q);
    ASSERT_GE(q.depthSamples(), pl::kPrebufferSamples);
    ASSERT_GE(popTag(q), 0) << "the gate should be open before reset";

    q.reset();

    EXPECT_TRUE(q.empty());
    EXPECT_EQ(0, q.depthSamples());
    // The gate is armed again, so one packet is not enough to play.
    offer(q, packet(9));
    EXPECT_EQ(-1, popTag(q)) << "reset left the prebuffer gate open";
}
