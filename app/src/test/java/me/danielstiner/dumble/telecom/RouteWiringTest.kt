package me.danielstiner.dumble.telecom

import android.os.ParcelUuid
import androidx.core.telecom.CallEndpointCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Covers everything between the platform's endpoints and the menu: the
 * `CallEndpointCompat` -> `AudioRoute` mapping, `RouteState.publish`'s sort and dedupe, and the
 * `retired` guard. None of it was covered before this file, and a swapped constant in
 * `toAudioRoute`'s `when` would map every endpoint to UNKNOWN while everything downstream still
 * *appeared* to work — rows render, taps forward — showing only as wrong icons and a control stuck
 * in toggle mode.
 *
 * Robolectric, not for any UI reason, but because `CallEndpointCompat`'s constructor needs a real
 * `ParcelUuid` — `@RequiresApi(O)`, comfortably below this project's minSdk 30, same reasoning
 * `CallControlsTest` already relies on for its own Robolectric run.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RouteWiringTest {

    // nameUUIDFromBytes rather than ParcelUuid.fromString(id): it accepts any label, not just a
    // strict UUID string, and — the property the dedupe test needs — two calls with the same label
    // produce the same uuid.
    private fun endpoint(type: Int, name: String = "name", id: String = UUID.randomUUID().toString()) =
        CallEndpointCompat(name, type, ParcelUuid(UUID.nameUUIDFromBytes(id.toByteArray())))

    @Test fun everyKnownTypeConstantMapsToItsAudioRouteType() {
        assertEquals(AudioRoute.Type.EARPIECE, endpoint(CallEndpointCompat.TYPE_EARPIECE).toAudioRoute().type)
        assertEquals(AudioRoute.Type.BLUETOOTH, endpoint(CallEndpointCompat.TYPE_BLUETOOTH).toAudioRoute().type)
        assertEquals(
            AudioRoute.Type.WIRED_HEADSET,
            endpoint(CallEndpointCompat.TYPE_WIRED_HEADSET).toAudioRoute().type,
        )
        assertEquals(AudioRoute.Type.SPEAKER, endpoint(CallEndpointCompat.TYPE_SPEAKER).toAudioRoute().type)
        assertEquals(AudioRoute.Type.STREAMING, endpoint(CallEndpointCompat.TYPE_STREAMING).toAudioRoute().type)
    }

    /** The library's own escape hatch, plus a value it has never defined — both must fall safely,
     *  not throw or silently mis-sort. */
    @Test fun anUnmappedTypeFallsToUnknown() {
        assertEquals(AudioRoute.Type.UNKNOWN, endpoint(CallEndpointCompat.TYPE_UNKNOWN).toAudioRoute().type)
        assertEquals(AudioRoute.Type.UNKNOWN, endpoint(999).toAudioRoute().type)
    }

    @Test fun idIsTheIdentifierStringified() {
        val ep = endpoint(CallEndpointCompat.TYPE_SPEAKER, id = "11111111-1111-1111-1111-111111111111")
        assertEquals(ep.identifier.toString(), ep.toAudioRoute().id)
    }

    @Test fun publishSortsByHardwarePreference() {
        val published = mutableListOf<AudioRoutes>()
        val routes = RouteState { published += it }
        routes.available = listOf(
            endpoint(CallEndpointCompat.TYPE_SPEAKER, id = "1"),
            endpoint(CallEndpointCompat.TYPE_WIRED_HEADSET, id = "2"),
        )

        routes.publish()

        assertEquals(
            listOf(AudioRoute.Type.WIRED_HEADSET, AudioRoute.Type.SPEAKER),
            published.last().available.map { it.type },
        )
    }

    /**
     * Two Bluetooth devices below API 34, without BLUETOOTH_CONNECT, share the platform's literal
     * "Bluetooth Device" name and therefore the same uuid (see `AudioRoute`'s KDoc). Undeduped, the
     * menu would render an unreachable second row ticked as current alongside the first.
     */
    @Test fun publishDedupesByIdKeepingTheFirst() {
        val published = mutableListOf<AudioRoutes>()
        val routes = RouteState { published += it }
        routes.available = listOf(
            endpoint(CallEndpointCompat.TYPE_BLUETOOTH, name = "Bluetooth Device", id = "collided"),
            endpoint(CallEndpointCompat.TYPE_BLUETOOTH, name = "Bluetooth Device", id = "collided"),
        )

        routes.publish()

        assertEquals(1, published.last().available.size)
    }

    @Test fun retiredSuppressesPublishing() {
        var calls = 0
        val routes = RouteState { calls++ }
        routes.available = listOf(endpoint(CallEndpointCompat.TYPE_SPEAKER))
        routes.retired = true

        routes.publish()

        assertEquals(0, calls)
    }
}
