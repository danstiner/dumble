package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyEmaTest {
    @Test fun nanUntilFirstSample() {
        assertTrue(LatencyEma().valueMs.isNaN())
    }

    @Test fun firstSampleSeedsValue() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(10.0)
        assertEquals(10.0, ema.valueMs, 0.0)
    }

    @Test fun convergesTowardConstant() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(10.0)
        repeat(200) { ema.update(20.0) }
        assertEquals(20.0, ema.valueMs, 0.01)
    }

    @Test fun gapHoldsLastValue() {
        val ema = LatencyEma(alpha = 0.1)
        ema.update(12.0)
        assertEquals(12.0, ema.valueMs, 0.0)
    }

    @Test fun smoothsSecondSample() {
        val ema = LatencyEma() // default alpha = 0.1 — pins the default to observable behavior
        ema.update(10.0)
        ema.update(20.0)
        assertEquals(11.0, ema.valueMs, 0.0)
    }
}
