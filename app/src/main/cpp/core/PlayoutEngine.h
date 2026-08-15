#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/PacketQueue.h"
#include "core/PlayoutConstants.h"
#include "core/Bitmap.h"
#include "core/SpeakerDecoder.h"

namespace dumble::playout {

static_assert(kMaxSpeakers <= Bitmap::kCapacity,
              "occupancy is one uint64_t, so kMaxSpeakers cannot exceed its width");

/**
 * Owns inbound voice below the platform: one PacketQueue and one SpeakerDecoder per sender, mixed
 * into one quantum per fillQuantum call. Platform-free — no JNI, no output device.
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
     *  terminator is accepted, since it carries no samples by definition. */
    int offer(int32_t session, const uint8_t* data, int len, bool terminator);

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
     * silent quantum, and leaves `out` untouched: the caller must not play a refused tick.
     */
    int fillQuantum(int16_t* out, int samples, int32_t* sessions, int32_t* liveSpeakers);

private:
    PlayoutEngine(int sampleRate, int maxQuantumSamples);

    /** Any thread; caller already holds mutex_. Slot for this session, claiming a free one if
     *  needed; -1 at the cap. */
    int slotFor(int32_t session);

    const int sampleRate_;
    const int maxQuantumSamples_;

    std::mutex mutex_;
    Bitmap slots_;
    int32_t sessions_[kMaxSpeakers] = {};
    // Consecutive ticks a claimed slot has produced nothing. Zeroed on claim.
    int idleTicks_[kMaxSpeakers] = {};
    // Built by create() and never rebuilt, which is what lets the playback thread hold references
    // into them across an unlocked decode.
    PacketQueue queues_[kMaxSpeakers];
    std::unique_ptr<SpeakerDecoder> decoders_[kMaxSpeakers];

    // Playback-thread-only scratch, sized once so no tick allocates.
    std::vector<int32_t> accumulator_;
    std::vector<int16_t> speakerOut_;
    uint8_t packetScratch_[kMaxPacketBytes] = {};
};

}  // namespace dumble::playout
