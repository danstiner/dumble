package me.danielstiner.dumble.telecom

import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRouteTest {
    @Test fun bluetoothUsesDeviceNameWhenPresent() {
        assertEquals("WH-1000XM4", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, "WH-1000XM4"))
    }

    @Test fun bluetoothFallsBackToGenericWhenNameNullOrBlank() {
        assertEquals("Bluetooth", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, null))
        assertEquals("Bluetooth", AudioRoute.label(CallEndpointCompat.TYPE_BLUETOOTH, "   "))
    }

    @Test fun genericTypesHaveFixedLabels() {
        assertEquals("Wired headset", AudioRoute.label(CallEndpointCompat.TYPE_WIRED_HEADSET))
        assertEquals("Earpiece", AudioRoute.label(CallEndpointCompat.TYPE_EARPIECE))
        assertEquals("Speaker", AudioRoute.label(CallEndpointCompat.TYPE_SPEAKER))
    }

    @Test fun unknownTypeFallsBack() {
        assertEquals("Unknown", AudioRoute.label(999))
    }

    @Test fun iconMapsEachType() {
        assertEquals(AudioRoute.RouteIcon.BLUETOOTH, AudioRoute.icon(CallEndpointCompat.TYPE_BLUETOOTH))
        assertEquals(AudioRoute.RouteIcon.WIRED, AudioRoute.icon(CallEndpointCompat.TYPE_WIRED_HEADSET))
        assertEquals(AudioRoute.RouteIcon.EARPIECE, AudioRoute.icon(CallEndpointCompat.TYPE_EARPIECE))
        assertEquals(AudioRoute.RouteIcon.SPEAKER, AudioRoute.icon(CallEndpointCompat.TYPE_SPEAKER))
        assertEquals(AudioRoute.RouteIcon.UNKNOWN, AudioRoute.icon(999))
    }
}
