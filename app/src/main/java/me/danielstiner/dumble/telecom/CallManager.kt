package me.danielstiner.dumble.telecom

import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.telecom.Connection
import android.telecom.DisconnectCause
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.protocol.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

object CallManager {
    private val _activeConnection = MutableStateFlow<Connection?>(null)
    val activeConnection: StateFlow<Connection?> = _activeConnection

    private var notificationManager: CallNotificationManager? = null
    private var serviceRef: WeakReference<Service>? = null
    private var isUiVisible = false
    private var appContext: Context? = null

    private var audioManager: android.media.AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var priorMode = android.media.AudioManager.MODE_NORMAL

    private val _isSpeaker = MutableStateFlow(false)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker
    private val _endpoints = MutableStateFlow<List<android.telecom.CallEndpoint>>(emptyList())

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridgeJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (notificationManager == null) {
            notificationManager = CallNotificationManager(context.applicationContext)
        }
        if (context is Service) {
            serviceRef = WeakReference(context)
        }
    }

    fun setUiVisible(visible: Boolean) {
        isUiVisible = visible
        // If the user just left the app during a call, make sure the notification is prominent
        val connection = _activeConnection.value
        if (!visible && connection != null) {
            updateNotification()
        }
    }

    fun setConnection(connection: Connection?) {
        _activeConnection.value = connection
        updateNotification()

        bridgeJob?.cancel()
        if (connection != null) {
            appContext?.let { enterCallAudio(it) }
            bridgeJob = bridgeScope.launch {
                // Synchronized is a stable state — fine to read off the conflated state flow.
                launch {
                    MumbleManager.state.collect { s ->
                        if (s is ConnectionState.Synchronized) connection.setActive()
                    }
                }
                // Failure teardown MUST use the non-conflated failures flow: the self-heal in
                // MumbleManager flips Failed -> Disconnected too fast for a conflated collector to
                // observe, which would otherwise strand this connection (never destroyed).
                launch {
                    MumbleManager.failures.collect {
                        connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                        connection.destroy()
                        exitCallAudio()
                        setConnection(null)
                    }
                }
            }
        }
    }

    private fun updateNotification() {
        val connection = _activeConnection.value
        val service = serviceRef?.get()

        if (connection != null) {
            // If the UI is not visible, we treat it as more "urgent" if it's just starting
            // But generally, once a call is active, it uses the ongoing channel.
            val notification = notificationManager?.createNotification("Dumble User", isIncoming = false)
            if (notification != null) {
                if (service != null) {
                    service.startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    notificationManager?.showNotification(notification)
                }
            }
        } else {
            notificationManager?.cancelNotification()
            service?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        }
    }

    fun disconnect() {
        MumbleManager.disconnect()
        _activeConnection.value?.onDisconnect()
        _activeConnection.value = null
        notificationManager?.cancelNotification()
        serviceRef?.get()?.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        exitCallAudio()
    }

    private fun enterCallAudio(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager = am
        priorMode = am.mode
        am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        am.requestAudioFocus(req); focusRequest = req
        logAecAvailability()
    }

    private fun exitCallAudio() {
        val am = audioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }; focusRequest = null
        am.mode = priorMode
        audioManager = null
    }

    private fun logAecAvailability() {
        android.util.Log.d("CallManager",
            "AEC available=${android.media.audiofx.AcousticEchoCanceler.isAvailable()}")
    }

    fun onAvailableEndpoints(list: List<android.telecom.CallEndpoint>) { _endpoints.value = list }

    fun onActiveEndpoint(ep: android.telecom.CallEndpoint) {
        _isSpeaker.value = ep.endpointType == android.telecom.CallEndpoint.TYPE_SPEAKER
    }

    fun setSpeaker(speaker: Boolean) {
        val conn = _activeConnection.value ?: return
        val target = if (speaker) android.telecom.CallEndpoint.TYPE_SPEAKER
                     else android.telecom.CallEndpoint.TYPE_EARPIECE
        val ep = _endpoints.value.firstOrNull { it.endpointType == target } ?: return
        conn.requestCallEndpointChange(ep, java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: android.telecom.CallEndpointException) {}
            })
    }
}
