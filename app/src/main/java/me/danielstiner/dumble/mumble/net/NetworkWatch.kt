package me.danielstiner.dumble.mumble.net

/**
 * Reports each change of the app's default network: a switch to another one, its loss, and its
 * return. The network we already have when registering is not a change.
 */
fun interface NetworkWatch {
    /** At most once. [onChanged] is called on the reporter's own thread, never the caller's. */
    fun start(onChanged: () -> Unit)
}
