#include "core/AudioEncoder.h"
#include <opus.h>

namespace dumble {

// Two application presets by bitrate.

// - VOIP mode uses SILK (speech codec), blending in CELT (general audio codec) as bitrate allows,
//   to achieve high quality voice at very low bitrates. This comes with a complexity cost, both a
//   6.5 ms lookahead and a modest CPU cost. It alone also high-passes the input at a cutoff
//   tracking the talker's pitch.
// - RESTRICTED_LOWDELAY from 64+ kb/s forces CELT, which opus would choose near that rate anyway.
//   This trims lookahead 6.5 -> 2.5 ms, saving 4 ms of latency, and reduces CPU usage slightly.
// - AUDIO mode is not used here. It would still pick SILK below ~60 kb/s exactly like VOIP; the
//   one thing it changes for us is dropping that high-pass filter.
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
    // Constant bit-rate encoding, which is what real-time voice on a shared network wants: a steady
    // packet size keeps our contribution to queueing delay predictable instead of bursty. It also
    // fits with Murmur's sliding-window bandwidth estimator used to cap per-speaker bandwidth.
    opus_encoder_ctl(enc, OPUS_SET_VBR(0));
    // Reduce encoding complexity from libopus's default of 9, trading a small loss in quality for
    // less work per frame to save battery. We stop at 7: in floating-point builds the speech/music
    // analysis is gated on complexity >= 7 (opus_encoder.c), and its voice estimate steers the
    // SILK-versus-CELT decision in the VOIP application preset above.
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(7));
    // In-band redundancy stays off for now. The official Mumble client neither produces it nor
    // consumes it — its encoder sets no redundancy controls, and it's opus decode pipeline sets
    // decode_fec = 0, concealing loss from decoder state instead.
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));
    // make_unique cannot reach a private constructor, and widening it to do so would reopen the
    // very hole the factory closes.
    return std::unique_ptr<AudioEncoder>(new AudioEncoder(enc));
}

AudioEncoder::~AudioEncoder() {
    opus_encoder_destroy(enc_);
}

int AudioEncoder::encode(const int16_t* pcm, int frameSamples, uint8_t* out, int outCap) {
    return opus_encode(enc_, pcm, frameSamples, out, outCap);
}

}  // namespace dumble
