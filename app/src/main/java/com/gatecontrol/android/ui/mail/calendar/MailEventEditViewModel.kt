package com.gatecontrol.android.ui.mail.calendar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MAttendeeInput
import com.gatecontrol.android.mail.MCalendar
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MContact
import com.gatecontrol.android.mail.MEvent
import com.gatecontrol.android.mail.MEventInput
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MRule
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
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
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** The form of an event (contract D3 "Termin bearbeiten"). */
data class EventForm(
    val calendarId: Long? = null,
    val title: String = "",
    val allDay: Boolean = false,
    val startDay: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDay: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val zone: String = ZoneId.systemDefault().id,
    /** none | daily | weekly | monthly_day | monthly_weekday | yearly */
    val ruleKind: String = MailPimLogic.RULE_NONE,
    val interval: Int = 1,
    val byDay: List<String> = emptyList(),
    /** never | until | count */
    val ruleEnd: String = END_NEVER,
    val until: LocalDate? = null,
    val count: Int = 10,
    val location: String = "",
    val teams: Boolean = false,
    val onlineUrl: String = "",
    val reminders: List<Int> = emptyList(),
    val attendees: List<MAttendeeInput> = emptyList(),
    val free: Boolean = false,
    val private: Boolean = false,
    val description: String = "",
) {
    companion object {
        const val END_NEVER = "never"
        const val END_UNTIL = "until"
        const val END_COUNT = "count"
    }
}

