package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.connection.ConnectionStatus
import me.danielstiner.dumble.mumble.connection.ErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectErrorTextTest {
    @Test fun aServerPasswordRejectionReadsByWhetherOneWasTyped() {
        val error = ConnectionStatus.Error(ErrorKind.WRONG_SERVER_PASSWORD, "Invalid server password")
        assertEquals("This server needs a password", connectErrorText(error, passwordBlank = true))
        assertEquals("Wrong server password", connectErrorText(error, passwordBlank = false))
    }
}
