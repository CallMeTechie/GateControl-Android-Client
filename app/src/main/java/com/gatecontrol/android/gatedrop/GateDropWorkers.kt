package com.gatecontrol.android.gatedrop

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GateDropEntryPoint {
    fun gateDropRepository(): GateDropRepository
    fun gateDropNotifier(): GateDropNotifier
}

private fun deps(context: Context) = EntryPointAccessors.fromApplication(context, GateDropEntryPoint::class.java)

/**
 * Sends one GateDrop job (upload / direct / server fallback) in the
 * background with a progress notification; survives the app being closed
 * and retries with a network.
 */
class GateDropSendWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val jobId: String get() = inputData.getString(KEY_JOB).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = deps(applicationContext).gateDropNotifier().progress(jobId, sending = true, title = null, percent = null, cancel = null)
        return ForegroundInfo(GateDropNotifier.progressId(jobId), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val d = deps(applicationContext)
        val repo = d.gateDropRepository()
        val notifier = d.gateDropNotifier()
        val job = repo.store.loadJob(jobId) ?: return Result.failure()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val names = job.files.take(2).joinToString(", ") { it.name }.ifBlank { null }
        var lastPercent: Int? = -1
        var lastAt = 0L
        suspend fun foreground(percent: Int?) {
            val now = System.currentTimeMillis()
            if (percent == lastPercent || now - lastAt < 700) return
            lastPercent = percent
            lastAt = now
            runCatching {
                setForeground(ForegroundInfo(GateDropNotifier.progressId(jobId), notifier.progress(jobId, true, names, percent, cancel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
        }
        foreground(null)
        val progress = MutableStateFlow<Int?>(null)
        val outcome = try {
            coroutineScope {
                val ticker = launch { progress.collect { foreground(it) } }
                repo.runJob(job) { _, p -> progress.value = p }.also { ticker.cancel() }
            }
        } catch (e: CancellationException) {
            repo.setJobState(jobId, GdJobState.Failed("cancelled"))
            throw e
        }
        return when (outcome) {
            is GdJobOutcome.Done -> {
                repo.setJobState(jobId, GdJobState.Done(outcome.sendId, outcome.link))
                repo.store.dropJob(jobId)
                notifier.cancel(GateDropNotifier.progressId(jobId))
                if (outcome.link != null) notifier.linkReady(jobId, outcome.link) else notifier.sent(jobId, inputData.getString(KEY_NAMES))
                Result.success()
            }
            is GdJobOutcome.Retry -> if (runAttemptCount >= MAX_RUNS) {
                giveUp(repo, notifier, outcome.code)
            } else {
                repo.setJobState(jobId, GdJobState.Uploading(null))
                Result.retry()
            }
            is GdJobOutcome.Failed -> giveUp(repo, notifier, outcome.code)
        }
    }

    private suspend fun giveUp(repo: GateDropRepository, notifier: GateDropNotifier, code: String): Result {
        Timber.w("GateDrop: send given up (%s)", code)
        repo.setJobState(jobId, GdJobState.Failed(code))
        repo.store.dropJob(jobId)
        notifier.cancel(GateDropNotifier.progressId(jobId))
        notifier.sendFailed(jobId, null)
        return Result.failure()
    }

    companion object {
        private const val KEY_JOB = "job"
        private const val KEY_NAMES = "names"
        private const val MAX_RUNS = 12

        fun enqueue(context: Context, jobId: String, targetNames: String?): java.util.UUID? = runCatching {
            val request = OneTimeWorkRequestBuilder<GateDropSendWorker>()
                .setInputData(workDataOf(KEY_JOB to jobId, KEY_NAMES to targetNames?.take(200)))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("gatedrop-send-$jobId", ExistingWorkPolicy.KEEP, request)
            request.id
        }.onFailure { Timber.w("GateDrop: send not scheduled (%s)", it.javaClass.simpleName) }.getOrNull()
    }
}

/**
 * Receives in the background with a progress notification: a direct
 * transfer offered by a silent push, or a send delivered over the server
 * (save its files to Downloads/GateControl).
 */
class GateDropReceiveWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val key: String get() = inputData.getString(KEY_TRANSFER) ?: ("s:" + inputData.getString(KEY_SEND).orEmpty())

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = deps(applicationContext).gateDropNotifier().progress(key, sending = false, title = null, percent = null, cancel = null)
        return ForegroundInfo(GateDropNotifier.progressId(key), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val d = deps(applicationContext)
        val repo = d.gateDropRepository()
        val notifier = d.gateDropNotifier()
        var lastAt = 0L
        suspend fun foreground(title: String?, percent: Int?) {
            val now = System.currentTimeMillis()
            if (now - lastAt < 700) return
            lastAt = now
            runCatching {
                setForeground(ForegroundInfo(GateDropNotifier.progressId(key), notifier.progress(key, false, title, percent, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            }
        }
        try {
            val transfer = inputData.getString(KEY_TRANSFER)
            if (transfer != null) {
                val file = offerFile(applicationContext, transfer.removePrefix("t:"))
                val offer = runCatching { GateDropJson.offer(com.google.gson.JsonParser.parseString(file.readText()).asJsonObject) }.getOrNull()
                file.delete()
                offer ?: return Result.failure()
                foreground(offer.from, null)
                val progress = MutableStateFlow<Int?>(null)
                coroutineScope {
                    val ticker = launch { progress.collect { foreground(offer.from, it) } }
                    repo.receiveOffer(offer) { progress.value = it }
                    ticker.cancel()
                }
                return Result.success()
            }
            val sendId = inputData.getString(KEY_SEND) ?: return Result.failure()
            foreground(null, null)
            val progress = MutableStateFlow<Int?>(null)
            val ok = coroutineScope {
                val ticker = launch { progress.collect { foreground(null, it) } }
                repo.fetchSend(
                sendId = sendId,
                save = inputData.getBoolean(KEY_SAVE, false),
                autoSave = inputData.getBoolean(KEY_AUTO, false),
                show = inputData.getBoolean(KEY_SHOW, true),
                notificationId = inputData.getInt(KEY_NOTIFICATION, 0).takeIf { it != 0 },
                messageId = inputData.getLong(KEY_MESSAGE, 0L).takeIf { it > 0 },
                quiet = inputData.getBoolean(KEY_QUIET, false),
                onProgress = { progress.value = it },
                ).also { ticker.cancel() }
            }
            return if (ok || runAttemptCount >= 5) Result.success() else Result.retry()
        } finally {
            notifier.cancel(GateDropNotifier.progressId(key))
        }
    }

    companion object {
        private const val KEY_TRANSFER = "transfer"
        private const val KEY_SEND = "send"
        private const val KEY_SAVE = "save"
        private const val KEY_AUTO = "auto"
        private const val KEY_SHOW = "show"
        private const val KEY_NOTIFICATION = "notification"
        private const val KEY_MESSAGE = "message"
        private const val KEY_QUIET = "quiet"

        /** The offer (it holds the key) waits in app-private storage, never in WorkManager's database. */
        private fun offerFile(context: Context, transferId: String): File =
            File(context.noBackupFilesDir, "gatedrop/offers").apply { mkdirs() }.let { File(it, "$transferId.json") }

        /** A direct transfer to receive ([raw] = the push's `gatedrop` object). */
        fun enqueueOffer(context: Context, offer: GdTransferOffer, raw: String) {
            runCatching {
                offerFile(context, offer.transferId).writeText(raw)
                val request = OneTimeWorkRequestBuilder<GateDropReceiveWorker>()
                    .setInputData(workDataOf(KEY_TRANSFER to "t:${offer.transferId}"))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork("gatedrop-recv-${offer.transferId}", ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("GateDrop: receive not scheduled (%s)", it.javaClass.simpleName) }
        }

        /** A send over the server: learn it, save its files when [save] (or [autoSave] allows). */
        fun enqueueSend(
            context: Context,
            sendId: String,
            save: Boolean,
            autoSave: Boolean,
            show: Boolean,
            notificationId: Int? = null,
            messageId: Long? = null,
            quiet: Boolean = false,
        ) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<GateDropReceiveWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_SEND to sendId,
                            KEY_SAVE to save,
                            KEY_AUTO to autoSave,
                            KEY_SHOW to show,
                            KEY_NOTIFICATION to (notificationId ?: 0),
                            KEY_MESSAGE to (messageId ?: 0L),
                            KEY_QUIET to quiet,
                        ),
                    )
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "gatedrop-fetch-$sendId",
                    if (save) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { Timber.w("GateDrop: fetch not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}
