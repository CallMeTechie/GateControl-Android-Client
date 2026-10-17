package com.gatecontrol.android.familie.places

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gatecontrol.android.familie.FamilieApi
import com.gatecontrol.android.familie.FamiliePlace
import com.gatecontrol.android.familie.FamilieRepository
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.FamilieStorage
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.support.CrashReports
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A registered geofence as the device keeps it (for the notification when it fires, offline). */
data class StoredFence(
    val placeId: Int,
    val title: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    val radius: Int,
    val trigger: String,
    val action: String,
    val listId: String?,
    val repeat: String,
)

/**
 * Place reminders run on the phone (contract C4): the places where this
 * user is the target, active and accepted, become Play services geofences.
 * Registered on app start, after boot, after `GET places` and after any
 * `place_*` push. The phone sends no position for it — only the trigger.
 * Without Play services the places are listed but marked unavailable.
 */
@Singleton
class GeofenceRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: FamilieApi,
    private val repository: FamilieRepository,
    private val storage: FamilieStorage,
) {
    /** [rules]: the rule places (Familie 1.5) this phone watches, next to the reminders. */
    private data class Stored(val owner: String? = null, val fences: List<StoredFence>? = null, val rules: List<RuleFence>? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    fun playServicesAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    fun readiness(): FamiliePermissions.GeofenceReadiness = FamiliePermissions.geofenceReadiness(
        playServices = playServicesAvailable(),
        fine = DeviceLocator.hasPrecisePermission(context),
        background = DeviceLocator.hasBackgroundPermission(context),
    )

    private fun readStored(): Stored? =
        storage.read<Stored>(FILE, object : TypeToken<Stored>() {}.type)?.takeIf { it.owner == storage.ownerKey() }

    /** Fences registered for this login (empty when none or another login). */
    fun stored(): List<StoredFence> {
        @Suppress("SENSELESS_COMPARISON")
        return readStored()?.fences.orEmpty().filter { it != null && it.title != null && it.trigger != null && it.action != null && it.note != null }
    }

    /** Rule places registered for this login. */
    fun storedRules(): List<RuleFence> {
        @Suppress("SENSELESS_COMPARISON")
        return readStored()?.rules.orEmpty().filter { it != null && it.name != null }
    }

    fun storedRule(placeId: Int): RuleFence? = storedRules().firstOrNull { it.placeId == placeId }

    fun storedFence(placeId: Int): StoredFence? = stored().firstOrNull { it.placeId == placeId }

    /** `GET places` and register what this phone has to watch. */
    suspend fun syncFromServer(): FamilieResult<List<FamiliePlace>> {
        val me = repository.cachedProfile() ?: repository.profileOrCached()
        val result = api.places()
        if (result is FamilieResult.Ok && me != null) {
            apply(if (me.modules.places) select(result.value, me.me.userId) else emptyList())
        } else if (result is FamilieResult.Failed && (result.code == "module_off" || result.code == "not_member" || result.http == 404)) {
            apply(emptyList())
        }
        return result
    }

    fun syncAsync() {
        scope.launch { syncFromServer() }
    }

    /** After boot / app start: the fences of the last sync again (Android drops them on reboot). */
    suspend fun reRegisterFromCache() {
        val fences = stored()
        val rules = storedRules()
        if (fences.isNotEmpty() || rules.isNotEmpty()) register(fences, rules)
    }

    /** Places already handled locally (repeat once): unregister without waiting for the server. */
    suspend fun removeLocal(placeId: Int) = mutex.withLock {
        val left = stored().filter { it.placeId != placeId }
        storage.write(FILE, Stored(storage.ownerKey(), left, storedRules()))
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(listOf(requestId(placeId))) }
        Unit
    }

    /** Removes every fence, reminders and rule places (plugin gone, not a member, module off). */
    suspend fun clear() {
        mutex.withLock { storage.write(FILE, Stored(storage.ownerKey(), emptyList(), emptyList())) }
        register(emptyList(), emptyList())
    }

    private suspend fun apply(places: List<FamiliePlace>) {
        val fences = places.map(::toFence)
        val rules = mutex.withLock {
            storedRules().also { storage.write(FILE, Stored(storage.ownerKey(), fences, it)) }
        }
        register(fences, rules)
    }

    /** The rule places to watch (Familie 1.5), next to the reminders; unchanged lists register nothing. */
    suspend fun applyRules(rules: List<RuleFence>) {
        val (fences, changed) = mutex.withLock {
            val before = storedRules()
            val fences = stored()
            storage.write(FILE, Stored(storage.ownerKey(), fences, rules))
            fences to (before != rules)
        }
        if (changed) register(fences, rules)
    }

    @SuppressLint("MissingPermission") // checked by readiness()
    private suspend fun register(fences: List<StoredFence>, rules: List<RuleFence>) = mutex.withLock {
        if (!playServicesAvailable()) return@withLock
        val client = LocationServices.getGeofencingClient(context)
        await(client.removeGeofences(pendingIntent(context)))
        val geofences = combined(fences, rules)
        if (geofences.isEmpty()) return@withLock
        if (readiness() != FamiliePermissions.GeofenceReadiness.READY) {
            Timber.i("Familie: %d geofences wait for the location permission", geofences.size)
            return@withLock
        }
        val request = GeofencingRequest.Builder()
            // No trigger for being inside already: re-registering (boot, app start) must not remind or report again.
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()
        val ok = try {
            await(client.addGeofences(request, pendingIntent(context)))
        } catch (e: SecurityException) {
            false
        }
        Timber.i("Familie: %d place reminders, %d rule places %s", fences.size, rules.size, if (ok) "registered" else "not registered")
    }

    private suspend fun <T> await(task: Task<T>): Boolean = withTimeoutOrNull(TASK_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            task.addOnCompleteListener { t -> if (cont.isActive) cont.resume(t.isSuccessful) }
        }
    } ?: false

    companion object {
        private const val FILE = "geofences.json"
        private const val TASK_TIMEOUT_MS = 15_000L
        /** Android allows 100 geofences per app; the server caps 50 active places per user. */
        const val MAX_FENCES = 90
        private const val REQUEST_PREFIX = "familie-place-"
        private const val RULE_PREFIX = "familie-rule-"

        /** Which places this phone watches (pure): mine as target, active, accepted. */
        fun select(places: List<FamiliePlace>, meId: Int): List<FamiliePlace> = places
            .filter { it.targetUserId == meId && it.active && it.consent == FamiliePlace.CONSENT_ACCEPTED }
            .distinctBy { it.id }
            .take(MAX_FENCES)

        fun toFence(p: FamiliePlace) = StoredFence(
            placeId = p.id,
            title = p.title,
            note = p.note,
            lat = p.lat,
            lon = p.lon,
            radius = p.radius,
            trigger = p.trigger,
            action = p.action,
            listId = p.listId,
            repeat = p.repeat,
        )

        fun requestId(placeId: Int) = "$REQUEST_PREFIX$placeId"

        fun placeIdOf(requestId: String): Int? = requestId.removePrefix(REQUEST_PREFIX).takeIf { requestId.startsWith(REQUEST_PREFIX) }?.toIntOrNull()

        fun ruleRequestId(placeId: Int) = "$RULE_PREFIX$placeId"

        fun rulePlaceIdOf(requestId: String): Int? = requestId.removePrefix(RULE_PREFIX).takeIf { requestId.startsWith(RULE_PREFIX) }?.toIntOrNull()

        /** The request ids to register (pure): reminders first, then rule places, at most [MAX_FENCES] together. */
        fun combinedIds(fences: List<StoredFence>, rules: List<RuleFence>): List<String> =
            (fences.map { requestId(it.placeId) } + rules.filter { it.enter || it.exit }.map { ruleRequestId(it.placeId) }).take(MAX_FENCES)

        private fun combined(fences: List<StoredFence>, rules: List<RuleFence>): List<Geofence> {
            val ids = combinedIds(fences, rules).toSet()
            return fences.filter { requestId(it.placeId) in ids }.map(::geofence) +
                rules.filter { ruleRequestId(it.placeId) in ids }.map(::ruleGeofence)
        }

        private fun ruleGeofence(f: RuleFence): Geofence = Geofence.Builder()
            .setRequestId(ruleRequestId(f.placeId))
            .setCircularRegion(f.lat, f.lon, f.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                (if (f.enter) Geofence.GEOFENCE_TRANSITION_ENTER else 0) or (if (f.exit) Geofence.GEOFENCE_TRANSITION_EXIT else 0),
            )
            .setNotificationResponsiveness(RULE_RESPONSIVENESS_MS)
            .build()

        /** Rules switch things ("500 m vor Zuhause" → heating on): sooner than a reminder needs. */
        private const val RULE_RESPONSIVENESS_MS = 30_000

        private fun geofence(f: StoredFence): Geofence = Geofence.Builder()
            .setRequestId(requestId(f.placeId))
            .setCircularRegion(f.lat, f.lon, f.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(if (f.trigger == FamiliePlace.TRIGGER_EXIT) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .build()

        /** Battery-friendly: within two minutes is soon enough for a reminder. */
        private const val RESPONSIVENESS_MS = 2 * 60_000

        /** Play services fill in the transition: the intent must be mutable (explicit, own receiver). */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