data class EventEditState(
    val form: EventForm = EventForm(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** The event being changed (null: a new one). */
    val existing: MEvent? = null,
    /** The rule can do more than the form: only this occurrence, or replacing the whole rule. */
    val ruleLocked: Boolean = false,
    val ruleTouched: Boolean = false,
    val onlineTouched: Boolean = false,
    /** A series' occurrence: ask "Nur dieser Termin" / "Alle Termine der Serie" before saving. */
    val askScope: Boolean = false,
    /** `409 conflict`: the newer state (dialog "Der Termin wurde inzwischen geändert"). */
    val conflict: MEvent? = null,
    val attendeeInput: String = "",
    val suggestions: List<MContact> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    /** Saved: the screen closes. */
    val done: Boolean = false,
)

/** A new event or a change (contract D3): the form, the attendees' suggestions, saving with the etag, conflicts. */
@HiltViewModel
class MailEventEditViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val calendars: StateFlow<MCalendars?> = pim.calendars
    val deviceZone: ZoneId = ZoneId.systemDefault()

    private val id: Long? = saved.get<String>(Screen.MailEventEdit.ARG_ID)?.toLongOrNull()?.takeIf { it > 0 }
    private val occurrence: String? = saved.get<String>(Screen.MailEventEdit.ARG_OCC)?.takeIf { it.isNotBlank() }
    private val argScope: String? = saved.get<String>(Screen.MailEventEdit.ARG_SCOPE)

    private val _state = MutableStateFlow(EventEditState())
    val state: StateFlow<EventEditState> = _state.asStateFlow()

    private var suggestJob: Job? = null
    /** The scope the person picked ("occurrence" | "series"), null until asked. */
    private var scope: String? = argScope?.takeIf { it == "occurrence" || it == "series" }

    val isNew: Boolean get() = id == null

    init {
        if (pim.calendars.value == null) viewModelScope.launch { pim.refreshCalendars() }
        if (id != null) load() else newForm(saved)
    }

    private fun newForm(saved: SavedStateHandle) {
        val cals = pim.calendars.value
        val now = System.currentTimeMillis()
        val start = saved.get<String>(Screen.MailEventEdit.ARG_START)?.toLongOrNull()
        val end = saved.get<String>(Screen.MailEventEdit.ARG_END)?.toLongOrNull()
        val allDay = saved.get<String>(Screen.MailEventEdit.ARG_ALL_DAY) == "1"
        val (a, b) = if (start != null) start to (end ?: (start + 3_600_000L)) else MailPimLogic.nextHour(now, deviceZone)
        val s = MailPimLogic.local(a, deviceZone)
        val e = MailPimLogic.local(maxOf(b, a), deviceZone)
        val cal = saved.get<String>(Screen.MailEventEdit.ARG_CAL)?.toLongOrNull() ?: defaultCalendar(cals)
        val to = saved.get<String>(Screen.MailEventEdit.ARG_TO)?.let { MailLogic.parseRecipients(it).addresses }.orEmpty()
        val prefs = cals?.prefs
        _state.update {
            it.copy(
                form = EventForm(
                    calendarId = cal,
                    allDay = allDay,
                    startDay = s.toLocalDate(),
                    startTime = s.toLocalTime(),
                    endDay = e.toLocalDate(),
                    endTime = e.toLocalTime(),
                    zone = deviceZone.id,
                    reminders = listOfNotNull(if (allDay) prefs?.allDayReminder else prefs?.defaultReminder ?: 15),
                    attendees = to.map { a -> MAttendeeInput(a.email, a.name) },
                ),
            )
        }
    }

    private fun defaultCalendar(cals: MCalendars?): Long? {
        val writable = cals?.calendars?.filter { it.writable }.orEmpty()
        return cals?.prefs?.defaultCalendarId?.takeIf { d -> writable.any { it.id == d } } ?: writable.firstOrNull { it.isDefault }?.id ?: writable.firstOrNull()?.id
    }

    private fun load() {
        val eventId = id ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = pim.event(eventId, occurrence)) {
                is MResult.Ok -> fill(r.value)
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** The form from an event (also "Neu laden" of a conflict). */
    private fun fill(ev: MEvent) {
        val o = ev.occ
        val zone = MailPimLogic.zone(o.timeZone) ?: deviceZone
        val form = if (o.allDay) {
            val (a, b) = MailPimLogic.datesOf(o, deviceZone)
            EventForm(startDay = a, endDay = b, allDay = true)
        } else {
            val s = MailPimLogic.local(o.start, zone)
            val e = MailPimLogic.local(o.end, zone)
            EventForm(startDay = s.toLocalDate(), startTime = s.toLocalTime(), endDay = e.toLocalDate(), endTime = e.toLocalTime())
        }
        val rule = ev.recurrence?.rule
        _state.update {
            it.copy(
                loading = false,
                existing = ev,
                ruleLocked = ev.recurrence?.editable == false,
                ruleTouched = false,
                onlineTouched = false,
                conflict = null,
                form = form.copy(
                    calendarId = o.calendarId,
                    title = o.title,
                    zone = zone.id,
                    ruleKind = MailPimLogic.kindOf(rule),
                    interval = rule?.interval ?: 1,
                    byDay = rule?.byDay.orEmpty(),
                    ruleEnd = when {
                        rule?.until != null -> EventForm.END_UNTIL
                        rule?.count != null -> EventForm.END_COUNT
                        else -> EventForm.END_NEVER
                    },
                    until = MailPimLogic.date(rule?.until),
                    count = rule?.count ?: 10,
                    location = o.location.orEmpty(),
                    teams = o.online?.provider == "teams" && o.online.url.isBlank(),
                    onlineUrl = o.online?.url.orEmpty(),
                    reminders = ev.reminders,
                    attendees = ev.attendees.filter { a -> !a.self }.map { a -> MAttendeeInput(a.email, a.name.ifBlank { null }, a.role) },
                    free = o.busy == "free",
                    private = o.private,
                    description = ev.description,
                ),
            )
        }
    }

    fun edit(change: (EventForm) -> EventForm) = _state.update { it.copy(form = change(it.form)) }

    /** The rule changed (it is sent with the series only then). */
    fun editRule(change: (EventForm) -> EventForm) = _state.update { it.copy(form = change(it.form), ruleTouched = true) }

    fun editOnline(change: (EventForm) -> EventForm) = _state.update { it.copy(form = change(it.form), onlineTouched = true) }

    /** The start moves: the end keeps the length. */
    fun setStart(day: LocalDate, time: LocalTime) {
        val f = _state.value.form
        val zone = MailPimLogic.zone(f.zone) ?: deviceZone
        val length = MailPimLogic.ms(f.endDay, f.endTime, zone) - MailPimLogic.ms(f.startDay, f.startTime, zone)
        val endMs = MailPimLogic.ms(day, time, zone) + length.coerceAtLeast(0)
        val e = MailPimLogic.local(endMs, zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(f.startDay, f.endDay)
        edit {
            if (it.allDay) it.copy(startDay = day, endDay = day.plusDays(days.coerceAtLeast(0)))
            else it.copy(startDay = day, startTime = time, endDay = e.toLocalDate(), endTime = e.toLocalTime())
        }
    }

    // ─── attendees ──────────────────────────────────────────────────

    fun setAttendeeInput(v: String) {
        val ends = v.endsWith(",") || v.endsWith(";") || v.endsWith("\n")
        if (ends) {
            commitAttendees(v)
            return
        }
        _state.update { it.copy(attendeeInput = v) }
        suggestJob?.cancel()
        val q = v.trim()
        if (q.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            val found = api.contacts(q).valueOrNull().orEmpty()
            val taken = _state.value.form.attendees.map { it.email.lowercase() }.toSet()
            _state.update { it.copy(suggestions = found.filter { c -> c.group || c.email.lowercase() !in taken }) }
        }
    }

    fun commitAttendees(raw: String? = null) {
        val parsed = MailLogic.parseRecipients(raw ?: _state.value.attendeeInput)
        if (parsed.addresses.isEmpty() && raw == null) return
        _state.update { s ->
            val list = (s.form.attendees + parsed.addresses.map { MAttendeeInput(it.email, it.name) }).distinctBy { it.email.lowercase() }
            s.copy(form = s.form.copy(attendees = list.take(100)), attendeeInput = parsed.rest, suggestions = emptyList())
        }
    }

    /** A suggestion: its address, or a group's members. */
    fun pickAttendee(c: MContact) {
        val add = if (c.group) c.members.map { MAttendeeInput(it.email, it.name) } else listOf(MAttendeeInput(c.email, c.name))
        _state.update { s ->
            s.copy(form = s.form.copy(attendees = (s.form.attendees + add).distinctBy { it.email.lowercase() }.take(100)), attendeeInput = "", suggestions = emptyList())
        }
    }

    fun removeAttendee(a: MAttendeeInput) = edit { it.copy(attendees = it.attendees - a) }

    /** The account the invitations go from ("Einladungen werden gesendet über …"), null when the provider sends them. */
    fun sender(me: MMe?, cal: MCalendar?): MAccount? {
        cal ?: return null
        cal.accountId?.let { return me?.account(it) }
        val kind = if (cal.kind == "business") MAccount.KIND_BUSINESS else MAccount.KIND_PERSONAL
        return me?.accounts?.firstOrNull { it.kind == kind } ?: me?.accounts?.firstOrNull()
    }

    // ─── saving ─────────────────────────────────────────────────────

    /** The rule of the form (null: none). */
    fun rule(f: EventForm = _state.value.form): MRule? = MailPimLogic.ruleFor(
        f.ruleKind, f.startDay, f.interval.coerceIn(1, 999), f.byDay,
        until = f.until?.takeIf { f.ruleEnd == EventForm.END_UNTIL }?.toString(),
        count = f.count.takeIf { f.ruleEnd == EventForm.END_COUNT }?.coerceIn(1, 1000),
    )

    /** What is wrong with the form (a text key), null when it can be saved. */
    fun problem(f: EventForm = _state.value.form): String? {
        if (f.title.isBlank()) return "title"
        if (f.calendarId == null) return "calendar"
        val zone = MailPimLogic.zone(f.zone) ?: return "zone"
        if (f.allDay) {
            if (f.endDay.isBefore(f.startDay)) return "end"
        } else if (MailPimLogic.ms(f.endDay, f.endTime, zone) < MailPimLogic.ms(f.startDay, f.startTime, zone)) return "end"
        return null
    }

    /** The form's start and end in ms (all-day: the midnights of the first day and after the last). */
    private fun times(f: EventForm): Pair<Long, Long> {
        val zone = MailPimLogic.zone(f.zone) ?: deviceZone
        return if (f.allDay) MailPimLogic.range(f.startDay, f.endDay, deviceZone)
        else MailPimLogic.ms(f.startDay, f.startTime, zone) to MailPimLogic.ms(f.endDay, f.endTime, zone)
    }

    /** Whether the time of a shown occurrence was changed in the form. */
    fun timeChanged(s: EventEditState = _state.value): Boolean {
        val ev = s.existing ?: return true
        val (a, b) = times(s.form)
        val o = ev.occ
        val (oa, ob) = if (o.allDay) MailPimLogic.datesOf(o, deviceZone).let { (x, y) -> MailPimLogic.range(x, y, deviceZone) } else o.start to o.end
        return s.form.allDay != o.allDay || a != oa || b != ob || (!s.form.allDay && s.form.zone != (o.timeZone ?: s.form.zone))
    }

    /**
     * Whether the whole series may take this change: a changed time needs the series' own times (`Event.series`,
     * Mail 1.3.0) to move it by as much; without them only this occurrence can change its time.
     */
    fun seriesAllowed(s: EventEditState = _state.value): Boolean =
        s.existing?.occ?.occurrence == null || !timeChanged(s) || s.existing.series != null

    private fun input(s: EventEditState, series: Boolean): MEventInput {
        val f = s.form
        val zone = MailPimLogic.zone(f.zone) ?: deviceZone
        val (sd, ed) = MailPimLogic.allDayDates(f.startDay, f.endDay)
        val sendRule = s.existing == null || (series && s.ruleTouched)
        val sendOnline = s.existing == null || s.onlineTouched
        val ev = s.existing
        // the whole series of a shown occurrence: its time only when changed, moved from the series' own times
        val seriesOfOcc = series && ev != null && ev.occ.occurrence != null
        val sendTime = !seriesOfOcc || (timeChanged(s) && ev?.series != null)
        val (start0, end0) = times(f)
        val shift = if (seriesOfOcc && ev?.series != null) {
            MailPimLogic.shiftSeries(ev.series, ev.occ, f.allDay, start0, end0, sd, ed, zone)
        } else {
            null
        }
        val start = shift?.start ?: start0
        val end = shift?.end ?: end0
        val ssd = shift?.startDate ?: sd
        val sed = shift?.endDate ?: ed
        return MEventInput(
            calendarId = f.calendarId.takeIf { s.existing == null },
            title = f.title.trim(),
            allDay = f.allDay.takeIf { sendTime },
            start = start.takeIf { !f.allDay && sendTime },
            end = end.takeIf { !f.allDay && sendTime },
            startDate = ssd.takeIf { f.allDay && sendTime },
            endDate = sed.takeIf { f.allDay && sendTime },
            timeZone = zone.id.takeIf { sendTime },
            location = f.location.trim().takeIf { it.isNotEmpty() || s.existing != null },
            description = f.description.takeIf { it.isNotEmpty() || s.existing != null },
            teams = f.teams,
            onlineUrl = f.onlineUrl.trim(),
            onlineSet = sendOnline && (s.existing != null || f.teams || f.onlineUrl.isNotBlank()),
            reminders = f.reminders,
            recurrence = rule(f),
            recurrenceSet = sendRule && (s.existing != null || f.ruleKind != MailPimLogic.RULE_NONE),
            attendees = f.attendees,
            busy = if (f.free) "free" else "busy",
            private = f.private,
            sendInvites = if (f.attendees.isNotEmpty()) true else null,
        )
    }

    /** Saving: a series' occurrence asks first ("Nur dieser" / "Alle"). */
    fun save() {
        val s = _state.value
        if (s.saving || problem() != null) return
        commitAttendees(s.attendeeInput.takeIf { it.isNotBlank() })
        val ev = s.existing
        if (ev != null && ev.occ.occurrence != null && scope == null) {
            _state.update { it.copy(askScope = true) }
            return
        }
        send(etag = ev?.etag)
    }

    fun pickScope(series: Boolean) {
        if (series && !seriesAllowed()) return
        scope = if (series) "series" else "occurrence"
        _state.update { it.copy(askScope = false) }
        send(etag = _state.value.existing?.etag)
    }

    fun dismissScope() = _state.update { it.copy(askScope = false) }

    private fun send(etag: String?) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val ev = s.existing
            val r = if (ev == null) {
                api.createEvent(MailPimJson.eventBody(input(s, series = true)))
            } else {
                val sc = scope ?: "series"
                val occ = ev.occ.occurrence.takeIf { sc == "occurrence" }
                api.updateEvent(ev.id, MailPimJson.eventBody(input(s, series = sc == "series"), scope = if (ev.occ.occurrence != null) sc else null, occurrence = occ, etag = etag))
            }
            when (r) {
                is MResult.Ok -> _state.update { it.copy(saving = false, done = true) }
                is MResult.Failed -> {
                    val current = if (r.code == "conflict") MailPimJson.conflictEvent(r.error) else null
                    // Mail 1.3.0: the series cannot take this move (`invalid { field: 'start', reason: 'series_shift' }`)
                    val shiftRefused = r.code == "invalid" && r.error?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString == SERIES_SHIFT
                    when {
                        current != null -> _state.update { it.copy(saving = false, conflict = current) }
                        shiftRefused -> _state.update { it.copy(saving = false, message = SERIES_SHIFT) }
                        else -> _state.update { it.copy(saving = false, message = r.code) }
                    }
                }
                else -> _state.update { it.copy(saving = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** Conflict: "Meine Änderung erneut anwenden" (the form over the newer state). */
    fun applyAgain() {
        val current = _state.value.conflict ?: return
        _state.update { it.copy(conflict = null, existing = current) }
        send(etag = current.etag)
    }

    /** Conflict: "Neu laden" (the newer state into the form; the own change is gone). */
    fun reloadConflict() {
        val current = _state.value.conflict ?: return
        fill(current)
    }

    fun dismissConflict() = _state.update { it.copy(conflict = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        const val SERIES_SHIFT = "series_shift"
    }
}
