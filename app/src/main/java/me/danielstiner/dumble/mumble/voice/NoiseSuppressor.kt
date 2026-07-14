package me.danielstiner.dumble.mumble.voice

/** In-place noise suppression on a sub-frame of PCM16. One instance per capture stream (stateful). */
interface NoiseSuppressor {
    /** Denoise n samples starting at pcm[off] in place. */
    fun process(pcm: ShortArray, off: Int, n: Int)
    fun close()

    /** No-op suppressor (Phase 1 default). */
    object None : NoiseSuppressor {
        override fun process(pcm: ShortArray, off: Int, n: Int) {}
        override fun close() {}
    }
}
