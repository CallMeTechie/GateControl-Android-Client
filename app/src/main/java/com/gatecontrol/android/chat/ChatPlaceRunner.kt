package com.gatecontrol.android.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.gatecontrol.android.familie.places.GeofenceBudget
import com.gatecontrol.android.familie.places.GeofenceRegistrar
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.push.PushOpenActivity
import com.gatecontrol.android.support.CrashReports
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.gson.JsonObject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/*
 * Messenger 1.27: Ortsnachrichten on this phone. [ChatPlaceMessagesRepository] keeps the list (`GET place-messages`,
 * a copy on the phone for a trigger without a network) and the phone's own marks (sent or asked within the hour,
 * dropped after 30 minutes); [ChatPlaceGeofences] registers the phone's own active ones as geofences — at most its
 * share of Android's 100 ([GeofenceBudget.placeMessages]) — and acts when one fires: the time window, once an hour,
 * then the question „Jetzt senden?“ (15 minutes) or right away [ChatPlaceFireWorker] (`POST place-messages/:id/fire`,
 * waits for a network up to 30 minutes) and the notification „Gesendet … Zurücknehmen“.
 */

/** The list of the Ortsnachrichten and what this phone remembers about them. */
@Singleton
class ChatPlaceMessagesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ChatApi,
    private val chat: ChatRepository,
    private val setupRepository: SetupRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _list = MutableStateFlow(cached())
    /** The Ortsnachrichten as last loaded (this login), null: never loaded. */
    val list: StateFlow<ChatPmList?> = _list.asStateFlow()

    @Volatile private var started = false

    /**
     * This installation's device id (`android-` and 16 hex digits): made once and kept out of the backup, so a new
     * phone restored from it is another device.
     */
    val deviceId: String by lazy {
        val f = File(context.noBackupFilesDir, DEVICE_FILE)
        runCatching { f.readText().trim() }.getOrNull()?.takeIf { ChatPlaceMessages.isDeviceId(it) } ?: run {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val id = "android-" + bytes.joinToString("") { "%02x".format(it) }
            runCatching { f.writeText(id) }
            id
        }
    }

    /** The phone's name for „Ortsnachrichten laufen auf diesem Handy (…)“. */
    val deviceName: String get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    /** `features.placeMessages` of the cached profile (null: no profile yet). */
    fun featureOn(): Boolean? = chat.profile.value?.let { ChatPlaceMessages.on(it.features) }

    fun start() {
        if (started) return
        started = true
        // Another login: nothing of the old one stays
        scope.launch { setupRepository.revision.collect { if (_list.value != null && owner() != prefs.getString(KEY_OWNER, null)) clear() } }
        scope.launch {
            chat.profile.map { it?.let { p -> ChatPlaceMessages.on(p.features) } }.distinctUntilChanged().collect { on ->
                when (on) {
                    true -> refresh()
                    false -> clear()
                    null -> Unit
                }
            }
        }
        scope.launch { chat.accountEvents.collect { e -> if (e.type == ChatPlaceMessages.EVENT) refresh() } }
    }

    private fun owner(): String = PluginDirectory.ownerKey(setupRepository.getServerUrl(), setupRepository.getApiToken())

    private fun cached(): ChatPmList? {
        if (prefs.getString(KEY_OWNER, null) != owner()) return null
        val raw = prefs.getString(KEY_LIST, null) ?: return null
        return runCatching { ChatJsonPatch.parse(raw)?.let { ChatPlaceMessages.list(it) } }.getOrNull()
    }

    private fun store(l: ChatPmList?) {
        _list.value = l
        prefs.edit {
            putString(KEY_OWNER, owner())
            if (l == null) remove(KEY_LIST) else putString(KEY_LIST, ChatPlaceMessages.writeList(l).toString())
        }
    }

    private fun clear() {
        synchronized(lock) {
            store(null)
            prefs.edit { remove(KEY_FIRED); remove(KEY_DROPPED); remove(KEY_FENCES); remove(KEY_LOG); remove(KEY_REGISTERED); remove(KEY_LOST) }
        }
        _diagTick.value++
    }

    /** The copy on the phone (for a trigger without a network). */
    fun cachedItems(): List<ChatPlaceMessage> = _list.value?.items.orEmpty()

    fun item(id: Long): ChatPlaceMessage? = cachedItems().firstOrNull { it.id == id }

    suspend fun refresh(): ChatResult<ChatPmList> {
        val r = api.placeMessages()
        if (r is ChatResult.Ok) synchronized(lock) { store(r.value) }
        // 404/403 while the profile still says `placeMessages` (the messenger restarting, a proxy in between): the copy
        // on the phone stays and keeps watching — only a profile without the feature (or another login) clears it
        else if (r is ChatResult.Failed && (r.http == 404 || r.http == 403) && featureOn() != true) clear()
        return r
    }

    private fun upsert(p: ChatPlaceMessage) = synchronized(lock) {
        val l = _list.value ?: ChatPmList(emptyList(), ChatPmLimits())
        val items = if (l.items.any { it.id == p.id }) l.items.map { if (it.id == p.id) p else it } else listOf(p) + l.items
        store(l.copy(items = items))
    }

    private fun remove(id: Long) = synchronized(lock) {
        _list.value?.let { l -> store(l.copy(items = l.items.filter { it.id != id })) }
    }

    suspend fun create(d: ChatPmDraft): ChatResult<ChatPlaceMessage> {
        val body = ChatPlaceMessages.body(d, deviceId) ?: return ChatResult.Failed("invalid", 0)
        val r = api.createPlaceMessage(body)
        if (r is ChatResult.Ok) upsert(r.value)
        return r
    }

    suspend fun update(id: Long, d: ChatPmDraft): ChatResult<ChatPlaceMessage> {
        val body = ChatPlaceMessages.body(d, deviceId) ?: return ChatResult.Failed("invalid", 0)
        return put(id, body)
    }

    private suspend fun put(id: Long, body: JsonObject): ChatResult<ChatPlaceMessage> {
        val r = api.updatePlaceMessage(id, body)
        if (r is ChatResult.Ok) upsert(r.value)
        return r
    }

    suspend fun setEnabled(id: Long, on: Boolean): ChatResult<ChatPlaceMessage> = put(id, JsonObject().apply { addProperty("enabled", on) })

    /** „Auf dieses Handy holen“: the Ortsnachrichten of another phone run here from now on. */
    suspend fun moveHere(ids: List<Long>): ChatResult<Unit> {
        var last: ChatResult<Unit> = ChatResult.Ok(Unit)
        for (id in ids) {
            val r = put(id, JsonObject().apply { addProperty("deviceId", deviceId) })
            if (r !is ChatResult.Ok) last = r.asFailure()
        }
        return last
    }

    suspend fun delete(id: Long): ChatResult<Unit> {
        val r = api.deletePlaceMessage(id)
        if (r is ChatResult.Ok || (r is ChatResult.Failed && r.code == "not_found")) {
            remove(id)
            return ChatResult.Ok(Unit)
        }
        return r
    }

    /** After `fire`: the Ortsnachricht as the server has it now (lastFiredAt, a `once` done). */
    fun applyFired(f: ChatPmFired) {
        f.placeMessage?.let { upsert(it) }
        dropped(f.placeMessage?.id ?: return, null)
    }

    // ─── the phone's own marks ──────────────────────────────────────

    private fun readMap(key: String): Map<Long, Long> =
        prefs.getString(key, null)?.split(',')?.mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { k -> b.toLongOrNull()?.let { k to it } } } }
            ?.toMap().orEmpty()

    private fun writeMap(key: String, m: Map<Long, Long>) = prefs.edit { putString(key, m.entries.joinToString(",") { "${it.key}:${it.value}" }) }

    /** When the phone last sent or asked, by id (once an hour also without a network). */
    fun localFired(): Map<Long, Long> = synchronized(lock) { readMap(KEY_FIRED) }

    fun markFired(id: Long, ms: Long) = synchronized(lock) {
        val now = ms
        writeMap(KEY_FIRED, readMap(KEY_FIRED).filterValues { now - it < ChatPlaceMessages.EVERY_MS * 2 } + (id to ms))
    }

    /** Ortsnachrichten whose message was dropped (no network within 30 minutes), by id → when. */
    fun dropped(): Map<Long, Long> = synchronized(lock) { readMap(KEY_DROPPED) }

    /** [ms] null: no longer dropped (it went out since). */
    fun dropped(id: Long, ms: Long?) {
        synchronized(lock) {
            val m = readMap(KEY_DROPPED)
            writeMap(KEY_DROPPED, if (ms == null) m - id else m + (id to ms))
        }
        if (ms != null) logEvent(id, ChatPlaceWatch.DROPPED, atMs = ms)
    }

    // ─── what the phone knows about the places and did (the status) ─

    private val _diagTick = MutableStateFlow(0L)
    /** Bumped on every change of the log, the registered fences or the sides: the status lines draw again. */
    val diagTick: StateFlow<Long> = _diagTick.asStateFlow()

    private fun readJson(key: String): JsonObject? = prefs.getString(key, null)?.let { runCatching { ChatJsonPatch.parse(it) }.getOrNull() }

    /** The side of each watched place, by id ([ChatPlaceWatch]). */
    fun fences(): Map<Long, ChatPmFence> = synchronized(lock) { ChatPlaceWatch.readFences(readJson(KEY_FENCES)) }

    fun setFences(m: Map<Long, ChatPmFence>) {
        synchronized(lock) { prefs.edit { putString(KEY_FENCES, ChatPlaceWatch.writeFences(m).toString()) } }
        _diagTick.value++
    }

    /** The last events of each Ortsnachricht on this phone. */
    fun log(): Map<Long, List<ChatPmEvent>> = synchronized(lock) { ChatPlaceWatch.readLog(readJson(KEY_LOG)) }

    fun logEvent(id: Long, kind: String, detail: String? = null, atMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val m = ChatPlaceWatch.readLog(readJson(KEY_LOG))
            // only the Ortsnachrichten that still exist (the list may not be loaded yet: then all stay)
            val known = _list.value?.items?.map { it.id }?.toSet()
            val next = (m + (id to ChatPlaceWatch.append(m[id].orEmpty(), ChatPmEvent(atMs, kind, detail))))
                .filterKeys { known == null || it in known || it == id }
            prefs.edit { putString(KEY_LOG, ChatPlaceWatch.writeLog(next).toString()) }
        }
        Timber.i("Chats: Ortsnachricht %d %s %s", id, kind, detail.orEmpty())
        _diagTick.value++
    }

    /** The ids registered with Android last (empty: none, or waiting for the permission). */
    fun registered(): Set<Long> = synchronized(lock) { prefs.getString(KEY_REGISTERED, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty() }

    fun setRegistered(ids: Collection<Long>) {
        synchronized(lock) { prefs.edit { putString(KEY_REGISTERED, ids.joinToString(",")) } }
        _diagTick.value++
    }

    /** Android said the geofences are gone (the location was switched off): until they are added again. */
    fun lost(): Boolean = prefs.getBoolean(KEY_LOST, false)

    fun setLost(on: Boolean) {
        prefs.edit { putBoolean(KEY_LOST, on) }
        _diagTick.value++
    }

    companion object {
        private const val PREFS = "chat_place_messages"
        private const val DEVICE_FILE = "chat_place_device"
        private const val KEY_OWNER = "owner"
        private const val KEY_LIST = "list"
        private const val KEY_FIRED = "fired"
        private const val KEY_DROPPED = "dropped"
        private const val KEY_FENCES = "fences"
        private const val KEY_LOG = "log"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_LOST = "lost"
    }
}

