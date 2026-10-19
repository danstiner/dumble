package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets a test drive a hold or a resume synchronously, without AndroidVoiceCall's main-looper hop.
 * Commands apply inside the call, in the order the real call's looper would apply them: a start
 * supersedes silently, and an end with no live call throws, since the caller contract rules it out.
 */
class FakeVoiceCall : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    /** Generation per start(), in order — so a test can address a superseded call by generation. */
    val startedGens = CopyOnWriteArrayList<Int>()
    var ends = 0; private set
    /** routeId per requestRoute(), in order — so a test can assert which route was asked for. */
    val routeRequests = CopyOnWriteArrayList<String>()

    private val lock = Any()
    private var liveGen = NO_CALL
    /** Whether the platform currently holds an un-ended call — for a test to check that the
     *  connection's own idea of "connected" and the platform's idea of "a call exists" agree. */
    val hasLiveCall: Boolean get() = synchronized(lock) { liveGen != NO_CALL }
    // Per generation, not one field: a stale hold from a superseded call is exactly the failure the
    // connection's generation check exists to stop, and a single field cannot express one.
    private val onActive = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    // Per generation for the same reason onActive is: a route update from a superseded call is
    // exactly what the connection's generation guard exists to drop.
    private val onRoutes = ConcurrentHashMap<Int, (AudioRoutes) -> Unit>()

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        onActive: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    ) {
        synchronized(lock) {
            starts += endpoint.host
            startedGens += gen
            liveGen = gen
            this.onActive[gen] = onActive
            this.onRoutes[gen] = onRoutes
        }
    }

    override fun end(): Unit = synchronized(lock) {
        check(liveGen != NO_CALL) { "end() with no call to end" }
        liveGen = NO_CALL
        ends++
    }

    override fun requestRoute(gen: Int, routeId: String): Unit = synchronized(lock) {
        // Mirrors the real generation guard. Deliberately does not echo the route back: the
        // platform confirms through onRoutes, so a test drives that itself via emitRoutes().
        if (gen != liveGen) return
        routeRequests += routeId
        Unit
    }

    /** Deliver a route update to a specific generation, live or not — same reason as [holdFor]. */
    fun emitRoutesFor(gen: Int, routes: AudioRoutes) { onRoutes[gen]?.invoke(routes) }

    fun emitRoutes(routes: AudioRoutes) = emitRoutesFor(liveGen, routes)

    // hold() and resume() no-op once end() has run: liveGen is NO_CALL then, matching a platform
    // call that has ended.
    fun hold() = holdFor(liveGen)

    fun resume() = resumeFor(liveGen)

    /**
     * Deliver a hold to a specific generation, live or not — the platform does not fence a callback
     * already in flight, so a superseded call can still call back.
     */
    fun holdFor(gen: Int) { onActive[gen]?.invoke(false) }

    fun resumeFor(gen: Int) { onActive[gen]?.invoke(true) }

    private companion object {
        const val NO_CALL = -1
    }
}
