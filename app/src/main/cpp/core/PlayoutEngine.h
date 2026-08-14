#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/PlayoutConstants.h"
#include "core/SlotSet.h"
#include "core/SpeakerQueue.h"

namespace dumble::playout {

/**
 * Owns inbound voice below the platform: one SpeakerQueue per sender, and one mixed quantum per
 * fillQuantum call. Platform-free — no JNI, no output device.
 *
 * fillQuantum is deliberately shaped as an audio data callback: non-blocking, allocation-free, and
 * taking its frame count per call. Today a Kotlin playback thread drives it between AudioTrack
 * writes; an Oboe callback will call the same function unchanged.
 */
class PlayoutEngine {
public:
    /** Null when libopus cannot be reached at all, when maxSpeakers exceeds the one machine word
     *  of occupancy SlotSet holds — a mismatch with the caller's array sizing, caught here rather
     *  than as an out-of-bounds write at 100 Hz — or when maxQuantumSamples is outside
     *  (0, kMaxFrameSamples], which is what the per-speaker buffer sizing can represent. */
    static std::unique_ptr<PlayoutEngine> create(int sampleRate, int maxQuantumSamples,
                                                 int maxSpeakers);

    /** Reader thread. Returns one of the kOffer* codes. `data` may be null when `len` is 0; a
     *  payload-free terminator is accepted, since it prices to nothing by definition. */
    int offer(int32_t session, const uint8_t* data, int len, bool terminator);

    /**
     * Playback thread. Writes exactly `frames` samples of mixed audio into `out`. Returns how many
     * speakers produced audio and writes their sessions into `sessions[0..n)`, which must hold
     * maxSpeakers entries. `liveSpeakers` receives the claimed-slot count, which is how the caller
     * distinguishes "nobody is here" from "somebody is prebuffering" — always written, including
     * on refusal. `frames` must not exceed the maxQuantumSamples given to create(); outside that
     * range this returns kErrorBufferTooSmall rather than a silent quantum.
     */
    int fillQuantum(int16_t* out, int frames, int32_t* sessions, int32_t* liveSpeakers);

    /** Any thread. Returns the live speaker count, filling `sessions` and `depths` to match and
     *  `counters` with kCounterCount monotonic values. */
    int readStats(int32_t* sessions, int32_t* depths, int64_t* counters);

private:
    PlayoutEngine(int sampleRate, int maxQuantumSamples, int maxSpeakers);

    /** Mutex held. Slot for this session, claiming and building one if needed; -1 when the cap is
     *  reached, -2 when a decoder could not be built. */
    int slotFor(int32_t session);

    const int sampleRate_;
    const int maxQuantumSamples_;
    const int maxSpeakers_;

    std::mutex mutex_;
    SlotSet slots_;
    int32_t sessions_[SlotSet::kCapacity] = {};
    std::unique_ptr<SpeakerQueue> queues_[SlotSet::kCapacity];
    int64_t concealedTicks_ = 0;
    int64_t droppedPackets_ = 0;

    // Playback-thread-only scratch, sized once at construction so no tick allocates.
    std::vector<int32_t> accumulator_;
    std::vector<int16_t> speakerOut_;
    std::vector<uint8_t> packetScratch_;
};

}  // namespace dumble::playout
