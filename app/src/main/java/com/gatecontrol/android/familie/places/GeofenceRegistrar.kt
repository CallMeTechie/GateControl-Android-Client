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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * After the move of the place reminders (Familie 1.24): [moved] some of them live in „Notizen & Aufgaben“ now, [left]
 * how many still belong to the Familie plugin („Bescheid sagen“, switched off, not taken over).
 */
data class PlacesHome(val moved: Boolean, val left: Int)

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
    /**
     * [rules]: the rule places (Familie 1.5) this phone watches, next to the reminders. [registered]: the request ids
     * added last (null: an older version of the app, which did not keep them); [sides]: the side of each fence
     * ([FenceSides]).
     */
    private data class Stored(
        val owner: String? = null,
        val fences: List<StoredFence>? = null,
        val rules: List<RuleFence>? = null,
        val registered: List<String>? = null,
        val sides: Map<String, FenceSide>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** The fences of the Familie plugin were registered again — whoever shares Android's 100 with them ([GeofenceBudget]) looks again. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    private val _home = kotlinx.coroutines.flow.MutableStateFlow<PlacesHome?>(null)
    /** What `GET places` last said about the move to „Notizen & Aufgaben“ (null: not asked yet in this process). */
    val home: kotlinx.coroutines.flow.StateFlow<PlacesHome?> = _home

    /**
     * Called right before the Familie plugin's fences are registered, with their number: whoever shares Android's 100
     * with them takes away what no longer fits ([GeofenceBudget.keep]) — the family's come first.
     */
    @Volatile var makeRoom: (suspend (family: Int) -> Unit)? = null

    /** Messenger 1.27: the Ortsnachrichten make room as well (their share shrinks only with very many family places). */
    private val roomMakers = java.util.concurrent.CopyOnWriteArrayList<suspend (family: Int) -> Unit>()

    fun addRoomMaker(f: suspend (family: Int) -> Unit) {
        roomMakers += f
    }

    @Volatile private var placeMessagesPlanned = 0

    /** How many geofences the Ortsnachrichten take on this phone (Messenger 1.27) — the hub leaves them room. */
    fun placeMessagesPlanned(): Int = placeMessagesPlanned

    /** The Ortsnachrichten registered [n] geofences: whoever shares Android's 100 with them looks again. */
    fun setPlaceMessagesPlanned(n: Int) {
        if (n == placeMessagesPlanned) return
        placeMessagesPlanned = n
        _changes.tryEmit(Unit)
    }

    /** How many of Android's 100 geofences the Familie plugin takes on this phone (reminders and rule places, as kept). */
    fun plannedCount(): Int = combinedIds(stored(), storedRules()).size

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
        if (result is FamilieResult.Ok) _home.value = placesHome(result.value)
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
        val id = requestId(placeId)
        val left = stored().filter { it.placeId != placeId }
        val s = readStored()
        writeStored(left, storedRules(), s?.registered?.minus(id), s?.sides.orEmpty() - id)
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(listOf(id)) }
        Unit
    }

    /**
     * Android reports [enter] (or leaving) for the fence [requestId]: whether this is a transition to act on — or only
     * the report on adding it again (the phone was there already, [FenceSides.observe]). The side is kept either way.
     */
    suspend fun observe(requestId: String, enter: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        val s = readStored() ?: return@withLock true
        val sides = s.sides.orEmpty()
        val (next, moved) = FenceSides.observe(sides[requestId], FenceSides.sideOf(enter), nowMs)
        if (next != null && next != sides[requestId]) storage.write(FILE, s.copy(sides = sides + (requestId to next)))
        moved
    }

    /** Writes the reminders and rule places, keeping what is known about the registered fences (or [registered]/[sides]). */
    private fun writeStored(
        fences: List<StoredFence>,
        rules: List<RuleFence>,
        registered: List<String>? = readStored()?.registered,
        sides: Map<String, FenceSide>? = readStored()?.sides,
    ) {
        storage.write(FILE, Stored(storage.ownerKey(), fences, rules, registered, sides))
    }

    /** Removes every fence, reminders and rule places (plugin gone, not a member, module off). */
    suspend fun clear() {
        mutex.withLock { writeStored(emptyList(), emptyList()) }
        register(emptyList(), emptyList())
    }

    private suspend fun apply(places: List<FamiliePlace>) {
        val fences = places.map(::toFence)
        val rules = mutex.withLock {
            storedRules().also { writeStored(fences, it) }
        }
        register(fences, rules)
    }

    /** The rule places to watch (Familie 1.5), next to the reminders; unchanged lists register nothing. */
    suspend fun applyRules(rules: List<RuleFence>) {
        val (fences, changed) = mutex.withLock {
            val before = storedRules()
            val fences = stored()
            writeStored(fences, rules)
            fences to (before != rules)
        }
        if (changed) register(fences, rules)
    }

    private suspend fun register(fences: List<StoredFence>, rules: List<RuleFence>) {
        try {
            registerLocked(fences, rules)
        } finally {
            _changes.tryEmit(Unit)
        }
    }

    /**
     * Replaces the fences in place (the ones no longer wanted go, the others are added again with the same request id —
     * never all removed first) with `INITIAL_TRIGGER_ENTER | _EXIT`: Android says at once which side the phone is on, and
     * [observe] tells a real arrival (outside before) from being there already ([FenceSides]).
     */
    @SuppressLint("MissingPermission") // checked by readiness()
    private suspend fun registerLocked(fences: List<StoredFence>, rules: List<RuleFence>) = mutex.withLock {
        if (!playServicesAvailable()) return@withLock
        val client = LocationServices.getGeofencingClient(context)
        val geofences = combined(fences, rules)
        val sigs = signatures(fences, rules)
        val before = readStored()
        val remove = FenceSides.toRemove(before?.registered, sigs.keys)
        if (geofences.isEmpty()) {
            if (remove == null || remove.isNotEmpty()) await(client.removeGeofences(pendingIntent(context)))
            writeStored(fences, rules, emptyList(), emptyMap())
            return@withLock
        }
        if (readiness() != FamiliePermissions.GeofenceReadiness.READY) {
            Timber.i("Familie: %d geofences wait for the location permission", geofences.size)
            return@withLock
        }
        runCatching { makeRoom?.invoke(geofences.size) }
        roomMakers.forEach { f -> runCatching { f(geofences.size) } }
        // the ones no longer watched go (an older version's list is unknown: everything of this receiver, once)
        when {
            remove == null -> await(client.removeGeofences(pendingIntent(context)))
            remove.isNotEmpty() -> await(client.removeGeofences(remove.toList()))
        }
        // the sides first: the report on adding may come before addGeofences returns
        writeStored(fences, rules, before?.registered, FenceSides.afterRegister(before?.sides.orEmpty(), sigs, System.currentTimeMillis()))
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(geofences)
            .build()
        val ok = try {
            await(client.addGeofences(request, pendingIntent(context)))
        } catch (e: SecurityException) {
            false
        }
        // not added: unknown what Android kept — the next registration starts clean
        writeStored(fences, rules, if (ok) sigs.keys.toList() else null)
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
        /** Android allows 100 geofences per app; the server caps 50 active places per user ([GeofenceBudget]). */
        const val MAX_FENCES = GeofenceBudget.FAMILY_MAX
        private const val REQUEST_PREFIX = "familie-place-"
        private const val RULE_PREFIX = "familie-rule-"

        /**
         * Which places this phone watches (pure): mine as target, active, accepted — and never one that moved to
         * „Notizen & Aufgaben“ (Familie 1.24: `active: false`, `home: notizen`, `moved`): the hub tells the phone to
         * watch it as a task with a place, so it does not take a second geofence or speak up twice.
         */
        fun select(places: List<FamiliePlace>, meId: Int): List<FamiliePlace> = places
            .filter { it.targetUserId == meId && it.active && !it.inHub && it.consent == FamiliePlace.CONSENT_ACCEPTED }
            .distinctBy { it.id }
            .take(MAX_FENCES)

        /** The reminders the Familie plugin's page still lists: not those that moved (they are tasks with a place now). */
        fun shown(places: List<FamiliePlace>): List<FamiliePlace> = places.filter { !it.inHub }

        fun placesHome(places: List<FamiliePlace>): PlacesHome = PlacesHome(moved = places.any { it.inHub }, left = shown(places).size)

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

        /** Request id → what makes the fence the same one ([FenceSides.signature]) of what [combinedIds] keeps (pure). */
        fun signatures(fences: List<StoredFence>, rules: List<RuleFence>): Map<String, String> {
            val ids = combinedIds(fences, rules).toSet()
            return fences.filter { requestId(it.placeId) in ids }.associate { requestId(it.placeId) to FenceSides.signature(it.lat, it.lon, it.radius, it.trigger) } +
                rules.filter { ruleRequestId(it.placeId) in ids }.associate { ruleRequestId(it.placeId) to FenceSides.signature(it.lat, it.lon, it.radius, "rule") }
        }

        private fun combined(fences: List<StoredFence>, rules: List<RuleFence>): List<Geofence> {
            val ids = combinedIds(fences, rules).toSet()
            return fences.filter { requestId(it.placeId) in ids }.map(::geofence) +
                rules.filter { ruleRequestId(it.placeId) in ids }.map(::ruleGeofence)
        }

        // both transitions on every fence: the phone keeps the side of each ([observe]); what a rule or a reminder
        // waits for is decided when one fires (GeofenceReceiver)
        private fun ruleGeofence(f: RuleFence): Geofence = Geofence.Builder()
            .setRequestId(ruleRequestId(f.placeId))
            .setCircularRegion(f.lat, f.lon, FenceSides.radius(f.radius).toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(RULE_RESPONSIVENESS_MS)
            .build()

        /** Rules switch things ("500 m vor Zuhause" → heating on): sooner than a reminder needs. */
        private const val RULE_RESPONSIVENESS_MS = 30_000

        private fun geofence(f: StoredFence): Geofence = Geofence.Builder()
            .setRequestId(requestId(f.placeId))
            .setCircularRegion(f.lat, f.lon, FenceSides.radius(f.radius).toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
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
