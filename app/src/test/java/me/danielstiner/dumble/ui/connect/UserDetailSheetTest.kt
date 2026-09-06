package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.Composable
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutDelay
import me.danielstiner.dumble.mumble.voice.SendDelay
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Robolectric for the reason [ChannelTreeViewTest] gives: CI runs testDebugUnitTest only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserDetailSheetTest {

    @get:Rule val compose = createComposeRule()

    private val refreshed = mutableListOf<Int>()

    @Composable
    private fun stats(
        tcpPing: Duration? = null,
        udpPing: Duration? = null,
        tcpJitter: Duration? = null,
        udpJitter: Duration? = null,
        bandwidthBitsPerSecond: Int? = null,
    ) = UserStats(9, tcpPing, udpPing, tcpJitter, udpJitter, bandwidthBitsPerSecond)

    /** A delay with only the jitter-buffer depth read: what most of these cases care about. */
    private fun buffered(millis: Int?) =
        PlayoutDelay(network = null, jitterBuffer = millis?.milliseconds, audioOutput = null)

    @Composable
    private fun Sheet(
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
            Sheet(buffered(120), stats(tcpPing = 23.5.milliseconds, udpPing = 18.2.milliseconds))
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
        compose.setContent { Sheet(buffered(30), stats(tcpPing = 0.27.milliseconds)) }
        compose.onNodeWithText("0.3 ms").assertExists()
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Fixed labels for both pings; a dash under UDP is the evidence that a peer tunnels. */
    @Test fun eachPingHasItsOwnRow() {
        compose.setContent { Sheet(buffered(30), stats(tcpPing = 23.milliseconds, udpPing = 18.milliseconds)) }
        compose.onNodeWithText("TCP ping").assertExists()
        compose.onNodeWithText("UDP ping").assertExists()
        compose.onNodeWithText("23 ms").assertExists()
        compose.onNodeWithText("18 ms").assertExists()
    }

    @Test fun aTunnellingPeerHasNoUdpPing() {
        compose.setContent { Sheet(buffered(30), stats(tcpPing = 23.milliseconds)) }
        compose.onNodeWithText("UDP ping").assertExists()
        // Network, audio output, UDP ping, jitter, bandwidth.
        compose.onAllNodesWithText("—").assertCountEquals(5)
    }

    /** Each reading stands alone — a server that refuses stats must not blank the depth too. */
    @Test fun oneReadingCanBeMissingWithoutTheOther() {
        compose.setContent { Sheet(buffered(120)) }
        compose.onNodeWithText("120 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(6)
    }

    /** A retired speaker has no reading; "0 ms" would claim a drained queue, a real reading. */
    @Test fun noReadingIsADashNotAZero() {
        compose.setContent { Sheet(buffered(null)) }
        compose.onAllNodesWithText("—").assertCountEquals(8)
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Absent readings do not blank the ones that are present. */
    @Test fun aPresentReadingSurvivesAnAbsentOne() {
        compose.setContent {
            Sheet(buffered(null), stats(tcpPing = 12.milliseconds), name = "dan")
        }
        compose.onNodeWithText("12 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(7)
    }

    /** The sheet drives its own refresh, so composing it must already have asked once. */
    @Test fun composingAsksForTheSubjectsStats() {
        compose.setContent { Sheet(buffered(120)) }
        compose.waitForIdle()
        assertEquals(listOf(9), refreshed)
    }

    /**
     * Jitter belongs to whichever leg carries voice, so a peer on UDP must not be described by
     * their TCP variation — the control connection is not what their audio travels on.
     */
    @Test fun jitterFollowsThePathCarryingVoice() {
        compose.setContent {
            Sheet(buffered(30), stats(tcpPing = 23.milliseconds, udpPing = 18.milliseconds,
                            tcpJitter = 9.milliseconds, udpJitter = 2.milliseconds))
        }
        compose.onNodeWithText("2.0 ms").assertExists()
        compose.onNodeWithText("9.0 ms").assertDoesNotExist()
    }

    @Test fun aTunnellingPeerIsDescribedByItsTcpJitter() {
        compose.setContent {
            Sheet(buffered(30), stats(tcpPing = 23.milliseconds, tcpJitter = 9.milliseconds, udpJitter = 2.milliseconds))
        }
        compose.onNodeWithText("9.0 ms").assertExists()
    }

    /** Kilobits: a voice stream is tens of them, and the bits are noise at this width. */
    @Test fun bandwidthReadsInKilobits() {
        compose.setContent { Sheet(buffered(30), stats(tcpPing = 1.milliseconds, bandwidthBitsPerSecond = 8060)) }
        compose.onNodeWithText("8.1 kbit/s").assertExists()
    }

    /** Every step with a reading is in the total: 3 + 150 + 20 = 173. */
    @Test fun theTotalAddsEveryStepThatHasAReading() {
        compose.setContent {
            Sheet(PlayoutDelay(network = 3.milliseconds, jitterBuffer = 150.milliseconds,
                               audioOutput = 20.milliseconds))
        }
        compose.onNodeWithText("Latency (server to ear)").assertExists()
        compose.onNodeWithText("> 173 ms").assertExists()
    }

    /** Their microphone-to-encoder time never arrives, so the total is a floor. */
    @Test fun theTotalIsAFloor() {
        compose.setContent { Sheet(buffered(150)) }
        compose.onNodeWithText("> 150 ms").assertExists()
    }

    /** An absent step shrinks the estimate rather than counting as zero delay for that step. */
    @Test fun anAbsentStepIsSkippedNotZeroed() {
        compose.setContent {
            Sheet(PlayoutDelay(network = null, jitterBuffer = 150.milliseconds,
                               audioOutput = 20.milliseconds))
        }
        compose.onNodeWithText("> 170 ms").assertExists()
    }

    /** A drained queue is a real 0 — the audio has moved into the track — not an absence. */
    @Test fun aDrainedQueueReadsAsZero() {
        compose.setContent { Sheet(buffered(0)) }
        compose.onNodeWithText("0 ms").assertExists()
    }

    /** A speaker the engine holds no slot for reads as absent, not as a confident zero. */
    @Test fun aRetiredSpeakerHasNoDelayRowsAtAll() {
        compose.setContent { Sheet(null) }
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    // ---- our own row ----

    @Composable
    private fun SelfSheet(delay: SendDelay?, capture: CaptureStats? = null, stats: UserStats? = null) =
        SelfDetailSheet(
            name = "dan",
            delay = delay,
            capture = capture,
            stats = stats,
            onRefresh = { refreshed += 0 },
            onDismiss = {},
        )

    private fun capture(streamOverruns: Long = 0, ringOverruns: Long = 0, droppedFrames: Int = 0) =
        CaptureStats(
            encodedPackets = 0, encodeErrors = 0, encodeMicrosMean = 0, encodeMicrosMax = 0,
            ringOverruns = ringOverruns, skippedSamples = 0, streamOverruns = streamOverruns,
            framesPerBurst = 0, droppedFrames = droppedFrames, inputLatencyMillis = null,
        )

    private fun uplink(good: Int, lost: Int) =
        UserStats(9, null, null, null, null, null, UserStats.PacketCounts(good, 0, lost, 0))

    /** The send side, added up and floored: 12 + 20.4 + 3 = 35.4. */
    @Test fun ourOwnSheetAddsUpTheSendSide() {
        compose.setContent {
            SelfSheet(SendDelay(inputBuffer = 12.milliseconds, encode = 20.4.milliseconds, network = 3.milliseconds))
        }
        compose.onNodeWithText("Latency (mouth to server)").assertExists()
        compose.onNodeWithText("Latency (server to ear)").assertDoesNotExist()
        compose.onNodeWithText("> 35 ms").assertExists()
        compose.onNodeWithText("12 ms").assertExists()
        compose.onNodeWithText("20 ms").assertExists()
        compose.onNodeWithText("3.0 ms").assertExists()
    }

    /** One in a thousand is a tenth of a percent, and must not round to a clean zero. */
    @Test fun lossToTheServerKeepsItsTenthOfAPercent() {
        compose.setContent { SelfSheet(null, stats = uplink(good = 999, lost = 1)) }
        compose.onNodeWithText("Packet loss").assertExists()
        compose.onNodeWithText("0.1 %").assertExists()
    }

    /** A talker the server has not heard from has no loss to report; "0.0 %" would claim it has. */
    @Test fun noDatagramsCountedIsNoLossReading() {
        compose.setContent { SelfSheet(null, stats = uplink(good = 0, lost = 0)) }
        compose.onNodeWithText("0.0 %").assertDoesNotExist()
    }

    /** Both overruns are microphone audio lost before it was encoded, so they read as one number. */
    @Test fun theOverrunsAreOneNumber() {
        compose.setContent { SelfSheet(null, capture = capture(streamOverruns = 2, ringOverruns = 3, droppedFrames = 4)) }
        compose.onNodeWithText("5").assertExists()
        compose.onNodeWithText("4").assertExists()
    }

    /** Without a capture session every reading of ours is absent, not zero. */
    @Test fun withoutACaptureSessionEveryOwnReadingIsAbsent() {
        compose.setContent { SelfSheet(null) }
        compose.onAllNodesWithText("—").assertCountEquals(8)
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test fun ourOwnSheetAsksForOurStatsToo() {
        compose.setContent { SelfSheet(null) }
        compose.waitForIdle()
        assertEquals(listOf(0), refreshed)
    }
}
