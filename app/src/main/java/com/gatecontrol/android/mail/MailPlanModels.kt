package com.gatecontrol.android.mail

/**
 * Free/busy, shared calendars and resources of the Mail plugin (since Mail 1.9.0, phase 3: mockups Planer, Teilen,
 * Ressource, Anfragen). Read by [MailPimJson] from the Gson tree, never by reflection. The person only serves themselves
 * here: resources are kept on the plugin's page in GateControl, rights are checked by the server.
 */

/** Working hours (`CalPrefs.workHours`, `Resource.rules.hours`): [days] 1 = Monday … 7 = Sunday, `HH:MM` (`to` up to `24:00`). */
data class MWorkHours(val days: List<Int>, val from: String, val to: String) {
    companion object {
        val DEFAULT = MWorkHours(listOf(1, 2, 3, 4, 5), "08:00", "18:00")
    }
}

/** A taken time without a title: `busy` | `tentative` (free/busy) or `busy` | `pending` (a resource's day). */
data class MBusyBlock(val start: Long, val end: Long, val status: String, val mine: Boolean = false) {
    companion object {
        const val BUSY = "busy"
        const val TENTATIVE = "tentative"
        const val PENDING = "pending"
    }
}

/** A row of `POST freebusy`: the person asking (`self`, always first), a person, a resource, someone else. */
data class MFreeBusyRow(
    val key: String,
    /** self | person | microsoft | resource | external */
    val kind: String,
    val email: String?,
    val resourceId: Long?,
    val userId: Long?,
    val name: String,
    /** `state: 'ok'`; false: unknown (the whole row grey). */
    val known: Boolean,
    val blocks: List<MBusyBlock>,
) {
    companion object {
        const val SELF = "self"
        const val PERSON = "person"
        const val MICROSOFT = "microsoft"
        const val RESOURCE = "resource"
        const val EXTERNAL = "external"
    }
}

data class MFreeBusy(val from: Long, val to: Long, val items: List<MFreeBusyRow>)

/** `POST freebusy/suggest`: slots where everybody known is free, in the asker's working hours. */
data class MSuggestions(val items: List<MSlot>, val unknown: List<String>, val workHours: MWorkHours?, val timeZone: String?)

data class MSlot(val start: Long, val end: Long)

/** A GateControl calendar somebody shares with the person („Mit mir geteilt“). */
data class MSharedCalendar(
    val id: Long,
    val name: String,
    val color: String,
    val ownerId: Long?,
    val ownerName: String,
    /** freebusy | read | write */
    val level: String,
    /** user | group */
    val via: String,
    val group: String?,
    val visible: Boolean,
    /** Shown once (the owner reads „hat angenommen“); false: „neu“. */
    val accepted: Boolean,
    val writable: Boolean,
)

/** A calendar of an account that belongs to another mailbox (`GET shared` → `accounts`): shown like the account's own. */
data class MSharedAccountCalendar(
    val calendarId: Long,
    val accountId: Long?,
    val name: String,
    val color: String,
    val ownerEmail: String,
    val writable: Boolean,
    val visible: Boolean,
)

/** `GET shared`. */
data class MSharedList(val items: List<MSharedCalendar>, val accounts: List<MSharedAccountCalendar>)

/** A share of an own calendar („Geteilt mit“). */
data class MShare(
    val id: Long,
    /** user | group */
    val type: String,
    val principalId: Long,
    val name: String,
    /** freebusy | read | write */
    val level: String,
    /** accepted | pending | group */
    val state: String,
    val members: Int?,
) {
    companion object {
        const val USER = "user"
        const val GROUP = "group"
        const val FREEBUSY = "freebusy"
        const val READ = "read"
        const val WRITE = "write"
        val LEVELS = listOf(FREEBUSY, READ, WRITE)
        const val STATE_ACCEPTED = "accepted"
        const val STATE_PENDING = "pending"
    }
}

/** `GET calendars/<id>/shares` (with the person's „Frei/Gebucht für alle“). */
data class MShares(val items: List<MShare>, val freeBusy: Boolean?)

/** A person or a group to share with (`GET principals`; never addresses). */
data class MPrincipal(
    /** user | group */
    val type: String,
    val id: Long,
    val name: String,
    val initials: String?,
    val color: String?,
    /** A group's path. */
    val sub: String?,
    val members: Int?,
)

/** A resource's rules: at most [maxMinutes] long, at most [horizonDays] ahead, only in [hours]. */
data class MResourceRules(val maxMinutes: Int?, val horizonDays: Int?, val hours: MWorkHours?)

/** A resource the person may book (`GET resources`, with [busy] for a range). */
data class MResource(
    val id: Long,
    val name: String,
    /** room | vehicle | device | other */
    val kind: String,
    val location: String?,
    val seats: Int?,
    val equipment: String?,
    val color: String,
    /** auto | approval */
    val approval: String,
    /** Who approves, as labels („Fuhrpark“). */
    val approvers: List<String>,
    val rules: MResourceRules,
    val timeZone: String?,
    /** The resource as an attendee (never shown). */
    val address: String,
    val busy: List<MBusyBlock>,
) {
    val needsApproval: Boolean get() = approval == APPROVAL

    companion object {
        const val ROOM = "room"
        const val VEHICLE = "vehicle"
        const val DEVICE = "device"
        const val OTHER = "other"
        val KINDS = listOf(ROOM, VEHICLE, DEVICE, OTHER)
        const val AUTO = "auto"
        const val APPROVAL = "approval"
    }
}

/** `GET resources`. */
data class MResources(val items: List<MResource>, val kinds: List<String>)

/** A rule a booking breaks: `max_duration` ([limit] minutes), `horizon` ([limit] days), `hours` ([days], [from]–[to]). */
data class MViolation(val rule: String, val limit: Int?, val days: List<Int>, val from: String?, val to: String?) {
    companion object {
        const val MAX_DURATION = "max_duration"
        const val HORIZON = "horizon"
        const val HOURS = "hours"
    }
}

/** A booking of a resource (a request, the person's own one, a decided one). */
data class MBooking(
    val id: Long,
    val resourceId: Long,
    val resourceName: String,
    val resourceKind: String,
    val resourceColor: String?,
    val resourceLocation: String?,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val startDate: String?,
    val endDate: String?,
    /** pending | confirmed | declined | cancelled */
    val state: String,
    val bookerName: String,
    /** The own event (own bookings only). */
    val eventId: Long?,
    val note: String?,
    val violations: List<MViolation>,
    /** Confirmed bookings in the way (pending only). */
    val conflicts: List<MSlot>,
    val decidedBy: String?,
    val decidedAt: Long?,
    val override: Boolean,
    val comment: String?,
    val canDecide: Boolean,
    /** The resource's day around it. */
    val day: List<MBusyBlock>,
) {
    companion object {
        const val PENDING = "pending"
        const val CONFIRMED = "confirmed"
        const val DECLINED = "declined"
        const val CANCELLED = "cancelled"
    }
}

/** `GET bookings?state=…`. */
data class MBookings(val items: List<MBooking>, val open: Int)

/**
 * Realtime event `resources`: `request` (the person approves: the list and the count), `booking` (the person's own
 * booking: the calendar), `resources` (the list of resources).
 */
data class MResChanged(val kind: String, val bookingId: Long? = null, val resourceId: Long? = null, val state: String? = null) {
    companion object {
        const val REQUEST = "request"
        const val BOOKING = "booking"
        const val RESOURCES = "resources"
    }
}
