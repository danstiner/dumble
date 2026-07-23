package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.danielstiner.dumble.data.ServerProfile
import me.danielstiner.dumble.mumble.channeltree.Channel
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun prefillsDraftFromLastUsed() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeConnection(), FakeConfigStore(ServerProfile("saved.example", 64738, "bob")))
        advanceUntilIdle()
        assertEquals("saved.example", vm.uiState.value.draft.host)
        assertEquals("bob", vm.uiState.value.draft.username)
    }

    @Test fun invalidPortBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("abc")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.portError)
        assertEquals(0, conn.connectCalls)
    }

    @Test fun invalidHostBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onHostChange("voice.example.com:64738"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.hostError)
        assertEquals(0, conn.connectCalls)
    }

    @Test fun validConnectDispatchesAndSaves() = runTest(dispatcher) {
        val conn = FakeConnection()
        val store = FakeConfigStore(null)
        val vm = ConnectViewModel(conn, store)
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNull(vm.uiState.value.portError)
        assertEquals(1, conn.connectCalls)
        assertEquals(ServerProfile("h", 64738, "u"), store.saved)
    }

    @Test fun channelTreeFromConnectionAppearsInUiState() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.channelTree.value = ChannelTree(channels = mapOf(0 to Channel(0, null, "Root", 0)))
        advanceUntilIdle()
        assertEquals("Root", vm.uiState.value.channelTree.channels[0]?.name)
    }
}
