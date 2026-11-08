package com.gatecontrol.android.notizen

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gatecontrol.android.support.CrashReports
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** An answer for a screen: from the server, or the device's copy while it cannot be reached. */
data class NCached<T>(val value: T, val offline: Boolean, val savedAt: Long = 0)

/**
 * „Notizen & Aufgaben“ on this phone: `me`, the area (Privat / Arbeit), the offline copy ([NotizenCache]) and the
 * outbox of ticks made without a connection ([NotizenOutbox], sent by [NotizenOutboxWorker] once there is a network),
 * and the plugin's live events (`changed`, `vault`) for the open screens.
 */
@Singleton
class NotizenRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val api: NotizenApi,
    private val cache: NotizenCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val outboxLock = Mutex()

    private val _me = MutableStateFlow<NMe?>(null)
    val me: StateFlow<NMe?> = _me.asStateFlow()

    private val _area = MutableStateFlow<String?>(null)
    /** The area shown (the person's last choice, `prefs.area`). */
    val area: StateFlow<String?> = _area.asStateFlow()

    private val _changed = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Something changed on the server (`tasks`, `notes`, `items`, `lists`, `notebooks`; empty: everything). */
    val changed: SharedFlow<Set<String>> = _changed.asSharedFlow()

    private val _vaultEvents = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** A Tresor's device was asked for or approved (its vault id). */
    val vaultEvents: SharedFlow<String> = _vaultEvents.asSharedFlow()

    private val _pending = MutableStateFlow<List<NotizenOutbox.Op>>(emptyList())
    /** Ticks waiting for a connection. */
    val pending: StateFlow<List<NotizenOutbox.Op>> = _pending.asStateFlow()

    init {
        scope.launch { _pending.value = cache.outbox() }
    }

    /** `GET me` (the copy while offline). */
    suspend fun refreshMe(): NCached<NMe>? {
        when (val r = api.me()) {
            is NResult.Ok -> {
                cache.put("me", r.value.second)
                _me.value = r.value.first
                if (_area.value == null) _area.value = r.value.first.prefs.area
                flushSoon()
                return NCached(r.value.first, false)
            }
            is NResult.Failed -> if (r.http == 404 || r.http == 401) {
                // the plugin is gone or the token: nothing of it stays on the phone
                cache.wipe()
                _pending.value = emptyList()
            }
            else -> Unit
        }
        val (raw, at) = cache.get("me") ?: return null
        val me = NotizenJson.me(raw) ?: return null
        if (_me.value == null) _me.value = me
        if (_area.value == null) _area.value = me.prefs.area
        return NCached(me, true, at)
    }

    suspend fun meOrCached(): NMe? = _me.value ?: refreshMe()?.value

    /** Privat / Arbeit, remembered on the server for every device of the person. */
    fun setArea(area: String) {
        val a = NArea.of(area)
        if (_area.value == a) return
        _area.value = a
        scope.launch { api.putPrefs(NotizenJson.prefsBody(area = a)) }
    }

    fun currentArea(): String = _area.value ?: _me.value?.prefs?.area ?: NArea.PRIVATE

    /** An answer of the server, kept for offline; the kept one when the server cannot be reached. */
    private suspend fun <T> cached(key: String, fetch: suspend () -> NResult<Pair<T, JsonObject>>, parse: (JsonObject) -> T?): NResult<NCached<T>> {
        return when (val r = fetch()) {
            is NResult.Ok -> {
                cache.put(key, r.value.second)
                NResult.Ok(NCached(r.value.first, false))
            }
            NResult.Offline -> {
                val kept: Pair<JsonObject, Long>? = cache.get(key)
                val value: T? = kept?.let { parse(it.first) }
                if (kept != null && value != null) NResult.Ok(NCached(value, true, kept.second)) else NResult.Offline
            }
            is NResult.Failed -> NResult.Failed(r.code, r.http, r.body)
            NResult.NotConfigured -> NResult.NotConfigured
        }
    }

    suspend fun nav(area: String) = cached("nav:$area", { api.nav(area) }, NotizenJson::nav)

    suspend fun view(name: String, area: String): NResult<NCached<NView>> =
        when (val r = cached("view:$name:$area", { api.view(name, area) }, NotizenJson::view)) {
            is NResult.Ok -> NResult.Ok(r.value.copy(value = NotizenOutbox.overlay(r.value.value, _pending.value)))
            else -> r
        }

    suspend fun task(id: Long) = cached("task:$id", { api.task(id) }, NotizenJson::taskDetail)

    suspend fun notes(area: String, notebookId: Long?, tag: String?, pinned: Boolean = false) =
        cached("notes:$area:${notebookId ?: "-"}:${tag ?: "-"}:$pinned", { api.notes(area, notebookId, tag, pinned) }, NotizenJson::notesPage)

    suspend fun note(id: Long) = cached("note:$id", { api.note(id) }, NotizenJson::note)

    // ── ticks (queued while offline) ────────────────────────

    /** How a tick went. */
    enum class Tick { DONE, QUEUED, FAILED, READ_ONLY }

    /**
     * Ticks [entry] off (or back): an own task, or an item in its plugin (`todo_complete` there). Without a connection
     * the tick waits in the outbox and is shown at once; a refusal of the plugin (`read_only`) is reported.
     */
    suspend fun complete(entry: NEntry, done: Boolean): Pair<Tick, NTask?> {
        val r: NResult<Any?> = when (entry) {
            is NTask -> api.completeTask(entry.id, done).let { if (it is NResult.Ok) NResult.Ok(it.value.second) else it }
            is NItem -> api.completeItem(entry.id, done).let { if (it is NResult.Ok) NResult.Ok(null) else it }
        }
        return when (r) {
            is NResult.Ok -> {
                _changed.tryEmit(setOf("tasks", "items"))
                Tick.DONE to (r.value as? NTask)
            }
            NResult.Offline -> {
                enqueue(NotizenOutbox.Op(if (entry is NTask) NotizenOutbox.TASK else NotizenOutbox.ITEM, if (entry is NTask) entry.id else (entry as NItem).id, done, System.currentTimeMillis()))
                Tick.QUEUED to null
            }
            is NResult.Failed -> (if (NotizenJson.reason(r.body) == "read_only" || NotizenJson.reason(r.body) == "login") Tick.READ_ONLY else Tick.FAILED) to null
            NResult.NotConfigured -> Tick.FAILED to null
        }
    }

    private suspend fun enqueue(op: NotizenOutbox.Op) = outboxLock.withLock {
        val next = NotizenOutbox.add(_pending.value, op)
        _pending.value = next
        cache.putOutbox(next)
        NotizenOutboxWorker.enqueue(context)
    }

    /** Sends the waiting ticks; false while the server cannot be reached. */
    suspend fun flush(): Boolean = outboxLock.withLock {
        var left = _pending.value
        if (left.isEmpty()) return@withLock true
        for (op in left.toList()) {
            val r = if (op.kind == NotizenOutbox.TASK) api.completeTask(op.id, op.done) else api.completeItem(op.id, op.done)
            if (r is NResult.Offline || r is NResult.NotConfigured) {
                _pending.value = left
                cache.putOutbox(left)
                return@withLock false
            }
            // sent, or refused for good (gone, read only): either way it no longer waits
            if (r is NResult.Failed) Timber.i("Notizen: a waiting tick was refused (%s)", r.code)
            left = left.filterNot { it === op }
        }
        _pending.value = left
        cache.putOutbox(left)
        _changed.tryEmit(setOf("tasks", "items"))
        true
    }

    private fun flushSoon() {
        if (_pending.value.isNotEmpty()) scope.launch { flush() }
    }

    // ── live events ─────────────────────────────────────────

    /** `event: plugin` of this plugin from the push stream (returns at once). */
    fun onPluginEvent(type: String, data: JsonElement?) {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject
        when (type) {
            "changed" -> {
                val what = o?.get("what")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() }?.toSet().orEmpty()
                _changed.tryEmit(what)
            }
            "vault" -> o?.get("vault")?.let { runCatching { it.asString }.getOrNull() }?.let { NotizenJson.vaultId(it) }?.let { _vaultEvents.tryEmit(it) }
        }
    }

    /** The push stream (re)connected: what changed meanwhile is fetched again, waiting ticks go out. */
    fun onStreamOpen() {
        if (_me.value == null) return
        _changed.tryEmit(emptySet())
        flushSoon()
    }

    /** Work a closing screen hands over (the last autosave of a note), done on the repository's own scope. */
    fun saveLater(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** A screen changed something itself: the others load again. */
    fun touched(vararg what: String) {
        _changed.tryEmit(what.toSet())
    }
}

/** Sends the ticks made without a connection once there is a network again. */
class NotizenOutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun notizenRepository(): NotizenRepository
    }

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).notizenRepository()
        return if (repo.flush()) Result.success() else if (runAttemptCount < 8) Result.retry() else Result.failure()
    }

    companion object {
        private const val WORK_NAME = "notizen-outbox"

        fun enqueue(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<NotizenOutboxWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { Timber.w("Notizen: outbox job not scheduled (%s)", it.javaClass.simpleName) }
        }
    }
}
