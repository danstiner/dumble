package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.SessionStateMachine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ComparableTimeMark

/**
 * One TLS connect and what lives exactly as long as it: the protocol session on it, the UDP
 * socket keyed by that session's cipher, which of the two carries our voice, and the scope of
 * the collectors that republish its flows. Connect-once, like the transport it wraps: the
 * session above it outlives it, and a replacement is a new Link.
 */
internal class Link(
    val transport: MumbleControlTransport,
    val stateMachine: SessionStateMachine,
    /** Opened once the control connection is up, closed with the link; inert in between if it
     *  could not be opened, and voice stays tunneled. */
    val udp: MumbleUdpTransport,
    /** Which transport carries our voice; `MumbleConnection.sendVoice` routes by it. */
    val path: VoicePath,
    /** The collectors that republish this link's flows. */
    val childScope: CoroutineScope,
    /** Never cancelled; where the blocking closes run. */
    private val scope: CoroutineScope,
) {
    /** When the link's state machine synchronized, on the driver's clock; null until then. A
     *  link that has lived [MumbleConnection.HEALTHY_AFTER] past this was a working path. */
    @Volatile var syncedAt: ComparableTimeMark? = null

    private val closed = AtomicBoolean(false)

    /**
     * Any thread; nothing here blocks; at most once. Safe before [transport] has connected: a
     * handshake that finishes before the deferred close lands is published and torn down a
     * moment later, one that finishes after it is discarded unpublished, and the collectors are
     * already cancelled either way.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // IO because the TLS close blocks: SSLSocket.close can stall writing close-notify to a
        // dead peer, and one slow socket must not delay anything else. UDP first: its close
        // never blocks, and datagrams would otherwise keep arriving while the TLS close stalls.
        scope.launch(Dispatchers.IO) {
            runCatching { udp.close() }
            runCatching { transport.close() }
        }
        // The collectors never finish on their own; nothing else stops them.
        childScope.cancel()
    }
}
