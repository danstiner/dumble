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
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

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

    @Test fun unreadIncrementsWhileChatClosed() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.messages.value = listOf(
            ChatMessage.Remote(1, null, "hi", Instant.EPOCH),
            ChatMessage.Remote(1, null, "there", Instant.EPOCH),
        )
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
        assertEquals(false, vm.uiState.value.showChat)
    }

    @Test fun warmConnectionDoesNotCountPreexistingAsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        conn.messages.value = listOf(ChatMessage.Remote(1, null, "old", Instant.EPOCH))
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun openChatResetsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        vm.openChat()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
        assertEquals(true, vm.uiState.value.showChat)
    }

    @Test fun messagesWhileOpenDoNotCount() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.openChat()
        advanceUntilIdle()
        conn.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun logShrinkResetsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.messages.value = listOf(
            ChatMessage.Remote(1, null, "hi", Instant.EPOCH),
            ChatMessage.Remote(1, null, "there", Instant.EPOCH),
        )
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
        conn.messages.value = emptyList()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    // The case that broke the counter approach: read N, reconnect to another server, receive N more.
    // A count/watermark would collide (new count lands back at the old read pointer); the read marker
    // is a prior-session instance absent from the new list, so all the new messages count.
    @Test fun reconnectToAnotherServerCountsTheNewMessagesAsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.messages.value = List(5) { ChatMessage.Remote(1, null, "a$it", Instant.EPOCH) }
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // read all 5 → marker = the 5th "a"
        vm.closeChat(); advanceUntilIdle()
        // Reconnect: log cleared then refilled with 5 brand-new instances (the empty emission may be
        // conflated away — doesn't matter, the old marker isn't in the new list either way).
        conn.messages.value = emptyList()
        conn.messages.value = List(5) { ChatMessage.Remote(9, null, "b$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.unread)
    }

    // The everyday case: read up to a point, more arrive while closed, marker still present → only the
    // tail past the marker counts (idx in the middle, not -1 and not the last element).
    @Test fun unreadCountsOnlyMessagesAfterTheReadMarker() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        val read = List(3) { ChatMessage.Remote(1, null, "a$it", Instant.EPOCH) }
        conn.messages.value = read
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // marker = read[2]
        vm.closeChat(); advanceUntilIdle()
        // Two more; the marker instance is still present (appended, not replaced).
        conn.messages.value = read + List(2) { ChatMessage.Remote(1, null, "b$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
    }

    // The original bug: once the log caps, the read message scrolls off, but new arrivals must still
    // count. Identity-miss → everything visible is unread.
    @Test fun unreadCountsNewMessagesAfterTheMarkerScrollsOff() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.messages.value = List(3) { ChatMessage.Remote(1, null, "seen$it", Instant.EPOCH) }
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // marker = the last "seen"
        vm.closeChat(); advanceUntilIdle()
        // Window slid past the marker — none of the read instances remain.
        conn.messages.value = List(4) { ChatMessage.Remote(1, null, "new$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.unread)
    }

    @Test fun sendMessageClearsDraftOnSuccess() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.sendResult = true
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals(listOf("hi"), conn.sentTexts)
        assertEquals("", vm.uiState.value.chatDraft)
    }

    @Test fun sendMessageTrimsAndEscapesBeforeSending() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.sendResult = true
        vm.onChatDraftChange("  a<b & \"c\"  ")
        vm.sendMessage()
        advanceUntilIdle()
        // Input shaping lives here now — the connection sends the body verbatim. io.ktor.util.escapeHTML
        // emits &quot;/&#x27; for quotes; both round-trip through Html.fromHtml at render.
        assertEquals(listOf("a&lt;b &amp; &quot;c&quot;"), conn.sentTexts)
    }

    @Test fun blankMessageIsNotSent() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onChatDraftChange("   ")
        vm.sendMessage()
        advanceUntilIdle()
        assertTrue(conn.sentTexts.isEmpty())
    }

    @Test fun sendMessageKeepsDraftOnFailure() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        conn.sendResult = false
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals("hi", vm.uiState.value.chatDraft)
    }

    @Test fun settingsAndAboutNavigateAsNestedRoutes() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeConnection(), FakeConfigStore(null))
        advanceUntilIdle()
        assertEquals(Route.Main, vm.uiState.value.route)
        vm.openSettings(); advanceUntilIdle()
        assertEquals(Route.Settings, vm.uiState.value.route)
        vm.openAbout(); advanceUntilIdle()
        assertEquals(Route.About, vm.uiState.value.route)
        // About is nested under Settings, so back lands there rather than on the form.
        vm.back(); advanceUntilIdle()
        assertEquals(Route.Settings, vm.uiState.value.route)
        vm.back(); advanceUntilIdle()
        assertEquals(Route.Main, vm.uiState.value.route)
    }

    /** Settings overlays the session, so connecting must not yank the user out of it. */
    @Test fun connectingLeavesAnOpenRouteAlone() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        advanceUntilIdle()
        vm.openSettings(); advanceUntilIdle()
        conn.status.value = ConnectionStatus.Connected(sessionId = 1)
        advanceUntilIdle()
        assertEquals(Route.Settings, vm.uiState.value.route)
        // Backing out lands on Main, which now renders the connected screen rather than the form.
        vm.back(); advanceUntilIdle()
        assertEquals(Route.Main, vm.uiState.value.route)
    }

    @Test fun speakingSessionsReachTheUiState() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        advanceUntilIdle()
        conn.speakingSessions.value = setOf(4, 5)
        advanceUntilIdle()
        assertEquals(setOf(4, 5), vm.uiState.value.speakingSessions)
    }

    @Test fun microphoneGrantedStartsUnknown() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeConnection(), FakeConfigStore(null))
        advanceUntilIdle()
        assertNull(vm.uiState.value.microphoneGranted)
    }

    @Test fun microphonePermissionResultRecordsGrant() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(true)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.microphoneGranted)
        // Recording the answer is not what starts capture: the answer outlives the connection it
        // was given for, so every connection starts its own session via onMicrophoneReady.
        assertEquals(0, conn.startCaptureCalls)
    }

    @Test fun microphonePermissionResultRecordsDenial() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(false)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.microphoneGranted)
        assertEquals(0, conn.startCaptureCalls)
    }

    /**
     * The defect this pins: the permission answer lives in the ViewModel and outlives the
     * connection, so a second connection in the same process is told nothing by the permission
     * callback. Every entry to the connected screen with the microphone granted has to start a
     * session, and startCapture is idempotent so repeats are free.
     */
    @Test fun everyConnectedScreenEntryStartsItsOwnCaptureSession() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(true)
        vm.onMicrophoneReady()
        vm.onMicrophoneReady()
        advanceUntilIdle()
        assertEquals(2, conn.startCaptureCalls)
    }

    @Test fun onTransmittingDrivesTheConnectionGate() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null))
        vm.onTransmitting(true)
        vm.onTransmitting(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), conn.transmitting)
    }
}
