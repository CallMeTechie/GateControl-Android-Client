package com.gatecontrol.android.tv.frame

import com.google.gson.JsonObject
import kotlin.random.Random

/**
 * The photo frame of the TV (stage 2, decision TV 6 and 23.11.): only the photos shared on purpose for the photo frame
 * (in the phone or Windows app: Fotos › select › „Für Fotorahmen freigeben“, plus family albums „Im Fotorahmen zeigen“),
 * from Drive's `GET frame` (Drive 1.31: also for app devices). Started by hand or when the TV sat idle in GateControl.
 *
 * [seconds] 0 and [order] null: as the server says (`seconds`, `order` of `GET frame`); a value here overrides it.
 */
data class TvFrameSettings(
    val seconds: Int = 0,
    val order: TvFrameOrder? = null,
    /** Minutes without a key in GateControl before the frame starts; 0: only by hand. */
    val idleMinutes: Int = 0,
    val clock: Boolean = true,
)

enum class TvFrameOrder(val wire: String) {
    RANDOM("random"),

    /** Newest first. */
    DATE("date"),
    ;

    companion object {
        fun of(raw: String?): TvFrameOrder? = entries.firstOrNull { it.wire == raw }
    }
}

/** One picture of the frame: [url] a preview-only address relative to the server (2 h valid), [takenAt] ISO or null. */
data class TvFramePhoto(val url: String, val takenAt: String?) {
    /**
     * What stays the same when the list is read again (the URLs are signed anew each time): when it was taken. Two
     * photos of the same second are told apart by their place among those (see [TvFramePlaylist.update]).
     */
    val key: String get() = takenAt ?: url
}

/** `GET frame`: [on] the photo frame switched on (by the administrator), [ready] the server hands out preview URLs. */
data class TvFrameFeed(
    val on: Boolean,
    val ready: Boolean,
    val seconds: Int,
    val order: TvFrameOrder,
    val items: List<TvFramePhoto>,
)

/** What the frame can show right now (the settings page and the frame itself say it). */
sealed interface TvFrameStatus {
    /** Not asked yet. */
    data object Unknown : TvFrameStatus

    /** Photos are shared: [count] of them. */
    data class Photos(val count: Int) : TvFrameStatus

    /** Switched off on the server, or nothing shared yet: „Noch keine Fotos für den Fotorahmen freigegeben …“. */
    data object NoPhotos : TvFrameStatus

    /** The server cannot hand out preview-only addresses yet (GateControl too old). */
    data object NotReady : TvFrameStatus

    /** Drive on the server does not know the frame for app devices (before 1.31), or no Drive at all. */
    data object Unavailable : TvFrameStatus

    /** Could not be asked (offline, server error): tried again in a minute. */
    data object Failed : TvFrameStatus
}

/** `GET frame` → [TvFrameFeed]. Pure, unit-tested. */
object TvFrameJson {
    private val PATH_RE = Regex("^/plugin-media/[A-Za-z0-9._~%-]{1,400}/preview(\\?[A-Za-z0-9=&._~%-]{0,200})?$")

    /** Only previews of the own server (`/plugin-media/<token>/preview?size=large`), nothing else. */
    fun safePath(url: String?): String? = url?.trim()?.takeIf { it.length < 700 && !it.contains("..") && PATH_RE.matches(it) }

    fun feed(o: JsonObject): TvFrameFeed? {
        if (o.has("ok") && o.get("ok").let { it.isJsonPrimitive && !it.asBoolean }) return null
        val on = o.bool("on") ?: return null
        val items = (o.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList()).mapNotNull { e ->
            val p = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val url = safePath(p.str("url")) ?: return@mapNotNull null
            TvFramePhoto(url, p.str("takenAt")?.take(40)?.takeIf { it.isNotBlank() })
        }.take(TvFrameSchedule.MAX_ITEMS)
        return TvFrameFeed(
            on = on,
            ready = o.bool("ready") ?: true,
            seconds = TvFrameSchedule.serverSeconds(o.int("seconds")),
            order = TvFrameOrder.of(o.str("order")) ?: TvFrameOrder.RANDOM,
            items = if (on) items else emptyList(),
        )
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.int(k: String): Int? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.toInt()
}

/** The timing and the order of the photo frame. Pure, unit-tested. */
object TvFrameSchedule {
    /** The server's frame_seconds when it says nothing (Drive's default). */
    const val DEFAULT_SECONDS = 20

    /** The choices of "Wechsel alle": 0 is „Wie am Server“. */
    val SECONDS = listOf(0, 10, 20, 30, 60, 300)

    /** The choices of "Reihenfolge": null is „Wie am Server“. */
    val ORDERS: List<TvFrameOrder?> = listOf(null, TvFrameOrder.RANDOM, TvFrameOrder.DATE)

    /** The choices of "Starten nach" (0: off). */
    val IDLE_MINUTES = listOf(0, 5, 10, 15, 30)

    /** `GET frame` is asked again after this long: the URLs hold 2 h, new shares come in, taken back ones go. */
    const val REFRESH_MS = 30 * 60_000L

    /** After a failure (or nothing to show) the frame asks again after this long. */
    const val RETRY_MS = 60_000L

    /** At most this many pictures in the frame (as Drive's own frame). */
    const val MAX_ITEMS = 1000

