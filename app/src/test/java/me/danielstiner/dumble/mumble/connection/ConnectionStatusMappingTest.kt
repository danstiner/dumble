package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.protocol.ConnectionState
import me.danielstiner.dumble.mumble.protocol.FailReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionStatusMappingTest {
    @Test fun disconnectedIsNotSurfaced() =
        assertNull(mapState(ConnectionState.Disconnected))

    @Test fun handshakingMapsThrough() =
        assertEquals(ConnectionStatus.Handshaking, mapState(ConnectionState.Handshaking))

    @Test fun synchronizedBecomesConnected() =
        assertEquals(ConnectionStatus.Connected(42), mapState(ConnectionState.Synchronized(42)))

    @Test fun authRejectBecomesAuthRejected() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.AUTH_REJECTED, "no"),
            mapState(ConnectionState.Failed(FailReason.AUTH_REJECT, "no")),
        )

    @Test fun timeoutMapsToTimeout() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.TIMEOUT, "slow"),
            mapState(ConnectionState.Failed(FailReason.TIMEOUT, "slow")),
        )

    @Test fun ioBecomesDisconnected() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.DISCONNECTED, "dropped"),
            mapState(ConnectionState.Failed(FailReason.IO, "dropped")),
        )

    @Test fun versionTooOldBecomesServerTooOld() =
        assertEquals(
            ConnectionStatus.Error(ErrorKind.SERVER_TOO_OLD, "server 1.4.287 — need >= 1.5"),
            mapState(ConnectionState.Failed(FailReason.VERSION_TOO_OLD, "server 1.4.287 — need >= 1.5")),
        )
}
