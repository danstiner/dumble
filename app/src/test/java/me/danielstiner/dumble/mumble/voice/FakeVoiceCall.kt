package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets a test drive a hold or a resume synchronously, without AndroidVoiceCall's main-looper hop.
 * Commands apply inside the call, in the order the real call's looper would apply them: a start
 * supersedes silently, and a superseded call's end does nothing.
 */
class FakeVoiceCall : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    /** Session id per start(), in order — so a test can address a superseded call by its id. */
    val startedIds = CopyOnWriteArrayList<Int>()
    var ends = 0; private set
    /** routeId per requestRoute(), in order — so a test can assert which route was asked for. */
    val routeRequests = CopyOnWriteArrayList<String>()

    private val lock = Any()
    private var liveId = NO_CALL
    /** Whether the platform currently holds an un-ended call — for a test to check that the
     *  app state's own idea of "connected" and the platform's idea of "a call exists" agree. */
    val hasLiveCall: Boolean get() = synchronized(lock) { liveId != NO_CALL }
    // Per call, not one field: a stale hold from a call that was replaced is exactly the
    // failure the capture lifecycle's liveness check exists to stop, and a single field cannot
    // express one.
    private val onHeld = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    // Per call for the same reason onHeld is: a route update from a superseded call is
    // exactly what the app state's liveness check exists to drop.
    private val onRoutes = ConcurrentHashMap<Int, (AudioRoutes) -> Unit>()

    override fun start(
        id: Int,
        endpoint: MumbleEndpoint,
        onHeld: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    ): () -> Unit {
        synchronized(lock) {
            starts += endpoint.host
            startedIds += id
            liveId = id
            this.onHeld[id] = onHeld
            this.onRoutes[id] = onRoutes
        }
        var called = false
        return {
            synchronized(lock) {
                check(!called) { "end of call $id called twice" }
                called = true
                if (liveId == id) {
                    liveId = NO_CALL
                    ends++
                }
            }
        }
    }

    // Dropped with no live call, as the real one does. Deliberately does not echo the route back:
    // the platform confirms through onRoutes, so a test drives that itself via emitRoutes().
    override fun requestRoute(routeId: String): Unit = synchronized(lock) {
        if (liveId != NO_CALL) routeRequests += routeId
    }

    /** Deliver a route update to a specific call, live or not — same reason as [holdFor]. */
    fun emitRoutesFor(id: Int, routes: AudioRoutes) { onRoutes[id]?.invoke(routes) }

    fun emitRoutes(routes: AudioRoutes) = emitRoutesFor(liveId, routes)

    // hold() and resume() no-op once end() has run: liveId is NO_CALL then, matching a platform
    // call that has ended.
    fun hold() = holdFor(liveId)

    fun resume() = resumeFor(liveId)

    /**
     * Deliver a hold to a specific call, live or not — the platform does not fence a callback
     * already in flight, so a superseded call can still call back.
     */
    fun holdFor(id: Int) { onHeld[id]?.invoke(true) }

    fun resumeFor(id: Int) { onHeld[id]?.invoke(false) }

    private companion object {
        const val NO_CALL = -1
    }
}
