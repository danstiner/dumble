package me.danielstiner.dumble.ui.connect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.util.escapeHTML
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.data.ServerProfile
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.Connection
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import javax.inject.Inject

sealed interface PortInput {
    data class Ok(val port: Int) : PortInput
    data class Invalid(val reason: String) : PortInput
}

/** Blank → default; otherwise must be a 1..65535 integer. */
fun parsePort(text: String): PortInput {
    if (text.isBlank()) return PortInput.Ok(MumbleEndpoint.DEFAULT_PORT)
    val n = text.trim().toIntOrNull() ?: return PortInput.Invalid("not a number")
    return if (n in 1..65535) PortInput.Ok(n) else PortInput.Invalid("out of range")
}

/**
 * Overlay screens, orthogonal to connection state — Settings is reachable both before and during
 * a session. [Main] means no overlay: show whatever the connection status dictates.
 */
enum class Route { Main, Settings, About }

data class ConnectUiState(
    val draft: ServerProfile = ServerProfile("", MumbleEndpoint.DEFAULT_PORT, ""),
    val portText: String = "",
    val password: String = "",
    val portError: String? = null,
    val hostError: String? = null,
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val rttMs: Double? = null,
    val channelTree: ChannelTree = ChannelTree(),
    val messages: List<ChatMessage> = emptyList(),
    val showChat: Boolean = false,
    val route: Route = Route.Main,
    val unread: Int = 0,
    val chatDraft: String = "",
    val speakingSessions: Set<Int> = emptySet(),
    // Read back from our own row in the channel tree rather than remembered from the tap: the
    // server decides, and it can refuse or force this. One round trip of lag on the button, and no
    // second copy of the truth to drift.
    val deafened: Boolean = false,
    val talkBlock: TalkBlock? = null,
    // The platform's answer, not the last tap — same discipline as [deafened]. One round trip of
    // lag on the caption, and no second copy of the truth to drift.
    val audioRoutes: AudioRoutes = AudioRoutes(),
    // The OS answer, read from the system rather than remembered from a dialog. It has to survive
    // a ViewModel that outlives no connection: the foreground service keeps the process alive after
    // the task is swiped away, so resuming from the notification builds a fresh ViewModel over a
    // still-live session, and a "not asked yet" value there would disable Talk for the rest of it.
    val microphoneGranted: Boolean = false,
    // [MonotonicClock] reading when the session reached Connected, or null. Read the elapsed
    // duration against the same clock, never against wall time — the two share no origin.
    val connectedSinceMillis: Long? = null,
)

private data class ConnSnapshot(
    val status: ConnectionStatus,
    val rttMs: Double?,
    val channelTree: ChannelTree,
    val messages: List<ChatMessage>,
    val audioRoutes: AudioRoutes,
)

