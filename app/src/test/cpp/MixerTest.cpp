#include <gtest/gtest.h>
#include "core/Mixer.h"
#include "core/PlayoutConstants.h"

using dumble::playout::mixAccumulate;
using dumble::playout::mixFinalize;

TEST(Mixer, QuietAudioPassesAtUnityGain) {
    int32_t acc[4] = {0, 0, 0, 0};
    const int16_t src[4] = {100, -100, 5000, -5000};
    mixAccumulate(acc, src, 4);
    int16_t out[4] = {0, 0, 0, 0};
    mixFinalize(acc, out, 4);
    EXPECT_EQ(100, out[0]);
    EXPECT_EQ(-100, out[1]);
    EXPECT_EQ(5000, out[2]);
    EXPECT_EQ(-5000, out[3]);
}

TEST(Mixer, DoubleTalkDoesNotClip) {
    // Two near-full-scale sources; a naive sum would wrap int16.
    int32_t acc[2] = {0, 0};
    const int16_t src[2] = {30000, -30000};
    mixAccumulate(acc, src, 2);
    mixAccumulate(acc, src, 2);
    int16_t out[2] = {0, 0};
    mixFinalize(acc, out, 2);
    EXPECT_GT(out[0], 26214) << "positive excursion wrapped";
    EXPECT_LT(out[1], -26214) << "negative excursion wrapped";
}

TEST(Mixer, WorstCaseSpeakerCountStillLimits) {
    // The loudest input the contract allows: kMaxSpeakers full-scale streams (2^21). mixFinalize's
    // int32 math relies on this bound; anything beyond is out of contract.
    int32_t neg[1] = {0};
    int32_t pos[1] = {0};
    const int16_t low[1] = {-32768};
    const int16_t high[1] = {32767};
    for (int i = 0; i < dumble::playout::kMaxSpeakers; i++) {
        mixAccumulate(neg, low, 1);
        mixAccumulate(pos, high, 1);
    }
    int16_t out[1] = {0};
    mixFinalize(neg, out, 1);
    EXPECT_LT(out[0], -32000) << "negative extreme collapsed";
    mixFinalize(pos, out, 1);
    EXPECT_GT(out[0], 32000) << "positive extreme collapsed";
}

TEST(Mixer, LimiterIsSymmetric) {
    int32_t pos[1] = {0};
    int32_t neg[1] = {0};
    const int16_t high[1] = {32767};
    const int16_t low[1] = {-32767};
    mixAccumulate(pos, high, 1);
    mixAccumulate(pos, high, 1);
    mixAccumulate(neg, low, 1);
    mixAccumulate(neg, low, 1);
    int16_t p[1] = {0};
    int16_t n[1] = {0};
    mixFinalize(pos, p, 1);
    mixFinalize(neg, n, 1);
    EXPECT_EQ(int(p[0]), -int(n[0]));
}

TEST(Mixer, ExactlyAtTheThresholdStaysUnityGain) {
    // The knee is inclusive at THRESHOLD; a strict comparison here would introduce a discontinuity
    // of one least-significant bit exactly where speech peaks sit.
    int32_t acc[2] = {26214, -26214};
    int16_t out[2] = {0, 0};
    mixFinalize(acc, out, 2);
    EXPECT_EQ(26214, out[0]);
    EXPECT_EQ(-26214, out[1]);
}
