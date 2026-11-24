package com.gatecontrol.android.tv.notizen

import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NParsedLine
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.notizen.NView
import com.gatecontrol.android.notizen.NotizenApi
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.tv.zuhause.TvSourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the TV loaded of Notizen: the left column, the views and notebooks shown, each with how it answered. */
data class TvNotizenData(
    val nav: TvNotesNav? = null,
    val navState: TvSourceState = TvSourceState.LOADING,
    /** Per [TvNotesPlace.key]: the view of a smart list or list. */
    val views: Map<String, NView> = emptyMap(),
    /** Per notebook: its notes (the readable ones). */
    val notes: Map<Long, List<NNoteSummary>> = emptyMap(),
    /** Per [TvNotesPlace.key]: how its last load went. */
    val states: Map<String, TvSourceState> = emptyMap(),
) {
    fun state(place: TvNotesPlace): TvSourceState = states[place.key] ?: TvSourceState.LOADING
}

/** How a change went: done, refused by the server (read only, no right), or no connection. */
enum class TvNotesDone { OK, REFUSED, OFFLINE }

/**
 * „Notizen“ of GateControl TV: Mein Tag, Demnächst, the lists and notebooks of the area „Privat“ through the Notizen
 * plugin's app API ([NotizenApi], the phone app's), with the rights of the person who paired the TV. Ticking off applies
 * at once on the page and goes to the server right away; there is no outbox on the TV (no connection: it goes back and
 * says so). Tresor notebooks, the area „Arbeit“ and places are never asked for.
 */
