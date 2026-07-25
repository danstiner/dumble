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

    // Trust-boundary check, not a redundant assertion: frameSize/offset/len are caller-supplied
    // and the Kotlin layer does not re-validate them (LibOpusDecoder always requests
    // MAX_FRAME_SAMPLES, regardless of the real size of `out`). libopus itself does no bounds
    // checking — it writes frameSize samples into out and reads len bytes from data at offset
    // unconditionally — so an unchecked mismatch here is a heap overflow or OOB read, not merely
    // a wrong decode result. Checked before any array is pinned, so these early returns have
    // nothing to release.
    if (frameSize < 0 || frameSize > (*env)->GetArrayLength(env, out)) return -1;
    if (data != NULL) {
        jsize dataLen = (*env)->GetArrayLength(env, data);
        if (offset < 0 || len < 0 || offset > dataLen || len > dataLen - offset) return -1;
    }

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
    // Trust-boundary check: offset/len are caller-supplied and opus_packet_get_nb_samples does no
    // bounds checking of its own, so an unchecked mismatch here is an OOB read. Checked before the
    // array is pinned, so this early return has nothing to release.
    jsize dataLen = (*env)->GetArrayLength(env, data);
    if (offset < 0 || len < 0 || offset > dataLen || len > dataLen - offset) return -1;

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
