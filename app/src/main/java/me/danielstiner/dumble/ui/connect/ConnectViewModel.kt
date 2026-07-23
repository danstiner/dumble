package me.danielstiner.dumble.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.data.ServerProfile
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
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
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connection: Connection,
    private val configStore: ServerConfigStore,
) : ViewModel() {

    private val form = MutableStateFlow(ConnectUiState())

    val uiState: StateFlow<ConnectUiState> =
        combine(form, connection.status, connection.serverVersion, connection.roundTripMillis, connection.channelTree) { f, status, ver, rtt, tree ->
            f.copy(status = status, serverVersion = ver, rttMs = rtt, channelTree = tree)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectUiState())

    init {
        viewModelScope.launch {
            configStore.lastUsed()?.let { p ->
                form.value = form.value.copy(draft = p, portText = p.port.toString())
            }
        }
    }

    fun onHostChange(v: String) { form.value = form.value.copy(draft = form.value.draft.copy(host = v), hostError = null) }
    fun onUsernameChange(v: String) { form.value = form.value.copy(draft = form.value.draft.copy(username = v)) }
    fun onPasswordChange(v: String) { form.value = form.value.copy(password = v) }
    fun onPortChange(v: String) { form.value = form.value.copy(portText = v, portError = null) }

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