/**
 * The phone watches the places of its own Ortsnachrichten and acts when one fires.
 *
 * The fences are added again on every start of the app, a boot, an update, the location switched on again and every
 * change of the list — with `INITIAL_TRIGGER_ENTER | _EXIT` and without removing them first, so there is never a moment
 * without them and Android says at once which side the phone is on. [ChatPlaceWatch.observe] tells a real arrival (the
 * phone was outside before) from being there already: adding them again while the phone is at the place, before the old
 * fence reported the arrival, no longer swallows it (18.11.: the app started at 07:42 at work, seven minutes after the
 * arrival, and the fences were removed and added with `setInitialTrigger(0)` — no „Jetzt senden?“).
 */
@Singleton
class ChatPlaceGeofences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ChatPlaceMessagesRepository,
    private val registrar: GeofenceRegistrar,
    private val notifier: ChatPlaceNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val mutex = Mutex()
    private val fireMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var registered: List<Long> = emptyList()
    @Volatile private var budget = -1

    fun readiness(): FamiliePermissions.GeofenceReadiness = registrar.readiness()

    /** The phone's state for the status lines. */
    fun diag(): ChatPmDiag = ChatPmDiag(
        readiness = readiness(),
        locationOn = DeviceLocator.isLocationOn(context),
        notificationsOn = notifier.enabled(),
        registered = repo.registered(),
        lost = repo.lost(),
        log = repo.log(),
    )

    /** App start: the list and its geofences, from now on after every change (and when the Familie plugin's change). */
    fun start() {
        if (started) return
        started = true
        repo.start()
        registrar.addRoomMaker(::makeRoom)
        scope.launch { repo.list.collect { runCatching { register() } } }
        scope.launch {
            registrar.changes.collect { if (GeofenceBudget.placeMessages(registrar.plannedCount()) != budget) runCatching { register() } }
        }
        // the location was switched off and on again: Android dropped the geofences meanwhile (GEOFENCE_NOT_AVAILABLE)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (DeviceLocator.isLocationOn(context)) scope.launch { runCatching { register() } }
                    }
                },
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Boot, an update of the app, the location permission granted: the geofences again. */
    fun onSystemChange() {
        scope.launch { runCatching { register() } }
    }

    @SuppressLint("MissingPermission") // checked by readiness()
    suspend fun register() = mutex.withLock {
        if (!registrar.playServicesAvailable()) return@withLock
        val client = LocationServices.getGeofencingClient(context)
        val items = if (repo.featureOn() == false) emptyList() else repo.cachedItems()
        budget = GeofenceBudget.placeMessages(registrar.plannedCount())
        val fences = ChatPlaceMessages.watched(items, repo.deviceId, budget)
        registrar.setPlaceMessagesPlanned(fences.size)
        val before = (registered + repo.registered()).toSet()
        if (fences.isEmpty()) {
            if (before.isNotEmpty()) await(client.removeGeofences(pendingIntent(context)))
            registered = emptyList()
            repo.setRegistered(emptyList())
            repo.setFences(emptyMap())
            return@withLock
        }
        if (readiness() != FamiliePermissions.GeofenceReadiness.READY) {
            Timber.i("Chats: %d Ortsnachrichten wait for the location permission", fences.size)
            registered = emptyList()
            repo.setRegistered(emptyList())
            return@withLock
        }
        // the ones no longer watched go; the others are replaced in place (the same request id), never removed first
        val gone = before - fences.map { it.id }.toSet()
        if (gone.isNotEmpty()) await(client.removeGeofences(gone.map { ChatPlaceMessages.requestId(it) }))
        val request = GeofencingRequest.Builder()
            // Android says at once which side the phone is on; [ChatPlaceWatch.observe] knows whether that is news
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(fences.map(::geofence))
            .build()
        val nowMs = System.currentTimeMillis()
        // the sides first: the report on adding may come before addGeofences returns
        fireMutex.withLock { repo.setFences(ChatPlaceWatch.afterRegister(repo.fences(), fences, nowMs)) }
        val ok = try {
            await(client.addGeofences(request, pendingIntent(context)))
        } catch (e: SecurityException) {
            false
        }
        registered = if (ok) fences.map { it.id } else emptyList()
        repo.setRegistered(registered)
        if (ok) repo.setLost(false)
        Timber.i("Chats: %d Ortsnachrichten %s", fences.size, if (ok) "registered" else "not registered")
        scheduleCheck()
    }

    /** The Familie plugin is about to register [family] fences: what no longer fits of the Ortsnachrichten goes first. */
    private suspend fun makeRoom(family: Int) = mutex.withLock {
        val keep = registered.take(GeofenceBudget.placeMessages(family))
        if (keep.size == registered.size) return@withLock
        val drop = registered.drop(keep.size)
        if (registrar.playServicesAvailable()) await(LocationServices.getGeofencingClient(context).removeGeofences(drop.map { ChatPlaceMessages.requestId(it) }))
        registered = keep
        repo.setRegistered(keep)
        registrar.setPlaceMessagesPlanned(keep.size)
    }

    /** Android: the geofences are gone (the location is off). They come back with the location ([start]) or the next start. */
    fun onUnavailable() {
        repo.setLost(true)
        repo.registered().forEach { repo.logEvent(it, ChatPlaceWatch.UNAVAILABLE) }
    }

    /**
     * Android says the phone is on [transition]'s side of the places [ids] — an arrival or a departure, or only the
     * report on adding them ([ChatPlaceWatch.observe]). What is due asks first or goes out right away; either way it
     * counts as sent for the hour. Each step goes into the log.
     */
    suspend fun onTransition(ids: List<Long>, transition: String) = fireMutex.withLock {
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        val at = Instant.ofEpochMilli(nowMs).truncatedTo(ChronoUnit.SECONDS).toString()
        val side = ChatPlaceWatch.sideOf(transition)
        val states = repo.fences().toMutableMap()
        for (id in ids) {
            val fresh = states[id]?.let { it.side == null } == true
            val (next, moved) = ChatPlaceWatch.observe(states[id], side, nowMs)
            if (next != null) states[id] = next
            val p = repo.item(id)
            if (!moved) {
                // a new fence learned its side: being there already when saving sends nothing
                if (fresh && p != null) repo.logEvent(id, ChatPlaceWatch.LEARNED, side, nowMs)
                continue
            }
            if (p == null) {
                repo.logEvent(id, ChatPlaceWatch.SKIPPED, ChatPlaceWatch.WHY_UNKNOWN, nowMs)
                continue
            }
            // a departure of an Ortsnachricht „Beim Ankommen“ (and the other way round) only moves the side
            if (p.trigger != transition) continue
            repo.logEvent(id, ChatPlaceWatch.DETECTED, transition, nowMs)
            val why = ChatPlaceWatch.skipReason(p, transition, repo.deviceId, now, nowMs, repo.localFired())
            if (why != null) {
                repo.logEvent(id, ChatPlaceWatch.SKIPPED, why, nowMs)
                continue
            }
            act(p, at, nowMs)
        }
        repo.setFences(states)
        scheduleCheck()
    }

    /** At the start of a window: what waits for it (the phone got there before) goes now. */
    suspend fun onWindowStart() {
        fireMutex.withLock {
            val nowMs = System.currentTimeMillis()
            val at = Instant.ofEpochMilli(nowMs).truncatedTo(ChronoUnit.SECONDS).toString()
            val due = ChatPlaceWatch.catchUp(repo.cachedItems(), repo.fences(), repo.deviceId, LocalDateTime.now(), nowMs, repo.localFired())
            for (p in due) {
                repo.logEvent(p.id, ChatPlaceWatch.CAUGHT_UP, null, nowMs)
                act(p, at, nowMs)
            }
        }
        scheduleCheck()
    }

    private suspend fun act(p: ChatPlaceMessage, at: String, nowMs: Long) {
        if (p.ask) {
            if (notifier.ask(p, at)) {
                repo.markFired(p.id, nowMs)
                repo.logEvent(p.id, ChatPlaceWatch.ASKED, null, nowMs)
            } else {
                // no notification may be shown (Mitteilungen off): nothing counts as asked, the status says why
                repo.logEvent(p.id, ChatPlaceWatch.BLOCKED, null, nowMs)
            }
        } else {
            repo.markFired(p.id, nowMs)
            repo.logEvent(p.id, ChatPlaceWatch.CONFIRMED, null, nowMs)
            ChatPlaceFireWorker.enqueue(context, p.id, at)
        }
    }

    /** The next window start an Ortsnachricht waits for (the phone is at its place): a job then, else none. */
    private fun scheduleCheck() {
        val now = LocalDateTime.now()
        val next = ChatPlaceWatch.nextCheck(repo.cachedItems(), repo.fences(), repo.deviceId, now)
        val delayMs = next?.let { java.time.Duration.between(now, it).toMillis().coerceAtLeast(0L) }
        ChatPlaceWindowWorker.schedule(context, delayMs)
    }

    private suspend fun <T> await(task: Task<T>): Boolean = withTimeoutOrNull(TASK_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            task.addOnCompleteListener { t -> if (cont.isActive) cont.resume(t.isSuccessful) }
        }
    } ?: false

    companion object {
        private const val TASK_TIMEOUT_MS = 15_000L
        /** Battery-friendly, as the other places: Android reports an arrival within about two minutes. */
        private const val RESPONSIVENESS_MS = 2 * 60_000

        /** Both directions for every place: the phone keeps its side ([ChatPlaceWatch]); the trigger is checked on the phone. */
        private fun geofence(p: ChatPlaceMessage): Geofence = Geofence.Builder()
            .setRequestId(ChatPlaceMessages.requestId(p.id))
            .setCircularRegion(p.place.lat, p.place.lon, ChatPlaceWatch.fenceRadius(p.place.radiusM).toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .build()

        /** Play services fill in the transition: mutable, explicit, the own receiver — not the Familie plugin's or the hub's. */
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ChatPlaceReceiver::class.java).setAction(ChatPlaceReceiver.ACTION_GEOFENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/** At the start of a time window: [ChatPlaceGeofences.onWindowStart]. One job at a time, the earliest start. */
class ChatPlaceWindowWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, ChatPlaceEntryPoint::class.java).placeGeofences().onWindowStart()
        return Result.success()
    }

    companion object {
        private const val NAME = "chat-place-window"

        /** [delayMs] null: nothing waits for a window — the job goes. */
        fun schedule(context: Context, delayMs: Long?) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                if (delayMs == null) {
                    wm.cancelUniqueWork(NAME)
                    return
                }
                val request = OneTimeWorkRequestBuilder<ChatPlaceWindowWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
            }.onFailure { Timber.w("Chats: window check not queued (%s)", it.javaClass.simpleName) }
        }
    }
}

