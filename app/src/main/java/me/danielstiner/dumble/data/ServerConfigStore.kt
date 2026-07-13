package me.danielstiner.dumble.data

import android.content.Context

/** Last-used server details. The password is intentionally NOT part of this model — it is never persisted. */
data class SavedServer(
    val host: String = "",
    val port: Int = 64738,
    val username: String = "",
)

interface ServerConfigStore {
    fun load(): SavedServer
    fun save(host: String, port: Int, username: String)
}

class SharedPrefsServerConfigStore(context: Context) : ServerConfigStore {
    private val prefs = context.getSharedPreferences("dumble_server", Context.MODE_PRIVATE)

    override fun load(): SavedServer = SavedServer(
        host = prefs.getString(KEY_HOST, "") ?: "",
        port = prefs.getInt(KEY_PORT, 64738),
        username = prefs.getString(KEY_USERNAME, "") ?: "",
    )

    override fun save(host: String, port: Int, username: String) {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
    }
}
