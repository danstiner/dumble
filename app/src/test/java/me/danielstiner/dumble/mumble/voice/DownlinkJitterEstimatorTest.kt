package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownlinkJitterEstimatorTest {
    private val ms = 1_000_000L        // ns per ms
    private val frame = 960L           // 20 ms packet = 960 samples (frameNumber += 2)

    /** Feed `count` in-order 20 ms packets starting at bucket-spaced arrivals, jitter per packet from [jit]. */
    private fun feed(est: DownlinkJitterEstimator, count: Int, startTs: Long, startArr: Long, stepNs: Long, jit: (Int) -> Long) {
        var ts = startTs
        var arr = startArr
        for (k in 0 until count) {
            est.onPacket(ts, arr + jit(k), false)
            ts += frame
            arr += stepNs
        }
    }

    @Test fun coldStartIsFloor() {
        val est = DownlinkJitterEstimator()
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun steadyLowJitterStaysAtFloor() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { 0 }
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun sustainedJitterGrowsTarget() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 120 * ms }
        assertEquals(120 * 48, est.targetSamples) // 48 samples/ms
    }

    @Test fun oneOffSpikeIsIgnored() {
        val est = DownlinkJitterEstimator()
        // 45 STEADY packets 200 ms apart in BOTH rtp and arrival (ts += 9600 = 200 ms, arr += 200 ms) so
        // each lands in its own bucket with a constant delay, then a single 300 ms spike in one bucket.
        var ts = 0L; var arr = 1_000_000_000L
        for (k in 0 until 45) { est.onPacket(ts, arr, false); ts += frame * 10; arr += 200 * ms }
        est.onPacket(ts, arr + 300 * ms, false) // spike in one fresh bucket
        // p95 over 40 live buckets excludes the top ~2 → the lone spike is ignored → target stays at floor.
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun clampsAtMax() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 5_000 * ms }
        assertEquals((DownlinkJitterEstimator.MAX_NS * 48 / ms).toInt(), est.targetSamples) // 400 ms → 19200
    }

    @Test fun fixedArrivalOffsetIsCancelled() {
        val est = DownlinkJitterEstimator()
        // Steady traffic with a large CONSTANT arrival offset (+5 s): relative delay is 0 because the
        // window-local baseline subtracts the offset → target stays at the floor. (A realistic ~50 ppm
        // clock drift over the 8 s window is < 1 sample, so offset-cancellation is the property to test.)
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L + 5_000 * ms, stepNs = 20 * ms) { 0 }
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun largeGapAgesOutToFloor() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 120 * ms }
        assertTrue(est.targetSamples > DownlinkJitterEstimator.FLOOR_SAMPLES)
        // A packet ~20 s later: gap >> 8 s window → all buckets aged out (advanceTo cap) → back to floor.
        est.onPacket(60 * frame, 1_000_000_000L + (60 * 20 + 20_000) * ms, false)
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }

    @Test fun resetReturnsToFloor() {
        val est = DownlinkJitterEstimator()
        feed(est, 60, startTs = 0, startArr = 1_000_000_000L, stepNs = 20 * ms) { k -> if (k == 0) 0 else 120 * ms }
        assertTrue(est.targetSamples > DownlinkJitterEstimator.FLOOR_SAMPLES)
        est.reset()
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
        assertFalse(est.lateBurst)
    }

    @Test fun lateBurstFiresOnThreeLatesInWindow() {
        val est = DownlinkJitterEstimator()
        val base = 1_000_000_000L
        est.onPacket(0, base + 0 * ms, true)
        est.onPacket(frame, base + 50 * ms, true)
        assertFalse(est.lateBurst)
        est.onPacket(2 * frame, base + 100 * ms, true) // 3 lates within 100 ms < 200 ms
        assertTrue(est.lateBurst)
    }

    @Test fun lateBurstClearsWhenLatesAgeOut() {
        val est = DownlinkJitterEstimator()
        val base = 1_000_000_000L
        est.onPacket(0, base, true)
        est.onPacket(frame, base + 50 * ms, true)
        est.onPacket(2 * frame, base + 100 * ms, true)
        assertTrue(est.lateBurst)
        est.onPacket(3 * frame, base + 600 * ms, false)
        assertFalse(est.lateBurst)
    }
}
