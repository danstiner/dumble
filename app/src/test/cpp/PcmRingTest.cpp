#include <gtest/gtest.h>
#include <atomic>
#include <thread>
#include <vector>
#include "core/PcmRing.h"

using dumble::PcmRing;

TEST(PcmRing, ReadExactReturnsZeroUntilAFullRequestIsAvailable) {
    PcmRing ring(64);
    std::vector<int16_t> in(10, 7);
    ASSERT_TRUE(ring.write(in.data(), 10));
    std::vector<int16_t> out(20);
    EXPECT_EQ(0u, ring.readExact(out.data(), 20));
    EXPECT_EQ(10u, ring.available());
}

#ifndef NDEBUG
// Debug builds assert on a non-power-of-two request — a caller asking for one size and silently
// getting another. Release builds round up and carry on, so NDEBUG compiles the assert away and
// this test with it; the host tree defaults to Debug precisely so it runs.
TEST(PcmRingDeathTest, DebugBuildsRejectANonPowerOfTwoCapacity) {
    EXPECT_DEATH(PcmRing(6), "power of two");
    EXPECT_DEATH(PcmRing(0), "power of two");
}
#endif

TEST(PcmRing, ReadUpToTakesWhatIsThereBoundedByTheAsk) {
    PcmRing ring(64);
    std::vector<int16_t> in(10);
    for (int i = 0; i < 10; i++) in[i] = int16_t(i);
    ASSERT_TRUE(ring.write(in.data(), 10));
    std::vector<int16_t> out(20, -1);
    ASSERT_EQ(6u, ring.readUpTo(out.data(), 6));   // bounded by the ask
    for (int i = 0; i < 6; i++) EXPECT_EQ(i, out[i]);
    ASSERT_EQ(4u, ring.readUpTo(out.data(), 20));  // bounded by what's buffered
    for (int i = 0; i < 4; i++) EXPECT_EQ(6 + i, out[i]);
    EXPECT_EQ(0u, ring.readUpTo(out.data(), 20));  // empty ring reads nothing
}

TEST(PcmRing, WrapsAroundWithoutLosingOrder) {
    PcmRing ring(8);
    std::vector<int16_t> out(6);
    for (int16_t base = 0; base < 30; base += 6) {
        std::vector<int16_t> in{base, int16_t(base + 1), int16_t(base + 2),
                                int16_t(base + 3), int16_t(base + 4), int16_t(base + 5)};
        ASSERT_TRUE(ring.write(in.data(), 6));
        ASSERT_EQ(6u, ring.readExact(out.data(), 6));
        for (int i = 0; i < 6; i++) EXPECT_EQ(base + i, out[i]);
    }
}

TEST(PcmRing, WriteThatDoesNotFitIsDroppedWholeAndCounted) {
    PcmRing ring(8);
    std::vector<int16_t> in(6, 1);
    ASSERT_TRUE(ring.write(in.data(), 6));
    // Only 2 slots left; a 6-sample write does not fit and must be refused whole.
    EXPECT_FALSE(ring.write(in.data(), 6));
    EXPECT_EQ(1u, ring.droppedWrites());
    EXPECT_EQ(6u, ring.available());  // existing data untouched
}

TEST(PcmRing, SkipToNewestBoundsStalenessAndCountsSamples) {
    PcmRing ring(32);
    std::vector<int16_t> in(24);
    for (int i = 0; i < 24; i++) in[i] = int16_t(i);
    ASSERT_TRUE(ring.write(in.data(), 24));
    ring.skipToNewest(4);
    EXPECT_EQ(4u, ring.available());
    EXPECT_EQ(20u, ring.skippedSamples());
    std::vector<int16_t> out(4);
    ASSERT_EQ(4u, ring.readExact(out.data(), 4));
    EXPECT_EQ(20, out[0]);  // the newest 4, not the oldest
}

TEST(PcmRing, ResetDiscardsEverything) {
    PcmRing ring(16);
    std::vector<int16_t> in(12, 3);
    ASSERT_TRUE(ring.write(in.data(), 12));
    ring.reset();
    EXPECT_EQ(0u, ring.available());
}

// The property CaptureEngine rests on: marks measure the stream, not the buffer, so discarding
// buffered audio moves the read mark forward to meet the write mark rather than rewinding either.
// A wrapped-pointer implementation would fail this the moment the ring wrapped.
TEST(PcmRing, MarksTrackTheStreamAcrossDiscards) {
    PcmRing ring(16);
    std::vector<int16_t> in(12, 7);
    ASSERT_TRUE(ring.write(in.data(), 12));
    EXPECT_EQ(12u, ring.writeIndex());
    EXPECT_EQ(0u, ring.readIndex());

    ring.skipToNewest(4);
    EXPECT_EQ(12u, ring.writeIndex());  // discarding does not unwrite
    EXPECT_EQ(8u, ring.readIndex());

    // Wrapping past the 16-sample buffer must not wrap the marks.
    ASSERT_TRUE(ring.write(in.data(), 12));
    ring.reset();
    EXPECT_EQ(24u, ring.writeIndex());
    EXPECT_EQ(24u, ring.readIndex());
    EXPECT_EQ(0u, ring.available());
}

// A wrap-split, index-mapping, duplication, lost-sample, or stale-read defect has to surface
// through one of three independent checks: samples within a read are consecutive, burst indices
// strictly increase (dropped bursts appear as gaps), and every accepted burst is read exactly
// once. A constant payload would pass all three blind once the ring has wrapped.
TEST(PcmRing, SurvivesConcurrentProducerAndConsumer) {
    PcmRing ring(1024);
    constexpr int kBursts = 100000, kBurst = 96;
    std::atomic<bool> done{false};
    uint64_t acceptedBursts = 0;  // producer-owned; read only after join()
    std::thread producer([&] {
        std::vector<int16_t> buf(kBurst);
        for (int i = 0; i < kBursts; i++) {
            for (int j = 0; j < kBurst; j++) buf[j] = int16_t(i * kBurst + j);
            if (ring.write(buf.data(), kBurst)) acceptedBursts++;
        }
        done = true;
    });
    std::vector<int16_t> out(kBurst);
    uint64_t readBursts = 0;
    int64_t lastBurst = -1;
    bool corrupt = false;
    while (!corrupt && (!done || ring.available() >= kBurst)) {
        if (ring.readExact(out.data(), kBurst) != uint32_t(kBurst)) continue;
        // The ring starts empty and both sides move in whole bursts, so every successful read is
        // exactly one producer burst. Recover its index from the first sample by scanning forward
        // from the last one seen — the payload wraps in int16, so equality search, not arithmetic.
        int64_t b = lastBurst + 1;
        while (b < kBursts && int16_t(b * kBurst) != out[0]) b++;
        corrupt = b >= kBursts;
        for (int j = 0; !corrupt && j < kBurst; j++) corrupt = out[j] != int16_t(b * kBurst + j);
        lastBurst = b;
        readBursts++;
    }
    // Break-then-join rather than asserting mid-loop: a failing ASSERT returns out of the test
    // with the producer still joinable, and ~thread() on a joinable thread is std::terminate.
    producer.join();
    EXPECT_FALSE(corrupt) << "read data matches no producer burst after #" << lastBurst;
    if (!corrupt) EXPECT_EQ(acceptedBursts, readBursts);  // every accepted burst read once, in order
    EXPECT_GT(readBursts, 0u);
}
