package me.danielstiner.dumble.ui.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.session.ErrorKind
import me.danielstiner.dumble.session.SessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // JUnit builds a fresh instance per test, so this starts at 0 for each. Controllable rather
    // than real: the timer test compares two marks, which a real clock can hand back identical.
    private val clock = TestTimeSource()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun prefillsDraftFromLastUsed() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeAppState(), FakeConfigStore(ServerProfile("saved.example", 64738, "bob")))
        advanceUntilIdle()
        assertEquals("saved.example", vm.uiState.value.draft.host)
        assertEquals("bob", vm.uiState.value.draft.username)
    }

    @Test fun theVoicePathReachesUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.voicePath.value = VoicePath.State(onUdp = true, roundTrip = 7.milliseconds)
        advanceUntilIdle()
        assertEquals(VoicePath.State(onUdp = true, roundTrip = 7.milliseconds), vm.uiState.value.voicePath)
    }

    @Test fun ourCaptureCountersReachUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        val counters = CaptureStats(
            encodedPackets = 3, encodeErrors = 0, encodeMicrosMean = 400, encodeMicrosMax = 900,
            ringOverruns = 0, skippedSamples = 0, streamOverruns = 0, framesPerBurst = 96,
            droppedFrames = 0, inputLatencyMillis = 12.0,
        )
        app.captureStats.value = counters
        advanceUntilIdle()
        assertEquals(counters, vm.uiState.value.captureStats)
    }

    @Test fun invalidPortBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("abc")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.portError)
        assertEquals(0, app.connectCalls)
    }

    @Test fun invalidHostBlocksDispatchAndSetsError() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("voice.example.com:64738"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.hostError)
        assertEquals(0, app.connectCalls)
    }

    @Test fun validConnectDispatchesAndSaves() = runTest(dispatcher) {
        val app = FakeAppState()
        val store = FakeConfigStore(null)
        val vm = ConnectViewModel(app, store)
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        vm.onConnect()
        advanceUntilIdle()
        assertNull(vm.uiState.value.portError)
        assertEquals(1, app.connectCalls)
        assertEquals(ServerProfile("h", 64738, "u"), store.saved)
    }

    @Test fun anyEditRetiresARejectionOfTheFormUntilTheNextConnect() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        val edits = listOf({ vm.onHostChange("h2") }, { vm.onPortChange("64739") }, { vm.onUsernameChange("u2") }, { vm.onPasswordChange("p") })
        for ((i, edit) in edits.withIndex()) {
            app.status.value = SessionStatus.Connecting
            vm.onConnect()
            app.status.value = SessionStatus.Error(ErrorKind.WRONG_SERVER_PASSWORD, null)
            runCurrent()
            assertFalse("connect $i", vm.uiState.value.editedSinceConnect)
            edit()
            runCurrent()
            assertTrue("edit $i", vm.uiState.value.editedSinceConnect)
        }
    }

    @Test fun anEditWhileTheConnectIsInFlightDoesNotHideItsRejection() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        app.onConnect = { app.status.value = SessionStatus.Connecting }
        vm.onConnect()
        vm.onUsernameChange("u2")
        app.status.value = SessionStatus.Error(ErrorKind.USERNAME_TAKEN, null)
        runCurrent()
        assertFalse(vm.uiState.value.editedSinceConnect)
    }

    /** The new form's password is blank, whatever the rejected connect sent. */
    @Test fun aRejectionFromBeforeTheFormOpenedIsAlreadyRetired() = runTest(dispatcher) {
        val app = FakeAppState()
        app.status.value = SessionStatus.Error(ErrorKind.WRONG_SERVER_PASSWORD, null)
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        runCurrent()
        assertTrue(vm.uiState.value.editedSinceConnect)
    }

    /** The status never stops reading blocked, as when a new block lands within one frame. */
    @Test fun localNetworkIsAskedOncePerConnect() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        app.status.value = SessionStatus.Error(ErrorKind.LOCAL_NETWORK_BLOCKED, null)
        runCurrent()
        assertTrue(vm.uiState.value.askLocalNetwork)
        vm.onLocalNetworkAsked()
        runCurrent()
        assertFalse(vm.uiState.value.askLocalNetwork)
        vm.onConnect()
        runCurrent()
        assertTrue(vm.uiState.value.askLocalNetwork)
    }

    /** On the main thread collectors can run inside the connect, so the old block must not read as unasked there. */
    @Test fun theOldBlockIsNotAskedAboutDuringAConnect() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onHostChange("h"); vm.onUsernameChange("u"); vm.onPortChange("64738")
        app.status.value = SessionStatus.Error(ErrorKind.LOCAL_NETWORK_BLOCKED, null)
        runCurrent()
        vm.onLocalNetworkAsked()
        var askedDuringConnect = false
        app.onConnect = {
            runCurrent()
            askedDuringConnect = vm.uiState.value.askLocalNetwork
        }
        vm.onConnect()
        assertFalse(askedDuringConnect)
    }

    @Test fun aBlockFromBeforeThisViewModelIsNotAskedAgain() = runTest(dispatcher) {
        val app = FakeAppState()
        app.status.value = SessionStatus.Error(ErrorKind.LOCAL_NETWORK_BLOCKED, null)
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        runCurrent()
        assertFalse(vm.uiState.value.askLocalNetwork)
    }

    @Test fun channelTreeFromConnectionAppearsInUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.channelTree.value = ChannelTree(channels = mapOf(0 to Channel(0, null, "Root", 0)))
        advanceUntilIdle()
        assertEquals("Root", vm.uiState.value.channelTree.channels[0]?.name)
    }

    @Test fun unreadIncrementsWhileChatClosed() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.messages.value = listOf(
            ChatMessage.Remote(1, null, "hi", Instant.EPOCH),
            ChatMessage.Remote(1, null, "there", Instant.EPOCH),
        )
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
        assertEquals(false, vm.uiState.value.showChat)
    }

    @Test fun warmConnectionDoesNotCountPreexistingAsUnread() = runTest(dispatcher) {
        val app = FakeAppState()
        app.messages.value = listOf(ChatMessage.Remote(1, null, "old", Instant.EPOCH))
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun openChatResetsUnread() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        vm.openChat()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
        assertEquals(true, vm.uiState.value.showChat)
    }

    @Test fun messagesWhileOpenDoNotCount() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.openChat()
        advanceUntilIdle()
        app.messages.value = listOf(ChatMessage.Remote(1, null, "hi", Instant.EPOCH))
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    @Test fun logShrinkResetsUnread() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.messages.value = listOf(
            ChatMessage.Remote(1, null, "hi", Instant.EPOCH),
            ChatMessage.Remote(1, null, "there", Instant.EPOCH),
        )
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
        app.messages.value = emptyList()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.unread)
    }

    // The case that broke the counter approach: read N, reconnect to another server, receive N more.
    // A count/watermark would collide (new count lands back at the old read pointer); the read marker
    // is a prior-session instance absent from the new list, so all the new messages count.
    @Test fun reconnectToAnotherServerCountsTheNewMessagesAsUnread() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.messages.value = List(5) { ChatMessage.Remote(1, null, "a$it", Instant.EPOCH) }
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // read all 5 → marker = the 5th "a"
        vm.closeChat(); advanceUntilIdle()
        // Reconnect: log cleared then refilled with 5 brand-new instances (the empty emission may be
        // conflated away — doesn't matter, the old marker isn't in the new list either way).
        app.messages.value = emptyList()
        app.messages.value = List(5) { ChatMessage.Remote(9, null, "b$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(5, vm.uiState.value.unread)
    }

    // The everyday case: read up to a point, more arrive while closed, marker still present → only the
    // tail past the marker counts (idx in the middle, not -1 and not the last element).
    @Test fun unreadCountsOnlyMessagesAfterTheReadMarker() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        val read = List(3) { ChatMessage.Remote(1, null, "a$it", Instant.EPOCH) }
        app.messages.value = read
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // marker = read[2]
        vm.closeChat(); advanceUntilIdle()
        // Two more; the marker instance is still present (appended, not replaced).
        app.messages.value = read + List(2) { ChatMessage.Remote(1, null, "b$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.unread)
    }

    // The original bug: once the log caps, the read message scrolls off, but new arrivals must still
    // count. Identity-miss → everything visible is unread.
    @Test fun unreadCountsNewMessagesAfterTheMarkerScrollsOff() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.messages.value = List(3) { ChatMessage.Remote(1, null, "seen$it", Instant.EPOCH) }
        advanceUntilIdle()
        vm.openChat(); advanceUntilIdle()          // marker = the last "seen"
        vm.closeChat(); advanceUntilIdle()
        // Window slid past the marker — none of the read instances remain.
        app.messages.value = List(4) { ChatMessage.Remote(1, null, "new$it", Instant.EPOCH) }
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.unread)
    }

    @Test fun sendMessageClearsDraftOnSuccess() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.sendResult = true
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals(listOf("hi"), app.sentTexts)
        assertEquals("", vm.uiState.value.chatDraft)
    }

    @Test fun sendMessageTrimsAndEscapesBeforeSending() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.sendResult = true
        vm.onChatDraftChange("  a<b & \"c\"  ")
        vm.sendMessage()
        advanceUntilIdle()
        // Input shaping lives here now — the app state sends the body verbatim. io.ktor.util.escapeHTML
        // emits &quot;/&#x27; for quotes; both round-trip through Html.fromHtml at render.
        assertEquals(listOf("a&lt;b &amp; &quot;c&quot;"), app.sentTexts)
    }

    @Test fun blankMessageIsNotSent() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onChatDraftChange("   ")
        vm.sendMessage()
        advanceUntilIdle()
        assertTrue(app.sentTexts.isEmpty())
    }

    @Test fun sendMessageKeepsDraftOnFailure() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.sendResult = false
        vm.onChatDraftChange("hi")
        vm.sendMessage()
        advanceUntilIdle()
        assertEquals("hi", vm.uiState.value.chatDraft)
    }

    @Test fun settingsAndAboutNavigateAsNestedRoutes() = runTest(dispatcher) {
        val vm = ConnectViewModel(FakeAppState(), FakeConfigStore(null))
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
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        advanceUntilIdle()
        vm.openSettings(); advanceUntilIdle()
        app.status.value = SessionStatus.Connected(since = FakeAppState.SINCE, sessionId = 1)
        advanceUntilIdle()
        assertEquals(Route.Settings, vm.uiState.value.route)
        // Backing out lands on Main, which now renders the connected screen rather than the form.
        vm.back(); advanceUntilIdle()
        assertEquals(Route.Main, vm.uiState.value.route)
    }

    @Test fun speakingSessionsReachTheUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        advanceUntilIdle()
        app.speakingSessions.value = setOf(4, 5)
        advanceUntilIdle()
        assertEquals(setOf(4, 5), vm.uiState.value.speakingSessions)
    }

    @Test fun microphoneGrantedStartsFromTheSystemNotFromADialog() = runTest(dispatcher) {
        // Regression: the state used to start null and only a dialog could fill it in. The
        // foreground service keeps the process alive after the task is swiped away, so resuming
        // from the notification builds a fresh ViewModel over a live session — and a null there
        // disabled Talk for the rest of it with no way back.
        val held = ConnectViewModel(FakeAppState(), FakeConfigStore(null)) { true }
        val notHeld = ConnectViewModel(FakeAppState(), FakeConfigStore(null)) { false }
        advanceUntilIdle()
        assertEquals(true, held.uiState.value.microphoneGranted)
        assertEquals(false, notHeld.uiState.value.microphoneGranted)
    }

    @Test fun microphonePermissionResultRecordsGrant() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(true)
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.microphoneGranted)
        // Recording the answer is not what starts capture: the answer outlives the connection it
        // was given for, so every connection starts its own session via onMicrophoneReady.
        assertEquals(0, app.requestCaptureCalls)
    }

    @Test fun microphonePermissionResultRecordsDenial() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(false)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.microphoneGranted)
        assertEquals(0, app.requestCaptureCalls)
    }

    /**
     * The defect this pins: the permission answer lives in the ViewModel and outlives the
     * connection, so a second connection in the same process is told nothing by the permission
     * callback. Every entry to the connected screen with the microphone granted has to start a
     * session, and requestCapture is idempotent so repeats are free.
     */
    @Test fun everyConnectedScreenEntryStartsItsOwnCaptureSession() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(true)
        vm.onMicrophoneReady()
        vm.onMicrophoneReady()
        advanceUntilIdle()
        assertEquals(2, app.requestCaptureCalls)
    }

    @Test fun onTransmittingDrivesTheConnectionGate() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onTransmitting(true)
        vm.onTransmitting(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), app.transmitting)
    }

    /**
     * The press asks for capture too, but that pairing belongs to the capture lifecycle and is
     * pinned in CaptureLifecycleTest. All the ViewModel owes is forwarding the edge, which
     * onTransmittingDrivesTheConnectionGate above covers.
     */
    @Test fun onTransmittingDoesNotAskForCaptureItself() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onTransmitting(true)
        vm.onTransmitting(false)
        advanceUntilIdle()
        assertEquals(0, app.requestCaptureCalls)
    }

    /**
     * speakingSessions is populated from decoded *incoming* audio, and our own audio is never
     * decoded locally — so without this merge your row can never light up while you talk.
     */
    @Test fun holdingTalkMarksYourOwnRowSpeaking() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()

        app.selfSpeaking.value = true
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        app.selfSpeaking.value = false
        runCurrent()
        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /** `selfMute` tracks `selfDeaf` because murmur sets both — see [selfMutedAloneIsNotDeafened]. */
    private fun user(
        session: Int,
        selfDeaf: Boolean = false,
        mute: Boolean = false,
        selfMute: Boolean = selfDeaf,
        suppress: Boolean = false,
    ) = User(
        session = session, name = "u$session", channelId = 0,
        mute = mute, deaf = false, selfMute = selfMute, selfDeaf = selfDeaf, suppress = suppress,
    )

    private fun treeWith(vararg users: User) = ChannelTree(
        channels = mapOf(0 to Channel(0, null, "Root", 0)),
        users = users.associateBy { it.session },
    )

    /**
     * A reconnect does not hand back a microphone the user never granted. The Mute control disables
     * itself on this block alone, so letting the outage mask it would let that user mute themselves
     * for every reconnect and stay muted after it.
     */
    @Test fun noMicrophoneOutranksAnOutage() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(granted = false)
        app.channelTree.value = treeWith(user(7))
        app.emitConnected(sessionId = 7)
        runCurrent()
        assertEquals(TalkBlock.NO_MICROPHONE, vm.uiState.value.talkBlock)

        app.emitReconnecting(lastSessionId = 7)
        runCurrent()
        assertEquals(TalkBlock.NO_MICROPHONE, vm.uiState.value.talkBlock)
    }

    /**
     * Through an outage no echo arrives at all: the tree is frozen at the dead connection's close.
     * Read from it, a second tap asks for the same thing again and the control never moves.
     */
    @Test fun aMuteTakenDuringTheOutageCanBeTakenBack() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(granted = true)
        app.channelTree.value = treeWith(user(7))
        app.emitConnected(sessionId = 7)
        runCurrent()
        assertFalse(vm.uiState.value.muted)

        app.emitReconnecting(lastSessionId = 7)
        runCurrent()
        vm.onToggleMute()
        runCurrent()
        assertTrue("the control reads the ask", vm.uiState.value.muted)
        vm.onToggleMute()
        runCurrent()
        assertFalse("and a second tap takes it back", vm.uiState.value.muted)
        assertEquals(listOf(true, false), app.muted)

        vm.onToggleDeafen()
        runCurrent()
        assertTrue("deafen has no echo here at all", vm.uiState.value.deafened)
        assertEquals(listOf(true), app.selfDeaf)
    }

    /**
     * Deafen is what was asked for, not the echo on our own row: `self_deaf` is ours alone to set,
     * so the row can only lag the ask. A second tap inside the round trip is therefore an undeafen,
     * not a repeat.
     */
    @Test fun deafenedFollowsTheTapNotTheEcho() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7))
        runCurrent()

        vm.onToggleDeafen()
        runCurrent()
        assertTrue("the button moves at the tap", vm.uiState.value.deafened)

        vm.onToggleDeafen()
        runCurrent()
        assertEquals(listOf(true, false), app.selfDeaf)

        app.channelTree.value = treeWith(user(7, selfDeaf = true, selfMute = true))
        runCurrent()
        assertFalse("the first tap's echo, landing late, moves nothing", vm.uiState.value.deafened)
    }

    /**
     * Every other case here carries `self_mute` alongside `self_deaf`, because murmur sets both — so
     * they cannot tell `deafened` reading the wrong one of the two apart from it reading the right
     * one. Confirmed by mutation: `deafened` reading `selfMute` passed this whole class without this.
     */
    @Test fun selfMutedAloneIsNotDeafened() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        app.channelTree.value = treeWith(user(7))
        vm.onToggleMute()
        runCurrent()

        assertFalse(vm.uiState.value.deafened)
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)
    }

    @Test fun anotherUsersDeafenIsNotOurs() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        // Granted, so a non-null talkBlock below can only have come from user 9's row.
        vm.onMicrophonePermissionResult(granted = true)
        app.channelTree.value = treeWith(user(7), user(9, selfDeaf = true))
        runCurrent()

        assertFalse(vm.uiState.value.deafened)
        assertNull(vm.uiState.value.talkBlock)
    }

    @Test fun talkBlockReachesTheUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        app.channelTree.value = treeWith(user(7))
        vm.onToggleDeafen()
        runCurrent()

        assertEquals(TalkBlock.DEAFENED, vm.uiState.value.talkBlock)

        // The server's half still comes off our row.
        vm.onToggleDeafen()
        app.channelTree.value = treeWith(user(7, mute = true))
        runCurrent()
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)
    }

    /**
     * The gate can be open while nothing we send is carried — an admin mute shuts nothing here.
     * Showing our own row speaking then is a lie.
     */
    @Test fun aBlockedTalkNeverMarksYouSpeaking() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        app.channelTree.value = treeWith(user(7))
        runCurrent()

        app.selfSpeaking.value = true
        runCurrent()
        assertTrue(7 in vm.uiState.value.speakingSessions)

        app.channelTree.value = treeWith(user(7, mute = true))
        runCurrent()
        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /** A talk block outranks the signal: nothing we send is carried, so showing yourself speaking
     *  would be a lie. */
    @Test fun deniedMicrophoneNeverMarksYouSpeaking() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = false)
        app.selfSpeaking.value = true
        runCurrent()

        assertFalse(7 in vm.uiState.value.speakingSessions)
    }

    /** The timer is the session's own connect time, not one the view model keeps. */
    @Test fun theCallTimerIsTheSessionsConnectTime() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        val first = clock.markNow()
        app.emitConnected(sessionId = 7, since = first)
        runCurrent()
        assertEquals(first, vm.uiState.value.connectedSince)

        clock += 5.seconds
        val second = clock.markNow()
        app.emitConnected(sessionId = 8, since = second)
        runCurrent()

        assertEquals(second, vm.uiState.value.connectedSince)
    }

    /** Reconnecting is the same call: the timer keeps counting, Talk is blocked, and the row the
     *  controls read is the one the server session we last had. */
    @Test fun reconnectingKeepsTheTimerBlocksTalkAndReadsTheLastRow() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        vm.onMicrophonePermissionResult(granted = true)
        val root = Channel(id = 0, parentId = null, name = "Root", position = 0)
        fun me(session: Int) = User(
            session = session, name = "me", channelId = 0,
            mute = false, deaf = false, selfMute = true, selfDeaf = false, suppress = false,
        )
        app.channelTree.value = ChannelTree(channels = mapOf(0 to root), users = mapOf(7 to me(7)))
        app.emitConnected(sessionId = 7)
        // The mute on that row is one this session asked for: self_mute is ours to set, so the
        // echo can only ever be a copy of the ask, and through the outage the ask is what is left.
        vm.onToggleMute()
        runCurrent()
        val since = vm.uiState.value.connectedSince
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)

        app.emitReconnecting(lastSessionId = 7)
        runCurrent()

        assertEquals(since, vm.uiState.value.connectedSince)
        assertEquals(TalkBlock.RECONNECTING, vm.uiState.value.talkBlock)
        assertTrue(vm.uiState.value.muted)

        app.emitConnected(sessionId = 9)
        app.channelTree.value = ChannelTree(channels = mapOf(0 to root), users = mapOf(9 to me(9)))
        runCurrent()
        assertEquals(since, vm.uiState.value.connectedSince)
        assertEquals(TalkBlock.MUTED, vm.uiState.value.talkBlock)
    }

    @Test fun disconnectClearsTheCallTimerAnchor() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        runCurrent()
        assertNotNull(vm.uiState.value.connectedSince)

        app.status.value = SessionStatus.Error(ErrorKind.DISCONNECTED, null)
        runCurrent()

        assertNull(vm.uiState.value.connectedSince)
    }

    /** Proves the merge is a union: your own session must not crowd out or replace anyone else's. */
    @Test fun otherSpeakersAreUnaffected() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        vm.onMicrophonePermissionResult(granted = true)
        runCurrent()

        app.selfSpeaking.value = true
        app.emitSpeaking(setOf(9))
        runCurrent()

        assertEquals(setOf(7, 9), vm.uiState.value.speakingSessions)
    }

    @Test fun routesReachTheUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        val speaker = AudioRoute("id-speaker", AudioRoute.Type.SPEAKER)
        app.audioRoutes.value = AudioRoutes(listOf(speaker), speaker)
        runCurrent()

        assertEquals(listOf(speaker), vm.uiState.value.audioRoutes.available)
        assertEquals(speaker, vm.uiState.value.audioRoutes.current)
    }

    /** Fire-and-forget, like deafen: the id goes down, the answer comes back through the flow. */
    @Test fun pickingARouteForwardsItsId() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))

        vm.onSelectRoute("id-bluetooth")

        assertEquals(listOf("id-bluetooth"), app.routeRequests)
    }

    @Test fun lastServerReplyAtFromConnectionAppearsInUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        val mark = clock.markNow()
        app.lastServerReplyAt.value = mark
        advanceUntilIdle()
        assertEquals(mark, vm.uiState.value.lastServerReplyAt)
    }

    // The round trip must still arrive once the two share a combine — the restructure in Step 4 is
    // exactly where that would silently break.
    @Test fun theRoundTripStillReachesUiStateAfterTheLinkCombine() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.roundTripTime.value = 12.5.milliseconds
        advanceUntilIdle()
        assertEquals(12.5.milliseconds, vm.uiState.value.roundTripTime)
    }

    @Test fun theSelectedUsersDepthReachesUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9))
        app.emitDepths(mapOf(9 to 120 * 48))
        vm.openUserDetail(9)
        runCurrent()

        assertEquals(9, vm.uiState.value.selectedSession)
        assertEquals(120.milliseconds, vm.uiState.value.playoutStats?.depth(9))
    }

    /** A speaker the engine has retired has no queue, and the sheet must say so rather than 0. */
    @Test fun aSilentUserHasNoDepth() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9))
        app.emitDepths(mapOf(7 to 80 * 48))
        vm.openUserDetail(9)
        runCurrent()

        assertNull(vm.uiState.value.playoutStats?.depth(9))
    }

    /**
     * The selection is a session, and the sheet renders from the tree, so a subject who leaves —
     * or a disconnect, which empties the tree — has to take the sheet with them.
     */
    @Test fun aSelectedUserWhoLeavesClosesTheSheet() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9))
        vm.openUserDetail(9)
        runCurrent()
        assertEquals(9, vm.uiState.value.selectedSession)

        app.channelTree.value = treeWith(user(7))
        runCurrent()
        assertNull(vm.uiState.value.selectedSession)
    }

    @Test fun closingClearsTheSelection() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9))
        vm.openUserDetail(9)
        runCurrent()

        vm.closeUserDetail()
        runCurrent()
        assertNull(vm.uiState.value.selectedSession)
    }

    @Test fun refreshingAsksTheConnectionAboutThatUser() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        runCurrent()

        vm.refreshUserStats(9)

        assertEquals(listOf(9), app.userStatsRequests)
    }

    @Test fun theSelectedUsersPingReachesUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9))
        vm.openUserDetail(9)
        app.userStats.value = UserStats(9, 23.5.milliseconds, null, null, null, null)
        runCurrent()

        assertEquals(UserStats(9, 23.5.milliseconds, null, null, null, null), vm.uiState.value.userStats)
    }

    /**
     * The reply is asynchronous, so one asked for on a sheet that has since closed can land while
     * another user's is open. Rendering it there would put one person's ping under another's name.
     */
    @Test fun aPingForSomeoneElseIsNotShown() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7), user(9), user(11))
        vm.openUserDetail(11)
        app.userStats.value = UserStats(9, 23.5.milliseconds, null, null, null, null)
        runCurrent()

        assertNull(vm.uiState.value.userStats)
    }

    @Test fun theStoredModeLoadsAndReachesTheConnection() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null, TransmitMode.VoiceActivity))
        advanceUntilIdle()
        assertEquals(TransmitMode.VoiceActivity, vm.uiState.value.transmitMode)
        assertEquals(listOf(TransmitMode.VoiceActivity), app.transmitModes)
    }

    @Test fun selectingAModePersistsItAndAppliesIt() = runTest(dispatcher) {
        val app = FakeAppState()
        val store = FakeConfigStore(null)
        val vm = ConnectViewModel(app, store)
        advanceUntilIdle()

        vm.onSelectTransmitMode(TransmitMode.VoiceActivity)
        advanceUntilIdle()

        assertEquals(TransmitMode.VoiceActivity, vm.uiState.value.transmitMode)
        assertEquals(TransmitMode.VoiceActivity, store.savedMode)
        assertEquals(TransmitMode.VoiceActivity, app.transmitModes.last())
    }

    /** Same discipline as deafen: the button follows the tap, not our own row. */
    @Test fun mutedFollowsTheTapNotTheEcho() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7))
        runCurrent()

        vm.onToggleMute()
        runCurrent()
        assertTrue("the button moves at the tap", vm.uiState.value.muted)

        // No echo yet, and the second tap is still an unmute.
        vm.onToggleMute()
        runCurrent()
        assertEquals(listOf(true, false), app.muted)
    }

    /** Desktop's rule, reachable now that Mute reads muted the moment a deafen is asked for. */
    @Test fun muteTappedWhileDeafenedUnmutesAndUndeafens() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7))
        runCurrent()

        vm.onToggleDeafen()
        runCurrent()
        assertTrue("a deafen mutes", vm.uiState.value.muted)

        vm.onToggleMute()
        runCurrent()
        assertEquals(listOf(false), app.muted)
        assertFalse(vm.uiState.value.muted)
        assertFalse(vm.uiState.value.deafened)
    }

    /** Self-unmute stays legal under an admin mute or suppress; reported apart from [muted] so
     *  the control stays pressable. */
    @Test fun anAdminMuteOrSuppressIsReportedAsInaudible() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        app.channelTree.value = treeWith(user(7))
        runCurrent()
        assertFalse(vm.uiState.value.inaudible)

        app.channelTree.value = treeWith(user(7, mute = true))
        runCurrent()
        assertTrue("an admin mute is inaudible", vm.uiState.value.inaudible)
        assertFalse("but it is not a self-mute", vm.uiState.value.muted)

        app.channelTree.value = treeWith(user(7, suppress = true))
        runCurrent()
        assertTrue("a channel suppress is too", vm.uiState.value.inaudible)
    }

    @Test fun aHeldCallReachesTheUiState() = runTest(dispatcher) {
        val app = FakeAppState()
        val vm = ConnectViewModel(app, FakeConfigStore(null))
        app.emitConnected(sessionId = 7)
        runCurrent()
        assertFalse(vm.uiState.value.callHeld)

        app.callHeld.value = true
        runCurrent()
        assertTrue(vm.uiState.value.callHeld)
    }
}
