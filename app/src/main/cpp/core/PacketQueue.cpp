#include "core/PacketQueue.h"
#include <bit>
#include <cstring>

namespace dumble::playout {
namespace {

// kPacketSlots is a power of two so the ring wrap is a mask.
static_assert(std::has_single_bit(unsigned(kPacketSlots)));
constexpr int kSlotMask = kPacketSlots - 1;

// Slot narrows both fields to uint16_t. Neither bound is close to the limit, but a silent
// truncation here would corrupt depth accounting rather than fail, so it is a compile error.
static_assert(kMaxPacketBytes <= UINT16_MAX);
static_assert(kMaxFrameSamples <= UINT16_MAX);

// Why the drop loop below needs no guard against emptying the queue: one packet cannot exceed the
// cap on its own, because the largest legal Opus frame is a fraction of it.
static_assert(kMaxFrameSamples <= kHighWaterSamples);

}  // namespace

PacketQueue::PacketQueue() : pool_(size_t(kPacketSlots) * kMaxPacketBytes) {}

void PacketQueue::dropOldest() {
    depthSamples_ -= slots_[head_].spanSamples;
    head_ = (head_ + 1) & kSlotMask;
    count_--;
}

bool PacketQueue::offer(const uint8_t* data, int len, int spanSamples, bool terminator) {
    if (len > kMaxPacketBytes) return false;
    // Accept packets that either contain decodable samples or are empty terminator packets.
    // The upper bound is what makes Slot's uint16_t span safe: PlayoutEngine only ever measures a
    // real Opus header, so a span past the largest legal frame means a caller we do not have.
    const bool accepted = len == 0 || (spanSamples > 0 && spanSamples <= kMaxFrameSamples);
    if (accepted && len > 0) {
        // Drop before insert, not after: the pool is fixed, so there is no transient state in
        // which an extra packet exists.
        if (count_ == kPacketSlots) dropOldest();
        const int tail = (head_ + count_) & kSlotMask;
        std::memcpy(pool_.data() + size_t(tail) * kMaxPacketBytes, data, size_t(len));
        slots_[tail] = Slot{uint16_t(len), uint16_t(spanSamples)};
        count_++;
        depthSamples_ += spanSamples;
        while (depthSamples_ > kHighWaterSamples) dropOldest();
    }
    // Latches rather than opening the gate for one pop: only the drained path in endTick re-arms
    // it, so a terminator cannot clear the gate mid-spurt and strand the tail.
    if (terminator) gateOpen_ = true;
    return accepted;
}

int PacketQueue::pop(uint8_t* out, int outCap) {
    // Do not start playout until we have sufficient pre-roll buffer.
    if (!gateOpen_) {
        if (depthSamples_ < kPrebufferSamples) return 0;
        gateOpen_ = true;
    }
    if (count_ == 0) return 0;
    const Slot& slot = slots_[head_];
    if (slot.len > outCap) return -1;
    std::memcpy(out, pool_.data() + size_t(head_) * kMaxPacketBytes, size_t(slot.len));
    depthSamples_ -= slot.spanSamples;
    head_ = (head_ + 1) & kSlotMask;
    count_--;
    return slot.len;
}

void PacketQueue::endTick(bool decoderProduced) {
    if (!decoderProduced && count_ == 0) gateOpen_ = false;
}

}  // namespace dumble::playout
