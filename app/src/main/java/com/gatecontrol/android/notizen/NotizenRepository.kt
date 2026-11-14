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

/** How a write went: done on the server ([queued] false, [value] its answer), or waiting in the outbox ([offline]: for a connection). */
data class NWritten<T>(val id: Long, val value: T?, val queued: Boolean, val offline: Boolean = false)

/**
 * „Notizen & Aufgaben“ on this phone: `me`, the area (Privat / Arbeit), the offline copy ([NotizenCache]) and the
 * outbox ([NotizenOutbox], sent in order by [NotizenOutboxWorker] once there is a network): ticks always; with plugin
 * 1.1.0 (`offlineEdits`) also new tasks, lists, notebooks and notes and their changes and deletes — shown at once,
 * marked „Noch nicht gesendet“, things made offline under a negative id until the server's is known. And the plugin's
 * live events (`changed`, `vault`) for the open screens.
 */
@Singleton
class NotizenRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val api: NotizenApi,
    private val cache: NotizenCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)
    private val outboxLock = Mutex()
    private val flushLock = Mutex()

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

    private val _state = MutableStateFlow(NotizenOutbox.State())
    private val _pending = MutableStateFlow<List<NotizenOutbox.Op>>(emptyList())
    /** What waits for a connection (and changes refused with 409, waiting for the person). */
    val pending: StateFlow<List<NotizenOutbox.Op>> = _pending.asStateFlow()

    /** The revision of notes the outbox saved (an open note's next save goes on from it, not from what it loaded). */
    private val revs = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** The newest revision of note [id] this phone knows from what the outbox sent. */
    fun knownRev(id: Long): Long? = serverId(id)?.let { revs[it] }

    @Volatile private var loaded = false
    @Volatile private var sending: String? = null

    init {
        scope.launch { ensureLoaded() }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        outboxLock.withLock {
            if (loaded) return@withLock
            // every op gets a uid of its own (the outbox of 1.0 had none)
            val s = cache.outbox().let { st -> st.copy(ops = st.ops.map { if (it.uid.isEmpty()) it.copy(uid = newUid()) else it }) }
            _state.value = s
            _pending.value = s.ops
            loaded = true
        }
    }

    private fun newUid() = java.util.UUID.randomUUID().toString()

    /** `GET me` (the copy while offline). */
    suspend fun refreshMe(): NCached<NMe>? {
        when (val r = api.me()) {
            is NResult.Ok -> {
                cache.put("me", r.value.second)
                _me.value = r.value.first
                if (_area.value == null) _area.value = startArea(r.value.first.prefs)
                flushSoon()
                return NCached(r.value.first, false)
            }
            is NResult.Failed -> if (r.http == 404 || r.http == 401) {
                // the plugin is gone or the token: nothing of it stays on the phone
                cache.wipe()
                outboxLock.withLock {
                    _state.value = NotizenOutbox.State()
                    _pending.value = emptyList()
                }
            }
            else -> Unit
        }
        val (raw, at) = cache.get("me") ?: return null
        val me = NotizenJson.me(raw) ?: return null
        if (_me.value == null) _me.value = me
        if (_area.value == null) _area.value = startArea(me.prefs)
        return NCached(me, true, at)
    }

    suspend fun meOrCached(): NMe? = _me.value ?: refreshMe()?.value

    /** Plugin 1.1.0: creates, changes and deletes may wait in the outbox (the server knows a create sent twice). */
    suspend fun offlineEdits(): Boolean = meOrCached()?.has(FEATURE_OFFLINE) == true

    /** Privat / Arbeit, remembered on the server for every device of the person. */
    fun setArea(area: String) {
        val a = NArea.of(area)
        if (_area.value == a) return
        _area.value = a
        scope.launch { api.putPrefs(NotizenJson.prefsBody(area = a)) }
    }

    fun currentArea(): String = _area.value ?: _me.value?.prefs?.let(::startArea) ?: NArea.PRIVATE

    /** The area on opening: „Beim Öffnen“ (plugin 1.2.0 `startArea`), else the one open last. */
    private fun startArea(p: NPrefs): String = if (p.startArea == NArea.PRIVATE || p.startArea == NArea.WORK) p.startArea else p.area

    /** `GET me` again after the person changed a setting (the prefs in it). */
    fun setPrefs(p: NPrefs) {
        _me.value = _me.value?.copy(prefs = p)
    }

    /** The server's id of [id] (a negative one: made offline; null while it waits). */
    fun serverId(id: Long): Long? = if (id > 0) id else _state.value.ids[id]

    /** An answer of the server, kept for offline; the kept one when the server cannot be reached. */
    private suspend fun <T> cached(key: String, fetch: suspend () -> NResult<Pair<T, JsonObject>>, parse: (JsonObject) -> T?): NResult<NCached<T>> {
        ensureLoaded()
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

    private fun <T> NResult<NCached<T>>.over(f: (T) -> T): NResult<NCached<T>> = if (this is NResult.Ok) NResult.Ok(value.copy(value = f(value.value))) else this

    suspend fun nav(area: String) = cached("nav:$area", { api.nav(area) }, NotizenJson::nav).over { NotizenOutbox.overlayNav(it, _pending.value) }

    suspend fun view(name: String, area: String): NResult<NCached<NView>> {
        ensureLoaded()
        // a list made offline: its page from the outbox until the server knows it
        val local = name.removePrefix("list:").toLongOrNull()?.takeIf { name.startsWith("list:") && it < 0 }
        val real = local?.let { serverId(it) }?.let { "list:$it" } ?: name
        if (local != null && real == name) {
            return NotizenOutbox.localView(name, _pending.value)?.let { NResult.Ok(NCached(it, false)) } ?: NResult.Failed("not_found", 404)
        }
        return cached("view:$real:$area", { api.view(real, area) }, NotizenJson::view).over { NotizenOutbox.overlay(it, _pending.value, _me.value?.today) }
    }

    suspend fun task(id: Long): NResult<NCached<NTaskDetail>> {
        ensureLoaded()
        val sid = serverId(id) ?: return NotizenOutbox.localDetail(id, _pending.value)?.let { NResult.Ok(NCached(it, false)) } ?: NResult.Failed("not_found", 404)
        return cached("task:$sid", { api.task(sid) }, NotizenJson::taskDetail).over { NotizenOutbox.overlayDetail(it, _pending.value) }
    }

    suspend fun notes(area: String, notebookId: Long?, tag: String?, pinned: Boolean = false): NResult<NCached<NNotesPage>> {
        ensureLoaded()
        val nb = notebookId?.let { serverId(it) ?: it }
        if (nb != null && nb < 0) {
            val made = NotizenOutbox.overlayNotes(emptyList(), _pending.value, area, nb, tag, pinned)
            return NResult.Ok(NCached(NNotesPage(made, null), false))
        }
        return cached("notes:$area:${nb ?: "-"}:${tag ?: "-"}:$pinned", { api.notes(area, nb, tag, pinned) }, NotizenJson::notesPage)
            .over { it.copy(notes = NotizenOutbox.overlayNotes(it.notes, _pending.value, area, nb, tag, pinned)) }
    }

    suspend fun note(id: Long): NResult<NCached<NNote>> {
        ensureLoaded()
        val sid = serverId(id) ?: return _pending.value.firstNotNullOfOrNull { if (it.id == id) NotizenOutbox.noteOf(it) else null }
            ?.let { NResult.Ok(NCached(it, false)) } ?: NResult.Failed("not_found", 404)
        return cached("note:$sid", { api.note(sid) }, NotizenJson::note).over { NotizenOutbox.overlayNote(it, _pending.value) }
    }

    // ── ticks (queued while offline) ────────────────────────

    /** How a tick went. */
    enum class Tick { DONE, QUEUED, FAILED, READ_ONLY }

    /**
     * Ticks [entry] off (or back): an own task, or an item in its plugin (`todo_complete` there). Without a connection
     * the tick waits in the outbox and is shown at once; a refusal of the plugin (`read_only`) is reported.
     */
    suspend fun complete(entry: NEntry, done: Boolean): Pair<Tick, NTask?> = when (entry) {
        is NTask -> completeTask(entry.id, done)
        is NItem -> tick(NotizenOutbox.ITEM, entry.id, done)
    }

    /** A task (or sub task) ticked off or back. */
    suspend fun completeTask(id: Long, done: Boolean): Pair<Tick, NTask?> = tick(NotizenOutbox.TASK, id, done)

    private suspend fun tick(kind: String, id: Long, done: Boolean): Pair<Tick, NTask?> {
        ensureLoaded()
        val sid = if (kind == NotizenOutbox.TASK) serverId(id) else id
        // what waits goes first: this one joins the queue behind it
        if (sid == null || hasWaiting()) {
            enqueue(NotizenOutbox.Op(kind, id, done, System.currentTimeMillis(), uid = newUid()))
            flushSoon()
            return Tick.QUEUED to null
        }
        val r: NResult<Any?> = if (kind == NotizenOutbox.TASK) {
            api.completeTask(sid, done).let { if (it is NResult.Ok) NResult.Ok(it.value.first) else it }
        } else {
            api.completeItem(sid, done).let { if (it is NResult.Ok) NResult.Ok(null) else it }
        }
        return when (r) {
            is NResult.Ok -> {
                _changed.tryEmit(setOf("tasks", "items"))
                Tick.DONE to (r.value as? NTask)
            }
            NResult.Offline -> {
                enqueue(NotizenOutbox.Op(kind, sid, done, System.currentTimeMillis(), uid = newUid()))
                Tick.QUEUED to null
            }
            is NResult.Failed -> (if (NotizenJson.reason(r.body) == "read_only" || NotizenJson.reason(r.body) == "login") Tick.READ_ONLY else Tick.FAILED) to null
            NResult.NotConfigured -> Tick.FAILED to null
        }
    }

    // ── „Später“, „Verschieben“, „Gestern offen geblieben“ (plugin 1.3.0) ──

    /** How a button of a reminder or of the card went. */
    enum class Acted { DONE, QUEUED, GONE, READ_ONLY, FAILED }

    /**
     * „Später“ ([until]: `1h` | `evening` | `tomorrow`) for the reminder [tag] of task [id]. Sent twice it moves once
     * (the tag), so without a connection it waits in the outbox. „Heute Abend“ after the evening: in an hour instead.
     */
    suspend fun snoozeTask(id: Long, until: String, tag: String?): Acted {
        val body = NotizenJson.snoozeBody(until, tag)
        return action(NotizenOutbox.SNOOZE, id, body) { sid ->
            val r = api.snoozeTaskBody(sid, body)
            if (until == "evening" && r is NResult.Failed && r.http == 400) api.snoozeTask(sid, "1h", tag) else r
        }
    }

    /** „Heute“ / „Verschieben“ ([to]: `today` | `tomorrow` | `weekend` | a date); the same target twice changes nothing. */
    suspend fun moveTask(id: Long, to: String): Acted {
        val body = NotizenJson.moveBody(NotizenJson.moveTarget(to) ?: return Acted.FAILED)
        return action(NotizenOutbox.MOVE, id, body) { sid -> api.moveTask(sid, body) }
    }

    private suspend fun action(what: String, id: Long, body: JsonObject, direct: suspend (Long) -> NResult<*>): Acted {
        ensureLoaded()
        val sid = serverId(id)
        if (sid == null || hasWaiting()) {
            enqueueAction(what, id, body)
            flushSoon()
            return Acted.QUEUED
        }
        return when (val r = direct(sid)) {
            is NResult.Ok -> { _changed.tryEmit(setOf("tasks")); Acted.DONE }
            NResult.Offline -> { enqueueAction(what, sid, body); Acted.QUEUED }
            is NResult.Failed -> when {
                r.http == 404 -> Acted.GONE
                r.http == 403 -> Acted.READ_ONLY
                else -> Acted.FAILED
            }
            NResult.NotConfigured -> Acted.FAILED
        }
    }

    private suspend fun enqueueAction(what: String, id: Long, body: JsonObject) {
        outboxLock.withLock {
            val s = _state.value
            save(s.copy(ops = NotizenOutbox.add(s.ops, NotizenOutbox.Op(NotizenOutbox.ACTION, id, false, System.currentTimeMillis(), what, body, uid = newUid()), sending)))
            NotizenOutboxWorker.enqueue(context)
        }
        _changed.tryEmit(setOf("tasks"))
    }

    /**
     * The whole card at once (`POST yesterday`): [action] `done` | `today` | `move` (with [to]) for [ids]. Without a
     * connection each task waits by itself in the outbox (a tick, a move) — sent twice they change nothing.
     */
    suspend fun yesterdayAll(action: String, ids: List<Long>, to: String? = null): Acted {
        ensureLoaded()
        if (ids.isEmpty()) return Acted.DONE
        val target = if (action == "today") "today" else to
        if (action != "done" && NotizenJson.moveTarget(target) == null) return Acted.FAILED
        if (!hasWaiting() && ids.all { it > 0 }) {
            when (val r = api.postYesterday(action, to.takeIf { action == "move" }, ids)) {
                is NResult.Ok -> { _changed.tryEmit(setOf("tasks")); return if (r.value.failed.isEmpty()) Acted.DONE else Acted.READ_ONLY }
                is NResult.Failed -> return if (r.http == 403) Acted.READ_ONLY else if (r.http == 404) Acted.GONE else Acted.FAILED
                NResult.NotConfigured -> return Acted.FAILED
                NResult.Offline -> Unit
            }
        }
        for (id in ids) {
            if (action == "done") enqueue(NotizenOutbox.Op(NotizenOutbox.TASK, serverId(id) ?: continue, true, System.currentTimeMillis(), uid = newUid()))
            else enqueueAction(NotizenOutbox.MOVE, id, NotizenJson.moveBody(target!!))
        }
        flushSoon()
        return Acted.QUEUED
    }

    /** What waits in the outbox, read from the phone first (a process an alarm just started). */
    suspend fun waiting(): List<NotizenOutbox.Op> {
        ensureLoaded()
        return _pending.value
    }

    /** `GET me` as this phone last kept it, without asking the server (the alarms after a boot). */
    suspend fun cachedMe(): NMe? = _me.value ?: cache.get("me")?.let { NotizenJson.me(it.first) }?.also { if (_me.value == null) _me.value = it }

    // ── creates, changes, deletes (plugin 1.1.0: queued while offline) ──

    private fun hasWaiting(): Boolean = _pending.value.any { !it.conflict }

    /**
     * One write: straight to the server while nothing waits (an answer that does not come — the request may have
     * arrived — puts it in the outbox: the server knows it again by its clientId, `base` or `rev`); behind what waits
     * otherwise. Without `offlineEdits` only the straight way (offline: [NResult.Offline]).
     */
    private suspend fun <T> write(
        kind: String,
        what: String,
        id: Long,
        body: JsonObject,
        clientId: String?,
        direct: suspend (Long, JsonObject) -> NResult<Pair<Long, T?>>,
    ): NResult<NWritten<T>> {
        ensureLoaded()
        val queue = offlineEdits()
        val sid = if (kind == NotizenOutbox.CREATE) 0L else serverId(id)
        if (queue && (sid == null || hasWaiting())) {
            val local = enqueueWrite(kind, what, id, body, clientId)
            flushSoon()
            return NResult.Ok(NWritten(local, null, queued = true))
        }
        if (sid == null) return NResult.Offline
        return when (val r = direct(sid, NotizenOutbox.prepared(NotizenOutbox.Op(kind, id, false, 0, what, body, clientId), _state.value.ids) ?: body)) {
            is NResult.Ok -> NResult.Ok(NWritten(r.value.first, r.value.second, queued = false))
            NResult.Offline -> if (queue) {
                NResult.Ok(NWritten(enqueueWrite(kind, what, id, body, clientId), null, queued = true, offline = true))
            } else {
                NResult.Offline
            }
            is NResult.Failed -> r
            NResult.NotConfigured -> NResult.NotConfigured
        }
    }

    private suspend fun enqueueWrite(kind: String, what: String, id: Long, body: JsonObject, clientId: String?): Long = outboxLock.withLock {
        var s = _state.value
        var oid = id
        if (kind == NotizenOutbox.CREATE) {
            oid = s.next
            s = s.copy(next = s.next - 1)
        }
        val op = NotizenOutbox.Op(kind, oid, false, System.currentTimeMillis(), what, body, clientId, uid = newUid())
        s = s.copy(ops = NotizenOutbox.add(s.ops, op, sending))
        save(s)
        NotizenOutboxWorker.enqueue(context)
        oid
    }.also { _changed.tryEmit(setOf(whatTouched(what))) }

    private fun whatTouched(what: String) = when (what) {
        NotizenOutbox.LIST -> "lists"
        NotizenOutbox.NOTEBOOK -> "notebooks"
        NotizenOutbox.NOTE -> "notes"
        else -> "tasks"
    }

    private fun newClientId(): String = java.util.UUID.randomUUID().toString()

    /** `POST tasks` ([body] of [NotizenJson.taskBody]; `listId` / `parentId` may name something made offline). */
    suspend fun createTask(body: JsonObject): NResult<NWritten<NTask>> {
        val cid = if (offlineEdits()) newClientId() else null
        val b = body.deepCopy().apply { cid?.let { addProperty("clientId", it) } }
        return write(NotizenOutbox.CREATE, NotizenOutbox.TASK, 0, b, cid) { _, x -> api.createTask(x).map { it.id to it } }.touch("tasks")
    }

    /** `PATCH tasks/<id>`: [fields] with `base` — the values of [seen] (plugin 1.1.0: a field changed elsewhere meanwhile → 409). */
    suspend fun patchTask(id: Long, fields: JsonObject, seen: NTaskDetail?): NResult<NWritten<NTaskDetail>> {
        val b = fields.deepCopy()
        if (seen != null && seen.task.id > 0 && offlineEdits()) b.add("base", NotizenOutbox.taskBase(seen, fields.keySet()))
        return write(NotizenOutbox.PATCH, NotizenOutbox.TASK, id, b, null) { sid, x -> api.patchTask(sid, x).map { sid to it } }.touch("tasks")
    }

    /** `DELETE tasks/<id>` (also a sub task). Gone already: done. */
    suspend fun deleteTask(id: Long): NResult<NWritten<Unit>> =
        write(NotizenOutbox.DELETE, NotizenOutbox.TASK, id, JsonObject(), null) { sid, _ -> api.deleteTask(sid).goneOk().map { sid to it } }.touch("tasks")

    /** `POST lists`; [groupId]: into that group (one of the server's, not one made offline). */
    suspend fun createList(name: String, area: String, groupId: Long? = null): NResult<NWritten<NList>> {
        val cid = if (offlineEdits()) newClientId() else null
        val b = JsonObject().apply {
            addProperty("name", name.take(120))
            addProperty("area", area)
            groupId?.takeIf { it > 0 }?.let { addProperty("groupId", it) }
            cid?.let { addProperty("clientId", it) }
        }
        return write(NotizenOutbox.CREATE, NotizenOutbox.LIST, 0, b, cid) { _, x -> api.createListBody(x).map { it.id to it } }.touch("lists")
    }

    suspend fun createNotebook(name: String, area: String): NResult<NWritten<NNotebook>> {
        val cid = if (offlineEdits()) newClientId() else null
        val b = JsonObject().apply { addProperty("name", name.take(80)); addProperty("area", area); cid?.let { addProperty("clientId", it) } }
        return write(NotizenOutbox.CREATE, NotizenOutbox.NOTEBOOK, 0, b, cid) { _, x -> api.createNotebookBody(x).map { it.id to it } }.touch("notebooks")
    }

    /** `POST notes` ([NotizenJson.noteBody] or, in a Tresor, [NotizenJson.vaultNoteBody]: ciphertext only). */
    suspend fun createNote(body: JsonObject): NResult<NWritten<NNote>> {
        val cid = if (offlineEdits()) newClientId() else null
        val b = body.deepCopy().apply { cid?.let { addProperty("clientId", it) } }
        return write(NotizenOutbox.CREATE, NotizenOutbox.NOTE, 0, b, cid) { _, x -> api.createNote(x).map { it.summary.id to it } }.touch("notes")
    }

    /** `PATCH notes/<id>` (with `rev`: a change made elsewhere meanwhile → 409). */
    suspend fun patchNote(id: Long, body: JsonObject): NResult<NWritten<NNote>> =
        write(NotizenOutbox.PATCH, NotizenOutbox.NOTE, id, body, null) { sid, x -> api.patchNote(sid, x).map { sid to it } }.touch("notes")

    suspend fun deleteNote(id: Long): NResult<NWritten<Unit>> =
        write(NotizenOutbox.DELETE, NotizenOutbox.NOTE, id, JsonObject(), null) { sid, _ -> api.deleteNote(sid).goneOk().map { sid to it } }.touch("notes")

    private fun <T> NResult<NWritten<T>>.touch(what: String): NResult<NWritten<T>> {
        if (this is NResult.Ok && !value.queued) _changed.tryEmit(setOf(what))
        return this
    }

    private fun <A, B> NResult<A>.map(f: (A) -> B): NResult<B> = when (this) {
        is NResult.Ok -> NResult.Ok(f(value))
        is NResult.Failed -> this
        NResult.Offline -> NResult.Offline
        NResult.NotConfigured -> NResult.NotConfigured
    }

    /** A delete of something gone already (404, 410) is done. */
    private fun NResult<Unit>.goneOk(): NResult<Unit> = if (this is NResult.Failed && (http == 404 || http == 410)) NResult.Ok(Unit) else this

    // ── conflicts found in the background ───────────────────

    /** The change of [what] [id] the server refused (409), waiting for the person. */
    fun conflictFor(what: String, id: Long): NotizenOutbox.Op? =
        NotizenOutbox.conflictFor(_pending.value, what, id) ?: serverId(id)?.let { NotizenOutbox.conflictFor(_pending.value, what, it) }

    /** „Meine behalten“: the waiting change is sent again without `base` / `rev` ([body] instead: a Tresor note sealed again). */
    suspend fun keepMine(op: NotizenOutbox.Op, body: JsonObject? = null) {
        outboxLock.withLock {
            val s = _state.value
            save(s.copy(ops = s.ops.map { if (it.uid == op.uid) NotizenOutbox.keepMine(it, body) else it }))
        }
        _changed.tryEmit(setOf(whatTouched(op.what.orEmpty())))
        flushSoon()
    }

    /** „Die andere laden“: the waiting change is dropped. */
    suspend fun dropChange(op: NotizenOutbox.Op) {
        outboxLock.withLock {
            val s = _state.value
            save(s.copy(ops = s.ops.filterNot { it.uid == op.uid }))
        }
        _changed.tryEmit(setOf(whatTouched(op.what.orEmpty())))
    }

    // ── the outbox ──────────────────────────────────────────

    private suspend fun enqueue(op: NotizenOutbox.Op) {
        outboxLock.withLock {
            val s = _state.value
            save(s.copy(ops = NotizenOutbox.add(s.ops, op, sending)))
            NotizenOutboxWorker.enqueue(context)
        }
        _changed.tryEmit(setOf("tasks", "items"))
    }

    /** Inside [outboxLock]. */
    private suspend fun save(s: NotizenOutbox.State) {
        _state.value = s
        _pending.value = s.ops
        cache.putOutbox(s)
    }

    /** One op to the server: the server's id of what it made (a create), else 0. */
    private suspend fun send(op: NotizenOutbox.Op, id: Long, body: JsonObject): NResult<Long> = when (op.kind) {
        NotizenOutbox.TASK -> api.completeTask(id, op.done).map { 0L }
        NotizenOutbox.ITEM -> api.completeItem(id, op.done).map { 0L }
        NotizenOutbox.CREATE -> when (op.what) {
            NotizenOutbox.TASK -> api.createTask(body).map { it.id }
            NotizenOutbox.LIST -> api.createListBody(body).map { it.id }
            NotizenOutbox.NOTEBOOK -> api.createNotebookBody(body).map { it.id }
            else -> api.createNote(body).map { revs[it.summary.id] = it.summary.rev; it.summary.id }
        }
        NotizenOutbox.ACTION -> (if (op.what == NotizenOutbox.MOVE) api.moveTask(id, body) else api.snoozeTaskBody(id, body)).map { 0L }
        NotizenOutbox.PATCH -> if (op.what == NotizenOutbox.NOTE) api.patchNote(id, body).map { revs[id] = it.summary.rev; 0L } else api.patchTask(id, body).map { 0L }
        else -> (if (op.what == NotizenOutbox.NOTE) api.deleteNote(id) else api.deleteTask(id)).goneOk().map { 0L }
    }

    /**
     * Sends what waits, in order; false while the server cannot be reached. A create's answer gives the server's id
     * for everything behind it; a change refused with 409 stays for the person; anything else refused for good (gone,
     * not allowed) no longer waits.
     */
    suspend fun flush(): Boolean = flushLock.withLock {
        ensureLoaded()
        var changed = false
        val skip = mutableSetOf<String>()
        while (true) {
            val op = outboxLock.withLock {
                _state.value.ops.firstOrNull { !it.conflict && it.uid !in skip }?.also { sending = it.uid }
            } ?: break
            val ids = _state.value.ids
            val id = if (op.kind == NotizenOutbox.CREATE) 0L else NotizenOutbox.target(op, ids)
            val body = NotizenOutbox.prepared(op, ids)
            if (id == null || body == null) {
                // made in something that never reached the server: it cannot be sent
                Timber.i("Notizen: a waiting %s lost what it was made in", op.kind)
                outboxLock.withLock { sending = null; val s = _state.value; save(s.copy(ops = s.ops.filterNot { it.uid == op.uid })) }
                changed = true
                continue
            }
            val r = send(op, id, body)
            if (r is NResult.Offline || r is NResult.NotConfigured) {
                outboxLock.withLock { sending = null }
                if (changed) _changed.tryEmit(emptySet())
                return@withLock false
            }
            outboxLock.withLock {
                sending = null
                var s = _state.value
                when {
                    r is NResult.Ok -> {
                        s = s.copy(ops = s.ops.filterNot { it.uid == op.uid })
                        if (op.kind == NotizenOutbox.CREATE && r.value > 0) s = NotizenOutbox.mapped(s, op.id, r.value)
                    }
                    r is NResult.Failed && NotizenOutbox.conflictOf(op, r.http, r.code) -> {
                        s = s.copy(ops = s.ops.map { if (it.uid == op.uid) it.copy(conflict = true) else it })
                        skip.add(op.uid)
                    }
                    else -> {
                        Timber.i("Notizen: a waiting %s was refused (%s)", op.kind, (r as? NResult.Failed)?.code)
                        s = s.copy(ops = s.ops.filterNot { it.uid == op.uid })
                    }
                }
                save(s)
            }
            changed = true
        }
        if (changed) _changed.tryEmit(emptySet())
        true
    }

    private fun flushSoon() {
        if (_pending.value.any { !it.conflict }) scope.launch { flush() }
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

    /** The push stream (re)connected: what changed meanwhile is fetched again, what waits goes out. */
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

    companion object {
        /** `GET me` → features: creates, changes and deletes may be sent again (plugin 1.1.0). */
        const val FEATURE_OFFLINE = "offlineEdits"
    }
}

/** Sends what was made or changed without a connection once there is a network again. */
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
