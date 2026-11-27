package me.danielstiner.dumble.session

import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason
import me.danielstiner.dumble.mumble.proto.MumbleProtos.Reject.RejectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.TestTimeSource

class SessionStatusMappingTest {
    private val since = TestTimeSource().markNow()

    @Test fun disconnectedIsNotSurfaced() =
        assertNull(mapState(ConnectionState.Disconnected) { since })

    @Test fun handshakingMapsThrough() =
        assertEquals(SessionStatus.Handshaking, mapState(ConnectionState.Handshaking) { since })

    @Test fun synchronizedBecomesConnected() =
        assertEquals(
            SessionStatus.Connected(since, sessionId = 42),
            mapState(ConnectionState.Synchronized(42)) { since },
        )

    @Test fun eachRejectTypeMapsToItsKind() =
        mapOf(
            RejectType.WrongServerPW to ErrorKind.WRONG_SERVER_PASSWORD,
            RejectType.WrongUserPW to ErrorKind.WRONG_USER_PASSWORD,
            RejectType.UsernameInUse to ErrorKind.USERNAME_TAKEN,
            RejectType.InvalidUsername to ErrorKind.USERNAME_INVALID,
            RejectType.ServerFull to ErrorKind.SERVER_FULL,
            RejectType.NoCertificate to ErrorKind.AUTH_REJECTED,
            null to ErrorKind.AUTH_REJECTED,
        ).forEach { (type, kind) ->
            assertEquals(
                "$type",
                SessionStatus.Error(kind, "no"),
                errorFor(ConnectionState.Failed(FailReason.AUTH_REJECT, "no", rejectType = type)),
            )
        }

    @Test fun timeoutMapsToTimeout() =
        assertEquals(
            SessionStatus.Error(ErrorKind.TIMEOUT, "slow"),
            errorFor(ConnectionState.Failed(FailReason.TIMEOUT, "slow")),
        )

    @Test fun ioBecomesDisconnected() =
        assertEquals(
            SessionStatus.Error(ErrorKind.DISCONNECTED, "dropped"),
            errorFor(ConnectionState.Failed(FailReason.IO, "dropped")),
        )

    @Test fun versionTooOldBecomesServerTooOld() =
        assertEquals(
            SessionStatus.Error(ErrorKind.SERVER_TOO_OLD, "server 1.4.287 — need >= 1.5"),
            errorFor(ConnectionState.Failed(FailReason.VERSION_TOO_OLD, "server 1.4.287 — need >= 1.5")),
        )
}
