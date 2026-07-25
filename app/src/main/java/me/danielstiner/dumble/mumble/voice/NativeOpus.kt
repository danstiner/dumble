package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to libopus (see app/src/main/cpp/opus_jni.c). Handles are opaque pointers.
 *  Decode only — the encoder arrives with transmit support. */
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
