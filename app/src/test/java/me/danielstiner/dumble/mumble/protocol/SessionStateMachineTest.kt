package me.danielstiner.dumble.mumble.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.chat.DenyReason
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateMachineTest {

    private class FakeChannel : ControlChannel {
        val sent = mutableListOf<Pair<TcpMessageType, MessageLite>>()
        var closed = false
        override fun send(type: TcpMessageType, message: MessageLite): Boolean {
            sent += type to message; return true
        }
        override fun close() { closed = true }
    }

    private fun frame(type: TcpMessageType, message: MessageLite) =
        TcpFrame(type.id, message.toByteArray())

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

    // The ping send is deliberately not fatal: a full queue is backpressure, and a closed transport
    // is a death the reader already reports. Pin that, so treating it as fatal fails here.
    @Test
    fun aPingThatCannotBeQueuedDoesNotEndTheSession() = runTest {
        val ch = FailingChannel(TcpMessageType.Ping)
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(9).build()))

        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS * 3 + 100)

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
    fun pingEchoPublishesRoundTripTime() = runTest {
        val ch = FakeChannel()
        // Nonzero baseline: with a zero baseline an implementation that forgot to subtract the
        // send time would produce the same answer and the test would prove nothing.
        var now = 1_000_000L
        val sm = SessionStateMachine(ch, "tester", null, backgroundScope, clockNanos = { now }).apply { start() }
        sm.onFrame(frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(1).build()))
        advanceTimeBy(SessionStateMachine.PING_INTERVAL_MS + 100)

        now = 6_000_000L   // 5 milliseconds after the ping was sent
        val echoed = ch.sent.last { it.first == TcpMessageType.Ping }.second as MumbleProtos.Ping
        sm.onFrame(frame(TcpMessageType.Ping, echoed))

        assertEquals(5.0, sm.roundTripMillis.value!!, 0.001)
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
}
