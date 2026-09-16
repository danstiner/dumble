package me.danielstiner.dumble.mumble.net

/**
 * The app's default network and its changes. Networks are opaque identities: what the
 * connection asks is which one a link was dialed on, and whether that one is still up.
 */
interface NetworkWatch {
    /** The default network now, or null when there is none. */
    val current: Any?

    /** Whether [network] still exists. One the platform tore down does not; one it merely
     *  stopped preferring, an unvalidated WiFi with cellular behind it, does. */
    fun isUp(network: Any): Boolean

    /** Reports every arrival and loss of a default network, on the reporter's own thread.
     *  At most once. */
    fun start(onChanged: () -> Unit)
}

/** No network to speak of: nothing changes, and every network is up. */
object NoNetworkWatch : NetworkWatch {
    override val current: Any? = null
    override fun isUp(network: Any) = true
    override fun start(onChanged: () -> Unit) = Unit
}
