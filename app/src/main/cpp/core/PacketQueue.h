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
 * buffering policy — gate, high-water, drop-oldest — testable on synthetic bytes.
 *
 * The pool is a FIFO ring by arrival. The sender's `frame_number` is kept beside each packet, for
 * two things only: refusing a packet that arrives behind what is queued or already played, and
 * letting pop() ask the caller to conceal what is missing a frame at a time. Reordering is not
 * attempted — see outOfOrderPackets().
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
     *  `len` is 0, and the latch is what releases a tail below the target.
     *
     *  `frameNumber` is the sender's frame clock, counting 10 ms frames (kFrameNumberMillis), so
     *  a 20 ms packet advances it by two. A packet whose audio ends
     *  at or before the play cursor, or that is not ahead of the packet queued last, is refused and
     *  counted rather than stored. Both checks hold within one spurt only: they stand down for
     *  every packet offered while a terminator is queued, so a sender whose counter restarts every
     *  spurt (mumble-web restarts at zero) is not refused for landing behind the spurt that just
     *  ended. The terminator flag itself latches before either check, so a refused terminator
     *  still ends the spurt. A packet without the terminator flag stamped with the frame the
     *  current spurt began on cannot be late (a straggler of this spurt is after it, of an
     *  earlier one behind it, a repeat of the first is a replay the crypt layer refuses), so it is
     *  taken as a restart whose terminator was lost: it ends the spurt and begins the next.
     *  Anything behind the start is a straggler and refused however late.
     *
     *  Two packets stamped alike inside a spurt are a duplicate to this check, so a sender whose
     *  packets are shorter than one frame (2.5 or 5 ms) keeps only the first of each frame after
     *  the spurt's first, and opens a new spurt for each packet stamped with the first: a trade
     *  for duplicate suppression, taken because Mumble's own client cannot send one and
     *  JitterEstimator already declines to estimate for it. */
    void offer(const uint8_t* data, int len, int samples, bool terminator, uint64_t frameNumber);

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
     *  `concealSamples` is the queue asking for concealment instead of a packet. When it comes
     *  back non-zero the return is 0 and the caller conceals that much, then pops again; it is
     *  only ever 0 or one frame. The head starts later on the sender's frame clock than playout
     *  has reached, and the frames between are concealed one per pop: always for the PLC fade
     *  (kConcealSamples), beyond that only while the queue is below `target` and never past
     *  kHighWaterSamples. Below target each frame of silence lets a frame of new audio arrive
     *  and buys back margin the loss took; at target the break has been heard and the spurt
     *  re-anchors instead: the cursor jumps to the head, the rest of the gap is tallied, and the
     *  head is returned. A return of 0 with `*concealSamples == 0` is an empty or gated pop.
     *  Always 0 on the tunneled path, where nothing is ever missing.
     *
     *  Copies rather than lending a pointer into the pool: offer() runs on the reader thread and
     *  may overwrite this entry while the caller decodes with the mutex released. */
    int pop(uint8_t* out, int outCap, int target, bool catchUpAllowed, int* concealSamples);

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

    /** Audio the network never delivered, in samples: the frame-number gap before each packet
     *  popped or discarded while playout is under way. Distinct from droppedPackets(), which is
     *  audio we had and shed. Counted even when the gap was too wide to conceal, at most
     *  kHighWaterSamples per gap. */
    int64_t lostSamples() const { return lostSamples_; }

    /** Packets refused because their audio ended at or behind the play cursor, or because they
     *  were not ahead of the packet queued last — stragglers and duplicates. Reordering them
     *  would mean decoupling entries from their pool slots, and reorder on one UDP path is rare
     *  enough that this counter ships first, so the decision is made on a measurement. */
    int outOfOrderPackets() const { return outOfOrderPackets_; }

private:
    /** `frame` is the low 32 bits of the wire's frame number, compared by signed difference like
     *  a TCP sequence number, so a sender's counter wrapping or restarting needs no case of its
     *  own. `samples` is bounded by kMaxPacketSamples, which PacketQueue.cpp static_asserts still
     *  fits its 15 bits. */
    struct Entry {
        uint16_t len = 0;
        uint16_t samples : 15 = 0;
        uint16_t terminator : 1 = 0;
        uint32_t frame = 0;
    };
    static_assert(sizeof(Entry) == 8, "Entry has grown padding");

    /** Discards the oldest queued packet. */
    void dropOldest();

    /** Moves the play cursor past `entry`, ending continuity if it was the sender's terminator. */
    void advanceCursor(const Entry& entry);

    /** Audio missing between the play cursor and `entry`, in samples, added to lostSamples();
     *  0 while the cursor is dead. Capped so that one gap costs at most kHighWaterSamples with the
     *  frames pop() already concealed for it counted in. */
    int holeBefore(const Entry& entry);

    /** Ends the spurt at `entry`, counting the terminator once. */
    void stamp(Entry& entry);

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
    // Where playout has reached on the sender's frame clock: the frame after the last audio
    // delivered, valid only while haveCursor_. A hole is the gap between it and the next packet
    // popped, and nothing else — frame_number keeps running through silence on most senders and
    // stands still on others, so a jump between two spurts says nothing about loss. Every way
    // continuity can end clears the cursor instead: the sender's terminator, a pop that came up
    // empty (the engine's stall path already concealed that gap), the gate re-arming, reset().
    // A deliberate discard advances it past what was discarded, tallying any hole before it, so
    // shedding delay is not re-injected as concealment.
    uint32_t cursor_ = 0;
    bool haveCursor_ = false;
    // The frame the spurt in progress began on. A packet stamped with it is a restart whose
    // terminator was lost — see offer().
    uint32_t spurtStart_ = 0;
    // Frames concealed in the hole in front of the head, one per pop; what pop()'s conceal
    // budget is measured against. Reset when a packet pops. Not by a discard: the fade already
    // played, so a jump right after a mid-hole shrink still has it in front.
    int holeFrames_ = 0;
    // Terminators queued; while any is, offer()'s ordering checks stand down. A count, not a
    // flag: two short spurts fit in one queue depth, and popping the first's terminator must not
    // wake the checks on the second.
    int queuedTerminators_ = 0;
    int droppedPackets_ = 0;
    int shrunkPackets_ = 0;
    int catchUpPackets_ = 0;
    int outOfOrderPackets_ = 0;
    int64_t lostSamples_ = 0;
    Entry entries_[kMaxQueuedPackets];
};

}  // namespace dumble::playout
