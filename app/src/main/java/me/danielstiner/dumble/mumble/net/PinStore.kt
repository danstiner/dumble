package me.danielstiner.dumble.mumble.net

import java.security.cert.CertificateException

/**
 * User-accepted server certificate pins. The key is the connection target as the user specified it,
 * `host:port` from [MumbleEndpoint.address] — nothing is read off the certificate itself. The value is [sha256Hex]
 * of the leaf certificate.
 */
interface PinStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, fingerprint: String)
    suspend fun remove(key: String)
}

class InMemoryPinStore : PinStore {
    private val map = HashMap<String, String>()
    @Synchronized private fun read(k: String) = map[k]
    @Synchronized private fun write(k: String, v: String) { map[k] = v }
    @Synchronized private fun erase(k: String) { map.remove(k) }
    override suspend fun get(key: String): String? = read(key)
    override suspend fun put(key: String, fingerprint: String) = write(key, fingerprint)
    override suspend fun remove(key: String) = erase(key)
}

/** No pin on record for this endpoint. Carries the fingerprint so the interface can offer to accept it. */
class UntrustedCertificateException(val fingerprint: String) :
    CertificateException("untrusted certificate, sha256=$fingerprint")

/** A pin exists and the presented certificate does not match it. Never recoverable in place. */
class PinMismatchException(val stored: String, val presented: String) :
    CertificateException("pin mismatch: stored=$stored presented=$presented")
