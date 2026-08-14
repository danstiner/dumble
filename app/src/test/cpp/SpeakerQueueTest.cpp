#include <gtest/gtest.h>
#include <cmath>
#include <memory>
#include <vector>
#include "core/AudioDecoder.h"
#include "core/AudioEncoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"
#include "core/SpeakerQueue.h"

namespace {

using dumble::playout::SpeakerQueue;
namespace pl = dumble::playout;

constexpr int kQuantum = 480;

// One encoded packet spanning `tenMsFrames` * 10 ms, plus the sample count it decodes to.
struct Packet {
    std::vector<uint8_t> bytes;
    int spanSamples = 0;
};

Packet encode(int tenMsFrames) {
    static auto enc =
        dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000).release();
    const int samples = tenMsFrames * 480;
    std::vector<int16_t> pcm(samples);
    for (int i = 0; i < samples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));
    Packet p;
    p.bytes.resize(pl::kMaxPacketBytes);
    const int n = enc->encode(pcm.data(), samples, p.bytes.data(), int(p.bytes.size()));
    EXPECT_GT(n, 0);
    p.bytes.resize(n > 0 ? n : 0);
    p.spanSamples = samples;
    return p;
}

std::unique_ptr<SpeakerQueue> newQueue() {
    auto q = SpeakerQueue::create(dumble::kSampleRate, kQuantum);
    EXPECT_TRUE(q);
    return q;
}

bool offer(SpeakerQueue& q, const Packet& p, bool terminator = false) {
    return q.offer(p.bytes.data(), int(p.bytes.size()), p.spanSamples, terminator);
}

// One playback tick: pop-and-decode until a quantum is available, drain, then close the tick.
// Mirrors exactly what PlayoutEngine::fillQuantum will do per slot.
int tick(SpeakerQueue& q, int16_t* out, bool* retiredOut = nullptr) {
    uint8_t scratch[pl::kMaxPacketBytes];
    while (q.pcmAvailable() < kQuantum) {
        const int len = q.popPacket(scratch, int(sizeof scratch));
        if (len <= 0) break;
        q.decodeInto(scratch, len);
    }
    const int produced = q.drain(out, kQuantum);
    const bool retired = q.endTick(produced, kQuantum).retire;
    if (retiredOut) *retiredOut = retired;
    return produced;
}

// Ticks the queue until it stops producing, returning how many samples of real audio it emitted.
int drainSpurt(SpeakerQueue& q, int maxTicks = 500) {
    std::vector<int16_t> out(kQuantum);
    int total = 0;
    for (int i = 0; i < maxTicks; i++) {
        const int n = tick(q, out.data());
        if (n == 0) break;
        total += n;
    }
    return total;
}

// Enough packets to open the prebuffer gate, which needs kPrebufferSamples queued.
void arm(SpeakerQueue& q, int tenMsFrames = 1, int count = 6) {
    const Packet p = encode(tenMsFrames);
    for (int i = 0; i < count; i++) EXPECT_TRUE(offer(q, p));
}

}  // namespace

TEST(SpeakerQueue, CreateBuildsADecoderUpFront) {
    EXPECT_TRUE(SpeakerQueue::create(dumble::kSampleRate, kQuantum));
}

TEST(SpeakerQueue, ProducesNothingUntilThePrebufferIsMet) {
    auto q = newQueue();
    std::vector<int16_t> out(kQuantum);
    // 50 ms queued against a 60 ms margin.
    arm(*q, 1, 5);
    EXPECT_EQ(0, tick(*q, out.data()));
    arm(*q, 1, 1);
    EXPECT_EQ(kQuantum, tick(*q, out.data()));
}

TEST(SpeakerQueue, TenMillisecondSenderDrainsOneQuantumPerTick) {
    auto q = newQueue();
    arm(*q, 1, 6);
    std::vector<int16_t> out(kQuantum);
    for (int i = 0; i < 6; i++) EXPECT_EQ(kQuantum, tick(*q, out.data())) << "tick " << i;
    EXPECT_EQ(0, tick(*q, out.data()));
}

TEST(SpeakerQueue, SixtyMillisecondSenderDrainsOneQuantumPerTick) {
    auto q = newQueue();
    const Packet p = encode(6);
    ASSERT_TRUE(offer(*q, p));
    std::vector<int16_t> out(kQuantum);
    // One 60 ms packet is six quanta, and the fifo — not the packet boundary — sets the cadence.
    for (int i = 0; i < 6; i++) EXPECT_EQ(kQuantum, tick(*q, out.data())) << "tick " << i;
    EXPECT_EQ(0, tick(*q, out.data()));
}

