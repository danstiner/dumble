#include "core/PlayoutEngine.h"
#include <cstring>
#include "core/Mixer.h"

namespace dumble::playout {

std::unique_ptr<PlayoutEngine> PlayoutEngine::create(int sampleRate, int maxQuantumSamples,
                                                     int maxSpeakers) {
    if (maxSpeakers <= 0 || maxSpeakers > SlotSet::kCapacity) return nullptr;
    // Upper bound as well as lower: SpeakerQueue sizes its fifo from
    // bit_ceil(maxQuantumSamples + kMaxFrameSamples), which is signed overflow near INT_MAX and
    // not representable in uint32_t above 2^31 — both undefined — and merely a multi-gigabyte
    // allocation per speaker below that. kMaxFrameSamples is 120 ms, past any sane tick, and is
    // the same ceiling playout_jni.cpp already applies to `frames`.
    if (maxQuantumSamples <= 0 || maxQuantumSamples > kMaxFrameSamples) return nullptr;
    // A decoder is built per speaker, on demand, so there is nothing to fail here — but prove
    // libopus is reachable now rather than discovering it on the first packet, where the only
    // symptom would be permanent silence.
    if (!AudioDecoder::create(sampleRate, 1)) return nullptr;
    return std::unique_ptr<PlayoutEngine>(
        new PlayoutEngine(sampleRate, maxQuantumSamples, maxSpeakers));
}

PlayoutEngine::PlayoutEngine(int sampleRate, int maxQuantumSamples, int maxSpeakers)
    : sampleRate_(sampleRate),
      maxQuantumSamples_(maxQuantumSamples),
      maxSpeakers_(maxSpeakers),
      accumulator_(maxQuantumSamples),
      speakerOut_(maxQuantumSamples),
      packetScratch_(kMaxPacketBytes) {}

int PlayoutEngine::slotFor(int32_t session) {
    // The one path that does real work under mutex_: SpeakerQueue::create mallocs an opus decoder
    // and zero-initialises 67 KB of buffers, measured at 21 us on a host x86_64. Once per talk
    // spurt rather than once per session, since kRetireIdleTicks releases the queue 100 ms after a
    // speaker stops — so a busy channel pays it a few times a second, against a 10 ms tick, on the
    // reader thread while a THREAD_PRIORITY_URGENT_AUDIO playback thread may be waiting on the
    // mutex with no priority inheritance to lift this one. Not worth removing at that price.
    // Preallocating all kMaxSpeakers queues would commit ~5 MB resident to serve the handful of
    // speakers a channel actually has, and would only move this cost to startup; if the hold ever
    // does bite, the cheaper fix is to construct outside the lock and re-check under it, which
    // costs nothing but a rare wasted construction.
    int found = -1;
    slots_.forEach([&](int i) {
        if (sessions_[i] == session) found = i;
    });
    if (found >= 0) return found;
    if (slots_.size() >= maxSpeakers_) return -1;
    auto queue = SpeakerQueue::create(sampleRate_, maxQuantumSamples_);
    if (!queue) return -2;
    const int slot = slots_.claim();
    if (slot < 0) return -1;
    sessions_[slot] = session;
    queues_[slot] = std::move(queue);
    return slot;
}

int PlayoutEngine::offer(int32_t session, const uint8_t* data, int len, bool terminator) {
    // Refused whole, terminator included, unlike the unpriceable payload SpeakerQueue::offer still
    // latches: honouring the flag means taking the mutex on the garbage path, and a peer sending
    // oversized Opus payloads can withhold terminators just as easily.
    if (len > kMaxPacketBytes) return kOfferPacketTooLarge;
    // Priced before the payload is copied: two header bytes decide the span, so a packet that
    // will be dropped for overflow never costs a memcpy.
    const int span = len > 0 ? AudioDecoder::packetSamples(data, len, sampleRate_) : 0;
    std::lock_guard<std::mutex> guard(mutex_);
    const int slot = slotFor(session);
    if (slot == -1) return kOfferSpeakerCap;
    if (slot == -2) return kOfferEngineUnusable;
    // False means exactly one thing here — libopus could not parse a span out of the header, so
    // the payload cannot be scheduled. SpeakerQueue's other refusal, an oversized packet, is
    // unreachable past the guard above. It has already counted the drop and honoured any
    // terminator; all that is left is to say so instead of reporting acceptance.
    if (!queues_[slot]->offer(data, len, span > 0 ? span : 0, terminator))
        return kOfferMalformedPacket;
    return kOfferAccepted;
}

int PlayoutEngine::fillQuantum(int16_t* out, int frames, int32_t* sessions, int32_t* liveSpeakers) {
    if (frames <= 0 || frames > maxQuantumSamples_) {
        // Diagnostic, not silence: playout_jni.cpp validates `frames` against kMaxFrameSamples,
        // the absolute ceiling, and cannot see this engine's maxQuantumSamples — so this is the
        // only place the per-engine bound is caught, and returning 0 would leave a caller that
        // sized its quantum wrong permanently mute with nothing to look at. The zeroed buffer
        // still reaches every caller, the JNI seam included. The live count reaches only a direct
        // caller — Oboe will be one — because playout_jni.cpp sizes its status copy from the
        // return, and a refusal makes that copy empty. Hence VoiceReceiver treating a negative
        // return as fatal without reading it.
        std::memset(out, 0, size_t(frames > 0 ? frames : 0) * sizeof(int16_t));
        *liveSpeakers = 0;
        return kErrorBufferTooSmall;
    }
    std::memset(accumulator_.data(), 0, size_t(frames) * sizeof(int32_t));
    int producing = 0;

    // Snapshotted under the lock, and not just the slot indices: sessions_ and queues_ are
    // written by slotFor on the reader thread, so reading them during the unlocked decode below
    // would be a race even though the values cannot change while the slot stays claimed.
    struct Live {
        int slot;
        int32_t session;
        SpeakerQueue* queue;
    };
    Live live[SlotSet::kCapacity];
    int liveCount = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        slots_.forEach([&](int i) { live[liveCount++] = Live{i, sessions_[i], queues_[i].get()}; });
    }

    for (int n = 0; n < liveCount; n++) {
        const int i = live[n].slot;
        SpeakerQueue* queue = live[n].queue;
        // Pop under the lock, decode outside it: a decode is tens of microseconds and the reader
        // must never wait one out. One acquisition per packet, uncontended, costs nothing beside
        // the decode itself.
        while (queue->pcmAvailable() < frames) {
            int len;
            {
                std::lock_guard<std::mutex> guard(mutex_);
                len = queue->popPacket(packetScratch_.data(), kMaxPacketBytes);
            }
            if (len <= 0) break;
            queue->decodeInto(packetScratch_.data(), len);
        }
        const int produced = queue->drain(speakerOut_.data(), frames);
        if (produced > 0) {
            mixAccumulate(accumulator_.data(), speakerOut_.data(), frames);
            sessions[producing] = live[n].session;
            producing++;
        }
        std::lock_guard<std::mutex> guard(mutex_);
        if (queue->endTick(produced > 0)) {
            queues_[i].reset();
            slots_.release(i);
        }
    }

    mixFinalize(accumulator_.data(), out, frames);
    {
        std::lock_guard<std::mutex> guard(mutex_);
        *liveSpeakers = slots_.size();
    }
    return producing;
}


}  // namespace dumble::playout
