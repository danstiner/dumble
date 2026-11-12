package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.os.Process
import android.util.Log
import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.voice.latency.CapturedPacket
import me.danielstiner.dumble.time.BootTimeSource
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Pulls encoded packets off the native engine and puts them on the wire. The pump thread blocks
 * in native code (no CPU cost, no GC stall). Cannot be interrupted — [stop] works through the
 * engine.
 */
class VoiceSender(
    private val handle: CaptureHandle,
    /** Hands a finished packet, type byte first, to whichever transport carries voice. */
    private val send: (ByteArray) -> Boolean,
    /** Fired once, by the [step] that ends the pump, after its last use of [handle]. */
    private val onExit: (VoiceSender) -> Unit,
    /** Fired on the pump thread when we start or stop speaking. A packet of speech that reaches
     *  the wire raises it; a hangover packet, a terminator, a refused send, [SPEECH_STALL] with no
     *  packet, and the pump's end lower it. */
    private val onSpeaking: (Boolean) -> Unit = {},
    /** Fired on the pump thread every [statsInterval] with the engine's counters, [droppedFrames]
     *  filled in. */
    private val onStats: (CaptureStats) -> Unit = {},
    private val statsInterval: Duration = STATS_INTERVAL,
    /** Seam: the clock of the stats cadence and the speech stall, so a test that steps the pump
     *  can run both on virtual time. */
    private val clock: TimeSource = TimeSource.Monotonic,
    /** Seam: the clock of [capturedTap]'s marks, shared with [VoiceReceiver.arrivedTap] so a latency
     *  check can subtract one from the other. */
    private val tapClock: TimeSource.WithComparableMarks = BootTimeSource,
) {
    /** Seam so JVM tests can drive the pump without loading native code. */
    interface CaptureHandle {
        fun pollPacket(out: ByteArray, meta: LongArray): Int
        fun setGateOpen(open: Boolean)
        fun setTransmitMode(mode: TransmitMode)
        fun stop()

        /** Releases the engine. Called by whoever owns the session, never by the pump. */
        fun destroy()

        /** Diagnostics for the periodic log line; null when there is nothing to read. */
        fun stats(): CaptureStats?
    }

    /** Why the pump last exited. Set by [step], not [stop], so the real reason survives teardown. */
    enum class StopReason { REQUESTED, UNAVAILABLE }

    @Volatile var droppedFrames = 0; private set

    /** Who the server sends our voice to: [NORMAL_TALKING_TARGET], or [SERVER_LOOPBACK_TARGET]
     *  while a latency check runs. */
    @Volatile var target = NORMAL_TALKING_TARGET

    /** Set while a latency check listens: each packet capture produced, on the pump thread, before
     *  it is sent and whether or not the send succeeds. A throw from it is logged and the packet
     *  goes out. */
    @Volatile var capturedTap: ((CapturedPacket) -> Unit)? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var stopped = false

    /** Null while running or before the first [start]; left null by a poll that throws, which the
     *  owner reads as terminal. */
    @Volatile var stopReason: StopReason? = null; private set

    // The pump's own state: touched only by whichever thread steps it.
    private val frame = ByteArray(NativeCapture.MAX_PACKET_BYTES)
    private val meta = LongArray(2)
    private var nextStatsAt = clock.markNow() + statsInterval
    private var readings = 0
    private var speaking = false
    private var lastSpeech = clock.markNow()

    /** Single-shot: the engine's shutdown latch never resets. Build a sender per session. */
    fun start() {
        if (stopped || thread != null) return
        stopReason = null
        thread = Thread({
            // AUDIO not URGENT_AUDIO: this thread does socket writes, not bounded-time processing.
            // Still needed: the pump drops samples past kHighWaterSamples, so ~100 ms off-CPU loses audio.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                .onFailure { Log.w(TAG, "could not raise send thread priority", it) }
            while (step()) {}
        }, "dumble-voice-send").apply { isDaemon = true; start() }
    }

    /**
     * Requests shutdown and waits, bounded, for the pump to exit: the stream closes on its way
     * out, and the caller's one-microphone invariant needs that to have happened before it opens
     * another. A pump wedged in native code past the bound is left to [onExit] and the
     * connection's wedge check, as before.
     */
    fun stop() {
        stopped = true
        handle.stop()
        thread?.join(STOP_JOIN_MILLIS)
    }

    fun setTransmitting(on: Boolean) = handle.setGateOpen(on)

    /**
     * One poll and what it asks for. False once the pump has ended, [onExit] having fired — on a
     * throw too, which then propagates. [start] loops it on the pump thread; a test calls it
     * directly, and not again after false.
     */
    fun step(): Boolean {
        var running = false
        try {
            running = pollOnce()
        } finally {
            if (!running) {
                speak(false)
                onExit(this)
            }
        }
        return running
    }

    private fun pollOnce(): Boolean {
        val n = handle.pollPacket(frame, meta)
        val tap = capturedTap
        if (n > 0 && tap != null) {
            val at = tapClock.markNow()
            try {
                tap(CapturedPacket(meta[NativeCapture.META_FRAME_NUMBER], frame.copyOf(n), at))
            } catch (t: Throwable) {
                // A check's bug must not end the pump, and with it transmit.
                Log.e(TAG, "capture tap failed", t)
            }
        }
        if (nextStatsAt.hasPassedNow()) {
            nextStatsAt = clock.markNow() + statsInterval
            handle.stats()?.let {
                val stats = it.copy(droppedFrames = droppedFrames)
                onStats(stats)
                if (readings++ % LOG_EVERY_READINGS == 0) Log.d(TAG, stats.summary())
            }
        }
        if (n > 0) {
            transmit(frame, n, meta)
            return true
        }
        if (speaking && lastSpeech.elapsedNow() >= SPEECH_STALL) speak(false)
        when (n) {
            0 -> return true   // not a spin — pollPacket already blocked
            NativeCapture.POLL_RETRY -> return true   // stream down; that poll reopened it or will
            NativeCapture.POLL_SHUTDOWN -> stopReason = StopReason.REQUESTED
            NativeCapture.POLL_UNAVAILABLE -> {
                Log.w(TAG, "capture engine unavailable; transmit stopped for this session")
                stopReason = StopReason.UNAVAILABLE
            }
            NativeCapture.POLL_NO_SESSION, NativeCapture.POLL_BUFFER_TOO_SMALL -> {
                Log.e(TAG, "pollPacket rejected the call ($n); transmit stopped for this session")
                stopReason = StopReason.UNAVAILABLE
            }
            else -> {
                Log.e(TAG, "unknown pollPacket result $n; transmit stopped for this session")
                stopReason = StopReason.UNAVAILABLE
            }
        }
        return false
    }

    private fun transmit(frame: ByteArray, n: Int, meta: LongArray) {
        val flags = meta[NativeCapture.META_FLAGS]
        val terminator = flags and NativeCapture.FLAG_TERMINATOR != 0L
        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setTarget(target)
            .setFrameNumber(meta[NativeCapture.META_FRAME_NUMBER])
            .setIsTerminator(terminator)
            .setOpusData(ByteString.copyFrom(frame, 0, n))
            .build()
        val body = audio.toByteArray()
        val payload = ByteArray(body.size + 1)
        payload[0] = UDP_TYPE_AUDIO
        body.copyInto(payload, 1)
        val sent = send(payload)
        if (!sent) droppedFrames++
        speak(sent && !terminator && flags and NativeCapture.FLAG_HANGOVER == 0L)
    }

    private fun speak(now: Boolean) {
        if (now) lastSpeech = clock.markNow()
        if (now == speaking) return
        speaking = now
        onSpeaking(now)
    }

    companion object {
        private const val TAG = "VoiceSender"
        const val NORMAL_TALKING_TARGET = 0
        /** Murmur's loopback target: the server sends the audio back to its sender alone. */
        const val SERVER_LOOPBACK_TARGET = 31
        private const val UDP_TYPE_AUDIO: Byte = 0
        /** The sheet refreshes every two seconds; the log line keeps its old ten. */
        private val STATS_INTERVAL = 2.seconds
        private const val LOG_EVERY_READINGS = 5
        private const val STOP_JOIN_MILLIS = 1_000L
        /** Speech goes out every 20 ms and a spurt ends on a terminator; this long with neither,
         *  the spurt has ended without one. */
        internal val SPEECH_STALL = 100.milliseconds
    }
}

