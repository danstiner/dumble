package me.danielstiner.dumble.mumble.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

interface PinStore {
    fun get(key: String): String?
    fun put(key: String, fingerprint: String)
}

class InMemoryPinStore : PinStore {
    private val map = HashMap<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, fingerprint: String) { map[key] = fingerprint }
}

sealed class PinResult {
    object FirstUse : PinResult()
    object Match : PinResult()
    data class Mismatch(val stored: String, val presented: String) : PinResult()
}

class TofuVerifier(private val store: PinStore, private val key: String) {
    fun verify(encodedCert: ByteArray): PinResult {
        val fp = MessageDigest.getInstance("SHA-256").digest(encodedCert)
            .joinToString("") { "%02x".format(it) }
        return when (val stored = store.get(key)) {
            null -> { store.put(key, fp); PinResult.FirstUse }
            fp -> PinResult.Match
            else -> PinResult.Mismatch(stored, fp)
        }
    }
}

/** INSECURE-FOR-DEV: trust-on-first-use pinning, no CA validation. Must be replaced before real-world use. */
class TofuTrustManager(store: PinStore, key: String) : X509TrustManager {
    private val verifier = TofuVerifier(store, key)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
        throw CertificateException("client auth not supported")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val r = verifier.verify(chain[0].encoded)
        if (r is PinResult.Mismatch)
            throw CertificateException("TOFU pin mismatch: stored=${r.stored} presented=${r.presented}")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
