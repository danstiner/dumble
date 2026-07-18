package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStreamTest {
    private val codec = FakeOpusCodec()
    private fun encoded(samples: Int): ByteArray {
        // FakeOpusCodec header encodes the sample count; build a packet of that span.
        val b = ByteArray(4)
        b[0] = (samples ushr 24).toByte(); b[1] = (samples ushr 16).toByte()
        b[2] = (samples ushr 8).toByte();  b[3] = samples.toByte()
        return b
    }

    @Test fun fortyMsPacketYieldsTwoTicks() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, encoded(1920), 1920, false, 1_000_000_000L)          // one 40 ms packet
        val out = ShortArray(FRAME_SAMPLES_20MS)
        assertTrue(s.fillTick(out))                     // tick 1: first 960 from the single decode
        assertTrue(s.fillTick(out))                     // tick 2: second 960 from FIFO, no new decode
    }

    @Test fun lazyDecoderNotCreatedOnOffer() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        assertFalse(s.decoderCreated)                   // still null until first fillTick
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))
        assertTrue(s.decoderCreated)
    }

    @Test fun terminatorBoundaryResetsNotRetires() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        s.offer(960, ByteArray(0), 0, true, 1_020_000_000L)             // terminator at 960
        s.fillTick(ShortArray(FRAME_SAMPLES_20MS))      // plays ts 0..960
        assertFalse(s.fillTick(ShortArray(FRAME_SAMPLES_20MS))) // past terminator → reset (silence)
        assertFalse("boundary resets in place, does not retire", s.retired)
    }

    @Test fun retiresOnlyAfterLongIdle() {
        val s = SpeakerStream(codec, targetSamples = { 0 }, maxHoldTicks = 3, retireIdleTicks = 3)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)            // one packet, NO terminator
        val out = ShortArray(FRAME_SAMPLES_20MS)
        // tick1 decode; ticks 2-4 hold; tick5 reset; then idle ticks accumulate to retire
        repeat(15) { s.fillTick(out) }
        assertTrue("retires after sustained silence then long idle", s.retired)
    }

    @Test fun resumeAfterHoldIsNotLateDropped() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        assertTrue(s.fillTick(out))                     // decode ts0 → cursor held at 960
        assertEquals(-50, out[0].toInt())               // real audio
        repeat(3) { s.fillTick(out) }                   // live underrun → plcHold ×3, cursor STAYS 960
        // peer resumes at the continued timestamp (frame_number paused during silence)
        assertEquals("resume at held cursor is accepted, not late",
            JitterBuffer.OfferResult.QUEUED, s.offer(960, encoded(960), 960, false, 1_100_000_000L))
        assertTrue(s.fillTick(out))
        assertEquals(-50, out[0].toInt())               // resumed talkspurt decodes (not lost)
    }

    @Test fun terminatorReAnchorsSecondTalkspurtWithPrebuffer() {
        val s = SpeakerStream(codec, targetSamples = { FRAME_SAMPLES_20MS * 2 })  // 1920
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        s.offer(960, encoded(960), 960, true, 1_020_000_000L)           // terminated talkspurt 1 (buffered = 1920)
        repeat(4) { s.fillTick(out) }                   // drains both, resets on past-terminator underrun
        assertFalse(s.retired)
        // talkspurt 2: one packet is below prebuffer AND the tag was cleared → must wait
        s.offer(1920, encoded(960), 960, false, 1_100_000_000L)
        assertFalse("second talkspurt honors prebuffer (tag cleared)", s.fillTick(out))
        s.offer(2880, encoded(960), 960, false, 1_120_000_000L)         // buffered reaches 1920
        assertTrue(s.fillTick(out))                     // prebuffer met → plays
    }

    @Test fun shortPauseKeepsDecoderAndStream() {
        val s = SpeakerStream(codec, targetSamples = { 0 }, maxHoldTicks = 10)
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        s.fillTick(out)
        assertTrue(s.decoderCreated)
        repeat(3) { s.fillTick(out) }                   // short hold, well under maxHoldTicks
        assertFalse(s.retired)
        assertTrue(s.decoderCreated)
        assertEquals(JitterBuffer.OfferResult.QUEUED, s.offer(960, encoded(960), 960, false, 1_100_000_000L))
    }

    @Test fun measuredHoleIsConcealedThenDecoded() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, false, 1_000_000_000L)            // ts 0
        s.offer(1920, encoded(960), 960, false, 1_020_000_000L)         // ts 1920 → ts 960 frame lost (a real hole)
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())  // decode ts0 → cursor 960
        assertTrue(s.fillTick(out)); assertEquals(0, out[0].toInt())    // measured hole → PLC, cursor → 1920
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())  // ts 1920 due → decodes, not dropped
    }

    @Test fun contiguousTalkspurtPastTerminatorDoesNotSpuriouslyReset() {
        val s = SpeakerStream(codec, targetSamples = { 0 })
        val out = ShortArray(FRAME_SAMPLES_20MS)
        s.offer(0, encoded(960), 960, true, 1_000_000_000L)             // talkspurt-1 last frame + terminator (tag = 0)
        s.offer(960, encoded(960), 960, false, 1_020_000_000L)          // talkspurt-2 resumes contiguously at ts 960
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())   // decode ts0 (terminator frame), cursor=960
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())   // decode ts960 → clears stale tag, cursor=1920
        // mid-talkspurt-2 underrun (ts 1920 not yet arrived): must CONCEAL (plcHold), NOT reset
        assertTrue("mid-talkspurt loss conceals, not resets", s.fillTick(out))
        assertEquals(0, out[0].toInt())                 // PLC/hold silence, cursor HELD at 1920
        // resumed frame at the held timestamp is accepted (proves no spurious reset happened)
        assertEquals("resume accepted (cursor held, not reset)",
            JitterBuffer.OfferResult.QUEUED, s.offer(1920, encoded(960), 960, false, 1_100_000_000L))
        assertTrue(s.fillTick(out)); assertEquals(-50, out[0].toInt())
    }

    /** Valve FIRES and DEEPENS: with a due frame queued, a valve tick emits concealment instead,
     *  holds the cursor, and the real frame plays one tick later (net +20 ms depth). */
    @Test fun valveTickInsertsPlcBeforeQueuedFrameAndHoldsCursor() {
        val est = DownlinkJitterEstimator()
        var target = 0                                  // anchor immediately, no terminator needed
        val s = SpeakerStream(codec, estimator = est, targetSamples = { target })
        val out = ShortArray(FRAME_SAMPLES_20MS)

        s.offer(0, encoded(960), 960, false, 1_000_000_000L)
        assertTrue(s.fillTick(out))                     // anchors, decodes ts0 -> cursor 960
        assertEquals(-50, out[0].toInt())

        target = 100_000                                // permanently "shallow"
        var arr = 2_000_000_000L                        // 3 LATE within 200 ms -> lateBurst
        repeat(3) { s.offer(0, encoded(960), 960, false, arr); arr += 50_000_000L }
        assertTrue(est.lateBurst)

        s.offer(960, encoded(960), 960, false, arr)     // real due frame queued at the cursor (non-empty)
        assertTrue(s.fillTick(out))                     // valve tick
        assertEquals("valve tick emits concealment, not the queued frame", 0, out[0].toInt())
        assertEquals("cursor held (deepen, not advance)", 960L, s.playoutCursor())
        assertTrue(s.fillTick(out))
        assertEquals("queued frame deferred exactly one tick", -50, out[0].toInt())
        assertEquals(1920L, s.playoutCursor())
    }

    /** offer() resets the estimator after a long inbound-silence gap (frame_number renumber),
     *  even when the ts jump itself is small. Never fillTick -> cursor stays -1 -> offers are QUEUED. */
    @Test fun offerResetsEstimatorAfterLongArrivalGap() {
        val est = DownlinkJitterEstimator()
        val s = SpeakerStream(codec, estimator = est)
        var arr = 1_000_000_000L
        for (k in 0 until 60) {                                   // jittery feed raises the target
            s.offer(k * 960L, encoded(960), 960, false, arr + if (k == 0) 0L else 120_000_000L)
            arr += 20_000_000L
        }
        assertTrue("target grew above floor", est.targetSamples > DownlinkJitterEstimator.FLOOR_SAMPLES)
        s.offer(60 * 960L, encoded(960), 960, false, arr + 5_000_000_000L)   // ~5 s later -> renumber -> reset
        assertEquals(DownlinkJitterEstimator.FLOOR_SAMPLES, est.targetSamples)
    }
}
