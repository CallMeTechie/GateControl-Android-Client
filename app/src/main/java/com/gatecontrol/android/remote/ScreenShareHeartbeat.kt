package com.gatecontrol.android.remote

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreenShareEntryPoint {
    fun screenShareRepository(): ScreenShareRepository
}

/**
 * While „Bildschirm freigeben“ is on, the phone reports to the plugin every 15 minutes (the plugin counts a phone
 * online for 20 minutes after its last report); the card reports every minute while it is open.
 */
class ScreenShareHeartbeatWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!ScreenShareRepository.isOn(applicationContext)) return Result.success()
        EntryPointAccessors.fromApplication(applicationContext, ScreenShareEntryPoint::class.java).screenShareRepository().heartbeat()
        return Result.success()
    }
}

object ScreenShareHeartbeat {
    private const val WORK = "remote-screen-heartbeat"

    fun keepUp(context: Context, on: Boolean) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            if (!on) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<ScreenShareHeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }.onFailure { Timber.w("Fernzugriff: heartbeat not scheduled (%s)", it.javaClass.simpleName) }
    }
}
