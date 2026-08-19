#include <gtest/gtest.h>
#include <algorithm>
#include <cstdint>
#include "core/JitterEstimator.h"
#include "core/PlayoutConstants.h"

namespace {

using dumble::playout::JitterEstimator;
namespace pl = dumble::playout;

// 20 ms packets, the duration desktop Mumble and our own capture both default to.
constexpr int kFrame = 960;
constexpr int kFrameUnits = kFrame / (pl::kFrameNumberMillis * pl::kSamplesPerMilli);  // 2

// One sender arriving on time: frame N at millisecond N*10 plus a fixed one-way latency.
struct Sender {
    uint64_t frame = 1000;
    int64_t arrival = 50'000;

    void advance() {
        frame += kFrameUnits;
        arrival += pl::kFrameNumberMillis * kFrameUnits;
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
        EXPECT_EQ(est.observe(s.frame, kFrame, s.arrival), 0);
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
    const int64_t release = s.arrival + 300;
    int worst = 0;
    for (int i = 0; i < 15; i++) {
        s.advance();
        worst = std::max(worst, est.observe(s.frame, kFrame, release));
    }
    EXPECT_FALSE(est.discontinuous());
    EXPECT_GE(worst, 280);
}

TEST(JitterEstimator, ASilenceLongerThanTheWindowRebasesAndSkips) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    s.advance();
    est.observe(s.frame, kFrame, s.arrival);
    // 30 s of silence: contiguity breaks and the old minimum is far too stale to measure against.
    s.frame += 3000;
    s.arrival += 30'000;
    EXPECT_EQ(est.observe(s.frame, kFrame, s.arrival), -1);
    EXPECT_TRUE(est.discontinuous());
}

TEST(JitterEstimator, AnAbsurdFrameNumberIsSkippedRatherThanConverted) {
    JitterEstimator est;
    EXPECT_EQ(est.observe(pl::kMaxFrameNumber + 1, kFrame, 50'000), -1);
}

// Runs `count` packets through, spread far enough apart to close many peak-hold windows.
//
// Only every fourth packet is late, and that is the whole point: a delay applied to *every* packet
// is constant added latency, not jitter, and the sliding minimum absorbs it within two buckets —
// a helper that delayed everything would measure zero after two seconds and quietly make every
// test below assert the clean-link answer. Jitter is variance, so the fixture has to vary.
void feed(JitterEstimator& est, Sender& s, int count, int delayMillis) {
    for (int i = 0; i < count; i++) {
        s.advance();
        est.observe(s.frame, kFrame, s.arrival + (i % 4 == 0 ? delayMillis : 0));
    }
}

TEST(JitterEstimator, ColdStartsAtTheStartDelay) {
    JitterEstimator est;
    EXPECT_EQ(est.targetSamples(), pl::kColdStartMillis * pl::kSamplesPerMilli);
    EXPECT_FALSE(est.hasData());
}

TEST(JitterEstimator, ACleanLinkSettlesAtTheLowestBucketPlusTheMargin) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 500, 0);
    EXPECT_TRUE(est.hasData());
    // Lowest bucket answers (1 + 0) * 20, plus the 10 ms margin.
    EXPECT_EQ(est.targetSamples(),
              (pl::kTargetBucketMillis + pl::kSafetyMarginMillis) * pl::kSamplesPerMilli);
}

TEST(JitterEstimator, SilenceFreezesTheEstimate) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 120);
    const int earned = est.targetSamples();
    EXPECT_GT(earned, (pl::kTargetBucketMillis + pl::kSafetyMarginMillis) * pl::kSamplesPerMilli);
    // Thirty seconds of quiet. Nothing arrives, so nothing decays — this is the whole reason the
    // memory is a forgetting histogram and not a wall-clock ring.
    s.arrival += 30'000;
    s.frame += 3000;
    EXPECT_EQ(est.targetSamples(), earned);
}

TEST(JitterEstimator, ASpikeDecaysOutOfTheTarget) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 0);
    const int clean = est.targetSamples();

    // A 400 ms outlier moves the target, and is meant to: at this sample size one update is well
    // over the 5 % the quantile discards, and the start-forget ramp deliberately weights early
    // updates heavily. Absorbing an outlier is an asymptotic property, not one the estimator has
    // after fifteen windows, so what is worth pinning is that the spike does not *keep* the
    // target — which is the whole job of a forgetting histogram.
    s.advance();
    est.observe(s.frame, kFrame, s.arrival + 400);
    EXPECT_GT(est.targetSamples(), clean);

    // 40 s of clean arrivals, about 80 updates. The spike's share decays by the forget factor each
    // one, from ~12 % to under the 5 % the quantile drops.
    feed(est, s, 2000, 0);
    EXPECT_EQ(est.targetSamples(), clean);
}

TEST(JitterEstimator, TheTargetIsClamped) {
    JitterEstimator est;
    Sender s;
    est.observe(s.frame, kFrame, s.arrival);
    feed(est, s, 400, 5000);
    EXPECT_EQ(est.targetSamples(), pl::kMaxTargetMillis * pl::kSamplesPerMilli);
}

TEST(JitterEstimator, SeedingCopiesTheMemoryAndNotTheBaseline) {
    JitterEstimator source;
    Sender s;
    source.observe(s.frame, kFrame, s.arrival);
    feed(source, s, 400, 120);

    JitterEstimator fresh;
    fresh.seedFrom(source);
    EXPECT_EQ(fresh.targetSamples(), source.targetSamples());
    // The baseline is per-sender and must not travel: the first packet after a seed is still a
    // rebase.
    Sender other;
    other.frame = 9'000'000;
    EXPECT_EQ(fresh.observe(other.frame, kFrame, other.arrival), -1);
}

TEST(JitterEstimator, RelativeDelaysCanBeFedDirectly) {
    JitterEstimator pooled;
    int64_t at = 50'000;
    for (int i = 0; i < 400; i++) {
        pooled.observeRelativeDelay(120, at);
        at += pl::kFrameNumberMillis * kFrameUnits;
    }
    EXPECT_TRUE(pooled.hasData());
    EXPECT_GT(pooled.targetSamples(), 120 * pl::kSamplesPerMilli);
}

}  // namespace
