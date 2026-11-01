package com.gatecontrol.android.drive

import com.gatecontrol.android.drive.DriveJson.arr
import com.gatecontrol.android.drive.DriveJson.int
import com.gatecontrol.android.drive.DriveJson.long
import com.gatecontrol.android.drive.DriveJson.obj
import com.gatecontrol.android.drive.DriveJson.str
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ─── Drive 1.18: the overview (feature `overview`, `GET overview`) ───────────

/**
 * What the overview of Drive shows (Drive 1.18, mockup "Übersicht"): what is to be done, the files opened last, the
 * memories, people and places of the photos, what others added in shared areas, the storage. A part is null or empty
 * when there is nothing (or its feature is off). [assembled]: built from the older endpoints (a server without
 * `overview`); "Zuletzt geöffnet" and "Neu in Familie" are then missing.
 */
data class DOverview(
    val todo: DTodo? = null,
    val recent: List<DRecent> = emptyList(),
    val memories: List<DMemoryCard> = emptyList(),
    val people: DOverviewPeople? = null,
    val places: DOverviewPlaces? = null,
    val activity: List<DActivity> = emptyList(),
    val storage: DOverviewStorage? = null,
    val assembled: Boolean = false,
)

/**
 * "Zu erledigen": the next deadlines (at most three of [deadlinesTotal]), the photos to review, the mail attachments
 * "Zum Ablegen" ([toFile] null: no mail service) with the folder suggested for the first.
 */
data class DTodo(val deadlines: List<DDeadline>, val deadlinesTotal: Int, val review: Int, val toFile: Int?, val toFileHint: String?)

/** A file of "Zuletzt geöffnet": when the person opened it ([openedAt]) or, without that trace, changed it. */
data class DRecent(val node: DNode, val openedAt: String?, val changedAt: String?, val areaLabel: String?)

/** A memory group (this day [yearsAgo] years ago): its [cover] and how many photos; [items] when they came along. */
data class DMemoryCard(val yearsAgo: Int, val date: String?, val cover: DNode, val count: Int, val items: List<DNode> = emptyList())

/** The named people (at most eight) and how many are still without a name; [firstUnnamed] when one came along. */
data class DOverviewPeople(val people: List<DPerson>, val unnamed: Int, val firstUnnamed: Long? = null)

/** A place of the top three (coordinates when the server sends them, the fallback always has them). */
data class DPlaceTop(val key: String, val name: String, val count: Int, val lat: Double? = null, val lon: Double? = null)

/** "Orte": the top places, one bundle for the small map, how many photos have a place. */
data class DOverviewPlaces(val top: List<DPlaceTop>, val cluster: DCluster?, val total: Int)

/**
 * "Neu in Familie": what another person ([userName]) added or changed in a shared area, grouped (a person, a folder,
 * about an hour). [kind] `added_photos` | `added_files` | `changed`.
 */
data class DActivity(
    val at: String?,
    val userId: Int?,
    val userName: String?,
    val kind: String,
    val count: Int,
    val areaId: String?,
    val areaLabel: String?,
    val folderId: Long?,
    val folderPath: String?,
    val sample: DNode?,
)

/** How much storage the person (or the area) takes, of how much, and where it lies. */
data class DOverviewStorage(val used: Long, val total: Long?, val target: String?)

/** Pure parser of `GET overview` (bounded like every Drive parser; a part of the wrong shape is left out). */
internal object DriveOverviewJson {

    private const val DEADLINES_MAX = 3
    private const val RECENT_MAX = 10
    private const val MEMORIES_MAX = 4
    private const val PEOPLE_MAX = 8
    private const val PLACES_MAX = 3
    private const val ACTIVITY_MAX = 5
    private val KINDS = setOf(DriveOverviewLogic.ADDED_PHOTOS, DriveOverviewLogic.ADDED_FILES, DriveOverviewLogic.CHANGED)

    /** `GET overview` → the overview (at the top or as `overview`); null when it is not an object. */
    fun overview(o: JsonObject): DOverview? {
        val src = o.obj("overview") ?: o
        return DOverview(
            todo = todo(src.obj("todo")),
            recent = src.arr("recent")?.mapNotNull { recent(it as? JsonObject) }?.take(RECENT_MAX).orEmpty(),
            memories = src.arr("memories")?.mapNotNull { memory(it as? JsonObject) }?.take(MEMORIES_MAX).orEmpty(),
            people = people(src),
            places = places(src.obj("places")),
            activity = src.arr("activity")?.mapNotNull { activity(it as? JsonObject) }?.take(ACTIVITY_MAX).orEmpty(),
            storage = storage(src.obj("storage")),
        )
    }

