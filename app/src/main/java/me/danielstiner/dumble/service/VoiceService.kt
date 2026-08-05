package me.danielstiner.dumble.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import me.danielstiner.dumble.MainActivity
import me.danielstiner.dumble.R
import me.danielstiner.dumble.mumble.connection.Connection
import javax.inject.Inject

/**
 * Exists for two reasons that happen to have one fix. The microphone cannot be read from the
 * background without a `microphone`-type foreground service, and — separately — Android's
 * background-audio hardening fails playback for an app with no visible activity and no
 * while-in-use foreground service. That exemption is type-agnostic beyond excluding SHORT_SERVICE,
 * so this one service covers playback too.
 *
 * The type is per start because the answer can differ between starts and asking for `microphone`
 * without RECORD_AUDIO throws SecurityException on API 34+ — which once left the first session of
 * every install with no foreground service at all. `mediaPlayback` needs no permission and still
 * buys the background-audio exemption, so a user who refuses the microphone still receives; only
 * transmit needs `microphone`.
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject lateinit var connection: Connection

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // Handled here rather than in a BroadcastReceiver so the thing that represents the call
            // is the thing that ends it. Nothing else to do: disconnect tears the session down, and
            // that teardown is what stops this service.
            connection.disconnect()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // stopSelf(startId), not stopSelf(): a stale stop loses to any start delivered after
            // it, so a teardown for the replaced call cannot take down the service its replacement
            // just started. No startForeground needed: commands arrive in order, so any
            // startForegroundService promise was redeemed by the branch below before this runs —
            // and a plain-startService creation made no promise.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(intent?.getStringExtra(EXTRA_SERVER).orEmpty()),
                foregroundType(),
            )
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException if we lost the race into the background.
            // Dropping the service beats hard-crashing the process: the connection itself is
            // unaffected, and transmit degrades to working only while the app is visible.
            Log.e(TAG, "startForeground failed; stopping service", t)
            stopSelf(startId)
        }
        // Nothing to rebuild without a connection to attach to, and the connection lives in the
        // app process — a restart with a null intent would show a notification for a session that
        // no longer exists.
        return START_NOT_STICKY
    }

    /**
     * Read per start, never cached: the permission can arrive between two starts, and a service
     * that claimed `microphone` without it does not start at all.
     */
    private fun foregroundType(): Int =
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }

    private fun buildNotification(server: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_channel_name),
                    // Ongoing state, not something to interrupt for.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(server)
            .setOngoing(true)
            .setContentIntent(resumeIntent())
            // Ranks it with calls rather than with general ongoing work, which is what it is.
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.voice_notification_disconnect),
                PendingIntent.getService(
                    this,
                    REQUEST_DISCONNECT,
                    Intent(this, VoiceService::class.java).setAction(ACTION_DISCONNECT),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /**
     * SINGLE_TOP|CLEAR_TOP reuses the running activity instead of stacking a second one on top of
     * it. Measured, not assumed: an ACTION_MAIN/CATEGORY_LAUNCHER intent left two MainActivity
     * records in the same task, because a task rooted by an explicit-component start does not match
     * a launcher-style intent. The duplicate is easy to miss — Connection is a singleton, so the
     * new activity's ViewModel shows the same live session — until Back reveals the stale copy.
     */
    private fun resumeIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        ),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "voice"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_SERVER = "server"
        private const val TAG = "VoiceService"
        private const val ACTION_DISCONNECT = "me.danielstiner.dumble.DISCONNECT"
        private const val ACTION_STOP = "me.danielstiner.dumble.STOP"
        // Distinct from the content intent's, so the two PendingIntents cannot collide.
        private const val REQUEST_DISCONNECT = 1

        /** Caller must be foreground: a microphone service cannot be started from the background. */
        fun start(context: Context, server: String) {
            try {
                context.startForegroundService(
                    Intent(context, VoiceService::class.java).putExtra(EXTRA_SERVER, server)
                )
            } catch (t: Throwable) {
                // Throws at the *caller* when the app is no longer foreground, so letting it
                // propagate would take down whatever coroutine asked for capture.
                Log.e(TAG, "could not start the microphone service", t)
            }
        }

        fun stop(context: Context) {
            // Never stopService: it bypasses the intent queue and can bring the service down while
            // a startForegroundService promise is unredeemed, killing the process with
            // ForegroundServiceDidNotStartInTimeException — observed 25 ms apart on-device. Plain
            // startService makes no promise, so no background-start check; it throws only for a
            // background caller with no running service, exactly when there is nothing to stop.
            try {
                context.startService(Intent(context, VoiceService::class.java).setAction(ACTION_STOP))
            } catch (t: IllegalStateException) {
                Log.i(TAG, "no running service to stop", t)
            }
        }
    }
}
