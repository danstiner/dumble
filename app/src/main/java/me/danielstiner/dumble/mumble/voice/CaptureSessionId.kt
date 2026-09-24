package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * The audio session id every capture stream opens with. The platform's recording list strips each
 * recording's owner but keeps its session id, so this is how the voice call tells our recording
 * from another app's. One per process, since capture can open before its call starts.
 */
object CaptureSessionId {
    /** Oboe's `SessionId::None`: the stream opens without an id. */
    const val NONE = -1

    private var id: Int? = null

    @Synchronized
    fun get(context: Context): Int = id ?: allocate(context).also { id = it }

    private fun allocate(context: Context): Int {
        val generated = context.getSystemService(AudioManager::class.java).generateAudioSessionId()
        if (generated > 0) return generated
        // AudioManager.ERROR: capture still opens, and the call stops counting other apps'
        // recordings.
        Log.w("CaptureSessionId", "no audio session id could be allocated ($generated)")
        return NONE
    }
}
