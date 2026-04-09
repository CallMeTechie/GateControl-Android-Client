package com.gatecontrol.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class RdpSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "rdp_session"
        const val NOTIF_ID = 1002
        const val ACTION_DISCONNECT = "com.gatecontrol.android.ACTION_RDP_DISCONNECT"
        const val EXTRA_ROUTE_NAME = "route_name"
        const val EXTRA_CONNECTED_SINCE = "connected_since"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    private var routeName: String = ""
    private var connectedSinceMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Timber.d("RdpSessionService: disconnect action received")
            stopSelf()
            return START_NOT_STICKY
        }

        routeName = intent?.getStringExtra(EXTRA_ROUTE_NAME) ?: ""
        connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        startForeground(NOTIF_ID, buildNotification())
        startDurationUpdater()

        Timber.d("RdpSessionService: started, route=$routeName")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_rdp),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_rdp)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, RdpSessionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 2, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val uptimeSec = (System.currentTimeMillis() - connectedSinceMs) / 1000
        val title = getString(R.string.notif_rdp_session, routeName)
        val body = getString(R.string.notif_rdp_duration, Formatters.formatDuration(uptimeSec))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.notif_rdp_disconnect),
                disconnectPending,
            )
            .build()
    }

    private fun startDurationUpdater() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                delay(1_000)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
