package me.danielstiner.dumble.mumble.connection

import com.google.protobuf.ByteString
import me.danielstiner.dumble.hangGuard
import me.danielstiner.dumble.mumble.net.InMemoryPinStore
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.voice.FakeCaptureHandle
import me.danielstiner.dumble.mumble.voice.FakePlayoutEngine
import me.danielstiner.dumble.mumble.voice.FakeVoiceCall
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.mumble.voice.VoiceSender
import me.danielstiner.dumble.mumble.voice.latency.ArrivedPacket
import me.danielstiner.dumble.mumble.voice.latency.CapturedPacket
import me.danielstiner.dumble.mumble.voice.latency.CheckSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/** A latency check's own session: what it is built to do from its connect to its end. */
class MumbleConnectionCheckTest {

    @get:Rule val timeout = hangGuard()

    /** A connection on the rig, with every capture engine and pump it made, and what a check on
     *  it was handed; [wrapCapture] wraps each fake capture engine it opens. */
    private class Live(
        rig: Rig,
        wrapCapture: (FakeCaptureHandle) -> VoiceSender.CaptureHandle = { it },
    ) {
        val transports = mutableListOf<FakeControlTransport>()
        val handles = mutableListOf<FakeCaptureHandle>()
        val senders = mutableListOf<VoiceSender>()
        val captured = mutableListOf<CapturedPacket>()
        val arrived = mutableListOf<ArrivedPacket>()
        /** The newest session's engine. */
        var playout = FakePlayoutEngine()
        val call = FakeVoiceCall()
        val conn = rig.own(MumbleConnection(
            InMemoryPinStore(),
            newCapture = { wrapCapture(FakeCaptureHandle(blocking = false).also { handles += it }) },
            newPlayout = { FakePlayoutEngine().also { playout = it } }, call = call,
            startPump = { senders += it; rig.startPump(it) },
            captureClock = rig.clock, udpClock = rig.clock,
            context = rig.dispatcher, blocking = rig.dispatcher,
        ) { FakeControlTransport { _, _ -> }.also { transports += it } })

        /** Our voice as it reached the server. */
        fun voice() = transports.flatMap { t ->
            t.sentRaw.filter { it.first == TcpMessageType.UDPTunnel }.map { it.second }
        }

        /** One audio packet from the server, through the tunnel. */
        fun hear(session: Int, frameNumber: Long) = transports.last().listener!!.onFrame(TcpFrame(
            TcpMessageType.UDPTunnel.id,
            byteArrayOf(0) + MumbleUdpProtos.Audio.newBuilder()
                .setSenderSession(session).setFrameNumber(frameNumber)
                .setOpusData(ByteString.copyFrom(byteArrayOf(1))).build().toByteArray(),
        ))
    }

    /** Its stop reaches no poll, so its pump never exits. */
    private val wedged: (FakeCaptureHandle) -> VoiceSender.CaptureHandle =
        { object : VoiceSender.CaptureHandle by it { override fun stop() {} } }

    /** Its stop throws, so its release never gets as far as closing the stream. */
    private val stopThrows: (FakeCaptureHandle) -> VoiceSender.CaptureHandle =
        { object : VoiceSender.CaptureHandle by it { override fun stop() { throw IllegalStateException("stop") } } }

    private fun Rig.synchronize(live: Live) {
        startedTransportAt(live.transports, 0).listener!!.onFrame(serverSync(SELF))
        connected(live.conn)
    }

    /** A check's session, connected and synchronized. */
    private fun Rig.checking(live: Live): CheckSession {
        val check = live.conn.connectForCheck(
            MumbleEndpoint.parse("localhost"), "check", "ab12", { live.captured += it }, { live.arrived += it },
        )!!
        synchronize(live)
        return check
    }

    /** The same, with capture open. */
    private fun Rig.capturing(live: Live): CheckSession = checking(live).also {
        live.conn.requestCapture()
        assertSettled("capture must open") { live.handles.size == 1 }
    }

    private fun frame(n: Long, terminator: Boolean = false) =
        FakeCaptureHandle.Step.Frame(byteArrayOf(n.toByte()), n, terminator)

    private fun parse(payload: ByteArray) = MumbleUdpProtos.Audio.parseFrom(payload.copyOfRange(1, payload.size))

    @Test fun aCheckSessionsCaptureGoesToTheCheckAndNeverToTheServer() = deterministic {
        val live = Live(this)
        capturing(live).setGateOpen(true)
        live.handles[0].script(frame(1), frame(3), frame(5, terminator = true))
        settle()
        elapse(20.milliseconds)
        settle()

        assertEquals(listOf(1L, 3L, 5L), live.captured.map { it.frameNumber })
        assertArrayEquals(byteArrayOf(3), live.captured[1].opusData)
        assertEquals(20.milliseconds, live.captured[1].at - live.captured[0].at)
        assertTrue("nothing captured on a check's session reaches the server", live.voice().isEmpty())
    }

    /** Every pump the session opens is built with the check as its wire. */
    @Test fun aCaptureReopenedOnACheckSessionGoesToTheCheckToo() = deterministic {
        val live = Live(this)
        val check = checking(live)
        assertFalse(check.captureOpen.value)
        live.conn.requestCapture()
        assertSettled("capture opens") { check.captureOpen.value }

        live.call.hold()
        assertSettled("a call hold releases capture") { live.handles[0].destroyed }
        assertFalse(check.captureOpen.value)

        live.call.resume()
        assertSettled("capture reopens") { live.handles.size == 2 }
        assertTrue(check.captureOpen.value)

        live.handles[1].script(frame(7))
        settle()
        assertEquals(listOf(7L), live.captured.map { it.frameNumber })
        assertTrue(live.voice().isEmpty())
    }

