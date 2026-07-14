package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to libopus (see app/src/main/cpp/opus_jni.c). Handles are opaque pointers. */
object NativeOpus {
    init { System.loadLibrary("dumble") }

    external fun createEncoder(sampleRate: Int, channels: Int, application: Int): Long
    external fun configureEncoder(enc: Long, bitrate: Int, complexity: Int): Int
    external fun createDecoder(sampleRate: Int, channels: Int): Long
    external fun encode(enc: Long, pcm: ShortArray, frameSize: Int, out: ByteArray, maxBytes: Int): Int
    external fun decode(dec: Long, data: ByteArray?, offset: Int, len: Int, out: ShortArray, frameSize: Int, fec: Int): Int
    external fun packetGetNbSamples(data: ByteArray, offset: Int, len: Int, sampleRate: Int): Int
    external fun destroyEncoder(enc: Long)
    external fun destroyDecoder(dec: Long)
}
