package com.gatecontrol.android.drive

import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The pure rules of places and people in the photos (Drive 1.16): when the map loads its bundles again, how a place's
 * time and days read, the suggestions while typing and the marks of a search, who is on the photos shown, the name
 * suggestions from family and contacts, and the order of the people. Nothing here talks to the network or Android.
 */
object DrivePlacesLogic {

    // ─── the map ────────────────────────────────────────────────────

    /** What the map shows: its box and its (whole) zoom. */
    data class View(val bbox: DBbox, val zoom: Int)

    /** What was loaded: the box asked for (larger than the view) at a zoom. */
    data class Loaded(val bbox: DBbox, val zoom: Int)

    /** Web Mercator stops here; osmdroid gives more at the poles. */
    const val MAX_LAT = 85.0

    /** A box valid for Drive: latitudes within ±85, longitudes within ±180 (a box over the date line: the whole width). */
    fun clamp(b: DBbox): DBbox {
        val south = b.south.coerceIn(-MAX_LAT, MAX_LAT)
        val north = b.north.coerceIn(-MAX_LAT, MAX_LAT)
        val wraps = b.west > b.east || b.east - b.west >= 360.0
        val west = if (wraps) -180.0 else b.west.coerceIn(-180.0, 180.0)
        val east = if (wraps) 180.0 else b.east.coerceIn(-180.0, 180.0)
        return DBbox(west, min(south, north), east, max(south, north))
    }

    /** [b] grown by [factor] of its size on every side (then clamped): the bundles just outside the view come along. */
    fun padded(b: DBbox, factor: Double = 0.5): DBbox {
        val c = clamp(b)
        val dx = (c.east - c.west) * factor
        val dy = (c.north - c.south) * factor
        return clamp(DBbox(c.west - dx, c.south - dy, c.east + dx, c.north + dy))
    }

    fun contains(outer: DBbox, inner: DBbox): Boolean =
        inner.west >= outer.west && inner.east <= outer.east && inner.south >= outer.south && inner.north <= outer.north

    /**
     * Whether the bundles have to come again for [view]: nothing loaded yet, another zoom, or the view left what was
     * loaded. Moving within the padded box loads nothing (the screen throttles the rest by time).
     */
    fun needsLoad(loaded: Loaded?, view: View): Boolean {
        if (loaded == null) return true
        if (loaded.zoom != view.zoom) return true
        return !contains(loaded.bbox, clamp(view.bbox))
    }

    /** The box around [points] (lat, lon), a little larger; null for none. */
    fun around(points: List<Pair<Double, Double>>, margin: Double = 0.15): DBbox? {
        if (points.isEmpty()) return null
        val s = points.minOf { it.first }
        val n = points.maxOf { it.first }
        val w = points.minOf { it.second }
        val e = points.maxOf { it.second }
        val dy = max((n - s) * margin, 0.01)
        val dx = max((e - w) * margin, 0.01)
        return clamp(DBbox(w - dx, s - dy, e + dx, n + dy))
    }

    // ─── time and days ──────────────────────────────────────────────

