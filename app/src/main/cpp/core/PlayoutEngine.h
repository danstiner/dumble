#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/JitterEstimator.h"
#include "core/PacketQueue.h"
#include "core/PlayoutConstants.h"
#include "core/Bitmap.h"
#include "core/SpeakerDecoder.h"

namespace dumble::playout {

static_assert(kMaxSpeakers <= Bitmap::kCapacity,
              "occupancy is one uint64_t, so kMaxSpeakers cannot exceed its width");

/**
 * Owns inbound voice below the platform: one PacketQueue and one SpeakerDecoder per sender, mixed
 * into one frame per fillQuantum call. Platform-free — no JNI, no output device.
 *
 * The two halves are separate types because they answer to different threads. A packet queue is
 * touched only under mutex_; a decoder only from the playback thread, with mutex_ released. The
 * pop-then-decode loop in fillQuantum is where they meet — one lock acquisition per packet, and no
 * decode inside it.
 *
 * Every speaker is built by create() and lives as long as the engine, so a slot is claimed, reset
 * and released but never allocated: offer() takes no malloc under the mutex a playback thread is
 * waiting on.
 *
 * Retirement of a speaker at the end of a talking spurt lives here rather than in either half:
 * a slot is this class's to claim and release, and neither half alone can tell a speaker that
 * has stopped talking from one still waiting to fill prebuffer before producing audio.
 *
 * Each method below opens with the thread that may call it and what it does with mutex_. Stated
 * per method rather than once for the type, as PacketQueue and SpeakerDecoder each state theirs:
 * this is the one class where the two sides meet, so there is no single rule to state.
 *
 * fillQuantum is deliberately shaped as an audio data callback: non-blocking, allocation-free, and
 * taking its sample count per call. Today a Kotlin playback thread drives it between AudioTrack
 * writes; an Oboe callback will call the same function unchanged.
 *
 * One fillQuantum call — a fill — is the engine's only clock; nothing in here advances between
 * fills. What a fill produces is a **quantum**: `samples` of audio, picked by the caller, which
 * today's playback loop sets to one frame. A fill produces at most that, and often less.
 *
 * Fills come in two kinds, and that is what decides whether counting them measures time. A fill
 * that produced is written to the output, so the device paces it and kConcealQuanta really is
 * about 100 ms at today's quantum. A fill that produced nothing is a **poll** — the loop parks and
 * an arriving packet wakes it — so polls outrun real time and kRetireIdlePolls and kStallIdlePolls
 * are ceilings on fills, not durations. Concealment counts as production.
 */
class PlayoutEngine {
public:
    /** Any thread; no lock, nothing is shared until it returns. Null when libopus cannot build a
     *  decoder, or when `maxQuantumSamples` is outside (0, kMaxPacketSamples] — every buffer here
     *  and in each speaker is sized from it. */
    static std::unique_ptr<PlayoutEngine> create(int sampleRate, int maxQuantumSamples);

    /** Reader thread; takes mutex_. By convention and not by requirement — this is internally
     *  synchronized — but it is the convention the design is built around: the reader must never
     *  be made to wait out a decode, which is what fillQuantum's pop-then-decode split buys.
     *
     *  Returns one of the kOffer* codes. `data` may be null when `len` is 0; a payload-free
     *  terminator is accepted, since it carries no samples by definition. `frameNumber` is the
     *  sender's own wall-clock frame counter, peer-controlled and sanity-checked by the estimator
     *  rather than here. */
    int offer(int32_t session, const uint8_t* data, int len, uint64_t frameNumber, bool terminator);

    /**
     * Playback thread, and only ever one; takes mutex_, and releases it to decode. A requirement
     * rather than a convention, unlike offer(): a SpeakerDecoder's PcmRing permits only its
     * consumer to move the read index, so a second caller is undefined behaviour even if the two
     * never overlap.
     *
     * Writes exactly `samples` of mixed audio into `out`. Returns how many speakers produced
     * audio and writes their sessions into `sessions[0..n)`, which must hold kMaxSpeakers entries.
     * `liveSpeakers` receives the claimed-slot count — how the caller tells "nobody is here" from
     * "somebody is prebuffering" — and is always written, refusals included. A `samples` outside
     * (0, the maxQuantumSamples given to create()] answers kErrorBufferTooSmall rather than a
     * silent frame, and leaves `out` untouched: the caller must not play a refused fill.
     */
    int fillQuantum(int16_t* out, int samples, int32_t* sessions, int32_t* liveSpeakers);

