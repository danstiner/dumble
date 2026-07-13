package me.danielstiner.dumble.ui

import me.danielstiner.dumble.data.FakeServerConfigStore
import me.danielstiner.dumble.data.SavedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectViewModelTest {
    @Test fun prefillsFromStore() {
        val vm = ConnectViewModel(FakeServerConfigStore(SavedServer("h", 5000, "u")))
        val f = vm.form.value
        assertEquals("h", f.host)
        assertEquals("5000", f.port)
        assertEquals("u", f.username)
        assertEquals("", f.password) // password never pre-filled
    }

    @Test fun canConnectReflectsValidation() {
        val vm = ConnectViewModel(FakeServerConfigStore())
        assertFalse(vm.canConnect()) // empty host/username
        vm.update { it.copy(host = "h", username = "u") }
        assertTrue(vm.canConnect())
    }

    @Test fun persistAndBuildSavesAndReturnsConfig() {
        val store = FakeServerConfigStore()
        val vm = ConnectViewModel(store)
        vm.update { it.copy(host = " h ", port = "64738", username = " u ", password = "pw") }
        val config = vm.persistAndBuild()
        assertEquals("h", config.host)
        assertEquals("pw", config.password)
        assertEquals(SavedServer("h", 64738, "u"), store.load()) // password not saved
    }
}
