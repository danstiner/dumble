package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class JitterStatsTest {
    @Test fun emptyDefaultsToFloor() {
        val j = JitterStats()
        assertEquals(10, j.targetMs); assertEquals(0, j.p95Ms); assertEquals(0, j.perSpeaker.size)
    }
    @Test fun aggregateIsMaxOverSpeakers() {
        val j = JitterStats(perSpeaker = listOf(
            SpeakerJitter(1, targetMs = 20, p95Ms = 40, bufferedMs = 60, lateDrops = 2),
            SpeakerJitter(2, targetMs = 300, p95Ms = 4000, bufferedMs = 120, lateDrops = 0),
        ))
        assertEquals(300, j.targetMs); assertEquals(4000, j.p95Ms)
    }
}
