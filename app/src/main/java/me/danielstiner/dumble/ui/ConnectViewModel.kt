package me.danielstiner.dumble.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.mumble.MumbleServerConfig

class ConnectViewModel(private val store: ServerConfigStore) : ViewModel() {
    private val _form = MutableStateFlow(
        store.load().let { ConnectForm(host = it.host, port = it.port.toString(), username = it.username) }
    )
    val form: StateFlow<ConnectForm> = _form.asStateFlow()

    fun update(transform: (ConnectForm) -> ConnectForm) { _form.value = transform(_form.value) }

    fun errors(): FieldErrors = validate(_form.value)
    fun canConnect(): Boolean = errors().isValid

    /** Persist the non-secret fields and return the config to connect with. Call only when [canConnect]. */
    fun persistAndBuild(): MumbleServerConfig {
        val f = _form.value
        store.save(f.host.trim(), f.port.trim().toInt(), f.username.trim())
        return f.toConfig()
    }
}
