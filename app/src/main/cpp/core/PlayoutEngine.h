#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/PacketQueue.h"
#include "core/PlayoutConstants.h"
#include "core/SlotSet.h"
#include "core/SpeakerDecoder.h"

namespace dumble::playout {

/**
 * Owns inbound voice below the platform: one PacketQueue and one SpeakerDecoder per sender, and
 * one mixed quantum per fillQuantum call. Platform-free — no JNI, no output device.
 *
 * The two halves are separate types because they answer to different threads. A packet queue is
 * touched only under mutex_; a decoder only from the playback thread, with mutex_ released. This
 * engine is the one place that holds both, and the pop-then-decode loop in fillQuantum is where
 * they meet — one lock acquisition per packet, and no decode inside it.
 *
 * Retirement lives here rather than in either half: a slot is this class's to claim and release,
 * and neither half alone can tell a speaker that has stopped talking from one still waiting out
 * its prebuffer.
 *
 * fillQuantum is deliberately shaped as an audio data callback: non-blocking, allocation-free, and
 * taking its sample count per call. Today a Kotlin playback thread drives it between AudioTrack
 * writes; an Oboe callback will call the same function unchanged.
 */
class PlayoutEngine {
public:
    /** Null when maxSpeakers exceeds the one machine word of occupancy SlotSet holds — a mismatch
     *  with the caller's array sizing, caught here rather than as an out-of-bounds write at 100 Hz
     *  — or when a speaker could not be built at all, which covers both libopus being unreachable
     *  and maxQuantumSamples being outside what the per-speaker buffer sizing can represent. */
    static std::unique_ptr<PlayoutEngine> create(int sampleRate, int maxQuantumSamples,
                                                 int maxSpeakers);

    /** Reader thread. Returns one of the kOffer* codes. `data` may be null when `len` is 0; a
     *  payload-free terminator is accepted, since it carries no samples by definition. */
    int offer(int32_t session, const uint8_t* data, int len, bool terminator);

    /**
     * Playback thread. Writes exactly `samples` of mixed audio into `out`. Returns how many
     * speakers produced audio and writes their sessions into `sessions[0..n)`, which must hold
     * maxSpeakers entries. `liveSpeakers` receives the claimed-slot count, which is how the caller
     * distinguishes "nobody is here" from "somebody is prebuffering" — always written, including
     * on refusal. `samples` must not exceed the maxQuantumSamples given to create(); outside that
     * range this returns kErrorBufferTooSmall rather than a silent quantum.
     */
    int fillQuantum(int16_t* out, int samples, int32_t* sessions, int32_t* liveSpeakers);

private:
    PlayoutEngine(int sampleRate, int maxQuantumSamples, int maxSpeakers);

    /** Mutex held. Slot for this session, claiming and building one if needed; -1 when the cap is
     *  reached, -2 when a speaker could not be built. */
    int slotFor(int32_t session);

    const int sampleRate_;
    const int maxQuantumSamples_;
    const int maxSpeakers_;

    std::mutex mutex_;
    SlotSet slots_;
    int32_t sessions_[SlotSet::kCapacity] = {};
    std::unique_ptr<PacketQueue> queues_[SlotSet::kCapacity];
    std::unique_ptr<SpeakerDecoder> decoders_[SlotSet::kCapacity];
    // Consecutive ticks a claimed slot has produced nothing. Reset on claim, since slots are
    // reused and a stale count would retire a new speaker early.
    int idleTicks_[SlotSet::kCapacity] = {};

    // Playback-thread-only scratch, sized once at construction so no tick allocates.
    std::vector<int32_t> accumulator_;
    std::vector<int16_t> speakerOut_;
    std::vector<uint8_t> packetScratch_;
};

}  // namespace dumble::playout
