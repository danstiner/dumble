package me.danielstiner.dumble.mumble.voice

import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos

/** First byte of a Mumble UDP plaintext packet. */
private const val UDP_TYPE_AUDIO = 0

/**
 * Owns inbound voice: hands packets to the native playout session, and runs the one coroutine —
 * the poll — that owns the session's output stream and republishes what it reports.
 *
 * The audio itself never comes up here. The native session's output stream pulls each burst from
 * the engine on its own realtime thread; the poll's job is to keep that stream started for the
 * life of the receiver — paused only while the platform holds the call — and to read the engine's
 * stats every [POLL_MILLIS]: the speaking set for the UI, the counters for [playoutStats].
 *
 * Started throughout rather than around speech: a start costs up to ~150 ms, and every packet
 * that lands during it piles up ahead of the prebuffer gate — on a sender that never pauses,
 * that pile stays as standing delay (`docs/playout.md` has the numbers). An idle stream costs
 * one silent fill per burst, and during a call the capture stream keeps the audio HAL awake
 * regardless.
 *
 * Two rules carry the threading. `offer()` and `destroy()` share this object's monitor, so a
 * packet can never reach a freed session; `offer()` never touches the stream. And the poll is
 * the only caller of `start()`/`pause()`, and [stop] joins it before destroying, so no stream
 * call is ever in flight against a dead session.
 */
class VoiceReceiver(private val newEngine: () -> PlayoutEngine?) {
    /** Seam so JVM tests can drive the receiver without loading native code. */
    interface PlayoutEngine {
        /** Reader thread. One of [NativePlayout]'s `OFFER_*` codes. [frameNumber] is the sender's
         *  own frame counter, which the engine measures arrival jitter against. */
        fun offer(session: Int, opusData: ByteArray, frameNumber: Long, terminator: Boolean): Int

        /** Poll only. Returns the live speaker count and fills the four arrays for it;
         *  `audible[i]` is 1 when `sessions[i]` produced in the last fill. Negative when the
         *  arrays are too small — this side's bug. */
        fun readStats(
            sessions: IntArray,
            depths: IntArray,
            targets: IntArray,
            audible: IntArray,
            counters: LongArray,
        ): Int

        /** Poll only — the one owner of stream state. [start] is idempotent and answers whether
         *  a started stream exists, so the poll calls it every interval: that is how a stream
         *  lost to an error comes back. [pause] holds it while the platform has the device. */
        fun start(): Boolean
        fun pause()

        /** Under the receiver's monitor, after the poll has been joined. */
        fun destroy()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

    private val _playoutStats = MutableStateFlow<PlayoutStats?>(null)
    val playoutStats: StateFlow<PlayoutStats?> = _playoutStats.asStateFlow()

    // Null until start() builds one, and permanently null if it never does (newEngine() refused,
    // or start() is never called at all — the ordinary case for a superseded or failed attempt).
    // Guarded by this object's monitor.
    private var engine: PlayoutEngine? = null

    // The poll, once start() has launched it. Guarded by the monitor.
    private var poll: Job? = null

    // Latched by the parse catch in onTunneledAudio, its one setter — see the reasoning there.
    @Volatile
    private var voiceUnavailable = false

    // Codes already logged once. Every refusal is a condition a misbehaving server can produce on
    // every packet, and this path runs at ~100 Hz, so an unlatched log is its own liveness
    // problem. Guarded by the monitor, like everything else on the offer path.
    private val refusalLogged = HashSet<Int>()

    // One-way, and the poll's exit condition. Set by stop(), and by start() when there is no
    // engine to be had. One-way because every "stop" is terminal here: the caller builds a
    // receiver per attempt and never restarts one.
    @Volatile
    private var stopped = false

    // The platform has the audio device (an incoming cellular call). Read by the poll, which
    // pauses the stream and keeps it paused for as long as this holds.
    @Volatile
    private var held = false

    /**
     * Single-shot, and synchronized to pair with [stop]: the caller builds a receiver per attempt.
     *
     * [newEngine] is called from here, not from the constructor: the engine must exist if and only
     * if the poll that owns its stream is running. Building it eagerly at construction leaked one
     * engine per attempt that was superseded, retired, or failed before ever reaching this call.
     */
    @Synchronized
    fun start() {
        if (stopped || poll != null) return
        val built = newEngine()
        if (built == null) {
            // Voice is additive, so an unavailable engine disables receive for this session
            // rather than failing the connection. Nothing was built, so there is nothing to free.
            stopped = true
            return
        }
        engine = built
        poll = scope.launch { poll(built) }
    }

