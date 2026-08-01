#include "android/OboeCapture.h"
#include <android/log.h>
#include <chrono>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OboeCapture", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OboeCapture", __VA_ARGS__)

namespace dumble {
namespace {
int64_t nowMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}
constexpr int kMaxReopenFailures = 5;
}  // namespace

std::shared_ptr<OboeCapture> OboeCapture::create(std::shared_ptr<CaptureEngine> engine) {
    // Not make_shared: the constructor is private, and the one extra allocation happens once per
    // session. The callbacks have to be built here rather than in the constructor because they
    // hold a weak_ptr back, which needs the shared_ptr to already exist.
    std::shared_ptr<OboeCapture> capture(new OboeCapture(std::move(engine)));
    capture->callbacks_ = std::make_shared<Callbacks>(capture->engine_, capture);
    return capture;
}

oboe::Result OboeCapture::open() {
    if (stopping_.load(std::memory_order_acquire)) return oboe::Result::ErrorClosed;

    const int64_t t0 = nowMicros();
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(kSampleRate)
        ->setChannelCount(kChannels)
        // Buys the platform echo canceller, noise suppressor and gain control — what a voice chat
        // client wants on by default, and what nothing in core/ replaces. It costs the low-latency
        // memory-mapped path, because the effects chain only exists on the legacy AudioRecord
        // route: measured on a Pixel 7a, VoiceRecognition opens at a 96-frame burst (2 ms) and
        // VoiceCommunication at 960 (20 ms). Taking the 18 ms — it buys duplex that does not
        // echo, on a path already spending 20 ms filling a packet.
        ->setInputPreset(oboe::InputPreset::VoiceCommunication)
        // Correctness over latency on a device that will not open at 48 kHz; Oboe resamples and
        // logActualConfig makes the deviation visible rather than silent.
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(callbacks_)
        ->setErrorCallback(callbacks_);

    std::shared_ptr<oboe::AudioStream> newStream;
    const oboe::Result r = b.openStream(newStream);
    if (r != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(r));
        return r;
    }
    // Every builder setting above is a request, and Oboe converts channel count and format only in
    // a few device-quirk cases — its fallback when the conversion graph will not configure is to
    // open the device stream raw. So a mono I16 ask can come back stereo, or float. onAudioReady
    // would read either as mono I16: a stereo stream consumed at half length and double pitch,
    // with nothing in the audio itself to say so. Refuse instead, and let the retry loop turn it
    // into "transmit unavailable" — recoverable and legible, unlike transmitting noise. Sample
    // rate is exempt because Oboe genuinely does resample it.
    if (newStream->getChannelCount() != kChannels ||
        newStream->getFormat() != oboe::AudioFormat::I16) {
        LOGW("device opened ch=%d format=%s; need mono I16", newStream->getChannelCount(),
             oboe::convertToText(newStream->getFormat()));
        newStream->close();
        return oboe::Result::ErrorInvalidFormat;
    }
    // openStream() talks to the audio HAL and can take a while; re-check rather than publish and
    // start a stream that close() already asked us not to open.
    if (stopping_.load(std::memory_order_acquire)) {
        newStream->close();
        return oboe::Result::ErrorClosed;
    }
    {
        std::lock_guard<std::mutex> lk(streamMu_);
        stream_ = newStream;
    }
    const oboe::Result s = newStream->requestStart();
    logActualConfig("open", t0, newStream);
    if (s != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(s));
        // Close by hand. Oboe hands out a plain shared_ptr and ~AudioStream only logs, so letting
        // the last reference go deletes the wrapper and strands the device handle — and the next
        // attempt would do exactly that, since it overwrites stream_ (and openStream() itself
        // begins by resetting whatever it is given). Once per failed start, on the retry path
        // where failures cluster.
        {
            std::lock_guard<std::mutex> lk(streamMu_);
            if (stream_ == newStream) stream_.reset();
        }
        newStream->close();
        return s;
    }
    engine_->setStreamDown(false);
    return oboe::Result::OK;
}

void OboeCapture::logActualConfig(const char* phase, int64_t startMicros,
                                   const std::shared_ptr<oboe::AudioStream>& stream) {
    if (!stream) return;
    // Every builder setting is a request. Oboe documents performance and sharing mode in
    // particular as things it may change while building the stream, so only the opened stream
    // says what we actually got: the burst size that sets how often onPcm() fires, whether we
    // landed on the low-latency path, and what an open — or a reopen — costs. Not one of the
    // three has a value we could predict, which is the whole reason they are logged.
    LOGI("%s: %lld us, rate=%d ch=%d perf=%s sharing=%s burst=%d",
         phase, (long long)(nowMicros() - startMicros),
         stream->getSampleRate(), stream->getChannelCount(),
         oboe::convertToText(stream->getPerformanceMode()),
         oboe::convertToText(stream->getSharingMode()),
         stream->getFramesPerBurst());
    if (stream->getSampleRate() != kSampleRate) {
        LOGW("device opened at %d Hz; Oboe is resampling and the low-latency path is likely lost",
             stream->getSampleRate());
    }
}

