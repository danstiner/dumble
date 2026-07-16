package me.danielstiner.dumble.mumble.voice

/** In-place noise suppression on a sub-frame of PCM16. One instance per capture stream (stateful). */
interface NoiseSuppressor {
    /** Denoise n samples starting at pcm[off] in place. */
    fun process(pcm: ShortArray, off: Int, n: Int)
    fun close()

    /**
     * Enable/disable actual denoising. A suppressor that also serves as the VAD keeps running for the
     * probability and only stops altering the audio; a plain suppressor with no VAD role may ignore
     * this. Default: no-op (for suppressors that don't denoise, like [None]).
     */
    fun setDenoiseEnabled(enabled: Boolean) {}

    /** No-op suppressor (Phase 1 default). */
    object None : NoiseSuppressor {
        override fun process(pcm: ShortArray, off: Int, n: Int) {}
        override fun close() {}
    }
}
