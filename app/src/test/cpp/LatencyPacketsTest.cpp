#include <gtest/gtest.h>
#include <algorithm>
#include <vector>
#include "LatencyFixture.h"
#include "core/CaptureConstants.h"
#include "core/LatencyPackets.h"
#include "core/LatencyProbe.h"

namespace {

using namespace dumble;
namespace lat = dumble::latency;

constexpr int kBitrate = 32000;
constexpr int16_t kPeak = 1600;

// 200 ms of quiet noise, the probe, 200 ms more: the spurt the device check sends.
std::vector<int16_t> spurt(int64_t lead, uint32_t seed) {
    auto pcm = latencytest::noise(lead + kSampleRate / 4 + kSampleRate / 5, 20, seed);
    const auto p = lat::probe(kPeak);
    for (size_t i = 0; i < p.size(); i++) pcm[lead + i] = int16_t(pcm[lead + i] + p[i]);
    return pcm;
}

TEST(LatencyPackets, EncodeNumbersPacketsInTensOfMillisecondsAndPadsTheLast) {
    const auto packets = lat::encode(std::vector<int16_t>(kTxPacketSamples * 2 + 1, 0), 100, kBitrate);
    ASSERT_EQ(packets.size(), 3u);
    EXPECT_EQ(packets[0].frameNumber, 100u);
    EXPECT_EQ(packets[1].frameNumber, 102u);
    EXPECT_EQ(packets[2].frameNumber, 104u);
}

TEST(LatencyPackets, CodecDelayIsTheHarnesssFigure) {
    EXPECT_EQ(lat::codecDelay(lat::probe(kPeak), kBitrate), 310);
}

TEST(LatencyPackets, LocateNamesWhereTheProbeSatInTheEncodedAudio) {
    const int64_t codec = lat::codecDelay(lat::probe(kPeak), kBitrate);
    for (int64_t lead : {int64_t(kSampleRate / 5), int64_t(kSampleRate / 5 + 333)}) {
        const auto d = lat::locate(lat::encode(spurt(lead, 3), 7000, kBitrate), lat::probe(kPeak), codec);
        EXPECT_TRUE(d.found()) << d.peak << " over " << d.nextPeak;
        EXPECT_EQ(d.offset, lead);
    }
}

TEST(LatencyPackets, AMissingPacketKeepsTheRestInPlace) {
    // A gap before the probe: later packets still decode at their own frame's position.
    const int64_t lead = kSampleRate / 5;
    auto packets = lat::encode(spurt(lead, 4), 0, kBitrate);
    packets.erase(packets.begin() + 3);
    const auto d = lat::locate(packets, lat::probe(kPeak), lat::codecDelay(lat::probe(kPeak), kBitrate));
    EXPECT_TRUE(d.found());
    EXPECT_NEAR(double(d.offset), double(lead), 2);
}

TEST(LatencyPackets, LocateTakesPacketsInAnyOrder) {
    // Taps record packets as they arrive, and a network can reorder them.
    const int64_t lead = kSampleRate / 5;
    auto packets = lat::encode(spurt(lead, 5), 0, kBitrate);
    std::reverse(packets.begin(), packets.end());
    const auto d = lat::locate(packets, lat::probe(kPeak), lat::codecDelay(lat::probe(kPeak), kBitrate));
    EXPECT_TRUE(d.found());
    EXPECT_EQ(d.offset, lead);
}

TEST(LatencyPackets, CodecDelayDoesNotDependOnTheProbesLevel) {
    // The harness measures it at 8000; the device check sends 1600.
    EXPECT_EQ(lat::codecDelay(lat::probe(8000), kBitrate), lat::codecDelay(lat::probe(kPeak), kBitrate));
}

TEST(LatencyPackets, NoPacketsFindNothing) {
    EXPECT_FALSE(lat::locate({}, lat::probe(kPeak), 310).found());
}

}  // namespace
