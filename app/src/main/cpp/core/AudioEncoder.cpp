#include "core/AudioEncoder.h"
#include <opus.h>

namespace dumble {

// VOIP: SILK-based, 6.5 ms lookahead, high-pass filter. RESTRICTED_LOWDELAY at 64+ kb/s: forces
// CELT (which Opus would pick near that rate anyway), trims lookahead to 2.5 ms. AUDIO mode is
// not used: it still picks SILK below ~60 kb/s, and the only thing it changes for us is dropping
// the high-pass filter.
int AudioEncoder::applicationForBitrate(int bitrate) {
    if (bitrate >= 64000) return OPUS_APPLICATION_RESTRICTED_LOWDELAY;
    return OPUS_APPLICATION_VOIP;
}

std::unique_ptr<AudioEncoder> AudioEncoder::create(int sampleRate, int channels, int bitrate) {
    int err = OPUS_OK;
    OpusEncoder* enc =
        opus_encoder_create(sampleRate, channels, applicationForBitrate(bitrate), &err);
    if (err != OPUS_OK || enc == nullptr) {
        if (enc != nullptr) opus_encoder_destroy(enc);
        return nullptr;
    }
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    // Constant bit-rate: steady packet size for predictable queueing delay.
    opus_encoder_ctl(enc, OPUS_SET_VBR(0));
    // 7 (not default 9) to save battery. Below 7 disables speech/music analysis (opus_encoder.c).
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(7));
    // Off: Mumble neither produces nor consumes in-band FEC.
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
    return std::unique_ptr<AudioEncoder>(new AudioEncoder(enc));
}

AudioEncoder::~AudioEncoder() {
    opus_encoder_destroy(enc_);
}

int AudioEncoder::encode(const int16_t* pcm, int frameSamples, uint8_t* out, int outCap) {
    return opus_encode(enc_, pcm, frameSamples, out, outCap);
}

void AudioEncoder::reset() { opus_encoder_ctl(enc_, OPUS_RESET_STATE); }

}  // namespace dumble
