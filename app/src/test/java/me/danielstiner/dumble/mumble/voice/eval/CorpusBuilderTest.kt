package me.danielstiner.dumble.mumble.voice.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusBuilderTest {
    @Test fun buildsLabeledClips() {
        val clips = CorpusBuilder.build()
        assertTrue("at least 3 real utterance clips", clips.size >= 3)
        for (c in clips) {
            assertTrue("${c.name} has audio", c.pcm.isNotEmpty())
            assertTrue("${c.name} has segments", c.segments.isNotEmpty())
            // Segments are contiguous and cover the clip from 0 (real labels are NOT grid-aligned,
            // but they must tile the timeline with no gaps or overlaps).
            assertEquals("${c.name} starts at 0", 0, c.segments.first().startMs)
            for (i in 1 until c.segments.size)
                assertEquals("${c.name} segment ${i} contiguous",
                    c.segments[i - 1].endMs, c.segments[i].startMs)
            assertTrue("${c.name} scoreFrom within clip", c.scoreFromMs in 0..c.segments.last().endMs)
        }
        assertTrue("has a speech clip", clips.any { c -> c.segments.any { it.kind == Kind.SPEECH } })
        assertTrue("has a clip with a real pause between regions",
            clips.any { c -> c.segments.any { it.kind == Kind.PAUSE } })
    }
}
