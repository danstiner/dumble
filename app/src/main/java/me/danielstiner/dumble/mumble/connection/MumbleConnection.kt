package me.danielstiner.dumble.mumble.connection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
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
import me.danielstiner.dumble.mumble.voice.NoVoiceCall
import me.danielstiner.dumble.mumble.voice.OpusCodec
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import me.danielstiner.dumble.mumble.voice.VoiceSender
import me.danielstiner.dumble.mumble.voice.openNativeCapture
import me.danielstiner.dumble.telecom.TelecomCall
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
    // Defaulted so the tests that predate voice capture keep their trailing-lambda transport.
    private val newCapture: () -> VoiceSender.CaptureHandle? = { null },
    private val call: VoiceCall = NoVoiceCall,
    private val newTransport: (expectedPin: String?) -> MumbleControlTransport,
) : Connection {
    @Inject constructor(
        @ApplicationContext context: Context,
        pinStore: PinStore,
        opusCodec: OpusCodec,
    ) : this(
        pinStore, opusCodec, { AndroidAudioOut() }, { openNativeCapture() },
        TelecomCall(context),
        { MumbleTcpTransport(it) },
    )

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
        // Null until RECORD_AUDIO is granted and startCapture() succeeds; both are set together
        // under `lock`, so a non-null sender implies a handle to destroy.
        @Volatile var capture: VoiceSender.CaptureHandle? = null,
        @Volatile var sender: VoiceSender? = null,
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
        // Synchronously, on the caller's thread, and here rather than in startCapture(): this is the
        // Connect button, so the app is definitively foreground — which the call's `microphone`
        // foreground service requires — and tying the call to the connection rather than to the
        // microphone is what gives a user who denied RECORD_AUDIO a service at all, and with it
        // receive that survives backgrounding.
        call.start(gen, endpoint, username, onActive = { active -> onCallActive(active) }, onEnded = { disconnect() })

        scope.launch {
            val pin = pinStore.get(endpoint.address)
            Log.i(TAG, "connect gen=$gen endpoint=${endpoint.address} user=$username storedPin=${pin != null}")
            val childScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val transport = newTransport(pin)
            val sm = SessionStateMachine(transport, username, password, childScope)
            val receiver = VoiceReceiver(opusCodec, newAudioOut)
            val att = Attempt(gen, endpoint, username, password, transport, sm, childScope, receiver)
            val live = synchronized(lock) { if (gen == attempt) { current = att; true } else false }
            if (!live) { teardown(att); return@launch }   // superseded before publish

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
                    else -> Log.w(TAG, "connect failed for ${endpoint.address}", t)
                }
                publishStatus(gen, status)
                // The handshake never produced a session, so there is nothing for the platform to
                // hold a call open for. Without this a refused connection — or a trust prompt the
                // user leaves sitting — keeps a registered call alive with no audio behind it.
                call.end(gen, VoiceCall.Reason.SESSION_FAILED)
                return@launch
            }
            if (gen != attempt) { teardown(att); return@launch }   // superseded mid-handshake

            sm.audioListener = SessionStateMachine.AudioListener { payload, _ ->
                receiver.onTunneledAudio(payload)
            }
            sm.start()
            childScope.launch {
                sm.state.collect { st ->
                    mapState(st)?.let { publishStatus(gen, it) }
                    // Retire the attempt if the session fails. Sequenced after publishStatus:
                    // retire() calls teardown(), which cancels this collector's own scope.
                    if (st is ConnectionState.Failed) retire(att)
                }
            }
            childScope.launch { sm.serverVersion.collect { publishVersion(gen, it) } }
            childScope.launch { sm.roundTripMillis.collect { publishRtt(gen, it) } }
            childScope.launch { sm.channelTree.collect { publishChannelTree(gen, it) } }
            childScope.launch { sm.messages.collect { publishMessages(gen, it) } }
            childScope.launch { receiver.speakingSessions.collect { publishSpeaking(gen, it) } }
            // Start the receiver if we are still on the current attempt. Guarded to avoid racing
            // with teardown(), which could leave a dangling playback thread. Both halves matter:
            // retire() clears `current` without bumping `attempt`.
            synchronized(lock) { if (gen == attempt && current === att) receiver.start() }
        }
    }

    /** Accept the presented certificate (first contact or a mismatch) and reconnect on the pinned path. */
    override fun trustAndConnect() {
        val att = current ?: return
        val presented = att.presented ?: return
        scope.launch {
            pinStore.put(att.endpoint.address, presented)
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

    override fun startCapture() {
        val att = current ?: return
        if (att.sender != null) return
        scope.launch(Dispatchers.IO) { openCapture(att) }
    }

    /**
     * A hold tears the capture session down rather than merely closing the gate. Whatever the system
     * held us for is holding the input device — an incoming cellular call is the case that matters —
     * so an open stream would spend the interruption cycling Oboe's reopen backoff against a device
     * it cannot have. This is what replaces §4's "gate the backoff on focus state": the session is
     * cheap to rebuild, and rebuilding needs no new native state to stay coherent with a disconnect
     * arriving concurrently on the error-callback thread.
     *
     * Resolves the attempt at call time rather than closing over one, so a hold arriving after a
     * reconnect acts on the live session instead of a dead one.
     */
    private fun onCallActive(active: Boolean) {
        Log.i(TAG, "call ${if (active) "resumed" else "held"}")
        val att = current ?: return
        scope.launch(Dispatchers.IO) { if (active) openCapture(att) else closeCapture(att) }
    }

    /** Blocks on create(): callers are on [Dispatchers.IO]. No-op unless [att] is live and idle. */
    private fun openCapture(att: Attempt) {
        // Cheap pre-check so a focus callback arriving after teardown does not open a stream just
        // to destroy it; the post-create check below is what actually closes the race.
        if (synchronized(lock) { att.gen != attempt || current !== att || att.sender != null }) return
        // Left running on failure: the service is also what keeps *receive* alive in the
        // background, so tearing it down would trade a working direction for a broken one.
        val handle = newCapture() ?: return
        val sender = VoiceSender(handle, att.transport::sendRaw)
        val live = synchronized(lock) {
            if (att.gen == attempt && current === att && att.sender == null) {
                att.capture = handle; att.sender = sender; true
            } else false
        }
        // Superseded while the stream was opening — teardown already ran and saw no sender, so
        // releasing it is this coroutine's job.
        if (!live) { handle.stop(); handle.destroy(); return }
        sender.start()
    }

    /** Blocks joining the pump: callers are on [Dispatchers.IO]. */
    private fun closeCapture(att: Attempt) {
        val handle: VoiceSender.CaptureHandle?
        val sender: VoiceSender?
        synchronized(lock) {
            handle = att.capture; sender = att.sender
            att.capture = null; att.sender = null
        }
        // Order matters as in teardown: stop() joins the pump, and destroying an engine a live pump
        // is still polling is a use-after-free.
        sender?.stop()
        handle?.destroy()
    }

    override fun setTransmitting(on: Boolean) { current?.sender?.setTransmitting(on) }

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
        if (live) teardown(att, VoiceCall.Reason.SESSION_FAILED)
    }

    private fun teardown(att: Attempt, reason: VoiceCall.Reason = VoiceCall.Reason.USER) {
        // Launch blocking work asynchronously, teardown() can be called from the main thread.
        // IO because both block: stop() joins the playback thread for up to a second, and
        // SSLSocket.close can stall writing close-notify to a dead peer. Socket first, since that
        // is the part a reconnect waits on. Safe to be asynchronous: teardown is idempotent and
        // every publish is generation-guarded.
        scope.launch(Dispatchers.IO) {
            runCatching { att.transport.close() }
            // Before destroy(): stop() joins the pump, and destroying an engine a live pump is
            // still polling would be a use-after-free.
            att.sender?.stop()
            att.capture?.destroy()
            att.receiver.stop()
            // Last, and generation-guarded inside: a superseded attempt reaching here after a
            // reconnect has already started the next call must not end it.
            call.end(att.gen, reason)
        }
        // The collectors never finish on their own, and retire() does not bump `attempt`, so this
        // is the only thing that stops a retired attempt still publishing. Stays last because
        // retire() can reach here from inside childScope itself.
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
