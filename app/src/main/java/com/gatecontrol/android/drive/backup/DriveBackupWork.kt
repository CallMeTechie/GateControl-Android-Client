package com.gatecontrol.android.drive.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
interface DriveBackupEntryPoint {
    fun driveBackup(): DriveBackup
}

private fun backup(context: Context) = EntryPointAccessors.fromApplication(context, DriveBackupEntryPoint::class.java).driveBackup()

/** One pass of the photo backup, with a progress notification while it runs. */
class DriveBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = DriveBackupWork.foreground(applicationContext, 0, 0)

    override suspend fun doWork(): Result {
        val b = backup(applicationContext)
        if (!b.config.value.enabled) return Result.success()
        var lastAt = 0L
        val outcome = b.run { s ->
            val now = System.currentTimeMillis()
            if (now - lastAt < 1_000) return@run
            lastAt = now
            // A run started in the background may not show itself; it then goes on as normal work.
            runCatching { setForegroundAsync(DriveBackupWork.foreground(applicationContext, s.done, s.total)) }
        }
        return when (outcome) {
            DriveBackup.Outcome.DONE, DriveBackup.Outcome.STOPPED -> Result.success()
            DriveBackup.Outcome.RETRY -> if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_RUNS = 8
    }
}

/** Waits for new photos in the media store, then starts a pass and waits again. */
class DriveBackupTrigger(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val b = backup(applicationContext)
        if (b.config.value.enabled) {
            DriveBackupWork.runNow(applicationContext)
            DriveBackupWork.watch(applicationContext, again = true)
        }
        return Result.success()
    }
}

/** Scheduling of the photo backup. */
object DriveBackupWork {
    private const val RUN = "drive-backup-run"
    private const val PERIODIC = "drive-backup-periodic"
    private const val TRIGGER = "drive-backup-trigger"
    private const val CHANNEL = "drive_backup"
    private const val NOTIFICATION_ID = 0x6E000001

    // its own "Nur im WLAN" and Drive's "Hochladen nur im WLAN" (Drive-Einstellungen, for every upload)
    private fun constraints(context: Context, cfg: DriveBackupConfig) = Constraints.Builder()
        .setRequiredNetworkType(if (cfg.wifiOnly || com.gatecontrol.android.drive.DriveSettings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(cfg.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    /** After a change of the settings: the work as they say, or none. */
    fun apply(context: Context, cfg: DriveBackupConfig) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!cfg.enabled) {
                wm.cancelUniqueWork(RUN)
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(TRIGGER)
                return
            }
            val periodic = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(context, cfg))
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            watch(context, again = false)
            // A pass waiting under the old conditions (e.g. for WLAN) gets the new ones.
            enqueueRun(context, cfg, ExistingWorkPolicy.REPLACE)
        }.onFailure { Timber.w("Drive backup: not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** A pass now (waits for WLAN / charging as set). */
    fun runNow(context: Context) {
        runCatching {
            val cfg = backup(context).config.value
            if (cfg.enabled) enqueueRun(context, cfg, ExistingWorkPolicy.KEEP)
        }.onFailure { Timber.w("Drive backup: run not scheduled (%s)", it.javaClass.simpleName) }
    }

    private fun enqueueRun(context: Context, cfg: DriveBackupConfig, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(constraints(context, cfg))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, request)
    }

    /**
     * Waits for a change in the photos (and videos). From the trigger itself ([again]) the next wait is appended
     * after it; from elsewhere an existing wait is kept.
     */
    fun watch(context: Context, again: Boolean) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveBackupTrigger>()
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                        .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                        .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
                        .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TRIGGER, if (again) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Drive backup: watch not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** On app start: the work is there when the backup is on (it may have been lost). */
    fun ensure(context: Context) {
        val cfg = backup(context).config.value
        if (!cfg.enabled) return
        runCatching {
            val periodic = PeriodicWorkRequestBuilder<DriveBackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(context, cfg)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            watch(context, again = false)
        }
    }

    fun foreground(context: Context, done: Int, total: Int): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(context, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(context: Context, done: Int, total: Int): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.drive_backup_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.drive_backup_title))
            .apply { if (total > 0) setContentText(context.getString(R.string.drive_backup_progress_short, done + 1, total)) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(0), done.coerceAtMost(total), total == 0)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }
}
