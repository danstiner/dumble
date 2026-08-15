#include "core/PlayoutEngine.h"
#include <cstring>
#include "core/AudioDecoder.h"
#include "core/Mixer.h"

namespace dumble::playout {

std::unique_ptr<PlayoutEngine> PlayoutEngine::create(int sampleRate, int maxQuantumSamples) {
    if (maxQuantumSamples <= 0 || maxQuantumSamples > kMaxPacketSamples) return nullptr;
    std::unique_ptr<PlayoutEngine> engine(new PlayoutEngine(sampleRate, maxQuantumSamples));
    // Every speaker up front, so offer() never allocates and we catch Opus init failure early.
    for (auto& decoder : engine->decoders_) {
        decoder = SpeakerDecoder::create(sampleRate, maxQuantumSamples);
        if (!decoder) return nullptr;
    }
    return engine;
}

int PlayoutEngine::offer(int32_t session, const uint8_t* data, int len, bool terminator) {
    // Judge the payload before the mutex. Extracting the sample count here is also what keeps the
    // packet queue free of Opus details.
    int verdict = kOfferAccepted;
    int samples = 0;
    if (len > kMaxPacketBytes) {
        verdict = kOfferPacketTooLarge;
    } else if (len > 0) {
        samples = AudioDecoder::packetSamples(data, len, sampleRate_);
        if (samples <= 0) verdict = kOfferMalformedPacket;
    }
    // A refused payload is dropped, unless it carries the terminator flag. is_terminator is a
    // protobuf field, so we should respect it even if the opus data is malformed.
    if (verdict != kOfferAccepted && !terminator) return verdict;

    std::lock_guard<std::mutex> guard(mutex_);
    const int slot = slotFor(session);
    if (slot < 0) return kOfferSpeakerCap;
    PacketQueue& queue = queues_[slot];
    if (verdict == kOfferAccepted) {
        queue.offer(data, len, samples, terminator);
    } else {
        // Terminator-only, the opus data was rejected.
        queue.offer(nullptr, 0, 0, true);
    }
    return verdict;
}

int PlayoutEngine::fillQuantum(int16_t* out, int samples, int32_t* sessions,
                               int32_t* liveSpeakers) {
    if (samples <= 0 || samples > maxQuantumSamples_) {
        // Diagnostic, not silence: maxQuantumSamples is this engine's alone, so no caller above
        // can catch the mismatch, and a 0 would leave one that sized its quantum wrong permanently
        // mute with nothing to look at. `out` is left alone — writing `samples` of anything would
        // mean trusting the number this branch exists to reject.
        *liveSpeakers = 0;
        return kErrorBufferTooSmall;
    }
    std::memset(accumulator_.data(), 0, size_t(samples) * sizeof(int32_t));

    // Snapshot which slots are claimed, so the decode phase runs with the mutex released. The
    // snapshot cannot go stale underneath us: offer() only sets bits, and the sole code that
    // clears one is this function's commit phase — on this thread. A slot claimed after the
    // snapshot just waits a tick, well inside its prebuffer gate. The queues and decoders need
    // no snapshot of their own — create() built them and nothing replaces them.
    int live[kMaxSpeakers];
    int liveCount = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        for (int i = 0; i < kMaxSpeakers; i++)
            if (slots_.test(i)) live[liveCount++] = i;
    }

    // Pop under the lock, decode and mix outside it. The packet is copied through packetScratch_
    // rather than decoded in place because offer() may overwrite that pool entry during decode.
    bool produced[kMaxSpeakers];
    for (int n = 0; n < liveCount; n++) {
        PacketQueue& queue = queues_[live[n]];
        SpeakerDecoder& decoder = *decoders_[live[n]];
        while (decoder.available() < samples) {
            int len;
            {
                std::lock_guard<std::mutex> guard(mutex_);
                len = queue.pop(packetScratch_, kMaxPacketBytes);
            }
            if (len <= 0) break;
            decoder.decode(packetScratch_, len);
        }
        produced[n] = decoder.drain(speakerOut_.data(), samples) > 0;
        if (produced[n]) mixAccumulate(accumulator_.data(), speakerOut_.data(), samples);
    }
    mixFinalize(accumulator_.data(), out, samples);

    // Commit: sessions, tick bookkeeping and retirement, in one acquisition.
    int producing = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        for (int n = 0; n < liveCount; n++) {
            const int i = live[n];
            PacketQueue& queue = queues_[i];
            // sessions_[i] is still the speaker the snapshot saw — see the snapshot comment.
            if (produced[n]) sessions[producing++] = sessions_[i];
            queue.endTick(produced[n]);
            idleTicks_[i] = produced[n] ? 0 : idleTicks_[i] + 1;
            // Two windows, because "produced nothing this tick" means two different things. Once
            // the queue is drained it means the speaker stopped talking, the short window. While
            // packets remain it means the prebuffer gate has not opened yet — a spurt is silent
            // for its first kPrebufferSamples, and the loop ticks faster than 100 Hz while doing
            // so because each arriving packet wakes it, so charging those as idle would retire a
            // speaker before it plays. Read fresh here, unlike the pop-time record endTick's
            // re-arm judges by: retiring resets the queue, and a packet that arrived during the
            // decodes must widen the window rather than be destroyed with the slot.
            if (idleTicks_[i] >= (queue.empty() ? kRetireIdleTicks : kStallIdleTicks)) {
                // Cleaned here rather than on the next claim: this thread is PcmRing's consumer,
                // the only side allowed to move its read index, and the only one that can see the
                // slot idle. The stall window retires with packets still queued, so this is not
                // defensive. decoder->reset() under the mutex is fine — it is a small memset, not
                // a decode, and retirement is rare.
                queue.reset();
                decoders_[i]->reset();
                slots_.clear(i);
            }
        }
        *liveSpeakers = slots_.count();
    }
    return producing;
}

PlayoutEngine::PlayoutEngine(int sampleRate, int maxQuantumSamples)
    : sampleRate_(sampleRate),
      maxQuantumSamples_(maxQuantumSamples),
      accumulator_(maxQuantumSamples),
      speakerOut_(maxQuantumSamples) {}

int PlayoutEngine::slotFor(int32_t session) {
    // One pass to find this session's slot or the first unoccupied slot it can claim.
    int free = -1;
    for (int i = 0; i < kMaxSpeakers; i++) {
        if (!slots_.test(i)) {
            if (free < 0) free = i;
        } else if (sessions_[i] == session) {
            return i;
        }
    }
    if (free < 0) return -1;
    // No allocation and no reset — a free slot was cleaned by whoever retired it.
    slots_.set(free);
    sessions_[free] = session;
    idleTicks_[free] = 0;
    return free;
}

}  // namespace dumble::playout
