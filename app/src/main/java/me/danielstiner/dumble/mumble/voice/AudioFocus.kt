package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Platform audio focus, narrowed to the three transitions that mean anything to a talk button.
 * A seam so the JVM tests can drive loss and regain without a device.
 */
interface AudioFocus {
    /** False if the system refused. Advisory — the session continues either way. */
    fun request(onChange: (Change) -> Unit): Boolean
    fun abandon()

    enum class Change { LOST, LOST_TEMPORARILY, REGAINED }
}

/**
 * Null for every code that should not disturb capture — notably
 * [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK], where ducking is a *playback* decision and
 * cutting the microphone would end a sentence over a notification chime. A pure function so that
 * case is pinned by a test rather than by reading the listener.
 */
fun focusChangeOf(code: Int): AudioFocus.Change? = when (code) {
    AudioManager.AUDIOFOCUS_LOSS -> AudioFocus.Change.LOST
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocus.Change.LOST_TEMPORARILY
    AudioManager.AUDIOFOCUS_GAIN -> AudioFocus.Change.REGAINED
    else -> null
}

/** For the connection tests that predate focus, and for a build with no AudioManager to talk to. */
object NoAudioFocus : AudioFocus {
    override fun request(onChange: (AudioFocus.Change) -> Unit) = true
    override fun abandon() = Unit
}

class AndroidAudioFocus(context: Context) : AudioFocus {

    private val manager = context.getSystemService(AudioManager::class.java)
    private var held: AudioFocusRequest? = null

    override fun request(onChange: (AudioFocus.Change) -> Unit): Boolean {
        // GAIN rather than GAIN_TRANSIENT: a call is not a short interruptible sound. Attributes
        // match AndroidAudioOut's, so the request describes the same stream the user hears.
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { code -> focusChangeOf(code)?.let(onChange) }
            .build()
        held = req
        val granted = manager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Log.w(TAG, "audio focus refused; continuing without it")
        return granted
    }

    override fun abandon() {
        held?.let { manager.abandonAudioFocusRequest(it) }
        held = null
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
