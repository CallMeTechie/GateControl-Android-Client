package com.gatecontrol.android.ui.notizen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NAttachment
import com.gatecontrol.android.notizen.NCached
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NMe
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NPersonPick
import com.gatecontrol.android.notizen.NRepeat
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NSearchResult
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.notizen.NView
import com.gatecontrol.android.notizen.NoteMd
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.notizen.NotizenOutbox
import com.gatecontrol.android.notizen.NotizenRepository
import com.gatecontrol.android.notizen.NotizenVaultSession
import com.gatecontrol.android.notizen.QuickParse
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A short result to show once: an error code of the plugin, `offline`, `queued`, `read_only`, `saved` … */
typealias NMessage = String

internal fun <T> NResult<T>.code(): String? = when (this) {
    is NResult.Ok -> null
    is NResult.Failed -> NotizenJson.reason(body)?.takeIf { it in REASONS } ?: code
    NResult.Offline -> "offline"
    NResult.NotConfigured -> "not_configured"
}

/** The `reason` of a refusal that has a text of its own (plugin 1.2.0: `owner`, `default`, `bound`). */
private val REASONS = setOf("login", "read_only", "off", "owner", "default", "bound", "parents_only", "family")

/** „Mein Tag“, „Aufgaben“ (the lists) and „Notizen“ — the tab of the plugin (mockups Main, Listen, Notizen). */
@HiltViewModel
class NotizenHomeViewModel @Inject constructor(
    private val repo: NotizenRepository,
    val vault: NotizenVaultSession,
    private val saved: SavedStateHandle,
    private val hub: com.gatecontrol.android.notizen.NotizenShopping,
    private val geofences: com.gatecontrol.android.notizen.NotizenGeofences,
) : ViewModel() {

    private val _near = MutableStateFlow<List<com.gatecontrol.android.notizen.NNearNow>>(emptyList())
    /**
     * Plugin 1.5.0 („Gerade in der Nähe“): the tasks whose place the phone is at right now — worked out on the phone
     * from its geofences; the server gets nothing of it. Empty with an older plugin.
     */
    val near: StateFlow<List<com.gatecontrol.android.notizen.NNearNow>> = _near.asStateFlow()

    private var nearJob: Job? = null

    private fun refreshNear() {
        nearJob?.cancel()
        nearJob = viewModelScope.launch {
            _near.value = if (_ui.value.me?.taskPlaces == true) runCatching { geofences.nearNow() }.getOrDefault(emptyList()) else emptyList()
        }
    }

    /** „Nicht hier“ of the card: this one place is not offered for the task any more (at once; sent now or later). */
    fun notHere(n: com.gatecontrol.android.notizen.NNearNow) {
        _near.update { l -> l.filterNot { it.task.taskId == n.task.taskId && it.fence.id == n.fence.id } }
        viewModelScope.launch {
            val r = geofences.notHere(n.task.taskId, n.fence.id)
            _message.value = when (r) {
                NotizenRepository.Acted.QUEUED -> "queued"
                NotizenRepository.Acted.READ_ONLY -> "read_only"
                NotizenRepository.Acted.FAILED -> "failed"
                else -> "place_not_here"
            }
        }
    }

    /** Plugin 1.4.0: the shopping lists as the phone has them (with what waits) — the side menu's „Einkaufslisten“. */
    val shopping: StateFlow<com.gatecontrol.android.familie.shopping.ShoppingSnapshot> = hub.data
        .map { com.gatecontrol.android.familie.shopping.ShoppingLogic.view(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), com.gatecontrol.android.familie.shopping.ShoppingSnapshot())

    /** A new shopping list of mine in the area shown (made on the phone at once, also offline) → [open] with its id. */
    fun createShopping(name: String, open: (String) -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch { open(hub.addList(name.trim().take(40), _ui.value.area)) }
    }


    data class Ui(
        val me: NMe? = null,
        /** `GET me` failed: the plugin is not there for this person (no licence, not allowed) or the server is offline. */
        val meError: String? = null,
        val area: String = NArea.PRIVATE,
        val nav: NNav? = null,
        val myDay: NView? = null,
        val pinned: List<NNoteSummary> = emptyList(),
        val notes: List<NNoteSummary> = emptyList(),
        val notebook: Long? = null,
        /** A tag the notes are filtered by (`#auto`). */
        val tag: String? = null,
        val loading: Boolean = true,
        val offline: Boolean = false,
        val savedAt: Long = 0,
        val pending: Int = 0,
        /** Plugin 1.1.0: what is made or changed offline waits and is sent later. */
        val offlineEdits: Boolean = false,
        /** Pulled down: loading again. */
        val refreshing: Boolean = false,
        /** A button of the morning hint „Gestern offen geblieben“ opened the app: the question or the choice of days. */
        val yAsk: YAsk? = null,
    )

    /**
     * What a button of the morning hint asks (plugin 1.3.0): [action] `done` — „Alle N abhaken?“ once; `move` — the
     * choice „Morgen · Am Wochenende · Anderes Datum“ — for the tasks [ids].
     */
    data class YAsk(val action: String, val ids: List<Long>, val options: List<com.gatecontrol.android.notizen.NMoveOption> = emptyList())

    /** The morning hint's button waiting for „Mein Tag“ to be loaded; [yTriedOther]: the other area was looked at. */
    private var yPending: com.gatecontrol.android.notizen.NotizenPush.Yesterday? = null
    private var yTriedOther = false

    private val _ui = MutableStateFlow(Ui(notebook = saved.get<Long>(KEY_NB)?.takeIf { it > 0 }))
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    /** „Mein Tag“ or the notes (the lists open as pages of their own from the side menu). */
    private val _segment = MutableStateFlow(saved.get<String>(KEY_SEG)?.takeIf { it == SEG_NOTES } ?: SEG_DAY)
    val segment: StateFlow<String> = _segment.asStateFlow()

    /** The groups closed in the side menu. */
    private val _collapsed = MutableStateFlow(saved.get<LongArray>(KEY_COLLAPSED)?.toSet().orEmpty())
    val collapsed: StateFlow<Set<Long>> = _collapsed.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repo.changed.collect {
                delay(300)
                load(quiet = true)
            }
        }
        viewModelScope.launch { repo.pending.collect { p -> _ui.update { it.copy(pending = p.count { !it.conflict }) } } }
        // the phone entered or left a place of a task while the page is open
        viewModelScope.launch { geofences.snapshot.collect { refreshNear() } }
        load()
    }

    fun consumeMessage() { _message.value = null }

    /** „Umzug abgeschlossen“ was read („OK“ or „Ansehen“, plugin 1.6.0): it does not come again. */
    fun familySeen() {
        _ui.update { u -> u.copy(me = u.me?.let { m -> m.copy(familyTasks = m.familyTasks?.let { f -> f.copy(moved = f.moved?.copy(seen = true)) }) }) }
        viewModelScope.launch { repo.familySeen() }
    }

    fun setSegment(s: String) {
        _segment.value = s
        saved[KEY_SEG] = s
    }

    fun toggleGroup(id: Long) {
        _collapsed.update { if (id in it) it - id else it + id }
        saved[KEY_COLLAPSED] = _collapsed.value.toLongArray()
    }

    /** Pulled down („Aktualisieren“). */
    fun refresh() {
        _ui.update { it.copy(refreshing = true) }
        load(quiet = true)
    }

    fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!quiet) _ui.update { it.copy(loading = true) }
            val me = repo.refreshMe()
            if (me == null) {
                val code = repo.api.me().code()
                _ui.update { it.copy(loading = false, refreshing = false, meError = code ?: "failed") }
                return@launch
            }
            // a push: its area, its notebook (the notes with it)
            val link = com.gatecontrol.android.notizen.NotizenDeepLink.take()
            link?.area?.let { a -> if (a != repo.currentArea()) repo.setArea(a) }
            link?.notebook?.let { nb ->
                _ui.update { it.copy(notebook = nb, tag = null) }
                saved[KEY_NB] = nb
                setSegment(SEG_NOTES)
            }
            com.gatecontrol.android.notizen.NotizenDeepLink.takeHint()?.let { _message.value = it }
            // the morning hint „Gestern offen geblieben“: „Mein Tag“ with the card
            link?.yesterday?.let { y ->
                yPending = y
                yTriedOther = link.area != null
                setSegment(SEG_DAY)
            }
            val area = repo.currentArea()
            _ui.update { it.copy(me = me.value, meError = null, area = area, offlineEdits = me.value.has(NotizenRepository.FEATURE_OFFLINE)) }
            // plugin 1.4.0: the shopping lists of the side menu (their copy on the phone follows)
            if (me.value.shoppingLists && !me.offline) hub.syncAsync()
            val nav = repo.nav(area)
            val day = repo.view("myday", area)
            val nb = _ui.value.notebook
            val tag = _ui.value.tag
            val pinned = if (nb == null && tag == null) repo.notes(area, null, null, pinned = true) else null
            val notes = repo.notes(area, nb, tag)
            val off = listOf(nav, day, notes).any { (it as? NResult.Ok<NCached<*>>)?.value?.offline == true } || me.offline
            _ui.update { u ->
                u.copy(
                    loading = false,
                    nav = nav.valueOrNull()?.value ?: u.nav,
                    myDay = day.valueOrNull()?.value ?: u.myDay,
                    pinned = pinned?.valueOrNull()?.value?.notes.orEmpty(),
                    notes = notes.valueOrNull()?.value?.notes?.let { list -> if (nb == null && tag == null) list.filterNot { it.pinned } else list } ?: u.notes,
                    offline = off,
                    savedAt = (day.valueOrNull()?.savedAt ?: 0),
                    refreshing = false,
                )
            }
            if (nav is NResult.Failed) _message.value = nav.code()
            refreshNear()
            if (yPending != null && !openYesterday(me.value, day.valueOrNull()?.value)) return@launch
            // the notebook of a push is not there (any more): the notes of all notebooks, and a hint
            val shownNav = nav.valueOrNull()?.value
            if (link?.notebook != null && shownNav != null && shownNav.notebooks.none { it.id == link.notebook }) {
                _message.value = "gone"
                selectNotebook(null)
            }
        }
    }

    fun setArea(area: String) {
        repo.setArea(area)
        _ui.update { it.copy(area = NArea.of(area), nav = null, myDay = null, notes = emptyList(), pinned = emptyList(), notebook = null) }
        saved[KEY_NB] = 0L
        load()
    }

    fun selectTag(tag: String?) {
        _ui.update { it.copy(tag = tag?.takeIf { t -> t != it.tag }) }
        load(quiet = true)
    }

    fun selectNotebook(id: Long?) {
        _ui.update { it.copy(notebook = id, tag = null) }
        saved[KEY_NB] = id ?: 0L
        load(quiet = true)
    }

    fun toggle(entry: NEntry) {
        val done = !entry.done
        // shown at once, corrected by the next load
        _ui.update { u -> u.copy(myDay = u.myDay?.let { v -> com.gatecontrol.android.notizen.NotizenOutbox.overlay(v, listOf(com.gatecontrol.android.notizen.NotizenOutbox.Op(if (entry is NTask) "task" else "item", entry.uid.substring(2).toLong(), done, 0))) }) }
        viewModelScope.launch {
            val (tick, _) = repo.complete(entry, done)
            when (tick) {
                NotizenRepository.Tick.QUEUED -> _message.value = "queued"
                NotizenRepository.Tick.READ_ONLY -> { _message.value = "read_only"; load(quiet = true) }
                NotizenRepository.Tick.FAILED -> { _message.value = "failed"; load(quiet = true) }
                NotizenRepository.Tick.DONE -> Unit
            }
        }
    }

    // ── „Gestern offen geblieben“ (plugin 1.3.0) ────────────

    /**
     * The morning hint opened the app: its card may be in the other area (the hint counts both) — then that one is
     * shown; „Erledigt“ asks once for the tasks of the hint still open, „Verschieben“ offers the days. False: loading
     * again (the other area).
     */
    private suspend fun openYesterday(me: NMe, day: NView?): Boolean {
        val y = yPending ?: return true
        if (!me.yesterdayOpen) { yPending = null; return true }
        val card = com.gatecontrol.android.notizen.NotizenYesterday.card(day, true)
        if (card == null && !yTriedOther) {
            yTriedOther = true
            val other = if (_ui.value.area == NArea.WORK) NArea.PRIVATE else NArea.WORK
            val there = repo.view("myday", other).valueOrNull()?.value
            if (com.gatecontrol.android.notizen.NotizenYesterday.card(there, true) != null) {
                setArea(other)
                return false
            }
        }
        yPending = null
        if (y.action == com.gatecontrol.android.notizen.NotizenPush.Y_OPEN) return true
        // what is still open, in both areas (offline: what the hint named)
        val open = repo.api.yesterday(null).valueOrNull()
        val ids = com.gatecontrol.android.notizen.NotizenYesterday.asked(y.ids, open?.items?.map { it.id } ?: card?.items?.map { it.id }.takeIf { y.ids.isEmpty() })
        if (ids.isEmpty()) return true
        _ui.update { it.copy(yAsk = YAsk(y.action, ids, open?.moveOptions ?: card?.moveOptions.orEmpty())) }
        return true
    }

    fun dismissYesterdayAsk() = _ui.update { it.copy(yAsk = null) }

    private fun acted(r: NotizenRepository.Acted) {
        when (r) {
            NotizenRepository.Acted.QUEUED -> _message.value = "queued"
            NotizenRepository.Acted.READ_ONLY -> { _message.value = "read_only"; load(quiet = true) }
            NotizenRepository.Acted.GONE -> { _message.value = "gone"; load(quiet = true) }
            NotizenRepository.Acted.FAILED -> { _message.value = "failed"; load(quiet = true) }
            NotizenRepository.Acted.DONE -> Unit
        }
    }

    /** Gone from the card (and from „Überfällig“) at once; the next load says how it is. */
    private fun decided(ids: Collection<Long>) = _ui.update { u -> u.copy(myDay = u.myDay?.let { com.gatecontrol.android.notizen.NotizenYesterday.without(it, ids.toSet()) }) }

    /** A row's „Erledigt“. */
    fun yesterdayDone(task: NTask) {
        decided(listOf(task.id))
        viewModelScope.launch {
            when (repo.completeTask(task.id, true).first) {
                NotizenRepository.Tick.QUEUED -> _message.value = "queued"
                NotizenRepository.Tick.READ_ONLY -> { _message.value = "read_only"; load(quiet = true) }
                NotizenRepository.Tick.FAILED -> { _message.value = "failed"; load(quiet = true) }
                NotizenRepository.Tick.DONE -> Unit
            }
        }
    }

    /** A row's „Heute“ ([to] `today`) or „Verschieben“ (`tomorrow`, `weekend`, a date). */
    fun yesterdayMove(task: NTask, to: String) {
        decided(listOf(task.id))
        viewModelScope.launch { acted(repo.moveTask(task.id, to)) }
    }

    /** „Alle heute“ at the head of the card, „Alle N abhaken“ and the choice of days of the morning hint. */
    fun yesterdayAll(action: String, ids: List<Long>, to: String? = null) {
        _ui.update { it.copy(yAsk = null) }
        if (ids.isEmpty()) return
        decided(ids)
        viewModelScope.launch { acted(repo.yesterdayAll(action, ids, to)) }
    }

    fun createNotebook(name: String) {
        viewModelScope.launch {
            val r = repo.createNotebook(name, _ui.value.area)
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else null
            load(quiet = true)
        }
    }

    /** The name of a notebook: its own, or the decrypted one of a Tresor (null while locked). */
    fun notebookName(nb: NNotebook): String? = nb.name ?: nb.vault?.let { vault.name(it.id, it.nameCipher) }

    companion object {
        const val SEG_DAY = "day"
        const val SEG_NOTES = "notes"
        private const val KEY_SEG = "seg"
        private const val KEY_COLLAPSED = "collapsed"
        private const val KEY_NB = "nb"
    }
}

