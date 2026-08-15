#include "core/SpeakerDecoder.h"
#include <bit>
#include <cstring>

namespace dumble::playout {

std::unique_ptr<SpeakerDecoder> SpeakerDecoder::create(int sampleRate, int maxQuantumSamples) {
    // The fifo is sized from bit_ceil(maxQuantumSamples + kMaxPacketSamples), which is signed
    // overflow near INT_MAX and not representable in uint32_t above 2^31 — both undefined — and
    // merely a multi-gigabyte allocation per speaker below that. kMaxPacketSamples is 120 ms, past
    // any sane tick.
    if (maxQuantumSamples <= 0 || maxQuantumSamples > kMaxPacketSamples) return nullptr;
    auto decoder = AudioDecoder::create(sampleRate, 1);
    if (!decoder) return nullptr;
    return std::unique_ptr<SpeakerDecoder>(
        new SpeakerDecoder(std::move(decoder), maxQuantumSamples));
}

SpeakerDecoder::SpeakerDecoder(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples)
    : decoder_(std::move(decoder)),
      // One tick decodes only while below a quantum and one decode adds at most one packet, so this
      // is the fifo's exact occupancy bound. bit_ceil because PcmRing wants a power of two: it
      // rounds up on its own in release but only asserts in debug, so rounding here keeps this
      // bound equal to the capacity the ring actually builds.
      fifo_(std::bit_ceil(uint32_t(maxQuantumSamples + kMaxPacketSamples))),
      decodeScratch_(kMaxPacketSamples) {}

void SpeakerDecoder::decode(const uint8_t* data, int len) {
    const int n = decoder_->decode(data, len, decodeScratch_.data(), kMaxPacketSamples);
    // The dropped-write case is unreachable: the fifo is sized for a quantum plus a packet and the
    // caller decodes only while below a quantum, so one decode always fits. PcmRing counts a drop
    // if that ever stops being true, which is the only reason ignoring the result is safe.
    if (n > 0) fifo_.write(decodeScratch_.data(), uint32_t(n));
}

int SpeakerDecoder::available() const {
    return int(fifo_.available());
}

void SpeakerDecoder::reset() {
    decoder_->reset();
    fifo_.reset();
}

int SpeakerDecoder::drain(int16_t* out, int samples) {
    const int taken = int(fifo_.readUpTo(out, uint32_t(samples)));
    if (taken < samples) std::memset(out + taken, 0, size_t(samples - taken) * sizeof(int16_t));
    return taken;
}

}  // namespace dumble::playout
