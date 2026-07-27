#include <jni.h>
#include <stdint.h>
#include <opus.h>

#define FN(name) Java_me_danielstiner_dumble_mumble_voice_NativeOpus_##name

/*
 * Bounds on one opus_decode call, i.e. one RFC 6716 opus packet — not a Mumble datagram, which
 * merely carries one. The spec caps a packet at 120 ms of audio.
 */
/* 120 ms at 48 kHz; opus_decode's frame_size arg caps how much one packet may produce. */
#define MAX_PACKET_SAMPLES 5760
/* 120 ms at opus's 510 kb/s ceiling is 7650 bytes; anything larger can only be padding from an
 * untrusted peer — reject it rather than size the stack for it. */
#define MAX_PACKET_BYTES 8192

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
    if (dec == NULL) return OPUS_BAD_ARG;

    // Trust boundary: frameSize/offset/len are caller-supplied and unchecked by the Kotlin layer.
    // libopus does no bounds checking, so frameSize is what stops it writing past pcm[] below.
    // Mono assumption: frameSize counts samples, not sample-sets; multi-channel needs * channels.
    if (frameSize < 0 || frameSize > MAX_PACKET_SAMPLES ||
        frameSize > (*env)->GetArrayLength(env, out)) return OPUS_BAD_ARG;

    // Decode into the stack, then copy out only the samples produced. Pinning `out` instead would
    // round-trip the whole worst-case-sized array (~23 KB of cache churn per 10 ms frame to move
    // ~1 KB of samples); a critical pin avoids the copy but couples ART's moving GC to the audio
    // path for the whole decode. Untouched stack bytes cost nothing.
    jshort pcm[MAX_PACKET_SAMPLES];
    int n;
    if (data == NULL) {
        // Packet loss concealment: libopus synthesises frameSize samples from decoder state.
        n = opus_decode(dec, NULL, 0, pcm, frameSize, 0);
    } else {
        jsize dataLen = (*env)->GetArrayLength(env, data);
        if (offset < 0 || len < 0 || len > MAX_PACKET_BYTES ||
            offset > dataLen || len > dataLen - offset) return OPUS_BAD_ARG;
        // Region copy costs len bytes no matter how big the caller's array is; pinning would
        // copy the whole array (or, critical, stall the GC across the decode).
        jbyte packet[MAX_PACKET_BYTES];
        (*env)->GetByteArrayRegion(env, data, offset, len, packet);
        n = opus_decode(dec, (const unsigned char *)packet, len, pcm, frameSize, fec);
    }
    // n <= frameSize <= out.length, so this cannot throw. On error `out` is untouched.
    if (n > 0) (*env)->SetShortArrayRegion(env, out, 0, n, pcm);
    return n;
}

JNIEXPORT jint JNICALL
FN(packetGetNbSamples)(JNIEnv *env, jobject thiz, jbyteArray data,
                       jint offset, jint len, jint sampleRate) {
    // Trust boundary: offset/len are caller-supplied and opus_packet_get_nb_samples does no bounds
    // checking, so a mismatch is an OOB read.
    jsize dataLen = (*env)->GetArrayLength(env, data);
    if (offset < 0 || len < 0 || offset > dataLen || len > dataLen - offset) return OPUS_BAD_ARG;

    // The sample count is fully determined by the TOC byte plus the code-3 frame-count byte, so
    // any len >= 2 gives the same answer; copy only those and pass the clamped len for opus's
    // own too-short validation.
    jbyte hdr[2];
    jint hdrLen = len < 2 ? len : 2;
    (*env)->GetByteArrayRegion(env, data, offset, hdrLen, hdr);
    return opus_packet_get_nb_samples((const unsigned char *)hdr, hdrLen, sampleRate);
}

JNIEXPORT void JNICALL
FN(destroyDecoder)(JNIEnv *env, jobject thiz, jlong handle) {
    OpusDecoder *dec = (OpusDecoder *)(intptr_t)handle;
    if (dec != NULL) opus_decoder_destroy(dec);
}