/** A list of the plugin: Wichtig, Geplant, Mir zugewiesen, an own list or a source (`name` of `GET views/<name>`). */
@HiltViewModel
class NotizenViewViewModel @Inject constructor(
    private val repo: NotizenRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val name: String = saved.get<String>("name").orEmpty()

    data class Ui(
        val view: NView? = null,
        val loading: Boolean = true,
        val offline: Boolean = false,
        val error: String? = null,
        val me: NMe? = null,
        val gone: Boolean = false,
        /** The side menu's data of the area (the groups for „In Gruppe verschieben“). */
        val nav: NNav? = null,
        val area: String = NArea.PRIVATE,
        /** The filter of a shared list (Alle, Mir zugewiesen, a person, Niemandem). */
        val filter: com.gatecontrol.android.notizen.NotizenLists.Filter = com.gatecontrol.android.notizen.NotizenLists.Filter.All,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repo.changed.collect { delay(300); load() } }
        load()
    }

    fun consumeMessage() { _message.value = null }

    /** Back from a task that is not there any more: its hint. */
    fun takeHint() { com.gatecontrol.android.notizen.NotizenDeepLink.takeHint()?.let { _message.value = it } }

    fun load() {
        viewModelScope.launch {
            val me = repo.meOrCached()
            val area = repo.currentArea()
            val r = repo.view(name, area)
            _ui.update { it.copy(me = me, area = area, view = r.valueOrNull()?.value ?: it.view, loading = false, offline = r.valueOrNull()?.offline == true, error = r.code(), gone = r is NResult.Failed && r.http == 404) }
            if (name.startsWith("list:")) repo.nav(area).valueOrNull()?.value?.let { n -> _ui.update { it.copy(nav = n) } }
        }
    }

    fun setFilter(f: com.gatecontrol.android.notizen.NotizenLists.Filter) = _ui.update { it.copy(filter = f) }

    /** The little picture at a task: assigned to someone of the list, or to nobody. */
    fun assign(task: NTask, personId: Long?) {
        if (task.assignee?.id == personId) return
        viewModelScope.launch {
            val r = repo.patchTask(task.id, NotizenJson.patch("assigneeId" to personId), null)
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else null
            load()
        }
    }

    fun toggle(entry: NEntry) {
        viewModelScope.launch {
            when (repo.complete(entry, !entry.done).first) {
                NotizenRepository.Tick.QUEUED -> _message.value = "queued"
                NotizenRepository.Tick.READ_ONLY -> _message.value = "read_only"
                NotizenRepository.Tick.FAILED -> _message.value = "failed"
                NotizenRepository.Tick.DONE -> Unit
            }
            load()
        }
    }

    /** A new task in this list (the field at the end of an own list). */
    fun add(title: String) {
        val listId = name.removePrefix("list:").toLongOrNull() ?: return
        viewModelScope.launch {
            val r = repo.createTask(NotizenJson.taskBody(title, listId = listId))
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else null
            if (r is NResult.Ok) load()
        }
    }
}

