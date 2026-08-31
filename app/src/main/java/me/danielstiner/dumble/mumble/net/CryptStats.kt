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
     * Datagrams the replay window refused, before any decryption, because the counter they rebuilt
     * to was already consumed. Local only; the wire has no field for it.
     *
     * Not a count of deliberate replays, and cannot be made into one cheaply: the window answers
     * before the tag is checked, so this also catches random garbage whose low byte happens to
     * rebuild into a consumed slot -- around a quarter of it, since the window covers 64 of the 256
     * values that byte can take. Read it as "rejected by the window rather than by the tag", which
     * is what separates a desynchronised stream from a corrupted one.
     */
    val replay: Int,
)
