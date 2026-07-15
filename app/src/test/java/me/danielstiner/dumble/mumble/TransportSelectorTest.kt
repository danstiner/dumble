package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.TransportSelector
import me.danielstiner.dumble.mumble.net.VoiceTransportMode
import org.junit.Assert.*
import org.junit.Test

class TransportSelectorTest {
    private fun stats(good: Int, remoteGood: Int) =
        CryptState.Stats(good, 0, 0, 0, remoteGood, 0, 0, 0)

    @Test fun startsOptimisticUdp() {
        assertEquals(VoiceTransportMode.UDP, TransportSelector(forceTcp = false).mode)
    }

    @Test fun forceTcpPinsTunnel() {
        val s = TransportSelector(forceTcp = true)
        s.evaluate(stats(100, 100), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
    }

    @Test fun stallTwoTicksFallsBackAndRecovers() {
        val s = TransportSelector(forceTcp = false)
        s.evaluate(stats(10, 10), sendingVoice = true)
        s.evaluate(stats(20, 20), sendingVoice = true)
        assertEquals(VoiceTransportMode.UDP, s.mode)
        s.evaluate(stats(20, 20), sendingVoice = true)
        assertEquals(VoiceTransportMode.UDP, s.mode)
        s.evaluate(stats(20, 20), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
        s.evaluate(stats(25, 24), sendingVoice = true)
        assertEquals(VoiceTransportMode.UDP, s.mode)
    }

    @Test fun tunnelRequiresBothDeltasToRecover() {
        val s = TransportSelector(forceTcp = false)
        // Drive into the tunnel: two stalled ticks while sending.
        s.evaluate(stats(10, 10), sendingVoice = true)
        s.evaluate(stats(10, 10), sendingVoice = true)
        s.evaluate(stats(10, 10), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
        // Only downlink flows (good advances, remoteGood frozen) -> no recovery.
        s.evaluate(stats(15, 10), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
        // Only uplink flows (remoteGood advances, good frozen) -> no recovery.
        s.evaluate(stats(15, 18), sendingVoice = true)
        assertEquals(VoiceTransportMode.TCP_TUNNEL, s.mode)
        // Both flow (bidirectional UDP restored, as UDP pings would produce) -> recover.
        s.evaluate(stats(20, 23), sendingVoice = true)
        assertEquals(VoiceTransportMode.UDP, s.mode)
    }

    @Test fun noStallDetectionWhenNotSending() {
        val s = TransportSelector(forceTcp = false)
        repeat(5) { s.evaluate(stats(0, 0), sendingVoice = false) }
        assertEquals(VoiceTransportMode.UDP, s.mode)
    }

    @Test fun statsPublished() {
        val s = TransportSelector(forceTcp = false)
        s.onTcpRtt(12.5); s.onUdpPong(4.0)
        s.evaluate(stats(3, 2), sendingVoice = true)
        val ns = s.stats.value
        assertEquals(12.5, ns.tcpRttMs, 0.01)
        assertEquals(4.0, ns.udpRttMs, 0.01)
        assertEquals(3, ns.good); assertEquals(2, ns.remoteGood)
    }
}
