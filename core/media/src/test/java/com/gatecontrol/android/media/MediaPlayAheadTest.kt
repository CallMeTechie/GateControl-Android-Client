package com.gatecontrol.android.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** „Beim Abspielen mitladen“ (part 2, stage 1): the space, the wait before „Original mit Mitladen“, when to ask. */
class MediaPlayAheadTest {

    private val gb = 1L shl 30
    private val film = 124 * 60_000L

    @Test
    fun `the space - the setting, at most half of what is free beyond the reserve, off below the minimum`() {
        assertEquals(4 * gb, MediaPlayAhead.limitBytes(4, 40 * gb))
        // 6 GB free: (6 − 1) / 2
        assertEquals(5 * gb / 2, MediaPlayAhead.limitBytes(4, 6 * gb))
        assertEquals(0, MediaPlayAhead.limitBytes(0, 40 * gb))
        assertEquals(0, MediaPlayAhead.limitBytes(8, gb + 100L * (1 shl 20)))
    }

    @Test
    fun `how far ahead - most of the space, on mobile data ten minutes of the film`() {
        assertEquals((4 * gb * 0.8).toLong(), MediaPlayAhead.aheadLimit(4 * gb, cellular = false, bitrateBps = 10_500_000))
        // 10 min × 10.5 Mbit/s = 787.5 MB
        assertEquals(787_500_000, MediaPlayAhead.aheadLimit(4 * gb, cellular = true, bitrateBps = 10_500_000))
        assertEquals((4 * gb * 0.8).toLong(), MediaPlayAhead.aheadLimit(4 * gb, cellular = true, bitrateBps = null))
    }

    @Test
    fun `the wait - Dauer times one minus U over R, with a share of the line and a margin`() {
        // the draft's example: 124 min, 10.5 Mbit/s over 9 Mbit/s (of which 85 % counted on)
        val wait = MediaPlayAhead.waitMs(film, 10_500_000, 9_000_000)
        val expected = film * (1 - 9_000_000 * 0.85 / 10_500_000) * 1.05
        assertEquals(expected.toLong(), wait)
        assertEquals(36, MediaPlayAhead.minutes(wait))
        assertEquals(0, MediaPlayAhead.waitMs(film, 8_000_000, 20_000_000))
        // what has to be ahead then: 124 min × (10.5 − 7.65) Mbit/s
        assertEquals((film / 1000.0 * 2_850_000 / 8 * 1.05).toLong(), MediaPlayAhead.aheadNeeded(film, 10_500_000, 9_000_000))
    }

    @Test
    fun `when to ask - away, measured, the original more than the line, it converts, Automatisch or Original`() {
        val fit = MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.AUTO, canConvert = true, alwaysOriginal = false, limitBytes = 4 * gb)
        assertNotNull(fit)
        assertEquals(MediaQuality.step(9_000_000), fit!!.convertedBps)
        assertEquals(MediaPlayAhead.waitMs(film, 10_500_000, 9_000_000), fit.waitMs)
        assertNotNull(MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.ORIGINAL, true, false, 4 * gb))
        // at home, it fits, a fixed step, no measurement, off, a share, "Immer Original"
        assertNull(MediaPlayAhead.fit(true, 9_000_000, 10_500_000, film, MediaQualitySetting.AUTO, true, false, 4 * gb))
        assertNull(MediaPlayAhead.fit(false, 40_000_000, 10_500_000, film, MediaQualitySetting.AUTO, true, false, 4 * gb))
        assertNull(MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.MBIT_4, true, false, 4 * gb))
        assertNull(MediaPlayAhead.fit(false, null, 10_500_000, film, MediaQualitySetting.AUTO, true, false, 4 * gb))
        assertNull(MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.AUTO, true, false, 0))
        assertNull(MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.AUTO, false, false, 4 * gb))
        assertNull(MediaPlayAhead.fit(false, 9_000_000, 10_500_000, film, MediaQualitySetting.AUTO, true, true, 4 * gb))
    }

    @Test
    fun `a 4K remux over a thin line does not fit the space - no waiting offered`() {
        val fit = MediaPlayAhead.fit(false, 10_000_000, 60_000_000, 120 * 60_000L, MediaQualitySetting.AUTO, true, false, 4 * gb)
        assertNotNull(fit)
        assertNull(fit!!.waitMs)
    }

    @Test
    fun `may it start - what is ahead and what comes meanwhile carry the rest`() {
        val rest = 60 * 60_000L
        val bitrate = 10_000_000L
        val rate = 8_000_000L
        assertFalse(MediaPlayAhead.canStart(0, rest, bitrate, rate, complete = false))
        val left = MediaPlayAhead.waitLeftMs(0, rest, bitrate, rate)!!
        // after that wait at that speed it may
        val ahead = MediaPlayAhead.bytesFor(left, rate) + 1_000_000
        assertTrue(MediaPlayAhead.canStart(ahead, rest, bitrate, rate, complete = false))
        assertTrue(MediaPlayAhead.canStart(0, rest, bitrate, rate, complete = true))
        assertNull(MediaPlayAhead.waitLeftMs(0, rest, bitrate, 0))
        assertEquals(0, MediaPlayAhead.waitLeftMs(ahead, rest, bitrate, rate))
    }

    @Test
    fun `the cache key leaves out what changes with each request`() {
        val a = "https://media.example.test/Videos/m1/stream?static=true&mediaSourceId=v1&playSessionId=abc&deviceId=gc-1"
        val b = "https://media.example.test/Videos/m1/stream?static=true&mediaSourceId=v1&playSessionId=def&deviceId=gc-1"
        assertEquals(MediaPlayAhead.cacheKey(a), MediaPlayAhead.cacheKey(b))
        assertEquals("https://media.example.test/Videos/m1/stream?static=true&mediaSourceId=v1&deviceId=gc-1", MediaPlayAhead.cacheKey(a))
        assertEquals("gcshare://s1/Filme/Film.mkv", MediaPlayAhead.cacheKey("gcshare://s1/Filme/Film.mkv"))
        assertEquals("https://media.example.test/v", MediaPlayAhead.cacheKey("https://media.example.test/v?PlaySessionId=x"))
    }

    @Test
    fun `how long what is ahead plays`() {
        assertEquals(60_000, MediaPlayAhead.aheadMs(75_000_000, 10_000_000))
        assertNull(MediaPlayAhead.aheadMs(1, null))
        assertEquals(MediaAwayOriginal.WAIT, MediaAwayOriginal.fromWire("wait"))
        assertEquals(MediaAwayOriginal.ASK, MediaAwayOriginal.fromWire(null))
    }
}
