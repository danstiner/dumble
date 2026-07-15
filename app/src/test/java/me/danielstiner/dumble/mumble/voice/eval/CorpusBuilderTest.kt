package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusBuilderTest {
    @Test fun buildsLabeledClips() {
        val clips = CorpusBuilder.build()
        assertTrue("at least 5 clips", clips.size >= 5)
        for (c in clips) {
            assertTrue("${c.name} has audio", c.pcm.isNotEmpty())
            assertTrue("${c.name} has segments", c.segments.isNotEmpty())
            for (s in c.segments) {
                assertTrue("${c.name} start on grid", (s.startMs % 20) == 0)
                assertTrue("${c.name} end on grid", (s.endMs % 20) == 0)
            }
        }
        assertTrue("has a paused clip", clips.any { c -> c.segments.any { it.kind == Kind.PAUSE } })
        assertTrue("has a noise-only clip", clips.any { c -> c.segments.all { it.kind == Kind.NOISE || it.kind == Kind.SILENCE } })
    }
}
