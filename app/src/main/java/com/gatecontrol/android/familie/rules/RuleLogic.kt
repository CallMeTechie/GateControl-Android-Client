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

    /** Profile line: active, paused and the newest run. */
    data class Summary(val active: Int, val paused: Int, val lastRunAt: String?)

    fun summary(p: RulesPayload): Summary {
        val last = (p.rules.mapNotNull { it.lastRunAt } + p.log.filter { it.fired }.mapNotNull { it.at })
            .maxByOrNull { epoch(it) ?: Long.MIN_VALUE }
        return Summary(p.rules.count { it.active }, p.rules.count { !it.active }, last)
    }

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
}
