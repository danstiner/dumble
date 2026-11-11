#include <gtest/gtest.h>
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <random>
#include <sstream>
#include <string>
#include <vector>
#include "LatencyFixture.h"
#include "WavFixture.h"
#include "core/BootClock.h"
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"
#include "core/LatencyProbe.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

// Dumble's own voice path, capture through playout, on a virtual clock: CaptureEngine fed in
// input-callback bursts, each packet delayed by a scripted network, PlayoutEngine pulled in
// output-callback bursts, and a probe found again in the output. The figures exclude the device's
// input and output buffers, which a phone adds on top. Everything is sample-exact, so the figures
// are pinned in latency/expected.txt and a change to any of them is a reviewed diff.
namespace {

using namespace dumble;
namespace pl = dumble::playout;
namespace lat = dumble::latency;

constexpr int64_t kMs = pl::kSamplesPerMilli;
constexpr int16_t kProbePeak = 8000;
constexpr int kNoiseAmplitude = 100;
constexpr int kProbes = 4;
constexpr int64_t kFirstProbe = 300 * kMs;
constexpr int64_t kProbeSpacing = 500 * kMs;
// Each probe sits 5 ms later within its packet than the last, so the four cover the phases.
constexpr int64_t kPhaseStep = 5 * kMs;
// The probe's 250 ms plus up to 200 ms of delay; shorter than the spacing, so one probe per window.
constexpr int64_t kSearchWindow = 450 * kMs;
constexpr int64_t kWarmSpurt = 1000 * kMs;
constexpr int64_t kWarmGap = 1000 * kMs;
// Each spurt must open where a frame and every scenario's input burst begin: packet starts
// are read from frame numbers, which round down, and an error there lands in jitterBuffer.
static_assert((kWarmSpurt + kWarmGap) % kTxPacketSamples == 0);
constexpr int32_t kSession = 1;

struct Scenario {
    const char* name;
    bool warm;   // a 1 s spurt and a 1 s gap before the measured spurt
    int inMs, outMs, networkMs, jitterMs;
};

constexpr Scenario kScenarios[] = {
    {"cold", false, 20, 4, 0, 0},
    {"warm", true, 20, 4, 0, 0},
    {"warm_network20", true, 20, 4, 20, 0},
    {"warm_network20_jitter40", true, 20, 4, 20, 40},
    {"warm_callbacks2", true, 2, 2, 0, 0},
    {"warm_in20_out10", true, 20, 10, 0, 0},
};

struct Breakdown {
    int64_t total, encode, network, jitterBuffer;   // samples
};

std::string ms(int64_t samples) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.2f", double(samples) / kMs);
    return buf;
}

// Opus's own delay at Dumble's settings, measured the way the harness measures everything else.
int64_t codecDelay() {
    const auto p = lat::probe(kProbePeak);
    std::vector<int16_t> in(int64_t(kSampleRate), 0);
    const int64_t at = 4 * kTxPacketSamples;
    std::copy(p.begin(), p.end(), in.begin() + at);
    const auto out = latencytest::opusRoundTrip(in);
    const auto d = lat::find(out.data(), int64_t(out.size()), p);
    EXPECT_TRUE(d.found());
    return d.offset - at;
}

