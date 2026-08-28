#include <jni.h>
#include <cmath>
#include <memory>
#include "android/OboePlayout.h"
#include "core/PlayoutConstants.h"
#include "core/PlayoutEngine.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativePlayout_##name

namespace pl = dumble::playout;

namespace {

// Flat layout for the one call that answers with more than one number. JNI carries primitive
// arrays and not structs, so the packing lives here, in the seam, and is named on the other side
// by NativePlayout's COUNTER_* constants. PlayoutEngine::Stats deliberately knows nothing about it.
constexpr int kCounterConcealedGaps = 0;
constexpr int kCounterDroppedPackets = 1;
constexpr int kCounterShrunkPackets = 2;
constexpr int kCounterCatchUpPackets = 3;
constexpr int kCounterContendedFills = 4;
constexpr int kCounterFillMicrosMax = 5;
constexpr int kCounterFillMicrosMean = 6;
// The stream's own count of bursts it played as silence, see OboePlayout::underruns.
constexpr int kCounterUnderruns = 7;
constexpr int kCounterLatencyMicros = 8;
constexpr int kCounterCount = 9;

struct Session {
    // In this order: the adapter is destroyed first, closing its stream, and only then the
    // engine it reads from.
    const std::unique_ptr<pl::PlayoutEngine> engine;
    const std::unique_ptr<dumble::OboePlayout> playout;
};

// No handle is checked for null below. NativePlayoutEngine is only ever constructed around a
// non-zero create() result — a zero becomes a Kotlin null and no engine object at all — so a null
// here would be unreachable, and offer() in particular has no honest code left to answer with.
inline Session* self(jlong h) { return reinterpret_cast<Session*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL FN(create)(JNIEnv*, jobject, jint sampleRate) {
    // 0 on failure; Kotlin treats it as "voice unavailable" rather than a usable handle. Sized
    // for the largest packet so any burst a device asks for is one fill.
    std::unique_ptr<pl::PlayoutEngine> engine =
        pl::PlayoutEngine::create(sampleRate, pl::kMaxPacketSamples);
    if (!engine) return 0;
    auto playout = std::make_unique<dumble::OboePlayout>(*engine);
    // Opened now and started by the poll: an open costs ~100 ms on a Pixel 7a, and packets that
    // land meanwhile pile up ahead of the gate as standing delay. An open stream that is not
    // started runs no callback. Failure is not terminal: start() opens again.
    playout->open();
    return reinterpret_cast<jlong>(new Session{std::move(engine), std::move(playout)});
}

JNIEXPORT jboolean JNICALL FN(start)(JNIEnv*, jobject, jlong h) {
    return self(h)->playout->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(pause)(JNIEnv*, jobject, jlong h) {
    self(h)->playout->pause();
}

JNIEXPORT jint JNICALL
FN(offer)(JNIEnv* env, jobject, jlong h, jint session, jbyteArray opusData, jlong frameNumber,
          jboolean terminator) {
    const jsize len = env->GetArrayLength(opusData);
    if (len > pl::kMaxPacketBytes) {
        // Refused here rather than by the engine, because the copy below is what we cannot afford
        // — but is_terminator is a protobuf field beside the payload and stays true when the
        // payload is garbage, so the end of the spurt is handed over on its own. Same answer the
        // engine gives an oversized payload, reached one step earlier.
        if (terminator == JNI_TRUE)
            self(h)->engine->offer(session, nullptr, 0, uint64_t(frameNumber), true);
        return pl::kOfferPacketTooLarge;
    }
    // Region copy onto the stack rather than a pin: the payload is at most kMaxPacketBytes, and a
    // pin here would be held across a mutex acquisition on the reader thread.
    uint8_t packet[pl::kMaxPacketBytes];
    if (len > 0) env->GetByteArrayRegion(opusData, 0, len, reinterpret_cast<jbyte*>(packet));
    return self(h)->engine->offer(session, len > 0 ? packet : nullptr, int(len),
                                  uint64_t(frameNumber), terminator == JNI_TRUE);
}

JNIEXPORT jint JNICALL
FN(readStats)(JNIEnv* env, jobject, jlong h, jintArray sessions, jintArray depths,
              jintArray targets, jintArray audible, jlongArray counters) {
    if (env->GetArrayLength(sessions) < pl::kMaxSpeakers ||
        env->GetArrayLength(depths) < pl::kMaxSpeakers ||
        env->GetArrayLength(targets) < pl::kMaxSpeakers ||
        env->GetArrayLength(audible) < pl::kMaxSpeakers ||
        env->GetArrayLength(counters) < kCounterCount) {
        return pl::kErrorBufferTooSmall;
    }
    const Session& s = *self(h);
    const pl::PlayoutEngine::Stats stats = s.engine->stats();
    const double latencyMillis = s.playout->latencyMillis();
    jlong countersOut[kCounterCount];
    countersOut[kCounterConcealedGaps] = jlong(stats.concealedGaps);
    countersOut[kCounterDroppedPackets] = jlong(stats.droppedPackets);
    countersOut[kCounterShrunkPackets] = jlong(stats.shrunkPackets);
    countersOut[kCounterCatchUpPackets] = jlong(stats.catchUpPackets);
    countersOut[kCounterContendedFills] = jlong(stats.contendedFills);
    countersOut[kCounterFillMicrosMax] = jlong(stats.fillMicrosMax);
    countersOut[kCounterFillMicrosMean] = jlong(stats.fillMicrosMean);
    countersOut[kCounterUnderruns] = jlong(s.playout->underruns());
    countersOut[kCounterLatencyMicros] = latencyMillis < 0 ? -1 : jlong(llround(latencyMillis * 1000));
    if (stats.speakers > 0) {
        int32_t audibleOut[pl::kMaxSpeakers];
        for (int n = 0; n < stats.speakers; n++) audibleOut[n] = stats.audible[n] ? 1 : 0;
        env->SetIntArrayRegion(sessions, 0, stats.speakers, stats.sessions);
        env->SetIntArrayRegion(depths, 0, stats.speakers, stats.depths);
        env->SetIntArrayRegion(targets, 0, stats.speakers, stats.targets);
        env->SetIntArrayRegion(audible, 0, stats.speakers, audibleOut);
    }
    env->SetLongArrayRegion(counters, 0, kCounterCount, countersOut);
    return stats.speakers;
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv*, jobject, jlong h) {
    delete self(h);
}

}  // extern "C"
