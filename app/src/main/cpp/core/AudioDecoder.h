#pragma once
#include <cstdint>
#include <memory>
struct OpusDecoder;

namespace dumble::playout {

/** One speaker's libopus decoder. Sibling of AudioEncoder, and a factory for the same reason:
 *  a failed create has no object to inhabit, so decode() needs no null test on a hot path. */
class AudioDecoder {
public:
    /** Null when libopus rejects the parameters or cannot allocate. */
    static std::unique_ptr<AudioDecoder> create(int sampleRate, int channels);

    /** Samples this packet decodes to, or a negative libopus error. Reads at most two bytes. */
    static int packetSamples(const uint8_t* data, int len, int sampleRate);

    ~AudioDecoder();
    AudioDecoder(const AudioDecoder&) = delete;
    AudioDecoder& operator=(const AudioDecoder&) = delete;

    /** Samples written, or a negative libopus error. `outCap` is what stops libopus writing past
     *  `out` — it does no bounds checking of its own — so it must be the real capacity. */
    int decode(const uint8_t* data, int len, int16_t* out, int outCap);

private:
    explicit AudioDecoder(OpusDecoder* dec) : dec_(dec) {}

    OpusDecoder* const dec_;
};

}  // namespace dumble::playout
