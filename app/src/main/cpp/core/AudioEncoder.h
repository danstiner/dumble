#pragma once
#include <cstdint>
#include <memory>
struct OpusEncoder;

namespace dumble {

/** Opus encoder configured to match Mumble where applicable. */
class AudioEncoder {
public:
    /** Null on failure. Factory so no half-built encoder exists. */
    static std::unique_ptr<AudioEncoder> create(int sampleRate, int channels, int bitrate);

    /** VOIP below 64 kb/s, RESTRICTED_LOWDELAY at or above. Public for test pinning. */
    static int applicationForBitrate(int bitrate);

    ~AudioEncoder();
    AudioEncoder(const AudioEncoder&) = delete;
    AudioEncoder& operator=(const AudioEncoder&) = delete;

    /** Returns bytes written, or a negative libopus error. */
    int encode(const int16_t* pcm, int frameSamples, uint8_t* out, int outCap);

    /** Drop predictor state at spurt onset — receivers start each spurt with a fresh decoder. */
    void reset();

private:
    explicit AudioEncoder(OpusEncoder* enc) : enc_(enc) {}

    OpusEncoder* const enc_;
};

}  // namespace dumble
