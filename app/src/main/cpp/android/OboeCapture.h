#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <chrono>
#include <memory>
#include "core/CaptureEngine.h"

namespace dumble {

/**
 * The only Android-aware piece of the capture path: owns the Oboe input stream and hands each
 * burst to CaptureEngine. Swapping Oboe for another backend means replacing this file and nothing
 * in core/.
 *
 * The pump thread owns the stream, as the receiver's poll owns playout's: it is the only caller
 * of start(), close() and the diagnostics, in sequence, which is all Oboe's thread-safety
 * contract asks — so there is no lock. The error callback runs on a thread Oboe detaches and
 * never joins, possibly after the session is gone, so it touches only the Callbacks object,
 * which Oboe keeps alive, and through it the engine, which Callbacks holds strongly for exactly
 * that reason.
 */
class OboeCapture {
public:
    explicit OboeCapture(std::shared_ptr<CaptureEngine> engine);
    ~OboeCapture() { close(); }

    /** True when a started stream exists on return. Cheap while one runs. A stream Oboe closed
     *  after an error is noticed here, dropped, and another opened — at most once a second, since
     *  the device state that decides the next attempt does not change faster. Below API 37 a
     *  disconnect lands on every routed-device change, so this is the normal path. */
    bool start();
    void close();

    /** Bursts the callback did not consume in time, which the device overwrote — Oboe's
     *  getXRunCount, which for an input stream can only mean overruns. 0 with no stream. */
    int32_t streamOverruns() const;
    int32_t framesPerBurst() const;

private:
    struct Callbacks : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
        explicit Callbacks(std::shared_ptr<CaptureEngine> e) : engine(std::move(e)) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                              int32_t numFrames) override;
        void onErrorBeforeClose(oboe::AudioStream*, oboe::Result) override;

        const std::shared_ptr<CaptureEngine> engine;
        // Set by the error callback, on Oboe's thread; consumed by start() on the pump's.
        std::atomic<bool> streamDead{false};
    };

    oboe::Result open();
    void logActualConfig(std::chrono::steady_clock::time_point started,
                         const std::shared_ptr<oboe::AudioStream>& stream);

    // Shared with Oboe, which holds it for every stream it is set on. Not shared with anything
    // of ours.
    const std::shared_ptr<Callbacks> callbacks_;
    // shared_ptr because openStream() hands out nothing else; no one but this object holds it.
    std::shared_ptr<oboe::AudioStream> stream_;
    std::chrono::steady_clock::time_point nextOpenAt_{};
};

}  // namespace dumble
