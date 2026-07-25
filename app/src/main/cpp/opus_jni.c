#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativeOpus_##name

JNIEXPORT jlong JNICALL
FN(createDecoder)(JNIEnv *env, jobject thiz, jint sampleRate, jint channels) {
    int err = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK) return 0;
    return (jlong)(intptr_t)dec;
}

JNIEXPORT jint JNICALL
FN(decode)(JNIEnv *env, jobject thiz, jlong handle, jbyteArray data,
           jint offset, jint len, jshortArray out, jint frameSize, jint fec) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)handle;
    if (dec == NULL) return -1;

    jshort *outBuf = (*env)->GetShortArrayElements(env, out, NULL);
    if (outBuf == NULL) return -1;

    int n;
    if (data == NULL) {
        // Packet loss concealment: libopus synthesises frameSize samples from decoder state.
        n = opus_decode(dec, NULL, 0, outBuf, frameSize, 0);
    } else {
        jbyte *inBuf = (*env)->GetByteArrayElements(env, data, NULL);
        if (inBuf == NULL) {
            (*env)->ReleaseShortArrayElements(env, out, outBuf, JNI_ABORT);
            return -1;
        }
        n = opus_decode(dec, (const unsigned char *)(inBuf + offset), len,
                        outBuf, frameSize, fec);
        (*env)->ReleaseByteArrayElements(env, data, inBuf, JNI_ABORT);
    }
    // Commit decoded samples on success; discard the copy on error so callers see nothing.
    (*env)->ReleaseShortArrayElements(env, out, outBuf, n < 0 ? JNI_ABORT : 0);
    return n;
}

JNIEXPORT jint JNICALL
FN(packetGetNbSamples)(JNIEnv *env, jobject thiz, jbyteArray data,
                       jint offset, jint len, jint sampleRate) {
    jbyte *inBuf = (*env)->GetByteArrayElements(env, data, NULL);
    if (inBuf == NULL) return -1;
    int n = opus_packet_get_nb_samples((const unsigned char *)(inBuf + offset), len, sampleRate);
    (*env)->ReleaseByteArrayElements(env, data, inBuf, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL
FN(destroyDecoder)(JNIEnv *env, jobject thiz, jlong handle) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)handle;
    if (dec != NULL) opus_decoder_destroy(dec);
}
