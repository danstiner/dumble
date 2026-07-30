package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * AudioTrack in streaming mode. Blocking writes are deliberate: they pace the playback loop off
 * the hardware clock, so no timer is needed and none should be added.
 */
class AndroidAudioOut(context: Context) : AudioOut {
    private val track: AudioTrack

    init {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Two quanta is below every device's minimum; take whichever is larger so the
        // buffer is legal without over-deepening it and adding latency.
        val requestedBytes = maxOf(minBytes, QUANTUM_SAMPLES * 2 * 2)
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
        logGrantedConfig(context, requestedBytes)
        track.play()
    }

    override fun write(pcm: ShortArray, n: Int): Boolean {
        // A negative return (ERROR_DEAD_OBJECT, ERROR_INVALID_OPERATION, ...) means the track
        // will never block again, so the caller must stop instead of spinning at audio priority.
        return track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING) >= 0
    }

    override fun close() = track.release()

    /**
     * What AudioFlinger granted, which is not what we asked for. The device burst is logged
     * alongside because the low-latency guidance is stated in terms of it: our fixed
     * [QUANTUM_SAMPLES] is misaligned wherever the burst does not divide it, and this line is what
     * says whether that is so on this device. Not logged: getPerformanceMode(), which reads back
     * granted flags but would report NONE everywhere, since AUDIO_OUTPUT_FLAG_FAST is never
     * granted unsolicited and we never request it.
     *
     * Debug rather than info, and ungated, for the reason the playout and capture summaries are:
     * being readable off a shipped build is the point of collecting this at all. It is one line
     * per track, and it is the most device-specific of the three — the numbers a bug report from
     * a device we do not have needs most.
     */
    private fun logGrantedConfig(context: Context, requestedBytes: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        Log.d(
            TAG,
            "track: buffer=${track.bufferSizeInFrames}f (asked ${requestedBytes / 2}f)" +
                " rate=${track.sampleRate}" +
                " | device: burst=${am?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}f" +
                " rate=${am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}" +
                " | quantum=${QUANTUM_SAMPLES}f",
        )
    }

    private companion object {
        const val TAG = "AudioOut"
    }
}
