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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.service.VoiceService

/**
 * Registers the Mumble session as a platform call through Jetpack Telecom, which is what puts the
 * system in charge of audio focus, MODE_IN_COMMUNICATION and routing.
 *
 * [CallsManager.addCall] grants the [CallControlScope] *inside* its block — 25 ms after the ask
 * warm, ~390 ms cold, measured — and every defect here came from operations racing that window
 * against a half-registered call. So the lifecycle is serialised: [start], [end], [requestActive]
 * and the platform's own callbacks all enqueue commands, one consumer runs each to completion,
 * and the Start handler does not finish until the grant arrives — no operation can see a
 * half-registered call, by construction.
 *
 * Two core-telecom 1.0.x behaviours, both learned the expensive way: the addCall block is a
 * `coroutineScope` that returns only when the child parked on [awaitCancellation] is cancelled —
 * completing the library's `blockingSessionExecution` latch (our disconnect(), the platform's
 * onDisconnect) clears addCall's *internal* wait, not this one — so every end-path cancels the
 * job explicitly. And the platform-side validation of `disconnect` (no version bump changes it)
 * throws IllegalArgumentException on ERROR before the call has ended, orphaning a call the system
 * then nags the user about; only LOCAL / REMOTE / MISSED / REJECTED are safe.
 */
class TelecomCall(private val context: Context) : VoiceCall {

