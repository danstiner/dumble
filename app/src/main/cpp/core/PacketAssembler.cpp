#include "core/PacketAssembler.h"
#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace dumble {

PacketAssembler::PacketAssembler(uint32_t packetSamples) : packetSamples_(packetSamples) {
    if (packetSamples == 0) std::abort();
}

bool PacketAssembler::takePacket(PcmRing& ring, int16_t* out) {
    return ring.readExact(out, packetSamples_) == packetSamples_;
}

void PacketAssembler::flushPacket(PcmRing& ring, int16_t* out, uint32_t budgetSamples) {
    const uint32_t got = ring.readUpTo(out, std::min(budgetSamples, packetSamples_));
    std::memset(out + got, 0, (packetSamples_ - got) * sizeof(int16_t));
}

}  // namespace dumble
