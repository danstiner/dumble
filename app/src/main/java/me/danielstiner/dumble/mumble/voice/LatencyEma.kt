package me.danielstiner.dumble.mumble.voice

/**
 * Single-value EMA smoother for a latency reading. Pure (no Android deps) → JVM-testable.
 * [valueMs] is written on the audio (read/write) thread and read on the playout/stats thread, so it
 * is @Volatile — a single volatile double is safe per JMM 17.7. NaN until the first sample; v1 does
 * not mark stale — a route that stops reporting timestamps simply holds the last value.
 */
class LatencyEma(private val alpha: Double = 0.1) {
    @Volatile
    var valueMs: Double = Double.NaN
        private set

    fun update(sampleMs: Double) {
        valueMs = if (valueMs.isNaN()) sampleMs else valueMs * (1 - alpha) + sampleMs * alpha
    }
}
