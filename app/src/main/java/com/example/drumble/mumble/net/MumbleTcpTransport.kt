package com.example.drumble.mumble.net

import com.example.drumble.mumble.protocol.ControlChannel
import com.example.drumble.mumble.protocol.MumbleCodec
import com.example.drumble.mumble.protocol.TcpFrame
import com.example.drumble.mumble.protocol.TcpMessageType
import com.example.drumble.mumble.util.MumbleLog
import com.google.protobuf.MessageLite
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class MumbleTcpTransport(private val pinStore: PinStore) : ControlChannel {
    companion object {
        private const val TAG = "MumbleTcpTransport"
        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    interface Listener {
        /** Called on the reader coroutine. */
        fun onFrame(frame: TcpFrame)
        /** cause == null for local close, non-null for remote/error close. Called at most once. */
        fun onClosed(cause: Throwable?)
    }

    private val closed = AtomicBoolean(false)
    private val closedReported = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    private val sendQueue = Channel<ByteArray>(capacity = 256)
    private var scope: CoroutineScope? = null

    /**
     * Guards the socket-assign / closed-check / startLoops sequence in [connect] against
     * [close] running concurrently (e.g. someone calls close() while the TLS handshake is
     * still blocking). `closed` itself stays the idempotency gate for close(); this lock only
     * serializes the two critical sections so a socket can never be assigned after (or while)
     * close() has already run its cleanup.
     */
    private val connectLock = Any()

    /** Blocking TLS connect + handshake, then starts reader/writer loops. Throws on failure. */
    suspend fun connect(host: String, port: Int, listener: Listener) = withContext(Dispatchers.IO) {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TofuTrustManager(pinStore, "$host:$port")), null)
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.startHandshake()
        synchronized(connectLock) {
            if (closed.get()) {
                // close() ran (or raced us) while we were handshaking. Don't publish the
                // socket or start loops on a transport that's already torn down — just shut
                // the freshly-handshaked socket back down ourselves.
                runCatching { s.close() }
            } else {
                socket = s
                startLoops(
                    DataInputStream(s.inputStream.buffered()),
                    DataOutputStream(s.outputStream.buffered()),
                    listener,
                )
            }
        }
    }

    /** Split out for JVM tests: drive with piped streams, no TLS needed. */
    internal fun startLoops(input: DataInputStream, output: DataOutputStream, listener: Listener) {
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch(CoroutineName("mumble-tcp-read")) {
            try {
                while (isActive) listener.onFrame(MumbleCodec.readFrame(input))
            } catch (t: Throwable) {
                val local = closed.get()
                if (closedReported.compareAndSet(false, true))
                    listener.onClosed(if (local) null else t)
                close()
            }
        }
        sc.launch(CoroutineName("mumble-tcp-write")) {
            try {
                // A normal local close() closes sendQueue, which makes this for-loop end on
                // its own (no exception) once drained — that path must NOT report an error.
                // Only a genuine output.write/flush failure throws and lands in the catch
                // below, mirroring the reader's failure handling.
                for (framed in sendQueue) { output.write(framed); output.flush() }
            } catch (t: Throwable) {
                MumbleLog.w(TAG, "writer stopped", t)
                val local = closed.get()
                if (closedReported.compareAndSet(false, true))
                    listener.onClosed(if (local) null else t)
                close()
            }
        }
    }

    /** Thread-safe, non-blocking. False if the queue is full or transport closed. */
    override fun send(type: TcpMessageType, message: MessageLite): Boolean {
        if (closed.get()) return false
        val bos = ByteArrayOutputStream(6 + message.serializedSize)
        MumbleCodec.writeFrame(DataOutputStream(bos), type.id, message.toByteArray())
        return sendQueue.trySend(bos.toByteArray()).isSuccess
    }

    /** Thread-safe, non-blocking. False if the queue is full or transport closed. */
    override fun sendRaw(type: TcpMessageType, payload: ByteArray, len: Int): Boolean {
        if (closed.get()) return false
        val bos = ByteArrayOutputStream(6 + len)
        val dos = DataOutputStream(bos)
        dos.writeShort(type.id); dos.writeInt(len); dos.write(payload, 0, len)
        return sendQueue.trySend(bos.toByteArray()).isSuccess
    }

    override fun close() {
        // closed.compareAndSet is the idempotency gate — only the winning caller runs
        // cleanup. The lock below just serializes that cleanup against connect()'s
        // socket-assign section so a socket can't be assigned after we've already closed.
        if (closed.compareAndSet(false, true)) {
            synchronized(connectLock) {
                sendQueue.close()
                runCatching { socket?.close() }
                scope?.cancel()
            }
        }
    }
}
