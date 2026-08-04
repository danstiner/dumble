package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Lets a test drive a hold, a resume, or a system-ended call without a platform to register with. */
class FakeVoiceCall : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    var ends = 0; private set
    /** Reason per end(), in order — so a test can assert a failure is not reported as a hang-up. */
    val endReasons = CopyOnWriteArrayList<VoiceCall.Reason>()
    /** Generation per requestActive(), in order — so a test can assert a Talk press asked, or didn't. */
    val activeRequests = CopyOnWriteArrayList<Int>()

    private var liveGen = NO_CALL
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
        // Mirrors TelecomCall.start: registering a call releases the one it replaces.
        if (liveGen != NO_CALL) end(liveGen, VoiceCall.Reason.USER)
        starts += endpoint.host
        liveGen = gen
        this.onActive[gen] = onActive
        this.onEnded[gen] = onEnded
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        // Mirrors the real generation guard, so a test that supersedes an attempt exercises it.
        if (gen != liveGen) return
        liveGen = NO_CALL
        ends++
        endReasons += reason
    }

    override fun requestActive(gen: Int) {
        // Mirrors the real generation guard. Deliberately does not grant it: TelecomCall's grant
        // arrives asynchronously through onActive, so a test drives that itself via resumeFor().
        if (gen != liveGen) return
        activeRequests += gen
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

    fun endedBySystem() { onEnded[liveGen]?.invoke() }

    private companion object {
        const val NO_CALL = -1
    }
}
