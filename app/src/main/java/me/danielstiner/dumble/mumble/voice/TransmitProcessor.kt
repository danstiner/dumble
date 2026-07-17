package me.danielstiner.dumble.mumble.voice

/**
 * The per-capture transmit-decision core, shared by [AudioVoiceEngine] and the VAD eval harness so
 * both exercise identical logic. Denoises each 10 ms sub-frame in place, computes its VAD level, and
 * runs the gate. Mute, frame numbering, Opus encode, and terminator emission stay in the engine.
 */
class TransmitProcessor(
    private val suppressor: NoiseSuppressor,
    vad: VadDetector,
    val gate: TransmitGate,
    private val gain: GainControl = GainControl(enabled = false),
) {
    /** The active VAD detector. Hot-swappable (e.g. [AudioVoiceEngine.setVadDetector]); the send
     *  thread always reads the current value on its next [process]/[denoise] call. */
    @Volatile var vad: VadDetector = vad
    private val subLevels = FloatArray(FRAMES_PER_PACKET)
    private val rawFrame = ShortArray(FRAME_SAMPLES_10MS)

    /** RNNoise probability from the most recent processed sub-frame (diagnostics only). */
    var lastVadProb: Float = 0f
        private set

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            System.arraycopy(capturePcm, off, rawFrame, 0, FRAME_SAMPLES_10MS)   // raw snapshot
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            // process() ALWAYS reads vad.level() — the gate needs the pre-gain prob regardless of
            // gain state (unlike denoise(), which reads it only for the gain).
            val prob = vad.level(rawFrame, 0, FRAME_SAMPLES_10MS)                 // VAD on RAW
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
            System.arraycopy(capturePcm, off, rawFrame, 0, FRAME_SAMPLES_10MS)
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            // VAD reads the RAW snapshot (pre-denoise). A stateful VAD (Silero) is fine here IF the
            // engine resets it on discontinuities; in PTT the gate is unused so a missed advance is benign.
            if (gain.enabled) {
                val prob = vad.level(rawFrame, 0, FRAME_SAMPLES_10MS)
                lastVadProb = prob
                gain.process(capturePcm, off, FRAME_SAMPLES_10MS, prob)
            }
        }
    }

    fun reset() = gate.reset()
}
