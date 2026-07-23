package me.danielstiner.dumble.mumble.net

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager

class MumbleTrustManagerTest {

    /** Minimal self-signed certificate; the stub delegate below decides trust, so contents barely matter. */
    private fun selfSigned(commonName: String): X509Certificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$commonName"),
            BigInteger.valueOf(now),
            Date(now - 86_400_000),
            Date(now + 86_400_000),
            X500Name("CN=$commonName"),
            keys.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun delegate(accepts: Boolean) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (!accepts) throw CertificateException("delegate rejects")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun authorityValidChainIsAcceptedWhenNothingIsPinned() {
        val cert = selfSigned("authority-valid")
        val tm = MumbleTrustManager(expectedPin = null, delegate = delegate(accepts = true))

        tm.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(TrustOutcome.CaValid, tm.outcome)
    }

    @Test
    fun pinnedCertPassesWhenDelegateRejects() {
        val cert = selfSigned("pinned")
        val tm = MumbleTrustManager(expectedPin = sha256Hex(cert.encoded), delegate = delegate(accepts = false))

        tm.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(TrustOutcome.Pinned, tm.outcome)
    }

    // The inverse of what this test used to assert. A pin must outrank the authority path: otherwise
    // any certificate a trusted authority issued for this host — mis-issuance, an enterprise root on
    // a managed device — silently overrides the exact server the user accepted. The cost is that a
    // legitimate certificate change now needs the pin removed, which is the honest prompt.
    @Test
    fun aStoredPinOutranksAnAuthorityValidChain() {
        val cert = selfSigned("authority-valid-but-not-the-pinned-one")
        val stored = "00".repeat(32)
        val tm = MumbleTrustManager(expectedPin = stored, delegate = delegate(accepts = true))

        val thrown = assertThrows(PinMismatchException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }

        assertEquals(stored, thrown.stored)
        assertEquals(sha256Hex(cert.encoded), thrown.presented)
        assertNull("a rejected certificate must not record an outcome", tm.outcome)
    }

    @Test
    fun aMatchingPinSkipsTheAuthorityPathEntirely() {
        val cert = selfSigned("pinned-and-authority-valid")
        var delegateConsulted = false
        val watchful = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                delegateConsulted = true
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tm = MumbleTrustManager(expectedPin = sha256Hex(cert.encoded), delegate = watchful)

        tm.checkServerTrusted(arrayOf(cert), "RSA")

        assertEquals(TrustOutcome.Pinned, tm.outcome)
        assertFalse("a pinned endpoint must not need the authority path", delegateConsulted)
    }

    @Test
    fun unknownCertThrowsCarryingItsFingerprint() {
        val cert = selfSigned("unknown")
        val tm = MumbleTrustManager(expectedPin = null, delegate = delegate(accepts = false))

        val thrown = assertThrows(UntrustedCertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
        assertEquals(sha256Hex(cert.encoded), thrown.fingerprint)
    }

    @Test
    fun mismatchedPinThrowsWithBothValues() {
        val cert = selfSigned("mismatch")
        val stored = "00".repeat(32)
        val tm = MumbleTrustManager(expectedPin = stored, delegate = delegate(accepts = false))

        val thrown = assertThrows(PinMismatchException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
        assertEquals(stored, thrown.stored)
        assertEquals(sha256Hex(cert.encoded), thrown.presented)
    }

    @Test
    fun emptyChainIsRejected() {
        val tm = MumbleTrustManager(expectedPin = null, delegate = delegate(accepts = true))

        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun clientAuthenticationIsNotSupported() {
        val tm = MumbleTrustManager(expectedPin = null, delegate = delegate(accepts = true))

        assertThrows(CertificateException::class.java) {
            tm.checkClientTrusted(arrayOf(selfSigned("client")), "RSA")
        }
    }

    // Every other test here hands MumbleTrustManager a stub delegate, so none of them would notice
    // if real certificate authority validation were broken or removed — the self-signed fixtures
    // all take the pinned path. This drives a genuine JSSE validator over a real chain instead.
    @Test
    fun realValidatorAcceptsAProperlyChainedCertificateWithNoPinStored() {
        val root = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rootCert = certificate("test-root", root, "test-root", root, isAuthority = true)
        val leaf = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val leafCert = certificate("leaf", leaf, "test-root", root, isAuthority = false)

        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("root", rootCert)
        }
        val tm = MumbleTrustManager(expectedPin = null, delegate = trustManagerFor(anchors))

        // expectedPin is null, so anything short of real validation throws UntrustedCertificate.
        tm.checkServerTrusted(arrayOf(leafCert, rootCert), "RSA")

        assertEquals(TrustOutcome.CaValid, tm.outcome)
    }

    // The chain test above builds its own anchors, so it cannot catch platformTrustManager()
    // returning something degenerate. The platform store can't be taught a throwaway root, so this
    // asserts the one property that is checkable: it is wired to real trust anchors.
    @Test
    fun platformTrustManagerIsBackedByRealTrustAnchors() {
        assertTrue(
            "platform trust manager exposed no issuers, so it can validate nothing",
            platformTrustManager().acceptedIssuers.isNotEmpty(),
        )
    }

    private fun certificate(
        subject: String,
        subjectKeys: java.security.KeyPair,
        issuer: String,
        issuerKeys: java.security.KeyPair,
        isAuthority: Boolean,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"),
            BigInteger.valueOf(now + if (isAuthority) 0 else 1),
            Date(now - 86_400_000),
            Date(now + 86_400_000),
            X500Name("CN=$subject"),
            subjectKeys.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(isAuthority))
        if (isAuthority) {
            builder.addExtension(
                Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign),
            )
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
