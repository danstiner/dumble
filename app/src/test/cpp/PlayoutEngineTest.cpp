#include <gtest/gtest.h>
#include <algorithm>
#include <climits>
#include <atomic>
#include <cmath>
#include <random>
#include <thread>
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

// Reads counters[kCounterDroppedPackets] off a fresh readStats.
int64_t droppedOf(PlayoutEngine& e) {
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    e.readStats(sessions.data(), depths.data(), counters.data());
    return counters[pl::kCounterDroppedPackets];
}

// Reads counters[kCounterConcealedTicks] off a fresh readStats.
int64_t concealedOf(PlayoutEngine& e) {
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    e.readStats(sessions.data(), depths.data(), counters.data());
    return counters[pl::kCounterConcealedTicks];
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
    // Counted as a drop, and the speaker still exists — the slot was claimed before the payload
    // was judged, so the next good packet from this session plays.
    EXPECT_EQ(1, droppedOf(*e));
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

TEST(PlayoutEngine, DoesNotCountAnOversizedPacket) {
    // Deliberate: it is refused before the mutex and already carries kOfferPacketTooLarge, so it
    // is not jitter-queue garbage and must not move the counter that measures that.
    auto e = newEngine();
    const std::vector<uint8_t> huge(pl::kMaxPacketBytes + 1, 0x00);
    ASSERT_EQ(pl::kOfferPacketTooLarge, e->offer(1, huge.data(), int(huge.size()), false));
    EXPECT_EQ(0, droppedOf(*e));
}

TEST(PlayoutEngine, ReportsEachSpeakersBufferedDepth) {
    auto e = newEngine();
    arm(*e, 4, 3);
    arm(*e, 8, 6);
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    const int n = e->readStats(sessions.data(), depths.data(), counters.data());
    ASSERT_EQ(2, n);
    for (int i = 0; i < n; i++) {
        if (sessions[i] == 4) EXPECT_EQ(3 * 480, depths[i]);
        if (sessions[i] == 8) EXPECT_EQ(6 * 480, depths[i]);
    }
}

TEST(PlayoutEngine, CountsAPartialQuantumAsConcealment) {
    auto e = newEngine();
    // A terminator latches SpeakerQueue's prebuffer gate open immediately (SpeakerQueue.cpp:57),
    // bypassing the usual kPrebufferSamples wait — that is what lets one packet play at all here.
    // The packet must be sub-quantum for the assertion below to mean anything: a 240-sample
    // (5 ms) packet decodes to less than the 480-sample quantum, so the tick is real audio for
    // its first half and zero-padding for the rest — speech spliced with silence.
    const std::vector<uint8_t> p = encodeSamples(240);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(2, p.data(), int(p.size()), true));
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    ASSERT_EQ(1, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));

    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    e->readStats(sessions.data(), depths.data(), counters.data());
    EXPECT_EQ(1, counters[pl::kCounterConcealedTicks]);
}

TEST(PlayoutEngine, CountsAMidSpurtStallAsConcealment) {
    // The gap a partial quantum cannot express: a speaker mid-sentence whose packets stop
    // arriving. Every tick of it produces nothing, so a count that only fires on a short tick
    // reports zero for the loudest dropout there is.
    auto e = newEngine();
    arm(*e, 1);
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    // Drain the armed spurt. Six 10 ms packets fill six whole quanta, so nothing is concealed yet.
    for (int i = 0; i < 6; i++)
        ASSERT_EQ(1, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
    ASSERT_EQ(0, concealedOf(*e)) << "whole quanta must not count";

    // Nothing arrives. No terminator ever came, so this is a stall, not the end of speech.
    ASSERT_EQ(0, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
    EXPECT_EQ(1, concealedOf(*e));

    // Charged once, on the leading edge — past the re-arm a stalled speaker and a silent one are
    // the same state, so the following ticks must not keep billing.
    for (int i = 0; i < 5; i++) e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    EXPECT_EQ(1, concealedOf(*e)) << "a stall must be counted once, not per tick";
}

TEST(PlayoutEngine, DoesNotCountASpurtItsSenderClosed) {
    // The other half of the stall rule, and the reason it needs the terminator at all: speech
    // ending normally must not read as a dropout, or the metric fires once per utterance and
    // means nothing.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    for (int i = 0; i < 6; i++)
        ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, nullptr, 0, true));
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 12; i++) e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
    EXPECT_EQ(0, concealedOf(*e));
}

TEST(PlayoutEngine, DoesNotCountASpeakerStillPrebuffering) {
    // Quiet by design, not by accident: the gate is closed for the spurt's first kPrebufferSamples
    // and the loop ticks throughout. Charging those would swamp every real gap.
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    ASSERT_EQ(pl::kOfferAccepted, e->offer(1, p.data(), int(p.size()), false));
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int i = 0; i < 4; i++)
        ASSERT_EQ(0, e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live));
    EXPECT_EQ(0, concealedOf(*e));
}

