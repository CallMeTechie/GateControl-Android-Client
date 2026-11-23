package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaAddress
import com.gatecontrol.android.media.MediaSourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareNamesTest {

    @Test
    fun `films - title and year from the file name`() {
        assertEquals(ShareNames.Parsed("Kobaltnacht", 2025), ShareNames.parse("Kobaltnacht (2025).mkv"))
        assertEquals(ShareNames.Parsed("Die letzte Fähre nach Mirow", 2022), ShareNames.parse("Die.letzte.Fähre.nach.Mirow.2022.1080p.BluRay.x265.mkv"))
        assertEquals(ShareNames.Parsed("Moorlicht"), ShareNames.parse("Moorlicht.mp4"))
        assertEquals(ShareNames.Parsed("Wolkenbruch & Söhne", 2023), ShareNames.parse("Wolkenbruch & Söhne [2023] German DL.mkv"))
        // a title that is only a year stays a title
        assertEquals(ShareNames.Parsed("2012"), ShareNames.parse("2012.mkv"))
        assertEquals(ShareNames.Parsed("Sternwarte 9", 2026), ShareNames.parse("Sternwarte_9_2026_2160p_HDR.mkv"))
    }

    @Test
    fun `episodes - SxxEyy, 1x02 and numbers in a season folder`() {
        val a = ShareNames.parse("Graufeld.S02E04.Die.Fähre.1080p.WEB-DL.mkv")
        assertEquals(ShareNames.Parsed("Graufeld", season = 2, episode = 4, episodeName = "Die Fähre"), a)
        assertTrue(a.isEpisode)
        assertEquals(ShareNames.Parsed("Die Lindqvist-Akten", season = 1, episode = 7), ShareNames.parse("Die Lindqvist-Akten - s01e07.mkv"))
        assertEquals(ShareNames.Parsed("Ankerplatz 12", season = 1, episode = 2, episodeName = "Ebbe"), ShareNames.parse("Ankerplatz 12 1x02 Ebbe.mp4"))
        // the series from its folder when the file has only the number
        assertEquals(
            ShareNames.Parsed("Graufeld", season = 1, episode = 3, episodeName = "Nebel"),
            ShareNames.parse("03 - Nebel.mkv", listOf("Serien", "Graufeld", "Staffel 1")),
        )
        assertEquals(ShareNames.Parsed("Hofgeflüster", season = 4, episode = 1), ShareNames.parse("S04E01.mkv", listOf("Hofgeflüster (2019)", "Season 04")))
        // a number in front without a season folder is a film
        assertFalse(ShareNames.parse("12 Monkeys.mkv", listOf("Filme")).isEpisode)
    }

    @Test
    fun `what is a video, what is left out`() {
        assertTrue(ShareNames.isVideo("Kobaltnacht.MKV"))
        assertTrue(ShareNames.isVideo("a.m2ts"))
        assertFalse(ShareNames.isVideo("Kobaltnacht.nfo"))
        assertFalse(ShareNames.isVideo("._Kobaltnacht.mkv"))
        assertFalse(ShareNames.isVideo("Kobaltnacht-sample.mkv"))
        assertTrue(ShareNames.isImage("poster.JPG"))
        assertTrue(ShareNames.skipDir("@eaDir"))
        assertTrue(ShareNames.skipDir("#recycle"))
        assertTrue(ShareNames.skipDir("Extras"))
        assertFalse(ShareNames.skipDir("Filme"))
        assertEquals(2, ShareNames.seasonOfDir("Staffel 2"))
        assertEquals(10, ShareNames.seasonOfDir("Season 10"))
        assertNull(ShareNames.seasonOfDir("Graufeld"))
    }

    @Test
    fun `keys - the same title written differently, A to Z as Jellyfin`() {
        assertEquals(ShareNames.key("Die letzte Fähre nach Mirow"), ShareNames.key("die.letzte.fähre.nach.mirow"))
        assertEquals("letzte fahre", ShareNames.sortKey("Letzte Fähre"))
        assertEquals("pegelwart", ShareNames.sortKey("The Pegelwart"))
        assertEquals("der pegelwart", ShareNames.sortKey("Der Pegelwart"))
    }

    @Test
    fun `share addresses - UNC, smb URL, WebDAV, only the home network for SMB`() {
        val unc = MediaAddress.parseSmb("\\\\nas\\filme")!!
        assertEquals(MediaAddress.Smb("nas", 445, "filme", ""), unc)
        assertEquals("smb://nas/filme", unc.url)
        assertEquals("\\\\nas\\filme", unc.unc)
        assertEquals(MediaAddress.Smb("192.168.1.20", 445, "filme", "Neu/2026"), MediaAddress.parseSmb("smb://192.168.1.20/filme/Neu/2026/"))
        assertEquals("\\\\192.168.1.20\\filme\\Neu", MediaAddress.parseSmb("192.168.1.20/filme/Neu")!!.unc)
        assertEquals(MediaAddress.Smb("nas.local", 4445, "filme", ""), MediaAddress.parseSmb("smb://NAS.local:4445/filme"))
        // no share, a host on the internet, a way out
        assertNull(MediaAddress.parseSmb("\\\\nas"))
        assertNull(MediaAddress.parseSmb("smb://media.example.test/filme"))
        assertNull(MediaAddress.parseSmb("smb://203.0.113.5/filme"))
        assertNull(MediaAddress.parseSmb("\\\\nas\\filme\\..\\geheim"))
        assertNull(MediaAddress.parseSmb("https://192.168.1.20/filme"))
        assertNull(MediaAddress.parseSmb("smb://user@nas/filme"))

        assertEquals("https://192.168.1.20:5006/filme", MediaAddress.normalizeWebDav("192.168.1.20:5006/filme"))
        assertEquals("https://dav.example.test/filme", MediaAddress.normalizeWebDav("https://dav.example.test/filme/"))
        assertEquals("http://192.168.1.20:5005/filme", MediaAddress.normalizeWebDav("http://192.168.1.20:5005/filme"))
        // plain HTTP only in the home network
        assertNull(MediaAddress.normalizeWebDav("http://dav.example.test/filme"))

        assertEquals("filme", MediaAddress.folderName(MediaSourceKind.SMB, "smb://nas/filme"))
        assertEquals("filme", MediaAddress.folderName(MediaSourceKind.WEBDAV, "https://192.168.1.20:5006/Filme"))
        assertEquals("neu", MediaAddress.folderName(MediaSourceKind.SMB, "smb://nas/filme/Neu"))
    }

    @Test
    fun `player URLs of a share's files`() {
        val uri = ShareUri.of("Filme/Kobaltnacht (2025) #1.mkv")
        assertEquals("gcshare://share/Filme/Kobaltnacht%20%282025%29%20%231.mkv", uri)
        assertEquals("Filme/Kobaltnacht (2025) #1.mkv", ShareUri.path(uri))
        assertEquals("Filme/a+b.mkv", ShareUri.path(ShareUri.of("Filme/a+b.mkv")))
        assertNull(ShareUri.path("gcshare://share/Filme/..%2F..%2Fetc"))
        assertNull(ShareUri.path("https://192.168.1.20/a.mkv"))
        assertEquals("a/b", SharePaths.clean("/a//b/"))
        assertNull(SharePaths.clean("a/../b"))
    }
}
