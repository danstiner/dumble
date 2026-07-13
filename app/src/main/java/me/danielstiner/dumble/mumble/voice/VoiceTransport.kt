package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.VoiceTransportMode
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.MumbleCodec
import me.danielstiner.dumble.mumble.util.MumbleLog
import com.google.protobuf.ByteString

/**
 * Voice hot path: dedicated send thread pulls frames from the seam, wraps them in
 * Audio protobuf plaintext, routes via UDP or TCP tunnel per the selector's mode.
 * Incoming plaintext (either transport) routes back through onPlaintext.
 */
class VoiceTransport(
    private val engine: VoiceEngine,
    private val modeProvider: () -> VoiceTransportMode,
    private val udpSend: (ByteArray, Int) -> Boolean,
    private val tunnelSend: (ByteArray, Int) -> Boolean,
    private val target: Int = LOOPBACK_TARGET,
    private val onUdpPing: (timestamp: Long, arrivalNanos: Long) -> Unit = { _, _ -> },
    /** Runs on the voice-send thread before the loop — Android layer sets URGENT_AUDIO here. */
    private val threadSetup: () -> Unit = {},
) {
    companion object {
        private const val TAG = "VoiceTransport"
        const val LOOPBACK_TARGET = 31
        const val FRAME_TIMEOUT_NANOS = 20_000_000L
        private const val WIRE_BUF_SIZE = 1024
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        engine.start()
        thread = Thread({ sendLoop() }, "mumble-voice-send").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    fun stop() {
        running = false
        engine.stop()
        thread?.join(500)
    }

    private fun sendLoop() {
        threadSetup()
        val wireBuf = ByteArray(WIRE_BUF_SIZE)
        while (running) {
            val frame = engine.nextOutgoingFrame(FRAME_TIMEOUT_NANOS) ?: continue
            val audio = MumbleUdpProtos.Audio.newBuilder()
                .setTarget(target)
                .setFrameNumber(frame.frameNumber)
                .setOpusData(ByteString.copyFrom(frame.opusData, 0, frame.length))
                .build()
            val n = MumbleCodec.writeUdpPlaintext(MumbleCodec.UDP_TYPE_AUDIO, audio, wireBuf)
            val ok = when (modeProvider()) {
                VoiceTransportMode.UDP -> udpSend(wireBuf, n)
                VoiceTransportMode.TCP_TUNNEL -> tunnelSend(wireBuf, n)
            }
            if (!ok) MumbleLog.d(TAG, "voice frame ${frame.frameNumber} dropped by transport")
        }
    }

    /** Route decrypted UDP plaintext or tunneled UDPTunnel payload. Receive-thread context. */
    fun onPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long) {
        if (len < 2) return
        when (buf[0].toInt()) {
            MumbleCodec.UDP_TYPE_AUDIO -> {
                val audio = MumbleUdpProtos.Audio.parser().parseFrom(buf, 1, len - 1)
                engine.onIncomingFrame(audio.opusData.toByteArray(), 0, audio.opusData.size(),
                    audio.frameNumber, audio.senderSession, arrivalNanos)
            }
            MumbleCodec.UDP_TYPE_PING -> {
                val ping = MumbleUdpProtos.Ping.parser().parseFrom(buf, 1, len - 1)
                onUdpPing(ping.timestamp, arrivalNanos)
            }
            else -> MumbleLog.d(TAG, "unknown UDP plaintext type ${buf[0]}")
        }
    }
}
