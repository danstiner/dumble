package me.danielstiner.dumble.mumble.connection

import me.danielstiner.dumble.mumble.net.NetworkWatch

/** A default network the test changes by hand. */
class FakeNetworkWatch : NetworkWatch {
    @Volatile private var onChanged: (() -> Unit)? = null
    override fun start(onChanged: () -> Unit) { this.onChanged = onChanged }
    fun change() = onChanged!!()
}
