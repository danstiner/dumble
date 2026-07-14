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
interface AudioIn { fun read(out: ShortArray, n: Int): Int; fun close() }
interface AudioOut { fun write(pcm: ShortArray, n: Int); fun close() }

class AudioVoiceEngine(
    private val codec: OpusCodec,
    private val recorderFactory: () -> AudioIn = { AndroidAudioIn() },
    private val trackFactory: () -> AudioOut = { AndroidAudioOut() },
    private val suppressor: NoiseSuppressor = NoiseSuppressor.None,
) : VoiceEngine {

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    @Volatile private var muted = false
    @Volatile private var running = false
    private var wasMuted = false

    private val encoder = codec.newEncoder()
    private val vad: VadDetector = EnergyVadDetector()
    private val gate = TransmitGate()
    private val capturePcm = ShortArray(CAPTURE_SAMPLES)
    private val subLevels = FloatArray(FRAMES_PER_PACKET)
    private var frameNumber = 0L

    private var recorder: AudioIn? = null
    private var track: AudioOut? = null
    private var playbackThread: Thread? = null

    private val speakers = ConcurrentHashMap<Int, SpeakerStream>()
    @Volatile private var sent = 0L
    private val received = java.util.concurrent.atomic.AtomicLong(0)
    private var uplinkBytes = 0L
    private var uplinkFrames = 0
    private var diagTick = 0   // TEMP: RNNoise pre/post RMS diagnostic (#40 debug)
    @Volatile private var lateDropCount = 0L
    val lateDrops: Long get() = lateDropCount

    fun setMuted(value: Boolean) { muted = value }
    val isMuted get() = muted

    override fun start() {
        if (running) return
        running = true
        recorder = recorderFactory()
        track = trackFactory()
        playbackThread = Thread({ playbackLoop() }, "dumble-voice-playback").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    /** Send thread. Drains the mic; gates transmission on voice activity. */
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, CAPTURE_SAMPLES)             // capture clock — runs even while muted
        if (muted) {
            gate.reset()                                 // so unmute starts closed
            if (!wasMuted) { wasMuted = true; return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true) }
            return null
        }
        wasMuted = false

        val diag = (diagTick++ % 50 == 0)                            // TEMP: ~1/s
        val preRms = if (diag) frameRms(capturePcm, CAPTURE_SAMPLES) else 0.0

        for (i in 0 until FRAMES_PER_PACKET) {
            val off = i * FRAME_SAMPLES_10MS
            suppressor.process(capturePcm, off, FRAME_SAMPLES_10MS)   // None = no-op (Phase 1)
            subLevels[i] = vad.level(capturePcm, off, FRAME_SAMPLES_10MS)
        }
        if (diag) runCatching {   // runCatching: android.util.Log is unmocked in unit tests
            android.util.Log.d("AudioVoiceEngine", "vaddiag preRms=%.0f postRms=%.0f lvl0=%.2f lvl1=%.2f"
                .format(preRms, frameRms(capturePcm, CAPTURE_SAMPLES), subLevels[0], subLevels[1]))
        }
        val d = gate.update(subLevels)

        if (d.terminator) {
            return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true)  // freeze frameNumber
        }
        if (!d.send) return null

        val opus = encoder.encode(capturePcm, CAPTURE_SAMPLES)
        uplinkBytes += opus.size
        if (++uplinkFrames >= 250) {
            val avgBytes = uplinkBytes.toDouble() / uplinkFrames
            android.util.Log.d("AudioVoiceEngine", "uplink avg=%.1f B/frame ~%.1f kbps".format(avgBytes, avgBytes * 0.4))
            uplinkBytes = 0; uplinkFrames = 0
        }
        val fn = frameNumber
        frameNumber += FRAMES_PER_PACKET
        sent++
        _stats.update { it.copy(sent = sent) }
        return VoiceFrame(opus, opus.size, fn)
    }

    private fun frameRms(pcm: ShortArray, n: Int): Double {   // TEMP diagnostic (#40 debug)
        var s = 0.0
        for (i in 0 until n) { val v = pcm[i].toDouble(); s += v * v }
        return kotlin.math.sqrt(s / n)
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
        var logTick = 0
        while (running) {
            java.util.Arrays.fill(acc, 0)
            var active = 0
            val it = speakers.entries.iterator()
            while (it.hasNext()) {
                val (session, stream) = it.next()
                val produced = stream.fillTick(speakerOut)
                if (produced) {
                    AudioMixer.accumulate(acc, speakerOut, FRAME_SAMPLES_20MS)
                    active++
                    if (logTick % 50 == 0) {
                        var peak = 0
                        for (i in 0 until FRAME_SAMPLES_20MS) {
                            val a = kotlin.math.abs(speakerOut[i].toInt())
                            if (a > peak) peak = a
                        }
                        android.util.Log.d("AudioVoiceEngine", "mix session=$session peak=$peak active=$active")
                    }
                }
                if (stream.retired) { stream.close(); it.remove() }
            }
            AudioMixer.finalizeMix(acc, mix, FRAME_SAMPLES_20MS)
            out.write(mix, FRAME_SAMPLES_20MS)            // ALWAYS write 20 ms (silence when idle)
            _stats.update { it.copy(received = received.get(), activeSpeakers = active) }
            logTick++
        }
    }

    override fun stop() {
        running = false
        playbackThread?.join()
        playbackThread = null
        speakers.values.forEach { it.close() }
        speakers.clear()
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
    override fun close() { runCatching { record.stop() }; record.release() }
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
