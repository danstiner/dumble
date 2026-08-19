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
 * Decoded audio exists as PCM for at most one packet's worth of fills — the caller decodes only
 * while below a frame, so the fifo never holds more than a frame plus the one packet that
 * crossed it; concealment fits the same bound, overshooting by less than kConcealGridSamples.
 * Two different units, and deliberately so: the frame is the output unit the device asks for,
 * the packet is the input granularity the sender chose. Everything waiting on the network stays
 * compressed, in PacketQueue.
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

    /** Synthesizes `samples` of libopus concealment into the fifo, rounded up to
     *  kConcealGridSamples, and returns how many samples it wrote. Extrapolated from decoder state,
     *  so it is only meaningful once real audio has decoded through this decoder; with no history
     *  libopus has nothing to extrapolate and answers with silence. */
    int conceal(int samples);

    /** Returns the stage to its just-constructed state, for a slot about to serve a different
     *  sender: libopus keeps prediction state between packets, and it must not carry one
     *  speaker's history into another's first packet. Consumer side, like the rest of this
     *  class — PcmRing only lets its consumer move the read index. */
    void reset();

    /** Decoded samples buffered. */
    int available() const;

    /** Copies min(available, samples) into `out` and zero-pads the rest, returning how many were
     *  real audio. Anything below `samples` is speech spliced with silence, which is why the count
     *  comes back and not just the audio: the padding is indistinguishable once written. */
    int drain(int16_t* out, int samples);

    /** Whether the last decoded frame sat in the bottom of this speaker's observed dynamic range —
     *  Mumble's `pow < fPowerMin + 0.01f * (fPowerMax - fPowerMin)`, over the same asymmetric
     *  envelope in the same RMS-amplitude domain the constants are tuned for. False before any
     *  audio has decoded, which is what stops a fresh slot's opening packet from being judged
     *  against an envelope that does not exist yet.
     *
     *  Describes the frame just decoded, not the packet a caller is about to discard — which plays
     *  a target's worth of samples later, so an attack inside that window can be dropped on the
     *  strength of the silence before it. Mumble has the identical blind spot; the bound is the
     *  target plus the shrink deadband. */
    bool quiet() const;

private:
    SpeakerDecoder(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples);

    const std::unique_ptr<AudioDecoder> decoder_;
    PcmRing fifo_;
    std::vector<int16_t> decodeScratch_;

    // Asymmetric envelope over decoded frame power: the maximum decays slowly and the minimum
    // creeps up, so the range tracks a speaker rather than the loudest thing they ever said.
    float power_ = 0;
    float powerMin_ = 0;
    float powerMax_ = 0;
};

}  // namespace dumble::playout
