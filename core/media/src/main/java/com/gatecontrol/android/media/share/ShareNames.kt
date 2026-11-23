package com.gatecontrol.android.media.share

import java.text.Normalizer
import java.util.Locale

/**
 * What a share's file names say (no Jellyfin, no server reads the files): film or episode, the title, the year, season
 * and episode — `Kobaltnacht (2025).mkv`, `Graufeld.S02E04.Die.Fähre.1080p.mkv`, `Graufeld/Staffel 1/03 - Nebel.mkv`.
 * Pure, unit-tested.
 */
object ShareNames {
    /** The containers the stick plays (mkv and mp4 almost always directly). */
    val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "ogv", "3gp", "flv")
    val IMAGE = setOf("jpg", "jpeg", "png", "webp")

    /** Folders that are not the film itself. */
    private val SKIP_DIRS = setOf("sample", "samples", "extras", "featurettes", "trailers", "behind the scenes", "deleted scenes", "system volume information")

    data class Parsed(
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeName: String? = null,
    ) {
        val isEpisode: Boolean get() = season != null && episode != null
    }

    private const val SEP = "[ ._\\-\\[\\]()]"
    private val EPISODE = Regex("(?i)(?:^|$SEP)s(\\d{1,2})$SEP?e(\\d{1,3})(?:$SEP?-?e\\d{1,3})*(?=$|$SEP)")
    private val EPISODE_X = Regex("(?i)(?:^|$SEP)(\\d{1,2})x(\\d{2,3})(?=$|$SEP)")
    private val YEAR = Regex("(?:^|$SEP)((?:19|20)\\d{2})(?=$|$SEP)")
    private val JUNK = Regex(
        "(?i)(?:^|$SEP)(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr10\\+?|hdr|dv|dovi|x26[45]|h\\.?26[45]|hevc|avc|xvid|divx|bluray|blu-ray|bdrip|brrip|" +
            "web-?dl|webrip|web|hdtv|dvdrip|remux|proper|repack|german|deutsch|english|multi|dl|ac3|dts|truehd|atmos|aac|ddp?5\\.1|10bit|extended|unrated)(?=$|$SEP)",
    )
    private val SEASON_DIR = Regex("(?i)^(?:staffel|season|s)[ ._-]*(\\d{1,2})$")
    private val LEADING_NUMBER = Regex("^(\\d{1,3})(?:$SEP+(.*))?$")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isVideo(name: String): Boolean = !hidden(name) && extension(name) in VIDEO && !isSample(name)

    fun isImage(name: String): Boolean = !hidden(name) && extension(name) in IMAGE

    /** Not shown and not searched: hidden files, Synology's `@eaDir`, recycle bins, `Thumbs.db`. */
    fun hidden(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.isEmpty() || n[0] in ".@#$~" || n == "thumbs.db" || n == "desktop.ini"
    }

    fun skipDir(name: String): Boolean = hidden(name) || name.lowercase(Locale.ROOT) in SKIP_DIRS

    private fun isSample(name: String): Boolean = Regex("(?i)(?:^|$SEP)sample$").containsMatchIn(name.substringBeforeLast('.'))

    /** "Staffel 2" / "Season 02" / "S2" → 2. */
    fun seasonOfDir(name: String): Int? = SEASON_DIR.find(name.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Dots and underscores to spaces, brackets and dashes off the ends. */
    fun clean(s: String): String =
        s.replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim().trim('-', '–', ' ', '(', '[', '{', ')', ']', '}').trim()

    private fun beforeJunk(s: String): String = JUNK.find(s)?.let { s.substring(0, it.range.first) } ?: s

    /**
     * [fileName] in its folders ([dirs]: from the root down to its own folder). An episode needs `S01E02`, `1x02` or
     * a number at the start inside a season folder; its series is the text before, else the series' folder.
     */
    fun parse(fileName: String, dirs: List<String> = emptyList()): Parsed {
        val base = fileName.substringBeforeLast('.').ifEmpty { fileName }
        val parent = dirs.lastOrNull()
        val parentSeason = parent?.let(::seasonOfDir)
        val seriesFolder = (if (parentSeason != null) dirs.dropLast(1).lastOrNull() else parent)?.let { clean(stripYear(it).first) }

        val ep = EPISODE.find(base) ?: EPISODE_X.find(base)
        if (ep != null) {
            val season = ep.groupValues[1].toInt()
            val episode = ep.groupValues[2].toInt()
            val before = clean(stripYear(base.substring(0, ep.range.first)).first)
            val after = clean(beforeJunk(base.substring(ep.range.last + 1)))
            val series = before.ifEmpty { seriesFolder.orEmpty() }.ifEmpty { clean(base) }
            return Parsed(series, season = season, episode = episode, episodeName = after.ifEmpty { null })
        }
        if (parentSeason != null && seriesFolder != null) {
            LEADING_NUMBER.find(base.trim())?.let { m ->
                val name = clean(beforeJunk(m.groupValues.getOrElse(2) { "" }))
                return Parsed(seriesFolder, season = parentSeason, episode = m.groupValues[1].toInt(), episodeName = name.ifEmpty { null })
            }
        }
        val (title, year) = stripYear(base)
        val cleaned = clean(beforeJunk(title))
        return Parsed(cleaned.ifEmpty { clean(base) }, year = year)
    }

    /** The text before the year and the year ("Kobaltnacht (2025) 1080p" → "Kobaltnacht ", 2025); a title of only a year stays. */
    private fun stripYear(s: String): Pair<String, Int?> {
        val m = YEAR.findAll(s).lastOrNull { it.range.first > 0 } ?: return s to null
        val before = s.substring(0, m.range.first)
        if (clean(before).isEmpty()) return s to null
        return before to m.groupValues[1].toInt()
    }

    /** The key two titles are the same by ("Die letzte Fähre nach Mirow" = "die.letzte.fähre.nach.mirow"). */
    fun key(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** A–Z as Jellyfin sorts by default (an English article in front does not count). */
    fun sortKey(name: String): String {
        val n = name.lowercase(Locale.ROOT).trim()
        val stripped = listOf("the ", "a ", "an ").firstOrNull { n.startsWith(it) && n.length > it.length }?.let { n.removePrefix(it) } ?: n
        val plain = Normalizer.normalize(stripped.replace("ß", "ss"), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex(" +"), " ").trim()
    }
}
