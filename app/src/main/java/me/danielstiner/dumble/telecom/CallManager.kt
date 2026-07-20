package me.danielstiner.dumble.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.model.currentChannelName
import me.danielstiner.dumble.mumble.model.serverLabel
import me.danielstiner.dumble.mumble.protocol.ConnectionState

/**
 * Singleton bridge between the app UI and Jetpack Telecom [CallsManager]. Owns the outgoing Mumble
 * "call": registers the app with Telecom once per process, launches the [CallsManager.addCall]
 * coroutine that lives for the call's duration, and mirrors the call's audio endpoints / mute /
 * Mumble connection-state into StateFlows the call screen observes.
 *
 * The library owns audio focus and audio mode (MODE_IN_COMMUNICATION), so we do NOT touch AudioManager.
 * It also grants the process foreground *procstate*, but NOT a foreground *service* — and Android
 * rejects a CallStyle notification (and cuts background mic on API 34+) without one. So we run our own
 * [CallForegroundService] for the call's lifetime to carry the notification and microphone FGS type.
 *
 * Lifecycle note (core-telecom 1.0.0): addCall wraps the block in a coroutineScope whose children are
 * the launched collectors below; those collectors are infinite, so addCall does NOT return on its own
 * when the call ends. Every end-path therefore drives [teardown] explicitly, and teardown's
 * callJob.cancel() is what unwinds that parked coroutineScope.
 */
object CallManager {
    private const val TAG = "CallManager"

    // Process-scoped: the call must outlive the Activity. Everything here runs on Main, so the plain
    // mutable fields below are Main-confined (no cross-thread sharing).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var callsManager: CallsManager? = null
    private var appContext: Context? = null
    private var registered = false

    private var callJob: Job? = null
    private var controlScope: CallControlScope? = null   // Main-confined
    // True while the user is explicitly on Speaker — the one route held against auto-routing (Main-confined).
    private var speakerHeld = false
    // Per-call generation. Guards teardown so a superseded call's late finalizer can't tear down the
    // next call (they share this singleton's fields).
    private var callSeq = 0
    private var tornDown = true

    // Wall-clock moment the call reached Synchronized; anchors the notification chronometer.
    private var connectedSinceMs: Long? = null

    // Latest server/channel for the notification (Main-confined, like connectedSinceMs).
    private var hostFallback: String = "Dumble"
    private var serverLabelState: String = "Dumble"
    private var channelNameState: String? = null

    private val _callActive = MutableStateFlow(false)
    /** True while a Telecom call is registered — drives the in-call UI (replaces the old Connection). */
    val callActive: StateFlow<Boolean> = _callActive

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker

    private val _endpoints = MutableStateFlow<List<CallEndpointCompat>>(emptyList())
    /** Available call audio routes (for the route picker when a headset is connected). */
    val endpoints: StateFlow<List<CallEndpointCompat>> = _endpoints

