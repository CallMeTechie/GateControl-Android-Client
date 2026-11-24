package com.gatecontrol.android.tv.zuhause

import com.gatecontrol.android.familie.Agenda
import com.gatecontrol.android.familie.AgendaEntry
import com.gatecontrol.android.familie.AlltagPayload
import com.gatecontrol.android.familie.AlltagWaste
import com.gatecontrol.android.familie.FamilieEvent
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.familie.shopping.ShoppingList
import com.gatecontrol.android.familie.shopping.ShoppingLogic
import com.gatecontrol.android.familie.shopping.ShoppingSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * One bin of "Müllabfuhr": its [kind] ("Gelbe Tonne"), the next collection, how often it comes (days between the
 * first two collections the Alltag plugin knows, null with only one) and [soon]: from the evening before on.
 */
data class TvWaste(val kind: String, val date: LocalDate, val color: String, val everyDays: Int?, val soon: Boolean)

/** One day of the calendar column: the Familie dates, Alltag appointments and waste collections of [date]. */
data class TvCalendarDay(val date: LocalDate, val entries: List<AgendaEntry>)

/** What "Heute zu Hause" on the start page shows: the next bin, the next date, the open entries of the list. */
data class TvHomeToday(val waste: TvWaste?, val next: AgendaEntry?, val list: ShoppingList?, val open: Int)

/**
 * The rules of the page "Zuhause" (mockup "3 · Zuhause"), free of Android: the waste collection by bin, the next
 * seven days of Familie and Alltag (no personal mail calendars, decision 14), the shopping list the TV shows.
 */
object TvZuhauseLogic {
    /** The calendar column: today and the six days after it. */
    const val CALENDAR_DAYS = 7

    /** How far the waste collection is asked ahead (a monthly bin still shows; Alltag answers at most 62 days). */
    const val WASTE_DAYS = 35

    /** From this hour on the day before, a collection is highlighted ("morgen früh rausstellen"). */
    const val EVENING_HOUR = 17

    /** The last day the calendar column shows. */
    fun calendarEnd(today: LocalDate): LocalDate = today.plusDays(CALENDAR_DAYS - 1L)

    /** Highlighted: the collection is today, or tomorrow and it is evening. */
    fun soon(date: LocalDate, today: LocalDate, hour: Int): Boolean =
        date == today || (date == today.plusDays(1) && hour >= EVENING_HOUR)

    /**
     * The bins in the order they are collected next: one line per kind with its next collection from [today] on.
     * Kinds are told apart by name, case aside ("Gelbe Tonne" and "gelbe tonne" are one bin).
     */
    fun waste(waste: List<AlltagWaste>, today: LocalDate, hour: Int): List<TvWaste> {
        val byKind = LinkedHashMap<String, MutableList<Pair<LocalDate, AlltagWaste>>>()
        val names = HashMap<String, String>()
        for (w in waste) {
            val date = parse(w.date) ?: continue
            if (date.isBefore(today)) continue
            for (kind in w.kinds) {
                val key = kind.trim().lowercase()
                if (key.isEmpty()) continue
                names.getOrPut(key) { kind.trim() }
                byKind.getOrPut(key) { mutableListOf() } += date to w
            }
        }
        return byKind.map { (key, dates) ->
            val sorted = dates.distinctBy { it.first }.sortedBy { it.first }
            val (next, w) = sorted.first()
            val every = sorted.getOrNull(1)?.let { ChronoUnit.DAYS.between(next, it.first).toInt() }?.takeIf { it > 0 }
            TvWaste(
                kind = names.getValue(key),
                date = next,
                color = color(names.getValue(key), w.color.takeIf { w.kinds.size == 1 }),
                everyDays = every,
                soon = soon(next, today, hour),
            )
        }.sortedWith(compareBy<TvWaste>({ it.date }, { it.kind.lowercase() }))
    }

