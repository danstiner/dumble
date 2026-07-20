package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.model.MumbleModel
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateMachineTest {
    private class FakeChannel : ControlChannel {
        val sent = mutableListOf<Pair<TcpMessageType, MessageLite>>()
        var closedCount = 0
        override fun send(type: TcpMessageType, message: MessageLite): Boolean { sent.add(type to message); return true }
        override fun sendRaw(type: TcpMessageType, payload: ByteArray, len: Int) = true
        override fun close() { closedCount++ }
    }

    private class RecordingEvents : SessionStateMachine.Events {
        var cryptReady = 0
        var lastRttMs = -1.0
        val tunneled = mutableListOf<ByteArray>()
        override fun onCryptReady() { cryptReady++ }
        override fun onTcpRtt(rttMs: Double) { lastRttMs = rttMs }
        override fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long) {
            tunneled.add(plaintext.copyOf(len))
        }
    }

    private lateinit var channel: FakeChannel
    private lateinit var events: RecordingEvents
    private lateinit var crypt: CryptState
    private lateinit var model: MumbleModel
    private lateinit var sm: SessionStateMachine
    private var nowNanos = 1_000_000_000L

    private val key = ByteArray(16) { it.toByte() }
    private val nA = ByteArray(16) { (0x40 + it).toByte() }
    private val nB = ByteArray(16) { (0x80 + it).toByte() }

    @Before fun setUp() {
        channel = FakeChannel(); events = RecordingEvents()
        crypt = CryptState(); model = MumbleModel()
        sm = SessionStateMachine(channel, model, crypt, events, clockNanos = { nowNanos })
    }

    private fun frame(type: TcpMessageType, msg: MessageLite) = sm.onFrame(TcpFrame(type.id, msg.toByteArray()))

    private fun fullCryptSetup() = MumbleProtos.CryptSetup.newBuilder()
        .setKey(ByteString.copyFrom(key))
        .setClientNonce(ByteString.copyFrom(nA))
        .setServerNonce(ByteString.copyFrom(nB)).build()

    @Test fun happyPathToSynchronized() {
        sm.start(username = "dan", password = null)
        assertEquals(TcpMessageType.Version, channel.sent[0].first)
        assertEquals(TcpMessageType.Authenticate, channel.sent[1].first)
        val auth = channel.sent[1].second as MumbleProtos.Authenticate
        assertEquals("dan", auth.username); assertTrue(auth.opus)

        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 5, 634)).build())
        frame(TcpMessageType.CryptSetup, fullCryptSetup())
        assertEquals(1, events.cryptReady); assertTrue(crypt.isValid())

        frame(TcpMessageType.ChannelState, MumbleProtos.ChannelState.newBuilder().setChannelId(0).setName("Root").build())
        frame(TcpMessageType.UserState, MumbleProtos.UserState.newBuilder().setSession(7).setName("dan").build())
        frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(7).build())

        assertEquals(ConnectionState.Synchronized(7), sm.state.value)
        assertEquals("Root", model.state.value.channels[0]!!.name)
        assertEquals(7, model.state.value.sessionId)
    }

    @Test fun versionTooOldFails() {
        sm.start("dan", null)
        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 4, 287)).build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.VERSION_TOO_OLD)
        assertEquals(1, channel.closedCount)
    }

    @Test fun rejectFails() {
        sm.start("dan", null)
        frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("bad pw").build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.AUTH_REJECT)
    }

    @Test fun cryptResyncFlows() {
        sm.start("dan", null)
        frame(TcpMessageType.CryptSetup, fullCryptSetup())
        frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder()
            .setServerNonce(ByteString.copyFrom(nB)).build())
        assertEquals(1, crypt.stats().resync)
        val sentBefore = channel.sent.size
        frame(TcpMessageType.CryptSetup, MumbleProtos.CryptSetup.newBuilder().build())
        val reply = channel.sent.drop(sentBefore).single()
        assertEquals(TcpMessageType.CryptSetup, reply.first)
        assertArrayEquals(crypt.encryptNonceCopy(), (reply.second as MumbleProtos.CryptSetup).clientNonce.toByteArray())
    }

    @Test fun pingEchoYieldsRttAndRemoteStats() {
        sm.start("dan", null)
        nowNanos = 5_000_000_000L
        sm.sendPing()
        nowNanos = 5_020_000_000L
        frame(TcpMessageType.Ping, MumbleProtos.Ping.newBuilder()
            .setTimestamp(5_000_000_000L).setGood(10).setLate(1).setLost(2).setResync(0).build())
        assertEquals(20.0, events.lastRttMs, 0.5)
        assertEquals(10, crypt.stats().remoteGood)
    }

    @Test fun sendPingSelfReportsMeasuredRtt() {
        sm.start("dan", null)
        sm.sendPing(tcpPingAvgMs = 15.5f, udpPingAvgMs = 20.25f)
        val ping = channel.sent.last { it.first == TcpMessageType.Ping }.second as MumbleProtos.Ping
        assertTrue("tcp_ping_avg set", ping.hasTcpPingAvg()); assertEquals(15.5f, ping.tcpPingAvg, 0.001f)
        assertTrue("udp_ping_avg set", ping.hasUdpPingAvg()); assertEquals(20.25f, ping.udpPingAvg, 0.001f)
    }

    @Test fun sendPingOmitsPingAvgWhenUnmeasured() {
        sm.start("dan", null)
        sm.sendPing()                                   // no measurement yet (default null)
        sm.sendPing(tcpPingAvgMs = -1f, udpPingAvgMs = -1f)   // sentinel "unknown" from selector (-1.0)
        channel.sent.filter { it.first == TcpMessageType.Ping }.forEach {
            val p = it.second as MumbleProtos.Ping
            assertFalse("tcp_ping_avg must stay unset when unmeasured", p.hasTcpPingAvg())
            assertFalse("udp_ping_avg must stay unset when unmeasured", p.hasUdpPingAvg())
        }
    }

    @Test fun udpTunnelRouted() {
        sm.start("dan", null)
        val payload = byteArrayOf(0, 1, 2, 3)
        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, payload))
        assertArrayEquals(payload, events.tunneled.single())
    }

    @Test fun versionV1FallbackDecodes() {
        sm.start("dan", null)
        // Server sends ONLY version_v1 (legacy encoding major<<16|minor<<8|patch) for 1.4.0 → too old.
        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(1, 4, 0)).build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.VERSION_TOO_OLD)
    }

    @Test fun versionV1FallbackAccepts15() {
        sm.start("dan", null)
        frame(TcpMessageType.Version, MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(1, 5, 0)).build())
        // 1.5 via v1-only encoding must NOT fail the version gate.
        assertFalse(sm.state.value is ConnectionState.Failed)
    }

    @Test fun sendSelfMuteBroadcastsUserStateTrue() {
        sm.sendSelfMute(true)
        val sent = channel.sent.single()
        assertEquals(TcpMessageType.UserState, sent.first)
        assertTrue((sent.second as MumbleProtos.UserState).selfMute)
    }

    @Test fun sendSelfMuteBroadcastsUserStateFalse() {
        sm.sendSelfMute(false)
        val sent = channel.sent.single()
        assertEquals(TcpMessageType.UserState, sent.first)
        assertFalse((sent.second as MumbleProtos.UserState).selfMute)
    }

    // --- Connecting-phase deadline (spans TLS handshake + auth -> ServerSync) ---

    @Test fun connectingPhaseTimesOutToFailedTimeout() = runTest {
        // Armed the moment connecting begins (before the blocking TCP connect/handshake). No
        // ServerSync ever arrives — the deadline must elapse and fail the connection.
        sm.armConnectTimeout(this, timeoutMs = 10_000L)
        advanceUntilIdle()
        val s = sm.state.value
        assertTrue("state=$s", s is ConnectionState.Failed && s.reason == FailReason.TIMEOUT)
        assertEquals("timeout must close the channel to unblock handshake/reader", 1, channel.closedCount)
    }

    @Test fun synchronizedBeforeDeadlineDoesNotTimeout() = runTest {
        val job = sm.armConnectTimeout(this, timeoutMs = 10_000L)
        sm.start("dan", null)
        frame(TcpMessageType.ServerSync, MumbleProtos.ServerSync.newBuilder().setSession(7).build())
        assertEquals(ConnectionState.Synchronized(7), sm.state.value)
        advanceUntilIdle()  // the deadline would have elapsed here; the watchdog already completed on Synchronized
        assertEquals("healthy connect: no timeout, no late overwrite", ConnectionState.Synchronized(7), sm.state.value)
        assertEquals("no timeout close on a healthy connect", 0, channel.closedCount)
        assertTrue("watchdog completes as soon as a terminal state is reached", job.isCompleted)
    }

    @Test fun failedBeforeDeadlineDoesNotAlsoTimeout() = runTest {
        val job = sm.armConnectTimeout(this, timeoutMs = 10_000L)
        sm.start("dan", null)
        frame(TcpMessageType.Reject, MumbleProtos.Reject.newBuilder().setReason("bad pw").build())
        val s = sm.state.value
        assertTrue(s is ConnectionState.Failed && s.reason == FailReason.AUTH_REJECT)
        advanceUntilIdle()  // the pre-existing failure must win; the deadline must not overwrite it with TIMEOUT
        val after = sm.state.value
        assertTrue("first failure wins — stays AUTH_REJECT", after is ConnectionState.Failed && after.reason == FailReason.AUTH_REJECT)
        assertTrue(job.isCompleted)
    }
}
