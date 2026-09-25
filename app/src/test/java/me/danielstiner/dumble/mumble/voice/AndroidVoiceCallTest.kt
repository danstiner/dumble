package me.danielstiner.dumble.mumble.voice

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
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
import org.robolectric.shadow.api.Shadow

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
    private val call = AndroidVoiceCall(context, captureSession = OURS)
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

    private fun otherAppCaptures(vararg sessions: Int) {
        shadow.setActiveRecordingConfigurations(
            sessions.map { shadow.createActiveRecordingConfiguration(it, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "") },
            true,
        )
        idle()
    }

    /** The phone setting its own mode; Robolectric's setMode dispatches the mode listeners. */
    private fun phone(mode: Int) {
        audio.mode = mode
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

    /**
     * `removeAvailableCommunicationDevice(headset, false)` drops the headset from
     * `availableCommunicationDevices` without notifying, same as a real vanish the app has not
     * republished the menu for yet: the tap for "7" still reaches `route`, which now finds no such
     * device and must drop it rather than throw.
     */
    @Test fun aPickOfAVanishedRouteIsDropped() {
        shadow.addAvailableCommunicationDevice(headset, false)
        start(1)
        shadow.removeAvailableCommunicationDevice(headset, false)
        val before = audio.communicationDevice?.id
        pick(1, "7")
        assertEquals(before, audio.communicationDevice?.id)
    }

    /**
     * As for a phone call, a headset that arrives takes the call, even from a speaker the user
     * picked.
     */
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

    /**
     * The arrival diff (`known`) is only updated by a reconcile that actually ran; a headset already
     * folded into it must not be mistaken for a fresh arrival by the next one.
     */
    @Test fun theArrivalDiffRemembers() {
        start(1)
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        pick(1, "3")
        platformConfirmsRoute()
        assertEquals(speaker.id, audio.communicationDevice?.id)
    }

    /** Releasing between attempts would drop SCO only for the successor to reopen it. */
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

    /**
     * With a headset available, the mode lands only after the route does; recording the
     * communication device from inside the mode listener catches the order directly.
     */
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

    /**
     * A supersede skips the fresh-start setup (route, mode, listeners); a start after a real end
     * must not be mistaken for one, or it inherits nothing of that setup.
     */
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

    @Test fun aCellularCallHoldsAndItsEndResumes() {
        start(1)
        phone(AudioManager.MODE_RINGTONE)
        phone(AudioManager.MODE_IN_CALL)
        phone(AudioManager.MODE_NORMAL)
        assertEquals(listOf(false, true), active[1])
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
    }

    @Test fun anotherAppsVoiceCaptureHoldsAndItsEndResumes() {
        start(1)
        otherAppCaptures(99)
        otherAppCaptures()
        assertEquals(listOf(false, true), active[1])
    }

    @Test fun ourOwnCaptureDoesNotHold() {
        start(1)
        otherAppCaptures(OURS)
        assertNull(active[1])
    }

    /**
     * The newest MODE_IN_COMMUNICATION setter can win the microphone, so taking the mode would
     * seize the other call.
     */
    @Test fun connectingDuringAnotherAppsCallStartsHeldAndTakesNothing() {
        otherAppCaptures(99)
        start(1)
        assertEquals(listOf(false), active[1])
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
        otherAppCaptures()
        assertEquals(listOf(false, true), active[1])
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(earpiece.id, audio.communicationDevice?.id)
    }

    @Test fun connectingDuringACellularCallStartsHeld() {
        phone(AudioManager.MODE_IN_CALL)
        start(1)
        assertEquals(listOf(false), active[1])
        assertNull(audio.communicationDevice)
    }

    /** MumbleConnection scopes holds by generation: a successor never told would open the microphone mid-call. */
    @Test fun aSupersedeDuringAHoldTellsTheSuccessor() {
        start(1)
        phone(AudioManager.MODE_IN_CALL)
        start(2)
        assertEquals(listOf(false), active[2])
        phone(AudioManager.MODE_NORMAL)
        assertEquals(listOf(false, true), active[2])
    }

    @Test fun aSuccessorThatIsNotHeldIsToldNothing() {
        start(1)
        start(2)
        assertNull(active[2])
    }

    /** The safety net behind a Talk press or the held banner reads the platform afresh. */
    @Test fun requestActiveRereadsTheRecordings() {
        start(1)
        shadow.setActiveRecordingConfigurations(
            listOf(shadow.createActiveRecordingConfiguration(99, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "")),
            false,
        )
        call.requestActive(1)
        idle()
        assertEquals(listOf(false), active[1])
    }

    /** The other direction of [requestActiveRereadsTheRecordings]: a resume no listener reported. */
    @Test fun requestActiveDetectsAResumeNobodyAnnounced() {
        start(1)
        otherAppCaptures(99)
        shadow.setActiveRecordingConfigurations(emptyList(), false)
        call.requestActive(1)
        idle()
        assertEquals(listOf(false, true), active[1])
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
    }

    /** Our route request applies only while we own the mode, so it waits for the resume. */
    @Test fun aHeadsetArrivingDuringAHoldIsTheRouteOnResume() {
        start(1)
        phone(AudioManager.MODE_IN_CALL)
        shadow.addAvailableCommunicationDevice(headset, true)
        idle()
        phone(AudioManager.MODE_NORMAL)
        assertEquals(headset.id, audio.communicationDevice?.id)
    }

    /**
     * requestRoute must mark the call routed even while held, or take()'s deferred preferredRoute
     * pick overwrites a route the user chose during the hold once the call resumes.
     */
    @Test fun aPickDuringAHoldIsTheRouteOnResume() {
        phone(AudioManager.MODE_IN_CALL)
        start(1)
        pick(1, "3")
        phone(AudioManager.MODE_NORMAL)
        assertEquals(speaker.id, audio.communicationDevice?.id)
    }

    @Test fun afterEndAHoldIsNotReported() {
        start(1)
        end(1)
        phone(AudioManager.MODE_IN_CALL)
        assertNull(active[1])
    }

    /**
     * Another app's call can end leaving the mode at IN_COMMUNICATION and still its own — only the
     * latest setMode call owns it — so the resume must call setMode again even though the value
     * does not change. Robolectric's own listener only fires on a change, so a counting shadow
     * stands in for the platform's ownership.
     */
    @Test
    @Config(shadows = [CountingModeAudioManagerShadow::class])
    fun aResumeSetsTheModeAgainEvenThoughItAlreadyReadsInCommunication() {
        val modeCalls = Shadow.extract<CountingModeAudioManagerShadow>(audio)
        start(1)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        otherAppCaptures(99)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        val callsWhileHeld = modeCalls.setModeCalls
        otherAppCaptures()
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(callsWhileHeld + 1, modeCalls.setModeCalls)
    }

    /**
     * AudioService drops the mode of an owner with no voice playback or capture after a grace
     * period, and hands it back once one starts. The call must not fight that by setting it again,
     * nor take the drop for a hold.
     */
    @Test
    @Config(shadows = [CountingModeAudioManagerShadow::class])
    fun anIdleOwnersDroppedModeIsLeftToThePlatform() {
        val modeCalls = Shadow.extract<CountingModeAudioManagerShadow>(audio)
        start(1)
        audio.mode = AudioManager.MODE_NORMAL
        val callsAfterTheDrop = modeCalls.setModeCalls
        idle()
        assertEquals(callsAfterTheDrop, modeCalls.setModeCalls)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(active[1])
    }

    private companion object {
        const val OURS = 41
    }
}
