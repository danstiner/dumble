package me.danielstiner.dumble.mumble.protocol

import me.danielstiner.dumble.mumble.proto.MumbleProtos
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerVersionTest {

    @Test fun decodesVersionV2() {
        val v = MumbleProtos.Version.newBuilder()
            .setVersionV2(MumbleVersion.encodeV2(1, 5, 735))
            .setRelease("Murmur").setOs("Linux").build()
        val sv = ServerVersion.from(v)
        assertEquals(1, sv.major); assertEquals(5, sv.minor); assertEquals(735, sv.patch)
        assertEquals("Murmur", sv.release); assertEquals("Linux", sv.os)
    }

    @Test fun fallsBackToVersionV1WhenV2Absent() {
        val v = MumbleProtos.Version.newBuilder()
            .setVersionV1(MumbleVersion.encodeV1(1, 4, 287)).build()  // 287 clamps to 255 (u8) upstream
        val sv = ServerVersion.from(v)
        assertEquals(1, sv.major); assertEquals(4, sv.minor); assertEquals(255, sv.patch)
    }
}
