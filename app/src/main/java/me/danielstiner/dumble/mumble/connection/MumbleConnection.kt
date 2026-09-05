package me.danielstiner.dumble.mumble.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.ComparableTimeMark
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.NoVoiceCall
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import me.danielstiner.dumble.mumble.voice.VoiceSender
import me.danielstiner.dumble.mumble.voice.openNativeCapture
import me.danielstiner.dumble.mumble.voice.openNativePlayout
import me.danielstiner.dumble.telecom.TelecomCall
import me.danielstiner.dumble.time.BootTimeSource
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * Owns the whole connection: the blocking TLS connect (which throws trust exceptions before the
 * protocol starts) and the [SessionStateMachine] that follows, unified into one [status] flow.
 *
 * Single active connection, guarded by [attempt]. A blocking handshake cannot be preempted by
 * teardown — the socket is not owned until it publishes — so an in-flight attempt can complete after
 * being superseded and would otherwise clobber a newer attempt's status. Every write is therefore
 * gated on the attempt token under [lock], and a superseded attempt's writes become no-ops.
 *
 * The audio-capture half of this class — the command channel, its single consumer, and the
 * level/reconcile model — is documented in `docs/capture.md`.
 */
@Singleton
class MumbleConnection internal constructor(
    private val pinStore: PinStore,
    // Defaulted so the tests that predate voice capture keep their trailing-lambda transport.
    private val newCapture: () -> VoiceSender.CaptureHandle? = { null },
    // Defaulted to no receive at all, the same way newCapture defaults to no send: a JVM test
    // that never overrides this never touches System.loadLibrary. Real callers get
    // openNativePlayout() from the @Inject constructor below.
    private val newPlayout: () -> VoiceReceiver.PlayoutEngine? = { null },
    private val call: VoiceCall = NoVoiceCall,
    // Seam: the wedge watchdog's deadline, so its tests do not each spend a real second.
    private val stuckPumpMillis: Long = 1_000L,
    // Seams: the UDP transport's clock, so its wiring test can jump the resync throttle's quiet
    // period rather than wait it out (it reads zero off-device, which is why the test must inject
    // one), and the ping interval, so the unanswered-ping wiring test does not wait two out.
    private val udpClock: TimeSource.WithComparableMarks = BootTimeSource,
    private val pingIntervalMs: Long = SessionStateMachine.PING_INTERVAL_MS,
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) : Connection {
    @Inject constructor(
        @ApplicationContext context: Context,
        pinStore: PinStore,
    ) : this(
        pinStore, { openNativeCapture(context) },
        { openNativePlayout() },
        TelecomCall(context),
        newTransport = { MumbleTcpTransport(it) },
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    private val _serverVersion = MutableStateFlow<ServerVersion?>(null)
    override val serverVersion: StateFlow<ServerVersion?> = _serverVersion.asStateFlow()
    private val _roundTripTime = MutableStateFlow<Duration?>(null)
    override val roundTripTime: StateFlow<Duration?> = _roundTripTime.asStateFlow()
    private val _voicePath = MutableStateFlow(VoicePath.State())
    override val voicePath: StateFlow<VoicePath.State> = _voicePath.asStateFlow()
    private val _lastServerReplyAt = MutableStateFlow<ComparableTimeMark?>(null)
    override val lastServerReplyAt: StateFlow<ComparableTimeMark?> = _lastServerReplyAt.asStateFlow()
    private val _channelTree = MutableStateFlow(ChannelTree())
    override val channelTree: StateFlow<ChannelTree> = _channelTree.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    override val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

    private val _selfSpeaking = MutableStateFlow(false)
    override val selfSpeaking: StateFlow<Boolean> = _selfSpeaking.asStateFlow()

    @Volatile private var lastAudioSentNanos = 0L

    private val _callHeld = MutableStateFlow(false)
    override val callHeld: StateFlow<Boolean> = _callHeld.asStateFlow()

    private val _playoutStats = MutableStateFlow<PlayoutStats?>(null)
    override val playoutStats: StateFlow<PlayoutStats?> = _playoutStats.asStateFlow()

    private val _userStats = MutableStateFlow<UserStats?>(null)
    override val userStats: StateFlow<UserStats?> = _userStats.asStateFlow()
    private val _audioRoutes = MutableStateFlow(AudioRoutes())
    override val audioRoutes: StateFlow<AudioRoutes> = _audioRoutes.asStateFlow()

    init {
        // One point that mirrors every status transition to logcat, whichever path set it.
        scope.launch { status.collect { Log.i(TAG, "status = $it") } }
    }

    private val lock = Any()
    private var attempt = 0
    @Volatile private var current: Attempt? = null

    /** The transmit mode, as a setting: outlives attempts and is applied to every session this
     *  connection opens. UI thread writes it. */
    @Volatile private var transmitMode = TransmitMode.VoiceActivity

    /**
     * One connection attempt. Built by [connect]'s coroutine and published as [current] under
     * [lock] if [gen] is still the live generation; unpublished by the next connect(), by
     * disconnect(), or by [retire] when its own session fails. Every publish is gen-guarded, so a
     * superseded attempt's late writes are no-ops. It outlives being current: capture state hangs
     * off the attempt, not the connection, because a release must find the attempt that opened the
     * session rather than whichever attempt replaced it.
     *
     * The first eight fields are immutable identity. The mutable ones are either `@Volatile`
     * (crossing threads) or plain (confined to the lifecycle consumer's single coroutine).
     */
    private class Attempt(
        val gen: Int,
        val endpoint: MumbleEndpoint,
        val username: String,
        val password: String?,
        val transport: MumbleControlTransport,
        val sm: SessionStateMachine,
        val childScope: CoroutineScope,
        val receiver: VoiceReceiver,
        /** The UDP voice socket. Opened once the control connection is up, closed with the
         *  attempt; inert in between if it could not be opened, and voice stays tunneled. */
        val udp: MumbleUdpTransport,
        /** Which transport carries our voice; [sendVoice] routes by it. */
        val path: VoicePath,
        /** Fingerprint the server presented when the handshake stopped for a trust decision;
         *  what [trustAndConnect] pins. Written by the connect coroutine, read on caller threads. */
        @Volatile var presented: String? = null,
        /** The live capture session, or null. One field, one lifetime: the handle and its pump
         *  used to be two fields paired by convention in three places, and a window where only one
         *  had been cleared is how a hold could open a second microphone stream. Written only by
         *  the lifecycle consumer; read on the UI thread by [apply]. */
        @Volatile var capture: CaptureSession? = null,
        /** Talk is held. A level, not an edge, so a session rebuilt under a still-held button comes
         *  up transmitting. UI thread writes it; [openSession] reads it after publishing [capture]
         *  — see [apply] for why that order matters, and keep both `@Volatile`. */
        @Volatile var pressed: Boolean = false,
        /** Self-mute. The wire half lives in [SessionStateMachine]; this half closes the gate. */
        @Volatile var muted: Boolean = false,
        /** The app wants a capture session on this attempt — the level [reconcile] opens from.
         *  Raised by Acquire, cleared by Release and by a terminal pump exit. */
        var wanted: Boolean = false,
        /** A release is in flight: stop() has returned and only the pump's own exit will free the
         *  engine. Keeps [reconcile] from starting a second release meanwhile; cleared by
         *  [onPumpExited]. */
        var releasing: Boolean = false,
    )

    private sealed interface CaptureCommand {
        /** A capture session is wanted on this attempt — the microphone became ready, or Talk was
         *  pressed. Pairs with [Release]; a level-raise, not an open, so repeats are free. */
        data class Acquire(val att: Attempt) : CaptureCommand
        /** The platform has taken, or returned, the input device for the call of this generation. */
        data class Held(val gen: Int, val held: Boolean) : CaptureCommand
        data class Release(val att: Attempt, val reason: VoiceCall.Reason) : CaptureCommand
        data class PumpExited(val att: Attempt, val sender: VoiceSender) : CaptureCommand
        data class WedgeCheck(val att: Attempt, val session: CaptureSession) : CaptureCommand
    }

    /**
     * A live capture session. Built and released as a unit; never half-present.
     *
     * Both fields are private and there is no accessor for either: setGateOpen reaches
     * `self(h)->engine` in capture_jni.cpp, a member of the Session that destroy() deletes, so a
     * push-to-talk edge racing a release is a use-after-free. The monitor is what closes it, and a
     * public `sender` would let a caller walk around it.
     */
    private inner class CaptureSession(
        private val handle: VoiceSender.CaptureHandle,
        private val sender: VoiceSender,
    ) {
        private var destroyed = false
        /** The engine's mode. A fresh engine is push-to-talk, and an engine never refuses a mode. */
        private var appliedMode = TransmitMode.PushToTalk

        fun ownedBy(s: VoiceSender) = sender === s

        /**
         * Push the levels to the engine: the mode, then the gate derived from it. Reads them under
         * the monitor so concurrent callers agree on the newest values.
         *
         * A direct call from the UI thread, not a command: the channel's consumer blocks in
         * `newCapture()` and `stop()`, busiest exactly when the user is pressing Talk, and a gate
         * edge queued behind that clips the start of a spurt. (The hop itself is p50 11 µs.)
         */
        fun apply(att: Attempt) {
            synchronized(this) {
                if (destroyed) return
                // Only on a change: every mode write resets the engine's detector, mid-spurt too.
                if (transmitMode != appliedMode) {
                    handle.setTransmitMode(transmitMode)
                    appliedMode = transmitMode
                }
                sender.setTransmitting(
                    !att.muted && (att.pressed || transmitMode == TransmitMode.VoiceActivity),
                )
            }
        }

        /** Free the engine. Lifecycle consumer only, and only after the pump has exited; the
         *  monitor is what keeps a racing push-to-talk edge off the freed engine. */
        fun destroy() = synchronized(this) { if (!destroyed) { destroyed = true; handle.destroy() } }

        /** Lifecycle consumer only. Off the monitor deliberately: this waits on a HAL close and
         *  the UI must not queue behind it. */
        fun stop() = sender.stop()

        val stopReason: VoiceSender.StopReason? get() = sender.stopReason
    }

    private val captureCommands = Channel<CaptureCommand>(Channel.UNLIMITED)

    /** The generation whose call the platform is holding, or [NO_GEN]. Consumer-only. */
    private var heldGen = NO_GEN

    init {
        // The single owner of capture. On `scope`, never a childScope — teardown cancels those
        // synchronously and would discard queued commands, leaking the engine and the microphone.
        // IO explicitly, because every handler blocks: newCapture() on the HAL, stop() on
        // OboeCapture::close(). runCatching because a SupervisorJob does not restart a coroutine
        // that threw, and a dead consumer fails silently and permanently.
        //
        // A second init block, not folded into the first: `captureCommands` is declared between
        // them, and Kotlin runs property initializers and init blocks in textual order, so a
        // consumer launched before that declaration can start against a still-null channel — this
        // is what a real, if rare, "Channel.iterator() on null" crash traced back to.
        scope.launch(Dispatchers.IO) {
            for (cmd in captureCommands) {
                runCatching { dispatch(cmd) }
                    .onFailure { Log.e(TAG, "capture command failed: $cmd", it) }
            }
        }
    }

    // Every status write goes through the lock so a bump + terminal write is atomic against stale writers.
    private fun publishStatus(gen: Int, s: ConnectionStatus) = synchronized(lock) { if (gen == attempt) _status.value = s }
    private fun publishVersion(gen: Int, v: ServerVersion?) = synchronized(lock) { if (gen == attempt) _serverVersion.value = v }
    private fun publishRtt(gen: Int, r: Duration?) = synchronized(lock) { if (gen == attempt) _roundTripTime.value = r }
    private fun publishVoicePath(gen: Int, s: VoicePath.State) = synchronized(lock) { if (gen == attempt) _voicePath.value = s }
    private fun publishPingReplyAt(gen: Int, at: ComparableTimeMark?) = synchronized(lock) { if (gen == attempt) _lastServerReplyAt.value = at }
    private fun publishChannelTree(gen: Int, t: ChannelTree) = synchronized(lock) { if (gen == attempt) _channelTree.value = t }
    private fun publishMessages(gen: Int, m: List<ChatMessage>) = synchronized(lock) { if (gen == attempt) _messages.value = m }
    private fun publishSpeaking(gen: Int, s: Set<Int>) = synchronized(lock) { if (gen == attempt) _speakingSessions.value = s }
    private fun publishPlayoutStats(gen: Int, p: PlayoutStats?) = synchronized(lock) { if (gen == attempt) _playoutStats.value = p }
    private fun publishUserStats(gen: Int, p: UserStats?) = synchronized(lock) { if (gen == attempt) _userStats.value = p }
    private fun publishRoutes(gen: Int, r: AudioRoutes) = synchronized(lock) { if (gen == attempt) _audioRoutes.value = r }

    /**
     * Any thread; never blocks. Cannot fail: the channel is UNLIMITED and never closed, and its
     * consumer lives on [scope], which is never cancelled. Checked because the failure would be
     * silent and permanent — a dropped Release strands both a microphone and the platform call.
     */
    private fun send(cmd: CaptureCommand) {
        if (captureCommands.trySend(cmd).isFailure) Log.e(TAG, "capture command dropped: $cmd")
    }

    /** Whether [att] is still the published, live attempt — the gate every open decision takes.
     *  Both halves matter: retire() clears `current` without bumping `attempt`. */
    private fun isLive(att: Attempt) =
        synchronized(lock) { att.gen == attempt && current === att }

    /** Lifecycle consumer only — every capture transition is serialised through here. */
    private fun dispatch(cmd: CaptureCommand) {
        when (cmd) {
            is CaptureCommand.Acquire -> onAcquire(cmd.att)
            is CaptureCommand.Held -> onHeld(cmd.gen, cmd.held)
            is CaptureCommand.Release -> onRelease(cmd.att, cmd.reason)
            is CaptureCommand.PumpExited -> onPumpExited(cmd.att, cmd.sender)
            is CaptureCommand.WedgeCheck -> onWedgeCheck(cmd.att, cmd.session)
        }
    }

    /**
     * A capture session was asked for — the microphone became ready, or Talk was pressed. Raises
     * the level and reconciles. While the platform holds the call, the ask also doubles as the
     * resume request: core-telecom sends no unsolicited resume, so the user asking to talk is the
     * only retry there is. Lifecycle consumer only.
     */
    private fun onAcquire(att: Attempt) {
        att.wanted = true
        // Guarded on heldGen so an ordinary press while active does not setActive() the platform
        // on every edge.
        if (heldGen == att.gen) call.requestActive(att.gen)
        reconcile(att)
    }

    /** Pump thread. The compare-and-set keeps one hold coroutine per spurt, not one per packet.
     *  Stamp first, then compare-and-set — mirrors [holdSelfSpeaking]'s lower-then-reread so a
     *  packet at the edge of the hold is never stranded.
     *
     *  Not gen-checked: a draining pump can still send after a release or retire. Accepted —
     *  the raise is truthful, cannot outlive its hold, and worst case is a ~200 ms halo past the
     *  last drained packet. Relies on [scope] never being cancelled. */
    private fun onAudioSent() {
        lastAudioSentNanos = System.nanoTime()
        if (_selfSpeaking.compareAndSet(expect = false, update = true)) {
            scope.launch { holdSelfSpeaking() }
        }
    }

    private fun holdRemainingMillis() =
        SPEAKING_HOLD_MILLIS - (System.nanoTime() - lastAudioSentNanos) / 1_000_000

    /** Lowers [selfSpeaking] once [SPEAKING_HOLD_MILLIS] pass with no packet. Loops rather than
     *  delaying once: every packet moves the stamp while this sleeps. */
    private suspend fun holdSelfSpeaking() {
        while (true) {
            val remaining = holdRemainingMillis()
            if (remaining > 0) { delay(remaining); continue }
            _selfSpeaking.value = false
            // Dekker-style: lower, then re-read, while onAudioSent stamps then compares-and-sets.
            // Each side writes its own variable first and reads the other's, so at least one
            // sees the other — a packet that lands during the lower either wins the
            // compare-and-set (launching a new hold) or is seen here via the fresh stamp.
            // Volatile under StateFlow's lock.
            if (holdRemainingMillis() <= 0) return
            if (!_selfSpeaking.compareAndSet(expect = false, update = true)) return
        }
    }

    /**
     * The platform took the input device out from under [gen]'s call — an incoming cellular call
     * is the case that matters — or gave it back. Records the hold as a level and reconciles the
     * live attempt, which releases the capture session on a hold and rebuilds it on a resume.
     * Lifecycle consumer only.
     */
    private fun onHeld(gen: Int, held: Boolean) {
        // One read: `attempt` says whether the callback is stale, `current` says who to
        // reconcile. Checked against `attempt` rather than `current` because a hold can
        // arrive before its attempt publishes — call.start runs on the caller's thread and
        // `current` is set later, on the connect coroutine.
        val (live, att) = synchronized(lock) { (gen == attempt) to current }
        // Dropped rather than recorded if it belongs to a superseded call: recording it
        // would let a stale hold clobber a live one, or a stale resume clear it — either
        // way the microphone ends up on a device the platform has taken.
        if (live) {
            Log.i(TAG, "call ${if (held) "held" else "resumed"} gen=$gen")
            heldGen = if (held) gen else NO_GEN
            _callHeld.value = held
            // The output stream follows the hold too: the platform has the device, and the
            // receiver's poll is the one owner of that stream.
            att?.receiver?.setHeld(held)
            att?.let { reconcile(it) }
        } else {
            Log.w(TAG, "call ${if (held) "hold" else "resume"} dropped: stale gen=$gen")
        }
    }

    /**
     * [att] is being torn down: lower its want, end its platform call, and close any session it
     * still holds. Queued synchronously by [teardown], which is what orders this ahead of anything
     * a replacing attempt can produce on the same channel. Lifecycle consumer only.
     */
    private fun onRelease(att: Attempt, reason: VoiceCall.Reason) {
        att.wanted = false
        // Before reconcile, not after, and needing nothing from it but the generation.
        // reconcile blocks in session.stop() — an unbounded HAL close — and this handler is
        // already queued behind whatever the consumer was doing, which can be a full
        // newCapture(). Ending afterwards left the platform call and the microphone
        // notification registered for that whole time, with the UI already back on the
        // connect form; and a throw anywhere in reconcile skipped the end entirely, since
        // the consumer loop swallows it. Still not deferred to the pump's exit, which is
        // what made a wedged pump never end the call at all.
        call.end(att.gen, reason)
        reconcile(att)
    }

    /** Lifecycle consumer only. The only place a capture session is created or released. */
    private fun reconcile(att: Attempt) {
        if (att.wanted && heldGen != att.gen && isLive(att)) {
            // A release in flight re-reconciles from onPumpExited; opening a second engine here is
            // exactly the two-microphone defect.
            if (att.capture != null) return
            openSession(att)
        } else {
            val s = att.capture ?: return
            if (!att.releasing) beginRelease(att, s)
        }
    }

    /** Build the native engine and its pump for [att] and publish them as its session. Lifecycle
     *  consumer only; blocks in newCapture() — a full HAL open — which is why the consumer runs on
     *  [Dispatchers.IO]. */
    private fun openSession(att: Attempt) {
        // Leaves `wanted` set on failure, unlike onPumpExited's terminal branch: opens here are
        // command-rate-bounded rather than a loop, and a Talk press re-asks anyway.
        val handle = newCapture() ?: return
        val sender = VoiceSender(
            handle, { sendVoice(att, it) },
            onExit = { s -> send(CaptureCommand.PumpExited(att, s)) },
            onAudioSent = ::onAudioSent,
        )
        // Recheck: `attempt`/`current` are still mutated on caller threads while newCapture()
        // blocks, so a disconnect landing in that window has already moved the world.
        if (!isLive(att)) { handle.stop(); handle.destroy(); return }
        val session = CaptureSession(handle, sender)
        // Published before the levels are read — the mirror of apply()'s order; see its KDoc.
        att.capture = session
        // Before start(), so the pump's first poll already reads the mode and the gate.
        session.apply(att)
        sender.start()
    }

    /** Whichever transport [att]'s path has voice on. A datagram the socket refuses goes through
     *  the tunnel in the same call, and the next one already starts there. */
    private fun sendVoice(att: Attempt, payload: ByteArray): Boolean {
        if (att.path.state.value.onUdp) {
            if (att.udp.send(payload, payload.size)) return true
            att.path.demote()
        }
        return att.transport.sendRaw(TcpMessageType.UDPTunnel, payload)
    }

    /** Start releasing [session]: close its stream synchronously, then leave the engine for the
     *  pump's own exit to free via [onPumpExited]. Lifecycle consumer only; callers guarantee
     *  `!att.releasing`. */
    private fun beginRelease(att: Attempt, session: CaptureSession) {
        // Synchronous, inline: stop() returns once the pump has closed the Oboe stream (or, wedged,
        // after its bound). That ordering is the entire one-microphone invariant — making it
        // asynchronous would silently reopen the hole.
        session.stop()
        // The signal belongs to the session being released; left standing, the hold would carry
        // it into whatever opens next. A late packet racing this raises it again for at most one
        // hold — accepted, see onAudioSent.
        _selfSpeaking.value = false
        // Marked released only once stop() has returned. Set before it, a throw out of that JNI call
        // latched the attempt for good: `capture` stays non-null and `releasing` stays true, after
        // which reconcile will neither reopen nor retry, and the engine is never destroyed. Left
        // false, the next reconcile simply tries the release again. Re-entry is not a concern —
        // this whole handler runs on the single lifecycle consumer.
        att.releasing = true
        // Armed after stop() returns, not before: stop() is itself unbounded, so timing across it
        // would report a slow HAL as a wedged pump.
        scope.launch { delay(stuckPumpMillis); send(CaptureCommand.WedgeCheck(att, session)) }
    }

    /**
     * The pump of [sender] has exited — the only signal that no thread can still touch its engine,
     * and therefore the only place the engine is freed. Ends by reconciling again, because a
     * resume or a fresh Acquire may have arrived while the release was in flight and was refused
     * then. Lifecycle consumer only.
     */
    private fun onPumpExited(att: Attempt, sender: VoiceSender) {
        // Identity, not attempt: a later open may already have published a different session, and
        // freeing that one would delete a live engine.
        val s = att.capture
        if (s != null && s.ownedBy(sender)) {
            s.destroy()
            att.capture = null
        } else {
            // Unreachable — reconcile refuses to open while capture is set, so at most one exit can
            // be outstanding. Logged rather than assumed, so a wrong argument is visible.
            Log.e(TAG, "pump exit for a session that is no longer this attempt's")
        }
        att.releasing = false
        // Anything but a requested exit is terminal for this engine. Rebuilding on the spot would
        // be a loop of full HAL opens against a cause that has not changed, so the retry stays
        // user-driven.
        if (sender.stopReason != VoiceSender.StopReason.REQUESTED) att.wanted = false
        reconcile(att)
    }

    /**
     * Watchdog armed by [beginRelease]: if the pump has still not exited this long after its stop,
     * it is wedged in native code, and this log line is the only diagnostic that will ever say so.
     * The leak it reports is deliberate — freeing an engine under a live pump is the
     * use-after-free this design exists to prevent. Lifecycle consumer only.
     */
    private fun onWedgeCheck(att: Attempt, session: CaptureSession) {
        if (att.capture !== session) return   // released in time, or superseded
        Log.e(
            TAG,
            "capture pump has not exited after ${stuckPumpMillis}ms " +
                "(stopReason=${session.stopReason}); engine and input stream are leaked unless it " +
                "exits later",
        )
    }

    /**
     * Retire the live attempt and clear everything published about it, returning the attempt to tear
     * down. Shared by [connect] and [disconnect] because the two clearing different subsets is a
     * silent bug — the survivor is a flow the next screen renders with the last session's data. The
     * status is the only thing that legitimately differs, so it is the only parameter.
     *
     * Bumping `attempt` under the same lock is what makes the writes safe: every publish helper
     * above is gen-checked, so an in-flight writer for the old generation cannot repopulate what
     * this just cleared. Caller holds [lock].
     */
    private fun retireAndClearLocked(status: ConnectionStatus): Attempt? {
        val prior = current
        current = null; attempt += 1
        _status.value = status
        _serverVersion.value = null; _roundTripTime.value = null; _lastServerReplyAt.value = null
        _voicePath.value = VoicePath.State()
        _channelTree.value = ChannelTree()
        _messages.value = emptyList()
        _speakingSessions.value = emptySet()
        // Speaking and held belong to the attempt being retired. A draining pump can raise
        // speaking again — bounded and invisible, see onAudioSent.
        _selfSpeaking.value = false
        _callHeld.value = false
        _playoutStats.value = null
        _userStats.value = null
        _audioRoutes.value = AudioRoutes()
        return prior
    }

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        val gen: Int
        val prior: Attempt?
        synchronized(lock) {
            prior = retireAndClearLocked(ConnectionStatus.Connecting)
            gen = attempt
        }
        prior?.let { teardown(it) }
        // Here rather than in requestCapture(): tying the call to the connection, not the
        // microphone, gives a user who denied RECORD_AUDIO a service at all, and receive that
        // survives backgrounding — true only since the service's mediaPlayback fallback; a
        // `microphone`-typed start threw without the permission and that user got nothing.
        // Foreground is no precondition either — the service starts inside addCall's block,
        // 25–390 ms after this returns, beyond any caller's control.
        call.start(
            gen, endpoint, username,
            // The generation, not the attempt: onCallActive used to resolve `current` at call time
            // with no generation check, so a hold from a superseded call could latch onto its
            // successor and kill transmit for the session with nothing to clear it.
            onActive = { active -> send(CaptureCommand.Held(gen, !active)) },
            onRoutes = { r -> publishRoutes(gen, r) },
            onEnded = { endedByPlatform(gen) },
        )

        scope.launch {
            val pin = pinStore.get(endpoint.address)
            Log.i(TAG, "connect gen=$gen endpoint=${endpoint.address} user=$username storedPin=${pin != null}")
            val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = newTransport(pin)
            val sm = SessionStateMachine(transport, username, password, childScope, pingIntervalMs = pingIntervalMs)
            // newPlayout itself, not its result: VoiceReceiver only calls it from start(), and
            // only an attempt that survives every guard below ever reaches that call. Building
            // the engine here, eagerly, is what used to leak one per superseded/failed attempt.
            val receiver = VoiceReceiver(newPlayout)
            val path = VoicePath()
            val udp = MumbleUdpTransport(sm.crypt, object : MumbleUdpTransport.Listener {
                private var heard = false   // the server chose UDP for our downlink; logged once
                override fun onVoicePacket(buf: ByteArray, len: Int) {
                    if (!heard) {
                        heard = true
                        Log.i(TAG, "UDP downlink: first voice packet gen=$gen")
                    }
                    receiver.onVoicePacket(buf, len)
                }
                override fun onPingReply(roundTrip: Duration) = path.onPingAnswered(roundTrip)
                // Demoting brings the downlink back with our next spurt; the tunneled ping does it
                // for a client that never speaks, since the server never re-learns an address
                // (docs/connection.md, UDP voice).
                override fun onPingsUnanswered() {
                    Log.w(TAG, "UDP pings unanswered; voice on the tunnel gen=$gen")
                    path.demote()
                    transport.sendRaw(TcpMessageType.UDPTunnel, TUNNEL_PING)
                }
                override fun requestCryptResync() { sm.requestCryptResync() }
            }, udpClock)
            val att = Attempt(gen, endpoint, username, password, transport, sm, childScope, receiver, udp, path)
            val live = synchronized(lock) { if (gen == attempt) { current = att; true } else false }
            if (!live) { teardown(att); return@launch }   // superseded before publish

            val listener = object : MumbleControlTransport.Listener {
                override fun onFrame(f: TcpFrame) = sm.onFrame(f)
                override fun onClosed(cause: Throwable?) = sm.onClosed(cause)
            }
            try {
                transport.connect(endpoint.host, endpoint.port, listener)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val status = mapConnectError(t, att)
                // A trust prompt is not a failure — the handshake stopped on purpose to ask the user.
                when (status) {
                    is ConnectionStatus.AwaitingTrust, is ConnectionStatus.PinMismatch ->
                        Log.i(TAG, "handshake stopped for trust decision: $status")
                    else -> Log.w(TAG, "connect failed for ${endpoint.address}", t)
                }
                publishStatus(gen, status)
                // The handshake never produced a session, so there is nothing for the platform to
                // hold a call open for. Without this a refused connection — or a trust prompt the
                // user leaves sitting — keeps a registered call alive with no audio behind it.
                call.end(gen, VoiceCall.Reason.SESSION_FAILED)
                return@launch
            }
            if (gen != attempt) { teardown(att); return@launch }   // superseded mid-handshake

            // Before sm.start(): the ping that registers our address fires the instant CryptSetup
            // keys the cipher, and from then on the server sends our downlink over UDP whether or
            // not we ever transmit (docs/connection.md, UDP voice), so it has to land on a socket
            // already listening. One that cannot be opened costs the session nothing but UDP.
            att.transport.remoteAddress()?.let { remote ->
                runCatching { att.udp.open(remote) }
                    .onFailure { Log.w(TAG, "no UDP socket; voice stays tunneled", it) }
            }
            sm.udpPing = { att.udp.sendPing() }
            sm.audioListener = SessionStateMachine.AudioListener { payload ->
                receiver.onVoicePacket(payload, payload.size)
            }
            sm.start()
            childScope.launch {
                sm.state.collect { st ->
                    mapState(st)?.let { publishStatus(gen, it) }
                    // Retire the attempt if the session fails. Sequenced after publishStatus:
                    // retire() calls teardown(), which cancels this collector's own scope.
                    if (st is ConnectionState.Failed) retire(att)
                }
            }
            childScope.launch { sm.serverVersion.collect { publishVersion(gen, it) } }
            childScope.launch { sm.roundTripTime.collect { publishRtt(gen, it) } }
            childScope.launch { att.path.state.collect { publishVoicePath(gen, it) } }
            childScope.launch { sm.lastServerReplyAt.collect { publishPingReplyAt(gen, it) } }
            childScope.launch { sm.channelTree.collect { publishChannelTree(gen, it) } }
            childScope.launch { sm.messages.collect { publishMessages(gen, it) } }
            childScope.launch { receiver.speakingSessions.collect { publishSpeaking(gen, it) } }
            childScope.launch { receiver.playoutStats.collect { publishPlayoutStats(gen, it) } }
            childScope.launch { sm.userStats.collect { publishUserStats(gen, it) } }
            // Start the receiver if we are still on the current attempt. Both halves matter:
            // retire() clears `current` without bumping `attempt`. Every earlier return in this
            // function skips this line, so an attempt that never gets here never calls
            // newPlayout() — see the comment where `receiver` is built.
            //
            // The check is under the lock; the start is not. start() opens the output stream,
            // ~100 ms of HAL on a Pixel 7a, and holding `lock` across that stalls a main-thread
            // disconnect() and every publish. A teardown landing in between is the receiver's
            // own latch to handle: its stop() before start() refuses the start, and after it
            // joins and destroys.
            val stillCurrent = synchronized(lock) { gen == attempt && current === att }
            if (stillCurrent) receiver.start()
        }
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val att = current ?: return
        val presented = att.presented ?: return
        scope.launch {
            pinStore.put(att.endpoint.address, presented)
            connect(att.endpoint, att.username, att.password)
        }
    }

    override fun cancelTrust() = disconnect()

    override fun disconnect() {
        val prior = synchronized(lock) { retireAndClearLocked(ConnectionStatus.Idle) }
        prior?.let { teardown(it) }
    }

    /**
     * The platform ended [gen]'s call. Generation-gated for the same reason `onActive` is: a hangup
     * delivered for a call we have already superseded — its callbacks fall silent only once the
     * supersede cancels its job — would otherwise retire the session that replaced it.
     */
    private fun endedByPlatform(gen: Int) {
        val prior = synchronized(lock) {
            // Status, not `current != null`: that is null for the whole Connecting window, where
            // a hangup is exactly the one that must retire.
            if (gen != attempt || !_status.value.ongoing) return
            retireAndClearLocked(ConnectionStatus.Idle)
        }
        prior?.let { teardown(it) }
    }

    override fun sendText(text: String): Boolean = current?.sm?.sendText(text) ?: false

    override fun setSelfDeaf(on: Boolean) { current?.sm?.setSelfDeaf(on) }

    override fun requestUserStats(session: Int) { current?.sm?.requestUserStats(session) }

    override fun requestAudioRoute(routeId: String) {
        // The live attempt supplies the generation the UI does not carry. TelecomCall re-checks it
        // on its consumer, so a route tapped as a session dies is dropped rather than applied to
        // its successor.
        val att = current ?: return
        call.requestRoute(att.gen, routeId)
    }

    override fun requestCapture() {
        val att = current ?: return
        send(CaptureCommand.Acquire(att))
    }

    /**
     * Re-derive the gate: open when not muted and either Talk is held or voice activity is on.
     * Wanting it open also asks for a session, which is what brings one back after a hold or a
     * terminal engine failure.
     *
     * Levels first, then the live session — the mirror of [openSession], which publishes the
     * session and then reads the levels. Each side writes one volatile and reads the other, so a
     * press cannot be lost to a racing open: for both to miss, this read of `capture` would have
     * to precede openSession's write of it *and* openSession's read of the level precede the
     * caller's write of it, which program order forbids. Every level must stay `@Volatile` for
     * that. When both act, the gate is set twice, which costs nothing.
     */
    private fun apply(att: Attempt) {
        if (!att.muted && (att.pressed || transmitMode == TransmitMode.VoiceActivity)) {
            send(CaptureCommand.Acquire(att))
        }
        att.capture?.apply(att)
    }

    /** A press while muted stays shut: mute has no engine-side existence, and the Talk button is
     *  only disabled once the server echoes `self_mute`. */
    override fun setTransmitting(on: Boolean) {
        val att = current ?: return
        att.pressed = on
        apply(att)
    }

    /** Switching to push-to-talk lifts a self-mute: that mode has no Mute control, so a mute
     *  carried into it would disable Talk with nothing to clear it. Its gate is closed anyway. */
    override fun setTransmitMode(mode: TransmitMode) {
        transmitMode = mode
        val att = current ?: return
        if (transmitMode != TransmitMode.VoiceActivity && att.muted) setMuted(false) else apply(att)
    }

    /** Closes the gate here, not just on the wire: the microphone goes quiet at the tap rather
     *  than at the server's echo, and a session rebuilt after the tap must not come up transmitting. */
    override fun setMuted(on: Boolean) {
        val att = current ?: return
        att.sm.setSelfMute(on)
        att.muted = on
        apply(att)
    }

    /**
     * Release a still-current attempt whose session died on its own. Deliberately does not bump
     * [attempt] or reset any published state — the terminal Error status is what the user is
     * looking at. Clearing [current] is what makes this at-most-once: a later disconnect() sees no
     * prior and a later connect() has nothing to tear down.
     */
    private fun retire(att: Attempt) {
        val live = synchronized(lock) {
            if (att.gen == attempt && current === att) { current = null; true } else false
        }
        if (live) teardown(att, VoiceCall.Reason.SESSION_FAILED)
    }

    /** Any thread; nothing here blocks. [att] is already out of [current] — the caller either just
     *  removed it or never published it — so this runs at most once per attempt. */
    private fun teardown(att: Attempt, reason: VoiceCall.Reason = VoiceCall.Reason.USER) {
        // First, synchronously, and outside the launch: connect() calls teardown(prior) on the
        // caller's thread before the coroutine that publishes the next attempt, so a synchronous
        // send is what guarantees the prior release is queued ahead of anything the next attempt
        // can produce. Queueing it inside the launch destroys that ordering. trySend never blocks,
        // so this is safe on the main thread.
        send(CaptureCommand.Release(att, reason))
        // IO because both block: stop() joins the receiver's poll, which can be inside a stream
        // start, and SSLSocket.close can stall writing close-notify to a dead peer. Kept off the capture
        // channel for that reason — one slow socket must not delay every other attempt's release.
        scope.launch(Dispatchers.IO) {
            // UDP first: its close never blocks, and the TLS close below can, while datagrams
            // would keep reaching a receiver that is about to stop.
            runCatching { att.udp.close() }
            runCatching { att.transport.close() }
            att.receiver.stop()
        }
        // The collectors never finish on their own, and retire() does not bump `attempt`, so this
        // is the only thing that stops a retired attempt still publishing. Stays last because
        // retire() can reach here from inside childScope itself.
        att.childScope.cancel()
    }

    private fun mapConnectError(t: Throwable, att: Attempt): ConnectionStatus {
        val chain = generateSequence(t as Throwable?) { it.cause }.toList()
        (chain.firstOrNull { it is UntrustedCertificateException } as? UntrustedCertificateException)?.let {
            att.presented = it.fingerprint
            return ConnectionStatus.AwaitingTrust(it.fingerprint)
        }
        (chain.firstOrNull { it is PinMismatchException } as? PinMismatchException)?.let {
            att.presented = it.presented
            return ConnectionStatus.PinMismatch(it.stored, it.presented)
        }
        if (chain.any { it is SocketTimeoutException }) return ConnectionStatus.Error(ErrorKind.TIMEOUT, t.message)
        return ConnectionStatus.Error(ErrorKind.CONNECT_FAILED, t.message)
    }

    private companion object {
        const val TAG = "MumbleConn"
        const val NO_GEN = -1

        /** How long the speaking halo outlives the last packet: enough to bridge the pauses
         *  inside a sentence, not so long it is still lit once someone has stopped. */
        const val SPEAKING_HOLD_MILLIS = 200L

        /** Tunneled for its side effect, never answered. Any frame of two bytes or more would
         *  do; this one is honest about what it is. */
        val TUNNEL_PING: ByteArray =
            byteArrayOf(1) + MumbleUdpProtos.Ping.newBuilder().setTimestamp(1).build().toByteArray()
    }
}
