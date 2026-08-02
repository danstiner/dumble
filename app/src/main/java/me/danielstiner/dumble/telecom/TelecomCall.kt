package me.danielstiner.dumble.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.service.VoiceService

/**
 * Registers the Mumble session as a platform call through Jetpack Telecom, which is what puts the
 * system in charge of audio focus, MODE_IN_COMMUNICATION and routing.
 *
 * Two behaviours of core-telecom 1.0.x shape everything here, both learned the expensive way:
 *
 * The [CallsManager.addCall] block is wrapped in a `coroutineScope`, and the library does not cancel
 * it when the call ends — so the block never returns on its own. It parks on [awaitCancellation] and
 * [finish] is what unwinds it; every end-path drives that explicitly rather than waiting for addCall.
 *
 * The platform's transactional `disconnect` accepts only LOCAL / REMOTE / MISSED / REJECTED and
 * throws IllegalArgumentException on ERROR. The throw aborts the caller before the platform call has
 * actually ended, orphaning it — after which the system believes a call is ongoing and interrupts
 * the user's next action to ask about it. The validation is platform-side, not in the library, so no
 * version bump changes this.
 */
class TelecomCall(private val context: Context) : VoiceCall {

    private val manager = CallsManager(context)
    // The call outlives any Activity, so this is process-scoped. Main because the library's
    // callbacks and our own single-threaded state below both belong there.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val lock = Any()
    private var registered = false
    // Generation of the live call, or [NO_CALL]. Written under [lock] from whichever thread is
    // connecting or tearing down.
    private var liveGen = NO_CALL
    private var job: Job? = null
    private var control: CallControlScope? = null

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onEnded: () -> Unit,
    ) {
        synchronized(lock) {
            if (!registered) {
                manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                registered = true
            }
        }
        // Built lazily so [liveGen] and [job] are published together. Started eagerly, an end() racing
        // in between would see the new generation as live but the previous call's job, and leave
        // this one's block parked forever.
        val parked = scope.launch(start = CoroutineStart.LAZY) {
            try {
                manager.addCall(
                    CallAttributesCompat(
                        displayName = DISPLAY_NAME,
                        // Use Mumble's URL scheme to encode user, host, and port for call history.
                        // Never the password — this string lands in the platform's call record.
                        // Quirk: sdks 26 & 27 will replace this with sip:packageName.
                        address = Uri.parse("mumble://${Uri.encode(username)}@${endpoint.address}"),
                        direction = CallAttributesCompat.DIRECTION_OUTGOING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                    ),
                    onAnswer = { /* outgoing: never rings */ },
                    // onEnded first: finish() cancels the coroutine this callback runs in.
                    onDisconnect = { onEnded(); finish(gen) },
                    onSetActive = { onActive(true) },
                    onSetInactive = { onActive(false) },
                ) {
                    synchronized(lock) { if (gen == liveGen) control = this }
                    // First thing in the block, and deliberately not behind setActive(): the
                    // platform fails the transaction and tears the call down unless a notification
                    // is posted within 5 s of the call being added, and setActive() is a suspend
                    // round trip inside that budget. A call that then fails to go active has its
                    // service stopped again by finish(), so leading costs nothing.
                    VoiceService.start(context, endpoint.host)
                    // The block itself is not suspending — CallControlScope is a CoroutineScope and
                    // the library keeps the call alive for as long as the children launched here
                    // run. A block that launches nothing returns at once and takes the call with it,
                    // which is why the park below is load-bearing rather than decorative.
                    launch {
                        // Outgoing and never ringing, so go active at once: this is what puts
                        // MODE_IN_COMMUNICATION in place before the capture engine opens its stream.
                        when (val result = setActive()) {
                            is CallControlResult.Success -> Unit
                            is CallControlResult.Error -> {
                                Log.w(TAG, "setActive failed error=${result.errorCode}; ending the call")
                                runCatching { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }
                                onEnded()
                                finish(gen)
                                return@launch
                            }
                        }
                        awaitCancellation()
                    }
                }
            } catch (c: CancellationException) {
                throw c // our own teardown, not a failure
            } catch (t: Throwable) {
                // Registration or permission failure. The session has no platform call and so no
                // guaranteed microphone; ending it is honest, and the connection surfaces it.
                Log.e(TAG, "addCall failed", t)
                onEnded()
                finish(gen)
            }
        }
        synchronized(lock) { liveGen = gen; job = parked }
        parked.start()
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        val (parked, scopeToEnd) = synchronized(lock) {
            if (gen != liveGen) return
            liveGen = NO_CALL
            val pair = job to control
            job = null; control = null
            pair
        }
        VoiceService.stop(context)
        if (scopeToEnd == null) {
            parked?.cancel()
            return
        }
        // Tell the platform before dropping the coroutine: cancelling the block alone would leave
        // the OS holding a call nobody ended. REMOTE for a session that died on us and LOCAL for a
        // hang-up, so the platform's record says which actually happened. Never ERROR, however
        // tempting for a failure — it throws (see the class comment).
        val cause = when (reason) {
            VoiceCall.Reason.USER -> DisconnectCause.LOCAL
            VoiceCall.Reason.SESSION_FAILED -> DisconnectCause.REMOTE
        }
        scope.launch {
            runCatching { scopeToEnd.disconnect(DisconnectCause(cause)) }
                .onFailure { Log.w(TAG, "disconnect failed", it) }
            parked?.cancel()
        }
    }

    /** Release our side of a call the system already ended. Idempotent, and never disconnects. */
    private fun finish(gen: Int) {
        val parked = synchronized(lock) {
            if (gen != liveGen) return
            liveGen = NO_CALL
            val parked = job
            job = null; control = null
            parked
        }
        VoiceService.stop(context)
        parked?.cancel()
    }

    private companion object {
        const val TAG = "TelecomCall"
        const val NO_CALL = -1
        const val DISPLAY_NAME = "Dumble"
    }
}
