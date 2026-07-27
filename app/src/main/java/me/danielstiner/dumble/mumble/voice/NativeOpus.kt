package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to libopus (see app/src/main/cpp/opus_jni.c). Handles are opaque pointers.
 *  Decode only — the encoder arrives with transmit support.
 *
 *  [createDecoder] returns 0 on failure. [decode] and [packetGetNbSamples] return a negative opus
 *  error code on failure (OPUS_BAD_ARG for bad or out-of-bounds offset/len/frameSize). [decode]
 *  writes the first n samples of `out` and returns n on success, leaves `out` untouched on
 *  failure, caps frameSize at 5760 (120 ms at 48 kHz, the most one decode can produce), and
 *  rejects compressed input over 8 KiB — more than 120 ms at opus's max bitrate can only be
 *  padding. Only data[offset, offset+len) is read, so `data` may be a large reused buffer at no
 *  extra cost. */
object NativeOpus {
    init { System.loadLibrary("dumble") }

    external fun createDecoder(sampleRate: Int, channels: Int): Long
    external fun decode(
        handle: Long, data: ByteArray?, offset: Int, len: Int,
        out: ShortArray, frameSize: Int, fec: Int,
    ): Int
    external fun packetGetNbSamples(data: ByteArray, offset: Int, len: Int, sampleRate: Int): Int
    external fun destroyDecoder(handle: Long)
}
