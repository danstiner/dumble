#pragma once
#include <bit>
#include <cstdint>

namespace dumble::playout {

/**
 * Occupancy for up to 64 indices, one bit each. Every operation is a mask or a single instruction,
 * and the whole map is one register — so the caller iterates its own array and asks this only
 * whether each index is taken, rather than the other way round.
 *
 * Holds no policy: which index to set is the caller's, since only the caller knows what else it
 * wants from the same pass.
 */
class Bitmap {
public:
    static constexpr int kCapacity = 64;

    bool test(int i) const { return (bits_ >> i) & 1; }
    void set(int i) { bits_ |= uint64_t{1} << i; }
    void clear(int i) { bits_ &= ~(uint64_t{1} << i); }
    int count() const { return std::popcount(bits_); }

private:
    uint64_t bits_ = 0;
};

}  // namespace dumble::playout
