package me.danielstiner.dumble.mumble.voice

/** Lets a test hand the connection a focus loss or gain without a device. */
class FakeAudioFocus : AudioFocus {
    private var listener: ((AudioFocus.Change) -> Unit)? = null
    var requests = 0; private set
    var abandons = 0; private set
    val held: Boolean get() = listener != null

    override fun request(onChange: (AudioFocus.Change) -> Unit): Boolean {
        requests++
        listener = onChange
        return true
    }

    override fun abandon() {
        abandons++
        listener = null
    }

    /** No-op once abandoned, matching a platform request that is no longer registered. */
    fun deliver(change: AudioFocus.Change) {
        listener?.invoke(change)
    }
}
