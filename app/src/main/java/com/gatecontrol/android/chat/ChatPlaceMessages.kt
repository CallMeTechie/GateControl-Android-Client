package com.gatecontrol.android.chat

import com.gatecontrol.android.chat.ChatJson.arr
import com.gatecontrol.android.chat.ChatJson.double
import com.gatecontrol.android.chat.ChatJson.flag
import com.gatecontrol.android.chat.ChatJson.id
import com.gatecontrol.android.chat.ChatJson.int
import com.gatecontrol.android.chat.ChatJson.obj
import com.gatecontrol.android.chat.ChatJson.objOrNull
import com.gatecontrol.android.chat.ChatJson.str
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDateTime

/*
 * Messenger 1.27 (`features.placeMessages`): Ortsnachrichten — „Wenn ich Zuhause erreiche, sende … an Kim“. The
 * messenger keeps them (`GET/POST/PUT/DELETE place-messages`), the phone watches the place itself (a geofence of its
 * own) and sends the finished message with `POST place-messages/:id/fire`. The server never learns a position. The
 * time window and „Vor dem Senden fragen“ are the phone's business (the server does not know the time zone). Pure,
 * unit-tested; the runner and the screens act on what these say.
 */

/** The place of an Ortsnachricht: a Familie place ([familieId]) or one picked on the map. */
data class ChatPmPlace(val familieId: String?, val name: String, val lat: Double, val lon: Double, val radiusM: Int)

/** Only at these times: [days] ISO (Mo = 1 … So = 7), [from]–[to] as `HH:MM` (over midnight when [to] is earlier). */
data class ChatPmWindow(val days: List<Int>, val from: String, val to: String)

/** Whom it goes to: the chat's title, its person, and the way right now (`target.reply`, only with `way: auto`). */
data class ChatPmTarget(val title: String?, val personId: Long?, val reply: ChatPersonReply?)

/** One Ortsnachricht. */
data class ChatPlaceMessage(
    val id: Long,
    val deviceId: String,
    val trigger: String,
    val place: ChatPmPlace,
    val text: String,
    val conversationId: Long,
    val way: String,
    val repeat: String,
    val window: ChatPmWindow?,
    val ask: Boolean,
    val enabled: Boolean,
    val lastFiredAt: String?,
    val doneAt: String?,
    val createdAt: String? = null,
    val target: ChatPmTarget? = null,
) {
    /** Switched on and not done (`once` already sent): it counts for the limit and is watched. */
    val active: Boolean get() = enabled && doneAt == null
}

/** `limits` of `GET place-messages`. */
data class ChatPmLimits(
    val activeMax: Int = 10,
    val textMax: Int = 1000,
    val nameMax: Int = 80,
    val radiusMinM: Int = 100,
    val radiusMaxM: Int = 2000,
    val radiusDefaultM: Int = 150,
    val everyS: Int = 3600,
    val lateS: Int = 1800,
)

data class ChatPmList(val items: List<ChatPlaceMessage>, val limits: ChatPmLimits)

/** `POST place-messages/:id/fire`: the Ortsnachricht as it stands now and the message that went out. */
data class ChatPmFired(val placeMessage: ChatPlaceMessage?, val message: ChatMessage?, val duplicate: Boolean, val way: String?, val conversationId: Long?)

/** `message.placeMessage` (only in the sender's own view): „Ortsnachricht · Zuhause“. */
data class ChatPlaceMark(val name: String, val trigger: String)

/** What the form edits (a new one or a change). */
data class ChatPmDraft(
    val trigger: String = ChatPlaceMessages.ENTER,
    val place: ChatPmPlace? = null,
    val text: String = "",
    val conversationId: Long? = null,
    val way: String = ChatPeople.REPLY_AUTO,
    val repeat: String = ChatPlaceMessages.ONCE,
    val window: ChatPmWindow? = null,
    val ask: Boolean = true,
    val enabled: Boolean = true,
)

object ChatPlaceMessages {
    /** The live event (lower case, as GateControl takes event types): created | updated | deleted | fired. */
    const val EVENT = "place_messages.changed"

