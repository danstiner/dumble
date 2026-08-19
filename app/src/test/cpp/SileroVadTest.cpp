#include <gtest/gtest.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>
#include "WavFixture.h"
#include "core/CaptureConstants.h"
#include "core/Decimator.h"
#include "core/SileroVad.h"

using dumble::Decimator;
using dumble::SileroVad;

namespace {

// FIPS 180-4, no external dependency: a shell-out (shasum/sha256sum) fails on infrastructure —
// unquoted paths word-split, some hosts have neither binary — rather than on a wrong blob.
std::string sha256Hex(const std::vector<uint8_t>& data) {
    static constexpr uint32_t k[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2};
    uint32_t h[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                      0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};

    std::vector<uint8_t> msg(data);
    const uint64_t bitLen = uint64_t(data.size()) * 8;
    msg.push_back(0x80);
    while (msg.size() % 64 != 56) msg.push_back(0);
    for (int i = 7; i >= 0; --i) msg.push_back(uint8_t(bitLen >> (i * 8)));

    auto rotr = [](uint32_t x, int n) { return (x >> n) | (x << (32 - n)); };

    for (size_t chunk = 0; chunk < msg.size(); chunk += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i)
            w[i] = (uint32_t(msg[chunk + 4 * i]) << 24) | (uint32_t(msg[chunk + 4 * i + 1]) << 16) |
                   (uint32_t(msg[chunk + 4 * i + 2]) << 8) | uint32_t(msg[chunk + 4 * i + 3]);
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            const uint32_t ch = (e & f) ^ (~e & g);
            const uint32_t temp1 = hh + s1 + ch + k[i] + w[i];
            const uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = s0 + maj;
            hh = g; g = f; f = e; e = d + temp1;
            d = c; c = b; b = a; a = temp1 + temp2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    char out[65];
    for (int i = 0; i < 8; ++i) std::snprintf(out + i * 8, 9, "%08x", h[i]);
    return std::string(out, 64);
}

// Regenerate with tools/silero/extract_weights.py; it prints this. A silently swapped blob would
// otherwise sail past create()'s size check and produce plausible-looking wrong probabilities.
constexpr const char* kWeightsSha256 =
    "5afe96454b4595a95479ac074d736b617253f7eecec9672d99e56579c86d4405";

std::vector<float> readTrace(const std::string& clip) {
    return dumble::fixture::readTrace(dumble::fixture::referencePath(clip + ".txt"));
}

std::vector<int16_t> readCorpusWav(const std::string& clip) {
    return dumble::fixture::readWav(dumble::fixture::corpusPath(clip + ".wav"));
}

// Decimate frame by frame, exactly as VoiceActivity will, then run whole windows.
std::vector<float> probabilities(const std::vector<int16_t>& pcm, SileroVad& vad) {
    Decimator decimator;
    std::vector<float> decimated;
    std::vector<float> frame(Decimator::kOutputSamples);
    for (size_t at = 0; at + dumble::kFrameSamples <= pcm.size(); at += dumble::kFrameSamples) {
        decimator.decimate(pcm.data() + at, frame.data());
        decimated.insert(decimated.end(), frame.begin(), frame.end());
    }
    std::vector<float> out;
    for (size_t at = 0; at + SileroVad::kWindow <= decimated.size(); at += SileroVad::kWindow)
        out.push_back(vad.process(decimated.data() + at));
    return out;
}

void expectMatchesTrace(const std::string& clip, const std::vector<int16_t>& pcm) {
    ASSERT_FALSE(pcm.empty()) << clip;
    const auto blob = dumble::fixture::weightBlob();
    auto vad = SileroVad::create(blob.data(), blob.size());
    ASSERT_TRUE(vad) << clip;
    const auto mine = probabilities(pcm, *vad);
    const auto reference = readTrace(clip);
    ASSERT_EQ(reference.size(), mine.size()) << clip;
    ASSERT_FALSE(reference.empty()) << clip;
    double worst = 0;
    for (size_t i = 0; i < mine.size(); i++)
        worst = std::max(worst, std::fabs(double(mine[i]) - double(reference[i])));
    EXPECT_LT(worst, 1e-4) << clip << " max |delta| " << worst;
}

}  // namespace

