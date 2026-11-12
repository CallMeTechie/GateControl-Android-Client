package com.gatecontrol.android.dnsprotect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.Locale

class DnsLogicTest {

    private val server = "2026-11-12T13:48:00.000Z"
    private val serverMs = DnsLogic.parseIso(server)!!

    private fun paused(until: String?, since: String? = null, openEnd: Boolean = false, remaining: Long? = null, serverTime: String? = server) =
        DnsStatus(
            state = DnsStatus.PAUSED,
            pause = DnsPause(scope = "peer", until = until, openEnd = openEnd, since = since, remainingSeconds = remaining),
            serverTime = serverTime,
        )

    @Test
    fun `the countdown follows the server's clock, not the device's`() {
        // The device's clock is an hour off: 24 minutes stay 24 minutes.
        val received = serverMs + 3_600_000
        val snap = DnsSnapshot(paused(until = "2026-11-12T14:12:00.000Z"), received)
        assertEquals(received + 24 * 60_000, DnsLogic.pauseEndsAtMs(snap))
        assertEquals(24 * 60L, DnsLogic.remainingSeconds(snap, received))
        assertEquals(24 * 60L - 10, DnsLogic.remainingSeconds(snap, received + 10_000))
        assertEquals(0L, DnsLogic.remainingSeconds(snap, received + 99 * 60_000), "never negative")
    }

    @Test
    fun `without the server's time the remaining seconds count, then the plain end`() {
        val bySeconds = DnsSnapshot(paused(until = "2026-11-12T14:12:00.000Z", remaining = 90, serverTime = null), 1_000)
        assertEquals(91_000L, DnsLogic.pauseEndsAtMs(bySeconds))
        val plain = DnsSnapshot(paused(until = "2026-11-12T14:12:00.000Z", serverTime = "nonsense"), 1_000)
        assertEquals(DnsLogic.parseIso("2026-11-12T14:12:00.000Z"), DnsLogic.pauseEndsAtMs(plain))
    }

    @Test
    fun `no end without a suspension or with an open one`() {
        assertNull(DnsLogic.pauseEndsAtMs(DnsSnapshot(DnsStatus(DnsStatus.ACTIVE), 0)))
        val open = DnsSnapshot(paused(until = null, openEnd = true), 0)
        assertNull(DnsLogic.pauseEndsAtMs(open))
        assertNull(DnsLogic.remainingSeconds(open, 5))
    }

    @Test
    fun `clock shows minutes and seconds, hours from an hour on`() {
        assertEquals("24:10", DnsLogic.clock(24 * 60 + 10))
        assertEquals("00:05", DnsLogic.clock(5))
        assertEquals("00:00", DnsLogic.clock(-3))
        assertEquals("1:00:00", DnsLogic.clock(3600))
        assertEquals("3:59:59", DnsLogic.clock(4 * 3600 - 1))
    }

    @Test
    fun `planned minutes come from since and until`() {
        assertEquals(30, DnsLogic.plannedMinutes(DnsPause(until = "2026-11-12T14:12:00.000Z", since = "2026-11-12T13:42:00.400Z")))
        assertNull(DnsLogic.plannedMinutes(DnsPause(until = "2026-11-12T14:12:00.000Z")))
        assertNull(DnsLogic.plannedMinutes(DnsPause(openEnd = true, since = "2026-11-12T13:42:00.000Z")))
        assertNull(DnsLogic.plannedMinutes(null))
    }

    @Test
    fun `chips - 5, 30 and 60 always, missing ones greyed out, 240 only when allowed`() {
        val may = DnsMay(pause = true, durations = listOf(5, 30), maxMinutes = 30)
        assertEquals(
            listOf(DnsChip(5, enabled = true, selected = false), DnsChip(30, enabled = true, selected = false), DnsChip(60, enabled = false, selected = false)),
            DnsLogic.chips(may, null),
        )
        assertEquals(listOf(5, 30, 60, 240), DnsLogic.chips(DnsMay(pause = true, durations = listOf(5, 30, 60, 240)), null).map { it.minutes })
        // Not allowed at all: nothing can be tapped.
        assertTrue(DnsLogic.chips(DnsMay(pause = false, durations = listOf(5, 30, 60)), null).none { it.enabled })
    }

