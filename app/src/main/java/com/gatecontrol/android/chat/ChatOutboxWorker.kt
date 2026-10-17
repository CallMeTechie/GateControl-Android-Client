package com.gatecontrol.android.chat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.JsonElement
import com.gatecontrol.android.notify.PluginEventSink
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Sends waiting chat messages once there is a network again (survives the app being closed). */
class ChatOutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun chatRepository(): ChatRepository
    }

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).chatRepository()
        val done = repo.processOutbox(schedule = false)
        return when {
            done -> Result.success()
            runAttemptCount >= MAX_RUNS -> Result.failure()
            // Entries wait for a later attempt: WorkManager's backoff, with a network.
            else -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "chat-outbox"
        private const val MAX_RUNS = 20

        /** Runs the outbox as soon as there is a network (a job already waiting is kept). */
        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<ChatOutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Chats: outbox job not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}

/**
 * The messenger's events of the push stream (`event: plugin`) → the
 * chats; other plugins' events are ignored.
 */
@Singleton
class ChatEventSink @Inject constructor(
    private val repository: dagger.Lazy<ChatRepository>,
) : PluginEventSink {
    override fun onPluginEvent(plugin: String, type: String, data: JsonElement?) {
        if (plugin != MESSENGER_PLUGIN) return
        val event = ChatEvents.parse(plugin, type, data) ?: return
        repository.get().onEvent(event)
    }

    override fun onStreamOpen() {
        repository.get().onStreamOpen()
    }
}
