package com.gatecontrol.android.familie.rules

import java.time.DayOfWeek
import java.time.Instant

/** Filter tabs of the list (mockup RulesList). */
enum class RuleFilter { ALL, REMINDERS, PRESENCE, PLACES }

/** Pure decisions of the rules screens, unit-tested. */
object RuleLogic {

    private val NUMBER_RE = Regex("-?[0-9]+(?:[.,][0-9]+)?")

    /** A reminder: a schedule or a personal rule. */
    fun isReminder(r: Rule): Boolean = r.trigger.type == RuleTriggerType.SCHEDULE || r.scope == RuleScope.PERSONAL

    /** The plugin's `kind` (1.9) decides; older rules by their trigger. */
    fun matches(r: Rule, filter: RuleFilter): Boolean = when (filter) {
        RuleFilter.ALL -> true
        RuleFilter.REMINDERS -> r.kind?.let { it == KIND_REMINDER } ?: isReminder(r)
        RuleFilter.PRESENCE -> r.kind?.let { it == KIND_PRESENCE } ?: (r.trigger.type in RuleTriggerType.PRESENCE)
        RuleFilter.PLACES -> r.kind?.let { it == KIND_PLACE } ?: (r.trigger.type in RuleTriggerType.PLACE)
    }

    const val KIND_REMINDER = "reminder"
    const val KIND_PRESENCE = "presence"
    const val KIND_PLACE = "place"

    /** The rules of [filter]: active ones first, then by title. */
    fun filter(rules: List<Rule>, filter: RuleFilter): List<Rule> =
        rules.filter { matches(it, filter) }.sortedWith(compareByDescending<Rule> { it.active }.thenBy { it.title.lowercase() })

    /** Profile line: active, paused, the newest run, today's open reminders, the holiday mode. */
    data class Summary(val active: Int, val paused: Int, val lastRunAt: String?, val open: Int = 0, val vacation: Boolean = false)

    fun summary(p: RulesPayload): Summary {
        val last = (p.rules.mapNotNull { it.lastRunAt } + p.log.filter { it.fired }.mapNotNull { it.at })
            .maxByOrNull { epoch(it) ?: Long.MIN_VALUE }
        return Summary(p.rules.count { it.active }, p.rules.count { !it.active }, last, p.open.size, p.vacation?.on == true)
    }

    /** Open reminders: one's own first, then by time. */
    fun openSorted(open: List<RuleOpen>, myUserId: Int?): List<RuleOpen> =
        open.sortedWith(compareByDescending<RuleOpen> { it.isMine(myUserId) }.thenBy { epoch(it.since) ?: Long.MAX_VALUE })

    fun epoch(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * Weekdays (ISO, Mo = 1) as a short text: runs of three or more as a
     * range ("Mo–Fr"), else listed ("Sa, So"); every day → [everyDay].
     */
    fun weekdays(days: List<Int>, names: (Int) -> String, everyDay: String): String {
        val d = days.filter { it in 1..7 }.distinct().sorted()
        if (d.size == 7) return everyDay
        val parts = mutableListOf<String>()
        var i = 0
        while (i < d.size) {
            var j = i
            while (j + 1 < d.size && d[j + 1] == d[j] + 1) j++
            if (j - i >= 2) parts += names(d[i]) + "–" + names(d[j]) else (i..j).forEach { parts += names(d[it]) }
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    /** The number in a widget value ("32 %" → 32, "10,5°" → 10.5); null without one. */
    fun number(text: String?): Double? = text?.let { NUMBER_RE.find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }

    /** A value for display without a needless ".0". */
    fun format(v: Double): String = if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e12) v.toLong().toString() else v.toString().replace('.', ',')

    /** Can [r] be saved? A title, a complete trigger and at least one action. */
    fun valid(r: Rule): Boolean {
        if (r.title.isBlank() || r.actions.isEmpty()) return false
        val t = r.trigger
        return when (t.type) {
            RuleTriggerType.SCHEDULE -> t.weekdays.isNotEmpty() && t.time != null
            in RuleTriggerType.PERSON -> t.userId != null
            in RuleTriggerType.PLACE -> t.placeId != null
            else -> true
        }
    }

    /**
     * A new reminder for a device (long press on a widget, mockup
     * ReminderEditor): personal, on [today] at 18:00, the device's first value
     * below a sensible limit, a push to me with "In 1 Std." and "Erledigt".
     * No device button: the widget hangs on one of the device's actions (a
     * car's climate action), which is rarely the one a reminder wants — the
     * editor offers the device's actions to pick from.
     */
    fun reminderFor(device: RuleOptionDevice?, actionId: String?, myUserId: Int?, today: DayOfWeek): Rule {
        val field = device?.fields?.firstOrNull()
        val current = number(field?.current)
        val limit = when {
            field?.unit?.contains('%') == true -> 50.0
            current != null -> current
            else -> 0.0
        }
        val id = device?.actionId ?: actionId
        return Rule(
            id = null,
            title = device?.label.orEmpty(),
            scope = RuleScope.PERSONAL,
            trigger = RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = listOf(today.value), time = "18:00"),
            conditions = RuleConditions(
                device = if (id != null && field != null) listOf(DeviceCondition(id, field.id, RuleOp.LT, limit)) else emptyList(),
            ),
            actions = listOf(
                RuleAction(
                    type = RuleActionType.NOTIFY,
                    to = RuleRecipients(RuleRecipients.ME),
                    facts = true,
                    buttons = listOf(RuleButton.SNOOZE, RuleButton.DONE),
                ),
            ),
        )
    }

    /** A new rule for the house: when the first person comes home, a push to the parents. */
    fun newRule(personal: Boolean, myUserId: Int?): Rule = Rule(
        id = null,
        title = "",
        scope = if (personal) RuleScope.PERSONAL else RuleScope.HOUSE,
        trigger = if (personal) RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = listOf(1, 2, 3, 4, 5), time = "07:00") else RuleTrigger(RuleTriggerType.FIRST_HOME),
        actions = listOf(
            RuleAction(
                type = RuleActionType.NOTIFY,
                to = if (personal) RuleRecipients(RuleRecipients.ME) else RuleRecipients(RuleRecipients.PARENTS),
            ),
        ),
    )

