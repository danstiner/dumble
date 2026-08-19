#include <gtest/gtest.h>
#include <cstdio>
#include <iterator>
#include <string>
#include <vector>
#include "EvalCorpus.h"
#include "core/CaptureConstants.h"
#include "core/VoiceActivity.h"

using dumble::VoiceActivity;
using dumble::eval::Clip;
using dumble::eval::Metrics;
using dumble::eval::Trace;

namespace {

/**
 * Runs the gate over a clip and returns the per-frame transmit trace, with the preroll burst
 * modelled: PR 2's engine holds kPrerollPackets packets and flushes them when the gate opens, so
 * an opening edge retroactively transmits the frames before it. Modelling it here is what makes
 * these numbers predict the shipped behaviour instead of the gate's raw output.
 *
 * openEdge marks the gate's own opening frames, separately from the preroll-expanded transmit
 * trace — see Trace's comment for why false-opening scoring needs the unexpanded edge.
 *
 * These pinned bars certify a MODELLED mechanism: kPrerollPackets exists as a constant but has no
 * production consumer yet, so the back-fill above is this test's simulation of PR 2's engine, not
 * a measurement of it. Before these numbers can be read as a product claim, PR 2 must either drive
 * this eval through the real capture path, or pin the engine's flush to exactly this back-fill
 * semantics.
 */
Trace transmitTrace(const Clip& clip, VoiceActivity& va) {
    constexpr int kPrerollFrames = dumble::kPrerollPackets * dumble::kFramesPerPacket;   // 6
    const int frames = int(clip.pcm.size()) / dumble::kFrameSamples;
    Trace trace{std::vector<bool>(size_t(frames), false), std::vector<bool>(size_t(frames), false)};
    bool wasTransmitting = false;
    for (int f = 0; f < frames; f++) {
        const auto decision = va.update(clip.pcm.data() + size_t(f) * dumble::kFrameSamples);
        trace.transmit[size_t(f)] = decision.transmit;
        if (decision.transmit && !wasTransmitting) {
            trace.openEdge[size_t(f)] = true;
            for (int back = 1; back <= kPrerollFrames && f - back >= 0; back++)
                trace.transmit[size_t(f - back)] = true;
        }
        wasTransmitting = decision.transmit && !decision.closing;
    }
    return trace;
}

struct Bars {
    const char* name;
    double minCoverage;
    int maxOnsetMs;
    int maxDropoutMs;
    double maxFalsePerExposed10s;
};

// Pinned a little under the numbers measured when the constants were chosen (coverage −0.03,
// onset/dropout +20 ms) — a regression guard, not a quality target. Coverage does not reach 1.0
// and is not supposed to: the gate opens partway into a soft onset, and preroll recovers 60 ms of
// it, not all of it.
//
// Matched against WavFixture.h's clipNames() by position AND by name: clipNames() is the one
// place the clip set is named (see EvalCorpus.h and make_reference.py, which each derive their
// own set from it or from the same directory listing), so a clip added there and not here is a
// size mismatch the test below catches. The name check on top of that catches a reorder — a new
// clip sorting into the middle of clipNames() — which a size-only check would miss, silently
// pairing every bar with the wrong clip.
constexpr Bars kBars[] = {
    {"dev-other-116-288045-0000-trim", 0.970, 20, 20, 0.0},
    {"dev-other-700-122866-0000", 0.968, 30, 20, 0.0},
    {"dev-other-1255-138279-0002", 0.970, 20, 20, 0.0},
};
static_assert(std::size(kBars) == 3);

}  // namespace

TEST(VoiceActivityEval, MeetsThePinnedBars) {
    const auto names = dumble::fixture::clipNames();
    ASSERT_EQ(names.size(), std::size(kBars))
        << "clipNames() and kBars must list the same clips in the same order";

    const auto blob = dumble::fixture::weightBlob();
    std::printf("[          ] open=%.2f close=%.2f hangover=%d frames preroll=%d packets\n",
                dumble::kOpenLevel, dumble::kCloseLevel, dumble::kHangoverFrames,
                dumble::kPrerollPackets);
    std::printf("[          ] %-34s %8s %8s %9s %8s %12s %7s\n",
                "clip", "coverage", "onset", "dropout", "tail", "false/exp10s", "missed");

    for (size_t i = 0; i < names.size(); i++) {
        const std::string& name = names[i];
        const Bars& bar = kBars[i];
        ASSERT_EQ(name, bar.name) << "kBars[" << i << "] is pinned to the wrong clip";
        const Clip clip = dumble::eval::loadClip(name);
        ASSERT_FALSE(clip.pcm.empty()) << name;
        ASSERT_FALSE(clip.speech.empty()) << name;
        auto va = VoiceActivity::create(blob.data(), blob.size());
        ASSERT_TRUE(va) << name;
        const Metrics m = dumble::eval::score(clip, transmitTrace(clip, *va));

        std::printf("[          ] %-34s %7.3f %7dms %8dms %6dms %11.2f %7d\n",
                    name.c_str(), m.coverage, m.worstOnsetMs, m.worstDropoutMs, m.worstTailMs,
                    m.falseOpeningsPerExposed10s, m.missedRegions);

        EXPECT_EQ(m.missedRegions, 0) << name;
        EXPECT_GE(m.coverage, bar.minCoverage) << name;
        EXPECT_LE(m.worstOnsetMs, bar.maxOnsetMs) << name;
        EXPECT_LE(m.worstDropoutMs, bar.maxDropoutMs) << name;
        EXPECT_LE(m.falseOpeningsPerExposed10s, bar.maxFalsePerExposed10s) << name;
    }
}
