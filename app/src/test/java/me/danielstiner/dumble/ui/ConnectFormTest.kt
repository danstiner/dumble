package me.danielstiner.dumble.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectFormTest {
    private val valid = ConnectForm(host = "mumble.example.com", port = "64738", username = "dan", password = "")

    @Test fun validFormHasNoErrors() {
        assertTrue(validate(valid).isValid)
    }

    @Test fun blankHostRejected() {
        assertFalse(validate(valid.copy(host = "  ")).isValid)
        assertEquals("Host required", validate(valid.copy(host = "")).host)
    }

    @Test fun blankUsernameRejected() {
        assertEquals("Username required", validate(valid.copy(username = "")).username)
    }

    @Test fun portBounds() {
        assertNull(validate(valid.copy(port = "1")).port)
        assertNull(validate(valid.copy(port = "65535")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "0")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "65536")).port)
        assertEquals("Port 1-65535", validate(valid.copy(port = "abc")).port)
    }

    @Test fun toConfigMapsBlankPasswordToNull() {
        val c = valid.toConfig()
        assertNull(c.password)
        assertEquals("mumble.example.com", c.host)
        assertEquals(64738, c.port)
        assertEquals("dan", c.username)
    }

    @Test fun toConfigKeepsPasswordAndTrims() {
        val c = valid.copy(host = "  h  ", username = "  u  ", password = "pw").toConfig()
        assertEquals("h", c.host)
        assertEquals("u", c.username)
        assertEquals("pw", c.password)
    }
}
