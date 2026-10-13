#include "core/BootClock.h"
#include <ctime>

namespace dumble {

namespace {

// Darwin has no CLOCK_BOOTTIME, and only the host test build compiles this file off Android.
// Darwin's CLOCK_MONOTONIC, unlike Linux's, keeps advancing while the system is asleep, so it is
// an exact stand-in. CLOCK_UPTIME_RAW is the closest-looking name and the one that stops there.
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
