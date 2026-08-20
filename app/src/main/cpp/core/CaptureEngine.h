#pragma once
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/PacketAssembler.h"
#include "core/AudioEncoder.h"
#include "core/PcmRing.h"
#include "core/VoiceActivity.h"

namespace dumble {

/** Which producer decides that audio goes out. Nothing sets VoiceActivity until PR 3. */
enum class TransmitMode { PushToTalk, VoiceActivity };

/**
 * Owns everything between "PCM arrived" and "an Opus packet is ready". Platform-free: no JNI, no
 * Oboe, no sockets. onPcm() is called from the audio callback; pollPacket() from a pump thread that
 * is allowed to block.
 */
class CaptureEngine {
public:
    /** Null if the encoder could not be created — there is no degraded mode.
     *  Null or malformed `weights` only disables voice activity; push-to-talk still works. */
    static std::unique_ptr<CaptureEngine> create(int sampleRate, int packetSamples, int bitrate,
                                                 const void* weights, size_t weightBytes);

    /** Any thread. Falls back to PushToTalk when voice activity is unavailable. */
    void setTransmitMode(TransmitMode mode);

    bool voiceActivityAvailable() const { return voiceActivity_ != nullptr; }

    /** Audio-callback thread. Lock-free, allocation-free. While gated, samples advance the
     *  frame_number clock but never enter the ring. */
    void onPcm(const int16_t* pcm, uint32_t n);

    /** Any thread. Close owes a terminator; open starts a new spurt or, under push-to-talk,
     *  merges with the previous one if its terminator has not shipped yet. Under voice activity
     *  a reopen never merges: the close already reset the detector. */
    void setGateOpen(bool open);

    /** Pump thread. Blocks up to the wait interval. Returns byte count, kPollRetry, or
     *  kPollShutdown. 0 means nothing to send (may return immediately after queueing a burst). */
    int pollPacket(uint8_t* out, int outCap, uint64_t* frameNumber, uint32_t* flags);

    /** Wakes a parked pump thread. Nothing else can: Thread.interrupt cannot reach a condvar. */
    void requestShutdown();

    /** Set by the Oboe error callback around a disconnect/reopen cycle. */
    void setStreamDown(bool down);

    /** Terminal: platform adapter exhausted reopen attempts. pollPacket() returns
     *  kPollUnavailable instead of kPollRetry. */
    void setStreamUnavailable();

    // One write is one Oboe burst; a dropped burst at capture is an overrun.
    uint64_t overrunBursts() const { return ring_.droppedWrites(); }
    uint64_t skippedSamples() const { return ring_.skippedSamples(); }
    // Test-only: verifies voice-activity drains the ring even while silent.
    uint32_t bufferedSamples() const { return ring_.available(); }
    uint64_t encodedPackets() const { return encodedPackets_.load(std::memory_order_relaxed); }
    // Without this, a broken encoder and an idle gate both look like pollPacket returning 0.
    uint64_t encodeErrors() const { return encodeErrors_.load(std::memory_order_relaxed); }
    // Test-only: verifies one reset per burst, not per held packet.
    uint64_t encoderResets() const { return encoderResets_.load(std::memory_order_relaxed); }

    // Encode cost against the 20 ms packet budget. Mean hides spikes, max hides frequency.
    uint64_t encodeMicrosMean() const {
        const uint64_t n = encodeCount_.load(std::memory_order_relaxed);
        return n == 0 ? 0 : encodeMicrosSum_.load(std::memory_order_relaxed) / n;
    }
    uint64_t encodeMicrosMax() const { return encodeMicrosMax_.load(std::memory_order_relaxed); }

    void setWaitMillisForTest(int ms) { waitMillis_ = ms; }
    // Pump thread only, like historyCount_ itself — safe to call between polls in a single-threaded
    // test, not a general-purpose accessor.
    int heldPacketsForTest() const { return historyCount_; }

private:
    CaptureEngine(int sampleRate, int packetSamples, std::unique_ptr<AudioEncoder> encoder,
                 std::unique_ptr<VoiceActivity> voiceActivity);

    void wakeup();

    /** OR-fold of the two frames in a packet. Terminator rides a real packet, never empty. */
    struct PacketDecision {
        bool transmit = false;
        bool closing = false;
    };
    PacketDecision judgePacket(const int16_t* packet);

    // Flushes buffered audio as one zero-padded terminator packet and resets the ring. Used by
    // gate close and detector reset — the two paths that orphan buffered audio. A detector's own
    // close rides the ordinary packet instead (decision.closing).
    void flushTerminator(uint64_t clockOffset, uint64_t* candidateFrameNumber);

