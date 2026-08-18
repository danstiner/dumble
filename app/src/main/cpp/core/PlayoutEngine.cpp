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
    // Charged to the engine because there is no queue to charge it to: a capped session's packets
    // are as lost as ones a live queue overflowed away.
    if (slot < 0) {
        droppedPackets_++;
        return kOfferSpeakerCap;
    }
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
        // can catch the mismatch, and a 0 would leave one that sized its frame wrong permanently
        // mute with nothing to look at. `out` is left alone — writing `samples` of anything would
        // mean trusting the number this branch exists to reject.
        *liveSpeakers = 0;
        return kErrorBufferTooSmall;
    }
    std::memset(accumulator_.data(), 0, size_t(samples) * sizeof(int32_t));

    // Snapshot which slots are claimed, so the decode phase runs with the mutex released. The
    // snapshot cannot go stale underneath us: offer() only sets bits, and the sole code that
    // clears one is this function's commit phase — on this thread. A slot claimed after the
    // snapshot just waits a fill, well inside its prebuffer gate. The queues and decoders need
    // no snapshot of their own — create() built them and nothing replaces them.
    int live[kMaxSpeakers];
    bool speaking[kMaxSpeakers];
    int liveCount = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        for (int i = 0; i < kMaxSpeakers; i++) {
            if (!slots_.test(i)) continue;
            // Snapshotted with the slot rather than read in the decode phase, which runs with the
            // mutex released. A terminator landing during the decodes therefore costs one concealed
            // frame — one frame of fade at the end of speech.
            speaking[liveCount] = queues_[i].speaking();
            live[liveCount++] = i;
        }
    }

    // Pop under the lock, decode and mix outside it. The packet is copied through packetScratch_
    // rather than decoded in place because offer() may overwrite that pool entry during decode.
    // Samples each speaker produced, not merely whether it did: the concealment rule below needs
    // to tell a full frame from a short one zero-padded around a gap.
    int produced[kMaxSpeakers];
    bool concealed[kMaxSpeakers];
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
        // Conceal a mid-spurt shortfall rather than let drain zero-pad it. The concealed frame then
        // counts as production, which is what holds the prebuffer gate open and the retire clock at
        // zero: packets the stall delayed play on the frame they arrive instead of waiting out a
        // second prebuffer. Bounded by kConcealQuanta — past that the speaker re-anchors.
        const int shortfall = samples - decoder.available();
        concealed[n] = false;
        if (shortfall > 0 && speaking[n] && stallQuanta_[live[n]] < kConcealQuanta)
            concealed[n] = decoder.conceal(shortfall) > 0;
        produced[n] = decoder.drain(speakerOut_.data(), samples);
        if (produced[n] > 0) mixAccumulate(accumulator_.data(), speakerOut_.data(), samples);
    }
    mixFinalize(accumulator_.data(), out, samples);

    // Commit: sessions, per-fill bookkeeping and retirement, in one acquisition.
    int producing = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        for (int n = 0; n < liveCount; n++) {
            const int i = live[n];
            PacketQueue& queue = queues_[i];
            const bool audible = produced[n] > 0;
            // sessions_[i] is still the speaker the snapshot saw — see the snapshot comment.
            if (audible) sessions[producing++] = sessions_[i];
            // Judged before endFill, which closes the gate speaking() reads. One gap, one charge,
            // however many frames its hold spans — and stallQuanta_ keeps counting past
            // kConcealQuanta, so the frame that gives up on a stall is not a second gap. speaking()
            // is read here rather than taken from the snapshot, so a terminator that landed during
            // the decodes is honoured: the end of speech is not a dropout.
            if (concealed[n] || (produced[n] < samples && queue.speaking())) {
                if (stallQuanta_[i]++ == 0) concealedGaps_++;
            } else {
                stallQuanta_[i] = 0;
                // Speech spliced with silence with the queue already closed: the tail of a spurt,
                // which no hold could have covered.
                if (produced[n] > 0 && produced[n] < samples) concealedGaps_++;
            }
            queue.endFill(audible);
            idlePolls_[i] = audible ? 0 : idlePolls_[i] + 1;
            // Two windows, because "produced nothing this fill" means two different things. Once
            // the queue is drained it means the speaker stopped talking, the short window. While
            // packets remain it means the prebuffer gate has not opened yet — a spurt is silent
            // for its first kPrebufferSamples, and the loop fills faster than 100 Hz while doing
            // so because each arriving packet wakes it, so charging those as idle would retire a
            // speaker before it plays. Read fresh here, unlike the pop-time record endFill's
            // re-arm judges by: retiring resets the queue, and a packet that arrived during the
            // decodes must widen the window rather than be destroyed with the slot.
            if (idlePolls_[i] >= (queue.empty() ? kRetireIdlePolls : kStallIdlePolls)) {
                // Cleaned here rather than on the next claim: this thread is PcmRing's consumer,
                // the only side allowed to move its read index, and the only one that can see the
                // slot idle. The stall window retires with packets still queued, so this is not
                // defensive. decoder->reset() under the mutex is fine — it is a small memset, not
                // a decode, and retirement is rare. Harvest the queue's tally first: reset()
                // clears it, and a channel that dropped audio all session must not report zero
                // the moment its speaker goes quiet.
                droppedPackets_ += queue.droppedPackets();
                queue.reset();
                decoders_[i]->reset();
                slots_.clear(i);
            }
        }
        *liveSpeakers = slots_.count();
    }
    return producing;
}

PlayoutEngine::Stats PlayoutEngine::stats() {
    std::lock_guard<std::mutex> guard(mutex_);
    Stats out;
    int n = 0;
    // Live queues carry their own tally until they retire into droppedPackets_, so the total is
    // the sum of the two — and both under one lock, since retirement moves a tally from one to
    // the other and a read straddling it would count that speaker twice or not at all.
    int64_t dropped = droppedPackets_;
    for (int i = 0; i < kMaxSpeakers; i++) {
        if (!slots_.test(i)) continue;
        out.sessions[n] = sessions_[i];
        out.depths[n] = queues_[i].depthSamples();
        dropped += queues_[i].droppedPackets();
        n++;
    }
    out.speakers = n;
    out.concealedGaps = concealedGaps_;
    out.droppedPackets = dropped;
    return out;
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
    idlePolls_[free] = 0;
    stallQuanta_[free] = 0;
    return free;
}

}  // namespace dumble::playout
