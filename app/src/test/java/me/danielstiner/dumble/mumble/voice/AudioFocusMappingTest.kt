package me.danielstiner.dumble.mumble.voice

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioFocusMappingTest {

    @Test fun permanentLossMapsToLost() =
        assertEquals(AudioFocus.Change.LOST, focusChangeOf(AudioManager.AUDIOFOCUS_LOSS))

    @Test fun transientLossMapsToTemporary() =
        assertEquals(
            AudioFocus.Change.LOST_TEMPORARILY,
            focusChangeOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )

    @Test fun gainMapsToRegained() =
        assertEquals(AudioFocus.Change.REGAINED, focusChangeOf(AudioManager.AUDIOFOCUS_GAIN))

    /**
     * The defect this exists to prevent: an earlier draft closed the transmit gate on this. Ducking
     * is a decision about *playback* volume — cutting the microphone for it ends a sentence over a
     * notification chime.
     */
    @Test fun duckingDoesNotDisturbCapture() =
        assertNull(focusChangeOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))

    @Test fun anUnknownCodeDoesNotDisturbCapture() = assertNull(focusChangeOf(Int.MIN_VALUE))
}
