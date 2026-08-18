#include <jni.h>
#include <memory>
#include "android/OboeCapture.h"
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativeCapture_##name

namespace {
struct Session {
    // kSampleRate/kTxPacketSamples rather than parameters: OboeCapture opens the stream from these
    // same constants, so taking them from Kotlin would let the encoder and the stream disagree.
    static Session* create(int bitrate) {
        std::shared_ptr<dumble::CaptureEngine> engine =
            dumble::CaptureEngine::create(dumble::kSampleRate, dumble::kTxPacketSamples, bitrate);
        // A session without an engine has nothing to capture into, so the failure travels out to
        // Kotlin as a null handle rather than being absorbed here.
        if (!engine) return nullptr;
        return new Session(std::move(engine));
    }

    // Shared rather than owned outright: Oboe can still be running a stream-error callback on a
    // detached thread when destroy() lands, and that callback writes into the engine. Both objects
    // outlive this Session by however long that takes.
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
FN(create)(JNIEnv*, jobject, jint bitrate) {
    // 0 on failure; Kotlin treats it as "capture unavailable" rather than a usable handle.
    return reinterpret_cast<jlong>(Session::create(bitrate));
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
    // Order matters: wake the pump before tearing the stream down, so it observes kPollShutdown
    // and returns rather than parking again against a closing stream.
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
    // Refuse a short array rather than quietly encoding into whatever room it has. outCap is the
    // ceiling handed to opus_encode, so shrinking it changes what gets transmitted, not just what
    // fits — and it would do so only on the loudest frames, which is the worst way to find out.
    if (env->GetArrayLength(out) < dumble::kMaxPacketBytes) return dumble::kPollBufferTooSmall;
    // Stack scratch, then one copy of only the bytes produced. Pinning `out` across the blocking
    // wait inside pollPacket would hold a GC-visible pin for milliseconds at a time, and the
    // critical-section variant that avoids the copy outright forbids blocking while it is held.
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

// One counter per method rather than a packed long[]: each is an independent relaxed atomic, so a
// single call was never a consistent snapshot of all of them, and the array bought nothing but an
// index-to-meaning mapping that had to be kept in step by hand in two languages. Read at a few Hz
// by a debug overlay, so the extra crossings cost nothing worth the ambiguity.
JNIEXPORT jlong JNICALL FN(overrunBursts)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->overrunBursts()) : 0;
}

JNIEXPORT jlong JNICALL FN(skippedSamples)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->skippedSamples()) : 0;
}

JNIEXPORT jlong JNICALL FN(encodedPackets)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodedPackets()) : 0;
}

// Absent from the old long[3] entirely, which is its own argument against the array: without it a
// persistent libopus failure and an idle gate both look like pollPacket returning 0.
JNIEXPORT jlong JNICALL FN(encodeErrors)(JNIEnv*, jobject, jlong h) {
    return h ? jlong(self(h)->engine->encodeErrors()) : 0;
}

}  // extern "C"
