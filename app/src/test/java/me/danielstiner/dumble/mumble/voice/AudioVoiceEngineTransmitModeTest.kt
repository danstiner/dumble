package me.danielstiner.dumble.mumble.voice

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioVoiceEngineTransmitModeTest {

    /** ±amp square wave — same shape the frame-number test proves opens the VA gate. */
    private class SquareAudioIn(private val amp: Int = 8000) : AudioIn {
        override fun read(out: ShortArray, n: Int): Int {
            for (i in 0 until n) out[i] = if (i % 2 == 0) amp.toShort() else (-amp).toShort()
            return n
        }
        override fun close() {}
    }

    private fun engine(): AudioVoiceEngine =
        AudioVoiceEngine(FakeOpusCodec(), { SquareAudioIn() }, { FakeAudioOut() }).also { it.start() }

    @Test fun pttNotHeldTransmitsNothing() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        repeat(5) { assertNull("PTT idle must not transmit", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun pttHeldTransmitsOneFramePerCapture() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK); e.setPttHeld(true)
        repeat(3) {
            val f = e.nextOutgoingFrame(0)
            assertTrue("held → a real, non-terminator frame", f != null && !f.isTerminator && f.length > 0)
        }
        e.stop()
    }

    @Test fun pttReleaseEmitsExactlyOneRealTerminatorThenSilence() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        e.setPttHeld(true); repeat(3) { e.nextOutgoingFrame(0) }
        e.setPttHeld(false)

        val closing = e.nextOutgoingFrame(0)
        assertTrue("release → one terminator", closing != null && closing.isTerminator)
        assertTrue("terminator is a real (non-empty) frame", closing!!.length > 0)
        repeat(3) { assertNull("silence after the terminator", e.nextOutgoingFrame(0)) }
        e.stop()
    }

    @Test fun muteDuringPttDoesNotDoubleTerminateOnUnmute() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        e.setPttHeld(true); repeat(2) { e.nextOutgoingFrame(0) }   // transmitting

        e.setMuted(true)
        val muteTerm = e.nextOutgoingFrame(0)
        assertTrue("mute emits one terminator", muteTerm != null && muteTerm.isTerminator)

        e.setPttHeld(false)                                        // released while muted
        e.setMuted(false)                                          // unmute; no live talkspurt was open
        assertNull("no spurious second terminator after unmute", e.nextOutgoingFrame(0))
        e.stop()
    }

    @Test fun switchingToPttMidTalkspurtClosesWithOneTerminator() {
        val e = engine()                       // starts VOICE_ACTIVATED; loud square wave opens gate
        val speech = e.nextOutgoingFrame(0)
        assertTrue("VA is transmitting speech", speech != null && !speech.isTerminator)

        e.setTransmitMode(TransmitMode.PUSH_TO_TALK)   // switch mid-talkspurt, button not held
        val closing = e.nextOutgoingFrame(0)
        assertTrue("mode switch closes the open talkspurt", closing != null && closing.isTerminator)
        assertNull("then PTT idle is silent", e.nextOutgoingFrame(0))
        e.stop()
    }

    @Test fun switchingModeWhileMutedEmitsNoSecondTerminator() {
        val e = engine()                                   // VA
        e.setMuted(true)
        val muteTerm = e.nextOutgoingFrame(0)
        assertTrue("mute emits one terminator", muteTerm != null && muteTerm.isTerminator)
        e.setTransmitMode(TransmitMode.PUSH_TO_TALK)        // switch while muted
        assertNull("no second terminator from a mode switch while muted", e.nextOutgoingFrame(0))
        e.stop()
    }

    @Test fun switchingPttToVaMidHoldClosesWithOneTerminator() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK); e.setPttHeld(true)
        repeat(2) { e.nextOutgoingFrame(0) }               // transmitting via PTT
        e.setTransmitMode(TransmitMode.VOICE_ACTIVATED)     // switch mid-hold
        val closing = e.nextOutgoingFrame(0)
        assertTrue("PTT→VA switch closes the open talkspurt", closing != null && closing.isTerminator)
        e.stop()
    }

    @Test fun frameNumberAdvancesAtWallClockDuringPttIdle() {
        val e = engine(); e.setTransmitMode(TransmitMode.PUSH_TO_TALK)
        repeat(5) { e.nextOutgoingFrame(0) }               // idle (not held): null, but clock advances
        e.setPttHeld(true)
        val f = e.nextOutgoingFrame(0)
        assertTrue("first held frame after idle exists", f != null)
        assertTrue("frameNumber advanced across the 5 idle captures",
            f!!.frameNumber >= 5L * FRAMES_PER_PACKET)
        e.stop()
    }
}