/** One task (mockup Aufgabe): sub tasks, Mein Tag, due, reminder, repeat, assignee, list, attachments, note. */
@HiltViewModel
class NotizenTaskViewModel @Inject constructor(
    private val repo: NotizenRepository,
    private val hub: com.gatecontrol.android.notizen.NotizenShopping,
    private val geofences: com.gatecontrol.android.notizen.NotizenGeofences,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: Long = saved.get<String>("id")?.toLongOrNull() ?: 0L

    /** Plugin 1.5.0 (`places`): the choice „Welcher Baumarkt?“ for this task's place. */
    val placeChooser = PlaceChooser(repo, viewModelScope) { geofences.position() }

    /** Can this phone remind at a place (Play services, the location also in the background). */
    fun placeReadiness() = geofences.readiness()

    /** The location was just allowed: the places of my tasks become geofences now. */
    fun placePermissionChanged() = geofences.syncSoon()

    fun choosePlace() {
        val u = _ui.value
        placeChooser.open(u.detail?.task?.place, null, u.me)
    }

    /** What the choice gave ([place] as `PATCH tasks` takes it; null: „Ohne Ort“). A place needs the server: never queued. */
    fun setPlace(place: JsonObject?) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = repo.setTaskPlace(id, place)
            _ui.update { it.copy(busy = false, detail = (r as? NResult.Ok)?.value ?: it.detail) }
            _message.value = when {
                r is NResult.Ok -> null
                r is NResult.Offline -> "place_offline"
                else -> r.code()
            }
        }
    }

    /** „Annehmen“ / „Ablehnen“: someone else set the place — my phone watches it only once I agree. */
    fun placeConsent(accept: Boolean) {
        viewModelScope.launch {
            val r = repo.placeConsent(id, accept)
            _message.value = when {
                r is NResult.Ok -> null
                r is NResult.Offline -> "place_offline"
                r is NResult.Failed && r.http == 403 -> "place_consent_other"
                else -> r.code()
            }
            load()
        }
    }

    data class Ui(
        val detail: NTaskDetail? = null,
        val me: NMe? = null,
        val people: List<NPersonPick> = emptyList(),
        /** Plugin 1.6.0: the list the task is in (its people for „Wer ist dran?“); null: not known. */
        val list: com.gatecontrol.android.notizen.NList? = null,
        val loading: Boolean = true,
        val offline: Boolean = false,
        val gone: Boolean = false,
        val busy: Boolean = false,
        /** A change of this phone was refused (409, now or in the background): „Meine behalten“ / „Die andere laden“. */
        val conflict: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    /** The change refused right now (online): sent again without `base` for „Meine behalten“. */
    private var refused: JsonObject? = null

    init {
        load()
        viewModelScope.launch { repo.api.people().valueOrNull()?.let { p -> _ui.update { it.copy(people = p) } } }
        // what waits was sent (or refused): the page as it is now
        // … or the entries of the list it carries changed („Einkauf 3/8“)
        viewModelScope.launch { repo.changed.collect { if (it.isEmpty() || "tasks" in it || "shopping" in it) { delay(300); load() } } }
        // the lists to choose from for „Angehängte Liste“
        hub.syncAsync()
    }

    fun consumeMessage() { _message.value = null }

    fun load() {
        viewModelScope.launch {
            val me = repo.meOrCached()
            when (val r = repo.task(id)) {
                is NResult.Ok -> {
                    _ui.update {
                        it.copy(detail = r.value.value, me = me, loading = false, offline = r.value.offline, conflict = refused != null || repo.conflictFor(NotizenOutbox.TASK, id) != null)
                    }
                    // plugin 1.6.0: the people of the task's list, for „Wer ist dran?“ and „Abhaken für …“
                    val d = r.value.value
                    if (me?.taskTurns == true && d.task.listId != null && _ui.value.list?.id != d.task.listId) {
                        val list = repo.nav(d.listArea?.let { NArea.of(it) } ?: repo.currentArea()).valueOrNull()?.value?.lists?.firstOrNull { it.id == d.task.listId }
                        _ui.update { it.copy(list = list) }
                    }
                }
                is NResult.Failed -> {
                    val gone = r.http == 404 || r.code == "not_found"
                    _ui.update { it.copy(loading = false, gone = gone, me = me) }
                    _message.value = r.code()
                }
                else -> _ui.update { it.copy(loading = false, offline = true, me = me) }
            }
        }
    }

    private fun patch(vararg fields: Pair<String, Any?>) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val body = NotizenJson.patch(*fields)
            val r = repo.patchTask(id, body, _ui.value.detail)
            when {
                r is NResult.Ok && !r.value.queued -> _ui.update { it.copy(detail = r.value.value ?: it.detail, busy = false) }
                r is NResult.Ok -> {
                    _ui.update { it.copy(busy = false) }
                    if (r.value.offline) _message.value = "queued"
                    load()
                }
                r is NResult.Failed && r.http == 409 && r.code == "conflict" -> {
                    // someone changed the same field meanwhile: the person decides
                    refused = body
                    _ui.update { it.copy(busy = false, conflict = true) }
                }
                else -> {
                    _ui.update { it.copy(busy = false) }
                    _message.value = r.code()
                }
            }
        }
    }

    /** „Meine behalten“: this phone's change over the other one. */
    fun keepMine() {
        viewModelScope.launch {
            val now = refused
            refused = null
            _ui.update { it.copy(conflict = false) }
            if (now != null) {
                val r = repo.patchTask(id, now, null)
                _message.value = r.code()
            } else {
                repo.conflictFor(NotizenOutbox.TASK, id)?.let { repo.keepMine(it) }
            }
            load()
        }
    }

    /** „Die andere laden“: this phone's change is dropped, the task as it is now. */
    fun takeTheirs() {
        viewModelScope.launch {
            refused = null
            _ui.update { it.copy(conflict = false) }
            repo.conflictFor(NotizenOutbox.TASK, id)?.let { repo.dropChange(it) }
            load()
        }
    }

    fun setTitle(t: String) { if (t.isNotBlank() && t != _ui.value.detail?.task?.title) patch("title" to t.trim().take(500)) }
    fun setNote(n: String) { if (n != _ui.value.detail?.note.orEmpty()) patch("note" to n) }
    fun setImportant(v: Boolean) = patch("important" to v)
    fun setMyDay(v: Boolean) = patch("myDay" to v)
    fun setDue(d: NDue?) = patch("due" to NotizenJson.dueBody(d))
    fun setRemind(d: NDue?) = patch("remindAt" to NotizenJson.dueBody(d))
    fun setRepeat(r: NRepeat.Rule?) = patch("repeat" to (r?.let { NRepeat.toJson(it) }))
    fun setAssignee(id: Long?) = patch("assigneeId" to id)
    fun setList(listId: Long) = patch("listId" to listId)

    /**
     * „Wer ist dran?“ (plugin 1.6.0): [mode] [NotizenTurns.ONE] makes an ordinary task again (the first of [people] is
     * assigned), `rotate` / `first` go to [people] — with „reihum“ the person who is on stays on when still among them.
     */
    fun setTurn(mode: String, people: List<Long>) {
        val t = _ui.value.detail?.task ?: return
        val turn = NotizenJson.turn(mode)
        val fields = NotizenJson.turnFields(turn, people).toMutableList()
        when {
            fields.first().second == null -> fields += "assigneeId" to people.firstOrNull()
            turn == com.gatecontrol.android.notizen.TurnWords.ROTATE -> fields += "assigneeId" to (t.assignee?.id?.takeIf { it in people } ?: people.first())
        }
        patch(*fields.toTypedArray())
    }

    /** The stars of a task of the family's list (plugin 1.6.0; the parents only — the server says so too). */
    fun setStars(n: Int) {
        if (_ui.value.detail?.task?.stars != n) patch("stars" to n.coerceIn(0, 10))
    }

    /** A parent ticks the task off for [userId] of the family (the stars go to that person). */
    fun completeFor(userId: Long) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val r = repo.completeTaskFor(id, userId)
            _ui.update { it.copy(busy = false) }
            _message.value = if (r is NResult.Offline) "offline_write" else r.code()
            load()
        }
    }

    /** Plugin 1.4.0 (`taskList`): the shopping list this task carries ([listNo]: the hub's number of it; null: none). */
    fun setAttached(listNo: Long?) {
        if (_ui.value.detail?.task?.attachedList?.id != listNo) patch("attachedListId" to listNo)
    }

    /** The shopping lists to choose from (the phone's copy, with what waits). */
    val shopping: StateFlow<com.gatecontrol.android.familie.shopping.ShoppingSnapshot> = hub.data
        .map { com.gatecontrol.android.familie.shopping.ShoppingLogic.view(it) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), com.gatecontrol.android.familie.shopping.ShoppingSnapshot())

    /** „Neue Liste“ in the choice: made (also offline), sent, then attached by the number the server gave it. */
    fun attachNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val uid = hub.addList(name, _ui.value.detail?.listArea ?: repo.currentArea())
            hub.sync()
            val no = hub.current().server.lists.firstOrNull { it.id == uid }?.listNo
            _ui.update { it.copy(busy = false) }
            if (no != null) setAttached(no) else _message.value = "list_later"
        }
    }

    fun complete() {
        val t = _ui.value.detail?.task ?: return
        viewModelScope.launch {
            val (tick, next) = repo.completeTask(id, !t.done)
            _message.value = when (tick) {
                NotizenRepository.Tick.QUEUED -> "queued"
                NotizenRepository.Tick.READ_ONLY -> "read_only"
                NotizenRepository.Tick.FAILED -> "failed"
                NotizenRepository.Tick.DONE -> if (next != null) "next" else null
            }
            load()
        }
    }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val r = repo.createTask(NotizenJson.taskBody(title.trim(), parentId = id))
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else null
            load()
        }
    }

    fun toggleSub(subId: Long, done: Boolean) {
        viewModelScope.launch {
            _message.value = when (repo.completeTask(subId, done).first) {
                NotizenRepository.Tick.FAILED -> "failed"
                NotizenRepository.Tick.READ_ONLY -> "read_only"
                else -> null
            }
            load()
        }
    }

    fun deleteSub(subId: Long) {
        viewModelScope.launch {
            _message.value = repo.deleteTask(subId).code()
            load()
        }
    }

    fun delete(done: () -> Unit) {
        viewModelScope.launch {
            val r = repo.deleteTask(id)
            if (r is NResult.Ok) done() else _message.value = r.code()
        }
    }

    /** A picture or file of the phone (read by the screen) → an attachment of the task. */
    fun attach(bytes: ByteArray, mime: String, name: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val limit = (_ui.value.me?.attachMB ?: 25) * 1024L * 1024L
            if (bytes.size > limit) {
                _ui.update { it.copy(busy = false) }
                _message.value = "too_large"
                return@launch
            }
            // pictures and files need the server (and a task it knows)
            val sid = repo.serverId(id) ?: run {
                _ui.update { it.copy(busy = false) }
                _message.value = "attach_offline"
                return@launch
            }
            val up = repo.api.upload(bytes, mime)
            val r = if (up is NResult.Ok) repo.api.attach(NotizenJson.attachBody("task", sid, up.value, name, null)) else up
            _ui.update { it.copy(busy = false) }
            _message.value = if (r is NResult.Offline) "attach_offline" else r.code()
            load()
        }
    }

    fun removeAttachment(a: NAttachment) {
        viewModelScope.launch {
            _message.value = repo.api.deleteAttachment(a.id).code()
            load()
        }
    }

    suspend fun download(a: NAttachment): ByteArray? {
        val url = a.url ?: repo.api.attachment(a.id).valueOrNull()?.url ?: return null
        return repo.api.download(url)
    }
}

