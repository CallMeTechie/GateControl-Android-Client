package com.gatecontrol.android.drive

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Pure helpers of the Drive tab (unit-tested). */
internal object DriveLogic {

    /** What a file is, for its icon and its tile in "Zuletzt". */
    enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOC, SHEET, SLIDES, ARCHIVE, OTHER }

    fun kind(node: DNode): Kind = if (node.isDir) Kind.FOLDER else kind(node.mime, node.name)

    fun kind(mime: String?, name: String): Kind {
        val m = mime.orEmpty().lowercase(Locale.ROOT)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            m.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp") -> Kind.IMAGE
            m.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "avi", "3gp") -> Kind.VIDEO
            m.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") -> Kind.AUDIO
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            ext in setOf("doc", "docx", "odt", "rtf", "pages") || m.contains("wordprocessing") || m == "application/msword" -> Kind.DOC
            ext in setOf("xls", "xlsx", "ods", "csv", "numbers") || m.contains("spreadsheet") -> Kind.SHEET
            ext in setOf("ppt", "pptx", "odp", "key") || m.contains("presentation") -> Kind.SLIDES
            ext in setOf("zip", "7z", "rar", "tar", "gz", "tgz") || m.contains("zip") || m.contains("compressed") -> Kind.ARCHIVE
            m.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "log") -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /**
     * Files the server cannot make a preview of (HEIC/HEIF photos, videos): the uploader sends its own picture.
     * JPEG, PNG, WebP, GIF and AVIF the server makes itself (it refuses one sent for them).
     */
    fun needsOwnPreview(mime: String?): Boolean {
        val m = mime.orEmpty().lowercase(Locale.ROOT).substringBefore(';').trim()
        return m == "image/heic" || m == "image/heif" || m.startsWith("video/")
    }

    /** Short label of a tile ("PDF", "FOTO" …): the extension, else the kind. */
    fun badge(node: DNode, photo: String): String {
        val ext = node.name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
        return when (kind(node)) {
            Kind.IMAGE -> photo
            else -> ext.ifEmpty { "…" }
        }
    }

    /** 1,2 MB style, with the locale's decimal separator. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v >= 100 || i == 0) String.format(locale, "%.0f %s", v, units[i]) else String.format(locale, "%.1f %s", v, units[i])
    }

    private val FORBIDDEN = Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]")

    /**
     * A name Drive takes (plugin README: no `/ \ : * ? " < > |`, no trailing dot or space, ≤ 255 characters, not
     * `.gcdrive`): forbidden characters become `_`; null when nothing is left.
     */
    fun safeName(raw: String?): String? {
        val s = raw.orEmpty().replace(FORBIDDEN, "_").trim().trimEnd('.', ' ').take(255).trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == ".." || s.equals(".gcdrive", ignoreCase = true)) return null
        return s
    }

    /** Whether [raw] is a valid new name as typed (before any replacing). */
    fun validName(raw: String): Boolean = raw.isNotBlank() && !FORBIDDEN.containsMatchIn(raw) && safeName(raw) == raw.trim()

    /**
     * "Zuletzt": the newest files that were created or changed, newest first, each once, without the ones that are
     * gone (a later delete or a move out of the areas the viewer sees).
     */
    fun recent(changes: List<DChange>, max: Int = 6): List<DNode> {
        val gone = HashSet<Long>()
        val seen = HashSet<Long>()
        val out = ArrayList<DNode>()
        for (c in changes.sortedByDescending { it.seq }) {
            val id = c.nodeId ?: continue
            if (c.op == "delete" || c.op == "purge") {
                gone.add(id)
                continue
            }
            if (c.op !in setOf("create", "update", "restore", "move")) continue
            val n = c.node ?: continue
            if (n.isDir || id in gone || !seen.add(id)) continue
            out.add(n)
            if (out.size >= max) break
        }
        return out
    }

    /** Order of the areas: my files, the family, the children, the teams (then by label). */
    fun sortAreas(list: List<DArea>): List<DArea> {
        val rank = mapOf("mine" to 0, "family" to 1, "child" to 2, "team" to 3)
        return list.sortedWith(compareBy<DArea>({ rank[it.kind] ?: 9 }, { it.label.lowercase(Locale.ROOT) }))
    }

    /** Usage of an area: "38 GB" or "38 GB von 50 GB". */
    fun usage(storage: DStorage?, of: String, locale: Locale = Locale.getDefault()): String? {
        val bytes = storage?.bytes ?: return null
        val quota = storage.quota?.takeIf { it > 0 }
        return if (quota == null) size(bytes, locale) else "${size(bytes, locale)} $of ${size(quota, locale)}"
    }

    /** Where a picked file goes when the name is taken: a new version when it is the same file kind, else a free name. */
    /**
     * The name a scan is saved under, without extension: what the person typed (a `.pdf`/`.jpg` of their own taken
     * off, forbidden characters replaced), or [fallback] when nothing is left.
     */
    fun scanBase(typed: String, fallback: String): String =
        safeName(typed.trim().replace(SCAN_EXT, ""))?.take(200)?.trimEnd('.', ' ')?.ifEmpty { null } ?: fallback

    /** The files of a scan: one PDF, or one picture per page ("Name.jpg"; several pages: "Name 1.jpg", "Name 2.jpg" …). */
    fun scanFiles(base: String, pdf: Boolean, pages: Int): List<String> = when {
        pdf -> listOf("$base.pdf")
        pages <= 1 -> listOf("$base.jpg")
        else -> (1..pages).map { "$base $it.jpg" }
    }

    /** The text of a whole scan for the server: the pages' texts, blank ones left out; null when there is none. */
    fun scanText(pages: List<String?>): String? = pages.mapNotNull { it?.trim()?.ifEmpty { null } }.joinToString("\n\n").ifEmpty { null }

    // ─── portal links, live updates, ransomware protection ──────────

    /** The portal page of the file or folder [nodeId] (feature `links`); null when the server offers none. */
    fun portalNodeUrl(me: DMe?, nodeId: Long): String? {
        if (me == null || !me.has(DMe.FEATURE_LINKS) || nodeId <= 0) return null
        return me.links?.node?.takeIf { it.contains("{id}") }?.replace("{id}", nodeId.toString())
    }

    /** The portal's Drive page (feature `links`), e.g. to look at an incident there. */
    fun portalDriveUrl(me: DMe?): String? = me?.takeIf { it.has(DMe.FEATURE_LINKS) }?.links?.drive

    /**
     * The incidents the Drive tab shows (feature `guard`): the open ones of `incidents` and the one that stops this
     * device, each once, the newest first.
     */
    fun openIncidents(me: DMe?): List<DIncident> {
        if (me == null || !me.has(DMe.FEATURE_GUARD)) return emptyList()
        val all = listOfNotNull(me.guard?.incident) + me.incidents
        return all.filter { it.open }.distinctBy { it.id }.sortedByDescending { it.at.orEmpty() }
    }

    /** Whether this device's writes are stopped right now (feature `guard`). */
    fun locked(me: DMe?): Boolean = me != null && me.has(DMe.FEATURE_GUARD) && me.guard?.locked == true

    /** Whether a `changed` event concerns a folder of the area [areaId] (no areas named, or unknown area: yes). */
    fun affects(event: DChanged, areaId: String?): Boolean = event.areas.isEmpty() || areaId == null || areaId in event.areas

    /** How long a reload waits so there is at most one every [gapMs] (0: now). */
    fun liveDelay(lastAtMs: Long, nowMs: Long, gapMs: Long = LIVE_GAP_MS): Long =
        if (lastAtMs <= 0L) 0L else (lastAtMs + gapMs - nowMs).coerceIn(0L, gapMs)

    /** At most one live reload of a folder this often. */
    const val LIVE_GAP_MS = 2_000L

    /** What a tap on the path of a folder's page does (see [crumbStep]). */
    enum class CrumbStep { STAY, BACK, TAB, NEW }

    /**
     * A tap on folder [id] in the path of a folder's page: nothing when the page on top shows it ([top]); back to the
     * folder page that shows it ([onStack]); back to the Drive tab when its Files page shows it ([tab]); else a new page.
     */
    fun crumbStep(top: Long?, id: Long, onStack: Boolean, tab: Long?): CrumbStep = when {
        top == id -> CrumbStep.STAY
        onStack -> CrumbStep.BACK
        tab == id -> CrumbStep.TAB
        else -> CrumbStep.NEW
    }

    private val SCAN_EXT = Regex("\\.(pdf|jpe?g)$", RegexOption.IGNORE_CASE)

    // ─── when a photo was taken (Drive 1.7, feature `takenAt`) ──────

    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
    private val VIDEO_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    /**
     * EXIF `DateTimeOriginal` ("2026:10:27 14:05:00") with its `OffsetTimeOriginal` ("+02:00") as ISO 8601; without an
     * offset the time counts in [zone] (the phone's). Null for a missing, empty ("0000:00:00 …") or broken value.
     */
    fun exifTakenAt(dateTime: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val raw = dateTime?.trim()?.take(19)?.takeIf { it.length == 19 } ?: return null
        // some cameras write dashes in the date
        val fixed = raw.replaceRange(4, 5, ":").replaceRange(7, 8, ":")
        val local = runCatching { LocalDateTime.parse(fixed, EXIF_TIME) }.getOrNull() ?: return null
        if (local.year < 1900) return null
        val off = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        val at = if (off != null) local.atOffset(off) else local.atZone(zone).toOffsetDateTime()
        return at.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /**
     * A video's recording date as the container has it ("20261027T140500.000Z", UTC) as ISO 8601. Null when there is
     * none — or the container's empty value (1904, 1970).
     */
    fun videoTakenAt(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.length >= 15 } ?: return null
        val local = runCatching { LocalDateTime.parse(s.take(15), VIDEO_TIME) }.getOrNull() ?: return null
        if (local.year <= 1970) return null
        return local.atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /** The media store's `DATE_TAKEN` (ms) as ISO 8601; null when unknown. */
    fun msTakenAt(ms: Long?): String? = ms?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS).toString() }

    // ─── photos (Drive 1.7) ──────────────────────────────────────────

    /** The month a photo belongs to ("2026-10", in [zone]) by when it was taken, else its file time; null when unknown. */
    fun monthOf(node: DNode, zone: ZoneId = ZoneId.systemDefault()): String? {
        val iso = node.takenAt ?: node.mtime ?: node.updatedAt ?: return null
        val at = runCatching { Instant.parse(iso) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: return null
        val d = at.atZone(zone)
        return "%04d-%02d".format(Locale.ROOT, d.year, d.monthValue)
    }

    /** The photos in runs of one month each, in their order (newest first as the server sends them). */
    fun byMonth(items: List<DNode>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String?, List<DNode>>> {
        val out = ArrayList<Pair<String?, MutableList<DNode>>>()
        for (n in items) {
            val m = monthOf(n, zone)
            if (out.isNotEmpty() && out.last().first == m) out.last().second += n else out += m to mutableListOf(n)
        }
        return out
    }

    /**
     * Whether the Photos tab asks for the camera uploads only (`camera=1`): the person chose "Nur Kamera-Uploads" ([on])
     * and Drive knows them (1.10, feature `photosFolder`) — without it the choice is kept, but not sent.
     */
    fun cameraFilter(on: Boolean, me: DMe?): Boolean = on && me?.has(DMe.FEATURE_PHOTOS_FOLDER) == true

    /** The Photos page's search finds photos by content (1.14), people or places (1.16): else the files' search. */
    fun photoSearchable(me: DMe?): Boolean =
        me != null && (me.has(DMe.FEATURE_PHOTO_SEARCH) || me.has(DMe.FEATURE_PHOTO_PLACES) || me.has(DMe.FEATURE_PHOTO_PEOPLE))

    /** Pages of a photo list put together: each node once (a page boundary may repeat one). */
    fun appendPage(have: List<DNode>, page: List<DNode>): List<DNode> {
        val seen = have.mapTo(HashSet()) { it.id }
        return have + page.filter { seen.add(it.id) }
    }

    // ─── storage overview (Drive 1.7) ────────────────────────────────

    /**
     * Ticks [id] of a duplicate [group] on or off. One copy of each group always stays: the last one cannot be ticked,
     * nor one the person may not delete.
     */
    fun toggleDuplicate(selected: Set<Long>, group: DDuplicates, id: Long): Set<Long> {
        if (id in selected) return selected - id
        val item = group.items.firstOrNull { it.id == id } ?: return selected
        if (!item.canDelete) return selected
        val ticked = group.items.count { it.id in selected } + 1
        return if (ticked >= group.items.size) selected else selected + id
    }

    /** "Alle Kopien bis auf eine": in each group every copy that may go, except the oldest one kept. */
    fun allButOne(groups: List<DDuplicates>): Set<Long> = groups.flatMap { g ->
        val keep = g.items.firstOrNull { !it.canDelete } ?: g.items.minByOrNull { it.updatedAt ?: "" } ?: return@flatMap emptyList()
        g.items.filter { it.id != keep.id && it.canDelete }.map { it.id }
    }.toSet()

    /** The bytes the [selected] copies take. */
    fun freed(selected: Set<Long>, groups: List<DDuplicates>): Long =
        groups.flatMap { it.items }.distinctBy { it.id }.filter { it.id in selected }.sumOf { it.size }

    const val ON_CONFLICT_RENAME = "rename"
    const val ON_CONFLICT_VERSION = "version"

    // ─── Drive 1.8 / 1.9: AI for documents, import, office, vaults ──

    /** The office opens these (Drive 1.9 `office.js`). */
    private val OFFICE_EXT = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "csv")

    /** "Im Browser bearbeiten / ansehen": the office is set up, an office file, not in a vault. */
    fun officeAllowed(me: DMe?, node: DNode): Boolean =
        me?.has(DMe.FEATURE_OFFICE) == true && !node.isDir && !node.encrypted && node.name.substringAfterLast('.', "").lowercase() in OFFICE_EXT

    /** The import from Google Drive / OneDrive runs in the portal only: its direct link (Drive 1.8 `cloudImport`). */
    const val IMPORT_PORTAL_PATH = "/portal?drive=import#plg-gatecontrol-drive"

    /** How many days before a deadline a reminder may come (the server takes 0…90). */
    val REMIND_DAYS = listOf(0, 1, 2, 3, 7, 14, 30)
    const val REMIND_DEFAULT = 3

    /** Days from [today] to [date] (`YYYY-MM-DD`); negative when over, null when not a date. */
    fun daysUntil(date: String, today: java.time.LocalDate): Long? =
        runCatching { ChronoUnit.DAYS.between(today, java.time.LocalDate.parse(date)) }.getOrNull()

    /** A reminder can still be set (the server refuses one of a day that is over). */
    fun canRemind(deadline: DDeadline, today: java.time.LocalDate): Boolean = (daysUntil(deadline.date, today) ?: -1) >= 0

    /** The doc kind of a file in a list (a small symbol): only when the AI is done. */
    fun docKind(node: DNode): String? = node.insight?.takeIf { it.status == "ready" && !node.isDir }?.kind

    // ─── the Files and Photos tabs ───────────────────────────────────

    /** The Drive tab's pages: the files, then (feature `photos`) the photos; swiping goes from one to the other. */
    const val TAB_FILES = 0
    const val TAB_PHOTOS = 1

    fun tabCount(hasPhotos: Boolean): Int = if (hasPhotos) 2 else 1

    /** The page to show for the saved choice "photos" ([photos]); without the Photos tab always the files. */
    fun tabOf(photos: Boolean, hasPhotos: Boolean): Int = if (photos && hasPhotos) TAB_PHOTOS else TAB_FILES

    /** Whether [page] is the photos (what is saved as the tab's choice). */
    fun isPhotosTab(page: Int, hasPhotos: Boolean): Boolean = hasPhotos && page == TAB_PHOTOS

    /** Swiping between the pages: only when there are two, not while photos are ticked (that bar belongs to the page). */
    fun tabSwipe(hasPhotos: Boolean, selecting: Boolean): Boolean = hasPhotos && !selecting

    // ─── sorting a folder ───────────────────────────────────────────

    /** The sorts of a folder: `name`, `mtime`, `size` (the server's) and `type` (sorted here). */
    val SORTS = listOf("name", "mtime", "size", "type")

    /** How a folder is sorted; kept on the device. */
    data class FolderSort(val key: String = "name", val desc: Boolean = false)

    /** A sort's direction when it is picked: names and types A–Z, dates and sizes newest / largest first. */
    fun defaultDesc(key: String): Boolean = key == "mtime" || key == "size"

    fun sortOf(key: String?, desc: Boolean?): FolderSort {
        val k = key?.takeIf { it in SORTS } ?: "name"
        return FolderSort(k, desc ?: defaultDesc(k))
    }

    /** What the server sorts by (`type` is sorted here, on top of the names). */
    fun serverSort(key: String): String = if (key == "type") "name" else key

    /**
     * The groups of "Dateityp" (after the folders): documents, spreadsheets, presentations, PDF, pictures, videos, text,
     * archives, audio — and everything else.
     */
    fun typeGroup(node: DNode): Int = when (kind(node)) {
        Kind.DOC -> 0
        Kind.SHEET -> 1
        Kind.SLIDES -> 2
        Kind.PDF -> 3
        Kind.IMAGE -> 4
        Kind.VIDEO -> 5
        Kind.TEXT -> 6
        Kind.ARCHIVE -> 7
        Kind.AUDIO -> 8
        Kind.OTHER, Kind.FOLDER -> 9
    }

    /**
     * "Dateityp": the folders first (by name), then the files by their group ([typeGroup]), within it by extension, then
     * by name. Descending turns the order of the folders and of the files around — the folders stay first.
     */
    fun sortByType(items: List<DNode>, desc: Boolean): List<DNode> {
        val name = compareBy<DNode>({ it.name.lowercase(Locale.ROOT) }, { it.name }, { it.id })
        val folders = items.filter { it.isDir }.sortedWith(name)
        val files = items.filter { !it.isDir }.sortedWith(
            compareBy<DNode>({ typeGroup(it) }, { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) }).then(name),
        )
        return if (desc) folders.reversed() + files.reversed() else folders + files
    }

    /** The items of a folder in the order [sort] wants (the server sorted by [serverSort] already). */
    fun ordered(items: List<DNode>, sort: FolderSort): List<DNode> = if (sort.key == "type") sortByType(items, sort.desc) else items

    /** "Name (2).pdf", "Name (3).pdf" …: the first that is not [taken] (case does not count, as on the server). */
    fun freeName(name: String, taken: Collection<String>): String {
        val have = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (name.lowercase(Locale.ROOT) !in have) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (i in 2 until 10_000) {
            val c = "$base ($i)$ext"
            if (c.lowercase(Locale.ROOT) !in have) return c
        }
        return name
    }

    /** A vault's nodes with their names: folders first, then by name. */
    fun sortVault(items: List<Pair<DNode, String>>): List<Pair<DNode, String>> =
        items.sortedWith(compareBy<Pair<DNode, String>>({ !it.first.isDir }, { it.second.lowercase(Locale.ROOT) }))

    // ─── Drive 1.11: vaults only in the menu ───────────────────────

    /**
     * A folder's content without its vault folders (Drive 1.11: vaults are opened from the menu only). Inside a vault
     * nothing is left out. A Drive with `vaultsApart` leaves them out itself; an older one lists them.
     */
    fun withoutVaults(folder: DFolder): DFolder {
        if (folder.node.encrypted) return folder
        val items = folder.items.filter { !it.encrypted }
        if (items.size == folder.items.size) return folder
        return folder.copy(items = items, total = (folder.total - (folder.items.size - items.size)).coerceAtLeast(items.size))
    }

    /** Search, photos, albums: nothing of a vault. */
    fun withoutVaults(items: List<DNode>): List<DNode> = items.filter { !it.encrypted }

    /**
     * The areas a new vault can go to ("Wer darf ihn öffnen?"): Meine Dateien ("Nur ich"), the family (with a family
     * seat) and, with a business seat, each team folder with the write right. The vault lies in the area's top folder.
     */
    fun vaultAreas(areas: List<DArea>, seat: String?): List<DArea> = areas.filter { a ->
        a.writable && when (a.kind) {
            "mine" -> true
            "family" -> seat == "family"
            "team" -> seat == "business"
            else -> false
        }
    }.sortedBy { listOf("mine", "family", "team").indexOf(it.kind) }
}