    /**
     * A personal rule may only reach its creator: notify / messenger actions
     * go to `me` (the server refuses anything else, `forbidden`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun forScope(r: Rule, myUserId: Int?): Rule {
        if (r.scope != RuleScope.PERSONAL) return r
        val me = RuleRecipients(RuleRecipients.ME)
        return r.copy(actions = r.actions.map { a -> if (a.type == RuleActionType.NOTIFY || a.type == RuleActionType.MESSENGER) a.copy(to = me) else a })
    }

    /** Toggles the button [b] of a notify action; at most one device button. */
    fun toggleButton(a: RuleAction, b: String, on: Boolean): RuleAction {
        val quick = b.startsWith(RuleButton.QUICK_PREFIX)
        val rest = a.buttons.filter { it != b && !(on && quick && it.startsWith(RuleButton.QUICK_PREFIX)) }
        // The device's own button first, then "In 1 Std.", then "Erledigt" (Android shows three).
        val order = listOf(RuleButton.QUICK_PREFIX, RuleButton.SNOOZE, RuleButton.DONE)
        val next = if (on) rest + b else rest
        return a.copy(buttons = next.sortedBy { x -> order.indexOfFirst { x.startsWith(it) }.let { if (it < 0) 9 else it } }.take(3))
    }

    // ─── templates (a new rule from „Vorlage“) ──────────────────────

    private val CHARGE_RE = Regex("(?i)lade|akku|batter|charge|soc")
    private val CHARGE_ACTION_RE = Regex("(?i)laden|charg")
    private val OFF_RE = Regex("(?i)licht|lampe|leuchte|light|lamp|klima|climate|air ?con|heiz|heat|ventilator|fan")
    private val SCHOOL_RE = Regex("(?i)schul|school")
    private val WASTE_RE = Regex("(?i)m(ü|ue)ll|abfall|abfuhr|tonne|waste|trash|bin")

    /** The templates [o] and [can] allow: only those whose devices and triggers exist here. */
    fun templates(o: RuleOptions, can: RuleCan): List<RuleTemplate> = buildList {
        if (can.any) add(RuleTemplate.TRASH)
        if (can.any && o.deviceValues && chargeField(o) != null) add(RuleTemplate.CAR_CHARGE)
        if (can.house && RuleTriggerType.ALL_AWAY in o.triggers) add(RuleTemplate.ALL_AWAY_OFF)
        val children = o.people.any { it.child }
        if (can.house && children && RuleTriggerType.CHILD_HOME_ALONE in o.triggers) add(RuleTemplate.CHILD_ALONE)
        if (can.house && children && RuleTriggerType.PLACE_ENTER in o.triggers) add(RuleTemplate.SCHOOL)
    }

    /** A device value that looks like a charge level (a "%" or its name), with its device. */
    fun chargeField(o: RuleOptions): Pair<RuleOptionDevice, RuleOptionField>? {
        for (d in o.devices) {
            val f = d.fields.firstOrNull { f -> f.unit?.contains('%') == true || f.current?.contains('%') == true || CHARGE_RE.containsMatchIn(f.label) }
            if (f != null) return d to f
        }
        return null
    }

