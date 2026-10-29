package com.gatecontrol.android.drive.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveWifiTimelineTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    private fun at(m: Int) = t0 + m * min

    @Test
    fun `a look of the same session and WLAN adds nothing, another session does`() {
        var list = DriveWifiTimeline.add(emptyList(), WifiSeen(at(0), "Firma", "a"))
        val same = DriveWifiTimeline.add(list, WifiSeen(at(5), "Firma", "a"))
        assertSame(list, same)
        list = DriveWifiTimeline.add(list, WifiSeen(at(10), "Firma", "b"))
        assertEquals(2, list.size)
        list = DriveWifiTimeline.add(list, WifiSeen(at(12), null, "b"))
        assertEquals(listOf("Firma", "Firma", null), list.map { it.ssid })
        // a late look goes into its place
        list = DriveWifiTimeline.add(list, WifiSeen(at(11), "Firma", "c"), now = at(12))
        assertEquals(listOf(at(0), at(10), at(11), at(12)), list.map { it.at })
    }

    @Test
    fun `within one session the WLAN holds until the next look`() {
        val list = listOf(WifiSeen(at(0), "Firma", "a"), WifiSeen(at(300), null, "a"))
        assertEquals("Firma", DriveWifiTimeline.wifiAt(list, at(200), current = "x"))
        assertNull(DriveWifiTimeline.wifiAt(list, at(301), current = "x"))
    }

    @Test
    fun `across a gap only the same WLAN on both sides holds`() {
        val same = listOf(WifiSeen(at(0), "Firma", "a"), WifiSeen(at(300), "Firma", "b"))
        assertEquals("Firma", DriveWifiTimeline.wifiAt(same, at(150), current = "c"))
        val other = listOf(WifiSeen(at(0), "Firma", "a"), WifiSeen(at(300), null, "b"))
        // the app was not running: when it left the WLAN is unknown
        assertNull(DriveWifiTimeline.wifiAt(other, at(150), current = "c"))
        // a look close to the photo counts
        assertEquals("Firma", DriveWifiTimeline.wifiAt(other, at(1), current = "c"))
        assertNull(DriveWifiTimeline.wifiAt(other, at(299), current = "c"))
        val home = listOf(WifiSeen(at(0), "Firma", "a"), WifiSeen(at(300), "Zuhause", "b"))
        assertEquals("Zuhause", DriveWifiTimeline.wifiAt(home, at(299), current = "c"))
    }

    @Test
    fun `after the last look only this running session, or a moment, counts`() {
        val list = listOf(WifiSeen(at(0), "Firma", "a"))
        assertEquals("Firma", DriveWifiTimeline.wifiAt(list, at(500), current = "a"))
        assertNull(DriveWifiTimeline.wifiAt(list, at(500), current = "b"))
        assertEquals("Firma", DriveWifiTimeline.wifiAt(list, at(1), current = "b"))
        // before the first look
        assertEquals("Firma", DriveWifiTimeline.wifiAt(list, at(-1), current = "a"))
        assertNull(DriveWifiTimeline.wifiAt(list, at(-10), current = "a"))
        assertNull(DriveWifiTimeline.wifiAt(emptyList(), at(0), current = "a"))
    }

    @Test
    fun `old looks go, the last before the cut stays`() {
        val day = 24 * 60
        val list = listOf(WifiSeen(at(0), "A", "a"), WifiSeen(at(day), "B", "a"), WifiSeen(at(40 * day), "C", "a"))
        val pruned = DriveWifiTimeline.prune(list, now = at(40 * day))
        assertEquals(listOf("B", "C"), pruned.map { it.ssid })
        val all = DriveWifiTimeline.prune(list.take(2), now = at(40 * day))
        assertEquals(listOf("B"), all.map { it.ssid })
        val many = (0 until DriveWifiTimeline.MAX + 10).map { WifiSeen(at(it), if (it % 2 == 0) "A" else "B", "s") }
        assertEquals(DriveWifiTimeline.MAX, DriveWifiTimeline.prune(many, now = at(DriveWifiTimeline.MAX + 10)).size)
    }

    @Test
    fun `kept as JSON, anything broken reads as empty`() {
        val list = listOf(WifiSeen(at(0), "Firma \"Gast\"", "a"), WifiSeen(at(1), null, "b"))
        assertEquals(list, DriveWifiTimeline.decode(DriveWifiTimeline.encode(list)))
        assertTrue(DriveWifiTimeline.decode(null).isEmpty())
        assertTrue(DriveWifiTimeline.decode("{").isEmpty())
        assertEquals(1, DriveWifiTimeline.decode("""{"v":1,"s":[[1,"A","s"],["x","B","s"],[2,"C"]]}""").size)
    }
}
