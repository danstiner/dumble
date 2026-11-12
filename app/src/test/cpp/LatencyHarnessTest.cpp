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
// output-callback bursts, and a probe found again in the output. The engines are sample-exact and
// the codec's delay is measured once through the same probe, so the figures are pinned in
// latency/expected.txt and a change to any of them is a reviewed diff.
//
// What a total holds:
//   packetization  from a sample's capture to its packet leaving
//   network        the scripted delay
//   jitter buffer  the time playout holds the audio; the remainder once the others are taken out
//   codec          Opus's own delay
//   write-ahead    the audio the sink already holds when a fill is written
//
// What it leaves out, which a phone adds on top:
//   - the rest of the device's input and output latency
//   - the wait before the sending thread notices a packet, up to kPollWaitMillis
//   - a packet arriving just after a fill, which waits up to one output callback more than here
namespace {

using namespace dumble;
namespace pl = dumble::playout;
namespace lat = dumble::latency;
using std::chrono::milliseconds;
using namespace std::chrono_literals;

// One measured spurt of push-to-talk speech. The defaults are the baseline, and each scenario
// names only what it changes.
struct Scenario {
    const char* name;
    // A 1 s spurt and a 1 s gap come first, so playout has already measured this speaker's
    // network when the measured spurt opens.
    bool warm = true;
    // How much audio the device hands over, and asks for, at a time. The defaults are what was
    // measured in a call, with the platform's voice effects on.
    milliseconds inputCallback = 20ms;
    milliseconds outputCallback = 10ms;
    // Every packet arrives this long after it is sent, plus a seeded random extra of up to
    // networkJitter. Packets stay in order.
    milliseconds networkDelay = 0ms;
    milliseconds networkJitter = 0ms;
};

constexpr Scenario kScenarios[] = {
    // The first spurt heard from a speaker. Playout knows nothing about the network yet and
    // starts from its cautious default, so this is the largest figure.
    {.name = "cold", .warm = false},
    // The baseline the rest are read against: a later spurt on a network with no delay and no
    // jitter, so what is left is Dumble's own.
    {.name = "warm"},
    // A 20 ms network with up to 40 ms of spread: what the jitter buffer adds to ride it out.
    {.name = "warm_network_20ms_jitter_40ms", .networkDelay = 20ms, .networkJitter = 40ms},
    // The low-latency streams a device grants with the voice effects off: the sink holds 4 ms
    // where the baseline's holds 20, and a packet is still 20 ms.
    {.name = "warm_callbacks_2ms", .inputCallback = 2ms, .outputCallback = 2ms},
};

constexpr milliseconds kWarmSpurt = 1000ms;
constexpr milliseconds kWarmGap = 1000ms;

// The delay is the same for every sample of a spurt unless playout adapts inside it, which
// several probes show as a spread between min and max. No scenario here provokes one: a shrink
// needs kShrinkCooldownSamples of produced audio first, and the measured spurt is shorter.
constexpr int kProbes = 4;
constexpr milliseconds kFirstProbe = 300ms;
constexpr milliseconds kProbeSpacing = 500ms;
// The probe's 250 ms plus up to 200 ms of delay; shorter than the spacing, so one probe per window.
constexpr milliseconds kSearchWindow = 450ms;
constexpr int16_t kProbePeak = 8000;
constexpr int kNoiseAmplitude = 100;
constexpr int32_t kSession = 1;

// The gate must change where an input burst ends, or the burst straddling it is fed late, and the
// measured spurt must open where a packet begins: packet starts are read from frame numbers,
// which round down. An error in either lands in the jitter buffer row. Probes open where packets
// begin too, so every probe meets its packet at the same point.
constexpr bool timesAlign() {
    for (const auto& s : kScenarios) {
        if (kWarmSpurt % s.inputCallback != 0ms || (kWarmSpurt + kWarmGap) % s.inputCallback != 0ms) {
            return false;
        }
    }
    return pl::samplesIn(kWarmSpurt + kWarmGap) % kTxPacketSamples == 0 &&
           pl::samplesIn(kFirstProbe) % kTxPacketSamples == 0 &&
           pl::samplesIn(kProbeSpacing) % kTxPacketSamples == 0;
}
static_assert(timesAlign());

milliseconds measuredSpurtStart(const Scenario& s) { return s.warm ? kWarmSpurt + kWarmGap : 0ms; }

// Two callbacks, as the output stream reports when it opens.
milliseconds writeAhead(const Scenario& s) { return 2 * s.outputCallback; }

std::vector<milliseconds> probeTimes(const Scenario& s) {
    std::vector<milliseconds> times;
    for (int k = 0; k < kProbes; k++) {
        times.push_back(measuredSpurtStart(s) + kFirstProbe + k * kProbeSpacing);
    }
    return times;
}

// Quiet noise with a probe added at each time.
std::vector<int16_t> microphone(milliseconds length, const std::vector<milliseconds>& probeTimes,
                                const std::vector<int16_t>& probe) {
    auto pcm = latencytest::noise(pl::samplesIn(length), kNoiseAmplitude, 7);
    for (milliseconds at : probeTimes) {
        int16_t* onset = pcm.data() + pl::samplesIn(at);
        for (size_t i = 0; i < probe.size(); i++) onset[i] = int16_t(onset[i] + probe[i]);
    }
    return pcm;
}

struct Packet {
    std::vector<uint8_t> data;
    uint64_t frame;
    bool terminator;
    milliseconds sent, arrival;
};

struct Call {
    std::vector<Packet> packets;
    std::vector<int16_t> heard;   // a sample's index is the time it reaches the listener
};

// Runs the scenario a millisecond at a time. Within one millisecond the order is: the input
// callback and the packets it completes, the push-to-talk key, arrivals, the output callback.
Call simulate(const Scenario& s, const std::vector<int16_t>& microphone, milliseconds length) {
    Call call;
    auto capture = CaptureEngine::create(latencytest::kTransmitBitrate, fixture::weightBlob().data(), fixture::weightBlob().size());
    auto playout = pl::PlayoutEngine::create(kSampleRate, pl::kMaxPacketSamples);
    EXPECT_TRUE(capture && playout);
    if (!capture || !playout) {
        return call;
    }
    capture->setTransmitMode(TransmitMode::PushToTalk);
    capture->setWaitMillisForTest(0);
    playout->setWriteAheadSamples(pl::samplesIn(writeAhead(s)));
    call.heard.assign(pl::samplesIn(length + writeAhead(s) + s.outputCallback), 0);

    std::mt19937 jitter(11);
    milliseconds lastArrival = 0ms;
    size_t delivered = 0;
    uint8_t buf[kMaxPacketBytes];
    int32_t sessions[pl::kMaxSpeakers];
    int32_t live = 0;

    capture->setGateOpen(true);
    for (milliseconds t = 0ms; t < length; t += 1ms) {
        if (t > 0ms && t % s.inputCallback == 0ms) {
            capture->onPcm(microphone.data() + pl::samplesIn(t - s.inputCallback),
                           uint32_t(pl::samplesIn(s.inputCallback)));
            uint64_t frame;
            uint32_t flags;
            int n;
            while ((n = capture->pollPacket(buf, sizeof buf, &frame, &flags)) > 0) {
                const milliseconds extra = s.networkJitter > 0ms
                    ? milliseconds(jitter() % uint32_t(s.networkJitter.count() + 1)) : 0ms;
                // In order, as over TCP: jitter never reorders, so a packet whose delay ends before
                // an earlier one's waits for it, and that wait is part of its network row.
                lastArrival = std::max(lastArrival, t + s.networkDelay + extra);
                call.packets.push_back({{buf, buf + n}, frame, (flags & kFlagTerminator) != 0, t, lastArrival});
            }
        }

        // After the input callback, so a key change takes effect from sample t exactly.
        if (s.warm && t == kWarmSpurt) capture->setGateOpen(false);
        if (s.warm && t == measuredSpurtStart(s)) capture->setGateOpen(true);

        // Before this millisecond's fill, so a packet is never one output callback late.
        for (; delivered < call.packets.size() && call.packets[delivered].arrival <= t; delivered++) {
            const Packet& p = call.packets[delivered];
            playout->offer(kSession, p.data.data(), int(p.data.size()), p.frame, p.terminator,
                           BootClock::time_point(p.arrival));
        }

        if (t % s.outputCallback == 0ms) {
            // What is filled now is heard once the audio the sink already holds has played.
            playout->fillQuantum(call.heard.data() + pl::samplesIn(t + writeAhead(s)),
                                 pl::samplesIn(s.outputCallback), sessions, &live);
        }
    }
    // A packet that failed to encode is skipped, and playout would conceal it.
    EXPECT_EQ(capture->encodeErrors(), 0u) << s.name;
    return call;
}

struct Breakdown {
    int64_t total, packetization, network, jitterBuffer, writeAhead;   // samples
};

// One breakdown per probe found in what the listener heard.
std::vector<Breakdown> measure(const Scenario& s, int64_t codec) {
    const auto times = probeTimes(s);
    const milliseconds length = times.back() + kSearchWindow;
    const auto probe = lat::probe(kProbePeak);
    const Call call = simulate(s, microphone(length, times, probe), length);
    if (call.heard.empty()) {
        return {};
    }

    std::vector<Breakdown> breakdowns;
    for (milliseconds at : times) {
        const int64_t spoken = pl::samplesIn(at);
        const auto d = lat::find(call.heard.data() + spoken, pl::samplesIn(kSearchWindow), probe);
        EXPECT_TRUE(d.found()) << s.name << ": probe at " << at.count() << " ms not found (" << d.peak
                               << " over " << d.nextPeak << "); if latency grew past 200 ms, widen "
                               << "kSearchWindow and kProbeSpacing together";
        if (!d.found()) continue;

        const uint64_t spokenFrame = uint64_t(spoken / kFrameSamples);
        const auto carrier = std::find_if(call.packets.begin(), call.packets.end(), [&](const Packet& p) {
            return p.frame <= spokenFrame && spokenFrame < p.frame + kFramesPerPacket;
        });
        EXPECT_NE(carrier, call.packets.end()) << s.name << ": no packet carries frame " << spokenFrame;
        if (carrier == call.packets.end()) continue;

        Breakdown b;
        b.total = d.offset;
        b.packetization = pl::samplesIn(carrier->sent) - int64_t(carrier->frame) * kFrameSamples;
        b.network = pl::samplesIn(carrier->arrival - carrier->sent);
        b.writeAhead = pl::samplesIn(writeAhead(s));
        b.jitterBuffer = b.total - b.packetization - b.network - codec - b.writeAhead;
        breakdowns.push_back(b);
    }
    return breakdowns;
}

// Opus's own delay at Dumble's settings: the encoder's lookahead, 6.5 ms below 64 kbit/s. Measured
// the way the harness measures everything else, not read from the encoder, so that the probe's
// own error of a sample or two cancels out of the jitter buffer row.
int64_t codecDelay() {
    const auto probe = lat::probe(kProbePeak);
    std::vector<int16_t> in(int64_t(kSampleRate), 0);
    const int64_t at = 4 * kTxPacketSamples;
    std::copy(probe.begin(), probe.end(), in.begin() + at);
    const auto out = latencytest::opusRoundTrip(in);
    const auto d = lat::find(out.data(), int64_t(out.size()), probe);
    EXPECT_TRUE(d.found());
    return d.offset - at;
}

std::string ms(int64_t samples) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.2f", double(samples) / pl::kSamplesPerMilli);
    return buf;
}

