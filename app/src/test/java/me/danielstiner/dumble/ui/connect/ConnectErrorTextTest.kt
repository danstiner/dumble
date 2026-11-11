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

    /** A typed password may be the server's, and right: Murmur rejects the name before checking it. */
    @Test fun aRegisteredNameMarksTheUsernameWhateverPasswordWasTyped() {
        val error = ConnectionStatus.Error(ErrorKind.WRONG_USER_PASSWORD, "Wrong certificate or password for existing user")
        assertEquals(ErrorPlace.USERNAME, connectErrorPlace(error.kind))
        assertEquals("This name is registered: enter its password, or choose another", connectErrorText(error, passwordBlank = true))
        assertEquals("This name is registered to someone else, or that isn't its password", connectErrorText(error, passwordBlank = false))
    }
}
