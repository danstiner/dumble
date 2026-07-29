package me.danielstiner.dumble.mumble.protocol

import android.util.Log
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
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.ChannelTreeReducers
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.chat.DenyReason
import me.danielstiner.dumble.mumble.proto.MumbleProtos

/**
 * Drives the control-channel handshake to [ConnectionState.Synchronized].
 *
 * Threading: the transport delivers [onFrame] and [onClosed] from its single reader coroutine, one
 * at a time and never nested, so fields touched only by them need no further synchronization. The
 * ping ticker runs on its own coroutine, so state it shares with the frame handler is volatile or in
 * a [MutableStateFlow]. `start()` runs on the caller's thread, a third context, so the job handles
 * it writes are volatile.
 */
class SessionStateMachine(
    private val channel: ControlChannel,
    private val username: String,
    private val password: String?,
    private val scope: CoroutineScope,
    private val clockNanos: () -> Long = System::nanoTime,
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _roundTripMillis = MutableStateFlow<Double?>(null)
    val roundTripMillis: StateFlow<Double?> = _roundTripMillis.asStateFlow()

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

    /** CryptSetup key material, stored for the voice task. Unused here. */
    @Volatile var cryptKey: ByteArray? = null
        private set

    /**
     * Tunneled voice payloads. A callback rather than a StateFlow: this is a per-frame hot path
     * and StateFlow conflates, so a dropped emission would be dropped audio. Invoked on the
     * transport's single reader coroutine, the same context as every other frame handler.
     */
    fun interface AudioListener {
        fun onTunneledAudio(payload: ByteArray, arrivalNanos: Long)
    }

    @Volatile var audioListener: AudioListener? = null

    @Volatile private var deadlineJob: Job? = null
    @Volatile private var pingJob: Job? = null

    /** Written by the ping ticker, read by the frame handler on another coroutine. */
    @Volatile private var lastPingSentNanos = 0L

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
                    // The server drops over-cap packets silently, with no error to the client, so
                    // without this the symptom is undiagnosable one-way audio. Adaptation is out
                    // of scope. ACCOUNTED_BITRATE is derived from the encoder's own rate rather
                    // than written down again here, so the two cannot drift apart — a drift would
                    // mis-calibrate the one warning that explains the symptom.
                    if (sync.maxBandwidth in 1 until ACCOUNTED_BITRATE) {
                        Log.w(TAG, "server max_bandwidth=${sync.maxBandwidth} is below our fixed " +
                            "$ACCOUNTED_BITRATE — the server will silently drop audio packets " +
                            "over the cap")
                    }
                }
            }
            TcpMessageType.Ping -> {
                // The server echoes our timestamp; anything else is a server-initiated ping.
                // Ignore echoes before we have ever sent one, where 0 would match an unset field.
                val echo = MumbleProtos.Ping.parseFrom(frame.payload)
                if (lastPingSentNanos != 0L && echo.timestamp == lastPingSentNanos) {
                    _roundTripMillis.value = (clockNanos() - lastPingSentNanos) / 1_000_000.0
                }
            }
            TcpMessageType.Reject -> {
                val reject = MumbleProtos.Reject.parseFrom(frame.payload)
                fail(FailReason.AUTH_REJECT, reject.reason)
            }
            TcpMessageType.CryptSetup -> {
                // Either an initial key exchange or a mid-session resync, and a resync carries only
                // nonces — its absent key must not overwrite the one already negotiated.
                val setup = MumbleProtos.CryptSetup.parseFrom(frame.payload)
                if (setup.hasKey()) cryptKey = setup.key.toByteArray()
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
                // The server never acks a delivered TextMessage and gives no id to tie a rejection to
                // the message that caused it, so — like the reference client — surface it as its own
                // notice rather than trying to roll back the optimistic echo. Structured, not worded:
                // the UI turns the reason into (someday localized) text.
                val pd = MumbleProtos.PermissionDenied.parseFrom(frame.payload)
                appendMessage(ChatMessage.Denied(denyReason(pd), clock()))
            }

            // Raw UDP packet bytes, not a protobuf UDPTunnel message — the message of that name
            // in Mumble.proto is dead code and is never serialized by either end.
            TcpMessageType.UDPTunnel -> audioListener?.onTunneledAudio(frame.payload, clockNanos())

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

    /** Translate a [MumbleProtos.PermissionDenied] into the domain reason; wording is the UI's job. */
    private fun denyReason(pd: MumbleProtos.PermissionDenied): DenyReason = when (pd.type) {
        MumbleProtos.PermissionDenied.DenyType.TextTooLong -> DenyReason.TooLong
        MumbleProtos.PermissionDenied.DenyType.Permission ->
            DenyReason.NoPostPermission(if (pd.hasChannelId()) _channelTree.value.channels[pd.channelId]?.name else null)
        else -> DenyReason.Other(pd.reason.ifBlank { null })
    }

    /** Mumble servers disconnect clients that stop pinging, so this keeps the session alive. */
    private fun startPings() {
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                lastPingSentNanos = clockNanos()
                // Not fatal, unlike the handshake sends. A false here means the queue is full or
                // the transport is already closed — backpressure, or a death the reader is already
                // reporting. Neither is evidence this session is dead. Liveness belongs to the echo
                // that does not come back, which nothing watches yet.
                channel.send(
                    TcpMessageType.Ping,
                    MumbleProtos.Ping.newBuilder().setTimestamp(lastPingSentNanos).build(),
                )
            }
        }
    }

    /**
     * The transport reports the channel has closed. This ends the session whether it interrupts the
     * handshake or drops an established one — [ConnectionState.Synchronized] is not terminal against a
     * real disconnect. An existing [ConnectionState.Failed] is preserved so a specific reason (a
     * during-handshake reject, or the deadline's timeout) wins over the generic close that follows it.
     * The channel is already closed here, so unlike [fail] this does not close it again.
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
     * First failure wins. The deadline coroutine runs outside the transport's listener lock and
     * mutates the same state, so a plain check-then-write loses the race it is meant to settle —
     * whichever failure actually ended the session must be the one reported.
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
    }
}
