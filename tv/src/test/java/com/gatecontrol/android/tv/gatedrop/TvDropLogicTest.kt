package com.gatecontrol.android.tv.gatedrop

import com.gatecontrol.android.gatedrop.GdDevice
import com.gatecontrol.android.gatedrop.GdEntry
import com.gatecontrol.android.gatedrop.GdItem
import com.gatecontrol.android.gatedrop.GdOwner
import com.gatecontrol.android.gatedrop.GdPerson
import com.gatecontrol.android.gatedrop.GdTargets
import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TvDropLogicTest {

    private fun item(name: String, mime: String = "image/jpeg", bytes: Long = 1024, media: String? = "m-$name", available: Boolean = true, scan: MediaScan? = null) =
        GdItem(media, name, mime, bytes, available, scan)

    @Test
    fun `file names stay in the drop folder`() {
        assertEquals("0-IMG_2041.jpg", TvDropLogic.fileName(0, "IMG_2041.jpg"))
        assertEquals("1-passwd", TvDropLogic.fileName(1, "../../etc/passwd"))
        assertEquals("2-a_b.txt", TvDropLogic.fileName(2, "a:b.txt"))
        assertEquals("3-file", TvDropLogic.fileName(3, "..."))
        assertEquals(2 + 80, TvDropLogic.fileName(0, "x".repeat(300) + ".jpg").length)
    }

    @Test
    fun `what the TV fetches`() {
        assertTrue(TvDropLogic.fetchable(item("a.jpg")))
        assertFalse(TvDropLogic.fetchable(item("a.jpg", media = null)))
        assertFalse(TvDropLogic.fetchable(item("a.jpg", available = false)))
        assertFalse(TvDropLogic.fetchable(item("a.jpg", scan = MediaScan(MediaScan.INFECTED, blocked = true))))
        assertFalse(TvDropLogic.fetchable(item("big.mkv", "video/x-matroska", bytes = 3L * 1024 * 1024 * 1024)))
        assertTrue(TvDropLogic.media("video/mp4"))
        assertFalse(TvDropLogic.media("application/pdf"))
    }

    @Test
    fun `the newest stays, the oldest go beyond the limits`() {
        fun e(id: String, at: Long, bytes: Long = 10) = TvDropEntry(id, "Mila", at, files = listOf(TvDropFile("a", "image/jpeg", bytes, "$id/0-a")))
        val old = (1..TvDropLogic.MAX_ENTRIES).map { e("s$it", it.toLong()) }
        val (kept, gone) = TvDropLogic.prune(old, e("new", 1_000))
        assertEquals(TvDropLogic.MAX_ENTRIES, kept.size)
        assertEquals("new", kept.first().sendId)
        assertEquals(listOf("s1"), gone.map { it.sendId })
        val (k2, g2) = TvDropLogic.prune(listOf(e("x", 1, TvDropLogic.MAX_BYTES)), e("y", 2, TvDropLogic.MAX_BYTES))
        assertEquals(listOf("y"), k2.map { it.sendId })
        assertEquals(listOf("x"), g2.map { it.sendId })
    }

    @Test
    fun `asked once, never for what is kept, expired or removed`() {
        val send = GdEntry("s1", 1, "Mila", items = listOf(item("a.jpg")))
        assertTrue(TvDropLogic.shouldAsk(send, emptySet(), emptyList()))
        assertFalse(TvDropLogic.shouldAsk(send, setOf("s1"), emptyList()))
        assertFalse(TvDropLogic.shouldAsk(send, emptySet(), listOf(TvDropEntry("s1", "Mila", 1))))
        assertFalse(TvDropLogic.shouldAsk(send.copy(expired = true), emptySet(), emptyList()))
        assertFalse(TvDropLogic.shouldAsk(send.copy(removed = true), emptySet(), emptyList()))
    }

    @Test
    fun `own devices first, online first, then people and others' devices`() {
        val t = GdTargets(
            people = listOf(GdPerson("u2", "Mila")),
            devices = listOf(GdDevice("d1", "Laptop", "windows", online = false), GdDevice("d2", "Pixel", "android", online = true)),
            peopleDevices = listOf(GdDevice("d9", "Tablet", "android", online = true, owner = GdOwner("u3", "Jonas"))),
        )
        val list = TvDropLogic.targets(t, ownDeviceId = null)
        assertEquals(listOf("d2", "d1", "u2", "d9"), list.map { it.id })
        assertEquals("Tablet · Jonas", list.last().name)
        assertFalse(list[2].device)
        assertEquals(listOf("d2", "u2", "d9"), TvDropLogic.targets(t, ownDeviceId = "d1").map { it.id })
    }

    @Test
    fun sizes() {
        assertEquals("900 B", TvDropLogic.size(900, Locale.ROOT))
        assertEquals("4.1 MB", TvDropLogic.size((4.1 * 1024 * 1024).toLong(), Locale.ROOT))
        assertEquals("48 MB", TvDropLogic.size(48L * 1024 * 1024, Locale.ROOT))
        assertEquals("4,1 MB", TvDropLogic.size((4.1 * 1024 * 1024).toLong(), Locale.GERMANY))
    }

    private fun kept(name: String, mime: String, path: String? = "s/0-$name") = TvDropFile(name, mime, 1024, path)

    @Test
    fun `the card of a send on the start page`() {
        fun e(vararg f: TvDropFile, text: String? = null, link: String? = null) = TvDropEntry("s1", "Mila", 1, text, link, f.toList())
        assertEquals(TvDropSummary(TvDropKind.FILE, 1, "Rezept.pdf"), TvDropLogic.summary(e(kept("Rezept.pdf", "application/pdf"))))
        assertEquals(TvDropSummary(TvDropKind.PHOTOS, 3), TvDropLogic.summary(e(kept("a.jpg", "image/jpeg"), kept("b.png", "image/png"), kept("c.heic", "image/heic"))))
        assertEquals(TvDropKind.VIDEOS, TvDropLogic.summary(e(kept("a.mp4", "video/mp4"), kept("b.mkv", "video/x-matroska"))).kind)
        assertEquals(TvDropKind.MEDIA, TvDropLogic.summary(e(kept("a.jpg", "image/jpeg"), kept("b.mp4", "video/mp4"))).kind)
        assertEquals(TvDropKind.FILES, TvDropLogic.summary(e(kept("a.jpg", "image/jpeg"), kept("b.pdf", "application/pdf"))).kind)
        assertEquals(TvDropSummary(TvDropKind.TEXT, 0, "Hallo"), TvDropLogic.summary(e(text = "Hallo")))
        assertEquals(TvDropSummary(TvDropKind.LINK, 0, "https://example.com"), TvDropLogic.summary(e(link = "https://example.com")))
        // the picture: the first one kept on the TV
        assertEquals(1, TvDropLogic.cover(e(kept("a.pdf", "application/pdf"), kept("b.jpg", "image/jpeg"))))
        assertEquals(null, TvDropLogic.cover(e(kept("b.jpg", "image/jpeg", path = null), kept("v.mp4", "video/mp4"))))
        assertEquals(1, TvDropLogic.fresh(listOf(e(), e().copy(sendId = "s2", seen = true))))
    }

    @Test
    fun `links the TV hands to a browser`() {
        assertTrue(TvDropLogic.openableLink("https://example.com/a?b=c"))
        assertTrue(TvDropLogic.openableLink("http://example.test"))
        assertFalse(TvDropLogic.openableLink(null))
        assertFalse(TvDropLogic.openableLink("javascript:alert(1)"))
        assertFalse(TvDropLogic.openableLink("file:///data/data/x"))
        assertFalse(TvDropLogic.openableLink("https://example.com/a b"))
    }
}
