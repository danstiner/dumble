package me.danielstiner.dumble.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * own [Connection]s. Builds the one [CaptureLifecycle] its sessions share.
 *
 * A blocking handshake cannot be preempted, so a superseded session can complete late. [connect]
 * and [disconnect] therefore end the last session, close its publisher and reset the state in one
 * critical section, and its late writes do nothing. A session that failed on its own stays in
 * [current] with its final status published; user actions go through [live], which skips it.
 */
@Singleton
class AppStateImpl internal constructor(
    private val pinStore: PinStore,
    // Defaulted so the tests that predate voice capture keep their trailing-lambda transport.
    private val newCapture: () -> VoiceSender.CaptureHandle? = { null },
    // Defaulted to no receive at all, the same way newCapture defaults to no send: a JVM test
    // that never overrides this never touches System.loadLibrary. Real callers get
    // openNativePlayout() from the @Inject constructor below.
    private val newPlayout: () -> VoiceReceiver.PlayoutHandle? = { null },
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
    private var lastId = 0
    /** The last session opened; it may have ended. */
    @Volatile private var current: Session? = null
    private val live: Session? get() = current?.takeUnless { it.ended }

    /** The transmit mode, as a setting: outlives sessions and is applied to every session this
     *  app state opens. UI thread writes it. */
    @Volatile private var transmitMode = TransmitMode.VoiceActivity

    private val captures = CaptureLifecycle(
        newCapture, startPump, captureClock, stuckPumpMillis, { transmitMode },
        scope, blockingScope,
    )

    // The call's own flow: an ended session's call is ended, so its late updates are dropped too.
    private fun publishRoutes(session: Session, r: AudioRoutes) =
        synchronized(lock) { if (!session.ended) state.audioRoutes.value = r }

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
        current = null; lastId += 1
        last?.publisher?.close()
        // end() queues the capture release here, under the lock, so it is ahead of anything the
        // next session queues and runs first. Queuing never blocks, so it is safe under the lock.
        val prior = last?.takeIf { it.end() }
        state.reset(status)
        return prior
    }

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) =
        connect(endpoint, username, password, answering = null)

    /**
     * Ending the last session, starting the call and publishing the new session are one critical
     * section: connects run on two threads (the UI's and trust-and-connect's), and [lock] hands
     * the call their starts in connect order. Every end goes out under it too, so none can
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
            val id = lastId
            session = Session(
                id, Publisher(state, lock), endpoint, username, password,
                pinStore, newTransport, newUdp, newPlayout, call, udpClock, pingIntervalMs, sleep,
                networkWatch, networkChanges, parentScope = scope, blockingScope = blockingScope,
                captures = captures,
            )
            current = session
            // Here rather than in requestCapture(): tying the call to the session, not the
            // microphone, gives a user who denied RECORD_AUDIO a service at all, and receive that
            // survives backgrounding. The service starts a main-looper hop after this returns, while
            // the connect tap still has the app in front — a microphone service cannot start later.
            // No end for the prior's call: this start supersedes it, carrying its mode and route.
            call.start(
                id, endpoint,
                onHeld = { held ->
                    // The output stream follows the hold too: straight to the receiver, not through
                    // the capture consumer, which can be blocked in newCapture() or stop(). The
                    // receiver is this session's own, so a dead session's hold reaches nothing live.
                    session.receiver.setHeld(held)
                    captures.held(session.capture, held)
                },
                onRoutes = { r -> publishRoutes(session, r) },
            )
        }
        prior?.close()
        session.start()
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val session = synchronized(lock) { current } ?: return
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
        captures.acquire(session.capture)
    }

    /** A press while muted stays shut: mute has no engine-side existence, so nothing below this
     *  refuses it. */
    override fun setTransmitting(on: Boolean) {
        val session = live ?: return
        captures.press(session.capture, on)
    }

    /** Switching to push-to-talk lifts the user's own mute: that mode has no Mute control, so one
     *  carried into it would disable Talk with nothing to clear it. A deafen stands. */
    override fun setTransmitMode(mode: TransmitMode) {
        transmitMode = mode
        val session = live ?: return
        val carried = mode != TransmitMode.VoiceActivity && session.selfState.ownMute
        if (carried) ask { it.liftOwnMute() } else captures.apply(session.capture)
    }

    override fun setMuted(on: Boolean) = ask { it.mute(on) }

    /** [Session.ask] on the live session, then the gate, which a mute moves. */
    private fun ask(change: (DeafenState) -> DeafenState) {
        val session = live ?: return
        if (!session.ask(change)) return
        // Sets the gate and may ask for capture. Never under the lock.
        captures.apply(session.capture)
    }

    private companion object {
        const val TAG = "MumbleConn"
    }
}

