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
interface DriveFolderBackupEntryPoint {
    fun driveFolderBackup(): DriveFolderBackup
}

private fun folders(context: Context) = EntryPointAccessors.fromApplication(context, DriveFolderBackupEntryPoint::class.java).driveFolderBackup()

/** One pass of the folder backup over every folder, with a progress notification while it runs. */
class DriveFolderBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = DriveFolderBackupWork.foreground(applicationContext, null, 0, 0)

    override suspend fun doWork(): Result {
        val b = folders(applicationContext)
        if (!b.active()) return Result.success()
        var lastAt = 0L
        val outcome = b.run { s ->
            val now = System.currentTimeMillis()
            if (now - lastAt < 1_000) return@run
            lastAt = now
            // A run started in the background may not show itself; it then goes on as normal work.
            runCatching { setForegroundAsync(DriveFolderBackupWork.foreground(applicationContext, s.folder, s.done, s.total)) }
        }
        return when (outcome) {
            DriveFolderBackup.Outcome.DONE, DriveFolderBackup.Outcome.STOPPED -> Result.success()
            DriveFolderBackup.Outcome.RETRY -> if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_RUNS = 8
    }
}

/** Waits for a change in the media store (WhatsApp, downloads and documents are in it), then starts a pass. */
class DriveFolderBackupTrigger(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (folders(applicationContext).active()) {
            DriveFolderBackupWork.runNow(applicationContext)
            DriveFolderBackupWork.watch(applicationContext, again = true)
        }
        return Result.success()
    }
}

/** Scheduling of the folder backup: every hour, after changes, and "Jetzt sichern" — under the conditions set. */
object DriveFolderBackupWork {
    private const val RUN = "drive-folders-run"
    private const val PERIODIC = "drive-folders-periodic"
    private const val TRIGGER = "drive-folders-trigger"
    private const val CHANNEL = "drive_backup"
    private const val NOTIFICATION_ID = 0x6E000002

    private fun constraints(s: DriveFolderSettings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(s.chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    /** After a change of the folders or the settings: the work as they say, or none. */
    fun apply(context: Context, active: Boolean, settings: DriveFolderSettings) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!active) {
                wm.cancelUniqueWork(RUN)
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(TRIGGER)
                return
            }
            val periodic = PeriodicWorkRequestBuilder<DriveFolderBackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(settings)).build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            watch(context, again = false)
            // A pass waiting under the old conditions (e.g. for WLAN) gets the new ones.
            enqueueRun(context, settings, ExistingWorkPolicy.REPLACE)
        }.onFailure { Timber.w("Drive folders: not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** A pass now (waits for WLAN / charging as set). */
    fun runNow(context: Context) {
        runCatching {
            val b = folders(context)
            if (b.active()) enqueueRun(context, b.settings.value, ExistingWorkPolicy.KEEP)
        }.onFailure { Timber.w("Drive folders: run not scheduled (%s)", it.javaClass.simpleName) }
    }

    private fun enqueueRun(context: Context, settings: DriveFolderSettings, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DriveFolderBackupWorker>()
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, request)
    }

    /** Waits for a change in the media store; from the trigger itself ([again]) the next wait is appended. */
    fun watch(context: Context, again: Boolean) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveFolderBackupTrigger>()
                .setConstraints(
                    Constraints.Builder()
                        .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                        .setTriggerContentUpdateDelay(1, TimeUnit.MINUTES)
                        .setTriggerContentMaxDelay(10, TimeUnit.MINUTES)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TRIGGER, if (again) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Drive folders: watch not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** On app start: the work is there while a folder is backed up (it may have been lost). */
    fun ensure(context: Context) {
        val b = folders(context)
        if (!b.active()) return
        runCatching {
            val periodic = PeriodicWorkRequestBuilder<DriveFolderBackupWorker>(1, TimeUnit.HOURS).setConstraints(constraints(b.settings.value)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            watch(context, again = false)
        }
    }

    fun foreground(context: Context, folder: String?, done: Int, total: Int): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(context, folder, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(context: Context, folder: String?, done: Int, total: Int): Notification {
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
            .setContentTitle(folder?.let { context.getString(R.string.drive_folders_notification_folder, it) } ?: context.getString(R.string.drive_folders_title))
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
