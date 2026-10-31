package com.gatecontrol.android.ui.mail.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MCalPrefs
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MEvents
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class CalendarState(
    val anchor: LocalDate = LocalDate.now(),
    /** day | 3day | week | month | list (null until the person's setting came). */
    val view: String? = null,
    val events: MEvents? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** How many days the list ("Liste") shows from [anchor] (more while scrolling down). */
    val listDays: Int = 60,
    /** A message for a moment (a code of [com.gatecontrol.android.ui.mail.mailText] or a text key). */
    val message: String? = null,
)

/**
 * The calendar area of the Mail tab (contract D3): the view and the days it shows, the occurrences of the visible
 * calendars and overlays, today and paging, the calendar list of the menu (visible, color, reminders, rename,
 * delete, add, iCal subscription). Live events `calendar` load the shown days again; offline the copy is read.
 */
@HiltViewModel
class MailCalendarViewModel @Inject constructor(
    private val repository: MailRepository,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val offline: StateFlow<Boolean> = pim.offline
    val relogins: StateFlow<Set<Long>> = pim.relogins

    /** The device's zone: the grid's days and hours. */
    val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var zoneSent = false

    init {
        viewModelScope.launch {
            val r = pim.refreshCalendars()
            r.valueOrNull()?.let { c ->
                if (_state.value.view == null) _state.update { it.copy(view = c.prefs.view) }
                syncZone(c.prefs)
            }
            if (_state.value.view == null) _state.update { it.copy(view = pim.calendars.value?.prefs?.view ?: MCalPrefs.VIEW_3DAY) }
            load()
        }
        viewModelScope.launch {
            pim.calChanged.collect {
                delay(300)
                load(quiet = true)
            }
        }
    }

    /** The person's zone on the server follows the device's (reminders of all-day events, the overlays' days). */
    private suspend fun syncZone(prefs: MCalPrefs) {
        if (zoneSent) return
        zoneSent = true
        if (prefs.timeZone != zone.id) api.calendarSettings(MailPimJson.calSettingsBody(timeZone = zone.id)).valueOrNull()?.let(pim::putPrefs)
    }

    fun onShown() {
        pim.refreshCalendarsSoon()
        load(quiet = true)
    }

    /** The days the view shows (the list: from its first day on). */
    fun shownDays(s: CalendarState = _state.value): List<LocalDate> {
        val view = s.view ?: MCalPrefs.VIEW_3DAY
        val prefs = pim.calendars.value?.prefs ?: MCalPrefs.DEFAULT
        return if (view == MCalPrefs.VIEW_LIST) (0 until s.listDays).map { s.anchor.plusDays(it.toLong()) } else MailPimLogic.days(view, s.anchor, prefs.weekStart, prefs.workWeek)
    }

    /** The occurrences of the shown days (again). */
    fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        val days = shownDays()
        if (days.isEmpty()) return
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val (from, to) = MailPimLogic.range(days.first(), days.last(), zone)
            val now = System.currentTimeMillis()
            val keep = com.gatecontrol.android.mail.MailPimCacheLogic.keep(from, to, now)
            when (val r = pim.events(from, to, keep)) {
                is MResult.Ok -> _state.update { it.copy(events = r.value, loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun setView(view: String) {
        if (view !in MCalPrefs.VIEWS || view == _state.value.view) return
        _state.update { it.copy(view = view, anchor = if (view == MCalPrefs.VIEW_LIST) LocalDate.now(zone) else it.anchor, listDays = 60) }
        load()
        viewModelScope.launch { api.calendarSettings(MailPimJson.calSettingsBody(view = view)).valueOrNull()?.let(pim::putPrefs) }
    }

    fun today() {
        _state.update { it.copy(anchor = LocalDate.now(zone), listDays = 60) }
        load()
    }

    /** ‹ / › or a swipe. */
    fun step(delta: Int) {
        val s = _state.value
        _state.update { it.copy(anchor = MailPimLogic.step(s.view ?: MCalPrefs.VIEW_3DAY, s.anchor, delta)) }
        load()
    }

    /** A day of the month (or a mini calendar): the day view. */
    fun openDay(day: LocalDate) {
        _state.update { it.copy(anchor = day, view = MCalPrefs.VIEW_DAY) }
        load()
    }

    /** The list reached its end: 30 days more. */
    fun moreList() {
        val s = _state.value
        if (s.loading || s.listDays >= 365) return
        _state.update { it.copy(listDays = it.listDays + 30) }
        load(quiet = true)
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun fail(r: MResult<*>) = _state.update { it.copy(message = MailHomeViewModel.code(r)) }

    // ─── the calendar list ──────────────────────────────────────────

    fun toggleCalendar(c: MCalendar) {
        pim.putCalendar(c.copy(visible = !c.visible))
        viewModelScope.launch {
            when (val r = api.patchCalendar(c.id, MailPimJson.calendarPatch(visible = !c.visible))) {
                is MResult.Ok -> pim.putCalendar(r.value)
                else -> {
                    pim.putCalendar(c)
                    fail(r)
                }
            }
            load(quiet = true)
        }
    }

    fun toggleOverlay(id: String) {
        val o = pim.calendars.value?.overlays?.firstOrNull { it.id == id } ?: return
        pim.putOverlay(o.copy(visible = !o.visible))
        viewModelScope.launch {
            when (val r = api.patchOverlay(id, MailPimJson.overlayPatch(visible = !o.visible))) {
                is MResult.Ok -> pim.putOverlay(r.value)
                else -> {
                    pim.putOverlay(o)
                    fail(r)
                }
            }
            load(quiet = true)
        }
    }

    fun setColor(c: MCalendar, color: String) = patch(c, MailPimJson.calendarPatch(color = color))

    fun setOverlayColor(id: String, color: String) {
        viewModelScope.launch {
            when (val r = api.patchOverlay(id, MailPimJson.overlayPatch(color = color))) {
                is MResult.Ok -> pim.putOverlay(r.value)
                else -> fail(r)
            }
            load(quiet = true)
        }
    }

    fun setNotify(c: MCalendar, on: Boolean) = patch(c, MailPimJson.calendarPatch(notify = on))

    fun rename(c: MCalendar, name: String) {
        if (name.isBlank()) return
        patch(c, MailPimJson.calendarPatch(name = name))
    }

    private fun patch(c: MCalendar, body: com.google.gson.JsonObject) {
        viewModelScope.launch {
            when (val r = api.patchCalendar(c.id, body)) {
                is MResult.Ok -> pim.putCalendar(r.value)
                else -> fail(r)
            }
            load(quiet = true)
        }
    }

    fun delete(c: MCalendar) {
        viewModelScope.launch {
            when (val r = api.deleteCalendar(c.id)) {
                is MResult.Ok -> pim.dropCalendar(c.id)
                else -> fail(r)
            }
            load(quiet = true)
        }
    }

    fun add(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val r = api.createCalendar(name, null)) {
                is MResult.Ok -> pim.putCalendar(r.value)
                else -> fail(r)
            }
        }
    }

    fun subscribe(url: String, name: String?) {
        if (url.isBlank()) return
        viewModelScope.launch {
            when (val r = api.subscribeCalendar(url, name)) {
                is MResult.Ok -> {
                    pim.putCalendar(r.value)
                    load(quiet = true)
                }
                else -> fail(r)
            }
        }
    }

    /** "Neu anmelden" of a Microsoft account (the hint about the company's consent follows if it stays). */
    fun relogin(accountId: Long) = pim.markRelogin(accountId)
}
