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
        private const val NOTIFICATION_ID = 1001
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

    fun createNotification(callerName: String, isIncoming: Boolean): Notification {
        val channelId = if (isIncoming) INCOMING_CHANNEL_ID else ONGOING_CHANNEL_ID
        
        val intent = Intent(appContext, ActiveCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(appContext, CallActionReceiver::class.java).apply {
            action = "ACTION_HANGUP"
        }
        val hangupPendingIntent = PendingIntent.getBroadcast(
            appContext, 1, hangupIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Dumble Call")
            .setContentText(if (isIncoming) "Incoming call from $callerName" else "Call with $callerName")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(pendingIntent)

        if (isIncoming) {
            // style for incoming call (usually includes Answer/Reject)
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    android.app.Person.Builder().setName(callerName).build(),
                    hangupPendingIntent, // reject
                    pendingIntent // answer (launches activity)
                )
            )
        } else {
            // style for ongoing call
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    android.app.Person.Builder().setName(callerName).build(),
                    hangupPendingIntent
                )
            )
        }

        return builder.build()
    }

    fun showNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
