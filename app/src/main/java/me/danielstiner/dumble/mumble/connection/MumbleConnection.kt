package me.danielstiner.dumble.mumble.connection

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the whole connection: the blocking TLS connect (which throws trust exceptions before the
 * protocol starts) and the [SessionStateMachine] that follows, unified into one [status] flow.
 *
 * Single active connection, guarded by [attempt]. A blocking handshake cannot be preempted by
 * teardown — the socket is not owned until it publishes — so an in-flight attempt can complete after
 * being superseded and would otherwise clobber a newer attempt's status. Every write is therefore
 * gated on the attempt token under [lock], and a superseded attempt's writes become no-ops.
 */
@Singleton
class MumbleConnection internal constructor(
    private val pinStore: PinStore,
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) {
    @Inject constructor(pinStore: PinStore) : this(pinStore, { MumbleTcpTransport(it) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    private val _serverVersion = MutableStateFlow<ServerVersion?>(null)
    val serverVersion: StateFlow<ServerVersion?> = _serverVersion.asStateFlow()
    private val _roundTripMillis = MutableStateFlow<Double?>(null)
    val roundTripMillis: StateFlow<Double?> = _roundTripMillis.asStateFlow()

    init {
        // One point that mirrors every status transition to logcat, whichever path set it.
        scope.launch { status.collect { Log.i(TAG, "status = $it") } }
    }

    private val lock = Any()
    private var attempt = 0
    @Volatile private var current: Attempt? = null

    private class Attempt(
        val gen: Int,
        val endpoint: MumbleEndpoint,
        val username: String,
        val password: String?,
        val transport: MumbleControlTransport,
        val childScope: CoroutineScope,
        @Volatile var presented: String? = null,
    )

    // Every status write goes through the lock so a bump + terminal write is atomic against stale writers.
    private fun publishStatus(gen: Int, s: ConnectionStatus) = synchronized(lock) { if (gen == attempt) _status.value = s }
    private fun publishVersion(gen: Int, v: ServerVersion?) = synchronized(lock) { if (gen == attempt) _serverVersion.value = v }
    private fun publishRtt(gen: Int, r: Double?) = synchronized(lock) { if (gen == attempt) _roundTripMillis.value = r }

    fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        val gen: Int
        val prior: Attempt?
        synchronized(lock) {
            prior = current; current = null; attempt += 1; gen = attempt
            _status.value = ConnectionStatus.Connecting
            _serverVersion.value = null; _roundTripMillis.value = null
        }
        prior?.let { teardown(it) }

        scope.launch {
            val pin = pinStore.get(endpoint.pinKey)
            Log.i(TAG, "connect gen=$gen endpoint=${endpoint.pinKey} user=$username storedPin=${pin != null}")
            val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = newTransport(pin)
            val sm = SessionStateMachine(transport, username, password, childScope)
            val att = Attempt(gen, endpoint, username, password, transport, childScope)
            val live = synchronized(lock) { if (gen == attempt) { current = att; true } else false }
            if (!live) { runCatching { transport.close() }; childScope.cancel(); return@launch }

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
                    else -> Log.w(TAG, "connect failed for ${endpoint.pinKey}", t)
                }
                publishStatus(gen, status)
                return@launch
            }
            if (gen != attempt) { teardown(att); return@launch }   // superseded mid-handshake

            sm.start()
            childScope.launch { sm.state.collect { mapState(it)?.let { s -> publishStatus(gen, s) } } }
            childScope.launch { sm.serverVersion.collect { publishVersion(gen, it) } }
            childScope.launch { sm.roundTripMillis.collect { publishRtt(gen, it) } }
        }
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    fun trustAndConnect() {
        val att = current ?: return
        val presented = att.presented ?: return
        scope.launch {
            pinStore.put(att.endpoint.pinKey, presented)
            connect(att.endpoint, att.username, att.password)
        }
    }

    fun cancelTrust() = disconnect()

    fun disconnect() {
        val prior: Attempt?
        synchronized(lock) {
            prior = current; current = null; attempt += 1
            _status.value = ConnectionStatus.Idle
            _serverVersion.value = null; _roundTripMillis.value = null
        }
        prior?.let { teardown(it) }
    }

    private fun teardown(att: Attempt) {
        runCatching { att.transport.close() }
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
    }
}
