package me.danielstiner.dumble.mumble.voice

/**
 * Sleeps ~1 quantum (10 ms) per write rather than counting down a latch: callers such as
 * MumbleConnectionTest run the receiver open-endedly until disconnect() and can't predict a write
 * count up front. Pacing off real time — roughly what AudioTrack.write blocks for — is what keeps
 * VoiceReceiver's loop from hot-spinning while still letting the test just poll state.
 *
 * [writeSleepMillis] is lowered by tests that need many writes: a stats sample costs 100 of them,
 * which is a full second at the default.
 */
class FakeAudioOut(private val writeSleepMillis: Long = 10) : AudioOut {
    @Volatile var closed = false
        private set

    /** Whatever the next outputStats() should report. Written by tests, read on the audio thread. */
    @Volatile var nextStats = OutputStats(latencyMs = null, underrunsTotal = 0)

    override fun write(pcm: ShortArray, n: Int): Boolean {
        if (writeSleepMillis > 0) Thread.sleep(writeSleepMillis)
        return true
    }

    override fun outputStats() = nextStats

    override fun close() {
        closed = true
    }
}
