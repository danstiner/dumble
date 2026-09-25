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
     * [gen] is the connection attempt's generation. [onActive] reports the platform holding the
     * call — the phone, or another app's call, taking the audio — and resuming it.
     *
     * Calls here apply in send order on a single consumer — an [end] arriving before the call has
     * started is ordered, not lost — and return before their effects apply.
     */
    fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        onActive: (active: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    )

    /**
     * Ignored unless [gen] is the live call. Connecting tears the prior attempt down and starts the
     * next call in the same breath, so without the generation a superseded attempt's teardown would
     * end its successor.
     */
    fun end(gen: Int)

    /**
     * Route call audio to [routeId] — one of the ids last reported through [start]'s `onRoutes`.
     * Ignored unless [gen] is the live call, and dropped with a log if that endpoint has since gone
     * away. Fire-and-forget: the move comes back through `onRoutes` if the platform makes it, so
     * nothing here assumes it happened.
     */
    fun requestRoute(gen: Int, routeId: String)
}

/** The constructor default the tests use; every build uses [AndroidVoiceCall]. */
object NoVoiceCall : VoiceCall {
    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        onActive: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    ) = Unit
    override fun end(gen: Int) = Unit
    override fun requestRoute(gen: Int, routeId: String) = Unit
}
