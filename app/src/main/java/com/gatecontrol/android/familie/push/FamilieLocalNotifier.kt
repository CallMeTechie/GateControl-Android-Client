package com.gatecontrol.android.familie.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.familieLocalized
import com.gatecontrol.android.familie.places.PlaceTriggerWorker
import com.gatecontrol.android.familie.places.StoredFence
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications the phone makes by itself for the Familie feature: place
 * reminders when a geofence fires (no server message involved).
 */
@Singleton
class FamilieLocalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private suspend fun localized(): Context = familieLocalized(context, settingsRepository)

    private fun ensureChannel(ctx: Context) {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLACES, ctx.getString(R.string.familie_channel_places), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    suspend fun showPlace(fence: StoredFence, transition: String, openItems: List<String>) {
        if (!canPost()) return
        val ctx = localized()
        ensureChannel(ctx)
        val fallback = ctx.getString(
            if (transition == FamiliePlace.TRIGGER_EXIT) R.string.familie_place_left else R.string.familie_place_arrived,
        )
        val text = when (fence.action) {
            FamiliePlace.ACTION_LIST -> if (openItems.isEmpty()) {
                ctx.getString(R.string.familie_place_list_empty)
            } else {
                ctx.resources.getQuantityString(
                    R.plurals.familie_place_list_items,
                    openItems.size,
                    openItems.size,
                    openItems.take(8).joinToString(", "),
                )
            }
            FamiliePlace.ACTION_TELL -> ctx.getString(R.string.familie_place_told)
            else -> fence.note.ifBlank { fallback }
        }
        val dest = PlaceTriggerWorker.destination(fence)
        val tap = PendingIntent.getActivity(
            context,
            0,
            FamilieOpenActivity.intent(context, dest).setData("gatecontrol-familie://place/${fence.placeId}".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_PLACES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(fence.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(ctx.getString(R.string.familie_channel_places))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(placeNotificationId(fence.placeId), notification)
        } catch (_: SecurityException) {
            // Notifications switched off meanwhile.
        }
    }

    /**
     * A notification button did not work (offline, request no longer open):
     * the notification says so instead of silently staying as it was.
     */
    suspend fun showActionFailed(notificationId: Int, dest: FamilieDest, detail: String? = null) {
        if (!canPost()) return
        val ctx = localized()
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            context,
            0,
            FamilieOpenActivity.intent(context, dest).setData("gatecontrol-familie://failed/$notificationId".toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = detail ?: ctx.getString(R.string.familie_action_failed_body)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_PLACES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.familie_action_failed))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }

    /** POST_NOTIFICATIONS exists from Android 13; before, posting needs no runtime permission. */
    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_PLACES = "familie_places"

        /** Below the push ids (≥ 2^30), above the fixed service ids. */
        fun placeNotificationId(placeId: Int): Int = 0x20000000 or (placeId and 0x0fffffff)
    }
}
