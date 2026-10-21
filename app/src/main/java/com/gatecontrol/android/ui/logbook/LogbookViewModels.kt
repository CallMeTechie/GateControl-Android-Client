package com.gatecontrol.android.ui.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.logbook.LogbookExportFile
import com.gatecontrol.android.logbook.LogbookListFilter
import com.gatecontrol.android.logbook.LogbookLogic
import com.gatecontrol.android.logbook.LogbookMonth
import com.gatecontrol.android.logbook.LogbookRepository
import com.gatecontrol.android.logbook.LogbookResult
import com.gatecontrol.android.logbook.LogbookTrip
import com.gatecontrol.android.logbook.LogbookTripChange
import com.gatecontrol.android.logbook.errorCode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot message of the logbook screens (snackbar), or an export to share. */
sealed interface LogbookNotice {
    data class SaveFailed(val code: String?) : LogbookNotice
    data class ExportFailed(val code: String?) : LogbookNotice
    data class Export(val file: LogbookExportFile) : LogbookNotice
    data object Saved : LogbookNotice
}

/**
 * Which month of which vehicle a screen shows and how loading it went.
 * [vehicleId]/[month] null until the first answer says which (the first own
 * vehicle, this month); [error] the code of the last load.
 */
data class LogbookLoad(
    val vehicleId: Long? = null,
    val month: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/** Loads months through [repository] for one screen; the month itself comes from the repository's shared cache. */
private class MonthLoader(private val repository: LogbookRepository, private val scope: kotlinx.coroutines.CoroutineScope) {
    val state = MutableStateFlow(LogbookLoad())
    private var job: Job? = null

    fun data(months: Map<String, LogbookMonth>, s: LogbookLoad): LogbookMonth? = s.month?.let { months[repository.key(s.vehicleId, it)] }

    /**
     * Shows [vehicleId]'s [month] (from the cache at once, when loaded
     * before) and asks the server — unless [onlyIfMissing] and this app run
     * has the month already (the map beside or after the list).
     */
    fun show(vehicleId: Long?, month: String?, onlyIfMissing: Boolean = false) {
        state.update { it.copy(vehicleId = vehicleId ?: it.vehicleId, month = month ?: it.month, error = null) }
        val s = state.value
        if (onlyIfMissing && s.month != null && repository.cached(s.vehicleId, s.month) != null) return
        load(spinner = true)
    }

    fun load(spinner: Boolean) {
        job?.cancel()
        val s = state.value
        val known = s.month != null && repository.cached(s.vehicleId, s.month) != null
        state.update { it.copy(loading = spinner && !known, refreshing = !spinner, error = null) }
        job = scope.launch {
            val r = repository.load(s.vehicleId, s.month)
            state.update {
                when (r) {
                    is LogbookResult.Ok -> it.copy(vehicleId = r.value.vehicleId ?: it.vehicleId, month = r.value.month, loading = false, refreshing = false, error = null)
                    else -> it.copy(loading = false, refreshing = false, error = r.errorCode())
                }
            }
        }
    }
}

/**
 * Logbook ("Fahrtenbuch") list: the month of a vehicle with its sums, the
 * trips by day, quick purposes for open trips, month and vehicle switching,
 * export. Also holds the month the all-trips map beside it shows on wide windows.
 */
@HiltViewModel
class LogbookViewModel @Inject constructor(
    private val repository: LogbookRepository,
) : ViewModel() {

    data class Ui(
        val load: LogbookLoad = LogbookLoad(),
        val data: LogbookMonth? = null,
        /** Trips whose purpose is on its way. */
        val saving: Set<Long> = emptySet(),
        val exporting: Boolean = false,
        /** "n Fahrten zeigen" from the map: only these trips. */
        val filter: LogbookListFilter? = null,
    )

    private val loader = MonthLoader(repository, viewModelScope)
    private val saving = MutableStateFlow<Set<Long>>(emptySet())
    private val exporting = MutableStateFlow(false)

    val ui: StateFlow<Ui> = combine(loader.state, repository.months, saving, exporting, repository.listFilter) { s, months, sv, ex, f ->
        val data = loader.data(months, s)
        Ui(s, data, sv, ex, f?.takeIf { it.month == data?.month && (it.vehicleId == null || it.vehicleId == data.vehicleId) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Ui())

    private val notices = Channel<LogbookNotice>(Channel.BUFFERED)
    val notice: Flow<LogbookNotice> = notices.receiveAsFlow()

    private var started = false

    init {
        // "n Fahrten zeigen" on the map of another month: the list follows to that month.
        viewModelScope.launch {
            repository.listFilter.collect { f ->
                val s = loader.state.value
                if (f != null && started && (f.month != s.month || (f.vehicleId != null && f.vehicleId != s.vehicleId))) {
                    loader.show(f.vehicleId ?: s.vehicleId, f.month, onlyIfMissing = true)
                }
            }
        }
    }

    /** First show (the vehicle a quick action names, null = the first own one). */
    fun start(vehicleId: Long?) {
        if (started) return
        started = true
        // "n Fahrten zeigen" of an earlier visit does not stick.
        repository.setListFilter(null)
        loader.show(vehicleId, null)
    }

    fun refresh() = loader.load(spinner = false)

    fun retry() = loader.load(spinner = true)

    fun setMonth(month: String) {
        if (LogbookLogic.parseMonth(month) == null || month == loader.state.value.month) return
        repository.setListFilter(null)
        loader.show(loader.state.value.vehicleId, month)
    }

    fun previousMonth() {
        loader.state.value.month?.let(LogbookLogic::previous)?.let(::setMonth)
    }

    fun nextMonth() {
        val data = ui.value.data ?: return
        LogbookLogic.next(data.month, data.currentMonth)?.let(::setMonth)
    }

    fun setVehicle(vehicleId: Long) {
        if (vehicleId == loader.state.value.vehicleId) return
        repository.setListFilter(null)
        loader.show(vehicleId, loader.state.value.month)
    }

    fun clearFilter() = repository.setListFilter(null)

    /** A purpose for [trip] (the quick buttons of an open trip). */
    fun setPurpose(trip: LogbookTrip, purpose: String?) {
        if (trip.id in saving.value) return
        saving.update { it + trip.id }
        viewModelScope.launch {
            val r = repository.updateTrip(trip.id, LogbookTripChange(purpose = purpose ?: ""))
            saving.update { it - trip.id }
            if (r !is LogbookResult.Ok) notices.send(LogbookNotice.SaveFailed(r.errorCode()))
        }
    }

    /** The shown month as `pdf` or `csv`; the file comes as [LogbookNotice.Export]. */
    fun export(format: String) {
        val data = ui.value.data ?: return
        val vehicleId = data.vehicleId ?: return
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            val r = repository.export(vehicleId, data.month, data.month, format)
            exporting.value = false
            notices.send(if (r is LogbookResult.Ok) LogbookNotice.Export(r.value) else LogbookNotice.ExportFailed(r.errorCode()))
        }
    }
}

/** All trips of a month on the map: filter, the chosen route. */
@HiltViewModel
class LogbookMapViewModel @Inject constructor(
    private val repository: LogbookRepository,
) : ViewModel() {

    data class Ui(
        val load: LogbookLoad = LogbookLoad(),
        val data: LogbookMonth? = null,
        val filter: LogbookLogic.MapFilter = LogbookLogic.MapFilter.ALL,
        /** Key of the chosen route group (bottom sheet). */
        val selected: String? = null,
    )

    private val loader = MonthLoader(repository, viewModelScope)
    private val filter = MutableStateFlow(LogbookLogic.MapFilter.ALL)
    private val selected = MutableStateFlow<String?>(null)

    val ui: StateFlow<Ui> = combine(loader.state, repository.months, filter, selected) { s, months, f, sel ->
        Ui(s, loader.data(months, s), f, sel)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Ui())

    private var shown: Pair<Long?, String?>? = null

    /** Shows [vehicleId]'s [month] (null: the list's last vehicle / this month). */
    fun show(vehicleId: Long?, month: String?) {
        val key = vehicleId to month
        if (key == shown) return
        shown = key
        selected.value = null
        loader.show(vehicleId ?: repository.lastVehicleId, month, onlyIfMissing = true)
    }

    /** The map's own month switcher (phones). */
    fun setMonth(month: String) {
        if (LogbookLogic.parseMonth(month) == null) return
        shown = loader.state.value.vehicleId to month
        selected.value = null
        loader.show(loader.state.value.vehicleId, month, onlyIfMissing = true)
    }

    fun retry() = loader.load(spinner = true)

    fun setFilter(f: LogbookLogic.MapFilter) {
        filter.value = f
        selected.value = null
    }

    fun select(key: String?) {
        selected.value = key
    }

    /** "n Fahrten zeigen": the list shows only [trips] of the shown month. */
    fun showInList(trips: List<LogbookTrip>, label: String?) {
        val data = ui.value.data ?: return
        repository.setListFilter(LogbookListFilter(data.vehicleId, data.month, label, trips.map { it.id }.toSet()))
    }
}

/** One trip: purpose, note, start and destination. */
@HiltViewModel
class LogbookTripViewModel @Inject constructor(
    private val repository: LogbookRepository,
) : ViewModel() {

    data class Ui(
        val load: LogbookLoad = LogbookLoad(),
        val trip: LogbookTrip? = null,
        val canEdit: Boolean = false,
        val saving: Boolean = false,
    )

    private val loader = MonthLoader(repository, viewModelScope)
    private val tripId = MutableStateFlow(0L)
    private val saving = MutableStateFlow(false)

    val ui: StateFlow<Ui> = combine(loader.state, repository.months, tripId, saving) { s, months, id, sv ->
        // The shown month first; a trip opened from elsewhere may sit in another loaded one.
        val month = loader.data(months, s)?.takeIf { m -> m.trips.any { it.id == id } }
            ?: months.values.firstOrNull { m -> m.trips.any { it.id == id } }
        Ui(s, month?.trips?.firstOrNull { it.id == id }, month?.canEdit == true, sv)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Ui())

    private val notices = Channel<LogbookNotice>(Channel.BUFFERED)
    val notice: Flow<LogbookNotice> = notices.receiveAsFlow()

    /** Shows trip [id] of [vehicleId]'s [month]; the month is loaded unless this app run has it already. */
    fun show(id: Long, vehicleId: Long?, month: String?) {
        if (id == tripId.value) return
        tripId.value = id
        loader.state.update { it.copy(vehicleId = vehicleId ?: repository.lastVehicleId, month = month) }
        val known = repository.months.value.values.any { m -> m.trips.any { it.id == id } }
        if (!known) loader.load(spinner = true)
    }

    fun retry() = loader.load(spinner = true)

    fun setPurpose(purpose: String?) = save(LogbookTripChange(purpose = purpose ?: ""), quiet = true)

    /** The note when the field is left or "done" (only when it changed). */
    fun saveNote(note: String) {
        val trip = ui.value.trip ?: return
        if (note.trim() == (trip.note ?: "")) return
        save(LogbookTripChange(note = note.trim()))
    }

    fun savePlaces(start: String, end: String) {
        val trip = ui.value.trip ?: return
        val change = LogbookTripChange(
            startAddress = start.trim().takeIf { it != (trip.startAddress ?: "") },
            endAddress = end.trim().takeIf { it != (trip.endAddress ?: "") },
        )
        if (change.startAddress == null && change.endAddress == null) return
        save(change)
    }

    private fun save(change: LogbookTripChange, quiet: Boolean = false) {
        val trip = ui.value.trip ?: return
        if (!ui.value.canEdit) return
        viewModelScope.launch {
            saving.value = true
            val r = repository.updateTrip(trip.id, change)
            saving.value = false
            when {
                r !is LogbookResult.Ok -> notices.send(LogbookNotice.SaveFailed(r.errorCode()))
                !quiet -> notices.send(LogbookNotice.Saved)
            }
        }
    }
}