    /** The local day of a photo (taken, else changed); null without a date. */
    fun dayOf(node: DNode, zone: ZoneId): LocalDate? = (node.takenAt ?: node.mtime)?.let { iso ->
        runCatching { Instant.parse(iso).atZone(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
    }

    /** How many different days the photos are of. */
    fun days(items: List<DNode>, zone: ZoneId): Int = items.mapNotNull { dayOf(it, zone) }.distinct().size

    /** A day of a place's photos: its photos and the district most of them are in. */
    data class Day(val date: LocalDate?, val items: List<DNode>, val district: String?)

    /** The photos by day (newest first, as they come), each with its most frequent district. */
    fun byDay(items: List<DNode>, zone: ZoneId): List<Day> {
        val out = ArrayList<Day>()
        var current: LocalDate? = null
        var list = ArrayList<DNode>()
        var started = false
        fun close() {
            if (!started) return
            out += Day(current, list, district(list))
        }
        for (n in items) {
            val d = dayOf(n, zone)
            if (!started || d != current) {
                close()
                current = d
                list = ArrayList()
                started = true
            }
            list += n
        }
        close()
        return out
    }

    /** The district most of [items] are in; null when none says. */
    fun district(items: List<DNode>): String? =
        items.mapNotNull { it.geo?.place?.district }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** "Juli 2025", "März – Juli 2025", "2014 – 2026" of two ISO times; null without them. */
    fun span(from: String?, to: String?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String? {
        fun ym(s: String?): YearMonth? = s?.let { iso ->
            runCatching { YearMonth.from(Instant.parse(iso).atZone(zone)) }.getOrNull() ?: runCatching { YearMonth.parse(iso.take(7)) }.getOrNull()
        }
        val a = ym(from) ?: ym(to) ?: return null
        val b = ym(to) ?: a
        val first = minOf(a, b)
        val last = maxOf(a, b)
        val month = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        return when {
            first == last -> first.format(month)
            first.year == last.year -> first.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " – " + last.format(month)
            else -> "${first.year} – ${last.year}"
        }
    }

    /** "2016 – 2026", "2026"; null without years. */
    fun years(from: Int?, to: Int?): String? = when {
        from == null && to == null -> null
        from == null || to == null || from == to -> (from ?: to).toString()
        else -> "${min(from, to)} – ${max(from, to)}"
    }

    // ─── people on the photos ───────────────────────────────────────

    /** Who is on [items] how often (`with=people`), most first. */
    fun peopleCounts(items: List<DNode>): List<Pair<Long, Int>> =
        items.flatMap { it.people.orEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

    /** [id] added to or taken from the people filter. */
    fun toggle(list: List<Long>, id: Long): List<Long> = if (id in list) list - id else list + id

    /** The people as the "Personen" page orders them: named (most photos first), without a name, hidden. */
    data class Groups(val named: List<DPerson>, val unnamed: List<DPerson>, val hidden: List<DPerson>)

    fun groups(people: List<DPerson>): Groups = Groups(
        named = people.filter { !it.hidden && it.named }.sortedWith(compareByDescending<DPerson> { it.count }.thenBy { it.name?.lowercase() }),
        unnamed = people.filter { !it.hidden && !it.named }.sortedByDescending { it.count },
        hidden = people.filter { it.hidden },
    )

    /** The bar on the Photos page: the named people, then "Wer ist das?" when someone is still without a name. */
    fun bar(people: List<DPerson>, max: Int = 12): Pair<List<DPerson>, DPerson?> {
        val g = groups(people)
        return g.named.take(max) to g.unnamed.firstOrNull()
    }

    /** One or two initials of a name ("Oma Gerda" → "OG"). */
    fun initials(name: String?): String {
        val words = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(2).joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }.ifEmpty { "?" }
    }

    // ─── search ─────────────────────────────────────────────────────

    /** Lower case without accents, for comparing names ("Köln" ~ "koln"). */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()

    /** A suggestion while typing: a person, a place or what is in the photos. */
    data class Suggestion(val kind: Kind, val id: String, val name: String, val sub: String?, val count: Int) {
        enum class Kind { PERSON, PLACE, LABEL }
    }

    /** Drive's suggestions in the order of the list: people, places, what is in the photos. */
    fun suggestions(s: DSuggest?): List<Suggestion> {
        if (s == null) return emptyList()
        return s.people.mapNotNull { p -> p.name?.let { Suggestion(Suggestion.Kind.PERSON, p.id.toString(), it, null, p.count) } } +
            s.places.map { Suggestion(Suggestion.Kind.PLACE, it.key, it.name, it.sub, it.count) } +
            s.labels.map { Suggestion(Suggestion.Kind.LABEL, it.label, it.text, null, it.count) }
    }

    /** The search with [word] instead of the last word typed (a picked suggestion completes it). */
    fun complete(q: String, word: String): String {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val keep = if (q.endsWith(" ")) words else words.dropLast(1)
        return (keep + word.trim()).joinToString(" ").take(DriveJson.PHOTO_Q_MAX)
    }

    /** A mark of a search: a person or place Drive took words as (shown as a chip). */
    data class Mark(val person: Long?, val place: String?, val name: String)

    fun marks(m: DSearchMarks): List<Mark> =
        m.people.map { Mark(it.first, null, it.second) } + m.places.map { Mark(null, it.first, it.second) }

    /** The search without the words of a mark (its chip's ✕). */
    fun withoutMark(q: String, mark: Mark): String {
        val drop = fold(mark.name).split(Regex("\\s+")).toSet()
        return q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && fold(it) !in drop }.joinToString(" ")
    }

    // ─── naming ─────────────────────────────────────────────────────

    /** A name to offer while naming someone: a family member or a contact, and who already has it (a named person). */
    data class NameOption(val kind: String, val id: String, val name: String, val sub: String?, val namedCount: Int? = null)

    /**
     * Up to [max] names for [typed]: family first, then contacts; each name once, matched at a word's start without
     * accents (all of them while nothing is typed). [named] are the names people already have (lower case → photos).
     */
    fun nameOptions(typed: String, family: List<NameOption>, contacts: List<NameOption>, named: Map<String, Int>, max: Int = 3): List<NameOption> {
        val q = fold(typed)
        fun hits(name: String): Boolean {
            if (q.isEmpty()) return true
            val f = fold(name)
            return f.startsWith(q) || f.split(Regex("\\s+")).any { it.startsWith(q) }
        }
        val seen = HashSet<String>()
        return (family + contacts).filter { it.name.isNotBlank() && hits(it.name) && seen.add(fold(it.name)) }
            .take(max)
            .map { o -> o.copy(namedCount = named[fold(o.name)]) }
    }
}
