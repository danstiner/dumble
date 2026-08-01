package me.danielstiner.dumble.ui.connect

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
class PttButtonTest {

    @get:Rule val compose = createComposeRule()

    private val transmitting = mutableListOf<Boolean>()

    private fun setContent(enabled: Boolean = true) = compose.setContent {
        PttButton(enabled = enabled, disabledReason = null, onTransmitting = { transmitting += it })
    }

    @Test
    fun pressOpensTheGateAndReleaseClosesIt() {
        setContent()
        compose.onNodeWithText("Hold to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf(true), transmitting)

        compose.onNodeWithText("Hold to talk").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(true, false), transmitting)
    }

    @Test
    fun aCancelledGestureStillClosesTheGate() {
        // The reason this is not onClick. A gesture killed by a system dialog or a drag away never
        // delivers an up, and failing to close the gate leaves the microphone live.
        setContent()
        compose.onNodeWithText("Hold to talk").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithText("Hold to talk").performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals("a cancelled gesture must still close the gate", listOf(true, false), transmitting)
    }

    @Test
    fun aDisabledButtonNeverOpensTheGate() {
        setContent(enabled = false)
        compose.onNodeWithText("Microphone unavailable").performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(emptyList<Boolean>(), transmitting)
    }
}
