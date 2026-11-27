package com.gatecontrol.android.media.server

import com.gatecontrol.android.media.jellyfin.JellyfinJson.bool
import com.gatecontrol.android.media.jellyfin.JellyfinJson.int
import com.gatecontrol.android.media.jellyfin.JellyfinJson.obj
import com.gatecontrol.android.media.jellyfin.JellyfinJson.objects
import com.gatecontrol.android.media.jellyfin.JellyfinJson.str
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Videos umwandeln“ of the plugin „Medien“ (from gatecontrol-medien 1.6.0, App-API v1 `v1/convert/…`): where a file
 * stands. [NONE] not on the night's list (or failed, skipped, the original back), [QUEUED] waits for the night,
 * [RUNNING] is converted (or checked in Jellyfin), [DONE] converted: plays directly now.
 */
enum class MediaConvertState { NONE, QUEUED, RUNNING, DONE }

/** One file of `v1/convert/status` (unknown fields passed over). */
data class MediaConvertItem(
    /** The Jellyfin item id, as [MediaConvertLogic.itemId] writes it. */
    val id: String,
    val state: MediaConvertState = MediaConvertState.NONE,
    /** The place in the queue from 1 (only [MediaConvertState.QUEUED]). */
    val position: Int? = null,
    /** The estimated start, epoch ms (only [MediaConvertState.QUEUED]). */
    val startsAt: Long? = null,
    /** 0…100 (only [MediaConvertState.RUNNING]). */
    val percent: Int? = null,
    /** Someone asked for it already („Umwandeln anfragen“). */
    val requested: Boolean = false,
    /** The plugin's finding: `convert`, `hint`, `info`; null: plays everywhere or not known. */
    val level: String? = null,
)

/** `GET v1/convert/status`: whether a gateway can convert now, and the files asked for by id. */
data class MediaConvertStatus(val available: Boolean, val items: Map<String, MediaConvertItem>)

/**
 * The plugin's routes for „Videos umwandeln“ in the apps (`umwandeln-apps-vertrag`): the state of up to 100 files at
 * once, and „Umwandeln anfragen“ (only onto the list; the admin queues it, question 100 b).
 */
interface MediaConvertApi {
    /** `GET v1/convert/status?items=<id>,…` (1…100 ids). */
    suspend fun convertStatus(itemIds: List<String>): MediaServerResult<MediaConvertStatus>

    /** `POST v1/convert/request { itemId }`: the file's state afterwards (with `requested`). */
    suspend fun convertRequest(itemId: String): MediaServerResult<MediaConvertItem>
}

/** What the page says before playing a file this device does not play directly (pictures 5 to 7 of the mockup). */
sealed interface MediaConvertHint {
    /** Converted while playing; „Für heute Nacht vormerken“ is offered. */
    data object Offer : MediaConvertHint

    /** Asked for (by this person or another): the admin queues it. */
    data object Requested : MediaConvertHint

    /** Converted tonight: [position] in the queue, from about [startsAt] (epoch ms). */
    data class Queued(val position: Int?, val startsAt: Long?) : MediaConvertHint

    /** Being converted now, [percent] done (null: not known). */
    data class Running(val percent: Int?) : MediaConvertHint
}

/** The rules of the hint and the plugin's JSON. Pure, unit-tested. */
object MediaConvertLogic {
    /** At most this many ids per question (the plugin's limit). */
    const val MAX_IDS = 100

    private val HEX32 = Regex("^[0-9a-f]{32}$")

    /** [raw] as a Jellyfin item id the plugin takes (32 hex digits, lower case, without dashes); null: not one. */
    fun itemId(raw: String?): String? = raw?.trim()?.replace("-", "")?.lowercase(Locale.ROOT)?.takeIf { HEX32.matches(it) }

    fun state(text: String?): MediaConvertState = when (text) {
        "queued" -> MediaConvertState.QUEUED
        "running" -> MediaConvertState.RUNNING
        "done" -> MediaConvertState.DONE
        else -> MediaConvertState.NONE
    }