    // The currently-active call audio endpoint (BT / wired / earpiece / speaker), surfaced read-only
    // as a route indicator on the call screen. Reset on teardown so the previous call's route can't
    // linger into the next one before the framework reports the new active endpoint.
    private val _activeEndpoint = MutableStateFlow<CallEndpointCompat?>(null)
    val activeEndpoint: StateFlow<CallEndpointCompat?> = _activeEndpoint

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (callsManager == null) callsManager = CallsManager(app)
    }

    fun setUiVisible(visible: Boolean) {
        // The CallStyle notification is posted for the whole call (that's what grants foreground
        // priority), so leaving the app needs no extra action. Kept for the Activity's lifecycle hook.
    }

    /** Start the outgoing Mumble call: register once, then launch the addCall coroutine. */
    fun startCall(host: String) {
        val cm = callsManager ?: run { Log.e(TAG, "startCall before init"); return }
        if (callJob?.isActive == true) { Log.w(TAG, "startCall while a call is active"); return }
        hostFallback = host
        serverLabelState = host
        channelNameState = null
        ensureRegistered(cm)
        val seq = ++callSeq
        tornDown = false
        callJob = appScope.launch {
            try {
                cm.addCall(
                    CallAttributesCompat(
                        displayName = "Dumble",
                        address = Uri.fromParts("tel", "DumbleUser", null),
                        direction = CallAttributesCompat.DIRECTION_OUTGOING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        // Default callCapabilities (SUPPORTS_SET_INACTIVE) already lets the framework
                        // hold/resume the call around a system interruption (e.g. an incoming cellular
                        // call), so onSetInactive/onSetActive below work without an explicit flag.
                    ),
                    onAnswer = { /* n/a: outgoing call, never rings */ },
                    // System-initiated end. addCall won't return on its own (see class doc), so drive
                    // teardown() here.
                    onDisconnect = { cause ->
                        Log.d(TAG, "system onDisconnect: $cause")
                        teardown(seq)
                    },
                    onSetActive = { Log.d(TAG, "onSetActive (resumed from hold)") },
                    onSetInactive = { Log.d(TAG, "onSetInactive (held)") },
                ) {
                    controlScope = this
                    _callActive.value = true
                    // Outgoing, no ringing -> go active immediately so MODE_IN_COMMUNICATION is set
                    // around AudioVoiceEngine start. On success bring up the call foreground service
                    // (owns the CallStyle notification + microphone FGS type); on failure the "call"
                    // would have no audio focus/mode, so end it instead.
                    launch {
                        when (val res = setActive()) {
                            is CallControlResult.Success -> startCallForeground()
                            is CallControlResult.Error -> {
                                Log.w(TAG, "setActive failed error=${res.errorCode}; disconnecting")
                                disconnect(DisconnectCause(DisconnectCause.LOCAL))   // see below: ERROR is rejected
                                teardown(seq)
                            }
                        }
                    }
                    launch {
                        availableEndpoints.collect { eps ->
                            _endpoints.value = eps
                            Log.d(TAG, "availableEndpoints: ${eps.map { AudioRoute.label(it.type, it.name) }}")
                            // Proactively route to the best device (BT > wired > earpiece). The framework
                            // does NOT auto-route to BT at call start (observed: plays out the speaker), and a
                            // headset can connect mid-call — so keep the call on the preferred endpoint
                            // whenever the available set changes. Speaker is the one route we DON'T override:
                            // the user opted into it, and it's held until they toggle it off.
                            if (!speakerHeld) {
                                val preferred = preferredEndpoint(eps)
                                if (preferred != null && preferred.type != _activeEndpoint.value?.type) {
                                    Log.d(TAG, "auto-route -> ${AudioRoute.label(preferred.type, preferred.name)}")
                                    requestEndpoint(preferred)
                                }
                            }
                        }
                    }
                    launch { currentCallEndpoint.collect { onActiveEndpoint(it) } }
                    // System/hardware mute (BT-headset button, system call controls) -> mute in Mumble
                    // (broadcasts self_mute). drop(1) skips the framework's initial emit so it can't
                    // stomp a "join muted" state; genuine later toggles still propagate. One-way; the
                    // app's own Mute button is unaffected.
                    launch { isMuted.drop(1).collect { MumbleManager.setMuted(it) } }
                    launch {
                        MumbleManager.state.collect { s ->
                            if (s is ConnectionState.Synchronized && connectedSinceMs == null) {
                                connectedSinceMs = System.currentTimeMillis()
                                startCallForeground() // refresh the FGS notification with the chronometer anchor
                            }
                        }
                    }
                    // Live server/channel for the notification. Runs on Main (appScope); the …State fields
                    // are Main-confined. Conflated StateFlow + the equality guard limit re-posts to changes.
                    launch {
                        MumbleManager.model.state.collect { m ->
                            val label = serverLabel(m, hostFallback)
                            val channel = currentChannelName(m)
                            if (label != serverLabelState || channel != channelNameState) {
                                serverLabelState = label
                                channelNameState = channel
                                startCallForeground()
                            }
                        }
                    }
                    // Failure teardown MUST use the non-conflated failures flow: MumbleManager self-heals
                    // Failed -> Disconnected too fast for a conflated collector to observe.
                    launch {
                        MumbleManager.failures.collect {
                            // REMOTE, not ERROR: the transactional CallControl.disconnect only accepts
                            // LOCAL/REMOTE/MISSED/REJECTED and THROWS IllegalArgumentException on ERROR — which
                            // aborted this collector before the platform call was ended, orphaning it (the OS
                            // then thinks a call is ongoing → spurious "end your call?" prompts on the next action).
                            val res = disconnect(DisconnectCause(DisconnectCause.REMOTE))
                            Log.w(TAG, "disconnect(REMOTE) on Mumble failure result=$res")
                            teardown(seq)
                        }
                    }
                }
            } catch (c: CancellationException) {
                throw c // deliberate teardown cancel — not an error
            } catch (t: Throwable) {
                Log.e(TAG, "addCall failed", t)
            } finally {
                teardown(seq) // backstop for the throw/cancel path
            }
        }
    }

    private fun ensureRegistered(cm: CallsManager) {
        if (registered) return
        cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        registered = true
    }

    /**
     * Bring up (or refresh) the call foreground service that owns the CallStyle notification. core-telecom
     * grants foreground procstate but no foreground *service*, and Android rejects a CallStyle notification
     * without one — so this is what makes the notification post at all (and enables background mic). A
     * foreground-start failure must never tear down a live call, hence the catch.
     */
    private fun startCallForeground() {
        val ctx = appContext ?: return
        try {
            CallForegroundService.start(ctx, serverLabelState, channelNameState, connectedSinceMs)
        } catch (t: Throwable) {
            Log.e(TAG, "starting call foreground service failed", t)
        }
    }

    /** User hang-up (call screen / notification action). */
    fun hangUp() {
        val seq = callSeq
        val scope = controlScope
        if (scope != null) {
            appScope.launch {
                val res = scope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                Log.d(TAG, "user hang-up result=$res")
                teardown(seq)
            }
        } else {
            teardown(seq) // no active control scope (call never fully started) — clean up anyway
        }
    }

    /** Single idempotent cleanup all disconnect paths converge on. [seq] guards against a superseded
     *  call's late finalizer tearing down a newer call. */
    private fun teardown(seq: Int) {
        if (seq != callSeq || tornDown) return
        tornDown = true
        MumbleManager.disconnect()
        appContext?.let { CallForegroundService.stop(it) }
        controlScope = null
        _callActive.value = false
        _endpoints.value = emptyList()
        _activeEndpoint.value = null
        _isSpeaker.value = false
        speakerHeld = false
        connectedSinceMs = null
        serverLabelState = "Dumble"
        channelNameState = null
        callJob?.cancel() // unwinds the parked addCall coroutineScope (cancels the block's collectors)
        callJob = null
    }

    private fun onActiveEndpoint(ep: CallEndpointCompat) {
        Log.d(TAG, "activeEndpoint: ${AudioRoute.label(ep.type, ep.name)}")
        _activeEndpoint.value = ep
        _isSpeaker.value = ep.type == CallEndpointCompat.TYPE_SPEAKER
    }

    /** Best non-speaker endpoint by priority: Bluetooth > wired headset > earpiece (null if none). */
    private fun preferredEndpoint(eps: List<CallEndpointCompat>): CallEndpointCompat? =
        eps.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
            ?: eps.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
            ?: eps.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }

    /** Request the framework switch the call's audio route to [ep], logging the outcome. */
    private fun requestEndpoint(ep: CallEndpointCompat) {
        val scope = controlScope ?: return
        val label = AudioRoute.label(ep.type, ep.name)
        appScope.launch {
            when (val res = scope.requestEndpointChange(ep)) {
                is CallControlResult.Error -> Log.w(TAG, "requestEndpointChange $label error=${res.errorCode}")
                is CallControlResult.Success -> Log.d(TAG, "requestEndpointChange $label ok")
            }
        }
    }

    /** Route to the first available endpoint of [endpointType] (route picker: BT / wired / earpiece / speaker). */
    fun selectRoute(endpointType: Int) {
        speakerHeld = endpointType == CallEndpointCompat.TYPE_SPEAKER
        val ep = _endpoints.value.firstOrNull { it.type == endpointType }
        if (ep == null) {
            Log.w(TAG, "selectRoute: no endpoint of type $endpointType in " +
                "${_endpoints.value.map { AudioRoute.label(it.type, it.name) }}")
            return
        }
        requestEndpoint(ep)
    }

    /** Speaker on/off. Off returns to the best non-speaker route (BT > wired > earpiece), not always earpiece. */
    fun setSpeaker(speaker: Boolean) {
        speakerHeld = speaker
        val ep = if (speaker) _endpoints.value.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
                 else preferredEndpoint(_endpoints.value)
        if (ep == null) {
            Log.w(TAG, "setSpeaker($speaker): no target endpoint in " +
                "${_endpoints.value.map { AudioRoute.label(it.type, it.name) }}")
            return
        }
        requestEndpoint(ep)
    }
}
