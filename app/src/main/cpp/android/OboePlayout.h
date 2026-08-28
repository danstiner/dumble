#pragma once
#include <oboe/LatencyTuner.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <chrono>
#include <memory>
#include "core/PlayoutEngine.h"

namespace dumble {

/**
 * The only Android-aware piece of the playout path: owns the Oboe output stream and pulls each
 * burst from PlayoutEngine. Like OboeCapture in the stream it asks for and the mono-I16 refusal;
 * unlike it in ownership. Capture is shared-owned so a stream error, which Oboe delivers on a
 * detached thread it never joins, can reach the engine after the session is gone. Here the error
 * callbacks touch nothing but the Callbacks object — which Oboe keeps alive for as long as it
 * can call it — and the receiver's poll, calling start() every interval, is what notices the
 * dead stream, tells the engine, and opens another. So this object and the engine have plain
 * lifetimes: the engine outlives the adapter, and once close() returns no callback runs against
 * either.
 *
 * The tuner is per stream, rebuilt in open(), because LatencyTuner binds to one AudioStream& and
 * the callbacks object outlives reopens.
 *
 * No lock: one coroutine — the receiver's poll — makes every call that touches the stream, in
 * sequence, and the receiver joins it before destroying this. That is the whole of what Oboe
 * asks (no close, read, write or waitForStateChange from two threads at once), and a mutex here
 * would only pretend to cover a second caller that does not exist.
 */
class OboePlayout {
public:
    /** [engine] must outlive this object. */
    explicit OboePlayout(playout::PlayoutEngine& engine);
    ~OboePlayout() { close(); }

    /** Open without starting; not terminal on failure, start() opens again. Called once before
     *  the poll exists and then only from start(). */
    oboe::Result open();
    void close();

    /** Poll coroutine only. True when a started stream exists on return. Idempotent and cheap while
     *  one runs, so the poll calls it every interval: a stream Oboe closed after an error is
     *  noticed here, the engine told, and a new one opened — at most once a second when opening
     *  fails, since the device state that decides the next attempt does not change in 50 ms. */
    bool start();
    void pause();

    // Diagnostics, poll coroutine only.
    /** Bursts the callback did not fill in time and the device played as silence — Oboe's
     *  getXRunCount, which for an output stream can only mean underruns. */
    int32_t underruns() const;
    /** -1 when there is no started stream to measure. */
    double latencyMillis() const;

private:
    struct Callbacks : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
        explicit Callbacks(playout::PlayoutEngine& e) : engine(e) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                              int32_t numFrames) override;
        void onErrorBeforeClose(oboe::AudioStream*, oboe::Result) override;

        // Only the data callback reaches the engine, and close() stops that before it returns.
        playout::PlayoutEngine& engine;
        // fillQuantum's two outputs, which nothing reads: the poll takes the audible set from
        // stats() instead, under the engine's own lock.
        int32_t sessions[playout::kMaxSpeakers] = {};
        int32_t live = 0;
        // Touched by the realtime thread once the stream is started; rebuilt by open() before
        // requestStart, when no callback can be running for either stream.
        std::unique_ptr<oboe::LatencyTuner> tuner;
        int32_t lastBufferSize = 0;
        // Set by the error callback, on Oboe's thread; consumed by start() on the poll's.
        std::atomic<bool> streamDead{false};
    };

    void logActualConfig(const char* phase, std::chrono::steady_clock::time_point started,
                         const std::shared_ptr<oboe::AudioStream>& stream);

    playout::PlayoutEngine& engine_;
    // Shared with Oboe, which holds it for every stream it is set on. Not shared with anything
    // of ours.
    const std::shared_ptr<Callbacks> callbacks_;

    // shared_ptr because openStream() hands out nothing else; no one but this object holds it.
    std::shared_ptr<oboe::AudioStream> stream_;

    bool streamStarted_ = false;
    std::chrono::steady_clock::time_point nextOpenAt_{};
};

}  // namespace dumble
