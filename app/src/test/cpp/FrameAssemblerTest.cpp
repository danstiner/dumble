#include <gtest/gtest.h>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/FrameAssembler.h"

using dumble::FrameAssembler;
using dumble::PcmRing;

// Burst size is a device property and our 960-sample frame is a protocol choice, so the two align
// or not depending on the handset. 128 and 256 leave a frame boundary mid-burst — the case that can
// lose or duplicate a sample; 96, 192 and 240 divide 960 exactly and pin that the aligned case
// stays exact too. Frames must come out identical either way.
TEST(FrameAssembler, BuildsExactFramesFromAnyDeviceBurstSize) {
    for (uint32_t burst : {96u, 128u, 192u, 240u, 256u}) {
        PcmRing ring(16384);
        FrameAssembler fa(dumble::kTxFrameSamples);
        std::vector<int16_t> frame(dumble::kTxFrameSamples);
        int16_t next = 0;
        std::vector<int16_t> in(burst);
        // Enough bursts for three whole frames.
        const uint32_t bursts = (3 * dumble::kTxFrameSamples + burst - 1) / burst;
        int16_t expect = 0;
        int frames = 0;
        for (uint32_t b = 0; b < bursts; b++) {
            for (uint32_t i = 0; i < burst; i++) in[i] = next++;
            ASSERT_TRUE(ring.write(in.data(), burst)) << "burst " << burst;
            while (fa.takeFrame(ring, frame.data())) {
                for (int i = 0; i < dumble::kTxFrameSamples; i++)
                    ASSERT_EQ(expect++, frame[i]) << "burst " << burst << " frame " << frames;
                frames++;
            }
        }
        EXPECT_EQ(3, frames) << "burst " << burst;
    }
}

TEST(FrameAssembler, FlushZeroPadsAShortTail) {
    PcmRing ring(1024);
    FrameAssembler fa(dumble::kTxFrameSamples);
    std::vector<int16_t> in(100, 5);
    ASSERT_TRUE(ring.write(in.data(), 100));
    std::vector<int16_t> frame(dumble::kTxFrameSamples, -1);
    EXPECT_FALSE(fa.takeFrame(ring, frame.data()));
    fa.flushFrame(ring, frame.data(), ring.available());
    for (int i = 0; i < 100; i++) EXPECT_EQ(5, frame[i]);
    for (int i = 100; i < dumble::kTxFrameSamples; i++) EXPECT_EQ(0, frame[i]);
}

// The budget is the caller's span boundary: audio past it was captured after the spurt closed
// and must survive the flush untouched, not be swept into the terminator packet.
TEST(FrameAssembler, FlushTakesOnlyItsBudgetAndLeavesTheRest) {
    PcmRing ring(1024);
    FrameAssembler fa(dumble::kTxFrameSamples);
    std::vector<int16_t> in(300);
    for (int i = 0; i < 300; i++) in[i] = int16_t(i + 1);
    ASSERT_TRUE(ring.write(in.data(), 300));
    std::vector<int16_t> frame(dumble::kTxFrameSamples, -1);
    fa.flushFrame(ring, frame.data(), 100);
    for (int i = 0; i < 100; i++) EXPECT_EQ(i + 1, frame[i]);
    for (int i = 100; i < dumble::kTxFrameSamples; i++) EXPECT_EQ(0, frame[i]);
    EXPECT_EQ(200u, ring.available());
}

// Pins that the guard is unconditional — it must hold in release builds, where an assert
// would vanish. A negative is no longer expressible at the signature; zero is what remains.
TEST(FrameAssemblerDeathTest, RejectsAZeroFrameSize) {
    EXPECT_DEATH(FrameAssembler(0), "");
}

TEST(FrameAssembler, FlushOnAnEmptyRingStillProducesASilentFrame) {
    PcmRing ring(1024);
    FrameAssembler fa(dumble::kTxFrameSamples);
    std::vector<int16_t> frame(dumble::kTxFrameSamples, -1);
    fa.flushFrame(ring, frame.data(), uint32_t(dumble::kTxFrameSamples));
    for (int i = 0; i < dumble::kTxFrameSamples; i++) EXPECT_EQ(0, frame[i]);
}
