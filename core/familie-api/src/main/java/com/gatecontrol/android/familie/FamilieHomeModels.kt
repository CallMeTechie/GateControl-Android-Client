package com.gatecontrol.android.familie

/**
 * Models of the Familie and Alltag plugins shared by the phone app and
 * GateControl TV: the dates of the Familie plugin and the Alltag calendar
 * with its waste collection. They come out of [FamilieJsonBase], which
 * validates what the server sends; every field here is safe to use.
 */

const val FAMILIE_PLUGIN = "gatecontrol-familie"
const val ALLTAG_PLUGIN = "gatecontrol-alltag"

// ─── Dates ────────────────────────────────────────────────────────────

data class FamilieEvent(
    /** Server id as text; virtual member birthdays are `member:<userId>` (read only). */
    val id: String,
    val title: String,
    val date: String,
    val time: String?,
    val endDate: String?,
    val allDay: Boolean,
    /** none | yearly */
    val repeat: String,
    val memberIds: List<Int>,
    /** event | birthday */
    val kind: String,
    val remindDays: Int?,
    val note: String,
) {
    val readOnly: Boolean get() = id.startsWith("member:")
    val isBirthday: Boolean get() = kind == KIND_BIRTHDAY

    companion object {
        const val KIND_EVENT = "event"
        const val KIND_BIRTHDAY = "birthday"
    }
}

/** Body of `POST dates` / `PATCH dates/<id>`. */
data class EventDraft(
    val title: String,
    val date: String,
    val time: String? = null,
    val endDate: String? = null,
    val allDay: Boolean = true,
    val repeat: String = "none",
    val memberIds: List<Int> = emptyList(),
    val kind: String = FamilieEvent.KIND_EVENT,
    val remindDays: Int? = null,
    val note: String = "",
)

/** An appointment of the Alltag plugin (`GET app/events`). */
data class AlltagEvent(
    val id: String,
    val title: String,
    val date: String,
    val time: String?,
    val endTime: String?,
    val allDay: Boolean,
    val calendarName: String?,
    val calendarColor: String?,
)

data class AlltagWaste(val date: String, val kinds: List<String>, val color: String?)

data class AlltagPayload(val events: List<AlltagEvent>, val waste: List<AlltagWaste>)
