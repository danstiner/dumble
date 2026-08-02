package me.danielstiner.dumble.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
    // Null until the connected screen has asked; true/false is the OS answer. Outlives the
    // connection it was given for, which is why capture starts from onMicrophoneReady.
    val microphoneGranted: Boolean? = null,
    // [MonotonicClock] reading when the session reached Connected, or null. Read the elapsed
    // duration against the same clock, never against wall time — the two share no origin.
    val connectedSinceMillis: Long? = null,
)

private data class ConnSnapshot(
    val status: ConnectionStatus,
    val rttMs: Double?,
    val channelTree: ChannelTree,
    val messages: List<ChatMessage>,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connection: Connection,
    private val configStore: ServerConfigStore,
    private val clock: MonotonicClock,
) : ViewModel() {

    private val form = MutableStateFlow(ConnectUiState())

    // The push-to-talk gate as the UI knows it. connection.setTransmitting is fire-and-forget, so
    // this is the only record of it, and speakingSessions never contains us: it is built from
    // decoded incoming audio and our own audio is never decoded locally.
    private val transmitting = MutableStateFlow(false)

    // Kotlin's typed combine() maxes at 5 flows; nest the connection flows into one snapshot
    // so the top-level combine only needs form + snapshot + speakingSessions + transmitting.
    private val connSnapshot = combine(
        connection.status, connection.roundTripMillis,
        connection.channelTree, connection.messages,
    ) { status, rtt, tree, msgs -> ConnSnapshot(status, rtt, tree, msgs) }

    val uiState: StateFlow<ConnectUiState> =
        combine(form, connSnapshot, connection.speakingSessions, transmitting) { f, c, speaking, tx ->
            val status = c.status
            // Gated on the microphone: the gate can be open while capture never started — denied
            // permission, or an engine that failed to open — and showing yourself speaking then
            // would be a lie. PR 2 adds mute to this condition.
            val me = (status as? ConnectionStatus.Connected)
                ?.sessionId
                ?.takeIf { tx && f.microphoneGranted == true }
            f.copy(
                status = status, rttMs = c.rttMs,
                channelTree = c.channelTree, messages = c.messages,
                speakingSessions = if (me != null) speaking + me else speaking,
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
    fun onMicrophoneReady() = connection.startCapture()

    /**
     * Seam for [CallControls]: press and release open and close the transmit gate. Kept in
     * [transmitting], not just forwarded, so it can be merged into speakingSessions above — our
     * own session never otherwise appears there.
     */
    fun onTransmitting(active: Boolean) {
        transmitting.value = active
        connection.setTransmitting(active)
    }
}
