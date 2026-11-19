package com.gatecontrol.android.dnsprotect

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The status as the server answered it and when (this device's clock). */
data class DnsSnapshot(val status: DnsStatus, val receivedAtMs: Long)

/** One duration chip of "Schutz unterbrechen". */
data class DnsChip(val minutes: Int, val enabled: Boolean, val selected: Boolean)

/** What a refusal means for the screen. */
enum class DnsFailure {
    /** No DNS protection (any more): hide, reload the permissions. */
    GONE,
    /** The plugin or the filter service does not answer: keep the state, try later. */
    BUSY,
    /** Too many requests: ask less often. */
    RATE,
    /** The state changed under the app (filter down, forbidden): reload, no message. */
    STALE,
    /** Not allowed: show the server's sentence, reload. */
    REFUSED,
}

/** Pure rules of the "DNS-Schutz" screen (unit-tested). */
object DnsLogic {
    /** The chips the screen always shows; 240 only when the server allows it. */
    val STANDARD_MINUTES = listOf(5, 30, 60)
    const val LONG_MINUTES = 240
    /** "+30 Min.": what the button adds to the running suspension. */
    const val EXTEND_MINUTES = 30

    /** `reason` of the core's 404 when the plugin is gone (server 1.301+). */
    const val REASON_PLUGIN_UNAVAILABLE = "plugin_unavailable"

    const val POLL_MS = 45_000L
    /** After `rate_limited`. */
    const val POLL_SLOW_MS = 120_000L
    /** A look shortly after a suspension ended. */
    const val AFTER_END_MS = 2_000L

    fun parseIso(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * When the running suspension ends, on this device's clock: the server's
     * `until` against its `serverTime`, so a wrong device clock does not shift
     * the countdown. Null: not suspended, or until switched back on.
     */
    fun pauseEndsAtMs(snapshot: DnsSnapshot): Long? {
        val pause = snapshot.status.pause ?: return null
        if (pause.openEnd) return null
        return endsAtMs(snapshot, pause.until, pause.remainingSeconds)
    }

    /** When "Internet pausiert" (Familie) ends, on this device's clock as [pauseEndsAtMs]; null without it. */
    fun internetEndsAtMs(snapshot: DnsSnapshot): Long? {
        val internet = snapshot.status.internet ?: return null
        return endsAtMs(snapshot, internet.until, internet.remainingSeconds)
    }

    /**
     * Whether "Internet pausiert" still holds at [nowMs]: the server said so and its end has not
     * passed on this device's clock (without a readable end: as the server said).
     */
    fun internetPaused(snapshot: DnsSnapshot, nowMs: Long): Boolean {
        if (snapshot.status.internet == null) return false
        val end = internetEndsAtMs(snapshot) ?: return true
        return end > nowMs
    }

    /** Seconds left of "Internet pausiert" (never negative); null without it or without an end. */
    fun internetRemainingSeconds(snapshot: DnsSnapshot, nowMs: Long): Long? =
        internetEndsAtMs(snapshot)?.let { ((it - nowMs + 999) / 1000).coerceAtLeast(0) }

    private fun endsAtMs(snapshot: DnsSnapshot, untilIso: String?, remainingSeconds: Long?): Long? {
        val until = parseIso(untilIso)
        val server = parseIso(snapshot.status.serverTime)
        return when {
            until != null && server != null -> snapshot.receivedAtMs + (until - server)
            remainingSeconds != null -> snapshot.receivedAtMs + remainingSeconds * 1000
            else -> until
        }
    }

    /** Seconds left of the running suspension (never negative); null without an end. */
    fun remainingSeconds(snapshot: DnsSnapshot, nowMs: Long): Long? =
        pauseEndsAtMs(snapshot)?.let { ((it - nowMs + 999) / 1000).coerceAtLeast(0) }

    /** `24:10`, from an hour on `1:02:03`. */
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    /**
     * The minutes a suspension was chosen for, with "+30 Min." the whole of it: `pause.minutes`
     * (plugin 1.1), else `until` − `since`; null when open or unknown.
     */
    fun plannedMinutes(pause: DnsPause?): Int? {
        if (pause == null || pause.openEnd) return null
        pause.minutes?.let { return it }
        val until = parseIso(pause.until) ?: return null
        val since = parseIso(pause.since) ?: return null
        return ((until - since + 30_000) / 60_000).toInt().takeIf { it > 0 }
    }

    /**
     * The duration chips: 5, 30 and 60 minutes always (greyed out when the
     * server does not allow them), 240 only when allowed. While suspended
     * none can be tapped and the one the suspension was started with is
     * selected (it shows the countdown).
     */
    fun chips(may: DnsMay, pause: DnsPause?): List<DnsChip> {
        val minutes = if (LONG_MINUTES in may.durations || plannedMinutes(pause) == LONG_MINUTES) STANDARD_MINUTES + LONG_MINUTES else STANDARD_MINUTES
        val planned = plannedMinutes(pause)
        return minutes.map { m ->
            DnsChip(minutes = m, enabled = pause == null && may.pause && m in may.durations, selected = pause != null && planned == m)
        }
    }

    /** Whether the card "Schutz unterbrechen" stands on the screen. */
    fun showsPauseCard(status: DnsStatus): Boolean = when (status.state) {
        DnsStatus.ACTIVE -> status.may.pause
        DnsStatus.PAUSED -> true
        else -> false
    }

    /** Whether "+30 Min." stands beside "Wieder einschalten": the server allows extending a suspension with an end. */
    fun showsExtend(status: DnsStatus): Boolean =
        status.state == DnsStatus.PAUSED && status.may.extend && status.pause != null && !status.pause.openEnd

    /**
     * The notice in place of the chips while the protection is active and suspending is not
     * offered for a rights reason: `profile` or `admins_only`; null otherwise (an unknown
     * reason shows nothing).
     */
    fun notAllowedReason(status: DnsStatus): String? =
        status.may.reason?.takeIf { status.state == DnsStatus.ACTIVE && !status.may.pause && it in NOT_ALLOWED_REASONS }

    private val NOT_ALLOWED_REASONS = setOf("profile", "admins_only")

    /** "HH:mm" of an ISO time in the device's zone; null when unreadable. */
    fun timeOfDay(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? =
        parseIso(iso)?.let { TIME.format(Instant.ofEpochMilli(it).atZone(zone)) }

    /** "HH:mm" of a moment on this device's clock. */
    fun timeOfDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** `16,9` / `16.9` — the share with one decimal, as the mockup shows it. */
    fun percent(value: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", value)

    /** A time of today that holds the whole day. */
    fun wholeDay(entry: DnsScheduleEntry): Boolean = entry.from == "00:00" && (entry.to == "24:00" || entry.to == "00:00")

    fun failure(f: DnsResult.Failed): DnsFailure = when {
        f.code == "unsupported" || f.code == "off" || f.code == "no_user" -> DnsFailure.GONE
        // 404 of the core: the plugin is not installed or cannot run — marked since server 1.301
        // (`reason: plugin_unavailable`, which only the core sets), on older servers a 404 without the plugin's text.
        f.http == 404 && f.reason == REASON_PLUGIN_UNAVAILABLE -> DnsFailure.GONE
        f.http == 404 && f.message == null -> DnsFailure.GONE
        f.http == 429 || f.code == "rate_limited" -> DnsFailure.RATE
        f.code == "unavailable" || f.code == "plugin_error" || f.code == "not_running" || f.code == "timeout" || f.http in 502..504 -> DnsFailure.BUSY
        f.code == "not_filtering" || f.code == "forbidden" || f.code == "forever_not_allowed" || f.code == "not_found" -> DnsFailure.STALE
        else -> DnsFailure.REFUSED
    }

    /**
     * The state for the start tile before `GET status` answered: only what
     * `permissions.vpnDns.state` tells (`fallback` = down).
     */
    fun coarseState(vpnDnsState: String?): String = when (vpnDnsState) {
        "fallback" -> DnsStatus.FALLBACK
        "starting" -> DnsStatus.STARTING
        else -> DnsStatus.ACTIVE
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
}
