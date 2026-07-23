package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.data.ServerProfile

class FakeConfigStore(private val initial: ServerProfile?) : ServerConfigStore {
    var saved: ServerProfile? = null; private set
    override suspend fun lastUsed(): ServerProfile? = initial
    override suspend fun saveLastUsed(profile: ServerProfile) { saved = profile }
}
