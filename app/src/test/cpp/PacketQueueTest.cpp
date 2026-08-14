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
    int spanSamples = 0;
};

Packet packet(uint8_t tag, int spanSamples = kFrame, int len = 40) {
    return Packet{std::vector<uint8_t>(size_t(len), tag), spanSamples};
}

bool offer(PacketQueue& q, const Packet& p, bool terminator = false) {
    return q.offer(p.bytes.data(), int(p.bytes.size()), p.spanSamples, terminator);
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
    for (int i = 0; i < needed; i++) ASSERT_TRUE(offer(q, packet(uint8_t(first + i))));
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
        ASSERT_TRUE(offer(q, packet(i)));
        queued += kFrame;
        EXPECT_EQ(-1, popTag(q)) << "gate opened at " << q.depthSamples() << " samples";
    }
    ASSERT_TRUE(offer(q, packet(99)));
    ASSERT_GE(q.depthSamples(), pl::kPrebufferSamples);
    EXPECT_GE(popTag(q), 0);
}

TEST(PacketQueue, PopReturnsPacketsInArrivalOrder) {
    PacketQueue q;
    for (uint8_t i = 0; i < 6; i++) ASSERT_TRUE(offer(q, packet(uint8_t(10 + i))));
    for (int i = 0; i < 6; i++) EXPECT_EQ(10 + i, popTag(q));
}

TEST(PacketQueue, PopReportsTheStoredLength) {
    PacketQueue q;
    for (int i = 0; i < 8; i++) ASSERT_TRUE(offer(q, packet(uint8_t(i), kFrame, 7 + i)));
    uint8_t out[pl::kMaxPacketBytes];
    EXPECT_EQ(7, q.pop(out, int(sizeof out)));
    EXPECT_EQ(8, q.pop(out, int(sizeof out)));
}

TEST(PacketQueue, ATerminatorOpensTheGateBelowThePrebuffer) {
    PacketQueue q;
    ASSERT_TRUE(offer(q, packet(3), /*terminator=*/true));
    ASSERT_LT(q.depthSamples(), pl::kPrebufferSamples);
    EXPECT_EQ(3, popTag(q));
}

TEST(PacketQueue, WithoutATerminatorAShortSpurtStaysClosed) {
    PacketQueue q;
    ASSERT_TRUE(offer(q, packet(3)));
    EXPECT_EQ(-1, popTag(q));
}

TEST(PacketQueue, TheGateStaysOpenAcrossAnEmptyPoolWhileTheSpurtRuns) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    ASSERT_TRUE(q.empty());
    // Still mid-spurt: the decoder is producing, so endTick must not re-arm the gate.
    q.endTick(/*decoderProduced=*/true);
    ASSERT_TRUE(offer(q, packet(42)));
    EXPECT_EQ(42, popTag(q));
}

TEST(PacketQueue, GoingIdleReArmsThePrebuffer) {
    PacketQueue q;
    arm(q);
    EXPECT_GT(drainCount(q), 0);
    q.endTick(/*decoderProduced=*/false);
    ASSERT_TRUE(offer(q, packet(42)));
    EXPECT_EQ(-1, popTag(q)) << "gate stayed open into the next spurt";
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
    ASSERT_TRUE(offer(q, packet(90), /*terminator=*/true));
    q.endTick(/*decoderProduced=*/true);
    EXPECT_EQ(31, popTag(q));
}

TEST(PacketQueue, TheTailOfASpurtIsNotStranded) {
    PacketQueue q;
    ASSERT_TRUE(offer(q, packet(1)));
    ASSERT_TRUE(offer(q, packet(2), /*terminator=*/true));
    EXPECT_EQ(2, drainCount(q));
}

TEST(PacketQueue, HighWaterCapsTheQueueInSamples) {
    PacketQueue q;
    const int span = pl::kHighWaterSamples / 8;
    for (uint8_t i = 0; i < 20; i++) {
        ASSERT_TRUE(offer(q, packet(i, span)));
        EXPECT_LE(q.depthSamples(), pl::kHighWaterSamples);
    }
}

TEST(PacketQueue, HighWaterDropsTheOldestFirst) {
    PacketQueue q;
    const int span = pl::kMaxPacketSamples;
    for (uint8_t i = 0; i < 8; i++) ASSERT_TRUE(offer(q, packet(i, span)));
    // The survivors are the newest, so the first pop is not tag 0.
    EXPECT_GT(popTag(q), 0);
}

TEST(PacketQueue, SlotsCapTheQueueEvenBelowHighWater) {
    PacketQueue q;
    // Small spans so kHighWaterSamples cannot bind before kPacketSlots does.
    const int span = pl::kHighWaterSamples / (4 * pl::kPacketSlots);
    for (uint8_t i = 0; i < pl::kPacketSlots + 8; i++) ASSERT_TRUE(offer(q, packet(i, span)));
    EXPECT_EQ(pl::kPacketSlots, drainCount(q));
}

TEST(PacketQueue, ASpanLongerThanTheLargestOpusPacketIsRefused) {
    // Nothing PlayoutEngine measures can exceed this, so the bound is defence in depth — but it
    // is also what keeps Slot's uint16_t span from truncating a caller's number in silence.
    PacketQueue q;
    EXPECT_FALSE(offer(q, packet(5, pl::kMaxPacketSamples + 1)));
    EXPECT_TRUE(q.empty());
    EXPECT_TRUE(offer(q, packet(6, pl::kMaxPacketSamples)));
    EXPECT_EQ(pl::kMaxPacketSamples, q.depthSamples());
}

TEST(PacketQueue, AnOversizedPacketIsRefusedAlongWithItsTerminator) {
    PacketQueue q;
    const std::vector<uint8_t> big(pl::kMaxPacketBytes + 1, 7);
    EXPECT_FALSE(q.offer(big.data(), int(big.size()), kFrame, /*terminator=*/true));
    ASSERT_TRUE(offer(q, packet(1)));
    EXPECT_EQ(-1, popTag(q)) << "the refused packet's terminator opened the gate";
}

TEST(PacketQueue, AnUnmeasurablePayloadIsRefused) {
    PacketQueue q;
    EXPECT_FALSE(offer(q, packet(1, /*spanSamples=*/0)));
    EXPECT_TRUE(q.empty());
    EXPECT_EQ(0, q.depthSamples());
}

TEST(PacketQueue, AnUnmeasurablePayloadStillHonoursItsTerminator) {
    PacketQueue q;
    ASSERT_TRUE(offer(q, packet(4)));
    EXPECT_FALSE(offer(q, packet(9, /*spanSamples=*/0), /*terminator=*/true));
    EXPECT_EQ(4, popTag(q));
}

TEST(PacketQueue, ATagOnlyFrameIsNotQueuedButItsTerminatorIsHonoured) {
    PacketQueue q;
    ASSERT_TRUE(offer(q, packet(4)));
    EXPECT_TRUE(q.offer(nullptr, 0, 0, /*terminator=*/true));
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
    EXPECT_TRUE(offer(q, packet(9)));
    EXPECT_EQ(-1, popTag(q)) << "reset left the prebuffer gate open";
}
