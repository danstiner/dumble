#include <gtest/gtest.h>
#include "TestTone.h"
#include <cmath>
#include <cstring>
#include <opus.h>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/AudioEncoder.h"

namespace {

// One 440 Hz frame through the encoder at the given bitrate and back out of a stock decoder.
// Returns the decoded frame's mean energy so callers can assert against silence; reports the
// packet size through bytesOut. Uses EXPECT rather than ASSERT so it can return a value.
double roundTripToneEnergy(int bitrate, int* bytesOut) {
    auto enc = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, bitrate);
    EXPECT_TRUE(enc);
    if (!enc) return 0;

    const std::vector<int16_t> pcm = dumble::testtone::tone(dumble::kTxPacketSamples);

    std::vector<uint8_t> packet(4000);
    const int bytes =
        enc->encode(pcm.data(), dumble::kTxPacketSamples, packet.data(), int(packet.size()));
    *bytesOut = bytes;
    EXPECT_GT(bytes, 0);
    if (bytes <= 0) return 0;

    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(dumble::kSampleRate, dumble::kChannels, &err);
    EXPECT_EQ(OPUS_OK, err);
    std::vector<int16_t> back(dumble::kTxPacketSamples);
    const int samples =
        opus_decode(dec, packet.data(), bytes, back.data(), dumble::kTxPacketSamples, 0);
    opus_decoder_destroy(dec);
    EXPECT_EQ(dumble::kTxPacketSamples, samples);

    return dumble::testtone::meanEnergy(back.data(), dumble::kTxPacketSamples);
}

}  // namespace

// libdumble.so is built for Android ABIs only, so no JVM test can reach libopus. core/ builds on
// the host, which makes this the only automated encoder coverage in the project.
TEST(AudioEncoder, AToneSurvivesEncodeAndDecode) {
    int bytes = 0;
    EXPECT_GT(roundTripToneEnergy(40000, &bytes), 1000.0) << "decoded frame is silent";
    EXPECT_LE(bytes, 1010) << "payload cannot exceed the server's 1024-byte tunnel cap";
}

// The factory's whole point: libopus accepts only 8/12/16/24/48 kHz, and a rejected configuration
// must yield no object at all rather than one that fails every encode for the rest of the session.
TEST(AudioEncoder, CreateRefusesAnUnsupportedSampleRate) {
    EXPECT_EQ(nullptr, dumble::AudioEncoder::create(44100, dumble::kChannels, 40000));
}

// Pins the two-tier choice boundary-exactly. 32000 staying VOIP is the deliberate divergence
// from Mumble (which switches to AUDIO there): it keeps the adaptive high-pass at our everyday
// rate. 64000 itself is already the low-delay preset, matching Mumble's inclusive edge.
TEST(AudioEncoder, TheApplicationPresetIsVoipUntilTheLowDelayTier) {
    EXPECT_EQ(OPUS_APPLICATION_VOIP, dumble::AudioEncoder::applicationForBitrate(8000));
    EXPECT_EQ(OPUS_APPLICATION_VOIP, dumble::AudioEncoder::applicationForBitrate(32000));
    EXPECT_EQ(OPUS_APPLICATION_VOIP, dumble::AudioEncoder::applicationForBitrate(63999));
    EXPECT_EQ(OPUS_APPLICATION_RESTRICTED_LOWDELAY,
              dumble::AudioEncoder::applicationForBitrate(64000));
}

// The low-delay tier reconfigures libopus more deeply than VOIP — CELT-only, no delay
// compensation — so prove an encoder built there still yields packets a stock decoder accepts.
TEST(AudioEncoder, ALowDelayTierEncoderStillRoundTrips) {
    int bytes = 0;
    EXPECT_GT(roundTripToneEnergy(64000, &bytes), 1000.0) << "decoded frame is silent";
}

TEST(AudioEncoder, ResetReturnsTheEncoderToAColdState) {
    // Opus is a predictive codec: without a reset, packet N depends on packets before it. A
    // receiver starts every spurt with a fresh decoder (PlayoutEngine resets on slot retire), so
    // an unreset encoder predicts from audio the decoder never had.
    auto warm = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000);
    auto cold = dumble::AudioEncoder::create(dumble::kSampleRate, dumble::kChannels, 40000);
    ASSERT_TRUE(warm); ASSERT_TRUE(cold);

    std::vector<int16_t> tone(dumble::kTxPacketSamples);
    for (size_t i = 0; i < tone.size(); i++)
        tone[i] = int16_t(std::sin(2.0 * M_PI * 440.0 * double(i) / dumble::kSampleRate) * 8000);

    uint8_t a[dumble::kMaxPacketBytes], b[dumble::kMaxPacketBytes], c[dumble::kMaxPacketBytes];
    // Warm the encoder up with several packets so its predictor state is genuinely non-cold.
    for (int i = 0; i < 5; i++)
        ASSERT_GT(warm->encode(tone.data(), dumble::kTxPacketSamples, a, sizeof(a)), 0);

    const int continued = warm->encode(tone.data(), dumble::kTxPacketSamples, a, sizeof(a));
    warm->reset();
    const int afterReset = warm->encode(tone.data(), dumble::kTxPacketSamples, b, sizeof(b));
    const int fromCold = cold->encode(tone.data(), dumble::kTxPacketSamples, c, sizeof(c));

    ASSERT_GT(continued, 0); ASSERT_GT(afterReset, 0); ASSERT_GT(fromCold, 0);
    // The reset encoder must agree with a cold one, byte for byte...
    ASSERT_EQ(afterReset, fromCold);
    EXPECT_EQ(0, std::memcmp(b, c, size_t(fromCold)));
    // ...and must differ from the warm continuation, or reset() did nothing and this test would
    // pass on an empty implementation.
    EXPECT_FALSE(continued == afterReset && std::memcmp(a, b, size_t(afterReset)) == 0)
        << "reset() had no observable effect";
}
