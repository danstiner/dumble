package com.example.drumble.mumble.net

import com.example.drumble.mumble.util.MumbleLog
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Connected UDP channel. Receive: dedicated blocking thread, pooled buffers,
 * decrypt at the edge, arrival timestamps in CLOCK_MONOTONIC (System.nanoTime).
 * Send: in-line on the caller's thread (voice-send hot path / ping cold path);
 * CryptState.encrypt is synchronized so concurrent callers are safe.
 */
class MumbleUdpTransport(
    private val crypt: CryptState,
    private val listener: Listener,
    /** Runs on the receive thread before the loop — Android layer sets URGENT_AUDIO priority here. */
    private val threadSetup: () -> Unit = {},
) {
    companion object {
        private const val TAG = "MumbleUdpTransport"
        private const val BUFFER_SIZE = 2048
        private const val RESYNC_QUIET_NANOS = 5_000_000_000L
    }

    interface Listener {
        /** Decrypted plaintext [u8 type][protobuf]. buf is reused — copy what you keep. Receive-thread context. */
        fun onUdpPlaintext(buf: ByteArray, len: Int, arrivalNanos: Long)
        fun onUdpError(e: Exception)
        /** Persistent decrypt failures — owner should send an empty CryptSetup. */
        fun requestCryptResync()
    }

    @Volatile private var running = false
    private var channel: DatagramChannel? = null
    private var receiveThread: Thread? = null

    private val sendLock = Any()
    private val sendCipher = ByteArray(BUFFER_SIZE)
    private val sendBuf = ByteBuffer.wrap(sendCipher)

    fun connect(host: String, port: Int) {
        val ch = DatagramChannel.open()
        ch.connect(InetSocketAddress(host, port))
        ch.configureBlocking(true)
        channel = ch
        running = true
        receiveThread = Thread({ receiveLoop(ch) }, "mumble-udp-recv").apply {
            isDaemon = true; priority = Thread.MAX_PRIORITY; start()
        }
    }

    private fun receiveLoop(ch: DatagramChannel) {
        threadSetup()
        val wire = ByteBuffer.allocate(BUFFER_SIZE) // heap: array() feeds decrypt
        val plain = ByteArray(BUFFER_SIZE)
        while (running) {
            try {
                wire.clear()
                val n = ch.read(wire)
                val arrival = System.nanoTime()
                if (n < 5 || !crypt.isValid()) continue
                val plainLen = crypt.decrypt(wire.array(), n, plain)
                if (plainLen < 0) {
                    if (crypt.lastGoodElapsedNanos() > RESYNC_QUIET_NANOS &&
                        crypt.lastRequestElapsedNanos() > RESYNC_QUIET_NANOS) {
                        crypt.markResyncRequested()
                        listener.requestCryptResync()
                    }
                    continue
                }
                listener.onUdpPlaintext(plain, plainLen, arrival)
            } catch (e: Exception) {
                if (running) { listener.onUdpError(e); MumbleLog.w(TAG, "receive error", e) }
                return
            }
        }
    }

    /** Encrypt+send in-line. Thread-safe. False when crypt not ready or I/O fails. */
    fun send(plaintext: ByteArray, len: Int): Boolean {
        if (!crypt.isValid() || !running) return false
        synchronized(sendLock) {
            return try {
                val n = crypt.encrypt(plaintext, len, sendCipher)
                sendBuf.position(0).limit(n)
                channel?.write(sendBuf)
                true
            } catch (e: Exception) {
                MumbleLog.w(TAG, "send failed", e); false
            }
        }
    }

    fun close() {
        running = false
        runCatching { channel?.close() } // unblocks the receive thread
        receiveThread?.join(500)
    }
}
