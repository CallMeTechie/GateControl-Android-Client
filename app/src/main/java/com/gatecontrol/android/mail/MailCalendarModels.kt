package com.gatecontrol.android.mail

/**
 * Calendars of the Mail plugin (since Mail 1.2.0, contract C3 "Kalender"): the person's calendars, the overlays of
 * Familie, Alltag and the birthdays, occurrences in a range, one event, invitations. Read by [MailPimJson] from the
 * Gson tree, never by reflection.
 */

/** `Account.calendar` / `Account.contacts`: the account's calendar or address book side. */
data class MPimSide(
    val sync: Boolean,
    /** ok | syncing | auth_failed | unreachable | consent_required | none | off */
    val state: String,
    val readOnly: Boolean,
    val server: String?,
) {
    companion object {
        const val STATE_OK = "ok"
        const val STATE_SYNCING = "syncing"
        const val STATE_AUTH_FAILED = "auth_failed"
        const val STATE_UNREACHABLE = "unreachable"
        const val STATE_CONSENT = "consent_required"
        const val STATE_NONE = "none"
        const val STATE_OFF = "off"
    }
}

data class MCalendar(
    val id: Long,
    /** local | graph | caldav | ics */
    val source: String,
    val accountId: Long?,
    val name: String,
    /** `#RRGGBB` */
    val color: String,
    /** private | business | other */
    val kind: String,
    val writable: Boolean,
    val visible: Boolean,
    /** Reminders of this calendar. */
    val notify: Boolean,
    val isDefault: Boolean,
    val canInvite: Boolean,
    /** Teams meetings (Microsoft 365 calendars). */
    val canOnline: Boolean,
    val maxReminders: Int,
    /** ok | syncing | auth_failed | unreachable | consent_required */
    val state: String,
    val errorCode: String?,
    val syncedAt: Long?,
    val version: Long,
) {
    val local: Boolean get() = source == SOURCE_LOCAL

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_GRAPH = "graph"
        const val SOURCE_CALDAV = "caldav"
        const val SOURCE_ICS = "ics"
    }
}

/** An overlay: `familie`, `alltag:<subId>` or `birthdays` (read only). */
data class MOverlay(val id: String, val source: String, val name: String, val color: String, val visible: Boolean)

/** The person's calendar settings (`CalPrefs`). */
data class MCalPrefs(
    val defaultCalendarId: Long?,
    /** day | 3day | week | month | list */
    val view: String,
    /** 1 Monday, 0 Sunday */
    val weekStart: Int,
    val workWeek: Boolean,
    val timeZone: String?,
    val reminders: Boolean,
    /** Minutes before, null: none. */
    val defaultReminder: Int?,
    /** Minutes before midnight of an all-day event (1080 → 18:00 the day before), null: none. */
    val allDayReminder: Int?,
) {
    companion object {
        const val VIEW_DAY = "day"
        const val VIEW_3DAY = "3day"
        const val VIEW_WEEK = "week"
        const val VIEW_MONTH = "month"
        const val VIEW_LIST = "list"
        val VIEWS = listOf(VIEW_DAY, VIEW_3DAY, VIEW_WEEK, VIEW_MONTH, VIEW_LIST)
        val DEFAULT = MCalPrefs(null, VIEW_3DAY, 1, false, null, true, 15, 1080)
    }
}

/** A Microsoft account whose calendar needs the person to sign in again. */
data class MConsent(val accountId: Long, val missing: List<String>)

/** `GET calendars`. */
data class MCalendars(val calendars: List<MCalendar>, val overlays: List<MOverlay>, val prefs: MCalPrefs, val consent: List<MConsent>) {
    fun calendar(id: Long?): MCalendar? = id?.let { c -> calendars.firstOrNull { it.id == c } }
}

data class MOnline(val url: String, /** teams | zoom | meet | webex | other */ val provider: String)