    const val ENTER = "enter"
    const val EXIT = "exit"
    const val ONCE = "once"
    const val ALWAYS = "always"

    /** The question „Jetzt senden?“ goes away after 15 minutes without an answer. */
    const val ASK_TTL_MS = 15 * 60_000L

    /** At most once an hour per Ortsnachricht (`too_soon` on the server). */
    const val EVERY_MS = 60 * 60_000L

    /** Without a network the message waits at most 30 minutes (`expired` on the server), then it is dropped. */
    const val LATE_MS = 30 * 60_000L

    const val RADIUS_MIN_M = 100
    const val RADIUS_MAX_M = 2000
    const val RADIUS_DEFAULT_M = 150
    const val TEXT_MAX = 1000
    const val NAME_MAX = 80

    /** The codes of `fire` after which nothing is tried again. */
    val FINAL_CODES: Set<String> = setOf("too_soon", "closed", "expired", "not_allowed", "not_found", "readonly", "bad_request", "invalid", "blocked")

    private val TRIGGERS = setOf(ENTER, EXIT)
    private val REPEATS = setOf(ONCE, ALWAYS)
    private val WAY_RE = Regex("^[a-z][a-z0-9_-]{0,31}$")
    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    /** Familie's place ids as `GET familie/places` gives them: `home`, `place:<id>` (a rule place), a number. */
    private val FAMILIE_ID_RE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val DEVICE_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private const val MAX_ITEMS = 100

    fun on(features: ChatFeatures?): Boolean = features?.placeMessages == true

    // ─── parsing ────────────────────────────────────────────────────

    fun place(o: JsonObject?): ChatPmPlace? {
        if (o == null) return null
        val lat = o.double("lat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.double("lon")?.takeIf { it in -180.0..180.0 } ?: return null
        val fid = o.get("familieId")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
            when {
                p.isNumber -> ChatJson.idOf(p)?.toString()
                p.isString -> p.asString.takeIf { FAMILIE_ID_RE.matches(it) }
                else -> null
            }
        }
        return ChatPmPlace(
            familieId = fid,
            name = ChatJson.line(o.str("name"), NAME_MAX) ?: return null,
            lat = lat,
            lon = lon,
            radiusM = clampRadius(o.int("radiusM")),
        )
    }

