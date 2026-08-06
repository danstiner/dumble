package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {

    private fun route(type: Type, name: String = "", id: String = "id-$type") =
        AudioRoute(id, type, name)

    /** The only type whose platform name carries information. */
    @Test fun bluetoothPrefersTheDeviceName() {
        assertEquals("OpenRun by Shokz", route(Type.BLUETOOTH, "OpenRun by Shokz").label)
    }

    /**
     * The blank fallback is defensive, not observed — see [AudioRoute.label]'s KDoc for what the
     * platform actually hands us instead of blank. Pins the fallback's own behaviour, in case it is
     * ever reachable, not a real device case.
     */
    @Test fun aBlankBluetoothNameFallsBackToAFixedLabel() {
        assertEquals("Bluetooth", route(Type.BLUETOOTH, "").label)
        assertEquals("Bluetooth", route(Type.BLUETOOTH, "   ").label)
    }

    /**
     * What this project actually ships without BLUETOOTH_CONNECT below API 34: the platform
     * substitutes this literal string rather than a blank one (`EndpointUtils.kt:446-448`), and it
     * reads through unchanged — the blank fallback above never engages for it.
     */
    @Test fun theRealNoPermissionPlaceholderReadsThroughUnchanged() {
        assertEquals("Bluetooth Device", route(Type.BLUETOOTH, "Bluetooth Device").label)
    }

    /** Everything else is named by its type; the platform's own string is ignored. */
    @Test fun everyOtherTypeHasAFixedLabel() {
        assertEquals("Wired headset", route(Type.WIRED_HEADSET, "ignored").label)
        assertEquals("Speaker", route(Type.SPEAKER, "ignored").label)
        assertEquals("Earpiece", route(Type.EARPIECE, "ignored").label)
        assertEquals("Streaming", route(Type.STREAMING, "ignored").label)
        assertEquals("Unknown", route(Type.UNKNOWN, "ignored").label)
    }

    /**
     * Menu order is ours, not the library's: core-telecom's V2 path sorts and its legacy path is
     * not documented to, so leaving it to them makes the order depend on which one ran.
     */
    @Test fun routesSortByHardwarePreference() {
        val unsorted = listOf(
            route(Type.EARPIECE), route(Type.SPEAKER),
            route(Type.UNKNOWN), route(Type.BLUETOOTH, "Shokz"),
            route(Type.STREAMING), route(Type.WIRED_HEADSET),
        )
        assertEquals(
            listOf(
                Type.WIRED_HEADSET, Type.BLUETOOTH, Type.SPEAKER,
                Type.EARPIECE, Type.STREAMING, Type.UNKNOWN,
            ),
            unsorted.sorted().map { it.type },
        )
    }

    /** Two headsets paired at once is the case the whole id-keyed design exists for. */
    @Test fun twoOfATypeSortByLabel() {
        val a = route(Type.BLUETOOTH, "Zeta", id = "1")
        val b = route(Type.BLUETOOTH, "Alpha", id = "2")
        assertEquals(listOf("Alpha", "Zeta"), listOf(a, b).sorted().map { it.label })
    }
}
