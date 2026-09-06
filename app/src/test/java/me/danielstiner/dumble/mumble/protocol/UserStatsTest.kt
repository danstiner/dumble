package me.danielstiner.dumble.mumble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserStatsTest {

    /** Lost over everything the server expected: the late ones did arrive, so they count. */
    @Test fun lossIsTheShareOfWhatTheServerExpected() {
        assertEquals(3.0 / 1000, UserStats.PacketCounts(good = 990, late = 7, lost = 3, resync = 0).lossFraction!!, 1e-12)
    }

    /** A talker the server has not heard from yet has nothing to be a share of. */
    @Test fun noDatagramsIsNoReadingNotZeroLoss() {
        assertNull(UserStats.PacketCounts(0, 0, 0, 0).lossFraction)
    }
}
