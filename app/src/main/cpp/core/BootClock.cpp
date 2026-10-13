#include "core/BootClock.h"
#include <ctime>

namespace dumble {

namespace {

// Darwin has no CLOCK_BOOTTIME, and the host test build is the only thing that compiles this file
// off Android. Its CLOCK_MONOTONIC is the right stand-in rather than a compromise: unlike Linux's,
// it keeps advancing while the system is asleep, which is the property this needs. CLOCK_UPTIME_RAW
// is the one that stops there, and is what a port reaching for the closest-looking name would pick.
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
