package com.gatecontrol.android.drive

// ─── Drive 1.17: deadlines into a calendar of the mail plugin too (feature `deadlineMailCalendar`) ──

/**
 * Where a deadline goes into a calendar: [KIND_FAMILY] (the family calendar) or [KIND_MAIL] (a calendar [calendarId] of
 * the mail plugin, e.g. Outlook). No target (off) is `null`. [label] is the server's text of it ("Outlook · Kalender").
 */
data class DDeadlineTarget(val kind: String, val calendarId: String? = null, val label: String? = null) {
    val family: Boolean get() = kind == KIND_FAMILY
    val mail: Boolean get() = kind == KIND_MAIL

    /** The same calendar (the label does not count). */
    fun sameAs(other: DDeadlineTarget?): Boolean = other != null && kind == other.kind && (family || calendarId == other.calendarId)

    companion object {
        const val KIND_FAMILY = "family"
        const val KIND_MAIL = "mail"
        val FAMILY = DDeadlineTarget(KIND_FAMILY)
    }
}

/** An account of the mail plugin ([kind] e.g. `microsoft`, `caldav`, `local`). */
data class DMailAccount(val id: String, val label: String, val kind: String?)

/** A calendar of the mail plugin the person may write to (`GET deadlines/calendars`); [color] `#rrggbb`. */
data class DMailCalendar(val id: String, val name: String, val account: DMailAccount, val color: String?)

/** `GET deadlines/calendars`: whether the family calendar takes entries, and the person's mail calendars. */
data class DDeadlineCalendars(val family: Boolean, val mail: List<DMailCalendar>) {
    companion object {
        val NONE = DDeadlineCalendars(family = false, mail = emptyList())
    }
}

/** `GET deadlines/targets`: my target for the deadlines of area [areaId] ([label] its name); null: off. */
data class DDeadlineTargetRow(val areaId: String, val label: String, val target: DDeadlineTarget?)

/** What the settings and the deadlines offer: the choices, grouped like the dialog shows them, and the texts of a target. */
object DriveDeadlineTargets {

    /** One line of the choice: off, the family calendar or a mail calendar. */
    data class Choice(val target: DDeadlineTarget?, val calendar: DMailCalendar? = null)

    /** A block of the choice: mail calendars under their [account] (null: off and the family calendar, no heading). */
    data class Group(val account: DMailAccount?, val choices: List<Choice>)

    /**
     * The choice of an area's target: "Nicht eintragen" ([off]), the family calendar (when Familie takes entries), then
     * the mail calendars by account (only with [mailFeature]), in the server's order.
     */
    fun groups(calendars: DDeadlineCalendars, mailFeature: Boolean, off: Boolean = true): List<Group> {
        val first = buildList {
            if (off) add(Choice(null))
            if (calendars.family) add(Choice(DDeadlineTarget.FAMILY))
        }
        val mail = if (!mailFeature) emptyList() else calendars.mail.groupBy { it.account.id }.values.map { list ->
            Group(list.first().account, list.map { c -> Choice(mailTarget(c), c) })
        }
        return (if (first.isEmpty()) emptyList() else listOf(Group(null, first))) + mail
    }

    /** The targets a single deadline can go to (no "off"). */
    fun targets(calendars: DDeadlineCalendars, mailFeature: Boolean): List<DDeadlineTarget> =
        groups(calendars, mailFeature, off = false).flatMap { g -> g.choices.mapNotNull { it.target } }

    /** "In Kalender eintragen" asks for the target only when more than one is possible. */
    fun ask(targets: List<DDeadlineTarget>): Boolean = targets.size > 1

    /**
     * The target picked first for a deadline of area [areaId]: my target of that area when it is still possible, else
     * the family calendar, else the first one.
     */
    fun preselect(rows: List<DDeadlineTargetRow>, areaId: String?, targets: List<DDeadlineTarget>): DDeadlineTarget? {
        val mine = rows.firstOrNull { it.areaId == areaId }?.target
        return targets.firstOrNull { it.sameAs(mine) } ?: targets.firstOrNull { it.family } ?: targets.firstOrNull()
    }

    /** A mail calendar as a target, labelled "Outlook · Kalender". */
    fun mailTarget(c: DMailCalendar) = DDeadlineTarget(DDeadlineTarget.KIND_MAIL, c.id, mailLabel(c))

    fun mailLabel(c: DMailCalendar): String = listOf(c.account.label, c.name).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    /**
     * The text of a mail target ("Outlook · Kalender"): the calendar as it is now when it is still there, else what
     * the server keeps; null for off and the family calendar (the app's own words).
     */
    fun mailText(target: DDeadlineTarget?, calendars: DDeadlineCalendars): String? {
        if (target?.mail != true) return null
        return calendars.mail.firstOrNull { it.id == target.calendarId }?.let(::mailLabel) ?: target.label?.takeIf { it.isNotBlank() }
    }

    /**
     * "Im Kalender · Outlook": the account a deadline's entry went to; empty for a mail calendar without a label, null for
     * the family calendar.
     */
    fun entryText(entry: DCalendarEntry?): String? {
        val t = entry?.target ?: return null
        if (!t.mail) return null
        return t.label?.substringBefore(" · ")?.trim()?.takeIf { it.isNotEmpty() } ?: ""
    }

    /** My target of [areaId] now set to [target] (the rows of the settings). */
    fun put(rows: List<DDeadlineTargetRow>, areaId: String, target: DDeadlineTarget?): List<DDeadlineTargetRow> =
        rows.map { if (it.areaId == areaId) it.copy(target = target) else it }
}
