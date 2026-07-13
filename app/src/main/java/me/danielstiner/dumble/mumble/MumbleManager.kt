package me.danielstiner.dumble.mumble

import android.content.Context
import android.util.Log
import me.danielstiner.dumble.mumble.model.MumbleModel
import me.danielstiner.dumble.mumble.net.*
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.*
import me.danielstiner.dumble.mumble.util.MumbleLog
import me.danielstiner.dumble.mumble.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

data class MumbleServerConfig(
    val host: String,
    val port: Int = 64738,
    val username: String,
    val password: String? = null,
    val forceTcp: Boolean = false,
    val loopbackVoice: Boolean = true,
)

class SharedPrefsPinStore(context: Context) : PinStore {
    private val prefs = context.getSharedPreferences("mumble_tofu_pins", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, fingerprint: String) { prefs.edit().putString(key, fingerprint).apply() }
}

/**
 * Facade over the Mumble protocol stack: owns a per-connection [ActiveSession] that wires
 * CryptState + TCP/UDP transports + [TransportSelector] + synthetic voice loopback + a 5s ping
 * loop, and exposes [state]/[model]/[netStats]/[loopbackStats] as StateFlows for the UI and the
 * Telecom bridge ([me.danielstiner.dumble.telecom.CallManager]) to observe.
 */
object MumbleManager {
    private const val TAG = "MumbleManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val model = MumbleModel()
    private val _netStats = MutableStateFlow(NetStats())
    val netStats: StateFlow<NetStats> = _netStats.asStateFlow()
    private val _loopbackStats = MutableStateFlow(LoopbackStats())
    val loopbackStats: StateFlow<LoopbackStats> = _loopbackStats.asStateFlow()

    private var pinStore: PinStore = InMemoryPinStore()
    private var active: ActiveSession? = null

    init {
        // Long-lived watcher on the app-lifetime `scope` (outlives any single session):
        // whenever a session reaches Failed (e.g. auth reject, TLS/TOFU pin mismatch, IO
        // error surfaced by the state machine), self-heal by tearing the session down so
        // `active` is reset to null and a fresh connect() isn't a silent no-op. shutdown()
        // sets _state = Disconnected *after* tearing down, so this doesn't re-fire or recurse.
        scope.launch {
            state.collect { s -> if (s is ConnectionState.Failed) disconnect() }
        }
    }

    fun init(context: Context) {
        pinStore = SharedPrefsPinStore(context.applicationContext)
        MumbleLog.sink = { tag, msg, t -> if (t != null) Log.w(tag, msg, t) else Log.d(tag, msg) }
    }

    @Synchronized fun connect(config: MumbleServerConfig) {
        if (active != null) { Log.w(TAG, "connect ignored — session active"); return }
        model.reset()
        active = ActiveSession(config).also { it.start() }
    }

    @Synchronized fun disconnect() {
        active?.shutdown()
        active = null
    }

    private fun urgentAudioThread() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        catch (t: Throwable) { Log.w(TAG, "setThreadPriority failed", t) }
    }

    private class ActiveSession(private val config: MumbleServerConfig) {
        // Child of the manager-lifetime `scope`, scoped to this session only. shutdown()
        // cancels this (not the individual jobs list) so every coroutine launched under it —
        // including pingLoop, which is launched later from inside the connect coroutine — is
        // torn down atomically, with no race against late-arriving `jobs +=` appends.
        private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        private val crypt = CryptState()
        private val tcp = MumbleTcpTransport(pinStore)
        private val selector = TransportSelector(config.forceTcp)
        private val synthetic = SyntheticVoiceSource()
        @Volatile private var udp: MumbleUdpTransport? = null
        private val pingBuf = ByteArray(256)

        private val voice = VoiceTransport(
            engine = synthetic,
            modeProvider = { selector.mode },
            udpSend = { buf, n -> udp?.send(buf, n) ?: false },
            tunnelSend = { buf, n -> tcp.sendRaw(TcpMessageType.UDPTunnel, buf, n) },
            onUdpPing = { ts, arrival -> selector.onUdpPong((arrival - ts) / 1e6) },
            threadSetup = ::urgentAudioThread,
        )

        private val events = object : SessionStateMachine.Events {
            override fun onCryptReady() {
                val u = MumbleUdpTransport(crypt, object : MumbleUdpTransport.Listener {
                    override fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) =
                        voice.onPlaintext(buf, len, arrivalNanos)
                    override fun onUdpError(e: Exception) { MumbleLog.w(TAG, "udp error — tunnel continues", e) }
                    override fun requestCryptResync() { sm.requestCryptResync() }
                }, threadSetup = ::urgentAudioThread)
                u.connect(config.host, config.port)
                udp = u
                if (config.loopbackVoice) voice.start()
            }
            override fun onTcpRtt(rttMs: Double) = selector.onTcpRtt(rttMs)
            override fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long) =
                voice.onPlaintext(plaintext, len, arrivalNanos)
        }

        private val sm: SessionStateMachine = SessionStateMachine(tcp, model, crypt, events)

        fun start() {
            sessionScope.launch { sm.state.collect { _state.value = it } }
            sessionScope.launch { selector.stats.collect { _netStats.value = it } }
            sessionScope.launch { synthetic.stats.collect { _loopbackStats.value = it } }
            sessionScope.launch {
                _state.value = ConnectionState.Connecting
                try {
                    tcp.connect(config.host, config.port, object : MumbleTcpTransport.Listener {
                        override fun onFrame(frame: TcpFrame) = sm.onFrame(frame)
                        override fun onClosed(cause: Throwable?) {
                            if (cause != null) sm.fail(FailReason.IO, cause.message, cause)
                        }
                    })
                } catch (t: Throwable) {
                    _state.value = ConnectionState.Failed(classify(t), t.message, t)
                    return@launch
                }
                sm.start(config.username, config.password)
                sessionScope.launch { pingLoop() }
            }
        }

        private suspend fun pingLoop() {
            while (currentCoroutineContext().isActive) {
                delay(SessionStateMachine.PING_INTERVAL_MS)
                sm.sendPing()
                udp?.let { u ->
                    val ping = MumbleUdpProtos.Ping.newBuilder().setTimestamp(System.nanoTime()).build()
                    synchronized(pingBuf) {
                        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_PING, ping, pingBuf)
                        u.send(pingBuf, n)
                    }
                }
                selector.evaluate(crypt.stats(), sendingVoice = config.loopbackVoice && crypt.isValid())
            }
        }

        private fun classify(t: Throwable): FailReason = when {
            t is UnknownHostException -> FailReason.DNS
            t is SocketTimeoutException -> FailReason.TIMEOUT
            t is CertificateException || t.cause is CertificateException ->
                if ((t.message ?: t.cause?.message ?: "").contains("pin mismatch")) FailReason.PIN_MISMATCH else FailReason.TLS
            t is SSLException -> FailReason.TLS
            else -> FailReason.IO
        }

        fun shutdown() {
            voice.stop()
            udp?.close()
            sm.disconnectLocal()
            sessionScope.cancel()
            _state.value = ConnectionState.Disconnected
            _netStats.value = NetStats()
            _loopbackStats.value = LoopbackStats()
        }
    }
}
