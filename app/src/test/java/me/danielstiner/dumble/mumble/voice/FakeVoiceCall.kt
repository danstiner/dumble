package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import java.util.concurrent.CopyOnWriteArrayList

/** Lets a test drive a hold, a resume, or a system-ended call without a platform to register with. */
class FakeVoiceCall : VoiceCall {
    /** Server host per start(), in order — so a test can assert what the call was opened against. */
    val starts = CopyOnWriteArrayList<String>()
    var ends = 0; private set
    /** Reason per end(), in order — so a test can assert a failure is not reported as a hang-up. */
    val endReasons = CopyOnWriteArrayList<VoiceCall.Reason>()

    private var liveGen = NO_CALL
    private var onActive: ((Boolean) -> Unit)? = null
    private var onEnded: (() -> Unit)? = null

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onEnded: () -> Unit,
    ) {
        starts += endpoint.host
        liveGen = gen
        this.onActive = onActive
        this.onEnded = onEnded
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        // Mirrors the real generation guard, so a test that supersedes an attempt exercises it.
        if (gen != liveGen) return
        liveGen = NO_CALL
        ends++
        endReasons += reason
        onActive = null
        onEnded = null
    }

    /** No-op once ended, matching a platform call that is no longer registered. */
    fun hold() = onActive?.invoke(false)

    fun resume() = onActive?.invoke(true)

    fun endedBySystem() {
        onEnded?.invoke()
    }

    private companion object {
        const val NO_CALL = -1
    }
}
