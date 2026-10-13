#include <gtest/gtest.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <optional>
#include "core/BootClock.h"
#include "core/JitterEstimator.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::BootClock;
using dumble::playout::JitterEstimator;
namespace pl = dumble::playout;
using namespace std::chrono_literals;

// 20 ms packets, the duration desktop Mumble and our own capture both default to.
constexpr int kFrame = 960;
constexpr int kFrameUnits = kFrame / pl::samplesIn(pl::kFrameNumberUnit);  // 2

// One sender arriving on time: frame N at millisecond N*10 plus a fixed one-way latency.
struct Sender {
    uint64_t frame = 1000;
    BootClock::time_point arrival{50s};

    void advance() {
        frame += kFrameUnits;
        arrival += kFrameUnits * pl::kFrameNumberUnit;
    }
};

TEST(JitterEstimator, ANewSenderStartsDiscontinuous) {
    JitterEstimator est;
    Sender s;
    // Nothing precedes a session's first packet, so whatever it queues is a spurt opening rather
    // than the resumption of one a stall interrupted. A later task reads this to decide whether a
    // backlog may be trimmed, and trimming an opening discards the speaker's first syllable.
    est.observe(s.frame, kFrame, s.arrival);
    EXPECT_TRUE(est.discontinuous());
}

TEST(JitterEstimator, ContiguousRunIsNeverDiscontinuous) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    est.clearDiscontinuity();
    for (int i = 0; i < 50; i++) {
        s.advance();
        est.observe(s.frame, kFrame, s.arrival);
    }
    EXPECT_FALSE(est.discontinuous());
}

TEST(JitterEstimator, AShortReleaseCountsAsAJump) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    est.clearDiscontinuity();
    // One packet's worth of push-to-talk release. The sender's counter runs at wall clock through
    // the gap, so the resumed spurt is not the successor of what came before.
    s.advance();
    s.advance();
    est.observe(s.frame, kFrame, s.arrival);
    EXPECT_TRUE(est.discontinuous());
}

TEST(JitterEstimator, OnTimeArrivalsHaveNoRelativeDelay) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    for (int i = 0; i < 20; i++) {
        s.advance();
        EXPECT_EQ(est.observe(s.frame, kFrame, s.arrival), 0ms);
    }
}

TEST(JitterEstimator, AStallBurstReadsAsLateAgainstTheHeldBaseline) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    // The session's first packet latches the flag, as every session's does. Cleared here so the
    // assertion below is about the stall and nothing else.
    est.clearDiscontinuity();
    for (int i = 0; i < 20; i++) {
        s.advance();
        est.observe(s.frame, kFrame, s.arrival);
    }
    // 300 ms of link stall, then everything captured during it lands at once. Frame numbers stay
    // contiguous, which is what keeps the baseline — and so makes the burst measurable as late.
    const BootClock::time_point release = s.arrival + 300ms;
    std::chrono::milliseconds worst{};
    for (int i = 0; i < 15; i++) {
        s.advance();
        worst = std::max(worst, est.observe(s.frame, kFrame, release).value_or(0ms));
    }
    EXPECT_FALSE(est.discontinuous());
    EXPECT_GE(worst, 280ms);
}

TEST(JitterEstimator, ASilenceLongerThanTheWindowRebasesAndSkips) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    s.advance();
    est.observe(s.frame, kFrame, s.arrival);
    // 30 s of silence: contiguity breaks and the old minimum is far too stale to measure against.
    s.frame += 3000;
    s.arrival += 30s;
    EXPECT_EQ(est.observe(s.frame, kFrame, s.arrival), std::nullopt);
    EXPECT_TRUE(est.discontinuous());
}

TEST(JitterEstimator, AnAbsurdFrameNumberIsSkippedRatherThanConverted) {
    JitterEstimator est;
    const BootClock::time_point arrival{50s};
    EXPECT_EQ(est.observe(pl::kMaxFrameNumber + 1, kFrame, arrival), std::nullopt);
}

