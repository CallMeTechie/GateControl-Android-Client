package com.gatecontrol.android.familie.rides

/**
 * Abholen & Fahrten (Familie 1.1, module `rides`). A ride is one leg: one
 * trip at one time. Occurrences of a weekly series carry the composite id
 * `<seriesId>:<date>`; a change to one of them is stored by the server as
 * an override of that date. Dates and times are the server's local time.
 */

/** A member (`{userId, name}`) as the ride answers carry it. */
data class RidePerson(val userId: Int, val name: String)

/** Who drives: a family member, a free label ("Familie Weber", "Bus"), or nobody. */
sealed interface RideDriver {
    data class Member(val userId: Int, val name: String) : RideDriver

    data class Label(val label: String) : RideDriver
}

data class Ride(
    /** `"<int>"` or `"<seriesId>:<date>"`. */
    val id: String,
    val seriesId: Int?,
    /** `YYYY-MM-DD` */
    val date: String,
    /** `HH:MM` */
    val time: String,
    val title: String,
    val place: String,
    val lat: Double?,
    val lon: Double?,
    val childIds: List<Int>,
    /** open | taken | cancelled */
    val status: String,
    val driver: RideDriver?,
    val requestedBy: RidePerson?,
    val declined: List<RidePerson>,
    val askedAt: String?,
    val remindMinutes: Int,
    val note: String,
    /** The series' rule (weekdays, first date, end), null for a one-off ride. */
    val rule: RideRule? = null,
    /** Start as an instant (server addition). */
    val startAt: String? = null,
) {
    val isOpen: Boolean get() = status == STATUS_OPEN
    val isTaken: Boolean get() = status == STATUS_TAKEN
    val isCancelled: Boolean get() = status == STATUS_CANCELLED
    val hasPosition: Boolean get() = lat != null && lon != null
    val isSeries: Boolean get() = seriesId != null
    val driverId: Int? get() = (driver as? RideDriver.Member)?.userId

    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_TAKEN = "taken"
        const val STATUS_CANCELLED = "cancelled"
        val STATUSES = listOf(STATUS_OPEN, STATUS_TAKEN, STATUS_CANCELLED)
        const val DEFAULT_REMIND = 20
        const val MAX_REMIND = 120
        val REMIND_STEPS = listOf(0, 10, 20, 30, 45, 60, 90, 120)
    }
}

/** What the driver field of a create / edit asks for. */
sealed interface DriverChoice {
    data object None : DriverChoice

    data class Member(val userId: Int) : DriverChoice

    data class Label(val label: String) : DriverChoice
}

/** Weekly repetition of a new ride (`repeat`). */
data class RideRepeat(
    /** 1 = Monday … 7 = Sunday. */
    val weekdays: List<Int>,
    val endDate: String? = null,
)

/** The rule of a weekly series as the server returns it (`repeat`). */
data class RideRule(val weekdays: List<Int>, val startDate: String?, val endDate: String?)

/** Body of `POST rides` / `PATCH rides/<id>`. */
data class RideDraft(
    val date: String,
    val time: String,
    val title: String,
    val place: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val childIds: List<Int> = emptyList(),
    val driver: DriverChoice = DriverChoice.None,
    val remindMinutes: Int = Ride.DEFAULT_REMIND,
    val note: String = "",
    /** New ride: makes it a series; change of the whole series: its new rule. */
    val repeat: RideRepeat? = null,
    /** "Wer kann …?" right away (children: always). */
    val ask: Boolean? = null,
)

/** `GET overview` → `rides`. */
data class OverviewRides(val next: List<Ride>, val open: Int)
