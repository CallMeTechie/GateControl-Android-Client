package com.gatecontrol.android.drive.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveKi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveSettings
import com.gatecontrol.android.drive.work.DriveWorkLabeler
import com.gatecontrol.android.familie.PluginDirectory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Fotos nach Inhalt finden" (Drive 1.14, D3, feature `photoSearch`): what is in a photo — found on the phone by ML Kit's
 * image labeling ([DriveWorkLabeler], the same as for the work photos; no picture leaves the phone for it) — goes to
 * Drive as words (`PUT nodes/<id>/labels`, ML Kit's English labels; Drive knows them in German too). A photo gets them
 * when the backup sends it ([afterBackup]); photos already in Drive without them (sent before, or from the PC) get them
 * later from their preview ([backfill], in the background under the backup's conditions, a few hundred a run). The
 * photos this phone labeled are remembered, so none is labeled twice.
 */
@Singleton
class DrivePhotoLabels @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val live: DriveLive,
    private val labeler: DriveWorkLabeler,
    private val setup: SetupRepository,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val file = File(context.filesDir, FILE)
    @Volatile private var done: MutableSet<Long>? = null

    private fun owner() = PluginDirectory.ownerKey(setup.getServerUrl(), setup.getApiToken())

    /** The ids of the photos labeled for this login (read once). */
    private fun doneSet(): MutableSet<Long> {
        done?.let { return it }
        val set: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        if (prefs.getString(KEY_OWNER, null) == owner()) {
            runCatching { file.forEachLine { l -> l.trim().toLongOrNull()?.let(set::add) } }
        } else {
            file.delete()
            prefs.edit {
                clear()
                putString(KEY_OWNER, owner())
            }
        }
        done = set
        return set
    }

    private fun remember(id: Long) {
        val set = doneSet()
        if (set.add(id)) runCatching { file.appendText("$id\n") }
    }

    private suspend fun enabled(): Boolean = live.supports(DMe.FEATURE_PHOTO_SEARCH)

    /** The photo [node] the backup just sent from [uri] gets its labels (best effort). */
    suspend fun afterBackup(node: DNode, uri: Uri, video: Boolean) {
        if (video || node.encrypted || !enabled()) return
        val found = labeler.namesOfUri(uri) ?: return
        send(node.id, found)
    }

    private suspend fun send(id: Long, found: List<Pair<String, Float>>): Boolean = lock.withLock {
        val labels = DriveKi.labels(found)
        // nothing sure enough: an empty list says "looked at" all the same
        when (val r = api.putLabels(id, labels)) {
            is DResult.Ok -> { remember(id); true }
            // gone, not mine to change, a vault, no such feature: never again
            is DResult.Failed -> { if (r.code in FINAL) remember(id); r.code in FINAL }
            else -> false
        }
    }

    enum class Outcome { DONE, MORE, RETRY }

    /**
     * Labels up to [budget] photos in Drive that this phone has not labeled yet, from their previews, going on where the
     * last run stopped; a whole pass through the photos comes at most once a day.
     */
    suspend fun backfill(budget: Int = BUDGET): Outcome {
        if (!enabled()) return Outcome.DONE
        doneSet()
        var cursor = prefs.getString(KEY_CURSOR, null)
        var labeled = 0
        var pages = 0
        while (labeled < budget && pages < MAX_PAGES) {
            val r = api.photos(before = cursor, limit = PAGE)
            val page = when (r) {
                is DResult.Ok -> r.value
                DResult.Offline -> return Outcome.RETRY
                else -> return Outcome.DONE
            }
            pages++
            for (n in page.items) {
                if (labeled >= budget) break
                if (n.id in doneSet() || n.encrypted || !n.preview || DriveLogic.kind(n) != DriveLogic.Kind.IMAGE) continue
                val found = try {
                    labeler.namesOfNode(n)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (found == null) {
                    // no preview to read: skipped for good
                    remember(n.id)
                    continue
                }
                if (!send(n.id, found)) return Outcome.RETRY
                labeled++
            }
            if (labeled >= budget) break
            cursor = page.next
            prefs.edit { putString(KEY_CURSOR, cursor) }
            if (cursor == null) {
                prefs.edit { putLong(KEY_PASS_AT, System.currentTimeMillis()) }
                Timber.i("Drive labels: a pass through the photos is done (%d now)", labeled)
                return Outcome.DONE
            }
        }
        return Outcome.MORE
    }

    /** Whether a new pass is due (the last whole one is more than a day ago, or a pass is half done). */
    fun due(): Boolean {
        doneSet()
        return prefs.getString(KEY_CURSOR, null) != null || System.currentTimeMillis() - prefs.getLong(KEY_PASS_AT, 0L) > PASS_GAP_MS
    }

    companion object {
        private const val PREFS = "drive_photo_labels"
        private const val FILE = "drive_photo_labels.txt"
        private const val KEY_OWNER = "owner"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_PASS_AT = "pass_at"
        private const val PAGE = 120
        private const val MAX_PAGES = 30
        const val BUDGET = 200
        private const val PASS_GAP_MS = 24 * 3_600_000L
        private val FINAL = setOf("not_found", "forbidden", "encrypted", "invalid", "feature_off")
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DrivePhotoLabelsEntryPoint {
    fun drivePhotoLabels(): DrivePhotoLabels
    fun driveBackup(): DriveBackup
}

/** One run of [DrivePhotoLabels.backfill]; a run with more to do asks for the next one. */
class DrivePhotoLabelsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val labels = EntryPointAccessors.fromApplication(applicationContext, DrivePhotoLabelsEntryPoint::class.java).drivePhotoLabels()
        return when (labels.backfill()) {
            DrivePhotoLabels.Outcome.DONE -> Result.success()
            DrivePhotoLabels.Outcome.MORE -> Result.success().also { DrivePhotoLabelsWork.next(applicationContext) }
            DrivePhotoLabels.Outcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}

/** Scheduling of the labels' backfill: under the photo backup's conditions (WLAN, charging), quietly. */
object DrivePhotoLabelsWork {
    private const val RUN = "drive-photo-labels"

    private fun constraints(context: Context): Constraints {
        val cfg = runCatching { EntryPointAccessors.fromApplication(context, DrivePhotoLabelsEntryPoint::class.java).driveBackup().config.value }.getOrNull()
            ?: DriveBackupConfig()
        return Constraints.Builder()
            .setRequiredNetworkType(if (cfg.wifiOnly || DriveSettings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg.chargingOnly)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    /** The Photos page opened with `photoSearch`: a run when one is due (an existing one is kept). */
    fun request(context: Context) {
        runCatching {
            val labels = EntryPointAccessors.fromApplication(context, DrivePhotoLabelsEntryPoint::class.java).drivePhotoLabels()
            if (!labels.due()) return
            enqueue(context, ExistingWorkPolicy.KEEP, 0)
        }.onFailure { Timber.w("Drive labels: not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** The next part of the pass, a little later. */
    fun next(context: Context) {
        runCatching { enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, 2) }
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy, delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<DrivePhotoLabelsWorker>()
            .setConstraints(constraints(context))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, request)
    }
}
