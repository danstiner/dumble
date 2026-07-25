package me.danielstiner.dumble.mumble.voice

/** Abstracts the Android playback device so the receiver's logic stays JVM-testable. */
interface AudioOut {
    /** Blocking. Paces the playback loop off the audio clock — see VoiceReceiver. */
    fun write(pcm: ShortArray, n: Int)
    fun close()
}