TEST(PlayoutEngine, ConcealedTicksAreMonotonic) {
    // Kotlin subtracts a spurt-start baseline, exactly as it does for the platform's underrun
    // counter, so this must never reset on its own.
    auto e = newEngine();
    // Sub-quantum for the same reason as CountsAPartialQuantumAsConcealment above: only a packet
    // shorter than kQuantum leaves a tick partially real.
    const std::vector<uint8_t> p = encodeSamples(240);
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    int64_t previous = 0;
    for (int32_t session = 20; session < 23; session++) {
        ASSERT_EQ(pl::kOfferAccepted, e->offer(session, p.data(), int(p.size()), true));
        e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
        e->readStats(sessions.data(), depths.data(), counters.data());
        EXPECT_GE(counters[pl::kCounterConcealedTicks], previous);
        previous = counters[pl::kCounterConcealedTicks];
    }
    EXPECT_EQ(3, previous);
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

    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    e->readStats(sessions.data(), depths.data(), counters.data());
    // Two 480-sample packets exactly fill the 960-sample quantum, so nothing is concealed.
    EXPECT_EQ(0, counters[pl::kCounterConcealedTicks]);
}

// Run this under ThreadSanitizer; see the plan's Step 6.
TEST(PlayoutEngine, SurvivesConcurrentOfferAndFill) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(1);
    std::atomic<bool> stop{false};
    std::thread reader([&] {
        for (int i = 0; !stop.load(std::memory_order_relaxed); i++)
            e->offer(int32_t(i % 8), p.data(), int(p.size()), i % 32 == 31);
    });
    std::vector<int16_t> pcm(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    int64_t previousConcealed = 0;
    for (int i = 0; i < 5000; i++) {
        const int producing = e->fillQuantum(pcm.data(), kQuantum, speaking.data(), &live);
        // The invariants that must hold no matter how the two threads interleave. A torn read of
        // the slot set shows up here as a producing count above the live count, or above the cap.
        ASSERT_GE(producing, 0);
        ASSERT_LE(producing, pl::kMaxSpeakers);
        ASSERT_GE(live, 0);
        ASSERT_LE(live, pl::kMaxSpeakers);
        for (int n = 0; n < producing; n++) {
            ASSERT_GE(speaking[n], 0);
            ASSERT_LT(speaking[n], 8) << "session outside the range the reader offers";
        }
        if (i % 100 == 0) {
            const int speakers = e->readStats(sessions.data(), depths.data(), counters.data());
            ASSERT_GE(speakers, 0);
            ASSERT_LE(speakers, pl::kMaxSpeakers);
            for (int n = 0; n < speakers; n++) ASSERT_GE(depths[n], 0);
            ASSERT_GE(counters[pl::kCounterConcealedTicks], previousConcealed);
            previousConcealed = counters[pl::kCounterConcealedTicks];
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
    std::vector<int16_t> out(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    std::vector<int32_t> sessions(pl::kMaxSpeakers), depths(pl::kMaxSpeakers);
    std::vector<int64_t> counters(pl::kCounterCount);
    int64_t lastDropped = 0, lastConcealed = 0;

    for (int i = 0; i < 8000; i++) {
        const int len = int(rng() % (pl::kMaxPacketBytes + 64));
        for (int b = 0; b < len; b++) buf[b] = uint8_t(rng());
        // Sessions past kMaxSpeakers so the cap refusal is on the hot path too.
        e->offer(int32_t(rng() % 80), buf.data(), len, (rng() & 7) == 0);
        if ((i & 15) == 0) {
            ASSERT_GE(e->fillQuantum(out.data(), kQuantum, speaking.data(), &live), 0);
            ASSERT_GE(live, 0);
            ASSERT_LE(live, pl::kMaxSpeakers);
        }
        if ((i & 255) == 0) {
            const int n = e->readStats(sessions.data(), depths.data(), counters.data());
            ASSERT_GE(n, 0);
            ASSERT_LE(n, pl::kMaxSpeakers);
            for (int s = 0; s < n; s++) ASSERT_GE(depths[s], 0) << "negative depth at " << s;
            ASSERT_GE(counters[pl::kCounterDroppedPackets], lastDropped);
            ASSERT_GE(counters[pl::kCounterConcealedTicks], lastConcealed);
            lastDropped = counters[pl::kCounterDroppedPackets];
            lastConcealed = counters[pl::kCounterConcealedTicks];
        }
    }
}

// The shape a lossy or hostile transport actually produces, which random bytes rarely reach: a
// real Opus header with the payload cut short, including the zero-length tag-only frame.
TEST(PlayoutEngine, SurvivesEveryTruncationOfARealPacket) {
    auto e = newEngine();
    const std::vector<uint8_t> p = encode(2);
    std::vector<int16_t> out(kQuantum);
    std::vector<int32_t> speaking(pl::kMaxSpeakers);
    int32_t live = 0;
    for (int cut = 0; cut <= int(p.size()); cut++) {
        e->offer(7, p.data(), cut, false);
        ASSERT_GE(e->fillQuantum(out.data(), kQuantum, speaking.data(), &live), 0) << "cut=" << cut;
    }
}
