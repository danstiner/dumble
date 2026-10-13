#pragma once
#include <chrono>
#include <cstdint>
#include <optional>
#include "core/BootClock.h"
#include "core/PlayoutConstants.h"

namespace dumble::playout {

/**
 * One sender's playout target, estimated from measured arrival jitter.
 *
 * Holds no audio and knows nothing about queues, decoders or slots — it sees arrival times and
 * sender frame numbers, and answers with a target depth. That is what lets PlayoutEngine keep one
 * of these per session in a table that outlives slot retirement, which is the whole point: a
 * speaker who stops for thirty seconds must not come back with a cold estimate.
 *
 * Not internally synchronized. PlayoutEngine writes every field under its mutex, from offer(), and
 * reads under the same mutex once per fill.
 */
class JitterEstimator {
public:
    /**
     * Records one arrival and returns its delay relative to the fastest packet in the last 1-2 s,
     * or nothing when the observation was skipped, which is a rebase and not an error.
     * `frameNumber` is the packet's first frame, `samples` its duration and never negative.
     *
     * A packet that is not the exact successor of the last one latches the discontinuity flag and
     * rebases the baseline. Both halves matter and they are separate: the flag says "the sender's
     * stream broke, so a burst at the next gate-open is a spurt opening and must not be trimmed",
     * while the rebase stops a stale minimum from making every packet of the new spurt read as
     * late. A stall is deliberately not either of these — its frame numbers stay contiguous, the
     * baseline is held, and that is exactly what makes the burst measurable as the lateness it is.
     */
    std::optional<std::chrono::milliseconds> observe(uint64_t frameNumber, int samples,
                                                     BootClock::time_point arrival);

    /** Feeds a relative delay measured against another sender's baseline. The engine-wide
     *  estimator's only input: frame_number origins are per-sender, so a raw (arrival - sendTime)
     *  carries an arbitrary offset and pooling those would let whichever sender has the largest
     *  one own the shared minimum. Relative delay has the offset already subtracted out, which
     *  makes it the only quantity meaningful across senders. */
    void observeRelativeDelay(std::chrono::milliseconds delay, BootClock::time_point arrival);

    /** The playout target, in samples. kColdStart until the first histogram update. */
    int targetSamples() const;

    /** Whether any histogram update has landed. The engine reads it to decide whether the
     *  engine-wide estimator is worth seeding a newcomer from. */
    bool hasData() const { return updates_ > 0; }

    /** Copies another estimator's memory, and only its memory. The baseline is per-sender and
     *  stays untouched, so the first packet after a seed is still a rebase. */
    void seedFrom(const JitterEstimator& other);

    /** True when the sender's frame numbering has broken since the last clearDiscontinuity(). */
    bool discontinuous() const { return sawDiscontinuity_; }

    /** Clears the latch, once a spurt is actually playing. */
    void clearDiscontinuity() { sawDiscontinuity_ = false; }

    /** Returns this estimator to its just-constructed state, for a table entry about to serve a
     *  different sender. */
    void reset();

private:
    // Baseline: the minimum of (arrival - sendTime) over the current bucket and the previous one.
    bool haveCurrent_ = false;
    bool havePrevious_ = false;
    BootClock::time_point bucketStart_;
    std::chrono::milliseconds currentMin_{};
    std::chrono::milliseconds previousMin_{};

    // The sender's stream. nextFrameNumber_ is what the next packet must carry to be contiguous.
    bool haveFrame_ = false;
    bool sawDiscontinuity_ = false;
    uint64_t nextFrameNumber_ = 0;

    /** Peak-holds `delay` and closes the window when kPeakHold has passed. */
    void hold(std::chrono::milliseconds delay, BootClock::time_point arrival);

    /** One histogram update, with the forgetting factor applied first. */
    void update(std::chrono::milliseconds delay);

    // Peak-hold, feeding one histogram update per kPeakHold of arrivals.
    bool havePeak_ = false;
    BootClock::time_point peakStart_;
    std::chrono::milliseconds peak_{};

    // The memory. Scaled so the buckets always sum to 1<<30, which is what lets the quantile
    // search be an integer prefix sum.
    int32_t buckets_[kTargetBuckets] = {};
    int updates_ = 0;
};

}  // namespace dumble::playout
