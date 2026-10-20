package com.gatecontrol.android.familie.rules

/**
 * Familie "Regeln und Erinnerungen" (contract B1, client API
 * `/api/v1/client/plugins/gatecontrol-familie/api/rules…`): the presence
 * rules of Familie 1.2–1.8 grown into reminders with a schedule, device
 * values and calendar entries as conditions.
 */

/** `scope` of a rule: for the whole house (parents) or the creator only. */
object RuleScope {
    const val HOUSE = "house"
    const val PERSONAL = "personal"
}

/** `trigger.type` values. */
object RuleTriggerType {
    const val SCHEDULE = "schedule"
    const val ALL_AWAY = "all_away"
    const val FIRST_HOME = "first_home"
    const val CHILD_HOME_ALONE = "child_home_alone"
    const val PERSON_HOME = "person_home"
    const val PERSON_AWAY = "person_away"
    const val PLACE_ENTER = "place_enter"
    const val PLACE_EXIT = "place_exit"

    /** Familie 1.12: the timetable of the Schule plugin changes (only offered with Schule installed). */
    const val SCHOOL_CHANGE = "school_change"

    val PRESENCE = setOf(ALL_AWAY, FIRST_HOME, CHILD_HOME_ALONE, PERSON_HOME, PERSON_AWAY)
    val PLACE = setOf(PLACE_ENTER, PLACE_EXIT)
    val PERSON = setOf(PERSON_HOME, PERSON_AWAY)
    /** What an older plugin without `triggers` in its options offers. */
    val ALL = listOf(SCHEDULE, FIRST_HOME, ALL_AWAY, CHILD_HOME_ALONE, PERSON_HOME, PERSON_AWAY, PLACE_ENTER, PLACE_EXIT)

    /** Every trigger the editor knows; the ones beyond [ALL] only when the plugin offers them. */
    val KNOWN = ALL + SCHOOL_CHANGE
}

/** `trigger.kinds` of [RuleTriggerType.SCHOOL_CHANGE]: what happened to a lesson. */
object RuleSchoolKind {
    const val CANCELLED = "cancelled"
    const val CHANGED = "changed"
    const val EXTRA = "extra"
    const val RESTORED = "restored"
    val ALL = listOf(CANCELLED, CHANGED, EXTRA, RESTORED)
}

/** `trigger.days` of [RuleTriggerType.SCHOOL_CHANGE]: which school day the change is about. */
object RuleSchoolDays {
    const val TODAY = "today"
    const val TOMORROW = "tomorrow"
    const val ANY = "any"
    val ALL = listOf(TODAY, TOMORROW, ANY)
}

/** `actions[].type` values. */
object RuleActionType {
    const val QUICK_ACTION = "quick_action"
    const val NOTIFY = "notify"
    const val MESSENGER = "messenger"
}

/** Buttons of a `notify` action (B1): a quick action of the device, "In 1 Std.", "Erledigt". */
object RuleButton {
    const val SNOOZE = "snooze_1h"
    const val DONE = "done"
    const val QUICK_PREFIX = "quick_action:"

    fun quick(actionId: String): String = QUICK_PREFIX + actionId
}

/** Comparison of a device condition. */
object RuleOp {
    const val LT = "<"
    const val LE = "<="
    const val GT = ">"
    const val GE = ">="
    const val EQ = "="
    val ALL = listOf(LT, LE, GT, GE, EQ)
}

/**
 * When a rule fires. [weekdays] (ISO, Mo = 1) and [time] (`HH:MM`) for a
 * schedule; [userId] for person triggers (null at a place: anyone; at
 * school_change: any child); [placeId] for place triggers; [graceMinutes]
 * for "alle weg"; [kinds] ([RuleSchoolKind]) and [days] ([RuleSchoolDays])
 * for school_change.
 */
data class RuleTrigger(
    val type: String,
    val weekdays: List<Int> = emptyList(),
    val time: String? = null,
    val userId: Int? = null,
    val placeId: Int? = null,
    val placeName: String? = null,
    val graceMinutes: Int? = null,
    val kinds: List<String> = emptyList(),
    val days: String? = null,
)

/** A local time window (may span midnight). */
data class RuleTimeWindow(val from: String, val to: String)

