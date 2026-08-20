#include "core/CaptureEngine.h"
#include <algorithm>
#include <cstring>

namespace dumble {

std::unique_ptr<CaptureEngine> CaptureEngine::create(int sampleRate, int packetSamples,
                                                     int bitrate, const void* weights,
                                                     size_t weightBytes) {
    auto encoder = AudioEncoder::create(sampleRate, kChannels, bitrate);
    if (!encoder) return nullptr;
    // Null VoiceActivity only costs voice-activity mode. The packetSamples check prevents
    // judgePacket's fixed kFramesPerPacket walk from overrunning scratch_.
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
    // Leak guarantee: gate stops ring writes, reset policy clears held packets on every arming
    // transition. Relaxed is fine — a straddling burst is only a few milliseconds.
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

void CaptureEngine::holdPacket(const int16_t* pcm, uint64_t candidateFrameNumber) {
    const int slot = (historyOldest_ + historyCount_) % kHistorySlots;
    std::memcpy(history_[slot].pcm, pcm, sizeof(history_[slot].pcm));
    history_[slot].candidateFrameNumber = candidateFrameNumber;
    if (historyCount_ < kHistorySlots) {
        historyCount_++;
    } else {
        historyOldest_ = (historyOldest_ + 1) % kHistorySlots;
    }
}

CaptureEngine::PacketDecision CaptureEngine::judgePacket(const int16_t* packet) {
    PacketDecision decision;
    for (int f = 0; f < kFramesPerPacket; ++f) {
        const auto frame = voiceActivity_->update(packet + f * kFrameSamples);
        decision.transmit |= frame.transmit;
        decision.closing |= frame.closing;
    }
    return decision;
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

    if (burstRemaining_ > 0) {
        // One held packet per poll, oldest first. Before the terminator claim so a close
        // mid-burst cannot ship its terminator ahead of the burst audio.
        const HeldPacket& held = history_[historyOldest_];
        std::memcpy(scratch_.data(), held.pcm, sizeof(held.pcm));
        candidateFrameNumber = held.candidateFrameNumber;
        historyOldest_ = (historyOldest_ + 1) % kHistorySlots;
        historyCount_--;
        burstRemaining_--;
        haveFrame = true;
    } else {
        uint64_t clockOffset = 0;
        {
            std::lock_guard<std::mutex> lk(spurtMutex_);
            if (terminatorDebt_ != TerminatorDebt::None) {
                // Uses debtClockOffset_ (not the live offset, which a reopen may have already
                // overwritten for the next spurt).
                terminatorDebt_ = TerminatorDebt::None;
                haveTerminator = true;
                clockOffset = debtClockOffset_;
                transmittingSpurt_ = false;
            }
        }

        if (haveTerminator) {
            flushTerminator(clockOffset, &candidateFrameNumber);
            haveFrame = true;
        } else if (gateOpen_.load(std::memory_order_acquire)) {
            // Read after gateOpen_ is true: setGateOpen() writes clockOffset_ before publishing
            // gateOpen_, so this order guarantees the offset belongs to the current spurt.
            {
                std::lock_guard<std::mutex> lk(spurtMutex_);
                clockOffset = clockOffset_;
            }
            ring_.skipToNewest(kHighWaterSamples);
            const uint64_t readPos = ring_.readIndex();
            candidateFrameNumber = offsetFrameNumber(readPos, clockOffset);
            if (transmitMode_.load(std::memory_order_relaxed) == TransmitMode::PushToTalk) {
                haveFrame = assembler_.takePacket(ring_, scratch_.data());
            } else if (assembler_.takePacket(ring_, scratch_.data())) {
                // Unconditional: the detector must see every packet to maintain its state.
                const PacketDecision decision = judgePacket(scratch_.data());
                std::lock_guard<std::mutex> lk(spurtMutex_);
                if (!decision.transmit) {
                    holdPacket(scratch_.data(), candidateFrameNumber);
                } else if (!transmittingSpurt_) {
                    // Opening edge: queue this packet behind the preroll burst.
                    transmittingSpurt_ = true;
                    resetEncoderPending_.store(true, std::memory_order_release);
                    holdPacket(scratch_.data(), candidateFrameNumber);
                    burstRemaining_ = historyCount_;
                } else {
                    haveFrame = true;
                    if (decision.closing) {
                        haveTerminator = true;
                        transmittingSpurt_ = false;
                        // This closing packet IS the terminator — discharge any mid-poll mute debt.
                        terminatorDebt_ = TerminatorDebt::None;
                    }
                }
            }
        }
    }

    if (!haveFrame) {
        // Skip the wait when a burst is queued — parking would add one poll to onset latency.
        if (burstRemaining_ == 0) {
            std::unique_lock<std::mutex> lk(wakeMutex_);
            wakeCondition_.wait_for(lk, std::chrono::milliseconds(waitMillis_));
        }
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
