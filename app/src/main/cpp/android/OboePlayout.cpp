#include "android/OboePlayout.h"
#include <android/log.h>
#include <chrono>
#include "core/CaptureConstants.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OboePlayout", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OboePlayout", __VA_ARGS__)

namespace dumble {
namespace {
int64_t nowMicros() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}
constexpr int kMaxReopenFailures = 5;
constexpr int64_t kOpenRetryMicros = 1'000'000;
}  // namespace

std::shared_ptr<OboePlayout> OboePlayout::create(std::shared_ptr<playout::PlayoutEngine> engine) {
    std::shared_ptr<OboePlayout> playout(new OboePlayout(std::move(engine)));
    playout->callbacks_ = std::make_shared<Callbacks>(playout->engine_, playout);
    return playout;
}

oboe::Result OboePlayout::open() {
    if (stopping_.load(std::memory_order_acquire)) return oboe::Result::ErrorClosed;
    std::lock_guard<std::mutex> opening(openMutex_);
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        if (stream_) return oboe::Result::OK;
    }

    const int64_t t0 = nowMicros();
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(kSampleRate)
        ->setChannelCount(kChannels)
        // The voice-call output route, which is what pairs with capture's VoiceCommunication
        // preset for the platform echo canceller.
        ->setUsage(oboe::Usage::VoiceCommunication)
        ->setContentType(oboe::ContentType::Speech)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(callbacks_)
        ->setErrorCallback(callbacks_);

    std::shared_ptr<oboe::AudioStream> newStream;
    const oboe::Result r = b.openStream(newStream);
    if (r != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(r));
        return r;
    }
    // See OboeCapture.cpp for why a stream that came back stereo or float is refused, not read.
    if (newStream->getChannelCount() != kChannels ||
        newStream->getFormat() != oboe::AudioFormat::I16) {
        LOGW("device opened ch=%d format=%s; need mono I16", newStream->getChannelCount(),
             oboe::convertToText(newStream->getFormat()));
        newStream->close();
        return oboe::Result::ErrorInvalidFormat;
    }
    if (stopping_.load(std::memory_order_acquire)) {
        newStream->close();
        return oboe::Result::ErrorClosed;
    }
    // Per stream, before any callback can run for it: the tuner starts the buffer at two bursts
    // and grows it a burst per xRun, and the callback passes each change on as the engine's
    // write-ahead.
    callbacks_->tuner = std::make_unique<oboe::LatencyTuner>(*newStream);
    callbacks_->lastBufferSize = newStream->getBufferSizeInFrames();
    engine_->setWriteAheadSamples(callbacks_->lastBufferSize);
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        stream_ = newStream;
    }
    logActualConfig("open", t0, newStream);
    if (!wantStarted_.load(std::memory_order_acquire)) return oboe::Result::OK;
    // From the first callback on, the engine's filler runs on a realtime thread: every fill
    // must take the engine's mutex with try_lock or not at all.
    engine_->setRealtime(true);
    const oboe::Result s = newStream->requestStart();
    if (s != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(s));
        // Closed by hand, for the reason OboeCapture.cpp gives: dropping the last reference
        // strands the device handle.
        {
            std::lock_guard<std::mutex> lk(streamMutex_);
            if (stream_ == newStream) stream_.reset();
        }
        newStream->close();
        return s;
    }
    streamStarted_.store(true, std::memory_order_release);
    engine_->setOutputDown(false);
    return oboe::Result::OK;
}

bool OboePlayout::start() {
    wantStarted_.store(true, std::memory_order_release);
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        s = stream_;
    }
    if (!s) {
        const int64_t now = nowMicros();
        if (now < nextOpenMicros_) return false;
        nextOpenMicros_ = now + kOpenRetryMicros;
        return open() == oboe::Result::OK;
    }
    if (streamStarted_.load(std::memory_order_acquire)) return true;
    engine_->setRealtime(true);
    const oboe::Result r = s->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("requestStart: %s", oboe::convertToText(r));
        return false;
    }
    streamStarted_.store(true, std::memory_order_release);
    return true;
}

void OboePlayout::pause() {
    wantStarted_.store(false, std::memory_order_release);
    streamStarted_.store(false, std::memory_order_release);
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lk(streamMutex_);
        s = stream_;
    }
    if (!s) return;
    // Flushed as well as paused, so the silence buffered while idle is not what plays first
    // when the next spurt starts. AAudio refuses a flush until the pause has completed, so the
    // poll thread — which has nothing else to do — waits for it.
    if (s->requestPause() != oboe::Result::OK) return;
    oboe::StreamState next = oboe::StreamState::Unknown;
    s->waitForStateChange(oboe::StreamState::Pausing, &next, 100 * oboe::kNanosPerMillisecond);
    const oboe::Result r = s->requestFlush();
    if (r != oboe::Result::OK) LOGW("requestFlush: %s", oboe::convertToText(r));
}

void OboePlayout::logActualConfig(const char* phase, int64_t startMicros,
                                  const std::shared_ptr<oboe::AudioStream>& stream) {
    if (!stream) return;
    // Every builder setting is a request; see OboeCapture.cpp. The two extra fields here are
    // what decide the callback's budget: the burst is how often it fires, and the buffer is the
    // write-ahead the engine adds to its targets.
    LOGI("%s: %lld us, api=%s rate=%d ch=%d perf=%s sharing=%s burst=%d buffer=%d/%d",
         phase, (long long)(nowMicros() - startMicros),
         oboe::convertToText(stream->getAudioApi()),
         stream->getSampleRate(), stream->getChannelCount(),
         oboe::convertToText(stream->getPerformanceMode()),
         oboe::convertToText(stream->getSharingMode()),
         stream->getFramesPerBurst(), stream->getBufferSizeInFrames(),
         stream->getBufferCapacityInFrames());
    if (stream->getSampleRate() != kSampleRate) {
        LOGW("device opened at %d Hz; Oboe is resampling and the low-latency path is likely lost",
             stream->getSampleRate());
    }
}

