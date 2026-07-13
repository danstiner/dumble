package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import me.danielstiner.dumble.mumble.proto.MumbleUdpProtos
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoSmokeTest {
    @Test fun versionRoundTrip() {
        val v = MumbleProtos.Version.newBuilder().setRelease("Drumble").build()
        assertEquals("Drumble", MumbleProtos.Version.parseFrom(v.toByteArray()).release)
    }

    @Test fun udpAudioRoundTrip() {
        val a = MumbleUdpProtos.Audio.newBuilder().setFrameNumber(42L).setTarget(31).build()
        val parsed = MumbleUdpProtos.Audio.parseFrom(a.toByteArray())
        assertEquals(42L, parsed.frameNumber)
        assertEquals(31, parsed.target)
    }
}
