#pragma once
#include <atomic>
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
static_assert(kEstimatorSlots <= Bitmap::kCapacity,
              "estimator occupancy is one uint64_t, so the table cannot outgrow its width");

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
 * fillQuantum is deliberately shaped as an audio data callback: non-blocking in realtime mode,
 * allocation-free, and taking its sample count per call. Today a Kotlin playback thread drives it between AudioTrack
 * writes; an Oboe callback will call the same function unchanged.
 *
 * One fillQuantum call — a fill — is the engine's only clock; nothing in here advances between
 * fills. What a fill produces is a **quantum**: `samples` of audio, picked by the caller, which
 * today's playback loop sets to one frame. A fill produces at most that, and often less.
 *
 * Every limit below is a count of samples accumulated from each fill's `samples`, so it means the
 * same time at any fill size. A fill that produced nothing counts its samples as elapsed too:
 * under a device-paced caller it was, and under the push loop it is the 10 ms park.
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
     * "somebody is prebuffering" — and is always written, refusals included. A `samples` of zero
     * or less answers kErrorBufferTooSmall rather than a silent frame, and leaves `out` untouched:
     * the caller must not play a refused fill. A `samples` above the maxQuantumSamples given to
     * create() is served as consecutive whole fills, and the return value and `liveSpeakers`
     * describe the last of them.
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
        // Whether each live speaker produced audio in the most recent fill — what a caller shows
        // as "speaking". Aligned with sessions[]. Taken under the same lock as everything else
        // here, so a retire-and-reclaim between two reads cannot map a bit to the wrong session.
        bool audible[kMaxSpeakers];

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
        // Audio the network never delivered, in samples; see PlayoutStats for the lost/dropped
        // distinction.
        int64_t lostSamples;
        // Packets the queue refused as out of order; see PlayoutStats.
        int64_t outOfOrderPackets;
        // Realtime-mode fills answered with silence because the reader held mutex_ — see
        // setRealtime. Monotonic, like the totals above.
        uint64_t contendedFills;

        // Wall time per fill, max and mean, over the fills since the last stats() read. A device
        // callback's budget is one burst, and this is how much of it the engine — decodes
        // included — used.
        uint64_t fillMicrosMax;
        uint64_t fillMicrosMean;
    };

    /** Any thread; takes mutex_. */
    Stats stats();

    /** Any thread; no lock, so the realtime callback may call it when the sink's buffer changes.
     *  How far ahead of playout the output sink holds audio, in samples. Added to every target a
     *  queue is measured against, so the estimator's target is what the *queue* holds: a packet
     *  is popped this far before it plays, and only what is still queued at that instant is
     *  margin against a late arrival. 0 until the sink reports. */
    void setWriteAheadSamples(int samples);

    /** Any thread; takes mutex_. The output sink has gone (true) or come back (false). Going
     *  down releases every slot — tallies harvested, queues and decoders reset — and keeps every
     *  estimator: with no fills nothing can conceal a spurt in flight, and a backlog played out
     *  on reopen would be standing delay with no catch-up to trim it, so the next spurt
     *  prebuffers afresh against a warm estimate. Coming back changes nothing; the argument
     *  exists so the adapter's two error callbacks are symmetric. */
    void setOutputDown(bool down);

    /** Any thread. In realtime mode every mutex_ acquisition on the fill path is a try_lock: a
     *  fill that finds the reader inside offer() outputs silence, touches no per-speaker state,
     *  and counts one in Stats::contendedFills, rather than blocking a realtime thread behind a
     *  normal-priority one — bionic's std::mutex has no priority inheritance, and AAudio's
     *  callback contract forbids waiting on one. Off by default: the push loop's own thread is
     *  the only other caller, and blocking there is harmless. */
    void setRealtime(bool realtime);

#ifdef DUMBLE_TESTING
    /** Holds mutex_ around `f`, so a test can stage the contention a realtime fill must survive. */
    template <class F>
    void holdMutexForTest(F f) {
        std::lock_guard<std::mutex> guard(mutex_);
        heldForTest_ = true;
        f();
        heldForTest_ = false;
    }
    bool mutexHeldForTest() const { return heldForTest_.load(); }
#endif