    private val manager = CallsManager(context)
    // The call outlives any Activity, so this is process-scoped. Main because the library's
    // callbacks and our own single-threaded state below both belong there.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private sealed interface Command {
        data class Start(
            val gen: Int,
            val endpoint: MumbleEndpoint,
            val username: String,
            val onActive: (Boolean) -> Unit,
            val onEnded: () -> Unit,
        ) : Command
        /** We are ending the call and owe the platform a disconnect. */
        data class End(val gen: Int, val cause: Int) : Command
        /** The platform ended it; release our side only. */
        data class Finish(val gen: Int) : Command
        data class RequestActive(val gen: Int) : Command
    }

    // UNLIMITED is what makes trySend total: every entry point is non-suspending on an arbitrary
    // thread, and a dropped End is the zombie-call defect. The producers bound the queue: one
    // Start in flight (Connect is gated on Idle/Error), one End per attempt, one Finish per call.
    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** The platform call we currently own. Built only once the grant has arrived, so [control]
     *  is non-null for its whole lifetime. */
    private class LiveCall(
        val gen: Int,
        val job: Job,
        val control: CallControlScope,
        /** Raises the same resume signal a real onSetActive would: core-telecom fires onSetActive
         *  only "on behalf of a system service (e.g. Automotive) or a device (e.g. Wearable)",
         *  never for our own setActive() calls. Confirmed on-device: after a Success, dumpsys read
         *  ACTIVE but onSetActive never fired and no audio resumed. */
        val onActive: (Boolean) -> Unit,
    )

    /** Null between calls. Consumer-confined: nothing outside [dispatch] may touch it. */
    private var live: LiveCall? = null
    /** Unlike [live], written by the Start job, not the consumer — but both run on the shared
     *  Main dispatcher, and a second Start cannot dispatch until the first grant resolves. */
    private var registered = false

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onEnded: () -> Unit,
    ) = send(Command.Start(gen, endpoint, username, onActive, onEnded))

    override fun end(gen: Int, reason: VoiceCall.Reason) = send(
        // REMOTE for a session that died on us and LOCAL for a hang-up, so the platform's record
        // says which actually happened. Never ERROR, however tempting for a failure.
        Command.End(
            gen,
            when (reason) {
                VoiceCall.Reason.USER -> DisconnectCause.LOCAL
                VoiceCall.Reason.SESSION_FAILED -> DisconnectCause.REMOTE
            },
        )
    )

    override fun requestActive(gen: Int) = send(Command.RequestActive(gen))

    /** Any thread; never blocks. Cannot fail: the channel is UNLIMITED and never closed. */
    private fun send(c: Command) {
        commands.trySend(c).onFailure { Log.e(TAG, "dropped telecom command: $c", it) }
    }

    private suspend fun dispatch(c: Command) {
        when (c) {
            is Command.Start -> handleStart(c.gen, c.endpoint, c.username, c.onActive, c.onEnded)
            is Command.End -> handleEnd(c.gen, c.cause)
            is Command.Finish -> handleFinish(c.gen)
            is Command.RequestActive -> handleRequestActive(c.gen)
        }
    }

    /**
     * Register [gen]'s call, superseding whatever call we still hold. Suspends until the platform
     * grants control, which is what stops anything else in this lifecycle interleaving with a
     * half-registered call. Lifecycle consumer only.
     */
    private suspend fun handleStart(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (Boolean) -> Unit,
        onEnded: () -> Unit,
    ) {
        // Deliberately not the End path: that stops the service, which the replacement needs
        // inside the platform's 5 s notification budget. LOCAL — replacing our own call is local.
        live?.let { prior ->
            disconnect(prior, DisconnectCause.LOCAL, "superseding disconnect")
            live = null
        }

        val granted = CompletableDeferred<CallControlScope>()
        val job = scope.launch {
            try {
                // Inside the try: registration throws too, and needs the same handling as addCall.
                if (!registered) {
                    manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered = true
                }
                manager.addCall(
                    attributesFor(endpoint, username),
                    onAnswer = { /* outgoing: never rings */ },
                    // Finish before onEnded(): onEnded's teardown reaches MumbleConnection's
                    // capture queue, whose onRelease sends an End(gen) back to this channel with
                    // no ordering against this Finish. Program order puts Finish first, so
                    // handleEnd finds `live` cleared and no-ops instead of disconnecting a call
                    // the platform already tore down.
                    onDisconnect = { send(Command.Finish(gen)); onEnded() },
                    onSetActive = { onActive(true) },
                    onSetInactive = { onActive(false) },
                ) {
                    // First: the platform tears the call down unless a notification is posted
                    // within 5 s of the add, and setActive() is a suspend round trip inside that
                    // budget. Also before the grant is published: an End queued behind this Start
                    // cannot dispatch until handleStart returns, which needs the line below — so
                    // its VoiceService.stop cannot precede this start, by program order rather
                    // than dispatch semantics.
                    VoiceService.start(context, endpoint.host)
                    // This closure's own deferred, never a lookup of `live` — a lookup is skipped
                    // exactly when a teardown has already cleared it, the window this design closes.
                    granted.complete(this)
                    // CallControlScope is a CoroutineScope; the library keeps the call alive only
                    // while children launched here run, so the park below is load-bearing.
                    launch {
                        // Outgoing and never ringing, so go active at once: this is what puts
                        // MODE_IN_COMMUNICATION in place before the capture engine opens its stream.
                        when (val result = setActive()) {
                            is CallControlResult.Success -> Unit
                            is CallControlResult.Error -> {
                                Log.w(TAG, "setActive failed error=${result.errorCode}; ending the call")
                                platformCall("disconnect after setActive failure") {
                                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                                }
                                // Finish before onEnded — same race as onDisconnect above.
                                send(Command.Finish(gen))
                                onEnded()
                                return@launch
                            }
                        }
                        awaitCancellation()
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    // Registration or permission failure. The session has no platform call and so
                    // no guaranteed microphone; ending it is honest, and the connection surfaces it.
                    Log.e(TAG, "addCall failed", t)
                    // Required, not merely safe: a failed supersede's replacement left the prior
                    // call's service running, and nothing else will ever stop it.
                    VoiceService.stop(context)
                    onEnded()
                }
                // Only cancellation propagates: this is a root launch with no
                // CoroutineExceptionHandler, so rethrowing anything else kills the process —
                // right after we handled it. The connection still learns, via invokeOnCompletion
                // completing `granted` exceptionally.
                if (t is CancellationException) throw t
            }
        }
        // A job cancelled inside addCall before entering the block would strand the await forever.
        // Registering after the eager launch is safe: nothing suspends in between, and kotlinx
        // fires a handler registered on an already-completed job immediately.
        job.invokeOnCompletion { cause ->
            granted.completeExceptionally(cause ?: CancellationException("call ended before the grant"))
        }
        // Throws when registration failed; correct — the consumer logs it, `live` stays null, and
        // the connection has already been told.
        live = LiveCall(gen, job, granted.await(), onActive)
    }

    /**
     * The session ended and the ending is ours to report: tell the platform, stop the service,
     * release the block. Ignored unless [gen] is the live call. Lifecycle consumer only.
     */
    private suspend fun handleEnd(gen: Int, cause: Int) {
        // The guard covers the stop as well as the disconnect: a Start(N+1) commonly lands ahead
        // of a stale End(N) and supersedes without stopping the service, so this End must drop
        // whole — a stop hoisted above this line kills the service the replacement is running on.
        val l = live?.takeIf { it.gen == gen } ?: return
        live = null
        // Before the disconnect, so the notification does not linger through that round trip;
        // still after the block's VoiceService.start, because handleStart has returned by now.
        VoiceService.stop(context)
        disconnect(l, cause, "disconnect")
    }

    /**
     * The platform ended [gen]'s call and we are only releasing our side. Never disconnects:
     * accepting the platform's own callback is what completes that transaction. Ignored unless
     * [gen] is the live call. Lifecycle consumer only.
     */
    private fun handleFinish(gen: Int) {
        val l = live?.takeIf { it.gen == gen } ?: return
        live = null
        VoiceService.stop(context)
        l.job.cancel()
    }

    /**
     * Ask the platform to make a held call active again — the only resume path core-telecom
     * offers; it gives no signal of its own when the interrupting call ends. A grant is reported
     * through [LiveCall.onActive] (see its KDoc for why onSetActive cannot be relied on). Ignored
     * unless [gen] is the live call. Lifecycle consumer only.
     */
    private fun handleRequestActive(gen: Int) {
        val l = live?.takeIf { it.gen == gen } ?: return
        // Resolved off the consumer: a resume is a platform round trip, and resume spam must not
        // delay a teardown queued behind it. `l` is captured by value, so a later End cannot change
        // what this sees.
        scope.launch {
            // platformCall for both halves of its job: setActive() on a scope whose job is gone
            // throws rather than returning Error and `scope` has no CoroutineExceptionHandler, and
            // the bound stops a stalled resume leaking this coroutine, and its LiveCall, for the
            // life of the process. A Success landing after the bound is dropped — the call goes
            // active while capture stays released — which the next Talk press retries.
            when (val result = platformCall("requestActive") { l.control.setActive() }) {
                is CallControlResult.Success -> l.onActive(true)
                is CallControlResult.Error ->
                    Log.w(TAG, "requestActive failed error=${result.errorCode}")
                null -> Unit
            }
        }
    }

    /**
     * Every platform transaction goes through here, bounded: the library bounds only addCall,
     * and one transaction stalled on a deferred the platform never completes would freeze this
     * singleton consumer for good. 5 s matches the library's own bound and is ~15x the slowest
     * disconnect measured (323 ms over 9 samples).
     *
     * Rethrows cancellation rather than runCatching, which would swallow the timeout's own
     * CancellationException — defeating the bound and logging it as a call failure. Null means
     * timed out or threw, both already logged.
     */
    private suspend fun <T> platformCall(what: String, block: suspend () -> T): T? =
        withTimeoutOrNull(PLATFORM_TIMEOUT_MS) {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "$what threw", t)
                null
            }
        } ?: run { Log.w(TAG, "$what did not answer within ${PLATFORM_TIMEOUT_MS}ms"); null }

    /** Tell the platform, then release the block. Lifecycle consumer only. */
    private suspend fun disconnect(l: LiveCall, cause: Int, what: String) {
        // On timeout the platform at worst keeps a call its own watchdog reaps; cancelling the
        // job regardless is what stops us waiting on it.
        platformCall(what) { l.control.disconnect(DisconnectCause(cause)) }
        l.job.cancel()
    }

    private fun attributesFor(endpoint: MumbleEndpoint, username: String) = CallAttributesCompat(
        displayName = DISPLAY_NAME,
        // Use Mumble's URL scheme to encode user, host, and port for call history. Never the
        // password — this string lands in the platform's call record. Quirk: sdks 26 & 27 will
        // replace this with sip:packageName.
        address = Uri.parse("mumble://${Uri.encode(username)}@${endpoint.address}"),
        direction = CallAttributesCompat.DIRECTION_OUTGOING,
        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
    )

    // Last, after every field the consumer reads: a consumer launched above a declaration can run
    // against an uninitialized field — the capture lifecycle traced a real "Channel.iterator() on
    // null" crash to exactly that ordering.
    init {
        scope.launch {
            for (c in commands) {
                // A throw would kill the consumer silently and permanently (SupervisorJob does not
                // restart it). Live path: addCall throws on the platform's own "already another
                // call connecting" refusal.
                runCatching { dispatch(c) }
                    .onFailure { Log.e(TAG, "telecom command failed: $c", it) }
            }
        }
    }

    private companion object {
        const val TAG = "TelecomCall"
        const val DISPLAY_NAME = "Dumble"
        const val PLATFORM_TIMEOUT_MS = 5_000L
    }
}