int64_t median(std::vector<int64_t> v) {
    std::sort(v.begin(), v.end());
    return (v[(v.size() - 1) / 2] + v[v.size() / 2]) / 2;
}

// The text of expected.txt for what was just measured. Prints each probe's breakdown on the way.
std::string figures() {
    const int64_t codec = codecDelay();
    std::ostringstream text;
    text << "codec_delay_ms = " << ms(codec) << "\n";
    for (const auto& s : kScenarios) {
        const auto breakdowns = measure(s, codec);
        EXPECT_EQ(breakdowns.size(), size_t(kProbes)) << s.name;
        if (breakdowns.size() != size_t(kProbes)) continue;
        std::vector<int64_t> totals, buffers;
        std::printf("%s\n", s.name);
        for (const auto& b : breakdowns) {
            std::printf("  total %7s = packetization %6s + network %6s + jitter buffer %7s + codec %s"
                        " + write-ahead %6s\n",
                        ms(b.total).c_str(), ms(b.packetization).c_str(), ms(b.network).c_str(),
                        ms(b.jitterBuffer).c_str(), ms(codec).c_str(), ms(b.writeAhead).c_str());
            totals.push_back(b.total);
            buffers.push_back(b.jitterBuffer);
        }
        text << s.name << ".total_min_ms = " << ms(*std::min_element(totals.begin(), totals.end())) << "\n"
             << s.name << ".total_median_ms = " << ms(median(totals)) << "\n"
             << s.name << ".total_max_ms = " << ms(*std::max_element(totals.begin(), totals.end())) << "\n"
             << s.name << ".jitter_buffer_median_ms = " << ms(median(buffers)) << "\n";
    }
    return text.str();
}

TEST(LatencyHarness, MatchesExpected) {
    const std::string actual = figures();
    ASSERT_FALSE(HasFailure()) << "not comparing or rewriting figures from a failed run";

    const std::string path = std::string(DUMBLE_TEST_DATA_DIR) + "/latency/expected.txt";
    std::ifstream file(path);
    std::stringstream expected;
    if (file) expected << file.rdbuf();
    if (actual == expected.str()) return;

    const char* update = std::getenv("DUMBLE_UPDATE_EXPECTED");
    if (!update || std::string(update) != "1") {
        ASSERT_TRUE(file) << "cannot read " << path;
        EXPECT_EQ(actual, expected.str())
            << "Latency changed. If intended, rewrite the pinned figures and commit them:\n"
            << "  cmake --build build/host && DUMBLE_UPDATE_EXPECTED=1 ctest --test-dir build/host -R Latency";
        return;
    }
    std::ofstream out(path);
    out << actual;
    out.close();
    ASSERT_FALSE(out.fail()) << "cannot write " << path;
    // A failure, so that the variable left set cannot pass a change nobody looked at.
    FAIL() << "rewrote " << path << ": review and commit the diff";
}

}  // namespace
