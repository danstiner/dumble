#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include "core/CaptureConstants.h"
#include "core/Decimator.h"
#include "core/SileroVad.h"

namespace dumble {

/**
 * Turns a stream of per-frame speech levels into transmit decisions: two-threshold hysteresis so a
 * level hovering at the boundary cannot chatter the gate, plus a hangover counted in frames.
 *
 * Separate from VoiceActivity because it is the whole gate policy and none of the signal path — no
 * model, no weights, no resampling — so its tests drive levels directly instead of hunting for
 * audio that produces them. Single-thread.
 */
class SpeechGate {
public:
    struct Decision {
        /** This frame's audio belongs to the spurt and should go out. */
        bool transmit;
        /** The spurt ends here; this frame carries the terminator. Never set without transmit. */
        bool closing;
    };

    Decision update(float level);
    void reset() { *this = SpeechGate(); }

private:
    bool transmitting_ = false;
    int hangoverFrames_ = 0;
};

/**
 * The transmit decision for one 10 ms frame. Decimates to 16 kHz, feeds Silero in 512-sample
 * windows, holds the probability between inferences, and hands it to SpeechGate.
 *
 * Per frame rather than per packet because Silero's window closes every 32 ms while frames are
 * 10 ms: the two never align, so a decision tied to inference would stutter. Holding the last
 * probability gives every frame a level, and the hold duration stays invariant to packet size.
 *
 * This is NOT the arming gate. The arming gate lives in CaptureEngine::onPcm and decides whether
 * the microphone reaches the ring at all; this decides whether what reached the ring is speech.
 * Single-thread — the pump thread.
 */
class VoiceActivity {
public:
    using Decision = SpeechGate::Decision;

    /** Null if the weight blob is not what SileroVad expects. */
    static std::unique_ptr<VoiceActivity> create(const void* weights, size_t bytes);

    /** kFrameSamples of 48 kHz PCM. */
    Decision update(const int16_t* frame);

    /** Capture discontinuity: an arming-gate transition, stream recovery, or a mode change. */
    void reset();

    float lastProbability() const { return held_; }
    /** Inferences since the last reset. The gate does not use it; the tests pin the cycle with it. */
    int inferences() const { return inferences_; }

private:
    explicit VoiceActivity(std::unique_ptr<SileroVad> vad);

    // The loop leaves fewer than kWindow samples buffered, so one frame's worth always fits.
    static constexpr int kWindowCapacity = SileroVad::kWindow + Decimator::kOutputSamples;

    std::unique_ptr<SileroVad> vad_;
    Decimator decimator_;
    SpeechGate gate_;
    float window_[kWindowCapacity] = {};
    int windowLength_ = 0;
    float held_ = 0.0f;
    int inferences_ = 0;
};

}  // namespace dumble
