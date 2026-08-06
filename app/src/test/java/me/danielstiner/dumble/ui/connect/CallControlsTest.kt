package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
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

    /** Every case but the two that need conditional or mutable content. */
    private fun controls(
        talkBlock: TalkBlock? = null,
        deafened: Boolean = false,
        onTransmitting: (Boolean) -> Unit = {},
        onToggleDeafen: () -> Unit = {},
        onHangUp: () -> Unit = {},
    ) = compose.setContent {
        CallControls(talkBlock, deafened, onTransmitting, onToggleDeafen, onHangUp)
    }

    /** The whole point of the control: the gate opens on press and closes on release, not on click. */
    @Test fun talkOpensTheGateOnPressAndClosesOnRelease() {
        val events = mutableListOf<Boolean>()
        controls(onTransmitting = { events += it })

        compose.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), events)

        compose.onNodeWithContentDescription("Push to talk").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), events)
    }

    @Test fun deniedMicrophoneDisablesTalkAndExplainsWhy() {
        controls(talkBlock = TalkBlock.NO_MICROPHONE)
        compose.onNodeWithText("No mic").assertExists()
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).assertIsNotEnabled()
    }

    @Test fun beingDeafenedDisablesTalkAndSaysHowToFixIt() {
        controls(talkBlock = TalkBlock.DEAFENED, deafened = true)
        compose.onNodeWithText("Deafened").assertExists()
        compose.onNodeWithContentDescription("Undeafen to talk").assertIsNotEnabled()
    }

    @Test fun beingMutedDisablesTalkWithoutBlamingTheCause() {
        controls(talkBlock = TalkBlock.MUTED)
        compose.onNodeWithText("Muted").assertExists()
        compose.onNodeWithContentDescription(
            "Muted — the server will not carry your audio",
        ).assertIsNotEnabled()
    }

    /** Speaker is still a placeholder (PR 3) — it must not look tappable until that work lands. */
    @Test fun speakerIsDisabled() {
        controls()
        compose.onNodeWithContentDescription("Speaker").assertIsNotEnabled()
        // Mute must not come back as a separate slot — it is Talk's alternative, not an addition.
        compose.onNodeWithContentDescription("Mute").assertDoesNotExist()
    }

    @Test fun deafenIsATogglingButtonNotAPlaceholder() {
        var toggles = 0
        controls(onToggleDeafen = { toggles++ })
        compose.onNodeWithContentDescription("Deafen").assertIsEnabled().performClick()
        assertEquals(1, toggles)
    }

    /**
     * The caption stays "Deafen" in both states — a label that renames itself under the user's thumb
     * reads as the button having moved — so the description is what has to say which way the tap
     * goes.
     */
    @Test fun aDeafenedButtonOffersToUndeafen() {
        controls(deafened = true, talkBlock = TalkBlock.DEAFENED)
        compose.onNodeWithText("Deafen").assertExists()
        compose.onNodeWithContentDescription("Undeafen — you cannot hear anyone").assertIsEnabled()
        compose.onNodeWithContentDescription("Deafen").assertDoesNotExist()
    }

    /**
     * New with deafen: Talk's `enabled` can now flip mid-press, which `granted` never could. Left
     * open, the gate is not merely stuck for this press — `MumbleConnection` keeps transmit as a
     * level that every rebuilt capture session re-applies, so it survives engine rebuilds.
     *
     * The defence is Compose's own: disabling a `clickable` disposes its interactions and emits
     * `PressInteraction.Cancel`, which `TalkControl` treats as a release. That is a third-party
     * behaviour we depend on and do not control, so it gets pinned here.
     */
    @Test fun talkDisabledWhileHeldClosesTheGate() {
        val events = mutableListOf<Boolean>()
        var block by mutableStateOf<TalkBlock?>(null)
        compose.setContent {
            CallControls(
                talkBlock = block, deafened = false,
                onTransmitting = { events += it }, onToggleDeafen = {}, onHangUp = {},
            )
        }
        compose.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), events)

        // Via runOnIdle, not a bare write: a snapshot write from the test thread races invalidation
        // delivery, which has flaked this file before.
        compose.runOnIdle { block = TalkBlock.DEAFENED }
        compose.waitForIdle()
        assertEquals(listOf(true, false), events)
    }

    @Test fun disconnectInvokesItsCallback() {
        var hungUp = false
        controls(onHangUp = { hungUp = true })
        compose.onNodeWithContentDescription("Disconnect").performClick()
        assertTrue(hungUp)
    }

    /**
     * A gesture that ends without a Release — dragged off the button, or a dialog stealing the
     * window — must still close the gate, or the microphone stays live after the user let go.
     */
    @Test fun aCancelledGestureStillClosesTheGate() {
        val events = mutableListOf<Boolean>()
        controls(onTransmitting = { events += it })
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
                CallControls(
                    talkBlock = null, deafened = false,
                    onTransmitting = { events += it }, onToggleDeafen = {}, onHangUp = {},
                )
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
        controls(talkBlock = TalkBlock.NO_MICROPHONE, onTransmitting = { events += it })
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertTrue(events.isEmpty())
    }

    /**
     * The label used to sit on the `Icon`, which put it on a second, non-clickable node: a
     * `content-desc` match then landed on something reporting `enabled=true` whatever the button
     * said — a TalkBack question and a trap for anyone verifying this screen with `uiautomator`.
     *
     * Unmerged tree on purpose. The merged tree folds the icon's description into the button, so
     * this passes against the defect and proves nothing.
     */
    @Test fun aControlsDescriptionSitsOnItsClickableNode() {
        controls()
        compose.onNode(
            hasContentDescription("Speaker") and hasClickAction(),
            useUnmergedTree = true,
        ).assertIsNotEnabled()
        compose.onNode(
            hasContentDescription("Disconnect") and hasClickAction(),
            useUnmergedTree = true,
        ).assertIsEnabled()
    }
}