/** `conditions.device[]`: a value of a quick action's widget compared to a number. */
data class DeviceCondition(
    /** `<plugin>/<id>`. */
    val actionId: String,
    /** `value`, `badge` or `reading:<id>`. */
    val field: String,
    val op: String,
    val value: Double,
    /** The plugin's names of the value and its unit (answers only). */
    val label: String? = null,
    val unit: String? = null,
)

/** `conditions.calendar`: an entry today / tomorrow whose title contains [match]. */
data class CalendarCondition(
    val dayOffset: Int,
    val sources: List<String>,
    val match: String,
    val memberIds: List<Int> = emptyList(),
)

data class RuleConditions(
    val time: RuleTimeWindow? = null,
    val afterSunset: Boolean = false,
    val device: List<DeviceCondition> = emptyList(),
    val calendar: CalendarCondition? = null,
) {
    val isEmpty: Boolean get() = time == null && !afterSunset && device.isEmpty() && calendar == null
}

/**
 * Who a notify / messenger action reaches: a keyword (`parents`, `all`,
 * `family`) or user ids.
 */
data class RuleRecipients(val keyword: String? = null, val userIds: List<Int> = emptyList()) {
    companion object {
        const val PARENTS = "parents"
        const val ALL = "all"
        const val FAMILY = "family"
        /** The creator (sending only; the plugin answers with the user id). */
        const val ME = "me"
    }
}

/**
 * One "Dann" step. quick_action: [plugin] / [id] / [state] (on | off |
 * null = a button); notify: [to], [text], [facts], [buttons]; messenger:
 * [to], [text]. [available] false: the owner no longer has the quick
 * action; [needsTarget]: a toggle without a target state (older rule).
 */
data class RuleAction(
    val type: String,
    val plugin: String? = null,
    val id: String? = null,
    val title: String? = null,
    val state: String? = null,
    val to: RuleRecipients? = null,
    val text: String? = null,
    val facts: Boolean = false,
    val buttons: List<String> = emptyList(),
    val available: Boolean? = null,
    val needsTarget: Boolean = false,
    /** quick_action: toggle | button | null, and the toggle's word for [state] ("Verriegeln"). */
    val kind: String? = null,
    val verb: String? = null,
) {
    /** `<plugin>/<id>` of a quick action. */
    val actionId: String? get() = if (plugin != null && id != null) "$plugin/$id" else null
}

data class Rule(
    val id: Int?,
    val title: String,
    val scope: String = RuleScope.HOUSE,
    val active: Boolean = true,
    val trigger: RuleTrigger,
    val conditions: RuleConditions = RuleConditions(),
    val actions: List<RuleAction> = emptyList(),
    val lastRunAt: String? = null,
    val lastOk: Boolean? = null,
    val ownerId: Int? = null,
    val ownerName: String? = null,
    /** reminder | presence | place (Familie 1.9); null from older plugins. */
    val kind: String? = null,
    /** The caller may change / pause it (a child sees a house rule read only). */
    val editable: Boolean = true,
    val pausable: Boolean = true,
)

/**
 * An open reminder (`GET rules` → `open[]`): the widget of [actionIds] shows a hint. [userId] whose it
 * is (Familie 1.11: parents get every member's; null from older plugins = the caller's own);
 * [snoozeUntil] when "In 1 Std." brings it back.
 */
data class RuleOpen(
    val ruleId: Int,
    val title: String,
    val key: String?,
    val snoozed: Boolean,
    val since: String?,
    val actionIds: List<String>,
    val userId: Int? = null,
    val snoozeUntil: String? = null,
) {
    /** The caller's own (it may be ticked off or snoozed here). */
    fun isMine(myUserId: Int?): Boolean = userId == null || userId == myUserId
}

/**
 * The holiday mode (Familie 1.11, `GET rules` → `vacation`): every active house rule paused,
 * [paused] of them, until [until] (`YYYY-MM-DD`, the last day) or by hand.
 */
data class RuleVacation(val on: Boolean, val until: String? = null, val since: String? = null, val paused: Int = 0)

