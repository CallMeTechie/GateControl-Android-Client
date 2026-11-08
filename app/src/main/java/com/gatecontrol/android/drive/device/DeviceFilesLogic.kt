package com.gatecontrol.android.drive.device

import java.util.Locale

/** A file or folder on this phone (internal storage, SD card, USB stick), as the page "Auf dem Gerät" shows it. */
data class DeviceEntry(
    /** Absolute path (`/storage/emulated/0/Download/Rechnung.pdf`). */
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    /** Last change, epoch ms (0: unknown). */
    val modified: Long,
    val mime: String? = null,
    /** Files in a folder (folders only; null: not counted). */
    val children: Int? = null,
)

/**
 * The rules of "Auf dem Gerät" (Drive › hard disk symbol): what kind a file is, which category it counts in, sorting and
 * filtering, whether it is in Drive, the safe target of a ZIP entry and the storage bar — pure, unit-tested.
 */
object DeviceFilesLogic {

    /** The kind of a file: its symbol, its filter chip and its category. */
    enum class Type { FOLDER, PDF, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

    /** The tiles of the start page. [DOWNLOAD] is a place (the Download folder), the others are kinds of files. */
    enum class Category(val key: String) {
        DOWNLOAD("download"), IMAGES("images"), VIDEOS("videos"), DOCUMENTS("documents"), AUDIO("audio"), ARCHIVES("archives");

        companion object {
            fun of(key: String?): Category? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Sort { NEWEST, NAME, SIZE }

    /** The chips above a list: "Alle" and the kinds that are in it. */
    enum class Filter { ALL, PDF, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES }

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "dng", "svg")
    private val VIDEO = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "wmv")
    private val AUDIO = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi", "wma")
    private val DOCUMENT = setOf(
        "doc", "docx", "odt", "rtf", "txt", "md", "markdown", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp",
        "epub", "pages", "numbers", "key", "json", "xml", "log", "html", "htm", "vcf", "ics",
    )
    private val ARCHIVE = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
    private val APK = setOf("apk", "apks", "xapk", "apkm")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** By the name first (a `.ts` video is no TypeScript file), then by the MIME type. */
    fun typeOf(name: String, mime: String?, isDir: Boolean = false): Type {
        if (isDir) return Type.FOLDER
        val e = ext(name)
        val m = mime.orEmpty().lowercase(Locale.ROOT).substringBefore(';').trim()
        return when {
            e == "pdf" -> Type.PDF
            e in APK -> Type.APK
            e in ARCHIVE -> Type.ARCHIVE
            e in IMAGE -> Type.IMAGE
            e in VIDEO -> Type.VIDEO
            e in AUDIO -> Type.AUDIO
            e in DOCUMENT -> Type.DOCUMENT
            m == "application/pdf" -> Type.PDF
            m == "application/vnd.android.package-archive" -> Type.APK
            m.startsWith("image/") -> Type.IMAGE
            m.startsWith("video/") -> Type.VIDEO
            m.startsWith("audio/") -> Type.AUDIO
            m.contains("zip") || m.contains("compressed") || m.contains("x-tar") || m.contains("x-7z") || m.contains("rar") -> Type.ARCHIVE
            m.startsWith("text/") || m.contains("wordprocessing") || m.contains("spreadsheet") || m.contains("presentation") ||
                m == "application/msword" || m.contains("opendocument") || m == "application/epub+zip" -> Type.DOCUMENT
            else -> Type.OTHER
        }
    }

    /** The category a file of [type] counts in (null: none, e.g. a folder or an unknown file). */
    fun categoryOf(type: Type): Category? = when (type) {
        Type.IMAGE -> Category.IMAGES
        Type.VIDEO -> Category.VIDEOS
        Type.AUDIO -> Category.AUDIO
        Type.PDF, Type.DOCUMENT -> Category.DOCUMENTS
        Type.ARCHIVE, Type.APK -> Category.ARCHIVES
        Type.FOLDER, Type.OTHER -> null
    }

    /** In the Download folder of the internal storage (MediaStore's relative path, `Download/…`). */
    fun isDownload(relativePath: String?): Boolean {
        val p = relativePath.orEmpty().trimStart('/')
        return p.equals("Download", ignoreCase = true) || p.startsWith("Download/", ignoreCase = true)
    }