    private fun todo(o: JsonObject?): DTodo? {
        if (o == null) return null
        val deadlines = o.arr("deadlines")?.mapNotNull { DriveJson.deadline(it as? JsonObject) }.orEmpty()
            .filter { it.node?.encrypted != true }.take(DEADLINES_MAX)
        return DTodo(
            deadlines = deadlines,
            deadlinesTotal = (o.int("deadlinesTotal") ?: deadlines.size).coerceAtLeast(deadlines.size),
            review = o.int("review")?.coerceAtLeast(0) ?: 0,
            toFile = o.int("toFile")?.coerceAtLeast(0),
            toFileHint = o.str("toFileHint")?.trim()?.take(200)?.ifEmpty { null },
        )
    }

    private fun recent(o: JsonObject?): DRecent? {
        val node = DriveJson.node(o) ?: return null
        if (node.isDir || node.encrypted || DriveOverviewLogic.isMedia(node)) return null
        return DRecent(node, o!!.str("openedAt")?.take(40), o.str("changedAt")?.take(40), o.str("areaLabel")?.trim()?.take(100)?.ifEmpty { null })
    }

    private fun memory(o: JsonObject?): DMemoryCard? {
        if (o == null) return null
        val items = o.arr("items")?.mapNotNull { DriveJson.node(it as? JsonObject) }.orEmpty().filter { !it.encrypted }
        val cover = DriveJson.node(o.obj("cover"))?.takeIf { !it.encrypted } ?: items.firstOrNull() ?: return null
        return DMemoryCard(
            yearsAgo = o.int("yearsAgo")?.coerceAtLeast(0) ?: 0,
            date = o.str("date")?.take(40),
            cover = cover,
            count = (o.int("count") ?: items.size).coerceAtLeast(1),
            items = items.take(30),
        )
    }

    /** `people: [...]` with `unnamed` beside it, or `people: { people|items: [...], unnamed }`; null when missing. */
    private fun people(src: JsonObject): DOverviewPeople? {
        val e = src.get("people") ?: return null
        val (list, unnamedCount) = when {
            e.isJsonArray -> e.asJsonArray to (src.int("unnamed") ?: src.int("peopleUnnamed"))
            e.isJsonObject -> e.asJsonObject.let { p -> (p.arr("people") ?: p.arr("items") ?: p.arr("named")) to p.int("unnamed") }
            else -> return null
        }
        val all = list?.mapNotNull { DrivePlacesJson.person(it as? JsonObject) }.orEmpty()
        val named = all.filter { it.named && !it.hidden }.take(PEOPLE_MAX)
        val firstUnnamed = all.firstOrNull { !it.named && !it.hidden }?.id
        val unnamed = (unnamedCount ?: all.count { !it.named && !it.hidden }).coerceAtLeast(0)
        return DOverviewPeople(named, unnamed, firstUnnamed)
    }

    private fun places(o: JsonObject?): DOverviewPlaces? {
        if (o == null) return null
        val top = o.arr("top")?.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            DPlaceTop(
                key = DrivePlacesJson.placeKey(p.str("key")) ?: return@mapNotNull null,
                name = p.str("name")?.trim()?.take(DrivePlacesJson.NAME_MAX)?.ifEmpty { null } ?: return@mapNotNull null,
                count = p.int("count")?.coerceAtLeast(0) ?: 0,
                lat = DrivePlacesJson.lat(p.num("lat")),
                lon = DrivePlacesJson.lon(p.num("lon")),
            )
        }?.take(PLACES_MAX).orEmpty()
        val cluster = o.obj("cluster")?.let { c ->
            val lat = DrivePlacesJson.lat(c.num("lat")) ?: return@let null
            val lon = DrivePlacesJson.lon(c.num("lon")) ?: return@let null
            DCluster(lat, lon, c.int("count")?.coerceAtLeast(0) ?: 0, null, null)
        }
        val total = (o.int("total") ?: top.sumOf { it.count }).coerceAtLeast(0)
        return DOverviewPlaces(top, cluster, total)
    }

    private fun activity(o: JsonObject?): DActivity? {
        if (o == null) return null
        val user = o.obj("user")
        val area = o.obj("area")
        val folder = o.obj("folder")
        return DActivity(
            at = o.str("at")?.take(40),
            userId = user?.int("id"),
            userName = user?.str("name")?.trim()?.take(100)?.ifEmpty { null },
            kind = o.str("kind")?.takeIf { it in KINDS } ?: return null,
            count = o.int("count")?.coerceAtLeast(1) ?: 1,
            areaId = DriveJson.areaId(area?.str("id")),
            areaLabel = area?.str("label")?.trim()?.take(100)?.ifEmpty { null },
            folderId = folder?.long("id"),
            folderPath = folder?.str("path")?.trim()?.take(500)?.ifEmpty { null },
            sample = DriveJson.node(o.obj("sample"))?.takeIf { !it.encrypted },
        )
    }

    private fun storage(o: JsonObject?): DOverviewStorage? {
        if (o == null) return null
        return DOverviewStorage(
            used = o.long("used")?.coerceAtLeast(0) ?: return null,
            total = o.long("total")?.takeIf { it > 0 },
            target = o.str("target")?.trim()?.take(100)?.ifEmpty { null },
        )
    }

    private fun JsonObject.num(key: String): Double? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }
}

