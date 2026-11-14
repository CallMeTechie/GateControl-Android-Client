package com.gatecontrol.android.notizen

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.familie.familieLocalized
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.places.GeofenceBudget
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.location.LocateResult
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.support.CrashReports
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The places of tasks on this phone (plugin 1.5.0, `places`): `POST places/watch` says which places to watch and what
 * their reminders say; the phone registers them as Play services geofences — in the server's order, as many as the
 * Familie plugin's fences leave room for ([GeofenceBudget]) — and shows the reminder itself when one fires
 * (`POST places/fired` first while there is a connection, by [PlaceGuard] without one). Asked again on app start, after
 * a boot or an update of the app, when `changed` says `places` or `tasks`, when the Familie plugin's fences changed,
 * when the location was switched on again, and when the phone left the area the places were chosen for — never in
 * fixed intervals. With an older plugin (no such word in `features`) nothing is asked, registered or kept.
 *
 * A position leaves the phone only rounded ([PlaceLogic.near]) and only while the place search is on.
 */
@Singleton
class NotizenGeofences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: NotizenRepository,
    private val cache: NotizenCache,
    private val registrar: GeofenceRegistrar,
    private val reminders: NotizenReminderCenter,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val lock = Mutex()
    private val registerLock = Mutex()
    private val fireLock = Mutex()
    private val wanted = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    @Volatile private var started = false
    /** The budget the server was last asked with. */
    @Volatile private var askedBudget = -1
    /** The phone's last position (never kept on disk, never sent as it is) and when it was taken. */
    @Volatile private var lastFix: Triple<Double, Double, Long>? = null
    @Volatile private var cleared = false
    @Volatile private var probed = false

    /** What the phone watches and remembers — for „Gerade in der Nähe“ and the hints of a task. */
    data class Snapshot(val watch: NWatch = NWatch(), val state: PlaceState = PlaceState())

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Can this phone watch places: Play services, the precise location, „Immer erlauben“. */
    fun readiness(): FamiliePermissions.GeofenceReadiness = registrar.readiness()

    /** App start: the kept places again (Android drops geofences on a reboot), then the server's list; from now on after every change. */
    fun start() {
        if (started) return
        started = true
        scope.launch { wanted.collectLatest { delay(DEBOUNCE_MS); runCatching { sync() } } }
        scope.launch { repo.changed.collect { if (it.isEmpty() || "places" in it || "tasks" in it) wanted.tryEmit(Unit) } }
        scope.launch { repo.me.distinctUntilChangedBy { it?.taskPlaces to it?.placeSearch }.collect { if (it != null) wanted.tryEmit(Unit) } }
        // the Familie plugin comes first: before it registers more places, the hub's least important ones make room
        registrar.makeRoom = ::makeRoom
        // the Familie plugin registered other places: the room left for the hub may have changed
        scope.launch { registrar.changes.collect { if (askedBudget >= 0 && askedBudget != budget()) wanted.tryEmit(Unit) } }
        scope.launch {
            runCatching { reRegister() }
            if (repo.cachedMe() != null) wanted.tryEmit(Unit)
        }
        // the location was switched off and on again: Android dropped the geofences meanwhile
        runCatching {
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (DeviceLocator.isLocationOn(context)) scope.launch { runCatching { reRegister() } }
                    }
                },
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Boot, an update of the app: the kept places again, then the server's list. */
    fun onSystemChange() {
        scope.launch {
            runCatching { reRegister() }
            wanted.tryEmit(Unit)
        }
    }

    /** The location permission was just granted (or something else the list depends on changed here). */
    fun syncSoon() {
        wanted.tryEmit(Unit)
    }

    /**
     * The phone's exact position for what is worked out on the phone (the order and distances of the hits of a search);
     * null without the permission or a fix. It is never sent: what goes to the server is rounded first ([PlaceLogic.near]).
     */
    suspend fun position(): Pair<Double, Double>? =
        (DeviceLocator.locate(context, LOCATE_MS, STALE_MS) as? LocateResult.Fix)?.location?.let { it.latitude to it.longitude }

    private fun budget(): Int = GeofenceBudget.notizen(registrar.plannedCount())

    /** A position for the server: rounded here, and only while the place search is on; else the area asked last, or none. */
    private suspend fun nearFor(me: NMe, kept: NWatch): Pair<Double, Double>? {
        if (!me.placeSearch) return null
        val decimals = me.places?.decimals ?: 2
        // one fix serves the questions of the next minutes (a change of a task asks again — the phone has hardly moved)
        val now = clock()
        val last = lastFix?.takeIf { now - it.third in 0 until FIX_KEEP_MS }
        if (last != null) return PlaceLogic.near(last.first, last.second, decimals)
        // a new fix only where it serves something: while places are watched, and once per start of the app (a task with
        // „Jeder Lidl“ gets its places only with a position); else a position the phone has anyway, or none
        val active = kept.fences.isNotEmpty() || !probed
        probed = true
        val fix = (DeviceLocator.locate(context, if (active) LOCATE_MS else 0L, STALE_MS) as? LocateResult.Fix)?.location
        if (fix != null) lastFix = Triple(fix.latitude, fix.longitude, now)
        return if (fix != null) PlaceLogic.near(fix.latitude, fix.longitude, decimals) else PlaceLogic.near(kept.cellLat, kept.cellLon, decimals)
    }

    /** `POST places/watch` → the phone's copy and its geofences. False while the server cannot be reached (the kept ones stay). */
    suspend fun sync(): Boolean {
        val me = repo.cachedMe()
        if (me?.taskPlaces != true) {
            // an older plugin, the plugin gone or another login (its copy was deleted): no place of it stays registered
            clear()
            return true
        }
        val kept = cache.watch()
        val budget = budget()
        askedBudget = budget
        return when (val r = repo.api.placesWatch(nearFor(me, kept), budget)) {
            is NResult.Ok -> {
                cleared = false
                cache.putWatch(r.value.second)
                val tasks = r.value.first.fences.flatMap { f -> f.tasks.map { it.taskId } }.toSet()
                update { PlaceGuard.pruned(it, clock(), tasks) }
                register()
                true
            }
            // the plugin is older again, gone, or the login: no places of it stay
            is NResult.Failed -> {
                if (r.http == 404 || r.http == 401 || r.http == 403) clear()
                true
            }
            NResult.Offline -> false
            NResult.NotConfigured -> { clear(); true }
        }
    }

    private suspend fun update(f: (PlaceState) -> PlaceState): PlaceState = lock.withLock {
        val next = f(cache.placeState())
        cache.putPlaceState(next)
        next
    }

    private suspend fun publish() {
        _snapshot.value = Snapshot(cache.watch(), lock.withLock { cache.placeState() })
    }

    /** Nothing of the places stays on the phone (an older plugin, the plugin gone, another login). */
    private suspend fun clear() {
        val kept = lock.withLock { cache.placeState() } != PlaceState() || cache.watch().fences.isNotEmpty()
        if (cleared && !kept) return
        cleared = true
        if (kept) {
            cache.putWatch(null)
            update { PlaceState() }
        }
        // also when nothing is kept (the phone's copy went with the login): geofences registered before are taken away
        registerLock.withLock {
            if (registrar.playServicesAvailable()) await(LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)))
        }
        publish()
    }

    /**
     * The Familie plugin is about to register [family] fences: the hub's fences beyond what is left for it are taken
     * away first (the last of the server's order — the least important), so Android never refuses the family's. The
     * next question to the server then goes out with the smaller budget.
     */
    private suspend fun makeRoom(family: Int) = registerLock.withLock {
        val registered = lock.withLock { cache.placeState() }.registered
        val keep = GeofenceBudget.keep(registered, family)
        if (keep.size == registered.size) return@withLock
        val drop = registered.drop(keep.size)
        if (registrar.playServicesAvailable()) await(LocationServices.getGeofencingClient(context).removeGeofences(drop.map { REQUEST_PREFIX + it }))
        update { s -> s.copy(registered = keep, inside = s.inside - drop.toSet()) }
        Timber.i("Notizen: %d places made room for the Familie plugin's", drop.size)
    }

    /** After a boot or the app's start: the places of the last answer again. */
    suspend fun reRegister() {
        if (lock.withLock { cache.placeState() }.registered.isNotEmpty() || cache.watch().fences.isNotEmpty()) register() else publish()
    }

    /** The kept list as geofences: the hub's own are taken away and set again (the Familie plugin's stay untouched). */
    @SuppressLint("MissingPermission") // checked by readiness()
    private suspend fun register() = registerLock.withLock {
        var registered = emptyList<String>()
        try {
            if (!registrar.playServicesAvailable()) return@withLock
            val client = LocationServices.getGeofencingClient(context)
            await(client.removeGeofences(pendingIntent(context)))
            val watch = cache.watch()
            val fences = PlaceLogic.select(watch.fences, budget())
            if (fences.isEmpty()) return@withLock
            if (readiness() != FamiliePermissions.GeofenceReadiness.READY) {
                Timber.i("Notizen: %d places wait for the location permission", fences.size)
                return@withLock
            }
            val geofences = fences.map(::geofence).toMutableList()
            // leaving the area the additional places were chosen for asks the server again (with the new, rounded position)
            val cLat = watch.cellLat
            val cLon = watch.cellLon
            if (cLat != null && cLon != null && watch.search == NPlacesInfo.ON) {
                geofences += Geofence.Builder()
                    .setRequestId(MOVE_ID)
                    .setCircularRegion(cLat, cLon, watch.refreshM.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                    .setNotificationResponsiveness(MOVE_RESPONSIVENESS_MS)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                // no trigger for being inside already: registering again (boot, a change) must not remind again
                .setInitialTrigger(0)
                .addGeofences(geofences)
                .build()
            val ok = try {
                await(client.addGeofences(request, pendingIntent(context)))
            } catch (e: SecurityException) {
                false
            }
            if (ok) registered = fences.map { it.id }
            Timber.i("Notizen: %d places %s", fences.size, if (ok) "registered" else "not registered")
        } finally {
            val ids = registered
            update { s -> s.copy(registered = ids, inside = s.inside.filter { it in ids }.toSet()) }
            publish()
        }
    }

    private suspend fun <T> await(task: Task<T>): Boolean = withTimeoutOrNull(TASK_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            task.addOnCompleteListener { t -> if (cont.isActive) cont.resume(t.isSuccessful) }
        }
    } ?: false

    private fun online(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    // ── a place fires ───────────────────────────────────────

    /**
     * Android says the phone entered or left the fences [fenceIds] ([transition] `enter` | `exit`). For every task of
     * them that waits for this: the server decides while it can be reached (two phones in one pocket ring once), the
     * phone by the same rules without it — the reminder never waits for a connection.
     */
    suspend fun onTransition(fenceIds: List<String>, transition: String) = fireLock.withLock {
        val now = clock()
        if (MOVE in fenceIds && transition == NPlace.EXIT) {
            lastFix = null
            wanted.tryEmit(Unit)
        }
        val ids = fenceIds.mapNotNull { NotizenPlacesJson.fenceId(it) }
        if (ids.isEmpty()) return@withLock
        var state = update { PlaceGuard.moved(it, ids, transition == NPlace.ENTER) }
        val watch = cache.watch()
        val due = watch.fences.filter { it.id in ids }.flatMap { f -> f.tasks.map { f to it } }
            .filter { (f, t) -> t.trigger == transition && PlaceGuard.muted(state, t.taskId, f.id, now) == null }
        if (due.isEmpty()) {
            publish()
            return@withLock
        }
        val waiting = repo.waiting()
        val zone = repo.cachedMe()?.timezone
        val asked: List<NResult<NFired>?> = if (online()) {
            coroutineScope { due.map { (f, t) -> async { withTimeoutOrNull(CHECK_MS) { repo.api.placeFired(t.taskId, f.id, transition, CHECK_MS) } } }.awaitAll() }
        } else {
            due.map { null }
        }
        var gone = false
        due.forEachIndexed { i, (f, t) ->
            // ticked off or deleted on this phone, still waiting for a connection
            if (settledOffline(waiting, t.taskId)) return@forEachIndexed
            val answer = asked[i]
            val show = when {
                answer is NResult.Ok -> answer.value.notify.also { if (!it && (answer.value.reason == "done" || answer.value.reason == "gone")) gone = true }
                // the server refused the question itself: nothing to show
                answer is NResult.Failed && answer.http in 400..499 -> false
                else -> PlaceGuard.offline(t, f.id, transition, watch, state, zone, now) == null
            }
            if (show && show(f, t)) state = update { PlaceGuard.shown(it, t, f.id, clock()) }
        }
        publish()
        // done or gone meanwhile: its geofence goes with the next list
        if (gone) wanted.tryEmit(Unit)
    }

    private fun settledOffline(ops: List<NotizenOutbox.Op>, taskId: Long): Boolean = ops.any { o ->
        o.id == taskId && !o.conflict && when (o.kind) {
            NotizenOutbox.TASK -> ops.last { it.kind == NotizenOutbox.TASK && it.id == taskId }.done
            NotizenOutbox.DELETE -> o.what == NotizenOutbox.TASK
            else -> false
        }
    }

    /** The reminder of [t] at [f], as the server worded it — the same notification the task's push uses (its collapse key). */
    private suspend fun show(f: NFence, t: NFenceTask): Boolean {
        val quiet = reminders.shade(clock()) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val ctx = familieLocalized(context, settingsRepository)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tap = PendingIntent.getActivity(
            context, 0,
            PushOpenActivity.notizenIntent(context, NotizenPush.encode(NotizenPush.Task(t.taskId, t.area)), t.tag).setData("gatecontrol-notizen://place-open/${t.collapseKey}".toUri()),
            flags,
        )
        val builder = NotificationCompat.Builder(ctx, reminders.channel(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.notifyTitle)
            .setSubText(ctx.getString(R.string.notizen_tab))
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (t.important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
        if (t.notifyBody.isNotBlank()) {
            builder.setContentText(t.notifyBody.lineSequence().first())
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(t.notifyBody))
        }
        // the server's buttons — the phone carries them out itself (they are no push callbacks)
        PlaceGuard.buttons(t).forEachIndexed { i, a ->
            val list = t.attachedList?.takeIf { a.id == NotizenPush.ACTION_OPEN_LIST }
            val intent = if (list != null) {
                PendingIntent.getActivity(
                    context, 0,
                    PushOpenActivity.notizenIntent(context, NotizenPush.encode(NotizenPush.Shopping(list.uid, t.area)), t.tag).setData("gatecontrol-notizen://place-list/${t.collapseKey}".toUri()),
                    flags,
                )
            } else {
                PendingIntent.getBroadcast(context, 0, NotizenPlaceReceiver.buttonIntent(context, t, f.id, a.id, i), flags)
            }
            builder.addAction(0, a.label, intent)
        }
        return try {
            NotificationManagerCompat.from(context).notify(reminders.notificationId(t.collapseKey), builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** A button of the reminder at a place: the notification goes, the phone remembers it at once, the server is told (or the outbox keeps it). */
    suspend fun button(taskId: Long, fence: String, key: String, actionId: String) {
        NotificationManagerCompat.from(context).cancel(reminders.notificationId(key))
        when (actionId) {
            PlaceGuard.DONE -> repo.completeTask(taskId, true)
            PlaceGuard.LATER -> later(taskId)
            PlaceGuard.NOT_HERE -> notHere(taskId, fence)
        }
    }

    /** „Später“: three hours of quiet for this task at every place. */
    suspend fun later(taskId: Long): NotizenRepository.Acted {
        update { PlaceGuard.later(it, taskId, clock()) }
        publish()
        return repo.placeLater(taskId)
    }

    /** „Nicht hier“: [fence] is not offered for this task any more. */
    suspend fun notHere(taskId: Long, fence: String): NotizenRepository.Acted {
        val id = NotizenPlacesJson.fenceId(fence) ?: return NotizenRepository.Acted.FAILED
        update { PlaceGuard.skipped(it, taskId, id) }
        publish()
        return repo.placeSkip(taskId, id)
    }

    /**
     * „Gerade in der Nähe“ (the card of „Mein Tag“): the tasks of the fences the phone is inside, with the distance from
     * its exact position — worked out here; the server gets nothing of it.
     */
    suspend fun nearNow(): List<NNearNow> {
        val s = _snapshot.value.takeIf { it.watch.fences.isNotEmpty() } ?: Snapshot(cache.watch(), lock.withLock { cache.placeState() })
        if (s.state.inside.isEmpty() || s.state.registered.isEmpty()) return emptyList()
        val fix = (DeviceLocator.locate(context, NEAR_LOCATE_MS, STALE_MS) as? LocateResult.Fix)?.location
        return PlaceLogic.nearNow(s.watch, s.state, fix?.latitude, fix?.longitude, clock())
            // the phone was switched off inside a place and on again elsewhere: what is clearly not here is left out
            .filter { n -> n.distanceM == null || n.distanceM <= n.fence.radius * 2 + NEAR_SLACK_M }
    }

    companion object {
        private const val REQUEST_PREFIX = "nz:"
        private const val MOVE = "cell"
        private const val MOVE_ID = REQUEST_PREFIX + MOVE
        private const val TASK_TIMEOUT_MS = 15_000L
        private const val DEBOUNCE_MS = 2_000L
        private const val CHECK_MS = 4_000L
        private const val LOCATE_MS = 6_000L
        private const val NEAR_LOCATE_MS = 3_000L
        private const val STALE_MS = 15 * 60_000L
        private const val FIX_KEEP_MS = 10 * 60_000L
        private const val NEAR_SLACK_M = 300
        /** Battery-friendly, as the Familie plugin's reminders: within two minutes is soon enough. */
        private const val RESPONSIVENESS_MS = 2 * 60_000
        private const val MOVE_RESPONSIVENESS_MS = 5 * 60_000

        /** The fence ids ([NFence.id], or `cell`) of Play services' request ids; others are not the hub's. */
        fun fenceIdsOf(requestIds: List<String>): List<String> = requestIds.filter { it.startsWith(REQUEST_PREFIX) }.map { it.removePrefix(REQUEST_PREFIX) }

        /** Both transitions, whatever the tasks wait for: the phone knows which places it is inside („Gerade in der Nähe“). */
        private fun geofence(f: NFence): Geofence = Geofence.Builder()
            .setRequestId(REQUEST_PREFIX + f.id)
            .setCircularRegion(f.lat, f.lon, f.radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .build()

        /** Play services fill in the transition: the intent must be mutable (explicit, own receiver) — not the Familie plugin's. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotizenPlaceReceiver::class.java).setAction(NotizenPlaceReceiver.ACTION_GEOFENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotizenGeofencesEntryPoint {
    fun notizenGeofences(): NotizenGeofences
}

/**
 * Geofence transitions of the places of tasks (from Play services, through the app's own mutable PendingIntent) and the
 * buttons of their reminders. NOT exported.
 */
@AndroidEntryPoint
class NotizenPlaceReceiver : BroadcastReceiver() {

    @Inject lateinit var places: NotizenGeofences

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GEOFENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    Timber.w("Notizen: geofence error %d", event.errorCode)
                    return
                }
                val transition = when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> NPlace.ENTER
                    Geofence.GEOFENCE_TRANSITION_EXIT -> NPlace.EXIT
                    else -> return
                }
                val ids = NotizenGeofences.fenceIdsOf(event.triggeringGeofences.orEmpty().map { it.requestId })
                if (ids.isEmpty()) return
                handle { places.onTransition(ids, transition) }
            }
            ACTION_BUTTON -> {
                val taskId = intent.getLongExtra(EXTRA_TASK, 0L).takeIf { it > 0 } ?: return
                val fence = intent.getStringExtra(EXTRA_FENCE).orEmpty()
                val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                val actionId = intent.getStringExtra(EXTRA_ACTION).orEmpty()
                handle { places.button(taskId, fence, key, actionId) }
            }
        }
    }

    private fun handle(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { block() }
            } catch (e: Exception) {
                Timber.w("Notizen: place handling failed (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GEOFENCE = "com.gatecontrol.android.notizen.PLACE_GEOFENCE"
        const val ACTION_BUTTON = "com.gatecontrol.android.notizen.PLACE_BUTTON"
        private const val EXTRA_TASK = "com.gatecontrol.android.notizen.extra.PLACE_TASK"
        private const val EXTRA_FENCE = "com.gatecontrol.android.notizen.extra.PLACE_FENCE"
        private const val EXTRA_KEY = "com.gatecontrol.android.notizen.extra.PLACE_KEY"
        private const val EXTRA_ACTION = "com.gatecontrol.android.notizen.extra.PLACE_ACTION"
        /** A broadcast may live ~10 s with goAsync. */
        private const val TIMEOUT_MS = 9_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        /** Explicit intent to this non-exported receiver; [slot] keeps the PendingIntents of one notification apart. */
        fun buttonIntent(context: Context, t: NFenceTask, fence: String, actionId: String, slot: Int): Intent =
            Intent(context, NotizenPlaceReceiver::class.java)
                .setAction(ACTION_BUTTON)
                .setData("gatecontrol-notizen://place-ui/${t.collapseKey}/$slot".toUri())
                .putExtra(EXTRA_TASK, t.taskId)
                .putExtra(EXTRA_FENCE, fence)
                .putExtra(EXTRA_KEY, t.collapseKey)
                .putExtra(EXTRA_ACTION, actionId)
    }
}