    /**
     * Safe to call before start(), twice, or from two threads at once: `stopped` latches, the
     * join is idempotent, and the destroy is guarded by the engine going null under the monitor.
     */
    fun stop() {
        stopped = true
        // Joined, not merely cancelled: a start()/pause() in flight on the poll's thread must
        // have returned before the session underneath it is freed. Outside the monitor because
        // the poll takes it for readStats, and a join under it would wait on itself.
        val job = synchronized(this) { poll }
        if (job != null) runBlocking { job.cancelAndJoin() }
        synchronized(this) {
            // Under the monitor, like offer(): a reader already inside offer() finishes first,
            // and one arriving later sees `stopped`.
            engine?.destroy()
            engine = null
        }
        scope.cancel()
        _speakingSessions.value = emptySet()
        _playoutStats.value = null
    }

    /** Any thread. While held the poll keeps the stream paused; a resume starts it again, within
     *  a poll interval. */
    fun setHeld(held: Boolean) {
        this.held = held
    }

    /**
     * Reader-coroutine context; must not block. [payload] is the raw tunneled UDP packet:
     * a one-byte type followed by a protobuf body.
     */
    fun onTunneledAudio(payload: ByteArray) {
        if (voiceUnavailable || stopped) return
        // The platform has the device: the stream is paused and the engine's speakers released
        // with it, so a packet queued now would only play stale on resume.
        if (held) return
        if (payload.isEmpty() || payload[0].toInt() != UDP_TYPE_AUDIO) return
        val audio = try {
            // Parsed in place; copyOfRange here would allocate a whole packet per frame at ~100 Hz.
            MumbleUdpProtos.Audio.parser().parseFrom(payload, 1, payload.size - 1)
        } catch (e: InvalidProtocolBufferException) {
            // A corrupt frame must not propagate into the transport's reader and kill the session.
            Log.w(TAG, "dropping malformed tunneled audio", e)
            return
        } catch (t: Throwable) {
            // Not every parse failure is an InvalidProtocolBufferException. protobuf-lite resolves
            // fields reflectively, so a keep rule that stopped firing surfaces here as a plain
            // RuntimeException out of the generated schema initializer. Uncaught it reaches
            // MumbleTcpTransport's reader, which tears the whole session down on Throwable — chat
            // and channels would die the moment anyone spoke, in release builds only. Latched
            // because it is a build property, identical for every packet that follows.
            voiceUnavailable = true
            Log.e(TAG, "tunneled audio unparseable, disabling receive for this session", t)
            return
        }
        val session = audio.senderSession
        synchronized(this) {
            if (stopped) return
            // Null before start() has built one — reachable, since sm.audioListener is wired
            // before receiver.start() runs — or permanently null if it never will. Either way,
            // dropped rather than queued: there is nothing yet for it to queue into.
            val live = engine ?: return
            // A refusal is a latched log and nothing more: the engine has no failure a packet can
            // trigger, only refusals a misbehaving server can. Whatever it refused is already
            // dropped by the time this returns, and the counts live in PlayoutStats.
            val code = live.offer(session, audio.opusData.toByteArray(), audio.frameNumber,
                                  audio.isTerminator)
            val refusal = when (code) {
                NativePlayout.OFFER_SPEAKER_CAP -> "speaker cap reached; ignoring further sessions"
                NativePlayout.OFFER_PACKET_TOO_LARGE -> "dropping oversized opus payload"
                NativePlayout.OFFER_MALFORMED_PACKET -> "dropping unparseable opus payload"
                else -> null
            }
            if (refusal != null && refusalLogged.add(code)) Log.w(TAG, refusal)
        }
    }

    /** Counters are monotonic since the session was built, so a spurt's numbers are read against
     *  where they stood when it opened (the stream's own underruns) or when the previous one closed
     *  (everything the engine counts). */
    private class Baselines {
        var underruns = 0L
        var concealed = 0L
        var dropped = 0L
        var shrunk = 0L
        var catchUp = 0L
        var contended = 0L

        /** Re-armed from the reading the closing publish just took, rather than a second read:
         *  a drop landing between two reads would be published in neither spurt. */
        fun rearm(c: LongArray) {
            concealed = c[NativePlayout.COUNTER_CONCEALED_GAPS]
            dropped = c[NativePlayout.COUNTER_DROPPED_PACKETS]
            shrunk = c[NativePlayout.COUNTER_SHRUNK_PACKETS]
            catchUp = c[NativePlayout.COUNTER_CATCH_UP_PACKETS]
            contended = c[NativePlayout.COUNTER_CONTENDED_FILLS]
        }
    }

