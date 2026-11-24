package me.danielstiner.dumble.mumble.net

/** What [CryptState] has seen since the last [CryptState.setKeys], reported on the periodic ping. */
data class CryptStats(
    /** Packets accepted. */
    val good: Int,
    /** Accepted packets that arrived behind the highest counter seen. */
    val late: Int,
    /** Packets that still appear to be missing; a late arrival takes one back off. */
    val lost: Int,
    /** Times the peer has handed us its counter, whether or not it moved us. */
    val resync: Int,
    /**
     * Datagrams [CryptState.decrypt] refused, for any reason the wire could cause: a runt, a tag
     * that did not match, a counter already accepted, or a packet that beat the key exchange.
     * Local only; the wire has no field for it.
     */
    val rejected: Int,
)
