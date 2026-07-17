package me.danielstiner.dumble.mumble.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Abstracts the Android capture device so the engine's logic is JVM-testable. */
interface AudioIn { fun read(out: ShortArray, n: Int): Int; fun close(); fun captureInfo(): CaptureInfo? = null }
interface AudioOut { fun write(pcm: ShortArray, n: Int); fun close() }

private const val DIAG_INTERVAL = 25   // ~500 ms at 20 ms captures

class AudioVoiceEngine(
    private val codec: OpusCodec,
    private val recorderFactory: () -> AudioIn = { AndroidAudioIn() },
    private val trackFactory: () -> AudioOut = { AndroidAudioOut() },
    private val suppressor: NoiseSuppressor = NoiseSuppressor.None,
    private val vad: VadDetector = EnergyVadDetector(),
    gateOpenLevel: Float = 0.60f,
    initialTransmitMode: TransmitMode = TransmitMode.VOICE_ACTIVATED,
    initialAgcEnabled: Boolean = true,
    initialAgcTargetDbFs: Float = GainControl.DEFAULT_TARGET_DBFS,
    initialRnnoiseEnabled: Boolean = true,
    initialLookaheadCaptures: Int = 0,
) : VoiceEngine {

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    private val _diagnostics = MutableStateFlow(AudioDiagnostics())
    val diagnostics: StateFlow<AudioDiagnostics> = _diagnostics.asStateFlow()
    private var diagTick = 0

    @Volatile private var muted = false
    @Volatile private var running = false
    private var wasMuted = false
    @Volatile private var transmitMode = initialTransmitMode
    @Volatile private var pttHeld = false
    private var lastMode = initialTransmitMode   // send-thread-only: detects a live mode change
    private var sending = false                  // send-thread-only: last emitted frame was live (non-terminator)

    private val encoder = codec.newEncoder()
    private val gate = TransmitGate(openLevel = gateOpenLevel)
    private val gainControl = GainControl(
        targetDbFs = initialAgcTargetDbFs, enabled = initialAgcEnabled)
    private val processor = TransmitProcessor(suppressor, vad, gate, gainControl)
    private val capturePcm = ShortArray(CAPTURE_SAMPLES)
    private var frameNumber = 0L
    @Volatile private var lookahead = LookaheadDelay(initialLookaheadCaptures)
    @Volatile private var pendingLookaheadCaptures: Int? = null

    /** Live-adjust the onset-recovery lookahead delay (0 = off/identity). 20 ms per capture.
     *  Runs on the UI thread: records a pending K, applied (flush + clean close) on the send thread. */
    fun setLookaheadMs(ms: Int) { pendingLookaheadCaptures = (ms / 20).coerceAtLeast(0) }

    init { suppressor.setDenoiseEnabled(initialRnnoiseEnabled) }

    private var recorder: AudioIn? = null
    private var track: AudioOut? = null
    private var playbackThread: Thread? = null

    private val speakers = ConcurrentHashMap<Int, SpeakerStream>()
    @Volatile private var sent = 0L
    private val received = java.util.concurrent.atomic.AtomicLong(0)
    private var uplinkBytes = 0L
    private var uplinkFrames = 0
    @Volatile private var lateDropCount = 0L
    val lateDrops: Long get() = lateDropCount

    private val _speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    /** Sessions currently producing playout audio (with a ~200 ms release hold). */
    val speakingSessions: StateFlow<Set<Int>> = _speakingSessions.asStateFlow()
    private val _selfTransmitting = MutableStateFlow(false)
    /** True while our uplink is sending real (non-terminator) frames (with a ~200 ms release hold). */
    val selfTransmitting: StateFlow<Boolean> = _selfTransmitting.asStateFlow()

    private val speakingHold = SpeakingHold()   // playback thread only
    private val transmitHold = TransmitHold()   // send thread only
    @Volatile private var deafened = false

    /** Live-toggle self-deafen: mutes playout (still draining streams to keep jitter buffers sane).
     *  Deafening also drops any held PTT so a later un-deafen can't reopen the mic with no finger on Talk. */
    fun setDeafened(value: Boolean) { deafened = value; if (value) pttHeld = false }
    internal val isDeafened get() = deafened   // test seam

    fun setMuted(value: Boolean) { muted = value }
    val isMuted get() = muted

    /** Live-adjust the transmit gate's open threshold (the RNNoise VAD probability to open at). */
    fun setVadThreshold(value: Float) { gate.openLevel = value }

    /** Live-adjust the makeup-gain target loudness (dBFS RMS). */
    fun setAgcTargetDbFs(value: Float) { gainControl.targetDbFs = value }

    /** Live-enable/disable the makeup gain (off = unity passthrough). */
    fun setAgcEnabled(value: Boolean) { gainControl.enabled = value }

    /** Live-enable/disable RNNoise denoising. Off keeps RNNoise running for the VAD but sends raw audio. */
    fun setRnnoiseEnabled(value: Boolean) { suppressor.setDenoiseEnabled(value) }

    /** Switch transmit mode live. The send thread detects the change and flushes any open
     *  talkspurt; a fresh button press is required after any mode change. */
    fun setTransmitMode(mode: TransmitMode) { transmitMode = mode; pttHeld = false }

    /** Push-to-talk button state (only meaningful in [TransmitMode.PUSH_TO_TALK]). */
    fun setPttHeld(value: Boolean) { pttHeld = value }

    override fun start() {
        if (running) return
        running = true
        vad.reset()
        recorder = recorderFactory()
        recorder?.captureInfo()?.let {
            _diagnostics.value = AudioDiagnostics(effects = it.effects, deviceModel = it.deviceModel, connected = true)
        }
        track = trackFactory()
        playbackThread = Thread({ playbackLoop() }, "dumble-voice-playback").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    /**
     * Send thread. Drains the mic; gates transmission on voice activity.
     *
     * `frameNumber` advances at WALL-CLOCK rate (every 20 ms capture, sent or not) — Mumble's
     * receiver schedules by absolute `frame_number`, so a frozen counter would land resumed
     * talkspurts in the past of a still-alive jitter buffer and get them dropped as late.
     * Terminators are REAL (silent, non-empty) frames — Mumble drops empty-payload packets
     * before the terminator flag is read.
     */
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val frame = computeOutgoing()
        val realSend = frame != null && !frame.isTerminator
        val transmitting = transmitHold.update(realSend)
        if (transmitting != _selfTransmitting.value) _selfTransmitting.value = transmitting
        return frame
    }

    private fun computeOutgoing(): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, CAPTURE_SAMPLES)             // capture clock — runs even while muted
        val fn = frameNumber
        frameNumber += FRAMES_PER_PACKET                 // wall-clock: advance every capture

        // Apply a pending K change here on the send thread (setLookaheadMs only records it on the UI
        // thread). Mirror the mode-change flush+terminate so the old delayed stream closes cleanly
        // instead of leaving `sending` stale-true with buffered captures silently dropped.
        val pending = pendingLookaheadCaptures
        if (pending != null && pending != lookahead.k) {
            pendingLookaheadCaptures = null
            lookahead.flush()                            // discard old ring's buffered captures
            lookahead = LookaheadDelay(pending)
            if (sending) return terminatorFrame(fn)      // close the old delayed stream cleanly
        }

        val diag = (++diagTick % DIAG_INTERVAL == 0)
        val rawDb = if (diag) rmsDbFs(capturePcm, 0, CAPTURE_SAMPLES) else 0f

        // One consistent read of the UI-written mode for the whole frame (no torn read between the
        // mode-change check and the dispatch below). A live mode change flushes any open talkspurt
        // with one real terminator, so the far end decodes/flushes instead of waiting on a stream
        // that never resumes in the new mode.
        val mode = transmitMode
        if (mode != lastMode) {
            lastMode = mode
            gate.reset()
            vad.reset()
            // Rare transition: discard whatever pre-onset audio is still buffered in the lookahead
            // ring rather than trying to emit multiple frames from this one nextOutgoingFrame() call.
            val la = lookahead
            if (la.k > 0) la.flush()
            if (sending) return terminatorFrame(fn)
        }

        if (muted) {
            gate.reset()                                 // so unmute starts closed
            if (!wasMuted) {                             // one real (silent) terminator on mute
                wasMuted = true
                val la = lookahead
                if (la.k > 0) la.flush()                  // discard buffered pre-mute audio (v1, rare)
                return terminatorFrame(fn)
            }
            return null
        }
        if (wasMuted) { vad.reset(); wasMuted = false }   // unmute edge → VAD discontinuity

        return when (mode) {
            TransmitMode.VOICE_ACTIVATED -> {
                val d = processor.process(capturePcm)     // denoise (in place) → vad → gate
                if (diag) pushDiagnostics(rawDb)
                val la = lookahead
                if (la.k == 0) {
                    // IDENTITY PATH — unchanged from today
                    if (!d.send) { sending = false; return null }
                    return encodeAndCount(fn, d.terminator)   // speech / hangover / closing frame
                }
                // K>0: feed the live gate-open decision into the delay ring, emit the delayed capture.
                // Emergent: two talkspurts separated by fewer than K silent captures merge into one
                // continuous transmission (no intervening terminator) — an accepted consequence of the
                // OR-window recovery (the ring's send=OR-over-window keeps the bridge frames live).
                val openLive = d.send && !d.terminator    // real speech this tick (exclude the closing terminator tick)
                val emit = la.offer(capturePcm, openLive, fn) ?: return null   // priming → nothing out yet
                if (emit.send) {
                    encodeBuffer(emit.pcm, emit.frameNumber, terminator = false)   // sets sending=true
                } else if (sending) {
                    // delayed stream just closed → one real terminator on the delayed frame
                    java.util.Arrays.fill(emit.pcm, 0)
                    encodeBuffer(emit.pcm, emit.frameNumber, terminator = true)    // sets sending=false
                } else null
            }
            TransmitMode.PUSH_TO_TALK -> {
                gate.reset()                              // keep the VA gate closed under PTT
                if (pttHeld) {
                    processor.denoise(capturePcm)         // clean the mic, but do NOT gate
                    if (diag) pushDiagnostics(rawDb)
                    encodeAndCount(fn, terminator = false)
                } else if (sending) {                     // release edge → one real terminator
                    terminatorFrame(fn)
                } else null
            }
        }
    }

    /** Sample post-gain level + gain + prob and push a diagnostics update. capturePcm is post-process. */
    private fun pushDiagnostics(rawDb: Float) {
        val postGainDb = rmsDbFs(capturePcm, 0, CAPTURE_SAMPLES)
        // Effective gain: GainControl freezes .gain at its last value when disabled (A/B carry-over)
        // but applies NONE, so the HUD must treat disabled as unity (0 dB) or post-denoise is offset.
        val effectiveGain = if (gainControl.enabled) gainControl.gain else 1f
        val gainDb = (20.0 * kotlin.math.log10(effectiveGain.coerceAtLeast(1e-6f).toDouble())).toFloat()
        _diagnostics.update {
            it.copy(rawDbFs = rawDb, postGainDbFs = postGainDb, agcGainDb = gainDb,
                    vadProb = processor.lastVadProb, connected = true)
        }
    }

    /** A real (silent, non-empty) terminator frame. Mumble drops empty-payload packets before
     *  reading the terminator flag, so the closing frame must carry real (silent) Opus bytes. */
    private fun terminatorFrame(fn: Long): VoiceFrame {
        sending = false
        java.util.Arrays.fill(capturePcm, 0)
        val opus = encoder.encode(capturePcm, CAPTURE_SAMPLES)
        return VoiceFrame(opus, opus.size, fn, isTerminator = true)
    }

    /** Encode the current capture, update uplink stats, and mark send state. */
    private fun encodeAndCount(fn: Long, terminator: Boolean): VoiceFrame =
        encodeBuffer(capturePcm, fn, terminator)

    /** Encode an arbitrary capture buffer (current or delayed-from-the-lookahead-ring), update
     *  uplink stats, and mark send state. */
    private fun encodeBuffer(pcm: ShortArray, fn: Long, terminator: Boolean): VoiceFrame {
        sending = !terminator
        val opus = encoder.encode(pcm, CAPTURE_SAMPLES)
        uplinkBytes += opus.size
        if (++uplinkFrames >= 250) {
            val avgBytes = uplinkBytes.toDouble() / uplinkFrames
            android.util.Log.d("AudioVoiceEngine", "uplink avg=%.1f B/frame ~%.1f kbps".format(avgBytes, avgBytes * 0.4))
            uplinkBytes = 0; uplinkFrames = 0
        }
        sent++
        _stats.update { it.copy(sent = sent) }
        return VoiceFrame(opus, opus.size, fn, isTerminator = terminator)
    }

    /** Receive thread — must not block, must not allocate a decoder. */
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long,
                                 isTerminator: Boolean) {
        val terminator = isTerminator || length == 0     // keep the empty-payload inference (mute path)
        val copy = if (length == 0) ByteArray(0) else opusData.copyOfRange(offset, offset + length)
        val span = if (length == 0) 0 else codec.packetSamples(copy, 0, copy.size)
        val stream = speakers.computeIfAbsent(senderSession) {
            android.util.Log.d("AudioVoiceEngine", "new speaker session=$senderSession (total=${speakers.size + 1})")
            SpeakerStream(codec)
        }
        val queued = stream.offer(frameNumber * FRAME_SAMPLES_10MS, copy, span, terminator)
        if (!queued && !terminator) lateDropCount++
        received.incrementAndGet()
    }

    private fun playbackLoop() {
        val out = track!!
        val mix = ShortArray(FRAME_SAMPLES_20MS)
        val acc = IntArray(FRAME_SAMPLES_20MS)
        val speakerOut = ShortArray(FRAME_SAMPLES_20MS)
        val producedSessions = HashSet<Int>()
        while (running) {
            java.util.Arrays.fill(acc, 0)
            producedSessions.clear()
            var active = 0
            val it = speakers.entries.iterator()
            while (it.hasNext()) {
                val (session, stream) = it.next()
                val produced = stream.fillTick(speakerOut)
                if (produced) {
                    AudioMixer.accumulate(acc, speakerOut, FRAME_SAMPLES_20MS)
                    producedSessions.add(session)
                    active++
                }
                if (stream.retired) { stream.close(); it.remove(); speakingHold.drop(session) }
            }
            AudioMixer.finalizeMix(acc, mix, FRAME_SAMPLES_20MS)
            if (deafened) java.util.Arrays.fill(mix, 0)   // mute playout, streams already drained above
            out.write(mix, FRAME_SAMPLES_20MS)            // ALWAYS write 20 ms (silence when idle/deafened)
            val speaking = speakingHold.tick(producedSessions)
            if (speaking != _speakingSessions.value) _speakingSessions.value = speaking
            _stats.update { it.copy(received = received.get(), activeSpeakers = active) }
        }
    }

    override fun stop() {
        running = false
        playbackThread?.join()
        playbackThread = null
        speakers.values.forEach { it.close() }
        speakers.clear()
        _speakingSessions.value = emptySet()
        _selfTransmitting.value = false
        speakingHold.clear()
        transmitHold.clear()
        deafened = false
        recorder?.close(); recorder = null
        track?.close(); track = null
        encoder.close()
        suppressor.close()
    }
}

