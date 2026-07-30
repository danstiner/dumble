package me.danielstiner.dumble.mumble.voice

import android.os.Process
import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import java.util.concurrent.ConcurrentHashMap

/** First byte of a Mumble UDP plaintext packet. */
private const val UDP_TYPE_AUDIO = 0

/**
 * Owns inbound voice: one [SpeakerPlayout] per sender, a playback thread that mixes them, and the
 * speaking-session set the UI observes.
 *
 * Pacing: while any speaker is draining, the loop is clocked by the blocking [AudioOut.write] —
 * AudioTrack consumes exactly one quantum per quantum-duration off the audio clock, so no timer
 * is used and none should be added. When nobody is draining there is nothing to block on, so the
 * loop parks on [idleLock]: unbounded when no speaker exists at all, and 10 ms at a time while one
 * does.
 */
class VoiceReceiver(
    private val codec: OpusCodec,
    private val outFactory: () -> AudioOut,
) {
    private val speakers = ConcurrentHashMap<Int, SpeakerPlayout>()

    // java.lang.Object rather than Any: the bounded park in loop() needs wait/notifyAll, which
    // Kotlin's Any does not expose.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val idleLock = Object()

    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

    // Latched once a decode path throws something other than a malformed packet (notably
    // UnsatisfiedLinkError when libopus didn't load). Voice is additive: it must fail silent,
    // never take the control connection down with it, and never retry a broken native path once
    // per packet at ~100 Hz.
    @Volatile
    private var voiceUnavailable = false

    // Latched: the cap is only hit by a server misbehaving, and this path runs at ~100 Hz, so an
    // unlatched log is its own liveness problem.
    private var speakerCapReported = false

    // One-way, and the loop's only exit condition. Set by stop() and by loop() on its way out, so
    // a playback thread that died on its own silences the reader too: offer() returning false makes
    // the reader take a *fresh* playout, so an ungated packet does not merely get dropped — it
    // allocates a native decoder into a map nothing will sweep again. One-way rather than a pair of
    // flags because every "stop" is terminal here; `thread` already distinguishes not-yet-started
    // from running, and a latch cannot be clobbered by a thread that is on its way out.
    @Volatile
    private var stopped = false

    // Written by start() outside the stop() monitor and read by stop() under it — no other
    // ordering ties the two, so without @Volatile a stop() on another thread could observe a
    // stale null and race start()'s own write.
    @Volatile
    private var thread: Thread? = null

    /** Single-shot, and synchronized to pair with [stop]: the caller builds a receiver per attempt. */
    @Synchronized
    fun start() {
        if (stopped || thread != null) return
        thread = Thread({
            // Here rather than in start(): setThreadPriority applies to the calling thread. JVM
            // Thread.priority is a hint Android mostly ignores — only this nice-value bump holds
            // the 10 ms cadence. Priority is an optimisation, so a refusal (SecurityException on
            // some OEM builds) must degrade cadence, not throw.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
                .onFailure { Log.w(TAG, "could not raise playback thread priority", it) }

            loop()
        }, "dumble-voice-playback").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Synchronized because teardown can reach the same attempt twice (a handshake completing
     * after it was superseded), and each teardown hands the join to a coroutine — so two calls
     * genuinely race. Unserialized, both would observe a dead thread and both would close every
     * decoder in [speakers], which is the same double-free this method is careful to avoid on the
     * timeout path.
     */
    @Synchronized
    fun stop() {
        // Before the join, so the loop sees its exit condition and a reader arriving later never
        // allocates; one already inside onTunneledAudio is serialized against the sweep by idleLock.
        stopped = true
        synchronized(idleLock) { idleLock.notifyAll() }
        val worker = thread
        worker?.join(1_000)
        if (worker == null || !worker.isAlive) {
            // Only clear `thread` when the join actually observed exit. If a repeat call sees
            // `thread == null` it must mean this branch already ran, not "the thread must have
            // exited by now" — that conflation is what let a second stop() close live decoders
            // out from under a still-running loop() the last time this bug happened.
            thread = null
            // Same monitor the reader admits under, so no insert can straddle this sweep.
            synchronized(idleLock) {
                speakers.values.forEach { it.close() }
                speakers.clear()
            }
        } else {
            // join timing out doesn't mean the thread died — it can still be blocked in a wedged
            // AudioTrack.write and will keep touching `speakers` itself (retiring/closing
            // playouts). Closing decoders here too races that thread's own close() on the same
            // native handle. OpusDecoder.close() is idempotent, but decode() reads the handle and
            // uses it in two steps, so a close landing between them still frees under a decode.
            // Leaking bounded native memory beats that. Leaving `thread` set means a later stop()
            // re-joins and re-checks liveness instead of trusting a stale "already gone".
            Log.w(TAG, "playback thread outlived stop(); leaking decoders")
        }
        _speakingSessions.value = emptySet()
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
        // Checked before the payload is touched: the cap has to bound a hostile server's invented
        // sessions whether or not their opus data is well-formed. See MAX_SPEAKERS for the pricing.
        if (speakers.size >= MAX_SPEAKERS && !speakers.containsKey(session)) {
            if (!speakerCapReported) {
                speakerCapReported = true
                Log.w(TAG, "speaker cap of $MAX_SPEAKERS reached; ignoring further sessions")
            }
            return
        }
        try {
            val opusData = audio.opusData.toByteArray()
            // Retirement removes a playout from the map on the playback thread, and the lookup
            // here is not atomic with the offer that follows — so it can retire in between. offer
            // reports that rather than swallowing the packet: drop the dead entry and take a fresh
            // one. A retry is rare and cannot spin: retirement costs at least RETIRE_IDLE_TICKS
            // drained playback ticks (~100 ms), which the playout this iteration just created has
            // not had.
            // Under idleLock so the `stopped` check and the insert are atomic against stop()'s
            // sweep. The latch alone leaves a window — this thread reads it false, stop() sweeps,
            // then the insert lands in a map nothing will clear again, leaking a native decoder for
            // the life of the process. The playback thread takes this monitor only when it has
            // nothing to mix, so the paced path never contends for it.
            synchronized(idleLock) {
                while (!stopped) {
                    val playout = speakers.computeIfAbsent(session) { SpeakerPlayout(codec) }
                    if (playout.offer(opusData, audio.isTerminator)) break
                    // Re-checked rather than looping straight back: stop() retires every playout it
                    // sweeps, so without this the retry turns a concurrent stop() into an
                    // allocation loop, each pass leaving another native decoder behind it.
                    speakers.remove(session, playout)
                }
                idleLock.notifyAll()
            }
        } catch (t: Throwable) {
            // offer() reaches LibOpusCodec -> NativeOpus, whose class init loads libopus.
            // UnsatisfiedLinkError (an Error, not an Exception) on a missing/unpackaged .so is a
            // real device state, and MumbleTcpTransport's reader catches Throwable and tears the
            // whole session down. Voice is additive on top of chat/channels here, so a broken
            // decoder must degrade to silence, not take those down with it. Latch instead of
            // logging per-packet: this path runs at ~100 Hz and a log at that rate is its own
            // liveness problem.
            voiceUnavailable = true
            Log.e(TAG, "voice decode unavailable, disabling receive for this session", t)
        }
    }

    private fun loop() {
        val acc = IntArray(QUANTUM_SAMPLES)
        val mix = ShortArray(QUANTUM_SAMPLES)
        val speakerOut = ShortArray(QUANTUM_SAMPLES)
        val producing = HashSet<Int>()

        // Acquired last: nothing may run between this and the try that guarantees close(), or a
        // throw there leaks the AudioTrack.
        val out = try {
            outFactory()
        } catch (t: Throwable) {
            // AudioTrack.Builder().build() throws (UnsupportedOperationException/
            // IllegalStateException) when the device can't honor the requested route. Uncaught on
            // this thread, Android's default handler kills the whole process. Receive-only voice
            // must degrade to silence instead of taking the session down.
            Log.e(TAG, "audio output unavailable, voice playback disabled", t)
            stopped = true
            return
        }
        try {
            while (!stopped) {
                acc.fill(0)
                producing.clear()

                speakers.forEach { (session, playout) ->
                    if (playout.fillTick(speakerOut)) {
                        AudioMixer.accumulate(acc, speakerOut, QUANTUM_SAMPLES)
                        producing += session
                    }
                    // retired is one-way, so drop the playout here; a later packet from this
                    // session allocates a fresh one via computeIfAbsent. Removed by identity
                    // rather than by key alone: the reader may already have swapped in a
                    // replacement for this session, and an unconditional removal by key would
                    // discard that one instead of this corpse.
                    if (playout.retired) {
                        playout.close()
                        speakers.remove(session, playout)
                    }
                }

                if (producing.isEmpty()) {
                    _speakingSessions.value = emptySet()
                    // Nothing to block on. Do NOT zero-fill to keep the clock: that would leave
                    // AudioTrack permanently full after a burst, ratcheting latency up with no
                    // gap to drain it.
                    //
                    // The 10 ms bound only exists to keep charging idle ticks against a playout
                    // that is prebuffering or has gone quiet, so it is needed exactly while one
                    // exists; polling an empty map burned ~100 wakeups a second at
                    // THREAD_PRIORITY_URGENT_AUDIO for the whole of a silent call. Emptiness is
                    // read under idleLock, and onTunneledAudio and stop() both notify under the
                    // same monitor, so the unbounded park cannot miss a wakeup.
                    synchronized(idleLock) {
                        if (!stopped) if (speakers.isEmpty()) idleLock.wait() else idleLock.wait(10)
                    }
                    continue
                }

                if (_speakingSessions.value != producing) {
                    _speakingSessions.value = HashSet(producing)
                }
                AudioMixer.finalizeMix(acc, mix, QUANTUM_SAMPLES)
                if (!out.write(mix, QUANTUM_SAMPLES)) {
                    // A failed write does not block, unlike every successful one — write() is
                    // this loop's only pacing. Ignoring the failure would busy-spin a CPU core at
                    // THREAD_PRIORITY_URGENT_AUDIO for as long as any speaker keeps producing.
                    Log.e(TAG, "audio output write failed, stopping playback")
                    stopped = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback loop died", t)
        } finally {
            // Latched here rather than only on the paths that decide to stop, so it can never
            // outlive the thread it describes: a loop that died on its own (a failed write, a
            // throw) would otherwise leave the reader parsing and allocating at ~100 Hz into
            // playouts nothing will ever drain or retire.
            stopped = true
            // The loop owns this flow while it is alive, so it has to hand it back empty. Every
            // self-death path here (a failed write, a throw) happens on an iteration that just
            // published a non-empty set, and no stop() need follow — an audioserver restart alone
            // would otherwise leave a speaker lit in the channel tree for the rest of the session.
            _speakingSessions.value = emptySet()
            out.close()
        }
    }

    private companion object {
        const val TAG = "VoiceReceiver"
    }
}
