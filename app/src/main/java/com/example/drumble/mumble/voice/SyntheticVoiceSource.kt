package com.example.drumble.mumble.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

data class LoopbackStats(
    val sent: Long = 0, val received: Long = 0, val lost: Long = 0,
    val lastRttMs: Double = -1.0, val avgRttMs: Double = -1.0, val jitterMs: Double = 0.0,
)

/**
 * Self-clocked synthetic frame source for loopback validation (no audio hardware,
 * so absolute-deadline pacing is correct here — no producer clock to phase-match).
 * Payload: [8B big-endian send-nanos][filler] — RTT computed on echo.
 */
class SyntheticVoiceSource(
    private val frameIntervalNanos: Long = 10_000_000L,
    private val payloadSize: Int = 40,
    private val clockNanos: () -> Long = System::nanoTime,
) : VoiceEngine {
    private val _stats = MutableStateFlow(LoopbackStats())
    val stats: StateFlow<LoopbackStats> = _stats.asStateFlow()

    @Volatile private var running = false
    private var nextDeadline = 0L
    private var frameNumber = 0L

    private var highestSeen = -1L
    private var lostCount = 0L
    private var receivedCount = 0L
    private var avgRtt = -1.0
    private var jitter = 0.0
    private var lastRtt = -1.0

    override fun start() {
        running = true
        frameNumber = 0
        nextDeadline = clockNanos() + frameIntervalNanos
    }

    override fun stop() { running = false }

    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        if (!running) return null
        val wait = nextDeadline - clockNanos()
        if (wait > timeoutNanos) { LockSupport.parkNanos(timeoutNanos); return null }
        if (wait > 0) LockSupport.parkNanos(wait)
        if (!running) return null
        nextDeadline += frameIntervalNanos
        val payload = ByteArray(payloadSize)
        val sendNanos = clockNanos()
        for (i in 0 until 8) payload[i] = (sendNanos ushr ((7 - i) * 8)).toByte()
        val fn = frameNumber++
        _stats.update { it.copy(sent = fn + 1) }
        return VoiceFrame(payload, payloadSize, fn)
    }

    @Synchronized
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
        if (length < 8) return
        var sendNanos = 0L
        for (i in 0 until 8) sendNanos = (sendNanos shl 8) or (opusData[offset + i].toLong() and 0xFF)
        val rttMs = (arrivalNanos - sendNanos) / 1e6
        receivedCount++
        if (frameNumber > highestSeen) {
            if (highestSeen >= 0) lostCount += frameNumber - highestSeen - 1
            highestSeen = frameNumber
        } else if (lostCount > 0) {
            lostCount--
        }
        if (lastRtt >= 0) jitter += (abs(rttMs - lastRtt) - jitter) / 16.0
        lastRtt = rttMs
        avgRtt = if (avgRtt < 0) rttMs else avgRtt * 0.9 + rttMs * 0.1
        _stats.update {
            it.copy(received = receivedCount, lost = lostCount,
                lastRttMs = rttMs, avgRttMs = avgRtt, jitterMs = jitter)
        }
    }
}
