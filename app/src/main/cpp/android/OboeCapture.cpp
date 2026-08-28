#include "android/OboeCapture.h"
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OboeCapture", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "OboeCapture", __VA_ARGS__)

namespace dumble {
namespace {
using Clock = std::chrono::steady_clock;
constexpr auto kOpenRetryInterval = std::chrono::seconds(1);
}  // namespace

OboeCapture::OboeCapture(std::shared_ptr<CaptureEngine> engine)
    : callbacks_(std::make_shared<Callbacks>(std::move(engine))) {}

bool OboeCapture::start() {
    if (stream_ && callbacks_->streamDead.load(std::memory_order_acquire)) {
        // Oboe closed it on its own thread; the engine has known since onErrorBeforeClose.
        stream_.reset();
    }
    if (stream_) return true;
    const Clock::time_point now = Clock::now();
    if (now < nextOpenAt_) return false;
    nextOpenAt_ = now + kOpenRetryInterval;
    return open() == oboe::Result::OK;
}

oboe::Result OboeCapture::open() {
    const Clock::time_point t0 = Clock::now();
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

    // Cleared before the open, not after: a route change can close a stream that is open but
    // not yet started, and that is an error callback like any other. Set between here and
    // requestStart, the flag is consumed by the next start().
    callbacks_->streamDead.store(false, std::memory_order_release);
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
    // with nothing in the audio itself to say so. Refuse instead — terminally, since what a
    // device opens for this request does not change by asking again — and the pump exits on
    // kPollUnavailable: recoverable and legible, unlike transmitting noise. Sample rate is exempt
    // because Oboe genuinely does resample it.
    if (newStream->getChannelCount() != kChannels ||
        newStream->getFormat() != oboe::AudioFormat::I16) {
        LOGW("device opened ch=%d format=%s; need mono I16", newStream->getChannelCount(),
             oboe::convertToText(newStream->getFormat()));
        newStream->close();
        callbacks_->engine->setStreamUnavailable();
        return oboe::Result::ErrorInvalidFormat;
    }
    const oboe::Result s = newStream->requestStart();
    logActualConfig(t0, newStream);
    if (s != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(s));
        // By hand: ~AudioStream only logs, so dropping the last reference would strand the
        // device handle.
        newStream->close();
        return s;
    }
    stream_ = newStream;
    callbacks_->engine->setStreamDown(false);
    return oboe::Result::OK;
}

void OboeCapture::logActualConfig(Clock::time_point started,
                                   const std::shared_ptr<oboe::AudioStream>& stream) {
    // Every builder setting is a request. Oboe documents performance and sharing mode in
    // particular as things it may change while building the stream, so only the opened stream
    // says what we actually got: the burst size that sets how often onPcm() fires, whether we
    // landed on the low-latency path, and what an open costs. Not one of the three has a value
    // we could predict, which is the whole reason they are logged.
    const auto took = std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - started);
    LOGI("open: %lld us, rate=%d ch=%d perf=%s sharing=%s burst=%d",
         (long long)took.count(),
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
    if (!stream_) return;
    // After close() returns Oboe delivers no more data callbacks; an error callback still in
    // flight touches only callbacks_, which Oboe keeps alive.
    stream_->stop();
    stream_->close();
    stream_.reset();
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
    // Oboe's own thread. Marking the stream down is what wakes a pump parked in pollPacket and
    // turns its next poll into kPollRetry, which is what brings start() back around — nothing on
    // the Kotlin side can reach it, since Thread.interrupt does not unblock a condition variable.
    LOGW("stream error: %s", oboe::convertToText(r));
    streamDead.store(true, std::memory_order_release);
    engine->setStreamDown(true);
}

int32_t OboeCapture::streamOverruns() const {
    if (!stream_) return 0;
    const auto count = stream_->getXRunCount();
    return count ? count.value() : 0;
}

int32_t OboeCapture::framesPerBurst() const {
    return stream_ ? stream_->getFramesPerBurst() : 0;
}

double OboeCapture::inputLatencyMillis() const {
    if (!stream_) return -1;
    // Drops when the callback reads and climbs as frames accumulate — Oboe's own note on input
    // streams — so this is a snapshot of wherever the burst happens to sit, not a constant.
    const auto latency = stream_->calculateLatencyMillis();
    return latency ? latency.value() : -1;
}

}  // namespace dumble
