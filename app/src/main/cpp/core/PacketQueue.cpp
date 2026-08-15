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

// Entry narrows both fields to uint16_t. Neither bound is close to the limit, but a silent
// truncation here would corrupt depth accounting rather than fail, so it is a compile error.
static_assert(kMaxPacketBytes <= UINT16_MAX);
static_assert(kMaxPacketSamples <= UINT16_MAX);

// Why the drop loop below needs no guard against emptying the queue: one packet cannot exceed the
// cap on its own, because the largest legal Opus packet is a fraction of it.
static_assert(kMaxPacketSamples <= kHighWaterSamples);

}  // namespace

PacketQueue::PacketQueue() : pool_(size_t(kMaxQueuedPackets) * kMaxPacketBytes) {}

void PacketQueue::dropOldest() {
    samples_ -= entries_[head_].samples;
    head_ = (head_ + 1) & kEntryMask;
    count_--;
}

void PacketQueue::offer(const uint8_t* data, int len, int samples, bool terminator) {
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
    // Only push an entry for packets that contain audio samples.
    if (samples > 0) {
        // Drop before insert, not after: the pool is fixed, so there is no transient state in
        // which an extra packet exists.
        if (count_ == kMaxQueuedPackets) dropOldest();
        const int tail = (head_ + count_) & kEntryMask;
        std::memcpy(pool_.data() + size_t(tail) * kMaxPacketBytes, data, size_t(len));
        entries_[tail] = Entry{uint16_t(len), uint16_t(samples)};
        count_++;
        samples_ += samples;
        while (samples_ > kHighWaterSamples) dropOldest();
    }
    // Open the playout gate immediately when a spurt terminates so whatever we have queued plays.
    // endTick will close the gate again once the queue drains. Clearing emptyAtPop_ is what lets
    // the latch survive an endTick already in flight: the engine may have seen this queue empty at
    // its last pop, and a terminator landing before its endTick says the spurt is complete — the
    // re-arm must not close the gate over it, or a short spurt below the prebuffer never plays.
    if (terminator) {
        gateOpen_ = true;
        emptyAtPop_ = false;
    }
}

int PacketQueue::pop(uint8_t* out, int outCap) {
    // Do not start playout until we have sufficient pre-roll buffer.
    if (!gateOpen_) {
        if (samples_ < kPrebufferSamples) {
            emptyAtPop_ = count_ == 0;
            return 0;
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
}

void PacketQueue::endTick(bool decoderProduced) {
    if (!decoderProduced && emptyAtPop_) gateOpen_ = false;
}

}  // namespace dumble::playout