/** One occurrence of an event in a range (`Occurrence`). */
data class MOccurrence(
    /** `<eventId>:<occurrence>` or `<eventId>` */
    val key: String,
    val eventId: Long,
    val calendarId: Long,
    /** The original start (ms as text) or date (`YYYY-MM-DD`) of a recurring one; null for a single event. */
    val occurrence: String?,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    /** All-day: the first and the last day (inclusive). */
    val startDate: String?,
    val endDate: String?,
    val timeZone: String?,
    val location: String?,
    val online: MOnline?,
    /** confirmed | tentative | cancelled */
    val status: String,
    /** busy | free | tentative | oof */
    val busy: String,
    /** organizer | accepted | tentative | declined | needs_action | null */
    val myResponse: String?,
    val recurring: Boolean,
    val attendees: Int,
    val private: Boolean,
    val reminder: Int?,
) {
    val cancelled: Boolean get() = status == STATUS_CANCELLED
    /** An invitation not answered yet (mockup: dashed). */
    val open: Boolean get() = myResponse == RESPONSE_NEEDS_ACTION

    companion object {
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_TENTATIVE = "tentative"
        const val STATUS_CANCELLED = "cancelled"
        const val RESPONSE_ORGANIZER = "organizer"
        const val RESPONSE_ACCEPTED = "accepted"
        const val RESPONSE_TENTATIVE = "tentative"
        const val RESPONSE_DECLINED = "declined"
        const val RESPONSE_NEEDS_ACTION = "needs_action"
    }
}

/** An overlay's entry (Familie, Alltag, a birthday): read only. */
data class MOverlayItem(
    val key: String,
    val overlayId: String,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val startDate: String?,
    val endDate: String?,
    val location: String?,
)

/** `GET events`. */
data class MEvents(val items: List<MOccurrence>, val overlays: List<MOverlayItem>, val pending: List<Long>)

/** The organizer of an event or an invitation. */
data class MOrganizer(val name: String, val email: String, val self: Boolean, val contactId: Long?) {
    val label: String get() = name.ifBlank { email }
}

data class MAttendee(
    val name: String,
    val email: String,
    /** required | optional | resource */
    val role: String,
    /** accepted | tentative | declined | needs_action */
    val response: String,
    val self: Boolean,
    val contactId: Long?,
) {
    val label: String get() = name.ifBlank { email }

    companion object {
        const val ROLE_REQUIRED = "required"
        const val ROLE_OPTIONAL = "optional"
        const val ROLE_RESOURCE = "resource"
    }
}

/** A rule the form can show and change (also what `POST events` takes as `recurrence`). */
data class MRule(
    /** daily | weekly | monthly | yearly */
    val freq: String,
    val interval: Int = 1,
    /** `MO` … `SU` */
    val byDay: List<String>? = null,
    val byMonthDay: List<Int>? = null,
    /** With one [byDay]: the n-th weekday of the month (-1 the last). */
    val bySetPos: Int? = null,
    /** `YYYY-MM-DD` */
    val until: String? = null,
    val count: Int? = null,
) {
    companion object {
        const val DAILY = "daily"
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val YEARLY = "yearly"
        val FREQS = listOf(DAILY, WEEKLY, MONTHLY, YEARLY)
        val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    }
}

/** `Event.recurrence`. */
data class MRecurrence(
    val rrule: String,
    /** False: the rule can do more than the form (only "this one" or replacing the whole rule). */
    val editable: Boolean,
    val rule: MRule,
    val exceptions: List<String>,
    val deleted: List<String>,
)

/** `GET events/<id>`: an occurrence with everything about its event. */
data class MEvent(
    val occ: MOccurrence,
    val description: String,
    /** Cleaned by the server (Graph), shown like a mail's HTML. */
    val descriptionHtml: String?,
    val organizer: MOrganizer?,
    val attendees: List<MAttendee>,
    val reminders: List<Int>,
    val recurrence: MRecurrence?,
    /** The series of an occurrence ([occ] of a recurring event). */
    val seriesId: Long?,
    val readOnly: Boolean,
    val canRespond: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val categories: List<String>,
    val invitationId: Long?,
    val url: String?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val etag: String?,
    /**
     * Since Mail 1.3.0: the series' own start and end (DTSTART/DTEND of the master) — the reference when the whole
     * series moves. Null from older servers: a change of time then only goes to this occurrence.
     */
    val series: MSeries? = null,
) {
    val id: Long get() = occ.eventId
}

/**
 * `Event.series` (Mail 1.3.0): DTSTART/DTEND of the series' master in the form of `start` / `end`; an all-day series
 * also with its dates (`endDate` inclusive).
 */
