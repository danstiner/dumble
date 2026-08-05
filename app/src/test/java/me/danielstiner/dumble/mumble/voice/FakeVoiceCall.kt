package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets a test drive a hold, a resume, or a system-ended call without a platform to register with.
 *
 * The grant is its own event because the real [TelecomCall] gets its CallControlScope inside
 * core-telecom's addCall block, well after start() returns; a fake that superseded inside start()
 * could not express the defects that live in that window.
 *
 * [autoGrant] only moves *when* the grant fires: the default grants immediately, so observable
 * counts at assertion time are unchanged.
 */
class FakeVoiceCall(private val autoGrant: Boolean = true) : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    /** Generation per start(), in order — so a test can address a superseded call by generation. */
    val startedGens = CopyOnWriteArrayList<Int>()
    var ends = 0; private set
    /** Reason per end(), in order — so a test can assert a failure is not reported as a hang-up. */
    val endReasons = CopyOnWriteArrayList<VoiceCall.Reason>()
    /** Generation per requestActive(), in order — so a test can assert a Talk press asked, or didn't. */
    val activeRequests = CopyOnWriteArrayList<Int>()

    private val lock = Any()
    private var liveGen = NO_CALL
    private var pendingGen = NO_CALL
    /** An end() that arrived before the grant; applied when it lands, never dropped. */
    private var pendingEnd: VoiceCall.Reason? = null
    /** Whether the platform currently holds a granted, un-ended call — for a test to check that
     *  the connection's own idea of "connected" and the platform's idea of "a call exists" agree. */
    val hasLiveCall: Boolean get() = synchronized(lock) { liveGen != NO_CALL }
    /** Whether an end() is parked awaiting the still-outstanding start's grant — for a test to
     *  observe that a queued end actually arrived, rather than racing a fixed delay against it. */
    val hasPendingEnd: Boolean get() = synchronized(lock) { pendingEnd != null }
    // Per generation, not one field: a stale hold from a superseded call is exactly the failure the
    // connection's generation check exists to stop, and a single field cannot express one.
    private val onActive = ConcurrentHashMap<Int, (Boolean) -> Unit>()
    private val onEnded = ConcurrentHashMap<Int, () -> Unit>()

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onEnded: () -> Unit,
    ) {
        synchronized(lock) {
            // A pending start's queued end is recorded before the new start supersedes it,
            // mirroring the real consumer's ordered queue.
            if (pendingGen != NO_CALL && pendingEnd != null) {
                ends++
                endReasons += pendingEnd!!
            }
            starts += endpoint.host
            startedGens += gen
            pendingGen = gen
            pendingEnd = null
            this.onActive[gen] = onActive
            this.onEnded[gen] = onEnded
        }
        if (autoGrant) grant(gen)
    }

    /**
     * The platform granted control for [gen]. This — not start() — is where the call being
     * replaced ends, mirroring TelecomCall's supersede-then-register ordering.
     */
    fun grant(gen: Int): Unit = synchronized(lock) {
        if (gen == NO_CALL) return
        if (gen != pendingGen) return
        if (liveGen != NO_CALL) endNow(VoiceCall.Reason.USER)
        liveGen = gen
        pendingGen = NO_CALL
        pendingEnd?.let { reason -> pendingEnd = null; endNow(reason) }
        Unit
    }

    /** Grant whatever start() is outstanding. No-op when there is none. */
    fun grantPending(): Unit = synchronized(lock) {
        grant(pendingGen)
    }

    override fun end(gen: Int, reason: VoiceCall.Reason): Unit = synchronized(lock) {
        // Ordered, not lost: the real consumer handles an End queued behind a Start after that
        // Start has finished registering.
        if (gen == pendingGen) { pendingEnd = reason; return }
        // Mirrors the real generation guard, so a test that supersedes an attempt exercises it.
        if (gen != liveGen) return
        endNow(reason)
    }

    private fun endNow(reason: VoiceCall.Reason) {
        liveGen = NO_CALL
        ends++
        endReasons += reason
    }

    override fun requestActive(gen: Int): Unit = synchronized(lock) {
        // Mirrors the real generation guard. Deliberately does not grant it: TelecomCall's grant
        // arrives asynchronously through onActive, so a test drives that itself via resumeFor().
        if (gen != liveGen) return
        activeRequests += gen
        Unit
    }

    // hold(), resume(), and endedBySystem() no-op once end() has run: liveGen is NO_CALL then,
    // matching a platform call that is no longer registered.
    fun hold() = holdFor(liveGen)

    fun resume() = resumeFor(liveGen)

    /**
     * Deliver a hold to a specific generation, live or not — the platform does not fence a callback
     * already in flight, so a superseded call can still call back.
     */
    fun holdFor(gen: Int) { onActive[gen]?.invoke(false) }

    fun resumeFor(gen: Int) { onActive[gen]?.invoke(true) }

    fun endedBySystem() = endedBySystemFor(liveGen)

    /** A platform hangup for a specific generation, live or not — same reason as [holdFor]. */
    fun endedBySystemFor(gen: Int) { onEnded[gen]?.invoke() }

    private companion object {
        const val NO_CALL = -1
    }
}
