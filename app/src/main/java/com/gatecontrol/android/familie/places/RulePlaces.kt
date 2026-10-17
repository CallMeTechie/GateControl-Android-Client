package com.gatecontrol.android.familie.places

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.presence.PresenceStore
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/*
 * Familie 1.5: places as triggers of the presence rules ("Marc kommt an der
 * Schule an", "500 m vor Zuhause"). Parents define the places and rules on
 * the server; each person agrees on the phone ("Orte für Regeln teilen").
 * The phone then watches only the places an active rule needs for this
 * person (`GET presence/watch`) as Play services geofences and reports only
 * "entered / left place X" (`POST presence/places/<id>/event`) — never a
 * position.
 */

/** One rule place to watch; [enter] / [exit]: which transitions a rule needs. */
data class RuleFence(
    val placeId: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Int,
    val enter: Boolean,
    val exit: Boolean,
) {
    fun watches(transition: String): Boolean = (transition == ENTER && enter) || (transition == EXIT && exit)

    companion object {
        const val ENTER = "enter"
        const val EXIT = "exit"
        const val MIN_RADIUS = 50
        const val MAX_RADIUS = 5000
    }
}

/** `GET presence/watch`. */
data class RuleWatch(val places: List<RuleFence>, val version: String?)

/**
 * My consent to place events and the rule places this phone watches: the
 * list follows the consent and the module (on → `GET presence/watch`,
 * off → no fences), and is fetched again from a periodic job, on app start
 * and whenever a presence answer carries another `watchVersion`.
 */
@Singleton
class RulePlaceWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val store: PresenceStore,
    private val registrar: GeofenceRegistrar,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val syncLock = Mutex()
    private var started = false

    /** I share place events for rules, as the server last confirmed. */
    val share: StateFlow<Boolean> = store.places

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.cachedProfile()
            combine(store.places, repository.profile) { share, profile -> share && profile?.modules?.presence == true }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) {
                        schedulePeriodic()
                        sync()
                    } else {
                        cancelPeriodic()
                        registrar.applyRules(emptyList())
                    }
                }
        }
    }

    /** "Orte für Regeln teilen" on / off (`PUT presence/consent { places }`); stored only when the server took it. */
    suspend fun setShare(share: Boolean): FamilieResult<Unit> {
        val r = api.presenceConsent(places = share)
        if (r is FamilieResult.Ok) store.setPlaces(share)
        return r
    }

    /** The server knows the place consent (Familie 1.5+): only then is the switch offered. */
    val supported: StateFlow<Boolean> = store.placesSupported

    /**
     * From `GET presence`: my consent as the server has it. null = a server
     * before 1.5 — no switch (its `PUT presence/consent` without `share`
     * would switch presence sharing off) and nothing watched.
     */
    fun consentSeen(places: Boolean?) {
        store.setPlacesSupported(places != null)
        val share = places == true
        if (share != store.places.value) store.setPlaces(share)
    }

    /** A presence answer's `watchVersion`: fetch the list again when it changed. */
    fun versionSeen(version: String?) {
        if (version != null && store.places.value && version != store.watchVersion()) scope.launch { sync() }
    }

    /** `GET presence/watch` and register what it lists (nothing without my consent or with the module off). */
    suspend fun sync(): FamilieResult<RuleWatch>? = syncLock.withLock {
        val profile = repository.profile.value ?: repository.cachedProfile()
        if (!store.places.value || profile?.modules?.presence != true) {
            registrar.applyRules(emptyList())
            return@withLock null
        }
        val r = api.presenceWatch()
        when {
            r is FamilieResult.Ok -> {
                registrar.applyRules(r.value.places)
                store.setWatchVersion(r.value.version)
            }
            // The server does not have my consent (withdrawn in the portal or on another phone): the switch goes off.
            r is FamilieResult.Failed && r.code == "no_consent" -> store.setPlaces(false)
            r is FamilieResult.Failed && (r.code == "module_off" || r.code == "not_member" || r.http == 404) -> registrar.applyRules(emptyList())
        }
        r
    }

    /** After the location permission changed: register the stored rule places again. */
    fun permissionsChanged() {
        scope.launch { registrar.reRegisterFromCache() }
    }

    private fun schedulePeriodic() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<RulePlaceSyncWorker>(SYNC_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Timber.w("Familie: rule place sync not scheduled (%s)", it.javaClass.simpleName) }
    }

    private fun cancelPeriodic() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK) }
    }

    private companion object {
        const val SYNC_WORK = "familie_rule_places_sync"
        const val SYNC_HOURS = 1L
    }
}

/** Periodic: the rule places again (a parent may have changed a rule or a place). */
class RulePlaceSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun rulePlaceWatcher(): RulePlaceWatcher
    }

    override suspend fun doWork(): Result {
        try {
            EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).rulePlaceWatcher().sync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Familie: rule place sync failed (%s)", e.javaClass.simpleName)
        }
        return Result.success()
    }
}

/** `POST presence/places/<id>/event` once there is a network (retried; a refusal is final). */
class RulePlaceEventWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun familieApi(): FamilieApi
        fun presenceStore(): PresenceStore
    }

    override suspend fun doWork(): Result {
        val placeId = inputData.getInt(KEY_PLACE, -1).takeIf { it > 0 } ?: return Result.failure()
        val transition = inputData.getString(KEY_TRANSITION)?.takeIf { it == RuleFence.ENTER || it == RuleFence.EXIT } ?: return Result.failure()
        val at = inputData.getString(KEY_AT) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        // Consent withdrawn meanwhile: nothing leaves the phone.
        if (!deps.presenceStore().places.value) return Result.success()
        return when (val r = deps.familieApi().rulePlaceEvent(placeId, transition, at)) {
            is FamilieResult.Ok -> Result.success()
            is FamilieResult.Failed -> when {
                r.code == "no_consent" -> {
                    deps.presenceStore().setPlaces(false)
                    Result.success()
                }
                r.http == 429 || r.http >= 500 -> if (runAttemptCount < 6) Result.retry() else Result.success()
                else -> Result.success()
            }
            // The server marks an event older than 10 minutes as late (no rule runs), so retrying long is pointless.
            else -> if (runAttemptCount < 6) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val KEY_PLACE = "place"
        private const val KEY_TRANSITION = "transition"
        private const val KEY_AT = "at"

        fun enqueue(context: Context, placeId: Int, transition: String, at: String) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<RulePlaceEventWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_PLACE to placeId, KEY_TRANSITION to transition, KEY_AT to at))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }.onFailure { Timber.w("Familie: rule place event not queued (%s)", it.javaClass.simpleName) }
        }
    }
}