    PcmRing ring_{kRingCapacitySamples};
    PacketAssembler assembler_;
    // Non-null for the object's whole life: create() is the only way in and it refuses without one.
    const std::unique_ptr<AudioEncoder> encoder_;
    // Null when the weight blob was absent or malformed. Touched only by the pump thread.
    const std::unique_ptr<VoiceActivity> voiceActivity_;
    std::atomic<TransmitMode> transmitMode_{TransmitMode::PushToTalk};
    std::vector<int16_t> scratch_;

    // Where the pump parks between polls; wakeup() is the only thing that ends a wait early.
    std::mutex wakeMutex_;
    std::condition_variable wakeCondition_;

    // Commanded arming state. Written only by setGateOpen() under spurtMutex_; read lock-free
    // by onPcm() and pollPacket(). Single writer keeps the repeat guard and the audio gate in sync.
    std::atomic<bool> gateOpen_{false};
    std::atomic<bool> shutdown_{false};
    std::atomic<bool> streamDown_{false};
    std::atomic<bool> streamUnavailable_{false};
    std::atomic<uint64_t> encodedPackets_{0};
    std::atomic<uint64_t> encodeErrors_{0};
    std::atomic<uint64_t> encoderResets_{0};
    std::atomic<uint64_t> encodeCount_{0};
    std::atomic<uint64_t> encodeMicrosSum_{0};
    std::atomic<uint64_t> encodeMicrosMax_{0};
    // Every sample delivered to onPcm(), gate or not. Drives frame_number: closed-gate pauses
    // appear as elapsed time at the receiver (except a merged push-to-talk reopen).
    std::atomic<uint64_t> sampleClock_{0};

    // Guards the fields below. Gate transitions are human-rate, so a mutex is fine.
    std::mutex spurtMutex_;
    // A terminator tells receivers the speaker stopped, so they can drop decoder state. This
    // tracks an owed terminator that has not shipped yet. Separate from gateOpen_ to
    // avoid the state collapse that once turned a mute into a no-op. Only strengthens until claimed:
    //   Mergeable — push-to-talk release; a re-press cancels it (merges the spurts).
    //   Firm      — voice-activity mute or detector reset; nothing cancels it.
    enum class TerminatorDebt { None, Mergeable, Firm };
    TerminatorDebt terminatorDebt_ = TerminatorDebt::None;
    // Snapshotted at debt creation so a reopen's new clockOffset_ does not corrupt the terminator.
    uint64_t debtClockOffset_ = 0;
    // Ring-to-clock translation: sampleClock_ − writeIndex() at spurt open. Converts any ring
    // position to wall-clock frame_number. Under voice activity, overrun-dropped writes make
    // this slightly lag wall clock (bounded by overrunBursts(), not spurt length).
    uint64_t clockOffset_ = 0;

    // Set on any thread, claimed by the pump before the spurt's first encode. Atomic rather than
    // under spurtMutex_ because the pump also raises it at a voice-activity opening edge.
    std::atomic<bool> resetEncoderPending_{false};
    // Set on any arming transition, mode change, or stream recovery; claimed by the pump.
    std::atomic<bool> resetDetectorPending_{false};

    // Floor for the next frame_number — see pollPacket() for the clamping rationale.
    uint64_t frameNumber_ = 0;
    const uint64_t frameNumberStep_;
    int waitMillis_ = kPollWaitMillis;

    // True while a spurt is on the wire. The close path reads it to decide whether a mute owes
    // a terminator. Guarded by spurtMutex_ for the cross-thread read from setGateOpen().
    bool transmittingSpurt_ = false;

    // Silent packets held for preroll. PCM, not encoded: Opus is predictive, so encoding now
    // would mismatch the predictor state when they go out in burst order. Fixed storage — the
    // pump thread does not allocate.
    struct HeldPacket {
        int16_t pcm[kTxPacketSamples];
        uint64_t candidateFrameNumber;
    };
    // +1: the onset packet transits through history on its way into the burst. Without it the
    // burst back-fills only 40 ms against the detector's 40 ms blind spot — zero margin.
    static constexpr int kHistorySlots = kPrerollPackets + 1;
    HeldPacket history_[kHistorySlots];
    int historyCount_ = 0;      // entries held, <= kHistorySlots
    int historyOldest_ = 0;     // ring index of the oldest held entry
    int burstRemaining_ = 0;    // held entries still to emit

    void holdPacket(const int16_t* pcm, uint64_t candidateFrameNumber);
    void clearHistory() {
        historyCount_ = 0; historyOldest_ = 0; burstRemaining_ = 0;
    }
};

}  // namespace dumble