void OboeCapture::close() {
    // Latch first: gates open() from publishing a fresh stream (checked inside open(), twice —
    // before and after the blocking openStream() call) and gates retryReopen() from starting a
    // new attempt (checked below, under retryMu_, before incrementing retriesInProgress_).
    stopping_.store(true, std::memory_order_release);
    {
        // Block until no retryReopen() invocation is running or about to start, so close() cannot
        // return while a backoff sleep on Oboe's error-callback thread is still going to wake up
        // and open a stream nobody will ever close.
        std::unique_lock<std::mutex> lk(retryMu_);
        retryCv_.notify_all();
        retryCv_.wait(lk, [this] { return retriesInProgress_ == 0; });
    }

    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lk(streamMu_);
        s.swap(stream_);
    }
    if (!s) return;
    // Blocking Oboe calls happen on our local copy, outside streamMu_ — holding that lock across
    // them risks deadlocking against Oboe's own internal locking while it tears the stream down.
    s->stop();
    s->close();
}

oboe::DataCallbackResult OboeCapture::Callbacks::onAudioReady(oboe::AudioStream*, void* audioData,
                                                              int32_t numFrames) {
    // numFrames counts frames; onPcm() counts samples. open() refuses any stream that did not come
    // back mono, so the two are the same number here — this is what breaks if kChannels moves.
    static_assert(kChannels == 1, "onPcm takes samples, and frames are samples only while mono");
    engine->onPcm(static_cast<const int16_t*>(audioData), uint32_t(numFrames));
    return oboe::DataCallbackResult::Continue;
}

void OboeCapture::Callbacks::onErrorBeforeClose(oboe::AudioStream*, oboe::Result r) {
    // Runs on its own thread. Marking the stream down is what wakes a pump thread parked in
    // pollFrame — nothing on the Kotlin side can reach it, since Thread.interrupt does not
    // unblock a condition variable.
    LOGW("stream error: %s", oboe::convertToText(r));
    engine->setStreamDown(true);
}

void OboeCapture::Callbacks::onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result r) {
    if (r != oboe::Result::ErrorDisconnected) return;
    // Oboe delivers this on a thread it detaches and never joins, so the capture that opened the
    // stream may already be gone — a disconnect landing at the same moment as stop(). lock() is
    // what makes that case a no-op, and holds the capture alive across the reopen if it isn't.
    const std::shared_ptr<OboeCapture> capture = owner.lock();
    if (!capture) return;
    {
        std::lock_guard<std::mutex> lk(capture->streamMu_);
        // Only drop the reference if it's still the stream that just errored — close() may have
        // already swapped stream_ out from under us between the error firing and this callback
        // running, since the two run on unrelated threads with no ordering guarantee either way.
        if (capture->stream_.get() == audioStream) capture->stream_.reset();
    }
    capture->retryReopen();
}

void OboeCapture::retryReopen() {
    // Below API 37 AAudio disconnects on every routed-device change, so a headset plug or a
    // Bluetooth connect lands here on essentially every device. This is the normal path.
    //
    // Runs on the thread Oboe created to deliver onErrorAfterClose — per AudioStreamCallback.h,
    // distinct from onAudioReady's realtime thread and not subject to its no-block/no-sleep
    // rules, so an interruptible backoff sleep here is within Oboe's documented contract.
    {
        std::lock_guard<std::mutex> lk(retryMu_);
        if (stopping_.load(std::memory_order_acquire)) return;
        ++retriesInProgress_;
    }

    bool reopened = false;
    for (int attempt = 1; attempt <= kMaxReopenFailures && !reopened; ++attempt) {
        const int64_t t0 = nowMicros();
        if (open() == oboe::Result::OK) {
            std::shared_ptr<oboe::AudioStream> s;
            {
                std::lock_guard<std::mutex> lk(streamMu_);
                s = stream_;
            }
            logActualConfig("reopen", t0, s);
            reopened = true;
            break;
        }
        if (attempt == kMaxReopenFailures) break;
        // Exponential backoff (200, 400, 800, 1600 ms) rather than hammering openStream() while,
        // e.g., a Bluetooth codec is still negotiating. wait_for's predicate makes this
        // interruptible: close() wakes it immediately instead of waiting out the interval.
        std::unique_lock<std::mutex> lk(retryMu_);
        if (retryCv_.wait_for(lk, std::chrono::milliseconds(200 << (attempt - 1)),
                               [this] { return stopping_.load(std::memory_order_acquire); })) {
            break;  // stop() requested; abandon the retry sequence
        }
    }
    if (!reopened && !stopping_.load(std::memory_order_acquire)) {
        LOGW("giving up reopening after %d attempts; transmit is unavailable", kMaxReopenFailures);
        // Terminal: distinct from setStreamDown(true) (already set by the onErrorBeforeClose that
        // started this sequence) so pollFrame() can report "never coming back" rather than
        // "still retrying" — see core/CaptureConstants.h's kPollUnavailable.
        engine_->setStreamUnavailable();
    }
    {
        std::lock_guard<std::mutex> lk(retryMu_);
        --retriesInProgress_;
    }
    retryCv_.notify_all();
}

}  // namespace dumble
