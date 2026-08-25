#pragma once
#include <gtest/gtest.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include "core/CaptureConstants.h"

namespace dumble::fixture {

struct Region {
    int startMs;
    int endMs;
};

inline std::vector<uint8_t> readBytes(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    EXPECT_TRUE(in) << "cannot open " << path;
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)),
                                std::istreambuf_iterator<char>());
}

inline uint32_t le32(const std::vector<uint8_t>& b, size_t at) {
    return uint32_t(b[at]) | (uint32_t(b[at + 1]) << 8) | (uint32_t(b[at + 2]) << 16) |
           (uint32_t(b[at + 3]) << 24);
}

/** 48 kHz mono PCM16 little-endian WAV, read by walking chunks. */
inline std::vector<int16_t> readWav(const std::string& path) {
    const auto bytes = readBytes(path);
    // Each EXPECT that guards a later memory access needs a matching early return. These helpers
    // return non-void so ASSERT_* is unavailable, and EXPECT_* is non-fatal — without the return,
    // a missing fixture reaches memcmp on an empty vector, whose data() may be null. That turns a
    // readable gtest failure into a crashed test binary.
    EXPECT_GE(bytes.size(), 44u) << path;
    if (bytes.size() < 44) return {};
    EXPECT_EQ(0, std::memcmp(bytes.data(), "RIFF", 4)) << path;
    EXPECT_EQ(0, std::memcmp(bytes.data() + 8, "WAVE", 4)) << path;
    size_t at = 12, dataAt = 0, dataLen = 0;
    int channels = 0, bits = 0, audioFormat = 0;
    uint32_t sampleRate = 0;
    while (at + 8 <= bytes.size()) {
        const uint32_t len = le32(bytes, at + 4);
        const size_t body = at + 8;
        if (std::memcmp(bytes.data() + at, "fmt ", 4) == 0) {
            // The loop condition bounds-checks the 8-byte chunk header, not the body. A file with
            // a filler chunk before `fmt ` can clear the 44-byte guard and still end mid-body, so
            // the 16 bytes read here need their own check. Leaving channels/bits at 0 lets the
            // post-loop guard turn it into a readable failure instead of a crashed test binary.
            if (body + 16 > bytes.size()) break;
            audioFormat = int(bytes[body]) | (int(bytes[body + 1]) << 8);
            channels = int(bytes[body + 2]) | (int(bytes[body + 3]) << 8);
            sampleRate = le32(bytes, body + 4);
            bits = int(bytes[body + 14]) | (int(bytes[body + 15]) << 8);
        } else if (std::memcmp(bytes.data() + at, "data", 4) == 0) {
            dataAt = body;
            dataLen = std::min(size_t(len), bytes.size() - body);
        }
        at = body + len + (len & 1);
    }
    EXPECT_EQ(1, audioFormat) << path << " is not WAVE_FORMAT_PCM";
    EXPECT_EQ(1, channels) << path;
    EXPECT_EQ(16, bits) << path;
    // Every consumer decimates assuming 48 kHz input; a 16 kHz fixture would silently pass through
    // the decimator as garbage instead of failing loudly here.
    EXPECT_EQ(uint32_t(kSampleRate), sampleRate) << path;
    EXPECT_GT(dataLen, 0u) << path;
    if (audioFormat != 1 || channels != 1 || bits != 16 || sampleRate != uint32_t(kSampleRate) ||
        dataLen == 0)
        return {};
    std::vector<int16_t> pcm(dataLen / 2);
    for (size_t i = 0; i < pcm.size(); i++)
        pcm[i] = int16_t(uint16_t(bytes[dataAt + 2 * i]) | (uint16_t(bytes[dataAt + 2 * i + 1]) << 8));
    return pcm;
}

/** Audacity label export: startSeconds<TAB>endSeconds<TAB>label, one region per line. */
inline std::vector<Region> readLabels(const std::string& path) {
    std::ifstream in(path);
    EXPECT_TRUE(in) << "cannot open " << path;
    std::vector<Region> regions;
    for (std::string line; std::getline(in, line);) {
        std::istringstream fields(line);
        double start = 0, end = 0;
        if (!(fields >> start >> end)) continue;
        regions.push_back({int(start * 1000), int(end * 1000)});
    }
    std::sort(regions.begin(), regions.end(),
              [](const Region& a, const Region& b) { return a.startMs < b.startMs; });
    return regions;
}

inline std::vector<float> readTrace(const std::string& path) {
    std::ifstream in(path);
    EXPECT_TRUE(in) << "cannot open " << path;
    std::vector<float> out;
    for (double v; in >> v;) out.push_back(float(v));
    return out;
}

/** The shipped Silero weight blob. Shared so the size and hash checks all read the same bytes. */
// Cached: every engine now needs it, and the file is 1.2 MB.
inline const std::vector<uint8_t>& weightBlob() {
    static const auto blob = readBytes(std::string(DUMBLE_ASSETS_DIR) + "/silero_vad_weights.bin");
    return blob;
}

inline std::string corpusPath(const std::string& name) {
    return std::string(DUMBLE_TEST_DATA_DIR) + "/LibriSpeech-ASR-corpus/" + name;
}

inline std::string referencePath(const std::string& name) {
    return std::string(DUMBLE_TEST_DATA_DIR) + "/silero-reference/" + name;
}

inline std::vector<std::string> clipNames() {
    return {"dev-other-116-288045-0000-trim",
            "dev-other-700-122866-0000",
            "dev-other-1255-138279-0002"};
}

}  // namespace dumble::fixture
