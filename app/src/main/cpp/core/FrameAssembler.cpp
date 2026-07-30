#include "core/FrameAssembler.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace dumble {

FrameAssembler::FrameAssembler(uint32_t frameSamples) : frameSamples_(frameSamples) {
    // Unconditional rather than assert, so the guard also holds in release builds.
    if (frameSamples == 0) std::abort();
}

bool FrameAssembler::takeFrame(PcmRing& ring, int16_t* out) {
    return ring.readExact(out, frameSamples_) == frameSamples_;
}

void FrameAssembler::flushFrame(PcmRing& ring, int16_t* out, uint32_t budgetSamples) {
    const uint32_t got = ring.readUpTo(out, std::min(budgetSamples, frameSamples_));
    std::memset(out + got, 0, (frameSamples_ - got) * sizeof(int16_t));
}

}  // namespace dumble
