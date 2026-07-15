package me.danielstiner.dumble.mumble.voice

/**
 * RNNoise noise suppression. One persistent DenoiseState per capture stream, fed consecutive
 * 480-sample frames in order (never reset between frames) to preserve RNNoise's internal
 * pitch/overlap continuity. Denoises in place; the cleaned audio feeds both the VAD and the
 * Opus encoder (Mumble-faithful: denoise -> detect/encode).
 */
class RnnoiseSuppressor : NoiseSuppressor, VadDetector {
    private val state = NativeRnnoise.createState().also {
        require(it != 0L) { "rnnoise_create failed" }
    }

    /** RNNoise's voice-activity probability (0..1) from the most recent [process] call. */
    var lastVadProb: Float = 0f
        private set

    override fun process(pcm: ShortArray, off: Int, n: Int) {
        require(n == FRAME_SAMPLES_10MS) { "RNNoise requires 480-sample frames, got $n" }
        lastVadProb = NativeRnnoise.processFrame(state, pcm, off)
    }

    /**
     * VadDetector: RNNoise computes its VAD probability as a byproduct of denoising, so [level]
     * returns the probability from the [process] call for this same sub-frame. It MUST be called
     * immediately after `process(pcm, off, n)` for that frame — the engine's per-sub-frame loop
     * guarantees this; the args are ignored (the value already came from that process()).
     */
    override fun level(pcm: ShortArray, off: Int, n: Int): Float = lastVadProb

    override fun close() { NativeRnnoise.destroyState(state) }
}