private:
    PlayoutEngine(int sampleRate, int maxQuantumSamples);

    /** One fill of at most maxQuantumSamples_: fillQuantum's contract, minus the size loop. */
    int fillOnce(int16_t* out, int samples, int32_t* sessions, int32_t* liveSpeakers);

    /** Any thread; caller already holds mutex_. Slot for this session, claiming a free one if
     *  needed; -1 at the cap. */
    int slotFor(int32_t session);

    /** Playback thread; no lock, this is the path that could not take one. Silence for the whole
     *  fill, the last live count the commit phase saw, and one more contended fill. */
    int contendedFill(int16_t* out, int samples, int32_t* liveSpeakers);

    /** Playback thread, or any thread while the output is down; caller already holds mutex_.
     *  Frees a claimed slot: tallies harvested, queue and decoder reset. */
    void releaseSlot(int slot);

    /** Any thread; caller already holds mutex_. Index of this session's estimator, claiming or
     *  evicting one if needed. Never negative. */
    int estimatorFor(int32_t session, int64_t arrivalMillis);

    /** Any thread; caller already holds mutex_. This slot's estimator, or null when the table
     *  evicted it out from under a slot that is still live. */
    JitterEstimator* estimatorForSlot(int slot);

    // Tracked senders' estimates, keyed by session and not by slot: a slot retires about 100 ms
    // after its queue drains and reset()s with it, so estimator state living there would die within
    // 100 ms of silence — the bug this table exists to fix.
    //
    // Split rather than an array of {session, estimator} structs because the only hot operation is
    // the key scan. One estimator is 200 bytes, so scanning an array of them strides 13.8 KB to
    // read 64 session ids; the keys alone are 256 bytes, and the occupancy is one word. The bodies
    // are touched only on a hit.
    Bitmap estimatorUsed_;
    int32_t estimatorSessions_[kEstimatorSlots] = {};
    int64_t estimatorLastArrival_[kEstimatorSlots] = {};
    JitterEstimator estimators_[kEstimatorSlots];
    // Each live slot's index into that table, so the playback thread indexes instead of scanning.
    // A hint, not a handle: the table can evict an entry while its slot is still live, so every use
    // re-checks the session it lands on.
    int estimatorIndex_[kMaxSpeakers] = {};
    // Shares only the histogram, fed the relative delays the per-sender entries compute. Never fed
    // a raw arrival: frame_number origins are per-sender, so pooling raw offsets would let
    // whichever sender has the largest one own the shared minimum.
    JitterEstimator downlink_;

    const int sampleRate_;
    const int maxQuantumSamples_;
    std::atomic<int> writeAhead_{0};
    std::atomic<bool> realtime_{false};
    // Both written on the fill path without mutex_ — a contended fill is exactly the one that
    // could not take it — so stats() and the next fill read them atomically.
    std::atomic<uint64_t> contendedFills_{0};
    std::atomic<int32_t> lastLive_{0};
#ifdef DUMBLE_TESTING
    std::atomic<bool> heldForTest_{false};
#endif

    std::mutex mutex_;
    Bitmap slots_;
    int32_t sessions_[kMaxSpeakers] = {};
    // Samples of silence a claimed slot has answered with in a row. Zeroed on claim.
    int idleSamples_[kMaxSpeakers] = {};
    // Whether the slot produced in the last fill. Written in the commit phase and read by stats(),
    // both under mutex_. Zeroed on claim.
    bool audible_[kMaxSpeakers] = {};
    // Samples a claimed slot has starved mid-spurt, and so the hold's clock. Cleared only by real
    // audio, never by a fill that merely did not conceal: an expired hold would otherwise clear
    // its own counter and start concealing again on the next fill, forever. Keeps counting past
    // kConcealSamples for the same reason. Zeroed on claim, like idleSamples_.
    int stallSamples_[kMaxSpeakers] = {};
    // Samples produced since this slot last shrank. Counted only on fills that produced, which is
    // what makes kShrinkCooldownSamples a real two seconds.
    int shrinkSamples_[kMaxSpeakers] = {};
    // Whole-engine totals, monotonic since construction. A retiring queue's own tally is
    // harvested into droppedPackets_ before reset() clears it.
    int64_t concealedGaps_ = 0;
    int64_t droppedPackets_ = 0;
    int64_t shrunkPackets_ = 0;
    int64_t catchUpPackets_ = 0;
    int64_t lostSamples_ = 0;
    int64_t outOfOrderPackets_ = 0;
    // Fill timing since the last stats() read. Written in the commit phase and read and zeroed
    // by stats(), both under mutex_.
    uint64_t fillMicrosSum_ = 0;
    uint64_t fillMicrosMax_ = 0;
    uint64_t fillCount_ = 0;
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
