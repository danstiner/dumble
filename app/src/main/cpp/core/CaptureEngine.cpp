#include "core/CaptureEngine.h"
#include <algorithm>
#include <cstring>

namespace dumble {

std::unique_ptr<CaptureEngine> CaptureEngine::create(int sampleRate, int frameSamples,
                                                     int bitrate) {
    auto encoder = AudioEncoder::create(sampleRate, kChannels, bitrate);
    if (!encoder) return nullptr;
    return std::unique_ptr<CaptureEngine>(
        new CaptureEngine(sampleRate, frameSamples, std::move(encoder)));
}

CaptureEngine::CaptureEngine(int sampleRate, int frameSamples,
                             std::unique_ptr<AudioEncoder> encoder)
    : assembler_(frameSamples),
      encoder_(std::move(encoder)),
      scratch_(size_t(frameSamples)),
      frameNumberStep_(uint64_t(frameSamples) / uint64_t(sampleRate / 100)) {}

void CaptureEngine::onPcm(const int16_t* pcm, uint32_t n) {
    // The frame_number clock: counts every sample the device delivers, gate open or not, so a
    // resumed spurt's frame number reflects the closed-gate pause. Must never be gated.
    sampleClock_.fetch_add(n, std::memory_order_relaxed);
    // The push-to-talk gate lives here, close to the audio source — similar to desktop Mumble.
    // When the gate is closed, audio never enters the ring, so nothing downstream can leak it.
    // Relaxed is enough: one in-flight burst could straddle a transition and slip through, but
    // bursts are only a few milliseconds (controlled by the device platform layer).
    if (gateOpen_.load(std::memory_order_relaxed)) ring_.write(pcm, n);
}

void CaptureEngine::setGateOpen(bool open) {
    {
        std::lock_guard<std::mutex> lk(spurtMutex_);
        // A repeat of the commanded state is not a transition and must do nothing.
        if (open == (gateState_ == GateState::Open)) return;
        if (open) {
            if (gateState_ == GateState::TerminatorOwed) {
                // Reopened before the pump sent the close's terminator. Cancel it and keep the
                // original clock offset: the push-to-talk presses merge into one continuous
                // transmission, similar to desktop Mumble's frame-granular behavior for a release
                // and re-press inside one frame. The terminator is simply a signal to other clients
                // that we are finished speaking and they can drop decoder state, skipping it should
                // not affect the produced audio, we are not touching samples in the ring buffer.
            } else {
                clockOffset_ =
                    sampleClock_.load(std::memory_order_acquire) - ring_.writeIndex();
            }
            gateState_ = GateState::Open;
            gateOpen_.store(true, std::memory_order_release);
        } else {
            gateState_ = GateState::TerminatorOwed;
            gateOpen_.store(false, std::memory_order_release);
        }
    }
    wakeup();
}

void CaptureEngine::requestShutdown() {
    shutdown_.store(true, std::memory_order_release);
    wakeup();
}

void CaptureEngine::setStreamDown(bool down) {
    streamDown_.store(down, std::memory_order_release);
    wakeup();
}

void CaptureEngine::wakeup() {
    std::lock_guard<std::mutex> lk(wakeMutex_);
    wakeCondition_.notify_all();
}

namespace {

// Wall-clock frame number for a packet whose content starts at ringReadPos (ring
// space), given the spurt's ring-to-clock translation. See clockOffset_ for why ring position
// alone cannot carry wall time.
uint64_t offsetFrameNumber(uint64_t ringReadPos, uint64_t clockOffset) {
    return (ringReadPos + clockOffset) / uint64_t(kFrameNumberUnitSamples);
}

}  // namespace

int CaptureEngine::pollFrame(uint8_t* out, int outCap, uint64_t* frameNumber, uint32_t* flags) {
    *flags = 0;
    if (shutdown_.load(std::memory_order_acquire)) return kPollShutdown;

    if (streamDown_.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> lk(wakeMutex_);
        wakeCondition_.wait_for(lk, std::chrono::milliseconds(waitMillis_));
        return shutdown_.load(std::memory_order_acquire) ? kPollShutdown : kPollRetry;
    }

    bool haveFrame = false;
    uint64_t candidateFrameNumber = 0;

    bool haveTerminator = false;
    uint64_t clockOffset = 0;
    {
        std::lock_guard<std::mutex> lk(spurtMutex_);
        if (gateState_ == GateState::TerminatorOwed) {
            gateState_ = GateState::Closed;
            haveTerminator = true;
            // The closed spurt's offset. Still valid: a close never touches it, and any reopen
            // since would have merged into this spurt and cancelled the debt instead.
            clockOffset = clockOffset_;
        }
    }

    if (haveTerminator) {
        // Everything buffered is the closed spurt's own audio — the gate in onPcm() saw to that —
        // so there is no boundary to respect. Flush up to one frame, zero-padded the way the
        // Mumble client pads its terminator, so a spurt shorter than one packet — or one already
        // fully drained by ordinary polling before it closed — still produces a real payload.
        // Any backlog beyond that one frame is dropped rather than sent late.
        const uint64_t readPos = ring_.readIndex();   // where this packet's content starts
        candidateFrameNumber = offsetFrameNumber(readPos, clockOffset);
        assembler_.flushFrame(ring_, scratch_.data(),
                              std::min(ring_.available(), uint32_t(assembler_.frameSamples())));
        // A press landing between the claim above and this reset can lose the first samples of
        // the new spurt, or let a few ride out inside the terminator — a microseconds window,
        // bounded to that, and index-safe: reset() only ever moves the read index forward.
        ring_.reset();
        haveFrame = true;
    } else if (gateOpen_.load(std::memory_order_acquire)) {
        // Read the offset only after gateOpen_ says true, and never alongside the terminator
        // read above. setGateOpen() writes it before publishing gateOpen_, so this order is
        // what guarantees it belongs to the spurt now open; hoisting it into the lock above
        // would let a press land in between and hand this packet the previous spurt's offset,
        // silently losing the closed-gate gap from its frame number.
        {
            std::lock_guard<std::mutex> lk(spurtMutex_);
            clockOffset = clockOffset_;
        }
        // Bound staleness before taking a frame, so a stalled pump does not transmit a growing
        // backlog of increasingly old audio once it recovers.
        ring_.skipToNewest(kHighWaterSamples);
        const uint64_t readPos = ring_.readIndex();
        candidateFrameNumber = offsetFrameNumber(readPos, clockOffset);
        haveFrame = assembler_.takeFrame(ring_, scratch_.data());
    }

    if (!haveFrame) {
        std::unique_lock<std::mutex> lk(wakeMutex_);
        wakeCondition_.wait_for(lk, std::chrono::milliseconds(waitMillis_));
        return shutdown_.load(std::memory_order_acquire) ? kPollShutdown : 0;
    }

    const int bytes = encoder_->encode(scratch_.data(), assembler_.frameSamples(), out, outCap);
    if (bytes <= 0) {
        encodeErrors_.fetch_add(1, std::memory_order_relaxed);
        return 0;   // drop the frame; a libopus error is not a reason to end the stream
    }

    // frameNumber_ is a floor, not a running count: the emitted value is clamped up to it
    // whenever the wall-clock candidate would be lower or equal, which is what guarantees strict,
    // collision-free monotonicity (a terminator with little or no real audio, or an instant
    // re-press, would otherwise compute a candidate that collides with or precedes whatever came
    // right before it). The candidate wins whenever real time has genuinely moved further ahead
    // — a closed-gate pause — which is what makes the gap wall-clock-accurate instead of frozen.
    // Never reset on a gate transition (see the tests).
    const uint64_t emittedFn = std::max(candidateFrameNumber, frameNumber_);
    *frameNumber = emittedFn;
    frameNumber_ = emittedFn + frameNumberStep_;
    if (haveTerminator) *flags |= kFlagTerminator;
    encodedPackets_.fetch_add(1, std::memory_order_relaxed);
    return bytes;
}

}  // namespace dumble
