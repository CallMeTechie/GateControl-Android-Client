package com.gatecontrol.android.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.push.PushOpenActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The notifications of „Bildschirm freigeben“: a helper's question while the app is in the background (opens the
 * card, the answer is given there — never from the notification, the person sees the dialog with name and
 * picture) and the ongoing notification of a running sharing (built for [ScreenShareService], with „Beenden“).
 */
@Singleton
class ScreenShareNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun channels() {
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REQUEST, context.getString(R.string.screen_share_channel_request), NotificationManager.IMPORTANCE_HIGH).apply {
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, context.getString(R.string.screen_share_channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
    }

    private fun openCard(): PendingIntent = PendingIntent.getActivity(
        context, REQ_OPEN, PushOpenActivity.remoteIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun showRequest(req: ScreenRequest) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channels()
        val n = NotificationCompat.Builder(context, CHANNEL_REQUEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.screen_share_ask_title, req.fromName))
            .setContentText(context.getString(R.string.screen_share_request_tap))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openCard())
            .setAutoCancel(true)
            .setTimeoutAfter((req.expiresAt - System.currentTimeMillis()).coerceIn(1_000L, 60_000L))
            .build()
        runCatching { nm.notify(ID_REQUEST, n) }
    }

    fun cancelRequest() {
        runCatching { nm.cancel(ID_REQUEST) }
    }

    /** The ongoing notification of a running sharing: who watches (or who is awaited), „Beenden“. Not to be swiped away. */
    fun live(viewerName: String, joined: Boolean, stop: PendingIntent): Notification {
        channels()
        return NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.screen_share_notif_title))
            .setContentText(
                if (joined) context.getString(R.string.screen_share_live_bar, viewerName)
                else context.getString(R.string.screen_share_waiting_viewer, viewerName),
            )
            .setColor(LIVE_COLOR)
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openCard())
            .addAction(0, context.getString(R.string.screen_share_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_REQUEST = "remote_screen_request"
        const val CHANNEL_LIVE = "remote_screen_live"
        const val ID_REQUEST = 1031
        const val ID_LIVE = 1032
        private const val REQ_OPEN = 31
        /** The red of the bar while the screen is shared (design: „rote Leiste“). */
        private const val LIVE_COLOR = 0xFFC2410C.toInt()
    }
}
