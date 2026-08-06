package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {

    private fun route(type: Type, name: String = "") = AudioRoute("id-$type", type, name)

    private val earpiece = route(Type.EARPIECE)
    private val speaker = route(Type.SPEAKER)
    private val wired = route(Type.WIRED_HEADSET)
    private val bluetooth = route(Type.BLUETOOTH, "Shokz")

    /**
     * Bluetooth *availability* is the trigger, not what is currently active — the stock app reads
     * `getSupportedRouteMask()`, not `getRoute()`.
     */
    @Test fun bluetoothAnywhereInTheListCallsForAMenu() {
        assertTrue(routeMenuNeeded(listOf(earpiece, speaker, bluetooth)))
        assertFalse(routeMenuNeeded(listOf(earpiece, speaker)))
        assertFalse(routeMenuNeeded(emptyList()))
    }

    /** Wired is not Bluetooth. Stock keeps the toggle here; the prototype did not, and was wrong. */
    @Test fun aWiredHeadsetDoesNotCallForAMenu() {
        assertFalse(routeMenuNeeded(listOf(earpiece, speaker, wired)))
    }

    @Test fun theToggleGoesToSpeakerAndBack() {
        assertEquals(speaker, speakerToggleTarget(listOf(earpiece, speaker), current = earpiece))
        assertEquals(earpiece, speakerToggleTarget(listOf(earpiece, speaker), current = speaker))
    }

    /** `ROUTE_WIRED_OR_EARPIECE`: coming off the speaker prefers what is plugged in. */
    @Test fun leavingTheSpeakerPrefersAWiredHeadsetOverTheEarpiece() {
        assertEquals(wired, speakerToggleTarget(listOf(earpiece, speaker, wired), current = speaker))
    }

    /** Nothing to toggle to — the control has to disable rather than send a request nobody can serve. */
    @Test fun aToggleWithNoDestinationIsNull() {
        assertNull(speakerToggleTarget(emptyList(), current = null))
        assertNull(speakerToggleTarget(listOf(speaker), current = speaker))
    }
}
