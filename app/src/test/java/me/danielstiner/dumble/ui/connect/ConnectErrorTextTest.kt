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

    @Test fun aRegisteredNameMarksThePasswordOnceOneWasTyped() {
        val error = ConnectionStatus.Error(ErrorKind.WRONG_USER_PASSWORD, "Wrong certificate or password for existing user")
        assertEquals(ErrorPlace.USERNAME, connectErrorPlace(error.kind, passwordBlank = true))
        assertEquals("This name is registered: enter its password, or choose another", connectErrorText(error, passwordBlank = true))
        assertEquals(ErrorPlace.PASSWORD, connectErrorPlace(error.kind, passwordBlank = false))
        assertEquals("This name is registered, and this isn't its password", connectErrorText(error, passwordBlank = false))
    }
}