/** Real capture: 48 kHz mono PCM16 from the VOICE_COMMUNICATION source (platform AEC/NS/AGC). */
class AndroidAudioIn : AudioIn {
    private val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuf, FRAME_SAMPLES_20MS * 2 * 4)).also { it.startRecording() }
    private val platformEffects = PlatformAudioEffects(record.audioSessionId)
    override fun captureInfo(): CaptureInfo =
        CaptureInfo(platformEffects.states, PlatformAudioEffects.deviceModel())
    private var readCount = 0
    override fun read(out: ShortArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = record.read(out, off, n - off, AudioRecord.READ_BLOCKING)
            if (r <= 0) {
                android.util.Log.w("AudioVoiceEngine", "AudioRecord.read=$r state=${record.recordingState} off=$off")
                break
            }
            off += r
        }
        if (readCount++ % 250 == 0) {
            android.util.Log.d("AudioVoiceEngine", "mic recordingState=${record.recordingState} lastRead=$off")
        }
        return off
    }
    override fun close() {
        runCatching { platformEffects.close() }
        runCatching { record.stop() }; record.release()
    }
}

/** Real playback: 48 kHz mono PCM16, VOICE_COMMUNICATION usage. */
class AndroidAudioOut : AudioOut {
    private val minBuf = AudioTrack.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES_20MS * 2 * 4))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
        .also { it.play() }
    private var writeCount = 0
    override fun write(pcm: ShortArray, n: Int) {
        val w = track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
        if (w < 0) {
            android.util.Log.w("AudioVoiceEngine", "AudioTrack.write err=$w playState=${track.playState}")
        }
        if (writeCount++ % 250 == 0) {
            android.util.Log.d("AudioVoiceEngine", "spk playState=${track.playState} lastWrite=$w")
        }
    }
    override fun close() { runCatching { track.stop() }; track.release() }
}
