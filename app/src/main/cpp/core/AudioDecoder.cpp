#include "core/AudioDecoder.h"
#include <opus.h>

namespace dumble::playout {

std::unique_ptr<AudioDecoder> AudioDecoder::create(int sampleRate, int channels) {
    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) {
        if (dec != nullptr) opus_decoder_destroy(dec);
        return nullptr;
    }
    // make_unique cannot reach a private constructor, and widening it would reopen the hole the
    // factory closes.
    return std::unique_ptr<AudioDecoder>(new AudioDecoder(dec));
}

int AudioDecoder::packetSamples(const uint8_t* data, int len, int sampleRate) {
    // Header-only: opus_packet_get_nb_frames reads the TOC byte plus, for code 3, the frame-count
    // byte, and uses `len` for nothing but its own too-short checks. So this measures a packet
    // before its payload is copied, and never touches the payload however long `len` claims to be.
    return opus_packet_get_nb_samples(data, len, sampleRate);
}

AudioDecoder::~AudioDecoder() {
    opus_decoder_destroy(dec_);
}

int AudioDecoder::decode(const uint8_t* data, int len, int16_t* out, int outCap) {
    if (outCap <= 0) return OPUS_BAD_ARG;
    // decode_fec = 0, matching the desktop client, which conceals from decoder state instead.
    return opus_decode(dec_, data, len, out, outCap, 0);
}

}  // namespace dumble::playout
