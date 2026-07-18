package me.danielstiner.dumble.telecom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * Minimal foreground service that carries the ongoing-call CallStyle notification for the lifetime of
 * a Dumble call.
 *
 * core-telecom 1.0.0 does NOT run a foreground service or post the notification itself — it only grants
 * the process foreground *procstate* via the platform call. But Android rejects a CallStyle
 * notification unless the posting app has a foreground service (or a user-initiated job, or a
 * fullScreenIntent), and background microphone capture on API 34+ requires an FGS of type microphone.
 * This service fills both roles, replacing the foreground role the old DumbleConnectionService used to
 * provide before the core-telecom migration — without bringing back the ConnectionService/PhoneAccount
 * machinery.
 *
 * [CallManager] drives the lifecycle: [start] (idempotent — also used to refresh the notification with
 * the chronometer anchor once Mumble synchronizes) and [stop].
 */
class CallForegroundService : Service() {

    // Lazily initialized on the first onStartCommand; its init creates the notification channels the
    // notification below depends on.
    private val notifications by lazy { CallNotificationManager(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: DEFAULT_CALLER
        val connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE_MS, 0L)?.takeIf { it > 0L }
        // The system crashes the process if we don't call startForeground within ~5s of
        // startForegroundService, so guarantee a valid notification even if the CallStyle build fails.
        val notification = try {
            notifications.createNotification(callerName, isIncoming = false, connectedSinceMs = connectedSinceMs)
        } catch (t: Throwable) {
            Log.e(TAG, "notification build failed; using fallback", t)
            notifications.createFallbackNotification()
        }
        try {
            ServiceCompat.startForeground(
                this,
                CallNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException if we somehow raced into the background.
            // Better to drop the (redundant) service than to hard-crash; the call keeps running on the
            // platform-granted procstate.
            Log.e(TAG, "startForeground failed; stopping service", t)
            stopSelf()
        }
        // Don't resurrect an empty service (with no call) if the process is killed.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallFgService"
        private const val DEFAULT_CALLER = "Dumble User"
        private const val EXTRA_CALLER_NAME = "caller_name"
        private const val EXTRA_CONNECTED_SINCE_MS = "connected_since_ms"

        /** Start the call FGS, or refresh its notification (e.g. with the chronometer anchor). Idempotent. */
        fun start(context: Context, callerName: String = DEFAULT_CALLER, connectedSinceMs: Long? = null) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                putExtra(EXTRA_CALLER_NAME, callerName)
                if (connectedSinceMs != null) putExtra(EXTRA_CONNECTED_SINCE_MS, connectedSinceMs)
            }
            context.startForegroundService(intent)
        }

        /** Stop the call FGS and remove its notification. Safe to call if never started (no-op). */
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