/** The notifications of Ortsnachrichten: „Jetzt senden?“, „Gesendet … Zurücknehmen“. */
@Singleton
class ChatPlaceNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private suspend fun localized(): Context {
        val lang = runCatching { settingsRepository.getLocale().first() }.getOrNull() ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return context.createConfigurationContext(config)
    }

    private fun allowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether „Jetzt senden?“ can be seen: the permission, the app's notifications and this channel not switched off. */
    fun enabled(): Boolean = runCatching {
        val nm = NotificationManagerCompat.from(context)
        allowed() && nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(true)

    private fun channel(ctx: Context): String {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.chat_pm_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.chat_pm_channel_desc)
            },
        )
        return CHANNEL
    }

    private fun wayLabel(p: ChatPlaceMessage): String =
        if (p.way == ChatPeople.REPLY_AUTO) p.target?.reply?.label ?: ChatPeople.labelOf(ChatPeople.WAY_GC) else ChatPeople.labelOf(p.way)

    private fun to(ctx: Context, p: ChatPlaceMessage): String = p.target?.title ?: ctx.getString(R.string.chat_pm_someone)

    /**
     * „Du bist bei Zuhause angekommen — An Kim über WhatsApp: „…“ — Jetzt senden · Ändern · Nicht senden“ (15 minutes).
     * False: it cannot be shown (no permission, notifications or the channel switched off).
     */
    suspend fun ask(p: ChatPlaceMessage, at: String): Boolean {
        if (!enabled()) return false
        val ctx = localized()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val id = askId(p.id)
        val title = ctx.getString(if (p.trigger == ChatPlaceMessages.EXIT) R.string.chat_pm_ask_left else R.string.chat_pm_ask_arrived, p.place.name)
        val body = ctx.getString(R.string.chat_pm_ask_body, to(ctx, p), wayLabel(p), p.text)
        val send = PendingIntent.getBroadcast(context, 0, ChatPlaceReceiver.intent(context, ChatPlaceReceiver.ACTION_SEND, p.id, at), flags)
        val skip = PendingIntent.getBroadcast(context, 0, ChatPlaceReceiver.intent(context, ChatPlaceReceiver.ACTION_SKIP, p.id, at), flags)
        // „Ändern“: the chat opens with the text in the composer
        val change = PendingIntent.getActivity(
            context, 0,
            PushOpenActivity.chatIntent(context, p.conversationId).putExtra(PushOpenActivity.EXTRA_CHAT_DRAFT, p.text).putExtra(PushOpenActivity.EXTRA_CANCEL, id)
                .setData("gatecontrol-chat://place-change/${p.id}".toUri()),
            flags,
        )
        val n = NotificationCompat.Builder(ctx, channel(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(ctx.getString(R.string.chat_pm_title))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(ChatPlaceMessages.ASK_TTL_MS)
            .setContentIntent(change)
            .addAction(0, ctx.getString(R.string.chat_pm_send_now), send)
            .addAction(0, ctx.getString(R.string.chat_pm_change), change)
            .addAction(0, ctx.getString(R.string.chat_pm_skip), skip)
            .build()
        return notify(id, n)
    }

    /** „Gesendet an Kim — „…“ · beim Ankommen in Zuhause — Zurücknehmen“. */
    suspend fun sent(p: ChatPlaceMessage, messageId: Long?) {
        if (!allowed()) return
        val ctx = localized()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val id = sentId(p.id)
        val body = ctx.getString(if (p.trigger == ChatPlaceMessages.EXIT) R.string.chat_pm_sent_body_exit else R.string.chat_pm_sent_body_enter, p.text, p.place.name)
        val b = NotificationCompat.Builder(ctx, channel(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.chat_pm_sent_title, to(ctx, p)))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(ctx.getString(R.string.chat_pm_title))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, PushOpenActivity.chatIntent(context, p.conversationId).setData("gatecontrol-chat://place-sent/${p.id}".toUri()), flags),
            )
        if (messageId != null) {
            b.addAction(0, ctx.getString(R.string.chat_pm_undo), PendingIntent.getBroadcast(context, 0, ChatPlaceReceiver.undoIntent(context, p.id, messageId), flags))
        }
        notify(id, b.build())
    }

    /** „Zurücknehmen“ did not work (the time to edit is over, no network). */
    suspend fun undoFailed(placeMessageId: Long) {
        if (!allowed()) return
        val ctx = localized()
        val n = NotificationCompat.Builder(ctx, channel(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.chat_pm_undo_failed))
            .setSubText(ctx.getString(R.string.chat_pm_title))
            .setAutoCancel(true)
            .build()
        notify(sentId(placeMessageId), n)
    }

    fun cancelAsk(id: Long) = runCatching { NotificationManagerCompat.from(context).cancel(askId(id)) }

    fun cancelSent(id: Long) = runCatching { NotificationManagerCompat.from(context).cancel(sentId(id)) }

    private fun notify(id: Int, n: android.app.Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(id, n)
        true
    } catch (_: SecurityException) {
        // the permission was taken away meanwhile
        false
    }

    companion object {
        const val CHANNEL = "chat_place_messages"
        fun askId(id: Long): Int = 7_300_000 + (id % 100_000).toInt()
        fun sentId(id: Long): Int = 7_400_000 + (id % 100_000).toInt()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatPlaceEntryPoint {
    fun placeGeofences(): ChatPlaceGeofences
    fun placeMessages(): ChatPlaceMessagesRepository
    fun placeNotifier(): ChatPlaceNotifier
    fun chatApi(): ChatApi
}

/** Geofence transitions of Ortsnachrichten and the buttons of their notifications. NOT exported. */
@AndroidEntryPoint
class ChatPlaceReceiver : BroadcastReceiver() {

    @Inject lateinit var geofences: ChatPlaceGeofences
    @Inject lateinit var repo: ChatPlaceMessagesRepository
    @Inject lateinit var notifier: ChatPlaceNotifier
    @Inject lateinit var api: ChatApi

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GEOFENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    Timber.w("Chats: geofence error %d", event.errorCode)
                    // the location was switched off: Android dropped the fences; they come back with the location
                    if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) geofences.onUnavailable()
                    return
                }
                val transition = when (event.geofenceTransition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> ChatPlaceMessages.ENTER
                    Geofence.GEOFENCE_TRANSITION_EXIT -> ChatPlaceMessages.EXIT
                    else -> return
                }
                val ids = event.triggeringGeofences.orEmpty().mapNotNull { ChatPlaceMessages.idOf(it.requestId) }
                if (ids.isEmpty()) return
                handle { geofences.onTransition(ids, transition) }
            }
            ACTION_SEND -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L).takeIf { it > 0 } ?: return
                val at = intent.getStringExtra(EXTRA_AT) ?: return
                notifier.cancelAsk(id)
                val atMs = ChatPlaceMessages.ms(at) ?: return
                // the question went away after 15 minutes: nothing goes out any more
                if (ChatPlaceMessages.askExpired(atMs, System.currentTimeMillis())) {
                    repo.logEvent(id, ChatPlaceWatch.SKIPPED, ChatPlaceWatch.WHY_ASK_EXPIRED)
                    return
                }
                repo.logEvent(id, ChatPlaceWatch.CONFIRMED)
                ChatPlaceFireWorker.enqueue(context, id, at)
            }
            ACTION_SKIP -> intent.getLongExtra(EXTRA_ID, 0L).takeIf { it > 0 }?.let {
                notifier.cancelAsk(it)
                repo.logEvent(it, ChatPlaceWatch.DECLINED)
            }
            ACTION_UNDO -> {
                val id = intent.getLongExtra(EXTRA_ID, 0L).takeIf { it > 0 } ?: return
                val messageId = intent.getLongExtra(EXTRA_MESSAGE, 0L).takeIf { it > 0 } ?: return
                handle {
                    notifier.cancelSent(id)
                    if (api.delete(messageId, forAll = true) !is ChatResult.Ok) notifier.undoFailed(id)
                }
            }
        }
    }

    private fun handle(block: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { block() }
            } catch (e: Exception) {
                Timber.w("Chats: Ortsnachricht not handled (%s)", e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_GEOFENCE = "com.gatecontrol.android.chat.PLACE_GEOFENCE"
        const val ACTION_SEND = "com.gatecontrol.android.chat.PLACE_SEND"
        const val ACTION_SKIP = "com.gatecontrol.android.chat.PLACE_SKIP"
        const val ACTION_UNDO = "com.gatecontrol.android.chat.PLACE_UNDO"
        private const val EXTRA_ID = "com.gatecontrol.android.chat.extra.PLACE_ID"
        private const val EXTRA_AT = "com.gatecontrol.android.chat.extra.PLACE_AT"
        private const val EXTRA_MESSAGE = "com.gatecontrol.android.chat.extra.PLACE_MESSAGE"
        /** A broadcast may live ~10 s with goAsync. */
        private const val TIMEOUT_MS = 9_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        fun intent(context: Context, action: String, id: Long, at: String): Intent =
            Intent(context, ChatPlaceReceiver::class.java).setAction(action)
                .setData("gatecontrol-chat://place/$action/$id".toUri())
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_AT, at)

        fun undoIntent(context: Context, id: Long, messageId: Long): Intent =
            Intent(context, ChatPlaceReceiver::class.java).setAction(ACTION_UNDO)
                .setData("gatecontrol-chat://place/undo/$id/$messageId".toUri())
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_MESSAGE, messageId)
    }
}

