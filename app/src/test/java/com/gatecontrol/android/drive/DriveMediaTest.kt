package com.gatecontrol.android.drive

import androidx.media3.common.PlaybackException
import com.gatecontrol.android.scan.MediaScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The viewer of pictures and videos (Drive 1.21): what it shows, where the player may stream from, the places. */
class DriveMediaTest {

    private fun node(id: Long, name: String, mime: String? = null, dir: Boolean = false, vault: String? = null, rev: Int = 1, scan: MediaScan? = null) = DNode(
        id = id, areaId = "u:1", parentId = 1, name = name, isDir = dir, size = 10, mime = mime, mediaId = "m$id",
        rev = rev, mtime = null, updatedAt = null, updatedBy = null, vaultId = vault, scan = scan,
    )

    @Test
    fun `pictures and videos are viewable, folders, documents and vault files are not`() {
        assertTrue(DriveMediaLogic.viewable(node(1, "a.jpg", "image/jpeg")))
        assertTrue(DriveMediaLogic.viewable(node(2, "b.mov", "video/quicktime")))
        assertTrue(DriveMediaLogic.viewable(node(3, "c.mkv", null)), "by its name when the type is unknown")
        assertTrue(DriveMediaLogic.viewable(node(4, "d.heic", "application/octet-stream")))
        assertFalse(DriveMediaLogic.viewable(node(5, "e.pdf", "application/pdf")))
        assertFalse(DriveMediaLogic.viewable(node(6, "Fotos", dir = true)))
        assertFalse(DriveMediaLogic.viewable(node(7, "0f3a", "application/octet-stream", vault = "v1")), "a vault's content is ciphertext")
        assertTrue(DriveMediaLogic.isVideo(node(8, "f.webm", "video/webm")))
        assertFalse(DriveMediaLogic.isVideo(node(9, "g.png", "image/png")))
    }

    @Test
    fun `the viewer goes through the pictures and videos of the folder, from the one tapped`() {
        val items = listOf(
            node(1, "Ordner", dir = true), node(2, "a.jpg", "image/jpeg"), node(3, "Brief.pdf", "application/pdf"),
            node(4, "b.mp4", "video/mp4"), node(5, "c.png", "image/png"),
        )
        val (list, at) = DriveMediaLogic.viewerList(items, items[3])
        assertEquals(listOf(2L, 4L, 5L), list.map { it.id })
        assertEquals(1, at)
        // a node no longer in the list (a reload meanwhile): just that one
        val gone = node(9, "x.jpg", "image/jpeg")
        assertEquals(listOf(gone) to 0, DriveMediaLogic.viewerList(items, gone))
    }

    @Test
    fun `the stream address is the app media route of the own server`() {
        assertEquals(
            "https://gc.example.com/api/v1/client/plugins/gatecontrol-drive/media/abcDEF_123-xyz",
            DriveMediaLogic.mediaUrl("https://gc.example.com/", "abcDEF_123-xyz"),
        )
        assertNull(DriveMediaLogic.mediaUrl("https://gc.example.com", "../etc/passwd"))
        assertNull(DriveMediaLogic.mediaUrl("https://gc.example.com", "a b"))
    }

    @Test
    fun `the player loads only Drive files of the own server`() {
        val s = "https://gc.example.com"
        val ok = "https://gc.example.com/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"
        assertTrue(DriveMediaLogic.allowed(s, ok))
        assertTrue(DriveMediaLogic.allowed("https://GC.example.com/", ok))
        assertFalse(DriveMediaLogic.allowed(s, "http://gc.example.com/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"), "another scheme")
        assertFalse(DriveMediaLogic.allowed(s, "https://evil.example.com/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"), "another host")
        assertFalse(DriveMediaLogic.allowed(s, "https://gc.example.com:8443/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"), "another port")
        assertFalse(DriveMediaLogic.allowed(s, "https://gc.example.com/api/v1/client/plugins/gatecontrol-mail/media/abcdef123456"), "another plugin")
        assertFalse(DriveMediaLogic.allowed(s, "$ok?x=1"), "no query")
        assertFalse(DriveMediaLogic.allowed(s, "$ok/preview"), "nothing below")
        assertFalse(DriveMediaLogic.allowed(s, "https://user@gc.example.com/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"))
        assertFalse(DriveMediaLogic.allowed(s, "https://gc.example.com/api/v1/client/plugins/gatecontrol-drive/media/../../me"))
        // a server below a path
        assertTrue(DriveMediaLogic.allowed("https://example.com/gc", "https://example.com/gc/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"))
        assertFalse(DriveMediaLogic.allowed("https://example.com/gc", "https://example.com/api/v1/client/plugins/gatecontrol-drive/media/abcdef123456"))
    }

    @Test
    fun `player errors - the format, the network, else`() {
        assertEquals(DriveMediaLogic.Trouble.CANNOT_PLAY, DriveMediaLogic.trouble(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(DriveMediaLogic.Trouble.CANNOT_PLAY, DriveMediaLogic.trouble(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(DriveMediaLogic.Trouble.CANNOT_PLAY, DriveMediaLogic.trouble(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(DriveMediaLogic.Trouble.NETWORK, DriveMediaLogic.trouble(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(DriveMediaLogic.Trouble.OTHER, DriveMediaLogic.trouble(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals("MKV", DriveMediaLogic.typeName(node(1, "Konzert.mkv", "video/x-matroska")))
        assertEquals("video/mp4", DriveMediaLogic.typeName(node(2, "ohne-endung", "video/mp4")))
    }

    @Test
    fun `originals are decoded to at most 4096 px`() {
        assertEquals(1, DriveMediaLogic.sampleSize(4000, 3000))
        assertEquals(1, DriveMediaLogic.sampleSize(4096, 4096))
        assertEquals(2, DriveMediaLogic.sampleSize(6000, 4000))
        assertEquals(4, DriveMediaLogic.sampleSize(12000, 9000))
        assertEquals(2, DriveMediaLogic.sampleSize(3000, 8000))
    }

    @Test
    fun `a video's place - kept after 5 s, not near the end, per revision, the last ones only`() {
        val p = DriveVideoPlaces(max = 2)
        val a = node(1, "a.mp4", "video/mp4")
        p.keep(a, 3_000, 60_000)
        assertNull(p.get(a), "not within the first 5 s")
        p.keep(a, 30_000, 60_000)
        assertEquals(30_000L, p.get(a))
        assertNull(p.get(a.copy(rev = 2)), "a new revision starts from the beginning")
        p.keep(a, 57_000, 60_000)
        assertNull(p.get(a), "near the end: from the beginning next time")
        p.keep(a, 20_000, 0)
        assertEquals(20_000L, p.get(a), "a stream without a known length")
        val b = node(2, "b.mp4", "video/mp4")
        val c = node(3, "c.mp4", "video/mp4")
        p.keep(b, 10_000, 60_000)
        p.keep(c, 10_000, 60_000)
        assertNull(p.get(a), "the oldest goes")
        assertEquals(10_000L, p.get(c))
        p.forget(c)
        assertNull(p.get(c))
    }

    @Test
    fun `a blocked file is not streamed - its refusal code comes first`() {
        val pending = node(1, "a.mp4", "video/mp4", scan = MediaScan(state = MediaScan.PENDING, blocked = true))
        assertTrue(pending.blocked)
        assertEquals("scan_pending", pending.scan?.refusalCode)
        val marked = node(2, "b.mp4", "video/mp4", scan = MediaScan(state = MediaScan.INFECTED, blocked = false, finding = "X"))
        assertFalse(marked.blocked)
        assertTrue(marked.scan?.warn == true, "asked first, then played")
    }
}
