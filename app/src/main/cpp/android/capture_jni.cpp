#include <jni.h>
#include <cstdint>
#include <memory>
#include <vector>
#include "android/OboeCapture.h"
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativeCapture_##name

namespace {
struct Session {
    static Session* create(int bitrate, const void* weights, size_t weightBytes) {
        std::shared_ptr<dumble::CaptureEngine> engine =
            dumble::CaptureEngine::create(bitrate, weights, weightBytes);
        if (!engine) return nullptr;
        return new Session(std::move(engine));
    }

    // The engine is shared because Oboe's error callback can reach it after destroy(); the
    // adapter is ours alone. Declared in this order so the adapter, and its stream, go first.
    const std::shared_ptr<dumble::CaptureEngine> engine;
    const std::unique_ptr<dumble::OboeCapture> capture;

private:
    explicit Session(std::shared_ptr<dumble::CaptureEngine> e)
        : engine(std::move(e)), capture(std::make_unique<dumble::OboeCapture>(engine)) {}
};
inline Session* self(jlong h) { return reinterpret_cast<Session*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
FN(create)(JNIEnv* env, jobject, jint bitrate, jbyteArray weights) {
    const jsize n = env->GetArrayLength(weights);
    std::vector<uint8_t> blob(static_cast<size_t>(n));
    env->GetByteArrayRegion(weights, 0, n, reinterpret_cast<jbyte*>(blob.data()));
    return reinterpret_cast<jlong>(Session::create(bitrate, blob.data(), blob.size()));
}

JNIEXPORT jboolean JNICALL FN(start)(JNIEnv*, jobject, jlong h) {
    return h && self(h)->capture->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL FN(encodeMicrosMean)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeMicrosMean()) : 0;
}

JNIEXPORT jlong JNICALL FN(encodeMicrosMax)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeMicrosMax()) : 0;
}

JNIEXPORT jlong JNICALL FN(streamOverruns)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->capture->streamOverruns()) : 0;
}

JNIEXPORT jlong JNICALL FN(framesPerBurst)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->capture->framesPerBurst()) : 0;
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv*, jobject, jlong h) {
    // The stream is the pump's: it closes on the poll that reports this shutdown, below.
    if (h) self(h)->engine->requestShutdown();
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jobject, jlong h) {
    delete self(h);
}

JNIEXPORT void JNICALL FN(setGateOpen)(JNIEnv*, jobject, jlong h, jboolean open) {
    if (h) self(h)->engine->setGateOpen(open == JNI_TRUE);
}

JNIEXPORT jint JNICALL
FN(pollPacket)(JNIEnv* env, jobject, jlong h, jbyteArray out, jlongArray meta) {
    if (!h) return dumble::kPollNoSession;
    if (env->GetArrayLength(out) < dumble::kMaxPacketBytes) return dumble::kPollBufferTooSmall;
    // Stack scratch: pinning `out` across the blocking wait would hold a GC pin for milliseconds.
    uint8_t buf[dumble::kMaxPacketBytes];
    uint64_t frameNumber = 0;
    uint32_t flags = 0;
    const int n = self(h)->engine->pollPacket(buf, int(sizeof(buf)), &frameNumber, &flags);
    // The pump is the stream's only thread, so the stream's recovery and its close ride on the
    // poll: a down stream is reopened here, and the poll that ends the pump closes it.
    if (n == dumble::kPollRetry) self(h)->capture->start();
    else if (n == dumble::kPollShutdown) self(h)->capture->close();
    if (n > 0) {
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(buf));
        jlong m[2] = {jlong(frameNumber), jlong(flags)};
        env->SetLongArrayRegion(meta, 0, 2, m);
    }
    return n;
}

JNIEXPORT jlong JNICALL FN(ringOverruns)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->ringOverruns()) : 0;
}

JNIEXPORT jlong JNICALL FN(skippedSamples)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->skippedSamples()) : 0;
}

JNIEXPORT jlong JNICALL FN(encodedPackets)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodedPackets()) : 0;
}

JNIEXPORT jlong JNICALL FN(encodeErrors)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeErrors()) : 0;
}

JNIEXPORT void JNICALL FN(setVoiceActivity)(JNIEnv*, jobject, jlong h, jboolean on) {
    if (h) {
        self(h)->engine->setTransmitMode(on ? dumble::TransmitMode::VoiceActivity
                                            : dumble::TransmitMode::PushToTalk);
    }
}

}  // extern "C"
