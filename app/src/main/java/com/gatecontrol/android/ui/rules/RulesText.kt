package com.gatecontrol.android.ui.rules

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.rules.CalendarCondition
import com.gatecontrol.android.familie.rules.DeviceCondition
import com.gatecontrol.android.familie.rules.Rule
import com.gatecontrol.android.familie.rules.RuleAction
import com.gatecontrol.android.familie.rules.RuleActionType
import com.gatecontrol.android.familie.rules.RuleLogic
import com.gatecontrol.android.familie.rules.RuleOp
import com.gatecontrol.android.familie.rules.RuleOptions
import com.gatecontrol.android.familie.rules.RuleRecipients
import com.gatecontrol.android.familie.rules.RuleSchoolDays
import com.gatecontrol.android.familie.rules.RuleSchoolKind
import com.gatecontrol.android.familie.rules.RuleTemplate
import com.gatecontrol.android.familie.rules.RuleTrigger
import com.gatecontrol.android.familie.rules.RuleTriggerType
import com.gatecontrol.android.ui.components.GcIcons
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Texts of rules: triggers, conditions and actions as in the mockups. */

@Composable
internal fun ruleLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Short weekday name, ISO day (Mo = 1). */
internal fun dayName(day: Int, locale: Locale): String = DayOfWeek.of(day.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, locale).trimEnd('.')

internal fun triggerIcon(type: String): ImageVector = when (type) {
    RuleTriggerType.SCHEDULE -> GcIcons.Bell
    in RuleTriggerType.PLACE -> GcIcons.Pin
    RuleTriggerType.ALL_AWAY -> GcIcons.Power
    RuleTriggerType.SCHOOL_CHANGE -> GcIcons.School
    else -> GcIcons.Home
}

@Composable
internal fun triggerLabel(type: String): String = when (type) {
    RuleTriggerType.SCHEDULE -> stringResource(R.string.rules_trigger_schedule)
    RuleTriggerType.FIRST_HOME -> stringResource(R.string.rules_trigger_first_home)
    RuleTriggerType.ALL_AWAY -> stringResource(R.string.rules_trigger_all_away)
    RuleTriggerType.CHILD_HOME_ALONE -> stringResource(R.string.rules_trigger_child_alone)
    RuleTriggerType.PERSON_HOME -> stringResource(R.string.rules_trigger_person_home)
    RuleTriggerType.PERSON_AWAY -> stringResource(R.string.rules_trigger_person_away)
    RuleTriggerType.PLACE_ENTER -> stringResource(R.string.rules_trigger_place_enter)
    RuleTriggerType.PLACE_EXIT -> stringResource(R.string.rules_trigger_place_exit)
    RuleTriggerType.SCHOOL_CHANGE -> stringResource(R.string.rules_trigger_school_change)
    else -> type
}

@Composable
internal fun templateTitle(t: RuleTemplate): String = stringResource(
    when (t) {
        RuleTemplate.TRASH -> R.string.rules_tpl_trash
        RuleTemplate.CAR_CHARGE -> R.string.rules_tpl_car
        RuleTemplate.ALL_AWAY_OFF -> R.string.rules_tpl_all_away
        RuleTemplate.CHILD_ALONE -> R.string.rules_tpl_child_alone
        RuleTemplate.SCHOOL -> R.string.rules_tpl_school
        RuleTemplate.SCHOOL_CANCELLED -> R.string.rules_tpl_school_cancelled
    },
)

@Composable
internal fun templateHint(t: RuleTemplate): String = stringResource(
    when (t) {
        RuleTemplate.TRASH -> R.string.rules_tpl_trash_hint
        RuleTemplate.CAR_CHARGE -> R.string.rules_tpl_car_hint
        RuleTemplate.ALL_AWAY_OFF -> R.string.rules_tpl_all_away_hint
        RuleTemplate.CHILD_ALONE -> R.string.rules_tpl_child_alone_hint
        RuleTemplate.SCHOOL -> R.string.rules_tpl_school_hint
        RuleTemplate.SCHOOL_CANCELLED -> R.string.rules_tpl_school_cancelled_hint
    },
)

