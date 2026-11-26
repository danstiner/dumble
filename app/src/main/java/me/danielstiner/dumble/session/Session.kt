package me.danielstiner.dumble.session

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionOptions
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.LocalNetworkBlockedException
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.NetworkWatch
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UdpTransport
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.ConnectionStateMachine
import me.danielstiner.dumble.mumble.protocol.DeafenState
import me.danielstiner.dumble.mumble.protocol.FailReason
import me.danielstiner.dumble.mumble.voice.ACCOUNTED_BITRATE
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import java.net.SocketTimeoutException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The seams every session is built from; the app state makes one. */
internal class SessionParts(
    val pinStore: PinStore,
    val newTransport: (expectedPin: String?) -> MumbleControlTransport,
    val newUdp: (CryptState, MumbleUdpTransport.Listener) -> UdpTransport,
    val newPlayout: () -> VoiceReceiver.PlayoutEngine?,
    val call: VoiceCall,
    /** The clock of everything that must count sleep; see AppStateImpl's `udpClock`. */
    val bootClock: TimeSource.WithComparableMarks,
    val pingIntervalMs: Long,
    val sleep: suspend (Duration) -> Unit,
    val networkWatch: NetworkWatch,
    /** Bumped on every change of the default network. */
    val networkChanges: StateFlow<Int>,
    /** Parent of every session's and connection's scope. */
    val scope: CoroutineScope,
    /** Never cancelled; the launches that block. */
    val blockingScope: CoroutineScope,
    /** Queues the capture release of a session that has just ended. Called under the lock. */
    val releaseCapture: (Session) -> Unit,
)

/**
 * Everything one Connect starts. It lasts until the user disconnects or connects elsewhere, or it
 * fails for good; each reconnect in between is a new [Connection] in the same session.
 *
 * It ends in one of two ways. Its owner supersedes or disconnects it: [end] inside the owner's
 * critical section, then [close] once the lock is dropped. Or it fails for good and ends itself
 * ([endSelf]), keeping its publisher open, so its final status stays published.
 *
 * Capture state hangs off the session, not the app state, because a release must find the
 * session that opened the microphone rather than whichever session replaced it.
 *
 * The mutable fields are either `@Volatile` (crossing threads) or plain (confined to the
 * lifecycle consumer's single coroutine).
 */
