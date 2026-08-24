#include "core/CaptureEngine.h"
#include <algorithm>
#include <cstring>

namespace dumble {

std::unique_ptr<CaptureEngine> CaptureEngine::create(int sampleRate, int packetSamples,
                                                     int bitrate, const void* weights,
                                                     size_t weightBytes) {
    auto encoder = AudioEncoder::create(sampleRate, kChannels, bitrate);
    if (!encoder) return nullptr;
    // Null VoiceActivity only costs voice-activity mode. popPacket() always memsets
    // kTxPacketSamples into scratch_, sized to packetSamples, so any other packetSamples would
    // overrun it; this check makes popPacket() unreachable instead.
    auto voiceActivity = (weights && packetSamples == kTxPacketSamples)
                             ? VoiceActivity::create(weights, weightBytes)
                             : nullptr;
    return std::unique_ptr<CaptureEngine>(new CaptureEngine(
        sampleRate, packetSamples, std::move(encoder), std::move(voiceActivity)));
}

CaptureEngine::CaptureEngine(int sampleRate, int packetSamples,
                             std::unique_ptr<AudioEncoder> encoder,
                             std::unique_ptr<VoiceActivity> voiceActivity)
    : assembler_(packetSamples),
      encoder_(std::move(encoder)),
      voiceActivity_(std::move(voiceActivity)),
      scratch_(size_t(packetSamples)),
      // frame_number counts 10 ms frames; a packet carries kFramesPerPacket of them.
      frameNumberStep_(uint64_t(packetSamples) / uint64_t(sampleRate / 100)) {}

void CaptureEngine::setTransmitMode(TransmitMode mode) {
    if (mode == TransmitMode::VoiceActivity && !voiceActivity_) return;
    // Push-to-talk never reads history_, so stale voice-activity entries must not survive.
    resetDetectorPending_.store(true, std::memory_order_release);
    transmitMode_.store(mode, std::memory_order_relaxed);
    wakeup();
}

void CaptureEngine::onPcm(const int16_t* pcm, uint32_t n) {
    sampleClock_.fetch_add(n, std::memory_order_relaxed);
    // Leak guarantee: gate stops ring writes; the new-spurt branch in setGateOpen(true) resets
    // the ring so pre-close audio cannot survive into the next session's preroll.
    if (gateOpen_.load(std::memory_order_relaxed)) ring_.write(pcm, n);
}

void CaptureEngine::setGateOpen(bool open) {
    {
        std::lock_guard<std::mutex> lk(spurtMutex_);
        // gateOpen_ is only written here, so this guard cannot desync from the audio path.
        if (open == gateOpen_.load(std::memory_order_relaxed)) return;
        const bool pushToTalk =
            transmitMode_.load(std::memory_order_relaxed) == TransmitMode::PushToTalk;
        if (open) {
            if (terminatorDebt_ == TerminatorDebt::Mergeable && pushToTalk) {
                // Re-press before the terminator shipped: merge into one transmission.
                terminatorDebt_ = TerminatorDebt::None;
            } else {
                if (terminatorDebt_ != TerminatorDebt::None) terminatorDebt_ = TerminatorDebt::Firm;
                ring_.reset();
                clockOffset_ =
                    sampleClock_.load(std::memory_order_acquire) - ring_.writeIndex();
                // New spurt: receiver starts a fresh decoder, so reset the encoder.
                resetEncoderPending_.store(true, std::memory_order_release);
            }
            gateOpen_.store(true, std::memory_order_release);
        } else {
            // Under voice activity an idle mute has nothing on the wire — flushing a terminator
            // would leak un-judged audio.
            if (pushToTalk || transmittingSpurt_) {
                if (terminatorDebt_ == TerminatorDebt::None) {
                    debtClockOffset_ = clockOffset_;
                    terminatorDebt_ =
                        pushToTalk ? TerminatorDebt::Mergeable : TerminatorDebt::Firm;
                } else {
                    terminatorDebt_ = TerminatorDebt::Firm;
                }
            }
            gateOpen_.store(false, std::memory_order_release);
        }
        // Both transitions: opening must not carry over stale history_, and closing (idle mute)
        // must not let it survive into the next session.
        resetDetectorPending_.store(true, std::memory_order_release);
    }
    wakeup();
}

void CaptureEngine::requestShutdown() {
    shutdown_.store(true, std::memory_order_release);
    wakeup();
}

void CaptureEngine::setStreamDown(bool down) {
    streamDown_.store(down, std::memory_order_release);
    // Recovery invalidates the decimator, window ring, and LSTM state.
    if (!down) resetDetectorPending_.store(true, std::memory_order_release);
    wakeup();
}

void CaptureEngine::setStreamUnavailable() {
    streamUnavailable_.store(true, std::memory_order_release);
    wakeup();
}

void CaptureEngine::wakeup() {
    std::lock_guard<std::mutex> lk(wakeMutex_);
    wakeCondition_.notify_all();
}

namespace {

// Wall-clock frame number from a ring position and the spurt's ring-to-clock translation.
uint64_t offsetFrameNumber(uint64_t ringReadPos, uint64_t clockOffset) {
    return (ringReadPos + clockOffset) / uint64_t(kFrameSamples);
}

}  // namespace

CaptureEngine::HeldFrame& CaptureEngine::pushSlot() {
    const int slot = (historyOldest_ + historyCount_) % kHistorySlots;
    if (historyCount_ < kHistorySlots) {
        historyCount_++;
    } else {
        historyOldest_ = (historyOldest_ + 1) % kHistorySlots;
    }
    return history_[slot];
}

uint64_t CaptureEngine::popPacket() {
    const uint64_t frameNumber = history_[historyOldest_].candidateFrameNumber;
    int taken = 0;
    while (taken < kFramesPerPacket && readyFrames_ > 0) {
        std::memcpy(scratch_.data() + taken * kFrameSamples, history_[historyOldest_].pcm,
                    sizeof(history_[historyOldest_].pcm));
        historyOldest_ = (historyOldest_ + 1) % kHistorySlots;
        historyCount_--;
        readyFrames_--;
        taken++;
    }
    // A spurt ending on an odd frame ships a short packet rather than holding its last 10 ms for a
    // partner that the closed gate will never produce.
    std::memset(scratch_.data() + taken * kFrameSamples, 0,
                (size_t(kTxPacketSamples) - size_t(taken) * kFrameSamples) * sizeof(int16_t));
    return frameNumber;
}

void CaptureEngine::flushTerminator(uint64_t clockOffset, uint64_t* candidateFrameNumber) {
    const uint64_t readPos = ring_.readIndex();
    *candidateFrameNumber = offsetFrameNumber(readPos, clockOffset);
    assembler_.flushPacket(ring_, scratch_.data(),
                           std::min(ring_.available(), uint32_t(assembler_.packetSamples())));
    // A press between claim and reset can lose/leak a few samples — microseconds window.
    ring_.reset();
}

int CaptureEngine::pollPacket(uint8_t* out, int outCap, uint64_t* frameNumber, uint32_t* flags) {
    *flags = 0;
    if (shutdown_.load(std::memory_order_acquire)) return kPollShutdown;
    // Before streamDown_: both are set on final failure, and the caller needs to distinguish them.
    if (streamUnavailable_.load(std::memory_order_acquire)) return kPollUnavailable;

    if (streamDown_.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> lk(wakeMutex_);
        wakeCondition_.wait_for(lk, std::chrono::milliseconds(waitMillis_));
        return shutdown_.load(std::memory_order_acquire) ? kPollShutdown : kPollRetry;
    }

    bool haveFrame = false;
    uint64_t candidateFrameNumber = 0;
    bool haveTerminator = false;

    if (resetDetectorPending_.exchange(false, std::memory_order_acq_rel)) {
        if (voiceActivity_) voiceActivity_->reset();
        clearHistory();
        {
            std::lock_guard<std::mutex> lk(spurtMutex_);
            // Mid-spurt reset severs it: the detector that governed the spurt is gone, so any
            // pending debt hardens to Firm.
            if (transmittingSpurt_) {
                if (terminatorDebt_ == TerminatorDebt::None) debtClockOffset_ = clockOffset_;
                terminatorDebt_ = TerminatorDebt::Firm;
            }
            transmittingSpurt_ = false;
            // Gate shut with no debt owed: buffered audio has no spurt to claim it (idle
            // voice-activity mute). Conditioned on the debt because an unconditional drop would
            // eat audio a push-to-talk terminator is about to flush.
            if (!gateOpen_.load(std::memory_order_relaxed) &&
                terminatorDebt_ == TerminatorDebt::None) {
                ring_.reset();
            }
        }
    }

    uint64_t clockOffset = 0;
    bool debtWaiting = false;
    {
        std::lock_guard<std::mutex> lk(spurtMutex_);
        if (terminatorDebt_ != TerminatorDebt::None) {
            // Refuse while committed frames are queued: a close can land between the reset
            // exchange (:171) and this lock, leaving readyFrames_ > 0 with a debt. Claiming now
            // would ship the terminator; the next poll's reset would then drop the leftover via
            // clearHistory(). Deferring lets the pop branch (:218) drain it first.
            if (readyFrames_ > 0) {
                debtWaiting = true;
            } else {
                // Uses debtClockOffset_ (not the live offset, which a reopen may have already
                // overwritten for the next spurt).
                terminatorDebt_ = TerminatorDebt::None;
                haveTerminator = true;
                clockOffset = debtClockOffset_;
                transmittingSpurt_ = false;
            }
        }
    }

    if (haveTerminator) {
        flushTerminator(clockOffset, &candidateFrameNumber);
        haveFrame = true;
    } else if (readyFrames_ >= kFramesPerPacket || debtWaiting) {
        // A packet's worth is already committed — the preroll burst draining, or a leftover frame
        // that a live frame has just partnered. Emit before reading more, so burst order holds.
        // With a debt waiting, a lone committed frame ships short: its spurt is closed, so no
        // partner frame is coming.
        candidateFrameNumber = popPacket();
        haveFrame = true;
    } else if (gateOpen_.load(std::memory_order_acquire)) {
        // Read after gateOpen_ is true: setGateOpen() writes clockOffset_ before publishing
        // gateOpen_, so this order guarantees the offset belongs to the current spurt.
        {
            std::lock_guard<std::mutex> lk(spurtMutex_);
            clockOffset = clockOffset_;
        }
        ring_.skipToNewest(kHighWaterSamples);
        if (transmitMode_.load(std::memory_order_relaxed) == TransmitMode::PushToTalk) {
            candidateFrameNumber = offsetFrameNumber(ring_.readIndex(), clockOffset);
            haveFrame = assembler_.takePacket(ring_, scratch_.data());
        } else {
            // At most one packet's worth of new audio per poll: the same throughput as the
            // packet-native take this replaces, and the bound that stops a silent ring draining
            // in a single pass.
            bool closing = false;
            for (int taken = 0; taken < kFramesPerPacket && readyFrames_ < kFramesPerPacket;
                 ++taken) {
                // Checked before claiming a slot: a full queue claims by evicting the oldest
                // frame, which no undo can restore — never claim against a dry ring.
                if (ring_.available() < uint32_t(kFrameSamples)) break;
                const uint64_t slotFrameNumber =
                    offsetFrameNumber(ring_.readIndex(), clockOffset);
                // Claimed before the read so the frame lands straight in the queue, skipping a
                // copy. The availability check above guarantees the read fills it.
                HeldFrame& slot = pushSlot();
                ring_.readExact(slot.pcm, kFrameSamples);
                slot.candidateFrameNumber = slotFrameNumber;
                // Unconditional: the detector must see every frame to maintain its state.
                const auto decision = voiceActivity_->update(slot.pcm);
                std::lock_guard<std::mutex> lk(spurtMutex_);
                if (!decision.transmit) continue;   // silence: stays queued as preroll
                if (!transmittingSpurt_) {
                    // Opening edge: everything queued becomes the burst, this frame included.
                    transmittingSpurt_ = true;
                    resetEncoderPending_.store(true, std::memory_order_release);
                    readyFrames_ = historyCount_;
                } else {
                    readyFrames_++;
                }
                if (decision.closing) {
                    // Bounded to kFramesPerPacket: the unbounded opening-edge branch above needs
                    // transmittingSpurt_ false, which only follows a debt claim — and every debt
                    // path also sets resetDetectorPending_, resetting the gate before the next
                    // update() can see transmitting_ still true.
                    closing = true;
                    transmittingSpurt_ = false;
                    // This closing frame IS the terminator — discharge any mid-poll mute debt.
                    terminatorDebt_ = TerminatorDebt::None;
                    break;
                }
            }
            if (readyFrames_ >= kFramesPerPacket || (closing && readyFrames_ > 0)) {
                candidateFrameNumber = popPacket();
                haveTerminator = closing;
                haveFrame = true;
            }
        }
    }

    if (!haveFrame) {
        std::unique_lock<std::mutex> lk(wakeMutex_);
        wakeCondition_.wait_for(lk, std::chrono::milliseconds(waitMillis_));
        return shutdown_.load(std::memory_order_acquire) ? kPollShutdown : 0;
    }

    if (resetEncoderPending_.exchange(false, std::memory_order_acq_rel)) {
        encoder_->reset();
        encoderResets_.fetch_add(1, std::memory_order_relaxed);
    }
    const auto encodeStart = std::chrono::steady_clock::now();
    const int bytes = encoder_->encode(scratch_.data(), assembler_.packetSamples(), out, outCap);
    const auto encodeMicros = uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - encodeStart).count());
    encodeCount_.fetch_add(1, std::memory_order_relaxed);
    encodeMicrosSum_.fetch_add(encodeMicros, std::memory_order_relaxed);
    // Single writer; the CAS is for the read side, not for contention.
    uint64_t prevMax = encodeMicrosMax_.load(std::memory_order_relaxed);
    while (encodeMicros > prevMax &&
           !encodeMicrosMax_.compare_exchange_weak(prevMax, encodeMicros,
                                                   std::memory_order_relaxed)) {}
    if (bytes <= 0) {
        encodeErrors_.fetch_add(1, std::memory_order_relaxed);
        return 0;   // drop the frame; a libopus error is not a reason to end the stream
    }

    // Clamp to floor for strict monotonicity: terminators and instant re-presses can produce
    // candidates that collide with the previous value. Never reset on gate transitions.
    const uint64_t emittedFn = std::max(candidateFrameNumber, frameNumber_);
    *frameNumber = emittedFn;
    frameNumber_ = emittedFn + frameNumberStep_;
    if (haveTerminator) *flags |= kFlagTerminator;
    encodedPackets_.fetch_add(1, std::memory_order_relaxed);
    return bytes;
}

}  // namespace dumble
