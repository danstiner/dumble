package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTrips() {
        val pcm = ShortArray(1000) { (it * 31 % 65536 - 32768).toShort() }
        val f = tmp.newFile("t.wav")
        WavReader.write(f, pcm)
        assertArrayEquals(pcm, WavReader.read(f))
    }
}
