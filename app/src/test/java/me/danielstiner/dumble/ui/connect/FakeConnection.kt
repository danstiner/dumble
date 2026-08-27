package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import me.danielstiner.dumble.mumble.voice.TransmitMode

class FakeConnection : Connection {
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    override val serverVersion = MutableStateFlow<ServerVersion?>(null)
    override val roundTripTime = MutableStateFlow<Duration?>(null)
    override val lastServerReplyAt = MutableStateFlow<ComparableTimeMark?>(null)
    override val channelTree = MutableStateFlow(ChannelTree())
    override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val selfSpeaking = MutableStateFlow(false)
    override val callHeld = MutableStateFlow(false)
    override val speakingSessions = MutableStateFlow<Set<Int>>(emptySet())
    override val playoutStats = MutableStateFlow<PlayoutStats?>(null)
    override val userStats = MutableStateFlow<UserStats?>(null)
    override val audioRoutes = MutableStateFlow(AudioRoutes())

    var connectCalls = 0; private set
    var lastEndpoint: MumbleEndpoint? = null; private set
    var trustCalls = 0; private set
    var cancelCalls = 0; private set
    var disconnectCalls = 0; private set
    val sentTexts = mutableListOf<String>()
    var sendResult = true
    var requestCaptureCalls = 0; private set
    val transmitting = mutableListOf<Boolean>()
    val selfDeaf = mutableListOf<Boolean>()
    val muted = mutableListOf<Boolean>()
    val transmitModes = mutableListOf<TransmitMode>()
    val routeRequests = mutableListOf<String>()
    val userStatsRequests = mutableListOf<Int>()

    override fun connect(endpoint: MumbleEndpoint, username: String, password: String?) {
        connectCalls++; lastEndpoint = endpoint
    }
    override fun trustAndConnect() { trustCalls++ }
    override fun cancelTrust() { cancelCalls++ }
    override fun disconnect() { disconnectCalls++ }
    override fun sendText(text: String): Boolean { sentTexts += text; return sendResult }
    override fun requestCapture() { requestCaptureCalls++ }
    override fun setTransmitting(on: Boolean) { transmitting += on }

    override fun setSelfDeaf(on: Boolean) { selfDeaf += on }

    override fun setMuted(on: Boolean) { muted += on }

    override fun setTransmitMode(mode: TransmitMode) { transmitModes += mode }

    override fun requestAudioRoute(routeId: String) { routeRequests += routeId }

    override fun requestUserStats(session: Int) { userStatsRequests += session }

    fun emitConnected(sessionId: Int) { status.value = ConnectionStatus.Connected(sessionId) }
    fun emitSpeaking(sessions: Set<Int>) { speakingSessions.value = sessions }

    /** Only [PlayoutStats.bufferedSamples] is read today; the rest of the record stays at zero. */
    fun emitDepths(depths: Map<Int, Int>) {
        playoutStats.value = PlayoutStats(
            latencyMs = null, underruns = null, concealedGaps = 0, droppedPackets = 0,
            shrunkPackets = 0, catchUpPackets = 0, bufferedSamples = depths,
            targetSamples = emptyMap(),
        )
    }
}

/** Advances only when a test says so, so anchors taken at different moments are always distinct. */