TEST(SpeakerQueue, DecodedAudioIsNotSilence) {
    auto q = newQueue();
    arm(*q, 1, 6);
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, tick(*q, out.data()));
    double energy = 0;
    for (int s : out) energy += double(s) * s;
    EXPECT_GT(energy / kQuantum, 1000.0);
}

TEST(SpeakerQueue, AShortDrainZeroPadsAndReportsTheRealCount) {
    auto q = newQueue();
    // A single 10 ms packet with a terminator: the gate opens, but only half a quantum exists.
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p, true));
    std::vector<int16_t> out(kQuantum, 999);
    const int produced = tick(*q, out.data());
    EXPECT_EQ(480, produced);
    // drain must zero the tail rather than leave the caller's previous quantum behind it.
    for (int i = produced; i < kQuantum; i++) EXPECT_EQ(0, out[i]) << "at " << i;
}

TEST(SpeakerQueue, TerminatorPlaysOutAShortSpurt) {
    auto q = newQueue();
    const Packet p = encode(1);
    // 10 ms, far below the 60 ms margin: without the terminator this would never play.
    ASSERT_TRUE(offer(*q, p, true));
    EXPECT_EQ(480, drainSpurt(*q));
}

TEST(SpeakerQueue, WithoutATerminatorAShortSpurtStillWaitsOnThePrebuffer) {
    auto q = newQueue();
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p, false));
    std::vector<int16_t> out(kQuantum);
    EXPECT_EQ(0, tick(*q, out.data()));
}

TEST(SpeakerQueue, GoingIdleReArmsThePrebuffer) {
    auto q = newQueue();
    arm(*q, 1, 6);
    EXPECT_GT(drainSpurt(*q), 0);
    // Second spurt: one packet is not enough, the gate must have re-armed.
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p));
    std::vector<int16_t> out(kQuantum);
    EXPECT_EQ(0, tick(*q, out.data()));
}

TEST(SpeakerQueue, TerminatorDoesNotReArmTheGateMidSpurt) {
    auto q = newQueue();
    arm(*q, 1, 6);
    std::vector<int16_t> out(kQuantum);
    ASSERT_EQ(kQuantum, tick(*q, out.data()));
    ASSERT_EQ(kQuantum, tick(*q, out.data()));
    // Two ticks have popped 960 samples, leaving 1920 queued — below kPrebufferSamples. A
    // terminator here must only ever latch the gate open, never clear it: if offer() regressed to
    // `prebuffered_ = false`, the next popPacket would see queuedSamples_ (2400, still under the
    // 2880 threshold) and refuse to pop, stranding the rest of the spurt. The queue must be left
    // below the threshold for that regression to show up as a shortfall rather than a no-op.
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p, true));
    EXPECT_EQ(2400, drainSpurt(*q));
}

TEST(SpeakerQueue, TheTailOfASpurtIsNotStranded) {
    auto q = newQueue();
    arm(*q, 1, 6);
    // 60 ms in, 60 ms out — every sample offered has to come back.
    EXPECT_EQ(6 * 480, drainSpurt(*q));
}

TEST(SpeakerQueue, OverflowDropsOldestAndIsCappedInSamples) {
    auto q = newQueue();
    const Packet p = encode(6);   // 60 ms each; 10 of them is 600 ms
    for (int i = 0; i < 20; i++) offer(*q, p);
    EXPECT_LE(q->queuedSamples(), pl::kHighWaterSamples);
    EXPECT_GT(q->droppedPackets(), 0);
}

TEST(SpeakerQueue, OverflowIsAlsoCappedInSlots) {
    auto q = newQueue();
    const Packet p = encode(1);   // 10 ms each; the sample cap alone would allow 60
    for (int i = 0; i < pl::kPacketSlots + 10; i++) offer(*q, p);
    EXPECT_LE(q->queuedSamples(), pl::kPacketSlots * 480);
    EXPECT_GT(q->droppedPackets(), 0);
}

TEST(SpeakerQueue, AnOversizedPacketIsRefusedButNotCounted) {
    // Unreachable in production — PlayoutEngine::offer refuses it first and answers
    // kOfferPacketTooLarge — so counting it here would double-count if it ever became reachable.
    auto q = newQueue();
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    EXPECT_FALSE(q->offer(huge.data(), int(huge.size()), 480, false));
    EXPECT_EQ(0, q->queuedSamples());
    EXPECT_EQ(0, q->droppedPackets());
}

TEST(SpeakerQueue, AnUnpriceablePayloadIsRefusedAndCounted) {
    // What PlayoutEngine hands down when AudioDecoder::packetSamples cannot read the Opus header:
    // a real payload with no span. It cannot be scheduled, and before this counter existed it
    // vanished with no signal at all.
    auto q = newQueue();
    const Packet p = encode(1);
    EXPECT_FALSE(q->offer(p.bytes.data(), int(p.bytes.size()), 0, false));
    EXPECT_EQ(0, q->queuedSamples());
    EXPECT_EQ(1, q->droppedPackets());
}

