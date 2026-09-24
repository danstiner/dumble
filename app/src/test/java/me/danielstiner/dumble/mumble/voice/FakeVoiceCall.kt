package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets a test drive a hold or a resume synchronously, without AndroidVoiceCall's main-looper hop.
 *
 * The grant is its own event because the real call applies start() later, on the main looper,
 * after start() returns; a fake that superseded inside start() could not express the defects that
 * live in that window.
 *
 * [autoGrant] only moves *when* the grant fires: the default grants immediately, so observable
 * counts at assertion time are unchanged.
 */
class FakeVoiceCall(
    private val autoGrant: Boolean = true,
    private val holdInsideStart: Boolean = false,
) : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    /** Generation per start(), in order — so a test can address a superseded call by generation. */
    val startedGens = CopyOnWriteArrayList<Int>()
    var ends = 0; private set
    /** routeId per requestRoute(), in order — so a test can assert which route was asked for. */
    val routeRequests = CopyOnWriteArrayList<String>()

    private val lock = Any()
    private var liveGen = NO_CALL
    private var pendingGen = NO_CALL
    /** An end() that arrived before the grant; applied when it lands, never dropped. */
    private var pendingEnd = false
    /** Whether the platform currently holds a granted, un-ended call — for a test to check that
     *  the connection's own idea of "connected" and the platform's idea of "a call exists" agree. */
    val hasLiveCall: Boolean get() = synchronized(lock) { liveGen != NO_CALL }
    /** Whether an end() is parked awaiting the still-outstanding start's grant — for a test to
     *  observe that a queued end actually arrived, rather than racing a fixed delay against it. */
    val hasPendingEnd: Boolean get() = synchronized(lock) { pendingEnd }
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
            // A pending start's queued end is recorded before the new start supersedes it,
            // mirroring the real consumer's ordered queue.
            if (pendingGen != NO_CALL && pendingEnd) ends++
            starts += endpoint.host
            startedGens += gen
            pendingGen = gen
            pendingEnd = false
            this.onActive[gen] = onActive
            this.onRoutes[gen] = onRoutes
        }
        if (autoGrant) grant(gen)
        // A hold from inside the platform's own start, before connect() has published the session.
        if (holdInsideStart) onActive(false)
    }

    /**
     * start()'s effects landing for [gen], standing in for AndroidVoiceCall's main-looper hop.
     * This — not start() — is where the call being replaced ends, mirroring the real call's
     * supersede ordering.
     */
    fun grant(gen: Int): Unit = synchronized(lock) {
        if (gen == NO_CALL) return
        if (gen != pendingGen) return
        if (liveGen != NO_CALL) endNow()
        liveGen = gen
        pendingGen = NO_CALL
        if (pendingEnd) { pendingEnd = false; endNow() }
    }

    /** Grant whatever start() is outstanding. No-op when there is none. */
    fun grantPending(): Unit = synchronized(lock) {
        grant(pendingGen)
    }

    override fun end(gen: Int): Unit = synchronized(lock) {
        // Ordered, not lost: the real consumer handles an End queued behind a Start after that
        // Start's effects have applied on the main looper.
        if (gen == pendingGen) { pendingEnd = true; return }
        // Mirrors the real generation guard, so a test that supersedes a session exercises it.
        if (gen != liveGen) return
        endNow()
    }

    private fun endNow() {
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
    // call that is no longer registered.
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
