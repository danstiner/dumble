package me.danielstiner.dumble.mumble.voice

import kotlin.math.roundToLong

/** Pure so it is JVM-testable; the wrap handling below is the part worth a test. */
object LatencyMath {
    /**
     * Audio in flight between our last write and the speaker, in ms: everything written but not yet
     * presented. Best-effort *pipeline* latency — it covers the track buffer and the HAL below it,
     * not the analog path, so it is a floor on mouth-to-ear rather than the whole of it.
     *
     * [tsNanoTime] is CLOCK_MONOTONIC, so it compares directly against System.nanoTime(). The
     * timestamp is a past reading, hence extrapolating the frame position forward to [nowNanos].
     *
     * AudioTrack reports framePosition as the low-order 32 bits despite the field being a Long, so
     * the subtraction is done modulo 2^32 and reinterpreted as a small signed count — valid because
     * real in-flight audio is nowhere near 2^31 frames.
     *
     * Two guards, because a frozen reading is the failure mode that matters and age alone does not
     * catch it. After an inter-spurt gap the platform can return the last pre-gap position, and
     * extrapolating that forward puts "presented" far past "written". Age catches only the long
     * gaps: conversational pauses run 200-800 ms and would clear any [maxStaleNanos] loose enough
     * to be useful, so the overshoot itself is the second signal. Overshoot beyond one quantum
     * cannot be extrapolation rounding — it means the position stalled — so it is refused rather
     * than clamped, because clamping is what turns it into a confident 0.0 ms. Callers already
     * handle null as "no reading"; a wrong number they cannot detect is worse than none.
     *
     * Oboe would need neither guard: AAudio's pull model keeps a started stream's position
     * advancing, while our push model genuinely stops between talk spurts. Negative ages are
     * rejected uniformly rather than permitting small ones, since a mismatched timebase would
     * otherwise produce confident wrong numbers and an occasional lost reading is harmless.
     */
    fun outputLatencyMs(
        framesWritten: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long,
        rate: Int, maxStaleNanos: Long,
    ): Double? {
        val ageNanos = nowNanos - tsNanoTime
        if (ageNanos < 0 || ageNanos > maxStaleNanos) return null
        val presentedNow = tsFramePosition + ((ageNanos * rate) / 1e9).roundToLong()
        val diff32 = (framesWritten - presentedNow) and 0xFFFFFFFFL
        val inFlight = if (diff32 >= 0x80000000L) diff32 - 0x100000000L else diff32
        if (inFlight < -QUANTUM_SAMPLES) return null
        return maxOf(inFlight, 0L).toDouble() / rate * 1000.0
    }
}
