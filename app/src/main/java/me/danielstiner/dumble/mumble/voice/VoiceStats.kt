package me.danielstiner.dumble.mumble.voice

data class VoiceStats(
    val sent: Long = 0,
    val received: Long = 0,
    val lost: Long = 0,
    val concealed: Long = 0,
    val bufferMs: Int = 0,
    val activeSpeakers: Int = 0,
)
