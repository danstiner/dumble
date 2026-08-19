package me.danielstiner.dumble.ui.connect

import androidx.compose.runtime.Composable
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
    private fun sheet(
        playoutTargetMillis: Int?,
        tcpPingMillis: Float? = null,
        udpPingMillis: Float? = null,
        name: String = "alice",
    ) = UserDetailSheet(
        session = 9,
        name = name,
        playoutTargetMillis = playoutTargetMillis,
        tcpPingMillis = tcpPingMillis,
        udpPingMillis = udpPingMillis,
        onRefresh = { refreshed += it },
        onDismiss = {},
    )

    @Test fun showsEveryReadingInMillis() {
        compose.setContent {
            sheet(playoutTargetMillis = 120, tcpPingMillis = 23.5f, udpPingMillis = 18.2f)
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
        compose.setContent { sheet(playoutTargetMillis = 30, tcpPingMillis = 0.27f) }
        compose.onNodeWithText("0.3 ms").assertExists()
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /**
     * Murmur exchanges UDP pings only with a peer that has a working UDP path, so an average there
     * is the evidence for how their voice travels. Nothing reports it directly.
     */
    @Test fun aUdpPingMeansTheirVoiceTakesTheUdpPath() {
        compose.setContent { sheet(playoutTargetMillis = 30, tcpPingMillis = 23f, udpPingMillis = 18f) }
        compose.onNodeWithText("UDP").assertExists()
    }

    @Test fun noUdpPingMeansTheyAreTunnelling() {
        compose.setContent { sheet(playoutTargetMillis = 30, tcpPingMillis = 23f, udpPingMillis = null) }
        compose.onNodeWithText("TCP").assertExists()
    }

    /** Each reading stands alone — a server that refuses stats must not blank the target too. */
    @Test fun oneReadingCanBeMissingWithoutTheOther() {
        compose.setContent { sheet(playoutTargetMillis = 120) }
        compose.onNodeWithText("120 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    /**
     * A retired speaker has no reading, and a dash has to be what says so — "0 ms" would claim the
     * estimator published a target it can never publish.
     */
    @Test fun noReadingIsADashNotAZero() {
        compose.setContent { sheet(playoutTargetMillis = null) }
        compose.onAllNodesWithText("—").assertCountEquals(3)
        compose.onNodeWithText("0 ms").assertDoesNotExist()
    }

    /** Absent readings do not blank the ones that are present. */
    @Test fun aPresentReadingSurvivesAnAbsentOne() {
        compose.setContent {
            sheet(playoutTargetMillis = null, tcpPingMillis = 12f, name = "dan")
        }
        compose.onNodeWithText("12 ms").assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    /** The sheet drives its own refresh, so composing it must already have asked once. */
    @Test fun composingAsksForTheSubjectsStats() {
        compose.setContent { sheet(playoutTargetMillis = 120) }
        compose.waitForIdle()
        assertEquals(listOf(9), refreshed)
    }
}
