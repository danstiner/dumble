#include "core/PacketQueue.h"
#include <algorithm>
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

// One frame of the wire's frame_number, the 10 ms it counts in, as samples.
constexpr int kFrameSamples = kFrameNumberMillis * kSamplesPerMilli;

// How many frames a packet spans on the frame clock. Rounded up, not down: a sub-10 ms packet
// (120 or 240 samples, which AudioDecoder::packetSamples accepts) would otherwise leave the
// cursor where it was and draw a spurious hole before every later pop.
uint32_t framesFor(int samples) {
    return uint32_t((samples + kFrameSamples - 1) / kFrameSamples);
}

// Signed distance on the frame clock, wrap-safe. Positive when `a` is later than `b`.
int32_t ahead(uint32_t a, uint32_t b) {
    return int32_t(a - b);
}

}  // namespace

PacketQueue::PacketQueue() : pool_(size_t(kMaxQueuedPackets) * kMaxPacketBytes) {}

void PacketQueue::advanceCursor(const Entry& entry) {
    cursor_ = entry.frame + framesFor(entry.samples);
    haveCursor_ = !entry.terminator;
}

int PacketQueue::holeBefore(const Entry& entry) {
    if (!haveCursor_) return 0;
    const int32_t gap = ahead(entry.frame, cursor_);
    if (gap <= 0) return 0;
    // Bounded by what this queue could ever hold: a wider gap is a discontinuity to re-anchor on,
    // not audio the network lost, and the bound is also what keeps a hostile frame jump from
    // inflating the tally.
    const int64_t cap = kHighWaterSamples - int64_t(holeFrames_) * kFrameSamples;
    const int missing = int(std::min<int64_t>(int64_t(gap) * kFrameSamples, cap));
    lostSamples_ += missing;
    return missing;
}

void PacketQueue::stamp(Entry& entry) {
    // Counted once: a terminator can be latched twice for one packet, when a duplicate or a
    // refused straggler lands after the tail already carries the flag.
    if (entry.terminator) return;
    entry.terminator = 1;
    queuedTerminators_++;
}

void PacketQueue::dropOldest() {
    const Entry& entry = entries_[head_];
    // Only while the cursor is live: establishing one here would invent continuity across a gap
    // pop() has not crossed. A hole before the discarded packet is the network's all the same, so
    // it is tallied; not concealed, because a discard is not a splice.
    if (haveCursor_) {
        holeBefore(entry);
        advanceCursor(entry);
    }
    // Independent of the cursor: a terminator can be shed for backlog while haveCursor_ is already
    // clear, and the count must not outlive the entry that carried it.
    if (entry.terminator) queuedTerminators_--;
    samples_ -= entry.samples;
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
        // Stamped with the spurt's own start frame and not its terminator: a restart whose
        // terminator was lost — see offer()'s doc comment for why no late packet can match.
        // Ended the way the terminator would have; the gate is left alone, so the new spurt
        // still prebuffers.
        const bool spurtOpen = tail ? !tail->terminator : haveCursor_;
        if (spurtOpen && !terminator && frame == spurtStart_) {
            if (tail) {
                stamp(*tail);
            } else {
                haveCursor_ = false;
            }
        }
        // Whether this packet, once stored, begins a spurt. Read after the stamp above.
        const bool opensSpurt = tail ? bool(tail->terminator) : !haveCursor_;
        // Ended at or before the cursor: too late to be anything but a replay of audio already
        // played. Judged on where the packet ends, not where it starts, because Humla stamps
        // a padded terminator a frame behind the packet before it — real speech, refused only
        // if none of it is still ahead of playout.
        //
        // Neither check fires while a terminator is queued — see queuedTerminators_.
        const bool behindPlayout = haveCursor_ && queuedTerminators_ == 0 &&
                                   ahead(frame + framesFor(samples), cursor_) <= 0;
        const bool behindQueue = tail && !tail->terminator && ahead(frame, tail->frame) <= 0;
        if (behindPlayout || behindQueue) {
            // Behind playout, or behind what is queued: counted, not stored.
            outOfOrderPackets_++;
        } else {
            // Ahead of playout and of everything queued: append. Drop before insert, not after:
            // the pool is fixed, so there is no transient state in which an extra packet exists.
            if (count_ == kMaxQueuedPackets) {
                dropOldest();
                droppedPackets_++;
            }
            const int slot = (head_ + count_) & kEntryMask;
            std::memcpy(pool_.data() + size_t(slot) * kMaxPacketBytes, data, size_t(len));
            entries_[slot] = Entry{uint16_t(len), uint16_t(samples), terminator, frame};
            if (opensSpurt) spurtStart_ = frame;
            if (terminator) queuedTerminators_++;
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
    // ends the spurt with whatever is queued, or right now if nothing is.
    if (terminator && !queued) {
        if (count_ > 0) {
            stamp(entries_[(head_ + count_ - 1) & kEntryMask]);
        } else {
            haveCursor_ = false;
        }
    }
}

int PacketQueue::pop(uint8_t* out, int outCap, int target, bool catchUpAllowed,
                     int* concealSamples) {
    // Written before every early return, so the caller never reads a stale request.
    *concealSamples = 0;
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
        // The engine's stall path conceals this gap; attributing it again when audio resumes
        // would double the standing delay.
        haveCursor_ = false;
        return 0;
    }
    emptyAtPop_ = false;
    const Entry& entry = entries_[head_];
    if (entry.len > outCap) return -1;
    if (haveCursor_ && ahead(entry.frame, cursor_) > 0) {
        // A hole: the head starts later than playout has reached. Concealed a frame per pop
        // under the budget in pop()'s doc comment. The capacity cap keeps a far-future stamp
        // from holding a slot in PLC forever, since concealed frames count as production.
        const int concealed = holeFrames_ * kFrameSamples;
        const int budget = samples_ < target ? kHighWaterSamples : kConcealSamples;
        if (concealed < budget) {
            cursor_++;
            holeFrames_++;
            lostSamples_ += kFrameSamples;
            *concealSamples = kFrameSamples;
            return 0;
        }
        holeBefore(entry);
    }
    holeFrames_ = 0;
    advanceCursor(entry);
    if (entry.terminator) queuedTerminators_--;
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
    cursor_ = 0;
    haveCursor_ = false;
    holeFrames_ = 0;
    spurtStart_ = 0;
    queuedTerminators_ = 0;
    droppedPackets_ = 0;
    shrunkPackets_ = 0;
    catchUpPackets_ = 0;
    outOfOrderPackets_ = 0;
    lostSamples_ = 0;
}

void PacketQueue::endFill(bool decoderProduced) {
    if (!decoderProduced && emptyAtPop_) {
        gateOpen_ = false;
        terminated_ = false;
        // A new spurt; the pause before it is not loss.
        haveCursor_ = false;
    }
}

}  // namespace dumble::playout
