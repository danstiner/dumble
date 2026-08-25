package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.TransmitMode
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
        audioRoutes: AudioRoutes = AudioRoutes(),
        onTransmitting: (Boolean) -> Unit = {},
        onToggleDeafen: () -> Unit = {},
        onSelectRoute: (String) -> Unit = {},
        onHangUp: () -> Unit = {},
        transmitMode: TransmitMode = TransmitMode.PushToTalk,
        muted: Boolean = false,
        inaudible: Boolean = false,
        onToggleMute: () -> Unit = {},
    ) = compose.setContent {
        CallControls(
            talkBlock, deafened, audioRoutes, onTransmitting, onToggleDeafen, onSelectRoute,
            onHangUp, transmitMode = transmitMode, muted = muted, inaudible = inaudible,
            onToggleMute = onToggleMute,
        )
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

    /** Mute must not come back as a separate slot — it is Talk's alternative, not an addition. */
    @Test fun thereIsNoSeparateMuteControl() {
        controls()
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
                talkBlock = block, deafened = false, audioRoutes = AudioRoutes(),
                onTransmitting = { events += it }, onToggleDeafen = {}, onSelectRoute = {},
                onHangUp = {},
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
                    talkBlock = null, deafened = false, audioRoutes = AudioRoutes(),
                    onTransmitting = { events += it }, onToggleDeafen = {}, onSelectRoute = {},
                    onHangUp = {},
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
            hasContentDescription("Audio output") and hasClickAction(),
            useUnmergedTree = true,
        ).assertIsNotEnabled()
        compose.onNode(
            hasContentDescription("Disconnect") and hasClickAction(),
            useUnmergedTree = true,
        ).assertIsEnabled()
    }

    private val earpiece = AudioRoute("id-earpiece", AudioRoute.Type.EARPIECE)
    private val speaker = AudioRoute("id-speaker", AudioRoute.Type.SPEAKER)
    private val wired = AudioRoute("id-wired", AudioRoute.Type.WIRED_HEADSET)
    private val shokz = AudioRoute("id-bt", AudioRoute.Type.BLUETOOTH, "OpenRun by Shokz")

    /**
     * No Bluetooth, so the choice is binary and the control routes straight there without a menu —
     * the stock app's `nonBluetoothMode`.
     */
    @Test fun withoutBluetoothTheControlIsAToggle() {
        val picked = mutableListOf<String>()
        controls(
            audioRoutes = AudioRoutes(listOf(speaker, earpiece), earpiece),
            onSelectRoute = { picked += it },
        )

        compose.onNodeWithContentDescription(
            "Audio output — Earpiece, tap for Speaker",
        ).performClick()
        compose.waitForIdle()

        assertEquals(listOf("id-speaker"), picked)
    }

    /** …and back off it again. Leaving the speaker lands on the earpiece with nothing plugged in. */
    @Test fun theToggleComesBackOffSpeaker() {
        val picked = mutableListOf<String>()
        controls(
            audioRoutes = AudioRoutes(listOf(speaker, earpiece), speaker),
            onSelectRoute = { picked += it },
        )

        compose.onNodeWithContentDescription(
            "Audio output — Speaker, tap for Earpiece",
        ).performClick()
        compose.waitForIdle()

        assertEquals(listOf("id-earpiece"), picked)
    }

    /**
     * A wired headset alone does *not* earn a menu. Stock switches on Bluetooth only
     * (`SpeakerButtonInfo`), and leaving the speaker prefers the wired headset over the earpiece
     * (`ROUTE_WIRED_OR_EARPIECE`). The prototype triggered its picker on wired too; that was its own
     * invention, and this pins the difference.
     */
    @Test fun aWiredHeadsetAloneStillGetsAToggle() {
        val picked = mutableListOf<String>()
        controls(
            audioRoutes = AudioRoutes(listOf(wired, speaker), speaker),
            onSelectRoute = { picked += it },
        )

        compose.onNodeWithContentDescription(
            "Audio output — Speaker, tap for Wired headset",
        ).performClick()
        compose.waitForIdle()

        assertEquals(listOf("id-wired"), picked)
    }

    /** With Bluetooth around the tap opens the menu and commits to nothing on its own. */
    @Test fun withBluetoothTheControlOpensAMenuInsteadOfRouting() {
        val picked = mutableListOf<String>()
        controls(
            audioRoutes = AudioRoutes(listOf(shokz, speaker, earpiece), earpiece),
            onSelectRoute = { picked += it },
        )

        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), picked)
        compose.onNodeWithContentDescription("OpenRun by Shokz").assertExists()
    }

    /** The caption names the current selection — this is a picker, not a toggle. */
    @Test fun theRouteButtonNamesTheCurrentRoute() {
        controls(audioRoutes = AudioRoutes(listOf(earpiece, speaker), earpiece))

        compose.onNodeWithText("Earpiece").assertExists()
    }

    /** Nothing is routable without a call, and a button that does nothing should not invite a tap. */
    @Test fun noRoutesDisablesTheRouteButton() {
        controls(audioRoutes = AudioRoutes())

        compose.onNodeWithContentDescription("Audio output").assertIsNotEnabled()
    }

    /**
     * Clicks the middle row: not [available]'s first entry (shokz) and not the current route
     * (earpiece, which is also the last entry here). A regression that always emitted the first
     * row's id, or always echoed the current route's id, would each fail this differently than
     * clicking either edge would catch alone.
     */
    @Test fun pickingARouteEmitsItsId() {
        val picked = mutableListOf<String>()
        controls(
            audioRoutes = AudioRoutes(listOf(shokz, speaker, earpiece), earpiece),
            onSelectRoute = { picked += it },
        )

        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Speaker").performClick()
        compose.waitForIdle()

        assertEquals(listOf("id-speaker"), picked)
    }

    /** The check has to be reachable to a screen reader, so it rides the row's own description. */
    @Test fun theCurrentRouteIsMarkedInTheMenu() {
        controls(audioRoutes = AudioRoutes(listOf(shokz, earpiece), earpiece))

        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Earpiece, current route").assertExists()
        compose.onNodeWithContentDescription("OpenRun by Shokz").assertExists()
    }

    /**
     * Rotating with the picker open used to drop it — a plain [remember] does not survive the
     * activity being recreated. Found by rotating the phone mid-pick; the menu vanished with no
     * explanation and the call screen came back underneath.
     */
    @Test fun theMenuSurvivesTheActivityBeingRecreated() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CallControls(null, false, AudioRoutes(listOf(shokz, earpiece), earpiece), {}, {}, {}, {})
        }

        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Earpiece, current route").assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithContentDescription("Earpiece, current route").assertExists()
    }

    /**
     * …but surviving must not mean outliving the menu itself. Leaving menu mode drops the dropdown
     * from composition without any dismiss firing, so an unkeyed `rememberSaveable` held `true` and
     * reopened the menu the moment Bluetooth returned — a menu the user never asked for, over a call
     * screen they were looking at. The `menuMode` key is what resets it.
     */
    @Test fun theMenuDoesNotReopenWhenBluetoothReturns() {
        var routes by mutableStateOf(AudioRoutes(listOf(shokz, speaker, earpiece), earpiece))
        compose.setContent {
            CallControls(null, false, routes, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Earpiece, current route").assertExists()

        // The headset leaves mid-call: the control falls back to a toggle and the menu goes with it.
        routes = AudioRoutes(listOf(speaker, earpiece), earpiece)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Earpiece, current route").assertDoesNotExist()

        // …and comes back. No tap in between, so no menu.
        routes = AudioRoutes(listOf(shokz, speaker, earpiece), earpiece)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Earpiece, current route").assertDoesNotExist()
    }

    /**
     * `available` and `current` are separate collectors, so a frame lands with routes but no current
     * route yet. The button is live and has somewhere to go in that frame, and used to describe
     * itself with the bare "Audio output" it shows when there is nothing to route at all — the one
     * string a screen reader gets, identical for a working control and a dead one.
     */
    @Test fun theToggleNamesItsDestinationBeforeThePlatformNamesTheRoute() {
        controls(audioRoutes = AudioRoutes(listOf(speaker, earpiece), current = null))

        compose.onNodeWithContentDescription("Audio output — tap for Speaker").assertIsEnabled()
    }

    /** The same latch by the other route in: a new call must not inherit the last one's open menu. */
    @Test fun theMenuDoesNotReopenOnTheNextCall() {
        var routes by mutableStateOf(AudioRoutes(listOf(shokz, earpiece), earpiece))
        compose.setContent {
            CallControls(null, false, routes, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription("Audio output — Earpiece").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Earpiece, current route").assertExists()

        routes = AudioRoutes()
        compose.waitForIdle()
        routes = AudioRoutes(listOf(shokz, earpiece), earpiece)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Earpiece, current route").assertDoesNotExist()
    }

    /** One slot, chosen by mode: a Talk button under voice activity has nothing to do. */
    @Test fun theSlotHoldsTalkUnderPushToTalkAndMuteUnderVoiceActivity() {
        controls(transmitMode = TransmitMode.PushToTalk)
        compose.onNodeWithText("Talk").assertExists()
        compose.onAllNodesWithText("Mute").assertCountEquals(0)
    }

    @Test fun voiceActivityPutsMuteInTheSlot() {
        controls(transmitMode = TransmitMode.VoiceActivity)
        compose.onNodeWithText("Mute").assertExists()
        compose.onAllNodesWithText("Talk").assertCountEquals(0)
    }

    /** A latch, not a press: the mute survives the finger leaving, unlike Talk. */
    @Test fun muteTogglesOnClick() {
        var taps = 0
        controls(transmitMode = TransmitMode.VoiceActivity, onToggleMute = { taps++ })

        compose.onNodeWithContentDescription("Mute — your microphone is live").performClick()
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    /** Stock-dialer convention, like Deafen: the caption stays put, shape and colour carry the
     *  state, and the description spells it out. */
    @Test fun muteCaptionStaysPutWhileMuted() {
        controls(transmitMode = TransmitMode.VoiceActivity, muted = true)
        compose.onNodeWithText("Mute").assertExists()
        compose.onAllNodesWithText("Muted").assertCountEquals(0)
        compose.onNodeWithContentDescription("Unmute — your microphone is off").assertExists()
    }

    /** Only a denied microphone disables Mute: a self-mute is the state it exists to lift. */
    @Test fun aSelfMuteLeavesMutePressable() {
        controls(transmitMode = TransmitMode.VoiceActivity, muted = true, talkBlock = TalkBlock.MUTED)
        compose.onNodeWithContentDescription("Unmute — your microphone is off").assertIsEnabled()
    }

    @Test fun aDeniedMicrophoneDisablesMute() {
        controls(transmitMode = TransmitMode.VoiceActivity, talkBlock = TalkBlock.NO_MICROPHONE)
        compose.onNodeWithContentDescription(
            "Microphone permission denied — you can still hear others",
        ).assertIsNotEnabled()
    }

    /** Self-unmute stays legal under an admin mute but nobody hears it, so the control says so
     *  on both sides of the latch. */
    @Test fun anAdminMuteIsSaidRatherThanBlocked() {
        controls(transmitMode = TransmitMode.VoiceActivity, inaudible = true)
        compose.onNodeWithContentDescription("Mute — the server is already muting you")
            .assertIsEnabled()
    }

    @Test fun unmutingUnderAnAdminMuteSaysItWillNotBeHeard() {
        controls(transmitMode = TransmitMode.VoiceActivity, muted = true, inaudible = true)
        compose.onNodeWithContentDescription(
            "Unmute — the server is muting you, so you still will not be heard",
        ).assertIsEnabled()
    }
}
