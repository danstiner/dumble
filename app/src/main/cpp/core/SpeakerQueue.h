#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "core/AudioDecoder.h"
#include "core/PcmRing.h"
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One speaker's playout path: encoded packets in, quantum-sized PCM out.
 *
 * offer() appends still-encoded packets to a preallocated pool; each tick pops and decodes just
 * enough of them to emit one quantum. Audio therefore waits compressed for as long as the network
 * requires (capped by kHighWaterSamples and kPacketSlots) and exists as PCM for at most one
 * frame's worth of ticks. A new talk spurt is held until kPrebufferSamples are queued, so the
 * first network stall does not glitch the first syllable.
 *
 * Not internally synchronized. PlayoutEngine holds its mutex across offer(), popPacket(),
 * endTick(), and queuedSamples(), and releases it around decodeInto()/drain()/pcmAvailable(),
 * which are playback-thread-only — so a slow decode never stalls the reader.
 *
 * Arrival order is correct order: this slice receives audio only through the TCP tunnel, which
 * delivers in order and without loss, so the pool is a plain FIFO ring rather than a
 * timestamp-keyed reorder buffer. `frame_number` becomes load-bearing when UDP lands.
 */
class SpeakerQueue {
public:
    /** Null when libopus cannot build a decoder — the only way this fails. */
    static std::unique_ptr<SpeakerQueue> create(int sampleRate, int maxQuantumSamples);

    /** Engine mutex held. False when a payload was refused: `spanSamples` <= 0 with a
     *  non-empty payload, or — unreachable from PlayoutEngine — larger than kMaxPacketBytes, the
     *  one case that discards the terminator too. Otherwise the terminator is honoured whether or
     *  not anything was queued: `data` may be null when `len` is 0, a malformed payload may price
     *  to nothing, and either way the latch is what releases a tail below kPrebufferSamples. */
    bool offer(const uint8_t* data, int len, int spanSamples, bool terminator);

    /** Engine mutex held. Copies the next playable packet into `out`, returning its length; 0 when
     *  the pool is empty or the prebuffer gate has not opened, negative when `outCap` is too
     *  small for the packet. */
    int popPacket(uint8_t* out, int outCap);

    /** Playback thread, no lock. Decodes one packet into the PCM fifo. */
    void decodeInto(const uint8_t* data, int len);

    /** Playback thread, no lock. */
    int pcmAvailable() const;

    /** Playback thread, no lock. Copies min(available, frames) into `out` and zero-pads the rest,
     *  returning how many samples were real audio. Anything below `frames` is speech spliced with
     *  silence — an audible gap the caller counts. */
    int drain(int16_t* out, int frames);

    /** Engine mutex held. Closes the tick: updates idle accounting and the prebuffer re-arm.
     *  Returns true once this queue has retired and its slot must be released. */
    bool endTick(bool produced);

    /** Engine mutex held. Encoded audio waiting, in samples — the jitter-buffer depth. The PCM
     *  fifo is deliberately excluded: it is bounded at one quantum plus one frame and says nothing
     *  about how much delay the network has added. */
    int queuedSamples() const { return queuedSamples_; }


private:
    SpeakerQueue(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples);

    struct Slot {
        int len = 0;
        int spanSamples = 0;
    };

    /** Engine mutex held. Discards the oldest queued packet. */
    void dropOldest();

    const std::unique_ptr<AudioDecoder> decoder_;
    std::vector<uint8_t> pool_;          // kPacketSlots * kMaxPacketBytes, allocated once
    Slot slots_[kPacketSlots];
    int head_ = 0;
    int count_ = 0;
    int queuedSamples_ = 0;
    bool prebuffered_ = false;

    // Playback-thread-only below.
    PcmRing fifo_;
    std::vector<int16_t> decodeScratch_;
    int idleTicks_ = 0;
};

}  // namespace dumble::playout
