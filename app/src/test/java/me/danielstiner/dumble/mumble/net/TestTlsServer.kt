package me.danielstiner.dumble.mumble.net

import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * One-connection TLS server with a throwaway self-signed certificate whose subject alternative
 * name is `localhost`, so the client's host name verification passes. Exposes the certificate
 * digest so the client can pin it — no certificate authority is involved.
 */
class TestTlsServer : AutoCloseable {

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val cert = run {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=localhost"),
            BigInteger.valueOf(now),
            Date(now - 86_400_000),
            Date(now + 86_400_000),
            X500Name("CN=localhost"),
            keys.public,
        ).addExtension(
            Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, "localhost")),
        )
        JcaX509CertificateConverter()
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
    }

    val certSha256: String = sha256Hex(cert.encoded)

    /** The server's self-signed leaf certificate, for tests that need to assert an exact fingerprint. */
    val leafCertificate: X509Certificate get() = cert

    private val serverSocket: SSLServerSocket = run {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("k", keys.private, PASSWORD, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, PASSWORD) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    }

    val port: Int get() = serverSocket.localPort

    @Volatile private var accepted: Socket? = null
    private val ready = CountDownLatch(1)

    /**
     * Loops accept(): a trust test dials this server twice on the same port — once rejected
     * (first contact, no pin), once pinned after the client accepts the fingerprint — and both
     * attempts must land on the same certificate. A failed handshake (e.g. the client's trust
     * manager rejecting the cert) is swallowed by runCatching so the loop goes back to accept()
     * rather than exiting; [close] unblocks the pending accept() and ends the thread.
     */
    fun start() {
        thread(isDaemon = true) {
            while (!serverSocket.isClosed) {
                runCatching {
                    val s = serverSocket.accept() as SSLSocket
                    accepted = s
                    // JSSE only processes handshake records when something reads or writes on the
                    // socket. Without an explicit startHandshake() here, nothing ever drives the
                    // server's half of the handshake, and the client blocks in startHandshake()
                    // until its read times out.
                    s.startHandshake()
                    ready.countDown()
                }
            }
        }
    }

    /** Blocks until a client has connected, then writes one control frame. */
    fun writeFrame(type: Int, payload: ByteArray) {
        ready.await()
        val out = DataOutputStream(accepted!!.getOutputStream())
        out.writeShort(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    override fun close() {
        runCatching { accepted?.close() }
        runCatching { serverSocket.close() }
    }

    companion object {
        private val PASSWORD = "test".toCharArray()

        /** Rejects every certificate, forcing [MumbleTrustManager] past the authority path to
         *  [UntrustedCertificateException] — stands in for a client with no CA that trusts a
         *  self-signed server, i.e. genuine first contact. */
        fun rejectingTrustManager(): X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
                throw CertificateException("test: reject")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String): Unit =
                throw CertificateException("test: reject")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
