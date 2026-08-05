package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * The Chat and Settings buttons swap `ConnectedScreen` out from under a held Talk
     * (`MainActivity.kt` routes on `showChat`/`route` above the controls), and the gesture's
     * `PressInteraction.Cancel` cannot save us: the clickable node emits it at detach, but the
     * collector's `LaunchedEffect` job is cancelled in the same frame and never resumes to read it.
     * Left open, the level in `MumbleConnection.transmitting` is what every later `openSession`
     * re-applies, so one tap while held is a microphone that stays live across engine rebuilds.
     *
     * Leaving composition is the invariant, not the two-finger gesture that first found it: rotating
     * the device recreates the Activity under a held button and reaches the same defect one-handed,
     * confirmed on-device against a build with the `DisposableEffect` deleted.
     */
    @Test fun leavingCompositionWhileHeldClosesTheGate() {
        val events = mutableListOf<Boolean>()
        var onScreen by mutableStateOf(true)
        compose.setContent {
            if (onScreen) {
                CallControls(microphoneGranted = true, onTransmitting = { events += it }, onHangUp = {})
            }
        }
        compose.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), events)

        // The second finger hitting Chat, with Talk still down. Written via runOnIdle, not from
        // the test thread: a bare snapshot write here races invalidation delivery, and one full-
        // suite run in ~10 caught waitForIdle returning before the recomposition it was waiting
        // for existed — expected:<[true, false]> but was:<[true]>, with onDispose never run.
        compose.runOnIdle { onScreen = false }
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
