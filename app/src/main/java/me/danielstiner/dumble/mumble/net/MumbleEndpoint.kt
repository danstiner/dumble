package me.danielstiner.dumble.mumble.net

import java.net.IDN

/**
 * A connection target after canonicalization. [host] is what the socket and host-name verifier get;
 * [address] is the canonical `host:port` the rest of the app identifies a server by — the key
 * [PinStore] uses and the authority of the call's `mumble://` URL. Derived together with [host] so
 * they can never disagree.
 */
class MumbleEndpoint private constructor(val host: String, val port: Int) {
    // host is IPv6 iff it holds >=2 colons (a lone colon is a mistyped host:port, rejected in parse),
    // so bracketing here is unambiguous and "[::1]:64738" cannot collide with a distinct endpoint.
    val address: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    companion object {
        const val DEFAULT_PORT = 64738

        fun parse(rawHost: String, rawPort: Int? = null): MumbleEndpoint {
            val port = rawPort ?: DEFAULT_PORT
            require(port in 1..65535) { "port out of range: $port" }
            var host = rawHost.trim().removeSuffix(".").removePrefix("[").removeSuffix("]")
            require(host.isNotEmpty()) { "host is empty" }
            val colons = host.count { it == ':' }
            require(colons == 0 || colons >= 2) { "host looks like it includes a port; use the port field" }
            // Punycode Unicode hostnames; leave IPv6 literals alone. Locale-safe lowercase either way.
            host = if (colons == 0) IDN.toASCII(host).lowercase() else host.lowercase()
            return MumbleEndpoint(host, port)
        }
    }
}
