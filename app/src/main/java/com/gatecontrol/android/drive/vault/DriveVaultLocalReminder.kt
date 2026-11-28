package com.gatecontrol.android.drive.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveVaultLocalReminderEntryPoint {
    fun store(): DriveVaultLocalStore
}

/**
 * The daily look at the folders kept only on this phone: what lies longer than 30 days in a folder's trash goes for
 * good (no key needed, nothing is decrypted), and the monthly reminder of a new export (concept 1.7, question 5):
 * whether a folder has no export, one older than a month or one older than its last change — then, at most once a
 * month, a notification "Zeit für eine neue Sicherung". Never a folder's name (it is a vault's).
 */
class DriveVaultLocalReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = EntryPointAccessors.fromApplication(applicationContext, DriveVaultLocalReminderEntryPoint::class.java).store()
        val now = System.currentTimeMillis()
        runCatching { store.purgeExpiredAll(now) }.onFailure { Timber.w("Drive vault local: trash not emptied (%s)", it.javaClass.simpleName) }
        val due = store.load().values.filter { DriveVaultLocalLogic.remindNow(it, now) }
        if (due.isNotEmpty()) {
            due.forEach { f -> runCatching { store.update(f.nodeId) { it.copy(remindedAt = now) } } }
            DriveVaultLocalReminder.notify(applicationContext, due.size)
        }
        return Result.success()
    }
}

object DriveVaultLocalReminder {
    private const val NAME = "drive-vault-local-reminder"
    private const val CHANNEL = "drive_vault_local_backup"
    private const val NOTIFICATION_ID = 0x6E000021

    /** The daily look (kept while the app runs it; nothing happens without a folder kept here). */
    fun schedule(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<DriveVaultLocalReminderWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Drive vault local: reminder not scheduled (%s)", it.javaClass.simpleName) }
    }

    fun notify(context: Context, count: Int) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.drive_vault_local_backup_channel), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.drive_vault_local_backup_title))
                .setContentText(context.resources.getQuantityString(R.plurals.drive_vault_local_backup_text, count, count))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.resources.getQuantityString(R.plurals.drive_vault_local_backup_text, count, count)))
                .setAutoCancel(true)
                .apply { open?.let { setContentIntent(it) } }
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }.onFailure { Timber.w("Drive vault local: reminder not shown (%s)", it.javaClass.simpleName) }
    }
}
