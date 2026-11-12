#include <gtest/gtest.h>
#include <algorithm>
#include <chrono>
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
// output-callback bursts, and a probe found again in the output. The output is heard one
// write-ahead after it is filled, as the engine is told the sink holds it; the rest of the device's
// input and output latency is excluded, and a phone adds it on top. So is the wait before the
// sending thread notices a packet, up to kPollWaitMillis, and a packet arriving just after a fill,
// which waits up to one output callback more than here. The engines are sample-exact and the
// codec's delay is measured once through the same probe, so the figures are pinned in
// latency/expected.txt and a change to any of them is a reviewed diff.
namespace {

using namespace dumble;
namespace pl = dumble::playout;
namespace lat = dumble::latency;
using std::chrono::milliseconds;
using namespace std::chrono_literals;

constexpr int16_t kProbePeak = 8000;
constexpr int kNoiseAmplitude = 100;
// The delay is the same for every sample of a spurt unless playout adapts inside it, which
// several probes show as a spread between min and max. No scenario here provokes one: a shrink
// needs kShrinkCooldownSamples of produced audio first, and the measured spurt is shorter.
constexpr int kProbes = 4;
constexpr milliseconds kFirstProbe = 300ms;
constexpr milliseconds kProbeSpacing = 500ms;
// The probe's 250 ms plus up to 200 ms of delay; shorter than the spacing, so one probe per window.
constexpr milliseconds kSearchWindow = 450ms;
constexpr milliseconds kWarmSpurt = 1000ms;
constexpr milliseconds kWarmGap = 1000ms;
constexpr int32_t kSession = 1;

struct Scenario {
    const char* name;
    bool warm;   // a 1 s spurt and a 1 s gap before the measured spurt
    milliseconds in, out, network, jitter;   // callback sizes, then the network's delay and its spread
};

constexpr Scenario kScenarios[] = {
    {"cold", false, 20ms, 4ms, 0ms, 0ms},
    {"warm", true, 20ms, 4ms, 0ms, 0ms},
    {"warm_network20", true, 20ms, 4ms, 20ms, 0ms},
    {"warm_network20_jitter40", true, 20ms, 4ms, 20ms, 40ms},
    {"warm_callbacks2", true, 2ms, 2ms, 0ms, 0ms},
    {"warm_in20_out10", true, 20ms, 10ms, 0ms, 0ms},
};

// The gate must change where an input burst ends, or the burst straddling it is fed late, and the
// measured spurt must open where a packet begins: packet starts are read from frame numbers,
// which round down. An error in either lands in jitterBuffer. Probes open where packets begin
// too, so every probe meets its packet at the same point.
constexpr bool spurtsAlign() {
    for (const auto& s : kScenarios) {
        if (kWarmSpurt % s.in != 0ms || (kWarmSpurt + kWarmGap) % s.in != 0ms) {
            return false;
        }
    }
    return pl::samplesIn(kWarmSpurt + kWarmGap) % kTxPacketSamples == 0 &&
           pl::samplesIn(kFirstProbe) % kTxPacketSamples == 0 &&
           pl::samplesIn(kProbeSpacing) % kTxPacketSamples == 0;
}
static_assert(spurtsAlign());

struct Breakdown {
    int64_t total, packetization, network, jitterBuffer, writeAhead;   // samples
};

std::string ms(int64_t samples) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.2f", double(samples) / pl::kSamplesPerMilli);
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
    const milliseconds spurtStart = s.warm ? kWarmSpurt + kWarmGap : 0ms;
    // Two callbacks, as the output stream reports when it opens.
    const milliseconds writeAhead = 2 * s.out;
    std::vector<milliseconds> placements;
    for (int k = 0; k < kProbes; k++) {
        placements.push_back(spurtStart + kFirstProbe + k * kProbeSpacing);
    }
    const milliseconds total = placements.back() + kSearchWindow;

    auto in = latencytest::noise(pl::samplesIn(total), kNoiseAmplitude, 7);
    const auto p = lat::probe(kProbePeak);
    for (milliseconds at : placements) {
        int16_t* onset = in.data() + pl::samplesIn(at);
        for (size_t i = 0; i < p.size(); i++) onset[i] = int16_t(onset[i] + p[i]);
    }

    auto capture = CaptureEngine::create(latencytest::kTransmitBitrate, fixture::weightBlob().data(), fixture::weightBlob().size());
    auto playout = pl::PlayoutEngine::create(kSampleRate, pl::kMaxPacketSamples);
    EXPECT_TRUE(capture && playout);
    if (!capture || !playout) {
        return {};
    }
    capture->setTransmitMode(TransmitMode::PushToTalk);
    capture->setWaitMillisForTest(0);
    playout->setWriteAheadSamples(pl::samplesIn(writeAhead));

    struct Sent { std::vector<uint8_t> data; uint64_t frame; bool terminator; milliseconds ready, arrival; };
    std::vector<Sent> sent;
    size_t delivered = 0;
    std::mt19937 jitter(11);
    milliseconds lastArrival = 0ms;
    std::vector<int16_t> out(pl::samplesIn(total + writeAhead + s.out), 0);
    int32_t sessions[pl::kMaxSpeakers];
    int32_t live = 0;
    uint8_t buf[kMaxPacketBytes];

