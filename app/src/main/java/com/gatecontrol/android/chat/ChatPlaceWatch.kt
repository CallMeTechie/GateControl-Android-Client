package com.gatecontrol.android.chat

import com.gatecontrol.android.familie.locate.FamiliePermissions
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Messenger 1.27: what the phone knows about each watched place of an Ortsnachricht and what it did — pure, unit-tested.
 *
 * Inside or outside: Android reports a geofence's state when it is added (`INITIAL_TRIGGER_ENTER | _EXIT`) and every
 * transition after. The phone keeps the last side of each fence, so adding the fences again (each start of the app, a
 * boot, an update) is harmless: the report on adding is an arrival only when the phone was outside before — an arrival
 * the old fence had not reported yet is not lost — and nothing when it was already there. A fence that is new (a new
 * Ortsnachricht, a changed place) only learns its side: being at the place while saving it sends nothing.
 *
 * The time window: an arrival outside it is noted; at the window's next start the phone checks once whether it is still
 * at the place and then asks (or sends) — „Mo–Fr 06:00–09:00“ also works for someone who got there at 05:50.
 *
 * The log: the last events of each Ortsnachricht on this phone (detected, skipped with the reason, asked, sent, failed)
 * for the status in the list, the chat info and the form.
 */

/** The side of a place the phone was last on; [side] null: added, not reported yet (a new fence). */
data class ChatPmFence(val sig: String, val side: String?, val sinceMs: Long)

/** One event of an Ortsnachricht on this phone. */
data class ChatPmEvent(val atMs: Long, val kind: String, val detail: String? = null)

/** Whether an Ortsnachricht is watched on this phone right now (the status line). */
enum class ChatPmHealth {
    WATCHING,
    OFF,
    OTHER_DEVICE,
    NO_POINT,
    NO_PLAY_SERVICES,
    NEED_LOCATION,
    NEED_BACKGROUND,
    LOCATION_OFF,
    NOTIFICATIONS_OFF,
    NOT_REGISTERED,
}

/** What the screens show: the phone's state for all Ortsnachrichten. */
data class ChatPmDiag(
    val readiness: FamiliePermissions.GeofenceReadiness = FamiliePermissions.GeofenceReadiness.READY,
    val locationOn: Boolean = true,
    val notificationsOn: Boolean = true,
    val registered: Set<Long> = emptySet(),
    val lost: Boolean = false,
    val log: Map<Long, List<ChatPmEvent>> = emptyMap(),
)

object ChatPlaceWatch {
    const val IN = "in"
    const val OUT = "out"

    // the kinds of [ChatPmEvent]
    const val DETECTED = "detected"
    const val LEARNED = "learned"
    const val SKIPPED = "skipped"
    const val ASKED = "asked"
    const val CONFIRMED = "confirmed"
    const val DECLINED = "declined"
    const val SENT = "sent"
    const val FAILED = "failed"
    const val DROPPED = "dropped"
    const val BLOCKED = "blocked"
    const val UNAVAILABLE = "unavailable"
    const val CAUGHT_UP = "caught_up"

    // the reasons of [SKIPPED]
    const val WHY_WINDOW = "window"
    const val WHY_TOO_SOON = "too_soon"
    const val WHY_INACTIVE = "inactive"
    const val WHY_OTHER_DEVICE = "other_device"
    const val WHY_UNKNOWN = "unknown"
    const val WHY_ASK_EXPIRED = "ask_expired"

    /** The last events kept per Ortsnachricht. */
    const val LOG_MAX = 8

    /**
     * The smallest radius the phone watches. The server takes 100 m, but Google asks for at least 100–150 m and inside a
     * building the position is often only good to 100 m or worse: a 100 m circle may never report the arrival. The
     * saved radius stays as it is; only the geofence is at least this large.
     */
    const val FENCE_MIN_M = 150

    fun fenceRadius(m: Int): Int = ChatPlaceMessages.clampRadius(m).coerceAtLeast(FENCE_MIN_M)

    /** What makes a fence the same one: the trigger, the point and the radius. */
    fun signature(p: ChatPlaceMessage): String =
        "${p.trigger}|${"%.6f".format(java.util.Locale.ROOT, p.place.lat)}|${"%.6f".format(java.util.Locale.ROOT, p.place.lon)}|${fenceRadius(p.place.radiusM)}"