/** An item of another plugin (its original stays there): open it there, tick it off, or give it a task of its own. */
@HiltViewModel
class NotizenItemViewModel @Inject constructor(
    private val repo: NotizenRepository,
    saved: SavedStateHandle,
) : ViewModel() {
    val id: Long = saved.get<String>("id")?.toLongOrNull() ?: 0L

    data class Ui(val item: NItem? = null, val taskId: Long? = null, val me: NMe? = null, val loading: Boolean = true, val error: String? = null)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    init { load() }

    fun consumeMessage() { _message.value = null }

    fun load() {
        viewModelScope.launch {
            val me = repo.meOrCached()
            val r = repo.api.item(id)
            _ui.update { it.copy(item = r.valueOrNull()?.first ?: it.item, taskId = r.valueOrNull()?.second, me = me, loading = false, error = r.code()) }
        }
    }

    fun toggle() {
        val item = _ui.value.item ?: return
        viewModelScope.launch {
            _message.value = when (repo.complete(item, !item.done).first) {
                NotizenRepository.Tick.QUEUED -> "queued"
                NotizenRepository.Tick.READ_ONLY -> "read_only"
                NotizenRepository.Tick.FAILED -> "failed"
                NotizenRepository.Tick.DONE -> null
            }
            load()
        }
    }

    /** The app has no screen for the item's plugin: where to find it instead. */
    fun notHere() { _message.value = "not_here" }

    /** „Unteraufgaben, Notiz, Erinnerung …“: the item gets a task here → its id. */
    fun carry(open: (Long) -> Unit) {
        viewModelScope.launch {
            when (val r = repo.api.carryItem(id)) {
                is NResult.Ok -> { repo.touched("tasks"); open(r.value.task.id) }
                else -> _message.value = r.code()
            }
        }
    }
}

