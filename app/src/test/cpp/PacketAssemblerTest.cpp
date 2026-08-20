#include <gtest/gtest.h>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/PacketAssembler.h"

using dumble::PacketAssembler;
using dumble::PcmRing;

// Burst size is a device property and our 960-sample packet is a protocol choice, so the two align
// or not depending on the handset. 128 and 256 leave a packet boundary mid-burst — the case that can
// lose or duplicate a sample; 96, 192 and 240 divide 960 exactly and pin that the aligned case
// stays exact too. Packets must come out identical either way.
TEST(PacketAssembler, BuildsExactPacketsFromAnyDeviceBurstSize) {
    for (uint32_t burst : {96u, 128u, 192u, 240u, 256u}) {
        PcmRing ring(16384);
        PacketAssembler fa(dumble::kTxPacketSamples);
        std::vector<int16_t> packet(dumble::kTxPacketSamples);
        int16_t next = 0;
        std::vector<int16_t> in(burst);
        // Enough bursts for three whole packets.
        const uint32_t bursts = (3 * dumble::kTxPacketSamples + burst - 1) / burst;
        int16_t expect = 0;
        int packets = 0;
        for (uint32_t b = 0; b < bursts; b++) {
            for (uint32_t i = 0; i < burst; i++) in[i] = next++;
            ASSERT_TRUE(ring.write(in.data(), burst)) << "burst " << burst;
            while (fa.takePacket(ring, packet.data())) {
                for (int i = 0; i < dumble::kTxPacketSamples; i++)
                    ASSERT_EQ(expect++, packet[i]) << "burst " << burst << " packet " << packets;
                packets++;
            }
        }
        EXPECT_EQ(3, packets) << "burst " << burst;
    }
}

TEST(PacketAssembler, FlushZeroPadsAShortTail) {
    PcmRing ring(1024);
    PacketAssembler fa(dumble::kTxPacketSamples);
    std::vector<int16_t> in(100, 5);
    ASSERT_TRUE(ring.write(in.data(), 100));
    std::vector<int16_t> packet(dumble::kTxPacketSamples, -1);
    EXPECT_FALSE(fa.takePacket(ring, packet.data()));
    fa.flushPacket(ring, packet.data(), ring.available());
    for (int i = 0; i < 100; i++) EXPECT_EQ(5, packet[i]);
    for (int i = 100; i < dumble::kTxPacketSamples; i++) EXPECT_EQ(0, packet[i]);
}

// The budget is the caller's span boundary: audio past it was captured after the spurt closed
// and must survive the flush untouched, not be swept into the terminator packet.
TEST(PacketAssembler, FlushTakesOnlyItsBudgetAndLeavesTheRest) {
    PcmRing ring(1024);
    PacketAssembler fa(dumble::kTxPacketSamples);
    std::vector<int16_t> in(300);
    for (int i = 0; i < 300; i++) in[i] = int16_t(i + 1);
    ASSERT_TRUE(ring.write(in.data(), 300));
    std::vector<int16_t> packet(dumble::kTxPacketSamples, -1);
    fa.flushPacket(ring, packet.data(), 100);
    for (int i = 0; i < 100; i++) EXPECT_EQ(i + 1, packet[i]);
    for (int i = 100; i < dumble::kTxPacketSamples; i++) EXPECT_EQ(0, packet[i]);
    EXPECT_EQ(200u, ring.available());
}

// Pins that the guard is unconditional — it must hold in release builds, where an assert
// would vanish. A negative is no longer expressible at the signature; zero is what remains.
TEST(PacketAssemblerDeathTest, RejectsAZeroPacketSize) {
    EXPECT_DEATH(PacketAssembler(0), "");
}

TEST(PacketAssembler, FlushOnAnEmptyRingStillProducesASilentPacket) {
    PcmRing ring(1024);
    PacketAssembler fa(dumble::kTxPacketSamples);
    std::vector<int16_t> packet(dumble::kTxPacketSamples, -1);
    fa.flushPacket(ring, packet.data(), uint32_t(dumble::kTxPacketSamples));
    for (int i = 0; i < dumble::kTxPacketSamples; i++) EXPECT_EQ(0, packet[i]);
}