/**
 * `POST place-messages/:id/fire { at, deviceId }` once there is a network — at most 30 minutes after the trigger,
 * then the message is dropped. The same `at` again is harmless (`duplicate`).
 */
class ChatPlaceFireWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, 0L).takeIf { it > 0 } ?: return Result.failure()
        val at = inputData.getString(KEY_AT) ?: return Result.failure()
        val atMs = ChatPlaceMessages.ms(at) ?: return Result.failure()
        val deps = EntryPointAccessors.fromApplication(applicationContext, ChatPlaceEntryPoint::class.java)
        val repo = deps.placeMessages()
        if (ChatPlaceMessages.late(atMs, System.currentTimeMillis())) {
            repo.dropped(id, System.currentTimeMillis())
            return Result.success()
        }
        return when (val r = deps.chatApi().firePlaceMessage(id, at, repo.deviceId)) {
            is ChatResult.Ok -> {
                repo.applyFired(r.value)
                if (!r.value.duplicate) repo.logEvent(id, ChatPlaceWatch.SENT)
                val p = r.value.placeMessage ?: repo.item(id)
                // a repeat of the same trigger (`duplicate`) was announced already
                if (p != null && !r.value.duplicate) deps.placeNotifier().sent(p, r.value.message?.id)
                Result.success()
            }
            is ChatResult.Failed -> when {
                r.code == "expired" -> { repo.dropped(id, System.currentTimeMillis()); Result.success() }
                r.code in ChatPlaceMessages.FINAL_CODES || r.http in 400..499 -> {
                    repo.logEvent(id, ChatPlaceWatch.FAILED, r.code)
                    Result.success()
                }
                else -> retry(id)
            }
            ChatResult.NotConfigured -> Result.success()
            else -> retry(id)
        }
    }

    private fun retry(id: Long): Result {
        if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        EntryPointAccessors.fromApplication(applicationContext, ChatPlaceEntryPoint::class.java).placeMessages().dropped(id, System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_AT = "at"
        private const val MAX_ATTEMPTS = 12

        fun enqueue(context: Context, id: Long, at: String) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<ChatPlaceFireWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_ID to id, KEY_AT to at))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork("chat-place-$id-$at", ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Chats: Ortsnachricht not queued (%s)", it.javaClass.simpleName) }
        }
    }
}
