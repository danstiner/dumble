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
) {
    private val subLevels = FloatArray(FRAMES_PER_PACKET)

    /** Denoise [capturePcm] (CAPTURE_SAMPLES) in place, then decide send/terminator for this capture. */
    fun process(capturePcm: ShortArray): TransmitGate.Decision {
        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        return gate.update(subLevels)
    }

    fun reset() = gate.reset()
}