std::vector<Breakdown> run(const Scenario& s, int64_t codec) {
    const int64_t inSamples = s.inMs * kMs, outSamples = s.outMs * kMs;
    const int64_t spurtStart = s.warm ? kWarmSpurt + kWarmGap : 0;
    std::vector<int64_t> placements;
    for (int k = 0; k < kProbes; k++) {
        placements.push_back(spurtStart + kFirstProbe + k * (kProbeSpacing + kPhaseStep));
    }
    const int64_t total = placements.back() + kSearchWindow;

    auto in = latencytest::noise(total, kNoiseAmplitude, 7);
    const auto p = lat::probe(kProbePeak);
    for (int64_t at : placements) {
        for (size_t i = 0; i < p.size(); i++) in[at + i] = int16_t(in[at + i] + p[i]);
    }

    auto capture = CaptureEngine::create(latencytest::kTransmitBitrate, fixture::weightBlob().data(), fixture::weightBlob().size());
    capture->setTransmitMode(TransmitMode::PushToTalk);
    capture->setWaitMillisForTest(0);
    auto playout = pl::PlayoutEngine::create(kSampleRate, pl::kMaxPacketSamples);
    EXPECT_TRUE(capture && playout);
    if (!capture || !playout) {
        return {};
    }
    playout->setWriteAheadSamples(int(2 * outSamples));

    struct Sent { std::vector<uint8_t> data; uint64_t frame; bool terminator; int64_t ready, arrival; };
    std::vector<Sent> sent;
    size_t delivered = 0;
    std::mt19937 jitter(11);
    int64_t lastArrival = 0;
    std::vector<int16_t> out(total + outSamples, 0);
    int32_t sessions[pl::kMaxSpeakers];
    int32_t live = 0;
    uint8_t buf[kMaxPacketBytes];

    capture->setGateOpen(true);
    for (int64_t t = 0; t < total; t += kMs) {
        if (t > 0 && t % inSamples == 0) {
            capture->onPcm(in.data() + t - inSamples, uint32_t(inSamples));
            uint64_t frame;
            uint32_t flags;
            int n;
            while ((n = capture->pollPacket(buf, sizeof buf, &frame, &flags)) > 0) {
                const int64_t delay =
                    (s.networkMs + (s.jitterMs ? int64_t(jitter() % uint32_t(s.jitterMs + 1)) : 0)) * kMs;
                // In order, as over TCP: jitter never reorders, so a packet whose delay ends before
                // an earlier one's waits for it, and that wait is part of its network row.
                lastArrival = std::max(lastArrival, t + delay);
                sent.push_back({{buf, buf + n}, frame, (flags & kFlagTerminator) != 0, t, lastArrival});
            }
        }
        // After the input burst, so a gate change takes effect from sample t exactly.
        if (s.warm && t == kWarmSpurt) capture->setGateOpen(false);
        if (s.warm && t == spurtStart) capture->setGateOpen(true);
        for (; delivered < sent.size() && sent[delivered].arrival <= t; delivered++) {
            const auto& q = sent[delivered];
            playout->offer(kSession, q.data.data(), int(q.data.size()), q.frame, q.terminator,
                           BootClock::time_point(BootClock::duration(q.arrival / kMs)));
        }
        if (t % outSamples == 0) playout->fillQuantum(out.data() + t, int(outSamples), sessions, &live);
    }

    std::vector<Breakdown> probes;
    for (int64_t at : placements) {
        const auto d = lat::find(out.data() + at, kSearchWindow, p);
        EXPECT_TRUE(d.found()) << s.name << ": probe at " << ms(at) << " ms not found (" << d.peak
                               << " over " << d.nextPeak << "); if latency grew past 200 ms, widen "
                               << "kSearchWindow and kProbeSpacing together";
        if (!d.found()) continue;
        const uint64_t onsetFrame = uint64_t(at / kFrameSamples);
        const auto carrier = std::find_if(sent.begin(), sent.end(), [&](const Sent& q) {
            return q.frame <= onsetFrame && onsetFrame < q.frame + kFramesPerPacket;
        });
        EXPECT_NE(carrier, sent.end()) << s.name << ": no packet carries frame " << onsetFrame;
        if (carrier == sent.end()) continue;
        const int64_t packetStart = int64_t(carrier->frame) * kFrameSamples;
        Breakdown r;
        r.total = d.offset;
        r.encode = carrier->ready - packetStart;
        r.network = carrier->arrival - carrier->ready;
        r.jitterBuffer = r.total - r.encode - r.network - codec;
        probes.push_back(r);
    }
    return probes;
}

int64_t median(std::vector<int64_t> v) {
    std::sort(v.begin(), v.end());
    return (v[(v.size() - 1) / 2] + v[v.size() / 2]) / 2;
}

TEST(LatencyHarness, MatchesExpected) {
    const int64_t codec = codecDelay();
    std::ostringstream actual;
    actual << "codec_delay_ms = " << ms(codec) << "\n";
    for (const auto& s : kScenarios) {
        const auto probes = run(s, codec);
        ASSERT_EQ(probes.size(), size_t(kProbes)) << s.name;
        std::vector<int64_t> totals, buffers;
        std::printf("%s\n", s.name);
        for (const auto& r : probes) {
            std::printf("  total %7s = encode %6s + network %6s + jitter buffer %7s + codec %s\n",
                        ms(r.total).c_str(), ms(r.encode).c_str(), ms(r.network).c_str(),
                        ms(r.jitterBuffer).c_str(), ms(codec).c_str());
            totals.push_back(r.total);
            buffers.push_back(r.jitterBuffer);
        }
        actual << s.name << ".total_min_ms = " << ms(*std::min_element(totals.begin(), totals.end())) << "\n"
               << s.name << ".total_median_ms = " << ms(median(totals)) << "\n"
               << s.name << ".total_max_ms = " << ms(*std::max_element(totals.begin(), totals.end())) << "\n"
               << s.name << ".jitter_buffer_median_ms = " << ms(median(buffers)) << "\n";
    }

    const std::string path = std::string(DUMBLE_TEST_DATA_DIR) + "/latency/expected.txt";
    const char* update = std::getenv("DUMBLE_UPDATE_EXPECTED");
    if (update && std::string(update) == "1") {
        ASSERT_FALSE(HasFailure()) << "not rewriting " << path << " from a failed run";
        std::ofstream out(path);
        out << actual.str();
        ASSERT_TRUE(out) << "cannot write " << path;
        std::printf("rewrote %s\n", path.c_str());
        return;
    }
    std::ifstream file(path);
    ASSERT_TRUE(file) << "cannot read " << path;
    std::stringstream expected;
    expected << file.rdbuf();
    EXPECT_EQ(actual.str(), expected.str())
        << "Latency changed. If intended, rewrite the pinned figures and commit them:\n"
        << "  cmake --build build/host && DUMBLE_UPDATE_EXPECTED=1 ctest --test-dir build/host -R Latency";
}

}  // namespace
