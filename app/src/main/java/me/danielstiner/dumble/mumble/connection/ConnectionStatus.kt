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
    data class Connected(val sessionId: Int) : ConnectionStatus
    data class Error(val kind: ErrorKind, val detail: String?) : ConnectionStatus
}

enum class ErrorKind { CONNECT_FAILED, AUTH_REJECTED, TIMEOUT, DISCONNECTED }

/**
 * Protocol state → whole-connection status. Null for [ConnectionState.Disconnected]: it is the state
 * machine's start value and the coordinator, not the protocol, owns the pre-handshake phases.
 */
fun mapState(s: ConnectionState): ConnectionStatus? = when (s) {
    ConnectionState.Disconnected -> null
    ConnectionState.Handshaking -> ConnectionStatus.Handshaking
    is ConnectionState.Synchronized -> ConnectionStatus.Connected(s.sessionId)
    is ConnectionState.Failed -> when (s.reason) {
        FailReason.AUTH_REJECT -> ConnectionStatus.Error(ErrorKind.AUTH_REJECTED, s.detail)
        FailReason.TIMEOUT -> ConnectionStatus.Error(ErrorKind.TIMEOUT, s.detail)
        FailReason.IO -> ConnectionStatus.Error(ErrorKind.DISCONNECTED, s.detail)
    }
}