    private fun item(id: String, o: JsonObject, base: MediaConvertItem? = null): MediaConvertItem {
        val state = o.str("state")?.let(::state) ?: base?.state ?: MediaConvertState.NONE
        return MediaConvertItem(
            id = id,
            state = state,
            position = (o.int("position") ?: base?.position)?.takeIf { it > 0 && state == MediaConvertState.QUEUED },
            startsAt = (MediaServerJson.time(o.str("startsAt")) ?: base?.startsAt)?.takeIf { state == MediaConvertState.QUEUED },
            percent = (o.int("percent") ?: base?.percent)?.coerceIn(0, 100)?.takeIf { state == MediaConvertState.RUNNING },
            requested = o.bool("requested") ?: base?.requested ?: false,
            level = (o.str("level") ?: base?.level)?.takeIf { it in LEVELS },
        )
    }

    private val LEVELS = setOf("convert", "hint", "info")

    /** The answer of `v1/convert/status`: `items` (with `level`, `requested`), else `states` by id. */
    fun status(o: JsonObject): MediaConvertStatus {
        val out = LinkedHashMap<String, MediaConvertItem>()
        for (e in o.objects("items")) {
            val id = itemId(e.str("id")) ?: continue
            out[id] = item(id, e)
        }
        o.obj("states")?.entrySet()?.forEach { (key, v) ->
            val id = itemId(key) ?: return@forEach
            if (!v.isJsonObject) return@forEach
            out[id] = item(id, v.asJsonObject, out[id])
        }
        return MediaConvertStatus(available = o.bool("available") ?: false, items = out)
    }

    /** The answer of `v1/convert/request` for [id]. */
    fun requested(id: String, o: JsonObject): MediaConvertItem = item(id, o).copy(requested = true)

    /**
     * The hint for a file whose picture this device does not play directly ([problem], e.g. „H.264 10-Bit (High 10)“;
     * null: it plays, no hint), by the plugin's [item] and whether a gateway converts ([available]). Nothing for a
     * converted file, nothing without the plugin's answer; „Für heute Nacht vormerken“ only where the plugin found
     * something and a gateway can convert.
     */
    fun hint(problem: String?, item: MediaConvertItem?, available: Boolean): MediaConvertHint? {
        if (problem == null || item == null) return null
        return when (item.state) {
            MediaConvertState.DONE -> null
            MediaConvertState.QUEUED -> MediaConvertHint.Queued(item.position, item.startsAt)
            MediaConvertState.RUNNING -> MediaConvertHint.Running(item.percent)
            MediaConvertState.NONE -> when {
                item.requested -> MediaConvertHint.Requested
                available && (item.level == "convert" || item.level == "hint") -> MediaConvertHint.Offer
                else -> null
            }
        }
    }

    /**
     * The clock time of [startsAt] for „ab 01:08 Uhr“ (German: 24 hours; otherwise the locale's short time); null when
     * not known or already past (it starts any moment).
     */
    fun startClock(startsAt: Long?, now: Long, zone: java.time.ZoneId, locale: Locale): String? {
        if (startsAt == null || startsAt <= now) return null
        val f = if (locale.language == "de") java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale)
        else java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale)
        return java.time.Instant.ofEpochMilli(startsAt).atZone(zone).format(f)
    }

    /**
     * The refusals after which the plugin is not asked again for a while: no route (older plugin, 404), no right
     * „Medien“, no licence, the plugin off.
     */
    fun switchesOff(e: MediaServerError): Boolean =
        e.unavailable || e.http == 404 || e.http == 403 || e.code in setOf("no_access", "license_required", "forbidden", "not_found")
}

