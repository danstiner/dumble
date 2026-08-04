package me.danielstiner.dumble.mumble.voice

import me.danielstiner.dumble.mumble.net.MumbleEndpoint

/**
 * The platform's notion of the call a connection represents. Registering one is what hands the
 * system ownership of audio focus, audio mode and routing — which is why nothing here talks to
 * AudioManager: doing so alongside a registered call is forbidden, and it is what left capture on
 * the built-in microphone with a headset connected.
 *
 * A seam, so the JVM tests can drive hold and resume without a device.
 */
interface VoiceCall {
    /**
     * [gen] is the connection attempt's generation. [onActive] reports the system resuming the call
     * and holding it (a cellular call arriving); [onEnded] reports the system ending it, which the
     * connection answers by disconnecting.
     *
     * Must be called from the foreground: the call brings up a `microphone` foreground service, and
     * one cannot be started from the background.
     */
    fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (active: Boolean) -> Unit,
        onEnded: () -> Unit,
    )

    /**
     * Ignored unless [gen] is the live call. Connecting tears the prior attempt down and starts the
     * next call in the same breath, so without the generation a superseded attempt's teardown would
     * end its successor.
     */
    fun end(gen: Int, reason: Reason = Reason.USER)

    /**
     * Ask the platform to make a held call active again. Core-telecom delivers no signal of its own
     * when the interrupting call ends — `onActive` fires only in answer to this — so a held session
     * stays dead until something calls it. Ignored unless [gen] is the live call; a grant reaches the
     * connection back through [start]'s `onActive`, same as any other resume.
     */
    fun requestActive(gen: Int)

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
        onEnded: () -> Unit,
    ) = Unit
    override fun end(gen: Int, reason: VoiceCall.Reason) = Unit
    override fun requestActive(gen: Int) = Unit
}
