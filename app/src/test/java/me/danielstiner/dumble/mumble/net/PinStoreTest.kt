package me.danielstiner.dumble.mumble.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.danielstiner.dumble.data.PinDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PinStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun inMemoryStoreRoundTrips() = runTest {
        val store = InMemoryPinStore()
        store.put("example.com:64738", "abcd")

        assertEquals("abcd", store.get("example.com:64738"))
    }

    @Test
    fun unknownKeyReturnsNull() = runTest {
        assertNull(InMemoryPinStore().get("nope:64738"))
    }

    // runBlocking, not runTest: this test does real file I/O and must fully tear the first
    // DataStore down before opening the second. DataStore forbids two live instances over one
    // file, so the writer's scope is cancel-and-joined first — the same release that happens at
    // process exit, which is exactly the persistence path this test is meant to prove.
    @Test
    fun pinSurvivesANewStoreInstance() = runBlocking {
        val file = tmp.newFile("pins.preferences_pb").also { it.delete() }

        val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PinDataStore(PreferenceDataStoreFactory.create(scope = writerScope) { file })
            .put("example.com:64738", "deadbeef")
        writerScope.coroutineContext[Job]!!.cancelAndJoin()

        val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = PinDataStore(PreferenceDataStoreFactory.create(scope = readerScope) { file })
            assertEquals("deadbeef", reopened.get("example.com:64738"))
        } finally {
            readerScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun removeReturnsTheEndpointToFirstContact() = runTest {
        val store = InMemoryPinStore()
        store.put("example.com:64738", "abcd")

        store.remove("example.com:64738")

        assertNull(store.get("example.com:64738"))
    }

    @Test
    fun removingAnUnpinnedEndpointIsHarmless() = runTest {
        InMemoryPinStore().remove("never-pinned:64738")
    }

    // The removal has to reach the file, not just the in-memory snapshot: a user who clears a pin
    // and restarts must land back at first contact rather than the mismatch they were escaping.
    @Test
    fun removalSurvivesANewStoreInstance() = runBlocking {
        val file = tmp.newFile("pins.preferences_pb").also { it.delete() }

        val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PinDataStore(PreferenceDataStoreFactory.create(scope = writerScope) { file }).apply {
            put("example.com:64738", "deadbeef")
            put("other.example:64738", "feedface")
            remove("example.com:64738")
        }
        writerScope.coroutineContext[Job]!!.cancelAndJoin()

        val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = PinDataStore(PreferenceDataStoreFactory.create(scope = readerScope) { file })
            assertNull(reopened.get("example.com:64738"))
            // The neighbouring pin proves removal was targeted, not a wipe of the whole store.
            assertEquals("feedface", reopened.get("other.example:64738"))
        } finally {
            readerScope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
}