/** Pure part of the overview (unit-tested): which sections show, the fallback, the labels. */
internal object DriveOverviewLogic {

    const val ADDED_PHOTOS = "added_photos"
    const val ADDED_FILES = "added_files"
    const val CHANGED = "changed"

    /** The sections in the mockup's order. */
    enum class Section { SEARCH, STATUS, TODO, RECENT, MEMORIES, PEOPLE, PLACES, ACTIVITY }

    /** The line "Fotos gesichert · vor N Min." (only with the photo backup on). */
    sealed class Backup {
        data class Running(val done: Int, val total: Int) : Backup()
        data class Saved(val at: Long) : Backup()
        data object Waiting : Backup()
        /** Stopped by [code] (no permission, Drive full …): the backup's page tells more. */
        data class Stopped(val code: String) : Backup()
    }

    /** The photo backup's state as a line; null while it is off. */
    fun backup(enabled: Boolean, running: Boolean, done: Int, total: Int, lastRunAt: Long, error: String?): Backup? = when {
        !enabled -> null
        running -> Backup.Running(done, total)
        error != null -> Backup.Stopped(error)
        lastRunAt > 0 -> Backup.Saved(lastRunAt)
        else -> Backup.Waiting
    }

    fun hasTodo(t: DTodo?): Boolean = t != null && (t.deadlines.isNotEmpty() || t.review > 0 || (t.toFile ?: 0) > 0)

    /** The number beside "Zu erledigen": the deadlines, then one each for the review and "Zum Ablegen". */
    fun todoCount(t: DTodo?): Int {
        if (t == null) return 0
        return t.deadlinesTotal + (if (t.review > 0) 1 else 0) + (if ((t.toFile ?: 0) > 0) 1 else 0)
    }

    fun hasPeople(p: DOverviewPeople?): Boolean = p != null && (p.people.isNotEmpty() || p.unnamed > 0)

    fun hasPlaces(p: DOverviewPlaces?): Boolean = p != null && p.top.isNotEmpty()

    /** The sections shown, in order; an empty one is left out (the search always shows). */
    fun sections(o: DOverview?, backup: Backup?): List<Section> = buildList {
        add(Section.SEARCH)
        if (o == null) return@buildList
        if (backup != null || o.storage != null) add(Section.STATUS)
        if (hasTodo(o.todo)) add(Section.TODO)
        if (o.recent.isNotEmpty()) add(Section.RECENT)
        if (o.memories.isNotEmpty()) add(Section.MEMORIES)
        if (hasPeople(o.people)) add(Section.PEOPLE)
        if (hasPlaces(o.places)) add(Section.PLACES)
        if (o.activity.isNotEmpty()) add(Section.ACTIVITY)
    }

    /** "Heute vor 2 Jahren" ([today]: the cover is of exactly this calendar day) or "Vor 2 Jahren" (a day beside it). */
    data class MemoryLabel(val years: Int, val today: Boolean)

    fun memoryLabel(card: DMemoryCard, today: LocalDate, zone: ZoneId): MemoryLabel {
        val day = DrivePlacesLogic.dayOf(card.cover, zone)
            ?: card.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val same = day != null && day.monthValue == today.monthValue && day.dayOfMonth == today.dayOfMonth
        val years = card.yearsAgo.takeIf { it > 0 } ?: day?.let { today.year - it.year }?.takeIf { it > 0 } ?: 0
        return MemoryLabel(years, same)
    }