    /**
     * After adding [fences]: the kept side of each that is the same fence as before; a new or changed one starts unknown
     * (it only learns its side); those no longer watched go.
     */
    fun afterRegister(prev: Map<Long, ChatPmFence>, fences: List<ChatPlaceMessage>, nowMs: Long): Map<Long, ChatPmFence> =
        fences.associate { p ->
            val sig = signature(p)
            val old = prev[p.id]?.takeIf { it.sig == sig }
            p.id to (old ?: ChatPmFence(sig, null, nowMs))
        }

    /**
     * Android reports [side] for a fence whose state was [state]: the new state and whether this is a transition (an
     * arrival or a departure to act on). Unknown fence (none kept — added by an older version of the app with
     * transitions only): a transition, as it was then.
     */
    fun observe(state: ChatPmFence?, side: String, nowMs: Long): Pair<ChatPmFence?, Boolean> = when {
        state == null -> null to true
        state.side == null -> state.copy(side = side, sinceMs = nowMs) to false
        state.side == side -> state to false
        else -> state.copy(side = side, sinceMs = nowMs) to true
    }

    fun sideOf(transition: String): String = if (transition == ChatPlaceMessages.EXIT) OUT else IN

    /**
     * Why [p] does not go out now for [transition], or null: it is due. The phone's own, active, the same trigger, within
     * the window at [now] and not within the hour since the last time.
     */
    fun skipReason(p: ChatPlaceMessage, transition: String, deviceId: String, now: LocalDateTime, nowMs: Long, localFired: Map<Long, Long>): String? = when {
        p.trigger != transition -> "trigger"
        !p.active -> WHY_INACTIVE
        p.deviceId != deviceId -> WHY_OTHER_DEVICE
        !ChatPlaceMessages.inWindow(p.window, now) -> WHY_WINDOW
        ChatPlaceMessages.tooSoon(lastFired(p, localFired), nowMs) -> WHY_TOO_SOON
        else -> null
    }

    fun lastFired(p: ChatPlaceMessage, localFired: Map<Long, Long>): Long? = listOfNotNull(ChatPlaceMessages.ms(p.lastFiredAt), localFired[p.id]).maxOrNull()

    private fun minutes(hhmm: String): Int? = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$").matchEntire(hhmm)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** The next start of [w] after [now] (strictly later), or null (no window, not valid). */
    fun nextWindowStart(w: ChatPmWindow?, now: LocalDateTime): LocalDateTime? {
        if (w == null || w.days.isEmpty()) return null
        val from = minutes(w.from) ?: return null
        for (d in 0..7) {
            val day = now.toLocalDate().plusDays(d.toLong())
            val start = day.atStartOfDay().plusMinutes(from.toLong())
            if (start.isAfter(now) && day.dayOfWeek.value in w.days) return start
        }
        return null
    }

    /** When the window that holds [now] started, or null (outside the window, or none). */
    fun windowStartedAt(w: ChatPmWindow?, now: LocalDateTime): LocalDateTime? {
        if (w == null || !ChatPlaceMessages.inWindow(w, now)) return null
        val from = minutes(w.from) ?: return null
        val today = now.toLocalDate().atStartOfDay().plusMinutes(from.toLong())
        // a window over midnight that started yesterday
        return if (today.isAfter(now)) today.minusDays(1) else today
    }

