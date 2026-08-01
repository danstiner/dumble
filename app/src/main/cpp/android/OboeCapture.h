#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include "core/CaptureEngine.h"

namespace dumble {

/**
 * The only Android-aware piece of the capture path. Owns the Oboe stream and translates its
 * callbacks into CaptureEngine calls. Swapping Oboe for another backend means replacing this file
 * and nothing in core/.
 *
 * Shared-owned, because Oboe outlives us: it delivers stream errors on a detached thread it never
 * joins, so a disconnect landing at the same moment as stop() would otherwise run callbacks
 * against freed memory. See Callbacks below.
 */
class OboeCapture {
public:
    static std::shared_ptr<OboeCapture> create(std::shared_ptr<CaptureEngine> engine);
    ~OboeCapture() { close(); }

    oboe::Result open();
    void close();

private:
    explicit OboeCapture(std::shared_ptr<CaptureEngine> engine) : engine_(std::move(engine)) {}

    /**
     * What Oboe calls into. Separate from OboeCapture so it can be handed over as a shared_ptr:
     * the raw-pointer setters are deprecated precisely because "the errorCallback object might get
     * deleted by the app while it is being used", and OboeCapture itself cannot be the one shared
     * — it holds the stream, the stream would hold it back, and nothing would ever break the
     * cycle. The reference back to the capture is therefore weak, which is also the guard: if the
     * session is gone, lock() fails and the callback does nothing.
     */
    struct Callbacks : oboe::AudioStreamDataCallback, oboe::AudioStreamErrorCallback {
        Callbacks(std::shared_ptr<CaptureEngine> e, std::weak_ptr<OboeCapture> o)
            : engine(std::move(e)), owner(std::move(o)) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* audioData,
                                              int32_t numFrames) override;
        void onErrorBeforeClose(oboe::AudioStream*, oboe::Result) override;
        void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override;

        // Strong, unlike owner: onAudioReady is the realtime callback and must not lock() a
        // weak_ptr to reach the engine it writes into. Holding the engine directly keeps it alive
        // for exactly as long as Oboe keeps these callbacks alive, and costs the hot path nothing.
        const std::shared_ptr<CaptureEngine> engine;
        const std::weak_ptr<OboeCapture> owner;
    };

    void logActualConfig(const char* phase, int64_t startMicros,
                          const std::shared_ptr<oboe::AudioStream>& stream);
    // Exponential-backoff reopen loop, run on the thread Oboe creates to deliver
    // onErrorAfterClose — documented as safe to block/sleep on, unlike onAudioReady's thread.
    void retryReopen();

    const std::shared_ptr<CaptureEngine> engine_;
    // Built once by create(), then handed to every stream this object opens.
    std::shared_ptr<Callbacks> callbacks_;

    // Guards stream_ only — never onAudioReady's hot path, which doesn't touch it. open() (app
    // thread via start(), or the retry loop's own thread) and close() (app thread via stop())
    // both read and write it, with nothing else serializing them.
    std::mutex streamMu_;
    std::shared_ptr<oboe::AudioStream> stream_;

    // Guards retriesInProgress_ and backs the interruptible backoff sleep in retryReopen(). Also
    // what gives close() a definite point after which no reopen attempt is running or will start:
    // without it, close() could return leaving a retry loop about to open a fresh stream.
    std::mutex retryMu_;
    std::condition_variable retryCv_;
    int retriesInProgress_ = 0;   // guarded by retryMu_; supports overlapping retry sequences

    std::atomic<bool> stopping_{false};   // latched by close(); never cleared
};

}  // namespace dumble
