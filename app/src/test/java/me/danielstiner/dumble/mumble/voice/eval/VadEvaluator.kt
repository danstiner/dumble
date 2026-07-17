package me.danielstiner.dumble.mumble.voice.eval

import me.danielstiner.dumble.mumble.voice.*
import kotlin.math.log10
import kotlin.math.sqrt

data class Metrics(
    val coverage: Double,
    val onsetMs: Int,
    val hangoverMs: Int,
    val midDropoutMs: Int,
    val falseOpenings: Double,
    val speechLoudnessDbFs: Double,
    val clipping: Int,
)

object VadEvaluator {
    private const val MS = SAMPLE_RATE / 1000
    private const val CAP_MS = CAPTURE_SAMPLES / MS   // 20 ms per capture

    private fun labelAt(clip: Clip, capIndex: Int): Kind {
        val midMs = capIndex * CAP_MS + CAP_MS / 2
        return clip.segments.firstOrNull { midMs >= it.startMs && midMs < it.endMs }?.kind ?: Kind.SILENCE
    }

    /** Pure metric computation from a per-capture send[] decision array (unit-testable, no DSP). */
    fun scoreDecisions(clip: Clip, send: BooleanArray): Metrics {
        val scoreFromCap = clip.scoreFromMs / CAP_MS
        val firstSpeech = clip.segments.firstOrNull { it.kind == Kind.SPEECH }
        val lastSpeechEnd = clip.segments.lastOrNull { it.kind == Kind.SPEECH }?.endMs ?: 0

        var onsetMs = -1
        for (c in send.indices) {
            if (c < scoreFromCap || firstSpeech == null) continue
            if (send[c] && labelAt(clip, c) == Kind.SPEECH) {
                onsetMs = (c * CAP_MS - firstSpeech.startMs).coerceAtLeast(0); break
            }
        }

        var speechCaps = 0; var speechSent = 0; var falseOpen = 0; var nonSpeechCaps = 0
        for (c in send.indices) {
            if (c < scoreFromCap) continue
            when (labelAt(clip, c)) {
                Kind.SPEECH -> { speechCaps++; if (send[c]) speechSent++ }
                Kind.SILENCE, Kind.NOISE -> {
                    nonSpeechCaps++
                    if (send[c] && (c == 0 || !send[c - 1])) falseOpen++
                }
                Kind.PAUSE -> { /* exempt from coverage + false-activation */ }
            }
        }

        // Mid-utterance dropout: only SPEECH captures that were NOT sent count. Captures in a
        // PAUSE (a real gap the gate is allowed to close on) or SILENCE must be excluded, or a
        // multi-region clip's legit inter-region pauses would be miscounted as dropouts.
        var midDropMs = 0
        if (onsetMs >= 0 && firstSpeech != null) {
            val onsetCap = (firstSpeech.startMs + onsetMs) / CAP_MS
            val lastCap = lastSpeechEnd / CAP_MS
            for (c in onsetCap until lastCap)
                if (c in send.indices && !send[c] && labelAt(clip, c) == Kind.SPEECH) midDropMs += CAP_MS
        }

        var hangoverMs = 0
        for (c in send.indices) if (c * CAP_MS >= lastSpeechEnd && send[c]) hangoverMs += CAP_MS

        val coverage = if (speechCaps == 0) 1.0 else speechSent.toDouble() / speechCaps
        val nonSpeechSecs = (nonSpeechCaps * CAP_MS) / 1000.0
        val falsePer10s = if (nonSpeechSecs <= 0) 0.0 else falseOpen / nonSpeechSecs * 10.0
        return Metrics(coverage, onsetMs.coerceAtLeast(0), hangoverMs, midDropMs, falsePer10s, 0.0, 0)
    }

    /** Silero VAD over the corpus. denoise=false → Silero sees raw PCM; denoise=true → Silero sees
     *  RNNoise-denoised PCM. Dedicated loop (NOT TransmitProcessor, whose raw-snapshot always feeds the
     *  VAD raw). Silero resamples 48k→16k internally. */
    fun evaluateSilero(clip: Clip, denoise: Boolean, modelBytes: ByteArray): Metrics {
        val silero = SileroVadDetector(SileroOnnxSession(modelBytes))
        val rnnoise = if (denoise) RnnoiseSuppressor() else null
        val gate = TransmitGate()
        try {
            val caps = clip.pcm.size / CAPTURE_SAMPLES
            val send = BooleanArray(caps)
            val cap = ShortArray(CAPTURE_SAMPLES)
            val subLevels = FloatArray(FRAMES_PER_PACKET)
            for (c in 0 until caps) {
                System.arraycopy(clip.pcm, c * CAPTURE_SAMPLES, cap, 0, CAPTURE_SAMPLES)
                for (i in 0 until FRAMES_PER_PACKET) {
                    val off = i * FRAME_SAMPLES_10MS
                    if (denoise) rnnoise!!.process(cap, off, FRAME_SAMPLES_10MS)   // denoise in place
                    subLevels[i] = silero.level(cap, off, FRAME_SAMPLES_10MS)      // Silero on (denoised|raw) sub-frame
                }
                send[c] = gate.update(subLevels).send
            }
            return scoreDecisions(clip, send)
        } finally {
            silero.close(); rnnoise?.close()
        }
    }

    /** Full DSP evaluation: fresh RNNoise + gate per clip, optional makeup gain. */
    fun evaluate(clip: Clip, gain: GainControl? = null): Metrics {
        val suppressor = RnnoiseSuppressor()
        try {
            val proc = TransmitProcessor(
                suppressor, suppressor, TransmitGate(),
                gain ?: GainControl(enabled = false))
            val caps = clip.pcm.size / CAPTURE_SAMPLES
            val send = BooleanArray(caps)
            var speechSumSq = 0.0; var speechSamples = 0L; var clip16 = 0
            val cap = ShortArray(CAPTURE_SAMPLES)
            for (c in 0 until caps) {
                System.arraycopy(clip.pcm, c * CAPTURE_SAMPLES, cap, 0, CAPTURE_SAMPLES)
                val d = proc.process(cap)   // denoises cap in place
                send[c] = d.send
                if (d.send && labelAt(clip, c) == Kind.SPEECH) {
                    for (s in cap) { speechSumSq += s.toDouble() * s; if (s.toInt() == 32767 || s.toInt() == -32768) clip16++ }
                    speechSamples += CAPTURE_SAMPLES
                }
            }
            val base = scoreDecisions(clip, send)
            val loud = if (speechSamples == 0L) -120.0
                       else 20.0 * log10(sqrt(speechSumSq / speechSamples) / 32768.0)
            return base.copy(speechLoudnessDbFs = loud, clipping = clip16)
        } finally {
            suppressor.close()
        }
    }
}
