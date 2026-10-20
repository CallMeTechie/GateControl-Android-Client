package com.gatecontrol.android.ui.familie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.familie.FamilieResult
import com.gatecontrol.android.familie.errorCode
import com.gatecontrol.android.familie.school.SchoolChange
import com.gatecontrol.android.familie.school.SchoolLogic
import com.gatecontrol.android.familie.school.SchoolRepository
import com.gatecontrol.android.familie.school.SchoolTimetable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Stundenplan screen: this week and next (the range the Familie card
 * keeps, offline too), or — for a push about a later day — the two weeks
 * from that day's Monday; the selected child and day; the change log.
 */
@HiltViewModel
class SchoolViewModel @Inject constructor(
    private val repository: SchoolRepository,
) : ViewModel() {

    private val today: LocalDate get() = LocalDate.now()

    private val _windowStart = MutableStateFlow(SchoolLogic.windowStart(today))
    /** First day of the loaded range (a Monday). */
    val windowStart: StateFlow<LocalDate> = _windowStart.asStateFlow()

    /** A range other than the usual one, loaded for a push. */
    private val _other = MutableStateFlow<Pair<LocalDate, SchoolTimetable>?>(null)

    /** The timetable of the shown range; null while there is none. */
    val timetable: StateFlow<SchoolTimetable?> = combine(repository.snapshot, _windowStart, _other) { snap, start, other ->
        if (start == SchoolLogic.windowStart(today)) {
            snap?.takeIf { it.from == start.toString() }?.timetable
        } else {
            other?.takeIf { it.first == start }?.second
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _studentId = MutableStateFlow<Int?>(null)
    val studentId: StateFlow<Int?> = _studentId.asStateFlow()

    private val _date = MutableStateFlow(SchoolLogic.defaultDay(LocalDateTime.now()))
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    /** The day was picked (by the person or a push): the default no longer moves it. */
    private var datePicked = false

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _changes = MutableStateFlow<List<SchoolChange>?>(null)
    val changes: StateFlow<List<SchoolChange>?> = _changes.asStateFlow()

    private val _changesError = MutableStateFlow<String?>(null)
    val changesError: StateFlow<String?> = _changesError.asStateFlow()

    private var loadJob: Job? = null
    private var opened = false

    init {
        viewModelScope.launch { repository.pushes.collect { refresh() } }
        viewModelScope.launch {
            timetable.collect { tt ->
                if (tt == null) return@collect
                if (!datePicked) _date.value = SchoolLogic.defaultDay(LocalDateTime.now(), SchoolLogic.hasLessons(tt, _studentId.value))
                if (_studentId.value == null || tt.student(_studentId.value) == null) {
                    _studentId.value = SchoolLogic.pickStudent(tt, _studentId.value)?.id
                }
            }
        }
    }

    /**
     * The child and day to start on (from the card or a push). The view
     * model can outlive one screen (wide windows): new arguments apply, the
     * same ones again do nothing.
     */
    fun open(studentId: Int?, date: String?) {
        val args = studentId to date
        if (opened && args == lastArgs) return
        val first = !opened
        opened = true
        lastArgs = args
        if (studentId != null) _studentId.value = studentId
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val start = _windowStart.value
        if (d != null) {
            _date.value = d
            datePicked = true
            _windowStart.value = SchoolLogic.windowFor(d, today)
        }
        if (first || _windowStart.value != start) {
            viewModelScope.launch {
                repository.cached()
                refresh()
            }
        }
    }

    private var lastArgs: Pair<Int?, String?>? = null

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _busy.value = true
            val start = _windowStart.value
            val r = if (start == SchoolLogic.windowStart(today)) {
                repository.refresh(today)
            } else {
                repository.load(start).also { if (it is FamilieResult.Ok) _other.value = start to it.value }
            }
            _error.value = if (r is FamilieResult.Ok) null else r.errorCode()
            when (val c = repository.changes()) {
                is FamilieResult.Ok -> {
                    _changes.value = c.value
                    _changesError.value = null
                }
                else -> _changesError.value = c.errorCode()
            }
            _busy.value = false
        }
    }

    fun selectStudent(id: Int) {
        _studentId.value = id
    }

    fun selectDate(date: LocalDate) {
        datePicked = true
        _date.value = date
    }

    /** The other week of the range ([weeks] = ±1): its first school day. */
    fun shiftWeek(weeks: Int) {
        val start = _windowStart.value
        val monday = SchoolLogic.monday(_date.value).plusWeeks(weeks.toLong())
        if (monday < start || monday > SchoolLogic.windowEnd(start)) return
        selectDate(SchoolLogic.weekDays(monday, SchoolLogic.hasLessons(timetable.value, _studentId.value)).firstOrNull() ?: monday)
    }
}
