package com.gatecontrol.android.ui.dnsprotect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gatecontrol.android.R
import com.gatecontrol.android.dnsprotect.DnsLogic
import com.gatecontrol.android.dnsprotect.DnsPause
import com.gatecontrol.android.dnsprotect.DnsScheduleEntry
import com.gatecontrol.android.dnsprotect.DnsSnapshot
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

/** The locale the app is shown in (its own language setting). */
@Composable
internal fun appLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
internal fun dnsCount(value: Long): String = NumberFormat.getIntegerInstance(appLocale()).format(value)

/** "5 Min", "1 Std", "4 Std" of a duration chip. */
@Composable
internal fun dnsChipLabel(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.dns_chip_hours, minutes / 60) else stringResource(R.string.dns_chip_min, minutes)

/** "30 Minuten", "1 Stunde" in a sentence. */
@Composable
internal fun dnsDurationWords(minutes: Int): String =
    if (minutes % 60 == 0) pluralStringResource(R.plurals.dns_hours, minutes / 60, minutes / 60)
    else pluralStringResource(R.plurals.dns_minutes, minutes, minutes)

/** Why suspending is not offered (`may.reason`); null for none or an unknown code. */
@Composable
internal fun dnsReasonText(reason: String?): String? = when (reason) {
    "admins_only" -> stringResource(R.string.dns_reason_admins_only)
    "profile" -> stringResource(R.string.dns_reason_profile)
    "not_filtered" -> stringResource(R.string.dns_reason_not_filtered)
    "no_device" -> stringResource(R.string.dns_reason_no_device)
    "fallback" -> stringResource(R.string.dns_reason_fallback)
    "off" -> stringResource(R.string.dns_reason_off)
    "paused_for_all" -> stringResource(R.string.dns_reason_paused_for_all)
    "internet_paused" -> stringResource(R.string.dns_reason_internet_paused)
    else -> null
}

/** One line of "Zeiten heute". */
@Composable
internal fun dnsScheduleLine(entry: DnsScheduleEntry): String {
    val what = if (entry.block == "all" || entry.categories.isEmpty()) {
        stringResource(R.string.dns_today_all)
    } else {
        entry.categories.map { c ->
            when (c) {
                "social" -> stringResource(R.string.dns_cat_social)
                "games" -> stringResource(R.string.dns_cat_games)
                "video" -> stringResource(R.string.dns_cat_video)
                else -> c
            }
        }.joinToString(", ")
    }
    return if (DnsLogic.wholeDay(entry)) stringResource(R.string.dns_today_whole, what)
    else stringResource(R.string.dns_today_entry, entry.from, entry.to, what)
}

/** "unterbrochen bis 15:12 Uhr" / "… bis wieder eingeschaltet wird" for a row of "Für andere unterbrechen". */
@Composable
internal fun dnsPauseLine(pause: DnsPause): String {
    val until = DnsLogic.timeOfDay(pause.until)
    return if (pause.openEnd || until == null) stringResource(R.string.dns_others_paused_open)
    else stringResource(R.string.dns_others_paused_until, until)
}

/** "Internet pausiert bis 20:49 Uhr" for a row of "Für andere unterbrechen" ([untilIso] the server's end). */
@Composable
internal fun dnsInternetLine(untilIso: String?): String =
    DnsLogic.timeOfDay(untilIso)?.let { stringResource(R.string.dns_others_internet_until, it) }
        ?: stringResource(R.string.dns_others_internet)

/**
 * Seconds left of the running suspension, counted down every second; null
 * without an end. A little after it ran out [onEnded] asks the server.
 */
@Composable
internal fun rememberDnsRemaining(snapshot: DnsSnapshot?, onEnded: () -> Unit): Long? =
    rememberDnsCountdown(snapshot, DnsLogic::pauseEndsAtMs, onEnded)

/**
 * Seconds left of "Internet pausiert" (Familie), counted down; null without it or an end.
 * A little after it ran out [onEnded] asks the server (the notice goes then).
 */
@Composable
internal fun rememberDnsInternetRemaining(snapshot: DnsSnapshot?, onEnded: () -> Unit): Long? =
    rememberDnsCountdown(snapshot, DnsLogic::internetEndsAtMs, onEnded)

/** Seconds until [endOf] of the answer, every second; once it ran out (and the answer still saw it coming) [onEnded]. */
@Composable
private fun rememberDnsCountdown(snapshot: DnsSnapshot?, endOf: (DnsSnapshot) -> Long?, onEnded: () -> Unit): Long? {
    fun left(s: DnsSnapshot, now: Long): Long? = endOf(s)?.let { ((it - now + 999) / 1000).coerceAtLeast(0) }
    val end = snapshot?.let(endOf)
    val remaining by produceState(
        initialValue = snapshot?.let { left(it, System.currentTimeMillis()) },
        snapshot,
    ) {
        if (snapshot == null || end == null) {
            value = null
            return@produceState
        }
        while (true) {
            val l = left(snapshot, System.currentTimeMillis()) ?: break
            value = l
            if (l <= 0L) {
                // Once per answer: an answer that already said "over" does not ask again.
                if (end > snapshot.receivedAtMs) {
                    delay(DnsLogic.AFTER_END_MS)
                    onEnded()
                }
                break
            }
            delay(1_000)
        }
    }
    return remaining
}
