package me.danielstiner.dumble.ui.connect

import me.danielstiner.dumble.mumble.net.MumbleEndpoint

/**
 * Whether Android counts [rawHost] as on the local network, judged from its text alone: an address
 * in a range Android's local-network definition lists, or a name only a LAN resolves — a single
 * label, or one under .local (multicast DNS), .home.arpa and .internal (reserved for private
 * networks) or .lan (a common router default, with no public domain behind it). That definition
 * decides IPv6 by the device's routes, which text cannot see, so every non-global prefix counts:
 * erring wide costs one dialog, erring narrow a silent connect timeout. No DNS lookup, so any other
 * name that resolves to a local address reads false; the Settings switch covers those.
 */
internal fun isLocalNetworkHost(rawHost: String): Boolean {
    val host = runCatching { MumbleEndpoint.parse(rawHost).host }.getOrElse { return false }
    if (LOCAL_SUFFIXES.any(host::endsWith)) return true
    if (':' in host) {
        // Link-local fe80::/10, site-local fec0::/10 and unique-local fc00::/7 by the first group.
        val first = host.substringBefore(':').toIntOrNull(16) ?: return false
        return (first and 0xff80) == 0xfe80 || (first and 0xfe00) == 0xfc00
    }
    if ('.' !in host) return host != "localhost"
    val octets = host.split('.').map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return false }
    if (octets.size != 4) return false
    val (a, b) = octets
    return a == 10 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

private val LOCAL_SUFFIXES = listOf(".local", ".home.arpa", ".internal", ".lan")