/** „Schnell hinzufügen“ (mockup Schnell): the line read on the phone ([QuickParse]) and what the person changed. */
@HiltViewModel
class NotizenQuickViewModel @Inject constructor(
    private val repo: NotizenRepository,
    private val geofences: com.gatecontrol.android.notizen.NotizenGeofences,
    saved: SavedStateHandle,
) : ViewModel() {

    /** Plugin 1.5.0 (`places`): the choice „Welcher Baumarkt?“ for the place the line names. */
    val placeChooser = PlaceChooser(repo, viewModelScope) { geofences.position() }

    /** The chip of the place was tapped: the choice — and, for a kind without a remembered place, the search (the tap confirms it). */
    fun openPlace() {
        val u = _ui.value
        placeChooser.open(null, u.place, u.me, search = true, picked = u.placePick?.spot, trigger = QuickPlace.effective(u.place, u.placePick, u.placeOff, u.placeTrigger)?.trigger, removable = true)
    }

    fun pickPlace(p: PlacePicked) = _ui.update { it.copy(placePick = p, placeOff = false, placeTrigger = null) }

    /** „Ohne Ort“: the task is made without a place, its title stays whole. */
    fun dropPlace() = _ui.update { it.copy(placeOff = true, placePick = null) }

    /** „Beim Ankommen“ ⇄ „Beim Verlassen“ on the chip. */
    fun togglePlaceTrigger() = _ui.update { u ->
        val now = QuickPlace.effective(u.place, u.placePick, u.placeOff, u.placeTrigger)?.trigger ?: return@update u
        u.copy(placeTrigger = if (now == com.gatecontrol.android.notizen.NPlace.EXIT) com.gatecontrol.android.notizen.NPlace.ENTER else com.gatecontrol.android.notizen.NPlace.EXIT)
    }

    data class Ui(
        val text: String = "",
        val note: Boolean = false,
        val me: NMe? = null,
        val nav: NNav? = null,
        val people: List<NPersonPick> = emptyList(),
        val parsed: QuickParse.Parsed? = null,
        val area: String = NArea.PRIVATE,
        /** Chips the person took away (a kind of [QuickParse.Mark]). */
        val off: Set<String> = emptySet(),
        /** The person picked these instead. */
        val due: NDue? = null,
        val listId: Long? = null,
        val busy: Boolean = false,
        val done: Boolean = false,
        /** Plugin 1.4.0 (`taskList`): the shopping list the server suggests for the line (null: none, or offline). */
        val attach: com.gatecontrol.android.notizen.NAttach? = null,
        /** The person chose this shopping list instead. */
        val attachPick: com.gatecontrol.android.notizen.NAttached? = null,
        /** The person took the shopping list away. */
        val attachOff: Boolean = false,
        /** Plugin 1.5.0 (`places`): the place the server reads in the line (null: none, an older plugin, or offline). */
        val place: com.gatecontrol.android.notizen.NPlaceSuggest? = null,
        /** The title the server read for the line (its `parsed.title`) — [place]'s title belongs to it. */
        val serverTitle: String? = null,
        /** The person chose this place in the choice. */
        val placePick: PlacePicked? = null,
        /** The person took the place away. */
        val placeOff: Boolean = false,
        /** The person turned the chip „Beim Ankommen“ over. */
        val placeTrigger: String? = null,
        /** Plugin 1.6.0 (`taskTurns`): the choice of „Wer ist dran?“ ([NotizenTurns.ONE], `rotate`, `first`; null: what the line says). */
        val turnPick: String? = null,
    )

    /** „Wer ist dran?“ was chosen in the quick entry. */
    fun pickTurn(mode: String) = _ui.update { it.copy(turnPick = mode) }

    /** The list the new task goes to as far as the line and the person say it (the default list: null). */
    private fun targetList(u: Ui): Long? = u.listId ?: u.parsed?.list?.id?.takeIf { "list" !in u.off }

    /** Plugin 1.6.0: several people, „reihum“ / „wer zuerst“ and stars of the line (null without `taskTurns`: all as before). */
    fun turns(): com.gatecontrol.android.notizen.NotizenTurns.Quick? {
        val u = _ui.value
        // the chip „2 Sterne“ taken away: the words stay in the title
        val read = com.gatecontrol.android.notizen.NotizenTurns.read(u.parsed, u.people.map { QuickParse.Ref(it.id, it.name) }, u.me, targetList(u).takeIf { "stars" !in u.off })
        // the list the task goes to: the one named or chosen, else „Aufgaben“ — a list still to be made has nobody yet
        val list = u.nav?.let { n -> targetList(u)?.let { id -> n.lists.firstOrNull { it.id == id } } ?: com.gatecontrol.android.notizen.NotizenLists.defaultList(n) }
        val newList = u.listId == null && "list" !in u.off && u.parsed?.listName != null && !QuickAttach.tagIsShopping(u.attach)
        val members = if (newList) emptySet() else com.gatecontrol.android.notizen.NotizenTurns.members(list)
        return com.gatecontrol.android.notizen.NotizenTurns.effective(read, u.turnPick, u.off, members)
    }

    private var attachJob: Job? = null

    private companion object {
        /** A pause in typing before the server is asked. */
        const val SUGGEST_MS = 350L
    }

    /**
     * Asks the server which shopping list fits the line (`POST parse` → `attach`; the line itself is read on the phone,
     * the parser knows nothing of lists). Without a connection or with an older plugin: no suggestion.
     */
    private fun suggest() {
        attachJob?.cancel()
        val u = _ui.value
        val lists = u.me?.taskList == true
        val places = u.me?.taskPlaces == true
        if (u.note || !(lists || places) || u.text.isBlank()) {
            _ui.update { it.copy(attach = null, place = null, serverTitle = null) }
            return
        }
        attachJob = viewModelScope.launch {
            delay(SUGGEST_MS)
            // one question for both: the list (1.4.0) and the place (1.5.0) — an older plugin leaves the field out
            val v = (repo.api.parse(u.text, u.area, targetList(u)?.takeIf { u.me?.taskTurns == true }) as? NResult.Ok)?.value
            _ui.update { it.copy(attach = v?.attach?.takeIf { lists }, place = v?.place?.takeIf { places }, serverTitle = v?.title) }
        }
    }

    /** The chip „Liste: Einkauf“ was taken away. */
    fun dropAttach() = _ui.update { it.copy(attachOff = true, attachPick = null) }

    /** Another shopping list from the choice. */
    fun pickAttach(l: com.gatecontrol.android.notizen.NAttached) = _ui.update { it.copy(attachPick = l, attachOff = false) }

    private val _ui = MutableStateFlow(Ui(text = saved.get<String>("text").orEmpty(), note = saved.get<String>("mode") == "note"))
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _message = MutableStateFlow<NMessage?>(null)
    val message: StateFlow<NMessage?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val me = repo.meOrCached()
            val area = repo.currentArea()
            val nav = repo.nav(area).valueOrNull()?.value
            _ui.update { it.copy(me = me, nav = nav, area = area) }
            reparse()
            suggest()
            repo.api.people().valueOrNull()?.let { p -> _ui.update { it.copy(people = p) }; reparse() }
        }
    }

    fun consumeMessage() { _message.value = null }

    fun setText(t: String) {
        _ui.update { it.copy(text = t.take(1000)) }
        reparse()
        suggest()
    }

    fun setNote(v: Boolean) {
        _ui.update { it.copy(note = v) }
        suggest()
    }

    fun setArea(a: String) {
        _ui.update { it.copy(area = NArea.of(a), listId = null, attachPick = null) }
        suggest()
        viewModelScope.launch {
            val nav = repo.nav(NArea.of(a)).valueOrNull()?.value
            _ui.update { it.copy(nav = nav) }
            reparse()
        }
    }

    fun drop(kind: String) = _ui.update { it.copy(off = it.off + kind) }
    fun pickDue(d: NDue?) = _ui.update { it.copy(due = d, off = it.off - "date" - "time") }
    fun pickList(id: Long?) = _ui.update { it.copy(listId = id, off = it.off - "list") }

    private fun reparse() {
        val u = _ui.value
        val me = u.me ?: return
        val ctx = QuickParse.Ctx(
            today = me.today,
            now = me.time,
            lists = u.nav?.lists.orEmpty().filter { it.canEdit }.map { QuickParse.Ref(it.id, it.name) },
            people = u.people.map { QuickParse.Ref(it.id, it.name) },
            times = me.prefs.times,
        )
        _ui.update { it.copy(parsed = runCatching { QuickParse.parse(u.text, ctx) }.getOrNull()) }
    }

    /** The task as it will be made (the chips the person kept). */
    fun effective(): Effective? {
        val u = _ui.value
        val p = u.parsed ?: return null
        val off = u.off
        val due = u.due ?: if ("date" in off) null else p.date?.let { NDue(it, if ("time" in off) null else p.time) }
        // plugin 1.6.0: the further „@Name“, „reihum“ / „wer zuerst“ and „2 Sterne“ leave the title
        val turns = turns()
        return Effective(
            title = (turns?.title ?: p.title).ifBlank { u.text.trim() },
            due = due,
            remind = if ("time" in off || due?.time == null) null else due,
            important = p.important && "important" !in off,
            listId = u.listId ?: p.list?.id?.takeIf { "list" !in off },
            // `#einkauf` names a shopping list (the server says so): the word is not a new task list
            newList = if (u.listId == null && "list" !in off && !QuickAttach.tagIsShopping(u.attach)) p.listName else null,
            assigneeId = if (turns != null) com.gatecontrol.android.notizen.NotizenTurns.assignee(turns) else p.assignee?.id?.takeIf { "person" !in off },
            repeat = p.repeat?.takeIf { "repeat" !in off },
            turns = turns,
            attached = QuickAttach.effective(u.attach, u.attachPick, u.attachOff),
            place = QuickPlace.effective(u.place, u.placePick, u.placeOff, u.placeTrigger),
        )
    }

    data class Effective(
        val title: String,
        val due: NDue?,
        val remind: NDue?,
        val important: Boolean,
        val listId: Long?,
        val newList: String?,
        val assigneeId: Long?,
        val repeat: NRepeat.Rule?,
        /** Plugin 1.4.0: the shopping list the task will carry (the chip the person kept or chose). */
        val attached: com.gatecontrol.android.notizen.NAttached? = null,
        /** Plugin 1.5.0: the place the task will hang on (decided by the line, or chosen). */
        val place: PlacePicked? = null,
        /** Plugin 1.6.0: the people, whose turn it is and the stars (null with an older plugin). */
        val turns: com.gatecontrol.android.notizen.NotizenTurns.Quick? = null,
    )

    fun save() {
        val u = _ui.value
        if (u.text.isBlank() || u.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            if (u.note) {
                val (title, body) = NoteMd.splitTitle(u.text)
                val r = repo.createNote(NotizenJson.noteBody(null, u.area, title, body))
                _ui.update { it.copy(busy = false, done = r is NResult.Ok) }
                _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else "note_saved"
                return@launch
            }
            val e = effective() ?: run { _ui.update { it.copy(busy = false) }; return@launch }
            var listId = e.listId
            if (listId == null && e.newList != null) listId = repo.createList(e.newList.replaceFirstChar { it.uppercase() }, u.area).valueOrNull()?.id
            // an older plugin would pass `place` over silently: only with `places`; the place's words leave the title
            val place = e.place?.takeIf { u.me?.taskPlaces == true }
            val title = e.title.ifBlank { u.text.trim() }.let { t -> if (place != null) com.gatecontrol.android.notizen.PlaceLogic.titleWithout(t, u.place, u.serverTitle) else t }
            val body = NotizenJson.taskBody(title, area = u.area, listId = listId).apply {
                place?.let { add("place", it.arg) }
                e.due?.let { add("due", NotizenJson.dueBody(it)) }
                e.remind?.let { add("remindAt", NotizenJson.dueBody(it)) }
                if (e.important) addProperty("important", true)
                e.assigneeId?.let { addProperty("assigneeId", it) }
                e.repeat?.let { add("repeat", NRepeat.toJson(it)) }
                if (e.due?.date == u.me?.today) addProperty("myDay", true)
                // an older plugin would pass the field over silently: only with `taskList`
                if (u.me?.taskList == true) e.attached?.let { addProperty("attachedListId", it.id) }
                // plugin 1.6.0 — only with its words: „reihum“ / „wer zuerst“ with their people; the stars where I may give them
                e.turns?.takeIf { u.me?.taskTurns == true && it.turn != null }?.let { t ->
                    addProperty("turn", t.turn)
                    add("people", com.google.gson.JsonArray().apply { t.people.forEach { add(it.id) } })
                }
                e.turns?.stars?.takeIf { com.gatecontrol.android.notizen.NotizenTurns.mayGiveStars(u.me, listId) }?.let { addProperty("stars", it) }
            }
            val r = repo.createTask(body)
            _ui.update { it.copy(busy = false, done = r is NResult.Ok) }
            _message.value = r.code() ?: if (r.valueOrNull()?.offline == true) "queued" else "task_saved"
        }
    }
}

