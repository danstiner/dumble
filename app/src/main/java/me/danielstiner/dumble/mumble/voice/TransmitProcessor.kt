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

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)  // pre-gain (gate input)
            subLevels[i] = prob
            gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)    // makeup gain, in place
        }
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
            if (gain.enabled) {
                val prob = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
        }
    }

    fun reset() = gate.reset()
}