@HiltViewModel
class ConnectViewModel internal constructor(
    private val connection: Connection,
    private val configStore: ServerConfigStore,
    private val clock: MonotonicClock,
    // Seam: the real check needs a Context and the JVM tests have none.
    private val microphoneHeld: () -> Boolean = { false },
) : ViewModel() {
    @Inject constructor(
        @ApplicationContext context: Context,
        connection: Connection,
        configStore: ServerConfigStore,
        clock: MonotonicClock,
    ) : this(connection, configStore, clock, {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    })

    private val form = MutableStateFlow(ConnectUiState(microphoneGranted = microphoneHeld()))

    // The push-to-talk gate as the UI knows it. connection.setTransmitting is fire-and-forget, so
    // this is the only record of it, and speakingSessions never contains us: it is built from
    // decoded incoming audio and our own audio is never decoded locally.
    private val transmitting = MutableStateFlow(false)

    // Kotlin's typed combine() maxes at 5 flows; nest the connection flows into one snapshot
    // so the top-level combine only needs form + snapshot + speakingSessions + transmitting.
    private val connSnapshot = combine(
        connection.status, connection.roundTripMillis,
        connection.channelTree, connection.messages, connection.audioRoutes,
    ) { status, rtt, tree, msgs, routes -> ConnSnapshot(status, rtt, tree, msgs, routes) }

    val uiState: StateFlow<ConnectUiState> =
        combine(form, connSnapshot, connection.speakingSessions, transmitting) { f, c, speaking, tx ->
            val status = c.status
            val session = (status as? ConnectionStatus.Connected)?.sessionId
            val me = session?.let { c.channelTree.users[it] }
            val block = talkBlock(me, f.microphoneGranted)
            // Gated on the block rather than the microphone alone: the gate can be open while
            // nothing we send is carried — a denied permission, an engine that never opened, or a
            // server discarding us — and showing yourself speaking then would be a lie.
            val speakingMe = session?.takeIf { tx && block == null }
            f.copy(
                status = status, rttMs = c.rttMs,
                channelTree = c.channelTree, messages = c.messages,
                speakingSessions = if (speakingMe != null) speaking + speakingMe else speaking,
                deafened = me?.selfDeaf == true,
                talkBlock = block,
                audioRoutes = c.audioRoutes,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectUiState())

    // The last message the user has read — the newest one present when chat was last open (null = read
    // nothing yet). Anchored to the message *instance*, not an index or count: a capped, conflated, or
    // reconnected list can't fool identity. A message dropped past MAX_MESSAGES, or one from a prior
    // session, simply isn't found, so unread falls back to "everything visible" (≤ MAX_MESSAGES). This
    // relies on the log being append-only, so instances stay stable across emissions — see
    // SessionStateMachine.appendMessage.
    private var lastReadMarker: ChatMessage? = connection.messages.value.lastOrNull()

    /** The session [ConnectUiState.connectedSinceMillis] was stamped for; null while disconnected. */
    private var anchoredSession: Int? = null

    init {
        viewModelScope.launch {
            configStore.lastUsed()?.let { p ->
                form.value = form.value.copy(draft = p, portText = p.port.toString())
            }
        }
        viewModelScope.launch {
            connection.messages.collect { msgs ->
                if (form.value.showChat) {
                    // Reading now: hold the marker at the tail so unread stays 0.
                    lastReadMarker = msgs.lastOrNull()
                    form.value = form.value.copy(unread = 0)
                } else {
                    form.value = form.value.copy(unread = unreadAfter(msgs))
                }
            }
        }
        viewModelScope.launch {
            connection.status.collect { s ->
                // Keyed on the session, not on connected-ness: status is a StateFlow, so the
                // disconnect between two calls can be conflated away, and a nullness comparison
                // would then treat the second call as a continuation of the first.
                val session = (s as? ConnectionStatus.Connected)?.sessionId
                if (session == anchoredSession) return@collect
                anchoredSession = session
                // A drop mid-press disposes the call screen before `clickable` emits its Cancel,
                // so the button's release never arrives; left open, the gate would mark our own
                // row speaking for the whole of the next call.
                transmitting.value = false
                form.value = form.value.copy(
                    connectedSinceMillis = session?.let { clock.millis() },
                )
            }
        }
    }

    /** Messages after [lastReadMarker]; the whole list when the marker has scrolled off the cap or
     *  belongs to a prior session (identity miss) — which naturally bounds unread by the window. */
    private fun unreadAfter(msgs: List<ChatMessage>): Int {
        val marker = lastReadMarker ?: return msgs.size
        return msgs.size - (msgs.indexOfLast { it === marker } + 1)
    }

    fun onHostChange(v: String) { form.value = form.value.copy(draft = form.value.draft.copy(host = v), hostError = null) }
    fun onUsernameChange(v: String) { form.value = form.value.copy(draft = form.value.draft.copy(username = v)) }
    fun onPasswordChange(v: String) { form.value = form.value.copy(password = v) }
    fun onPortChange(v: String) { form.value = form.value.copy(portText = v, portError = null) }
    fun onChatDraftChange(v: String) { form.value = form.value.copy(chatDraft = v) }
    fun openChat() { lastReadMarker = connection.messages.value.lastOrNull(); form.value = form.value.copy(showChat = true, unread = 0) }
    fun closeChat() { form.value = form.value.copy(showChat = false) }
    fun openSettings() { form.value = form.value.copy(route = Route.Settings) }
    fun openAbout() { form.value = form.value.copy(route = Route.About) }
    /** About is nested under Settings, so backing out of it lands there, not on the form. */
    fun back() {
        form.value = form.value.copy(
            route = if (form.value.route == Route.About) Route.Settings else Route.Main,
        )
    }
    fun sendMessage() {
        // Trim and HTML-escape here, at the input boundary — the connection sends the body verbatim.
        val body = form.value.chatDraft.trim()
        if (body.isEmpty()) return
        if (connection.sendText(body.escapeHTML())) form.value = form.value.copy(chatDraft = "")
    }

    fun onConnect() {
        val f = form.value
        when (val p = parsePort(f.portText)) {
            is PortInput.Invalid -> { form.value = f.copy(portError = p.reason); return }
            is PortInput.Ok -> {
                val profile = f.draft.copy(port = p.port)
                val endpoint = runCatching { profile.endpoint }.getOrElse { e ->
                    form.value = f.copy(draft = profile, portError = null, hostError = e.message ?: "invalid server address")
                    return
                }
                form.value = f.copy(draft = profile, portError = null, hostError = null)
                viewModelScope.launch { configStore.saveLastUsed(profile) }
                connection.connect(endpoint, profile.username, f.password.ifBlank { null })
            }
        }
    }

    fun onTrust() = connection.trustAndConnect()
    fun onCancelTrust() = connection.cancelTrust()
    fun onDisconnect() = connection.disconnect()

    fun onMicrophonePermissionResult(granted: Boolean) {
        form.value = form.value.copy(microphoneGranted = granted)
    }

    /**
     * The connected screen is up and the microphone is ours. Separate from the permission result
     * because the answer outlives the connection it was given for — every connection has to start
     * its own capture session, not just the one that happened to prompt.
     */
    fun onMicrophoneReady() = connection.requestCapture()

    /**
     * Seam for [CallControls]: press and release open and close the transmit gate. Kept in
     * [transmitting], not just forwarded, so it can be merged into speakingSessions above — our
     * own session never otherwise appears there.
     */
    fun onTransmitting(active: Boolean) {
        transmitting.value = active
        connection.setTransmitting(active)
    }

    /**
     * Reads the current value off [uiState] — the server's answer — rather than taking it from the
     * caller, so the button and this can never disagree about what "the other one" means. Two taps
     * inside one round trip therefore ask for the same thing twice; the state machine re-sends its
     * last intent for the second, which is what keeps that harmless.
     */
    fun onToggleDeafen() = connection.setSelfDeaf(!uiState.value.deafened)

    /**
     * Seam for the route control. Fire-and-forget for the same reason deafen is: the platform's answer
     * arrives through [ConnectUiState.audioRoutes], so nothing here guesses where audio went.
     */
    fun onSelectRoute(routeId: String) = connection.requestAudioRoute(routeId)
}
