package me.danielstiner.dumble.mumble.voice

/** Thin JNI binding to RNNoise (see app/src/main/cpp/rnnoise_jni.c). State is an opaque pointer. */
object NativeRnnoise {
    init { System.loadLibrary("dumble") }   // Opus + RNNoise are linked into the same shared lib

    external fun createState(): Long
    external fun destroyState(state: Long)
    /** Denoise 480 samples in place starting at pcm[offset]. */
    external fun processFrame(state: Long, pcm: ShortArray, offset: Int)
}