// Runs `count` packets through, spread far enough apart to close many peak-hold windows.
//
// Only every fourth packet is late, and that is the whole point: a delay applied to *every* packet
// is constant added latency, not jitter, and the sliding minimum absorbs it within two buckets —
// a helper that delayed everything would measure zero after two seconds and quietly make every
// test below assert the clean-link answer. Jitter is variance, so the fixture has to vary.
void feed(JitterEstimator& est, Sender& s, int count, std::chrono::milliseconds delay) {
    for (int i = 0; i < count; i++) {
        s.advance();
        est.observe(s.frame, kFrame, s.arrival + (i % 4 == 0 ? delay : 0ms));
    }
}

TEST(JitterEstimator, ColdStartsAtTheStartDelay) {
    JitterEstimator est;
    EXPECT_EQ(est.targetSamples(), pl::kColdStartSamples);
    EXPECT_FALSE(est.hasData());
}

TEST(JitterEstimator, ACleanLinkSettlesAtTheLowestBucketPlusTheMargin) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 500, 0ms);
    EXPECT_TRUE(est.hasData());
    // Lowest bucket answers (1 + 0) * 20, plus the 10 ms margin.
    EXPECT_EQ(est.targetSamples(), pl::samplesIn(pl::kTargetBucket + pl::kSafetyMargin));
}

TEST(JitterEstimator, SilenceFreezesTheEstimate) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 120ms);
    const int earned = est.targetSamples();
    EXPECT_GT(earned, pl::samplesIn(pl::kTargetBucket + pl::kSafetyMargin));
    // Thirty seconds of quiet. Nothing arrives, so nothing decays — this is the whole reason the
    // memory is a forgetting histogram and not a wall-clock ring.
    s.arrival += 30s;
    s.frame += 3000;
    EXPECT_EQ(est.targetSamples(), earned);
}

TEST(JitterEstimator, ASpikeDecaysOutOfTheTarget) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 0ms);
    const int clean = est.targetSamples();

    // A 400 ms outlier moves the target, and is meant to: at this sample size one update is well
    // over the 5 % the quantile discards, and the start-forget ramp deliberately weights early
    // updates heavily. Absorbing an outlier is an asymptotic property, not one the estimator has
    // after fifteen windows, so what is worth pinning is that the spike does not *keep* the
    // target — which is the whole job of a forgetting histogram.
    s.advance();
    est.observe(s.frame, kFrame, s.arrival + 400ms);
    EXPECT_GT(est.targetSamples(), clean);

    // 40 s of clean arrivals, about 80 updates. The spike's share decays by the forget factor each
    // one, from ~12 % to under the 5 % the quantile drops.
    feed(est, s, 2000, 0ms);
    EXPECT_EQ(est.targetSamples(), clean);
}

TEST(JitterEstimator, TheTargetIsClamped) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 5000ms);
    EXPECT_EQ(est.targetSamples(), pl::samplesIn(pl::kMaxTarget));
}

TEST(JitterEstimator, SeedingCopiesTheMemoryAndNotTheBaseline) {
    JitterEstimator source;
    Sender s;
    source.observe(s.frame, kFrame, s.arrival);
    feed(source, s, 400, 120ms);

    JitterEstimator fresh;
    fresh.seedFrom(source);
    EXPECT_EQ(fresh.targetSamples(), source.targetSamples());
    // The baseline is per-sender and must not travel: the first packet after a seed is still a
    // rebase.
    Sender other;
    other.frame = 9'000'000;
    EXPECT_EQ(fresh.observe(other.frame, kFrame, other.arrival), std::nullopt);
}

TEST(JitterEstimator, RelativeDelaysCanBeFedDirectly) {
    JitterEstimator pooled;
    BootClock::time_point at{50s};
    for (int i = 0; i < 400; i++) {
        pooled.observeRelativeDelay(120ms, at);
        at += kFrameUnits * pl::kFrameNumberUnit;
    }
    EXPECT_TRUE(pooled.hasData());
    EXPECT_GT(pooled.targetSamples(), pl::samplesIn(120ms));
}

}  // namespace