TEST(SileroVad, RefusesABlobOfTheWrongSize) {
    std::vector<float> tooSmall(SileroVad::kWeightFloats - 1, 0.0f);
    EXPECT_EQ(nullptr, SileroVad::create(tooSmall.data(), tooSmall.size() * sizeof(float)));
    std::vector<float> tooBig(SileroVad::kWeightFloats + 1, 0.0f);
    EXPECT_EQ(nullptr, SileroVad::create(tooBig.data(), tooBig.size() * sizeof(float)));
}

TEST(SileroVad, ShippedBlobIsTheExpectedSize) {
    EXPECT_EQ(SileroVad::kWeightFloats * sizeof(float), dumble::fixture::weightBlob().size());
}

TEST(SileroVad, MatchesOnnxRuntimeOnSyntheticAudio) {
    // The widest-range clip: a gate-order or stride error cannot hide in a trace that is all
    // near-zero, so this is the assertion most likely to catch one.
    const auto reference = readTrace("synthetic");
    ASSERT_FALSE(reference.empty());
    float low = 1.0f, high = 0.0f;
    for (float p : reference) { low = std::min(low, p); high = std::max(high, p); }
    EXPECT_LT(low, 0.01f) << "reference trace never goes quiet";
    EXPECT_GT(high, 0.9f) << "reference trace never reaches speech";
    expectMatchesTrace("synthetic",
                       dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav")));
}

TEST(SileroVad, MatchesOnnxRuntimeOnTheCorpus) {
    for (const std::string& clip : dumble::fixture::clipNames())
        expectMatchesTrace(clip, readCorpusWav(clip));
}

TEST(SileroVad, ResetReproducesAColdStart) {
    const auto pcm = readCorpusWav("dev-other-700-122866-0000");
    const auto blob = dumble::fixture::weightBlob();
    auto vad = SileroVad::create(blob.data(), blob.size());
    ASSERT_TRUE(vad);
    const auto first = probabilities(pcm, *vad);
    vad->reset();
    const auto second = probabilities(pcm, *vad);
    ASSERT_EQ(first.size(), second.size());
    for (size_t i = 0; i < first.size(); i++) EXPECT_FLOAT_EQ(first[i], second[i]) << "i=" << i;
}

TEST(SileroVad, CostPerWindow) {
    const auto pcm = readCorpusWav("dev-other-1255-138279-0002");
    const auto blob = dumble::fixture::weightBlob();
    auto vad = SileroVad::create(blob.data(), blob.size());
    ASSERT_TRUE(vad);
    const auto start = std::chrono::steady_clock::now();
    const auto probs = probabilities(pcm, *vad);
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - start).count();
    ASSERT_FALSE(probs.empty());
    const double usPerWindow = double(micros) / double(probs.size());
    // The absolute number is informational only — a host number says nothing about a phone, and
    // PR 2 measures the phone — but 20x margin against Debug's ~1583 us still catches a
    // catastrophic regression (an accidental O(n^2), a debug-only sanitizer left on, ...).
    // CMAKE_BUILD_TYPE is printed alongside so a Debug number is never mistaken for the real cost.
    EXPECT_LT(usPerWindow, 32000.0) << "build=" << DUMBLE_BUILD_TYPE;
    std::printf("[          ] %.1f us/window over %zu windows (%.2f%% of the 32 ms each covers, "
                "build=%s)\n",
                usPerWindow, probs.size(), 100.0 * usPerWindow / 32000.0, DUMBLE_BUILD_TYPE);
}

TEST(SileroVad, ShippedBlobMatchesThePinnedHash) {
    // A crude but dependency-free FNV-1a over the blob would not be a cryptographic pin — parity's
    // 1e-4 tolerance would pass sub-tolerance blob drift — so this is real sha256, computed in
    // process over the same bytes the parity tests read. No shell-out: a popen command line built
    // from DUMBLE_ASSETS_DIR word-splits under a checkout path containing a space, and a host with
    // neither shasum nor sha256sum would fail on infrastructure rather than on the blob.
    EXPECT_EQ(kWeightsSha256, sha256Hex(dumble::fixture::weightBlob()))
        << "blob hash changed; re-run tools/silero/extract_weights.py and update the pin";
}
