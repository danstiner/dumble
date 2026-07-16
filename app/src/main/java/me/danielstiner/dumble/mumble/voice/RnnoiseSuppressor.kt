package me.danielstiner.dumble.mumble.voice

/**
 * RNNoise noise suppression. One persistent DenoiseState per capture stream, fed consecutive
 * 480-sample frames in order (never reset between frames) to preserve RNNoise's internal
 * pitch/overlap continuity. Denoises in place; the cleaned audio feeds both the VAD and the
 * Opus encoder (Mumble-faithful: denoise -> detect/encode).
 *
 * Denoising can be disabled at runtime while keeping the VAD alive: RNNoise still runs every frame
 * (on a throwaway scratch copy) so its voice-activity probability keeps gating transmission, but the
 * caller's audio is left un-denoised. The DenoiseState advances identically either way — the state
 * update and returned probability are a function of the input frame, not the output buffer — so
 * voice-activation is unaffected by the toggle.
 */
class RnnoiseSuppressor : NoiseSuppressor, VadDetector {
    private val state = NativeRnnoise.createState().also {
        require(it != 0L) { "rnnoise_create failed" }
    }

    // Cross-thread flag: toggled from the UI, read on the capture/send thread.
    @Volatile private var denoiseOn: Boolean = true

    // Reused throwaway frame for the denoise-disabled path (capture/send thread only).
    private val scratch = ShortArray(FRAME_SAMPLES_10MS)

    /** RNNoise's voice-activity probability (0..1) from the most recent [process] call. */
    var lastVadProb: Float = 0f
        private set

    override fun process(pcm: ShortArray, off: Int, n: Int) {
        require(n == FRAME_SAMPLES_10MS) { "RNNoise requires 480-sample frames, got $n" }
        lastVadProb = if (denoiseOn) {
            NativeRnnoise.processFrame(state, pcm, off)
        } else {
            // Denoise a copy so the DenoiseState advances and the VAD prob is produced exactly as in
            // the in-place case, then discard it — pcm[off..off+n) stays raw.
            System.arraycopy(pcm, off, scratch, 0, n)
            NativeRnnoise.processFrame(state, scratch, 0)
        }
    }

    /**
     * VadDetector: RNNoise computes its VAD probability as a byproduct of denoising, so [level]
     * returns the probability from the [process] call for this same sub-frame. It MUST be called
     * immediately after `process(pcm, off, n)` for that frame — the engine's per-sub-frame loop
     * guarantees this; the args are ignored (the value already came from that process()).
     */
    override fun level(pcm: ShortArray, off: Int, n: Int): Float = lastVadProb

    /** Enable/disable denoising. When off, RNNoise keeps running for the VAD but the audio is raw. */
    override fun setDenoiseEnabled(enabled: Boolean) { denoiseOn = enabled }

    override fun close() { NativeRnnoise.destroyState(state) }
}
