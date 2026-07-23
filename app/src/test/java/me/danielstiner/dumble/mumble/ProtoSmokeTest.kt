package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoSmokeTest {
    @Test
    fun versionMessageRoundTrips() {
        val original = MumbleProtos.Version.newBuilder()
            .setRelease("dumble-test")
            .setOs("Android")
            .build()

        val parsed = MumbleProtos.Version.parseFrom(original.toByteArray())

        assertEquals("dumble-test", parsed.release)
        assertEquals("Android", parsed.os)
    }
}
