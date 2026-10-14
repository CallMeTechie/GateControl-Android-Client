package com.gatecontrol.android.familie

import com.gatecontrol.android.familie.board.OverviewBoard
import com.gatecontrol.android.familie.meals.OverviewMeals
import com.gatecontrol.android.familie.money.OverviewAllowance
import com.gatecontrol.android.familie.presence.PresenceMember
import com.gatecontrol.android.familie.rides.OverviewRides

/**
 * Domain models of the Familie plugin's app API (gatecontrol-familie,
 * `/api/v1/client/plugins/gatecontrol-familie/api/…`, contract B3). They
 * come out of [FamilieJson], which validates what the server sends; every
 * field here is safe to use. Field names follow the contract.
 */

const val FAMILIE_PLUGIN = "gatecontrol-familie"
const val ALLTAG_PLUGIN = "gatecontrol-alltag"

enum class FamilieRole(val wire: String) {
    PARENT("parent"),
    CHILD("child");

    companion object {
        fun fromWire(value: String?): FamilieRole? = entries.firstOrNull { it.wire == value }
    }
}

/** How a child's phone answers a locate request (`locateMode`). */
enum class LocateMode(val wire: String) {
    AUTO("auto"),
    CONFIRM("confirm"),
    OFF("off");

    companion object {
        fun fromWire(value: String?): LocateMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Which modules the administrator switched on (`GET me` → `modules`). */
data class FamilieModules(
    val shopping: Boolean = false,
    val tasks: Boolean = false,
    val stars: Boolean = false,
    val dates: Boolean = false,
    val locate: Boolean = false,
    val arrivalSos: Boolean = false,
    val places: Boolean = false,
    /** 1.1: Abholen & Fahrten. */
    val rides: Boolean = false,
    /** 1.1: Notfall-Karte. */
    val emergency: Boolean = false,
    /** 1.1: wall tablet (portal only, no app UI). */
    val wall: Boolean = false,
    /** 1.2: Taschengeld. */
    val allowance: Boolean = false,
    /** 1.2: Wunschlisten. */
    val wishes: Boolean = false,
    /** 1.2: Haushaltsbuch (parents only). */
    val expenses: Boolean = false,
    /** 1.2: Pinnwand & Packlisten. */
    val board: Boolean = false,
    /** 1.2: Essensplan. */
    val meals: Boolean = false,
    /** 1.2: Anwesenheit & Smart Home. */
    val presence: Boolean = false,
) {
    val any: Boolean
        get() = shopping || tasks || stars || dates || locate || arrivalSos || places || rides || emergency ||
            allowance || wishes || expenses || board || meals || presence
}

data class FamilieMe(
    val userId: Int,
    val name: String,
    val role: FamilieRole,
    val age: Int?,
    /** The caller's own mode when the caller is a child, else null ('off' for an adult child). */
    val locateMode: LocateMode?,
    val color: String? = null,
) {
    val isParent: Boolean get() = role == FamilieRole.PARENT
    val isChild: Boolean get() = role == FamilieRole.CHILD
}

data class FamilieMember(
    val userId: Int,
    val name: String,
    val role: FamilieRole,
    val age: Int?,
    /** `#RRGGBB` or null. */
    val color: String?,
    /** Children only, and only shown to parents. */
    val locateMode: LocateMode?,
    val locatable: Boolean?,
) {
    val isChild: Boolean get() = role == FamilieRole.CHILD
}

/** `GET me`. */
data class FamilieProfile(
    val me: FamilieMe,
    val modules: FamilieModules,
    /** The other members (the caller may or may not be listed). */
    val members: List<FamilieMember>,
    val serverTime: String?,
) {
    fun member(userId: Int?): FamilieMember? = members.firstOrNull { it.userId == userId }

    fun nameOf(userId: Int?): String? =
        if (userId == me.userId) me.name else member(userId)?.name

    /** Everybody including the caller (the caller first). */
    val everyone: List<FamilieMember>
        get() = listOf(
            FamilieMember(me.userId, me.name, me.role, me.age, me.color ?: member(me.userId)?.color, me.locateMode, null),
        ) + members.filter { it.userId != me.userId }

    val children: List<FamilieMember> get() = members.filter { it.isChild && it.userId != me.userId }
}

// ─── Overview (start screen) ──────────────────────────────────────────

data class OverviewList(val id: String, val name: String, val open: Int)

data class OverviewStars(val week: Int, val goal: Int)

data class OverviewLocate(val childId: Int, val status: String, val at: String?, val requestId: Int? = null)

/** Someone said "Ich gehe einkaufen" (`overview.shopping.going`). */
data class OverviewGoing(val userId: Int, val listId: String?, val at: String?)

/** A recent "Bin angekommen" (`overview.arrivals`). */
data class OverviewArrival(val userId: Int, val label: String?, val at: String?)

data class FamilieOverview(
    /** null: module off. */
    val shoppingLists: List<OverviewList>?,
    val myTasks: List<FamilieTask>?,
    val openTasks: Int,
    val stars: OverviewStars?,
    val nextDates: List<FamilieEvent>?,
    val lastLocates: List<OverviewLocate>?,
    val going: List<OverviewGoing> = emptyList(),
    /** null: module arrival_sos off. */
    val arrivals: List<OverviewArrival>? = null,
    /** null: module rides off (or a server before 1.1). */
    val rides: OverviewRides? = null,
    /** 1.2, null when off: the child's balance or, for parents, the sum. */
    val allowance: OverviewAllowance? = null,
    /** 1.2, null when off: today's meals. */
    val meals: OverviewMeals? = null,
    /** 1.2, null when off: pinned notes, open checklists. */
    val board: OverviewBoard? = null,
    /** 1.2, null when off: who is home (members that share their presence). */
    val presence: List<PresenceMember>? = null,
)

// ─── Tasks ────────────────────────────────────────────────────────────

data class TaskLastDone(val by: Int?, val at: String?)

data class FamilieTask(
    val id: Int,
    val title: String,
    /** fixed | rotate | first */
    val mode: String,
    val assignees: List<Int>,
    val current: Int?,
    /** none | daily | weekly | monthly */
    val repeat: String,
    /** 1 = Monday … 7 = Sunday (weekly). */
    val weekdays: List<Int>,
    val dueTime: String?,
    val dueDate: String?,
    val stars: Int,
    val reminder: Boolean,
    val doneToday: Boolean,
    val lastDone: TaskLastDone?,
) {
    /** Who the task is on right now: the current one (rotate), else every assignee. */
    fun responsible(): List<Int> = if (mode == MODE_ROTATE && current != null) listOf(current) else assignees

    companion object {
        const val MODE_FIXED = "fixed"
        const val MODE_ROTATE = "rotate"
        const val MODE_FIRST = "first"
        val MODES = listOf(MODE_FIXED, MODE_ROTATE, MODE_FIRST)
        val REPEATS = listOf("none", "daily", "weekly", "monthly")
    }
}

/** Stars per child: this week's total and the goal (keys = user ids). */
data class StarsBoard(val week: Map<Int, Int>, val goal: Map<Int, Int>)

data class TasksPayload(val tasks: List<FamilieTask>, val stars: StarsBoard?)

/** Body of `POST tasks` / `PATCH tasks/<id>`. */
data class TaskDraft(
    val title: String,
    val mode: String = FamilieTask.MODE_FIXED,
    val assignees: List<Int> = emptyList(),
    val repeat: String = "none",
    val weekdays: List<Int> = emptyList(),
    val dueTime: String? = null,
    val dueDate: String? = null,
    val stars: Int = 0,
    val reminder: Boolean = true,
)

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

// ─── Locate, arrival, SOS ─────────────────────────────────────────────

data class LocateRequest(
    val id: Int,
    val childId: Int,
    val requestedBy: Int?,
    val mode: LocateMode?,
    /** pending | answered | declined | failed | expired */
    val status: String,
    val createdAt: String?,
    val expiresAt: String?,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    /** Time of the fix. */
    val at: String?,
    val battery: Int?,
    /** Reason of a failed/declined request when the server sends it. */
    val code: String?,
    val requestedByName: String? = null,
    val childName: String? = null,
) {
    val hasPosition: Boolean get() = lat != null && lon != null

    companion object {
        const val PENDING = "pending"
        const val ANSWERED = "answered"
        const val DECLINED = "declined"
        const val FAILED = "failed"
        const val EXPIRED = "expired"
    }
}

/** Why the child's phone could not answer (`POST …/fail`). */
enum class LocateFailCode(val wire: String) {
    NO_PERMISSION("no_permission"),
    LOCATION_OFF("location_off"),
    TIMEOUT("timeout"),
}

// ─── Places ───────────────────────────────────────────────────────────

data class FamiliePlace(
    val id: Int,
    val ownerId: Int,
    val targetUserId: Int,
    val title: String,
    val note: String,
    val lat: Double,
    val lon: Double,
    /** 50…2000 m */
    val radius: Int,
    /** enter | exit */
    val trigger: String,
    /** note | list | tell */
    val action: String,
    val listId: String?,
    val tellUserIds: List<Int>,
    /** once | always */
    val repeat: String,
    val active: Boolean,
    /** accepted | pending | declined */
    val consent: String,
    val ownerName: String? = null,
    val targetName: String? = null,
) {
    companion object {
        const val TRIGGER_ENTER = "enter"
        const val TRIGGER_EXIT = "exit"
        const val ACTION_NOTE = "note"
        const val ACTION_LIST = "list"
        const val ACTION_TELL = "tell"
        const val CONSENT_ACCEPTED = "accepted"
        const val CONSENT_PENDING = "pending"
        const val CONSENT_DECLINED = "declined"
        const val MIN_RADIUS = 50
        const val MAX_RADIUS = 2000
    }
}

/** Body of `POST places` / `PATCH places/<id>`. */
data class PlaceDraft(
    val title: String,
    val note: String = "",
    val lat: Double,
    val lon: Double,
    val radius: Int = 150,
    val trigger: String = FamiliePlace.TRIGGER_ENTER,
    val action: String = FamiliePlace.ACTION_NOTE,
    val listId: String? = null,
    val tellUserIds: List<Int> = emptyList(),
    val repeat: String = "always",
    val active: Boolean = true,
    val targetUserId: Int? = null,
)
