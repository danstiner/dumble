package me.danielstiner.dumble.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigStoreTest {
    @Test fun defaultsWhenEmpty() {
        assertEquals(SavedServer("", 64738, ""), FakeServerConfigStore().load())
    }

    @Test fun roundTripsSavedValues() {
        val store = FakeServerConfigStore()
        store.save("mumble.example.com", 64738, "danielstiner")
        assertEquals(SavedServer("mumble.example.com", 64738, "danielstiner"), store.load())
    }
}