/** Production seam: binds a native engine handle to the pump. */
class NativeCaptureHandle(private val handle: Long) : VoiceSender.CaptureHandle {
    override fun pollPacket(out: ByteArray, meta: LongArray) = NativeCapture.pollPacket(handle, out, meta)
    override fun setGateOpen(open: Boolean) = NativeCapture.setGateOpen(handle, open)
    // The engine's enum is two states, so the boundary carries a boolean rather than an ordinal:
    // an ordinal silently means the other mode if either enum is ever reordered, and this one
    // decides whether the microphone is live.
    override fun setTransmitMode(mode: TransmitMode) =
        NativeCapture.setVoiceActivity(handle, mode == TransmitMode.VoiceActivity)
    override fun stop() = NativeCapture.stop(handle)
    override fun destroy() = NativeCapture.destroy(handle)
    override fun stats() = CaptureStats.read(handle)
}

/** Build a started capture engine, or null on failure. Destroys the engine on a failed start. */
fun openNativeCapture(context: Context): VoiceSender.CaptureHandle? {
    // The blob is packaged in the APK, so a failed read is a broken build. No push-to-talk
    // fallback: it would leave the app's mode and the engine's disagreeing.
    val weights = try {
        context.assets.open("silero_vad_weights.bin").use { it.readBytes() }
    } catch (e: IOException) {
        Log.e("VoiceSender", "Silero weights could not be read", e)
        return null
    }
    val handle = NativeCapture.create(TRANSMIT_BITRATE, weights, CaptureSessionId.get(context))
    if (handle == 0L) {
        Log.e("VoiceSender", "capture engine could not be created")
        return null
    }
    if (!NativeCapture.start(handle)) {
        Log.e("VoiceSender", "capture engine could not be started")
        NativeCapture.destroy(handle)
        return null
    }
    return NativeCaptureHandle(handle)
}
