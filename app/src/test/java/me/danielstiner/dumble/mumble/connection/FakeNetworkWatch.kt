package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.net.NetworkWatch
import java.util.concurrent.ConcurrentHashMap

/** Networks the test brings up, prefers, and takes down by hand. */
class FakeNetworkWatch(default: Any? = "wifi") : NetworkWatch {
    @Volatile override var current: Any? = default
    private val up = ConcurrentHashMap.newKeySet<Any>().apply { default?.let { add(it) } }
    @Volatile private var onChanged: (() -> Unit)? = null

    override fun isUp(network: Any) = network in up
    override fun start(onChanged: () -> Unit) { this.onChanged = onChanged }

    fun change() = onChanged!!()

    /** The default moves to [network] with the old one still up: a preference, not a loss. */
    fun switchTo(network: Any) { up += network; current = network; change() }

    /** [network] is gone; the default is null until something else arrives. */
    fun lose(network: Any) { up -= network; if (current == network) current = null; change() }
}
