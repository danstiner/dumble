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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.protocol.ConnectionState

/**
 * Singleton bridge between the app UI and Jetpack Telecom [CallsManager]. Owns the outgoing Mumble
 * "call": registers the app with Telecom once per process, launches the [CallsManager.addCall]
 * coroutine that lives for the call's duration, and mirrors the call's audio endpoints / mute /
 * Mumble connection-state into StateFlows the call screen observes.
 *
 * The library owns audio focus, audio mode (MODE_IN_COMMUNICATION), and foreground-execution
 * priority (granted while a CallStyle notification is posted). We do NOT touch AudioManager or
 * startForeground anymore — that would conflict with the library.
 */
object CallManager {
    private const val TAG = "CallManager"

    // The call must outlive the Activity, so this scope is process-scoped (not tied to any UI).
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var callsManager: CallsManager? = null
    private var notificationManager: CallNotificationManager? = null
    private var registered = false

    // The live call: the addCall coroutine + the CallControlScope handle used to drive the call.
    private var callJob: Job? = null
    @Volatile private var controlScope: CallControlScope? = null
    private var tornDown = true

    // Wall-clock moment the call reached Synchronized; anchors the notification chronometer.
    private var connectedSinceMs: Long? = null

    private var isUiVisible = false

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
        if (callsManager == null) callsManager = CallsManager(app)
        if (notificationManager == null) notificationManager = CallNotificationManager(app)
    }

    fun setUiVisible(visible: Boolean) {
        isUiVisible = visible
        // The CallStyle notification is posted for the whole call (it's what grants foreground
        // priority), so leaving the app needs no extra action — the notification is already up.
    }

    /** Start the outgoing Mumble call: register once, then launch the addCall coroutine. */
    fun startCall() {
        val cm = callsManager ?: run { Log.e(TAG, "startCall before init"); return }
        if (callJob?.isActive == true) { Log.w(TAG, "startCall while a call is active"); return }
        ensureRegistered(cm)
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
                    // System-initiated end: after this returns the block's scope is cancelled ->
                    // the finally below runs teardown().
                    onDisconnect = { cause -> Log.d(TAG, "system onDisconnect: $cause") },
                    onSetActive = { Log.d(TAG, "onSetActive (resumed from hold)") },
                    onSetInactive = { Log.d(TAG, "onSetInactive (held)") },
                ) {
                    controlScope = this
                    _callActive.value = true
                    // Outgoing, no ringing -> go active immediately so MODE_IN_COMMUNICATION is set
                    // around AudioVoiceEngine start; post the required CallStyle notification within 5s.
                    launch {
                        val res = setActive()
                        Log.d(TAG, "setActive result=$res")
                        postCallNotification()
                    }
                    launch { availableEndpoints.collect { _endpoints.value = it } }
                    launch { currentCallEndpoint.collect { onActiveEndpoint(it) } }
                    // System/hardware mute (BT-headset button, system call controls) -> mute in Mumble
                    // (broadcasts self_mute). One-way; the app's own Mute button is unaffected.
                    launch { isMuted.collect { MumbleManager.setMuted(it) } }
                    launch {
                        MumbleManager.state.collect { s ->
                            if (s is ConnectionState.Synchronized && connectedSinceMs == null) {
                                connectedSinceMs = System.currentTimeMillis()
                                postCallNotification() // re-post with the chronometer anchor
                            }
                        }
                    }
                    // Failure teardown MUST use the non-conflated failures flow: MumbleManager self-heals
                    // Failed -> Disconnected too fast for a conflated collector to observe.
                    launch {
                        MumbleManager.failures.collect {
                            val res = disconnect(DisconnectCause(DisconnectCause.ERROR))
                            Log.d(TAG, "disconnect(ERROR) on Mumble failure result=$res")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "addCall failed", t)
            } finally {
                teardown()
            }
        }
    }

    private fun ensureRegistered(cm: CallsManager) {
        if (registered) return
        cm.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        registered = true
    }

    private fun postCallNotification() {
        val nm = notificationManager ?: return
        nm.showNotification(
            nm.createNotification("Dumble User", isIncoming = false, connectedSinceMs = connectedSinceMs)
        )
    }

    /** User hang-up (call screen / notification action). */
    fun disconnect() {
        val scope = controlScope
        if (scope != null) {
            appScope.launch {
                val res = scope.disconnect(DisconnectCause(DisconnectCause.LOCAL))
                Log.d(TAG, "user disconnect result=$res") // block ends -> finally -> teardown
            }
        } else {
            teardown() // no active control scope (call never fully started) — clean up anyway
        }
    }

    /** Single idempotent cleanup all disconnect paths converge on. */
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        MumbleManager.disconnect()
        notificationManager?.cancelNotification()
        controlScope = null
        _callActive.value = false
        _endpoints.value = emptyList()
        _activeEndpoint.value = null
        _isSpeaker.value = false
        connectedSinceMs = null
        callJob?.cancel()
        callJob = null
    }

    private fun onActiveEndpoint(ep: CallEndpointCompat) {
        _activeEndpoint.value = ep
        _isSpeaker.value = ep.type == CallEndpointCompat.TYPE_SPEAKER
    }

    /** Route to the first available endpoint of [endpointType] (BT / wired / earpiece / speaker). */
    fun selectRoute(endpointType: Int) {
        val scope = controlScope ?: return
        val ep = _endpoints.value.firstOrNull { it.type == endpointType } ?: return
        appScope.launch {
            // Surfaced (parity with today's silent no-op, but now attributable — the later #59 fix
            // consumes this error).
            when (val res = scope.requestEndpointChange(ep)) {
                is CallControlResult.Error ->
                    Log.w(TAG, "requestEndpointChange type=$endpointType error=${res.errorCode}")
                is CallControlResult.Success ->
                    Log.d(TAG, "requestEndpointChange type=$endpointType ok")
            }
        }
    }

    /** Speaker on/off toggle for the no-headset case (earpiece <-> speaker). */
    fun setSpeaker(speaker: Boolean) = selectRoute(
        if (speaker) CallEndpointCompat.TYPE_SPEAKER else CallEndpointCompat.TYPE_EARPIECE
    )
}
