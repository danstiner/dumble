#include <gtest/gtest.h>
#include "core/CaptureConstants.h"
#include "core/VoiceActivity.h"

using dumble::SpeechGate;

namespace {

constexpr float kBetween = (dumble::kOpenLevel + dumble::kCloseLevel) / 2.0f;

}  // namespace

TEST(SpeechGate, StaysShutBelowTheOpenLevel) {
    SpeechGate gate;
    for (int frame = 0; frame < 100; frame++) {
        const auto decision = gate.update(kBetween);
        ASSERT_FALSE(decision.transmit) << "frame " << frame;
        ASSERT_FALSE(decision.closing) << "frame " << frame;
    }
}

TEST(SpeechGate, HangoverHoldsThenClosesExactlyOnce) {
    SpeechGate gate;
    ASSERT_TRUE(gate.update(1.0f).transmit);

    for (int frame = 1; frame < dumble::kHangoverFrames; frame++) {
        const auto decision = gate.update(0.0f);
        EXPECT_TRUE(decision.transmit) << "hangover frame " << frame;
        EXPECT_FALSE(decision.closing) << "hangover frame " << frame;
    }
    const auto closing = gate.update(0.0f);
    EXPECT_TRUE(closing.transmit) << "the closing frame still carries audio";
    EXPECT_TRUE(closing.closing);

    const auto after = gate.update(0.0f);
    EXPECT_FALSE(after.transmit);
    EXPECT_FALSE(after.closing);
}

TEST(SpeechGate, HysteresisKeepsTheGateOpenBetweenTheThresholds) {
    SpeechGate gate;
    // Open, then drop to a level that would not have opened it: stays open, no hangover consumed.
    ASSERT_TRUE(gate.update(1.0f).transmit);
    for (int frame = 0; frame < dumble::kHangoverFrames * 2; frame++)
        EXPECT_TRUE(gate.update(kBetween).transmit) << "frame " << frame;
}

TEST(SpeechGate, SpeechInsideTheHangoverRearmsItWithoutClosing) {
    // The hangover is a hold, not a countdown to a close: a level above the close threshold part
    // way through it must restore the full hold rather than let the spurt end on schedule.
    SpeechGate gate;
    ASSERT_TRUE(gate.update(1.0f).transmit);
    for (int frame = 1; frame < dumble::kHangoverFrames; frame++) ASSERT_FALSE(gate.update(0.0f).closing);
    ASSERT_FALSE(gate.update(1.0f).closing);
    for (int frame = 1; frame < dumble::kHangoverFrames; frame++)
        EXPECT_FALSE(gate.update(0.0f).closing) << "frame " << frame;
    EXPECT_TRUE(gate.update(0.0f).closing);
}

TEST(SpeechGate, TheHangoverRunsFromTheFirstQuietFrameUntilSpeechReturnsOrTheGateCloses) {
    SpeechGate gate;
    gate.update(1.0f);
    EXPECT_FALSE(gate.inHangover()) << "speech";
    gate.update(kBetween);
    EXPECT_FALSE(gate.inHangover()) << "above the close level is still speech";
    for (int frame = 1; frame < dumble::kHangoverFrames; frame++) {
        gate.update(0.0f);
        EXPECT_TRUE(gate.inHangover()) << "hangover frame " << frame;
    }
    gate.update(1.0f);
    EXPECT_FALSE(gate.inHangover()) << "speech came back";
    for (int frame = 1; frame < dumble::kHangoverFrames; frame++) gate.update(0.0f);
    ASSERT_TRUE(gate.update(0.0f).closing);
    EXPECT_FALSE(gate.inHangover()) << "a closed gate has no hangover";
}

TEST(SpeechGate, ResetShutsAnOpenGateWithoutAClosingFrame) {
    SpeechGate gate;
    ASSERT_TRUE(gate.update(1.0f).transmit);
    gate.reset();
    // A reset is a discontinuity, not the end of a spurt: whoever reset owns the terminator.
    const auto decision = gate.update(0.0f);
    EXPECT_FALSE(decision.transmit);
    EXPECT_FALSE(decision.closing);
}
