package me.danielstiner.dumble.mumble.connection

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.UdpTransport
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.ConnectionStateMachine
import me.danielstiner.dumble.mumble.protocol.DeafenState
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.TcpFrame
import me.danielstiner.dumble.mumble.protocol.TcpMessageType
import me.danielstiner.dumble.mumble.protocol.UserStats
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One TLS connect and what lives exactly as long as it: the state machine on it, the UDP
 * socket keyed by its cipher, which of the two carries our voice, and the scope of
 * the collectors that republish its flows. Connect-once, like the transport it wraps: the
 * session above it outlives it, and a replacement is a new Connection.
 */
internal class Connection(
    private val options: ConnectionOptions,
    /** Called once, with [ConnectionOptions.pin]. */
    newTransport: (expectedPin: String?) -> MumbleControlTransport,
    newUdp: (CryptState, MumbleUdpTransport.Listener) -> UdpTransport,
    /** Received voice, from either transport. */
    private val onVoice: (ByteArray, Int) -> Unit,
    /** The default network this connection was dialed on, or null when there was none to know. */
    val network: Any?,
    /** The app state's count of network changes when this connection was dialed, so whether the
     *  network has changed since is one compare. */
    val dialedUnder: Int,
    /** Everything that lives exactly as long as the connection: its collectors and its state
     *  machine. */
    val scope: CoroutineScope,
    /** The app state's; never cancelled, where the blocking closes run. */
    private val blockingScope: CoroutineScope,
    bootClock: TimeSource.WithComparableMarks,
    pingIntervalMs: Long,
    /** The session's connect count, for logs. */
    private val gen: Int,
) {
    private val transport = newTransport(options.pin)
    private val stateMachine = ConnectionStateMachine(
        transport, options.username, options.password, scope,
        bootClock = bootClock,
        pingIntervalMs = pingIntervalMs,
        voiceBitrate = options.voiceBitrate,
    )
    private val listener = object : MumbleControlTransport.Listener {
        override fun onFrame(f: TcpFrame) = stateMachine.onFrame(f)
        override fun onClosed(cause: Throwable?) = stateMachine.onClosed(cause)
    }
    /** Which transport carries our voice; [sendVoice] routes by it. */
    private val path = VoicePath()
    /** Opened once the control transport is up, closed with the connection; inert in between if it
     *  could not be opened, and voice stays tunneled. */
    private val udp = newUdp(stateMachine.crypt, object : MumbleUdpTransport.Listener {
        private var heard = false   // the server chose UDP for our downlink; logged once
        override fun onVoicePacket(buf: ByteArray, len: Int) {
            if (!heard) {
                heard = true
                Log.i(TAG, "UDP downlink: first voice packet gen=$gen")
            }
            onVoice(buf, len)
        }
        override fun onPingReply(roundTrip: Duration) {
            if (path.onPingAnswered(roundTrip)) stateMachine.udpPingAnswered(roundTrip)
        }
        // Demoting brings the downlink back with our next spurt; the tunneled ping does it
        // for a client that never speaks, since the server never re-learns an address
        // (docs/network.md, When UDP stops working).
        override fun onPingsUnanswered() {
            Log.w(TAG, "UDP pings unanswered; voice on the tunnel gen=$gen")
            path.demote()
            transport.sendRaw(TcpMessageType.UDPTunnel, TUNNEL_PING)
        }
        override fun requestCryptResync() { stateMachine.requestCryptResync() }
    })

    private val closed = AtomicBoolean(false)

    val state: StateFlow<ConnectionState> get() = stateMachine.state
    val sync: ConnectionStateMachine.Sync? get() = stateMachine.sync
    val serverVersion: StateFlow<ServerVersion?> get() = stateMachine.serverVersion
    val roundTripTime: StateFlow<Duration?> get() = stateMachine.roundTripTime
    val voicePath: StateFlow<VoicePath.State> get() = path.state
    val lastServerReplyAt: StateFlow<ComparableTimeMark?> get() = stateMachine.lastServerReplyAt
    val channelTree: StateFlow<ChannelTree> get() = stateMachine.channelTree
    val userStats: StateFlow<UserStats?> get() = stateMachine.userStats
    val messages: StateFlow<List<ChatMessage>> get() = stateMachine.messages

    /** The blocking TLS connect. Throws what the transport's connect throws. */
    suspend fun connect() {
        transport.connect(options.endpoint.host, options.endpoint.port, listener)
    }

    /** After [connect]: opens the UDP socket, wires the state machine to it, starts the
     *  protocol. */
    fun start() {
        // Before stateMachine.start(): the ping that registers our address fires the instant
        // CryptSetup keys the cipher, and from then on the server sends our downlink over UDP
        // whether or not we ever transmit (docs/network.md, UDP voice), so it has to land on a
        // socket already listening. One that cannot be opened costs the session nothing but UDP.
        transport.remoteAddress()?.let { remote ->
            runCatching { udp.open(remote) }
                .onFailure { Log.w(TAG, "no UDP socket; voice stays tunneled", it) }
        }
        stateMachine.udpPing = { udp.sendPing() }
        stateMachine.audioListener = ConnectionStateMachine.AudioListener { payload ->
            onVoice(payload, payload.size)
        }
        stateMachine.start()
    }

    /** Pump thread. UDP when it carries our voice, the tunnel otherwise or when the socket
     *  refuses: a refused datagram goes through the tunnel in the same call, and the next one
     *  already starts there. */
    fun sendVoice(payload: ByteArray): Boolean {
        if (path.state.value.onUdp) {
            if (udp.send(payload, payload.size)) return true
            path.demote()
        }
        return transport.sendRaw(TcpMessageType.UDPTunnel, payload)
    }

    fun sendSelfState(state: DeafenState) { stateMachine.sendSelfState(state) }

    fun sendText(text: String): Boolean = stateMachine.sendText(text)

    fun requestUserStats(session: Int) { stateMachine.requestUserStats(session) }

    /** Raised outside any lock a caller holds, so a check-then-write against it under one can
     *  still lose the race. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Any thread; nothing here blocks; at most once. Safe before [transport] has connected: a
     * handshake in flight is aborted, one that finished before the close landed is published and
     * torn down a moment later, and the collectors are already cancelled either way.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // On the app state's blocking scope because the TLS close blocks: SSLSocket.close can
        // stall writing close-notify to a dead peer, and one slow socket must not delay anything
        // else. UDP first: its close never blocks, and datagrams would otherwise keep arriving
        // while the TLS close stalls.
        blockingScope.launch {
            runCatching { udp.close() }
            runCatching { transport.close() }
        }
        // The collectors never finish on their own; nothing else stops them.
        scope.cancel()
    }

    private companion object {
        const val TAG = "MumbleConn"

        /** Tunneled for its side effect, never answered. Any frame of two bytes or more would
         *  do; this one is honest about what it is. */
        val TUNNEL_PING: ByteArray =
            byteArrayOf(1) + MumbleUdpProtos.Ping.newBuilder().setTimestamp(1).build().toByteArray()
    }
}
