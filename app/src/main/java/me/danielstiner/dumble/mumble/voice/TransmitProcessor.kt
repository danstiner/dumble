package me.danielstiner.dumble.mumble.voice

/**
 * The per-capture transmit-decision core, shared by [AudioVoiceEngine] and the VAD eval harness so
 * both exercise identical logic. Denoises each 10 ms sub-frame in place, computes its VAD level, and
 * runs the gate. Mute, frame numbering, Opus encode, and terminator emission stay in the engine.
 */
class TransmitProcessor(
    private val suppressor: NoiseSuppressor,
    private val vad: VadDetector,
    val gate: TransmitGate,
    private val gain: GainControl = GainControl(enabled = false),
) {
    private val subLevels = FloatArray(FRAMES_PER_PACKET)

    /** RNNoise probability from the most recent processed sub-frame (diagnostics only). */
    var lastVadProb: Float = 0f
        private set

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            // process() ALWAYS reads vad.level() — the gate needs the pre-gain prob regardless of
            // gain state (unlike denoise(), which reads it only for the gain).
            val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)  // pre-gain (gate input)
            subLevels[i] = prob
            gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)    // makeup gain, in place
        }
        lastVadProb = subLevels[FRAMES_PER_PACKET - 1]
        return gate.update(subLevels)
    }

    /**
     * Denoise [capturePcm] (CAPTURE_SAMPLES) in place per 10 ms sub-frame, WITHOUT running the gate.
     * Used by the push-to-talk path. The makeup gain still applies (a quiet talker is quiet in PTT
     * too); the RNNoise probability it needs is read through [vad] only when the gain is enabled, so
     * a disabled gain keeps this a pure denoise (no VAD).
     */
    fun denoise(capturePcm: ShortArray) {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            // NOTE: denoise() reads vad.level() only when gain is enabled — safe only because the
            // paired VAD (RnnoiseSuppressor) has a side-effect-free level(). Do not wire a stateful
            // VAD (e.g. EnergyVadDetector) here: its level() mutates the noise floor every call, so
            // skipping it when gain is disabled would drift.
            if (gain.enabled) {
                val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
                lastVadProb = prob
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
        }
    }

    fun reset() = gate.reset()
}
