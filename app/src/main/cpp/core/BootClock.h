#pragma once
#include <chrono>

namespace dumble {

/**
 * CLOCK_BOOTTIME as a std::chrono clock, which steady_clock is not: that is CLOCK_MONOTONIC on
 * bionic, and stops in deep sleep. frame_number is a sender-side wall-clock counter that keeps
 * advancing through our suspend; measured against a clock that stopped, the first packet after
 * resume would look impossibly early, become the window minimum, and leave every packet after it
 * reading as hugely late. BootTimeSource is the Kotlin side's, for the same reason.
 *
 * Milliseconds, the jitter estimator's unit: nanoseconds times a sample rate overflows int64 at
 * plausible boot times, and its histogram's resolution is 20 ms, so nothing finer would survive
 * bucketing.
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
