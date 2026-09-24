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
     * [gen] is the connection attempt's generation. [onActive] reports the system resuming the
     * call and holding it (a cellular call arriving); [onEnded] reports the system ending it.
     *
     * Calls here apply in send order on a single consumer — an [end] arriving before the platform
     * has granted the call is ordered, not lost — and return before their effects apply.
     */
    fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (active: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
        onEnded: () -> Unit,
    )

    /**
     * Ignored unless [gen] is the live call. Connecting tears the prior attempt down and starts the
     * next call in the same breath, so without the generation a superseded attempt's teardown would
     * end its successor.
     */
    fun end(gen: Int, reason: Reason = Reason.USER)

    /**
     * Re-check whether [gen]'s call is held, resuming it if not. The platform reports a hold ending
     * on its own; this is the net behind a Talk press or the held banner. Ignored unless [gen] is
     * the live call; a resume reaches the connection through [start]'s `onActive`, as any other does.
     */
    fun requestActive(gen: Int)

    /**
     * Route call audio to [routeId] — one of the ids last reported through [start]'s `onRoutes`.
     * Ignored unless [gen] is the live call, and dropped with a log if that endpoint has since gone
     * away. Fire-and-forget: the move comes back through `onRoutes` if the platform makes it, so
     * nothing here assumes it happened.
     */
    fun requestRoute(gen: Int, routeId: String)

    /** Why the call ended. The platform records a different disconnect cause for each. */
    enum class Reason { USER, SESSION_FAILED }
}

/** For the connection tests, and any build with no platform to register a call with. */
object NoVoiceCall : VoiceCall {
    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
        onEnded: () -> Unit,
    ) = Unit
    override fun end(gen: Int, reason: VoiceCall.Reason) = Unit
    override fun requestActive(gen: Int) = Unit
    override fun requestRoute(gen: Int, routeId: String) = Unit
}