    capture->setGateOpen(true);
    for (milliseconds t = 0ms; t < total; t += 1ms) {
        if (t > 0ms && t % s.in == 0ms) {
            capture->onPcm(in.data() + pl::samplesIn(t - s.in), uint32_t(pl::samplesIn(s.in)));
            uint64_t frame;
            uint32_t flags;
            int n;
            while ((n = capture->pollPacket(buf, sizeof buf, &frame, &flags)) > 0) {
                const milliseconds spread =
                    s.jitter > 0ms ? milliseconds(jitter() % uint32_t(s.jitter.count() + 1)) : 0ms;
                // In order, as over TCP: jitter never reorders, so a packet whose delay ends before
                // an earlier one's waits for it, and that wait is part of its network row.
                lastArrival = std::max(lastArrival, t + s.network + spread);
                sent.push_back({{buf, buf + n}, frame, (flags & kFlagTerminator) != 0, t, lastArrival});
            }
        }
        // After the input burst, so a gate change takes effect from sample t exactly.
        if (s.warm && t == kWarmSpurt) capture->setGateOpen(false);
        if (s.warm && t == spurtStart) capture->setGateOpen(true);
        // Before this millisecond's fill, so a packet is never one output callback late.
        for (; delivered < sent.size() && sent[delivered].arrival <= t; delivered++) {
            const auto& q = sent[delivered];
            playout->offer(kSession, q.data.data(), int(q.data.size()), q.frame, q.terminator,
                           BootClock::time_point(q.arrival));
        }
        if (t % s.out == 0ms) {
            // What is filled now is heard once the audio the sink already holds has played.
            playout->fillQuantum(out.data() + pl::samplesIn(t + writeAhead), pl::samplesIn(s.out), sessions, &live);
        }
    }
    // A packet that failed to encode is skipped, and playout would conceal it.
    EXPECT_EQ(capture->encodeErrors(), 0u) << s.name;

    std::vector<Breakdown> probes;
    for (milliseconds at : placements) {
        const int64_t onset = pl::samplesIn(at);
        const auto d = lat::find(out.data() + onset, pl::samplesIn(kSearchWindow), p);
        EXPECT_TRUE(d.found()) << s.name << ": probe at " << at.count() << " ms not found (" << d.peak
                               << " over " << d.nextPeak << "); if latency grew past 200 ms, widen "
                               << "kSearchWindow and kProbeSpacing together";
        if (!d.found()) continue;
        const uint64_t onsetFrame = uint64_t(onset / kFrameSamples);
        const auto carrier = std::find_if(sent.begin(), sent.end(), [&](const Sent& q) {
            return q.frame <= onsetFrame && onsetFrame < q.frame + kFramesPerPacket;
        });
        EXPECT_NE(carrier, sent.end()) << s.name << ": no packet carries frame " << onsetFrame;
        if (carrier == sent.end()) continue;
        Breakdown r;
        r.total = d.offset;
        r.packetization = pl::samplesIn(carrier->ready) - int64_t(carrier->frame) * kFrameSamples;
        r.network = pl::samplesIn(carrier->arrival - carrier->ready);
        r.writeAhead = pl::samplesIn(writeAhead);
        r.jitterBuffer = r.total - r.packetization - r.network - codec - r.writeAhead;
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
            std::printf("  total %7s = packetization %6s + network %6s + jitter buffer %7s + codec %s"
                        " + write-ahead %6s\n",
                        ms(r.total).c_str(), ms(r.packetization).c_str(), ms(r.network).c_str(),
                        ms(r.jitterBuffer).c_str(), ms(codec).c_str(), ms(r.writeAhead).c_str());
            totals.push_back(r.total);
            buffers.push_back(r.jitterBuffer);
        }
        actual << s.name << ".total_min_ms = " << ms(*std::min_element(totals.begin(), totals.end())) << "\n"
               << s.name << ".total_median_ms = " << ms(median(totals)) << "\n"
               << s.name << ".total_max_ms = " << ms(*std::max_element(totals.begin(), totals.end())) << "\n"
               << s.name << ".jitter_buffer_median_ms = " << ms(median(buffers)) << "\n";
    }

    const std::string path = std::string(DUMBLE_TEST_DATA_DIR) + "/latency/expected.txt";
    std::ifstream file(path);
    std::stringstream expected;
    if (file) expected << file.rdbuf();
    if (actual.str() == expected.str()) return;

    const char* update = std::getenv("DUMBLE_UPDATE_EXPECTED");
    if (!update || std::string(update) != "1") {
        ASSERT_TRUE(file) << "cannot read " << path;
        EXPECT_EQ(actual.str(), expected.str())
            << "Latency changed. If intended, rewrite the pinned figures and commit them:\n"
            << "  cmake --build build/host && DUMBLE_UPDATE_EXPECTED=1 ctest --test-dir build/host -R Latency";
        return;
    }
    ASSERT_FALSE(HasFailure()) << "not rewriting " << path << " from a failed run";
    std::ofstream out(path);
    out << actual.str();
    out.close();
    ASSERT_FALSE(out.fail()) << "cannot write " << path;
    // A failure, so that the variable left set cannot pass a change nobody looked at.
    FAIL() << "rewrote " << path << ": review and commit the diff";
}

}  // namespace
