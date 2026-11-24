package me.danielstiner.dumble.mumble.voice.latency

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ResultsFile {
    private const val TAG = "LatencyCheck"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    /** In the app's external files directory: no permission, and `adb pull` reads it from a release build. */
    fun write(context: Context, text: String, now: Instant): File? {
        val dir = File(context.getExternalFilesDir(null) ?: return null, "latency").apply { mkdirs() }
        return File(dir, "latency-${stamp.format(now)}.txt").also {
            it.writeText(text)
            Log.i(TAG, "results: ${it.absolutePath}")
        }
    }

    fun deviceFacts(context: Context): DeviceFacts {
        val audio = context.getSystemService(AudioManager::class.java)
        return DeviceFacts(
            Build.MODEL, Build.VERSION.RELEASE,
            audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL), audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
        )
    }
}
