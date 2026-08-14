#include "core/PlayoutEngine.h"
#include <cstring>
#include "core/AudioDecoder.h"
#include "core/Mixer.h"

namespace dumble::playout {

std::unique_ptr<PlayoutEngine> PlayoutEngine::create(int sampleRate, int maxQuantumSamples,
                                                     int maxSpeakers) {
    if (maxSpeakers <= 0 || maxSpeakers > kMaxSpeakers) return nullptr;
    // Before the constructor, which sizes accumulator_ and speakerOut_ from this: unbounded, a
    // caller's typo asks the allocator for gigabytes here rather than being turned away. Its own
    // check and not a restatement of SpeakerDecoder's — the scratch below is this class's.
    if (maxQuantumSamples <= 0 || maxQuantumSamples > kMaxPacketSamples) return nullptr;
    std::unique_ptr<PlayoutEngine> engine(
        new PlayoutEngine(sampleRate, maxQuantumSamples, maxSpeakers));
    // Every speaker up front, so offer() never allocates. A failure here is libopus being
    // unreachable or maxQuantumSamples being outside what the per-speaker fifo can be built from
    // — either way it surfaces at startup instead of as permanent silence on the first packet.
    for (auto& decoder : engine->decoders_) {
        decoder = SpeakerDecoder::create(sampleRate, maxQuantumSamples);
        if (!decoder) return nullptr;
    }
    return engine;
}

PlayoutEngine::PlayoutEngine(int sampleRate, int maxQuantumSamples, int maxSpeakers)
    : sampleRate_(sampleRate),
      maxQuantumSamples_(maxQuantumSamples),
      maxSpeakers_(maxSpeakers),
      queues_(size_t(maxSpeakers)),
      decoders_(size_t(maxSpeakers)),
      accumulator_(maxQuantumSamples),
      speakerOut_(maxQuantumSamples),
      packetScratch_(kMaxPacketBytes) {}

int PlayoutEngine::slotFor(int32_t session) {
    int found = -1;
    slots_.forEach([&](int i) {
        if (sessions_[i] == session) found = i;
    });
    if (found >= 0) return found;
    if (slots_.size() >= maxSpeakers_) return -1;
    const int slot = slots_.claim();
    if (slot < 0) return -1;
    // No allocation and no reset: a free slot was already cleaned by whoever retired it, so this
    // holds mutex_ for a bitmask claim and two stores while a THREAD_PRIORITY_URGENT_AUDIO
    // playback thread may be waiting on it with no priority inheritance to lift this one.
    sessions_[slot] = session;
    idleTicks_[slot] = 0;
    return slot;
}

int PlayoutEngine::offer(int32_t session, const uint8_t* data, int len, bool terminator) {
    // Refused whole, terminator included, unlike the unmeasurable payload PacketQueue::offer still
    // latches: honouring the flag means taking the mutex on the garbage path, and a peer sending
    // oversized Opus payloads can withhold terminators just as easily.
    if (len > kMaxPacketBytes) return kOfferPacketTooLarge;
    // Measured before the payload is copied: two header bytes decide the span, so a packet that
    // will be dropped for overflow never costs a memcpy. This is also the only place that reads
    // the payload as Opus before the decode stage — PacketQueue takes the span as a number.
    const int span = len > 0 ? AudioDecoder::packetSamples(data, len, sampleRate_) : 0;
    std::lock_guard<std::mutex> guard(mutex_);
    const int slot = slotFor(session);
    if (slot < 0) return kOfferSpeakerCap;
    // False means exactly one thing here — libopus could not parse a span out of the header, so
    // the payload cannot be scheduled. PacketQueue's other refusal, an oversized packet, is
    // unreachable past the guard above. It has already honoured any terminator; all that is left
    // is to say so instead of reporting acceptance.
    if (!queues_[slot].offer(data, len, span > 0 ? span : 0, terminator))
        return kOfferMalformedPacket;
    return kOfferAccepted;
}

int PlayoutEngine::fillQuantum(int16_t* out, int samples, int32_t* sessions, int32_t* liveSpeakers) {
    if (samples <= 0 || samples > maxQuantumSamples_) {
        // Diagnostic, not silence: maxQuantumSamples is this engine's alone, so no caller above
        // can catch the mismatch, and returning 0 would leave one that sized its quantum wrong
        // permanently mute with nothing to look at. The zeroed buffer still reaches every caller.
        std::memset(out, 0, size_t(samples > 0 ? samples : 0) * sizeof(int16_t));
        *liveSpeakers = 0;
        return kErrorBufferTooSmall;
    }
    std::memset(accumulator_.data(), 0, size_t(samples) * sizeof(int32_t));
    int producing = 0;

    // Snapshotted under the lock: which slots are claimed, and the session each carries, both of
    // which slotFor writes on the reader thread. The queue and decoder themselves need no snapshot
    // — create() built them and nothing replaces them — so this copies two ints per live speaker.
    struct Live {
        int slot;
        int32_t session;
    };
    Live live[kMaxSpeakers];
    int liveCount = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        slots_.forEach([&](int i) { live[liveCount++] = Live{i, sessions_[i]}; });
    }

    for (int n = 0; n < liveCount; n++) {
        const int i = live[n].slot;
        PacketQueue& queue = queues_[i];
        SpeakerDecoder& decoder = *decoders_[i];
        // Pop under the lock, decode outside it: a decode is tens of microseconds and the reader
        // must never wait one out. One acquisition per packet, uncontended, costs nothing beside
        // the decode itself. The packet is copied through packetScratch_ rather than decoded in
        // place because offer() may overwrite that pool slot while this runs unlocked.
        while (decoder.available() < samples) {
            int len;
            {
                std::lock_guard<std::mutex> guard(mutex_);
                len = queue.pop(packetScratch_.data(), kMaxPacketBytes);
            }
            if (len <= 0) break;
            decoder.decode(packetScratch_.data(), len);
        }
        const int produced = decoder.drain(speakerOut_.data(), samples);
        if (produced > 0) {
            mixAccumulate(accumulator_.data(), speakerOut_.data(), samples);
            sessions[producing] = live[n].session;
            producing++;
        }
        std::lock_guard<std::mutex> guard(mutex_);
        queue.endTick(produced > 0);
        idleTicks_[i] = produced > 0 ? 0 : idleTicks_[i] + 1;
        // Two windows, because "produced nothing this tick" means two different things. Once the
        // queue is drained it means the speaker stopped talking, which is the short window. While
        // packets remain it means the prebuffer gate has not opened yet — a spurt is silent for
        // its first kPrebufferSamples, and the loop ticks faster than 100 Hz while doing so
        // because each arriving packet wakes it, so charging those as idle would retire a speaker
        // before it plays.
        if (idleTicks_[i] >= (queue.empty() ? kRetireIdleTicks : kStallIdleTicks)) {
            // Cleaned here rather than on the next claim: this thread is PcmRing's consumer, the
            // only side allowed to move its read index, and it is also the only thread that can
            // see the slot idle. The stall window retires with packets still queued, so the reset
            // is not merely defensive.
            queue.reset();
            decoder.reset();
            slots_.release(i);
        }
    }

    mixFinalize(accumulator_.data(), out, samples);
    {
        std::lock_guard<std::mutex> guard(mutex_);
        *liveSpeakers = slots_.size();
    }
    return producing;
}

}  // namespace dumble::playout
