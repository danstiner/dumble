package me.danielstiner.dumble.mumble.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * AudioTrack in streaming mode. Blocking writes are deliberate: they pace the playback loop off
 * the hardware clock, so no timer is needed and none should be added.
 */
class AndroidAudioOut : AudioOut {
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
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(format)
            // Two quanta is below every device's minimum; take whichever is larger so the
            // buffer is legal without over-deepening it and adding latency.
            .setBufferSizeInBytes(maxOf(minBytes, QUANTUM_SAMPLES * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    override fun write(pcm: ShortArray, n: Int): Boolean {
        // A negative return (ERROR_DEAD_OBJECT, ERROR_INVALID_OPERATION, ...) means the track
        // will never block again, so the caller must stop instead of spinning at audio priority.
        return track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING) >= 0
    }

    override fun close() {
        track.stop()
        track.release()
    }
}
