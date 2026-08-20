#pragma once
#include "core/PcmRing.h"

namespace dumble {

class PacketAssembler {
public:
    /** Aborts on zero. */
    explicit PacketAssembler(uint32_t packetSamples);

    /** Takes one whole packet from the ring, or returns false leaving it untouched. */
    bool takePacket(PcmRing& ring, int16_t* out);

    /** Flush up to budgetSamples from the ring, zero-padded to a whole packet. Always produces
     *  a packet, even from an empty ring — a terminator must carry a real payload. */
    void flushPacket(PcmRing& ring, int16_t* out, uint32_t budgetSamples);

    uint32_t packetSamples() const { return packetSamples_; }

private:
    const uint32_t packetSamples_;
};

}  // namespace dumble
