package com.gatecontrol.android.media.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.R
import com.gatecontrol.android.media.MediaPlayerLogic
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaOfflineEntryPoint {
    fun mediaOffline(): MediaOffline
}

private fun offline(context: Context) = EntryPointAccessors.fromApplication(context, MediaOfflineEntryPoint::class.java).mediaOffline()

/** Loads the waiting downloads, only on Wi-Fi, with a notification of the progress while it runs. */
class MediaOfflineWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = MediaOfflineWork.foreground(applicationContext, null)

    override suspend fun doWork(): Result {
        val o = offline(applicationContext)
        var lastAt = 0L
        val outcome = o.downloader().runAll { item ->
            val now = System.currentTimeMillis()
            if (now - lastAt < 1_000 && item.state == OfflineState.LOADING) return@runAll
            lastAt = now
            // a run started in the background may not show itself; it then goes on as normal work
            runCatching { setForegroundAsync(MediaOfflineWork.foreground(applicationContext, item)) }
        }
        return when (outcome) {
            MediaOfflineDownloader.Outcome.DONE -> Result.success()
            MediaOfflineDownloader.Outcome.RETRY -> if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_RUNS = 20
    }
}

/** Tells Jellyfin what was watched offline, at the next contact. */
class MediaOfflineSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        if (offline(applicationContext).sync()) Result.success() else if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()

    private companion object {
        const val MAX_RUNS = 12
    }
}

/** Scheduling of the downloads and of the progress watched offline. */
object MediaOfflineWork {
    private const val DOWNLOAD = "media-offline-download"
    private const val SYNC = "media-offline-sync"
    private const val CHANNEL = "media_offline"
    private const val NOTIFICATION_ID = 0x6E000010

    /** The downloads (question 22 a: only on Wi-Fi; waits for it, and for free room). */
    fun download(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<MediaOfflineWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Medien offline: download not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** The progress to Jellyfin once any network is there (the server is asked; away through the tunnel). */
    fun sync(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<MediaOfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.REPLACE, request)
        }.onFailure { Timber.w("Medien offline: sync not scheduled (%s)", it.javaClass.simpleName) }
    }

    fun foreground(context: Context, item: OfflineItem?): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification(context, item), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(context: Context, item: OfflineItem?): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.media_offline_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val percent = item?.let { (it.progress * 100).toInt() } ?: 0
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.media_offline_notification))
            .apply { item?.let { setContentText(MediaPlayerLogic.name(it.title)) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent, item == null || item.expected <= 0)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }
}
