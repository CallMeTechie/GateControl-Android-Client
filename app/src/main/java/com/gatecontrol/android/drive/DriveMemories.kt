package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveJson.arr
import com.gatecontrol.android.drive.DriveJson.int
import com.gatecontrol.android.drive.DriveJson.long
import com.gatecontrol.android.drive.DriveJson.obj
import com.gatecontrol.android.drive.DriveJson.str
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * One collection of the page „Erinnerungen“ (Drive 1.26, `GET memories`): [id] for `GET memories/photos`, [kind] one of
 * [DriveMemoriesLogic.KINDS], how many photos, the [cover]; by kind: [yearsAgo] and [date] („Heute vor X Jahren“),
 * [month] („Diesen Monat vor X Jahren“), [year] („Jahresrückblick“), [placeName] / [places] / [days] / [firstDay]
 * (a trip, a place), [personId] / [personName] („Mit Noah“).
 */
data class DMemoryCollection(
    val id: String,
    val kind: String,
    val count: Int,
    val cover: DNode?,
    val yearsAgo: Int? = null,
    val date: String? = null,
    val month: String? = null,
    val year: Int? = null,
    val placeName: String? = null,
    val places: List<String> = emptyList(),
    val days: Int? = null,
    val firstDay: String? = null,
    val lastDay: String? = null,
    val personId: Long? = null,
    val personName: String? = null,
)

/** `GET memories`: the day asked for, home (the place's name, null: none known) and the collections in their order. */
data class DMemoryList(val date: String?, val home: String?, val collections: List<DMemoryCollection>)

/** `GET memories` and `GET memories/photos` (Drive 1.26, feature `memoryCollections`). */
object DriveMemoriesJson {
    private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MONTH = Regex("""^\d{4}-\d{2}$""")
    private const val ID_MAX = 100
    private const val NAME_MAX = 200

    fun list(o: JsonObject): DMemoryList? {
        val arr = o.arr("collections") ?: return null
        return DMemoryList(
            date = o.str("date")?.takeIf { DAY.matches(it) },
            home = o.obj("home")?.str("name")?.trim()?.take(NAME_MAX)?.ifEmpty { null },
            collections = arr.mapNotNull { collection(it as? JsonObject) },
        )
    }

    fun collection(o: JsonObject?): DMemoryCollection? {
        if (o == null) return null
        val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= ID_MAX } ?: return null
        val kind = o.str("kind")?.takeIf { it in DriveMemoriesLogic.KINDS } ?: return null
        val count = o.int("count")?.takeIf { it > 0 } ?: return null
        val cover = DriveJson.node(o.obj("cover"))?.takeIf { !it.encrypted }
        val place = o.obj("place")
        val person = o.obj("person")
        return DMemoryCollection(
            id = id,
            kind = kind,
            count = count,
            cover = cover,
            yearsAgo = o.int("yearsAgo")?.takeIf { it > 0 },
            date = o.str("date")?.takeIf { DAY.matches(it) },
            month = o.str("month")?.takeIf { MONTH.matches(it) },
            year = o.int("year")?.takeIf { it in 1..9999 },
            placeName = place?.str("name")?.trim()?.take(NAME_MAX)?.ifEmpty { null },
            places = o.arr("places")?.mapNotNull { e -> runCatching { e.asString }.getOrNull()?.trim()?.take(NAME_MAX)?.ifEmpty { null } }?.take(3).orEmpty(),
            days = o.int("days")?.takeIf { it > 0 },
            firstDay = o.str("firstDay")?.takeIf { DAY.matches(it) },
            lastDay = o.str("lastDay")?.takeIf { DAY.matches(it) },
            personId = person?.long("id")?.takeIf { it > 0 },
            personName = person?.str("name")?.trim()?.take(NAME_MAX)?.ifEmpty { null },
        )
    }
}

/** The pure part of „Erinnerungen“ (unit-tested): the sections, the lines of a card, what older servers send. */
object DriveMemoriesLogic {
    const val DAY = "day"
    const val TRIP = "trip"
    const val PERSON = "person"
    const val MONTH = "month"
    const val YEAR = "year"
    const val PLACE = "place"

