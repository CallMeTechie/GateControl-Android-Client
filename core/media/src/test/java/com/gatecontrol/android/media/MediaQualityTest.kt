package com.gatecontrol.android.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The choice of quality by bandwidth (decision 10): at home the original, away only converted when needed. */
class MediaQualityTest {

    private val original = MediaQuality.ORIGINAL_BPS

    @Test
    fun `at home always the original`() {
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.AUTO, atHome = true, measuredBps = 5_000_000, originalBps = 52_000_000))
        val offer = MediaQuality.offer(atHome = true, measuredBps = null, originalBps = 52_000_000)
        assertTrue(offer.originalFits)
        assertNull(offer.adaptedBps)
        assertFalse(offer.suggestAdapted)
    }

    @Test
    fun `away 4K over 18 Mbit per s - the NAS converts to 8`() {
        // the mockup: 52 Mbit/s original, 18 Mbit/s measured → "1080p · 8 Mbit/s"
        assertEquals(8_000_000L, MediaQuality.cap(MediaQualitySetting.AUTO, atHome = false, measuredBps = 18_000_000, originalBps = 52_000_000))
        val offer = MediaQuality.offer(atHome = false, measuredBps = 18_000_000, originalBps = 52_000_000)
        assertFalse(offer.originalFits)
        assertEquals(8_000_000L, offer.adaptedBps)
        assertTrue(offer.suggestAdapted)
        assertEquals("1080p", MediaQuality.resolution(8_000_000))
    }

    @Test
    fun `away a small file goes as it is`() {
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.AUTO, atHome = false, measuredBps = 40_000_000, originalBps = 9_000_000))
        val offer = MediaQuality.offer(atHome = false, measuredBps = 40_000_000, originalBps = 9_000_000)
        assertTrue(offer.originalFits)
        assertNull(offer.adaptedBps)
    }

    @Test
    fun `immer Original and a fixed step`() {
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.AUTO, false, 3_000_000, 52_000_000, alwaysOriginal = true))
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.ORIGINAL, false, 3_000_000, 52_000_000))
        assertEquals(4_000_000L, MediaQuality.cap(MediaQualitySetting.MBIT_4, true, null, 52_000_000))
        // the offer still shows what fits, but suggests the original
        assertFalse(MediaQuality.offer(false, 18_000_000, 52_000_000, alwaysOriginal = true).suggestAdapted)
    }

    @Test
    fun `a share cannot convert`() {
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.MBIT_2, false, 1_000_000, 52_000_000, canConvert = false))
        assertNull(MediaQuality.offer(false, 1_000_000, 52_000_000, canConvert = false).adaptedBps)
    }

    @Test
    fun `away without a measurement a careful start`() {
        assertEquals(MediaQuality.UNMEASURED_BPS, MediaQuality.cap(MediaQualitySetting.AUTO, false, null, 52_000_000))
        assertEquals(MediaQuality.UNMEASURED_BPS, MediaQuality.cap(MediaQualitySetting.AUTO, false, null, null))
        assertEquals(original, MediaQuality.cap(MediaQualitySetting.AUTO, false, null, 3_000_000))
        assertTrue(MediaQuality.offer(false, null, 52_000_000).suggestAdapted)
    }

    @Test
    fun `the steps of the ladder`() {
        assertEquals(20_000_000L, MediaQuality.step(100_000_000))
        assertEquals(12_000_000L, MediaQuality.step(25_000_000))
        assertEquals(4_000_000L, MediaQuality.step(7_000_000))
        assertEquals(1_000_000L, MediaQuality.step(500_000))
        assertFalse(MediaQuality.fits(null, 100_000_000))
    }

    @Test
    fun `labels`() {
        assertEquals("18 Mbit/s", MediaQuality.label(18_400_000))
        assertEquals("8 Mbit/s", MediaQuality.label(8_000_000))
        assertEquals("2,5 Mbit/s", MediaQuality.label(2_500_000))
        assertEquals("800 kbit/s", MediaQuality.label(800_000))
        assertEquals("720p", MediaQuality.resolution(4_000_000))
        assertEquals("480p", MediaQuality.resolution(1_000_000))
        assertEquals("1080p", MediaQuality.videoLabel(MediaStream(0, MediaStream.Type.VIDEO, width = 1920, height = 800)))
        assertEquals("4K Dolby Vision", MediaQuality.videoLabel(MediaStream(0, MediaStream.Type.VIDEO, height = 2160, videoRangeType = "DOVIWithHDR10")))
        assertNull(MediaQuality.hdrLabel(MediaStream(0, MediaStream.Type.VIDEO, height = 1080, videoRange = "SDR", videoRangeType = "SDR")))
        assertEquals(MediaQualitySetting.MBIT_8, MediaQualitySetting.fromWire("8"))
        assertEquals(MediaQualitySetting.AUTO, MediaQualitySetting.fromWire("bogus"))
    }
}
