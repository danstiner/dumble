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
    val loopbackVoice: Boolean = false,
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
    private const val DEFAULT_VAD_THRESHOLD = 0.4f
    private const val DEFAULT_HYSTERESIS_GAP = 0.15f
    private const val DEFAULT_AGC_TARGET_DBFS = -24f
    private const val DEFAULT_RNNOISE_ENABLED = false
    // AGC (makeup gain) follows RNNoise: with RNNoise defaulted off we trust the platform
    // VOICE_COMMUNICATION AGC/NS, so default AGC off too rather than double-processing.
    private const val DEFAULT_AGC_ENABLED = DEFAULT_RNNOISE_ENABLED
    private const val DEFAULT_PREROLL_MS = 40
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    val model = MumbleModel()
    private val _netStats = MutableStateFlow(NetStats())
    val netStats: StateFlow<NetStats> = _netStats.asStateFlow()
    private val _loopbackStats = MutableStateFlow(LoopbackStats())
    val loopbackStats: StateFlow<LoopbackStats> = _loopbackStats.asStateFlow()
    private val _voiceStats = MutableStateFlow(VoiceStats())
    val voiceStats: StateFlow<VoiceStats> = _voiceStats.asStateFlow()
    private val _audioDiagnostics = MutableStateFlow(AudioDiagnostics())
    /** Read-only transmit-path diagnostics (platform effects + stage levels), live during a call. */
    val audioDiagnostics: StateFlow<AudioDiagnostics> = _audioDiagnostics.asStateFlow()
    private val unprocessedSupport: String? by lazy {
        (appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
            ?.let { PlatformAudioEffects.unprocessedSupported(it) }
    }
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _vadThreshold = MutableStateFlow(DEFAULT_VAD_THRESHOLD)
    /** RNNoise VAD open threshold (0..1), persisted and applied live to the active call. */
    val vadThreshold: StateFlow<Float> = _vadThreshold.asStateFlow()
    private val _hysteresisGap = MutableStateFlow(DEFAULT_HYSTERESIS_GAP)
    /** Transmit gate hysteresis release margin (0..0.3), persisted and applied live to the active call. */
    val hysteresisGap: StateFlow<Float> = _hysteresisGap.asStateFlow()
    private val _transmitMode = MutableStateFlow(TransmitMode.VOICE_ACTIVATED)
    /** Voice-activation vs push-to-talk, persisted and applied live to the active call. */
    val transmitMode: StateFlow<TransmitMode> = _transmitMode.asStateFlow()
    private val _agcTargetDbFs = MutableStateFlow(DEFAULT_AGC_TARGET_DBFS)
    /** Makeup-gain target loudness (dBFS RMS), persisted and applied live to the active call. */
    val agcTargetDbFs: StateFlow<Float> = _agcTargetDbFs.asStateFlow()
    private val _agcEnabled = MutableStateFlow(DEFAULT_AGC_ENABLED)
    /** Makeup-gain on/off, persisted and applied live to the active call. */
    val agcEnabled: StateFlow<Boolean> = _agcEnabled.asStateFlow()
    private val _rnnoiseEnabled = MutableStateFlow(DEFAULT_RNNOISE_ENABLED)
    /** RNNoise denoising on/off (the VAD keeps running regardless), persisted and applied live. */
    val rnnoiseEnabled: StateFlow<Boolean> = _rnnoiseEnabled.asStateFlow()
    private val _vadEngine = MutableStateFlow("rnnoise")
    /** Which detector drives the transmit gate: "energy" | "rnnoise" | "silero". Persisted, applied live. */
    val vadEngine: StateFlow<String> = _vadEngine.asStateFlow()
    private val _prerollMs = MutableStateFlow(DEFAULT_PREROLL_MS)
    /** Onset-recovery detection preroll in ms (0 = off), persisted and applied live. */
    val prerollMs: StateFlow<Int> = _prerollMs.asStateFlow()
    private val _deafened = MutableStateFlow(false)
    /** Self-deafen (mutes playout + implies self-mute), broadcast to peers. */
    val deafened: StateFlow<Boolean> = _deafened.asStateFlow()
    private var deafenSetMute = false
    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    /** Sessions currently producing playout audio (live during a call). */
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()
    private val _selfTransmitting = MutableStateFlow(false)
    /** True while our uplink is transmitting (live during a call). */
    val selfTransmitting: StateFlow<Boolean> = _selfTransmitting.asStateFlow()
    private var appContext: Context? = null

    @Synchronized fun setMuted(value: Boolean) {
        if (!value && _deafened.value) {
            // Unmuting while deafened undeafens too (mirrors murmur + desktop: self_mute=false => self_deaf=false).
            deafenSetMute = false
            _deafened.value = false
            _muted.value = false
            active?.setDeafened(false)
            active?.setMuted(false)
            active?.sendSelfDeaf(false, false)
            return
        }
        _muted.value = value
        active?.setMuted(value)
        active?.sendSelfMute(value)
    }

    @Synchronized fun setDeafened(value: Boolean) {
        val r = DeafenLogic.onSetDeafened(value, _muted.value, deafenSetMute)
        deafenSetMute = r.deafenSetMute
        _deafened.value = value
        _muted.value = r.muted                 // set directly — one combined UserState below, no double-send
        active?.setDeafened(value)
        active?.setMuted(r.muted)
        active?.sendSelfDeaf(value, r.muted)
    }

    @Synchronized fun setVadThreshold(value: Float) {
        val v = value.coerceIn(0.1f, 0.9f)
        _vadThreshold.value = v
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("vad_threshold", v)?.apply()
        active?.setVadThreshold(v)
    }

    @Synchronized fun setHysteresisGap(value: Float) {
        val v = value.coerceIn(0f, 0.3f)
        _hysteresisGap.value = v
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("hysteresis_gap", v)?.apply()
        active?.setHysteresisGap(v)
    }

    @Synchronized fun setAgcTargetDbFs(value: Float) {
        val v = value.coerceIn(-30f, -9f)
        _agcTargetDbFs.value = v
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putFloat("agc_target_dbfs", v)?.apply()
        active?.setAgcTargetDbFs(v)
    }

    @Synchronized fun setAgcEnabled(value: Boolean) {
        _agcEnabled.value = value
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("agc_enabled", value)?.apply()
        active?.setAgcEnabled(value)
    }

    @Synchronized fun setRnnoiseEnabled(value: Boolean) {
        _rnnoiseEnabled.value = value
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("rnnoise_enabled", value)?.apply()
        active?.setRnnoiseEnabled(value)
    }

    /** Select which detector drives the transmit gate. Persists + sets the state optimistically, then
     *  builds the detector off the UI thread (asset read + native ORT init are tens of ms) and swaps
     *  it into the live engine. A Silero load failure reverts the pref/state to [prev] — no swap. */
    @Synchronized fun setVadEngine(engineName: String) {
        val prev = _vadEngine.value
        _vadEngine.value = engineName
        persistVadEngine(engineName)
        applyVadEngine(engineName, prev)
    }

    private fun persistVadEngine(name: String) {
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putString("vad_engine", name)?.apply()
    }

    /** Build the Silero detector from the bundled asset. Throws-free: returns a [Result]. Shared by
     *  [applyVadEngine] (background) and [ActiveSession.initialVad] (engine construction). */
    private fun buildSileroDetector(): Result<SileroVadDetector> = runCatching {
        val bytes = appContext!!.assets.open("silero_vad_16k_op15.onnx").use { it.readBytes() }
        SileroVadDetector(SileroOnnxSession(bytes))
    }

    @Synchronized fun setPrerollMs(ms: Int) {
        _prerollMs.value = ms
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putInt("preroll_ms", ms)?.apply()
        active?.setPrerollMs(ms)
    }

    /** Build the requested detector on a background thread (asset I/O + native init off the UI thread)
     *  and hot-swap it into the live session's engine. A Silero load failure reverts the pref/state to
     *  [prev] and logs — never crashes, never swaps. No-op (beyond the persisted preference already
     *  written) when no call is active; the next connect() picks up the persisted [_vadEngine] value. */
    private fun applyVadEngine(engineName: String, prev: String) {
        val session = active ?: return   // persist-only; next connect() reads the persisted _vadEngine
        // Launch on the SESSION's scope so shutdown()'s sessionScope.cancel() cancels an in-flight
        // switch via structured concurrency (no swap into a torn-down engine).
        session.sessionScope.launch(Dispatchers.IO) {
            val built: VadDetector = when (engineName) {
                "silero" -> buildSileroDetector().getOrElse {
                    Log.w(TAG, "Silero load failed; reverting", it)
                    _vadEngine.value = prev
                    persistVadEngine(prev)
                    return@launch
                }
                "energy" -> EnergyVadDetector()
                else -> session.rnnoise   // RNNoise-as-VAD; kept alive for its VAD prob
            }
            // The call may have ended (or been replaced) while we built off-thread. Bail without
            // swapping and close any freshly built native session so it isn't leaked.
            if (!isActive || active !== session) { (built as? SileroVadDetector)?.close(); return@launch }
            session.setVadDetector(built)
        }
    }

    @Synchronized fun setTransmitMode(mode: TransmitMode) {
        if (mode == _transmitMode.value) return           // no-op on redundant selection
        _transmitMode.value = mode
        appContext?.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
            ?.edit()?.putString("transmit_mode", mode.name)?.apply()
        // In PTT the hold button is the sole transmit control, so clear any self-mute (this also
        // broadcasts selfMute=false so other clients stop showing the mute icon).
        if (mode == TransmitMode.PUSH_TO_TALK) setMuted(false)
        active?.setTransmitMode(mode)
    }

    @Synchronized fun setPttHeld(held: Boolean) { active?.setPttHeld(held) }

    // Non-conflated failure events. `state` is a conflated StateFlow and the self-heal below flips
    // Failed -> Disconnected almost immediately, so main-thread observers can miss the transient
    // Failed. Subscribers that must reliably react to a failure (UI snackbar, Telecom teardown)
    // collect this instead.
    private val _failures = MutableSharedFlow<ConnectionState.Failed>(extraBufferCapacity = 8)
    val failures: SharedFlow<ConnectionState.Failed> = _failures.asSharedFlow()

    private var pinStore: PinStore = InMemoryPinStore()
    private var active: ActiveSession? = null

    init {
        // Long-lived watcher on the app-lifetime `scope` (outlives any single session):
        // whenever a session reaches Failed (e.g. auth reject, TLS/TOFU pin mismatch, IO
        // error surfaced by the state machine), self-heal by tearing the session down so
        // `active` is reset to null and a fresh connect() isn't a silent no-op. shutdown()
        // sets _state = Disconnected *after* tearing down, so this doesn't re-fire or recurse.
        scope.launch {
            state.collect { s ->
                if (s is ConnectionState.Failed) {
                    _failures.tryEmit(s)
                    disconnect()
                }
            }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        pinStore = SharedPrefsPinStore(app)
        val audioPrefs = app.getSharedPreferences("dumble_audio", Context.MODE_PRIVATE)
        _vadThreshold.value = audioPrefs.getFloat("vad_threshold", DEFAULT_VAD_THRESHOLD)
        _hysteresisGap.value = audioPrefs.getFloat("hysteresis_gap", DEFAULT_HYSTERESIS_GAP)
        val modeName = audioPrefs.getString("transmit_mode", null)
        _transmitMode.value = TransmitMode.entries.firstOrNull { it.name == modeName }
            ?: TransmitMode.VOICE_ACTIVATED
        _agcTargetDbFs.value = audioPrefs.getFloat("agc_target_dbfs", DEFAULT_AGC_TARGET_DBFS)
        _agcEnabled.value = audioPrefs.getBoolean("agc_enabled", DEFAULT_AGC_ENABLED)
        _rnnoiseEnabled.value = audioPrefs.getBoolean("rnnoise_enabled", DEFAULT_RNNOISE_ENABLED)
        _vadEngine.value = audioPrefs.getString("vad_engine", "rnnoise") ?: "rnnoise"
        _prerollMs.value = audioPrefs.getInt("preroll_ms", DEFAULT_PREROLL_MS)
        MumbleLog.sink = { tag, msg, t -> if (t != null) Log.w(tag, msg, t) else Log.d(tag, msg) }
    }

    @Synchronized fun connect(config: MumbleServerConfig) {
        if (active != null) { Log.w(TAG, "connect ignored — session active"); return }
        model.reset()
        active = ActiveSession(config).also { it.start() }
        _muted.value = false
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
        // Non-private so applyVadEngine can launch the live VAD-switch build on it — shutdown()'s
        // sessionScope.cancel() then cancels an in-flight switch (ActiveSession is itself private).
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        private val crypt = CryptState()
        private val tcp = MumbleTcpTransport(pinStore)
        private val selector = TransportSelector(config.forceTcp)
        private val codec = LibOpusCodec()
        // RNNoise does double duty: denoises the uplink AND supplies its VAD probability as the gate
        // detector. One instance is both the suppressor and the VAD; the denoise half can be toggled
        // off (VAD keeps running) via setRnnoiseEnabled.
        val rnnoise = RnnoiseSuppressor()
        private val initialVad: VadDetector = when (_vadEngine.value) {
            "silero" -> buildSileroDetector().getOrElse {
                Log.w(TAG, "Silero load failed at connect; falling back to rnnoise", it); rnnoise
            }
            "energy" -> EnergyVadDetector()
            else -> rnnoise
        }
        private val engine = AudioVoiceEngine(
            codec, suppressor = rnnoise, vad = initialVad, gateOpenLevel = _vadThreshold.value,
            gateCloseGap = _hysteresisGap.value,
            initialTransmitMode = _transmitMode.value,
            initialAgcEnabled = _agcEnabled.value,
            initialAgcTargetDbFs = _agcTargetDbFs.value,
            initialRnnoiseEnabled = _rnnoiseEnabled.value,
            initialPrerollCaptures = _prerollMs.value / 20)
        @Volatile private var udp: MumbleUdpTransport? = null
        private val pingBuf = ByteArray(256)

        private val voice = VoiceTransport(
            engine = engine,
            modeProvider = { selector.mode },
            target = if (config.loopbackVoice) VoiceTransport.LOOPBACK_TARGET else 0,
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
                voice.start()
            }
            override fun onTcpRtt(rttMs: Double) = selector.onTcpRtt(rttMs)
            override fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long) =
                voice.onPlaintext(plaintext, len, arrivalNanos)
        }

        private val sm: SessionStateMachine = SessionStateMachine(tcp, model, crypt, events)

        fun start() {
            sessionScope.launch { sm.state.collect { _state.value = it } }
            sessionScope.launch { selector.stats.collect { _netStats.value = it } }
            sessionScope.launch { engine.stats.collect { _voiceStats.value = it } }
            sessionScope.launch { engine.speakingSessions.collect { _speakingSessions.value = it } }
            sessionScope.launch { engine.selfTransmitting.collect { _selfTransmitting.value = it } }
            sessionScope.launch { engine.diagnostics.collect { _audioDiagnostics.value = it.copy(unprocessedSupported = unprocessedSupport) } }
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
                try {
                    sm.sendPing()
                    // Send a UDP ping every tick whenever the UDP socket exists — INCLUDING while
                    // tunneled. This is what enables tunnel->UDP recovery: TransportSelector only
                    // returns to UDP when BOTH `good` (we decrypt inbound pongs) and `remoteGood`
                    // (the server's count of our inbound pings, echoed in its TCP ping reply)
                    // advance. With no UDP leaving the client while tunneled, both deltas stay
                    // frozen at 0 and recovery can never fire (matches the selector's KDoc).
                    val u = udp
                    if (u != null) {
                        val ping = MumbleUdpProtos.Ping.newBuilder().setTimestamp(System.nanoTime()).build()
                        synchronized(pingBuf) {
                            val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_PING, ping, pingBuf)
                            u.send(pingBuf, n)
                        }
                    }
                    val stats = crypt.stats()
                    selector.evaluate(stats, sendingVoice = crypt.isValid())
                    Log.d("Ping", "tick good=${stats.good} late=${stats.late} lost=${stats.lost} remoteGood=${stats.remoteGood} mode=${selector.mode} udp=${u != null} valid=${crypt.isValid()} voiceRx=${engine.stats.value.received} udpAudioRx=${u?.audioRx} udpPingRx=${u?.pingRx} decryptFail=${u?.decryptFail} lateDrops=${engine.lateDrops}")
                } catch (t: Throwable) {
                    Log.e("Ping", "pingLoop iteration threw (continuing)", t)
                }
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

        fun setMuted(value: Boolean) = engine.setMuted(value)
        fun setVadThreshold(value: Float) = engine.setVadThreshold(value)
        fun setHysteresisGap(value: Float) = engine.setHysteresisGap(value)
        fun setAgcTargetDbFs(value: Float) = engine.setAgcTargetDbFs(value)
        fun setAgcEnabled(value: Boolean) = engine.setAgcEnabled(value)
        fun setRnnoiseEnabled(value: Boolean) = engine.setRnnoiseEnabled(value)
        fun setVadDetector(vad: VadDetector) = engine.setVadDetector(vad)
        fun setPrerollMs(ms: Int) = engine.setPrerollMs(ms)
        fun setTransmitMode(mode: TransmitMode) = engine.setTransmitMode(mode)
        fun setPttHeld(held: Boolean) = engine.setPttHeld(held)
        fun sendSelfMute(muted: Boolean) = sm.sendSelfMute(muted)
        fun setDeafened(value: Boolean) = engine.setDeafened(value)
        fun sendSelfDeaf(deaf: Boolean, mute: Boolean) = sm.sendSelfDeaf(deaf, mute)

        fun shutdown() {
            voice.stop()
            udp?.close()
            sm.disconnectLocal()
            sessionScope.cancel()
            _state.value = ConnectionState.Disconnected
            _netStats.value = NetStats()
            _loopbackStats.value = LoopbackStats()
            _audioDiagnostics.value = AudioDiagnostics()
            _speakingSessions.value = emptySet()
            _selfTransmitting.value = false
            _deafened.value = false
            deafenSetMute = false
        }
    }
}
