#include <gtest/gtest.h>
#include <chrono>
#include <cstdio>
#include <vector>
#include "TestTone.h"
#include "core/AudioDecoder.h"
#include "core/CaptureConstants.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

namespace {

namespace pl = dumble::playout;

// A device callback's budget is one burst, so the engine must be Opus decode plus nothing. Pinned
// as a ratio against the codec measured on the same machine, and only in Release: the Debug tree
// builds opus at -O0, three times slower than a phone, and the ratio says nothing there.

using Clock = std::chrono::steady_clock;

double microsSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::micro>(Clock::now() - t0).count();
}

// Microseconds per 20 ms packet decoded, warmed.
double decodeMicros(const std::vector<uint8_t>& packet) {
    auto d = pl::AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
    std::vector<int16_t> out(pl::kMaxPacketSamples);
    for (int i = 0; i < 50; i++) d->decode(packet.data(), int(packet.size()), out.data(), int(out.size()));
    const int n = 2000;
    const auto t0 = Clock::now();
    for (int i = 0; i < n; i++) d->decode(packet.data(), int(packet.size()), out.data(), int(out.size()));
    return microsSince(t0) / n;
}

// Microseconds per concealment request of `samples`, the grain a starved fill asks for, measured
// against a decoder with history and the decode that gave it that history subtracted out.
double concealMicros(const std::vector<uint8_t>& packet, int samples, double decodeUs) {
    auto d = pl::AudioDecoder::create(dumble::kSampleRate, dumble::kChannels);
    std::vector<int16_t> out(pl::kMaxPacketSamples);
    constexpr int kPerDecode = 8;
    const int n = 500;
    const auto t0 = Clock::now();
    for (int i = 0; i < n; i++) {
        d->decode(packet.data(), int(packet.size()), out.data(), int(out.size()));
        for (int c = 0; c < kPerDecode; c++) d->decode(nullptr, 0, out.data(), samples);
    }
    return (microsSince(t0) / n - decodeUs) / kPerDecode;
}

struct Rig {
    std::unique_ptr<pl::PlayoutEngine> e = pl::PlayoutEngine::create(dumble::kSampleRate, pl::kMaxPacketSamples);
    std::vector<int16_t> pcm;
    std::vector<int32_t> sessions = std::vector<int32_t>(pl::kMaxSpeakers);
    int32_t live = 0;
    uint64_t frame = 5000;
    int64_t supplied = 0, consumed = 0;
    const std::vector<uint8_t>& packet;
    const int quantum;

    Rig(const std::vector<uint8_t>& p, int q) : pcm(size_t(q)), packet(p), quantum(q) {}

    // One 20 ms packet to each of the eight speakers.
    void offerAll() {
        for (int s = 1; s <= pl::kMaxSpeakers; s++) e->offer(s, packet.data(), int(packet.size()), frame, false);
        frame += 2;
        supplied += 960;
    }
    void fill() {
        e->fillQuantum(pcm.data(), quantum, sessions.data(), &live);
        consumed += quantum;
    }
};

TEST(PlayoutEngineBench, EightSpeakersAreDecodePlusNothing) {
#ifndef NDEBUG
    GTEST_SKIP() << "benchmark: run from the Release tree (build/host-rel)";
#endif
    const std::vector<uint8_t> packet = dumble::testtone::encodeTone(960);  // 20 ms, as most senders
    const double decodeUs = decodeMicros(packet);
    const double concealUs = concealMicros(packet, pl::kConcealGridSamples, decodeUs);
    printf("codec: decode %.1f us per 20 ms packet, conceal %.1f us per %d-sample request\n",
           decodeUs, concealUs, pl::kConcealGridSamples);

    // Steady supply: every fill decodes, and per 20 ms of output the codec costs eight decodes.
    for (const int quantum : {128, 480, 1088}) {
        Rig r(packet, quantum);
        for (int i = 0; i < 10; i++) r.offerAll();
        for (int i = 0; i < 20; i++) r.fill();
        (void)r.e->stats();
        const int fills = 400;
        for (int i = 0; i < fills; i++) {
            while (r.supplied < r.consumed + 2 * 960) r.offerAll();
            r.fill();
        }
        const auto st = r.e->stats();
        const double enginePer20ms = double(st.fillMicrosMean) * 960.0 / quantum;
        const double codecPer20ms = pl::kMaxSpeakers * decodeUs;
        printf("quantum=%4d decoding:   fill mean=%3llu us max=%4llu us | per 20 ms: engine %.0f us, codec %.0f us\n",
               quantum, (unsigned long long)st.fillMicrosMean, (unsigned long long)st.fillMicrosMax,
               enginePer20ms, codecPer20ms);
        EXPECT_LE(enginePer20ms - codecPer20ms, 0.5 * codecPer20ms) << "quantum " << quantum;
        EXPECT_LT(double(st.fillMicrosMax), quantum * 1000.0 / 48.0) << "a fill outran its own duration";
    }

    // Starved: every fill conceals for all eight, one grid-sized request each.
    {
        const int quantum = 128;
        Rig r(packet, quantum);
        for (int i = 0; i < 10; i++) r.offerAll();
        while (r.consumed < r.supplied) r.fill();
        (void)r.e->stats();
        const int fills = pl::kConcealSamples / quantum;
        for (int i = 0; i < fills; i++) r.fill();
        const auto st = r.e->stats();
        const double codecPerFill = pl::kMaxSpeakers * concealUs;
        printf("quantum=%4d concealing: fill mean=%3llu us max=%4llu us | per fill: codec %.0f us\n",
               quantum, (unsigned long long)st.fillMicrosMean, (unsigned long long)st.fillMicrosMax, codecPerFill);
        EXPECT_LE(double(st.fillMicrosMean) - codecPerFill, 0.5 * codecPerFill);
    }
}

}  // namespace
