package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsePortTest {
    @Test fun blankIsDefault() = assertEquals(PortInput.Ok(MumbleEndpoint.DEFAULT_PORT), parsePort(""))
    @Test fun validInRange() = assertEquals(PortInput.Ok(64738), parsePort("64738"))
    @Test fun nonNumericIsError() = assertTrue(parsePort("abc") is PortInput.Invalid)
    @Test fun outOfRangeIsError() = assertTrue(parsePort("70000") is PortInput.Invalid)
}