internal fun templateIcon(t: RuleTemplate): ImageVector = when (t) {
    RuleTemplate.TRASH -> GcIcons.Trash
    RuleTemplate.CAR_CHARGE -> GcIcons.Car
    RuleTemplate.ALL_AWAY_OFF -> GcIcons.Power
    RuleTemplate.CHILD_ALONE -> GcIcons.Home
    RuleTemplate.SCHOOL -> GcIcons.Pin
    RuleTemplate.SCHOOL_CANCELLED -> GcIcons.School
}

@Composable
internal fun scheduleText(t: RuleTrigger): String {
    val locale = ruleLocale()
    val days = RuleLogic.weekdays(t.weekdays, { dayName(it, locale) }, stringResource(R.string.rules_every_day))
    return listOfNotNull(days.takeIf { it.isNotEmpty() }, t.time).joinToString(" ")
}

/** The kinds of a school_change trigger as chips. */
@Composable
internal fun schoolKindLabel(kind: String): String = when (kind) {
    RuleSchoolKind.CANCELLED -> stringResource(R.string.rules_school_kind_cancelled)
    RuleSchoolKind.CHANGED -> stringResource(R.string.rules_school_kind_changed)
    RuleSchoolKind.EXTRA -> stringResource(R.string.rules_school_kind_extra)
    RuleSchoolKind.RESTORED -> stringResource(R.string.rules_school_kind_restored)
    else -> kind
}

@Composable
internal fun schoolDaysLabel(days: String): String = when (days) {
    RuleSchoolDays.TODAY -> stringResource(R.string.rules_school_days_today)
    RuleSchoolDays.TOMORROW -> stringResource(R.string.rules_school_days_tomorrow)
    else -> stringResource(R.string.rules_school_days_any)
}

/** „Wenn bei Lena eine Stunde ausfällt (morgen)“, „Wenn sich bei einem Kind der Stundenplan ändert“. */
@Composable
internal fun schoolText(t: RuleTrigger, child: String?): String {
    val s = RuleLogic.schoolSummary(t)
    val who = child?.let { stringResource(R.string.rules_school_who_child, it) } ?: stringResource(R.string.rules_school_who_any)
    val line = stringResource(
        when (s.kind) {
            RuleSchoolKind.CANCELLED -> R.string.rules_school_cancelled
            RuleSchoolKind.CHANGED -> R.string.rules_school_changed
            RuleSchoolKind.EXTRA -> R.string.rules_school_extra
            RuleSchoolKind.RESTORED -> R.string.rules_school_restored
            else -> R.string.rules_school_any
        },
        who,
    )
    val day = when (s.day) {
        RuleSchoolDays.TODAY -> stringResource(R.string.rules_today)
        RuleSchoolDays.TOMORROW -> stringResource(R.string.rules_tomorrow)
        else -> null
    }
    return if (day != null) "$line ($day)" else line
}

/** "So 18:00", "Anna kommt heim", "Schule betreten · Lena", "Alle weg · nach 10 Min.". */
@Composable
internal fun triggerText(t: RuleTrigger, options: RuleOptions, nameOf: (Int) -> String?): String {
    val person = t.userId?.let { id ->
        nameOf(id) ?: options.people.firstOrNull { it.userId == id }?.name ?: options.school?.students?.firstOrNull { it.userId == id }?.name
    }
    val place = t.placeName ?: t.placeId?.let { id -> options.places.firstOrNull { it.id == id }?.name }
    return when (t.type) {
        RuleTriggerType.SCHEDULE -> scheduleText(t)
        RuleTriggerType.PERSON_HOME -> person?.let { stringResource(R.string.rules_person_home, it) } ?: triggerLabel(t.type)
        RuleTriggerType.PERSON_AWAY -> person?.let { stringResource(R.string.rules_person_away, it) } ?: triggerLabel(t.type)
        RuleTriggerType.PLACE_ENTER, RuleTriggerType.PLACE_EXIT -> listOfNotNull(
            triggerLabel(t.type) + (place?.let { " · $it" } ?: ""),
            person ?: stringResource(R.string.rules_anyone),
        ).joinToString(" · ")
        RuleTriggerType.ALL_AWAY -> triggerLabel(t.type) + (t.graceMinutes?.takeIf { it > 0 }?.let { " · " + stringResource(R.string.rules_after_min, it) } ?: "")
        RuleTriggerType.SCHOOL_CHANGE -> schoolText(t, person ?: t.userId?.let { "#$it" })
        else -> triggerLabel(t.type)
    }
}

