package me.danielstiner.dumble.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.ComparableTimeMark
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.net.AndroidNetworkWatch
import me.danielstiner.dumble.mumble.net.ClientIdentityStore
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.NetworkWatch
import me.danielstiner.dumble.mumble.net.NoNetworkWatch
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UdpTransport
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.ConnectionStateMachine
import me.danielstiner.dumble.mumble.protocol.DeafenState
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.AndroidVoiceCall
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
import me.danielstiner.dumble.time.BootTimeSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * Owns what outlives a connect: the [SessionState] behind the [AppState] flows, the [lock] every
 * write to it takes, the transmit-mode setting, and the [Session] opened last, which opens its
 * own [Connection]s.
 *
 * A blocking handshake cannot be preempted, so a superseded session can complete late. [connect]
 * and [disconnect] therefore end the last session, close its publisher and reset the state in one
 * critical section, and its late writes do nothing. A session that failed on its own stays in
 * [current] with its final status published; user actions go through [live], which skips it.
 *
 * The audio-capture half of this class — the command channel, its single consumer, and the
 * level/reconcile model — is documented in `docs/capture.md`.
 */
@Singleton
class AppStateImpl internal constructor(
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
    // Seam: what runs a built pump — its own thread, or a test that steps it.
    private val startPump: (VoiceSender) -> Unit = VoiceSender::start,
    // Seam: the pump's clock, for its stats cadence and its speech stall, so a test that steps the
    // pump runs both on the scheduler's time.
    private val captureClock: TimeSource = TimeSource.Monotonic,
    // Seams: the clock of everything that must count sleep — the sync stamp, the reconnect budget,
    // the UDP transport — which reads zero off-device, so a test injects one; and the ping
    // interval, so a test can keep the ticker out of its way.
    private val udpClock: TimeSource.WithComparableMarks = BootTimeSource,
    private val pingIntervalMs: Long = ConnectionStateMachine.PING_INTERVAL_MS,
    // Seam: the backoff's waits, so its tests drive a clock instead of sleeping.
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    // Seam: what reports a change of the default network; the app registers with ConnectivityManager.
    private val networkWatch: NetworkWatch = NoNetworkWatch,
    // Seams: where everything that suspends runs, and where the three launches that block do
    // (HAL opens, socket closes, the receiver's native destroy). A test passes one
    // StandardTestDispatcher for both and drives the scheduler; production never names another
    // dispatcher below here.
    private val context: CoroutineContext = Dispatchers.Default,
    private val blocking: CoroutineDispatcher = Dispatchers.IO,
    // Seam: the UDP voice socket, so a test runs a connection's UDP wiring on virtual time
    // against a fake instead of a socket and its reader thread.
    private val newUdp: (CryptState, MumbleUdpTransport.Listener) -> UdpTransport =
        { crypt, listener -> MumbleUdpTransport(crypt, listener, udpClock) },
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) : AppState {
    @Inject constructor(
        @ApplicationContext context: Context,
        pinStore: PinStore,
        identityStore: ClientIdentityStore,
    ) : this(
        pinStore, { openNativeCapture(context) },
        { openNativePlayout() },
        AndroidVoiceCall(context),
        networkWatch = AndroidNetworkWatch(context),
        newTransport = { MumbleTcpTransport(it, identityStore = identityStore) },
    )

    private val scope = CoroutineScope(context + SupervisorJob())
    /** Never cancelled; the launches that block. */
    private val blockingScope = CoroutineScope(SupervisorJob() + blocking)

    private val state = SessionState()
    override val status: StateFlow<SessionStatus> = state.status.asStateFlow()
    override val serverVersion: StateFlow<ServerVersion?> = state.serverVersion.asStateFlow()
    override val roundTripTime: StateFlow<Duration?> = state.roundTripTime.asStateFlow()
    override val voicePath: StateFlow<VoicePath.State> = state.voicePath.asStateFlow()
    override val lastServerReplyAt: StateFlow<ComparableTimeMark?> = state.lastServerReplyAt.asStateFlow()
    override val channelTree: StateFlow<ChannelTree> = state.channelTree.asStateFlow()
    override val messages: StateFlow<List<ChatMessage>> = state.messages.asStateFlow()
    /** What this session asked of the server: the only self-state there is through an outage,
     *  since the echo stops with the connection. */
    override val selfState: StateFlow<DeafenState> = state.selfState.asStateFlow()
    override val speakingSessions: StateFlow<Set<Int>> = state.speakingSessions.asStateFlow()
    override val selfSpeaking: StateFlow<Boolean> = state.selfSpeaking.asStateFlow()
    override val callHeld: StateFlow<Boolean> = state.callHeld.asStateFlow()
    override val playoutStats: StateFlow<PlayoutStats?> = state.playoutStats.asStateFlow()
    override val captureStats: StateFlow<CaptureStats?> = state.captureStats.asStateFlow()
    override val userStats: StateFlow<UserStats?> = state.userStats.asStateFlow()
    override val audioRoutes: StateFlow<AudioRoutes> = state.audioRoutes.asStateFlow()

    init {
        // One point that mirrors every status transition to logcat, whichever path set it.
        scope.launch { status.collect { Log.i(TAG, "status = $it") } }
    }

    /** Bumped on every change of the default network. A count, not the network: what the driver
     *  asks is whether it has changed since a connection or an attempt was opened. */
    private val networkChanges = MutableStateFlow(0)

    init { networkWatch.start { networkChanges.update { it + 1 } } }

    private val lock = Any()
    /** Bumped by every connect and disconnect: names the connect a status, the platform call and
     *  a log line are about. */
    private var generation = 0
    /** The last session opened; it may have ended. */
    @Volatile private var current: Session? = null
    private val live: Session? get() = current?.takeUnless { it.ended }

    /** The transmit mode, as a setting: outlives sessions and is applied to every session this
     *  app state opens. UI thread writes it. */
    @Volatile private var transmitMode = TransmitMode.VoiceActivity

    private sealed interface CaptureCommand {
        /** Capture is wanted on this session — the microphone became ready, or Talk was
         *  pressed. Pairs with [Release]; a level-raise, not an open, so repeats are free. */
        data class Acquire(val session: Session) : CaptureCommand
        /** The platform has taken, or returned, the input device for [session]'s call. */
        data class Held(val session: Session, val held: Boolean) : CaptureCommand
        data class Release(val session: Session) : CaptureCommand
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
    internal inner class CaptureSession(
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
                sender.setTransmitting(wantsGate(session))
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

    init {
        // The single owner of capture. On blockingScope, never a session's or a connection's scope
        // — teardown cancels those synchronously and would discard queued commands, leaking the
        // engine and the microphone — and because every handler blocks: newCapture() on the HAL,
        // stop() on OboeCapture::close(). runCatching because a SupervisorJob does not restart a
        // coroutine that threw, and a dead consumer fails silently and permanently.
        //
        // A second init block, not folded into the first: `captureCommands` is declared between
        // them, and Kotlin runs property initializers and init blocks in textual order, so a
        // consumer launched before that declaration can start against a still-null channel — this
        // is what a real, if rare, "Channel.iterator() on null" crash traced back to.
        blockingScope.launch {
            for (cmd in captureCommands) {
                runCatching { dispatch(cmd) }
                    .onFailure { Log.e(TAG, "capture command failed: $cmd", it) }
            }
        }
    }


    /**
     * Any thread; never blocks. Cannot fail: the channel is UNLIMITED and never closed, and its
     * consumer lives on [blockingScope], which is never cancelled. Checked because the failure would be
     * silent and permanent — a dropped Release strands a microphone.
     */
    private fun send(cmd: CaptureCommand) {
        if (captureCommands.trySend(cmd).isFailure) Log.e(TAG, "capture command dropped: $cmd")
    }

    // The call's own flow: an ended session's call is ended, so its late updates are dropped too.
    private fun publishRoutes(session: Session, r: AudioRoutes) =
        synchronized(lock) { if (!session.ended) state.audioRoutes.value = r }

    /** Lifecycle consumer only — every capture transition is serialised through here. */
    private fun dispatch(cmd: CaptureCommand) {
        when (cmd) {
            is CaptureCommand.Acquire -> onAcquire(cmd.session)
            is CaptureCommand.Held -> onHeld(cmd.session, cmd.held)
            is CaptureCommand.Release -> onRelease(cmd.session)
            is CaptureCommand.PumpExited -> onPumpExited(cmd.session, cmd.sender)
            is CaptureCommand.WedgeCheck -> onWedgeCheck(cmd.session, cmd.capture)
        }
    }

    /**
     * A capture session was asked for — the microphone became ready, or Talk was pressed. Raises
     * the level and reconciles. Lifecycle consumer only.
     */
    private fun onAcquire(session: Session) {
        session.wanted = true
        reconcile(session)
    }

    /**
     * The platform took the input device out from under [session]'s call — another call, the
     * phone's or another app's, is the case that matters — or gave it back. Records the hold as a
     * level and reconciles, which releases the capture session on a hold and rebuilds it on a
     * resume. Lifecycle consumer only.
     */
    private fun onHeld(session: Session, held: Boolean) {
        // Published under the check's lock: a connect() between the two would clear the banner
        // only for this stale hold to set it again on the new session.
        val live = synchronized(lock) {
            (!session.ended).also { if (it) state.callHeld.value = held }
        }
        // An ended session's hold changes nothing: its capture is released, and the banner
        // belongs to the live one.
        if (live) {
            Log.i(TAG, "call ${if (held) "held" else "resumed"} gen=${session.gen}")
            session.held = held
            reconcile(session)
        } else {
            Log.w(TAG, "call ${if (held) "hold" else "resume"} dropped: gen=${session.gen} not live")
        }
    }

    /**
     * [session] has ended: lower its want and close any capture session it still holds. Queued
     * under [lock] in the critical section that ended it, which is what orders this ahead of
     * anything a replacing session can produce on the same channel. Lifecycle consumer only.
     */
    private fun onRelease(session: Session) {
        session.wanted = false
        reconcile(session)
    }

    /** Lifecycle consumer only. The only place a capture session is created or released. */
    private fun reconcile(session: Session) {
        if (session.wanted && !session.held && session.isLive) {
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
     *  consumer runs on blockingScope. */
    private fun openCapture(session: Session) {
        // Leaves `wanted` set on failure, unlike onPumpExited's terminal branch: opens here are
        // command-rate-bounded rather than a loop, and a Talk press re-asks anyway.
        val handle = newCapture() ?: return
        val sender = VoiceSender(
            handle, { session.sendVoice(it) },
            onExit = { s -> send(CaptureCommand.PumpExited(session, s)) },
            onSpeaking = { speaking -> session.publisher.publish { selfSpeaking.value = speaking } },
            onStats = { stats -> session.publisher.publish { captureStats.value = stats } },
            clock = captureClock,
        )
        // Recheck: the session can end on another thread while newCapture() blocks.
        if (!session.isLive) { handle.stop(); handle.destroy(); return }
        val capture = CaptureSession(handle, sender)
        // Published before the levels are read — the mirror of apply()'s order; see its KDoc.
        session.capture = capture
        // Before the pump starts, so its first poll already reads the mode and the gate.
        capture.apply(session)
        startPump(sender)
    }

    /** Start releasing [capture]: close its stream synchronously, then leave the engine for the
     *  pump's own exit to free via [onPumpExited]. Lifecycle consumer only; callers guarantee
     *  `!session.releasing`. */
    private fun beginRelease(session: Session, capture: CaptureSession) {
        // Synchronous, inline: stop() returns once the pump has closed the Oboe stream (or, wedged,
        // after its bound). That ordering is the entire one-microphone invariant — making it
        // asynchronous would silently reopen the hole.
        capture.stop()
        // The signal belongs to the session being released. A pump that exited already lowered it;
        // a wedged one has not, and left standing it would carry into whatever opens next.
        state.selfSpeaking.value = false
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
            session.publisher.publish { captureStats.value = null }
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
     * End the last session and clear everything published about it, returning it for the caller
     * to close, or null if it had already ended by itself. Shared by [connect] and [disconnect]
     * because the two clearing different subsets is a silent bug — the survivor is a flow the next
     * screen renders with the last session's data. The status is the only thing that legitimately
     * differs, so it is the only parameter.
     *
     * Closing the publisher, ending the session and queuing its Release under the same lock is
     * what makes the writes safe: every write checks the publisher or [Session.ended] under it, and
     * the release is ordered ahead of anything the successor asks. Caller holds [lock].
     */
    private fun retireAndClearLocked(status: SessionStatus): Session? {
        val last = current
        current = null; generation += 1
        last?.publisher?.close()
        // end() queues the capture release here, under the lock: nothing the next session can
        // ask of the capture consumer is queued before it, so the release runs first. trySend
        // never blocks, so it is safe under the lock.
        val prior = last?.takeIf { it.end() }
        state.reset(status)
        return prior
    }

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) =
        connect(endpoint, username, password, answering = null)

    /**
     * Ending the last session, starting the call and publishing the new session are one critical
     * section: connects run on two threads (the UI's and trust-and-connect's), and [lock] hands
     * the call their starts in generation order. Every end goes out under it too, so none can
     * land after a later start.
     *
     * [answering] is the trust prompt this connect accepts. A disconnect or connect since has
     * dismissed it, and then this does nothing.
     */
    private fun connect(endpoint: MumbleEndpoint, username: String, password: String?, answering: Session?) {
        val prior: Session?
        val session: Session
        synchronized(lock) {
            if (answering != null && current !== answering) return
            prior = retireAndClearLocked(SessionStatus.Connecting)
            val gen = generation
            session = Session(
                gen, Publisher(state, lock), endpoint, username, password,
                pinStore, newTransport, newUdp, newPlayout, call, udpClock, pingIntervalMs, sleep,
                networkWatch, networkChanges, parentScope = scope, blockingScope = blockingScope,
                releaseCapture = { send(CaptureCommand.Release(it)) },
            )
            current = session
            // Here rather than in requestCapture(): tying the call to the session, not the
            // microphone, gives a user who denied RECORD_AUDIO a service at all, and receive that
            // survives backgrounding. The service starts a main-looper hop after this returns, while
            // the connect tap still has the app in front — a microphone service cannot start later.
            // No end for the prior's call: this start supersedes it, carrying its mode and route.
            call.start(
                gen, endpoint,
                onHeld = { held ->
                    // The output stream follows the hold too: straight to the receiver, not through
                    // the capture consumer, which can be blocked in newCapture() or stop(). The
                    // receiver is this session's own, so a dead session's hold reaches nothing live.
                    session.receiver.setHeld(held)
                    send(CaptureCommand.Held(session, held))
                },
                onRoutes = { r -> publishRoutes(session, r) },
            )
        }
        prior?.close()
        session.start()
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val session = synchronized(lock) { current?.takeIf { it.trustPending } } ?: return
        val presented = session.presented ?: return
        scope.launch {
            pinStore.put(session.endpoint.address, presented)
            connect(session.endpoint, session.username, session.password, answering = session)
        }
    }

    override fun cancelTrust() = disconnect()

    override fun disconnect() {
        val prior = synchronized(lock) {
            retireAndClearLocked(SessionStatus.Idle)?.also { call.end() }
        }
        prior?.close()
    }

    override fun sendText(text: String): Boolean = live?.sendText(text) ?: false

    override fun setSelfDeaf(on: Boolean) = ask { it.deafen(on) }

    override fun requestUserStats(session: Int) { live?.requestUserStats(session) }

    override fun requestAudioRoute(routeId: String) = call.requestRoute(routeId)

    override fun requestCapture() {
        val session = live ?: return
        send(CaptureCommand.Acquire(session))
    }

    /**
     * Re-derive the gate: open when not muted and either Talk is held or voice activity is on.
     * Wanting it open also asks for a session, which is what brings one back after a terminal
     * engine failure.
     *
     * Levels first, then the live session — the mirror of [openCapture], which publishes the
     * session and then reads the levels. Each side writes one volatile and reads the other, so a
     * press cannot be lost to a racing open: for both to miss, this read of `capture` would have
     * to precede openCapture's write of it *and* openCapture's read of the level precede the
     * caller's write of it, which program order forbids. Every level must stay `@Volatile` for
     * that. When both act, the gate is set twice, which costs nothing.
     */
    private fun apply(session: Session) {
        if (wantsGate(session)) send(CaptureCommand.Acquire(session))
        session.capture?.apply(session)
    }

    private fun wantsGate(session: Session) =
        !session.selfState.muted && (session.pressed || transmitMode == TransmitMode.VoiceActivity)

    /** A press while muted stays shut: mute has no engine-side existence, so nothing below this
     *  refuses it. */
    override fun setTransmitting(on: Boolean) {
        val session = live ?: return
        session.pressed = on
        apply(session)
    }

    /** Switching to push-to-talk lifts the user's own mute: that mode has no Mute control, so one
     *  carried into it would disable Talk with nothing to clear it. A deafen stands. */
    override fun setTransmitMode(mode: TransmitMode) {
        transmitMode = mode
        val session = live ?: return
        val carried = mode != TransmitMode.VoiceActivity && session.selfState.ownMute
        if (carried) ask { it.liftOwnMute() } else apply(session)
    }

    override fun setMuted(on: Boolean) = ask { it.mute(on) }

    /** [Session.ask] on the live session, then the gate, which a mute moves. */
    private fun ask(next: (DeafenState) -> DeafenState) {
        val session = live ?: return
        if (!session.ask(next)) return
        apply(session)   // opens or closes a capture engine; never under the lock
    }

    private companion object {
        const val TAG = "MumbleConn"
    }
}