@Singleton
class TvNotizen @Inject constructor(private val api: NotizenApi) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val navMutex = Mutex()

    private val _data = MutableStateFlow(TvNotizenData())
    val data: StateFlow<TvNotizenData> = _data.asStateFlow()

    @Volatile
    private var lastNav = 0L

    @Volatile
    private var navDay: LocalDate? = null

    /** The left column and Mein Tag (also the start row's counts); at most every [MIN_GAP_MS] unless [force]d or a new day. */
    suspend fun refresh(force: Boolean = false, today: LocalDate = LocalDate.now()) = navMutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && navDay == today && now - lastNav < MIN_GAP_MS) return@withLock
        lastNav = now
        navDay = today
        val r = api.nav(TvNotizenLogic.AREA)
        _data.update { d ->
            when (r) {
                is NResult.Ok -> d.copy(nav = TvNotizenLogic.nav(r.value.first), navState = TvSourceState.OK)
                else -> d.copy(navState = state(r))
            }
        }
        if (r is NResult.Ok) load(TvNotesPlace.MyDay)
    }

    fun refreshAsync(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    /** The tasks of a smart list or list, or the notes of a notebook. */
    suspend fun load(place: TvNotesPlace) {
        val name = TvNotizenLogic.viewName(place)
        if (name != null) {
            val r = api.view(name, TvNotizenLogic.AREA)
            _data.update { d ->
                if (r is NResult.Ok) {
                    d.copy(views = d.views + (place.key to r.value.first), states = d.states + (place.key to TvSourceState.OK))
                } else {
                    d.copy(states = d.states + (place.key to state(r)))
                }
            }
            return
        }
        val id = (place as TvNotesPlace.Notebook).id
        val r = api.notes(TvNotizenLogic.AREA, notebookId = id)
        _data.update { d ->
            if (r is NResult.Ok) {
                d.copy(notes = d.notes + (id to TvNotizenLogic.notes(r.value.first.notes)), states = d.states + (place.key to TvSourceState.OK))
            } else {
                d.copy(states = d.states + (place.key to state(r)))
            }
        }
    }

    fun loadAsync(place: TvNotesPlace) {
        scope.launch { load(place) }
    }

    /** OK on a row: ticked (or open again) — shown at once in every loaded view, then sent. */
    suspend fun tick(entry: NEntry, done: Boolean): TvNotesDone {
        if (!TvNotizenLogic.canTick(entry)) return TvNotesDone.REFUSED
        mark(entry.uid, done)
        val r = when (entry) {
            is NTask -> api.completeTask(entry.id, done)
            is NItem -> api.completeItem(entry.id, done)
        }
        if (r !is NResult.Ok) {
            mark(entry.uid, !done)
            return outcome(r)
        }
        // a repeating task has its next one now, counts change: the views as the server says
        reloadShown()
        return TvNotesDone.OK
    }

    /** The details of a task (its note and sub tasks); null when they cannot be had. */
    suspend fun detail(id: Long): NTaskDetail? = (api.task(id) as? NResult.Ok)?.value?.first

    /** A sub task ticked in the details. */
    suspend fun tickSub(id: Long, done: Boolean): TvNotesDone = outcome(api.completeTask(id, done))

    /** „Auf morgen verschieben“: the due day goes to tomorrow, the time stays. */
    suspend fun moveTomorrow(task: NTask): TvNotesDone {
        if (!TvNotizenLogic.canMove(task)) return TvNotesDone.REFUSED
        val r = api.moveTask(task.id, NotizenJson.moveBody("tomorrow"))
        if (r is NResult.Ok) reloadShown()
        return outcome(r)
    }

    /** A note to read; null when it cannot be had or is a Tresor's. */
    suspend fun note(id: Long): NNote? = TvNotizenLogic.readable((api.note(id) as? NResult.Ok)?.value?.first)

    /** The line as the server's quick entry reads it („morgen 17 Uhr“ → tomorrow, 17:00). */
    suspend fun parse(text: String): NResult<NParsedLine> {
        val line = TvNotizenLogic.line(text) ?: return NResult.Failed("invalid", 0)
        return api.parseLine(line, TvNotizenLogic.AREA)
    }

    /**
     * „Aufgabe hinzufügen“ in Mein Tag: the server reads the line again as the quick entry (`POST tasks { text }`); in
     * Mein Tag when it has no day or today's. [clientId]: the same task when it is sent again.
     */
    suspend fun add(text: String, parsed: NParsedLine?, today: LocalDate = LocalDate.now(), clientId: String = UUID.randomUUID().toString()): TvNotesDone {
        val line = TvNotizenLogic.line(text) ?: return TvNotesDone.REFUSED
        val body = NotizenJson.quickTaskBody(line, TvNotizenLogic.AREA, null, myDay = TvNotizenLogic.inMyDay(parsed?.due?.date, today), createList = false)
            .apply { addProperty("clientId", clientId) }
        val r = api.createTask(body)
        if (r is NResult.Ok) refresh(force = true)
        return outcome(r)
    }

    /** Unpaired: nothing of this login stays. */
    fun clear() {
        _data.value = TvNotizenData()
        lastNav = 0L
        navDay = null
    }

    private fun mark(uid: String, done: Boolean) = _data.update { d ->
        d.copy(views = d.views.mapValues { (_, v) -> TvNotizenLogic.withDone(v, uid, done) })
    }

    private suspend fun reloadShown() {
        for (key in _data.value.views.keys.toList()) {
            // Mein Tag comes with the left column below
            val place = when {
                key == TvNotizenLogic.PLANNED -> TvNotesPlace.Planned
                key.startsWith("list:") -> key.removePrefix("list:").toLongOrNull()?.let { TvNotesPlace.List(it) }
                else -> null
            } ?: continue
            load(place)
        }
        refresh(force = true)
    }

    private fun state(r: NResult<*>): TvSourceState = when (r) {
        is NResult.Ok -> TvSourceState.OK
        NResult.Offline, NResult.NotConfigured -> TvSourceState.OFFLINE
        is NResult.Failed -> TvSourceState.MISSING
    }

    private fun outcome(r: NResult<*>): TvNotesDone = when (r) {
        is NResult.Ok -> TvNotesDone.OK
        NResult.Offline, NResult.NotConfigured -> TvNotesDone.OFFLINE
        is NResult.Failed -> TvNotesDone.REFUSED
    }

    private companion object {
        const val MIN_GAP_MS = 60_000L
    }
}
