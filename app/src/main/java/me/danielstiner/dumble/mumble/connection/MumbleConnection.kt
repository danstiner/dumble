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
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.MumbleTcpTransport
import me.danielstiner.dumble.mumble.net.PinMismatchException
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UntrustedCertificateException
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.voice.AndroidAudioOut
import me.danielstiner.dumble.mumble.voice.AudioOut
import me.danielstiner.dumble.mumble.voice.OpusCodec
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
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
    private val opusCodec: OpusCodec,
    private val newAudioOut: () -> AudioOut,
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) : Connection {
    @Inject constructor(pinStore: PinStore, opusCodec: OpusCodec) :
        this(pinStore, opusCodec, { AndroidAudioOut() }, { MumbleTcpTransport(it) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    private val _serverVersion = MutableStateFlow<ServerVersion?>(null)
    override val serverVersion: StateFlow<ServerVersion?> = _serverVersion.asStateFlow()
    private val _roundTripMillis = MutableStateFlow<Double?>(null)
    override val roundTripMillis: StateFlow<Double?> = _roundTripMillis.asStateFlow()
    private val _channelTree = MutableStateFlow(ChannelTree())
    override val channelTree: StateFlow<ChannelTree> = _channelTree.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    override val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()

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
        val sm: SessionStateMachine,
        val childScope: CoroutineScope,
        val receiver: VoiceReceiver,
        @Volatile var presented: String? = null,
    )

    // Every status write goes through the lock so a bump + terminal write is atomic against stale writers.
    private fun publishStatus(gen: Int, s: ConnectionStatus) = synchronized(lock) { if (gen == attempt) _status.value = s }
    private fun publishVersion(gen: Int, v: ServerVersion?) = synchronized(lock) { if (gen == attempt) _serverVersion.value = v }
    private fun publishRtt(gen: Int, r: Double?) = synchronized(lock) { if (gen == attempt) _roundTripMillis.value = r }
    private fun publishChannelTree(gen: Int, t: ChannelTree) = synchronized(lock) { if (gen == attempt) _channelTree.value = t }
    private fun publishMessages(gen: Int, m: List<ChatMessage>) = synchronized(lock) { if (gen == attempt) _messages.value = m }
    private fun publishSpeaking(gen: Int, s: Set<Int>) = synchronized(lock) { if (gen == attempt) _speakingSessions.value = s }

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        val gen: Int
        val prior: Attempt?
        synchronized(lock) {
            prior = current; current = null; attempt += 1; gen = attempt
            _status.value = ConnectionStatus.Connecting
            _serverVersion.value = null; _roundTripMillis.value = null
            _channelTree.value = ChannelTree()
            _messages.value = emptyList()
            _speakingSessions.value = emptySet()
        }
        prior?.let { teardown(it) }

        scope.launch {
            val pin = pinStore.get(endpoint.pinKey)
            Log.i(TAG, "connect gen=$gen endpoint=${endpoint.pinKey} user=$username storedPin=${pin != null}")
            val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = newTransport(pin)
            val sm = SessionStateMachine(transport, username, password, childScope)
            val receiver = VoiceReceiver(opusCodec, newAudioOut)
            val att = Attempt(gen, endpoint, username, password, transport, sm, childScope, receiver)
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

            sm.audioListener = SessionStateMachine.AudioListener { payload, _ ->
                receiver.onTunneledAudio(payload)
            }
            sm.start()
            childScope.launch {
                sm.state.collect { st ->
                    mapState(st)?.let { s -> publishStatus(gen, s) }
                    // A session can die without anyone calling disconnect(): an auth reject, a
                    // server below 1.5, a dropped socket. Nothing else reaches teardown on that
                    // path — connect() only tears down a *prior* attempt — so the receiver would
                    // keep its playback thread waking at 100 Hz on THREAD_PRIORITY_URGENT_AUDIO
                    // with an open AudioTrack for as long as the user sits on the error screen.
                    // Handed to `scope` because teardown cancels the childScope this collector
                    // runs in. The error status stays published: retire() does not touch _status.
                    if (st is ConnectionState.Failed) scope.launch { retire(att) }
                }
            }
            childScope.launch { sm.serverVersion.collect { publishVersion(gen, it) } }
            childScope.launch { sm.roundTripMillis.collect { publishRtt(gen, it) } }
            childScope.launch { sm.channelTree.collect { publishChannelTree(gen, it) } }
            childScope.launch { sm.messages.collect { publishMessages(gen, it) } }
            // Same gen==attempt guard as every other mutation here: without it, a disconnect()/
            // connect() racing this exact point supersedes `att` — teardown() no-ops on a
            // not-yet-started receiver — and this call starts it anyway, so nothing will ever
            // stop() it again and the playback thread runs forever holding an open AudioOut.
            synchronized(lock) { if (gen == attempt) receiver.start() }
            childScope.launch { receiver.speakingSessions.collect { publishSpeaking(gen, it) } }
        }
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val att = current ?: return
        val presented = att.presented ?: return
        scope.launch {
            pinStore.put(att.endpoint.pinKey, presented)
            connect(att.endpoint, att.username, att.password)
        }
    }

    override fun cancelTrust() = disconnect()

    override fun disconnect() {
        val prior: Attempt?
        synchronized(lock) {
            prior = current; current = null; attempt += 1
            _status.value = ConnectionStatus.Idle
            _serverVersion.value = null; _roundTripMillis.value = null
            _channelTree.value = ChannelTree()
            _messages.value = emptyList()
            _speakingSessions.value = emptySet()
        }
        prior?.let { teardown(it) }
    }

    override fun sendText(text: String): Boolean = current?.sm?.sendText(text) ?: false

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
        if (live) teardown(att)
    }

    private fun teardown(att: Attempt) {
        // receiver.stop() joins the playback thread (up to 1s), and teardown() runs synchronously
        // on whatever thread calls disconnect()/connect() — including the main thread, since
        // ConnectViewModel.onDisconnect() calls disconnect() straight from a Button onClick. Hand
        // the join to `scope` so a wedged AudioTrack.write can't freeze the UI. transport.close()
        // and childScope.cancel() stay here: neither blocks.
        scope.launch { att.receiver.stop() }
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