    /**
     * The colour of a bin's can: the plugin's when the date has only this bin, else by its name as in Germany
     * (yellow packaging, grey residual, blue paper, brown organic, green glass), else grey.
     */
    fun color(kind: String, plugin: String?): String {
        plugin?.takeIf { HEX_RE.matches(it) }?.let { return it }
        val k = kind.lowercase()
        return when {
            "gelb" in k || "verpack" in k || "wertstoff" in k || "yellow" in k -> "#E6C229"
            "papier" in k || "pappe" in k || "blau" in k || "paper" in k -> "#2F6FD0"
            "bio" in k || "kompost" in k || "braun" in k || "organic" in k -> "#7A5230"
            "glas" in k || "glass" in k -> "#3E9B5B"
            "sperr" in k || "bulky" in k -> "#B0703C"
            else -> "#6B6F76"
        }
    }

    /** The calendar column: every day from [today] that has an entry (Agenda's order: all-day first, then by time). */
    fun calendar(own: List<FamilieEvent>, alltag: AlltagPayload?, today: LocalDate): List<TvCalendarDay> {
        val entries = Agenda.merge(own, alltag, today.toString(), calendarEnd(today).toString())
        return entries.groupBy { it.date }.mapNotNull { (d, list) -> parse(d)?.let { TvCalendarDay(it, list) } }.sortedBy { it.date }
    }

    /**
     * The next date of the start page: today's still to come (an all-day one counts all day) or the first later one.
     * Waste collections are not a date here, they have their own card.
     */
    fun nextEntry(days: List<TvCalendarDay>, today: LocalDate, now: LocalTime): AgendaEntry? {
        for (day in days) {
            for (e in day.entries) {
                if (e is AgendaEntry.Waste) continue
                if (day.date.isAfter(today)) return e
                if (day.date == today) {
                    val t = e.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                    if (t == null || !t.isBefore(now.truncatedTo(ChronoUnit.MINUTES))) return e
                }
            }
        }
        return null
    }

    /** The lists to choose from, in the server's order. */
    fun lists(view: ShoppingSnapshot): List<ShoppingList> =
        view.lists.filter { !it.deleted }.sortedWith(compareBy<ShoppingList>({ it.sort }, { it.name.lowercase() }))

    /**
     * The list the TV shows: the one chosen on the TV while it is there, else the one with the most open entries
     * (the first in order on a tie).
     */
    fun chosenList(view: ShoppingSnapshot, wanted: String?): ShoppingList? {
        val lists = lists(view)
        lists.firstOrNull { it.id == wanted }?.let { return it }
        val open = ShoppingLogic.openCounts(view)
        return lists.maxByOrNull { open[it.id] ?: 0 }?.takeIf { (open[it.id] ?: 0) > 0 } ?: lists.firstOrNull()
    }

    /** The entries of [listId] as the TV shows them: open first, then the ticked ones (struck through). */
    fun items(view: ShoppingSnapshot, listId: String?): List<ShoppingItem> =
        if (listId == null) emptyList() else ShoppingLogic.ordered(view, listId).filter { !it.deleted }

    fun open(view: ShoppingSnapshot, listId: String?): Int = if (listId == null) 0 else ShoppingLogic.progress(view, listId).first

    /** A typed or spoken entry as the server takes it: one line, control characters out, at most 120 characters. */
    fun entryText(text: String): String? =
        CONTROL_RE.replace(text, " ").trim().replace(SPACES_RE, " ").take(MAX_TEXT).takeIf { it.isNotEmpty() }

    /** Who ticked an entry, when it is a name (the plugin may send a user id, which the TV cannot show). */
    fun checkedByName(item: ShoppingItem): String? = item.checkedBy?.takeIf { it.isNotBlank() && it.toIntOrNull() == null }

    private fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    private val HEX_RE = Regex("^#[0-9A-Fa-f]{6}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val SPACES_RE = Regex("\\s{2,}")
    private const val MAX_TEXT = 120
}