internal fun opText(op: String): String = when (op) {
    RuleOp.LE -> "≤"
    RuleOp.GE -> "≥"
    else -> op
}

@Composable
internal fun deviceText(d: DeviceCondition, options: RuleOptions): String {
    val dev = options.device(d.actionId)
    val field = dev?.fields?.firstOrNull { it.id == d.field }
    val unit = (field?.unit ?: d.unit)?.let { " $it" } ?: ""
    // „Elroq · Ladestand < 50 %“ — the device's name (not the action carrying its widget), then the value
    val name = dev?.label ?: d.actionId.substringAfter('/')
    return listOfNotNull(name, field?.label ?: d.label).joinToString(" · ") + " ${opText(d.op)} ${RuleLogic.format(d.value)}$unit"
}

@Composable
internal fun calendarText(c: CalendarCondition): String {
    val day = stringResource(if (c.dayOffset == 1) R.string.rules_tomorrow else R.string.rules_today)
    return if (c.match.isBlank()) stringResource(R.string.rules_calendar_any, day) else stringResource(R.string.rules_calendar_match, day, c.match)
}

/** The conditions as short lines. */
@Composable
internal fun conditionTexts(r: Rule, options: RuleOptions): List<String> = buildList {
    r.conditions.time?.let { add(stringResource(R.string.rules_cond_time_value, it.from, it.to)) }
    if (r.conditions.afterSunset) add(stringResource(R.string.rules_cond_sunset))
    r.conditions.device.forEach { add(deviceText(it, options)) }
    r.conditions.calendar?.let { add(calendarText(it)) }
}

@Composable
internal fun recipientsText(to: RuleRecipients?, myUserId: Int?, nameOf: (Int) -> String?): String = when {
    to == null || to.keyword == RuleRecipients.ME -> stringResource(R.string.rules_to_me)
    to.keyword == RuleRecipients.PARENTS -> stringResource(R.string.rules_to_parents)
    to.keyword == RuleRecipients.ALL -> stringResource(R.string.rules_to_all)
    to.keyword == RuleRecipients.FAMILY -> stringResource(R.string.rules_to_family_chat)
    to.userIds == listOfNotNull(myUserId) -> stringResource(R.string.rules_to_me)
    to.userIds.size == 1 -> nameOf(to.userIds[0]) ?: pluralStringResource(R.plurals.rules_to_people, 1, 1)
    else -> pluralStringResource(R.plurals.rules_to_people, to.userIds.size, to.userIds.size)
}

/** "Flurlicht einschalten", "Mitteilung an mich", "Chat: „{Name} ist zu Hause“". */
@Composable
internal fun actionText(a: RuleAction, options: RuleOptions, myUserId: Int?, nameOf: (Int) -> String?): String = when (a.type) {
    RuleActionType.QUICK_ACTION -> {
        val label = a.title ?: options.labelOf(a.actionId) ?: a.id.orEmpty()
        when (a.state) {
            "on" -> stringResource(R.string.rules_action_on, label)
            "off" -> stringResource(R.string.rules_action_off, label)
            else -> stringResource(R.string.rules_action_run, label)
        }
    }
    RuleActionType.NOTIFY -> stringResource(R.string.rules_action_notify, recipientsText(a.to, myUserId, nameOf))
    RuleActionType.MESSENGER -> a.text?.let { stringResource(R.string.rules_action_chat_text, it) }
        ?: stringResource(R.string.rules_action_chat, recipientsText(a.to, myUserId, nameOf))
    else -> stringResource(R.string.rules_action_unknown, a.type)
}

/** The line under a rule in the list. */
@Composable
internal fun ruleSummary(r: Rule, options: RuleOptions, myUserId: Int?, nameOf: (Int) -> String?): String {
    val parts = mutableListOf<String>()
    if (!r.active) parts += stringResource(R.string.rules_paused)
    parts += triggerText(r.trigger, options, nameOf)
    parts += conditionTexts(r, options)
    parts += if (r.actions.size == 1) actionText(r.actions[0], options, myUserId, nameOf) else pluralStringResource(R.plurals.rules_actions_n, r.actions.size, r.actions.size)
    return parts.joinToString(" · ")
}