    /**
     * The rule a template starts with ([title] in the app's language; [today] for a weekly
     * schedule). Everything stays editable; a place for [RuleTemplate.SCHOOL] may still be missing.
     */
    fun fromTemplate(t: RuleTemplate, o: RuleOptions, can: RuleCan, title: String, today: DayOfWeek): Rule {
        val personal = if (can.personal) RuleScope.PERSONAL else RuleScope.HOUSE
        val toMe = RuleRecipients(RuleRecipients.ME)
        val toParents = RuleRecipients(RuleRecipients.PARENTS)
        val remindButtons = if (o.buttons) listOf(RuleButton.SNOOZE, RuleButton.DONE) else emptyList()
        return when (t) {
            RuleTemplate.TRASH -> {
                val cal = o.calendars.firstOrNull { it.source.startsWith("alltag:") && WASTE_RE.containsMatchIn(it.label) }
                Rule(
                    id = null,
                    title = title,
                    scope = personal,
                    trigger = RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = if (cal != null) (1..7).toList() else listOf(today.value), time = "19:00"),
                    conditions = RuleConditions(calendar = cal?.let { CalendarCondition(dayOffset = 1, sources = listOf(it.source), match = "") }),
                    actions = listOf(RuleAction(RuleActionType.NOTIFY, to = toMe, facts = cal != null, buttons = remindButtons)),
                )
            }
            RuleTemplate.CAR_CHARGE -> {
                val (device, field) = chargeField(o) ?: (null to null)
                val charge = device?.let { o.deviceActions(it.actionId).firstOrNull { a -> CHARGE_ACTION_RE.containsMatchIn(a.title) } }
                Rule(
                    id = null,
                    title = title,
                    scope = personal,
                    trigger = RuleTrigger(RuleTriggerType.SCHEDULE, weekdays = (1..7).toList(), time = "20:00"),
                    conditions = RuleConditions(
                        device = if (device != null && field != null) listOf(DeviceCondition(device.actionId, field.id, RuleOp.LT, 50.0)) else emptyList(),
                    ),
                    actions = listOf(
                        RuleAction(
                            RuleActionType.NOTIFY,
                            to = toMe,
                            facts = true,
                            buttons = if (o.buttons) listOfNotNull(charge?.let { RuleButton.quick(it.actionId) }) + remindButtons else emptyList(),
                        ),
                    ),
                )
            }
            RuleTemplate.ALL_AWAY_OFF -> {
                // not the car's air conditioning: it belongs to whoever drives off
                val car = chargeField(o)?.first?.let { d -> o.deviceActions(d.actionId).map { it.actionId }.toSet() }.orEmpty()
                val off = o.actions.filter { it.kind == "toggle" && it.actionId !in car && (OFF_RE.containsMatchIn(it.title) || OFF_RE.containsMatchIn(it.subtitle.orEmpty())) }.take(6)
                Rule(
                    id = null,
                    title = title,
                    scope = RuleScope.HOUSE,
                    trigger = RuleTrigger(RuleTriggerType.ALL_AWAY, graceMinutes = 10),
                    actions = off.map { a ->
                        RuleAction(RuleActionType.QUICK_ACTION, plugin = a.actionId.substringBefore('/'), id = a.actionId.substringAfter('/'), title = a.title, state = "off", kind = a.kind)
                    }.ifEmpty { listOf(RuleAction(RuleActionType.NOTIFY, to = toParents)) },
                )
            }
            RuleTemplate.CHILD_ALONE -> Rule(
                id = null,
                title = title,
                scope = RuleScope.HOUSE,
                trigger = RuleTrigger(RuleTriggerType.CHILD_HOME_ALONE),
                actions = listOf(RuleAction(RuleActionType.NOTIFY, to = toParents)),
            )
            RuleTemplate.SCHOOL -> {
                val place = o.places.firstOrNull { SCHOOL_RE.containsMatchIn(it.name) }
                val children = o.people.filter { it.child }
                Rule(
                    id = null,
                    title = title,
                    scope = RuleScope.HOUSE,
                    trigger = RuleTrigger(RuleTriggerType.PLACE_ENTER, placeId = place?.id, placeName = place?.name, userId = children.singleOrNull()?.userId),
                    actions = listOf(RuleAction(RuleActionType.NOTIFY, to = toParents)),
                )
            }
        }
    }
}

/** The templates of a new rule (only offered when what they need is there, [RuleLogic.templates]). */
enum class RuleTemplate(val id: String) {
    TRASH("trash"),
    CAR_CHARGE("car_charge"),
    ALL_AWAY_OFF("all_away_off"),
    CHILD_ALONE("child_alone"),
    SCHOOL("school"),
    ;

    companion object {
        fun of(id: String?): RuleTemplate? = entries.firstOrNull { it.id == id }
    }
}