    @Test
    fun `chips while suspended - none can be tapped, the started one is selected`() {
        val may = DnsMay(pause = false, resume = true, durations = listOf(5, 30, 60))
        val pause = DnsPause(until = "2026-11-12T14:12:00.000Z", since = "2026-11-12T13:42:00.000Z")
        val chips = DnsLogic.chips(may, pause)
        assertTrue(chips.none { it.enabled })
        assertEquals(listOf(30), chips.filter { it.selected }.map { it.minutes })
        // A length no chip has (an admin's 45 minutes, "+30 Min."): none selected, the status card counts.
        val odd = DnsLogic.chips(may, DnsPause(until = "2026-11-12T14:27:00.000Z", since = "2026-11-12T13:42:00.000Z"))
        assertTrue(odd.none { it.selected })
        // Four hours started elsewhere: its chip shows although this viewer may not choose it.
        val long = DnsLogic.chips(may, DnsPause(until = "2026-11-12T17:42:00.000Z", since = "2026-11-12T13:42:00.000Z"))
        assertEquals(listOf(240), long.filter { it.selected }.map { it.minutes })
    }

    @Test
    fun `the card to suspend stands with the right to, and while suspended`() {
        assertTrue(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.ACTIVE, may = DnsMay(pause = true))))
        assertFalse(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.ACTIVE, may = DnsMay(pause = false, reason = "profile"))))
        assertFalse(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.ACTIVE, may = DnsMay(pause = false, reason = "admins_only"))))
        assertTrue(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.PAUSED, may = DnsMay(reason = "paused_for_all"))))
        assertFalse(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.FALLBACK)))
        assertFalse(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.UNFILTERED)))
        assertFalse(DnsLogic.showsPauseCard(DnsStatus(DnsStatus.STARTING)))
    }

    @Test
    fun `times of day in the device's zone`() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals("15:12", DnsLogic.timeOfDay("2026-11-12T14:12:00.000Z", berlin))
        assertNull(DnsLogic.timeOfDay(null as String?, berlin))
        assertNull(DnsLogic.timeOfDay("gestern", berlin))
    }

    @Test
    fun `share with one decimal in the app's language`() {
        assertEquals("16,9", DnsLogic.percent(16.9, Locale.GERMAN))
        assertEquals("27.0", DnsLogic.percent(27.0, Locale.ENGLISH))
    }

    @Test
    fun `a whole day`() {
        assertTrue(DnsLogic.wholeDay(DnsScheduleEntry("00:00", "24:00")))
        assertFalse(DnsLogic.wholeDay(DnsScheduleEntry("21:00", "07:00")))
    }

    @Test
    fun `refusals by the contract's table`() {
        fun kind(code: String, http: Int, message: String? = "Satz.") = DnsLogic.failure(DnsResult.Failed(code, http, message))
        // No DNS protection: plugin on an old server, switched off, plugin missing (core's 404 without text), token without a person.
        assertEquals(DnsFailure.GONE, kind("unsupported", 501))
        assertEquals(DnsFailure.GONE, kind("off", 409))
        assertEquals(DnsFailure.GONE, kind("not_found", 404, message = null))
        assertEquals(DnsFailure.GONE, kind("no_user", 403, message = null))
        // The plugin's own 404 (device or person unknown) is not "gone".
        assertEquals(DnsFailure.STALE, kind("not_found", 404))
        assertEquals(DnsFailure.BUSY, kind("unavailable", 503))
        assertEquals(DnsFailure.BUSY, kind("plugin_error", 502, null))
        assertEquals(DnsFailure.BUSY, kind("not_running", 503, null))
        assertEquals(DnsFailure.BUSY, kind("timeout", 504, null))
        assertEquals(DnsFailure.BUSY, kind("http_502", 502, null))
        assertEquals(DnsFailure.RATE, kind("rate_limited", 429, null))
        assertEquals(DnsFailure.STALE, kind("not_filtering", 409))
        assertEquals(DnsFailure.STALE, kind("forbidden", 403))
        assertEquals(DnsFailure.STALE, kind("forever_not_allowed", 403))
        assertEquals(DnsFailure.REFUSED, kind("not_allowed", 403))
        assertEquals(DnsFailure.REFUSED, kind("too_long", 403))
        assertEquals(DnsFailure.REFUSED, kind("invalid", 400))
        assertEquals(DnsFailure.REFUSED, kind("no_device", 409))
    }

    @Test
    fun `the tile before the first status knows only what the permissions tell`() {
        assertEquals(DnsStatus.FALLBACK, DnsLogic.coarseState("fallback"))
        assertEquals(DnsStatus.STARTING, DnsLogic.coarseState("starting"))
        assertEquals(DnsStatus.ACTIVE, DnsLogic.coarseState("filtering"))
    }
}
