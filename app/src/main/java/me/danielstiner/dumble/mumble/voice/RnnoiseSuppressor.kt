package me.danielstiner.dumble.mumble.voice

/**
 * RNNoise noise suppression. One persistent DenoiseState per capture stream, fed consecutive
 * 480-sample frames in order (never reset between frames) to preserve RNNoise's internal
 * pitch/overlap continuity. Denoises in place; the cleaned audio feeds both the VAD and the
 * Opus encoder (Mumble-faithful: denoise -> detect/encode).
 */
class RnnoiseSuppressor : NoiseSuppressor {
    private val state = NativeRnnoise.createState().also {
        require(it != 0L) { "rnnoise_create failed" }
    }

    override fun process(pcm: ShortArray, off: Int, n: Int) {
        require(n == FRAME_SAMPLES_10MS) { "RNNoise requires 480-sample frames, got $n" }
        NativeRnnoise.processFrame(state, pcm, off)
    }

    override fun close() { NativeRnnoise.destroyState(state) }
}
