#include <gtest/gtest.h>
#include <string>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/VoiceActivity.h"
#include "WavFixture.h"

using dumble::VoiceActivity;

namespace {

std::unique_ptr<VoiceActivity> make() {
    const auto blob = dumble::fixture::weightBlob();
    return VoiceActivity::create(blob.data(), blob.size());
}

std::vector<int16_t> silence() { return std::vector<int16_t>(dumble::kFrameSamples, 0); }

std::vector<int16_t> speech() {
    return dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
}

}  // namespace

TEST(VoiceActivity, RefusesABlobOfTheWrongSize) {
    std::vector<float> tooSmall(16, 0.0f);
    EXPECT_EQ(nullptr, VoiceActivity::create(tooSmall.data(), tooSmall.size() * sizeof(float)));
}

TEST(VoiceActivity, InferenceRunsOnAFixedFiveWindowsPerSixteenFramesCycle) {
    auto va = make();
    ASSERT_TRUE(va);
    const auto quiet = silence();
    int inferences = 0;
    std::vector<int> gaps;
    int sinceLast = 0;
    for (int frame = 0; frame < 64; frame++) {
        va->update(quiet.data());
        sinceLast++;
        if (va->inferences() != inferences) {
            inferences = va->inferences();
            gaps.push_back(sinceLast);
            sinceLast = 0;
        }
    }
    // 64 frames is four full cycles: 20 windows, and the gap pattern repeats 4,3,3,3,3.
    EXPECT_EQ(20, inferences);
    ASSERT_GE(gaps.size(), 10u);
    const std::vector<int> expected{4, 3, 3, 3, 3, 4, 3, 3, 3, 3};
    for (size_t i = 0; i < expected.size(); i++) EXPECT_EQ(expected[i], gaps[i]) << "gap " << i;
}

TEST(VoiceActivity, SilenceNeverOpensTheGate) {
    auto va = make();
    ASSERT_TRUE(va);
    const auto quiet = silence();
    for (int frame = 0; frame < 200; frame++) {
        const auto decision = va->update(quiet.data());
        ASSERT_FALSE(decision.transmit) << "frame " << frame;
        ASSERT_FALSE(decision.closing) << "frame " << frame;
    }
}

TEST(VoiceActivity, ResetReproducesAColdStartOverRealAudio) {
    // Require the second pass to reproduce the first exactly — probabilities AND decisions — so
    // this pins every stateful member at once, including the ones reset() could silently stop
    // clearing. Decisions matter separately from probabilities: they are the only view of the gate.
    auto va = make();
    ASSERT_TRUE(va);
    const auto pcm = speech();
    const int frames = int(pcm.size()) / dumble::kFrameSamples;
    ASSERT_GT(frames, 64);

    struct Frame { float probability; bool transmit, closing; };
    auto run = [&](VoiceActivity& v) {
        std::vector<Frame> out;
        for (int f = 0; f < frames; f++) {
            const auto decision = v.update(pcm.data() + size_t(f) * dumble::kFrameSamples);
            out.push_back({v.lastProbability(), decision.transmit, decision.closing});
        }
        return out;
    };

    const auto first = run(*va);
    va->reset();
    EXPECT_EQ(0, va->inferences());
    EXPECT_FLOAT_EQ(0.0f, va->lastProbability());
    const auto second = run(*va);
    ASSERT_EQ(first.size(), second.size());
    for (size_t i = 0; i < first.size(); i++) {
        EXPECT_FLOAT_EQ(first[i].probability, second[i].probability) << "frame " << i;
        EXPECT_EQ(first[i].transmit, second[i].transmit) << "frame " << i;
        EXPECT_EQ(first[i].closing, second[i].closing) << "frame " << i;
    }
}

TEST(VoiceActivity, ResetShutsAGateRealAudioOpened) {
    // ResetReproducesAColdStart only compares two passes; if the clip happens to end with the gate
    // shut, both passes agree whether or not reset() clears it. This opens the gate for real and
    // requires the very next frame after a reset to be silent, which a missed gate reset cannot
    // survive: it would spend a hangover transmitting and then emit a closing frame.
    auto va = make();
    ASSERT_TRUE(va);
    const auto pcm = speech();
    const int frames = int(pcm.size()) / dumble::kFrameSamples;
    bool opened = false;
    for (int f = 0; f < frames && !opened; f++)
        opened = va->update(pcm.data() + size_t(f) * dumble::kFrameSamples).transmit;
    ASSERT_TRUE(opened) << "the fixture never opened the gate; this test would prove nothing";

    va->reset();
    const auto quiet = silence();
    const auto decision = va->update(quiet.data());
    EXPECT_FALSE(decision.transmit);
    EXPECT_FALSE(decision.closing);
}

TEST(VoiceActivity, HoldsTheProbabilityBetweenInferences) {
    // Most frames run no inference — 160 decimated samples against a 512-sample window — and those
    // frames must report the previous inference's probability. Silence cannot pin this, because
    // held and not-held both read ~0; only real speech-like audio distinguishes them.
    auto va = make();
    ASSERT_TRUE(va);
    const auto pcm = speech();
    const int frames = int(pcm.size()) / dumble::kFrameSamples;

    int previousInferences = 0, heldFrames = 0, movedOnInference = 0;
    float previousProbability = 0.0f;
    for (int f = 0; f < frames; f++) {
        va->update(pcm.data() + size_t(f) * dumble::kFrameSamples);
        if (va->inferences() == previousInferences) {
            EXPECT_FLOAT_EQ(previousProbability, va->lastProbability())
                << "frame " << f << " ran no inference but the probability moved";
            heldFrames++;
        } else if (va->lastProbability() != previousProbability) {
            movedOnInference++;
        }
        previousInferences = va->inferences();
        previousProbability = va->lastProbability();
    }
    // Without these the test could pass vacuously on a clip that never holds or never moves.
    EXPECT_GT(heldFrames, 100) << "no held frames observed";
    EXPECT_GT(movedOnInference, 10) << "the probability never moved; this clip proves nothing";
}

TEST(VoiceActivity, TheFrameThatRunsAnInferenceDecidesOnThatInference) {
    // update() must run the inference BEFORE handing the level to the gate. Feeding the gate the
    // previous probability instead is invisible to every other test here — the reset tests compare
    // a run against itself, and the eval bars carry 20 ms of deliberate slack — but it costs 10 ms
    // of onset on every spurt, which is exactly what the preroll exists to buy back.
    auto va = make();
    ASSERT_TRUE(va);
    const auto pcm = speech();
    const int frames = int(pcm.size()) / dumble::kFrameSamples;
    int previousInferences = 0;
    bool checked = false;
    for (int f = 0; f < frames && !checked; f++) {
        const auto decision = va->update(pcm.data() + size_t(f) * dumble::kFrameSamples);
        const bool ranInference = va->inferences() != previousInferences;
        previousInferences = va->inferences();
        // The first frame whose fresh probability clears the open level must open on that frame.
        if (ranInference && va->lastProbability() > dumble::kOpenLevel) {
            EXPECT_TRUE(decision.transmit)
                << "frame " << f << " computed " << va->lastProbability()
                << " but decided on a stale level";
            checked = true;
        }
    }
    ASSERT_TRUE(checked) << "the fixture never produced an opening inference; this proves nothing";
}
