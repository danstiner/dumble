#pragma once
#include <cstdint>
#include <vector>
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One speaker's queue of still-encoded packets — the jitter buffer.
 *
 * Audio waits here compressed for as long as the network requires, bounded by kHighWaterSamples
 * and kPacketSlots. A new talk spurt is held until kPrebufferSamples are queued, so the first
 * network stall does not glitch the first syllable.
 *
 * Knows nothing about Opus, and holds no audio of its own — only bytes, and the sample count the
 * caller measured them at. The caller parses the packet header to reject a payload libopus cannot
 * read, so nothing here needs a decoder to decide what to keep or drop. That is what makes the
 * buffering policy — gate, high-water, drop-oldest — testable on synthetic bytes, and it is where
 * a reorder buffer keyed on `frame_number` grows when UDP lands.
 *
 * Arrival order is correct order today: this slice receives audio only through the TCP tunnel,
 * which delivers in order and without loss, so the pool is a plain FIFO ring.
 *
 * Not internally synchronized. PlayoutEngine holds its mutex across every method here.
 */
class PacketQueue {
public:
    PacketQueue();

    /** False when the payload was refused: a non-empty payload whose `spanSamples` is not a legal
     *  Opus packet length, or — unreachable from PlayoutEngine, which checks first — one longer
     *  than kMaxPacketBytes, the one case that discards the terminator too. Otherwise the
     *  terminator is honoured whether or not anything was queued: `data` may be null when `len` is
     *  0, and either way the latch is what releases a tail below kPrebufferSamples. */
    bool offer(const uint8_t* data, int len, int spanSamples, bool terminator);

    /** Copies the next playable packet into `out`, returning its length; 0 when the queue is empty
     *  or the prebuffer gate has not opened, negative when `outCap` is too small for the packet.
     *
     *  Copies rather than lending a pointer into the pool: offer() runs on the reader thread and
     *  may overwrite this slot while the caller decodes with the mutex released. */
    int pop(uint8_t* out, int outCap);

    /** Closes the tick, re-arming the prebuffer gate once the spurt has fully played out — this
     *  queue is empty and the decoder emitted nothing. On idle rather than on the terminator
     *  frame, so the tail of a spurt plays out first and a spurt whose terminator never arrives
     *  still re-arms. It takes the decoder's answer because an empty queue alone does not mean
     *  idle: packets already popped may still be playing out downstream. */
    void endTick(bool decoderProduced);

    /** Encoded audio waiting, in samples — the jitter-buffer depth. A duration, not a quantity
     *  held: what this queue stores is bytes, at whatever sample count the caller measured. */
    int depthSamples() const { return depthSamples_; }

    /** No packets queued. The engine pairs this with the decoder's output to tell a speaker that
     *  has stopped talking from one still waiting out its prebuffer. */
    bool empty() const { return count_ == 0; }

private:
    /** Four bytes, so the whole ring is two cache lines. Both fields are bounded by constants
     *  small enough to narrow; PacketQueue.cpp static_asserts that they still are. */
    struct Slot {
        uint16_t len = 0;
        uint16_t spanSamples = 0;
    };
    static_assert(sizeof(Slot) == 4, "the ring's footprint is the point");

    /** Discards the oldest queued packet. */
    void dropOldest();

    // Ordered for the pop path, which reads gateOpen_, depthSamples_, head_, count_ and the
    // pool's data pointer: keeping them ahead of slots_ puts all five in the first cache line, so
    // a pop touches that line plus the one Slot it wants, rather than straddling the array.
    std::vector<uint8_t> pool_;  // kPacketSlots * kMaxPacketBytes, allocated once
    int head_ = 0;
    int count_ = 0;
    int depthSamples_ = 0;
    bool gateOpen_ = false;
    Slot slots_[kPacketSlots];
};

}  // namespace dumble::playout
