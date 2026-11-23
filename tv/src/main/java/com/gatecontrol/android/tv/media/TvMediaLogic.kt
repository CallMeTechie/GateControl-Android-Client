package com.gatecontrol.android.tv.media

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaQuality
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.share.ShareNames
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

    /** "Jellyfin · DS918+", "NAS-Freigabe · \\nas\filme" ([shareWord]: the word for a share in the TV's language). */
    fun sourceLabel(s: MediaSource, shareWord: String = "NAS-Freigabe"): String =
        if (s.kind.share) "$shareWord · ${shareAddress(s)}" else "Jellyfin · ${s.name}"

    /** A share's address as people write it: `\\nas\filme`, `192.168.1.20:5006/filme`. */
    fun shareAddress(s: MediaSource): String = when (s.kind) {
        MediaSourceKind.SMB -> MediaAddress.parseSmb(s.address)?.unc ?: s.address
        else -> s.address.substringAfter("://")
    }

    /** The shares to show: each once, a pair (SMB + WebDAV of the same folder) as its SMB source. */
    fun shares(all: List<MediaSource>): List<MediaSource> = all.filter { it.kind.share }.filterNot { s ->
        s.kind == MediaSourceKind.WEBDAV && all.any { it.id == s.pairWith && it.pairWith == s.id && it.kind == MediaSourceKind.SMB }
    }

    /**
     * Which of a pair is used (decision 9): at home SMB (fastest), away WebDAV (HTTP with ranges bears the latency of
     * the tunnel better than the chatty SMB). Not known yet: as at home. One of them alone is used anywhere.
     */
    fun shareFor(smb: MediaSource?, webdav: MediaSource?, atHome: Boolean?): MediaSource? =
        if (atHome == false) webdav ?: smb else smb ?: webdav

    /** Two titles are the same film, series or episode (name, and the year when both have one). */
    fun same(a: MediaTitle, b: MediaTitle): Boolean {
        if (a.kind == MediaTitle.Kind.EPISODE || b.kind == MediaTitle.Kind.EPISODE) {
            return a.kind == b.kind && a.season == b.season && a.episode == b.episode && a.season != null &&
                ShareNames.key(a.seriesName.orEmpty()) == ShareNames.key(b.seriesName.orEmpty()) && a.seriesName != null
        }
        val series = a.kind == MediaTitle.Kind.SERIES
        if (series != (b.kind == MediaTitle.Kind.SERIES)) return false
        if (ShareNames.key(a.name) != ShareNames.key(b.name) || ShareNames.key(a.name).isEmpty()) return false
        return a.year == null || b.year == null || a.year == b.year
    }

    /** [shares]' titles without those [jellyfin] has too: a title in both shows once, Jellyfin's (mockup 7). */
    fun withoutDuplicates(shares: List<MediaTitle>, jellyfin: Collection<MediaTitle>): List<MediaTitle> {
        if (jellyfin.isEmpty()) return shares
        val byKey = jellyfin.groupBy { ShareNames.key(if (it.kind == MediaTitle.Kind.EPISODE) it.seriesName.orEmpty() else it.name) }
        return shares.filterNot { s ->
            byKey[ShareNames.key(if (s.kind == MediaTitle.Kind.EPISODE) s.seriesName.orEmpty() else s.name)].orEmpty().any { same(s, it) }
        }
    }

    /** "Weiterschauen" of all sources: the one watched last first (a title without a time keeps its place after). */
    fun byLastPlayed(lists: List<List<MediaTitle>>, limit: Int): List<MediaTitle> =
        lists.flatten().withIndex().sortedWith(compareByDescending<IndexedValue<MediaTitle>> { it.value.playedAt ?: 0L }.thenBy { it.index })
            .map { it.value }.take(limit)

    /** "Zuletzt hinzugefügt" of all sources, newest first. */
    fun byAdded(lists: List<List<MediaTitle>>, limit: Int): List<MediaTitle> =
        lists.flatten().withIndex().sortedWith(compareByDescending<IndexedValue<MediaTitle>> { it.value.addedAt ?: 0L }.thenBy { it.index })
            .map { it.value }.take(limit)

    /**
     * Filme A–Z (or Serien) of Jellyfin, loaded page by page, with [shares]' titles in their places. While Jellyfin has
     * more pages ([jellyfinComplete] false) a share's title after the last one loaded waits for the next page.
     */
    fun alphabetical(jellyfin: List<MediaTitle>, jellyfinComplete: Boolean, shares: List<MediaTitle>): List<MediaTitle> {
        val cut = if (jellyfinComplete) null else jellyfin.lastOrNull()?.let { ShareNames.sortKey(it.name) }
        val waiting = shares.filter { cut == null || ShareNames.sortKey(it.name) <= cut }.sortedBy { ShareNames.sortKey(it.name) }
        if (!jellyfinComplete && jellyfin.isEmpty()) return emptyList()
        val out = ArrayList<MediaTitle>(jellyfin.size + waiting.size)
        var i = 0
        for (j in jellyfin) {
            val k = ShareNames.sortKey(j.name)
            while (i < waiting.size && ShareNames.sortKey(waiting[i].name) < k) out += waiting[i++]
            out += j
        }
        while (i < waiting.size) out += waiting[i++]
        return out
    }

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
