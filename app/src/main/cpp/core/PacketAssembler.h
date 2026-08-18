#pragma once
#include "core/PcmRing.h"

namespace dumble {

/**
 * Turns whatever the device hands us into fixed frames. Device bursts (a few milliseconds) are
 * written into the PcmRing buffer, then extracted here into a packet sized frame for encoding.
 */
class PacketAssembler {
public:
    /** Aborts on zero — a zero-sample frame can only produce nonsense downstream. Unsigned
     *  because every producer of the value is: the frame size never crosses the JNI boundary,
     *  so no signed jint ever feeds this. */
    explicit PacketAssembler(uint32_t frameSamples);

    /** Takes one whole frame if the ring holds enough. Returns false otherwise, leaving the ring
     *  untouched so the partial frame stays buffered for the next call. */
    bool takeFrame(PcmRing& ring, int16_t* out);

    /** End-of-spurt flush: takes up to [budgetSamples] of the ring's oldest samples — the
     *  caller's claim on how much of the queue belongs to this spurt — zero-padded to a whole
     *  frame. Always produces a frame, even from an empty ring: a spurt shorter than one frame,
     *  or one already drained by ordinary polling before it closed, must still yield a real
     *  terminator payload. Samples beyond the budget stay queued for the next span. */
    void flushFrame(PcmRing& ring, int16_t* out, uint32_t budgetSamples);

    uint32_t frameSamples() const { return frameSamples_; }

private:
    // Unsigned because every consumer is: ring reads, the flush memset, and the callers' span
    // math. The one place int is the native type — libopus's frame size — casts at that boundary.
    const uint32_t frameSamples_;
};

}  // namespace dumble
