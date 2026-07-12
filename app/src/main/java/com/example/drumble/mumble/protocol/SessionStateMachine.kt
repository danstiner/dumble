package com.example.drumble.mumble.protocol

import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.util.MumbleLog
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Abstraction over MumbleTcpTransport so the state machine tests with a fake. */
interface ControlChannel {
    fun send(type: TcpMessageType, message: MessageLite): Boolean
    fun close()
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Handshaking : ConnectionState()
    data class Synchronized(val sessionId: Int) : ConnectionState()
    data class Failed(val reason: FailReason, val detail: String? = null, val cause: Throwable? = null) : ConnectionState()
}

enum class FailReason { DNS, TLS, PIN_MISMATCH, AUTH_REJECT, VERSION_TOO_OLD, TIMEOUT, IO }

object MumbleVersion {
    fun encodeV2(major: Int, minor: Int, patch: Int): Long =
        (major.toLong() shl 48) or (minor.toLong() shl 32) or (patch.toLong() shl 16)
    fun encodeV1(major: Int, minor: Int, patch: Int): Int =
        (major shl 16) or (minor shl 8) or patch
    fun majorOf(v2: Long): Int = (v2 ushr 48).toInt()
    fun minorOf(v2: Long): Int = ((v2 ushr 32) and 0xFFFF).toInt()
}

/**
 * Threading contract: [onFrame] is invoked single-threaded on the transport reader coroutine;
 * [sendPing] and [requestCryptResync] may be called from a separate ticker coroutine. State is
 * exposed via a thread-safe [MutableStateFlow]; [lastPingSentNanos] is `@Volatile` for
 * cross-coroutine visibility since it is written by [sendPing] on the ticker coroutine and read
 * by [handlePingEcho] on the reader coroutine.
 */
class SessionStateMachine(
    private val channel: ControlChannel,
    private val model: MumbleModel,
    private val crypt: CryptState,
    private val events: Events,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    companion object {
        private const val TAG = "SessionStateMachine"
        const val CLIENT_MAJOR = 1; const val CLIENT_MINOR = 5; const val CLIENT_PATCH = 0
        const val PING_INTERVAL_MS = 5_000L
    }

    interface Events {
        fun onCryptReady()
        fun onTcpRtt(rttMs: Double)
        fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long)
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile private var lastPingSentNanos = 0L

    fun start(username: String, password: String?) {
        _state.value = ConnectionState.Handshaking
        val version = MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setVersionV2(MumbleVersion.encodeV2(CLIENT_MAJOR, CLIENT_MINOR, CLIENT_PATCH))
            .setRelease("Drumble").setOs("Android").build()
        channel.send(TcpMessageType.Version, version)
        val auth = MumbleProtos.Authenticate.newBuilder()
            .setUsername(username).setOpus(true)
            .apply { password?.let { setPassword(it) } }.build()
        channel.send(TcpMessageType.Authenticate, auth)
    }

    fun onFrame(frame: TcpFrame) {
        when (TcpMessageType.from(frame.type)) {
            TcpMessageType.Version -> handleVersion(MumbleProtos.Version.parseFrom(frame.payload))
            TcpMessageType.Reject -> {
                val r = MumbleProtos.Reject.parseFrom(frame.payload)
                fail(FailReason.AUTH_REJECT, r.reason)
            }
            TcpMessageType.CryptSetup -> handleCryptSetup(MumbleProtos.CryptSetup.parseFrom(frame.payload))
            TcpMessageType.ServerSync -> {
                val sync = MumbleProtos.ServerSync.parseFrom(frame.payload)
                model.onServerSync(sync)
                _state.value = ConnectionState.Synchronized(sync.session)
            }
            TcpMessageType.ChannelState -> model.onChannelState(MumbleProtos.ChannelState.parseFrom(frame.payload))
            TcpMessageType.ChannelRemove -> model.onChannelRemove(MumbleProtos.ChannelRemove.parseFrom(frame.payload))
            TcpMessageType.UserState -> model.onUserState(MumbleProtos.UserState.parseFrom(frame.payload))
            TcpMessageType.UserRemove -> model.onUserRemove(MumbleProtos.UserRemove.parseFrom(frame.payload))
            TcpMessageType.Ping -> handlePingEcho(MumbleProtos.Ping.parseFrom(frame.payload))
            TcpMessageType.UDPTunnel -> events.onTunneledVoice(frame.payload, frame.payload.size, clockNanos())
            else -> MumbleLog.d(TAG, "ignoring message type ${frame.type}")
        }
    }

    private fun handleVersion(v: MumbleProtos.Version) {
        val v2 = if (v.hasVersionV2()) v.versionV2 else {
            val v1 = v.versionV1
            MumbleVersion.encodeV2((v1 shr 16) and 0xFFFF, (v1 shr 8) and 0xFF, v1 and 0xFF)
        }
        val major = MumbleVersion.majorOf(v2); val minor = MumbleVersion.minorOf(v2)
        if (major < 1 || (major == 1 && minor < 5)) {
            fail(FailReason.VERSION_TOO_OLD, "server $major.$minor — need >= 1.5 (new UDP protocol)")
        }
    }

    private fun handleCryptSetup(cs: MumbleProtos.CryptSetup) {
        when {
            cs.hasKey() && cs.hasClientNonce() && cs.hasServerNonce() -> {
                crypt.setKeys(cs.key.toByteArray(), cs.clientNonce.toByteArray(), cs.serverNonce.toByteArray())
                events.onCryptReady()
            }
            cs.hasServerNonce() -> crypt.setDecryptIV(cs.serverNonce.toByteArray())
            else -> channel.send(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
                .setClientNonce(ByteString.copyFrom(crypt.encryptNonceCopy())).build())
        }
    }

    private fun handlePingEcho(p: MumbleProtos.Ping) {
        if (p.hasTimestamp() && p.timestamp == lastPingSentNanos && lastPingSentNanos != 0L) {
            events.onTcpRtt((clockNanos() - p.timestamp) / 1e6)
        }
        crypt.setRemoteStats(p.good, p.late, p.lost, p.resync)
    }

    fun sendPing() {
        lastPingSentNanos = clockNanos()
        val s = crypt.stats()
        channel.send(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder()
            .setTimestamp(lastPingSentNanos)
            .setGood(s.good).setLate(s.late).setLost(s.lost).setResync(s.resync).build())
    }

    fun requestCryptResync() {
        crypt.markResyncRequested()
        channel.send(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder().build())
    }

    fun fail(reason: FailReason, detail: String? = null, cause: Throwable? = null) {
        val failed = ConnectionState.Failed(reason, detail, cause)
        while (true) {
            val cur = _state.value
            if (cur is ConnectionState.Failed) return // first failure wins
            if (_state.compareAndSet(cur, failed)) break
        }
        channel.close()
    }

    // Intentionally overrides any prior state (including Failed) — an explicit local disconnect
    // should always win.
    fun disconnectLocal() {
        _state.value = ConnectionState.Disconnected
        channel.close()
    }
}
