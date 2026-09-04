package me.danielstiner.dumble.mumble.protocol

import android.util.Log
import com.google.protobuf.ByteString
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.voice.ACCOUNTED_BITRATE
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import me.danielstiner.dumble.time.BootTimeSource
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.ChannelTreeReducers
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.chat.DenyReason
import me.danielstiner.dumble.mumble.proto.MumbleProtos

/**
 * Drives the control-channel handshake to [ConnectionState.Synchronized].
 *
 * Threading: [onFrame] and [onClosed] arrive on the transport's single reader coroutine, one at a
 * time and never nested, so fields only they touch need no synchronization. Anything shared with
 * the ping ticker or with `start()`/`setSelfDeaf` (a third, caller thread) is volatile or a
 * [MutableStateFlow]. [sent] is one immutable value rather than three booleans so its parts cannot
 * be read torn apart.
 */
class SessionStateMachine(
    private val channel: ControlChannel,
    private val username: String,
    private val password: String?,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    // Counts deep sleep, unlike System.nanoTime. See BootTimeSource.
    private val bootClock: TimeSource.WithComparableMarks = BootTimeSource,
    // Seam: a connection test that watches the UDP ping cadence would otherwise wait real
    // intervals out. Only the ticker reads it; the thresholds derived from the constant stay.
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _roundTripTime = MutableStateFlow<Duration?>(null)
    val roundTripTime: StateFlow<Duration?> = _roundTripTime.asStateFlow()

    private val _serverVersion = MutableStateFlow<ServerVersion?>(null)
    val serverVersion: StateFlow<ServerVersion?> = _serverVersion.asStateFlow()

    // Only ever written from onFrame, the transport's single reader coroutine, so a plain
    // read-modify-write assignment is race-free — unlike _state, which the deadline coroutine
    // also writes.
    private val _channelTree = MutableStateFlow(ChannelTree())
    val channelTree: StateFlow<ChannelTree> = _channelTree.asStateFlow()

    // Two writers — the reader coroutine (inbound frames) and the caller thread (sendText) — so this
    // uses an atomic update, unlike the single-writer _channelTree above.
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Capped to the most recent MAX_MESSAGES — the log is display-only and never re-read in full, so
    // old lines are dropped rather than retained for the whole connection. update() is atomic against
    // the two writers above.
    private fun appendMessage(msg: ChatMessage) {
        _messages.update { (it + msg).takeLast(MAX_MESSAGES) }
    }

    private val _userStats = MutableStateFlow<UserStats?>(null)
    val userStats: StateFlow<UserStats?> = _userStats.asStateFlow()

    /**
     * The voice cipher. Owned here because CryptSetup, which keys it and resyncs it, is a
     * control-channel message; the UDP transport borrows it to seal and open datagrams.
     */
    val crypt = CryptState()

    /**
     * Sends one UDP connectivity ping, if the attempt has a socket to send it on. Fired the moment
     * CryptSetup keys [crypt], which is when the server can first match our address, then once
     * per tick beside the TCP ping, so one ticker carries both as desktop's does. Not at
     * ServerSync as well: that follows keying within milliseconds, and a ping whose reply had no
     * time to arrive would read as unanswered to anything judging the cadence. Null until the
     * connection wires it.
     */
    @Volatile var udpPing: (() -> Unit)? = null

    /**
     * Tunneled voice payloads. A callback rather than a StateFlow: this is a per-frame hot path
     * and StateFlow conflates, so a dropped emission would be dropped audio. Invoked on the
     * transport's single reader coroutine, the same context as every other frame handler.
     */
    fun interface AudioListener {
        fun onTunneledAudio(payload: ByteArray)
    }

    @Volatile var audioListener: AudioListener? = null

    @Volatile private var deadlineJob: Job? = null
    @Volatile private var pingJob: Job? = null

    /**
     * What [setSelfDeaf] last put on the wire. Distinct from [channelTree], which is what the server
     * believes and what the UI renders — see [DeafenState.deafen] for why advancing from the echo
     * instead of from this strands the user muted.
     */
    @Volatile private var sent = DeafenState()

    /** The wire wants a number and Duration arithmetic wants a mark; this bridges them. */
    private val pingOrigin = bootClock.markNow()

    private val _lastServerReplyAt = MutableStateFlow<ComparableTimeMark?>(null)

    /**
     * When the server last said anything: seeded at ServerSync, then advanced by each ping reply.
     * Null only before Synchronized. Seeded rather than left null so a server that completes the
     * handshake and then answers no ping still ages — otherwise it would read healthy forever.
     * The UI ages this against [DEGRADED_PING_AGE]; nothing here ends a session on it.
     *
     * An instant, not an age, because what changes an age is the passage of time — deriving it in
     * the UI's own tick also means a doze shows up, which a count of unanswered pings cannot see:
     * [delay] stops with the CPU, so no tick fires to do the counting.
     */
    val lastServerReplyAt: StateFlow<ComparableTimeMark?> = _lastServerReplyAt.asStateFlow()

    /**
     * No-op unless this is the first attempt on a fresh machine. The transport's reader is already
     * live when `connect()` returns, so a socket that dies before this runs has already settled a
     * terminal state — a plain assignment would resurrect it and then report the deadline's timeout
     * instead of the real cause. Every other transition here settles by compare-and-set for the same
     * reason; this is the entry point that has to agree.
     */
    fun start() {
        if (!_state.compareAndSet(ConnectionState.Disconnected, ConnectionState.Handshaking)) return

        val version = MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setVersionV1(MumbleVersion.encodeV1(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setRelease("Dumble")
            .setOs("Android")
            .build()

        val authenticate = MumbleProtos.Authenticate.newBuilder()
            .setUsername(username)
            .also { if (password != null) it.password = password }
            .setOpus(true)
            .build()

        // A frame that never reached the wire must be reported as what it is. Left unchecked it
        // surfaces 15 seconds later as the deadline's timeout, blaming the server for a local fault.
        if (!channel.send(TcpMessageType.Version, version)) {
            return fail(FailReason.IO, "could not send Version")
        }
        if (!channel.send(TcpMessageType.Authenticate, authenticate)) {
            return fail(FailReason.IO, "could not send Authenticate")
        }

        // The transport's connect timeout bounds only the socket connect; the handshake exchange
        // is otherwise unbounded, so a server that accepts and then stalls would hang forever.
        deadlineJob = scope.launch {
            delay(HANDSHAKE_DEADLINE_MS)
            val timedOut = ConnectionState.Failed(
                FailReason.TIMEOUT,
                "no ServerSync within ${HANDSHAKE_DEADLINE_MS}ms",
            )
            if (_state.compareAndSet(ConnectionState.Handshaking, timedOut)) {
                pingJob?.cancel()
                channel.close()
            }
        }
    }

    fun onFrame(frame: TcpFrame) {
        when (TcpMessageType.from(frame.type)) {
            TcpMessageType.ServerSync -> {
                val sync = MumbleProtos.ServerSync.parseFrom(frame.payload)
                // compareAndSet so a deadline that fired moments earlier cannot be overwritten,
                // and a duplicate ServerSync cannot start a second ping ticker.
                if (_state.compareAndSet(
                        ConnectionState.Handshaking,
                        ConnectionState.Synchronized(sync.session),
                    )
                ) {
                    deadlineJob?.cancel()
                    startPings()
                    // Over-cap packets are dropped silently, so the symptom is otherwise
                    // undiagnosable one-way audio. ACCOUNTED_BITRATE derives from the encoder's
                    // own rate so the two cannot drift.
                    if (sync.maxBandwidth in 1 until ACCOUNTED_BITRATE) {
                        Log.w(TAG, "server max_bandwidth=${sync.maxBandwidth} is below our fixed " +
                            "$ACCOUNTED_BITRATE — the server will silently drop audio packets " +
                            "over the cap")
                    }
                }
            }
            TcpMessageType.Ping -> {
                // The reply carries the stamp we sent, so it dates itself however many pings are
                // in flight. The guards replace matching: a server-initiated Ping has no timestamp,
                // and an unset uint64 reads as 0.
                val sent = MumbleProtos.Ping.parseFrom(frame.payload).timestamp
                val now = bootClock.markNow()
                val roundTrip = now - (pingOrigin + sent.nanoseconds)
                if (sent > 0 && roundTrip >= Duration.ZERO && roundTrip < PING_REPLY_MAX_AGE) {
                    _roundTripTime.value = roundTrip
                    _lastServerReplyAt.value = now
                }
            }
            TcpMessageType.Reject -> {
                val reject = MumbleProtos.Reject.parseFrom(frame.payload)
                fail(FailReason.AUTH_REJECT, reject.reason)
            }
            TcpMessageType.CryptSetup -> {
                // Three messages share the type, told apart by which fields are present, and
                // the lengths are checked here rather than left to CryptState's own checks: those
                // throw, and a throw out of onFrame ends the session — chat and channels included
                // — over a cipher voice can do without. Upstream logs and carries on too.
                val setup = MumbleProtos.CryptSetup.parseFrom(frame.payload)
                when {
                    // The key exchange.
                    setup.hasKey() && setup.hasClientNonce() && setup.hasServerNonce() -> {
                        if (setup.key.isBlock() && setup.clientNonce.isBlock() &&
                            setup.serverNonce.isBlock()
                        ) {
                            crypt.setKeys(
                                setup.key.toByteArray(),
                                setup.clientNonce.toByteArray(),
                                setup.serverNonce.toByteArray(),
                            )
                            udpPing?.invoke()
                        } else {
                            Log.w(TAG, "ignoring CryptSetup with malformed key material")
                        }
                    }
                    // The answer to requestCryptResync: where the server's send counter really is.
                    setup.hasServerNonce() -> {
                        if (setup.serverNonce.isBlock()) {
                            crypt.setDecryptNonce(setup.serverNonce.toByteArray())
                        } else {
                            Log.w(TAG, "ignoring CryptSetup with a malformed server_nonce")
                        }
                    }
                    // Empty: the server cannot decrypt us and asks where our send counter is.
                    else -> channel.send(
                        TcpMessageType.CryptSetup,
                        MumbleProtos.CryptSetup.newBuilder()
                            .setClientNonce(ByteString.copyFrom(crypt.encryptNonce()))
                            .build(),
                    )
                }
            }
            TcpMessageType.Version -> {
                val version = ServerVersion.from(MumbleProtos.Version.parseFrom(frame.payload))
                // Publish before any rejection so the UI can name the version it refused.
                _serverVersion.value = version
                // Protobuf UDP audio is a 1.5 format: a 1.4 server parses our 0x00-prefixed
                // payload as malformed legacy CELT-alpha and silently drops every frame. Voice
                // is the point of connecting, so refuse rather than connect without it.
                if (version.major < 1 || (version.major == 1 && version.minor < 5)) {
                    fail(FailReason.VERSION_TOO_OLD, "server $version — need >= 1.5")
                }
            }

            TcpMessageType.ChannelState ->
                _channelTree.value = ChannelTreeReducers.applyChannelState(
                    _channelTree.value, MumbleProtos.ChannelState.parseFrom(frame.payload))
            TcpMessageType.ChannelRemove ->
                _channelTree.value = ChannelTreeReducers.applyChannelRemove(
                    _channelTree.value, MumbleProtos.ChannelRemove.parseFrom(frame.payload))
            TcpMessageType.UserState ->
                _channelTree.value = ChannelTreeReducers.applyUserState(
                    _channelTree.value, MumbleProtos.UserState.parseFrom(frame.payload))
            TcpMessageType.UserRemove ->
                _channelTree.value = ChannelTreeReducers.applyUserRemove(
                    _channelTree.value, MumbleProtos.UserRemove.parseFrom(frame.payload))

            TcpMessageType.TextMessage -> {
                val tm = MumbleProtos.TextMessage.parseFrom(frame.payload)
                // A server/system broadcast carries no actor; null keeps it from rendering as "user 0".
                val actor = if (tm.hasActor()) tm.actor else null
                // Resolve the sender's name now, not at render — the log is a transcript.
                val senderName = actor?.let { _channelTree.value.users[it]?.name }
                appendMessage(ChatMessage.Remote(actor, senderName, tm.message, clock()))
            }

            TcpMessageType.PermissionDenied -> {
                // No id ties a rejection to the message that caused it, so — like the reference
                // client — surface it as its own notice rather than rolling back the echo.
                val pd = MumbleProtos.PermissionDenied.parseFrom(frame.payload)
                appendMessage(ChatMessage.Denied(denyReason(pd), clock()))
            }

            // Raw UDP packet bytes: Mumble.proto's UDPTunnel message is dead code, never sent.
            TcpMessageType.UDPTunnel -> audioListener?.onTunneledAudio(frame.payload)

            TcpMessageType.UserStats ->
                _userStats.value = UserStats.from(MumbleProtos.UserStats.parseFrom(frame.payload))

            // Deliberately ignored — see the design's non-goals.
            TcpMessageType.CodecVersion,
            TcpMessageType.ServerConfig,
            TcpMessageType.PermissionQuery,
            -> Unit

            else -> Unit                       // unknown or unmodelled id: ignore, never fail
        }
    }

    /**
     * Send [body] to my current channel and optimistically echo it — the server strips the sender
     * from a TextMessage's recipients, so it never comes back. [body] is sent verbatim as the message
     * payload; formatting concerns (HTML-escaping user input, trimming) belong to the caller, not this
     * layer. Returns whether it was enqueued; a no-op until Synchronized with a known channel. Safe to
     * call off the reader thread: channel.send enqueues (non-blocking) and _messages.update is atomic.
     */
    fun sendText(body: String): Boolean {
        val session = (_state.value as? ConnectionState.Synchronized)?.sessionId ?: return false
        val channelId = _channelTree.value.users[session]?.channelId ?: return false
        val ok = channel.send(
            TcpMessageType.TextMessage,
            MumbleProtos.TextMessage.newBuilder().addChannelId(channelId).setMessage(body).build(),
        )
        val senderName = _channelTree.value.users[session]?.name
        if (ok) appendMessage(ChatMessage.Remote(session, senderName, body, clock()))
        return ok
    }

    /** The wire carries a variance; jitter is its deviation. Zero is "no reading", as with ping. */

    /**
     * Ask the server for [session]'s stats. The answer arrives on [userStats], asynchronously and
     * possibly not at all — a server may refuse. Returns whether the ask was enqueued.
     *
     * `stats_only` keeps the reply to the mutable numbers. Without it the server also sends the
     * user's certificate chain and IP address, which murmur gates on admin rights and which
     * nothing here wants.
     */
    fun requestUserStats(session: Int): Boolean {
        if (_state.value !is ConnectionState.Synchronized) return false
        return channel.send(
            TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder().setSession(session).setStatsOnly(true).build(),
        )
    }

    /**
     * Deafen or undeafen. Returns whether it was enqueued; a no-op until Synchronized.
     * [DeafenState.deafen] owns the coupling to `self_mute`.
     *
     * A repeat ask — a double-tap, before the server has answered — re-sends [sent] verbatim.
     * Advancing again would run [DeafenState.deafen] against state it just moved; returning early
     * would deaden the button, since murmur silently rate-limits UserState aimed at the sender and
     * every later tap would then match [sent] too.
     *
     * No optimistic echo, unlike [sendText]: the server broadcasts UserState back, so the reducer
     * shows what it believes. Safe off the reader thread — channel.send only enqueues.
     */
    fun setSelfDeaf(on: Boolean): Boolean =
        sendSelfState(if (on != sent.selfDeaf) sent.deafen(on) else sent)

    /**
     * Mute or unmute. Same shape and repeat guard as [setSelfDeaf]. Unmuting while deafened may
     * take two taps: the first undeafens and keeps a mute the user set themselves
     * ([DeafenState.mute]), and the button still reads muted after it because it is.
     */
    fun setSelfMute(on: Boolean): Boolean =
        sendSelfState(if (on != sent.selfMute) sent.mute(on) else sent)

    /** Ship [next] as our own UserState. Both fields ride together: murmur forces mute on with
     *  deaf (`Server::msgUserState`) and never takes it back off, so a frame carrying one alone
     *  would let the server's view and [sent] drift apart. */
    private fun sendSelfState(next: DeafenState): Boolean {
        val session = (_state.value as? ConnectionState.Synchronized)?.sessionId ?: return false
        val ok = channel.send(
            TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder()
                .setSession(session)
                .setSelfDeaf(next.selfDeaf)
                .setSelfMute(next.selfMute)
                .build(),
        )
        // Advanced only on a successful enqueue: a refused send must not leave this claiming
        // something the wire never carried, or the retry advances from a state that never existed.
        if (ok) sent = next
        return ok
    }

    /** Translate a [MumbleProtos.PermissionDenied] into the domain reason; wording is the UI's job. */
    private fun denyReason(pd: MumbleProtos.PermissionDenied): DenyReason = when (pd.type) {
        MumbleProtos.PermissionDenied.DenyType.TextTooLong -> DenyReason.TooLong
        MumbleProtos.PermissionDenied.DenyType.Permission ->
            DenyReason.NoPostPermission(if (pd.hasChannelId()) _channelTree.value.channels[pd.channelId]?.name else null)
        else -> DenyReason.Other(pd.reason.ifBlank { null })
    }

    /** Tags a ping log line with the session it belongs to — otherwise unattributable in logcat. */
    private val sessionId: Int?
        get() = (_state.value as? ConnectionState.Synchronized)?.sessionId

    /**
     * Ask the server for its current send counter: the recovery for a decrypt direction that has
     * drifted too far to rebuild. The UDP transport decides when, and throttles it; the reply
     * arrives as a CryptSetup carrying only `server_nonce`.
     */
    fun requestCryptResync() =
        channel.send(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.getDefaultInstance())

    private fun ByteString.isBlock() = size() == CryptState.NONCE_LEN

    /** Mumble servers disconnect clients that stop pinging, so this keeps the session alive. */
    private fun startPings() {
        pingJob = scope.launch {
            var lastTick = bootClock.markNow()
            var degraded = false
            _lastServerReplyAt.value = lastTick
            while (true) {
                delay(pingIntervalMs)
                val now = bootClock.markNow()
                val sinceLast = now - lastTick
                if (sinceLast > PING_GAP_WARN) {
                    Log.w(TAG, "no ping sent for ${sinceLast.inWholeMilliseconds}ms session=$sessionId")
                }
                lastTick = now
                // On the edge, not the level: the log is the trail a past outage leaves behind.
                val pingAge = _lastServerReplyAt.value?.let { now - it } ?: Duration.ZERO
                if (pingAge >= DEGRADED_PING_AGE && !degraded) {
                    degraded = true
                    Log.w(TAG, "no ping reply for ${pingAge.inWholeMilliseconds}ms session=$sessionId")
                } else if (pingAge < DEGRADED_PING_AGE && degraded) {
                    degraded = false
                    Log.i(TAG, "ping replies resumed session=$sessionId")
                }
                // Not fatal, unlike the handshake sends: backpressure, or a death the reader
                // already reports. Either way the ping goes unanswered and ages.
                //
                // The crypt counters ride along because the server folds them into the
                // UserStats other clients read. Nothing here reads them back.
                val stats = crypt.stats()
                channel.send(
                    TcpMessageType.Ping,
                    MumbleProtos.Ping.newBuilder()
                        .setTimestamp((now - pingOrigin).inWholeNanoseconds)
                        .setGood(stats.good)
                        .setLate(stats.late)
                        .setLost(stats.lost)
                        .setResync(stats.resync)
                        .build(),
                )
                udpPing?.invoke()
            }
        }
    }

    /**
     * Ends the session whether it interrupts the handshake or drops an established one. An existing
     * [ConnectionState.Failed] is preserved so a specific reason beats the generic close that
     * follows it. The channel is already closed, so unlike [fail] this does not close it again.
     */
    fun onClosed(cause: Throwable?) {
        deadlineJob?.cancel()
        pingJob?.cancel()
        // update() is an atomic compare-and-set retry against the one concurrent writer — the
        // deadline coroutine. Preserve an existing terminal reason; otherwise end as Failed(IO).
        val ended = ConnectionState.Failed(FailReason.IO, cause?.message, cause = cause)
        _state.update { current -> if (current is ConnectionState.Failed) current else ended }
    }

    /**
     * First failure wins. The deadline coroutine mutates the same state outside the transport's
     * listener lock, so a plain check-then-write loses the race it exists to settle.
     */
    private fun fail(reason: FailReason, detail: String?, cause: Throwable? = null) {
        val failed = ConnectionState.Failed(reason, detail, cause = cause)
        while (true) {
            val current = _state.value
            if (current is ConnectionState.Failed || current is ConnectionState.Synchronized) return
            if (_state.compareAndSet(current, failed)) break
        }
        deadlineJob?.cancel()
        pingJob?.cancel()
        channel.close()
    }

    companion object {
        private const val TAG = "SessionStateMachine"
        const val CLIENT_MAJOR = 1
        const val CLIENT_MINOR = 5
        const val CLIENT_PATCH = 0
        const val HANDSHAKE_DEADLINE_MS = 15_000L
        const val PING_INTERVAL_MS = 5_000L
        const val MAX_MESSAGES = 1000
        /** Three intervals: two replies must go missing, and still inside Murmur's 30 s reap. */
        val DEGRADED_PING_AGE = (PING_INTERVAL_MS * 3).milliseconds

        /** Real-time gap between sends worth logging: we may already have been reaped, doze or not. */
        val PING_GAP_WARN = (PING_INTERVAL_MS * 3).milliseconds

        /** Round trip past which a reply is not plausibly ours — stands in for matching. */
        val PING_REPLY_MAX_AGE = (PING_INTERVAL_MS * 6).milliseconds
    }
}
