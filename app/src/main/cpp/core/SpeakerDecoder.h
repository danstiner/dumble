#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "core/AudioDecoder.h"
#include "core/PcmRing.h"
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One speaker's decode stage: encoded packets in, quantum-sized PCM out.
 *
 * Decoded audio exists as PCM for at most one packet's worth of ticks — the caller decodes only
 * while below a quantum, so the fifo never holds more than a quantum plus the one packet that
 * crossed it. Two different units, and deliberately so: the quantum is the output tick the device
 * asks for, the packet is the input granularity the sender chose. Everything waiting on the network stays compressed, in PacketQueue.
 *
 * Playback-thread-only. PlayoutEngine pops a packet under its mutex and then calls decode() with
 * the mutex released, so a slow decode never stalls the reader thread; nothing here may be touched
 * from the reader side. Takes no lock of its own: PcmRing is an SPSC ring whose two ends both sit
 * on this thread today, which costs a couple of atomics and keeps the drain movable to an Oboe
 * callback without changing anything here.
 *
 * Mono, and not a parameter: decodeScratch_ is sized in total samples while opus_decode's
 * frame_size counts per channel, and decode() treats the return as a total. Mumble carries mono
 * voice and spatializes from positional_data at the far end, so there is no second channel to
 * plumb — a stereo output would pan mono speakers in the mixer, not decode two.
 */
class SpeakerDecoder {
public:
    /** Null when libopus cannot build a decoder, or when `maxQuantumSamples` is outside
     *  (0, kMaxPacketSamples] — the fifo is sized from it, and the rounding overflows above that. */
    static std::unique_ptr<SpeakerDecoder> create(int sampleRate, int maxQuantumSamples);

    /** Decodes one packet into the PCM fifo. A payload libopus refuses adds nothing and is not an
     *  error here: PlayoutEngine measures every packet before it is queued, so the only way one
     *  reaches this point is a payload that parsed as a header and then failed to decode. */
    void decode(const uint8_t* data, int len);

    /** Decoded samples buffered. */
    int available() const;

    /** Copies min(available, samples) into `out` and zero-pads the rest, returning how many were
     *  real audio. Anything below `samples` is speech spliced with silence, which is why the count
     *  comes back and not just the audio: the padding is indistinguishable once written. */
    int drain(int16_t* out, int samples);

private:
    SpeakerDecoder(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples);

    const std::unique_ptr<AudioDecoder> decoder_;
    PcmRing fifo_;
    std::vector<int16_t> decodeScratch_;
};

}  // namespace dumble::playout
