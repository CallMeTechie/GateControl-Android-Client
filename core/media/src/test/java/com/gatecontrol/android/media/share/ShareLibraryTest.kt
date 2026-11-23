package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaPlay
import com.gatecontrol.android.media.MediaProgress
import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import com.gatecontrol.android.media.PlayMethod
import com.gatecontrol.android.media.ShareProgress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShareLibraryTest {

    private val source = MediaSource("nas1", MediaSourceKind.SMB, "\\\\nas\\filme", "smb://nas/filme", userName = "kino", password = "pw")
    private val b = ByteArray(10)
    private val fs = FakeShareFs(
        mapOf(
            "Filme/Kobaltnacht (2025)/Kobaltnacht (2025).mkv" to ByteArray(1000) { it.toByte() },
            "Filme/Kobaltnacht (2025)/poster.jpg" to byteArrayOf(1, 2, 3),
            "Filme/Kobaltnacht (2025)/fanart.jpg" to byteArrayOf(4),
            "Filme/Kupferstadt.2025.1080p.mkv" to b,
            "Filme/Kupferstadt.2025.1080p-poster.png" to b,
            "Filme/Achterwasser (2019).mp4" to b,
            "Filme/Notizen.txt" to b,
            "Filme/@eaDir/Kupferstadt.2025.1080p.mkv/SYNOVIDEO_VIDEO_SCREENSHOT.jpg" to b,
            "Serien/Graufeld/poster.jpg" to b,
            "Serien/Graufeld/Staffel 1/Graufeld.S01E02.Ebbe.mkv" to b,
            "Serien/Graufeld/Staffel 1/Graufeld.S01E01.Flut.mkv" to b,
            "Serien/Graufeld/Staffel 2/01 - Nebel.mkv" to b,
            "#recycle/Alt.mkv" to b,
        ),
        modified = mapOf("Filme/Kupferstadt.2025.1080p.mkv" to 2_000L, "Filme/Achterwasser (2019).mp4" to 1_000L),
    )
    private val progress = MapProgress()
    private var now = 1_000_000L
    private val lib = ShareLibrary(source, fs, progress, seasonName = { "Staffel $it" }, now = { now })

    @Test
    fun `films and series from the folders, pictures beside them`() = runTest {
        val movies = lib.movies(0, 100).valueOrNull()!!
        assertEquals(listOf("Achterwasser", "Kobaltnacht", "Kupferstadt"), movies.items.map { it.name })
        assertEquals(3, movies.total)
        val kobalt = movies.items[1]
        assertEquals(2025, kobalt.year)
        assertTrue(kobalt.fromShare)
        assertEquals("nas1", kobalt.sourceId)
        assertEquals("Filme/Kobaltnacht (2025)/poster.jpg", kobalt.posterId)
        assertEquals("Filme/Kobaltnacht (2025)/fanart.jpg", kobalt.backdropId)
        assertEquals("Filme/Kupferstadt.2025.1080p-poster.png", movies.items[2].posterId)
        // the picture's bytes through the share
        assertEquals(listOf<Byte>(1, 2, 3), lib.imageBytes(kobalt, backdrop = false)!!.toList())
        // Achterwasser has none (the folder has several films: its folder.jpg would not be its)
        assertNull(movies.items[0].posterId)

        val series = lib.series(0, 100).valueOrNull()!!.items.single()
        assertEquals("Graufeld", series.name)
        assertEquals(MediaTitle.Kind.SERIES, series.kind)
        assertEquals(2, series.childCount)
        assertEquals("Serien/Graufeld/poster.jpg", series.posterId)
        val seasons = lib.seasons(series.id).valueOrNull()!!
        assertEquals(listOf("Staffel 1", "Staffel 2"), seasons.map { it.name })
        val first = lib.episodes(series.id, seasons[0].id).valueOrNull()!!
        assertEquals(listOf("Flut", "Ebbe"), first.map { it.name })
        // an episode without its own picture shows its series'
        assertEquals("Serien/Graufeld/poster.jpg", first[0].posterId)
        assertEquals(listOf("Nebel"), lib.episodes(series.id, seasons[1].id).valueOrNull()!!.map { it.name })
        assertEquals(3, lib.episodes(series.id).valueOrNull()!!.size)
        // hidden folders and recycle bins are not searched
        assertFalse(fs.listed.any { it.contains("@eaDir") || it.contains("#recycle") })
        // newest first: the series (none dated) after the dated films
        assertEquals("Kupferstadt", lib.latest(16).valueOrNull()!!.first().name)
    }

    @Test
    fun `played as the original, the progress kept on the device`() = runTest {
        val kobalt = lib.movies(0, 100).valueOrNull()!!.items[1]
        val plan = lib.play(MediaPlay(kobalt.id)).valueOrNull()!!
        assertEquals(PlayMethod.DIRECT_PLAY, plan.method)
        assertFalse(plan.converted)
        assertEquals("gcshare://share/Filme/Kobaltnacht%20%282025%29/Kobaltnacht%20%282025%29.mkv", plan.url)
        assertEquals("mkv", plan.version.container)
        // the player reads it from a place through the share
        val input = lib.files().open(ShareUri.path(plan.url)!!, 990)
        assertEquals(1000, input.length)
        assertEquals(10, input.stream.readBytes().size)

        lib.started(MediaProgress(plan, 0, paused = false, durationMs = 6_480_000))
        lib.progress(MediaProgress(plan, 2_530_000, paused = false, durationMs = 6_480_000))
        // within 30 s: not written again
        assertEquals(0, progress.map.values.single().positionMs)
        now += 31_000
        lib.progress(MediaProgress(plan, 2_530_000, paused = false, durationMs = 6_480_000))
        val resume = lib.resume(12).valueOrNull()!!
        assertEquals(listOf("Kobaltnacht"), resume.map { it.name })
        assertEquals(2_530_000, resume[0].positionMs)
        assertEquals(6_480_000, resume[0].runtimeMs)
        assertEquals(now, resume[0].playedAt)
        assertEquals(2_530_000, lib.detail(kobalt.id).valueOrNull()!!.title.positionMs)

        // to its end: watched, out of "Weiterschauen"
        lib.stopped(MediaProgress(plan, 6_000_000, paused = false, durationMs = 6_480_000))
        assertTrue(lib.resume(12).valueOrNull()!!.isEmpty())
        assertTrue(lib.detail(kobalt.id).valueOrNull()!!.title.played)
        // back to unwatched
        assertTrue(lib.setPlayed(kobalt.id, false) is MediaResult.Ok)
        assertFalse(lib.detail(kobalt.id).valueOrNull()!!.title.played)
        assertEquals(setOf("nas1|Filme/Kobaltnacht (2025)/Kobaltnacht (2025).mkv"), progress.map.keys)
    }

    @Test
    fun `a series knows its unwatched episodes, a pair shares the progress`() = runTest {
        val series = lib.series(0, 100).valueOrNull()!!.items.single()
        val eps = lib.episodes(series.id).valueOrNull()!!
        lib.setPlayed(eps[0].id, true)
        assertEquals(2, lib.series(0, 100).valueOrNull()!!.items.single().unplayed)
        // the same share by WebDAV away: the same keys, the same progress
        val away = ShareLibrary(source, FakeShareFs(fs.files, MediaSourceKind.WEBDAV), progress, now = { now })
        assertTrue(away.detail(eps[0].id).valueOrNull()!!.title.played)
        // another share's progress is not this one's
        progress.setProgress("other|Filme/Achterwasser (2019).mp4", ShareProgress(90_000, 100_000_000, false, 3))
        assertTrue(lib.resume(12).valueOrNull()!!.isEmpty())
    }

    @Test
    fun `not reachable or refused - the answer of the root, an old index stays`() = runTest {
        fs.answer = MediaResult.Unauthorized
        assertEquals(MediaResult.Unauthorized, lib.resume(12))
        fs.answer = null
        assertTrue(lib.movies(0, 10) is MediaResult.Ok)
        fs.answer = MediaResult.Offline
        now += ShareLibrary.INDEX_MS + 1
        // found before: still shown
        assertEquals(3, lib.movies(0, 10).valueOrNull()!!.total)
        assertEquals(MediaResult.NotFound, lib.detail("f:nope.mkv"))
        assertEquals(MediaResult.NotFound, lib.play(MediaPlay("s:graufeld")))
    }
}
