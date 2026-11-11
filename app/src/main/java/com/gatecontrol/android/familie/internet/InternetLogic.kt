package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.presence.PresenceState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure decisions of „Internet zu Hause“ (unit-tested): the state of a card, its buttons and the dialogs' times. */
object InternetLogic {

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")

    /** Choices of the dialogs (minutes). */
    val PAUSE_MINUTES = listOf(30, 60, 120)
    val EXTEND_MINUTES = listOf(15, 30, 60)

    /** "+30 Min." on a running pause or extra time. */
    const val ADD_MINUTES = 30

    fun epoch(iso: String?): Long? = iso?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(it.replace(' ', 'T') + if (it.endsWith("Z")) "" else "Z").toEpochMilli() }.getOrNull()
    }

    /** The family's zone, else the device's ([fallback]). */
    fun zone(timezone: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback

    /** `18:30` of [iso] in [zone]; null when it is no time. */
    fun time(iso: String?, zone: ZoneId): String? = epoch(iso)?.let { time(it, zone) }

    fun time(epochMs: Long, zone: ZoneId): String = HHMM.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun running(until: String?, nowMs: Long): Boolean = epoch(until)?.let { it > nowMs } == true

    /**
     * The state of [c] at [nowMs] (the server's clock): a running pause,
     * else a running extra time, else an error, else filtered, else not
     * filtered — the order of the portal's card.
     */
    fun state(c: InternetChild, nowMs: Long): InternetState = when {
        running(c.paused, nowMs) -> InternetState.PAUSED
        running(c.extended, nowMs) -> InternetState.EXTENDED
        c.error != null -> InternetState.ERROR
        c.filtered -> InternetState.ON
        else -> InternetState.UNFILTERED
    }

    /** The buttons of a card; none without [manage] (a child) and where a pause would do nothing. */
    enum class Button { PAUSE, EXTEND, RESUME, ADD_PAUSE, ADD_EXTRA, END_EXTRA }

    fun buttons(state: InternetState, manage: Boolean): List<Button> = if (!manage) emptyList() else when (state) {
        InternetState.PAUSED -> listOf(Button.RESUME, Button.ADD_PAUSE)
        InternetState.EXTENDED -> listOf(Button.PAUSE, Button.ADD_EXTRA, Button.END_EXTRA)
        InternetState.ON -> listOf(Button.PAUSE, Button.EXTEND)
        InternetState.ERROR, InternetState.UNFILTERED -> emptyList()
    }

    /** Minutes left until [until], rounded up; 0 when over. */
    fun minutesLeft(until: String?, nowMs: Long): Int {
        val end = epoch(until) ?: return 0
        return (((end - nowMs) + 59_999) / 60_000).coerceAtLeast(0).toInt()
    }

    /** The time the next pause needs to tick over: the nearest end of a pause or extra time after [nowMs]. */
    fun nextChangeMs(children: List<InternetChild>, nowMs: Long): Long? =
        children.flatMap { listOfNotNull(epoch(it.paused), epoch(it.extended)) }.filter { it > nowMs }.minOrNull()

    /** home | away of a child that shares; null: nothing to show (not shared, unknown, module off). */
    fun shownPresence(p: InternetPresence?): InternetPresence? =
        p?.takeIf { it.state == PresenceState.HOME || it.state == PresenceState.AWAY }

    /** `HH:MM` or null. */
    fun hhmm(value: String?): String? = value?.trim()?.take(5)?.takeIf { HHMM_RE.matches(it) }

    /**
     * "bis Uhrzeit": the next [time] (`HH:MM`) lies tomorrow when it is not
     * after [nowMs] today in [zone] — the plugin does the same.
     */
    fun untilIsTomorrow(time: String, nowMs: Long, zone: ZoneId): Boolean {
        val t = runCatching { LocalTime.parse(time) }.getOrNull() ?: return false
        return !t.isAfter(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime().withSecond(0).withNano(0))
    }

    /** The time of day the "bis Uhrzeit" field starts with: the next full hour, one hour on (17:52 → 19:00). */
    fun defaultUntil(nowMs: Long, zone: ZoneId): String {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        return HHMM.format(now.withMinute(0).withSecond(0).withNano(0).plusHours(2))
    }

    /**
     * "Heute länger" can count from the start of the next blocked time:
     * only when that one starts later today ([zone]) and does not run yet.
     * Its start as epoch ms, else null (the extra time starts now).
     */
    fun extendFromSchedule(c: InternetChild, nowMs: Long, zone: ZoneId): Long? {
        val s = c.nextSchedule?.takeIf { !it.active } ?: return null
        val from = epoch(s.from)?.takeIf { it > nowMs } ?: return null
        val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return from.takeIf { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
    }

    /** When an extra time of [minutes] ends: from [scheduleStartMs] (not [fromNow]) or from now. */
    fun extendEnd(minutes: Int, fromNow: Boolean, scheduleStartMs: Long?, nowMs: Long): Long =
        (if (fromNow || scheduleStartMs == null) nowMs else scheduleStartMs) + minutes * 60_000L

    /** Minutes as hours and the rest (90 → 1 h 30 min). */
    fun hoursMinutes(minutes: Int): Pair<Int, Int> = (minutes / 60) to (minutes % 60)
}
