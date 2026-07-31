#pragma once
#include <cstdint>
#include <memory>
struct OpusEncoder;

namespace dumble {

/** Configured mostly the way Mumble configures its own encoder; for each deviation (application
 *  preset tiers, lowered encode complexity) — we argue the case where it is set. */
class AudioEncoder {
public:
    /** Null when libopus rejects the parameters or cannot allocate — the only way this fails.
     *  A factory rather than a constructor so a failed create has no object to inhabit: there is
     *  no half-built encoder to check for, and encode() needs no null test on a hot path. */
    static std::unique_ptr<AudioEncoder> create(int sampleRate, int channels, int bitrate);

    /** VOIP until the rate reaches Mumble's low-delay tier at 64 kb/s. Public so the tests can
     *  pin the boundary. */
    static int applicationForBitrate(int bitrate);

    ~AudioEncoder();
    AudioEncoder(const AudioEncoder&) = delete;
    AudioEncoder& operator=(const AudioEncoder&) = delete;

    /** Returns bytes written, or a negative libopus error. */
    int encode(const int16_t* pcm, int frameSamples, uint8_t* out, int outCap);

private:
    explicit AudioEncoder(OpusEncoder* enc) : enc_(enc) {}

    OpusEncoder* const enc_;
};

}  // namespace dumble
