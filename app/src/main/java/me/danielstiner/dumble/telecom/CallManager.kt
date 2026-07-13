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

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bridgeJob: Job? = null

    fun init(context: Context) {
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
            bridgeJob = bridgeScope.launch {
                MumbleManager.state.collect { s ->
                    when (s) {
                        is ConnectionState.Synchronized -> connection.setActive()
                        is ConnectionState.Failed -> {
                            connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                            connection.destroy()
                            setConnection(null)
                        }
                        else -> { /* Connecting/Handshaking: stay initializing; Disconnected handled by disconnect() */ }
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
            val notification = notificationManager?.createNotification("Drumble User", isIncoming = false)
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
    }
}
