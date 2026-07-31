#pragma once
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/FrameAssembler.h"
#include "core/AudioEncoder.h"
#include "core/PcmRing.h"

namespace dumble {

/**
 * Owns everything between "PCM arrived" and "an Opus packet is ready". Platform-free: no JNI, no
 * Oboe, no sockets. onPcm() is called from the audio callback; pollFrame() from a pump thread that
 * is allowed to block.
 */
class CaptureEngine {
public:
    /** Null if the encoder could not be created. There is no useful degraded mode — an engine
     *  without an encoder produces nothing forever — so the failure surfaces once, here, instead
     *  of as frames that silently never encode. */
    static std::unique_ptr<CaptureEngine> create(int sampleRate, int frameSamples, int bitrate);

    /** Audio-callback thread. Lock-free, allocation-free. The gate lives here: while closed,
     *  samples are counted for the frame_number clock but never captured into the ring. */
    void onPcm(const int16_t* pcm, uint32_t n);

    /** Any thread. Opening starts a new spurt — or rejoins the one just closed, if its terminator
     *  has not been sent yet. Closing owes the pump one terminator. */
    void setGateOpen(bool open);

    /**
     * Pump thread. Blocks up to the wait interval. Returns a byte count, or kPollRetry while the
     * stream is down, or kPollShutdown once shutdown is requested. A return of 0 means the wait
     * elapsed with nothing to send — the caller loops.
     */
    int pollFrame(uint8_t* out, int outCap, uint64_t* frameNumber, uint32_t* flags);

    /** Wakes a parked pump thread. Nothing else can: Thread.interrupt cannot reach a condvar. */
    void requestShutdown();

    /** Set by the Oboe error callback around a disconnect/reopen cycle. */
    void setStreamDown(bool down);

    // The ring counts dropped writes in its own vocabulary; this is the layer that knows one
    // write is one Oboe burst, and that a dropped burst at capture is an overrun.
    uint64_t overrunBursts() const { return ring_.droppedWrites(); }
    uint64_t skippedSamples() const { return ring_.skippedSamples(); }
    uint64_t encodedPackets() const { return encodedPackets_.load(std::memory_order_relaxed); }
    // Distinguishes a persistent libopus failure from an idle gate: pollFrame() returns 0 for
    // both, so without this a broken encoder is silent and undiagnosable from the outside.
    uint64_t encodeErrors() const { return encodeErrors_.load(std::memory_order_relaxed); }

    void setWaitMillisForTest(int ms) { waitMillis_ = ms; }

private:
    CaptureEngine(int sampleRate, int frameSamples, std::unique_ptr<AudioEncoder> encoder);

    void wakeup();

    PcmRing ring_{kRingCapacitySamples};
    FrameAssembler assembler_;
    // Non-null for the object's whole life: create() is the only way in and it refuses without one.
    const std::unique_ptr<AudioEncoder> encoder_;
    std::vector<int16_t> scratch_;

    // Where the pump parks between polls; wakeup() is the only thing that ends a wait early.
    std::mutex wakeMutex_;
    std::condition_variable wakeCondition_;

    std::atomic<bool> gateOpen_{false};
    std::atomic<bool> shutdown_{false};
    std::atomic<bool> streamDown_{false};
    std::atomic<uint64_t> encodedPackets_{0};
    std::atomic<uint64_t> encodeErrors_{0};
    // The frame_number wall clock, in samples: every sample the device has ever delivered to
    // onPcm(), counted unconditionally, monotonically increasing — the gate stops only the ring
    // writes, never this. Deliberately not named "captured": captured means written to the ring,
    // and gate-closed samples never are. The frame_number a resumed spurt carries derives from
    // this clock, so closed-gate pauses show up as elapsed time at the receiver — except a pause
    // the merge path absorbs (reopen before the terminator went out), which is bounded by pump
    // latency: one poll interval in practice.
    std::atomic<uint64_t> sampleClock_{0};

    // Guards the fields below. setGateOpen() runs on whatever thread owns the push-to-talk
    // control; pollFrame() claims the owed terminator on the pump thread. A gate cycle is a rare,
    // human-triggered event, not onPcm()'s hot audio-callback path, so a mutex here costs nothing
    // that matters, and it keeps the gate state and the clock offset consistent with each other.
    std::mutex spurtMutex_;
    // The commanded gate state — the guard's own record, only ever touched under spurtMutex_;
    // gateOpen_ is its lock-free mirror for onPcm() and pollFrame(). An enum rather than a pair
    // of bools so that open-while-owed is unrepresentable, a repeated press or release is visibly
    // a state mapping to itself, and the clock offset below is meaningful exactly when the state
    // is not Closed. Closed means closed *and settled*; TerminatorOwed is the other closed state,
    // still owing the wire its terminator.
    //
    // TerminatorOwed carries no span and no queue: the gate in onPcm() means the ring never holds
    // gate-closed audio, so a close needs no boundary indices — whatever is buffered when the
    // pump claims the debt is the spurt's own tail. Reopening from TerminatorOwed cancels it and
    // keeps the offset: the presses merge into one continuous transmission, as desktop Mumble's
    // frame-granular gate merges a release and re-press inside one frame.
    enum class GateState { Closed, Open, TerminatorOwed };
    GateState gateState_ = GateState::Closed;
    // The spurt's ring-to-clock translation: sampleClock_ minus ring_.writeIndex(), both read at
    // the instant the spurt opened — that is, every sample delivered but never captured before
    // it. Adding it to any ring position inside the spurt yields that sample's clock time. Ring
    // indices stand still while the gate is closed — onPcm() writes nothing — so ring position
    // alone understates wall time by every closed gap; this offset is what carries the clock
    // across them, spurt to spurt. Never negative: ring-accepted samples are a strict subset of
    // clock-counted ones (a dropped write advances neither index), and the translation stays
    // exact within a spurt because the ring, continuously drained while open, does not overflow
    // mid-spurt.
    uint64_t clockOffset_ = 0;

    // Pump thread only. The floor for the next packet's frame number, not a running count: see
    // pollFrame() for why a candidate below this floor is clamped up rather than used as-is.
    uint64_t frameNumber_ = 0;
    const uint64_t frameNumberStep_;
    int waitMillis_ = kPollWaitMillis;
};

}  // namespace dumble