    /** The next value of [options] after [current] (OK on the row cycles); the first when [current] is not one. */
    fun <T> cycle(options: List<T>, current: T): T {
        val at = options.indexOf(current)
        return if (at < 0) options.first() else options[(at + 1) % options.size]
    }

    /** A stored local interval: one of [SECONDS], else 0 (as the server). */
    fun seconds(raw: Int): Int = raw.takeIf { it in SECONDS } ?: 0

    /** The server's `seconds` (Drive: 5 … 3600). */
    fun serverSeconds(raw: Int?): Int = raw?.coerceIn(5, 3600) ?: DEFAULT_SECONDS

    fun idleMinutes(raw: Int): Int = raw.takeIf { it in IDLE_MINUTES } ?: 0

    /** Seconds per picture: the TV's own choice, else the server's. */
    fun effectiveSeconds(settings: TvFrameSettings, feed: TvFrameFeed?): Int =
        settings.seconds.takeIf { it > 0 } ?: feed?.seconds ?: DEFAULT_SECONDS

    /** The order: the TV's own choice, else the server's. */
    fun effectiveOrder(settings: TvFrameSettings, feed: TvFrameFeed?): TvFrameOrder =
        settings.order ?: feed?.order ?: TvFrameOrder.RANDOM

    /** What a `GET frame` answer means for the frame; [feed] null: it failed, [unavailable] the server refused it. */
    fun status(feed: TvFrameFeed?, unavailable: Boolean = false): TvFrameStatus = when {
        feed == null -> if (unavailable) TvFrameStatus.Unavailable else TvFrameStatus.Failed
        !feed.on -> TvFrameStatus.NoPhotos
        !feed.ready -> TvFrameStatus.NotReady
        feed.items.isEmpty() -> TvFrameStatus.NoPhotos
        else -> TvFrameStatus.Photos(feed.items.size)
    }

    /**
     * May the frame start now: idle start on, photos shared (as far as known — [status] [TvFrameStatus.Unknown] is
     * asked first by the caller) and no key for [TvFrameSettings.idleMinutes] since [lastKeyMs].
     */
    fun idleDue(settings: TvFrameSettings, status: TvFrameStatus, lastKeyMs: Long, nowMs: Long): Boolean =
        settings.idleMinutes > 0 && status !is TvFrameStatus.NoPhotos && status !is TvFrameStatus.NotReady &&
            status !is TvFrameStatus.Unavailable && nowMs - lastKeyMs >= settings.idleMinutes * 60_000L

    /** Is `GET frame` due again: never asked, half an hour old, or the last try failed / showed nothing a minute ago. */
    fun refreshDue(loadedAtMs: Long, nowMs: Long, status: TvFrameStatus = TvFrameStatus.Unknown): Boolean = when {
        loadedAtMs <= 0L -> true
        status is TvFrameStatus.Photos -> nowMs - loadedAtMs >= REFRESH_MS
        status is TvFrameStatus.Unknown -> true
        else -> nowMs - loadedAtMs >= RETRY_MS
    }

    /** [items] in the order of the frame without the random part: newest first (no date last). */
    fun byDate(items: List<TvFramePhoto>): List<TvFramePhoto> =
        items.sortedWith(compareBy<TvFramePhoto> { it.takenAt == null }.thenByDescending { it.takenAt.orEmpty() })
}

/**
 * The order of the pictures: newest first or shuffled, every picture once before any comes again, and a new round
 * never starts with the picture the last one ended on. Read again ([update]): the round goes on with what is still
 * shared (with the fresh URLs), new pictures come in the next round.
 */
class TvFramePlaylist(items: List<TvFramePhoto>, private val shuffle: Boolean, private val random: Random = Random.Default) {
    private var items: List<TvFramePhoto> = items
    private var round: List<TvFramePhoto> = emptyList()
    private var at = -1

    val size: Int get() = items.size

    /** The picture after the current one; null when there is none. */
    fun next(): TvFramePhoto? {
        if (items.isEmpty()) return null
        if (at + 1 >= round.size) newRound()
        at++
        return round[at]
    }

    /** The picture before the current one (◀ in the frame); the same at the start of a round. */
    fun previous(): TvFramePhoto? {
        if (round.isEmpty() || at < 0) return next()
        if (at > 0) at--
        return round[at]
    }

    fun update(fresh: List<TvFramePhoto>) {
        // the fresh photos by key; a key twice (same second) is taken in turn
        val pool = HashMap<String, ArrayDeque<TvFramePhoto>>()
        for (p in fresh) pool.getOrPut(p.key) { ArrayDeque() }.addLast(p)
        var shown = 0
        val kept = ArrayList<TvFramePhoto>(round.size)
        round.forEachIndexed { i, old ->
            val now = pool[old.key]?.removeFirstOrNull() ?: return@forEachIndexed
            kept += now
            if (i <= at) shown++
        }
        items = fresh
        round = kept
        at = shown - 1
    }

    private fun newRound() {
        val last = round.getOrNull(at)
        var next = if (shuffle) items.shuffled(random) else TvFrameSchedule.byDate(items)
        if (shuffle && next.size > 1 && last != null && next.first().key == last.key) next = next.drop(1) + next.first()
        round = next
        at = -1
    }
}
