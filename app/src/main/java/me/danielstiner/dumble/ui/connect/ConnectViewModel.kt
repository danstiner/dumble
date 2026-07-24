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
import me.danielstiner.dumble.mumble.protocol.ServerVersion
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

data class ConnectUiState(
    val draft: ServerProfile = ServerProfile("", MumbleEndpoint.DEFAULT_PORT, ""),
    val portText: String = "",
    val password: String = "",
    val portError: String? = null,
    val hostError: String? = null,
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val serverVersion: ServerVersion? = null,
    val rttMs: Double? = null,
    val channelTree: ChannelTree = ChannelTree(),
    val messages: List<ChatMessage> = emptyList(),
    val showChat: Boolean = false,
    val unread: Int = 0,
    val chatDraft: String = "",
)

private data class ConnSnapshot(
    val status: ConnectionStatus,
    val serverVersion: ServerVersion?,
    val rttMs: Double?,
    val channelTree: ChannelTree,
    val messages: List<ChatMessage>,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connection: Connection,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val form = MutableStateFlow(ConnectUiState())

    // Kotlin's typed combine() maxes at 5 flows; nest the 5 connection flows into one
    // snapshot so the top-level combine only needs form + snapshot.
    private val connSnapshot = combine(
        connection.status, connection.serverVersion, connection.roundTripMillis,
        connection.channelTree, connection.messages,
    ) { status, ver, rtt, tree, msgs -> ConnSnapshot(status, ver, rtt, tree, msgs) }

    val uiState: StateFlow<ConnectUiState> =
        combine(form, connSnapshot) { f, c ->
            f.copy(
                status = c.status, serverVersion = c.serverVersion, rttMs = c.rttMs,
                channelTree = c.channelTree, messages = c.messages,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectUiState())

    // The last message the user has read — the newest one present when chat was last open (null = read
    // nothing yet). Anchored to the message *instance*, not an index or count: a capped, conflated, or
    // reconnected list can't fool identity. A message dropped past MAX_MESSAGES, or one from a prior
    // session, simply isn't found, so unread falls back to "everything visible" (≤ MAX_MESSAGES). This
    // relies on the log being append-only, so instances stay stable across emissions — see
    // SessionStateMachine.appendMessage.
    private var lastReadMarker: ChatMessage? = connection.messages.value.lastOrNull()

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
}
