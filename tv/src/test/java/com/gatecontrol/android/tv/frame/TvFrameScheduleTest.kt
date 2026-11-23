package com.gatecontrol.android.tv.frame

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TvFrameScheduleTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    /** Photo [n]: the higher, the older (taken on day 28 − n). */
    private fun photo(n: Int, token: String = "t$n") = TvFramePhoto("/plugin-media/$token/preview?size=large", "2026-10-%02dT10:00:00.000Z".format(28 - n))

    private val feed = TvFrameFeed(on = true, ready = true, seconds = 15, order = TvFrameOrder.DATE, items = listOf(photo(1)))

    // ─── GET frame ───────────────────────────────────────────────────

    @Test
    fun `GET frame is read with its photos, seconds and order`() {
        val f = TvFrameJson.feed(
            json(
                """{"ok":true,"on":true,"ready":true,"seconds":12,"order":"date","items":[
                {"url":"/plugin-media/abc_DEF-1/preview?size=large","takenAt":"2026-07-01T09:00:00.000Z"},
                {"url":"/plugin-media/xyz/preview?size=large","takenAt":null}]}""",
            ),
        )!!
        assertTrue(f.on)
        assertTrue(f.ready)
        assertEquals(12, f.seconds)
        assertEquals(TvFrameOrder.DATE, f.order)
        assertEquals(listOf("/plugin-media/abc_DEF-1/preview?size=large", "/plugin-media/xyz/preview?size=large"), f.items.map { it.url })
        assertEquals("2026-07-01T09:00:00.000Z", f.items[0].takenAt)
        assertNull(f.items[1].takenAt)
    }

    @Test
    fun `only previews of the own server are taken`() {
        val f = TvFrameJson.feed(
            json(
                """{"on":true,"items":[
                {"url":"https://other.example.com/plugin-media/a/preview"},
                {"url":"/plugin-media/../api/v1/x/preview"},
                {"url":"/api/v1/client/plugins/gatecontrol-drive/api/nodes/1/content"},
                {"url":"/plugin-media/ok/preview?size=large"},
                {"nourl":1}, 7]}""",
            ),
        )!!
        assertEquals(listOf("/plugin-media/ok/preview?size=large"), f.items.map { it.url })
    }

    @Test
    fun `odd answers fall back to the defaults or are refused`() {
        val f = TvFrameJson.feed(json("""{"on":true,"seconds":99999,"order":"name"}"""))!!
        assertEquals(3600, f.seconds)
        assertEquals(TvFrameOrder.RANDOM, f.order)
        assertTrue(f.ready)
        assertEquals(emptyList<TvFramePhoto>(), f.items)
        assertEquals(TvFrameSchedule.DEFAULT_SECONDS, TvFrameJson.feed(json("""{"on":false}"""))!!.seconds)
        assertEquals(5, TvFrameJson.feed(json("""{"on":true,"seconds":1}"""))!!.seconds)
        assertNull(TvFrameJson.feed(json("""{"ok":false,"error":"x"}""")))
        assertNull(TvFrameJson.feed(json("""{"items":[]}""")))
        // switched off: whatever came along is not shown
        assertEquals(0, TvFrameJson.feed(json("""{"on":false,"items":[{"url":"/plugin-media/a/preview"}]}"""))!!.items.size)
    }

    // ─── the states the frame says ───────────────────────────────────

    @Test
    fun `the states of the frame`() {
        assertEquals(TvFrameStatus.Photos(1), TvFrameSchedule.status(feed))
        assertEquals(TvFrameStatus.NoPhotos, TvFrameSchedule.status(feed.copy(on = false)))
        assertEquals(TvFrameStatus.NoPhotos, TvFrameSchedule.status(feed.copy(items = emptyList())))
        assertEquals(TvFrameStatus.NotReady, TvFrameSchedule.status(feed.copy(ready = false, items = emptyList())))
        assertEquals(TvFrameStatus.Failed, TvFrameSchedule.status(null))
        assertEquals(TvFrameStatus.Unavailable, TvFrameSchedule.status(null, unavailable = true))
    }

    // ─── interval, order: the server's unless the TV has its own ─────

    @Test
    fun `interval and order come from the server unless set on the TV`() {
        val own = TvFrameSettings()
        assertEquals(15, TvFrameSchedule.effectiveSeconds(own, feed))
        assertEquals(TvFrameOrder.DATE, TvFrameSchedule.effectiveOrder(own, feed))
        assertEquals(TvFrameSchedule.DEFAULT_SECONDS, TvFrameSchedule.effectiveSeconds(own, null))
        assertEquals(TvFrameOrder.RANDOM, TvFrameSchedule.effectiveOrder(own, null))
        val set = TvFrameSettings(seconds = 60, order = TvFrameOrder.RANDOM)
        assertEquals(60, TvFrameSchedule.effectiveSeconds(set, feed))
        assertEquals(TvFrameOrder.RANDOM, TvFrameSchedule.effectiveOrder(set, feed))
    }

    @Test
    fun `the choices cycle and odd values fall back`() {
        assertEquals(10, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 0))
        assertEquals(0, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 300))
        assertEquals(0, TvFrameSchedule.cycle(TvFrameSchedule.SECONDS, 7))
        assertEquals(TvFrameOrder.RANDOM, TvFrameSchedule.cycle(TvFrameSchedule.ORDERS, null))
        assertEquals(TvFrameOrder.DATE, TvFrameSchedule.cycle(TvFrameSchedule.ORDERS, TvFrameOrder.RANDOM))
        assertNull(TvFrameSchedule.cycle(TvFrameSchedule.ORDERS, TvFrameOrder.DATE))
        assertEquals(5, TvFrameSchedule.cycle(TvFrameSchedule.IDLE_MINUTES, 0))
        assertEquals(0, TvFrameSchedule.cycle(TvFrameSchedule.IDLE_MINUTES, 30))
        assertEquals(0, TvFrameSchedule.seconds(13))
        assertEquals(60, TvFrameSchedule.seconds(60))
        assertEquals(0, TvFrameSchedule.idleMinutes(-4))
        assertEquals(TvFrameOrder.DATE, TvFrameOrder.of("date"))
        assertNull(TvFrameOrder.of("name"))
    }

    // ─── idle start, reading again ───────────────────────────────────

    @Test
    fun `the idle start needs the setting, the time without a key and photos`() {
        val s = TvFrameSettings(idleMinutes = 10)
        val last = 1_000_000L
        val photos = TvFrameStatus.Photos(3)
        assertFalse(TvFrameSchedule.idleDue(s, photos, last, last + 9 * 60_000))
        assertTrue(TvFrameSchedule.idleDue(s, photos, last, last + 10 * 60_000))
        assertFalse(TvFrameSchedule.idleDue(s.copy(idleMinutes = 0), photos, last, last + 60 * 60_000))
        // not known yet or failed: the caller asks GET frame first
        assertTrue(TvFrameSchedule.idleDue(s, TvFrameStatus.Unknown, last, last + 60 * 60_000))
        assertTrue(TvFrameSchedule.idleDue(s, TvFrameStatus.Failed, last, last + 60 * 60_000))
        // nothing shared: no screen saver that only shows the hint
        assertFalse(TvFrameSchedule.idleDue(s, TvFrameStatus.NoPhotos, last, last + 60 * 60_000))
        assertFalse(TvFrameSchedule.idleDue(s, TvFrameStatus.NotReady, last, last + 60 * 60_000))
        assertFalse(TvFrameSchedule.idleDue(s, TvFrameStatus.Unavailable, last, last + 60 * 60_000))
    }

    @Test
    fun `GET frame is asked again every half hour, after a failure in a minute`() {
        val photos = TvFrameStatus.Photos(2)
        assertTrue(TvFrameSchedule.refreshDue(0, 5, photos))
        assertFalse(TvFrameSchedule.refreshDue(1, 29 * 60_000, photos))
        assertTrue(TvFrameSchedule.refreshDue(1, 30 * 60_000 + 1, photos))
        assertFalse(TvFrameSchedule.refreshDue(1, 59_000, TvFrameStatus.Failed))
        assertTrue(TvFrameSchedule.refreshDue(1, 60_001, TvFrameStatus.Failed))
        assertTrue(TvFrameSchedule.refreshDue(1, 60_001, TvFrameStatus.NoPhotos))
        assertTrue(TvFrameSchedule.refreshDue(1, 2, TvFrameStatus.Unknown))
    }

    // ─── the playlist ────────────────────────────────────────────────

    @Test
    fun `by date newest first, every picture once per round`() {
        val p = TvFramePlaylist(listOf(photo(3), photo(1), photo(2)), shuffle = false)
        assertEquals(listOf(1, 2, 3, 1, 2).map { photo(it) }, List(5) { p.next() })
        assertEquals(photo(1), p.previous())
        assertNull(TvFramePlaylist(emptyList(), shuffle = true).next())
        val undated = TvFramePhoto("/plugin-media/u/preview", null)
        assertEquals(listOf(photo(1), photo(2), undated), TvFrameSchedule.byDate(listOf(undated, photo(2), photo(1))))
    }

    @Test
    fun `shuffled every picture once per round, never twice in a row`() {
        val items = (1..6).map { photo(it) }
        repeat(20) { seed ->
            val p = TvFramePlaylist(items, shuffle = true, random = Random(seed))
            val shown = List(18) { p.next()!!.key }
            for (round in shown.chunked(6)) assertEquals(items.map { it.key }.toSet(), round.toSet())
            for (i in 1 until shown.size) assertNotEquals(shown[i - 1], shown[i])
        }
    }

    @Test
    fun `read again the round goes on with the fresh URLs`() {
        val p = TvFramePlaylist(listOf(photo(1), photo(2), photo(3), photo(4)), shuffle = false)
        assertEquals(photo(1), p.next())
        assertEquals(photo(2), p.next())
        // new URLs for all (signed anew), 2 taken back, 5 newly shared: the round goes on with 3, 4; 5 in the next one
        p.update(listOf(photo(1, "n1"), photo(3, "n3"), photo(4, "n4"), photo(5, "n5")))
        assertEquals(listOf(photo(3, "n3"), photo(4, "n4"), photo(1, "n1"), photo(3, "n3"), photo(4, "n4"), photo(5, "n5")), List(6) { p.next() })
    }

    @Test
    fun `photos of the same second are told apart`() {
        val a = TvFramePhoto("/plugin-media/a/preview", "2026-10-01T10:00:00.000Z")
        val b = TvFramePhoto("/plugin-media/b/preview", "2026-10-01T10:00:00.000Z")
        val p = TvFramePlaylist(listOf(a, b), shuffle = false)
        p.next()
        p.update(listOf(a.copy(url = "/plugin-media/a2/preview"), b.copy(url = "/plugin-media/b2/preview")))
        assertEquals("/plugin-media/b2/preview", p.next()!!.url)
        assertEquals(2, p.size)
    }
}