/** The place of the quick entry (plugin 1.5.0, pure, unit-tested). */
object QuickPlace {
    /**
     * The place the new task hangs on: the one the person chose ([pick]); else the one the line decides — an own place,
     * a place of the family, the remembered one of the kind — but only where a place is surely meant
     * ([com.gatecontrol.android.notizen.NPlaceSuggest.certain]). Nothing once the chip was taken away ([off]). [trigger]:
     * the chip „Beim Ankommen“ turned over.
     */
    fun effective(suggest: com.gatecontrol.android.notizen.NPlaceSuggest?, pick: PlacePicked?, off: Boolean, trigger: String?): PlacePicked? {
        // a choice belongs to the place the line names: once the line names none, nothing hangs on the task unseen
        if (off || suggest == null) return null
        if (pick != null) {
            if (trigger == null || trigger == pick.trigger) return pick
            return pick.copy(arg = pick.arg.deepCopy().apply { addProperty("trigger", trigger) }, trigger = trigger)
        }
        val s = suggest.takeIf { it.certain } ?: return null
        val spot = s.place ?: return null
        val t = trigger ?: s.trigger
        val arg = com.gatecontrol.android.notizen.NotizenPlacesJson.placeArg(spot, false, s.key, s.label.takeIf { s.key != null }, t) ?: return null
        return PlacePicked(arg, spot.name, t, spot)
    }

