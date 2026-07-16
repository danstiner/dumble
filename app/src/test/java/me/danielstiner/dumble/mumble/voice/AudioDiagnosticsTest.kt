package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDiagnosticsTest {
    private fun square(amp: Int, n: Int = 960) = ShortArray(n) { i -> if (i % 2 == 0) amp.toShort() else (-amp).toShort() }

    @Test fun rmsDbFsKnownLevels() {
        assertEquals(0.0, rmsDbFs(square(32767), 0, 960).toDouble(), 0.1)     // full scale ≈ 0
        assertEquals(-6.02, rmsDbFs(square(16384), 0, 960).toDouble(), 0.1)   // half scale
    }

    @Test fun rmsDbFsSilenceIsFlooredNotInfinite() {
        val db = rmsDbFs(ShortArray(960), 0, 960)
        assertEquals(-120f, db, 0f)
        assertTrue(db.isFinite())
    }

    @Test fun postDenoiseIsPostGainMinusGain() {
        val d = AudioDiagnostics(postGainDbFs = -18f, agcGainDb = 6f)
        assertEquals(-24f, d.postDenoiseDbFs, 1e-4f)
    }

    @Test fun rnnoiseAttenuationDerivation() {
        val d = AudioDiagnostics(rawDbFs = -12f, postGainDbFs = -18f, agcGainDb = 6f)
        assertEquals(12f, d.rnnoiseAttenuationDb, 1e-4f)   // -12 - (-24)
    }

    @Test fun idleDefaultsDoNotBlowUp() {
        val d = AudioDiagnostics()
        assertTrue(d.postDenoiseDbFs == Float.NEGATIVE_INFINITY)
        assertTrue(d.rnnoiseAttenuationDb.isNaN())
    }

    @Test fun attenuationIsNaNWhenRawUnknown() {
        val d = AudioDiagnostics(postGainDbFs = -18f, agcGainDb = 6f)  // rawDbFs defaults to -Infinity
        assertTrue(d.rnnoiseAttenuationDb.isNaN())
    }
}
