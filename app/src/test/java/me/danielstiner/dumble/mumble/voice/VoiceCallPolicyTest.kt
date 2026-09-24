package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallPolicyTest {
    private val earpiece = AudioRoute("2", Type.EARPIECE)
    private val speaker = AudioRoute("3", Type.SPEAKER)
    private val wired = AudioRoute("4", Type.WIRED_HEADSET)
    private val bluetooth = AudioRoute("7", Type.BLUETOOTH, "WH-1000XM5")

    @Test fun bluetoothBeatsWiredBeatsEarpiece() {
        assertEquals(bluetooth, preferredRoute(listOf(earpiece, speaker, wired, bluetooth)))
        assertEquals(wired, preferredRoute(listOf(earpiece, speaker, wired)))
        assertEquals(earpiece, preferredRoute(listOf(speaker, earpiece)))
    }

    /** The speaker is the user's to choose; with no earpiece either, the platform's default applies. */
    @Test fun theSpeakerIsNeverPicked() {
        assertNull(preferredRoute(listOf(speaker)))
    }

    @Test fun aHeadsetNotSeenBeforeHasArrived() {
        assertEquals(bluetooth, arrivedHeadset(setOf("2", "3"), listOf(earpiece, speaker, bluetooth)))
    }

    /** A reconnecting headset comes back under a new id (measured). */
    @Test fun aHeadsetUnderANewIdHasArrived() {
        val reconnected = AudioRoute("8", Type.BLUETOOTH, "WH-1000XM5")
        assertEquals(reconnected, arrivedHeadset(setOf("2", "7"), listOf(earpiece, reconnected)))
    }

    @Test fun aHeadsetAlreadyThereHasNotArrived() {
        assertNull(arrivedHeadset(setOf("2", "7"), listOf(earpiece, bluetooth)))
    }

    @Test fun theEarpieceAndSpeakerNeverArrive() {
        assertNull(arrivedHeadset(emptySet(), listOf(earpiece, speaker)))
    }

    @Test fun aWiredHeadsetArrives() {
        assertEquals(wired, arrivedHeadset(setOf("2", "3"), listOf(earpiece, speaker, wired)))
    }

    @Test fun thePhoneOwningTheAudioHolds() {
        for (mode in listOf(
            AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT, AudioManager.MODE_COMMUNICATION_REDIRECT,
        )) {
            assertTrue("mode $mode", isHeld(mode, otherVoiceCaptures = 0))
        }
    }

    @Test fun ourOwnModesDoNotHold() {
        assertFalse(isHeld(AudioManager.MODE_NORMAL, otherVoiceCaptures = 0))
        assertFalse(isHeld(AudioManager.MODE_IN_COMMUNICATION, otherVoiceCaptures = 0))
    }

    @Test fun anotherAppsVoiceCaptureHoldsInAnyMode() {
        assertTrue(isHeld(AudioManager.MODE_NORMAL, otherVoiceCaptures = 1))
        assertTrue(isHeld(AudioManager.MODE_IN_COMMUNICATION, otherVoiceCaptures = 2))
    }
}
