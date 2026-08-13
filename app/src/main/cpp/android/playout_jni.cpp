#include <jni.h>
#include <memory>
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativePlayout_##name

namespace {
inline dumble::playout::PlayoutEngine* self(jlong h) {
    return reinterpret_cast<dumble::playout::PlayoutEngine*>(h);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
FN(create)(JNIEnv*, jobject, jint sampleRate, jint maxQuantumSamples, jint maxSpeakers) {
    // 0 on failure; Kotlin treats it as "voice unavailable" rather than a usable handle.
    return reinterpret_cast<jlong>(
        dumble::playout::PlayoutEngine::create(sampleRate, maxQuantumSamples, maxSpeakers)
            .release());
}

JNIEXPORT jint JNICALL
FN(offer)(JNIEnv* env, jobject, jlong h, jint session, jbyteArray opusData, jboolean terminator) {
    if (!h) return dumble::playout::kOfferEngineUnusable;
    const jsize len = env->GetArrayLength(opusData);
    if (len > dumble::playout::kMaxPacketBytes) return dumble::playout::kOfferPacketTooLarge;
    // Region copy onto the stack rather than a pin: the payload is at most kMaxPacketBytes, and a
    // pin here would be held across a mutex acquisition on the reader thread.
    uint8_t packet[dumble::playout::kMaxPacketBytes];
    if (len > 0) env->GetByteArrayRegion(opusData, 0, len, reinterpret_cast<jbyte*>(packet));
    return self(h)->offer(session, len > 0 ? packet : nullptr, int(len), terminator == JNI_TRUE);
}

JNIEXPORT jint JNICALL
FN(fillQuantum)(JNIEnv* env, jobject, jlong h, jshortArray pcm, jintArray status) {
    if (!h) return 0;
    const jsize frames = env->GetArrayLength(pcm);
    // Refuse a short status array rather than writing past it. One producing session per live
    // speaker plus the count at index 0 is the worst case.
    if (env->GetArrayLength(status) < dumble::playout::kMaxSpeakers + 1)
        return dumble::playout::kErrorBufferTooSmall;
    if (frames <= 0) return dumble::playout::kErrorBufferTooSmall;

    // Stack scratch, then one copy of exactly the quantum produced. Pinning the caller's arrays
    // across the mix would couple ART's moving collector to the playback path.
    //
    // "Too small" is the umbrella code for every caller-sizing bug, so a pcm array too *large* for
    // this scratch reports it too — the alternative is a second code nothing would handle
    // differently. The engine's own maxQuantumSamples bound is tighter and reports the same code.
    if (frames > dumble::playout::kMaxFrameSamples) return dumble::playout::kErrorBufferTooSmall;
    int16_t out[dumble::playout::kMaxFrameSamples];
    int32_t statusOut[dumble::playout::kMaxSpeakers + 1] = {};

    // The flat layout kStatusActiveSpeakers describes, built by aiming the engine's two outputs at
    // index 1 and index 0 — so one region copy still carries both.
    const int producing = self(h)->fillQuantum(out, int(frames), statusOut + 1,
                                               statusOut + dumble::playout::kStatusActiveSpeakers);
    env->SetShortArrayRegion(pcm, 0, frames, out);
    // producing + 1 is how a refusal elides the status copy without a branch, which works only
    // because the refusal is exactly -1: any other negative code makes this a negative-length
    // region call.
    static_assert(dumble::playout::kErrorBufferTooSmall == -1);
    env->SetIntArrayRegion(status, 0, producing + 1, statusOut);
    return producing;
}

JNIEXPORT jint JNICALL
FN(readStats)(JNIEnv* env, jobject, jlong h, jintArray sessions, jintArray depths,
              jlongArray counters) {
    if (!h) return 0;
    if (env->GetArrayLength(sessions) < dumble::playout::kMaxSpeakers ||
        env->GetArrayLength(depths) < dumble::playout::kMaxSpeakers ||
        env->GetArrayLength(counters) < dumble::playout::kCounterCount) {
        return dumble::playout::kErrorBufferTooSmall;
    }
    int32_t sessionsOut[dumble::playout::kMaxSpeakers] = {};
    int32_t depthsOut[dumble::playout::kMaxSpeakers] = {};
    int64_t countersOut[dumble::playout::kCounterCount] = {};
    const int n = self(h)->readStats(sessionsOut, depthsOut, countersOut);
    if (n > 0) {
        env->SetIntArrayRegion(sessions, 0, n, sessionsOut);
        env->SetIntArrayRegion(depths, 0, n, depthsOut);
    }
    env->SetLongArrayRegion(counters, 0, dumble::playout::kCounterCount,
                            reinterpret_cast<const jlong*>(countersOut));
    return n;
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jobject, jlong h) {
    delete self(h);
}

}  // extern "C"
