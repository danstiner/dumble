#pragma once
#include <chrono>

namespace dumble {

/**
 * CLOCK_BOOTTIME as a std::chrono clock, which steady_clock is not: that is CLOCK_MONOTONIC on
 * bionic, and stops in deep sleep. The jitter estimator's arrival intervals, its stale-baseline
 * check above all, must count the time we spend suspended: a clock that stops there under-reads a
 * packet delivered late across the suspend. BootTimeSource is the Kotlin side's, for the same
 * reason.
 *
 * Milliseconds, so that a peer's frame number times kFrameNumberUnit fits the tick: in nanoseconds
 * it overflows int64 past ~9.2e11, well under kMaxFrameNumber. Nothing finer would survive the
 * histogram's 20 ms buckets anyway.
 */
struct BootClock {
    using duration = std::chrono::milliseconds;
    using rep = duration::rep;
    using period = duration::period;
    using time_point = std::chrono::time_point<BootClock>;
    static constexpr bool is_steady = true;

    static time_point now();
};

}  // namespace dumble
