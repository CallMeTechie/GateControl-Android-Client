package com.gatecontrol.android.drive.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveVaultLocalEntryPoint {
    fun jobs(): DriveVaultLocalJobs
}

/**
 * Runs the jobs of "Nur auf diesem Gerät" in the foreground (data sync) with their notification ("Ordner wird auf
 * dieses Handy verschoben · Prüfen · 4 von 6 Dateien"): the app may go to the background meanwhile. The jobs need the
 * open vault of this run of the app — a new process has nothing to run (the vault page goes on when it opens again).
 */
class DriveVaultLocalWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = DriveVaultLocalWork.foreground(applicationContext, null)

    override suspend fun doWork(): Result {
        val jobs = EntryPointAccessors.fromApplication(applicationContext, DriveVaultLocalEntryPoint::class.java).jobs()
        runCatching { setForeground(DriveVaultLocalWork.foreground(applicationContext, null)) }
        var lastAt = 0L
        jobs.drain { p ->
            val now = System.currentTimeMillis()
            if (now - lastAt < 1_000 && p.running) return@drain
            lastAt = now
            runCatching { setForegroundAsync(DriveVaultLocalWork.foreground(applicationContext, p)) }
        }
        return Result.success()
    }
}

object DriveVaultLocalWork {
    private const val NAME = "drive-vault-local"
    private const val CHANNEL = "drive_vault_local"
    private const val NOTIFICATION_ID = 0x6E000020

    fun start(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveVaultLocalWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }.onFailure { Timber.w("Drive vault local: work not scheduled (%s)", it.javaClass.simpleName) }
    }

    fun foreground(context: Context, p: DriveVaultLocalJobs.Progress?): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(context, p), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** The title and the line of the notification (the folder's name never: it is a vault's). */
    fun text(context: Context, p: DriveVaultLocalJobs.Progress?): Pair<String, String?> {
        if (p == null) return context.getString(R.string.drive_vault_local_notify_move) to null
        val title = context.getString(if (p.kind == DriveVaultLocalJobs.Kind.MOVE) R.string.drive_vault_local_notify_move else R.string.drive_vault_local_notify_back)
        val line = when {
            p.total == 0 -> null
            p.step == DriveVaultLocalJobs.Step.FINISH -> context.getString(if (p.kind == DriveVaultLocalJobs.Kind.MOVE) R.string.drive_vault_local_step_finish else R.string.drive_vault_local_step_finish_back)
            p.step == DriveVaultLocalJobs.Step.CHECK -> context.resources.getQuantityString(R.plurals.drive_vault_local_notify_check, p.total, p.checked, p.total)
            else -> context.resources.getQuantityString(R.plurals.drive_vault_local_notify_fetch, p.total, p.done, p.total)
        }
        return title to line
    }

    private fun notification(context: Context, p: DriveVaultLocalJobs.Progress?): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.drive_vault_local_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val (title, line) = text(context, p)
        val total = p?.total ?: 0
        val at = if (p?.step == DriveVaultLocalJobs.Step.DOWNLOAD) p.done else p?.checked ?: 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { line?.let { setContentText(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), at, total == 0)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }
}
