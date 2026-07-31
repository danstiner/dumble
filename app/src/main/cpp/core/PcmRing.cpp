#include "core/PcmRing.h"
#include <cassert>
#include <cstdlib>
#include <cstring>

namespace dumble {

namespace {
uint32_t roundUpToPowerOfTwo(uint32_t n) {
    uint32_t cap = 1;
    while (cap < n) {
        cap <<= 1;
        if (cap == 0) std::abort();  // more than 2^31 samples requested; not a real ring
    }
    return cap;
}
}  // namespace

PcmRing::PcmRing(uint32_t minCapacitySamples)
    : buf_(roundUpToPowerOfTwo(minCapacitySamples)), mask_(uint32_t(buf_.size()) - 1) {
    // The rounding above keeps release builds robust; this catches the caller confusion —
    // asking for one size and silently getting another — in the builds that can still catch it.
    assert(minCapacitySamples != 0 &&
           (minCapacitySamples & (minCapacitySamples - 1)) == 0 &&
           "capacity should already be a power of two; rounding up is a release safety net");
}

bool PcmRing::write(const int16_t* src, uint32_t n) {
    const uint64_t w = writeIdx_.load(std::memory_order_relaxed);
    // Acquire: pair with the consumer's release store so space freed by a read is visible here.
    const uint64_t r = readIdx_.load(std::memory_order_acquire);
    if (uint64_t(buf_.size()) - (w - r) < n) {
        droppedWrites_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    const uint32_t start = uint32_t(w & mask_);
    const uint32_t first = std::min(n, uint32_t(buf_.size()) - start);
    std::memcpy(&buf_[start], src, first * sizeof(int16_t));
    if (n > first) std::memcpy(&buf_[0], src + first, (n - first) * sizeof(int16_t));
    // Release: samples above must be visible before the consumer can observe the new index.
    writeIdx_.store(w + n, std::memory_order_release);
    return true;
}

uint32_t PcmRing::readExact(int16_t* dst, uint32_t n) {
    // Delegates so the wrap-split copy exists once. Sound because the fill can only grow between
    // this check and the copy: writes add, and no one else consumes.
    if (writeIdx_.load(std::memory_order_acquire) - readIdx_.load(std::memory_order_relaxed) < n)
        return 0;
    return readUpTo(dst, n);
}

uint32_t PcmRing::readUpTo(int16_t* dst, uint32_t maxSamples) {
    const uint64_t r = readIdx_.load(std::memory_order_relaxed);
    const uint64_t w = writeIdx_.load(std::memory_order_acquire);
    const uint32_t n = uint32_t(std::min(uint64_t(maxSamples), w - r));
    if (n == 0) return 0;
    const uint32_t start = uint32_t(r & mask_);
    const uint32_t first = std::min(n, uint32_t(buf_.size()) - start);
    std::memcpy(dst, &buf_[start], first * sizeof(int16_t));
    if (n > first) std::memcpy(dst + first, &buf_[0], (n - first) * sizeof(int16_t));
    readIdx_.store(r + n, std::memory_order_release);
    return n;
}

uint32_t PcmRing::available() const {
    // Never exceeds the buffer, so the narrowing is exact.
    return uint32_t(writeIdx_.load(std::memory_order_acquire)
                    - readIdx_.load(std::memory_order_relaxed));
}

uint64_t PcmRing::writeIndex() const {
    return writeIdx_.load(std::memory_order_acquire);
}

uint64_t PcmRing::readIndex() const {
    return readIdx_.load(std::memory_order_relaxed);
}

void PcmRing::skipToNewest(uint32_t keep) {
    const uint64_t r = readIdx_.load(std::memory_order_relaxed);
    const uint64_t w = writeIdx_.load(std::memory_order_acquire);
    const uint64_t have = w - r;
    if (have <= keep) return;
    const uint64_t drop = have - keep;
    skippedSamples_.fetch_add(drop, std::memory_order_relaxed);
    readIdx_.store(r + drop, std::memory_order_release);
}

void PcmRing::reset() {
    // Safe against a live producer because writeIdx_ only ever grows, so readIdx_ can only move
    // forward and the `write - read` invariant still holds afterwards. The producer's own space
    // check may briefly observe the pre-reset readIdx_ and refuse one write; that is bounded to
    // roughly one callback period and self-clears. See the spec's gate-open note.
    readIdx_.store(writeIdx_.load(std::memory_order_acquire), std::memory_order_release);
}

}  // namespace dumble