    /**
     * Not shown in the categories and "Zuletzt hinzugefügt": hidden files and folders (a dot in front), the apps' own
     * folders (`Android/data`, `Android/obb`, `Android/media`) and the trash of other apps.
     */
    fun hidden(path: String): Boolean {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it.startsWith('.') }) return true
        val i = parts.indexOfFirst { it == "Android" }
        return i >= 0 && parts.getOrNull(i + 1) in setOf("data", "obb", "media")
    }

    fun matches(filter: Filter, type: Type): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.PDF -> type == Type.PDF
        Filter.IMAGES -> type == Type.IMAGE
        Filter.VIDEOS -> type == Type.VIDEO
        Filter.AUDIO -> type == Type.AUDIO
        Filter.DOCUMENTS -> type == Type.DOCUMENT
        Filter.ARCHIVES -> type == Type.ARCHIVE || type == Type.APK
    }

    fun typeOf(e: DeviceEntry): Type = typeOf(e.name, e.mime, e.isDir)

    /** "Alle", then each kind that is in [entries] (in a fixed order); folders only show with "Alle". */
    fun filters(entries: List<DeviceEntry>): List<Filter> {
        val types = entries.filter { !it.isDir }.map { typeOf(it) }.toSet()
        return listOf(Filter.ALL) + Filter.entries.drop(1).filter { f -> types.any { matches(f, it) } }
    }

    /** [entries] with [filter] (folders only with "Alle"), sorted: folders first, then by [sort]. */
    fun view(entries: List<DeviceEntry>, sort: Sort, filter: Filter): List<DeviceEntry> {
        val shown = if (filter == Filter.ALL) entries else entries.filter { !it.isDir && matches(filter, typeOf(it)) }
        val byName = compareBy<DeviceEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val files = when (sort) {
            Sort.NEWEST -> compareByDescending<DeviceEntry> { it.modified }.then(byName)
            Sort.NAME -> byName
            Sort.SIZE -> compareByDescending<DeviceEntry> { it.size }.then(byName)
        }
        // folders on top, by name (or newest first); a folder's size is not known, so "Größe" keeps them by name
        val folders = if (sort == Sort.NEWEST) files else byName
        return shown.filter { it.isDir }.sortedWith(folders) + shown.filter { !it.isDir }.sortedWith(files)
    }

    // ─── "In Drive" / "Nur hier" ────────────────────────────────────

    enum class InDrive { IN_DRIVE, ONLY_HERE, UNKNOWN }

    /** A file Drive found for a name (`GET search?q=<name>`): name, size, folder or not. */
    data class Hit(val name: String, val size: Long, val isDir: Boolean)

    /** Drive's search gives at most this many results (`SEARCH_MAX` of the plugin); a full page may hide the match. */
    const val SEARCH_MAX = 100

    /** Drive's search takes 2 to 100 characters. */
    fun searchable(name: String): Boolean = name.trim().length in 2..100

    /**
     * The rule of the label: "In Drive" when Drive has a file of the same name (case does not matter) and exactly the
     * same size — in any area the person sees, outside the trash and the vaults; "Nur hier" when Drive answered
     * completely (fewer than [SEARCH_MAX] hits) and has none. Otherwise (no answer, offline, too many hits, a name it
     * cannot search) nothing is shown: [InDrive.UNKNOWN]. A copy under another name counts as "Nur hier".
     */
    fun inDrive(name: String, size: Long, hits: List<Hit>?): InDrive {
        if (hits == null || !searchable(name)) return InDrive.UNKNOWN
        val n = name.trim()
        if (hits.any { !it.isDir && it.size == size && it.name.trim().equals(n, ignoreCase = true) }) return InDrive.IN_DRIVE
        return if (hits.size < SEARCH_MAX) InDrive.ONLY_HERE else InDrive.UNKNOWN
    }

    // ─── ZIP ───────────────────────────────────────────────────────

    /** At most this many entries, this much in all, and never more than this times the archive's size (a ZIP bomb). */
    const val ZIP_MAX_ENTRIES = 20_000
    const val ZIP_MAX_BYTES = 8L * 1024 * 1024 * 1024
    const val ZIP_MAX_RATIO = 200L
    /** What stays free on the storage while unpacking. */
    const val ZIP_RESERVE = 200L * 1024 * 1024

    /**
     * Where entry [entryName] of a ZIP goes, relative to the target folder — null for one that would leave it ("zip
     * slip": `../`, an absolute path, a drive letter) or has no usable name. `\` counts as `/`, `.` and empty parts drop.
     */
    fun zipTarget(entryName: String): String? {
        if (entryName.isEmpty() || entryName.contains('\u0000')) return null
        val p = entryName.replace('\\', '/')
        if (p.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(p)) return null
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." || it.length > 255 }) return null
        return parts.joinToString("/")
    }

    /** Whether [child] (canonical) lies inside [folder] (canonical) — the second check of each entry after [zipTarget]. */
    fun inside(folder: String, child: String): Boolean {
        val f = folder.trimEnd('/')
        return child.startsWith("$f/") && child.length > f.length + 1
    }

    /** How much unpacking may write: the limit, less than the free space (minus [ZIP_RESERVE]), [ZIP_MAX_RATIO]× the archive. */
    fun zipBudget(archiveSize: Long, free: Long): Long =
        minOf(ZIP_MAX_BYTES, free - ZIP_RESERVE, archiveSize.coerceAtLeast(1) * ZIP_MAX_RATIO).coerceAtLeast(0)

    /** The folder a ZIP unpacks into: its name without `.zip` (a free one is found by [freeName]). */
    fun zipFolder(name: String): String = name.substringBeforeLast('.', name).trim().ifEmpty { name }

    /** [name], or "name (2).ext", "name (3).ext" … — the first one [taken] says is free. */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (i in 2..9_999) {
            val n = "$base ($i)$ext"
            if (!taken(n)) return n
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }

    // ─── places ────────────────────────────────────────────────────

    enum class VolumeKind { INTERNAL, SD, USB }

    /**
     * Internal (primary), a USB stick (Android names it "USB …") or an SD card (any other removable volume). Android
     * has no public type for it; the kind only picks the symbol, the name shown is Android's own.
     */
    fun volumeKind(primary: Boolean, description: String?): VolumeKind = when {
        primary -> VolumeKind.INTERNAL
        description.orEmpty().contains("usb", ignoreCase = true) -> VolumeKind.USB
        else -> VolumeKind.SD
    }

    /** The folders from [root] down to [path]: (name, path) each, without the root itself (the caller names it). */
    fun crumbs(root: String, path: String): List<Pair<String, String>> {
        val r = root.trimEnd('/')
        if (path.trimEnd('/') == r || !inside(r, path.trimEnd('/'))) return emptyList()
        val rest = path.trimEnd('/').removePrefix("$r/").split('/').filter { it.isNotEmpty() }
        return rest.indices.map { i -> rest[i] to (r + "/" + rest.subList(0, i + 1).joinToString("/")) }
    }

    /** Whether [target] is [source] itself or lies inside it (a folder cannot go into itself). */
    fun intoItself(source: String, target: String): Boolean {
        val s = source.trimEnd('/')
        val t = target.trimEnd('/')
        return t == s || inside(s, t)
    }

    // ─── the storage bar ───────────────────────────────────────────

    enum class Part { MEDIA, DOCUMENTS, AUDIO, OTHER }

    /**
     * The parts of the bar of the internal storage as shares of [total]: pictures and videos, documents, audio, the
     * rest of [used] (apps, system, everything else). Never more than [used] in all, nothing below 0.
     */
    fun bar(total: Long, used: Long, media: Long, documents: Long, audio: Long): List<Pair<Part, Float>> {
        if (total <= 0) return emptyList()
        val u = used.coerceIn(0, total)
        var left = u
        fun take(v: Long): Long = v.coerceIn(0, left).also { left -= it }
        val m = take(media)
        val d = take(documents)
        val a = take(audio)
        val o = left
        return listOf(Part.MEDIA to m, Part.DOCUMENTS to d, Part.AUDIO to a, Part.OTHER to o)
            .map { (p, v) -> p to v.toFloat() / total }
    }
}