/**
 * The states of „Videos umwandeln“ as the pages need them: asked together (up to 100 ids in one question), kept a few
 * minutes ([TTL_MS]), never asked per line or in a loop. Without the route (plugin before 1.6.0), without the right
 * or without a licence the plugin is not asked again for [OFF_MS], and no hint shows: the pages behave as before.
 */
@Singleton
class MediaConvert internal constructor(
    private val api: MediaConvertApi,
    private val now: () -> Long,
) {
    @Inject constructor(api: MediaConvertApi) : this(api, System::currentTimeMillis)

    private data class Kept(val item: MediaConvertItem?, val available: Boolean, val at: Long)

    private val kept = HashMap<String, Kept>()
    private var offUntil = 0L
    private val lock = Mutex()

    /**
     * The files of [rawIds] (Jellyfin item ids) with whether a gateway converts; those kept less than [TTL_MS] are not
     * asked again. Null: not to be shown (no answer, the route missing, no right).
     */
    suspend fun states(rawIds: List<String>): MediaConvertStatus? = lock.withLock {
        val ids = rawIds.mapNotNull(MediaConvertLogic::itemId).distinct()
        if (ids.isEmpty() || now() < offUntil) return@withLock null
        val t = now()
        val missing = ids.filter { id -> kept[id]?.let { t - it.at in 0 until TTL_MS } != true }
        var reachable = missing.isEmpty()
        for (chunk in missing.chunked(MediaConvertLogic.MAX_IDS)) {
            when (val r = api.convertStatus(chunk)) {
                is MediaServerResult.Ok -> {
                    reachable = true
                    for (id in chunk) kept[id] = Kept(r.value.items[id], r.value.available, t)
                }
                is MediaServerResult.Refused -> {
                    if (MediaConvertLogic.switchesOff(r.error)) {
                        offUntil = t + OFF_MS
                        kept.clear()
                        return@withLock null
                    }
                }
            }
        }
        val known = ids.mapNotNull { id -> kept[id]?.let { id to it } }
        if (known.isEmpty() && !reachable) return@withLock null
        if (kept.size > MAX_KEPT) kept.entries.sortedBy { it.value.at }.take(kept.size - MAX_KEPT).forEach { kept.remove(it.key) }
        MediaConvertStatus(available = known.any { it.second.available }, items = known.mapNotNull { (id, k) -> k.item?.let { id to it } }.toMap())
    }

    /** The hint for [rawId] whose picture this device does not play ([problem]); null: none (also without the plugin). */
    suspend fun hint(rawId: String, problem: String?): MediaConvertHint? {
        if (problem == null) return null
        val id = MediaConvertLogic.itemId(rawId) ?: return null
        val s = states(listOf(id)) ?: return null
        return MediaConvertLogic.hint(problem, s.items[id], s.available)
    }

    /** Asked ahead for the files of a list (a season's episodes), in one question; nothing is shown from it yet. */
    suspend fun prefetch(rawIds: List<String>) {
        states(rawIds)
    }

    /**
     * „Für heute Nacht vormerken“: [rawId] onto the plugin's list. The new hint ([MediaConvertHint.Requested] or the
     * queue), null when it was refused (also too many questions: 30 an hour).
     */
    suspend fun request(rawId: String): MediaConvertHint? {
        val id = MediaConvertLogic.itemId(rawId) ?: return null
        val r = api.convertRequest(id)
        val item = (r as? MediaServerResult.Ok)?.value ?: return null
        lock.withLock {
            val before = kept[id]
            kept[id] = Kept(item.copy(level = item.level ?: before?.item?.level), before?.available ?: true, now())
        }
        return MediaConvertLogic.hint("-", item, available = true) ?: MediaConvertHint.Requested
    }

    companion object {
        /** A state is kept this long (the contract: a few minutes at most). */
        const val TTL_MS = 3 * 60_000L

        /** Without the route, the right or the licence: not asked again for this long. */
        const val OFF_MS = 30 * 60_000L

        private const val MAX_KEPT = 500
    }
}
