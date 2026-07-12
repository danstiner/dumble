package com.example.drumble.mumble

import com.example.drumble.mumble.model.MumbleModel
import com.example.drumble.mumble.net.CryptState
import com.example.drumble.mumble.proto.MumbleProtos
import com.example.drumble.mumble.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionStateMachineTest {
    private class FakeChannel : ControlChannel {
        val sent = mutableListOf<Pair<TcpMessageType, MessageLite>>()
        var closedCount = 0
        override fun send(type: TcpMessageType, message: MessageLite): Boolean { sent.add(type to message); return true }
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

    @Test fun udpTunnelRouted() {
        sm.start("dan", null)
        val payload = byteArrayOf(0, 1, 2, 3)
        sm.onFrame(TcpFrame(TcpMessageType.UDPTunnel.id, payload))
        assertArrayEquals(payload, events.tunneled.single())
    }
}
