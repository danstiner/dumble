package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint

/**
 * The platform's notion of the call a connection represents: the audio mode, the route, the
 * microphone service, and what holds the session. The implementation owns all of them — nothing
 * else touches the mode or the route.
 *
 * A seam, so the JVM tests can drive hold and resume without a device.
 */
interface VoiceCall {
    /**
     * [gen] names the connection attempt in the call's logs. [onHeld] reports the platform holding the
     * call — the phone, or another app's call, taking the audio — and resuming it.
     *
     * Calls here apply in send order on a single consumer, and return before their effects apply.
     * A start while a call is live supersedes it, keeping the mode and route it took. Callers send
     * starts and ends in generation order; nothing here checks.
     */
    fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        onHeld: (held: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    )

    /** Ends the live call; sent only while there is one. A call being replaced gets no end. */
    fun end()

    /**
     * Route call audio to [routeId] — one of the ids last reported through [start]'s `onRoutes`.
     * Ignored with no call live, and dropped with a log if that endpoint has since gone away. A
     * pick racing a supersede lands on the successor, which keeps the route anyway.
     * Fire-and-forget: the move comes back through `onRoutes` if the platform makes it, so nothing
     * here assumes it happened.
     */
    fun requestRoute(routeId: String)
}

/** The constructor default the tests use; every build uses [AndroidVoiceCall]. */
object NoVoiceCall : VoiceCall {
    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        onHeld: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    ) = Unit
    override fun end() = Unit
    override fun requestRoute(routeId: String) = Unit
}
