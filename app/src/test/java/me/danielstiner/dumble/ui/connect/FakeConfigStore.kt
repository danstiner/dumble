package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.data.ServerConfigStore
import me.danielstiner.dumble.data.ServerProfile
import me.danielstiner.dumble.mumble.voice.TransmitMode

class FakeConfigStore(
    private val initial: ServerProfile?,
    private val initialMode: TransmitMode = TransmitMode.PushToTalk,
) : ServerConfigStore {
    var saved: ServerProfile? = null; private set
    var savedMode: TransmitMode? = null; private set
    override suspend fun lastUsed(): ServerProfile? = initial
    override suspend fun saveLastUsed(profile: ServerProfile) { saved = profile }
    override suspend fun transmitMode(): TransmitMode = initialMode
    override suspend fun saveTransmitMode(mode: TransmitMode) { savedMode = mode }
}