internal class Session(
    val gen: Int,
    val publisher: Publisher,
    val endpoint: MumbleEndpoint,
    val username: String,
    val password: String?,
    private val parts: SessionParts,
) {
    private val lock = publisher.lock
    private val state = publisher.state

    /** Built once per session and fed packets by its connection; never owns a transport. */
    val receiver: VoiceReceiver =
        // newPlayout itself, not its result: VoiceReceiver only calls it from start(), which
        // only a session whose connection comes up ever reaches — building eagerly would leak
        // one per session that fails or is superseded before that.
        VoiceReceiver(parts.newPlayout, context = parts.scope.coroutineContext)
    /** The driver and the receiver's collectors. Cancelled by [close], last. */
    private val scope: CoroutineScope =
        CoroutineScope(parts.scope.coroutineContext + SupervisorJob())
    /** The connection carrying this session, or null until the driver has built one. Written
     *  only under [lock], by the driver; read on the pump thread by [sendVoice]. */
    @Volatile var connection: Connection? = null
        private set
    /** A replacement connection being opened; the driver's, written under [lock] so a teardown
     *  during its blocking handshake finds it and closes it. */
    @Volatile private var next: Connection? = null
    /** Chat from connections this session has already replaced, oldest first. Written by the
     *  driver at the swap, read by the new connection's collector on its own scope. */
    @Volatile private var carried: List<ChatMessage> = emptyList()
    /** Fingerprint the server presented when the handshake stopped for a trust decision;
     *  what [AppStateImpl.trustAndConnect] pins. Written by the driver, read on caller threads. */
    @Volatile var presented: String? = null
        private set
    /** The live capture session, or null. One field, one lifetime: splitting handle and pump
     *  would leave a window where a hold could open a second microphone stream. Written only
     *  by the lifecycle consumer; read on the UI thread by [AppStateImpl.apply]. */
    @Volatile var capture: AppStateImpl.CaptureSession? = null
    /** Talk is held. A level, not an edge, so a capture session rebuilt under a still-held
     *  button comes up transmitting. UI thread writes it; [AppStateImpl.openCapture] reads it
     *  after publishing [capture] — see [AppStateImpl.apply] for why that order matters, and keep
     *  both `@Volatile`. */
    @Volatile var pressed: Boolean = false
    /** Self-mute and self-deafen as last asked for, not as last sent: a tap during a reconnect
     *  reaches no connection, and this is what the replacement is told. */
    @Volatile var selfState: DeafenState = DeafenState()
        private set
    /** The app wants capture on this session — the level [AppStateImpl.reconcile] opens from.
     *  Raised by Acquire, cleared by Release and by a terminal pump exit. */
    var wanted: Boolean = false
    /** A release is in flight: stop() has returned and only the pump's own exit will free the
     *  engine. Keeps [AppStateImpl.reconcile] from starting a second release meanwhile; cleared by
     *  [AppStateImpl.onPumpExited]. */
    var releasing: Boolean = false
    /** The platform is holding this session's call. Lifecycle consumer only. */
    var held: Boolean = false

    /** Superseded, disconnected, or failed. Written under the lock. */
    @Volatile var ended: Boolean = false
        private set
    /** The handshake stopped for a trust decision. Written under the lock, before the status that
     *  shows it. */
    @Volatile var trustPending: Boolean = false
        private set

    /** Whether this is still the live session, read under the lock: the gate every open decision
     *  takes. */
    val isLive: Boolean get() = synchronized(lock) { !ended }

    /** Starts the receiver's collectors and the driver. Once, after the owner has dropped the
     *  lock. */
    fun start() {
        scope.launch {
            receiver.speakingSessions.collect { ids ->
                publisher.publish { speakingSessions.value = ids }
            }
        }
        scope.launch {
            receiver.playoutStats.collect { stats ->
                publisher.publish { playoutStats.value = stats }
            }
        }
        scope.launch { drive() }
    }

    /** Ends this session and queues its capture release. Caller holds the lock. False if it
     *  had already ended. */
    fun end(): Boolean {
        if (ended) return false
        ended = true
        parts.releaseCapture(this)
        return true
    }

    /** Any thread; nothing here blocks. After the session has ended, at most once. */
    fun close() {
        // blockingScope because stop() ends with the native stream's destroy, which blocks; its
        // join of the poll suspends. Its own coroutine, so a stalled socket close cannot delay
        // it. The receiver drops any datagram that reaches it after stop().
        parts.blockingScope.launch { receiver.stop() }
        // Either the driver published the connection under the lock before the session ended,
        // and this closes it, or it will see the session has ended and close it itself.
        connection?.close()
        next?.close()
        // Last: endSelf() reaches here from the driver, which runs on this scope.
        scope.cancel()
    }

    /**
     * Advances what the session asks, publishes it, and sends it to whatever connection is there,
     * all under [lock] so a swap's wire() cannot slip between a tap's write and its send and leave
     * the server on the older of the two. During an outage the send reaches no connection; the
     * swap makes it. False if the session has ended.
     */
    fun ask(next: (DeafenState) -> DeafenState): Boolean {
        synchronized(lock) {
            if (ended) return false
            selfState = next(selfState)
            state.selfState.value = selfState
            connection?.sendSelfState(selfState)
        }
        return true
    }

    /** On the session's connection; dropped while it has none. Pump thread: reads the connection
     *  once, so a swap cannot split one packet. */
    fun sendVoice(payload: ByteArray): Boolean =
        connection?.sendVoice(payload) ?: false

    fun sendText(text: String): Boolean = connection?.sendText(text) ?: false

    fun requestUserStats(session: Int) { connection?.requestUserStats(session) }

    /**
     * A connection's own flow, guarded on the connection's identity and its close flag as well as
     * the publisher: cancelling a connection's collectors is no barrier for one already inside its
     * body. Best effort, since the flag is raised outside this lock. A kick's UserRemove lands
     * before the close, on a live connection; the UI blocking on Reconnecting is what keeps it from
     * reading as "you left".
     */
    private fun <T> publishFromConnection(connection: Connection, flow: MutableStateFlow<T>, value: T) =
        publisher.publish {
            if (this@Session.connection === connection && !connection.isClosed) flow.value = value
        }

    /** One TLS connect, built but not yet connected. */
    private fun buildConnection(pin: String?): Connection {
        val connectionScope = CoroutineScope(parts.scope.coroutineContext + SupervisorJob())
        return Connection(
            ConnectionOptions(
                endpoint, username, password, pin,
                voiceBitrate = ACCOUNTED_BITRATE,
            ),
            parts.newTransport, parts.newUdp, receiver::onVoicePacket,
            parts.networkWatch.current, parts.networkChanges.value, connectionScope,
            parts.blockingScope,
            // The driver compares the sync stamp against its own clock, and marks from two
            // sources cannot be compared.
            bootClock = parts.bootClock,
            pingIntervalMs = parts.pingIntervalMs,
            gen = gen,
        )
    }

    /**
     * The session's driver: opens its first connection and holds it until the state machine fails,
     * then replaces the connection under the same session until one synchronizes or the deadline
     * passes. One coroutine per session, on the session's own scope, so [close] cancels it
     * wherever it waits; the connections it built are closed by [close] itself, not by the
     * cancellation.
     */
    private suspend fun drive() {
        val pin = parts.pinStore.get(endpoint.address)
        Log.i(TAG, "connect gen=$gen endpoint=${endpoint.address} user=${username} storedPin=${pin != null}")
        // The loop below runs for minutes beside a live platform call: an escape would leave
        // the call up with nothing driving it.
        try {
            var connection = buildConnection(pin)
            // Published before the connect, so a teardown that lands during the blocking handshake
            // finds the connection and closes it; see Connection.close for what the transport does
            // with a handshake that finishes around that close.
            val live = synchronized(lock) {
                if (!ended) { this.connection = connection; true } else false
            }
            if (!live) { connection.close(); return }   // superseded during the pin lookup

            open(connection)?.let { fail(it); return }
            if (!isLive) { connection.close(); return }   // superseded mid-handshake
            wire(connection)
            // Its own coroutine so the state collector below subscribes before start() opens the
            // output stream, ~100 ms of HAL on a Pixel 7a, which is also why the lock is not held
            // across it — a teardown landing in between is the receiver's own latch to handle.
            // Every earlier return skips this, so a session that never gets here never calls
            // newPlayout().
            scope.launch { if (isLive) receiver.start() }
            // A network change is news the sockets have not had. A live connection whose network is
            // gone is dead (a Pixel 7a took 23 s to notice on its own), and an attempt in flight
            // was dialed on the network before this one and would run out its own timeout. A
            // connection whose network merely stopped being the default is left alone: with a LAN
            // server on a WiFi that lost its uplink, it is the only connection that reaches the
            // server. Filtered on the first connection's stamp, so a change during its handshake
            // still counts.
            val firstDialedUnder = connection.dialedUnder
            scope.launch {
                parts.networkChanges.filter { it != firstDialedUnder }.collect { changes ->
                    val doomed = synchronized(lock) {
                        next?.takeIf { it.dialedUnder != changes }
                            ?: this@Session.connection?.takeIf {
                                it.network?.let { n -> !parts.networkWatch.isUp(n) } ?: true
                            }
                    } ?: return@collect
                    val what = if (doomed === next) "the attempt" else "the link, its network gone"
                    Log.i(TAG, "network changed gen=$gen; closing $what")
                    doomed.close()
                }
            }

            var deadline: ComparableTimeMark? = null   // the open incident's end, if one is open
            while (true) {
                // Status is the driver's own collector, not one of the connection's: the terminal
                // Error must be on `status` before endSelf() cancels this coroutine's scope, and a
                // Failed is never published before it is classified.
                val failed = connection.state
                    .onEach { st ->
                        if (st !is ConnectionState.Failed) mapState(gen, st)?.let { mapped ->
                            publisher.publish { status.value = mapped }
                        }
                    }
                    .first { it is ConnectionState.Failed } as ConnectionState.Failed
                // The connection's own stamp, not what a collector on a conflating flow saw: it
                // decides the retry and names the row the UI reads.
                val synced = connection.sync
                // The first connection dying before it synchronized is the connect failing, never
                // retried; every later connection came out of replaceConnection synchronized.
                if (synced == null) {
                    fail(mapState(gen, failed)!!)
                    return
                }
                val now = parts.bootClock.markNow()
                // Past Synchronized a connection ends as IO, from its socket, or by the server
                // removing us, which is final: a kick rejoined at once is a kick again.
                classify(gen, failed, now)?.let { fail(it); return }
                // Losing a healthy connection is a new outage: fresh deadline, backoff from the
                // start. Losing one that never got healthy continues the outage, one attempt in,
                // against the same deadline, which is what bounds a path that dies every few
                // seconds.
                val healthy = now - synced.at >= HEALTHY_AFTER
                val attempt: Int
                if (healthy || deadline == null) {
                    deadline = now + GIVE_UP_AFTER
                    attempt = 0
                } else {
                    // Time spent synchronized was not spent reconnecting: given back, or a path
                    // that comes up for twenty seconds at a time spends the budget while the user
                    // is talking.
                    deadline += now - synced.at
                    attempt = 1
                }
                Log.i(TAG, "link died gen=$gen reason=${failed.reason} healthy=$healthy attempt=$attempt")
                publisher.publish {
                    status.value = SessionStatus.Reconnecting(gen, synced.sessionId)
                }
                connection.close()
                connection = replaceConnection(pin, deadline, diedAt = now, firstAttempt = attempt,
                    since = connection.dialedUnder)
                    ?: return
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "driver failed gen=$gen", t)
            fail(SessionStatus.Error(ErrorKind.CONNECT_FAILED, t.message))
        }
    }

    /**
     * Connects [connection] and starts its protocol. Null on success; otherwise the status the
     * failure maps to, with the connection still open: the caller either drops it or ends the
     * session it belongs to, which closes it.
     */
    private suspend fun open(connection: Connection): SessionStatus? {
        try {
            connection.connect()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "connect failed gen=${gen} for ${endpoint.address}", t)
            return mapConnectError(t)
        }
        connection.start()
        return null
    }

    /**
     * Republishes [connection]'s flows as the session's. It must already be the session's
     * connection.
     */
    private fun wire(connection: Connection) {
        // Seeded here, before the driver publishes this connection's Connected: a collector on its
        // own coroutine would leave the dead connection's tree published under the new session id,
        // one without our own row in it.
        fun <T> republish(from: StateFlow<T>, into: MutableStateFlow<T>) {
            publishFromConnection(connection, into, from.value)
            connection.scope.launch { from.collect { publishFromConnection(connection, into, it) } }
        }
        republish(connection.serverVersion, state.serverVersion)
        republish(connection.roundTripTime, state.roundTripTime)
        republish(connection.voicePath, state.voicePath)
        republish(connection.lastServerReplyAt, state.lastServerReplyAt)
        republish(connection.channelTree, state.channelTree)
        republish(connection.userStats, state.userStats)
        // Chat is the session's: what earlier connections received stays ahead of this connection's
        // own.
        connection.scope.launch {
            connection.messages.collect {
                publishFromConnection(
                    connection, state.messages,
                    (carried + it).takeLast(ConnectionStateMachine.MAX_MESSAGES),
                )
            }
        }
        // The wire state starts fresh per connection, so the replacement is told what the session
        // last asked for, deafen included; taps during the outage reached no connection, so this is
        // where they take effect. Under the lock, a tap racing the swap either precedes this read
        // or follows the send.
        synchronized(lock) {
            selfState.takeIf { it != DeafenState() }
                ?.let { connection.sendSelfState(it) }
        }
    }

    /**
     * Opens replacement connections until one synchronizes, backing off further on each failure, or
     * the deadline passes. Returns the synchronized connection, swapped in and wired; null once the
     * session has ended.
     */
    private suspend fun replaceConnection(
        pin: String?,
        deadline: ComparableTimeMark,
        diedAt: ComparableTimeMark,
        firstAttempt: Int,
        /** The change count the dead connection was dialed under. */
        since: Int,
    ): Connection? {
        var attempt = firstAttempt
        var seen = since
        var lastDial: ComparableTimeMark? = null
        while (true) {
            // Clamped, not skipped: the network is often back inside the last, clamped wait.
            // Measured on a Pixel 7a: WiFi returned with 28.8 s of budget left and a 30 s wait
            // next, while the in-flight connect, bound to the dead interface, ran out its own
            // timeout. One last attempt at the deadline costs one connect.
            val remaining = deadline - parts.bootClock.markNow()
            if (remaining <= Duration.ZERO) {
                Log.w(TAG, "gave up gen=$gen")
                fail(SessionStatus.Error(ErrorKind.DISCONNECTED, GAVE_UP_DETAIL))
                return null
            }
            // delay's clock and the deadline's boot clock disagree only across a suspend, and the
            // backoff cannot be suspended through: playout and capture stay open across the swap,
            // and audioserver holds partial wakelocks (AudioMix, AudioIn) while they are —
            // measured held across a whole outage.
            // Attempt 0 is the first since the network last changed, unless the last dial was
            // under a second ago: a flapping default must not turn the backoff into a connect
            // storm.
            if (parts.networkChanges.value != seen) {
                seen = parts.networkChanges.value
                if (lastDial == null || parts.bootClock.markNow() - lastDial >= BACKOFF[1]) {
                    attempt = 0
                }
            }
            if (backOff(minOf(BACKOFF[minOf(attempt, BACKOFF.lastIndex)], remaining))) continue
            val next = buildConnection(pin)
            // Published before the connect for the same reason the first connection is: a teardown
            // mid-handshake has to find it.
            val live = synchronized(lock) {
                if (!ended) { this.next = next; true } else false
            }
            if (!live) { next.close(); return null }
            // A change between the dial and the publish is one the collector could not have seen.
            if (parts.networkChanges.value != next.dialedUnder) { drop(next); continue }
            Log.i(TAG, "replacement gen=$gen attempt=$attempt")
            lastDial = parts.bootClock.markNow()
            val failure = open(next)
            if (failure != null) {
                drop(next)
                // The server's certificate changed, or Android is blocking this address. Both wait
                // on the user, so end the session and show the prompt a fresh connect would.
                if (failure is SessionStatus.AwaitingTrust || failure is SessionStatus.PinMismatch ||
                    (failure as? SessionStatus.Error)?.kind == ErrorKind.LOCAL_NETWORK_BLOCKED
                ) {
                    fail(failure)
                    return null
                }
                Log.w(TAG, "replacement failed gen=$gen attempt=$attempt status=$failure")
                attempt += 1
                continue
            }
            // Only the state is watched before the swap: a half-built channel tree from the
            // handshake never reaches the UI.
            val outcome = next.state.first {
                it is ConnectionState.Synchronized || it is ConnectionState.Failed
            }
            if (outcome is ConnectionState.Failed) {
                drop(next)
                classify(gen, outcome, diedAt)?.let { fail(it); return null }
                Log.w(
                    TAG,
                    "replacement handshake failed gen=$gen attempt=$attempt reason=${outcome.reason} " +
                        "detail=${outcome.detail}",
                )
                attempt += 1
                continue
            }
            val swapped = synchronized(lock) {
                if (!ended) {
                    carried = state.messages.value
                    connection = next
                    this.next = null
                    true
                } else false
            }
            if (!swapped) { drop(next); return null }
            wire(next)
            return next
        }
    }

    /** Waits [wait], or less: true if the default network changed meanwhile, the caller's cue
     *  to go round again. */
    private suspend fun backOff(wait: Duration): Boolean {
        val seen = parts.networkChanges.value
        merge(flow { parts.sleep(wait); emit(Unit) },
            parts.networkChanges.filter { it != seen }.map { }).first()
        return parts.networkChanges.value != seen
    }

    /**
     * Ends the session on [status]: published before [endSelf], which cancels the scope this runs
     * on. Ending keeps a Talk press from opening a microphone against a finished call. A trust
     * prompt raises [trustPending] before its status, since [AppStateImpl.trustAndConnect] can
     * follow that at once.
     */
    private fun fail(status: SessionStatus) {
        if (status is SessionStatus.AwaitingTrust || status is SessionStatus.PinMismatch) {
            Log.i(TAG, "handshake stopped for trust decision: $status")
            synchronized(lock) { trustPending = true }
        }
        publisher.publish { this.status.value = status }
        endSelf()
    }

    /** Closes a replacement that will not be swapped in, and unpublishes it. */
    private fun drop(connection: Connection) {
        connection.close()
        synchronized(lock) { if (next === connection) next = null }
    }

    /**
     * The status a dead connection ends the session with, or null when a replacement is worth
     * trying. [diedAt] is when the connection whose ghost might still hold the name died — the
     * backoff's own start, not the outage's, since every replacement that synchronizes leaves a
     * fresh ghost.
     */
    private fun classify(
        gen: Int,
        failed: ConnectionState.Failed,
        diedAt: ComparableTimeMark,
    ): SessionStatus? = when (failed.reason) {
        FailReason.IO, FailReason.TIMEOUT -> null
        // Past GHOST_REAP the name is someone else's, and retrying would only end on a timeout
        // that says nothing about why. Every other rejection is final at once.
        FailReason.AUTH_REJECT ->
            if (failed.rejectType == MumbleProtos.Reject.RejectType.UsernameInUse &&
                parts.bootClock.markNow() - diedAt < GHOST_REAP
            ) {
                null
            } else {
                mapState(gen, failed)
            }
        FailReason.VERSION_TOO_OLD, FailReason.KICKED, FailReason.BANNED -> mapState(gen, failed)
    }

    /**
     * Ends a session whose connection died on its own, or never came up. Deliberately does not
     * close its publisher or reset published state beyond the call's own — the terminal status is
     * what the user is looking at. [end] makes this at-most-once: a later connect() or
     * disconnect() finds the session ended and has nothing to release or close.
     */
    private fun endSelf() {
        val live = synchronized(lock) {
            // Under the lock, for the same ordering AppStateImpl.retireAndClearLocked keeps.
            if (end()) {
                parts.call.end()
                state.callHeld.value = false
                state.audioRoutes.value = AudioRoutes()
                true
            } else false
        }
        if (live) close()
    }

    private fun mapConnectError(t: Throwable): SessionStatus {
        val chain = generateSequence(t as Throwable?) { it.cause }.toList()
        (chain.firstOrNull { it is UntrustedCertificateException } as? UntrustedCertificateException)?.let {
            presented = it.fingerprint
            return SessionStatus.AwaitingTrust(it.fingerprint)
        }
        (chain.firstOrNull { it is PinMismatchException } as? PinMismatchException)?.let {
            presented = it.presented
            return SessionStatus.PinMismatch(it.stored, it.presented)
        }
        if (chain.any { it is LocalNetworkBlockedException }) return SessionStatus.Error(ErrorKind.LOCAL_NETWORK_BLOCKED, t.message)
        if (chain.any { it is SocketTimeoutException }) return SessionStatus.Error(ErrorKind.TIMEOUT, t.message)
        return SessionStatus.Error(ErrorKind.CONNECT_FAILED, t.message)
    }

    private companion object {
        const val TAG = "MumbleConn"

        /** A connection that stayed synchronized this long was a working path: losing it is a new
         *  outage, not another failure of the one being retried. */
        val HEALTHY_AFTER = 30.seconds

        /** How long after losing a healthy connection the session keeps trying before it ends. Long
         *  enough for an elevator or a tunnel; short enough that a dead server does not hold a
         *  platform call for minutes. */
        val GIVE_UP_AFTER = 2.minutes

        /** How long murmur takes to reap a ghost of ourselves still holding the name — its
         *  own idle timeout, and the only rejection a retry can outlast. */
        val GHOST_REAP = 45.seconds

        /** The wait before each replacement attempt, exponential; the last repeats. */
        val BACKOFF = listOf(0, 1, 2, 4, 8, 16, 30).map { it.seconds }

        /** No number: an attempt that hangs can finish past the deadline. */
        const val GAVE_UP_DETAIL = "could not get back to the server"
    }
}
