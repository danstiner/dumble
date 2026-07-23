package me.danielstiner.dumble.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import me.danielstiner.dumble.mumble.net.PinStore
import javax.inject.Inject

class PinDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PinStore {

    override suspend fun get(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun put(key: String, fingerprint: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = fingerprint }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }
}
