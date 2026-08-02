package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Robolectric rather than androidTest because CI runs testDebugUnitTest and no instrumented suite,
 * so an androidTest would compile and never run. The sdk pin is Robolectric's supported ceiling,
 * below the project's compileSdk; gesture dispatch is not API-level-sensitive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallControlsTest {

    @get:Rule val compose = createComposeRule()

    /** The whole point of the control: the gate opens on press and closes on release, not on click. */
    @Test fun talkOpensTheGateOnPressAndClosesOnRelease() {
        val events = mutableListOf<Boolean>()
        compose.setContent {
            CallControls(microphoneGranted = true, onTransmitting = { events += it }, onHangUp = {})
        }

        compose.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), events)

        compose.onNodeWithContentDescription("Push to talk").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), events)
    }

    @Test fun deniedMicrophoneDisablesTalkAndExplainsWhy() {
        compose.setContent {
            CallControls(microphoneGranted = false, onTransmitting = {}, onHangUp = {})
        }
        compose.onNodeWithText("No mic").assertExists()
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).assertIsNotEnabled()
    }

    /**
     * While the first-launch permission dialog is up the answer is unknown, not "no". Claiming a
     * denial there is a lie, and screen readers announce it before the user has even answered.
     */
    @Test fun aPendingPermissionIsNotReportedAsADenial() {
        compose.setContent {
            CallControls(microphoneGranted = null, onTransmitting = {}, onHangUp = {})
        }
        compose.onNodeWithText("No mic").assertDoesNotExist()
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).assertDoesNotExist()
        // Still not pressable: capture has not started, so an open gate would transmit nothing.
        compose.onNodeWithContentDescription("Push to talk").assertIsNotEnabled()
    }

    /** Placeholders for later PRs — they must not look tappable until that work lands. */
    @Test fun deafenAndSpeakerAreDisabled() {
        compose.setContent {
            CallControls(microphoneGranted = true, onTransmitting = {}, onHangUp = {})
        }
        compose.onNodeWithContentDescription("Deafen").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Speaker").assertIsNotEnabled()
        // Mute must not come back as a separate slot — it is Talk's alternative, not an addition.
        compose.onNodeWithContentDescription("Mute").assertDoesNotExist()
    }

    @Test fun disconnectInvokesItsCallback() {
        var hungUp = false
        compose.setContent {
            CallControls(microphoneGranted = true, onTransmitting = {}, onHangUp = { hungUp = true })
        }
        compose.onNodeWithContentDescription("Disconnect").performClick()
        assertTrue(hungUp)
    }

    /**
     * A gesture that ends without a Release — dragged off the button, or a dialog stealing the
     * window — must still close the gate, or the microphone stays live after the user let go.
     */
    @Test fun aCancelledGestureStillClosesTheGate() {
        val events = mutableListOf<Boolean>()
        compose.setContent {
            CallControls(microphoneGranted = true, onTransmitting = { events += it }, onHangUp = {})
        }
        compose.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Push to talk").performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), events)
    }

    /** A denied microphone must not be able to open the gate however the button is poked. */
    @Test fun aDisabledButtonNeverOpensTheGate() {
        val events = mutableListOf<Boolean>()
        compose.setContent {
            CallControls(microphoneGranted = false, onTransmitting = { events += it }, onHangUp = {})
        }
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue(events.isEmpty())
    }
}
