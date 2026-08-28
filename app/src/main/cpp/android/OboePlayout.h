#pragma once
#include <oboe/LatencyTuner.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include "core/PlayoutEngine.h"

namespace dumble {

/**
 * The only Android-aware piece of the playout path: owns the Oboe output stream and pulls each
 * burst from PlayoutEngine. The mirror of OboeCapture, which argues the ownership shape (a shared
 * Callbacks object, a weak owner), the reopen backoff and the mono-I16 refusal; those arguments
 * are not repeated here.
 *
 * Two things capture does not have. wantStarted_: the receiver's poll pauses the stream around
 * a hold, and a reopen after a disconnect must land in the state the poll last asked for. And
 * the tuner: per stream, rebuilt in open(), because LatencyTuner binds to one AudioStream& and
 * the callbacks object outlives reopens.
 */
class OboePlayout {
public:
    static std::shared_ptr<OboePlayout> create(std::shared_ptr<playout::PlayoutEngine> engine);
    ~OboePlayout() { close(); }

    /** Open, and start if wanted. Not terminal on failure: start() tries again. */
    oboe::Result open();
    void close();

    /** Poll thread only. True when a started stream exists on return. Idempotent and cheap while
     *  one runs, so the poll calls it every interval: that is what brings a stream back after a
     *  reopen gave up, or after an error that was not a disconnect. Opens at most once a second
     *  when there is no stream — the reopen thread has already backed off and failed, and the
     *  device state that decides the next attempt does not change in 50 ms. */
    bool start();
    void pause();

    // Diagnostics, any thread; take streamMutex_ for the reason OboeCapture's do.
    int32_t xRunCount() const;
    /** -1 when there is no started stream to measure. */
    double latencyMillis() const;
    int32_t framesPerBurst() const;
    int32_t bufferSizeInFrames() const;

private:
    explicit OboePlayout(std::shared_ptr<playout::PlayoutEngine> engine)
        : engine_(std::move(engine)) {}

    struct Callbacks : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
        Callbacks(std::shared_ptr<playout::PlayoutEngine> e, std::weak_ptr<OboePlayout> o)
            : engine(std::move(e)), owner(std::move(o)) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                              int32_t numFrames) override;
        void onErrorBeforeClose(oboe::AudioStream*, oboe::Result) override;
        void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

        const std::shared_ptr<playout::PlayoutEngine> engine;
        const std::weak_ptr<OboePlayout> owner;
        // fillQuantum's two outputs, which nothing reads: the poll takes the audible set from
        // stats() instead, under the engine's own lock.
        int32_t sessions[playout::kMaxSpeakers] = {};
        int32_t live = 0;
        // Touched by the realtime thread once the stream is started; rebuilt by open() before
        // requestStart, when no callback can be running for either stream.
        std::unique_ptr<oboe::LatencyTuner> tuner;
        int32_t lastBufferSize = 0;
    };

    void logActualConfig(const char* phase, int64_t startMicros,
                         const std::shared_ptr<oboe::AudioStream>& stream);
    void retryReopen();

    const std::shared_ptr<playout::PlayoutEngine> engine_;
    std::shared_ptr<Callbacks> callbacks_;

    // Serialises open() against itself and against close()'s swap: unlike capture, open() has
    // two callers — the poll's start() and the reopen thread — that can overlap, and two opens
    // would both publish a stream and rebuild the tuner under a live callback. Taken before
    // streamMutex_, never after.
    std::mutex openMutex_;
    mutable std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;

    std::mutex retryMutex_;
    std::condition_variable retryCondition_;
    int retriesInProgress_ = 0;

    std::atomic<bool> stopping_{false};
    std::atomic<bool> wantStarted_{false};
    // requestStart succeeded on the stream in stream_. Cleared wherever stream_ is, and by pause().
    std::atomic<bool> streamStarted_{false};
    // Poll thread only: the earliest a start() with no stream may open again.
    int64_t nextOpenMicros_ = 0;
};

}  // namespace dumble
