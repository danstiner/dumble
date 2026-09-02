package me.danielstiner.dumble.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.TransmitMode

/** A saved server. Identity is its canonical [endpoint]; the same type will back favorites later. */
data class ServerProfile(val host: String, val port: Int, val username: String) {
    val endpoint: MumbleEndpoint get() = MumbleEndpoint.parse(host, port)
}

/** The last connection, so the form can prefill, and the transmit mode. Never stores the
 *  password. */
interface ServerConfigStore {
    suspend fun lastUsed(): ServerProfile?
    suspend fun saveLastUsed(profile: ServerProfile)

    /** Push-to-talk unless stored otherwise — the mode that cannot transmit by surprise. */
    suspend fun transmitMode(): TransmitMode
    suspend fun saveTransmitMode(mode: TransmitMode)
}

class ServerConfigDataStore(private val dataStore: DataStore<Preferences>) : ServerConfigStore {
    override suspend fun lastUsed(): ServerProfile? {
        val prefs = dataStore.data.first()
        val host = prefs[HOST] ?: return null
        val port = prefs[PORT] ?: return null
        val user = prefs[USERNAME] ?: return null
        return ServerProfile(host, port, user)
    }

    override suspend fun saveLastUsed(profile: ServerProfile) {
        dataStore.edit {
            it[HOST] = profile.host
            it[PORT] = profile.port
            it[USERNAME] = profile.username
        }
    }

    override suspend fun transmitMode(): TransmitMode {
        // By name, not ordinal: an ordinal silently becomes a different mode when the enum grows.
        val name = dataStore.data.first()[TRANSMIT_MODE]
        return TransmitMode.entries.firstOrNull { it.name == name } ?: TransmitMode.VoiceActivity
    }

    override suspend fun saveTransmitMode(mode: TransmitMode) {
        dataStore.edit { it[TRANSMIT_MODE] = mode.name }
    }

    private companion object {
        val HOST = stringPreferencesKey("last_host")
        val PORT = intPreferencesKey("last_port")
        val USERNAME = stringPreferencesKey("last_username")
        val TRANSMIT_MODE = stringPreferencesKey("transmit_mode")
    }
}
