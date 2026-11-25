package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaTitle
import java.util.Locale

/** A subtitle file beside a video (`Geburtstag Oma.de.srt`): where, its language as the name says, forced or not. */
data class ShareSubtitle(val path: String, val mime: String, val language: String?, val forced: Boolean)

/** A video of a share's folder as the page "Ordner" shows it: the file, its title (with the progress), the subtitles beside it. */
data class ShareFolderVideo(val entry: ShareEntry, val title: MediaTitle, val subtitles: List<ShareSubtitle> = emptyList())

/** One folder of a share: its sub-folders and its videos, A–Z ([path] "" the share's root). */
data class ShareFolder(val path: String, val folders: List<ShareEntry>, val videos: List<ShareFolderVideo>)

/** What a folder holds, for its row ("12 Videos", "3 Ordner"). */
data class ShareFolderCount(val folders: Int, val videos: Int)

/**
 * The rules of the page "Ordner" (stage 4d): which entries of a folder show, the title and picture of a video from its
 * file name and the picture beside it (as [ShareIndex] does for the rows), the subtitle files beside it, the path bar.
 * Pure, unit-tested.
 */
object ShareFolders {
    /** Subtitles the player reads beside a video: SubRip, WebVTT, SubStation (Media3 parses all three). */
    val SUBTITLE_MIME = mapOf("srt" to "application/x-subrip", "vtt" to "text/vtt", "ass" to "text/x-ssa", "ssa" to "text/x-ssa")

    /** Language words in a subtitle's name and their ISO 639-1 code. */
    private val LANGUAGES = mapOf(
        "de" to "de", "ger" to "de", "deu" to "de", "german" to "de", "deutsch" to "de",
        "en" to "en", "eng" to "en", "english" to "en", "englisch" to "en",
        "fr" to "fr", "fre" to "fr", "fra" to "fr", "french" to "fr",
        "es" to "es", "spa" to "es", "spanish" to "es",
        "it" to "it", "ita" to "it", "italian" to "it",
        "nl" to "nl", "dut" to "nl", "nld" to "nl",
        "pl" to "pl", "pol" to "pl", "tr" to "tr", "tur" to "tr",
    )

    /** A folder the page shows: not hidden (`@eaDir`, `#recycle`, `.git`), "Extras" and "Samples" too (unlike the index). */
    fun shownDir(name: String): Boolean = !ShareNames.hidden(name) && !name.equals("System Volume Information", ignoreCase = true)

    /** The folder's parts of [path] from the root ("Filme/Familie" → "Filme", "Filme/Familie"). */
    fun crumbs(path: String): List<Pair<String, String>> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.indices.map { i -> parts[i] to parts.take(i + 1).joinToString("/") }
    }

    /**
     * Folder [dir] of share [sourceId] from its [entries]: the sub-folders that are not hidden (no `@eaDir`, no
     * recycle bin) and the videos with their title, picture and subtitles; both A–Z.
     */
    fun build(sourceId: String, dir: String, entries: List<ShareEntry>, seasonName: (Int) -> String = { "Staffel $it" }): ShareFolder {
        val folders = entries.filter { it.directory && shownDir(it.name) }.sortedBy { ShareNames.sortKey(it.name) }
        val names = dir.split('/').filter { it.isNotEmpty() }
        val index = ShareIndex(sourceId, ShareIndex.videosOf(names, entries, parentImages = null), seasonName)
        val videos = entries.filter { !it.directory && ShareNames.isVideo(it.name) }
            .sortedWith(compareBy<ShareEntry>({ ShareNames.sortKey(it.name) }, { it.name }))
            .mapNotNull { e -> index.file(ShareIndex.fileId(e.path))?.let { ShareFolderVideo(e, it, subtitlesOf(e, entries)) } }
        return ShareFolder(dir, folders, videos)
    }

    /** How many sub-folders and videos [entries] has. */
    fun count(entries: List<ShareEntry>) = ShareFolderCount(
        folders = entries.count { it.directory && shownDir(it.name) },
        videos = entries.count { !it.directory && ShareNames.isVideo(it.name) },
    )

    /**
     * The subtitle files beside [video] in [entries]: `Name.srt`, `Name.de.srt`, `Name.ger.forced.srt`, also `.vtt`
     * and `.ass`. Those with a language first in the order found.
     */
    fun subtitlesOf(video: ShareEntry, entries: List<ShareEntry>): List<ShareSubtitle> {
        val base = video.name.substringBeforeLast('.').lowercase(Locale.ROOT)
        return entries.asSequence()
            .filter { !it.directory && !ShareNames.hidden(it.name) }
            .mapNotNull { e ->
                val mime = SUBTITLE_MIME[ShareNames.extension(e.name)] ?: return@mapNotNull null
                val stem = e.name.substringBeforeLast('.').lowercase(Locale.ROOT)
                if (stem != base && !stem.startsWith("$base.")) return@mapNotNull null
                val tags = stem.removePrefix(base).split('.', '_', ' ', '-').filter { it.isNotEmpty() }
                ShareSubtitle(
                    path = e.path,
                    mime = mime,
                    language = tags.firstNotNullOfOrNull { LANGUAGES[it] },
                    forced = tags.any { it == "forced" || it == "erzwungen" },
                )
            }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .toList()
    }

    /**
     * A file name (without its extension) or a folder's name ([folder]) holds every word of [text], in any case and
     * spelling of accents ("fussball" finds "Fußball", "oma" finds "Geburtstag.Oma.mkv").
     */
    fun matches(name: String, text: String, folder: Boolean = false): Boolean {
        val words = ShareNames.sortKey(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val base = if (folder) name else name.substringBeforeLast('.')
        val n = ShareNames.sortKey(base.replace('.', ' ').replace('_', ' '))
        return words.all { it in n }
    }
}