    @Test fun injectedPacketsGoToTheLoopbackTarget() = deterministic {
        val live = Live(this)
        val check = checking(live)

        assertTrue(check.inject(40, byteArrayOf(4, 5, 6), terminator = true))

        val audio = parse(live.voice().single())
        assertEquals(VoiceSender.SERVER_LOOPBACK_TARGET, audio.target)
        assertEquals(40L, audio.frameNumber)
        assertTrue(audio.isTerminator)
        assertArrayEquals(byteArrayOf(4, 5, 6), audio.opusData.toByteArray())
    }

    @Test fun aCheckSessionIsPushToTalkOnTheChecksOwnGate() = deterministic {
        val live = Live(this)
        val check = capturing(live)
        val handle = live.handles[0]
        assertEquals("the app's own mode is voice activity", TransmitMode.PushToTalk, handle.transmitMode)
        assertFalse("voice activity would have opened it", handle.gateOpen)

        check.setGateOpen(true)
        assertTrue(handle.gateOpen)
        live.conn.setTransmitting(false)
        assertSettled("a Talk release changes nothing") { handle.gateOpen }
        check.setGateOpen(false)
        live.conn.setTransmitting(true)
        assertSettled("nor does a press") { !handle.gateOpen }
    }

    /** The session's own playout engine reports to the check; the receiver knows nothing of it. */
    @Test fun arrivalsAreReportedWithWhatWasAheadOfThem() = deterministic {
        val live = Live(this)
        capturing(live).setGateOpen(true)
        live.handles[0].script(frame(1))
        settle()
        live.playout.aheadSamples = 960
        elapse(30.milliseconds)
        live.hear(SELF, frameNumber = 2)

        val packet = live.arrived.single()
        assertEquals(2L, packet.frameNumber)
        assertEquals(960, packet.aheadSamples)
        assertEquals("on the clock capture's packets are marked on", 30.milliseconds, packet.at - live.captured.single().at)
    }

    /** A check measures one link: after a swap its numbering and its arrivals are another link's. */
    @Test fun aLinkSwapEndsTheCheck() = deterministic {
        val live = Live(this)
        val check = checking(live)

        live.transports[0].listener!!.onClosed(IOException("reset"))
        startedTransportAt(live.transports, 1).listener!!.onFrame(serverSync(SELF))
        connected(live.conn)

        assertTrue(check.ended.value)
        assertFalse(check.inject(40, byteArrayOf(1), terminator = false))
    }

    @Test fun aDisconnectEndsTheCheck() = deterministic {
        val live = Live(this)
        val check = checking(live)
        assertFalse(check.ended.value)

        live.conn.disconnect()

        assertTrue(check.ended.value)
    }

    @Test fun aCheckEndsItsOwnSessionAndNoOther() = deterministic {
        val live = Live(this)
        val check = checking(live)
        check.end()
        assertTrue(check.ended.value)
        assertEquals(ConnectionStatus.Idle, live.conn.status.value)

        live.conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        startedTransportAt(live.transports, 1).listener!!.onFrame(serverSync(SELF))
        connected(live.conn)
        check.end()
        assertSettled("the user's session stands") { live.conn.status.value is ConnectionStatus.Connected }
        assertEquals(2, live.transports.size)
    }

    @Test fun aCheckDoesNotReplaceALiveSession() = deterministic {
        val live = Live(this)
        live.conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        synchronize(live)

        assertNull(live.conn.connectForCheck(MumbleEndpoint.parse("localhost"), "check", "ab12", {}, {}))

        assertSettled("the user's session stands") { live.conn.status.value is ConnectionStatus.Connected }
        assertEquals(1, live.transports.size)
    }

    /** A release whose stop throws never reports a pump exit, and the stream is past using. */
    @Test fun aStopThatThrowsStillClosesCaptureForTheCheck() = deterministic {
        val live = Live(this, wrapCapture = stopThrows)
        val check = capturing(live)
        assertTrue(check.captureOpen.value)

        live.conn.disconnect()
        settle()

        assertFalse(check.captureOpen.value)
    }

    /** No release runs for a pump that ends on its own, yet its stream is closed all the same. */
    @Test fun aCaptureThatDiesClosesForTheCheck() = deterministic {
        val live = Live(this)
        val check = capturing(live)

        live.handles[0].script(FakeCaptureHandle.Step.Unavailable)
        assertSettled("the engine is freed") { live.handles[0].destroyed }

        assertFalse(check.captureOpen.value)
    }

    /** The stream closes when its release does, whether or not the pump ever reports its exit. */
    @Test fun aReleaseClosesCaptureForTheCheckBeforeThePumpExits() = deterministic {
        val live = Live(this, wrapCapture = wedged)
        val check = capturing(live)

        live.call.hold()
        settle()

        assertFalse(check.captureOpen.value)
    }

    /** What a check adds is on its session alone. */
    @Test fun anOrdinarySessionSendsItsCapture() = deterministic {
        val live = Live(this)
        live.conn.connect(MumbleEndpoint.parse("localhost"), "user", null)
        synchronize(live)
        live.conn.requestCapture()
        assertSettled("capture must open") { live.handles.size == 1 }
        live.handles[0].script(frame(1))
        live.hear(SELF, frameNumber = 2)

        assertSettled("capture reaches the server") { live.voice().size == 1 }
        assertEquals(VoiceSender.NORMAL_TALKING_TARGET, parse(live.voice().single()).target)
        assertEquals("the packet played", 1, live.playout.offered.size)
        assertTrue("and was reported to no check", live.arrived.isEmpty())
    }

    private companion object {
        const val SELF = 5
    }
}
