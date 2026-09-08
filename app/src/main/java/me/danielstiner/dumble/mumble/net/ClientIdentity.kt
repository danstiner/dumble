package me.danielstiner.dumble.mumble.net

import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.operator.bc.BcDefaultDigestProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilderProvider
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import javax.net.ssl.X509KeyManager

/** SHA-1 of [bytes] as lowercase hex: the digest Murmur keys a session's certificate by. */
fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * The certificate we present on every TLS handshake, and its key. Self-signed and shaped like
 * desktop Mumble's own (`SelfSignedCertificate::generate`) — RSA because the desktop's PKCS#12
 * import assumes it, so an identity exported from here can be imported there; 3072 bits rather than
 * the desktop's 2048 because the certificate is meant to outlast 2030, where NIST SP 800-57 stops
 * accepting 2048.
 *
 * Murmur keys "same client" on the SHA-1 of this certificate; see docs/connection.md, Client
 * certificate.
 */
class ClientIdentity(val certificate: X509Certificate, private val key: PrivateKey) {

    /** What Murmur records for the session. */
    val hash: String = sha1Hex(certificate.encoded)

    /** Offers [certificate] to any client-authentication request; there is one to offer. */
    fun keyManager(): X509KeyManager = object : X509KeyManager {
        override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = null
    }

    /**
     * PKCS#12, the format desktop Mumble's certificate wizard imports and exports, with plain key
     * and certificate bags and a SHA-256 MAC under an empty password. Plain rather than
     * password-encrypted: the password is empty by design (desktop Mumble's import tries none
     * first), so encryption would protect nothing, and the empty password is refused by Android's
     * PBKDF2 provider anyway. With no password the MAC is a damage check, not a secret. The
     * platform's own PKCS#12 store is not used because it writes 40-bit RC2, which OpenSSL 3 only
     * reads with its legacy provider.
     */
    fun encode(): ByteArray {
        val keyId = JcaX509ExtensionUtils().createSubjectKeyIdentifier(certificate.publicKey)
        val certBag = JcaPKCS12SafeBagBuilder(certificate)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, DERBMPString(ALIAS))
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, keyId)
            .build()
        val keyBag = JcaPKCS12SafeBagBuilder(key)
            .addBagAttribute(PKCS12SafeBag.friendlyNameAttribute, DERBMPString(ALIAS))
            .addBagAttribute(PKCS12SafeBag.localKeyIdAttribute, keyId)
            .build()
        // The bare AlgorithmIdentifier(id_sha256) (no parameters) fails BcPKCS12MacCalculatorBuilder's
        // own MAC verification against BcDefaultDigestProvider; the explicit DERNull parameter matches
        // what that provider expects and still parses in OpenSSL either way.
        val mac = BcPKCS12MacCalculatorBuilder(
            SHA256Digest(),
            AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256, DERNull.INSTANCE),
        )
        return PKCS12PfxPduBuilder().addData(certBag).addData(keyBag).build(mac, NO_PASSWORD).encoded
    }

    companion object {
        /** The bags' friendly name and the key manager's alias. */
        const val ALIAS = "dumble"
        private val NO_PASSWORD = CharArray(0)
        private const val KEY_BITS = 3072
        private const val VALID_YEARS = 20L
        private val NAME = X500Name("CN=Dumble User")

        fun generate(): ClientIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            val notBefore = Instant.now()
            val notAfter = notBefore.atZone(ZoneOffset.UTC).plusYears(VALID_YEARS).toInstant()
            val builder = JcaX509v3CertificateBuilder(
                NAME,
                BigInteger(63, SecureRandom()).setBit(0),   // positive and never zero
                Date.from(notBefore),
                Date.from(notAfter),
                NAME,
                keys.public,
            )
                .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
                .addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
                .addExtension(
                    Extension.subjectKeyIdentifier, false,
                    JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.public),
                )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keys.private)
            return ClientIdentity(JcaX509CertificateConverter().getCertificate(builder.build(signer)), keys.private)
        }

        /** The inverse of [encode]. Throws on a bad MAC, a missing bag, or anything unparsable. */
        fun decode(bytes: ByteArray): ClientIdentity {
            val pfx = PKCS12PfxPdu(bytes)
            require(pfx.isMacValid(BcPKCS12MacCalculatorBuilderProvider(BcDefaultDigestProvider.INSTANCE), NO_PASSWORD)) {
                "PKCS#12 integrity check failed"
            }
            var certificate: X509Certificate? = null
            var key: PrivateKey? = null
            for (info in pfx.contentInfos) {
                for (bag in PKCS12SafeBagFactory(info).safeBags) {
                    when (val value = bag.bagValue) {
                        is X509CertificateHolder -> certificate = JcaX509CertificateConverter().getCertificate(value)
                        is PrivateKeyInfo ->
                            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(value.encoded))
                    }
                }
            }
            return ClientIdentity(
                requireNotNull(certificate) { "no certificate in the PKCS#12" },
                requireNotNull(key) { "no private key in the PKCS#12" },
            )
        }
    }
}