data class MSeries(val start: Long, val end: Long, val allDay: Boolean, val startDate: String? = null, val endDate: String? = null)

/** The new times of a series moved from one of its occurrences ([MailPimLogic.shiftSeries]). */
data class MShift(val start: Long? = null, val end: Long? = null, val startDate: String? = null, val endDate: String? = null)

/** `POST/PATCH events` → `{ event, sent }`. */
data class MEventSaved(val event: MEvent, val sentMail: Int, val sentInternal: Int)

data class MInviteCounts(val accepted: Int, val tentative: Int, val declined: Int, val needsAction: Int) {
    val total: Int get() = accepted + tentative + declined + needsAction
}

data class MConflict(val title: String, val start: Long, val end: Long, val calendarId: Long?)

/** An invitation (from a mail or from a GateControl person). */
data class MInvitation(
    val id: Long,
    /** mail | internal */
    val kind: String,
    val messageId: Long?,
    val accountId: Long?,
    /** request | cancel | reply | counter */
    val method: String,
    /** open | answered | applied | cancelled | outdated */
    val state: String,
    val uid: String,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val startDate: String?,
    val endDate: String?,
    val timeZone: String?,
    val location: String?,
    val online: MOnline?,
    /** The rule in words (`{ de, en }`). */
    val recurrenceDe: String?,
    val recurrenceEn: String?,
    val organizer: MOrganizer,
    /** Who answers (reply, counter); the organizer otherwise. */
    val from: MAddress,
    val attendees: List<MAttendee>,
    val counts: MInviteCounts,
    val myResponse: String?,
    val comment: String?,
    /** counter: the time proposed. */
    val proposedStart: Long?,
    val proposedEnd: Long?,
    val eventId: Long?,
    val calendarId: Long?,
    val calendarChoices: List<Long>,
    val day: List<MOccurrence>,
    val conflicts: List<MConflict>,
) {
    companion object {
        const val METHOD_REQUEST = "request"
        const val METHOD_CANCEL = "cancel"
        const val METHOD_REPLY = "reply"
        const val METHOD_COUNTER = "counter"
        const val STATE_OPEN = "open"
        const val STATE_ANSWERED = "answered"
        const val STATE_APPLIED = "applied"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_OUTDATED = "outdated"
    }
}

/** `POST invitations/<id>/respond|apply` → `{ invitation, event }`. */
data class MInviteAnswer(val invitation: MInvitation, val event: MEvent?)

/** What the form sends (`POST events` / `PATCH events/<id>`); null fields stay out of a PATCH. */
data class MEventInput(
    val calendarId: Long? = null,
    val title: String? = null,
    val allDay: Boolean? = null,
    val start: Long? = null,
    val end: Long? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val timeZone: String? = null,
    /** "" clears it. */
    val location: String? = null,
    val description: String? = null,
    /** [online] given: a Teams meeting ([teams]) or a link ([onlineUrl], "" clears it). */
    val teams: Boolean = false,
    val onlineUrl: String? = null,
    val onlineSet: Boolean = false,
    val reminders: List<Int>? = null,
    /** [recurrenceSet]: the rule ([recurrence] null: none). */
    val recurrence: MRule? = null,
    val recurrenceSet: Boolean = false,
    val attendees: List<MAttendeeInput>? = null,
    val busy: String? = null,
    val private: Boolean? = null,
    val sendInvites: Boolean? = null,
)

data class MAttendeeInput(val email: String, val name: String? = null, val role: String = MAttendee.ROLE_REQUIRED)

/**
 * Realtime event `calendar`: `changed` (a calendar's events, [from]/[to] when known), `calendars` (the list and its
 * states), `invitation`, `conflict`.
 */
data class MCalChanged(
    val kind: String,
    val calendarId: Long? = null,
    val accountId: Long? = null,
    val from: Long? = null,
    val to: Long? = null,
    val invitationId: Long? = null,
    val messageId: Long? = null,
    val eventId: Long? = null,
) {
    companion object {
        const val CHANGED = "changed"
        const val CALENDARS = "calendars"
        const val INVITATION = "invitation"
        const val CONFLICT = "conflict"
    }
}
