package com.gatecontrol.android.drive.faces

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DPhotoFilter
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DrivePlacesPrefs
import com.gatecontrol.android.drive.DrivePreviews
import com.gatecontrol.android.drive.DriveNodeResolver
import com.gatecontrol.android.drive.work.DriveWorkGeo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Personen erkennen" (Drive 1.16, feature `photoPeople`, only while the person switched it on): this phone finds the
 * faces of photos and sends their features to the own server (`PUT nodes/<id>/faces`), which groups them into people.
 * New photos right after the backup sent them (the original on the phone, [DrivePlacesPrefs.pending]); older ones from
 * Drive's list of photos no phone has looked at for this person and model (`photos?unfaced=1`) — the original when it
 * is still on the phone, else the large preview from the own server. Runs as a WorkManager job, in WLAN and, by
 * default, only while charging; a few hundred pictures a run.
 */
@Singleton
class DrivePeople @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val live: DriveLive,
    private val prefs: DrivePlacesPrefs,
    private val engine: DriveFaceEngine,
    private val previews: DrivePreviews,
    private val resolver: DriveNodeResolver,
    private val geo: DriveWorkGeo,
    private val model: DriveFaceModel,
) {
    private val lock = Mutex()

    enum class Outcome { DONE, MORE, RETRY }

    /** Whether faces are to be found on this phone now (the feature, the person's switch). */
    suspend fun active(): Boolean = live.supports(DMe.FEATURE_PHOTO_PEOPLE) && prefs.settings.value.people

    /** The switches as the server has them now (the job must not work on an old "on"). */
    private suspend fun refresh(): Boolean {
        if (!live.supports(DMe.FEATURE_PHOTO_PEOPLE)) return false
        api.photoSettings().valueOrNull()?.let(prefs::took)
        return prefs.settings.value.people
    }

    /** One run: the photos just backed up, then up to [budget] older ones. */
    suspend fun run(budget: Int = BUDGET): Outcome = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!refresh()) return@withContext Outcome.DONE
            // the feature model first (loaded once; its job starts this one again when it is here)
            if (!model.ready()) return@withContext Outcome.DONE.also { DriveFaceModelWork.start(context) }
            val models = engine.open() ?: return@withContext Outcome.DONE
            try {
                pass(models, budget)
            } finally {
                models.close()
            }
        }
    }

    private suspend fun pass(models: DriveFaceEngine.Models, budget: Int): Outcome {
        var done = 0
        // 1. what the backup just sent: the original is on the phone
        for (p in prefs.pending()) {
            currentCoroutineContext().ensureActive()
            if (done >= budget) return Outcome.MORE
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.mediaId)
            val bitmap = engine.decode(uri)
            if (bitmap == null) {
                prefs.donePending(p.nodeId)
                continue
            }
            val faces = try {
                models.faces(bitmap)
            } catch (e: Exception) {
                Timber.d("Drive faces: not looked at (%s)", e.javaClass.simpleName)
                null
            } finally {
                bitmap.recycle()
            }
            if (faces == null) {
                prefs.donePending(p.nodeId)
                continue
            }
            when (val r = api.putFaces(p.nodeId, DriveFacesLogic.MODEL, faces)) {
                is DResult.Ok -> prefs.donePending(p.nodeId)
                DResult.Offline -> return Outcome.RETRY
                is DResult.Failed -> {
                    // switched off meanwhile (not just a child's folder, which is left out)
                    if (off(r)) return Outcome.DONE.also { switchedOff() }
                    prefs.donePending(p.nodeId)
                }
                else -> return Outcome.DONE
            }
            done++
        }
        // 2. what no phone has looked at yet (one pass at most once a day)
        val pass = DrivePlacesPrefs.CURSOR_FACES
        var cursor = prefs.cursor(pass)
        if (cursor == null && System.currentTimeMillis() - prefs.passAt(pass) < PASS_GAP_MS && prefs.passAt(pass) > 0) return Outcome.DONE
        var pages = 0
        while (done < budget && pages < MAX_PAGES) {
            val page = when (val r = api.photos(before = cursor, limit = PAGE, filter = DPhotoFilter(unfacedModel = DriveFacesLogic.MODEL))) {
                is DResult.Ok -> r.value
                DResult.Offline -> return Outcome.RETRY
                else -> return Outcome.DONE
            }
            pages++
            for (n in page.items) {
                currentCoroutineContext().ensureActive()
                if (done >= budget) return Outcome.MORE
                if (n.encrypted || DriveLogic.kind(n) != DriveLogic.Kind.IMAGE) continue
                val faces = facesOf(models, n) ?: continue
                when (val r = api.putFaces(n.id, DriveFacesLogic.MODEL, faces)) {
                    is DResult.Ok -> Unit
                    DResult.Offline -> return Outcome.RETRY
                    is DResult.Failed -> if (off(r)) return Outcome.DONE.also { switchedOff() }
                    else -> return Outcome.DONE
                }
                done++
            }
            cursor = page.next
            prefs.setCursor(pass, cursor)
            if (cursor == null) {
                prefs.setPassAt(pass, System.currentTimeMillis())
                Timber.i("Drive faces: a pass through the photos is done (%d now)", done)
                return Outcome.DONE
            }
        }
        return Outcome.MORE
    }

    /** Switched off elsewhere (another device, the portal): no faces any more, the model goes too. */
    private fun switchedOff() {
        prefs.took(com.gatecontrol.android.drive.DPhotoSettings(people = false))
        model.delete()
    }

    /** `403 feature_off`: "Personen erkennen" is off — but not for a picture in a child's folder (`reason: kids`). */
    private fun off(r: DResult.Failed): Boolean {
        if (r.code != FEATURE_OFF) return false
        val reason = r.body?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString
        val field = r.body?.get("field")?.takeIf { it.isJsonPrimitive }?.asString
        return reason != "kids" && field != "peopleKids"
    }

    /** The faces of a photo in Drive: from its original when it is on this phone, else from the large preview. */
    private suspend fun facesOf(models: DriveFaceEngine.Models, n: DNode): List<com.gatecontrol.android.drive.DFaceUpload>? {
        val local = try {
            withContext(Dispatchers.IO) { geo.original(n) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val bitmap = local?.let { engine.decode(it) } ?: run {
            val full = if (n.mediaId != null) n else resolver.resolve(n.id) ?: return null
            if (!full.preview) return null
            previews.load(full, large = true)
        } ?: return null
        return try {
            models.faces(bitmap)
        } catch (e: Exception) {
            Timber.d("Drive faces: not looked at (%s)", e.javaClass.simpleName)
            null
        } finally {
            // previews stay in their cache; only own decodes are freed
            if (local != null) bitmap.recycle()
        }
    }

    /** Whether there is something to do (photos just sent, or a pass due). */
    fun due(): Boolean {
        if (!prefs.settings.value.people) return false
        val pass = DrivePlacesPrefs.CURSOR_FACES
        return prefs.pending().isNotEmpty() || prefs.cursor(pass) != null || System.currentTimeMillis() - prefs.passAt(pass) > PASS_GAP_MS
    }

    companion object {
        const val BUDGET = 150
        private const val PAGE = 100
        private const val MAX_PAGES = 20
        private const val PASS_GAP_MS = 24 * 3_600_000L
        private const val FEATURE_OFF = "feature_off"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DrivePeopleEntryPoint {
    fun drivePeople(): DrivePeople
    fun drivePlacesPrefs(): DrivePlacesPrefs
}

/** One run of [DrivePeople.run]; a run with more to do asks for the next one. */
class DrivePeopleWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val people = EntryPointAccessors.fromApplication(applicationContext, DrivePeopleEntryPoint::class.java).drivePeople()
        return when (people.run()) {
            DrivePeople.Outcome.DONE -> Result.success()
            DrivePeople.Outcome.MORE -> Result.success().also { DrivePeopleWork.next(applicationContext) }
            DrivePeople.Outcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }
}

/** Scheduling of "Personen erkennen": WLAN, battery not low and — with "Nur beim Laden" (default) — charging. */
object DrivePeopleWork {
    private const val RUN = "drive-people"

    private fun constraints(chargingOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(chargingOnly)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun entry(context: Context) = EntryPointAccessors.fromApplication(context, DrivePeopleEntryPoint::class.java)

    /** A run when one is due (an existing one is kept): the Photos page opened, a backup finished. */
    fun request(context: Context) {
        runCatching {
            if (!entry(context).drivePeople().due()) return
            enqueue(context, ExistingWorkPolicy.KEEP, 0)
        }.onFailure { Timber.w("Drive faces: not scheduled (%s)", it.javaClass.simpleName) }
    }

    /** Switched on, or "Nur beim Laden" changed: a run with the conditions as they are now. */
    fun restart(context: Context) {
        runCatching { enqueue(context, ExistingWorkPolicy.REPLACE, 0) }
    }

    /** Switched off: no more runs. */
    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(RUN) }
    }

    fun next(context: Context) {
        runCatching { enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, 1) }
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy, delayMinutes: Long) {
        val charging = runCatching { entry(context).drivePlacesPrefs().settings.value.chargingOnly }.getOrDefault(true)
        val request = OneTimeWorkRequestBuilder<DrivePeopleWorker>()
            .setConstraints(constraints(charging))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, request)
    }
}
