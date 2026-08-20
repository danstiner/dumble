#include <gtest/gtest.h>
#include <cstdio>
#include <iterator>
#include <string>
#include <vector>
#include "EvalCorpus.h"
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"
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
 * The back-fill is a model of the engine, so on its own it would certify a simulation rather than
 * the product. TheEngineTransmitsEverythingTheModelPredicts below closes that: it drives the same
 * clips through a real CaptureEngine and requires the engine's transmitted frames to cover the
 * model's, which is what makes these bars a floor on shipped behaviour rather than a claim about
 * test code.
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

namespace {

/** Frames the real engine actually put on the wire, reconstructed from emitted frame numbers. */
std::vector<bool> engineTransmitTrace(const Clip& clip) {
    const auto blob = dumble::fixture::weightBlob();
    auto engine = dumble::CaptureEngine::create(dumble::kSampleRate, dumble::kTxPacketSamples, 40000,
                                                blob.data(), blob.size());
    EXPECT_TRUE(engine);
    const int frames = int(clip.pcm.size()) / dumble::kFrameSamples;
    std::vector<bool> sent(size_t(frames), false);
    if (!engine) return sent;

    engine->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    engine->setGateOpen(true);

    uint8_t out[dumble::kMaxPacketBytes];
    uint64_t fn = 0;
    uint32_t flags = 0;
    for (int f = 0; f < frames; f++) {
        engine->onPcm(clip.pcm.data() + size_t(f) * dumble::kFrameSamples, dumble::kFrameSamples);
        // Drain: an opening edge emits nothing itself and queues a burst, so one poll per frame
        // would fall behind the audio and never catch up.
        while (engine->pollPacket(out, sizeof(out), &fn, &flags) > 0) {
            for (int k = 0; k < dumble::kFramesPerPacket; k++) {
                const uint64_t covered = fn + uint64_t(k);
                if (covered < uint64_t(frames)) sent[size_t(covered)] = true;
            }
        }
    }
    return sent;
}

}  // namespace

TEST(VoiceActivityEval, TheEngineTransmitsEverythingTheModelPredicts) {
    // The bars above score a modelled preroll back-fill. This is what entitles them to be read as a
    // statement about the engine: every frame the model claims goes out must actually go out. The
    // relation is coverage, not equality — the engine flushes whole packets and opens on a packet
    // boundary, so it can only transmit MORE than the frame-granular model, never less. That is the
    // direction the deferral rationale has always asserted; this measures it.
    const auto blob = dumble::fixture::weightBlob();
    for (const std::string& name : dumble::fixture::clipNames()) {
        const Clip clip = dumble::eval::loadClip(name);
        ASSERT_FALSE(clip.pcm.empty()) << name;

        auto va = VoiceActivity::create(blob.data(), blob.size());
        ASSERT_TRUE(va) << name;
        const auto modelled = transmitTrace(clip, *va).transmit;
        const auto actual = engineTransmitTrace(clip);
        ASSERT_EQ(modelled.size(), actual.size()) << name;

        int modelledFrames = 0, missing = 0, firstMissing = -1;
        for (size_t f = 0; f < modelled.size(); f++) {
            if (!modelled[f]) continue;
            modelledFrames++;
            if (!actual[f]) { missing++; if (firstMissing < 0) firstMissing = int(f); }
        }
        EXPECT_GT(modelledFrames, 0) << name << ": the model transmitted nothing, so this proves nothing";
        EXPECT_EQ(0, missing) << name << ": the engine dropped " << missing << " of " << modelledFrames
                              << " modelled frames, first at frame " << firstMissing
                              << " — the bars above are not a floor on shipped behaviour";
    }
}