    /** The line names a place that is not decided: the chip „Ort: Baumarkt …“ asks. */
    fun asks(suggest: com.gatecontrol.android.notizen.NPlaceSuggest?, pick: PlacePicked?, off: Boolean): Boolean =
        !off && pick == null && suggest != null && effective(suggest, null, false, null) == null

    /** „Dein üblicher Ort für ‚Bäcker‘, zuletzt sechsmal gewählt“: the remembered place of a kind was put in by itself. */
    fun usual(suggest: com.gatecontrol.android.notizen.NPlaceSuggest?, pick: PlacePicked?, off: Boolean): Pair<String, Int>? =
        suggest?.takeIf { !off && pick == null && it.certain && it.place != null && it.key != null && it.uses > 0 }?.let { it.label to it.uses }
}

/** The suggestion „Liste anhängen“ of the quick entry (plugin 1.4.0, pure, unit-tested). */
object QuickAttach {
    /**
     * The shopping list the new task carries: the one the person chose ([pick]), else the server's suggestion while
     * exactly one fits — nothing once the person took the chip away ([off]) or with several to choose from.
     */
    fun effective(attach: com.gatecontrol.android.notizen.NAttach?, pick: com.gatecontrol.android.notizen.NAttached?, off: Boolean): com.gatecontrol.android.notizen.NAttached? =
        if (off) null else pick ?: attach?.list

