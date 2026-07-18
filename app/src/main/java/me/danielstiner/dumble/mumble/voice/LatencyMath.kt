package me.danielstiner.dumble.mumble.voice

import kotlin.math.roundToLong

/**
 * Pure latency arithmetic for AudioTrack/AudioRecord getTimestamp readings. No Android deps →
 * JVM-testable. Both functions clamp to >= 0 (extrapolation overshoot or a stalled position can push
 * the raw value slightly negative).
 *
 * getTimestamp semantics (fable-verified against AOSP, see the design doc): timestamp.nanoTime is
 * TIMEBASE_MONOTONIC, directly comparable to System.nanoTime(); the result is best-effort *pipeline*
 * latency, not acoustic mouth-to-ear latency.
 */
object LatencyMath {
    /**
     * Playout latency ms: in-flight audio between app and output = framesWritten - framesPresentedNow.
     * AudioTrack.framePosition is the LOW-ORDER 32 BITS in wrapping frame units (despite the long
     * field), so the difference is taken modulo 2^32 and reinterpreted as the small signed in-flight
     * count (true in-flight << 2^31 frames).
     */
    fun outputLatencyMs(
        framesWritten: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val presentedNow = tsFramePosition + ((nowNanos - tsNanoTime) * rate / 1e9).roundToLong()
        val diff32 = (framesWritten - presentedNow) and 0xFFFFFFFFL
        val inFlight = if (diff32 >= 0x80000000L) diff32 - 0x100000000L else diff32
        return maxOf(inFlight, 0L).toDouble() / rate * 1000.0
    }

    /**
     * Capture latency ms: now - capture-time-of-newest-read-frame. AudioRecord.framePosition uses all
     * 64 bits (no wrap), so plain arithmetic is safe.
     */
    fun inputLatencyMs(
        framesRead: Long, tsFramePosition: Long, tsNanoTime: Long, nowNanos: Long, rate: Int,
    ): Double {
        val captureTimeNanos = tsNanoTime + (framesRead - tsFramePosition) * 1_000_000_000L / rate
        return maxOf(nowNanos - captureTimeNanos, 0L).toDouble() / 1e6
    }
}
