package com.gatecontrol.android.familie.internet

import com.gatecontrol.android.familie.presence.PresenceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/** The state of a child's card, its buttons and the dialogs' times (mockup: Wednesday, 11 Nov, 17:52 in Berlin). */
class InternetLogicTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-11T16:52:10Z").toEpochMilli()
    private val schedule = InternetSchedule("2026-11-11T20:00:00Z", "2026-11-12T06:00:00Z", all = false, categories = listOf("social"), active = false)

    private fun child(
        paused: String? = null,
        extended: String? = null,
        filtered: Boolean = true,
        error: String? = null,
        next: InternetSchedule? = schedule,
    ) = InternetChild(5, "Tim", paused = paused, extended = extended, filtered = filtered, error = error, nextSchedule = next, devices = 2)

    @Test
    fun `the state follows the portal's order`() {
        assertEquals(InternetState.ON, InternetLogic.state(child(), now))
        assertEquals(InternetState.PAUSED, InternetLogic.state(child(paused = "2026-11-11T17:30:00Z", extended = "2026-11-11T20:30:00Z", error = "error"), now))
        assertEquals(InternetState.EXTENDED, InternetLogic.state(child(extended = "2026-11-11T20:30:00Z", error = "error"), now))
        assertEquals(InternetState.ERROR, InternetLogic.state(child(error = "unavailable"), now))
        assertEquals(InternetState.UNFILTERED, InternetLogic.state(child(filtered = false), now))
        // A pause that ran out is over, even while the answer still carries it.
        assertEquals(InternetState.ON, InternetLogic.state(child(paused = "2026-11-11T16:52:10Z"), now))
        assertEquals(InternetState.ON, InternetLogic.state(child(paused = "not a time"), now))
        // A pause on an unfiltered child still shows as a pause (the portal does the same).
        assertEquals(InternetState.PAUSED, InternetLogic.state(child(paused = "2026-11-11T17:30:00Z", filtered = false), now))
    }

    @Test
    fun `buttons per state, none for a child`() {
        assertEquals(listOf(InternetLogic.Button.PAUSE, InternetLogic.Button.EXTEND), InternetLogic.buttons(InternetState.ON, manage = true))
        assertEquals(listOf(InternetLogic.Button.RESUME, InternetLogic.Button.ADD_PAUSE), InternetLogic.buttons(InternetState.PAUSED, manage = true))
        assertEquals(
            listOf(InternetLogic.Button.PAUSE, InternetLogic.Button.ADD_EXTRA, InternetLogic.Button.END_EXTRA),
            InternetLogic.buttons(InternetState.EXTENDED, manage = true),
        )
        assertTrue(InternetLogic.buttons(InternetState.UNFILTERED, manage = true).isEmpty(), "a pause would do nothing")
        assertTrue(InternetLogic.buttons(InternetState.ERROR, manage = true).isEmpty())
        InternetState.entries.forEach { assertTrue(InternetLogic.buttons(it, manage = false).isEmpty(), "read only: $it") }
    }

    @Test
    fun `now runs in the server's clock`() {
        val payload = InternetPayload(true, true, listOf(child(paused = "2026-11-11T17:00:00Z")), serverTime = "2026-11-11T16:52:10Z")
        // The phone's clock is an hour ahead: the pause still runs.
        val phone = now + 3_600_000
        val snap = InternetSnapshot(payload, fetchedAtMs = phone)
        assertEquals(now, snap.nowMs(phone))
        assertEquals(InternetState.PAUSED, InternetLogic.state(snap.payload.children[0], snap.nowMs(phone)))
        // Eight minutes later it is over without a reload.
        assertEquals(InternetState.ON, InternetLogic.state(snap.payload.children[0], snap.nowMs(phone + 8 * 60_000)))
        assertEquals(Instant.parse("2026-11-11T17:00:00Z").toEpochMilli(), InternetLogic.nextChangeMs(payload.children, now))
        assertNull(InternetLogic.nextChangeMs(payload.children, now + 3_600_000))
        // A plugin without serverTime: the phone's clock.
        assertEquals(phone, InternetSnapshot(payload.copy(serverTime = null), phone).nowMs(phone))
        // The answer of a change replaces the child.
        val resumed = snap.with(child())
        assertNull(resumed.payload.children.single().paused)
    }

    @Test
    fun `times of day in the family's zone`() {
        assertEquals("18:30", InternetLogic.time("2026-11-11T17:30:00Z", berlin))
        assertNull(InternetLogic.time(null, berlin))
        assertEquals(berlin, InternetLogic.zone("Europe/Berlin", ZoneId.of("UTC")))
        assertEquals(ZoneId.of("UTC"), InternetLogic.zone("Nowhere/Town", ZoneId.of("UTC")))
        assertEquals(ZoneId.of("UTC"), InternetLogic.zone(null, ZoneId.of("UTC")))
        assertEquals(38, InternetLogic.minutesLeft("2026-11-11T17:30:00Z", now), "17:52:10 → 18:30 is 37 min 50 s")
        assertEquals(0, InternetLogic.minutesLeft("2026-11-11T16:00:00Z", now))
    }

    @Test
    fun `pause until a time of day`() {
        assertEquals("19:00", InternetLogic.defaultUntil(now, berlin), "17:52 → the next full hour and one on")
        assertFalse(InternetLogic.untilIsTomorrow("19:00", now, berlin))
        assertTrue(InternetLogic.untilIsTomorrow("07:00", now, berlin))
        assertTrue(InternetLogic.untilIsTomorrow("17:52", now, berlin), "this very minute is over")
        assertFalse(InternetLogic.untilIsTomorrow("17:53", now, berlin))
        assertEquals("07:05", InternetLogic.hhmm("07:05"))
        assertNull(InternetLogic.hhmm("7:5"))
        assertNull(InternetLogic.hhmm("24:00"))
    }

    @Test
    fun `longer today counts from the blocked time only when it still comes today`() {
        val start = Instant.parse("2026-11-11T20:00:00Z").toEpochMilli()
        assertEquals(start, InternetLogic.extendFromSchedule(child(), now, berlin))
        assertNull(InternetLogic.extendFromSchedule(child(next = null), now, berlin), "no blocked times: from now")
        assertNull(InternetLogic.extendFromSchedule(child(next = schedule.copy(active = true)), now, berlin), "it runs already")
        assertNull(InternetLogic.extendFromSchedule(child(next = schedule.copy(from = "2026-11-12T20:00:00Z")), now, berlin), "tomorrow's")
        // 23:30 UTC is tomorrow in Berlin, today in UTC.
        val late = child(next = schedule.copy(from = "2026-11-11T23:30:00Z"))
        assertNull(InternetLogic.extendFromSchedule(late, now, berlin))
        assertEquals(Instant.parse("2026-11-11T23:30:00Z").toEpochMilli(), InternetLogic.extendFromSchedule(late, now, ZoneId.of("UTC")))

        assertEquals("21:30", InternetLogic.time(InternetLogic.extendEnd(30, fromNow = false, scheduleStartMs = start, nowMs = now), berlin))
        assertEquals("18:22", InternetLogic.time(InternetLogic.extendEnd(30, fromNow = true, scheduleStartMs = start, nowMs = now), berlin))
        assertEquals("18:22", InternetLogic.time(InternetLogic.extendEnd(30, fromNow = false, scheduleStartMs = null, nowMs = now), berlin))
    }

    @Test
    fun `presence is shown for home and away only`() {
        val home = InternetPresence(PresenceState.HOME, null, "wifi")
        assertEquals(home, InternetLogic.shownPresence(home))
        assertNull(InternetLogic.shownPresence(InternetPresence(PresenceState.NOT_SHARED, null, null)))
        assertNull(InternetLogic.shownPresence(InternetPresence(PresenceState.UNKNOWN, null, null)))
        assertNull(InternetLogic.shownPresence(null))
        assertEquals(1 to 30, InternetLogic.hoursMinutes(90))
        assertEquals(0 to 15, InternetLogic.hoursMinutes(15))
    }
}