    /** A whole reading of the engine: plain data, holding no reference to anything the playback
     *  thread owns. */
    struct Stats {
        // Speakers holding a slot, and how many entries of the two arrays are filled. A speaker
        // still below its prebuffer gate is counted: it holds a slot and has depth, but produces
        // no audio yet, so this is not fillQuantum's producing count.
        int speakers;
        int32_t sessions[kMaxSpeakers];
        // Samples queued and not yet decoded, per speaker. Entries past `speakers` are unspecified.
        int32_t depths[kMaxSpeakers];
        // The target each speaker's gate is measuring against. Beside depths deliberately: target
        // against depth is the only reading that shows whether the buffer is converging, and
        // without it there is no way to tell a healthy 200 ms from a ratcheted one.
        int32_t targets[kMaxSpeakers];

        // The two totals below are monotonic since the engine was built: the caller subtracts a
        // talk-spurt baseline, the way it already does for the platform's underrun counter.

        // Gaps, not their length: a fill that produced less than its quantum, or the leading fill
        // of a mid-spurt stall.
        int64_t concealedGaps;
        // Packets the jitter queues threw away for backlog — past kMaxQueuedPackets or
        // kHighWaterSamples — plus packets refused because kMaxSpeakers was already met, which
        // have no queue to charge them to. A payload offer() refused is deliberately excluded: it
        // is dropped before the mutex is taken and already carries kOfferPacketTooLarge or
        // kOfferMalformedPacket, so counting it would put lock traffic on the garbage path. What
        // is left is exactly the loss no status code reports.
        int64_t droppedPackets;
        // Packets discarded on purpose to shed standing delay, split by mechanism because they
        // answer different questions: shrink rising means the link drifts, catch-up rising means
        // it stalls. Neither is loss, which is why neither is in droppedPackets — that one means
        // the network cost us audio. This is the invariant the seam and the stats sample carry
        // outward; if it moves, it moves here first.
        int64_t shrunkPackets;
        int64_t catchUpPackets;
    };

    /** Any thread; takes mutex_. */
    Stats stats();

private:
    PlayoutEngine(int sampleRate, int maxQuantumSamples);

    /** Any thread; caller already holds mutex_. Slot for this session, claiming a free one if
     *  needed; -1 at the cap. */
    int slotFor(int32_t session);

    /** One tracked sender's estimate. Keyed by session and not by slot, because a slot retires
     *  about 100 ms after its queue drains and reset()s with it — estimator state living there
     *  would die within 100 ms of silence, which is worse than the bug this table exists to fix. */
    struct EstimatorEntry {
        int32_t session = 0;
        bool used = false;
        int64_t lastArrivalMillis = 0;
        JitterEstimator estimator;
    };

    /** Any thread; caller already holds mutex_. This session's entry, claiming or evicting one if
     *  needed. Never null. */
    EstimatorEntry& entryFor(int32_t session, int64_t arrivalMillis);

    /** Any thread; caller already holds mutex_. This session's entry, or null — the lookup for
     *  readers that must not claim, which is the playback thread and stats(). */
    JitterEstimator* estimatorOrNull(int32_t session);

    EstimatorEntry estimators_[kEstimatorSlots];
    // Shares only the histogram, fed the relative delays the per-sender entries compute. Never fed
    // a raw arrival: frame_number origins are per-sender, so pooling raw offsets would let
    // whichever sender has the largest one own the shared minimum.
    JitterEstimator downlink_;

    const int sampleRate_;
    const int maxQuantumSamples_;

    std::mutex mutex_;
    Bitmap slots_;
    int32_t sessions_[kMaxSpeakers] = {};
    // Consecutive polls a claimed slot has answered with nothing. Zeroed on claim.
    int idlePolls_[kMaxSpeakers] = {};
    // Consecutive quanta a claimed slot has starved mid-spurt, and so the hold's clock. Cleared
    // only by real audio, never by a fill that merely did not conceal: an expired hold would
    // otherwise clear its own counter and start concealing again on the next fill, forever. Keeps
    // counting past kConcealQuanta for the same reason. Zeroed on claim, like idlePolls_.
    int stallQuanta_[kMaxSpeakers] = {};
    // Quanta produced since this slot last shrank. Counted only on fills that produced, which is
    // what makes kShrinkCooldownQuanta a real two seconds: each is paced by the output write,
    // while polls run faster than real time.
    int shrinkQuanta_[kMaxSpeakers] = {};
    // Whole-engine totals, monotonic since construction. A retiring queue's own tally is
    // harvested into droppedPackets_ before reset() clears it.
    int64_t concealedGaps_ = 0;
    int64_t droppedPackets_ = 0;
    int64_t shrunkPackets_ = 0;
    int64_t catchUpPackets_ = 0;
    // Built by create() and never rebuilt, which is what lets the playback thread hold references
    // into them across an unlocked decode.
    PacketQueue queues_[kMaxSpeakers];
    std::unique_ptr<SpeakerDecoder> decoders_[kMaxSpeakers];

    // Playback-thread-only scratch, sized once so no fill allocates.
    std::vector<int32_t> accumulator_;
    std::vector<int16_t> speakerOut_;
    uint8_t packetScratch_[kMaxPacketBytes] = {};
};

}  // namespace dumble::playout
