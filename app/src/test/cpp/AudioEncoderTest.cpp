#include <gtest/gtest.h>
#include <cmath>
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

    std::vector<int16_t> pcm(dumble::kTxFrameSamples);
    for (int i = 0; i < dumble::kTxFrameSamples; i++)
        pcm[i] = int16_t(8000 * std::sin(2.0 * M_PI * 440.0 * i / dumble::kSampleRate));

    std::vector<uint8_t> packet(4000);
    const int bytes =
        enc->encode(pcm.data(), dumble::kTxFrameSamples, packet.data(), int(packet.size()));
    *bytesOut = bytes;
    EXPECT_GT(bytes, 0);
    if (bytes <= 0) return 0;

    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(dumble::kSampleRate, dumble::kChannels, &err);
    EXPECT_EQ(OPUS_OK, err);
    std::vector<int16_t> back(dumble::kTxFrameSamples);
    const int samples =
        opus_decode(dec, packet.data(), bytes, back.data(), dumble::kTxFrameSamples, 0);
    opus_decoder_destroy(dec);
    EXPECT_EQ(dumble::kTxFrameSamples, samples);

    double energy = 0;
    for (int s : back) energy += double(s) * s;
    return energy / dumble::kTxFrameSamples;
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
