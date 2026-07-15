package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRnnoiseLoadTest {
    @Test fun loadsAndProcessesAFrame() {
        val state = NativeRnnoise.createState()
        assertNotEquals(0L, state)
        val pcm = ShortArray(FRAME_SAMPLES_10MS) { ((it % 100) - 50).toShort() }
        val prob = NativeRnnoise.processFrame(state, pcm, 0)
        assertTrue("VAD prob in [0,1], was $prob", prob in 0f..1f)
        NativeRnnoise.destroyState(state)
    }
}
