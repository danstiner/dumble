package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MumbleEndpointTest {

    @Test fun defaultsPortAndKeysHostColonPort() {
        val e = MumbleEndpoint.parse("example.com")
        assertEquals("example.com", e.host)
        assertEquals(64738, e.port)
        assertEquals("example.com:64738", e.address)
    }

    @Test fun canonicalizesCaseTrailingDotAndAliasesToOnePin() {
        val a = MumbleEndpoint.parse("Voice.Example.com", 64738)
        val b = MumbleEndpoint.parse("voice.example.com.", 64738)
        assertEquals("voice.example.com", a.host)
        assertEquals(a.address, b.address)
    }

    @Test fun ipv6IsBracketedOnlyInPinKey() {
        val e = MumbleEndpoint.parse("::1", 64738)
        assertEquals("::1", e.host)
        assertEquals("[::1]:64738", e.address)
    }

    @Test fun toleratesPastedBracketedIpv6() {
        val e = MumbleEndpoint.parse("[::1]", 64738)
        assertEquals("::1", e.host)
        assertEquals("[::1]:64738", e.address)
    }

    @Test fun lowercasesIpv6Hex() {
        assertEquals("2001:db8::1", MumbleEndpoint.parse("2001:DB8::1").host)
    }

    @Test fun rejectsHostWithEmbeddedPort() {
        assertThrows(IllegalArgumentException::class.java) { MumbleEndpoint.parse("example.com:8080", 64738) }
    }

    @Test fun rejectsEmptyHost() {
        assertThrows(IllegalArgumentException::class.java) { MumbleEndpoint.parse("   ", null) }
    }

    @Test fun rejectsOutOfRangePort() {
        assertThrows(IllegalArgumentException::class.java) { MumbleEndpoint.parse("example.com", 70000) }
        assertThrows(IllegalArgumentException::class.java) { MumbleEndpoint.parse("example.com", 0) }
    }

    @Test fun punycodesUnicodeHost() {
        assertEquals("xn--caf-dma.example.com", MumbleEndpoint.parse("café.example.com").host)
    }
}
