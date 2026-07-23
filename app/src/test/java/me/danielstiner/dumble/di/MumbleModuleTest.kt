package me.danielstiner.dumble.di

import org.junit.Assert.assertEquals
import org.junit.Test

class MumbleModuleTest {
    @Test
    fun pinStoreNameIsStable() {
        // Changing this silently orphans stored pins; every known server becomes first contact again.
        assertEquals("mumble_pins", MumbleModule.PIN_STORE_NAME)
    }

    @Test
    fun serverConfigNameIsStable() {
        // Changing this orphans stored profiles; every server becomes new again on reconnect.
        assertEquals("server_config", MumbleModule.SERVER_CONFIG_NAME)
    }
}
