package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.Composable
import me.danielstiner.dumble.mumble.protocol.UserStats
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

    @Composable
    private fun sheet(
        playoutTargetMillis: Int?,
        stats: UserStats? = null,
        name: String = "alice",
    ) = UserDetailSheet(
        session = 9,
        name = name,
        playoutTargetMillis = playoutTargetMillis,
        stats = stats,
        onRefresh = { refreshed += it },
        onDismiss = {},
    )

    @Test fun showsEveryReadingInMillis() {
        compose.setContent {
            sheet(120, stats(tcpPingMillis = 23.5f, udpPingMillis = 18.2f))
        }
        compose.onNodeWithText("alice").assertExists()
        // Named for the buffer, not for delay: it is only the buffer's share of the total.
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
        compose.setContent { sheet(30, stats(tcpPingMillis = 0.27f)) }
        compose.onNodeWithText("0.3 ms").assertExists()
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /**
     * Murmur exchanges UDP pings only with a peer that has a working UDP path, so an average there
     * is the evidence for how their voice travels. Nothing reports it directly.
     */
    @Test fun aUdpPingMeansTheirVoiceTakesTheUdpPath() {
        compose.setContent { sheet(30, stats(tcpPingMillis = 23f, udpPingMillis = 18f)) }
        compose.onNodeWithText("UDP").assertExists()
    }

    @Test fun noUdpPingMeansTheyAreTunnelling() {
        compose.setContent { sheet(30, stats(tcpPingMillis = 23f)) }
        compose.onNodeWithText("TCP").assertExists()
    }

    /** Each reading stands alone — a server that refuses stats must not blank the target too. */
    @Test fun oneReadingCanBeMissingWithoutTheOther() {
        compose.setContent { sheet(120) }
        compose.onNodeWithText("120 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(4)
    }

    /**
     * A retired speaker has no reading, and a dash has to be what says so — "0 ms" would claim the
     * estimator published a target it can never publish.
     */
    @Test fun noReadingIsADashNotAZero() {
        compose.setContent { sheet(null) }
        compose.onAllNodesWithText("—").assertCountEquals(5)
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Absent readings do not blank the ones that are present. */
    @Test fun aPresentReadingSurvivesAnAbsentOne() {
        compose.setContent {
            sheet(null, stats(tcpPingMillis = 12f), name = "dan")
        }
        compose.onNodeWithText("12 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(4)
    }

    /** The sheet drives its own refresh, so composing it must already have asked once. */
    @Test fun composingAsksForTheSubjectsStats() {
        compose.setContent { sheet(120) }
        compose.waitForIdle()
        assertEquals(listOf(9), refreshed)
    }

    /**
     * Jitter belongs to whichever leg carries voice, so a peer on UDP must not be described by
     * their TCP variation — the control connection is not what their audio travels on.
     */
    @Test fun jitterFollowsThePathCarryingVoice() {
        compose.setContent {
            sheet(30, stats(tcpPingMillis = 23f, udpPingMillis = 18f,
                            tcpJitterMillis = 9f, udpJitterMillis = 2f))
        }
        compose.onNodeWithText("2.0 ms").assertExists()
        compose.onNodeWithText("9.0 ms").assertDoesNotExist()
    }

    @Test fun aTunnellingPeerIsDescribedByItsTcpJitter() {
        compose.setContent {
            sheet(30, stats(tcpPingMillis = 23f, tcpJitterMillis = 9f, udpJitterMillis = 2f))
        }
        compose.onNodeWithText("9.0 ms").assertExists()
    }

    /** Kilobits: a voice stream is tens of them, and the bits are noise at this width. */
    @Test fun bandwidthReadsInKilobits() {
        compose.setContent { sheet(30, stats(tcpPingMillis = 1f, bandwidthBitsPerSecond = 8060)) }
        compose.onNodeWithText("8.1 kbit/s").assertExists()
    }
}
