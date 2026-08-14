#pragma once
#include <bit>
#include <cstdint>
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * Occupancy for up to 64 fixed slots, one bit each. Claim and release are one instruction plus a
 * mask, and iteration visits only occupied slots — so the playout fill loop costs the number of
 * live speakers, not kCapacity, which matters at the ~100 Hz it runs at.
 */
class SlotSet {
public:
    static constexpr int kCapacity = 64;

    /** Lowest free slot, or -1 when full. */
    int claim() {
        if (bits_ == ~uint64_t{0}) return -1;
        const int i = std::countr_zero(~bits_);
        bits_ |= uint64_t{1} << i;
        return i;
    }

    void release(int i) { bits_ &= ~(uint64_t{1} << i); }
    bool occupied(int i) const { return (bits_ >> i) & 1; }
    int size() const { return std::popcount(bits_); }
    bool empty() const { return bits_ == 0; }

    /** Ascending over occupied slots only. No early exit: the one searching caller runs at
     *  live-speaker count, and a second iteration idiom is not worth the compares it would save. */
    template <class F>
    void forEach(F&& f) const {
        for (uint64_t b = bits_; b; b &= b - 1) f(std::countr_zero(b));
    }

private:
    uint64_t bits_ = 0;
};

static_assert(kMaxSpeakers <= SlotSet::kCapacity,
              "occupancy is one uint64_t, so kMaxSpeakers cannot exceed its width");

}  // namespace dumble::playout
