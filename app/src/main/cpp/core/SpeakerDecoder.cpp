#include "core/SpeakerDecoder.h"
#include <bit>
#include <cstring>

namespace dumble::playout {

std::unique_ptr<SpeakerDecoder> SpeakerDecoder::create(int sampleRate, int maxQuantumSamples) {
    // The fifo is sized from bit_ceil(maxQuantumSamples + kMaxPacketSamples), which is signed
    // overflow near INT_MAX and not representable in uint32_t above 2^31 — both undefined — and
    // merely a multi-gigabyte allocation per speaker below that. kMaxPacketSamples is 120 ms, past
    // any sane frame.
    if (maxQuantumSamples <= 0 || maxQuantumSamples > kMaxPacketSamples) return nullptr;
    auto decoder = AudioDecoder::create(sampleRate, 1);
    if (!decoder) return nullptr;
    return std::unique_ptr<SpeakerDecoder>(
        new SpeakerDecoder(std::move(decoder), maxQuantumSamples));
}

SpeakerDecoder::SpeakerDecoder(std::unique_ptr<AudioDecoder> decoder, int maxQuantumSamples)
    : decoder_(std::move(decoder)),
      // One fill decodes only while below a frame and one decode adds at most one packet, so this
      // is the fifo's exact occupancy bound. bit_ceil because PcmRing wants a power of two: it
      // rounds up on its own in release but only asserts in debug, so rounding here keeps this
      // bound equal to the capacity the ring actually builds.
      fifo_(std::bit_ceil(uint32_t(maxQuantumSamples + kMaxPacketSamples))),
      decodeScratch_(kMaxPacketSamples) {}

void SpeakerDecoder::decode(const uint8_t* data, int len) {
    const int n = decoder_->decode(data, len, decodeScratch_.data(), kMaxPacketSamples);
    // The dropped-write case is unreachable: the fifo is sized for a frame plus a packet and the
    // caller decodes only while below a frame, so one decode always fits. PcmRing counts a drop
    // if that ever stops being true, which is the only reason ignoring the result is safe.
    if (n > 0) fifo_.write(decodeScratch_.data(), uint32_t(n));
}

int SpeakerDecoder::conceal(int samples) {
    // libopus only conceals in 2.5 ms units, so round the request up; the overshoot waits in the
    // fifo and plays next fill.
    const int rounded =
        (samples + kConcealGridSamples - 1) / kConcealGridSamples * kConcealGridSamples;
    // A decode with no packet is libopus's concealment call: it invents plausible audio from the
    // decoder's history, for exactly the duration asked. The engine calls this once per starved
    // call with that call's whole shortfall, so in practice every request is 10 ms and a hold is a
    // run of consecutive 10 ms requests, which libopus answers with a fade lasting ~60 ms.
    // Covering a fill with 2.5 ms grid-sized requests instead collapses to silence after the
    // first one, so the request is never subdivided — measured, and pinned by
    // ConcealsTheWholeGapInOneRequest.
    const int n = decoder_->decode(nullptr, 0, decodeScratch_.data(), rounded);
    // A negative is an error code, not a length — the same guard as decode().
    if (n <= 0) return 0;
    fifo_.write(decodeScratch_.data(), uint32_t(n));
    return n;
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
