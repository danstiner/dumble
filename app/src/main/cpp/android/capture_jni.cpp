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

    // Shared: Oboe's error callback may still reference engine/capture after destroy().
    const std::shared_ptr<dumble::CaptureEngine> engine;
    const std::shared_ptr<dumble::OboeCapture> capture;

private:
    // Declaration order matters: engine is live before OboeCapture::create() reads it.
    explicit Session(std::shared_ptr<dumble::CaptureEngine> e)
        : engine(std::move(e)), capture(dumble::OboeCapture::create(engine)) {}
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

JNIEXPORT jint JNICALL FN(start)(JNIEnv*, jobject, jlong h) {
    if (!h) return -1;
    return static_cast<jint>(self(h)->capture->open());
}

JNIEXPORT jlong JNICALL FN(encodeMicrosMean)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeMicrosMean()) : 0;
}

JNIEXPORT jlong JNICALL FN(encodeMicrosMax)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeMicrosMax()) : 0;
}

JNIEXPORT jlong JNICALL FN(xRunCount)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->capture->xRunCount()) : 0;
}

JNIEXPORT jlong JNICALL FN(framesPerBurst)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->capture->framesPerBurst()) : 0;
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    // Shutdown before close: the pump must see kPollShutdown, not park against a closing stream.
    self(h)->engine->requestShutdown();
    self(h)->capture->close();
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
    if (n > 0) {
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(buf));
        jlong m[2] = {jlong(frameNumber), jlong(flags)};
        env->SetLongArrayRegion(meta, 0, 2, m);
    }
    return n;
}

JNIEXPORT jlong JNICALL FN(overrunBursts)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->overrunBursts()) : 0;
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

}  // extern "C"
