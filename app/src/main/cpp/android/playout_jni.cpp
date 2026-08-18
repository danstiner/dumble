#include <jni.h>
#include <memory>
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativePlayout_##name

namespace pl = dumble::playout;

namespace {

// Flat layouts for the two calls that answer with more than one number. JNI carries primitive
// arrays and not structs, so the packing lives here, in the seam, and is named on the other side
// by NativePlayout's STATUS_* and COUNTER_* constants. PlayoutEngine::Stats deliberately knows
// nothing about it.
constexpr int kStatusActiveSpeakers = 0;
constexpr int kStatusSessions = 1;
constexpr int kStatusLength = kStatusSessions + pl::kMaxSpeakers;

constexpr int kCounterConcealedGaps = 0;
constexpr int kCounterDroppedPackets = 1;
constexpr int kCounterCount = 2;

// No handle is checked for null below. NativePlayoutEngine is only ever constructed around a
// non-zero create() result — a zero becomes a Kotlin null and no engine object at all — so a null
// here would be unreachable, and offer() in particular has no honest code left to answer with.
inline pl::PlayoutEngine* self(jlong h) { return reinterpret_cast<pl::PlayoutEngine*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
FN(create)(JNIEnv*, jobject, jint sampleRate, jint maxQuantumSamples) {
    // 0 on failure; Kotlin treats it as "voice unavailable" rather than a usable handle. The
    // speaker cap is not a parameter: it is the engine's own compile-time bound, and taking it
    // from here would let the arrays this file validates disagree with the ones it fills.
    return reinterpret_cast<jlong>(
        pl::PlayoutEngine::create(sampleRate, maxQuantumSamples).release());
}

JNIEXPORT jint JNICALL
FN(offer)(JNIEnv* env, jobject, jlong h, jint session, jbyteArray opusData, jboolean terminator) {
    const jsize len = env->GetArrayLength(opusData);
    if (len > pl::kMaxPacketBytes) {
        // Refused here rather than by the engine, because the copy below is what we cannot afford
        // — but is_terminator is a protobuf field beside the payload and stays true when the
        // payload is garbage, so the end of the spurt is handed over on its own. Same answer the
        // engine gives an oversized payload, reached one step earlier.
        if (terminator == JNI_TRUE) self(h)->offer(session, nullptr, 0, true);
        return pl::kOfferPacketTooLarge;
    }
    // Region copy onto the stack rather than a pin: the payload is at most kMaxPacketBytes, and a
    // pin here would be held across a mutex acquisition on the reader thread.
    uint8_t packet[pl::kMaxPacketBytes];
    if (len > 0) env->GetByteArrayRegion(opusData, 0, len, reinterpret_cast<jbyte*>(packet));
    return self(h)->offer(session, len > 0 ? packet : nullptr, int(len), terminator == JNI_TRUE);
}

JNIEXPORT jint JNICALL
FN(fillQuantum)(JNIEnv* env, jobject, jlong h, jshortArray pcm, jintArray status) {
    const jsize samples = env->GetArrayLength(pcm);
    // Refuse rather than write past the caller's array. An allocation bug on the Kotlin side and
    // never a peer's doing, so it answers the engine's own refusal code and the playback loop
    // stops on it — the alternative is going silently mute with nothing to look at.
    if (env->GetArrayLength(status) < kStatusLength) return pl::kErrorBufferTooSmall;
    // Unreachable as a difference, and kept anyway: create() refuses a maxQuantumSamples above
    // kMaxPacketSamples, so the engine's bound is always the tighter of the two and is what
    // actually rejects an oversized `pcm` — with this same code. This guard belongs to the array
    // on the next line rather than to the caller. If that invariant ever moves, what it costs is
    // a smashed stack rather than a wrong answer, which is why it is a branch and not a comment.
    if (samples <= 0 || samples > pl::kMaxPacketSamples) return pl::kErrorBufferTooSmall;

    // Stack scratch, then one copy of exactly the frame produced. Pinning the caller's arrays
    // across the mix would couple ART's moving collector to the playback path.
    int16_t out[pl::kMaxPacketSamples];
    int32_t statusOut[kStatusLength] = {};
    const int producing = self(h)->fillQuantum(out, int(samples), statusOut + kStatusSessions,
                                               statusOut + kStatusActiveSpeakers);
    // A refused frame leaves `out` untouched, so publishing it would hand the caller whatever
    // this frame's stack held. Nothing is copied and the code travels out unchanged.
    if (producing < 0) return producing;
    env->SetShortArrayRegion(pcm, 0, samples, out);
    env->SetIntArrayRegion(status, 0, kStatusSessions + producing, statusOut);
    return producing;
}

JNIEXPORT jint JNICALL
FN(readStats)(JNIEnv* env, jobject, jlong h, jintArray sessions, jintArray depths,
              jlongArray counters) {
    if (env->GetArrayLength(sessions) < pl::kMaxSpeakers ||
        env->GetArrayLength(depths) < pl::kMaxSpeakers ||
        env->GetArrayLength(counters) < kCounterCount) {
        return pl::kErrorBufferTooSmall;
    }
    const pl::PlayoutEngine::Stats stats = self(h)->stats();
    const jlong countersOut[kCounterCount] = {jlong(stats.concealedGaps),
                                              jlong(stats.droppedPackets)};
    if (stats.speakers > 0) {
        env->SetIntArrayRegion(sessions, 0, stats.speakers, stats.sessions);
        env->SetIntArrayRegion(depths, 0, stats.speakers, stats.depths);
    }
    env->SetLongArrayRegion(counters, 0, kCounterCount, countersOut);
    return stats.speakers;
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jobject, jlong h) {
    delete self(h);
}

}  // extern "C"
