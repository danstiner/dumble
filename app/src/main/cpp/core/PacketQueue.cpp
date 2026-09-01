#include "core/PacketQueue.h"
#include <bit>
#include <cassert>
#include <cstdlib>
#include <cstring>

namespace dumble::playout {
namespace {

// kMaxQueuedPackets is a power of two so the ring wrap is a mask.
static_assert(std::has_single_bit(unsigned(kMaxQueuedPackets)));
constexpr int kEntryMask = kMaxQueuedPackets - 1;

// Entry narrows both fields. Neither bound is close to the limit, but a silent truncation here
// would corrupt depth accounting rather than fail, so it is a compile error.
static_assert(kMaxPacketBytes <= UINT16_MAX);
static_assert(kMaxPacketSamples < (1 << 15));

// Why the drop loop below needs no guard against emptying the queue: one packet cannot exceed the
// cap on its own, because the largest legal Opus packet is a fraction of it.
static_assert(kMaxPacketSamples <= kHighWaterSamples);

// Signed distance on the frame clock, wrap-safe. Positive when `a` is later than `b`.
int32_t ahead(uint32_t a, uint32_t b) {
    return int32_t(a - b);
}

// A packet this far behind the tail could never be placed in the ring, so it is not a straggler
// but a sender whose counter restarted.
constexpr int32_t kRestartUnits = kHighWaterSamples / (kFrameNumberMillis * kSamplesPerMilli);

}  // namespace

PacketQueue::PacketQueue() : pool_(size_t(kMaxQueuedPackets) * kMaxPacketBytes) {}

void PacketQueue::dropOldest() {
    samples_ -= entries_[head_].samples;
    head_ = (head_ + 1) & kEntryMask;
    count_--;
}

bool PacketQueue::canShrink(int floor) const {
    return count_ > 0 && samples_ - entries_[head_].samples >= floor;
}

void PacketQueue::shrink() {
    dropOldest();
    shrunkPackets_++;
}

void PacketQueue::offer(const uint8_t* data, int len, int samples, bool terminator,
                        uint64_t frameNumber) {
    // Caller should already check the packet, these document the contract. No peer can reach any
    // of them — an oversized or unmeasurable packet is refused and reported before this is called
    // — so a failure here is a bug in the caller.
    assert(len <= kMaxPacketBytes && "packet is larger than one pool entry");
    assert(samples <= kMaxPacketSamples && "packet has more samples than the largest Opus packet");
    assert((len > 0) == (samples > 0) && "a packet either has bytes and samples, or neither");
    // The one contract enforced in release too, because it is the only one whose consequence is
    // the memcpy below overrunning the pool. There is nothing to recover from a broken caller,
    // and a tombstone beats corruption.
    if (len > kMaxPacketBytes) std::abort();
    // Open the playout gate immediately when a spurt terminates so whatever we have queued plays.
    // endFill will close the gate again once the queue drains. Clearing emptyAtPop_ is what lets
    // the latch survive an endFill already in flight: the engine may have seen this queue empty at
    // its last pop, and a terminator landing before its endFill says the spurt is complete — the
    // re-arm must not close the gate over it, or a short spurt below the prebuffer never plays.
    //
    // Latched before the packet is judged below: a terminator refused as a straggler or a
    // duplicate still ends the spurt, or a short spurt below the prebuffer stays silent until
    // kStallIdleSamples retires the slot.
    //
    // This is a second gate-open, and deliberately not a trim point. A stall that ends with the
    // sender falling silent flushes its whole backlog and this terminator together, so up to
    // kHighWaterSamples plays out untrimmed — six times the bound of the conceal-hold case.
    // Trimming here would be the wrong trade: pop()'s trim discards stale continuation before a
    // spurt resumes, whereas everything queued behind a terminator is the tail of a finished
    // sentence. Dropping it costs the listener the end of the words, to reclaim delay that drains
    // on its own and that no later audio inherits — the gate re-arms once the tail plays out and
    // the next spurt prebuffers fresh. The exception is a spurt that resumes inside that drain
    // window: it appends behind the stale tail, so the gate never re-arms and the inherited delay
    // unwinds only through shrink.
    if (terminator) {
        gateOpen_ = true;
        emptyAtPop_ = false;
        terminated_ = true;
    }
    const uint32_t frame = uint32_t(frameNumber);
    bool queued = false;
    // Only a packet with audio gets an entry. A terminator without payload has nothing to store;
    // its flag is stamped on the tail below.
    if (samples > 0) {
        // The ring is strictly increasing by construction, so the tail alone answers for every
        // entry. A queued terminator ends the comparison: the next packet begins a new spurt,
        // wherever its sender's counter restarts.
        Entry* tail = count_ > 0 ? &entries_[(head_ + count_ - 1) & kEntryMask] : nullptr;
        // Further behind the tail than the ring can hold is no straggler the ring could still
        // use: the sender's counter restarted (mumble-web restarts at zero every spurt) and the
        // terminator that would have ended the comparison was lost. Supplied here: the tail ends
        // its spurt and this packet begins the next. The gate is left alone, so the new spurt
        // still prebuffers.
        if (tail && !tail->terminator && ahead(frame, tail->frame) < -kRestartUnits) {
            tail->terminator = 1;
        }
        if (tail && !tail->terminator && ahead(frame, tail->frame) <= 0) {
            // A duplicate, or reordered behind what is queued: counted, not stored.
            outOfOrderPackets_++;
        } else {
            // Later than everything queued: append. Drop before insert, not after: the pool is
            // fixed, so there is no transient state in which an extra packet exists.
            if (count_ == kMaxQueuedPackets) {
                dropOldest();
                droppedPackets_++;
            }
            const int slot = (head_ + count_) & kEntryMask;
            std::memcpy(pool_.data() + size_t(slot) * kMaxPacketBytes, data, size_t(len));
            entries_[slot] = Entry{uint16_t(len), uint16_t(samples), terminator, frame};
            count_++;
            samples_ += samples;
            while (samples_ > kHighWaterSamples) {
                dropOldest();
                droppedPackets_++;
            }
            queued = true;
        }
    }
    // A terminator with no entry of its own to carry the flag — no payload, or refused above —
    // ends the spurt with whatever is queued.
    if (terminator && !queued && count_ > 0) {
        entries_[(head_ + count_ - 1) & kEntryMask].terminator = 1;
    }
}

int PacketQueue::pop(uint8_t* out, int outCap, int target, bool catchUpAllowed) {
    // Do not start playout until we have sufficient pre-roll buffer.
    if (!gateOpen_) {
        // A full ring is as ready as this queue can get. kMaxQueuedPackets binds before
        // kHighWaterSamples for any sender below 20 ms, so a target above what the ring can hold
        // would otherwise keep the gate shut forever and drop every arriving packet as overflow.
        if (samples_ < target && count_ < kMaxQueuedPackets) {
            emptyAtPop_ = count_ == 0;
            return 0;
        }
        // The one place audio can be discarded without a splice: nothing is playing, so what goes
        // is never cut out of a stream, it is simply never started. Only when the caller says this
        // gate-open follows a stall rather than opening a spurt — the oldest packet of a spurt is
        // its first syllable, which is what the prebuffer exists to protect.
        // The threshold decides *whether* to trim; the target decides *how far*. Folding both into
        // one loop condition would stop at the threshold and leave a backlog the gate just proved
        // was too large — the overshoot is what triggers a re-anchor, not what survives it.
        if (catchUpAllowed && samples_ > target + kCatchUpThresholdSamples) {
            while (canShrink(target)) {
                dropOldest();
                catchUpPackets_++;
            }
        }
        gateOpen_ = true;
    }
    if (count_ == 0) {
        emptyAtPop_ = true;
        return 0;
    }
    emptyAtPop_ = false;
    const Entry& entry = entries_[head_];
    if (entry.len > outCap) return -1;
    std::memcpy(out, pool_.data() + size_t(head_) * kMaxPacketBytes, size_t(entry.len));
    samples_ -= entry.samples;
    head_ = (head_ + 1) & kEntryMask;
    count_--;
    return entry.len;
}

void PacketQueue::reset() {
    head_ = 0;
    count_ = 0;
    samples_ = 0;
    gateOpen_ = false;
    emptyAtPop_ = false;
    terminated_ = false;
    droppedPackets_ = 0;
    shrunkPackets_ = 0;
    catchUpPackets_ = 0;
    outOfOrderPackets_ = 0;
}

void PacketQueue::endFill(bool decoderProduced) {
    if (!decoderProduced && emptyAtPop_) {
        gateOpen_ = false;
        terminated_ = false;
    }
}

}  // namespace dumble::playout