    /** Several lists fit and none is chosen yet: the choice is offered instead of a chip of one list. */
    fun asks(attach: com.gatecontrol.android.notizen.NAttach?, pick: com.gatecontrol.android.notizen.NAttached?, off: Boolean): Boolean =
        !off && pick == null && attach != null && attach.list == null && attach.lists.size > 1

    /** `#einkauf` names a shopping list: the parser's `listName` is not a new task list then. */
    fun tagIsShopping(attach: com.gatecontrol.android.notizen.NAttach?): Boolean = attach?.via == "tag"

    /** The lists of the choice: the server's candidates, the suggested one first. */
    fun choices(attach: com.gatecontrol.android.notizen.NAttach?): List<com.gatecontrol.android.notizen.NAttached> =
        (listOfNotNull(attach?.list) + attach?.lists.orEmpty()).distinctBy { it.id }
}

/** The search over tasks and notes (`GET search`, the server's full-text index). */
@HiltViewModel
class NotizenSearchViewModel @Inject constructor(private val repo: NotizenRepository) : ViewModel() {
    data class Ui(val query: String = "", val result: NSearchResult? = null, val busy: Boolean = false, val error: String? = null)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private var job: Job? = null

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q.take(200)) }
        job?.cancel()
        if (q.trim().length < 2) {
            _ui.update { it.copy(result = null, busy = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(350)
            _ui.update { it.copy(busy = true) }
            val r = repo.api.search(q.trim(), repo.currentArea())
            _ui.update { it.copy(result = r.valueOrNull() ?: it.result, busy = false, error = r.code()) }
        }
    }

    fun toggle(entry: NEntry) {
        viewModelScope.launch {
            repo.complete(entry, !entry.done)
            setQuery(_ui.value.query)
        }
    }
}
