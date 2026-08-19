#pragma once
#include <cstdint>
#include <vector>
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One speaker's queue of still-encoded packets — the jitter buffer.
 *
 * Audio waits here compressed for as long as the network requires, bounded by kHighWaterSamples
 * and kMaxQueuedPackets. A new talk spurt is held until the target is queued, so the first
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
     *  `len` is 0, and the latch is what releases a tail below the target. */
    void offer(const uint8_t* data, int len, int samples, bool terminator);

    /** Copies the next playable packet into `out`, returning its length; 0 when the queue is empty
     *  or the prebuffer gate has not opened, negative when `outCap` is too small for the packet.
     *
     *  `target` is the depth the gate waits for, supplied per call rather than held: it is the
     *  engine's estimate, and a queue that stored it would be storing policy. The gate latches
     *  open, so a target that moves mid-spurt cannot re-close it.
     *
     *  `catchUpAllowed` permits the catch-up trim at the moment the gate opens — see the trim in
     *  the implementation for what it costs and why the caller, not this class, decides. Ignored
     *  once the gate is already open.
     *
     *  Copies rather than lending a pointer into the pool: offer() runs on the reader thread and
     *  may overwrite this entry while the caller decodes with the mutex released. */
    int pop(uint8_t* out, int outCap, int target, bool catchUpAllowed);

    /** Closes the fill, re-arming the prebuffer gate once the spurt has fully played out — the
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
     *  A fill whose decoder was already full never pops at all, leaving an older record in place.
     *  Harmless because that fill necessarily produced: `decoderProduced` decides first. */
    void endFill(bool decoderProduced);

    /** Returns the queue to its just-constructed state, for a slot about to serve a different
     *  sender. The pool itself is left alone: entries past `count_` are never read. */
    void reset();

    /** Encoded audio waiting, in samples — the jitter-buffer depth. A duration, not a quantity
     *  held: what this queue stores is bytes, at whatever sample count the caller measured. */
    int depthSamples() const { return samples_; }

    /** No packets queued. The engine pairs this with the decoder's output to tell a speaker that
     *  has stopped talking from one still waiting out its prebuffer. */
    bool empty() const { return count_ == 0; }

    /** Whether dropping the oldest packet would leave at least `floor` samples queued. The
     *  no-undershoot rule as arithmetic rather than as a constant chosen large enough to usually
     *  work: it self-adapts to the sender's packet duration, which is the difference between
     *  correct and merely lucky for a 60 ms sender. */
    bool canShrink(int floor) const;

    /** Discards the oldest packet to shed standing delay. The caller owns the energy gate and the
     *  cooldown; this is only the mechanism. */
    void shrink();

    /** Whether playout has started for the current spurt. */
    bool gateOpen() const { return gateOpen_; }

    /** Packets deliberately discarded to shed delay, split by which mechanism did it. Separate
     *  from droppedPackets(), which means the network cost us audio — neither of these did. */
    int shrunkPackets() const { return shrunkPackets_; }
    int catchUpPackets() const { return catchUpPackets_; }

    /** Gate open and no terminator: the sender is mid-spurt. Distinguishes a dropout from the two
     *  expected silences — prebuffering and speech that ended normally. Read before endFill,
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
    // Whether the queue was empty the last time pop() came up short — endFill's re-arm verdict,
    // recorded at pop time and cleared by a terminator offer(). See endFill().
    bool emptyAtPop_ = false;
    // Whether this spurt was closed by its sender rather than merely stopping. Only speaking()
    // reads it: without it every normal end of speech would look like a dropout.
    bool terminated_ = false;
    int droppedPackets_ = 0;
    int shrunkPackets_ = 0;
    int catchUpPackets_ = 0;
    Entry entries_[kMaxQueuedPackets];
};

}  // namespace dumble::playout
