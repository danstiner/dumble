package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason

/** The whole-connection state the UI observes: TLS + trust + protocol, unified. */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Connecting : ConnectionStatus
    data class AwaitingTrust(val fingerprint: String) : ConnectionStatus
    data class PinMismatch(val stored: String, val presented: String) : ConnectionStatus
    data object Handshaking : ConnectionStatus
    /** [gen] is this connection's own generation, one per connect(); [sessionId] is the server's,
     *  and changes with every link. */
    data class Connected(val gen: Int, val sessionId: Int) : ConnectionStatus
    /** The session's link died and a replacement is being opened under the same generation;
     *  [lastSessionId] is the server session the UI's own row still belongs to. */
    data class Reconnecting(val gen: Int, val lastSessionId: Int) : ConnectionStatus
    data class Error(val kind: ErrorKind, val detail: String?) : ConnectionStatus
}

/**
 * Whether the session is still going. The other four outlive the platform call that carried them —
 * connect()'s catch ends the call but leaves its Error or trust prompt up — so a late hangup must
 * not retire them. Exhaustive so a new status has to be classified rather than default to false.
 */
val ConnectionStatus.ongoing: Boolean
    get() = when (this) {
        ConnectionStatus.Connecting, ConnectionStatus.Handshaking, is ConnectionStatus.Connected,
        is ConnectionStatus.Reconnecting ->
            true
        ConnectionStatus.Idle, is ConnectionStatus.AwaitingTrust, is ConnectionStatus.PinMismatch,
        is ConnectionStatus.Error -> false
    }

/** Our own server session, or null when there is none: through a relink the UI keeps reading the
 *  row of the session the dead link had. */
val ConnectionStatus.mySession: Int?
    get() = when (this) {
        is ConnectionStatus.Connected -> sessionId
        is ConnectionStatus.Reconnecting -> lastSessionId
        else -> null
    }

enum class ErrorKind { CONNECT_FAILED, AUTH_REJECTED, TIMEOUT, DISCONNECTED, SERVER_TOO_OLD }

/**
 * Protocol state → whole-connection status. Null for [ConnectionState.Disconnected]: it is the state
 * machine's start value and the coordinator, not the protocol, owns the pre-handshake phases.
 * [gen] is stamped into [ConnectionStatus.Connected]; the protocol layer never learns it.
 */
fun mapState(gen: Int, s: ConnectionState): ConnectionStatus? = when (s) {
    ConnectionState.Disconnected -> null
    ConnectionState.Handshaking -> ConnectionStatus.Handshaking
    is ConnectionState.Synchronized -> ConnectionStatus.Connected(gen, s.sessionId)
    is ConnectionState.Failed -> when (s.reason) {
        FailReason.AUTH_REJECT -> ConnectionStatus.Error(ErrorKind.AUTH_REJECTED, s.detail)
        FailReason.TIMEOUT -> ConnectionStatus.Error(ErrorKind.TIMEOUT, s.detail)
        FailReason.IO -> ConnectionStatus.Error(ErrorKind.DISCONNECTED, s.detail)
        FailReason.VERSION_TOO_OLD -> ConnectionStatus.Error(ErrorKind.SERVER_TOO_OLD, s.detail)
    }
}
