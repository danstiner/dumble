#include <jni.h>
#include <math.h>
#include <stdint.h>
#include "rnnoise.h"

#define RNN_FRAME 480   /* RNNoise fixed frame: 480 samples @ 48 kHz = 10 ms */

JNIEXPORT jlong JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_createState(JNIEnv *env, jobject thiz) {
    return (jlong)(intptr_t) rnnoise_create(NULL);
}

JNIEXPORT void JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_destroyState(JNIEnv *env, jobject thiz, jlong st) {
    if (st) rnnoise_destroy((DenoiseState *)(intptr_t) st);
}

/* Denoise 480 samples in place at pcm[off..off+480). RNNoise works on float samples in the
 * int16 range (NOT normalized to [-1,1]). */
JNIEXPORT void JNICALL
Java_me_danielstiner_dumble_mumble_voice_NativeRnnoise_processFrame(
        JNIEnv *env, jobject thiz, jlong st, jshortArray arr, jint off) {
    if (!st) return;
    jshort *pcm = (*env)->GetShortArrayElements(env, arr, NULL);
    float buf[RNN_FRAME];
    for (int i = 0; i < RNN_FRAME; i++) buf[i] = (float) pcm[off + i];
    rnnoise_process_frame((DenoiseState *)(intptr_t) st, buf, buf);
    for (int i = 0; i < RNN_FRAME; i++) {
        float v = buf[i];
        if (v > 32767.0f) v = 32767.0f;
        else if (v < -32768.0f) v = -32768.0f;
        pcm[off + i] = (jshort) lrintf(v);
    }
    (*env)->ReleaseShortArrayElements(env, arr, pcm, 0);
}
