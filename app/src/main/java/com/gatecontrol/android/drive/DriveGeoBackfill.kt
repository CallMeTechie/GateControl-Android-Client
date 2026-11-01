package com.gatecontrol.android.drive

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.drive.work.DriveWorkGeo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Orte nachtragen" (Drive 1.16, `photoPlaces`): photos backed up before Drive knew places get theirs from the original
 * still on this phone (EXIF GPS, read with "Zugriff auf Fotostandorte"). Drive lists the photos without a place where
 * one writes (`photos?unlocated=1`); a photo found here gets its position, or "none" when it has none
 * (`PUT nodes/<id>/geo`). A photo not on this phone stays for another device. Nothing with "Aufnahmeort mitsichern" off.
 */
@Singleton
class DriveGeoBackfill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val live: DriveLive,
    private val prefs: DrivePlacesPrefs,
    private val geo: DriveWorkGeo,
) {
    private val lock = Mutex()

    enum class Outcome { DONE, MORE, RETRY }

    fun canLook(): Boolean = geo.canLook()

    /** One run over up to [budget] photos of Drive's list, going on where the last one stopped. */
    suspend fun run(budget: Int = BUDGET): Outcome = lock.withLock {
        if (!live.supports(DMe.FEATURE_PHOTO_PLACES) || !prefs.settings.value.keepGeo || !geo.canLook()) return Outcome.DONE
        val key = DrivePlacesPrefs.CURSOR_GEO
        var cursor = prefs.cursor(key)
        var looked = 0
        var pages = 0
        while (looked < budget && pages < MAX_PAGES) {
            val page = when (val r = api.photos(before = cursor, limit = PAGE, filter = DPhotoFilter(unlocated = true))) {
                is DResult.Ok -> r.value
                DResult.Offline -> return Outcome.RETRY
                else -> return Outcome.DONE
            }
            pages++
            for (n in page.items) {
                currentCoroutineContext().ensureActive()
                if (n.located == true || n.geo != null) continue
                val r = when (val f = geo.find(n)) {
                    DriveWorkGeo.Found.Unknown, DriveWorkGeo.Found.NotHere -> continue
                    DriveWorkGeo.Found.NoPosition -> api.putGeo(n.id, null).also { prefs.geoLooked(false) }
                    is DriveWorkGeo.Found.At -> api.putGeo(n.id, f.at).also { prefs.geoLooked(true) }
                }
                if (r == DResult.Offline) return Outcome.RETRY
                looked++
            }
            cursor = page.next
            prefs.setCursor(key, cursor)
            if (cursor == null) {
                Timber.i("Drive places: a pass through the photos is done")
                return Outcome.DONE
            }
        }
        return Outcome.MORE
    }

    companion object {
        const val BUDGET = 400
        private const val PAGE = 150
        private const val MAX_PAGES = 30
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DriveGeoBackfillEntryPoint {
    fun driveGeoBackfill(): DriveGeoBackfill
    fun drivePlacesPrefs(): DrivePlacesPrefs
}

class DriveGeoBackfillWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, DriveGeoBackfillEntryPoint::class.java)
        entry.drivePlacesPrefs().geoRunning(true, fresh = runAttemptCount == 0 && !inputData.getBoolean(KEY_GOING_ON, false))
        val outcome = try {
            entry.driveGeoBackfill().run()
        } finally {
            entry.drivePlacesPrefs().geoRunning(false)
        }
        return when (outcome) {
            DriveGeoBackfill.Outcome.DONE -> Result.success()
            DriveGeoBackfill.Outcome.MORE -> Result.success().also { DriveGeoBackfillWork.next(applicationContext) }
            DriveGeoBackfill.Outcome.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_GOING_ON = "going_on"
    }
}

/** "Orte nachtragen": started from the settings, goes on in parts until Drive's list is through. */
object DriveGeoBackfillWork {
    private const val RUN = "drive-geo-backfill"

    fun start(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE, 0, goingOn = false)

    fun next(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, 1, goingOn = true)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy, delayMinutes: Long, goingOn: Boolean) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveGeoBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (DriveSettings.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(androidx.work.workDataOf(DriveGeoBackfillWorker.KEY_GOING_ON to goingOn))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RUN, policy, request)
        }.onFailure { Timber.w("Drive places: not scheduled (%s)", it.javaClass.simpleName) }
    }
}
