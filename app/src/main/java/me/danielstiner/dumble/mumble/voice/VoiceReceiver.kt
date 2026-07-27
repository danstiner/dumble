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
 * Owns inbound voice: one [SpeakerQueue] per sender, a playback thread that mixes them, and the
 * speaking-session set the UI observes.
 *
 * Pacing: while any speaker is draining, the loop is clocked by the blocking [AudioOut.write] —
 * AudioTrack consumes exactly one quantum per quantum-duration off the audio clock, so no timer
 * is used and none should be added. When every speaker is idle there is nothing to block on, so
 * the loop parks on [idleLock] until a frame arrives.
 */
class VoiceReceiver(
    private val codec: OpusCodec,
    private val outFactory: () -> AudioOut = { AndroidAudioOut() },
) {
    private val speakers = ConcurrentHashMap<Int, SpeakerQueue>()
    private val idleLock = Object()

    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

    // Latched once a decode path throws something other than a malformed packet (notably
    // UnsatisfiedLinkError when libopus didn't load). Voice is additive: it must fail silent,
    // never take the control connection down with it, and never retry a broken native path once
    // per packet at ~100 Hz.
    @Volatile private var voiceUnavailable = false

    @Volatile private var running = false
    // Written by start() outside the stop() monitor and read by stop() under it — no other
    // ordering ties the two, so without @Volatile a stop() on another thread could observe a
    // stale null and race start()'s own write.
    @Volatile private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            // JVM Thread.priority is a hint the Android scheduler mostly ignores; only the
            // nice-value bump from THREAD_PRIORITY_URGENT_AUDIO holds the 10 ms cadence loop()
            // depends on. Applies to the calling thread, so it must be set from inside loop's thread.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            loop()
        }, "dumble-voice-playback").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Synchronized because teardown can reach the same attempt twice (a handshake completing
     * after it was superseded), and each teardown now hands the join to a coroutine — so two
     * calls genuinely race. Unserialized, both would observe a dead thread and both would close
     * every decoder in [speakers], which is the same double-free this method is careful to avoid
     * on the timeout path.
     */
    @Synchronized
    fun stop() {
        running = false
        synchronized(idleLock) { idleLock.notifyAll() }
        val worker = thread
        worker?.join(1_000)
        if (worker == null || !worker.isAlive) {
            // Only clear `thread` when the join actually observed exit. If a repeat call sees
            // `thread == null` it must mean this branch already ran, not "the thread must have
            // exited by now" — that conflation is what let a second stop() close live decoders
            // out from under a still-running loop() the last time this bug happened.
            thread = null
            speakers.values.forEach { it.close() }
            speakers.clear()
        } else {
            // join timing out doesn't mean the thread died — it can still be blocked in a wedged
            // AudioTrack.write and will keep touching `speakers` itself (retiring/closing queues).
            // Closing decoders here too races that thread's own close() on the same native handle:
            // OpusDecoder.close() is at-most-once, so a second call is a double-free, not a
            // catchable exception. Leaking bounded native memory beats corrupting the heap.
            // Leaving `thread` set means a later stop() re-joins and re-checks liveness instead
            // of trusting a stale "already gone" conclusion.
            Log.w(TAG, "playback thread outlived stop(); leaking decoders to avoid a double-free")
        }
        _speakingSessions.value = emptySet()
    }

    /**
     * Reader-coroutine context; must not block. [payload] is the raw tunneled UDP packet:
     * a one-byte type followed by a protobuf body.
     */
    fun onTunneledAudio(payload: ByteArray, arrivalNanos: Long) {
        if (voiceUnavailable) return
        if (payload.isEmpty() || payload[0].toInt() != UDP_TYPE_AUDIO) return
        val audio = try {
            MumbleUdpProtos.Audio.parseFrom(payload.copyOfRange(1, payload.size))
        } catch (e: InvalidProtocolBufferException) {
            // A corrupt frame must not propagate into the transport's reader and kill the session.
            Log.w(TAG, "dropping malformed tunneled audio", e)
            return
        }
        try {
            speakers.computeIfAbsent(audio.senderSession) { SpeakerQueue(codec) }
                .offer(audio.opusData.toByteArray(), audio.isTerminator)
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
            return
        }
        synchronized(idleLock) { idleLock.notifyAll() }
    }

    private fun loop() {
        val out = try {
            outFactory()
        } catch (t: Throwable) {
            // AudioTrack.Builder().build() throws (UnsupportedOperationException/
            // IllegalStateException) when the device can't honor the requested route. Uncaught on
            // this thread, Android's default handler kills the whole process. Receive-only voice
            // must degrade to silence instead of taking the session down.
            Log.e(TAG, "audio output unavailable, voice playback disabled", t)
            running = false
            return
        }
        val acc = IntArray(QUANTUM_SAMPLES)
        val mix = ShortArray(QUANTUM_SAMPLES)
        val speakerOut = ShortArray(QUANTUM_SAMPLES)
        val producing = HashSet<Int>()
        try {
            while (running) {
                java.util.Arrays.fill(acc, 0)
                producing.clear()

                val it = speakers.entries.iterator()
                while (it.hasNext()) {
                    val entry = it.next()
                    val queue = entry.value
                    if (queue.fillTick(speakerOut)) {
                        AudioMixer.accumulate(acc, speakerOut, QUANTUM_SAMPLES)
                        producing += entry.key
                    }
                    // retired is one-way, so drop the queue here; a later packet from this
                    // session allocates a fresh one via computeIfAbsent.
                    if (queue.retired) {
                        queue.close()
                        it.remove()
                    }
                }

                if (producing.isEmpty()) {
                    if (_speakingSessions.value.isNotEmpty()) _speakingSessions.value = emptySet()
                    // Nothing to block on. Do NOT zero-fill to keep the clock: that would leave
                    // AudioTrack permanently full after a burst, ratcheting latency up with no
                    // gap to drain it. The bounded wait re-checks prebuffering speakers.
                    synchronized(idleLock) { if (running) idleLock.wait(10) }
                    continue
                }

                if (_speakingSessions.value != producing) _speakingSessions.value = HashSet(producing)
                AudioMixer.finalizeMix(acc, mix, QUANTUM_SAMPLES)
                if (!out.write(mix, QUANTUM_SAMPLES)) {
                    // A failed write does not block, unlike every successful one — write() is
                    // this loop's only pacing. Ignoring the failure would busy-spin a CPU core at
                    // THREAD_PRIORITY_URGENT_AUDIO for as long as any speaker keeps producing.
                    Log.e(TAG, "audio output write failed, stopping playback")
                    running = false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "playback loop died", t)
        } finally {
            out.close()
        }
    }

    private companion object {
        const val TAG = "VoiceReceiver"
    }
}