void OboePlayout::close() {
    stopping_.store(true, std::memory_order_release);
    {
        std::unique_lock<std::mutex> lk(retryMutex_);
        retryCondition_.notify_all();
        retryCondition_.wait(lk, [this] { return retriesInProgress_ == 0; });
    }
    std::shared_ptr<oboe::AudioStream> s;
    {
        // openMutex_ as well as streamMutex_: an open() past its stopping_ checks cannot publish
        // into a slot this has already emptied.
        std::lock_guard<std::mutex> opening(openMutex_);
        std::lock_guard<std::mutex> lk(streamMutex_);
        s.swap(stream_);
        streamStarted_.store(false, std::memory_order_release);
    }
    if (!s) return;
    s->stop();
    s->close();
}

oboe::DataCallbackResult OboePlayout::Callbacks::onAudioReady(oboe::AudioStream* stream,
                                                              void* audioData,
                                                              int32_t numFrames) {
    // Realtime thread. fillQuantum in realtime mode never blocks, and tune() touches only the
    // stream's own buffer size; nothing else belongs here — see AAudio's callback contract.
    static_assert(kChannels == 1, "fillQuantum counts samples, which are frames only while mono");
    engine->fillQuantum(static_cast<int16_t*>(audioData), numFrames, sessions, &live);
    tuner->tune();
    const int32_t size = stream->getBufferSizeInFrames();
    if (size != lastBufferSize) {
        lastBufferSize = size;
        engine->setWriteAheadSamples(size);
    }
    return oboe::DataCallbackResult::Continue;
}

void OboePlayout::Callbacks::onErrorBeforeClose(oboe::AudioStream*, oboe::Result r) {
    LOGW("stream error: %s", oboe::convertToText(r));
    engine->setOutputDown(true);
}

void OboePlayout::Callbacks::onErrorAfterClose(oboe::AudioStream* audioStream, oboe::Result r) {
    const std::shared_ptr<OboePlayout> playout = owner.lock();
    if (!playout) return;
    // Oboe has closed the stream whatever the error was, so it leaves stream_ either way; a
    // disconnect is reopened at once because it is the normal path below API 37, anything else
    // waits for the poll's next start().
    {
        std::lock_guard<std::mutex> lk(playout->streamMutex_);
        if (playout->stream_.get() == audioStream) {
            playout->stream_.reset();
            playout->streamStarted_.store(false, std::memory_order_release);
        }
    }
    if (r == oboe::Result::ErrorDisconnected) playout->retryReopen();
}

void OboePlayout::retryReopen() {
    // The shape and the backoff are OboeCapture's; see there for why this is the normal path
    // below API 37 and why sleeping on this thread is within Oboe's contract.
    {
        std::lock_guard<std::mutex> lk(retryMutex_);
        if (stopping_.load(std::memory_order_acquire)) return;
        ++retriesInProgress_;
    }
    bool reopened = false;
    for (int attempt = 1; attempt <= kMaxReopenFailures && !reopened; ++attempt) {
        const int64_t t0 = nowMicros();
        if (open() == oboe::Result::OK) {
            std::shared_ptr<oboe::AudioStream> s;
            {
                std::lock_guard<std::mutex> lk(streamMutex_);
                s = stream_;
            }
            logActualConfig("reopen", t0, s);
            reopened = true;
            break;
        }
        if (attempt == kMaxReopenFailures) break;
        std::unique_lock<std::mutex> lk(retryMutex_);
        if (retryCondition_.wait_for(lk, std::chrono::milliseconds(200 << (attempt - 1)),
                                     [this] { return stopping_.load(std::memory_order_acquire); })) {
            break;
        }
    }
    if (!reopened && !stopping_.load(std::memory_order_acquire)) {
        // Not terminal, unlike capture: the poll's start() keeps trying, once a second.
        LOGW("giving up reopening after %d attempts; start() keeps trying", kMaxReopenFailures);
    }
    {
        std::lock_guard<std::mutex> lk(retryMutex_);
        --retriesInProgress_;
    }
    retryCondition_.notify_all();
}

int32_t OboePlayout::xRunCount() const {
    std::lock_guard<std::mutex> lk(streamMutex_);
    if (!stream_) return 0;
    const auto count = stream_->getXRunCount();
    return count ? count.value() : 0;
}

double OboePlayout::latencyMillis() const {
    std::lock_guard<std::mutex> lk(streamMutex_);
    if (!stream_) return -1;
    const auto r = stream_->calculateLatencyMillis();
    return r ? r.value() : -1;
}

int32_t OboePlayout::framesPerBurst() const {
    std::lock_guard<std::mutex> lk(streamMutex_);
    return stream_ ? stream_->getFramesPerBurst() : 0;
}

int32_t OboePlayout::bufferSizeInFrames() const {
    std::lock_guard<std::mutex> lk(streamMutex_);
    return stream_ ? stream_->getBufferSizeInFrames() : 0;
}

}  // namespace dumble
