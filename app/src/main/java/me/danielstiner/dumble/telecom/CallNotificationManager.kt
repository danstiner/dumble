package me.danielstiner.dumble.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import me.danielstiner.dumble.ActiveCallActivity

class CallNotificationManager(context: Context) {

    companion object {
        private const val ONGOING_CHANNEL_ID = "dumble_ongoing_calls"
        private const val INCOMING_CHANNEL_ID = "dumble_incoming_calls"
        const val NOTIFICATION_ID = 1001
    }

    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        // Ongoing calls: Low importance so it doesn't "pop" while the app is open
        val ongoingChannel = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "Ongoing Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for active Dumble calls"
            setShowBadge(false)
        }

        // Incoming calls: High importance so it alerts the user
        val incomingChannel = NotificationChannel(
            INCOMING_CHANNEL_ID,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for new Dumble calls"
        }

        notificationManager.createNotificationChannel(ongoingChannel)
        notificationManager.createNotificationChannel(incomingChannel)
    }

    fun createNotification(serverLabel: String, channelName: String?, isIncoming: Boolean, connectedSinceMs: Long? = null): Notification {
        val channelId = if (isIncoming) INCOMING_CHANNEL_ID else ONGOING_CHANNEL_ID
        val title = serverLabel.ifBlank { "Dumble" }   // CallStyle throws on an empty Person name

        val intent = Intent(appContext, ActiveCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val hangupIntent = Intent(appContext, CallActionReceiver::class.java).apply { action = "ACTION_HANGUP" }
        val hangupPendingIntent = PendingIntent.getBroadcast(appContext, 1, hangupIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = Notification.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
        // Only set contentText when the channel is known — an empty string suppresses CallStyle's
        // localized "Ongoing call" default and would show a blank line during the handshake.
        channelName?.let { builder.setContentText("in $it") }

        val person = android.app.Person.Builder().setName(title).build()
        if (isIncoming) {
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, hangupPendingIntent, pendingIntent))
        } else {
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, hangupPendingIntent))
            if (connectedSinceMs != null) {
                builder.setWhen(connectedSinceMs).setUsesChronometer(true).setShowWhen(true)
            }
        }
        return builder.build()
    }

    /**
     * Plain ongoing notification (no CallStyle) used only as a last resort if [createNotification]
     * ever throws, so the foreground service can still satisfy its mandatory startForeground call
     * within the system's ~5s window instead of crashing the process.
     */
    fun createFallbackNotification(): Notification =
        Notification.Builder(appContext, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Dumble Call")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()

    fun showNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
