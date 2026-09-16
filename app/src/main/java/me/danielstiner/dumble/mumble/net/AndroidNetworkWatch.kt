package me.danielstiner.dumble.mumble.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * The app's default network as ConnectivityManager reports it, which is per uid: during a call
 * Telecom asks for a cellular slice on our behalf, and what this tracks is what our sockets get.
 */
class AndroidNetworkWatch(context: Context) : NetworkWatch {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override val current: Any? get() = connectivity.activeNetwork

    // Null capabilities is a network the platform no longer has; a lingering or unvalidated one
    // still reports them.
    override fun isUp(network: Any) = connectivity.getNetworkCapabilities(network as Network) != null

    override fun start(onChanged: () -> Unit) {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChanged()
            override fun onLost(network: Network) = onChanged()
        })
    }
}
