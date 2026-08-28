#include "android/OboePlayout.h"
#include <android/log.h>
#include "core/CaptureConstants.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OboePlayout", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OboePlayout", __VA_ARGS__)

namespace dumble {
namespace {
using Clock = std::chrono::steady_clock;
constexpr auto kOpenRetryInterval = std::chrono::seconds(1);
}  // namespace

OboePlayout::OboePlayout(playout::PlayoutEngine& engine)
    : engine_(engine), callbacks_(std::make_shared<Callbacks>(engine)) {}

oboe::Result OboePlayout::open() {
    if (stream_) return oboe::Result::OK;

    const Clock::time_point t0 = Clock::now();
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

    // Cleared before the open, not after: a route change can close a stream that is open but
    // not yet started, and on the legacy path that is an error callback like any other. Set
    // between here and requestStart, the flag is consumed by the next start().
    callbacks_->streamDead.store(false, std::memory_order_release);
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
    // Per stream, before any callback can run for it: the tuner starts the buffer at two bursts
    // and grows it a burst per underrun, and the callback passes each change on as the engine's
    // write-ahead.
    callbacks_->tuner = std::make_unique<oboe::LatencyTuner>(*newStream);
    callbacks_->lastBufferSize = newStream->getBufferSizeInFrames();
    engine_.setWriteAheadSamples(callbacks_->lastBufferSize);
    stream_ = newStream;
    logActualConfig("open", t0, newStream);
    return oboe::Result::OK;
}

bool OboePlayout::start() {
    if (stream_ && callbacks_->streamDead.load(std::memory_order_acquire)) {
        // Oboe closed it on its own thread. The engine's spurt in flight is gone with it; the
        // next spurt prebuffers afresh against the estimates it kept.
        engine_.setOutputDown(true);
        stream_.reset();
        streamStarted_ = false;
    }
    if (!stream_) {
        const Clock::time_point now = Clock::now();
        if (now < nextOpenAt_) return false;
        nextOpenAt_ = now + kOpenRetryInterval;
        if (open() != oboe::Result::OK) return false;
    }
    if (streamStarted_) return true;
    // From the first callback on, the engine's filler runs on a realtime thread: every fill
    // must take the engine's mutex with try_lock or not at all.
    engine_.setRealtime(true);
    const oboe::Result r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        // The stream is gone, not merely busy: on the MMAP path a disconnect while paused —
        // a call rerouting the device — arrives as nothing but this failure, since the error
        // callback fires only from a running data loop. Dropped here so the next poll opens
        // another; kept, it would be retried at every poll for the rest of the session.
        LOGW("requestStart: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        engine_.setOutputDown(true);
        return false;
    }
    streamStarted_ = true;
    engine_.setOutputDown(false);
    return true;
}

void OboePlayout::pause() {
    streamStarted_ = false;
    if (!stream_) return;
    // A pause that fails is a dead stream, which the start() after the hold notices.
    if (stream_->requestPause() != oboe::Result::OK) return;
    oboe::StreamState next = oboe::StreamState::Unknown;
    stream_->waitForStateChange(oboe::StreamState::Pausing, &next,
                                100 * oboe::kNanosPerMillisecond);
    // Still pausing after 100 ms is the one state in which a callback may yet run: nothing
    // below is safe, and AAudio refuses the flush anyway.
    if (next == oboe::StreamState::Pausing) return;
    // Settled, so no callback runs, which is what releasing the engine's speakers needs. The
    // hold ends every spurt here: whoever was mid-word is not left "speaking" for its length,
    // and their queue is emptied rather than played stale on resume — the receiver drops
    // packets while held for the same reason.
    engine_.setOutputDown(true);
    // Flushed as well as paused, so the silence buffered while idle is not what plays first
    // when the next spurt starts.
    const oboe::Result r = stream_->requestFlush();
    if (r != oboe::Result::OK) LOGW("requestFlush: %s", oboe::convertToText(r));
}

void OboePlayout::logActualConfig(const char* phase, Clock::time_point started,
                                  const std::shared_ptr<oboe::AudioStream>& stream) {
    if (!stream) return;
    // Every builder setting is a request; see OboeCapture.cpp. The two extra fields here are
    // what decide the callback's budget: the burst is how often it fires, and the buffer is the
    // write-ahead the engine adds to its targets.
    const auto took = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - started);
    LOGI("%s: %lld us, api=%s rate=%d ch=%d perf=%s sharing=%s burst=%d buffer=%d/%d",
         phase, (long long)took.count(),
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
    if (!stream_) return;
    // After close() returns Oboe delivers no more data callbacks; an error callback still in
    // flight touches only callbacks_, which Oboe keeps alive.
    stream_->stop();
    stream_->close();
    stream_.reset();
}

oboe::DataCallbackResult OboePlayout::Callbacks::onAudioReady(oboe::AudioStream* stream,
                                                              void* audioData,
                                                              int32_t numFrames) {
    // Realtime thread. fillQuantum in realtime mode never blocks, and tune() touches only the
    // stream's own buffer size; nothing else belongs here — see AAudio's callback contract.
    static_assert(kChannels == 1, "fillQuantum counts samples, which are frames only while mono");
    engine.fillQuantum(static_cast<int16_t*>(audioData), numFrames, sessions, &live);
    tuner->tune();
    const int32_t size = stream->getBufferSizeInFrames();
    if (size != lastBufferSize) {
        lastBufferSize = size;
        engine.setWriteAheadSamples(size);
    }
    return oboe::DataCallbackResult::Continue;
}

void OboePlayout::Callbacks::onErrorBeforeClose(oboe::AudioStream*, oboe::Result r) {
    // Oboe's own thread, possibly after the adapter is gone: nothing here but this flag. Below
    // API 37 a disconnect lands on every routed-device change, so this is the normal path.
    LOGW("stream error: %s", oboe::convertToText(r));
    streamDead.store(true, std::memory_order_release);
}

int32_t OboePlayout::underruns() const {
    if (!stream_) return 0;
    const auto count = stream_->getXRunCount();
    return count ? count.value() : 0;
}

double OboePlayout::latencyMillis() const {
    if (!stream_) return -1;
    const auto r = stream_->calculateLatencyMillis();
    return r ? r.value() : -1;
}

}  // namespace dumble
