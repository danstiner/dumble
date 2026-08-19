#include "core/VoiceActivity.h"
#include <cstring>

namespace dumble {

SpeechGate::Decision SpeechGate::update(float level) {
    const bool wasTransmitting = transmitting_;
    if (level > (transmitting_ ? kCloseLevel : kOpenLevel)) {
        hangoverFrames_ = 0;
        transmitting_ = true;
    } else if (transmitting_ && ++hangoverFrames_ >= kHangoverFrames) {
        transmitting_ = false;
    }
    // The closing frame is still transmitted: it is a real packet carrying the terminator, never
    // an empty payload, because murmur drops empty-payload packets before reading the flag.
    const bool closing = wasTransmitting && !transmitting_;
    return {transmitting_ || closing, closing};
}

std::unique_ptr<VoiceActivity> VoiceActivity::create(const void* weights, size_t bytes) {
    auto vad = SileroVad::create(weights, bytes);
    if (!vad) return nullptr;
    return std::unique_ptr<VoiceActivity>(new VoiceActivity(std::move(vad)));
}

VoiceActivity::VoiceActivity(std::unique_ptr<SileroVad> vad) : vad_(std::move(vad)) {}

VoiceActivity::Decision VoiceActivity::update(const int16_t* frame) {
    decimator_.decimate(frame, window_ + windowLength_);
    windowLength_ += Decimator::kOutputSamples;
    // 160 into 512 is not a whole number, so a window completes every 3 or 4 frames in a fixed
    // 4,3,3,3,3 cycle — 5 windows per 16 frames, exactly, with no drift. Between inferences the
    // last probability stands.
    while (windowLength_ >= SileroVad::kWindow) {
        held_ = vad_->process(window_);
        inferences_++;
        const int rest = windowLength_ - SileroVad::kWindow;
        std::memmove(window_, window_ + SileroVad::kWindow, size_t(rest) * sizeof(float));
        windowLength_ = rest;
    }
    return gate_.update(held_);
}

void VoiceActivity::reset() {
    vad_->reset();
    decimator_.reset();
    gate_.reset();
    windowLength_ = 0;
    held_ = 0.0f;
    inferences_ = 0;
}

}  // namespace dumble