TEST(SpeakerQueue, AnUnpriceablePayloadStillHonoursItsTerminator) {
    // The latch is the only thing that releases a tail below kPrebufferSamples, so dropping it
    // along with the malformed payload strands the 10 ms already queued until the speaker stalls
    // out ten seconds later — or splices it onto the front of that speaker's next spurt.
    auto q = newQueue();
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p));
    EXPECT_FALSE(q->offer(p.bytes.data(), int(p.bytes.size()), 0, true));
    EXPECT_EQ(1, q->droppedPackets());
    EXPECT_EQ(480, drainSpurt(*q));
}

TEST(SpeakerQueue, ATagOnlyFrameIsNotQueuedButItsTerminatorIsHonoured) {
    auto q = newQueue();
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p));
    // Empty payload, terminator set: nothing to queue, but the spurt must be released.
    EXPECT_TRUE(q->offer(nullptr, 0, 0, true));
    EXPECT_EQ(480, drainSpurt(*q));
}

TEST(SpeakerQueue, RetiresAfterIdleOnceDrained) {
    auto q = newQueue();
    arm(*q, 1, 6);
    EXPECT_GT(drainSpurt(*q), 0);
    std::vector<int16_t> out(kQuantum);
    bool retired = false;
    // drainSpurt's own final tick is what first observes the queue empty, so it already counts as
    // idle tick 1 before this loop starts — one fewer iteration is needed here than the raw
    // constant to land on the boundary.
    for (int i = 0; i < pl::kRetireIdleTicks - 2; i++) {
        tick(*q, out.data(), &retired);
        EXPECT_FALSE(retired) << "retired early at idle tick " << i;
    }
    tick(*q, out.data(), &retired);
    EXPECT_TRUE(retired);
}

TEST(SpeakerQueue, PrebufferingDoesNotCountAsTheShortIdleWindow) {
    auto q = newQueue();
    // One packet, no terminator: produces nothing, but packets remain, so the long window applies.
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p));
    std::vector<int16_t> out(kQuantum);
    bool retired = false;
    for (int i = 0; i < pl::kRetireIdleTicks + 5; i++) {
        tick(*q, out.data(), &retired);
        EXPECT_FALSE(retired) << "retired while prebuffering at tick " << i;
    }
}

TEST(SpeakerQueue, ASpurtStalledBelowThePrebufferEventuallyReleasesItsSlot) {
    auto q = newQueue();
    const Packet p = encode(1);
    ASSERT_TRUE(offer(*q, p));
    std::vector<int16_t> out(kQuantum);
    bool retired = false;
    for (int i = 0; i < pl::kStallIdleTicks && !retired; i++) tick(*q, out.data(), &retired);
    EXPECT_TRUE(retired);
}

TEST(SpeakerQueue, PopPacketRefusesAnOutputSmallerThanThePacket) {
    // The engine's scratch is kMaxPacketBytes, so this cannot happen in production — but a
    // truncating pop would corrupt audio silently rather than fail, so it is pinned.
    auto q = newQueue();
    arm(*q, 1, 6);
    uint8_t tiny[1];
    EXPECT_LT(q->popPacket(tiny, 1), 0);
}

// The 32-slot pool caps a 10 ms sender's backlog at 320 ms, tighter than kHighWaterSamples would
// allow on its own. This is the playout path's one deliberate departure from the Kotlin jitter
// buffer it replaced, and the only bound a real stall would reveal, so both halves of the claim
// are pinned here rather than left to the comment on kPacketSlots.
TEST(SpeakerQueue, ATenMsSenderIsCappedByThePoolAt320ms) {
    auto q = newQueue();
    const Packet p = encode(1);
    for (int i = 0; i < 200; i++) offer(*q, p);
    EXPECT_EQ(pl::kPacketSlots * 480, q->queuedSamples());
    EXPECT_LT(q->queuedSamples(), pl::kHighWaterSamples) << "the sample bound must not have bound";
    EXPECT_EQ(200 - pl::kPacketSlots, q->droppedPackets());
}

TEST(SpeakerQueue, ATwentyMsSenderIsCappedBySamplesAt600ms) {
    auto q = newQueue();
    const Packet p = encode(2);
    for (int i = 0; i < 200; i++) offer(*q, p);
    EXPECT_EQ(pl::kHighWaterSamples, q->queuedSamples());
    EXPECT_LT(q->queuedSamples(), pl::kPacketSlots * 960) << "the pool must not have bound";
}
