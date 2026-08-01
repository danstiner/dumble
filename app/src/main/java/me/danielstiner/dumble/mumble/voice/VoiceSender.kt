package me.danielstiner.dumble.mumble.voice

import android.os.Process
import android.util.Log
import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpMessageType

/**
 * Pulls encoded packets off the native engine and puts them on the wire.
 *
 * The pump thread spends nearly all of its time blocked inside [CaptureHandle.pollFrame]'s real
 * counterpart, in native code. That is deliberate: it costs no CPU, and a thread parked in JNI
 * does not stall a stop-the-world GC. It also cannot be interrupted, which is why [stop] works
 * through the engine rather than through [Thread.interrupt].
 */
class VoiceSender(
    private val handle: CaptureHandle,
    private val send: (TcpMessageType, ByteArray) -> Boolean,
) {
    /** Seam so JVM tests can drive the pump without loading native code. */
    interface CaptureHandle {
        fun pollFrame(out: ByteArray, meta: LongArray): Int
        fun setGateOpen(open: Boolean)
        fun stop()

        /** Releases the engine. Called by whoever owns the session, never by the pump. */
        fun destroy()

        /** Diagnostics for the periodic log line; null when there is nothing to read. */
        fun stats(): CaptureStats?
    }

    /**
     * Why the pump last exited. Set only by [pump] itself, from what `pollFrame` actually
     * returned — never by [stop] — so it still reads [UNAVAILABLE] even if a caller's generic
     * teardown calls [stop] afterward.
     */
    enum class StopReason { REQUESTED, UNAVAILABLE }

    @Volatile var droppedFrames = 0; private set
    @Volatile private var thread: Thread? = null
    @Volatile private var stopped = false
    val isRunning: Boolean get() = thread?.isAlive == true

    /** Null while running or before the first [start]. */
    @Volatile var stopReason: StopReason? = null; private set

    /**
     * Single-shot, like [me.danielstiner.dumble.mumble.net.MumbleTcpTransport.connect]: the
     * engine's shutdown latch never resets, so a sender restarted after [stop] would only run a
     * pump that exits immediately on [NativeCapture.POLL_SHUTDOWN]. Refused rather than allowed to
     * look like it worked; build a sender per session.
     */
    fun start() {
        if (stopped || thread != null) return
        stopReason = null
        thread = Thread({ pump() }, "dumble-voice-send").apply { isDaemon = true; start() }
    }

    /**
     * Blocks until the pump has exited. Not instant: [CaptureHandle.stop] goes through native
     * teardown, which waits out any reopen attempt in flight and then stops and closes the stream,
     * so it can take hundreds of milliseconds. Call it off the main thread.
     */
    fun stop() {
        stopped = true
        handle.stop()
        thread?.let {
            // Not a tuned deadline — the pump's real exit bound is sub-millisecond, since
            // requestShutdown() wakes it from pollFrame's condition variable and transmit()'s
            // trySend cannot block. This is three orders of magnitude above that, so reaching it
            // means the pump is wedged somewhere the shutdown check cannot reach.
            it.join(STUCK_PUMP_MILLIS)
            if (it.isAlive) {
                // Keeping the reference is the point of checking: start() refuses while it is set,
                // so a pump we could not join can never be joined by a second one on the same
                // handle. Both would then poll and transmit.
                Log.w(TAG, "send thread did not exit within ${STUCK_PUMP_MILLIS}ms; not releasing it")
                return
            }
        }
        thread = null
    }

    fun setTransmitting(on: Boolean) = handle.setGateOpen(on)

    private fun pump() {
        // Process, not Thread.priority: Android documents these levels as settable only this way
        // and applies them to the calling thread, which is why this is here and not in start().
        // AUDIO rather than URGENT_AUDIO — urgent is documented as being for time-critical audio
        // processing that can bound its CPU per time slice, and this thread ends in a socket
        // write, so it can promise nothing of the sort. It still needs the bump: pollFrame drops
        // samples once the ring passes kHighWaterSamples, so a pump descheduled for ~100 ms loses
        // audio. Best-effort, like the playback thread's — a refusal costs cadence, not the call.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onFailure { Log.w(TAG, "could not raise send thread priority", it) }

        // Preallocated: this runs 50 times a second and per-packet garbage is avoidable here even
        // though the protobuf builder below allocates regardless.
        val frame = ByteArray(NativeCapture.MAX_PACKET_BYTES)
        val meta = LongArray(2)
        var nextStatsAt = System.nanoTime() + STATS_INTERVAL_NANOS
        while (true) {
            val n = handle.pollFrame(frame, meta)
            // Debug, not info: six lines a minute for a whole session is noise in a log read for
            // anything else. Not verbose — that level is conventionally stripped from release
            // builds, and being readable off a shipped build is the point of collecting this.
            //
            // Every interval for as long as the pump runs, not only while the gate is open:
            // capture is continuous by design and discards when closed, so overruns and XRuns
            // accrue whether or not anyone is talking. Gating the log on transmit would hide
            // exactly the ones nobody was around to see.
            if (System.nanoTime() >= nextStatsAt) {
                nextStatsAt = System.nanoTime() + STATS_INTERVAL_NANOS
                handle.stats()?.let { Log.d(TAG, it.copy(droppedFrames = droppedFrames).summary()) }
            }
            // A packet is the nominal outcome, so it is handled here rather than as the `else` of
            // the codes below — that way `else` can mean what it should: something nobody planned
            // for. Everything past this point is non-positive.
            if (n > 0) {
                transmit(frame, n, meta)
                continue
            }
            when (n) {
                // The wait inside pollFrame elapsed with nothing to send. It already blocked, so
                // going straight round again is not a spin.
                0 -> {}
                // The stream is down and native code is reopening it. Keep polling: exiting here
                // would leave the reopened stream with nobody draining it, silently killing
                // transmit for the rest of the connection while receive kept working. Below API
                // 37, AAudio disconnects on essentially every routed-device change, so this is
                // not a rare path. Also already blocked before returning.
                NativeCapture.POLL_RETRY -> {}
                NativeCapture.POLL_SHUTDOWN -> {
                    stopReason = StopReason.REQUESTED
                    return
                }
                // Native exhausted its reopen backoff and gave up for good (see
                // NativeCapture.POLL_UNAVAILABLE) — unlike RETRY there is no recovery short of
                // destroy()+create(), so unlike RETRY this is terminal rather than something to
                // poll through.
                NativeCapture.POLL_UNAVAILABLE -> {
                    Log.w(TAG, "capture engine unavailable; transmit stopped for this session")
                    stopReason = StopReason.UNAVAILABLE
                    return
                }
                // Contract violations, not conditions: a dead handle or an undersized `frame`.
                // Neither resolves by polling again, and neither blocks first, so treating them as
                // retryable would spin a thread at full speed forever. Not a separate StopReason
                // because the user-visible outcome is the same — transmit is gone for the session
                // — and the log line is what tells the two apart.
                NativeCapture.POLL_NO_SESSION, NativeCapture.POLL_BUFFER_TOO_SMALL -> {
                    Log.e(TAG, "pollFrame rejected the call ($n); transmit stopped for this session")
                    stopReason = StopReason.UNAVAILABLE
                    return
                }
                // A code this build has never heard of: native grew an outcome and Kotlin was not
                // updated with it. Whether it blocks first is unknowable from here, so polling on
                // risks a full-speed spin — stop, and say so loudly enough to find in a log.
                else -> {
                    Log.e(TAG, "unknown pollFrame result $n; transmit stopped for this session")
                    stopReason = StopReason.UNAVAILABLE
                    return
                }
            }
        }
    }

    private fun transmit(frame: ByteArray, n: Int, meta: LongArray) {
        val audio = MumbleUdpProtos.Audio.newBuilder()
            // 0 is normal talking; 31 would be the server's loopback target.
            .setTarget(NORMAL_TALKING_TARGET)
            .setFrameNumber(meta[NativeCapture.META_FRAME_NUMBER])
            .setIsTerminator(
                meta[NativeCapture.META_FLAGS] and NativeCapture.FLAG_TERMINATOR != 0L)
            .setOpusData(ByteString.copyFrom(frame, 0, n))
            .build()
        val body = audio.toByteArray()
        val payload = ByteArray(body.size + 1)
        payload[0] = UDP_TYPE_AUDIO
        body.copyInto(payload, 1)
        if (!send(TcpMessageType.UDPTunnel, payload)) {
            // The transport's queue is full, meaning the socket has stalled. Dropping current
            // audio is the right outcome — it is stale by the time the stall clears — so this is
            // counted rather than retried.
            droppedFrames++
        }
    }

    companion object {
        private const val TAG = "VoiceSender"
        const val NORMAL_TALKING_TARGET = 0
        private const val UDP_TYPE_AUDIO: Byte = 0
        private const val STUCK_PUMP_MILLIS = 1_000L
        // Ten seconds: a minute of talking is six readable lines rather than a wall of noise.
        private const val STATS_INTERVAL_NANOS = 10_000_000_000L
    }
}

