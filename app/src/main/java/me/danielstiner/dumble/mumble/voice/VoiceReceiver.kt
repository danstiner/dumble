package me.danielstiner.dumble.mumble.voice

import android.os.Process
import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos

/** First byte of a Mumble UDP plaintext packet. */
private const val UDP_TYPE_AUDIO = 0

/**
 * Owns inbound voice: hands packets to the native playout engine, runs the playback thread that
 * pulls mixed frames from it, and republishes the speaking-session set the UI observes.
 *
 * Pacing: while any speaker is draining, the loop is clocked by the blocking [AudioOut.write] —
 * AudioTrack consumes exactly one frame per frame-duration off the audio clock, so no timer
 * is used and none should be added. When nobody is draining there is nothing to block on, so the
 * loop parks on [idleLock]: unbounded when no speaker exists at all, and 10 ms at a time while one
 * does.
 */
class VoiceReceiver(
    private val newEngine: () -> PlayoutEngine?,
    private val outFactory: () -> AudioOut,
) {
    /** Seam so JVM tests can drive the loop without loading native code. */
    interface PlayoutEngine {
        /** Reader thread. One of [NativePlayout]'s `OFFER_*` codes. [frameNumber] is the sender's
         *  own frame counter, which the engine measures arrival jitter against. */
        fun offer(session: Int, opusData: ByteArray, frameNumber: Long, terminator: Boolean): Int

        /** Playback thread. Fills [pcm] with one mixed frame, returns how many speakers
         *  produced, writes their sessions into `status[1..n]` and the live speaker count into
         *  `status[STATUS_ACTIVE_SPEAKERS]`. */
        fun fillQuantum(pcm: ShortArray, status: IntArray): Int

        /** Playback thread. Returns the live speaker count. */
        fun readStats(
            sessions: IntArray,
            depths: IntArray,
            targets: IntArray,
            counters: LongArray,
        ): Int

        /** Playback thread, from the loop's finally — never from another thread. */
        fun destroy()
    }

    // java.lang.Object rather than Any: the bounded park in loop() needs wait/notifyAll, which
    // Kotlin's Any does not expose.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val idleLock = Object()

    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

    private val _playoutStats = MutableStateFlow<PlayoutStats?>(null)
    val playoutStats: StateFlow<PlayoutStats?> = _playoutStats.asStateFlow()

    // Null until start() builds one, and permanently null if it never does (newEngine() refused,
    // or start() is never called at all — the ordinary case for a superseded or failed attempt).
    // Written once by start() before the playback thread is spawned; read by onTunneledAudio,
    // which can already be admitting packets by then. @Volatile because writer (start(), on the
    // receiver's monitor) and reader (onTunneledAudio, under idleLock) share no lock.
    @Volatile
    private var engine: PlayoutEngine? = null

    // Latched by the parse catch in onTunneledAudio, its one setter — see the reasoning there.
    @Volatile
    private var voiceUnavailable = false

    // Codes already logged once. Every refusal is a condition a misbehaving server can produce on
    // every packet, and this path runs at ~100 Hz, so an unlatched log is its own liveness
    // problem. Guarded by idleLock, like everything else on the offer path.
    private val refusalLogged = HashSet<Int>()

    // Same latching, but for the playback thread's own bug class rather than the reader's: a
    // readStats() this side sized wrong would otherwise refuse silently on every spurt close for
    // the rest of the session.
    private var statsRefusedReported = false

    // One-way, and the loop's only exit condition. Set by stop() and by loop() on its way out, so
    // a playback thread that died on its own silences the reader too. One-way rather than a pair
    // of flags because every "stop" is terminal here; `thread` already distinguishes not-yet-started
    // from running, and a latch cannot be clobbered by a thread that is on its way out.
    @Volatile
    private var stopped = false

    // Written by start() outside the stop() monitor and read by stop() under it — no other
    // ordering ties the two, so without @Volatile a stop() on another thread could observe a
    // stale null and race start()'s own write.
    @Volatile
    private var thread: Thread? = null

    /**
     * Single-shot, and synchronized to pair with [stop]: the caller builds a receiver per attempt.
     *
     * [newEngine] is called from here, not from the constructor: the engine must exist if and only
     * if the playback loop that owns destroying it is running, and this is the one place that
     * guarantees a thread is about to be spawned before anything native gets allocated. Building it
     * eagerly at construction — as an earlier version of this class did — leaked one engine per
     * attempt that was superseded, retired, or failed before ever reaching this call.
     */
    @Synchronized
    fun start() {
        if (stopped || thread != null) return
        val built = newEngine()
        if (built == null) {
            // Mirrors outFactory's failure path in loop(): voice is additive, so an unavailable
            // engine disables receive for this session rather than failing the connection. Unlike
            // that path this runs on the caller's thread, before any playback thread exists, so
            // there is nothing to destroy — newEngine() returning null means nothing was built.
            stopped = true
            return
        }
        engine = built
        thread = Thread({
            // Here rather than in start(): setThreadPriority applies to the calling thread. JVM
            // Thread.priority is a hint Android mostly ignores — only this nice-value bump holds
            // the 10 ms cadence. Priority is an optimisation, so a refusal (SecurityException on
            // some OEM builds) must degrade cadence, not throw.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                .onFailure { Log.w(TAG, "could not raise playback thread priority", it) }

            loop(built)
        }, "dumble-voice-playback").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Synchronized because teardown can reach the same attempt twice (a handshake completing
     * after it was superseded), and each teardown hands the join to a coroutine — so two calls
     * genuinely race. Unserialized, both would observe a dead thread and both would destroy the
     * engine, which is the same double-free this method is careful to avoid on the timeout path.
     */
    @Synchronized
    fun stop() {
        // Before the join, so the loop sees its exit condition and a reader arriving later never
        // offers into a dead engine; one already inside onTunneledAudio is serialized against the
        // latch by idleLock. Safe to call before start() too: it latches stopped, so a start() that
        // hasn't run yet — or is racing this on another thread, blocked on the same monitor — will
        // see it and refuse, per the check at the top of start().
        stopped = true
        synchronized(idleLock) { idleLock.notifyAll() }
        val worker = thread
        worker?.join(1_000)
        if (worker == null || !worker.isAlive) {
            // Only clear `thread` when the join actually observed exit. If a repeat call sees
            // `thread == null` it must mean this branch already ran, not "the thread must have
            // exited by now" — that conflation is what let a second stop() destroy the engine out
            // from under a still-running loop() the last time this bug happened.
            thread = null
        } else {
            // join timing out doesn't mean the thread died — it can still be blocked in a wedged
            // AudioTrack.write and will destroy the engine itself on its way out. Leaving `thread`
            // set means a later stop() re-joins and re-checks liveness instead of trusting a stale
            // "already gone".
            Log.w(TAG, "playback thread outlived stop(); leaking the playout engine")
        }
        // Both flows, for the same reason: loop()'s finally clears them, but it only runs if the
        // thread actually exited. On the join-timeout path above it never does, and a stats
        // reading from a dead connection would sit in the flow for the life of the process.
        _speakingSessions.value = emptySet()
        _playoutStats.value = null
    }

    /**
     * Reader-coroutine context; must not block. [payload] is the raw tunneled UDP packet:
     * a one-byte type followed by a protobuf body.
     */
    fun onTunneledAudio(payload: ByteArray) {
        if (voiceUnavailable || stopped) return
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
        // Under idleLock so the `stopped` check and the offer are atomic against the loop's
        // destroy(). The playback thread takes this monitor only when it has nothing to mix, so
        // the paced path never contends for it.
        synchronized(idleLock) {
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
            idleLock.notifyAll()
        }
    }

    private fun loop(engine: PlayoutEngine) {
        val mix = ShortArray(FRAME_SAMPLES)
        // Allocated once: the playback thread must not allocate per fill. Sized by the constants
        // the seam validates against — undersize one and every call answers ERROR_BUFFER_TOO_SMALL.
        val status = IntArray(NativePlayout.STATUS_LENGTH)
        val statSessions = IntArray(MAX_SPEAKERS)
        val statDepths = IntArray(MAX_SPEAKERS)
        val statTargets = IntArray(MAX_SPEAKERS)
        val counters = LongArray(NativePlayout.COUNTER_COUNT)

        var inSpurt = false
        var writesThisSpurt = 0
        var underrunBaseline: Int? = null
        var concealedBaseline = 0L
        var droppedBaseline = 0L
        var shrunkBaseline = 0L
        var catchUpBaseline = 0L

        val out = try {
            outFactory()
        } catch (t: Throwable) {
            Log.e(TAG, "audio output unavailable, voice playback disabled", t)
            stopped = true
            // Same monitor onTunneledAudio admits under, even though this thread never reached the
            // main loop below: a reader that already passed the `stopped` check can be inside
            // offer() right now, and an unguarded destroy() here would free the engine under it.
            synchronized(idleLock) { engine.destroy() }
            return
        }
        try {
            while (!stopped) {
                val producing = engine.fillQuantum(mix, status)
                if (producing < 0) {
                    // Our arrays, our bug. Treated like a failed write: it does not block, so
                    // continuing would spin this thread at THREAD_PRIORITY_URGENT_AUDIO.
                    Log.e(TAG, "playout engine refused our buffers, stopping playback")
                    stopped = true
                    continue
                }
                if (producing == 0) {
                    _speakingSessions.value = emptySet()
                    if (inSpurt) {
                        val published = publishStats(engine, out, underrunBaseline,
                                                     concealedBaseline, droppedBaseline,
                                                     shrunkBaseline, catchUpBaseline,
                                                     statSessions, statDepths, statTargets,
                                                     counters)
                        inSpurt = false
                        if (published) {
                            // Rearmed from the reading publishStats just took rather than a
                            // second readStats — a drop landing between two reads would be
                            // published in neither spurt. Once, here, rather than on every
                            // poll: the engine charges a gap once and then re-arms the speaker's
                            // prebuffer gate, so the charge a poll books is already inside this
                            // reading and later polls cannot add to it. And not at the next
                            // spurt's open, whose opening frame has already run by the time that
                            // branch checks — a partial-fill opening frame would vanish into its
                            // own baseline.
                            concealedBaseline = counters[NativePlayout.COUNTER_CONCEALED_GAPS]
                            // Unlike concealment, drops can move while idle — the reader fills
                            // queues the loop is not draining yet. Those land in the next spurt's
                            // window, which is where they belong: a backlog thrown away while a
                            // speaker prebuffers is that spurt's burst, not the previous one's.
                            droppedBaseline = counters[NativePlayout.COUNTER_DROPPED_PACKETS]
                            shrunkBaseline = counters[NativePlayout.COUNTER_SHRUNK_PACKETS]
                            catchUpBaseline = counters[NativePlayout.COUNTER_CATCH_UP_PACKETS]
                        } else if (!statsRefusedReported) {
                            // Our arrays, our bug — see fillQuantum's refusal above. Left
                            // unresolved, every future spurt would silently stop publishing:
                            // publishStats gives up on the whole reading, not just the baseline.
                            statsRefusedReported = true
                            Log.e(TAG, "playout engine refused our stats buffers; baselines are stale")
                        }
                    }
                    // Nothing to block on. Do NOT zero-fill to keep the clock: that would leave
                    // AudioTrack permanently full after a burst, ratcheting latency up with no
                    // gap to drain it.
                    synchronized(idleLock) {
                        if (!stopped) {
                            if (status[NativePlayout.STATUS_ACTIVE_SPEAKERS] == 0) idleLock.wait()
                            else idleLock.wait(10)
                        }
                    }
                    continue
                }

                if (!inSpurt) {
                    inSpurt = true
                    writesThisSpurt = 0
                    // Read before the spurt's first write, so the underrun the platform recorded
                    // for the preceding silence is inside the baseline and drops out. Unlike
                    // concealedBaseline, this is safe to read here: an output write, not
                    // fillQuantum(), is what moves the platform's underrun counter, so it cannot
                    // yet reflect a write this spurt has not made.
                    underrunBaseline = runCatching { out.outputStats().underrunsTotal }.getOrNull()
                }

                val speaking = HashSet<Int>(producing)
                for (i in 0 until producing) speaking += status[NativePlayout.STATUS_SESSIONS + i]
                if (_speakingSessions.value != speaking) _speakingSessions.value = speaking

                if (!out.write(mix, FRAME_SAMPLES)) {
                    Log.e(TAG, "audio output write failed, stopping playback")
                    stopped = true
                } else if (++writesThisSpurt >= WRITES_PER_SAMPLE) {
                    writesThisSpurt = 0
                    publishStats(engine, out, underrunBaseline, concealedBaseline,
                                 droppedBaseline, shrunkBaseline, catchUpBaseline,
                                 statSessions, statDepths, statTargets, counters)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback loop died", t)
        } finally {
            stopped = true
            _speakingSessions.value = emptySet()
            _playoutStats.value = null
            out.close()
            // After the latch and inside the monitor the reader admits under, so no offer can
            // straddle it. The playback thread is fillQuantum's only caller, so this is the same
            // rule capture uses: free only once the thread that touches it is done.
            synchronized(idleLock) { engine.destroy() }
        }
    }

    /**
     * Every path is wrapped: the loop's catch (Throwable) is fatal to playback, and instrumentation
     * must never be able to reach it. A failed [underrunBaseline] publishes a null count rather
     * than subtracting a stale one, which would be wrong for the entire spurt.
     *
     * True when `counters` holds a reading good enough to rearm the caller's baselines from, which
     * turns on [engine] alone: neither a platform that cannot answer nor a failure to present the
     * sample may cost the next spurt its baseline.
     */
    private fun publishStats(
        engine: PlayoutEngine,
        out: AudioOut,
        underrunBaseline: Int?,
        concealedBaseline: Long,
        droppedBaseline: Long,
        shrunkBaseline: Long,
        catchUpBaseline: Long,
        sessions: IntArray,
        depths: IntArray,
        targets: IntArray,
        counters: LongArray,
    ): Boolean {
        // Refused, or thrown: publishing off untouched scratch would report a spurt's worth of
        // zeros as if they were measurements.
        val speakers =
            runCatching { engine.readStats(sessions, depths, targets, counters) }.getOrDefault(-1)
        if (speakers < 0) return false
        // Everything below is presentation. Wrapped so it cannot reach the loop's fatal catch, and
        // outside the decision above so that it cannot withhold the rearm either: the counters are
        // already good, and a baseline left stale corrupts the next spurt as well as this one.
        runCatching {
            // Tolerated separately from the counters: a platform that cannot answer costs the two
            // fields derived from it, not the sample.
            val reading = runCatching { out.outputStats() }.getOrNull()
            val buffered = HashMap<Int, Int>(speakers)
            val targeted = HashMap<Int, Int>(speakers)
            for (i in 0 until speakers) {
                buffered[sessions[i]] = depths[i]
                targeted[sessions[i]] = targets[i]
            }
            val stats = PlayoutStats(
                latencyMs = reading?.latencyMs,
                underruns = reading?.let { r -> underrunBaseline?.let { r.underrunsTotal - it } },
                concealedGaps =
                    (counters[NativePlayout.COUNTER_CONCEALED_GAPS] - concealedBaseline).toInt(),
                droppedPackets =
                    (counters[NativePlayout.COUNTER_DROPPED_PACKETS] - droppedBaseline).toInt(),
                shrunkPackets =
                    (counters[NativePlayout.COUNTER_SHRUNK_PACKETS] - shrunkBaseline).toInt(),
                catchUpPackets =
                    (counters[NativePlayout.COUNTER_CATCH_UP_PACKETS] - catchUpBaseline).toInt(),
                bufferedSamples = buffered,
                targetSamples = targeted,
            )
            _playoutStats.value = stats
            // Debug rather than info, and ungated, for the reason VoiceSender's capture line is:
            // being readable off a shipped build is the point of collecting this at all.
            Log.d(TAG, stats.summary())
        }
        return true
    }

    private companion object {
        const val TAG = "VoiceReceiver"

        /** One second of audio at [FRAME_SAMPLES] per write. */
        const val WRITES_PER_SAMPLE = 100
    }
}
