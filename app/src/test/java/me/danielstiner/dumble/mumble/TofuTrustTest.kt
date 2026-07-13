package me.danielstiner.dumble.mumble

import me.danielstiner.dumble.mumble.net.*
import org.junit.Assert.*
import org.junit.Test

class TofuTrustTest {
    @Test fun firstUseStoresThenMatches() {
        val store = InMemoryPinStore()
        val v = TofuVerifier(store, "h:64738")
        val cert = ByteArray(64) { it.toByte() }
        assertTrue(v.verify(cert) is PinResult.FirstUse)
        assertTrue(v.verify(cert) is PinResult.Match)
    }

    @Test fun mismatchDetected() {
        val store = InMemoryPinStore()
        val v = TofuVerifier(store, "h:64738")
        v.verify(ByteArray(64) { it.toByte() })
        val r = v.verify(ByteArray(64) { (it + 1).toByte() })
        assertTrue(r is PinResult.Mismatch)
    }

    @Test fun distinctKeysIndependent() {
        val store = InMemoryPinStore()
        TofuVerifier(store, "a:1").verify(ByteArray(4) { 1 })
        assertTrue(TofuVerifier(store, "b:2").verify(ByteArray(4) { 2 }) is PinResult.FirstUse)
    }
}
