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

    override fun start(onChanged: () -> Unit) {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // Callbacks arrive on one thread, in order.
            private var current: Network? = null
            private var reported = false

            override fun onAvailable(network: Network) {
                if (network == current) return
                current = network
                // The first report is the network we already have, not a change.
                if (reported) onChanged() else reported = true
            }

            // A handover can report the old network's loss after the new one's arrival; only
            // the network our sockets are on counts as lost.
            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                onChanged()
            }
        })
    }
}
