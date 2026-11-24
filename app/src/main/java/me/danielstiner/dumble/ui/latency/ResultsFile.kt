package me.danielstiner.dumble.ui.latency

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import me.danielstiner.dumble.mumble.voice.latency.CallVolume
import me.danielstiner.dumble.mumble.voice.latency.DeviceFacts
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a latency check reads from the device, and where its results go. */
object ResultsFile {
    private const val TAG = "LatencyCheck"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    /** In the app's external files directory: no permission, and adb reads it from a release
     *  build. The path is logged, which is how `scripts/latency-check` finds the file. */
    fun write(context: Context, text: String) {
        val dir = File(context.getExternalFilesDir(null) ?: return, "latency").apply { mkdirs() }
        val file = File(dir, "latency-${stamp.format(Instant.now())}.txt")
        file.writeText(text)
        Log.i(TAG, "results: ${file.absolutePath}")
    }

    fun deviceFacts() = DeviceFacts(Build.MODEL, Build.VERSION.RELEASE)

    fun callVolume(context: Context): CallVolume {
        val audio = context.getSystemService(AudioManager::class.java)
        return CallVolume(
            audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL), audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
        )
    }
}
