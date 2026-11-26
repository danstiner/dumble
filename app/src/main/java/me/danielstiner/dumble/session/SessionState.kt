package me.danielstiner.dumble.session

import kotlinx.coroutines.flow.MutableStateFlow
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.DeafenState
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration

/** Everything a session publishes about itself: the flows [AppState] exposes. */
internal class SessionState {
    val status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val serverVersion = MutableStateFlow<ServerVersion?>(null)
    val roundTripTime = MutableStateFlow<Duration?>(null)
    val voicePath = MutableStateFlow(VoicePath.State())
    val lastServerReplyAt = MutableStateFlow<ComparableTimeMark?>(null)
    val channelTree = MutableStateFlow(ChannelTree())
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val selfState = MutableStateFlow(DeafenState())
    val speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    val selfSpeaking = MutableStateFlow(false)
    val callHeld = MutableStateFlow(false)
    val playoutStats = MutableStateFlow<PlayoutStats?>(null)
    val captureStats = MutableStateFlow<CaptureStats?>(null)
    val userStats = MutableStateFlow<UserStats?>(null)
    val audioRoutes = MutableStateFlow(AudioRoutes())

    /** Nothing published, on [status]. Every flow, because a survivor is one the next screen
     *  renders with the last session's data. Caller holds the lock its publishers share. */
    fun reset(status: SessionStatus) {
        this.status.value = status
        serverVersion.value = null
        roundTripTime.value = null
        lastServerReplyAt.value = null
        voicePath.value = VoicePath.State()
        channelTree.value = ChannelTree()
        messages.value = emptyList()
        selfState.value = DeafenState()
        speakingSessions.value = emptySet()
        selfSpeaking.value = false
        callHeld.value = false
        playoutStats.value = null
        captureStats.value = null
        userStats.value = null
        audioRoutes.value = AudioRoutes()
    }
}

/**
 * One session's licence to write a [SessionState]. A blocking handshake cannot be preempted, so a
 * superseded session can finish late; its owner revokes the licence under [lock] when it opens
 * the next session or disconnects, and the late writes do nothing. A session that ended on its
 * own keeps its licence: its terminal values are what the user is looking at.
 */
internal class Publisher(private val state: SessionState, private val lock: Any) {
    private var revoked = false

    fun revoke() = synchronized(lock) { revoked = true }

    /** Runs [write] under [lock], unless revoked. */
    fun publish(write: SessionState.() -> Unit) = synchronized(lock) { if (!revoked) state.write() }
}
