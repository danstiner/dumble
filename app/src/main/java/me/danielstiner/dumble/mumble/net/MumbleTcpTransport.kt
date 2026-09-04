package me.danielstiner.dumble.mumble.net

import androidx.annotation.VisibleForTesting
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.danielstiner.dumble.mumble.protocol.MumbleCodec
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.cert.CertificateException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS control channel. [expectedPin] is the stored fingerprint for this endpoint, loaded by the
 * caller before connecting — the trust callback runs inside the handshake and must not block on
 * storage.
 */
class MumbleTcpTransport(
    private val expectedPin: String?,
    private val trustDelegate: X509TrustManager = platformTrustManager(),
    /**
     * Android supplies a real verifier here. A plain Java virtual machine does not: its default is
     * a last-resort hook the runtime consults only after its own check already failed, so it is
     * hardcoded to reject and ignores the session entirely. Injected so tests exercise this class's
     * own decision — when to verify — rather than the platform's answer.
     */
    private val hostNameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val connectTimeoutMs: Int = 10_000,
    private val handshakeTimeoutMs: Int = 10_000,
) : MumbleControlTransport {

    // Small on purpose: this is a low-volume control channel — a couple of handshake frames, then a
    // ping every few seconds. A larger buffer would only let a stalled socket hide longer behind a
    // queue that keeps accepting frames. Revisit if voice is ever tunnelled here (UDPTunnel).
    private val sendQueue = Channel<ByteArray>(capacity = 32)

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var listener: MumbleControlTransport.Listener? = null

    /**
     * One monitor for everything that races: publishing the socket against tearing it down. The
     * blocking socket read stays outside it, so a close never waits on the network. Listener
     * callbacks are not made under it either — the reader delivers them, never nested (see
     * [startPumps]) — so there is no second lock and thus no lock-order hazard.
     */
    private val lock = Any()

    // Volatile, not plain: read lock-free by isConnected and send(). Written only under lock.
    @Volatile private var closed = false

    // The reason the first teardown recorded, which the reader reports. First-to-tear-down wins: a
    // write failure usually surfaces as itself because the reader is parked in a blocking read while
    // writes are sparse, but a fault that fails both directions at once may let the reader's own
    // generic socket-closed exception win instead. Best effort on which cause, not a guarantee.
    @Volatile private var closeCause: Throwable? = null

    /**
     * Test seam: invoked on the connect thread immediately before the publish critical section, so a
     * test can drive a close() into the exact publish window deterministically. Null in production.
     */
    @VisibleForTesting
    @Volatile internal var TESTONLY_beforePublish: (() -> Unit)? = null

    /**
     * Test seam: invoked in the writer loop just before each socket write, so a test can force a
     * write failure and prove it surfaces as onClosed's cause. Null in production.
     */
    @VisibleForTesting
    @Volatile internal var TESTONLY_beforeWrite: (() -> Unit)? = null

    /**
     * Test seam: invoked at the head of the reader coroutine, before it touches the socket, so a
     * test can land a close() in the window between publish and the reader's first dispatch —
     * otherwise reachable only by losing a scheduling race. Null in production.
     */
    @VisibleForTesting
    @Volatile internal var TESTONLY_beforeRead: (() -> Unit)? = null

    val isConnected: Boolean get() = !closed && socket != null

    @Volatile var trustOutcome: TrustOutcome? = null
        private set

    /**
     * Blocking connect and handshake, then starts the pumps. Throws on any failure. Call once per
     * instance: [closed] never resets, so after any teardown a second connect silently refuses to
     * publish. Reconnection is a new instance.
     */
    override suspend fun connect(host: String, port: Int, listener: MumbleControlTransport.Listener) = withContext(Dispatchers.IO) {
        this@MumbleTcpTransport.listener = listener

        // One trust manager per connection attempt: its outcome is per-handshake state.
        val trust = MumbleTrustManager(expectedPin, trustDelegate)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val s = ctx.socketFactory.createSocket() as SSLSocket

        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            // Nagle is on by default, and voice's 137-byte tunnel writes at 50 Hz are exactly the
            // pattern it delays.
            s.tcpNoDelay = true
            s.soTimeout = handshakeTimeoutMs
            s.startHandshake()
            s.soTimeout = 0
            verifyHostNameIfAuthorityValidated(host, s, trust.outcome)
        } catch (t: Throwable) {
            // Nothing owns this socket yet, so if we don't close it here the file descriptor leaks.
            runCatching { s.close() }
            throw t
        }

        trustOutcome = trust.outcome

        TESTONLY_beforePublish?.invoke()
        synchronized(lock) {
            if (closed) {
                // close() raced us while handshaking: never publish, just tear back down. No reader
                // was started, so no onClosed is owed — the listener was never told we opened.
                runCatching { s.close() }
                return@withContext
            }
            socket = s
            startPumps(s)
        }
    }

    override fun remoteAddress(): InetSocketAddress? = socket?.remoteSocketAddress as? InetSocketAddress

    /**
     * Host name checking applies only to the authority-validated path. A pinned certificate is
     * already bound to this exact endpoint by its fingerprint, and a stock Mumble server presents
     * a self-signed certificate whose subject is the literal "Murmur Autogenerated Certificate v2"
     * with no subject alternative name — demanding a name match there would make pinning unusable
     * against almost every real server.
     */
    private fun verifyHostNameIfAuthorityValidated(host: String, socket: SSLSocket, outcome: TrustOutcome?) {
        when (outcome) {
            TrustOutcome.CaValid ->
                if (!hostNameVerifier.verify(host, socket.session)) {
                    throw CertificateException("certificate is valid but was not issued for $host")
                }
            // The fingerprint already binds this certificate to this endpoint.
            TrustOutcome.Pinned -> Unit
            // Unreachable today: checkServerTrusted either throws or records an outcome. Enumerated
            // rather than folded into an early return so that a future path which forgets to record
            // one fails here, instead of reading as "not authority-validated, skip the name check".
            null -> throw CertificateException("handshake completed without a recorded trust outcome")
        }
    }

    private fun startPumps(s: SSLSocket) {
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = cs

        // ATOMIC so the reader always runs its finally, even if a close cancels the scope before this
        // coroutine is dispatched: the finally is the sole place onClosed is delivered, and a listener
        // told the connection opened must always be told it closed.
        cs.launch(start = CoroutineStart.ATOMIC) {
            var cause: Throwable? = null
            try {
                // Inside the try, not before it: a close() that lands between publish and this
                // coroutine's first dispatch has already closed the socket, and getInputStream then
                // throws. Outside, that throw escaped past the finally and the listener was never
                // told the connection closed — the exact guarantee ATOMIC is here to keep.
                TESTONLY_beforeRead?.invoke()
                val input = DataInputStream(s.inputStream.buffered())
                while (isActive) {
                    val frame = MumbleCodec.readFrame(input)
                    // No lock: the reader is the only caller of onFrame, and delivering onClosed from
                    // anywhere but here would let it nest inside onFrame on the same call stack.
                    listener?.onFrame(frame)
                }
            } catch (t: Throwable) {
                cause = t
            } finally {
                // If this is the first teardown, it records `cause`; otherwise an earlier caller's
                // cause already stands. Either way closeCause then holds the authoritative reason, and
                // reporting here — after onFrame has returned — is what keeps onClosed from nesting.
                teardown(cause)
                listener?.onClosed(closeCause)
            }
        }

        cs.launch {
            try {
                // Same window as the reader's: a close() before this dispatch makes getOutputStream
                // throw. Nothing is owed to the listener here, but an uncaught throw would still
                // skip the teardown below and print a spurious stack trace.
                val output = DataOutputStream(s.outputStream.buffered())
                for (bytes in sendQueue) {
                    TESTONLY_beforeWrite?.invoke()
                    output.write(bytes)
                    output.flush()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Tear down recording this write failure as the cause. Closing the socket unblocks
                // the reader, whose finally reports it — unless the reader's own read failed first
                // and won the teardown race (see closeCause).
                teardown(t)
            }
        }
    }

    // Guarded before serializing so a send on a closed channel costs nothing.
    override fun send(type: TcpMessageType, message: MessageLite): Boolean =
        !closed && sendRaw(type, message.toByteArray())

    override fun sendRaw(type: TcpMessageType, payload: ByteArray): Boolean {
        if (closed) return false
        val framed = ByteArrayOutputStream(payload.size + FRAME_HEADER_LEN)
        MumbleCodec.writeFrame(DataOutputStream(framed), type.id, payload)
        return sendQueue.trySend(framed.toByteArray()).isSuccess
    }

    override fun close() = teardown(null)

    /**
     * Idempotent. The first caller wins the [closed] gate, records [closeCause], and snapshots the
     * socket and scope so the actual I/O happens outside the lock — [SSLSocket.close] can block
     * writing a close-notify to a stalled peer, which must never hold up another thread. Does not
     * deliver onClosed: closing the socket unblocks the reader, whose finally is the single delivery
     * point. A close before any reader exists ([connect]'s abort branch) owes no notification.
     */
    private fun teardown(cause: Throwable?) {
        val toClose: SSLSocket?
        val toCancel: CoroutineScope?
        synchronized(lock) {
            if (closed) return
            closed = true
            closeCause = cause
            toClose = socket; socket = null
            toCancel = scope; scope = null
            sendQueue.close()
        }
        // Guard both independently: socket.close() is what unblocks the blocked reader and releases
        // the file descriptor, so a throw from cancel() must never skip it.
        runCatching { toCancel?.cancel() }
        runCatching { toClose?.close() }
    }

    private companion object {
        const val FRAME_HEADER_LEN = 6
    }
}
