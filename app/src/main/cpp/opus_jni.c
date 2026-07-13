#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <opus.h>

#define PKG(name) Java_me_danielstiner_dumble_mumble_voice_NativeOpus_##name

JNIEXPORT jlong JNICALL PKG(createEncoder)(JNIEnv *e, jobject o, jint sr, jint ch, jint app) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sr, ch, app, &err);
    return (err == OPUS_OK) ? (jlong)(intptr_t)enc : 0;
}

JNIEXPORT jint JNICALL PKG(configureEncoder)(JNIEnv *e, jobject o, jlong h, jint bitrate, jint complexity) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)h;
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(enc, OPUS_SET_VBR(1));
    opus_encoder_ctl(enc, OPUS_SET_VBR_CONSTRAINT(1));       /* CVBR */
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(0));           /* FEC off in v1 */
    opus_encoder_ctl(enc, OPUS_SET_DTX(0));                  /* DTX off in v1 */
    return OPUS_OK;
}

JNIEXPORT jlong JNICALL PKG(createDecoder)(JNIEnv *e, jobject o, jint sr, jint ch) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sr, ch, &err);
    return (err == OPUS_OK) ? (jlong)(intptr_t)dec : 0;
}

JNIEXPORT jint JNICALL PKG(encode)(JNIEnv *e, jobject o, jlong h, jshortArray pcm, jint frameSize, jbyteArray out, jint maxBytes) {
    OpusEncoder *enc = (OpusEncoder *)(intptr_t)h;
    jshort *in = (*e)->GetShortArrayElements(e, pcm, NULL);
    jbyte *ob = (*e)->GetByteArrayElements(e, out, NULL);
    int n = opus_encode(enc, in, frameSize, (unsigned char *)ob, maxBytes);
    (*e)->ReleaseShortArrayElements(e, pcm, in, JNI_ABORT);
    (*e)->ReleaseByteArrayElements(e, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL PKG(decode)(JNIEnv *e, jobject o, jlong h, jbyteArray data, jint offset, jint len, jshortArray out, jint frameSize, jint fec) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)h;
    jshort *ob = (*e)->GetShortArrayElements(e, out, NULL);
    int n;
    if (data == NULL) {
        n = opus_decode(dec, NULL, 0, ob, frameSize, fec);      /* PLC */
    } else {
        jbyte *db = (*e)->GetByteArrayElements(e, data, NULL);
        n = opus_decode(dec, (const unsigned char *)(db + offset), len, ob, frameSize, fec);
        (*e)->ReleaseByteArrayElements(e, data, db, JNI_ABORT);
    }
    (*e)->ReleaseShortArrayElements(e, out, ob, 0);
    return n;
}

JNIEXPORT jint JNICALL PKG(packetGetNbSamples)(JNIEnv *e, jobject o, jbyteArray data, jint offset, jint len, jint sr) {
    jbyte *db = (*e)->GetByteArrayElements(e, data, NULL);
    int n = opus_packet_get_nb_samples((const unsigned char *)(db + offset), len, sr);
    (*e)->ReleaseByteArrayElements(e, data, db, JNI_ABORT);
    return n;
}

JNIEXPORT void JNICALL PKG(destroyEncoder)(JNIEnv *e, jobject o, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

JNIEXPORT void JNICALL PKG(destroyDecoder)(JNIEnv *e, jobject o, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}
