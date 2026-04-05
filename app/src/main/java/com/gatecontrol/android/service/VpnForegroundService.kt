package com.gatecontrol.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
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

class VpnForegroundService : VpnService() {

    companion object {
        const val CHANNEL_ID = "vpn_status"
        const val NOTIF_ID = 1001
        const val ACTION_DISCONNECT = "com.gatecontrol.android.ACTION_DISCONNECT"
        const val EXTRA_SERVER = "server"

        // Stats extras updated from the tunnel layer
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_CONNECTED_SINCE = "connected_since"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statsJob: Job? = null

    private var serverLabel: String = ""
    private var rxBytes: Long = 0L
    private var txBytes: Long = 0L
    private var connectedSinceMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Timber.d("VpnForegroundService: disconnect action received")
            onRevoke()
            return START_NOT_STICKY
        }

        serverLabel = intent?.getStringExtra(EXTRA_SERVER) ?: ""
        rxBytes = intent?.getLongExtra(EXTRA_RX_BYTES, 0L) ?: 0L
        txBytes = intent?.getLongExtra(EXTRA_TX_BYTES, 0L) ?: 0L
        connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        startForeground(NOTIF_ID, buildNotification())
        startStatsUpdater()

        Timber.d("VpnForegroundService: started, server=$serverLabel")
        return START_STICKY
    }

    override fun onRevoke() {
        Timber.d("VpnForegroundService: revoked by system")
        statsJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsJob?.cancel()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_vpn),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_vpn)
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

        val disconnectIntent = Intent(this, VpnForegroundService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val uptimeSec = (System.currentTimeMillis() - connectedSinceMs) / 1000
        val statsText = getString(
            R.string.notif_vpn_stats,
            Formatters.formatBytes(rxBytes),
            Formatters.formatBytes(txBytes),
            Formatters.formatDuration(uptimeSec),
        )

        val title = if (serverLabel.isNotEmpty()) {
            getString(R.string.notif_vpn_connected, serverLabel)
        } else {
            getString(R.string.vpn_connected)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statsText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.vpn_disconnect),
                disconnectPending,
            )
            .build()
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(1_000)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
