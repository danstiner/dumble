package me.danielstiner.dumble.mumble.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.chat.DenyReason
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TestTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateMachineTest {

    private class FakeChannel : ControlChannel {
        val sent = mutableListOf<Pair<TcpMessageType, MessageLite>>()
        val sentRaw = mutableListOf<Pair<TcpMessageType, ByteArray>>()
        var closed = false
        /** Refuse sends, standing in for a full queue or an already-closed transport. */
        var sendResult = true
        override fun send(type: TcpMessageType, message: MessageLite): Boolean {
            if (!sendResult) return false
            sent += type to message; return true
        }
        override fun sendRaw(type: TcpMessageType, payload: ByteArray): Boolean {
            sentRaw += type to payload; return true
        }
        override fun close() { closed = true }
    }

    private fun frame(type: TcpMessageType, message: MessageLite) =
        TcpFrame(type.id, message.toByteArray())

    /** The server's echo of the most recent ping — the same timestamp, which is what the handler matches on. */
    private fun replyToLastPing(ch: FakeChannel): TcpFrame {
        val ping = ch.sent.last { it.first == TcpMessageType.Ping }.second as MumbleProtos.Ping
        return frame(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder().setTimestamp(ping.timestamp).build())
    }

    private fun synchronizedSm(ch: FakeChannel, scope: CoroutineScope): SessionStateMachine {
        val sm = SessionStateMachine(ch, "tester", null, scope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(1).build()))
        return sm
    }

    @Test
    fun sendsVersionThenAuthenticateOnStart() = runTest {
        val ch = FakeChannel()
        SessionStateMachine(ch, "tester", null, backgroundScope).start()

        assertEquals(listOf(TcpMessageType.Version, TcpMessageType.Authenticate), ch.sent.map { it.first })
    }

    // The transport's reader is live before start() is called, so a socket that dies in that window
    // settles a terminal state first. An unconditional write would resurrect the session and then
    // blame the server for a 15s timeout instead of reporting the input/output error that killed it.
    @Test
    fun startDoesNotResurrectASessionThatAlreadyFailed() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope)

        sm.onClosed(java.io.IOException("connection reset"))
        sm.start()

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.IO, state.reason)
        assertEquals("connection reset", state.detail)
        assertTrue("no handshake may be sent after a terminal state", ch.sent.isEmpty())
    }

    private class FailingChannel(private val failOn: TcpMessageType) : ControlChannel {
        val sent = mutableListOf<TcpMessageType>()
        override fun send(type: TcpMessageType, message: MessageLite): Boolean {
            if (type == failOn) return false
            sent += type
            return true
        }
        // This fake exists to fail one handshake message; audio never reaches it.
        override fun sendRaw(type: TcpMessageType, payload: ByteArray) = true
        override fun close() = Unit
    }

    // Reported as IO rather than TIMEOUT, and on the virtual clock without advancing it: a frame
    // that never left is a local fault, not a server that went quiet 15 seconds later.
    @Test
    fun aVersionThatNeverLeavesFailsImmediately() = runTest {
        val ch = FailingChannel(TcpMessageType.Version)
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.IO, state.reason)
        assertEquals("could not send Version", state.detail)
        assertTrue("Authenticate must not follow a Version that failed", ch.sent.isEmpty())
    }

    @Test
    fun anAuthenticateThatNeverLeavesFailsImmediately() = runTest {
        val ch = FailingChannel(TcpMessageType.Authenticate)
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.IO, state.reason)
        assertEquals("could not send Authenticate", state.detail)
    }

    // ---- ping liveness -------------------------------------------------------------------
    //
    // The machine publishes the *instant* of the last reply, not a duration: the duration is stale
    // the moment it is written and what changes it is the passage of time, so the UI derives it
    // against its own clock (ConnectedScreen, which already does this for the call duration).
    // These tests derive it the same way. elapsedMillis is injected off the test scheduler because
    // the value is real elapsed time and android.os.SystemClock returns 0 under
    // returnDefaultValues; driving it separately from delay() is also what lets the suspend case
    // below be expressed at all.

    /**
     * Advance the scheduler and the real clock together, which is what an awake device does. The
     * source moves first: the call fires inside advanceTimeBy and reads the clock as it goes, so
     * advancing the scheduler first would have it observe the pre-advance time.
     */
    private fun TestScope.advanceBoth(bootClock: TestTimeSource, millis: Long) {
        bootClock += millis.milliseconds
        advanceTimeBy(millis)
    }

    // runCurrent so the ping coroutine's body actually starts here. It marks the anchor the
    // silence is measured from, and scope.launch only queues it -- without this the anchor is taken
    // one advance late and every silence reads an interval short.
    private fun TestScope.pingSm(ch: ControlChannel, bootClock: TestTimeSource): SessionStateMachine {
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, bootClock = bootClock).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(1).build()))
        runCurrent()
        return sm
    }

    /** What the UI computes: elapsedNow() on the published mark, which needs no clock of its own. */
    private fun pingAge(sm: SessionStateMachine): Duration =
        sm.lastServerReplyAt.value?.elapsedNow() ?: Duration.ZERO

    // A link replying every interval never reads degraded. Silence oscillates between zero and one
    // interval by construction -- it is time since the last reply, and the link is only asked once
    // per interval -- which is why the threshold sits at three.
    @Test
    fun anAnsweredLinkNeverReadsDegraded() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)

        repeat(6) {
            advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
            sm.onFrame(replyToLastPing(ch))
            assertEquals(Duration.ZERO, pingAge(sm))
        }
    }

    @Test
    fun aSilentServerAgesTheLastReply() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)

        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
        val first = pingAge(sm)
        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
        val second = pingAge(sm)

        assertTrue("age must grow while nothing replies, was $first then $second", second > first)
        assertTrue("two intervals of age must not yet read degraded",
            second < SessionStateMachine.DEGRADED_PING_AGE)
        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
        assertTrue("three intervals of age must read degraded",
            pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE)
    }

    @Test
    fun aReplyResetsTheAgeImmediately() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)
        repeat(4) { advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1) }
        assertTrue(pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE)

        sm.onFrame(replyToLastPing(ch))

        assertEquals(Duration.ZERO, pingAge(sm))
    }

    // A round trip of several intervals is a slow link, not a dead one. Every reply on it lands
    // after the next call has re-stamped, so matching only the newest stamp would register no reply
    // at all and the link would read permanently silent while answering every ping. A duration does
    // not care which ping a reply names, so this needs no rule about outstanding pings.
    @Test
    fun aRoundTripOfSeveralIntervalsNeverReadsDegraded() = runTest {
        for (lagIntervals in 1..3) {
            val ch = FakeChannel()
            val bootClock = TestTimeSource()
            val sm = pingSm(ch, bootClock)
            val pings = mutableListOf<TcpFrame>()

            repeat(8) {
                advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
                pings += replyToLastPing(ch)
                pings.getOrNull(pings.size - 1 - lagIntervals)?.let { r -> sm.onFrame(r) }
                if (it >= lagIntervals) {
                    assertTrue(
                        "lag $lagIntervals: a link replying to every ping must not read degraded, " +
                            "was ${pingAge(sm)}",
                        pingAge(sm) < SessionStateMachine.DEGRADED_PING_AGE,
                    )
                }
            }
            assertTrue("the round trip must still be reported", sm.roundTripTime.value != null)
        }
    }

    // A reply for a long-superseded ping still clears the silence: whichever ping it names, the
    // link spoke. Dan's call 2026-08-13, replacing the earlier rule that rejected a superseded
    // reply outright -- that rule cannot coexist with tolerating a round trip longer than the
    // interval, since both are replies arriving after their successor was sent.
    @Test
    fun aLateReplyForASupersededPingIsStillTheLinkSpeaking() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)
        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
        val stale = replyToLastPing(ch)
        repeat(3) { advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1) }
        assertTrue(pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE)

        sm.onFrame(stale)
        assertEquals(Duration.ZERO, pingAge(sm))

        // And silence resuming climbs again from there, so a dying link is still reported.
        repeat(3) { advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1) }
        assertTrue(pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE)
    }

    // The blind spot a count of unanswered pings could not see. delay() schedules on
    // CLOCK_MONOTONIC, so a suspended CPU fires no calls at all and a counter of unanswered pings
    // reads zero straight through the outage -- while the server, whose reap runs on its own wall
    // clock, has already dropped us. Publishing the instant rather than a per-call duration is what
    // makes this visible without a call having to fire at all: real time moves, so the derived
    // silence moves with it.
    @Test
    fun timeAsleepCountsTowardTheAge() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)
        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)
        sm.onFrame(replyToLastPing(ch))
        assertEquals(Duration.ZERO, pingAge(sm))

        // Real time moves and the scheduler does not: no call can fire, exactly as in a doze.
        bootClock += 45.seconds

        assertTrue(
            "a doze past the server's reap must read as silence, was ${pingAge(sm)}",
            pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE,
        )
    }

    // A ping that never reached the wire is two claims, both load-bearing: it cannot be answered,
    // so the silence keeps growing, and it is not fatal -- a full queue or a dead transport must
    // not end the session, since the reader already reports that death on its own path.
    @Test
    fun aPingThatCannotBeQueuedStillAgesButIsNotFatal() = runTest {
        val ch = FailingChannel(TcpMessageType.Ping)
        val bootClock = TestTimeSource()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, bootClock = bootClock)
            .apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(9).build()))
        runCurrent()

        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS * 4 + 100)

        assertTrue(pingAge(sm) >= SessionStateMachine.DEGRADED_PING_AGE)
        assertEquals(ConnectionState.Synchronized(9), sm.state.value)
    }

    @Test
    fun startIsIdempotent() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.start()

        assertEquals(
            listOf(TcpMessageType.Version, TcpMessageType.Authenticate),
            ch.sent.map { it.first },
        )
    }

    @Test
    fun serverSyncReachesSynchronized() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(42).build()))

        assertEquals(ConnectionState.Synchronized(42), sm.state.value)
    }

    // A dropped connection must not keep reading as connected. Synchronized is not terminal against a
    // real close: the socket died, so the session ends as Failed(IO) carrying the cause.
    @Test
    fun aClosedConnectionEndsAnEstablishedSession() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(42).build()))
        assertEquals(ConnectionState.Synchronized(42), sm.state.value)

        sm.onClosed(java.io.IOException("server went away"))

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.IO, state.reason)
        assertEquals("server went away", state.detail)
    }

    // A specific handshake failure must survive the socket close that follows it — the close reports
    // IO, but the real reason (here a reject) is what already settled the state and must win.
    @Test
    fun aCloseDoesNotOverwriteAnEarlierFailureReason() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("banned").build()))
        assertEquals(FailReason.AUTH_REJECT, (sm.state.value as ConnectionState.Failed).reason)

        sm.onClosed(java.io.IOException("socket closed"))

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.AUTH_REJECT, state.reason)
        assertEquals("banned", state.detail)
    }

    @Test
    fun rejectBecomesAuthRejectWithReason() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("bad password").build()))

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.AUTH_REJECT, state.reason)
        assertEquals("bad password", state.detail)
    }

    @Test
    fun cryptSetupIsStoredWithoutChangingState() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
            .setKey(com.google.protobuf.ByteString.copyFrom(ByteArray(16) { 1 }))
            .build()))

        assertEquals(ConnectionState.Handshaking, sm.state.value)
        assertEquals(16, sm.cryptKey!!.size)
    }

    // A resync CryptSetup carries only nonces. Without a presence check its empty key would
    // silently clobber the negotiated one — invisible until voice reads cryptKey and decryption
    // breaks at whatever later moment the server happens to request a resync.
    @Test
    fun resyncCryptSetupDoesNotClobberTheNegotiatedKey() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
            .setKey(com.google.protobuf.ByteString.copyFrom(ByteArray(16) { 1 }))
            .build()))

        // Resync: server nonce only, no key.
        sm.onFrame(frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
            .setServerNonce(com.google.protobuf.ByteString.copyFrom(ByteArray(16) { 2 }))
            .build()))

        assertEquals(16, sm.cryptKey!!.size)
    }

    @Test
    fun versionFramePopulatesServerVersion() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 5, 735))
            .setRelease("Murmur")
            .setOs("Linux")
            .build()))

        assertEquals(ServerVersion(1, 5, 735, "Murmur", "Linux"), sm.serverVersion.value)
    }

    @Test
    fun unknownMessageIdIsIgnored() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0, 1, 2)))
        sm.onFrame(TcpFrame(9999, byteArrayOf(1)))

        assertEquals(ConnectionState.Handshaking, sm.state.value)
    }

    @Test
    fun textMessageFrameResolvesTheSenderNameEagerly() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(1000) }).apply { start() }
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(5).setName("alice").setChannelId(0).build()))

        sm.onFrame(frame(TcpMessageType.TextMessage,
            MumbleProtos.TextMessage.newBuilder().setActor(5).setMessage("hi there").build()))

        assertEquals(listOf(ChatMessage.Remote(5, "alice", "hi there", Instant.ofEpochMilli(1000))), sm.messages.value)
    }

    // A chat log is a transcript: the sender name is captured when the line arrives and must not
    // change when that user later leaves (their session is pruned from the tree).
    @Test
    fun senderNameSurvivesTheSenderLeaving() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(1000) }).apply { start() }
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(5).setName("alice").setChannelId(0).build()))
        sm.onFrame(frame(TcpMessageType.TextMessage,
            MumbleProtos.TextMessage.newBuilder().setActor(5).setMessage("hi there").build()))

        sm.onFrame(frame(TcpMessageType.UserRemove, MumbleProtos.UserRemove.newBuilder().setSession(5).build()))

        assertEquals("alice", (sm.messages.value.single() as ChatMessage.Remote).senderName)
    }

    @Test
    fun textMessageWithoutActorHasNullActor() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(1000) }).apply { start() }

        // A server/system broadcast carries no actor; null keeps it from rendering as "user 0".
        sm.onFrame(frame(TcpMessageType.TextMessage,
            MumbleProtos.TextMessage.newBuilder().setMessage("welcome").build()))

        assertEquals(listOf(ChatMessage.Remote(null, null, "welcome", Instant.ofEpochMilli(1000))), sm.messages.value)
    }

    @Test
    fun permissionDeniedAppendsAStructuredDenial() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(1000) }).apply { start() }

        sm.onFrame(frame(TcpMessageType.PermissionDenied,
            MumbleProtos.PermissionDenied.newBuilder()
                .setType(MumbleProtos.PermissionDenied.DenyType.TextTooLong).build()))

        // Domain reason, not pre-worded — the UI does the phrasing.
        assertEquals(
            listOf(ChatMessage.Denied(DenyReason.TooLong, Instant.ofEpochMilli(1000))),
            sm.messages.value,
        )
    }

    @Test
    fun permissionDeniedCapturesTheChannelForAPermissionDenial() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(1000) }).apply { start() }

        sm.onFrame(frame(TcpMessageType.ChannelState,
            MumbleProtos.ChannelState.newBuilder().setChannelId(7).setName("Gaming").build()))
        sm.onFrame(frame(TcpMessageType.PermissionDenied,
            MumbleProtos.PermissionDenied.newBuilder()
                .setType(MumbleProtos.PermissionDenied.DenyType.Permission).setChannelId(7).build()))

        // Channel name captured eagerly, like the sender name.
        assertEquals(
            listOf(ChatMessage.Denied(DenyReason.NoPostPermission("Gaming"), Instant.ofEpochMilli(1000))),
            sm.messages.value,
        )
    }

    @Test
    fun messageLogIsCappedAtMaxMessages() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(0) }).apply { start() }

        repeat(SessionStateMachine.MAX_MESSAGES + 5) { i ->
            sm.onFrame(frame(TcpMessageType.TextMessage,
                MumbleProtos.TextMessage.newBuilder().setActor(1).setMessage("m$i").build()))
        }

        assertEquals(SessionStateMachine.MAX_MESSAGES, sm.messages.value.size)
        // Oldest dropped, newest kept.
        assertEquals("m5", (sm.messages.value.first() as ChatMessage.Remote).htmlBody)
        assertEquals("m${SessionStateMachine.MAX_MESSAGES + 4}", (sm.messages.value.last() as ChatMessage.Remote).htmlBody)
    }

    // The unread marker anchors on instance identity, so a real wrap past the cap must not clone
    // survivors: a message that stays in the window keeps its reference (just shifts down), and one
    // that falls off the front is gone by identity.
    @Test
    fun cappedLogPreservesInstanceIdentityAcrossAWrap() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(0) }).apply { start() }
        repeat(SessionStateMachine.MAX_MESSAGES) { i ->
            sm.onFrame(frame(TcpMessageType.TextMessage,
                MumbleProtos.TextMessage.newBuilder().setActor(1).setMessage("m$i").build()))
        }
        val survivor = sm.messages.value[500]        // stays in the window
        val fallsOff = sm.messages.value.first()     // m0, about to drop

        repeat(5) { i ->
            sm.onFrame(frame(TcpMessageType.TextMessage,
                MumbleProtos.TextMessage.newBuilder().setActor(1).setMessage("n$i").build()))
        }

        // Same instance, shifted down by the 5 that fell off the front.
        assertEquals(495, sm.messages.value.indexOfLast { it === survivor })
        // The dropped one is no longer findable by identity.
        assertEquals(-1, sm.messages.value.indexOfLast { it === fallsOff })
    }

    @Test
    fun sendTextGoesToMyChannelVerbatimAndEchoesLocally() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clock = { Instant.ofEpochMilli(2000) }).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(1).build()))
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(1).setName("me").setChannelId(7).build()))

        // The body is sent as given — escaping is the caller's concern now, not this layer's.
        val ok = sm.sendText("a&lt;b")

        assertTrue(ok)
        val sent = ch.sent.last { it.first == TcpMessageType.TextMessage }.second as MumbleProtos.TextMessage
        assertEquals(listOf(7), sent.channelIdList)
        assertEquals("a&lt;b", sent.message)
        assertEquals(listOf(ChatMessage.Remote(1, "me", "a&lt;b", Instant.ofEpochMilli(2000))), sm.messages.value)
    }

    @Test
    fun sendTextBeforeSynchronizedIsANoOp() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        assertFalse(sm.sendText("hi"))
        assertTrue(ch.sent.none { it.first == TcpMessageType.TextMessage })
        assertTrue(sm.messages.value.isEmpty())
    }

    /** Every UserState this machine has sent, in order. */
    private fun FakeChannel.userStates() =
        sent.filter { it.first == TcpMessageType.UserState }.map { it.second as MumbleProtos.UserState }

    private fun synchronizedMachine(ch: FakeChannel, scope: CoroutineScope, session: Int = 4) =
        SessionStateMachine(ch, "tester", null, scope).apply {
            start()
            onFrame(frame(TcpMessageType.ServerSync,
                MumbleProtos.ServerSync.newBuilder().setSession(session).build()))
        }

    @Test
    fun deafenSendsSelfDeafAndSelfMuteForOurSession() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        assertTrue(sm.setSelfDeaf(true))

        val sent = ch.userStates().single()
        assertEquals(4, sent.session)
        assertTrue(sent.selfDeaf)
        assertTrue(sent.selfMute)
    }

    @Test
    fun undeafenAfterAPlainDeafenClearsBoth() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.setSelfDeaf(true)
        assertTrue(sm.setSelfDeaf(false))

        val sent = ch.userStates().last()
        assertFalse(sent.selfDeaf)
        assertFalse(sent.selfMute)
    }

    /**
     * The stranding regression. A double-tap lands inside one round trip, so the second ask arrives
     * with the tree — and therefore the UI's idea of `deafened` — unchanged, and reaches this as a
     * repeat. Recomputing it against state the first send already moved is what used to emit
     * `self_mute=true` here, leaving the user muted with no control able to clear it.
     *
     * Asserts every frame, not just the last: the bug was a *differing second* message, and
     * asserting only the last one passes against the broken version.
     */
    @Test
    fun undeafenTappedTwiceSendsTheSameMessageTwice() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)
        sm.setSelfDeaf(true)

        sm.setSelfDeaf(false)
        sm.setSelfDeaf(false)

        val undeafens = ch.userStates().drop(1)
        assertEquals(2, undeafens.size)
        undeafens.forEach {
            assertFalse("every undeafen must clear self_deaf", it.selfDeaf)
            assertFalse("every undeafen must clear self_mute", it.selfMute)
        }
    }

    /**
     * The same break from the other side: a repeated deafen must not recompute `unmuteOnUndeaf`
     * against its own first send, which would flip the debt down and make the eventual undeafen keep
     * the mute. The frames alone cannot show this — the *following* undeafen is the assertion.
     */
    @Test
    fun deafenTappedTwiceKeepsTheUnmuteDebt() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.setSelfDeaf(true)
        sm.setSelfDeaf(true)
        sm.setSelfDeaf(false)

        assertFalse("the debt survived, so the undeafen must unmute", ch.userStates().last().selfMute)
    }

    /**
     * Swallowing repeats would also fix the two tests above, and would leave the button dead for the
     * session: murmur silently rate-limits UserState addressed at the sender and never applies the
     * dropped message, after which every later tap matches the recorded intent and is swallowed too.
     */
    @Test
    fun aRepeatStillReachesTheWire() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.setSelfDeaf(true)
        sm.setSelfDeaf(true)

        assertEquals(2, ch.userStates().size)
    }

    /**
     * A refusal is reported rather than swallowed, and the retry still puts a complete deafen on the
     * wire.
     *
     * It does **not** pin `setSelfDeaf`'s "only advance the intent on a successful enqueue" guard,
     * despite looking like it should — dropping that guard passes this test. Only deafen can move
     * `selfMute` today, so the reachable intents are just (F,F,F) and (T,T,T), and from either one a
     * recorded-but-unsent intent and an unmoved one emit the same frame on every retry. The guard is
     * still correct and becomes observable the day a mute control lands; until then nothing here
     * defends it. Confirmed by mutation.
     */
    @Test
    fun aRefusedSendIsReportedAndTheRetryStillReachesTheWire() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        ch.sendResult = false
        assertFalse(sm.setSelfDeaf(true))
        assertTrue("a refused send must not reach the wire", ch.userStates().isEmpty())
        ch.sendResult = true

        assertTrue(sm.setSelfDeaf(true))
        val sent = ch.userStates().single()
        assertTrue(sent.selfDeaf)
        assertTrue(sent.selfMute)
    }

    /** No optimistic echo: the tree only moves when the server says so. */
    @Test
    fun deafenDoesNotTouchTheTreeUntilTheServerEchoes() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(4).setName("me").setChannelId(0).build()))

        sm.setSelfDeaf(true)
        assertFalse(sm.channelTree.value.users[4]!!.selfDeaf)

        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(4).setSelfDeaf(true).setSelfMute(true).build()))
        assertTrue(sm.channelTree.value.users[4]!!.selfDeaf)
    }

    @Test
    fun setSelfDeafBeforeSynchronizedIsANoOp() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        assertFalse(sm.setSelfDeaf(true))
        assertTrue(ch.sent.none { it.first == TcpMessageType.UserState })
    }

    @Test
    fun channelAndUserStateFramesBuildTheChannelTree() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.ChannelState,
            MumbleProtos.ChannelState.newBuilder().setChannelId(0).setName("Root").build()))
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(7).setName("alice").setChannelId(0).build()))

        val tree = sm.channelTree.value
        assertEquals("Root", tree.channels[0]!!.name)
        assertEquals("alice", tree.users[7]!!.name)
    }

    @Test
    fun removeFramesPruneTheChannelTree() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ChannelState,
            MumbleProtos.ChannelState.newBuilder().setChannelId(1).setName("Gaming").build()))
        sm.onFrame(frame(TcpMessageType.UserState,
            MumbleProtos.UserState.newBuilder().setSession(7).setName("alice").setChannelId(1).build()))

        sm.onFrame(frame(TcpMessageType.ChannelRemove, MumbleProtos.ChannelRemove.newBuilder().setChannelId(1).build()))
        sm.onFrame(frame(TcpMessageType.UserRemove, MumbleProtos.UserRemove.newBuilder().setSession(7).build()))

        assertTrue(sm.channelTree.value.channels.isEmpty())
        assertTrue(sm.channelTree.value.users.isEmpty())
    }

    @Test
    fun handshakeThatNeverCompletesTimesOut() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        advanceTimeBy(SessionStateMachine.HANDSHAKE_DEADLINE_MS + 1_000)

        val state = sm.state.value
        assertTrue("expected TIMEOUT, was $state",
            state is ConnectionState.Failed && state.reason == FailReason.TIMEOUT)
    }

    @Test
    fun pingsStartAfterSynchronized() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(1).build()))

        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS * 2 + 100)

        assertEquals(2, ch.sent.count { it.first == TcpMessageType.Ping })
    }

    @Test
    fun noPingsBeforeSynchronized() = runTest {
        val ch = FakeChannel()
        SessionStateMachine(ch, "tester", null, backgroundScope).start()

        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS * 2 + 100)

        assertEquals(0, ch.sent.count { it.first == TcpMessageType.Ping })
    }

    @Test
    fun aPingReplyPublishesTheRoundTrip() = runTest {
        val ch = FakeChannel()
        val bootClock = TestTimeSource()
        val sm = pingSm(ch, bootClock)
        advanceBoth(bootClock, SessionStateMachine.PING_INTERVAL_MS + 1)

        // The reply carries the stamp we sent, so the round trip is however long it took to come
        // back — not the age of the newest ping, which is what a locally-remembered send time
        // would measure.
        val echoed = ch.sent.last { it.first == TcpMessageType.Ping }.second as MumbleProtos.Ping
        bootClock += 5.milliseconds
        sm.onFrame(frame(TcpMessageType.Ping, echoed))

        assertEquals(5.milliseconds, sm.roundTripTime.value)
    }

    // The failure path is covered; this covers the success path staying successful — a deadline
    // that is not defused would clobber Synchronized once the interval elapses.
    @Test
    fun synchronizedSessionSurvivesTheDeadline() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(7).build()))

        advanceTimeBy(SessionStateMachine.HANDSHAKE_DEADLINE_MS + 1_000)

        assertEquals(ConnectionState.Synchronized(7), sm.state.value)
        assertFalse("channel must not be closed after a successful handshake", ch.closed)
    }

    // The deadline coroutine runs outside the transport's listener lock, so a firing deadline and
    // a late Reject genuinely race. Whichever failure actually ended the session must be the one
    // reported, not whichever was processed second.
    @Test
    fun firstFailureWinsOverALaterOne() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        advanceTimeBy(SessionStateMachine.HANDSHAKE_DEADLINE_MS + 1_000)
        sm.onFrame(frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("late").build()))

        val state = sm.state.value as ConnectionState.Failed
        assertEquals(FailReason.TIMEOUT, state.reason)
    }

    // Real dispatchers, not runTest: virtual time serializes concurrent writers, so it structurally
    // cannot observe this race. Repeats because the window is narrow.
    //
    // Races ServerSync's compareAndSet(Handshaking, Synchronized) against Reject's plain
    // check-then-write fail() — the same defect class as the deadline coroutine's
    // compareAndSet(Handshaking, Failed(TIMEOUT)) racing a late Reject described in the review,
    // reproduced without waiting out the real 15s handshake deadline. If fail() reads state before
    // the CAS commits Synchronized and then writes after, it silently downgrades an established
    // session to Failed.
    @Test
    fun firstFailureWinsUnderRealConcurrency() {
        // A blocking barrier or a fresh thread{} per trial adds microseconds of OS wake/creation
        // latency, which dwarfs the few-nanosecond window between fail()'s read and its write. Two
        // persistent busy-spinning threads plus an atomic generation counter remove that latency so
        // many more trials actually land inside the window.
        val trials = 200_000
        val gen = java.util.concurrent.atomic.AtomicInteger(0)
        val ackA = java.util.concurrent.atomic.AtomicInteger(0)
        val ackB = java.util.concurrent.atomic.AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val smRef = java.util.concurrent.atomic.AtomicReference<SessionStateMachine>()
        val violation = java.util.concurrent.atomic.AtomicReference<String>()

        val sync = frame(
            TcpMessageType.ServerSync,
            MumbleProtos.ServerSync.newBuilder().setSession(1).build(),
        )
        val reject = frame(
            TcpMessageType.Reject,
            MumbleProtos.Reject.newBuilder().setReason("late").build(),
        )

        val threadA = thread(name = "racer-sync") {
            var last = 0
            while (!stop.get()) {
                val g = gen.get()
                if (g == last) continue
                last = g
                smRef.get()?.onFrame(sync)
                ackA.set(last)
            }
        }
        val threadB = thread(name = "racer-reject") {
            var last = 0
            while (!stop.get()) {
                val g = gen.get()
                if (g == last) continue
                last = g
                smRef.get()?.onFrame(reject)
                ackB.set(last)
            }
        }

        try {
            var i = 1
            while (i <= trials && violation.get() == null) {
                val ch = FakeChannel()
                val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val sm = SessionStateMachine(ch, "tester", null, s).apply { start() }
                smRef.set(sm)
                gen.set(i)
                // The final state alone can't distinguish "fail() legitimately won" from "fail()
                // clobbered an already-committed Synchronized" — both end as Failed. Poll the
                // transient state while waiting for both racers to finish: if Synchronized was ever
                // observed but the run doesn't end there, something overwrote a committed CAS.
                var sawSynchronized = false
                while ((ackA.get() != i || ackB.get() != i) && !stop.get()) {
                    if (sm.state.value is ConnectionState.Synchronized) sawSynchronized = true
                }
                if (sm.state.value is ConnectionState.Synchronized) sawSynchronized = true

                val finalState = sm.state.value
                if (sawSynchronized && finalState !is ConnectionState.Synchronized) {
                    violation.compareAndSet(
                        null,
                        "a CAS-won Synchronized session was overwritten by a racing fail(): " +
                            "final state was $finalState (trial $i)",
                    )
                }
                s.cancel()
                i++
            }
        } finally {
            stop.set(true)
            threadA.join(5_000)
            threadB.join(5_000)
        }

        assertTrue(violation.get() ?: "no violation observed across $trials trials", violation.get() == null)
    }

    // Both encodings are required: modern servers read version_v2, older ones only version_v1.
    // Existing tests cover the encode helpers and the message ordering, but nothing asserted the
    // sent message actually carries both fields.
    @Test
    fun versionMessageCarriesBothEncodings() = runTest {
        val ch = FakeChannel()
        SessionStateMachine(ch, "tester", null, backgroundScope).start()

        val version = ch.sent.first { it.first == TcpMessageType.Version }.second as MumbleProtos.Version
        assertEquals(
            MumbleVersion.encodeV2(
                SessionStateMachine.CLIENT_MAJOR,
                SessionStateMachine.CLIENT_MINOR,
                SessionStateMachine.CLIENT_PATCH,
            ),
            version.versionV2,
        )
        assertEquals(
            MumbleVersion.encodeV1(
                SessionStateMachine.CLIENT_MAJOR,
                SessionStateMachine.CLIENT_MINOR,
                SessionStateMachine.CLIENT_PATCH,
            ),
            version.versionV1,
        )
    }

    @Test
    fun tunneledAudioReachesTheListener() = runTest {
        val ch = FakeChannel()
        // Nonzero baseline: with a zero baseline an implementation that forgot to pass
        // the arrival timestamp would produce the same answer and the test would prove nothing.
        val now = 1_000_000L
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clockNanos = { now })
        val seen = mutableListOf<Pair<ByteArray, Long>>()
        sm.audioListener = SessionStateMachine.AudioListener { payload, arrivalNanos ->
            seen += payload to arrivalNanos
        }

        val audio = MumbleUdpProtos.Audio.newBuilder()
            .setSenderSession(7)
            .setFrameNumber(3)
            .setOpusData(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()
        val payload = byteArrayOf(0) + audio.toByteArray()
        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, payload))

        assertEquals(1, seen.size)
        assertArrayEquals(payload, seen[0].first)
        assertEquals(now, seen[0].second)
    }

    @Test
    fun tunneledAudioWithNoListenerIsIgnored() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope)
        // No listener attached — the frame must be dropped, not throw.
        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, byteArrayOf(0, 1, 2)))
    }

    @Test
    fun aServerBelowOnePointFiveIsRejected() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 4, 287))
            .build()))

        val state = sm.state.value
        assertTrue("expected Failed, was $state", state is ConnectionState.Failed)
        assertEquals(FailReason.VERSION_TOO_OLD, (state as ConnectionState.Failed).reason)
    }

    // version_v2 was introduced in 1.5, so a real server old enough to be refused can only announce
    // itself in the legacy encoding. Rejecting setVersionV2(1,4,x) proves nothing about that server.
    @Test
    fun aLegacyEncodedServerBelowOnePointFiveIsRejected() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(1, 4, 287))
            .build()))

        val state = sm.state.value
        assertTrue("expected Failed, was $state", state is ConnectionState.Failed)
        assertEquals(FailReason.VERSION_TOO_OLD, (state as ConnectionState.Failed).reason)
    }

    // Guards the `major == 1 &&` conjunction against being flattened to a bare `minor < 5`.
    @Test
    fun aFutureMajorIsAccepted() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(2, 0, 0))
            .build()))

        assertEquals(ConnectionState.Handshaking, sm.state.value)
    }

    @Test
    fun exactlyOnePointFiveIsAccepted() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 5, 0))
            .build()))

        // Still handshaking — the version check must not settle a terminal state on a good server.
        assertEquals(ConnectionState.Handshaking, sm.state.value)
    }

    @Test
    fun serverVersionIsPublishedEvenWhenTooOld() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        sm.onFrame(frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 4, 287))
            .build()))

        // The UI names the offending version in its error, so it must survive the failure.
        assertEquals("1.4.287", sm.serverVersion.value?.toString())
    }

    @Test
    fun versionEncodingMatchesProtocol() {
        assertEquals((1L shl 48) or (5L shl 32), MumbleVersion.encodeV2(1, 5, 0))
        assertEquals((1 shl 16) or (5 shl 8), MumbleVersion.encodeV1(1, 5, 0))
    }

    // Only in-range components were covered before, so nothing caught components overflowing their
    // field and corrupting the neighbour. Upstream clamps; these pin that rather than wrapping.
    @Test
    fun legacyEncodingClampsRatherThanOverflowingIntoTheNextField() {
        // Version 2 exists because patch can exceed 255, so this is the realistic case: without a
        // clamp, 1.5.1000 encodes as 1.7.232 and the server reads a minor the client never claimed.
        assertEquals(1, v1Major(MumbleVersion.encodeV1(1, 5, 1000)))
        assertEquals(5, v1Minor(MumbleVersion.encodeV1(1, 5, 1000)))
        assertEquals(255, v1Patch(MumbleVersion.encodeV1(1, 5, 1000)))

        assertEquals(1, v1Major(MumbleVersion.encodeV1(1, 1000, 0)))
        assertEquals(255, v1Minor(MumbleVersion.encodeV1(1, 1000, 0)))

        assertEquals(65535, v1Major(MumbleVersion.encodeV1(70000, 0, 0)))
        assertEquals(0, v1Major(MumbleVersion.encodeV1(-1, 0, 0)))
    }

    @Test
    fun version2EncodingClampsEachComponentToItsOwnField() {
        assertEquals(1, v2Major(MumbleVersion.encodeV2(1, 0, 70000)))
        assertEquals(0, v2Minor(MumbleVersion.encodeV2(1, 0, 70000)))
        assertEquals(65535, v2Patch(MumbleVersion.encodeV2(1, 0, 70000)))

        assertEquals(1, v2Major(MumbleVersion.encodeV2(1, 70000, 0)))
        assertEquals(65535, v2Minor(MumbleVersion.encodeV2(1, 70000, 0)))

        // A negative sign-extends across every field when unguarded.
        assertEquals(1, v2Major(MumbleVersion.encodeV2(1, -1, 0)))
        assertEquals(0, v2Minor(MumbleVersion.encodeV2(1, -1, 0)))
    }

    private fun v1Major(v: Int) = (v ushr 16) and 0xFFFF
    private fun v1Minor(v: Int) = (v ushr 8) and 0xFF
    private fun v1Patch(v: Int) = v and 0xFF
    private fun v2Major(v: Long) = ((v ushr 48) and 0xFFFF).toInt()
    private fun v2Minor(v: Long) = ((v ushr 32) and 0xFFFF).toInt()
    private fun v2Patch(v: Long) = ((v ushr 16) and 0xFFFF).toInt()

    @Test
    fun requestUserStatsAsksForOneSessionAndOnlyItsMutableNumbers() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        assertTrue(sm.requestUserStats(9))

        val ask = ch.sent.last { it.first == TcpMessageType.UserStats }.second as MumbleProtos.UserStats
        assertEquals(9, ask.session)
        // Without this the server also sends certificates and the client's IP, which murmur gates
        // on admin rights and nothing here wants.
        assertTrue("stats_only keeps the reply to ping and packet counts", ask.statsOnly)
    }

    @Test
    fun requestUserStatsBeforeSynchronizedIsANoOp() = runTest {
        val ch = FakeChannel()
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }

        assertFalse(sm.requestUserStats(9))
        assertTrue(ch.sent.none { it.first == TcpMessageType.UserStats })
    }

    @Test
    fun aUserStatsReplyPublishesBothOfThatUsersLegs() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder()
                .setSession(9).setTcpPingAvg(23.5f).setUdpPingAvg(18.2f).build()))

        assertEquals(UserStats(9, 23.5f, 18.2f, null, null, null), sm.userStats.value)
    }

    /**
     * Every peer tunnels today, and the server reports the absent leg as a zero average rather
     * than by omitting it. Publishing that zero would claim a round trip of no time at all.
     */
    @Test
    fun aTunnellingPeerHasNoUdpLegRatherThanAZeroOne() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder()
                .setSession(9).setTcpPingAvg(23.5f).setUdpPingAvg(0f).build()))

        assertEquals(UserStats(9, 23.5f, null, null, null, null), sm.userStats.value)
    }

    /**
     * The wire carries a variance, so jitter is its square root — printing the variance would be a
     * number in square milliseconds under a label that says milliseconds.
     */
    @Test
    fun jitterIsTheDeviationOfTheVarianceOnTheWire() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder()
                .setSession(9).setTcpPingAvg(23f).setTcpPingVar(4f).setBandwidth(8060).build()))

        assertEquals(2f, sm.userStats.value?.tcpJitterMillis)
        assertEquals(8060, sm.userStats.value?.bandwidthBitsPerSecond)
    }

    /** Jitter belongs to the leg carrying voice, which is UDP when the server has pinged it. */
    @Test
    fun jitterFollowsTheLegCarryingVoice() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)

        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder()
                .setSession(9).setTcpPingAvg(23f).setTcpPingVar(81f)
                .setUdpPingAvg(18f).setUdpPingVar(4f).build()))

        assertEquals(2f, sm.userStats.value?.jitterMillis)
    }

    /**
     * A server that has not pinged a freshly connected user yet answers with no average. Zero is
     * not a round trip, and publishing it would read as a perfect link rather than as no reading.
     */
    @Test
    fun aReplyWithNoAverageLeavesTheLastReadingAlone() = runTest {
        val ch = FakeChannel()
        val sm = synchronizedMachine(ch, backgroundScope)
        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder().setSession(9).setTcpPingAvg(23.5f).build()))

        sm.onFrame(frame(TcpMessageType.UserStats,
            MumbleProtos.UserStats.newBuilder().setSession(11).build()))

        assertEquals(UserStats(9, 23.5f, null, null, null, null), sm.userStats.value)
    }
}
