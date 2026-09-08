package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusMappingTest {
    @Test fun disconnectedIsNotSurfaced() =
        assertNull(mapState(3, ConnectionState.Disconnected))

    @Test fun handshakingMapsThrough() =
        assertEquals(ConnectionStatus.Handshaking, mapState(3, ConnectionState.Handshaking))

    /** The generation is the caller's, not the protocol's: the state machine never learns it. */
    @Test fun synchronizedBecomesConnected() =
        assertEquals(
            ConnectionStatus.Connected(gen = 3, sessionId = 42),
            mapState(3, ConnectionState.Synchronized(42)),
        )

    @Test fun authRejectBecomesAuthRejected() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.AUTH_REJECTED, "no"),
            mapState(3, ConnectionState.Failed(FailReason.AUTH_REJECT, "no")),
        )

    @Test fun timeoutMapsToTimeout() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.TIMEOUT, "slow"),
            mapState(3, ConnectionState.Failed(FailReason.TIMEOUT, "slow")),
        )

    @Test fun ioBecomesDisconnected() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.DISCONNECTED, "dropped"),
            mapState(3, ConnectionState.Failed(FailReason.IO, "dropped")),
        )

    @Test fun versionTooOldBecomesServerTooOld() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.SERVER_TOO_OLD, "server 1.4.287 — need >= 1.5"),
            mapState(3, ConnectionState.Failed(FailReason.VERSION_TOO_OLD, "server 1.4.287 — need >= 1.5")),
        )
}
