#pragma once
#include <cstdint>
#include <vector>
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One speaker's queue of still-encoded packets — the jitter buffer.
 *
 * Audio waits here compressed for as long as the network requires, bounded by kHighWaterSamples
 * and kMaxQueuedPackets. A new talk spurt is held until kPrebufferSamples are queued, so the first
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

    /** Stores one packet and latches `terminator`. Cannot fail: PlayoutEngine decides what a
     *  valid packet is and normalises anything it cannot schedule to no payload, so the
     *  preconditions here — `len` fits one pool entry, `samples` fits Entry's uint16_t, and the two
     *  agree — are its contract, asserted rather than judged, since no peer can reach them. An
     *  oversized `len` aborts in release as well: past one entry lies the rest of the pool.
     *
     *  A terminator with no payload is the ordinary way a spurt ends: `data` may be null when
     *  `len` is 0, and the latch is what releases a tail below kPrebufferSamples. */
    void offer(const uint8_t* data, int len, int samples, bool terminator);

    /** Copies the next playable packet into `out`, returning its length; 0 when the queue is empty
     *  or the prebuffer gate has not opened, negative when `outCap` is too small for the packet.
     *
     *  Copies rather than lending a pointer into the pool: offer() runs on the reader thread and
     *  may overwrite this entry while the caller decodes with the mutex released. */
    int pop(uint8_t* out, int outCap);

    /** Closes the tick, re-arming the prebuffer gate once the spurt has fully played out — the
     *  last pop() came up empty and the decoder emitted nothing. On idle rather than on the
     *  terminator packet, so the tail of a spurt plays out first and a spurt whose terminator
     *  never arrives still re-arms. It takes the decoder's answer because an empty queue alone
     *  does not mean idle: packets already popped may still be playing out downstream.
     *
     *  Judged on the emptiness pop() recorded, not on count_ now: the engine decodes other
     *  speakers between its last pop and this call, and a packet offered in that window must not
     *  sway the verdict. A new spurt's first packet waits out the prebuffer rather than riding a
     *  gate the finished spurt left open; a terminator's latch clears the record in offer() and
     *  so survives, else a complete short spurt would be silenced and eventually reset away.
     *
     *  A tick whose decoder was already full never pops at all, leaving an older record in place.
     *  Harmless because that tick necessarily produced: `decoderProduced` decides first. */
    void endTick(bool decoderProduced);

    /** Returns the queue to its just-constructed state, for a slot about to serve a different
     *  sender. The pool itself is left alone: entries past `count_` are never read. */
    void reset();

    /** Encoded audio waiting, in samples — the jitter-buffer depth. A duration, not a quantity
     *  held: what this queue stores is bytes, at whatever sample count the caller measured. */
    int depthSamples() const { return samples_; }

    /** No packets queued. The engine pairs this with the decoder's output to tell a speaker that
     *  has stopped talking from one still waiting out its prebuffer. */
    bool empty() const { return count_ == 0; }

    /** Gate open and no terminator: the sender is mid-spurt. Distinguishes a dropout from the two
     *  expected silences — prebuffering and speech that ended normally. Read before endTick,
     *  which clears both flags. */
    bool speaking() const { return gateOpen_ && !terminated_; }

    /** Packets thrown away for backlog: past kMaxQueuedPackets or kHighWaterSamples. Cleared by
     *  reset(), so a retiring slot must be harvested first. */
    int droppedPackets() const { return droppedPackets_; }

private:
    /** Four bytes, so the whole ring is two cache lines. Both fields are bounded by constants
     *  small enough to narrow; PacketQueue.cpp static_asserts that they still are. */
    struct Entry {
        uint16_t len = 0;
        uint16_t samples = 0;
    };
    static_assert(sizeof(Entry) == 4, "the ring's footprint is the point");

    /** Discards the oldest queued packet. */
    void dropOldest();

    // Ordered for the pop path, which reads gateOpen_, samples_, head_, count_ and the
    // pool's data pointer: keeping them ahead of entries_ puts all five in the first cache
    // line, so a pop touches that line plus the one Entry it wants, not the whole array.
    std::vector<uint8_t> pool_;  // kMaxQueuedPackets * kMaxPacketBytes, allocated once
    int head_ = 0;
    int count_ = 0;
    int samples_ = 0;
    bool gateOpen_ = false;
    // Whether the queue was empty the last time pop() came up short — endTick's re-arm verdict,
    // recorded at pop time and cleared by a terminator offer(). See endTick().
    bool emptyAtPop_ = false;
    // Whether this spurt was closed by its sender rather than merely stopping. Only speaking()
    // reads it: without it every normal end of speech would look like a dropout.
    bool terminated_ = false;
    int droppedPackets_ = 0;
    Entry entries_[kMaxQueuedPackets];
};

}  // namespace dumble::playout