    /** Photos and videos are the Photos page's, not "Zuletzt geöffnet". */
    fun isMedia(node: DNode): Boolean = DriveLogic.kind(node).let { it == DriveLogic.Kind.IMAGE || it == DriveLogic.Kind.VIDEO }

    /**
     * The overview of a server without `overview` (Drive ≤ 1.17), from what it has: the deadlines from today, the
     * memories, the people, the places, the review count, the storage of "Meine Dateien". A missing part stays out.
     */
    fun assemble(
        today: LocalDate,
        deadlines: List<DDeadline>?,
        classes: DPhotoClasses?,
        memories: List<DMemories>?,
        people: DPeople?,
        places: List<DPlaceSummary>?,
        storage: DOverviewStorage?,
    ): DOverview {
        val next = deadlines.orEmpty()
            .filter { it.node?.encrypted != true && (DriveLogic.daysUntil(it.date, today) ?: -1) >= 0 }
            .sortedBy { it.date }
        val review = classes?.review ?: 0
        val todo = if (deadlines != null || classes != null) DTodo(next.take(3), next.size, review, null, null) else null
        val cards = memories.orEmpty().filter { it.items.isNotEmpty() }.take(4)
            .map { DMemoryCard(it.yearsAgo, it.date, it.items.first(), it.items.size, it.items) }
        val overviewPeople = people?.let { p ->
            val g = DrivePlacesLogic.groups(p.people)
            DOverviewPeople(g.named.take(8), g.unnamed.size, g.unnamed.firstOrNull()?.id)
        }
        val overviewPlaces = places?.takeIf { it.isNotEmpty() }?.let { list ->
            val sorted = list.sortedByDescending { it.count }
            val first = sorted.first()
            val total = list.sumOf { it.count }
            DOverviewPlaces(
                top = sorted.take(3).map { DPlaceTop(it.key, it.name, it.count, it.lat, it.lon) },
                cluster = DCluster(first.lat, first.lon, total, null, null),
                total = total,
            )
        }
        return DOverview(
            todo = todo,
            memories = cards,
            people = overviewPeople,
            places = overviewPlaces,
            storage = storage,
            assembled = true,
        )
    }

    /** The storage of "Meine Dateien" from the areas (what the storage page shows for the person). */
    fun storageOf(areas: List<DArea>): DOverviewStorage? {
        val mine = areas.firstOrNull { it.kind == "mine" }?.storage ?: return null
        return DOverviewStorage(mine.bytes ?: return null, mine.quota?.takeIf { it > 0 }, mine.target)
    }

    /** How full, 0…1 (null without a limit). */
    fun fill(s: DOverviewStorage): Float? = s.total?.takeIf { it > 0 }?.let { (s.used.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    /**
     * The box of the small map: around the top places when they have coordinates, else a region around the bundle
     * (a single point would be a street).
     */
    fun mapBox(p: DOverviewPlaces): DBbox? {
        val points = p.top.mapNotNull { t -> t.lat?.let { lat -> t.lon?.let { lon -> lat to lon } } }
        if (points.size >= 2) return DrivePlacesLogic.around(points, margin = 0.3)
        val c = p.cluster ?: points.firstOrNull()?.let { DCluster(it.first, it.second, p.total, null, null) } ?: return null
        return DBbox(
            (c.lon - REGION_LON).coerceAtLeast(-180.0),
            (c.lat - REGION_LAT).coerceAtLeast(-85.0),
            (c.lon + REGION_LON).coerceAtMost(180.0),
            (c.lat + REGION_LAT).coerceAtMost(85.0),
        )
    }

    /** The bubble on the small map: the server's bundle, else all photos at the first place. */
    fun mapCluster(p: DOverviewPlaces): DCluster? = p.cluster?.let { if (it.count <= 0) it.copy(count = p.total) else it }
        ?: p.top.firstOrNull { it.lat != null && it.lon != null }?.let { DCluster(it.lat!!, it.lon!!, p.total, null, null) }

    private const val REGION_LAT = 2.5
    private const val REGION_LON = 4.0

    /** When an event of "Neu in Familie" happened, as epoch ms (null when not a time). */
    fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** The last folder name of a path ("Familie/Urlaub 2026" → "Urlaub 2026"). */
    fun folderName(path: String?): String? = path?.trim('/')?.substringAfterLast('/')?.ifEmpty { null }

    /** Live events reload the overview at most this often. */
    const val LIVE_THROTTLE_MS = 15_000L
}