    /**
     * At a window's start: the Ortsnachrichten „Beim Ankommen“ with a window whose place the phone is at (since before)
     * and that did not go out in this window yet — once, as if the phone had arrived now.
     */
    fun catchUp(
        items: List<ChatPlaceMessage>,
        fences: Map<Long, ChatPmFence>,
        deviceId: String,
        now: LocalDateTime,
        nowMs: Long,
        localFired: Map<Long, Long>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<ChatPlaceMessage> = items.filter { p ->
        val f = fences[p.id] ?: return@filter false
        val started = windowStartedAt(p.window, now) ?: return@filter false
        val startedMs = started.atZone(zone).toInstant().toEpochMilli()
        f.side == IN && skipReason(p, ChatPlaceMessages.ENTER, deviceId, now, nowMs, localFired) == null &&
            (lastFired(p, localFired) ?: Long.MIN_VALUE) < startedMs
    }

    /** When to look next ([catchUp]): the earliest window start of an Ortsnachricht whose place the phone is at. */
    fun nextCheck(items: List<ChatPlaceMessage>, fences: Map<Long, ChatPmFence>, deviceId: String, now: LocalDateTime): LocalDateTime? =
        items.filter { p -> p.active && p.deviceId == deviceId && p.trigger == ChatPlaceMessages.ENTER && p.window != null && fences[p.id]?.side == IN }
            .mapNotNull { nextWindowStart(it.window, now) }
            .minOrNull()

    // ─── the status ─────────────────────────────────────────────────

    /** Whether [p] is watched on this phone: the first thing in the way, else [ChatPmHealth.WATCHING]. */
    fun health(p: ChatPlaceMessage, deviceId: String, d: ChatPmDiag): ChatPmHealth = when {
        !p.active -> ChatPmHealth.OFF
        p.deviceId != deviceId -> ChatPmHealth.OTHER_DEVICE
        !ChatPlaceMessages.hasPoint(p.place) -> ChatPmHealth.NO_POINT
        d.readiness == FamiliePermissions.GeofenceReadiness.UNAVAILABLE -> ChatPmHealth.NO_PLAY_SERVICES
        d.readiness == FamiliePermissions.GeofenceReadiness.NEED_LOCATION -> ChatPmHealth.NEED_LOCATION
        d.readiness == FamiliePermissions.GeofenceReadiness.NEED_BACKGROUND -> ChatPmHealth.NEED_BACKGROUND
        !d.locationOn || d.lost -> ChatPmHealth.LOCATION_OFF
        p.ask && !d.notificationsOn -> ChatPmHealth.NOTIFICATIONS_OFF
        p.id !in d.registered -> ChatPmHealth.NOT_REGISTERED
        else -> ChatPmHealth.WATCHING
    }

    /**
     * The newest event worth a line („Zuletzt …“): the phone's log, and the server's `lastFiredAt` when that is newer
     * (sent from another phone, or the log was cleared). Learning the side is not shown.
     */
    fun lastEvent(p: ChatPlaceMessage, log: List<ChatPmEvent>): ChatPmEvent? {
        val local = log.filter { it.kind != LEARNED && it.kind != CONFIRMED }.maxByOrNull { it.atMs }
        val server = ChatPlaceMessages.ms(p.lastFiredAt)?.let { ChatPmEvent(it, SENT) }
        return listOfNotNull(local, server).maxByOrNull { it.atMs }
    }

    // ─── storage ────────────────────────────────────────────────────

    fun append(log: List<ChatPmEvent>, e: ChatPmEvent): List<ChatPmEvent> = (log + e).sortedBy { it.atMs }.takeLast(LOG_MAX)

    fun writeLog(m: Map<Long, List<ChatPmEvent>>): JsonObject = JsonObject().apply {
        m.forEach { (id, events) ->
            add(id.toString(), JsonArray().apply {
                events.forEach { e -> add(JsonObject().apply { addProperty("at", e.atMs); addProperty("k", e.kind); e.detail?.let { addProperty("d", it) } }) }
            })
        }
    }

    fun readLog(o: JsonObject?): Map<Long, List<ChatPmEvent>> {
        if (o == null) return emptyMap()
        return o.entrySet().mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val events = runCatching {
                v.asJsonArray.mapNotNull { e ->
                    val eo = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val at = eo.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
                    val kind = eo.get("k")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                    ChatPmEvent(at, kind, eo.get("d")?.takeIf { it.isJsonPrimitive }?.asString)
                }
            }.getOrNull() ?: return@mapNotNull null
            id to events.takeLast(LOG_MAX)
        }.toMap()
    }

    fun writeFences(m: Map<Long, ChatPmFence>): JsonObject = JsonObject().apply {
        m.forEach { (id, f) ->
            add(id.toString(), JsonObject().apply { addProperty("sig", f.sig); f.side?.let { addProperty("side", it) }; addProperty("since", f.sinceMs) })
        }
    }

    fun readFences(o: JsonObject?): Map<Long, ChatPmFence> {
        if (o == null) return emptyMap()
        return o.entrySet().mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val f = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val sig = f.get("sig")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val side = f.get("side")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it == IN || it == OUT }
            id to ChatPmFence(sig, side, f.get("since")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L)
        }.toMap()
    }
}
