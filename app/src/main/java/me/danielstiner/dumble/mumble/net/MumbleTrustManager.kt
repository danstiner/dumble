package me.danielstiner.dumble.mumble.net

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** How trust was established — the interface later distinguishes an authority-validated server from a pinned one. */
enum class TrustOutcome { CaValid, Pinned }

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The platform's own trust manager, backed by the operating system's certificate store. */
fun platformTrustManager(): X509TrustManager = trustManagerFor(null)

/**
 * A null [anchors] selects the platform store. Split out so tests can drive this exact construction
 * with their own certificate authority — the platform store can't be taught to trust a throwaway
 * root, so otherwise nothing would ever prove real chain validation works rather than a stub's.
 */
internal fun trustManagerFor(anchors: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(anchors) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

/**
 * Pin first, certificate authority second.
 *
 * A pinned endpoint is answered by its pin alone. Checking the authority path first would let any
 * certificate a trusted authority issued for this host silently override the user's explicit "this
 * exact server" — mis-issuance, a coerced authority, or an enterprise root on a managed device —
 * which is the case pinning exists to stop. The desktop client orders these the other way; we match
 * its behaviour elsewhere, not here.
 *
 * The cost is that a server which legitimately changes certificate stops connecting until its pin
 * is removed. That is the correct prompt: the certificate really did change, and re-accepting it is
 * the same confirmation the user gave the first time.
 *
 * [expectedPin] is loaded from [PinStore] *before* connecting: this callback runs inside the
 * handshake and must not block on storage.
 *
 * This validates the certificate *chain* only. Host name verification is a separate concern the
 * transport enables on the socket; a chain-valid certificate issued for another host would
 * otherwise pass here unnoticed.
 *
 * Create one instance per connection attempt and never share it across sockets: [outcome] is
 * per-handshake state, so a reused instance would race between concurrent connections.
 */
@Suppress("CustomX509TrustManager") // Lint flags every X509TrustManager on sight, regardless of what
// it does, so no amount of correctness clears it. Justified here because the alternative cannot
// express this problem: network security config pins are compiled into the package against host
// names known at build time, whereas Mumble servers are arbitrary user-typed endpoints whose
// self-signed certificates are learned on first contact. An unpinned endpoint still goes through the
// platform's own trust manager rather than any hand-rolled validation.
class MumbleTrustManager(
    private val expectedPin: String?,
    private val delegate: X509TrustManager = platformTrustManager(),
) : X509TrustManager {

    @Volatile var outcome: TrustOutcome? = null
        private set

    /** Only ever installed to verify servers we dial out to, so a call here means the type was
     *  misused. Fail loud rather than quietly accepting whoever asked. */
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit =
        throw CertificateException("client authentication is not supported")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // Guards the chain[0] read below: an empty chain would otherwise surface as an unchecked
        // index exception, which this method's contract does not allow it to throw.
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")

        // Pins the whole leaf rather than its public key: a stock Mumble certificate is self-signed
        // with no rotation path that outlives the certificate, so key-only pinning would buy no
        // resilience.
        val presented = sha256Hex(chain[0].encoded)

        // If a certificate was pinned for this server, only trust exactly that pin.
        if (expectedPin != null) {
            if (expectedPin != presented) throw PinMismatchException(expectedPin, presented)
            outcome = TrustOutcome.Pinned
            return
        }

        // Otherwise fallback to the default platform trust check.
        try {
            delegate.checkServerTrusted(chain, authType)
            outcome = TrustOutcome.CaValid
            return
        } catch (_: CertificateException) {
            // Expected for the self-signed servers most Mumble hosts run.
        }

        throw UntrustedCertificateException(presented)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
