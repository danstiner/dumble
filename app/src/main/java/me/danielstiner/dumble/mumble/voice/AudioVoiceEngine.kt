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
) : VoiceEngine {

    private val _stats = MutableStateFlow(VoiceStats())
    val stats: StateFlow<VoiceStats> = _stats.asStateFlow()

    @Volatile private var muted = false
    @Volatile private var running = false
    private var wasMuted = false

    private val encoder = codec.newEncoder()
    private val capturePcm = ShortArray(FRAME_SAMPLES_20MS)
    private var frameNumber = 0L

    private var recorder: AudioIn? = null
    private var track: AudioOut? = null
    private var playbackThread: Thread? = null

    private val speakers = ConcurrentHashMap<Int, SpeakerStream>()
    @Volatile private var sent = 0L
    private val received = java.util.concurrent.atomic.AtomicLong(0)

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

    /** Send thread. Always drains the mic; returns null when muted (after emitting one terminator). */
    override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
        val rec = recorder ?: return null
        rec.read(capturePcm, FRAME_SAMPLES_20MS)          // capture clock — runs even while muted
        if (muted) {
            if (!wasMuted) { wasMuted = true; return VoiceFrame(ByteArray(0), 0, frameNumber, isTerminator = true) }
            return null
        }
        wasMuted = false
        val opus = encoder.encode(capturePcm, FRAME_SAMPLES_20MS)
        val fn = frameNumber
        frameNumber += 2                                  // 10 ms units: 20 ms = 2 frames
        sent++
        _stats.update { it.copy(sent = sent) }
        return VoiceFrame(opus, opus.size, fn)
    }

    /** Receive thread — must not block, must not allocate a decoder. */
    override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                 frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
        val isTerminator = length == 0
        val copy = if (length == 0) ByteArray(0) else opusData.copyOfRange(offset, offset + length)
        val span = if (length == 0) 0 else codec.packetSamples(copy, 0, copy.size)
        val stream = speakers.computeIfAbsent(senderSession) {
            android.util.Log.d("AudioVoiceEngine", "new speaker session=$senderSession (total=${speakers.size + 1})")
            SpeakerStream(codec)
        }
        stream.offer(frameNumber * FRAME_SAMPLES_10MS, copy, span, isTerminator)
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
    override fun read(out: ShortArray, n: Int): Int {
        var off = 0
        while (off < n) {
            val r = record.read(out, off, n - off, AudioRecord.READ_BLOCKING)
            if (r <= 0) break
            off += r
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
    override fun write(pcm: ShortArray, n: Int) { track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING) }
    override fun close() { runCatching { track.stop() }; track.release() }
}
