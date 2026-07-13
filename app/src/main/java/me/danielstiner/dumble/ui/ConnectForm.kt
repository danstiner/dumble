package me.danielstiner.dumble.ui

import me.danielstiner.dumble.mumble.MumbleServerConfig

/** Raw text form state. `port` is a String because it is bound to a text field. */
data class ConnectForm(
    val host: String = "",
    val port: String = "64738",
    val username: String = "",
    val password: String = "",
)

/** Per-field error messages; null means the field is valid. */
data class FieldErrors(
    val host: String? = null,
    val port: String? = null,
    val username: String? = null,
) {
    val isValid: Boolean get() = host == null && port == null && username == null
}

fun validate(form: ConnectForm): FieldErrors {
    val portInt = form.port.trim().toIntOrNull()
    return FieldErrors(
        host = if (form.host.isBlank()) "Host required" else null,
        port = if (portInt != null && portInt in 1..65535) null else "Port 1-65535",
        username = if (form.username.isBlank()) "Username required" else null,
    )
}

/** Build a config from a form assumed valid (blank password → null). */
fun ConnectForm.toConfig(): MumbleServerConfig = MumbleServerConfig(
    host = host.trim(),
    port = port.trim().toInt(),
    username = username.trim(),
    password = password.ifBlank { null },
)
