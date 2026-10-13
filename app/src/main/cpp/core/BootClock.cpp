#include "core/BootClock.h"
#include <ctime>

namespace dumble {

namespace {

// For the host tests on macOS, which has no CLOCK_BOOTTIME. Its CLOCK_MONOTONIC counts sleep,
// unlike Linux's; CLOCK_UPTIME_RAW is the one that stops.
#if defined(CLOCK_BOOTTIME)
constexpr clockid_t kClock = CLOCK_BOOTTIME;
#else
constexpr clockid_t kClock = CLOCK_MONOTONIC;
#endif

}  // namespace

BootClock::time_point BootClock::now() {
    timespec ts{};
    clock_gettime(kClock, &ts);
    return time_point(std::chrono::duration_cast<duration>(std::chrono::seconds(ts.tv_sec) +
                                                           std::chrono::nanoseconds(ts.tv_nsec)));
}

}  // namespace dumble
