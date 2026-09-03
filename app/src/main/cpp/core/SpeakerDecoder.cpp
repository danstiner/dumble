#include "core/SpeakerDecoder.h"
#include <cmath>
#include <bit>
#include <cstring>

namespace dumble::playout {

namespace {

// Root mean square, not mean square: Mumble computes `pow = sqrtf(sum / frame_size)`
// (AudioOutputSpeech.cpp:378) and tuned every envelope constant against that. Dropping the square
// root squares the quantity while leaving the constants alone, which moves the quiet threshold
// from amplitude < 0.01 of peak to amplitude < 0.1 — 20 dB more permissive, so soft speech would
// read as silence and shrink could splice a packet out of it.
float rootMeanSquare(const int16_t* pcm, int n) {
    double energy = 0;
    for (int i = 0; i < n; i++) energy += double(pcm[i]) * pcm[i];
    return float(std::sqrt(energy / n));
}

}  // namespace

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
      // One fill decodes only while below a frame, and one iteration adds either a concealed
      // frame or one packet, never both, so a frame plus one packet is the exact occupancy bound.
      // bit_ceil because PcmRing wants a power of two: it rounds up on its own in release but only
      // asserts in debug, so rounding here keeps this bound equal to the capacity the ring
      // actually builds.
      fifo_(std::bit_ceil(uint32_t(maxQuantumSamples + kMaxPacketSamples))),
      decodeScratch_(kMaxPacketSamples) {}

void SpeakerDecoder::decode(const uint8_t* data, int len) {
    const int n = decoder_->decode(data, len, decodeScratch_.data(), kMaxPacketSamples);
    // The dropped-write case is unreachable: the fifo is sized for a frame plus a packet and the
    // caller decodes only while below a frame, so one decode always fits. PcmRing counts a drop
    // if that ever stops being true, which is the only reason ignoring the result is safe.
    if (n > 0) {
        fifo_.write(decodeScratch_.data(), uint32_t(n));
        power_ = rootMeanSquare(decodeScratch_.data(), n);
        if (power_ >= powerMax_) {
            powerMax_ = power_;
        } else if (power_ <= powerMin_) {
            powerMin_ = power_;
        } else {
            powerMax_ = 0.99f * powerMax_;
            powerMin_ += 0.0001f * power_;
        }
    }
}

int SpeakerDecoder::conceal(int samples) {
    // libopus only conceals in 2.5 ms units, so round the request up; the overshoot waits in the
    // fifo and plays next fill.
    const int rounded =
        (samples + kConcealGridSamples - 1) / kConcealGridSamples * kConcealGridSamples;
    // A decode with no packet is libopus's concealment call: it invents plausible audio from the
    // decoder's history, for exactly the duration asked. The engine asks for a starved fill's
    // whole shortfall on a stall and for one frame per pop across a hole, so in practice every
    // request is 10 ms and a hold is a run of consecutive 10 ms requests, which libopus answers
    // with a fade lasting ~60 ms. Covering a fill with 2.5 ms grid-sized requests instead
    // collapses to silence after the first one, so a request is never subdivided — measured, and
    // pinned by ConcealsTheWholeGapInOneRequest.
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
    power_ = 0;
    powerMin_ = 0;
    powerMax_ = 0;
}

bool SpeakerDecoder::quiet() const {
    // Strictly less than, so an all-silence stream — where the range collapses to zero — reads as
    // not quiet. That is the safe answer: it withholds shrink rather than granting it on no
    // evidence.
    return power_ < powerMin_ + 0.01f * (powerMax_ - powerMin_);
}

int SpeakerDecoder::drain(int16_t* out, int samples) {
    const int taken = int(fifo_.readUpTo(out, uint32_t(samples)));
    if (taken < samples) std::memset(out + taken, 0, size_t(samples - taken) * sizeof(int16_t));
    return taken;
}

}  // namespace dumble::playout
