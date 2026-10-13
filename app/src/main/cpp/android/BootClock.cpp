#include "core/BootClock.h"
#include <ctime>

namespace dumble {

BootClock::time_point BootClock::now() {
    timespec ts{};
    clock_gettime(CLOCK_BOOTTIME, &ts);
    return time_point(std::chrono::duration_cast<duration>(std::chrono::seconds(ts.tv_sec) +
                                                           std::chrono::nanoseconds(ts.tv_nsec)));
}

}  // namespace dumble
