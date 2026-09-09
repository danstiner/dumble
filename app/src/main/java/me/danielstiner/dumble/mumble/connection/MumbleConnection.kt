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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.ComparableTimeMark
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.ClientIdentityStore
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Owns the whole connection: the blocking TLS connect (which throws trust exceptions before the
 * protocol starts) and the [SessionStateMachine] that follows, unified into one [status] flow.
 *
 * One live [Session] at a time, guarded by [generation]; each session's driver opens its [Link].
 * A blocking handshake cannot be preempted by teardown — the socket is not owned until it
 * publishes — so a superseded link can complete late and would otherwise clobber a newer
 * session's status. Every write is therefore gated on the generation under [lock], and a link's
 * own flows on the link's identity as well, so a superseded writer's late writes become no-ops.
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
    // Seam: the relink ladder's waits, so its tests drive a clock instead of sleeping.
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) : Connection {
    @Inject constructor(
        @ApplicationContext context: Context,
        pinStore: PinStore,
        identityStore: ClientIdentityStore,
    ) : this(
        pinStore, { openNativeCapture(context) },
        { openNativePlayout() },
        TelecomCall(context),
        newTransport = { MumbleTcpTransport(it, identityStore = identityStore) },
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

    private val _captureStats = MutableStateFlow<CaptureStats?>(null)
    override val captureStats: StateFlow<CaptureStats?> = _captureStats.asStateFlow()

    private val _userStats = MutableStateFlow<UserStats?>(null)
    override val userStats: StateFlow<UserStats?> = _userStats.asStateFlow()
    private val _audioRoutes = MutableStateFlow(AudioRoutes())
    override val audioRoutes: StateFlow<AudioRoutes> = _audioRoutes.asStateFlow()

    init {
        // One point that mirrors every status transition to logcat, whichever path set it.
        scope.launch { status.collect { Log.i(TAG, "status = $it") } }
    }

    private val lock = Any()
    private var generation = 0
    @Volatile private var current: Session? = null
    /** The session whose handshake stopped for a trust decision: retired like any failed connect,
     *  kept aside for [trustAndConnect] to reconnect from. Under [lock]. */
    private var trustPrompt: Session? = null

    /** The transmit mode, as a setting: outlives sessions and is applied to every session this
     *  connection opens. UI thread writes it. */
    @Volatile private var transmitMode = TransmitMode.VoiceActivity

    /**
     * What the user asked for, and what outlives its link: one per connect(), published as
     * [current] under [lock] and unpublished by the next connect(), by disconnect(), or by
     * [retire] when its link dies. Outlives being current too: capture state hangs off the
     * session, not the connection, because a release must find the session that opened the
     * microphone rather than whichever session replaced it.
     *
     * The first six fields are immutable identity. The mutable ones are either `@Volatile`
     * (crossing threads) or plain (confined to the lifecycle consumer's single coroutine).
     */
    private class Session(
        val gen: Int,
        val endpoint: MumbleEndpoint,
        val username: String,
        val password: String?,
        /** Built once per session and fed packets by its link; never owns a transport. */
        val receiver: VoiceReceiver,
        /** The driver and the receiver's collectors. Cancelled by [teardown], last. */
        val scope: CoroutineScope,
        /** The link carrying this session, or null until the driver has built one. Written only
         *  under [lock], by the driver; read on the pump thread by [sendVoice]. */
        @Volatile var link: Link? = null,
        /** A replacement link being opened; the driver's, written under [lock] so a teardown
         *  during its blocking handshake finds it and closes it. */
        @Volatile var next: Link? = null,
        /** Chat from links this session has already replaced, oldest first. Written by the driver
         *  at the swap, read by the new link's collector on its own scope. */
        @Volatile var carried: List<ChatMessage> = emptyList(),
        /** Fingerprint the server presented when the handshake stopped for a trust decision;
         *  what [trustAndConnect] pins. Written by the driver, read on caller threads. */
        @Volatile var presented: String? = null,
        /** The live capture session, or null. One field, one lifetime: splitting handle and pump
         *  would leave a window where a hold could open a second microphone stream. Written only
         *  by the lifecycle consumer; read on the UI thread by [apply]. */
        @Volatile var capture: CaptureSession? = null,
        /** Talk is held. A level, not an edge, so a capture session rebuilt under a still-held
         *  button comes up transmitting. UI thread writes it; [openCapture] reads it after
         *  publishing [capture] — see [apply] for why that order matters, and keep both `@Volatile`. */
        @Volatile var pressed: Boolean = false,
        /** Self-mute. The wire half lives in [SessionStateMachine]; this half closes the gate. */
        @Volatile var muted: Boolean = false,
        /** The app wants capture on this session — the level [reconcile] opens from.
         *  Raised by Acquire, cleared by Release and by a terminal pump exit. */
        var wanted: Boolean = false,
        /** A release is in flight: stop() has returned and only the pump's own exit will free the
         *  engine. Keeps [reconcile] from starting a second release meanwhile; cleared by
         *  [onPumpExited]. */
        var releasing: Boolean = false,
    )

    private sealed interface CaptureCommand {
        /** Capture is wanted on this session — the microphone became ready, or Talk was
         *  pressed. Pairs with [Release]; a level-raise, not an open, so repeats are free. */
        data class Acquire(val session: Session) : CaptureCommand
        /** The platform has taken, or returned, the input device for the call of this generation. */
        data class Held(val gen: Int, val held: Boolean) : CaptureCommand
        data class Release(val session: Session, val reason: VoiceCall.Reason) : CaptureCommand
        data class PumpExited(val session: Session, val sender: VoiceSender) : CaptureCommand
        data class WedgeCheck(val session: Session, val capture: CaptureSession) : CaptureCommand
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
        fun apply(session: Session) {
            synchronized(this) {
                if (destroyed) return
                // Only on a change: every mode write resets the engine's detector, mid-spurt too.
                if (transmitMode != appliedMode) {
                    handle.setTransmitMode(transmitMode)
                    appliedMode = transmitMode
                }
                sender.setTransmitting(
                    !session.muted && (session.pressed || transmitMode == TransmitMode.VoiceActivity),
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

    // Session-scoped flows: guarded on the generation alone, under the lock that bumps it, so a
    // superseded session's late writes are no-ops. A retired session (current cleared, generation
    // not bumped) may still land a write: its terminal values are what the user is looking at.
    private fun publishStatus(gen: Int, s: ConnectionStatus) = synchronized(lock) { if (gen == generation) _status.value = s }
    private fun publishSpeaking(gen: Int, s: Set<Int>) = synchronized(lock) { if (gen == generation) _speakingSessions.value = s }
    private fun publishPlayoutStats(gen: Int, p: PlayoutStats?) = synchronized(lock) { if (gen == generation) _playoutStats.value = p }
    private fun publishCaptureStats(gen: Int, c: CaptureStats?) = synchronized(lock) { if (gen == generation) _captureStats.value = c }
    private fun publishRoutes(gen: Int, r: AudioRoutes) = synchronized(lock) { if (gen == generation) _audioRoutes.value = r }

    /**
     * A link's own flow. Guarded on the link's identity and its close flag as well as the
     * generation: a link the session has moved on from must not land a write after the swap, and
     * closing a link's collectors is no barrier for one already inside its body — the flag is.
     * So a dead link is frozen from its close, and the kick the server gives the old session
     * never reads as "you left".
     */
    private fun <T> publishFromLink(session: Session, link: Link, flow: MutableStateFlow<T>, value: T) =
        synchronized(lock) {
            if (session.gen == generation && session.link === link && !link.isClosed) flow.value = value
        }

    /**
     * Any thread; never blocks. Cannot fail: the channel is UNLIMITED and never closed, and its
     * consumer lives on [scope], which is never cancelled. Checked because the failure would be
     * silent and permanent — a dropped Release strands both a microphone and the platform call.
     */
    private fun send(cmd: CaptureCommand) {
        if (captureCommands.trySend(cmd).isFailure) Log.e(TAG, "capture command dropped: $cmd")
    }

    /** Whether [session] is still the published, live session — the gate every open decision
     *  takes. Both halves matter: retire() clears `current` without bumping `generation`. */
    private fun isLive(session: Session) =
        synchronized(lock) { session.gen == generation && current === session }

    /** Lifecycle consumer only — every capture transition is serialised through here. */
    private fun dispatch(cmd: CaptureCommand) {
        when (cmd) {
            is CaptureCommand.Acquire -> onAcquire(cmd.session)
            is CaptureCommand.Held -> onHeld(cmd.gen, cmd.held)
            is CaptureCommand.Release -> onRelease(cmd.session, cmd.reason)
            is CaptureCommand.PumpExited -> onPumpExited(cmd.session, cmd.sender)
            is CaptureCommand.WedgeCheck -> onWedgeCheck(cmd.session, cmd.capture)
        }
    }

    /**
     * A capture session was asked for — the microphone became ready, or Talk was pressed. Raises
     * the level and reconciles. While the platform holds the call, the ask also doubles as the
     * resume request: core-telecom sends no unsolicited resume, so the user asking to talk is the
     * only retry there is. Lifecycle consumer only.
     */
    private fun onAcquire(session: Session) {
        session.wanted = true
        // Guarded on heldGen so an ordinary press while active does not setActive() the platform
        // on every edge.
        if (heldGen == session.gen) call.requestActive(session.gen)
        reconcile(session)
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
     * live session, which releases the capture session on a hold and rebuilds it on a resume.
     * Lifecycle consumer only.
     */
    private fun onHeld(gen: Int, held: Boolean) {
        // One read: `generation` says whether the callback is stale, `current` says who to
        // reconcile. Checked against `generation` rather than `current` because a hold can
        // arrive before its session publishes — call.start runs first on the caller's thread.
        val (live, session) = synchronized(lock) { (gen == generation) to current }
        // Dropped rather than recorded if it belongs to a superseded call: recording it
        // would let a stale hold clobber a live one, or a stale resume clear it — either
        // way the microphone ends up on a device the platform has taken.
        if (live) {
            Log.i(TAG, "call ${if (held) "held" else "resumed"} gen=$gen")
            heldGen = if (held) gen else NO_GEN
            _callHeld.value = held
            // The output stream follows the hold too: the platform has the device, and the
            // receiver's poll is the one owner of that stream.
            session?.receiver?.setHeld(held)
            session?.let { reconcile(it) }
        } else {
            Log.w(TAG, "call ${if (held) "hold" else "resume"} dropped: stale gen=$gen")
        }
    }

    /**
     * [session] is being torn down: lower its want, end its platform call, and close any capture
     * session it still holds. Queued synchronously by [teardown], which is what orders this ahead
     * of anything a replacing session can produce on the same channel. Lifecycle consumer only.
     */
    private fun onRelease(session: Session, reason: VoiceCall.Reason) {
        session.wanted = false
        // Before reconcile, needing nothing from it but the generation: reconcile blocks on
        // the HAL close and can throw (the consumer loop swallows it), either of which
        // would strand the call. Not deferred to the pump's exit either — a wedged pump
        // would never end it.
        call.end(session.gen, reason)
        reconcile(session)
    }

    /** Lifecycle consumer only. The only place a capture session is created or released. */
    private fun reconcile(session: Session) {
        if (session.wanted && heldGen != session.gen && isLive(session)) {
            // A release in flight re-reconciles from onPumpExited; opening a second engine here is
            // exactly the two-microphone defect.
            if (session.capture != null) return
            openCapture(session)
        } else {
            val capture = session.capture ?: return
            if (!session.releasing) beginRelease(session, capture)
        }
    }

    /** Build the native engine and its pump for [session] and publish them as its capture.
     *  Lifecycle consumer only; blocks in newCapture() — a full HAL open — which is why the
     *  consumer runs on [Dispatchers.IO]. */
    private fun openCapture(session: Session) {
        // Leaves `wanted` set on failure, unlike onPumpExited's terminal branch: opens here are
        // command-rate-bounded rather than a loop, and a Talk press re-asks anyway.
        val handle = newCapture() ?: return
        val sender = VoiceSender(
            handle, { sendVoice(session, it) },
            onExit = { s -> send(CaptureCommand.PumpExited(session, s)) },
            onAudioSent = ::onAudioSent,
            onStats = { publishCaptureStats(session.gen, it) },
        )
        // Recheck: `generation`/`current` are still mutated on caller threads while newCapture()
        // blocks, so a disconnect landing in that window has already moved the world.
        if (!isLive(session)) { handle.stop(); handle.destroy(); return }
        val capture = CaptureSession(handle, sender)
        // Published before the levels are read — the mirror of apply()'s order; see its KDoc.
        session.capture = capture
        // Before start(), so the pump's first poll already reads the mode and the gate.
        capture.apply(session)
        sender.start()
    }

    /** Whichever transport the session's link has voice on; dropped while it has none. A datagram
     *  the socket refuses goes through the tunnel in the same call, and the next one already
     *  starts there. Pump thread: reads the link once, so a swap cannot split one packet. */
    private fun sendVoice(session: Session, payload: ByteArray): Boolean {
        val link = session.link ?: return false
        if (link.path.state.value.onUdp) {
            if (link.udp.send(payload, payload.size)) return true
            link.path.demote()
        }
        return link.transport.sendRaw(TcpMessageType.UDPTunnel, payload)
    }

    /** Start releasing [capture]: close its stream synchronously, then leave the engine for the
     *  pump's own exit to free via [onPumpExited]. Lifecycle consumer only; callers guarantee
     *  `!session.releasing`. */
    private fun beginRelease(session: Session, capture: CaptureSession) {
        // Synchronous, inline: stop() returns once the pump has closed the Oboe stream (or, wedged,
        // after its bound). That ordering is the entire one-microphone invariant — making it
        // asynchronous would silently reopen the hole.
        capture.stop()
        // The signal belongs to the session being released; left standing, the hold would carry
        // it into whatever opens next. A late packet racing this raises it again for at most one
        // hold — accepted, see onAudioSent.
        _selfSpeaking.value = false
        // Marked released only once stop() has returned. Set before it, a throw out of that JNI call
        // latched the session for good: `capture` stays non-null and `releasing` stays true, after
        // which reconcile will neither reopen nor retry, and the engine is never destroyed. Left
        // false, the next reconcile simply tries the release again. Re-entry is not a concern —
        // this whole handler runs on the single lifecycle consumer.
        session.releasing = true
        // Armed after stop() returns, not before: stop() is itself unbounded, so timing across it
        // would report a slow HAL as a wedged pump.
        scope.launch { delay(stuckPumpMillis); send(CaptureCommand.WedgeCheck(session, capture)) }
    }

    /**
     * The pump of [sender] has exited — the only signal that no thread can still touch its engine,
     * and therefore the only place the engine is freed. Ends by reconciling again, because a
     * resume or a fresh Acquire may have arrived while the release was in flight and was refused
     * then. Lifecycle consumer only.
     */
    private fun onPumpExited(session: Session, sender: VoiceSender) {
        // Identity, not session: a later open may already have published a different capture, and
        // freeing that one would delete a live engine.
        val capture = session.capture
        if (capture != null && capture.ownedBy(sender)) {
            capture.destroy()
            session.capture = null
            publishCaptureStats(session.gen, null)
        } else {
            // Unreachable — reconcile refuses to open while capture is set, so at most one exit can
            // be outstanding. Logged rather than assumed, so a wrong argument is visible.
            Log.e(TAG, "pump exit for a capture that is no longer this session's")
        }
        session.releasing = false
        // Anything but a requested exit is terminal for this engine. Rebuilding on the spot would
        // be a loop of full HAL opens against a cause that has not changed, so the retry stays
        // user-driven.
        if (sender.stopReason != VoiceSender.StopReason.REQUESTED) session.wanted = false
        reconcile(session)
    }

    /**
     * Watchdog armed by [beginRelease]: if the pump has still not exited this long after its stop,
     * it is wedged in native code, and this log line is the only diagnostic that will ever say so.
     * The leak it reports is deliberate — freeing an engine under a live pump is the
     * use-after-free this design exists to prevent. Lifecycle consumer only.
     */
    private fun onWedgeCheck(session: Session, capture: CaptureSession) {
        if (session.capture !== capture) return   // released in time, or superseded
        Log.e(
            TAG,
            "capture pump has not exited after ${stuckPumpMillis}ms " +
                "(stopReason=${capture.stopReason}); engine and input stream are leaked unless it " +
                "exits later",
        )
    }

    /**
     * Retire the live session and clear everything published about it, returning the session to
     * tear down. Shared by [connect] and [disconnect] because the two clearing different subsets is
     * a silent bug — the survivor is a flow the next screen renders with the last session's data.
     * The status is the only thing that legitimately differs, so it is the only parameter.
     *
     * Bumping `generation` and queuing the prior's Release under the same lock is what makes the
     * writes safe: every publish helper is gen-checked, and the release is ordered ahead of
     * anything the successor asks. Caller holds [lock].
     */
    private fun retireAndClearLocked(status: ConnectionStatus): Session? {
        val prior = current
        current = null; generation += 1
        trustPrompt = null
        // Queued here, under the lock that unpublishes [prior]: nothing the next session can ask
        // of the capture consumer is queued before this, so the release runs first. trySend
        // never blocks, so it is safe under the lock.
        prior?.let { send(CaptureCommand.Release(it, VoiceCall.Reason.USER)) }
        _status.value = status
        _serverVersion.value = null; _roundTripTime.value = null; _lastServerReplyAt.value = null
        _voicePath.value = VoicePath.State()
        _channelTree.value = ChannelTree()
        _messages.value = emptyList()
        _speakingSessions.value = emptySet()
        // Speaking and held belong to the session being retired. A draining pump can raise
        // speaking again — bounded and invisible, see onAudioSent.
        _selfSpeaking.value = false
        _callHeld.value = false
        _playoutStats.value = null
        _captureStats.value = null
        _userStats.value = null
        _audioRoutes.value = AudioRoutes()
        return prior
    }

    /** The pieces of one TLS connect, built but not yet connected. */
    private fun buildLink(session: Session, pin: String?): Link {
        val gen = session.gen
        val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = newTransport(pin)
        val stateMachine = SessionStateMachine(
            transport, session.username, session.password, childScope, pingIntervalMs = pingIntervalMs,
        )
        val path = VoicePath()
        val udp = MumbleUdpTransport(stateMachine.crypt, object : MumbleUdpTransport.Listener {
            private var heard = false   // the server chose UDP for our downlink; logged once
            override fun onVoicePacket(buf: ByteArray, len: Int) {
                if (!heard) {
                    heard = true
                    Log.i(TAG, "UDP downlink: first voice packet gen=$gen")
                }
                session.receiver.onVoicePacket(buf, len)
            }
            override fun onPingReply(roundTrip: Duration) {
                if (path.onPingAnswered(roundTrip)) stateMachine.udpPingAnswered(roundTrip)
            }
            // Demoting brings the downlink back with our next spurt; the tunneled ping does it
            // for a client that never speaks, since the server never re-learns an address
            // (docs/connection.md, UDP voice).
            override fun onPingsUnanswered() {
                Log.w(TAG, "UDP pings unanswered; voice on the tunnel gen=$gen")
                path.demote()
                transport.sendRaw(TcpMessageType.UDPTunnel, TUNNEL_PING)
            }
            override fun requestCryptResync() { stateMachine.requestCryptResync() }
        }, udpClock)
        return Link(transport, stateMachine, udp, path, childScope, scope)
    }

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        val gen: Int
        val prior: Session?
        synchronized(lock) {
            prior = retireAndClearLocked(ConnectionStatus.Connecting)
            gen = generation
        }
        prior?.let { teardown(it) }
        // Here rather than in requestCapture(): tying the call to the connection, not the
        // microphone, gives a user who denied RECORD_AUDIO a service at all, and receive that
        // survives backgrounding. Foreground is no precondition — the service starts inside
        // addCall's block, 25–390 ms after this returns, beyond any caller's control.
        call.start(
            gen, endpoint, username,
            // The generation, not the session: a hold from a superseded call must not reach
            // the live session.
            onActive = { active -> send(CaptureCommand.Held(gen, !active)) },
            onRoutes = { r -> publishRoutes(gen, r) },
            onEnded = { endedByPlatform(gen) },
        )
        // newPlayout itself, not its result: VoiceReceiver only calls it from start(), which only
        // a session whose link comes up ever reaches — building eagerly would leak one per
        // session that fails or is superseded before that.
        val session = Session(
            gen, endpoint, username, password, VoiceReceiver(newPlayout),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        // Published after the prior's Release was queued above, so nothing this session asks of
        // the capture consumer is ordered ahead of that release; and under the lock, so a
        // disconnect() that landed since leaves it unpublished.
        val live = synchronized(lock) { if (gen == generation) { current = session; true } else false }
        if (!live) {
            send(CaptureCommand.Release(session, VoiceCall.Reason.USER))
            teardown(session)
            return
        }
        session.scope.launch { session.receiver.speakingSessions.collect { publishSpeaking(gen, it) } }
        session.scope.launch { session.receiver.playoutStats.collect { publishPlayoutStats(gen, it) } }
        session.scope.launch { drive(session) }
    }

    /**
     * The session's driver: opens its first link and holds it until the state machine fails,
     * then replaces the link under the same session until one synchronizes or the deadline
     * passes. One coroutine per session, on the session's own scope, so a teardown cancels it
     * wherever it waits; the links it built are closed by that teardown, not by the cancellation.
     */
    private suspend fun drive(session: Session) {
        val gen = session.gen
        val pin = pinStore.get(session.endpoint.address)
        Log.i(TAG, "connect gen=$gen endpoint=${session.endpoint.address} user=${session.username} storedPin=${pin != null}")
        var link = buildLink(session, pin)
        // Published before the connect, so a teardown that lands during the blocking handshake
        // finds the link and closes it; see Link.close for what the transport does with a
        // handshake that finishes around that close.
        val live = synchronized(lock) {
            if (gen == generation && current === session) { session.link = link; true } else false
        }
        if (!live) { link.close(); return }   // superseded during the pin lookup

        open(session, link)?.let { status ->
            if (status is ConnectionStatus.AwaitingTrust || status is ConnectionStatus.PinMismatch) {
                // Retired all the same: a session left current after its call ends is one a
                // Talk press opens a microphone against. Set before the status goes out, since
                // trustAndConnect() can follow the prompt at once.
                Log.i(TAG, "handshake stopped for trust decision: $status")
                synchronized(lock) { if (gen == generation) trustPrompt = session }
            } else {
                Log.w(TAG, "connect failed for ${session.endpoint.address}")
            }
            publishStatus(gen, status)
            // Ends the call as a failure and keeps the microphone from opening against it.
            retire(session)
            return
        }
        if (!isLive(session)) { link.close(); return }   // superseded mid-handshake
        wire(session, link)
        // Start the receiver if this is still the live session, on its own coroutine so the
        // status wait below subscribes before the stream open rather than after it. Both halves
        // of isLive matter: retire() clears `current` without bumping the generation. Every
        // earlier return skips this, so a session that never gets here never calls newPlayout()
        // — see the comment where the receiver is built.
        //
        // The check is under the lock; the start is not. start() opens the output stream,
        // ~100 ms of HAL on a Pixel 7a, and holding `lock` across that stalls a main-thread
        // disconnect() and every publish. A teardown landing in between is the receiver's
        // own latch to handle: its stop() before start() refuses the start, and after it
        // joins and destroys.
        session.scope.launch { if (isLive(session)) session.receiver.start() }

        var deadline: ComparableTimeMark? = null   // the open incident's end, if one is open
        var rung = 0
        var lastSessionId = 0
        while (true) {
            // Status is the driver's own collector, not one of the link's: the terminal Error
            // must be on `status` before retire() cancels this coroutine's scope, and a Failed
            // is never published before it is classified.
            val failed = link.stateMachine.state
                .onEach { st ->
                    if (st is ConnectionState.Synchronized) {
                        if (link.syncedAt == null) link.syncedAt = udpClock.markNow()
                        lastSessionId = st.sessionId
                    }
                    if (st !is ConnectionState.Failed) mapState(gen, st)?.let { publishStatus(gen, it) }
                }
                .first { it is ConnectionState.Failed } as ConnectionState.Failed
            val syncedAt = link.syncedAt
            // The first link dying before it synchronized is the connect failing: surfaced as it
            // always was, and never retried.
            if (syncedAt == null && deadline == null) {
                publishStatus(gen, mapState(gen, failed)!!)
                retire(session)
                return
            }
            classify(gen, failed)?.let { giveUp ->
                publishStatus(gen, giveUp)
                retire(session)
                return
            }
            // Losing a healthy link is a new outage: fresh deadline, ladder from the bottom. Losing
            // one that never got healthy is the same outage continuing: next rung, same deadline.
            val healthy = syncedAt != null && udpClock.markNow() - syncedAt >= HEALTHY_AFTER
            if (healthy || deadline == null) {
                deadline = udpClock.markNow() + RELINK_DEADLINE
                rung = 0
            } else {
                rung += 1
            }
            Log.i(TAG, "link died gen=$gen reason=${failed.reason} healthy=$healthy rung=$rung")
            publishStatus(gen, ConnectionStatus.Reconnecting(gen, lastSessionId))
            link.close()
            link = relink(session, pin, deadline, rung) ?: return
            // A link that synchronizes and dies again short of healthy restarts the ladder at rung
            // 1, not where the last attempt left off: what bounds that churn is the deadline.
            rung = 0
        }
    }

    /**
     * Connects [link] and starts its protocol. Null on success; otherwise the status the failure
     * maps to, with the link left for the caller to close.
     */
    private suspend fun open(session: Session, link: Link): ConnectionStatus? {
        val listener = object : MumbleControlTransport.Listener {
            override fun onFrame(f: TcpFrame) = link.stateMachine.onFrame(f)
            override fun onClosed(cause: Throwable?) = link.stateMachine.onClosed(cause)
        }
        try {
            link.transport.connect(session.endpoint.host, session.endpoint.port, listener)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return mapConnectError(t, session)
        }
        // Before start(): the ping that registers our address fires the instant CryptSetup keys
        // the cipher, and from then on the server sends our downlink over UDP whether or not we
        // ever transmit (docs/connection.md, UDP voice), so it has to land on a socket already
        // listening. One that cannot be opened costs the session nothing but UDP.
        link.transport.remoteAddress()?.let { remote ->
            runCatching { link.udp.open(remote) }
                .onFailure { Log.w(TAG, "no UDP socket; voice stays tunneled", it) }
        }
        link.stateMachine.udpPing = { link.udp.sendPing() }
        link.stateMachine.audioListener = SessionStateMachine.AudioListener { payload ->
            session.receiver.onVoicePacket(payload, payload.size)
        }
        link.stateMachine.start()
        return null
    }

    /** Republishes [link]'s flows as the session's. The link must already be `session.link`. */
    private fun wire(session: Session, link: Link) {
        // A link's flows republish under the link guard; the session's own keep the generation
        // guard alone.
        fun <T> republish(from: Flow<T>, into: MutableStateFlow<T>) =
            link.childScope.launch { from.collect { publishFromLink(session, link, into, it) } }
        republish(link.stateMachine.serverVersion, _serverVersion)
        republish(link.stateMachine.roundTripTime, _roundTripTime)
        republish(link.path.state, _voicePath)
        republish(link.stateMachine.lastServerReplyAt, _lastServerReplyAt)
        republish(link.stateMachine.channelTree, _channelTree)
        republish(link.stateMachine.userStats, _userStats)
        // Chat is the session's, not the link's: what earlier links received stays, by identity,
        // ahead of this link's own.
        link.childScope.launch {
            link.stateMachine.messages.collect {
                publishFromLink(
                    session, link, _messages,
                    (session.carried + it).takeLast(SessionStateMachine.MAX_MESSAGES),
                )
            }
        }
        // The gate is the session's and survives the swap; the wire state is the link's and starts
        // fresh, so a replacement has to be told what the user already asked for.
        if (session.muted) link.stateMachine.setSelfMute(true)
    }

    /**
     * Opens replacement links until one synchronizes, climbing the ladder on each failure, or
     * the deadline passes. Returns the synchronized link, swapped in and wired; null once the
     * session has been ended or parked here.
     */
    private suspend fun relink(session: Session, pin: String?, deadline: ComparableTimeMark, firstRung: Int): Link? {
        val gen = session.gen
        var rung = firstRung
        while (true) {
            val wait = RELINK_LADDER[minOf(rung, RELINK_LADDER.lastIndex)]
            if (udpClock.markNow() + wait > deadline) {
                Log.w(TAG, "relink gave up gen=$gen")
                publishStatus(gen, ConnectionStatus.Error(ErrorKind.DISCONNECTED, GAVE_UP_DETAIL))
                retire(session)
                return null
            }
            sleep(wait)
            val next = buildLink(session, pin)
            // Published as `next` before the connect, for the same reason the first link is
            // published as `link`: a teardown mid-handshake must find it.
            val live = synchronized(lock) {
                if (gen == generation && current === session) { session.next = next; true } else false
            }
            if (!live) { next.close(); return null }
            Log.i(TAG, "relink gen=$gen rung=$rung")
            val stop = open(session, next)
            if (stop != null) {
                next.close()
                synchronized(lock) { session.next = null }
                when (stop) {
                    // The server's certificate changed under us: the prompt, as a fresh connect
                    // would give it, the session retired and kept aside for trustAndConnect().
                    is ConnectionStatus.AwaitingTrust, is ConnectionStatus.PinMismatch -> {
                        synchronized(lock) { if (gen == generation) trustPrompt = session }
                        publishStatus(gen, stop)
                        retire(session)
                        return null
                    }
                    else -> { rung += 1; continue }
                }
            }
            // Only the state is watched before the swap: a half-built channel tree from the
            // handshake never reaches the UI.
            val outcome = next.stateMachine.state.first {
                it is ConnectionState.Synchronized || it is ConnectionState.Failed
            }
            if (outcome is ConnectionState.Failed) {
                next.close()
                synchronized(lock) { session.next = null }
                classify(gen, outcome)?.let { giveUp ->
                    publishStatus(gen, giveUp)
                    retire(session)
                    return null
                }
                rung += 1
                continue
            }
            next.syncedAt = udpClock.markNow()
            val swapped = synchronized(lock) {
                if (gen == generation && current === session) {
                    session.carried = _messages.value
                    session.link = next
                    session.next = null
                    true
                } else false
            }
            if (!swapped) {
                next.close()
                synchronized(lock) { session.next = null }
                return null
            }
            wire(session, next)
            return next
        }
    }

    /** The status a dead link ends the session with, or null when a replacement is worth trying. */
    private fun classify(gen: Int, failed: ConnectionState.Failed): ConnectionStatus? = when (failed.reason) {
        FailReason.IO, FailReason.TIMEOUT -> null
        // A ghost of ourselves left by a build without a certificate is reaped within 45 s,
        // inside the deadline; every other rejection is final.
        FailReason.AUTH_REJECT ->
            if (failed.rejectType == MumbleProtos.Reject.RejectType.UsernameInUse) null else mapState(gen, failed)
        FailReason.VERSION_TOO_OLD -> mapState(gen, failed)
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val session = synchronized(lock) { trustPrompt } ?: return
        val presented = session.presented ?: return
        scope.launch {
            pinStore.put(session.endpoint.address, presented)
            connect(session.endpoint, session.username, session.password)
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
            // Status, not `current != null`: a retired session has already cleared it, and a late
            // hangup must not replace the error or trust prompt the user is looking at.
            if (gen != generation || !_status.value.ongoing) return
            retireAndClearLocked(ConnectionStatus.Idle)
        }
        prior?.let { teardown(it) }
    }

    override fun sendText(text: String): Boolean = current?.link?.stateMachine?.sendText(text) ?: false

    override fun setSelfDeaf(on: Boolean) { current?.link?.stateMachine?.setSelfDeaf(on) }

    override fun requestUserStats(session: Int) { current?.link?.stateMachine?.requestUserStats(session) }

    override fun requestAudioRoute(routeId: String) {
        // The live session supplies the generation the UI does not carry. TelecomCall re-checks it
        // on its consumer, so a route tapped as a session dies is dropped rather than applied to
        // its successor.
        val session = current ?: return
        call.requestRoute(session.gen, routeId)
    }

    override fun requestCapture() {
        val session = current ?: return
        send(CaptureCommand.Acquire(session))
    }

    /**
     * Re-derive the gate: open when not muted and either Talk is held or voice activity is on.
     * Wanting it open also asks for a session, which is what brings one back after a hold or a
     * terminal engine failure.
     *
     * Levels first, then the live session — the mirror of [openCapture], which publishes the
     * session and then reads the levels. Each side writes one volatile and reads the other, so a
     * press cannot be lost to a racing open: for both to miss, this read of `capture` would have
     * to precede openCapture's write of it *and* openCapture's read of the level precede the
     * caller's write of it, which program order forbids. Every level must stay `@Volatile` for
     * that. When both act, the gate is set twice, which costs nothing.
     */
    private fun apply(session: Session) {
        if (!session.muted && (session.pressed || transmitMode == TransmitMode.VoiceActivity)) {
            send(CaptureCommand.Acquire(session))
        }
        session.capture?.apply(session)
    }

    /** A press while muted stays shut: mute has no engine-side existence, and the Talk button is
     *  only disabled once the server echoes `self_mute`. */
    override fun setTransmitting(on: Boolean) {
        val session = current ?: return
        session.pressed = on
        apply(session)
    }

    /** Switching to push-to-talk lifts a self-mute: that mode has no Mute control, so a mute
     *  carried into it would disable Talk with nothing to clear it. Its gate is closed anyway. */
    override fun setTransmitMode(mode: TransmitMode) {
        transmitMode = mode
        val session = current ?: return
        if (transmitMode != TransmitMode.VoiceActivity && session.muted) setMuted(false) else apply(session)
    }

    /** Closes the gate here, not just on the wire: the microphone goes quiet at the tap rather
     *  than at the server's echo, and a capture session rebuilt after the tap must not come up
     *  transmitting. */
    override fun setMuted(on: Boolean) {
        val session = current ?: return
        val stateMachine = session.link?.stateMachine ?: return
        stateMachine.setSelfMute(on)
        session.muted = on
        apply(session)
    }

    /**
     * Release a still-current session whose link died on its own, or never came up. Deliberately
     * does not bump [generation] or reset any published state — the terminal Error status is what
     * the user is looking at. Clearing [current] is what makes this at-most-once: a later
     * disconnect() sees no prior and a later connect() has nothing to tear down.
     */
    private fun retire(session: Session) {
        val live = synchronized(lock) {
            if (session.gen == generation && current === session) {
                current = null
                // Under the lock, for the same ordering retireAndClearLocked keeps.
                send(CaptureCommand.Release(session, VoiceCall.Reason.SESSION_FAILED))
                true
            } else false
        }
        if (live) teardown(session)
    }

    /** Any thread; nothing here blocks. [session] is already out of [current], its Release queued
     *  by whoever removed it, so this runs at most once per session. */
    private fun teardown(session: Session) {
        // IO because stop() blocks: it joins the receiver's poll, which can be inside a stream
        // start. Its own coroutine, so a stalled socket close cannot delay it. The receiver drops
        // any datagram that reaches it after stop().
        scope.launch(Dispatchers.IO) { session.receiver.stop() }
        // Either the driver published the link under the lock before `current` was cleared, and
        // this closes it, or it will see the session is no longer current and close it itself.
        session.link?.close()
        session.next?.close()
        // Last: retire() reaches here from the driver, which runs on this scope.
        session.scope.cancel()
    }

    private fun mapConnectError(t: Throwable, session: Session): ConnectionStatus {
        val chain = generateSequence(t as Throwable?) { it.cause }.toList()
        (chain.firstOrNull { it is UntrustedCertificateException } as? UntrustedCertificateException)?.let {
            session.presented = it.fingerprint
            return ConnectionStatus.AwaitingTrust(it.fingerprint)
        }
        (chain.firstOrNull { it is PinMismatchException } as? PinMismatchException)?.let {
            session.presented = it.presented
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

        /** A link that stayed synchronized this long was a working path: losing it is a new
         *  outage, not another failure of the one being retried. */
        val HEALTHY_AFTER = 30.seconds

        /** How long after losing a healthy link the session keeps trying before it ends. Long
         *  enough for an elevator or a tunnel; short enough that a dead server does not hold a
         *  platform call for minutes. */
        val RELINK_DEADLINE = 2.minutes

        /** Waits between replacement attempts; the last rung repeats. */
        val RELINK_LADDER = listOf(0, 1, 2, 4, 8, 16, 30).map { it.seconds }

        const val GAVE_UP_DETAIL = "reconnect gave up after 2 min"

        /** Tunneled for its side effect, never answered. Any frame of two bytes or more would
         *  do; this one is honest about what it is. */
        val TUNNEL_PING: ByteArray =
            byteArrayOf(1) + MumbleUdpProtos.Ping.newBuilder().setTimestamp(1).build().toByteArray()
    }
}
