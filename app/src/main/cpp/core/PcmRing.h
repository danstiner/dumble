#pragma once
#include <atomic>
#include <cstdint>
#include <vector>

namespace dumble {

/**
 * Single-producer single-consumer ring buffer specialized for int16 PCM samples. The producer is
 * a SCHED_FIFO audio callback, so write() allocates nothing, takes no lock, and never blocks.
 *
 * Indices are ever-increasing counters masked to the buffer, not wrapped pointers, so
 * `write - read` is the fill level without an ambiguous full/empty state and reset() cannot move
 * the read pointer backwards.
 *
 * Only the consumer may move readIdx_ — including in skipToNewest() and reset(). Letting the
 * producer trim the backlog would break the SPSC invariant that makes the lock-free pairing sound.
 */
class PcmRing {
public:
    /** Capacity rounds up to the next power of two — the wrap must be a mask. Debug builds
     *  additionally assert the request already was one, so a caller asking for one size and
     *  silently getting another is caught; release builds round up and carry on, the same
     *  policy ShortArrayFifo applies on the playback side. */
    explicit PcmRing(uint32_t minCapacitySamples);

    /** Producer. All-or-nothing: a write that does not fit is dropped whole and counted. */
    bool write(const int16_t* src, uint32_t n);

    /** Consumer. Copies exactly n samples, or none. Returns samples copied. */
    uint32_t readExact(int16_t* dst, uint32_t n);

    /** Consumer. Copies up to maxSamples — whatever is buffered, possibly nothing. For draining
     *  a bounded tail, where readExact()'s all-or-nothing contract would refuse a short remainder. */
    uint32_t readUpTo(int16_t* dst, uint32_t maxSamples);

    /** Samples currently buffered. Any thread — two atomic reads, moving nothing — though the
     *  answer can be stale by one concurrent write or read by the time the caller acts on it,
     *  which every caller tolerates: a frame missed this poll is taken on the next. */
    uint32_t available() const;

    /** Consumer. Discards all but the newest `keep` samples. */
    void skipToNewest(uint32_t keep);

    /** Consumer. Discards everything currently buffered. */
    void reset();

    uint64_t droppedWrites() const { return droppedWrites_.load(std::memory_order_relaxed); }
    uint64_t skippedSamples() const { return skippedSamples_.load(std::memory_order_relaxed); }

private:
    std::vector<int16_t> buf_;
    const uint32_t mask_;
    std::atomic<uint32_t> writeIdx_{0};
    std::atomic<uint32_t> readIdx_{0};
    std::atomic<uint64_t> droppedWrites_{0};
    std::atomic<uint64_t> skippedSamples_{0};
};

}  // namespace dumble
