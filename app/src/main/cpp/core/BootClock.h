#pragma once
#include <chrono>

namespace dumble {

/**
 * A std::chrono clock that keeps counting through deep sleep, which steady_clock does not on
 * Android. The jitter estimator's windows are real time: a delay baseline older than two seconds
 * is stale and rebuilt. On a clock that stops in suspend, a baseline from before the suspend still
 * looks fresh, and the first packets after it are measured against it. BootTimeSource is the
 * Kotlin side's.
 *
 * Milliseconds because a peer's frame number times kFrameNumberUnit must fit the tick: in
 * nanoseconds it overflows int64 past ~9.2e11 frames, under kMaxFrameNumber. Nothing finer
 * survives the histogram's 20 ms buckets anyway.
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
