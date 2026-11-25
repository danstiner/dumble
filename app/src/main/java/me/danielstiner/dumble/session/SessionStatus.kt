package me.danielstiner.dumble.session

import me.danielstiner.dumble.mumble.proto.MumbleProtos.Reject.RejectType
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason

/** The whole-connection state the UI observes: TLS + trust + protocol, unified. */
sealed interface SessionStatus {
    data object Idle : SessionStatus
    data object Connecting : SessionStatus
    data class AwaitingTrust(val fingerprint: String) : SessionStatus
    data class PinMismatch(val stored: String, val presented: String) : SessionStatus
    data object Handshaking : SessionStatus
    /** [gen] is this connection's own generation, one per connect(); [sessionId] is the server's,
     *  and changes with every link. */
    data class Connected(val gen: Int, val sessionId: Int) : SessionStatus
    /** The session's link died and a replacement is being opened under the same generation;
     *  [lastSessionId] is the server session the UI's own row still belongs to. */
    data class Reconnecting(val gen: Int, val lastSessionId: Int) : SessionStatus
    data class Error(val kind: ErrorKind, val detail: String?) : SessionStatus
}

/** Our own server session, or null when there is none: through a reconnect the UI keeps reading the
 *  row of the session the dead link had. */
val SessionStatus.mySession: Int?
    get() = when (this) {
        is SessionStatus.Connected -> sessionId
        is SessionStatus.Reconnecting -> lastSessionId
        else -> null
    }

/**
 * Why a connection ended, for the UI to put in its own words. [SessionStatus.Error.detail]
 * carries the system's or the server's text beside it.
 */
enum class ErrorKind {
    /** Any failure without a kind of its own, mostly a socket or TLS that never came up. */
    CONNECT_FAILED,
    /** Android blocked a LAN address: the app lacks local network access. */
    LOCAL_NETWORK_BLOCKED,
    /** The first connect got no answer in time. A live link that goes quiet reconnects instead. */
    TIMEOUT,
    /** The link failed after the socket came up, or reconnecting gave up. */
    DISCONNECTED,
    SERVER_TOO_OLD,
    /** Also how the server removes us when another device connects on the same certificate. */
    KICKED,
    BANNED,

    // The server's reject types; AUTH_REJECTED is any without a kind of its own.
    AUTH_REJECTED,
    WRONG_SERVER_PASSWORD,
    /** The name is registered, and neither our certificate nor the password matches it. */
    WRONG_USER_PASSWORD,
    USERNAME_TAKEN,
    USERNAME_INVALID,
    SERVER_FULL,
}

/**
 * Protocol state → whole-connection status. Null for [ConnectionState.Disconnected]: it is the state
 * machine's start value and the coordinator, not the protocol, owns the pre-handshake phases.
 * [gen] is stamped into [SessionStatus.Connected]; the protocol layer never learns it.
 */
fun mapState(gen: Int, s: ConnectionState): SessionStatus? = when (s) {
    ConnectionState.Disconnected -> null
    ConnectionState.Handshaking -> SessionStatus.Handshaking
    is ConnectionState.Synchronized -> SessionStatus.Connected(gen, s.sessionId)
    is ConnectionState.Failed -> when (s.reason) {
        FailReason.AUTH_REJECT -> SessionStatus.Error(rejectKind(s.rejectType), s.detail)
        FailReason.TIMEOUT -> SessionStatus.Error(ErrorKind.TIMEOUT, s.detail)
        FailReason.IO -> SessionStatus.Error(ErrorKind.DISCONNECTED, s.detail)
        FailReason.VERSION_TOO_OLD -> SessionStatus.Error(ErrorKind.SERVER_TOO_OLD, s.detail)
        FailReason.KICKED -> SessionStatus.Error(ErrorKind.KICKED, s.detail)
        FailReason.BANNED -> SessionStatus.Error(ErrorKind.BANNED, s.detail)
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