/** Production seam: binds a native engine handle to the pump. */
class NativeCaptureHandle(private val handle: Long) : VoiceSender.CaptureHandle {
    override fun pollFrame(out: ByteArray, meta: LongArray) = NativeCapture.pollFrame(handle, out, meta)
    override fun setGateOpen(open: Boolean) = NativeCapture.setGateOpen(handle, open)
    override fun stop() = NativeCapture.stop(handle)
    override fun destroy() = NativeCapture.destroy(handle)
    override fun stats() = CaptureStats.read(handle)
}

/**
 * Build a started capture engine, or null if the microphone could not be opened. Both failures are
 * terminal for the session rather than retryable: libopus refusing an encoder is the only way
 * create() returns 0, and native has already exhausted its own reopen backoff by the time start()
 * fails. Destroys the engine on a failed start so the handle cannot leak.
 */
fun openNativeCapture(): VoiceSender.CaptureHandle? {
    val handle = NativeCapture.create(TRANSMIT_BITRATE)
    if (handle == 0L) {
        Log.e("VoiceSender", "capture engine could not be created")
        return null
    }
    val rc = NativeCapture.start(handle)
    if (rc != 0) {
        Log.e("VoiceSender", "capture engine could not be started ($rc)")
        NativeCapture.destroy(handle)
        return null
    }
    return NativeCaptureHandle(handle)
}
