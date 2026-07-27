package me.danielstiner.dumble.mumble.voice

import java.util.concurrent.atomic.AtomicInteger

/**
 * Sleeps ~1 quantum (10 ms) per write rather than counting down a latch: callers such as
 * MumbleConnectionTest run the receiver open-endedly until disconnect() and can't predict a write
 * count up front. Pacing off real time -- roughly what AudioTrack.write blocks for -- is what
 * keeps VoiceReceiver's loop from hot-spinning while still letting the test just poll state.
 */
class FakeAudioOut : AudioOut {
    val writeCount = AtomicInteger()
    @Volatile var closed = false
        private set

    override fun write(pcm: ShortArray, n: Int): Boolean {
        writeCount.incrementAndGet()
        Thread.sleep(10)
        return true
    }

    override fun close() {
        closed = true
    }
}
