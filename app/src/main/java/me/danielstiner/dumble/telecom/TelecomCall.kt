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

    /**
     * The platform call we currently own: claimed in [start], released by [end], [finish], or the
     * next start()'s supersession. One reference, one lifetime — the generation, the parked addCall
     * block, and the resume path cannot be cleared out of step. Only [control] arrives late: the
     * platform grants it inside the addCall block, so it is filled in after the claim, under
     * [lock] like every access to [live].
     */
    private class LiveCall(
        val gen: Int,
        val job: Job,
        /** Raises the same resume signal a real onSetActive would. core-telecom's own doc for
         *  addCall says onSetActive fires only "on behalf of a system service (e.g. Automotive) or
         *  a device (e.g. Wearable)" — not for our own setActive() calls, which resolve through
         *  their direct return value instead. Confirmed on-device: after requestActive() got
         *  CallControlResult.Success, telecom's own dumpsys read ACTIVE, but onSetActive never
         *  fired and no audio resumed. */
        val onActive: (Boolean) -> Unit,
    ) {
        var control: CallControlScope? = null
    }

    /** Null between calls. Written under [lock] from whichever thread is connecting or tearing down. */
    private var live: LiveCall? = null

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
        // Built lazily so the claim below publishes the job together with its generation. Started
        // eagerly, an end() racing in between would see the new generation as live but the previous
        // call's job, and leave this one's block parked forever.
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
                    synchronized(lock) { live?.takeIf { it.gen == gen }?.control = this }
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
        // Claim and release in one swap. Overwriting the generation alone left the prior call
        // unreachable — end() and finish() both guard on it — so the platform kept a call nobody
        // could end, whose onDisconnect is wired to disconnect() and would take the replacing
        // session down with it.
        val (priorJob, priorControl) = synchronized(lock) {
            val p = live
            live = LiveCall(gen, parked, onActive)
            p?.job to p?.control
        }
        supersede(priorJob, priorControl)
        parked.start()
    }

    /**
     * The only resume path core-telecom offers: it does not tell us when the interrupting call
     * ends, so getting the call back means asking again with the same [CallControlScope.setActive]
     * used to go active the first time. [control] survives a hold — onSetInactive does not clear it,
     * only end()/finish() do — so this is the same scope. A grant is reported by invoking
     * [LiveCall.onActive] directly, because `onSetActive` does not fire for our own setActive() —
     * see that property's KDoc for the on-device evidence.
     *
     * Any thread: state is read under [lock], and the transaction resolves later on [scope].
     */
    override fun requestActive(gen: Int) {
        val (ctl, notify) = synchronized(lock) {
            val l = live?.takeIf { it.gen == gen } ?: return
            (l.control ?: return) to l.onActive
        }
        scope.launch {
            // runCatching, like every other transactional call here: `ctl` is read under [lock] but
            // the transaction resolves later, and end()/finish() may have cancelled the parked job
            // in between — setActive() on a scope whose job is gone throws rather than returning
            // Error. `scope` has no CoroutineExceptionHandler, so unguarded that reaches the default
            // uncaught handler and takes the process down. Reachable: the call is held, the user
            // presses Talk, and the interrupting call's end drives onDisconnect -> finish(gen)
            // before this resolves.
            runCatching { ctl.setActive() }
                .onSuccess { result ->
                    when (result) {
                        is CallControlResult.Success -> notify(true)
                        is CallControlResult.Error ->
                            Log.w(TAG, "requestActive failed error=${result.errorCode}")
                    }
                }
                .onFailure { Log.w(TAG, "requestActive threw", it) }
        }
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        val (parked, scopeToEnd) = synchronized(lock) {
            val l = live?.takeIf { it.gen == gen } ?: return
            live = null
            l.job to l.control
        }
        VoiceService.stop(context)
        // Tell the platform before dropping the coroutine: cancelling the block alone would leave
        // the OS holding a call nobody ended. REMOTE for a session that died on us and LOCAL for a
        // hang-up, so the platform's record says which actually happened. Never ERROR, however
        // tempting for a failure — it throws (see the class comment).
        val cause = when (reason) {
            VoiceCall.Reason.USER -> DisconnectCause.LOCAL
            VoiceCall.Reason.SESSION_FAILED -> DisconnectCause.REMOTE
        }
        disconnectAndCancel(parked, scopeToEnd, cause, "disconnect failed")
    }

    /**
     * Disconnect a call we are replacing. Deliberately not [end]: that guards on [liveGen], which
     * the caller has already claimed, and it stops the service the replacing call is about to need
     * inside its 5 s notification budget.
     */
    private fun supersede(parked: Job?, scopeToEnd: CallControlScope?) {
        // LOCAL, not REMOTE: replacing our own call is a local act. Never ERROR — see the class comment.
        disconnectAndCancel(parked, scopeToEnd, DisconnectCause.LOCAL, "superseding disconnect failed")
    }

    /** Shared by [end] and [supersede]: disconnect the platform call, then release its parked block. */
    private fun disconnectAndCancel(parked: Job?, scopeToEnd: CallControlScope?, cause: Int, logMessage: String) {
        if (scopeToEnd == null) { parked?.cancel(); return }
        scope.launch {
            runCatching { scopeToEnd.disconnect(DisconnectCause(cause)) }
                .onFailure { Log.w(TAG, logMessage, it) }
            parked?.cancel()
        }
    }

    /** Release our side of a call the system already ended. Idempotent, and never disconnects. */
    private fun finish(gen: Int) {
        val parked = synchronized(lock) {
            val l = live?.takeIf { it.gen == gen } ?: return
            live = null
            l.job
        }
        VoiceService.stop(context)
        parked.cancel()
    }

    private companion object {
        const val TAG = "TelecomCall"
        const val DISPLAY_NAME = "Dumble"
    }
}
