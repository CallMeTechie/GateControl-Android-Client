package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaTitle
import java.util.Locale

/** What the player goes through: titles of one source from [at] (a film, or the episodes of a series in order). */
data class TvMediaQueue(
    val sourceId: String,
    val items: List<MediaTitle>,
    val at: Int,
    /** The first title from here (ms); null: where Jellyfin has it, 0: from the start ("Von vorn"). */
    val startMs: Long? = null,
    /** Chosen on the detail page for the first title; null: the series' choice or the server's default. */
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
    /** The detail page's "Original (direkt)" (true) or "Angepasst" (false); null: as the quality setting says. */
    val original: Boolean? = null,
)

/** Small rules of the media pages, kept apart from the composables. Pure, unit-tested. */
object TvMediaLogic {

    /** "1:06" (hours and minutes, begun minutes count) for a time of an hour or more; null below (then "18 Min."). */
    fun hoursMinutes(ms: Long): String? {
        val m = (ms + 59_999) / 60_000
        if (m < 60) return null
        return String.format(Locale.ROOT, "%d:%02d", m / 60, m % 60)
    }

    /** Minutes, a begun one counts, at least 1. */
    fun minutes(ms: Long): Long = ((ms + 59_999) / 60_000).coerceAtLeast(1)

    /**
     * The queue of [title] in [episodes] (the whole series in order): from it to the series' end, so "Nächste Folge"
     * goes on. A film (or an episode not in the list) alone.
     */
    fun queue(sourceId: String, title: MediaTitle, episodes: List<MediaTitle>, startMs: Long? = null): TvMediaQueue {
        val playable = episodes.filter { it.playable }
        val i = playable.indexOfFirst { it.id == title.id }
        return if (title.kind == MediaTitle.Kind.EPISODE && i >= 0) {
            TvMediaQueue(sourceId, playable, i, startMs)
        } else {
            TvMediaQueue(sourceId, listOf(title), 0, startMs)
        }
    }

    /** "Jellyfin · DS918+". */
    fun sourceLabel(s: MediaSource): String = "Jellyfin · ${s.name}"

    /** Away and measured: "18 Mbit/s"; else null. */
    fun bandwidth(link: TvMediaLink): String? = link.bps?.takeIf { link.atHome == false }?.let { MediaQuality.label(it) }

    /** The first page of a long row, the next ones when the remote comes near its end. */
    fun wantsMore(focused: Int, loaded: Int, next: Int?): Boolean = next != null && focused >= loaded - PAGE_AHEAD

    /** The row "Zuletzt hinzugefügt" without what is already in "Weiterschauen". */
    fun latestWithout(latest: List<MediaTitle>, resume: List<MediaTitle>): List<MediaTitle> {
        val shown = resume.map { it.id }.toSet() + resume.mapNotNull { it.seriesId }.toSet()
        return latest.filterNot { it.id in shown }
    }

    const val PAGE = 40
    const val PAGE_AHEAD = 6
}