    private suspend fun poll(engine: PlayoutEngine) {
        // Allocated once, sized by the constants the seam validates against.
        val sessions = IntArray(MAX_SPEAKERS)
        val depths = IntArray(MAX_SPEAKERS)
        val targets = IntArray(MAX_SPEAKERS)
        val audible = IntArray(MAX_SPEAKERS)
        val counters = LongArray(NativePlayout.COUNTER_COUNT)
        val baselines = Baselines()
        var started = false
        var inSpurt = false
        var lastPublishNanos = 0L
        var refusalReported = false
        while (true) {
            if (stopped) return
            // Every interval, not once: start() is cheap while the stream runs, and it is what
            // reopens one lost to an error the adapter's own retries gave up on. Before the read
            // rather than after it, so the first packet finds the stream running.
            if (held) {
                if (started) engine.pause()
                started = false
            } else {
                val up = engine.start()
                if (up != started) Log.i(TAG, if (up) "output stream started" else "output stream lost; retrying")
                started = up
            }
            val live = synchronized(this) {
                if (stopped) return
                runCatching { engine.readStats(sessions, depths, targets, audible, counters) }
                    .getOrDefault(-1)
            }
            if (live < 0) {
                // Our arrays, our bug. Logged once and polled on: a refusal here must not take
                // the stream's start and pause with it.
                if (!refusalReported) {
                    refusalReported = true
                    Log.e(TAG, "playout engine refused our stats buffers")
                }
                delay(POLL_MILLIS)
                continue
            }
            val now = System.nanoTime()

            val speaking = HashSet<Int>(live)
            for (i in 0 until live) if (audible[i] == 1) speaking += sessions[i]

            // A spurt, for the counters, is speech: from the first poll that sees someone
            // audible to the first that sees nobody. Published once a second inside one and once
            // at its close, so a spurt shorter than a second still reports — a stall segments
            // glitchy speech into exactly such spurts. The closing sample goes out before the
            // speaking set clears; the connection re-collects each flow on its own, so that
            // order holds at this seam, not necessarily at the UI.
            if (speaking.isNotEmpty() && !inSpurt) {
                inSpurt = true
                baselines.underruns = counters[NativePlayout.COUNTER_UNDERRUNS]
                lastPublishNanos = now
            }
            if (inSpurt && speaking.isEmpty()) {
                inSpurt = false
                publishStats(live, sessions, depths, targets, counters, baselines)
                baselines.rearm(counters)
            } else if (inSpurt && now - lastPublishNanos >= STATS_PERIOD_MILLIS * 1_000_000) {
                lastPublishNanos = now
                publishStats(live, sessions, depths, targets, counters, baselines)
            }
            if (_speakingSessions.value != speaking) _speakingSessions.value = speaking
            delay(POLL_MILLIS)
        }
    }

    private fun publishStats(
        live: Int,
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        counters: LongArray,
        baselines: Baselines,
    ) {
        // Presentation only, and wrapped: a formatting bug must not take the poll — and with it
        // the stream's start and pause — down.
        runCatching {
            val buffered = HashMap<Int, Int>(live)
            val targeted = HashMap<Int, Int>(live)
            for (i in 0 until live) {
                buffered[sessions[i]] = depths[i]
                targeted[sessions[i]] = targets[i]
            }
            val latencyMicros = counters[NativePlayout.COUNTER_LATENCY_MICROS]
            val stats = PlayoutStats(
                latencyMs = if (latencyMicros >= 0) latencyMicros / 1000.0 else null,
                // Clamped: the stream's count restarts at zero on a reopen mid-spurt.
                underruns = (counters[NativePlayout.COUNTER_UNDERRUNS] - baselines.underruns)
                    .coerceAtLeast(0).toInt(),
                concealedGaps =
                    (counters[NativePlayout.COUNTER_CONCEALED_GAPS] - baselines.concealed).toInt(),
                droppedPackets =
                    (counters[NativePlayout.COUNTER_DROPPED_PACKETS] - baselines.dropped).toInt(),
                shrunkPackets =
                    (counters[NativePlayout.COUNTER_SHRUNK_PACKETS] - baselines.shrunk).toInt(),
                catchUpPackets =
                    (counters[NativePlayout.COUNTER_CATCH_UP_PACKETS] - baselines.catchUp).toInt(),
                contendedFills =
                    (counters[NativePlayout.COUNTER_CONTENDED_FILLS] - baselines.contended).toInt(),
                fillMicrosMax = counters[NativePlayout.COUNTER_FILL_MICROS_MAX],
                fillMicrosMean = counters[NativePlayout.COUNTER_FILL_MICROS_MEAN],
                bufferedSamples = buffered,
                targetSamples = targeted,
            )
            _playoutStats.value = stats
            // Debug rather than info, and ungated, for the reason VoiceSender's capture line is:
            // being readable off a shipped build is the point of collecting this at all.
            Log.d(TAG, stats.summary())
        }
    }

    private companion object {
        const val TAG = "VoiceReceiver"

        /** How often the poll reads the engine. The speaking set and a hold's pause lag by at
         *  most this. */
        const val POLL_MILLIS = 50L

        const val STATS_PERIOD_MILLIS = 1_000L
    }
}