    /** The kinds in the order of the page. */
    val KINDS = listOf(DAY, TRIP, PERSON, MONTH, YEAR, PLACE)

    /** „Heute vor X Jahren“ only with more than this many photos that day (± 1 day) — as the server (Drive 1.26). */
    const val DAY_MORE_THAN = 10

    /** A section of the page: its kind and its collections (in the server's order). */
    data class Section(val kind: String, val items: List<DMemoryCollection>)

    /** The collection [id] of the list (a pick of the overview after the app was restarted); null: not there. */
    fun find(list: DMemoryList?, id: String?): DMemoryCollection? = id?.let { i -> list?.collections?.firstOrNull { it.id == i } }

    fun sections(list: List<DMemoryCollection>): List<Section> =
        KINDS.mapNotNull { k -> list.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { Section(k, it) } }

    /** What a card's title says. */
    enum class Title { YEARS_AGO, PLACES, PERSON, MONTH_YEARS_AGO, YEAR, PLACE }

    /**
     * A card's words: [title] with [number] („vor 3 Jahren“, the year) or [name] (the person, the places), [when] (a
     * date or a month, null: none) and [days] (a trip's days, null: none). The photo count goes with them on the card.
     */
    data class Text(val title: Title, val number: Int?, val name: String?, val `when`: String?, val days: Int?)

    fun text(c: DMemoryCollection, locale: Locale): Text = when (c.kind) {
        DAY -> Text(Title.YEARS_AGO, c.yearsAgo ?: 1, null, c.date?.let { fullDate(it, locale) }, null)
        TRIP -> Text(Title.PLACES, null, (c.places.ifEmpty { listOfNotNull(c.placeName) }).joinToString(", ").ifEmpty { null }, c.firstDay?.let { monthYear(it.take(7), locale) }, c.days)
        PERSON -> Text(Title.PERSON, null, c.personName, null, null)
        MONTH -> Text(Title.MONTH_YEARS_AGO, c.yearsAgo ?: 1, null, c.month?.let { monthYear(it, locale) }, null)
        YEAR -> Text(Title.YEAR, c.year, null, null, null)
        else -> Text(Title.PLACE, null, c.placeName, null, null)
    }

    /** "2025-10" → "Oktober 2025" (null: not a month). */
    fun monthYear(month: String, locale: Locale): String? = runCatching {
        val ym = YearMonth.parse(month)
        "${ym.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${ym.year}"
    }.getOrNull()

    /** "2025-10-26" → "26. Oktober 2025" / "October 26, 2025" (null: not a day). */
    fun fullDate(day: String, locale: Locale): String? = runCatching {
        val d = LocalDate.parse(day)
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale).format(d)
    }.getOrNull()

    /** A photo item that is private: not below the work folder, not marked „Prüfen“, not in a team folder. */
    fun isPrivate(n: DNode): Boolean = !n.work && n.review == null && !n.areaId.startsWith("team:")

    /**
     * The overview's cards of an older Drive (without `overview`; `photos/memories` may still carry work photos): only
     * the private photos of each year, and only a year with more than [DAY_MORE_THAN] of them.
     */
    fun privateDays(groups: List<DMemories>): List<DMemories> =
        groups.map { it.copy(items = it.items.filter(::isPrivate)) }.filter { it.items.size > DAY_MORE_THAN }
}

/**
 * A collection the overview opens on the page „Erinnerungen“ (Drive 1.28, its picks): handed over as it is, so the
 * page shows it at once with its title — the route carries only its id. Taken once, by that id.
 */
object DriveMemoryHandoff {
    @Volatile
    private var pending: DMemoryCollection? = null

    fun put(c: DMemoryCollection) {
        pending = c
    }

    /** The collection handed over for [id] (null: none, or another one); it is gone afterwards. */
    @Synchronized
    fun take(id: String?): DMemoryCollection? {
        val c = pending ?: return null
        if (id == null || c.id != id) return null
        pending = null
        return c
    }
}
