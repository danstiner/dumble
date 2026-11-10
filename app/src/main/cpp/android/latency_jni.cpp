#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <vector>
#include "core/LatencyPackets.h"
#include "core/LatencyProbe.h"

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_latency_NativeLatencyProbe_##name

namespace lat = dumble::latency;

// Where the device check's own probe sits: find() normalises by the probe's energy, so the level
// the capture heard it at does not matter to the search.
constexpr int16_t kProbePeak = 1600;

extern "C" {

JNIEXPORT jobjectArray JNICALL
FN(encodeProbeSpurt)(JNIEnv* env, jobject, jint peak, jint leadSamples, jint tailSamples, jint bitrate) {
    const auto probe = lat::probe(int16_t(peak));
    std::vector<int16_t> pcm(size_t(leadSamples) + probe.size() + size_t(tailSamples), 0);
    std::copy(probe.begin(), probe.end(), pcm.begin() + leadSamples);
    const auto packets = lat::encode(pcm, 0, bitrate);
    jobjectArray out = env->NewObjectArray(jsize(packets.size()), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < packets.size(); i++) {
        const auto& opus = packets[i].opus;
        jbyteArray bytes = env->NewByteArray(jsize(opus.size()));
        env->SetByteArrayRegion(bytes, 0, jsize(opus.size()), reinterpret_cast<const jbyte*>(opus.data()));
        env->SetObjectArrayElement(out, jsize(i), bytes);
        env->DeleteLocalRef(bytes);
    }
    return out;
}

JNIEXPORT jint JNICALL FN(codecDelaySamples)(JNIEnv*, jobject, jint bitrate) {
    return jint(lat::codecDelay(lat::probe(kProbePeak), bitrate));
}

JNIEXPORT jboolean JNICALL
FN(locate)(JNIEnv* env, jobject, jlongArray frames, jobjectArray packets,
           jint codecDelaySamples, jdoubleArray out) {
    const jsize n = env->GetArrayLength(frames);
    if (env->GetArrayLength(packets) != n || env->GetArrayLength(out) < 3) {
        return JNI_FALSE;
    }
    std::vector<jlong> frameNumbers(static_cast<size_t>(n));
    env->GetLongArrayRegion(frames, 0, n, frameNumbers.data());
    std::vector<lat::TimedPacket> timed(static_cast<size_t>(n));
    for (jsize i = 0; i < n; i++) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(packets, i));
        const jsize len = env->GetArrayLength(bytes);
        timed[size_t(i)].frameNumber = uint64_t(frameNumbers[size_t(i)]);
        timed[size_t(i)].opus.resize(size_t(len));
        env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(timed[size_t(i)].opus.data()));
        env->DeleteLocalRef(bytes);
    }
    const auto d = lat::locate(std::move(timed), lat::probe(kProbePeak), codecDelaySamples);
    const jdouble result[3] = {jdouble(d.offset), d.peak, d.nextPeak};
    env->SetDoubleArrayRegion(out, 0, 3, result);
    return d.found() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
