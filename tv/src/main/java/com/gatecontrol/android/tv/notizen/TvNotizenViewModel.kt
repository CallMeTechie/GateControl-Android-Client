package com.gatecontrol.android.tv.notizen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NList
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NParsedLine
import com.gatecontrol.android.notizen.NResult
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.tv.zuhause.TvSourceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** „Aufgabe hinzufügen“: typing, the server's reading of the line, saving. */
sealed interface TvNotesAdd {
    data object Closed : TvNotesAdd
    data object Typing : TvNotesAdd

    /** `POST parse` asked. */
    data class Reading(val text: String) : TvNotesAdd

    /** The line as the server read it; [parsed] null when it could not say (saved as typed). */
    data class Preview(val text: String, val parsed: NParsedLine?, val clientId: String) : TvNotesAdd

    data class Saving(val text: String) : TvNotesAdd
}

/** A message at the bottom of the page for a few seconds. */
enum class TvNotesMessage { SAVED, MOVED, REFUSED, OFFLINE }

/** What the page „Notizen“ shows. */
data class TvNotizenUi(
    val today: LocalDate,
    val navState: TvSourceState = TvSourceState.LOADING,
    val myDayCount: Int = 0,
    val plannedCount: Int = 0,
    val lists: List<NList> = emptyList(),
    val notebooks: List<com.gatecontrol.android.notizen.NNotebook> = emptyList(),
    val place: TvNotesPlace = TvNotesPlace.MyDay,
    val placeState: TvSourceState = TvSourceState.LOADING,
    /** The rows of [place] (a smart list or list), the open ones first. */
    val entries: List<NEntry> = emptyList(),
    /** The notes of [place] (a notebook). */
    val notes: List<NNoteSummary> = emptyList(),
    /** The list names by id (the line under a task). */
    val listNames: Map<Long, String> = emptyMap(),
    /** The row just ticked: „Rückgängig“ for a few seconds. */
    val undo: NEntry? = null,
    val add: TvNotesAdd = TvNotesAdd.Closed,
    val message: TvNotesMessage? = null,
    /** The start row's „3 heute · 1 überfällig“ (null: Mein Tag not loaded). */
    val tasksToday: TvTasksToday? = null,
) {
    val open: Int get() = entries.count { !it.done }
    val doneCount: Int get() = entries.count { it.done }
}

/** The page „Notizen“ and the start row's tasks card. */
@HiltViewModel
class TvNotizenViewModel @Inject constructor(private val notizen: TvNotizen) : ViewModel() {

    private val _place = MutableStateFlow<TvNotesPlace>(TvNotesPlace.MyDay)
    private val _undo = MutableStateFlow<NEntry?>(null)
    private val _add = MutableStateFlow<TvNotesAdd>(TvNotesAdd.Closed)
    private val _message = MutableStateFlow<TvNotesMessage?>(null)
    private var undoJob: Job? = null
    private var messageJob: Job? = null

