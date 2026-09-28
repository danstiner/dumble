package me.danielstiner.dumble.mumble.voice

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import me.danielstiner.dumble.service.VoiceService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidVoiceCallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val shadow = shadowOf(audio)
    private val application = shadowOf(context as Application)
    private val earpiece = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, 2, "Pixel 7a")
    private val speaker = audioDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 3, "Pixel 7a")
    private val headset = audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 7, "WH-1000XM5")
    private val call = AndroidVoiceCall(context)
    /** What each generation was told through onActive, in order. */
    private val active = mutableMapOf<Int, MutableList<Boolean>>()
    /** The last routes each generation was given. */
    private val routes = mutableMapOf<Int, AudioRoutes>()

    @Before fun builtIns() {
        shadow.addAvailableCommunicationDevice(earpiece, false)
        shadow.addAvailableCommunicationDevice(speaker, false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(gen: Int, host: String = "host") {
        call.start(
            gen, MumbleEndpoint.parse(host), "user",
            onActive = { active.getOrPut(gen) { mutableListOf() } += it },
            onRoutes = { routes[gen] = it },
            onEnded = {},
        )
        idle()
    }

    private fun end(gen: Int) {
        call.end(gen, VoiceCall.Reason.USER)
        idle()
    }

    private fun pick(gen: Int, routeId: String) {
        call.requestRoute(gen, routeId)
        idle()
    }

    /** Robolectric's setCommunicationDevice does not call its own listener; the platform's does. */
    private fun platformConfirmsRoute() {
        shadow.callOnCommunicationDeviceChangedListeners(audio.communicationDevice)
        idle()
    }

    @Test fun startTakesAHeadsetThenTheMode() {
        shadow.addAvailableCommunicationDevice(headset, false)
        start(1)
        assertEquals(headset.id, audio.communicationDevice?.id)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals("7", routes[1]?.current?.id)
    }

    @Test fun withNoHeadsetStartTakesTheEarpieceNeverTheSpeaker() {
        start(1)
        assertEquals(earpiece.id, audio.communicationDevice?.id)
    }

    @Test fun theRoutesAreSorted() {
        shadow.addAvailableCommunicationDevice(headset, false)
        start(1)
        assertEquals(listOf(Type.BLUETOOTH, Type.SPEAKER, Type.EARPIECE), routes[1]?.available?.map { it.type })
    }

    @Test fun aPickIsApplied() {
        start(1)
        pick(1, "3")
        assertEquals(speaker.id, audio.communicationDevice?.id)
        platformConfirmsRoute()
        assertEquals("3", routes[1]?.current?.id)
    }

    @Test fun aStaleGenerationsPickIsDropped() {
        start(1)
        start(2)
        pick(1, "3")
        assertEquals(earpiece.id, audio.communicationDevice?.id)
    }

    /** The headset leaves unannounced, as it can between the menu rendering and the tap. */
    @Test fun aPickOfAVanishedRouteIsDropped() {
        shadow.addAvailableCommunicationDevice(headset, false)
        start(1)
        shadow.removeAvailableCommunicationDevice(headset, false)
        val before = audio.communicationDevice?.id
        pick(1, "7")
        assertEquals(before, audio.communicationDevice?.id)
    }

    /** As for a phone call, even from a speaker the user picked. */
    @Test fun anArrivingHeadsetTakesTheRoute() {
        start(1)
        pick(1, "3")
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        assertEquals(headset.id, audio.communicationDevice?.id)
        assertEquals(listOf("7", "3", "2"), routes[1]?.available?.map { it.id })
    }

    @Test fun aDepartingHeadsetIsRepublished() {
        shadow.addAvailableCommunicationDevice(headset, false)
        start(1)
        shadow.removeAvailableCommunicationDevice(headset, true)
        idle()
        assertEquals(listOf("3", "2"), routes[1]?.available?.map { it.id })
    }

    /** A headset already seen doesn't arrive again and undo the user's pick. */
    @Test fun theArrivalDiffRemembers() {
        start(1)
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        pick(1, "3")
        platformConfirmsRoute()
        assertEquals(speaker.id, audio.communicationDevice?.id)
    }

    @Test fun aSupersedeKeepsTheRouteAndMode() {
        start(1)
        pick(1, "3")
        var modeChanges = 0
        audio.addOnModeChangedListener(Runnable::run) { modeChanges++ }
        start(2)
        assertEquals(0, modeChanges)
        assertEquals(speaker.id, audio.communicationDevice?.id)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals("3", routes[2]?.current?.id)
    }

    @Test fun routeLandsBeforeMode() {
        shadow.addAvailableCommunicationDevice(headset, false)
        var deviceWhenModeChanged: Int? = null
        audio.addOnModeChangedListener(Runnable::run) { deviceWhenModeChanged = audio.communicationDevice?.id }
        start(1)
        assertEquals(headset.id, deviceWhenModeChanged)
    }

    @Test fun endReleasesTheModeAndRoute() {
        start(1)
        end(1)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
    }

    @Test fun aStaleEndIsIgnored() {
        start(1)
        start(2)
        end(1)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(earpiece.id, audio.communicationDevice?.id)
    }

    @Test fun afterEndNothingIsPublished() {
        start(1)
        end(1)
        val last = routes[1]
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        assertEquals(last, routes[1])
    }

    /** A start after an end sets everything up afresh; only a supersede skips that. */
    @Test fun aCallStartsAgainAfterAnEnd() {
        start(1)
        pick(1, "3")
        end(1)
        start(2)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(earpiece.id, audio.communicationDevice?.id)
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        assertTrue(routes[2]?.available?.any { it.id == "7" } == true)
    }

    @Test fun theMicrophoneServiceFollowsTheCall() {
        start(1, "alpha")
        val started = application.nextStartedService
        assertEquals(VoiceService::class.java.name, started?.component?.className)
        assertEquals("alpha", started?.getStringExtra(VoiceService.EXTRA_SERVER))

        start(2, "beta")
        val supersedeStart = application.nextStartedService
        assertEquals(VoiceService::class.java.name, supersedeStart?.component?.className)
        assertEquals("beta", supersedeStart?.getStringExtra(VoiceService.EXTRA_SERVER))

        end(2)
        val stopped = application.nextStartedService
        // VoiceService.stop is a startService carrying a stop action, not a stopService call.
        assertEquals(VoiceService::class.java.name, stopped?.component?.className)
        assertEquals(VoiceService.ACTION_STOP, stopped?.action)
    }
}
