package me.danielstiner.dumble.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerConfigStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun freshStoreHasNoLastUsed() = runBlocking {
        withStore { store -> assertNull(store.lastUsed()) }
    }

    @Test fun lastUsedRoundTripsAcrossInstances() {
        val file = tmp.newFile("server_config.preferences_pb").also { it.delete() }
        val profile = ServerProfile("Voice.Example.com", 64738, "alice")

        val writer = CoroutineScope(Dispatchers.IO + SupervisorJob())
        runBlocking {
            ServerConfigDataStore(PreferenceDataStoreFactory.create(scope = writer) { file }).saveLastUsed(profile)
        }
        runBlocking { writer.coroutineContext[Job]!!.cancelAndJoin() }

        val reader = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            runBlocking {
                val got = ServerConfigDataStore(PreferenceDataStoreFactory.create(scope = reader) { file }).lastUsed()
                assertEquals(profile, got)
            }
        } finally {
            runBlocking { reader.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    private inline fun withStore(block: (ServerConfigStore) -> Unit) {
        val file = tmp.newFile("server_config.preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            block(ServerConfigDataStore(PreferenceDataStoreFactory.create(scope = scope) { file }))
        } finally {
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
