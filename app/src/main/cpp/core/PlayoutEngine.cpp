#include "core/PlayoutEngine.h"
#include <cstring>
#include <ctime>
#include "core/AudioDecoder.h"
#include "core/Mixer.h"

namespace dumble::playout {

namespace {

// CLOCK_BOOTTIME and not CLOCK_MONOTONIC — which is what std::chrono::steady_clock gives on
// bionic, so it cannot be used here. frame_number is a sender-side wall-clock counter that keeps
// advancing through suspend; if our arrival clock stopped during deep sleep and the sender's did
// not, the first packet after resume would look impossibly early, become the window minimum, and
// leave every packet after it reading as hugely late. BootTimeSource made the same choice on the
// Kotlin side for the same reason.
//
// Darwin has no CLOCK_BOOTTIME, and the host test build is the only thing that compiles this file
// off Android. Its CLOCK_MONOTONIC is the right stand-in rather than a compromise: unlike Linux's,
// it keeps advancing while the system is asleep, which is the property this needs. CLOCK_UPTIME_RAW
// is the one that stops there, and is what a port reaching for the closest-looking name would pick.
#if defined(CLOCK_BOOTTIME)
constexpr clockid_t kArrivalClock = CLOCK_BOOTTIME;
#else
constexpr clockid_t kArrivalClock = CLOCK_MONOTONIC;
#endif

int64_t bootMillis() {
    timespec ts{};
    clock_gettime(kArrivalClock, &ts);
    return int64_t(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

}  // namespace

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

int PlayoutEngine::offer(int32_t session, const uint8_t* data, int len, uint64_t frameNumber,
                         bool terminator) {
    // Stamped before the payload is judged, so our own parse cost is not laundered into the jitter
    // measurement.
    const int64_t arrivalMillis = bootMillis();
    // Judge the payload before the mutex. Extracting the sample count here is also what keeps the
    // packet queue free of Opus details.
    int verdict = kOfferAccepted;
    int samples = 0;
    if (len > kMaxPacketBytes) {
        verdict = kOfferPacketTooLarge;
    } else if (len > 0) {
        samples = AudioDecoder::packetSamples(data, len, sampleRate_);
        // Zeroed because the negative is libopus's error code, and everything below — observe(),
        // queue.offer() — takes a duration.
        if (samples <= 0) {
            verdict = kOfferMalformedPacket;
            samples = 0;
        }
    }
    // A refused payload is dropped, unless it carries the terminator flag. is_terminator is a
    // protobuf field, so we should respect it even if the opus data is malformed.
    if (verdict != kOfferAccepted && !terminator) return verdict;

    std::lock_guard<std::mutex> guard(mutex_);
    const int slot = slotFor(session);
    // Before the cap's early return: a packet refused for want of a slot still arrived, and its
    // sender is exactly the one whose estimate we want warm for when a slot frees.
    const int estimator = estimatorFor(session, arrivalMillis);
    // The slot's index into the table, so fillQuantum indexes rather than scanning. Set here
    // because this is the one place both the slot and the entry are resolved together.
    if (slot >= 0) estimatorIndex_[slot] = estimator;
    const int relative = estimators_[estimator].observe(frameNumber, samples, arrivalMillis);
    if (relative >= 0) downlink_.observeRelativeDelay(relative, arrivalMillis);
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
        queue.offer(nullptr, 0, 0, true);
    }
    return verdict;
}

int PlayoutEngine::estimatorFor(int32_t session, int64_t arrivalMillis) {
    int free = -1;
    int oldest = -1;
    for (int i = 0; i < kEstimatorSlots; i++) {
        if (!estimatorUsed_.test(i)) {
            if (free < 0) free = i;
        } else if (estimatorSessions_[i] == session) {
            estimatorLastArrival_[i] = arrivalMillis;
            return i;
        } else if (oldest < 0 || estimatorLastArrival_[i] < estimatorLastArrival_[oldest]) {
            oldest = i;
        }
    }
    const int claimed = free >= 0 ? free : oldest;
    estimators_[claimed].reset();
    estimatorUsed_.set(claimed);
    estimatorSessions_[claimed] = session;
    estimatorLastArrival_[claimed] = arrivalMillis;
    // Start from what the link has already shown us rather than from the cold constant. Only the
    // histogram travels; the baseline is per-sender and stays empty, so this sender's first packet
    // is still a rebase.
    if (downlink_.hasData()) estimators_[claimed].seedFrom(downlink_);
    return claimed;
}

JitterEstimator* PlayoutEngine::estimatorForSlot(int slot) {
    const int i = estimatorIndex_[slot];
    if (estimatorUsed_.test(i) && estimatorSessions_[i] == sessions_[slot]) return &estimators_[i];
    return nullptr;
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
    int target[kMaxSpeakers];
    int liveCount = 0;
    {
        std::lock_guard<std::mutex> guard(mutex_);
        for (int i = 0; i < kMaxSpeakers; i++) {
            if (!slots_.test(i)) continue;
            // Snapshotted with the slot rather than read in the decode phase, which runs with the
            // mutex released. A terminator landing during the decodes therefore costs one concealed
            // frame — one frame of fade at the end of speech.
            speaking[liveCount] = queues_[i].speaking();
            // A live slot with no table entry is only reachable if the LRU evicted a speaker who
            // is still talking, which 64 entries against 8 slots makes vanishingly unlikely. The
            // cold constant is the right answer if it ever happens.
            const JitterEstimator* est = estimatorForSlot(i);
            target[liveCount] = (est ? est->targetSamples() : kColdStartSamples) + writeAhead_;
            // Unlike target, catchUpAllowed is not snapshotted here: the gate latches, so a target
            // that moves between snapshot and pop cannot re-close it and staleness there is
            // harmless. discontinuous() can flip false->true inside this same window if a fresh
            // spurt's burst arrives while the decode phase runs unlocked, and a stale true here
            // would open the gate on a first syllable as if it were a mid-spurt catch-up — the
            // exact trim the contiguity test exists to refuse. So it is read fresh inside pop's own
            // lock instead of carried from this snapshot.
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
                const JitterEstimator* est = estimatorForSlot(live[n]);
                len = queue.pop(packetScratch_, kMaxPacketBytes, target[n],
                                est && !est->discontinuous());
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
            // Once the spurt is playing the latch has done its job; a break arriving later sets it
            // again for the next gate-open.
            if (queue.gateOpen()) {
                if (JitterEstimator* est = estimatorForSlot(i)) est->clearDiscontinuity();
            }
            // Shed one packet of standing delay, but only where it cannot be heard. quiet()
            // describes the frame just decoded, so a fill that produced nothing has a stale
            // answer; the deadband is hysteresis and canShrink is what stops an undershoot. A
            // concealed fill can also reach here on a stale quiet(), which is harmless: it wants
            // delay shed if the backlog allows it, the same as any other fill.
            if (produced[n] > 0) {
                if (shrinkQuanta_[i] >= kShrinkCooldownQuanta && decoders_[i]->quiet() &&
                    queue.canShrink(target[n] + kShrinkDeadbandSamples)) {
                    queue.shrink();
                    shrinkQuanta_[i] = 0;
                } else {
                    shrinkQuanta_[i]++;
                }
            }
            idlePolls_[i] = audible ? 0 : idlePolls_[i] + 1;
            // Two windows, because "produced nothing this fill" means two different things. Once
            // the queue is drained it means the speaker stopped talking, the short window. While
            // packets remain it means the prebuffer gate has not opened yet — a spurt is silent
            // until it reaches the target, and the loop fills faster than 100 Hz while doing
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
                shrunkPackets_ += queue.shrunkPackets();
                catchUpPackets_ += queue.catchUpPackets();
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
    int64_t shrunk = shrunkPackets_;
    int64_t caughtUp = catchUpPackets_;
    for (int i = 0; i < kMaxSpeakers; i++) {
        if (!slots_.test(i)) continue;
        out.sessions[n] = sessions_[i];
        out.depths[n] = queues_[i].depthSamples();
        const JitterEstimator* est = estimatorForSlot(i);
        out.targets[n] = (est ? est->targetSamples() : kColdStartSamples) + writeAhead_;
        dropped += queues_[i].droppedPackets();
        shrunk += queues_[i].shrunkPackets();
        caughtUp += queues_[i].catchUpPackets();
        n++;
    }
    out.speakers = n;
    out.concealedGaps = concealedGaps_;
    out.droppedPackets = dropped;
    out.shrunkPackets = shrunk;
    out.catchUpPackets = caughtUp;
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
    shrinkQuanta_[free] = 0;
    return free;
}

void PlayoutEngine::setWriteAheadSamples(int samples) {
    std::lock_guard<std::mutex> lock(mutex_);
    writeAhead_ = samples > 0 ? samples : 0;
}

}  // namespace dumble::playout
