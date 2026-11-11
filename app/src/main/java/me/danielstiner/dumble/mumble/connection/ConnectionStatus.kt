package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.proto.MumbleProtos.Reject.RejectType
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

/** Our own server session, or null when there is none: through a reconnect the UI keeps reading the
 *  row of the session the dead link had. */
val ConnectionStatus.mySession: Int?
    get() = when (this) {
        is ConnectionStatus.Connected -> sessionId
        is ConnectionStatus.Reconnecting -> lastSessionId
        else -> null
    }

/**
 * Why a connection ended, for the UI to put in its own words. [ConnectionStatus.Error.detail]
 * carries the system's or the server's text beside it.
 */
enum class ErrorKind {
    /** The socket or TLS never came up. */
    CONNECT_FAILED,
    /** Android dropped the connect: the app lacks local network access, and the address is on the LAN. */
    LOCAL_NETWORK_BLOCKED,
    /** No answer in time: to the socket connect, to the handshake, or to pings on a live link. */
    TIMEOUT,
    /** The link failed after the socket came up. */
    DISCONNECTED,
    /** The server's protocol is older than Mumble 1.5. */
    SERVER_TOO_OLD,
    /** Removed by a moderator, or by another device connecting on the same certificate. The
     *  detail names who and why, where the server says. */
    KICKED,
    /** Removed and banned by a moderator; the detail names who and why, where the server says. */
    BANNED,

    // Authentication rejected. The five kinds below are the server's reject types with words of
    // our own; AUTH_REJECTED is every other type.

    /** A rejection Dumble has no words for; the detail is the server's reason, shown as is. */
    AUTH_REJECTED,
    /** Also sent for a blank password: Murmur and Grumble reject a missing password and a wrong
     *  one alike. */
    WRONG_SERVER_PASSWORD,
    /** The name is registered, and neither our certificate nor the password matches it. Dumble
     *  always presents a certificate, so this usually means the name belongs to someone else. */
    WRONG_USER_PASSWORD,
    USERNAME_TAKEN,
    /** The server's username rules refuse the name. */
    USERNAME_INVALID,
    SERVER_FULL,
}

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
        FailReason.AUTH_REJECT -> ConnectionStatus.Error(rejectKind(s.rejectType), s.detail)
        FailReason.TIMEOUT -> ConnectionStatus.Error(ErrorKind.TIMEOUT, s.detail)
        FailReason.IO -> ConnectionStatus.Error(ErrorKind.DISCONNECTED, s.detail)
        FailReason.VERSION_TOO_OLD -> ConnectionStatus.Error(ErrorKind.SERVER_TOO_OLD, s.detail)
        FailReason.KICKED -> ConnectionStatus.Error(ErrorKind.KICKED, s.detail)
        FailReason.BANNED -> ConnectionStatus.Error(ErrorKind.BANNED, s.detail)
    }
}

private fun rejectKind(type: RejectType?): ErrorKind = when (type) {
    RejectType.WrongServerPW -> ErrorKind.WRONG_SERVER_PASSWORD
    RejectType.WrongUserPW -> ErrorKind.WRONG_USER_PASSWORD
    RejectType.UsernameInUse -> ErrorKind.USERNAME_TAKEN
    RejectType.InvalidUsername -> ErrorKind.USERNAME_INVALID
    RejectType.ServerFull -> ErrorKind.SERVER_FULL
    else -> ErrorKind.AUTH_REJECTED
}
