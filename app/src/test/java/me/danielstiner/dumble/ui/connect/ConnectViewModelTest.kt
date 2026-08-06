package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.danielstiner.dumble.data.ServerProfile
import me.danielstiner.dumble.mumble.channeltree.Channel
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.channeltree.User
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // JUnit builds a fresh instance per test, so this starts at 0 for each. Controllable rather
    // than real: the reconnect assertions below compare two anchors, which a real clock can hand
    // back identical when both stamps land in the same millisecond.
    private val clock = FakeClock()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun prefillsDraftFromLastUsed() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeConnection(), FakeConfigStore(ServerProfile("saved.example", 64738, "bob")), clock)
        advanceUntilIdle()
        assertEquals("saved.example", vm.uiState.value.draft.host)
        assertEquals("bob", vm.uiState.value.draft.username)
    }

    @Test fun invalidPortBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("abc")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.portError)
        assertEquals(0, conn.connectCalls)
    }

    @Test fun invalidHostBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onHostChange("voice.example.com:64738"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.hostError)
        assertEquals(0, conn.connectCalls)
    }

    @Test fun validConnectDispatchesAndSaves() = runTest(dispatcher) {
        val conn = FakeConnection()
        val store = FakeConfigStore(null)
        val vm = ConnectViewModel(conn, store, clock)
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNull(vm.uiState.value.portError)
        assertEquals(1, conn.connectCalls)
        assertEquals(ServerProfile("h", 64738, "u"), store.saved)
    }

    @Test fun channelTreeFromConnectionAppearsInUiState() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.channelTree.value = ChannelTree(channels = mapOf(0 to Channel(0, null, "Root", 0)))
        advanceUntilIdle()
        assertEquals("Root", vm.uiState.value.channelTree.channels[0]?.name)
    }

    @Test fun unreadIncrementsWhileChatClosed() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun openChatResetsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        vm.openChat()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
        assertEquals(true, vm.uiState.value.showChat)
    }

    @Test fun messagesWhileOpenDoNotCount() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.openChat()
        advanceUntilIdle()
        conn.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun logShrinkResetsUnread() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.sendResult = true
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals(listOf("hi"), conn.sentTexts)
        assertEquals("", vm.uiState.value.chatDraft)
    }

    @Test fun sendMessageTrimsAndEscapesBeforeSending() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onChatDraftChange("   ")
        vm.sendMessage()
        advanceUntilIdle()
        assertTrue(conn.sentTexts.isEmpty())
    }

    @Test fun sendMessageKeepsDraftOnFailure() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.sendResult = false
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals("hi", vm.uiState.value.chatDraft)
    }

    @Test fun settingsAndAboutNavigateAsNestedRoutes() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeConnection(), FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
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
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        advanceUntilIdle()
        conn.speakingSessions.value = setOf(4, 5)
        advanceUntilIdle()
        assertEquals(setOf(4, 5), vm.uiState.value.speakingSessions)
    }

    @Test fun microphoneGrantedStartsFromTheSystemNotFromADialog() = runTest(dispatcher) {
        // Regression: the state used to start null and only a dialog could fill it in. The
        // foreground service keeps the process alive after the task is swiped away, so resuming
        // from the notification builds a fresh ViewModel over a live session — and a null there
        // disabled Talk for the rest of it with no way back.
        val held = ConnectViewModel(FakeConnection(), FakeConfigStore(null), clock) { true }
        val notHeld = ConnectViewModel(FakeConnection(), FakeConfigStore(null), clock) { false }
        advanceUntilIdle()
        assertEquals(true, held.uiState.value.microphoneGranted)
        assertEquals(false, notHeld.uiState.value.microphoneGranted)
    }

    @Test fun microphonePermissionResultRecordsGrant() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onMicrophonePermissionResult(true)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.microphoneGranted)
        // Recording the answer is not what starts capture: the answer outlives the connection it
        // was given for, so every connection starts its own session via onMicrophoneReady.
        assertEquals(0, conn.requestCaptureCalls)
    }

    @Test fun microphonePermissionResultRecordsDenial() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onMicrophonePermissionResult(false)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.microphoneGranted)
        assertEquals(0, conn.requestCaptureCalls)
    }

    /**
     * The defect this pins: the permission answer lives in the ViewModel and outlives the
     * connection, so a second connection in the same process is told nothing by the permission
     * callback. Every entry to the connected screen with the microphone granted has to start a
     * session, and requestCapture is idempotent so repeats are free.
     */
    @Test fun everyConnectedScreenEntryStartsItsOwnCaptureSession() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onMicrophonePermissionResult(true)
        vm.onMicrophoneReady()
        vm.onMicrophoneReady()
        advanceUntilIdle()
        assertEquals(2, conn.requestCaptureCalls)
    }

    @Test fun onTransmittingDrivesTheConnectionGate() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onTransmitting(true)
        vm.onTransmitting(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), conn.transmitting)
    }

    /**
     * The press asks for capture too, but that pairing belongs to the connection and is pinned
     * there — CaptureLifecycleTest.aTalkPressAloneRebuildsAndTransmits. All the ViewModel owes is
     * forwarding the edge, which onTransmittingDrivesTheConnectionGate above covers.
     */
    @Test fun onTransmittingDoesNotAskForCaptureItself() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        vm.onTransmitting(true)
        vm.onTransmitting(false)
        advanceUntilIdle()
        assertEquals(0, conn.requestCaptureCalls)
    }

    /**
     * speakingSessions is populated from decoded *incoming* audio, and our own audio is never
     * decoded locally — so without this merge your row can never light up while you talk.
     */
    @Test fun holdingTalkMarksYourOwnRowSpeaking() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()

        vm.onTransmitting(true)
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        vm.onTransmitting(false)
        runCurrent()
        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /** `selfMute` tracks `selfDeaf` because murmur sets both — see [selfMutedAloneIsNotDeafened]. */
    private fun user(
        session: Int,
        selfDeaf: Boolean = false,
        mute: Boolean = false,
        selfMute: Boolean = selfDeaf,
    ) = User(
        session = session, name = "u$session", channelId = 0,
        mute = mute, deaf = false, selfMute = selfMute, selfDeaf = selfDeaf, suppress = false,
    )

    private fun treeWith(vararg users: User) = ChannelTree(
        channels = mapOf(0 to Channel(0, null, "Root", 0)),
        users = users.associateBy { it.session },
    )

    /**
     * Deafen is the server's answer, read off our own row, not a local flag set by the tap. The tap
     * only sends; an admin or another client moving it is picked up for free.
     */
    @Test fun deafenedReflectsTheServerNotTheTap() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        conn.channelTree.value = treeWith(user(7))
        runCurrent()

        vm.onToggleDeafen()
        runCurrent()
        assertEquals(listOf(true), conn.selfDeaf)
        assertFalse("the tap alone must not move the button", vm.uiState.value.deafened)

        conn.channelTree.value = treeWith(user(7, selfDeaf = true))
        runCurrent()
        assertTrue(vm.uiState.value.deafened)

        // And the toggle now reads the other way, because it reads what the button shows.
        vm.onToggleDeafen()
        runCurrent()
        assertEquals(listOf(true, false), conn.selfDeaf)
    }

    /**
     * Every other case here carries `self_mute` alongside `self_deaf`, because murmur sets both — so
     * they cannot tell `deafened` reading the wrong one of the two apart from it reading the right
     * one. Confirmed by mutation: `deafened = me?.selfMute` passed this whole class without this.
     * Reachable now from another client, and from this app once a mute control lands.
     */
    @Test fun selfMutedAloneIsNotDeafened() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        conn.channelTree.value = treeWith(user(7, selfDeaf = false, selfMute = true))
        runCurrent()

        assertFalse(vm.uiState.value.deafened)
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)
    }

    @Test fun anotherUsersDeafenIsNotOurs() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        // Granted, so a non-null talkBlock below can only have come from user 9's row.
        vm.onMicrophonePermissionResult(granted = true)
        conn.channelTree.value = treeWith(user(7), user(9, selfDeaf = true))
        runCurrent()

        assertFalse(vm.uiState.value.deafened)
        assertNull(vm.uiState.value.talkBlock)
    }

    @Test fun talkBlockReachesTheUiState() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        conn.channelTree.value = treeWith(user(7, selfDeaf = true))
        runCurrent()

        assertEquals(TalkBlock.DEAFENED, vm.uiState.value.talkBlock)

        conn.channelTree.value = treeWith(user(7, mute = true))
        runCurrent()
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)
    }

    /**
     * The gate can be open while nothing we send is carried — a press already in flight when the
     * deafen echo lands is the reachable case. Showing our own row speaking then is a lie.
     */
    @Test fun aBlockedTalkNeverMarksYouSpeaking() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        conn.channelTree.value = treeWith(user(7))
        runCurrent()

        vm.onTransmitting(true)
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        conn.channelTree.value = treeWith(user(7, selfDeaf = true))
        runCurrent()
        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /** The gate can be open while capture never started — showing yourself speaking would be a lie. */
    @Test fun deniedMicrophoneNeverMarksYouSpeaking() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = false)
        vm.onTransmitting(true)
        runCurrent()

        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /**
     * A drop mid-press disposes the call screen, and the composition is gone before `clickable`
     * emits its Cancel — so the button's release never reaches the gate. Left open, it marks our
     * own row speaking for the whole of the next call.
     */
    @Test fun aDropMidPressDoesNotLeaveTheNextSessionSpeaking() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()
        vm.onTransmitting(true)
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        conn.status.value = ConnectionStatus.Error(ErrorKind.DISCONNECTED, null)
        runCurrent()
        conn.emitConnected(sessionId = 8)
        runCurrent()

        assertFalse(8 in vm.uiState.value.speakingSessions)
    }

    /** status is a StateFlow, so the disconnect between two sessions can be conflated away. */
    @Test fun aConflatedReconnectAlsoClosesTheGate() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()
        vm.onTransmitting(true)
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        conn.emitConnected(sessionId = 8)
        runCurrent()

        assertFalse(8 in vm.uiState.value.speakingSessions)
    }

    @Test fun aConflatedReconnectRestartsTheCallTimer() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        runCurrent()
        val first = vm.uiState.value.connectedSinceMillis
        assertNotNull(first)

        // The clock has to move for a re-stamp to be distinguishable from keeping the old anchor.
        clock.advance(5)
        conn.emitConnected(sessionId = 8)
        runCurrent()

        assertNotEquals(first, vm.uiState.value.connectedSinceMillis)
    }

    /**
     * Pins the anchor to the injected monotonic clock. Wall clock is what this must never be:
     * an NTP correction or a user clock change mid-call moves `currentTimeMillis` — backwards,
     * too — and the displayed duration would follow it.
     */
    @Test fun theCallAnchorIsReadFromTheMonotonicClock() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        clock.advance(9_000)
        conn.emitConnected(sessionId = 7)
        runCurrent()

        assertEquals(9_000L, vm.uiState.value.connectedSinceMillis)
    }

    @Test fun disconnectClearsTheCallTimerAnchor() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        runCurrent()
        assertNotNull(vm.uiState.value.connectedSinceMillis)

        conn.status.value = ConnectionStatus.Error(ErrorKind.DISCONNECTED, null)
        runCurrent()

        assertNull(vm.uiState.value.connectedSinceMillis)
    }

    /** Proves the merge is a union: your own session must not crowd out or replace anyone else's. */
    @Test fun otherSpeakersAreUnaffected() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        conn.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()

        vm.onTransmitting(true)
        conn.emitSpeaking(setOf(9))
        runCurrent()

        assertEquals(setOf(7, 9), vm.uiState.value.speakingSessions)
    }

    @Test fun routesReachTheUiState() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        val speaker = AudioRoute("id-speaker", AudioRoute.Type.SPEAKER)
        conn.audioRoutes.value = AudioRoutes(listOf(speaker), speaker)
        runCurrent()

        assertEquals(listOf(speaker), vm.uiState.value.audioRoutes.available)
        assertEquals(speaker, vm.uiState.value.audioRoutes.current)
    }

    /** Fire-and-forget, like deafen: the id goes down, the answer comes back through the flow. */
    @Test fun pickingARouteForwardsItsId() = runTest(dispatcher) {
        val conn = FakeConnection()
        val vm = ConnectViewModel(conn, FakeConfigStore(null), clock)

        vm.onSelectRoute("id-bluetooth")

        assertEquals(listOf("id-bluetooth"), conn.routeRequests)
    }
}