/** A line of the protocol. [fired] false: checked but not fired (a condition was not met). */
data class RuleLogEntry(
    val ruleId: Int?,
    val ruleName: String,
    val at: String?,
    val ok: Boolean,
    val fired: Boolean = true,
    val text: String? = null,
)

/** What the caller may create (`can`). */
data class RuleCan(val house: Boolean = false, val personal: Boolean = false) {
    val any: Boolean get() = house || personal
}

/** `GET rules`. */
data class RulesPayload(
    val rules: List<Rule>,
    val log: List<RuleLogEntry>,
    val can: RuleCan,
    val open: List<RuleOpen> = emptyList(),
    /** null: the plugin has no holiday mode (before 1.11). */
    val vacation: RuleVacation? = null,
)

/** A value of a device a condition can compare (`rules/options` → `devices[].fields[]`). */
data class RuleOptionField(val id: String, val label: String, val unit: String?, val current: String?)

/** A device (quick action) of `rules/options`; [kind] toggle | button | null. */
data class RuleOptionDevice(
    val actionId: String,
    val label: String,
    val icon: String?,
    val kind: String?,
    val fields: List<RuleOptionField>,
) {
    val plugin: String get() = actionId.substringBefore('/')
    val id: String get() = actionId.substringAfter('/')
}

/** A quick action a rule can run (`rules/options` → `actions[]`). */
data class RuleOptionAction(
    val actionId: String,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    /** toggle | button | null. */
    val kind: String?,
    /** The device the action belongs to (a car, a lamp) — its quick actions share it. */
    val deviceId: String? = null,
)

data class RuleOptionCalendar(val source: String, val label: String)

data class RuleOptionPlace(val id: Int, val name: String)

data class RuleOptionPerson(val userId: Int, val name: String, val child: Boolean = false)

/** A child with a timetable (`rules/options` → `school.students[]`). */
data class RuleOptionStudent(val userId: Int, val name: String)

/** `rules/options` → `school` (Familie 1.12, only with the Schule plugin installed). */
data class RuleOptionSchool(val students: List<RuleOptionStudent> = emptyList())

/** `GET rules/options`: what the editor offers. Empty lists from an older plugin. */
data class RuleOptions(
    val triggers: List<String> = RuleTriggerType.ALL,
    val devices: List<RuleOptionDevice> = emptyList(),
    val calendars: List<RuleOptionCalendar> = emptyList(),
    val places: List<RuleOptionPlace> = emptyList(),
    val people: List<RuleOptionPerson> = emptyList(),
    val can: RuleCan = RuleCan(),
    /** The messenger is there (a messenger action can be offered). */
    val messenger: Boolean = true,
    /** The quick actions "Gerät schalten" offers (empty from older plugins: [devices] instead). */
    val actions: List<RuleOptionAction> = emptyList(),
    /** Device values as conditions and buttons in pushes are possible (`features`). */
    val deviceValues: Boolean = true,
    val buttons: Boolean = true,
    /** The timetable's children; null without the Schule plugin (or before Familie 1.12). */
    val school: RuleOptionSchool? = null,
) {
    fun device(actionId: String?): RuleOptionDevice? = devices.firstOrNull { it.actionId == actionId }

    fun action(actionId: String?): RuleOptionAction? = actions.firstOrNull { it.actionId == actionId }

    /** Name of a device or quick action. */
    fun labelOf(actionId: String?): String? = action(actionId)?.title ?: device(actionId)?.label

    /**
     * The quick actions of the device behind [actionId] (the action that carries a widget, e.g. the
     * car's climate action): its siblings with the same device — „Laden starten“, „Klimatisierung“, …
     * Without a device just that action.
     */
    fun deviceActions(actionId: String?): List<RuleOptionAction> {
        val a = action(actionId) ?: return emptyList()
        val dev = a.deviceId ?: return listOf(a)
        val plugin = a.actionId.substringBefore('/')
        return actions.filter { it.deviceId == dev && it.actionId.substringBefore('/') == plugin }
    }
}

/** [ok] null: not checked (e.g. the trigger itself). */
data class RuleTestDetail(val kind: String, val ok: Boolean?, val text: String)

/** `POST rules/<id>/test`: a dry run. */
data class RuleTestResult(val wouldFire: Boolean, val details: List<RuleTestDetail>)
