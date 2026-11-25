package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The page "Ordner" (stage 4d): a share's folder as rows, the subtitle files beside a video, the search below a folder. */
class ShareFolderTest {

    private val source = MediaSource("nas1", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme")
    private val b = ByteArray(10)
    private val fs = FakeShareFs(
        mapOf(
            "Familie/Urlaub 2025/Strand.mp4" to b,
            "Familie/Urlaub 2025/Hafen.mp4" to b,
            "Familie/Schulfeste/Sommerfest.mkv" to b,
            "Familie/@eaDir/x.jpg" to b,
            "Familie/Geburtstag Oma.mkv" to ByteArray(1800),
            "Familie/Geburtstag Oma.jpg" to b,
            "Familie/Geburtstag Oma.de.srt" to b,
            "Familie/Geburtstag Oma.eng.forced.srt" to b,
            "Familie/Geburtstag Oma.txt" to b,
            "Familie/Fußballturnier Jonas.mp4" to b,
            "Familie/Silvester am See.DTS.mkv" to b,
            "Familie/Extras/Tief/Tiefer/Tiefst/Unten/Ganz/Weiter/Versteckt.mkv" to b,
        ),
    )
    private val progress = MapProgress()
    private var now = 1_000_000L
    private val lib = ShareLibrary(source, fs, progress, now = { now })

    @Test
    fun `a folder - its sub-folders A-Z without hidden ones, the videos with picture and subtitles`() = runTest {
        val f = lib.folder("Familie").valueOrNull()!!
        assertEquals(listOf("Extras", "Schulfeste", "Urlaub 2025"), f.folders.map { it.name })
        assertEquals(listOf("Fußballturnier Jonas.mp4", "Geburtstag Oma.mkv", "Silvester am See.DTS.mkv"), f.videos.map { it.entry.name })
        val oma = f.videos[1]
        assertEquals("Geburtstag Oma", oma.title.name)
        assertEquals("Familie/Geburtstag Oma.jpg", oma.title.posterId)
        assertEquals(1800, oma.entry.size)
        assertTrue(oma.title.fromShare)
        assertEquals(listOf("de" to false, "en" to true), oma.subtitles.map { it.language to it.forced })
        assertEquals("application/x-subrip", oma.subtitles[0].mime)
        assertTrue(f.videos[0].subtitles.isEmpty())
        // what a folder holds, for its row
        assertEquals(ShareFolderCount(folders = 0, videos = 2), lib.count("Familie/Urlaub 2025"))
        assertEquals(ShareFolderCount(folders = 3, videos = 3), lib.count("Familie"))
        // no way out of the share
        assertEquals(MediaResult.NotFound, lib.folder("../etc"))
    }

    @Test
    fun `the progress on the phone - 90 percent watched, under 30 s no place`() = runTest {
        val oma = lib.folder("Familie").valueOrNull()!!.videos[1].title
        val plan = lib.play(MediaPlay(oma.id)).valueOrNull()!!
        lib.stopped(MediaProgress(plan, 20_000, paused = true, durationMs = 2_520_000))
        assertEquals(0, lib.folder("Familie").valueOrNull()!!.videos[1].title.positionMs)
        now += 60_000
        lib.stopped(MediaProgress(plan, 1_380_000, paused = true, durationMs = 2_520_000))
        val begun = lib.folder("Familie").valueOrNull()!!.videos[1].title
        assertEquals(1_380_000, begun.positionMs)
        assertEquals(2_520_000, begun.runtimeMs)
        assertEquals(1_140_000, begun.leftMs)
        now += 60_000
        lib.stopped(MediaProgress(plan, 2_300_000, paused = false, durationMs = 2_520_000))
        assertTrue(lib.folder("Familie").valueOrNull()!!.videos[1].title.played)
    }

    @Test
    fun `played with the subtitle files beside it, only offered`() = runTest {
        val oma = lib.folder("Familie").valueOrNull()!!.videos[1].title
        val plan = lib.play(MediaPlay(oma.id)).valueOrNull()!!
        assertEquals(2, plan.sideSubtitles.size)
        val de = plan.sideSubtitles[0]
        assertEquals("gcshare://share/Familie/Geburtstag%20Oma.de.srt", de.url)
        assertEquals("de", de.language)
        assertFalse(de.default)
        assertFalse(de.forced)
        assertTrue(plan.sideSubtitles[1].forced)
        assertEquals("Familie/Geburtstag Oma.de.srt", ShareUri.path(de.url))
    }

    @Test
    fun `a video deeper than the index searches still plays from its folder`() = runTest {
        val path = "Familie/Extras/Tief/Tiefer/Tiefst/Unten/Ganz/Weiter/Versteckt.mkv"
        // the index leaves out "Extras" (and is not that deep)
        assertTrue(lib.movies(0, 100).valueOrNull()!!.items.none { it.name == "Versteckt" })
        val id = ShareIndex.fileId(path)
        val detail = lib.detail(id).valueOrNull()!!
        assertEquals("Versteckt", detail.title.name)
        assertEquals(MediaTitle.Kind.MOVIE, detail.title.kind)
        assertEquals("gcshare://share/" + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }, lib.play(MediaPlay(id)).valueOrNull()!!.url)
        // a file that is not there is not played
        assertEquals(MediaResult.NotFound, lib.play(MediaPlay(ShareIndex.fileId("Familie/Fehlt.mkv"))))
    }

    @Test
    fun `search below a folder by the file name, also with umlauts written out`() = runTest {
        val found = lib.searchBelow("Familie", "fussball").valueOrNull()!!
        assertEquals(listOf("Familie/Fußballturnier Jonas.mp4"), found.map { it.entry.path })
        assertEquals(listOf("Familie/Urlaub 2025/Hafen.mp4"), lib.searchBelow("Familie", "hafen").valueOrNull()!!.map { it.entry.path })
        assertTrue(lib.searchBelow("Familie/Schulfeste", "hafen").valueOrNull()!!.isEmpty())
        assertTrue(ShareFolders.matches("Silvester.am.See.mkv", "see silvester"))
        assertFalse(ShareFolders.matches("Silvester.mkv", ""))
        assertTrue(ShareFolders.matches("Urlaub 2025", "urlaub", folder = true))
    }

    @Test
    fun `subtitles beside a video and the path bar`() {
        val v = ShareEntry("Film.mkv", "A/Film.mkv", directory = false)
        val entries = listOf(
            v,
            ShareEntry("Film.srt", "A/Film.srt", false),
            ShareEntry("Film.German.vtt", "A/Film.German.vtt", false),
            ShareEntry("Filmriss.srt", "A/Filmriss.srt", false),
            ShareEntry(".Film.srt", "A/.Film.srt", false),
        )
        val subs = ShareFolders.subtitlesOf(v, entries)
        assertEquals(listOf("A/Film.German.vtt" to "de", "A/Film.srt" to null), subs.map { it.path to it.language })
        assertEquals("text/vtt", subs[0].mime)
        assertEquals(listOf("Filme" to "Filme", "Familie" to "Filme/Familie"), ShareFolders.crumbs("Filme/Familie"))
        assertTrue(ShareFolders.crumbs("").isEmpty())
        assertNull(ShareFolders.subtitlesOf(v, listOf(v)).firstOrNull())
    }
}
