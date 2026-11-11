package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind

/**
 * Human wording for a failed connect. Our own words rather than the server's reason, which differs
 * between servers; the reason shows only for a rejection we have no words for. Murmur and Grumble
 * answer a blank password with the same reject as a wrong one, hence [passwordBlank].
 */
fun connectErrorText(error: ConnectionStatus.Error, passwordBlank: Boolean): String = when (error.kind) {
    ErrorKind.WRONG_SERVER_PASSWORD -> if (passwordBlank) "This server needs a password" else "Wrong server password"
    ErrorKind.WRONG_USER_PASSWORD -> "This name is registered: enter its password, or choose another"
    ErrorKind.USERNAME_TAKEN -> "Username already in use"
    ErrorKind.USERNAME_INVALID -> "The server won't accept this username"
    ErrorKind.SERVER_FULL -> "The server is full"
    ErrorKind.AUTH_REJECTED -> error.detail?.ifBlank { null } ?: "The server refused the connection"
    ErrorKind.LOCAL_NETWORK_BLOCKED -> "Local network access is off"
    ErrorKind.SERVER_TOO_OLD -> "This server is older than Mumble 1.5"
    ErrorKind.CONNECT_FAILED -> "Couldn't reach the server"
    ErrorKind.TIMEOUT -> "The server didn't answer"
    ErrorKind.DISCONNECTED -> "The connection dropped"
    ErrorKind.KICKED -> "Kicked from the server"
    ErrorKind.BANNED -> "Banned from the server"
}

/** The system's or the server's own words, shown smaller under [connectErrorText]. */
fun connectErrorDetail(error: ConnectionStatus.Error): String? = when (error.kind) {
    // The headline already is the server's reason, or says what our own exception does.
    ErrorKind.AUTH_REJECTED, ErrorKind.LOCAL_NETWORK_BLOCKED -> null
    else -> error.detail?.ifBlank { null }
}

enum class ErrorPlace { USERNAME, PASSWORD, BELOW_CONNECT }

/**
 * A rejection of what was typed into one field shows under that field. A registered name usually
 * belongs to someone else, since Dumble always presents a certificate, so it marks the username.
 */
fun connectErrorPlace(kind: ErrorKind): ErrorPlace = when (kind) {
    ErrorKind.USERNAME_TAKEN, ErrorKind.USERNAME_INVALID, ErrorKind.WRONG_USER_PASSWORD -> ErrorPlace.USERNAME
    ErrorKind.WRONG_SERVER_PASSWORD -> ErrorPlace.PASSWORD
    else -> ErrorPlace.BELOW_CONNECT
}
