package com.gatecontrol.android.familie.places

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.push.FamilieLocalNotifier
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingRepository
import com.gatecontrol.android.support.CrashReports
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Geofence transitions from Play services (NOT exported: only the app's
 * own mutable PendingIntent reaches it). Shows the reminder right away
 * from what the phone stored and reports the trigger to the server
 * (`POST places/<id>/triggered`) as a job that waits for a network.
 */
@AndroidEntryPoint
class GeofenceReceiver : BroadcastReceiver() {

    @Inject lateinit var registrar: GeofenceRegistrar
    @Inject lateinit var shopping: ShoppingRepository
    @Inject lateinit var notifier: FamilieLocalNotifier

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Timber.w("Familie: geofence error %d", event.errorCode)
            return
        }
        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> FamiliePlace.TRIGGER_ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> FamiliePlace.TRIGGER_EXIT
            else -> return
        }
        val placeIds = event.triggeringGeofences.orEmpty().mapNotNull { GeofenceRegistrar.placeIdOf(it.requestId) }
        if (placeIds.isEmpty()) return
        val at = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    for (placeId in placeIds) {
                        val fence = registrar.storedFence(placeId) ?: continue
                        if (fence.trigger != transition) continue
                        val openItems = if (fence.action == FamiliePlace.ACTION_LIST && fence.listId != null) {
                            ShoppingLogic.view(shopping.current()).items.filter { it.listId == fence.listId && !it.checked }.map { it.text }
                        } else {
                            emptyList()
                        }
                        notifier.showPlace(fence, transition, openItems)
                        PlaceTriggerWorker.enqueue(context, placeId, transition, at)
                        if (fence.repeat == "once") registrar.removeLocal(placeId)
                    }
                }
            } catch (e: Exception) {
                Timber.w("Familie: geofence handling failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.gatecontrol.android.familie.GEOFENCE"
        private const val TIMEOUT_MS = 8_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    }
}

/** `POST places/<id>/triggered` once there is a network (retried a few times). */
class PlaceTriggerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun familieApi(): FamilieApi
    }

    override suspend fun doWork(): Result {
        val placeId = inputData.getInt(KEY_PLACE, -1).takeIf { it > 0 } ?: return Result.failure()
        val transition = inputData.getString(KEY_TRANSITION) ?: return Result.failure()
        val at = inputData.getString(KEY_AT) ?: return Result.failure()
        val api = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).familieApi()
        return when (api.placeTriggered(placeId, transition, at)) {
            is FamilieResult.Ok -> Result.success()
            // The server refused (place gone, consent withdrawn): nothing to retry.
            is FamilieResult.Failed -> Result.success()
            else -> if (runAttemptCount < 6) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_PLACE = "place"
        private const val KEY_TRANSITION = "transition"
        private const val KEY_AT = "at"

        fun enqueue(context: Context, placeId: Int, transition: String, at: String) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<PlaceTriggerWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_PLACE to placeId, KEY_TRANSITION to transition, KEY_AT to at))
                    .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }.onFailure { Timber.w("Familie: trigger not queued (%s)", it.javaClass.simpleName) }
        }

        /** Where a tap on the reminder leads. */
        fun destination(fence: StoredFence): FamilieDest =
            if (fence.action == FamiliePlace.ACTION_LIST) FamilieDest.Shopping(fence.listId) else FamilieDest.Places
    }
}
