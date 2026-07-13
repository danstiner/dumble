package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.net.VoiceTransportMode
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.MumbleCodec
import me.danielstiner.dumble.mumble.voice.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VoiceTransportTest {
    private class ScriptedEngine(frames: Int) : VoiceEngine {
        private var next = 0L
        private val total = frames
        val incoming = mutableListOf<Triple<Long, Int, Long>>()
        val done = CountDownLatch(1)
        override fun start() {}
        override fun stop() {}
        override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
            if (next >= total) { done.countDown(); Thread.sleep(5); return null }
            val fn = next++
            return VoiceFrame(ByteArray(20) { fn.toByte() }, 20, fn)
        }
        override fun onIncomingFrame(opusData: ByteArray, offset: Int, length: Int,
                                     frameNumber: Long, senderSession: Int, arrivalNanos: Long) {
            incoming.add(Triple(frameNumber, senderSession, arrivalNanos))
        }
    }

    @Test fun sendsFramesOverUdpWithLoopbackTarget() {
        val engine = ScriptedEngine(3)
        val sent = LinkedBlockingQueue<ByteArray>()
        val vt = VoiceTransport(
            engine = engine,
            modeProvider = { VoiceTransportMode.UDP },
            udpSend = { buf, n -> sent.add(buf.copyOf(n)); true },
            tunnelSend = { _, _ -> fail("tunnel used in UDP mode"); false },
        )
        vt.start()
        assertTrue(engine.done.await(2, TimeUnit.SECONDS))
        vt.stop()
        assertEquals(3, sent.size)
        val first = sent.take()
        assertEquals(MumbleCodec.UDP_TYPE_AUDIO, first[0].toInt())
        val audio = MumbleUdpProtos.Audio.parser().parseFrom(first, 1, first.size - 1)
        assertEquals(31, audio.target)
        assertEquals(0L, audio.frameNumber)
    }

    @Test fun tunnelModeUsesTunnelSender() {
        val engine = ScriptedEngine(1)
        val tunneled = LinkedBlockingQueue<ByteArray>()
        val vt = VoiceTransport(
            engine = engine,
            modeProvider = { VoiceTransportMode.TCP_TUNNEL },
            udpSend = { _, _ -> fail("udp used in tunnel mode"); false },
            tunnelSend = { buf, n -> tunneled.add(buf.copyOf(n)); true },
        )
        vt.start()
        assertTrue(engine.done.await(2, TimeUnit.SECONDS))
        vt.stop()
        assertEquals(1, tunneled.size)
    }

    @Test fun routesIncomingAudioAndPing() {
        val engine = ScriptedEngine(0)
        var pingTs = -1L
        val vt = VoiceTransport(engine, { VoiceTransportMode.UDP }, { _, _ -> true }, { _, _ -> true },
            onUdpPing = { ts, _ -> pingTs = ts })
        val audio = MumbleUdpProtos.Audio.newBuilder().setContext(0).setSenderSession(9)
            .setFrameNumber(5L)
            .setOpusData(com.google.protobuf.ByteString.copyFrom(ByteArray(12) { 3 })).build()
        val buf = ByteArray(256)
        val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, buf)
        vt.onPlaintext(buf, n, arrivalNanos = 777L)
        assertEquals(Triple(5L, 9, 777L), engine.incoming.single())

        val ping = MumbleUdpProtos.Ping.newBuilder().setTimestamp(1234L).build()
        val m = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_PING, ping, buf)
        vt.onPlaintext(buf, m, arrivalNanos = 888L)
        assertEquals(1234L, pingTs)
    }

    @Test fun terminatorFrameSetsIsTerminator() {
        val captured = ArrayList<ByteArray>()
        val engine = object : VoiceEngine {
            var sent = false
            override fun start() {}
            override fun stop() {}
            override fun nextOutgoingFrame(timeoutNanos: Long): VoiceFrame? {
                if (sent) return null
                sent = true
                return VoiceFrame(ByteArray(0), 0, 4, isTerminator = true)
            }
            override fun onIncomingFrame(o: ByteArray, off: Int, len: Int, fn: Long, s: Int, a: Long) {}
        }
        val t = VoiceTransport(engine, { VoiceTransportMode.UDP },
            udpSend = { buf, n -> captured.add(buf.copyOf(n)); true },
            tunnelSend = { _, _ -> true })
        t.start(); Thread.sleep(50); t.stop()
        val wire = captured.first()
        val audio = MumbleUdpProtos.Audio.parser().parseFrom(wire, 1, wire.size - 1)
        assertTrue(audio.isTerminator)
    }
}