    fun window(o: JsonObject?): ChatPmWindow? {
        if (o == null) return null
        val days = o.arr("days")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 1..7 } }
            ?.distinct()?.sorted().orEmpty()
        val from = o.str("from")?.takeIf { HHMM_RE.matches(it) } ?: return null
        val to = o.str("to")?.takeIf { HHMM_RE.matches(it) } ?: return null
        if (days.isEmpty()) return null
        return ChatPmWindow(days, from, to)
    }

    fun item(o: JsonObject?): ChatPlaceMessage? {
        if (o == null) return null
        val target = o.obj("target")?.let { t ->
            ChatPmTarget(
                title = ChatJson.line(t.str("title"), 120),
                personId = t.id("personId"),
                reply = t.obj("reply")?.let { r ->
                    val w = r.str("way")?.takeIf { WAY_RE.matches(it) } ?: return@let null
                    val cid = r.id("conversationId") ?: return@let null
                    ChatPersonReply(w, ChatJson.line(r.str("label"), 40) ?: ChatPeople.labelOf(w), cid, fallback = r.flag("fallback") == true)
                },
            )
        }
        return ChatPlaceMessage(
            id = o.id("id") ?: return null,
            deviceId = o.str("deviceId")?.takeIf { it.length in 1..64 }.orEmpty(),
            trigger = o.str("trigger")?.takeIf { it in TRIGGERS } ?: return null,
            place = place(o.obj("place")) ?: return null,
            text = ChatJson.text(o.str("text"), TEXT_MAX) ?: return null,
            conversationId = o.id("conversationId") ?: return null,
            way = o.str("way")?.takeIf { WAY_RE.matches(it) } ?: ChatPeople.REPLY_AUTO,
            repeat = o.str("repeat")?.takeIf { it in REPEATS } ?: ONCE,
            window = window(o.obj("window")),
            ask = o.flag("ask") ?: true,
            enabled = o.flag("enabled") ?: true,
            lastFiredAt = ChatJson.time(o.str("lastFiredAt")),
            doneAt = ChatJson.time(o.str("doneAt")),
            createdAt = ChatJson.time(o.str("createdAt")),
            target = target,
        )
    }

    fun limits(o: JsonObject?): ChatPmLimits {
        if (o == null) return ChatPmLimits()
        val d = ChatPmLimits()
        return ChatPmLimits(
            activeMax = o.int("activeMax")?.coerceIn(0, 100) ?: d.activeMax,
            textMax = o.int("textMax")?.coerceIn(1, 4000) ?: d.textMax,
            nameMax = o.int("nameMax")?.coerceIn(1, 200) ?: d.nameMax,
            radiusMinM = o.int("radiusMinM")?.coerceIn(10, 10_000) ?: d.radiusMinM,
            radiusMaxM = o.int("radiusMaxM")?.coerceIn(10, 10_000) ?: d.radiusMaxM,
            radiusDefaultM = o.int("radiusDefaultM")?.coerceIn(10, 10_000) ?: d.radiusDefaultM,
            everyS = o.int("everyS")?.coerceAtLeast(0) ?: d.everyS,
            lateS = o.int("lateS")?.coerceAtLeast(0) ?: d.lateS,
        )
    }

    fun list(o: JsonObject): ChatPmList? {
        val arr = o.arr("placeMessages") ?: return null
        return ChatPmList(arr.mapNotNull { item(it.objOrNull()) }.distinctBy { it.id }.take(MAX_ITEMS), limits(o.obj("limits")))
    }

    fun one(o: JsonObject): ChatPlaceMessage? = item(o.obj("placeMessage"))

    fun fired(o: JsonObject): ChatPmFired? {
        val pm = item(o.obj("placeMessage"))
        val m = ChatJson.message(o.obj("message"))
        if (pm == null && m == null) return null
        return ChatPmFired(pm, m, o.flag("duplicate") == true, o.str("way")?.takeIf { WAY_RE.matches(it) }, o.id("conversationId"))
    }

    /** `placeMessage` of a message; anything else → none. */
    fun mark(o: JsonObject?): ChatPlaceMark? {
        if (o == null) return null
        val name = ChatJson.line(o.str("name"), NAME_MAX) ?: return null
        return ChatPlaceMark(name, o.str("trigger")?.takeIf { it in TRIGGERS } ?: ENTER)
    }

    fun writeMark(m: ChatPlaceMark): JsonObject = JsonObject().apply {
        addProperty("name", m.name)
        addProperty("trigger", m.trigger)
    }

    // ─── writing ────────────────────────────────────────────────────

    fun placeJson(p: ChatPmPlace): JsonObject = JsonObject().apply {
        p.familieId?.let { f -> f.toLongOrNull()?.let { addProperty("familieId", it) } ?: addProperty("familieId", f) }
        addProperty("name", p.name)
        addProperty("lat", p.lat)
        addProperty("lon", p.lon)
        addProperty("radiusM", clampRadius(p.radiusM))
    }

    fun windowJson(w: ChatPmWindow?): com.google.gson.JsonElement = w?.let {
        JsonObject().apply {
            add("days", JsonArray().apply { it.days.forEach { d -> add(d) } })
            addProperty("from", it.from)
            addProperty("to", it.to)
        }
    } ?: JsonNull.INSTANCE

    /** The body of `POST place-messages` (and of a change: `PUT place-messages/:id`, every field). */
    fun body(d: ChatPmDraft, deviceId: String): JsonObject? {
        val place = d.place ?: return null
        val cid = d.conversationId ?: return null
        val text = clipText(d.text.trim()).trim().takeIf { it.isNotEmpty() } ?: return null
        if (!windowOk(d.window)) return null
        return JsonObject().apply {
            addProperty("deviceId", deviceId)
            addProperty("trigger", d.trigger)
            add("place", placeJson(place))
            addProperty("text", text)
            addProperty("conversationId", cid)
            addProperty("way", d.way)
            addProperty("repeat", d.repeat)
            add("window", windowJson(d.window))
            addProperty("ask", d.ask)
            addProperty("enabled", d.enabled)
        }
    }

    fun fireBody(at: String, deviceId: String): JsonObject = JsonObject().apply {
        addProperty("at", at)
        addProperty("deviceId", deviceId)
    }

    /** The cache on the phone: the same shape as the server's. */
    fun write(p: ChatPlaceMessage): JsonObject = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("deviceId", p.deviceId)
        addProperty("trigger", p.trigger)
        add("place", placeJson(p.place))
        addProperty("text", p.text)
        addProperty("conversationId", p.conversationId)
        addProperty("way", p.way)
        addProperty("repeat", p.repeat)
        add("window", windowJson(p.window))
        addProperty("ask", p.ask)
        addProperty("enabled", p.enabled)
        p.lastFiredAt?.let { addProperty("lastFiredAt", it) }
        p.doneAt?.let { addProperty("doneAt", it) }
        p.createdAt?.let { addProperty("createdAt", it) }
        p.target?.let { t ->
            add("target", JsonObject().apply {
                t.title?.let { addProperty("title", it) }
                t.personId?.let { addProperty("personId", it) }
                t.reply?.let { r ->
                    add("reply", JsonObject().apply {
                        addProperty("way", r.way)
                        addProperty("label", r.label)
                        addProperty("conversationId", r.conversationId)
                    })
                }
            })
        }
    }

    fun writeList(l: ChatPmList): JsonObject = JsonObject().apply {
        add("placeMessages", JsonArray().apply { l.items.forEach { add(write(it)) } })
        add("limits", JsonObject().apply {
            addProperty("activeMax", l.limits.activeMax)
            addProperty("textMax", l.limits.textMax)
            addProperty("nameMax", l.limits.nameMax)
            addProperty("radiusMinM", l.limits.radiusMinM)
            addProperty("radiusMaxM", l.limits.radiusMaxM)
            addProperty("radiusDefaultM", l.limits.radiusDefaultM)
            addProperty("everyS", l.limits.everyS)
            addProperty("lateS", l.limits.lateS)
        })
    }

    fun draftOf(p: ChatPlaceMessage): ChatPmDraft = ChatPmDraft(p.trigger, p.place, p.text, p.conversationId, p.way, p.repeat, p.window, p.ask, p.enabled)

    // ─── rules on the phone ─────────────────────────────────────────

    /**
     * At most [max] UTF-16 units, as the server counts (`text.length`, an emoji counts two): never cut between the two
     * halves of an emoji.
     */
    fun clipText(s: String, max: Int = TEXT_MAX): String {
        if (s.length <= max) return s
        val end = if (max > 0 && Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    /** A window the server takes: days 1–7 (at least one), `HH:MM`, and from ≠ to (the same time is refused). */
    fun windowOk(w: ChatPmWindow?): Boolean =
        w == null || (w.days.isNotEmpty() && w.days.all { it in 1..7 } && HHMM_RE.matches(w.from) && HHMM_RE.matches(w.to) && w.from != w.to)

    fun clampRadius(m: Int?): Int = (m ?: RADIUS_DEFAULT_M).coerceIn(RADIUS_MIN_M, RADIUS_MAX_M)

    /** A Familie place for the form: its name, point and radius (kept within 100–2000 m), its id as `familieId`. */
    fun fromFamilie(p: ChatPlace): ChatPmPlace? {
        val lat = p.lat ?: return null
        val lon = p.lon ?: return null
        return ChatPmPlace(p.id, p.name.take(NAME_MAX), lat, lon, clampRadius(p.radiusM))
    }

    private fun minutes(hhmm: String): Int? = HHMM_RE.matchEntire(hhmm)?.let { m -> m.groupValues[1].toInt() * 60 + hhmm.substring(3).toInt() }

    /**
     * Within the window at the phone's local [now]: no window → always. [ChatPmWindow.from] up to (not including)
     * [ChatPmWindow.to] on one of the days; a window over midnight (22:00–06:00) belongs to the day it starts on.
     * The same from and to: the whole day.
     */
    fun inWindow(w: ChatPmWindow?, now: LocalDateTime): Boolean {
        if (w == null) return true
        val from = minutes(w.from) ?: return true
        val to = minutes(w.to) ?: return true
        val t = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = now.dayOfWeek.minus(1).value
        return when {
            from == to -> today in w.days
            from < to -> today in w.days && t >= from && t < to
            t >= from -> today in w.days
            t < to -> yesterday in w.days
            else -> false
        }
    }

    /** Less than an hour since it last went out ([lastMs]: the newer of the server's `lastFiredAt` and the phone's). */
    fun tooSoon(lastMs: Long?, nowMs: Long): Boolean = lastMs != null && nowMs - lastMs in 0 until EVERY_MS

    /** The trigger at [atMs] lies more than 30 minutes back: the message is no longer sent. */
    fun late(atMs: Long, nowMs: Long): Boolean = nowMs - atMs > LATE_MS

    /** The question „Jetzt senden?“ asked at [askedMs] is no longer valid. */
    fun askExpired(askedMs: Long, nowMs: Long): Boolean = nowMs - askedMs > ASK_TTL_MS

    fun ms(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * What this phone watches: the active Ortsnachrichten of [deviceId], at most [budget] (the share of Android's 100
     * geofences, [com.gatecontrol.android.familie.places.GeofenceBudget.placeMessages]) — the oldest first, so a new
     * one never pushes out one that already runs.
     */
    fun watched(items: List<ChatPlaceMessage>, deviceId: String, budget: Int): List<ChatPlaceMessage> =
        items.filter { it.active && it.deviceId == deviceId }
            .sortedWith(compareBy<ChatPlaceMessage> { it.createdAt ?: "" }.thenBy { it.id })
            .take(budget.coerceAtLeast(0))

    /**
     * Which of [items] fire now for the fences [ids] and [transition]: the phone's own, active, the same trigger,
     * within the window at [now] and not within the hour since the last time ([localFired]: what the phone itself
     * sent or asked, by id).
     */
    fun due(
        items: List<ChatPlaceMessage>,
        ids: Collection<Long>,
        transition: String,
        deviceId: String,
        now: LocalDateTime,
        nowMs: Long,
        localFired: Map<Long, Long>,
    ): List<ChatPlaceMessage> = items.filter { p ->
        p.id in ids && p.active && p.deviceId == deviceId && p.trigger == transition && inWindow(p.window, now) &&
            !tooSoon(listOfNotNull(ms(p.lastFiredAt), localFired[p.id]).maxOrNull(), nowMs)
    }

    /** How many are active (for „Höchstens 10 aktive“). */
    fun activeCount(items: List<ChatPlaceMessage>): Int = items.count { it.active }

    /** Weekdays (ISO) of a window as one run where they follow each other: [1,2,3,4,5] → 1 to 5; else null. */
    fun dayRun(days: List<Int>): Pair<Int, Int>? {
        val d = days.distinct().sorted()
        if (d.size < 2) return null
        return if (d.zipWithNext().all { (a, b) -> b == a + 1 }) d.first() to d.last() else null
    }

    /** The geofence request id of [id] and back. */
    private const val REQUEST_PREFIX = "pm:"

    fun requestId(id: Long): String = "$REQUEST_PREFIX$id"

    fun idOf(requestId: String): Long? = requestId.takeIf { it.startsWith(REQUEST_PREFIX) }?.removePrefix(REQUEST_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

    /** A device id this phone sends (stable per installation): `android-` and 16 hex digits. */
    fun isDeviceId(s: String?): Boolean = s != null && DEVICE_RE.matches(s)
}
