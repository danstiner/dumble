#include "core/SpeakerQueue.h"
#include <bit>
#include <cstring>

namespace dumble::playout {
namespace {

// kPacketSlots is a power of two so the ring wrap is a mask.
static_assert(std::has_single_bit(unsigned(kPacketSlots)));
constexpr int kSlotMask = kPacketSlots - 1;

}  // namespace

std::unique_ptr<SpeakerQueue> SpeakerQueue::create(int sampleRate, int maxQuantumSamples) {
    // Mono, and not a parameter: decodeScratch_ is sized in total samples while opus_decode's
    // frame_size counts per channel, and decodeInto treats the return as a total. Mumble carries
    // mono voice and spatializes from positional_data at the far end, so there is no second
    // channel to plumb — a stereo output would pan mono speakers in the mixer, not decode two.
    auto decoder = AudioDecoder::create(sampleRate, 1);
    if (!decoder) return nullptr;
    return std::unique_ptr<SpeakerQueue>(new SpeakerQueue(std::move(decoder), maxQuantumSamples));
}

SpeakerQueue::SpeakerQueue(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples)
    : decoder_(std::move(decoder)),
      pool_(size_t(kPacketSlots) * kMaxPacketBytes),
      // One tick decodes only while below a quantum and one decode adds at most one frame, so this
      // is the fifo's exact occupancy bound. bit_ceil because PcmRing asserts a power-of-two
      // request rather than silently rounding a caller's number.
      fifo_(std::bit_ceil(uint32_t(maxQuantumSamples + kMaxFrameSamples))),
      decodeScratch_(kMaxFrameSamples) {}

void SpeakerQueue::dropOldest() {
    queuedSamples_ -= slots_[head_].spanSamples;
    head_ = (head_ + 1) & kSlotMask;
    count_--;
}

bool SpeakerQueue::offer(const uint8_t* data, int len, int spanSamples, bool terminator) {
    // Defence in depth ahead of the memcpy, not accounting: PlayoutEngine::offer refuses an
    // oversized packet before the mutex is taken and answers kOfferPacketTooLarge, so counting it
    // here would only double-count a protocol violation the caller already sees.
    if (len > kMaxPacketBytes) return false;
    // AudioDecoder::packetSamples could not price the header, so there is no span to charge the
    // queue for and the payload cannot be scheduled. A payload-free terminator is not a refusal:
    // it prices to nothing by definition.
    const bool priced = len == 0 || spanSamples > 0;
    if (priced && len > 0) {
        // Drop before insert, not after: the pool is fixed, so there is no transient state in
        // which an extra packet exists.
        if (count_ == kPacketSlots) dropOldest();
        const int tail = (head_ + count_) & kSlotMask;
        std::memcpy(pool_.data() + size_t(tail) * kMaxPacketBytes, data, size_t(len));
        slots_[tail] = Slot{len, spanSamples};
        count_++;
        queuedSamples_ += spanSamples;
        // count_ > 1 so a single packet longer than the cap is played rather than discarded.
        while (queuedSamples_ > kHighWaterSamples && count_ > 1) dropOldest();
    }
    // Latches rather than playing immediately: offer runs on the reader thread and must not touch
    // the fifo or the decoder, which are playback-thread-only. Only the drained path in endTick
    // re-arms it, so a terminator cannot clear the gate mid-spurt and strand the tail.
    if (terminator) prebuffered_ = true;
    return priced;
}

int SpeakerQueue::popPacket(uint8_t* out, int outCap) {
    if (!prebuffered_) {
        if (queuedSamples_ < kPrebufferSamples) return 0;
        prebuffered_ = true;
    }
    if (count_ == 0) return 0;
    const Slot& slot = slots_[head_];
    if (slot.len > outCap) return -1;
    std::memcpy(out, pool_.data() + size_t(head_) * kMaxPacketBytes, size_t(slot.len));
    queuedSamples_ -= slot.spanSamples;
    head_ = (head_ + 1) & kSlotMask;
    count_--;
    return slot.len;
}

void SpeakerQueue::decodeInto(const uint8_t* data, int len) {
    const int n = decoder_->decode(data, len, decodeScratch_.data(), kMaxFrameSamples);
    if (n > 0) fifo_.write(decodeScratch_.data(), uint32_t(n));
}

int SpeakerQueue::pcmAvailable() const {
    return int(fifo_.available());
}

int SpeakerQueue::drain(int16_t* out, int frames) {
    const int taken = int(fifo_.readUpTo(out, uint32_t(frames)));
    if (taken < frames) std::memset(out + taken, 0, size_t(frames - taken) * sizeof(int16_t));
    return taken;
}

bool SpeakerQueue::endTick(bool produced) {
    idleTicks_ = produced ? 0 : idleTicks_ + 1;
    const bool drained = count_ == 0;
    // Fully drained: re-arm so the next talk spurt rebuilds its playout margin. On idle rather
    // than on the terminator frame, so the tail of a spurt plays out first and a spurt whose
    // terminator never arrives still re-arms.
    if (!produced && drained) prebuffered_ = false;
    // Two windows, because "produced nothing this tick" means two different things. Once the pool
    // is drained it means the speaker stopped talking, which is the short window. While packets
    // remain it means the prebuffer gate has not opened yet — a spurt is silent for its first
    // kPrebufferSamples, and the loop ticks faster than 100 Hz while doing so because each
    // arriving packet wakes it, so charging those as idle would retire a speaker before it plays.
    return idleTicks_ >= (drained ? kRetireIdleTicks : kStallIdleTicks);
}

}  // namespace dumble::playout