    val ui: StateFlow<TvNotizenUi> = combine(notizen.data, _place, _undo, _add, _message) { d, wanted, undo, add, message ->
        val place = TvNotizenLogic.valid(wanted, d.nav)
        val view = d.views[place.key]
        TvNotizenUi(
            today = LocalDate.now(),
            navState = d.navState,
            myDayCount = d.nav?.myDay ?: 0,
            plannedCount = d.nav?.planned ?: 0,
            lists = d.nav?.lists.orEmpty(),
            notebooks = d.nav?.notebooks.orEmpty(),
            place = place,
            placeState = if (place is TvNotesPlace.Notebook) (if (d.notes.containsKey(place.id)) TvSourceState.OK else d.state(place)) else if (view != null) TvSourceState.OK else d.state(place),
            entries = TvNotizenLogic.entries(view),
            notes = (place as? TvNotesPlace.Notebook)?.let { d.notes[it.id] }.orEmpty(),
            listNames = d.nav?.lists.orEmpty().associate { it.id to it.name },
            undo = undo,
            add = add,
            message = message,
            tasksToday = TvNotizenLogic.today(d.views[TvNotizenLogic.MYDAY]),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvNotizenUi(LocalDate.now()))

    /** The page or the start page shows: the left column and Mein Tag (at most once a minute). */
    fun refresh(force: Boolean = false) = notizen.refreshAsync(force)

    /** A smart list, list or notebook of the left column. */
    fun choose(place: TvNotesPlace) {
        _place.value = place
        if (place != TvNotesPlace.MyDay) _add.value = TvNotesAdd.Closed
        notizen.loadAsync(place)
    }

    /** OK on a row: ticked, „Rückgängig“ for a few seconds; OK on a ticked one opens it again. */
    fun toggle(entry: NEntry) {
        if (!TvNotizenLogic.canTick(entry)) {
            say(TvNotesMessage.REFUSED)
            return
        }
        undoJob?.cancel()
        if (!entry.done) {
            _undo.value = entry
            undoJob = viewModelScope.launch {
                delay(UNDO_MS)
                _undo.value = null
            }
        } else {
            _undo.value = null
        }
        viewModelScope.launch {
            val r = notizen.tick(entry, !entry.done)
            if (r != TvNotesDone.OK) _undo.update { if (it?.uid == entry.uid) null else it }
            report(r)
        }
    }

    fun undo() {
        val entry = _undo.value ?: return
        undoJob?.cancel()
        _undo.value = null
        viewModelScope.launch { report(notizen.tick(entry, false)) }
    }

    suspend fun detail(task: NTask): NTaskDetail? = notizen.detail(task.id)

    /** A sub task in the details; → whether it went. */
    suspend fun tickSub(id: Long, done: Boolean): Boolean = notizen.tickSub(id, done).also { report(it) } == TvNotesDone.OK

    fun moveTomorrow(task: NTask) {
        viewModelScope.launch {
            val r = notizen.moveTomorrow(task)
            if (r == TvNotesDone.OK) say(TvNotesMessage.MOVED) else report(r)
        }
    }

    suspend fun note(id: Long): NNote? = notizen.note(id)

    // ── „Aufgabe hinzufügen“ ────────────────────────────────

    fun startAdd() {
        _add.value = TvNotesAdd.Typing
    }

    fun cancelAdd() {
        _add.value = TvNotesAdd.Closed
    }

    /** Fertig on the keyboard: the server reads the line, the preview shows what it understood. */
    fun read(text: String) {
        val line = TvNotizenLogic.line(text) ?: run {
            _add.value = TvNotesAdd.Closed
            return
        }
        _add.value = TvNotesAdd.Reading(line)
        viewModelScope.launch {
            val r = notizen.parse(line)
            if (r is NResult.Offline || r is NResult.NotConfigured) {
                _add.value = TvNotesAdd.Typing
                say(TvNotesMessage.OFFLINE)
                return@launch
            }
            _add.update { if (it is TvNotesAdd.Reading && it.text == line) TvNotesAdd.Preview(line, r.valueOrNull(), UUID.randomUUID().toString()) else it }
        }
    }

    /** „Speichern“ in the preview. */
    fun save() {
        val p = _add.value as? TvNotesAdd.Preview ?: return
        _add.value = TvNotesAdd.Saving(p.text)
        viewModelScope.launch {
            when (val r = notizen.add(p.text, p.parsed, clientId = p.clientId)) {
                TvNotesDone.OK -> {
                    _add.value = TvNotesAdd.Closed
                    say(TvNotesMessage.SAVED)
                }
                else -> {
                    // the same clientId: sent again it is still one task
                    _add.value = p
                    report(r)
                }
            }
        }
    }

    private fun report(r: TvNotesDone) {
        when (r) {
            TvNotesDone.OK -> Unit
            TvNotesDone.REFUSED -> say(TvNotesMessage.REFUSED)
            TvNotesDone.OFFLINE -> say(TvNotesMessage.OFFLINE)
        }
    }

    private fun say(m: TvNotesMessage) {
        messageJob?.cancel()
        _message.value = m
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _message.value = null
        }
    }

    private companion object {
        const val UNDO_MS = 5_000L
        const val MESSAGE_MS = 4_000L
    }
}
