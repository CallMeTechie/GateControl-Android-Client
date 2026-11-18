package com.gatecontrol.android.chat

import com.gatecontrol.android.familie.locate.FamiliePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The case of 18.11. (Wednesday): „Beim Ankommen“ at the Familie place „Arbeit“ (`place:3`), 100 m, „Jedes Mal“, Mo–Fr
 * 06:00–09:00, „Vor dem Senden fragen“. Arrival 07:35, the app started again at 07:42 (the location access Android
 * showed) — and no „Jetzt senden?“.
 */
class ChatPlaceWatchTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val me = "android-0011223344556677"
    private val work = ChatPlaceMessage(
        id = 7, deviceId = me, trigger = ChatPlaceMessages.ENTER,
        place = ChatPmPlace("place:3", "Arbeit", 51.2345, 7.1234, 100),
        text = "Bin gut angekommen.", conversationId = 41, way = "whatsapp", repeat = ChatPlaceMessages.ALWAYS,
        window = ChatPmWindow(listOf(1, 2, 3, 4, 5), "06:00", "09:00"), ask = true, enabled = true,
        lastFiredAt = null, doneAt = null, createdAt = "2026-11-17T19:30:00Z",
    )

    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m)
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `18 November 2026 is a Wednesday and 1 is Monday`() {
        assertEquals(DayOfWeek.WEDNESDAY, LocalDate.of(2026, 11, 18).dayOfWeek)
        assertEquals(3, LocalDate.of(2026, 11, 18).dayOfWeek.value)
        assertTrue(ChatPlaceMessages.inWindow(work.window, at(18, 7, 35)))
        assertTrue(ChatPlaceMessages.inWindow(work.window, at(18, 7, 42)))
    }

    @Test
    fun `adding the fences again at work before the arrival was reported - now an arrival, before nothing`() {
        // the evening before: saved at home — the new fence learns „outside“, nothing goes out
        val evening = ms(at(17, 19, 31))
        var fences = ChatPlaceWatch.afterRegister(emptyMap(), listOf(work), evening)
        assertNull(fences[7]!!.side)
        val (learned, moved) = ChatPlaceWatch.observe(fences[7], ChatPlaceWatch.OUT, evening)
        assertFalse(moved)
        fences = mapOf(7L to learned!!)

        // 07:42 the app starts (a push, the Familie place, a job): the fences are added again while the phone is at work
        val restart = ms(at(18, 7, 42))
        fences = ChatPlaceWatch.afterRegister(fences, listOf(work), restart)
        assertEquals("the same fence keeps its side", ChatPlaceWatch.OUT, fences[7]!!.side)
        // Android's report on adding (INITIAL_TRIGGER_ENTER) is the arrival: it was outside before
        val (inside, arrived) = ChatPlaceWatch.observe(fences[7], ChatPlaceWatch.IN, restart)
        assertTrue(arrived)
        assertEquals(ChatPlaceWatch.IN, inside!!.side)
        assertNull(ChatPlaceWatch.skipReason(work, ChatPlaceMessages.ENTER, me, at(18, 7, 42), restart, emptyMap()))

        // the old way: removed and added with setInitialTrigger(0) — Android reports nothing for a phone already
        // inside, so the arrival was gone. And a second report while inside is no second arrival:
        val (_, again) = ChatPlaceWatch.observe(inside, ChatPlaceWatch.IN, restart + 60_000)
        assertFalse(again)
    }

    @Test
    fun `saving it at the place sends nothing, the next arrival does`() {
        val now = ms(at(18, 8, 0))
        val f = ChatPlaceWatch.afterRegister(emptyMap(), listOf(work), now)
        val (inside, moved) = ChatPlaceWatch.observe(f[7], ChatPlaceWatch.IN, now)
        assertFalse(moved)
        val (outside, left) = ChatPlaceWatch.observe(inside, ChatPlaceWatch.OUT, now + 3_600_000)
        assertTrue(left)
        assertTrue(ChatPlaceWatch.observe(outside, ChatPlaceWatch.IN, now + 7_200_000).second)
    }

    @Test
    fun `a changed place starts again, a fence of an older app version counts as a transition`() {
        val now = 1_000L
        val f = ChatPlaceWatch.afterRegister(mapOf(7L to ChatPmFence(ChatPlaceWatch.signature(work), ChatPlaceWatch.IN, 0)), listOf(work.copy(place = work.place.copy(radiusM = 400))), now)
        assertNull(f[7]!!.side)
        assertTrue(ChatPlaceWatch.observe(null, ChatPlaceWatch.IN, now).second)
        // those no longer watched go
        assertTrue(ChatPlaceWatch.afterRegister(f, emptyList(), now).isEmpty())
    }

    @Test
    fun `the fence is at least 150 m, the saved radius stays`() {
        assertEquals(150, ChatPlaceWatch.fenceRadius(100))
        assertEquals(400, ChatPlaceWatch.fenceRadius(400))
        assertEquals(2000, ChatPlaceWatch.fenceRadius(5000))
        assertEquals(100, work.place.radiusM)
        assertNotEquals(ChatPlaceWatch.signature(work), ChatPlaceWatch.signature(work.copy(place = work.place.copy(radiusM = 300))))
    }

    @Test
    fun `arriving before 06 00 - noted, at 06 00 asked once`() {
        val early = at(18, 5, 50)
        assertEquals(ChatPlaceWatch.WHY_WINDOW, ChatPlaceWatch.skipReason(work, ChatPlaceMessages.ENTER, me, early, ms(early), emptyMap()))
        val fences = mapOf(7L to ChatPmFence(ChatPlaceWatch.signature(work), ChatPlaceWatch.IN, ms(early)))
        assertEquals(at(18, 6, 0), ChatPlaceWatch.nextCheck(listOf(work), fences, me, early))

        val six = at(18, 6, 2)
        assertEquals(listOf(work), ChatPlaceWatch.catchUp(listOf(work), fences, me, six, ms(six), emptyMap(), zone))
        // asked at 06:02: not again in this window (and not within the hour)
        val fired = mapOf(7L to ms(six))
        assertTrue(ChatPlaceWatch.catchUp(listOf(work), fences, me, at(18, 8, 30), ms(at(18, 8, 30)), fired, zone).isEmpty())
        // left before 06:00: nothing
        val gone = mapOf(7L to ChatPmFence(ChatPlaceWatch.signature(work), ChatPlaceWatch.OUT, ms(early)))
        assertTrue(ChatPlaceWatch.catchUp(listOf(work), gone, me, six, ms(six), emptyMap(), zone).isEmpty())
        assertNull(ChatPlaceWatch.nextCheck(listOf(work), gone, me, early))
    }

    @Test
    fun `the next window start - weekend to Monday, over midnight, none without a window`() {
        assertEquals(at(23, 6, 0), ChatPlaceWatch.nextWindowStart(work.window, at(21, 10, 0)))
        assertEquals(at(19, 6, 0), ChatPlaceWatch.nextWindowStart(work.window, at(18, 6, 0)))
        val night = ChatPmWindow(listOf(5), "22:00", "06:00")
        assertEquals(at(20, 22, 0), ChatPlaceWatch.nextWindowStart(night, at(18, 12, 0)))
        assertEquals(at(20, 22, 0), ChatPlaceWatch.windowStartedAt(night, at(21, 2, 0)))
        assertNull(ChatPlaceWatch.nextWindowStart(null, at(18, 12, 0)))
    }

    @Test
    fun `why it does not go out - another phone, switched off, within the hour`() {
        val now = at(18, 7, 42)
        assertEquals(ChatPlaceWatch.WHY_OTHER_DEVICE, ChatPlaceWatch.skipReason(work, "enter", "android-ffffffffffffffff", now, ms(now), emptyMap()))
        assertEquals(ChatPlaceWatch.WHY_INACTIVE, ChatPlaceWatch.skipReason(work.copy(enabled = false), "enter", me, now, ms(now), emptyMap()))
        assertEquals(ChatPlaceWatch.WHY_TOO_SOON, ChatPlaceWatch.skipReason(work, "enter", me, now, ms(now), mapOf(7L to ms(now) - 60_000)))
        assertEquals("trigger", ChatPlaceWatch.skipReason(work, "exit", me, now, ms(now), emptyMap()))
    }

    @Test
    fun `the status - what is in the way first`() {
        val ok = ChatPmDiag(registered = setOf(7L))
        assertEquals(ChatPmHealth.WATCHING, ChatPlaceWatch.health(work, me, ok))
        assertEquals(ChatPmHealth.OTHER_DEVICE, ChatPlaceWatch.health(work, "android-ffffffffffffffff", ok))
        assertEquals(ChatPmHealth.OFF, ChatPlaceWatch.health(work.copy(enabled = false), me, ok))
        assertEquals(ChatPmHealth.NEED_BACKGROUND, ChatPlaceWatch.health(work, me, ok.copy(readiness = FamiliePermissions.GeofenceReadiness.NEED_BACKGROUND)))
        assertEquals(ChatPmHealth.LOCATION_OFF, ChatPlaceWatch.health(work, me, ok.copy(lost = true)))
        assertEquals(ChatPmHealth.NOTIFICATIONS_OFF, ChatPlaceWatch.health(work, me, ok.copy(notificationsOn = false)))
        assertEquals(ChatPmHealth.WATCHING, ChatPlaceWatch.health(work.copy(ask = false), me, ok.copy(notificationsOn = false)))
        assertEquals(ChatPmHealth.NOT_REGISTERED, ChatPlaceWatch.health(work, me, ChatPmDiag()))
        assertEquals(ChatPmHealth.NO_POINT, ChatPlaceWatch.health(work.copy(place = work.place.copy(lat = 0.0, lon = 0.0)), me, ok))
    }

    @Test
    fun `a place without a point is not watched, not saved and not offered as a Familie place`() {
        val zero = work.copy(id = 8, place = work.place.copy(lat = 0.0, lon = 0.0))
        assertEquals(listOf(7L), ChatPlaceMessages.watched(listOf(work, zero), me, 10).map { it.id })
        assertNull(ChatPlaceMessages.body(ChatPlaceMessages.draftOf(zero), me))
        val places = listOf(ChatPlace("place:3", "Arbeit", null, null, 200), ChatPlace("place:4", "Schule", 0.0, 0.0, 200), ChatPlace("home", "Zuhause", 51.0, 7.0, 150))
        assertEquals(listOf("home"), places.mapNotNull { ChatPlaceMessages.fromFamilie(it) }.map { it.familieId })
        assertEquals(listOf("Arbeit", "Schule"), ChatPlaceMessages.familieWithoutPoint(places).map { it.name })
    }

    @Test
    fun `the log keeps the last eight, the server's lastFiredAt counts when newer, and both survive storage`() {
        var log = emptyList<ChatPmEvent>()
        repeat(10) { log = ChatPlaceWatch.append(log, ChatPmEvent(it.toLong(), ChatPlaceWatch.DETECTED, "enter")) }
        assertEquals(8, log.size)
        assertEquals(2L, log.first().atMs)
        val m = mapOf(7L to log.take(7) + ChatPmEvent(20, ChatPlaceWatch.SKIPPED, ChatPlaceWatch.WHY_WINDOW))
        assertEquals(m, ChatPlaceWatch.readLog(ChatPlaceWatch.writeLog(m)))
        val f = mapOf(7L to ChatPmFence("s", ChatPlaceWatch.IN, 5), 8L to ChatPmFence("t", null, 6))
        assertEquals(f, ChatPlaceWatch.readFences(ChatPlaceWatch.writeFences(f)))

        val skipped = listOf(ChatPmEvent(ms(at(18, 7, 42)), ChatPlaceWatch.SKIPPED, ChatPlaceWatch.WHY_WINDOW))
        assertEquals(ChatPlaceWatch.SKIPPED, ChatPlaceWatch.lastEvent(work, skipped)!!.kind)
        val later = work.copy(lastFiredAt = "2026-11-18T07:00:00Z")
        assertEquals(ChatPlaceWatch.SENT, ChatPlaceWatch.lastEvent(later, skipped)!!.kind)
        assertNull(ChatPlaceWatch.lastEvent(work, listOf(ChatPmEvent(1, ChatPlaceWatch.LEARNED, "in"))))
    }
}
