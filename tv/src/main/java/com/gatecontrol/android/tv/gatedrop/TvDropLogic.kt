package com.gatecontrol.android.tv.gatedrop

import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdItem
import com.gatecontrol.android.gatedrop.GdTargets
import java.util.Locale

/** A file of a send kept on the TV (in the app's own storage: a TV has no gallery). [path] relative to the drop folder. */
data class TvDropFile(val name: String, val mime: String, val bytes: Long, val path: String?)

/** A send received on the TV: from whom, when, its text and files. [seen]: opened on the TV once. */
data class TvDropEntry(
    val sendId: String,
    val from: String?,
    val at: Long,
    val text: String? = null,
    val link: String? = null,
    val files: List<TvDropFile> = emptyList(),
    val seen: Boolean = false,
) {
    val complete: Boolean get() = files.all { it.path != null }
}

/** Someone sends something to the TV: the question of mockup 5 ("Mila möchte dir 3 Dateien senden"). */
data class TvDropOffer(val entry: GdEntry) {
    val files: List<GdItem> get() = entry.items
    val bytes: Long get() = entry.items.sumOf { it.bytes }
}

/** A device or person the TV can send to (own devices first, then people, then others' open devices). */
data class TvDropTarget(val id: String, val name: String, val device: Boolean, val platform: String?, val online: Boolean)

/** What a send is, for its card in "GateDrop-Eingang" on the start page. */
enum class TvDropKind { PHOTOS, VIDEOS, MEDIA, FILE, FILES, TEXT, LINK }

/** "3 Fotos", "Rezept.pdf", a text: [name] for a single file, a text or a link. */
data class TvDropSummary(val kind: TvDropKind, val count: Int, val name: String? = null)

/** GateDrop on the TV without Android: what is received, kept, offered and where it can go. Pure, unit-tested. */
object TvDropLogic {
    /** At most this many sends are kept on the TV; the oldest go first. */
    const val MAX_ENTRIES = 50

    /** At most this much is kept (the stick has about 5 GB free); the oldest go first. */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** A single file above this is not taken onto the TV (it stays on the server for the other devices). */
    const val MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024

    private val SAFE = Regex("[^A-Za-z0-9._ -]")

    /** The name a file gets in the drop folder: no path, no odd characters, at most 80 characters, never empty. */
    fun fileName(index: Int, name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').replace(SAFE, "_").trim().trimStart('.')
        val cut = if (base.length > 80) base.take(40) + "_" + base.takeLast(39) else base
        return "$index-" + cut.ifEmpty { "file" }
    }

    /** A file the TV can fetch: on the server, not held back by the virus scan, not too large. */
    fun fetchable(item: GdItem): Boolean =
        item.mediaId != null && item.available && item.scan?.blocked != true && !item.local && item.bytes in 0..MAX_FILE_BYTES

    /** What can be shown right away: pictures and videos. */
    fun media(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/")

    fun isVideo(mime: String): Boolean = mime.startsWith("video/")

    /**
     * The entries to keep after adding [fresh] (newest first): at most [MAX_ENTRIES] and [MAX_BYTES]; the newest always
     * stays. Returns what is kept and what goes (its files are deleted by the caller).
     */
    fun prune(entries: List<TvDropEntry>, fresh: TvDropEntry? = null): Pair<List<TvDropEntry>, List<TvDropEntry>> {
        val all = (listOfNotNull(fresh) + entries.filter { it.sendId != fresh?.sendId }).sortedByDescending { it.at }
        val keep = ArrayList<TvDropEntry>()
        val gone = ArrayList<TvDropEntry>()
        var bytes = 0L
        for (e in all) {
            val size = e.files.filter { it.path != null }.sumOf { it.bytes }
            if (keep.isNotEmpty() && (keep.size >= MAX_ENTRIES || bytes + size > MAX_BYTES)) gone += e else {
                keep += e
                bytes += size
            }
        }
        return keep to gone
    }

    /** Ask about [send]: not answered yet on this TV and not taken already. */
    fun shouldAsk(send: GdEntry, handled: Set<String>, kept: List<TvDropEntry>): Boolean =
        send.id !in handled && kept.none { it.sendId == send.id } && !send.expired && !send.removed

    /** Where the TV can send to: own devices (not itself) that have GateDrop, then people, then others' open devices. */
    fun targets(t: GdTargets, ownDeviceId: String?): List<TvDropTarget> {
        fun dev(d: GdDevice) = TvDropTarget(d.id, d.owner?.let { "${d.name} · ${it.name}" } ?: d.name, true, d.platform, d.online)
        val own = t.devices.filter { it.id != ownDeviceId }.sortedByDescending { it.online }.map(::dev)
        val people = t.people.map { TvDropTarget(it.id, it.name, false, null, false) }
        val others = t.peopleDevices.sortedByDescending { it.online }.map(::dev)
        return own + people + others
    }

    /** What [e] is on its card: one file by its name, several by their kind ("3 Fotos"), else its text or link. */
    fun summary(e: TvDropEntry): TvDropSummary {
        val files = e.files
        return when {
            files.isEmpty() -> if (e.text != null || e.link == null) TvDropSummary(TvDropKind.TEXT, 0, e.text) else TvDropSummary(TvDropKind.LINK, 0, e.link)
            files.size == 1 -> TvDropSummary(TvDropKind.FILE, 1, files[0].name)
            files.all { it.mime.startsWith("image/") } -> TvDropSummary(TvDropKind.PHOTOS, files.size)
            files.all { isVideo(it.mime) } -> TvDropSummary(TvDropKind.VIDEOS, files.size)
            files.all { media(it.mime) } -> TvDropSummary(TvDropKind.MEDIA, files.size)
            else -> TvDropSummary(TvDropKind.FILES, files.size)
        }
    }

    /** The file of [e] whose picture the card shows: the first picture kept on the TV; null: none. */
    fun cover(e: TvDropEntry): Int? = e.files.indexOfFirst { it.path != null && it.mime.startsWith("image/") }.takeIf { it >= 0 }

    /** How many sends on the TV were not opened yet ("2 neu"). */
    fun fresh(entries: List<TvDropEntry>): Int = entries.count { !it.seen }

    /** A link the TV may hand to a browser: http(s) only. */
    fun openableLink(link: String?): Boolean {
        val l = link?.trim() ?: return false
        return (l.startsWith("https://", ignoreCase = true) || l.startsWith("http://", ignoreCase = true)) && l.length <= 2048 && l.none { it.isWhitespace() }
    }

    /** "3 Dateien · 48 MB" and the like: the size of a send. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            bytes < 1024 -> "$bytes B"
            mb < 1 -> String.format(locale, "%.0f KB", bytes / 1024.0)
            mb < 10 -> String.format(locale, "%.1f MB", mb)
            mb < 1024 -> String.format(locale, "%.0f MB", mb)
            else -> String.format(locale, "%.1f GB", mb / 1024.0)
        }
    }
}
