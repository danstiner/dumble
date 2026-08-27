package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.Composable
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.PlayoutDelay
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/** Robolectric for the reason [ChannelTreeViewTest] gives: CI runs testDebugUnitTest only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserDetailSheetTest {

    @get:Rule val compose = createComposeRule()

    private val refreshed = mutableListOf<Int>()

    @Composable
    private fun stats(
        tcpPingMillis: Float? = null,
        udpPingMillis: Float? = null,
        tcpJitterMillis: Float? = null,
        udpJitterMillis: Float? = null,
        bandwidthBitsPerSecond: Int? = null,
    ) = UserStats(9, tcpPingMillis, udpPingMillis, tcpJitterMillis, udpJitterMillis,
                  bandwidthBitsPerSecond)

    /** A delay with only the jitter-buffer depth read: what most of these cases care about. */
    private fun buffered(millis: Int?) =
        PlayoutDelay(network = null, jitterBuffer = millis?.milliseconds, audioOutput = null)

    @Composable
    private fun sheet(
        delay: PlayoutDelay?,
        stats: UserStats? = null,
        name: String = "alice",
    ) = UserDetailSheet(
        session = 9,
        name = name,
        delay = delay,
        stats = stats,
        onRefresh = { refreshed += it },
        onDismiss = {},
    )

    @Test fun showsEveryReadingInMillis() {
        compose.setContent {
            sheet(buffered(120), stats(tcpPingMillis = 23.5f, udpPingMillis = 18.2f))
        }
        compose.onNodeWithText("alice").assertExists()
        compose.onNodeWithText("Jitter buffer").assertExists()
        compose.onNodeWithText("120 ms").assertExists()
        // Rounded, not truncated: the server's average is a float and the row is a whole number.
        compose.onNodeWithText("24 ms").assertExists()
        compose.onNodeWithText("18 ms").assertExists()
    }

    /**
     * A LAN peer measures a fraction of a millisecond. Rounded to whole milliseconds that reads as
     * a broken zero rather than as a fast link, so below 10 ms the tenth is what distinguishes it.
     */
    @Test fun aSubMillisecondPingKeepsItsTenth() {
        compose.setContent { sheet(buffered(30), stats(tcpPingMillis = 0.27f)) }
        compose.onNodeWithText("0.3 ms").assertExists()
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Fixed labels for both pings; a dash under UDP is the evidence that a peer tunnels. */
    @Test fun eachPingHasItsOwnRow() {
        compose.setContent { sheet(buffered(30), stats(tcpPingMillis = 23f, udpPingMillis = 18f)) }
        compose.onNodeWithText("TCP ping").assertExists()
        compose.onNodeWithText("UDP ping").assertExists()
        compose.onNodeWithText("23 ms").assertExists()
        compose.onNodeWithText("18 ms").assertExists()
    }

    @Test fun aTunnellingPeerHasNoUdpPing() {
        compose.setContent { sheet(buffered(30), stats(tcpPingMillis = 23f)) }
        compose.onNodeWithText("UDP ping").assertExists()
        // Network, audio output, UDP ping, jitter, bandwidth.
        compose.onAllNodesWithText("—").assertCountEquals(5)
    }

    /** Each reading stands alone — a server that refuses stats must not blank the depth too. */
    @Test fun oneReadingCanBeMissingWithoutTheOther() {
        compose.setContent { sheet(buffered(120)) }
        compose.onNodeWithText("120 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(6)
    }

    /** A retired speaker has no reading; "0 ms" would claim a drained queue, a real reading. */
    @Test fun noReadingIsADashNotAZero() {
        compose.setContent { sheet(buffered(null)) }
        compose.onAllNodesWithText("—").assertCountEquals(8)
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Absent readings do not blank the ones that are present. */
    @Test fun aPresentReadingSurvivesAnAbsentOne() {
        compose.setContent {
            sheet(buffered(null), stats(tcpPingMillis = 12f), name = "dan")
        }
        compose.onNodeWithText("12 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(7)
    }

    /** The sheet drives its own refresh, so composing it must already have asked once. */
    @Test fun composingAsksForTheSubjectsStats() {
        compose.setContent { sheet(buffered(120)) }
        compose.waitForIdle()
        assertEquals(listOf(9), refreshed)
    }

    /**
     * Jitter belongs to whichever leg carries voice, so a peer on UDP must not be described by
     * their TCP variation — the control connection is not what their audio travels on.
     */
    @Test fun jitterFollowsThePathCarryingVoice() {
        compose.setContent {
            sheet(buffered(30), stats(tcpPingMillis = 23f, udpPingMillis = 18f,
                            tcpJitterMillis = 9f, udpJitterMillis = 2f))
        }
        compose.onNodeWithText("2.0 ms").assertExists()
        compose.onNodeWithText("9.0 ms").assertDoesNotExist()
    }

    @Test fun aTunnellingPeerIsDescribedByItsTcpJitter() {
        compose.setContent {
            sheet(buffered(30), stats(tcpPingMillis = 23f, tcpJitterMillis = 9f, udpJitterMillis = 2f))
        }
        compose.onNodeWithText("9.0 ms").assertExists()
    }

    /** Kilobits: a voice stream is tens of them, and the bits are noise at this width. */
    @Test fun bandwidthReadsInKilobits() {
        compose.setContent { sheet(buffered(30), stats(tcpPingMillis = 1f, bandwidthBitsPerSecond = 8060)) }
        compose.onNodeWithText("8.1 kbit/s").assertExists()
    }

    /** Every step with a reading is in the total: 3 + 150 + 20 = 173. */
    @Test fun theTotalAddsEveryStepThatHasAReading() {
        compose.setContent {
            sheet(PlayoutDelay(network = 3.milliseconds, jitterBuffer = 150.milliseconds,
                               audioOutput = 20.milliseconds))
        }
        compose.onNodeWithText("> 173 ms").assertExists()
    }

    /** Their microphone-to-encoder time never arrives, so the total is a floor. */
    @Test fun theTotalIsAFloor() {
        compose.setContent { sheet(buffered(150)) }
        compose.onNodeWithText("> 150 ms").assertExists()
    }

    /** An absent step shrinks the estimate rather than counting as zero delay for that step. */
    @Test fun anAbsentStepIsSkippedNotZeroed() {
        compose.setContent {
            sheet(PlayoutDelay(network = null, jitterBuffer = 150.milliseconds,
                               audioOutput = 20.milliseconds))
        }
        compose.onNodeWithText("> 170 ms").assertExists()
    }

    /** A drained queue is a real 0 — the audio has moved into the track — not an absence. */
    @Test fun aDrainedQueueReadsAsZero() {
        compose.setContent { sheet(buffered(0)) }
        compose.onNodeWithText("0 ms").assertExists()
    }

    /** A speaker the engine holds no slot for reads as absent, not as a confident zero. */
    @Test fun aRetiredSpeakerHasNoDelayRowsAtAll() {
        compose.setContent { sheet(null) }
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }
}
