package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.util.Log

/**
 * AudioTrack in streaming mode. Blocking writes are deliberate: they pace the playback loop off
 * the hardware clock, so no timer is needed and none should be added.
 */
class AndroidAudioOut(context: Context) : AudioOut {
    private val track: AudioTrack

    override val writeAheadSamples: Int

    /** Playback thread only — [write] is the sole writer, [outputStats] the sole other reader. */
    private var framesWritten = 0L
    private val timestamp = AudioTimestamp()

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Two frames is below every device's minimum; take whichever is larger so the
        // buffer is legal without over-deepening it and adding latency.
        val requestedBytes = maxOf(minBytes, FRAME_SAMPLES * 2 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            .setBufferSizeInBytes(requestedBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Number of audio frames that the HAL (Hardware Abstraction Layer) buffer can hold.
        // Constructing the output track with an exact multiple of this number can reduce jitter
        // by matching our fill callback to run at most once per HAL playout timeslice.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val halBufferString: String? = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val halBuffer: Int = halBufferString?.let { str ->
            Integer.parseInt(str).takeUnless { it == 0 }
        } ?: 256 // Use default
        // Set the playout buffer to double the HAL buffer size, or double our 10 ms Mumble frame,
        // whichever is larger, as a whole number of HAL buffers. Double buffering gives us space
        // and time to refill the buffer while the HAL is emptying it; Oboe's LatencyTuner starts
        // from a floor of 2x the HAL buffer for this reason, then grows one HAL buffer per
        // underrun — we do not grow yet, but that is a future feature. The frame floor is ours:
        // this loop refills a frame at a time from a Kotlin thread, and measured on a Pixel 7a
        // (HAL buffer 128 samples) two HAL buffers underran ~3000 times a minute, 640 samples
        // 2852, and two frames 0. A blocking write never needs room for a whole frame at once —
        // AudioTrack.write blocks and incrementally copies whatever fits each time the HAL frees
        // space — so this is about slack, not fit.
        val minSamples = maxOf(2 * halBuffer, 2 * FRAME_SAMPLES)
        val request = halBuffer * ((minSamples + halBuffer - 1) / halBuffer)
        // AudioTrack counts in frames of one sample per channel; mono, so samples.
        writeAheadSamples = track.setBufferSizeInFrames(request).takeIf { it > 0 }
            ?: track.bufferSizeInFrames
        logGrantedConfig(am, requestedBytes, halBuffer, request)
        track.play()
    }

    override fun write(pcm: ShortArray, n: Int): Boolean {
        val written = track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
        // Negative is an error code (ERROR_DEAD_OBJECT, ...). Short of the request means the track
        // was stopped or paused mid-call. Neither blocks, and write() is the loop's only pacing, so
        // both must stop it rather than let it spin at THREAD_PRIORITY_URGENT_AUDIO.
        if (written < n) return false
        // Shorts, not frames — identical only because voice is mono.
        framesWritten += written
        return true
    }

    override fun outputStats(): OutputStats {
        val latencyMs = if (track.getTimestamp(timestamp)) {
            LatencyMath.outputLatencyMs(
                framesWritten, timestamp.framePosition, timestamp.nanoTime,
                System.nanoTime(), SAMPLE_RATE, MAX_TIMESTAMP_AGE_NANOS,
            )
        } else {
            null
        }
        return OutputStats(latencyMs, track.underrunCount)
    }

    override fun close() = track.release()

    /**
     * What AudioFlinger granted, which is not what we asked for. The HAL buffer size is logged
     * alongside because the low-latency guidance is stated in terms of it: our fixed
     * [FRAME_SAMPLES] is misaligned wherever it does not divide it, and this line is what says
     * whether that is so on this device. Not logged: getPerformanceMode(), which reads back
     * granted flags but would report NONE everywhere, since AUDIO_OUTPUT_FLAG_FAST is never
     * granted unsolicited and we never request it.
     *
     * Debug rather than info, and ungated, for the reason the playout and capture summaries are:
     * being readable off a shipped build is the point of collecting this at all. It is one line
     * per track, and it is the most device-specific of the three — the numbers a bug report from
     * a device we do not have needs most.
     */
    private fun logGrantedConfig(am: AudioManager, requestedBytes: Int, halBuffer: Int, request: Int) {
        Log.d(
            TAG,
            "track: buffer=${track.bufferCapacityInFrames} (asked ${requestedBytes / 2})" +
                " writeAhead=$writeAheadSamples (asked $request)" +
                " rate=${track.sampleRate}" +
                " | device: halBuffer=$halBuffer" +
                " rate=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}" +
                " | frame=$FRAME_SAMPLES samples",
        )
    }

    private companion object {
        const val TAG = "AudioOut"

        /** Equal to the 1 s sample interval: outputStats() is called at least that often while a
         *  spurt is producing audio, so a reading staler than this can only be the platform's last
         *  pre-gap timestamp. */
        const val MAX_TIMESTAMP_AGE_NANOS = 1_000_000_000L
    }
}
