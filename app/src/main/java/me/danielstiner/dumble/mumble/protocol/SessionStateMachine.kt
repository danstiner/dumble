package me.danielstiner.dumble.mumble.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _roundTripMillis = MutableStateFlow<Double?>(null)
    val roundTripMillis: StateFlow<Double?> = _roundTripMillis.asStateFlow()

    /** CryptSetup key material, stored for the voice task. Unused here. */
    @Volatile var cryptKey: ByteArray? = null
        private set

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

            // Deliberately ignored in this task — see the design's non-goals.
            TcpMessageType.UDPTunnel,          // raw voice bytes, not protobuf; no voice yet
            TcpMessageType.ChannelState,       // the roster task owns these
            TcpMessageType.UserState,
            TcpMessageType.ChannelRemove,
            TcpMessageType.UserRemove,
            TcpMessageType.TextMessage,        // the text chat task
            TcpMessageType.CodecVersion,
            TcpMessageType.ServerConfig,
            TcpMessageType.PermissionQuery,
            TcpMessageType.Version,
            -> Unit

            else -> Unit                       // unknown or unmodelled id: ignore, never fail
        }
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
        const val CLIENT_MAJOR = 1
        const val CLIENT_MINOR = 5
        const val CLIENT_PATCH = 0
        const val HANDSHAKE_DEADLINE_MS = 15_000L
        const val PING_INTERVAL_MS = 5_000L
    }
}
