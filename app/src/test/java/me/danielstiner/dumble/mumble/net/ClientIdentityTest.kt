package me.danielstiner.dumble.mumble.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.time.ZoneOffset

class ClientIdentityTest {

    // RSA-3072 generation takes up to a few seconds; one identity serves every test that does
    // not need a second.
    private val identity by lazy { SHARED }

    @Test fun generatesASelfSignedRsa3072CertificateShapedLikeTheDesktops() {
        val cert = identity.certificate
        cert.verify(cert.publicKey)
        assertEquals(cert.issuerX500Principal, cert.subjectX500Principal)
        assertEquals("CN=Dumble User", cert.subjectX500Principal.name)
        assertEquals("RSA", cert.publicKey.algorithm)
        assertEquals(3072, (cert.publicKey as RSAPublicKey).modulus.bitLength())
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals(3, cert.version)
        assertEquals("CA:FALSE reads as -1", -1, cert.basicConstraints)
        assertTrue("basicConstraints must be critical", cert.criticalExtensionOIDs.contains("2.5.29.19"))
        assertEquals(listOf("1.3.6.1.5.5.7.3.2"), cert.extendedKeyUsage)
        assertNotNull("subject key identifier", cert.getExtensionValue("2.5.29.14"))
        val notBefore = cert.notBefore.toInstant().atZone(ZoneOffset.UTC)
        assertEquals(notBefore.plusYears(20).toInstant(), cert.notAfter.toInstant())
        assertTrue(cert.serialNumber.signum() > 0)
    }

    @Test fun hashIsTheSha1OfTheDer() {
        assertEquals(sha1Hex(identity.certificate.encoded), identity.hash)
        assertTrue(identity.hash, Regex("[0-9a-f]{40}").matches(identity.hash))
    }

    @Test fun roundTripsThroughPkcs12WithAnEmptyPassword() {
        val back = ClientIdentity.decode(identity.encode())
        assertArrayEquals(identity.certificate.encoded, back.certificate.encoded)
        assertEquals(identity.hash, back.hash)
        val payload = byteArrayOf(1, 2, 3)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(back.keyManager().getPrivateKey(ClientIdentity.ALIAS))
            update(payload)
        }.sign()
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(identity.certificate)
            update(payload)
        }
        assertTrue("the decoded key must be the certificate's", verifier.verify(signature))
    }

    @Test fun aCorruptedFileIsRefused() {
        val bytes = identity.encode()
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() }
        assertThrows(Exception::class.java) { ClientIdentity.decode(tampered) }
    }

    @Test fun keyManagerOffersTheOneCertificateToAnyRequestThatTakesRsa() {
        val km = identity.keyManager()
        assertEquals(ClientIdentity.ALIAS, km.chooseClientAlias(arrayOf("RSA"), null, null))
        assertEquals(ClientIdentity.ALIAS, km.chooseClientAlias(arrayOf("EC", "RSA"), null, null))
        assertEquals(ClientIdentity.ALIAS, km.chooseClientAlias(null, null, null))
        assertEquals(ClientIdentity.ALIAS, km.chooseClientAlias(emptyArray(), null, null))
        assertNull("an ECDSA-only request gets no certificate", km.chooseClientAlias(arrayOf("EC"), null, null))
        assertArrayEquals(arrayOf(ClientIdentity.ALIAS), km.getClientAliases("RSA", null))
        assertArrayEquals(arrayOf(ClientIdentity.ALIAS), km.getClientAliases(null, null))
        assertNull(km.getClientAliases("EC", null))
        assertSame(identity.certificate, km.getCertificateChain(ClientIdentity.ALIAS).single())
        assertNotNull(km.getPrivateKey(ClientIdentity.ALIAS))
        assertNull(km.getCertificateChain("other"))
        assertNull(km.getPrivateKey("other"))
        assertNull(km.chooseServerAlias("RSA", null, null))
    }

    @Test fun eachGenerationIsANewIdentity() {
        assertNotEquals(identity.hash, ClientIdentity.generate().hash)
    }

    private companion object {
        val SHARED: ClientIdentity by lazy { ClientIdentity.generate() }
    }
}
