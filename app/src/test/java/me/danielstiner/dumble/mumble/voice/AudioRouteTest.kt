package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {

    private fun route(type: Type, name: String = "", id: String = "id-$type") =
        AudioRoute(id, type, name)

    /** The only type labelled by its platform name. */
    @Test fun bluetoothPrefersTheDeviceName() {
        assertEquals("OpenRun by Shokz", route(Type.BLUETOOTH, "OpenRun by Shokz").label)
    }

    /**
     * Pins the fallback itself. A nameless device never arrives blank — the platform reports the
     * phone's model instead — so only a name of spaces reaches it.
     */
    @Test fun aBlankBluetoothNameFallsBackToAFixedLabel() {
        assertEquals("Bluetooth", route(Type.BLUETOOTH, "").label)
        assertEquals("Bluetooth", route(Type.BLUETOOTH, "   ").label)
    }

    /** Everything else is named by its type; the platform's own string is ignored. */
    @Test fun everyOtherTypeHasAFixedLabel() {
        assertEquals("Wired headset", route(Type.WIRED_HEADSET, "ignored").label)
        assertEquals("Speaker", route(Type.SPEAKER, "ignored").label)
        assertEquals("Earpiece", route(Type.EARPIECE, "ignored").label)
        assertEquals("Unknown", route(Type.UNKNOWN, "ignored").label)
    }

    /** Menu order is ours — hardware preference — not whatever order the platform lists devices in. */
    @Test fun routesSortByHardwarePreference() {
        val unsorted = listOf(
            route(Type.EARPIECE), route(Type.SPEAKER),
            route(Type.UNKNOWN), route(Type.BLUETOOTH, "Shokz"),
            route(Type.WIRED_HEADSET),
        )
        assertEquals(
            listOf(
                Type.WIRED_HEADSET, Type.BLUETOOTH, Type.SPEAKER,
                Type.EARPIECE, Type.UNKNOWN,
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
