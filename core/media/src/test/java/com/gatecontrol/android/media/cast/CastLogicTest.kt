package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.MediaSource
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.MediaTitle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastLogicTest {

    private val session = "a1b2c3d4e5f6a1b2c3d4e5f6"
    private val jelly = MediaSource("j1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u", token = "secret-token", serverId = "F00DFEED")
    private val smb = MediaSource("s1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "anna", password = "secret-pw", pairWith = "w1")
    private val webdav = MediaSource("w1", MediaSourceKind.WEBDAV, "nas", "https://192.168.1.20:5006/filme", pairWith = "s1")

    // ─── sources ────────────────────────────────────────────────────

    @Test
    fun `the reference never carries the access`() {
        val r = CastSources.ref(jelly)
        assertEquals(CastSourceRef(MediaSourceKind.JELLYFIN, "F00DFEED", "http://192.168.1.20:8096", null, "DS918+"), r)
        val s = CastSources.ref(smb)
        assertEquals("filme", s.folder)
        assertFalse(CastWire.encode(play(s)).contains("secret"))
        assertFalse(CastWire.encode(play(r)).contains("secret"))
    }

    private fun play(ref: CastSourceRef) = CastMessage.Play(session, 1, 1, ref, CastItem("f:a.mkv", "A", path = "a.mkv"), 0)

    @Test
    fun `items of a share go by their path, Jellyfin's by the id`() {
        val film = MediaTitle("f:Neu/Kobaltnacht.mkv", "s1", MediaTitle.Kind.MOVIE, "Kobaltnacht", runtimeMs = 1000)
        assertEquals("Neu/Kobaltnacht.mkv", CastSources.item(film, smb)!!.path)
        assertEquals(CastItem.KIND_MOVIE, CastSources.item(film, smb)!!.kind)
        val ep = MediaTitle("ep9", "j1", MediaTitle.Kind.EPISODE, "Folge 9", seriesName = "Station Eisvogel", season = 2, episode = 9)
        val item = CastSources.item(ep, jelly)!!
        assertNull(item.path)
        assertEquals(2, item.season)
        assertNull(CastSources.item(ep.copy(kind = MediaTitle.Kind.SERIES), jelly))
        // a share's title without a path id cannot be found on another device
        assertNull(CastSources.item(film.copy(id = "s:kobaltnacht", kind = MediaTitle.Kind.VIDEO), smb))
        assertEquals("f:Neu/Kobaltnacht.mkv", CastSources.itemId(smb, CastItem("x", "K", path = "Neu/Kobaltnacht.mkv")))
        assertEquals("ep9", CastSources.itemId(jelly, item))
    }

    @Test
    fun `the receiver finds its own Jellyfin by server id, else by address`() {
        val mine = jelly.copy(id = "tv-j", address = "http://nas.local:8096", serverId = "f00dfeed")
        assertEquals("tv-j", CastSources.match(CastSources.ref(jelly), listOf(smb, mine))?.id)
        val other = jelly.copy(id = "tv-j2", serverId = null)
        assertEquals("tv-j2", CastSources.match(CastSources.ref(jelly).copy(serverId = "nope"), listOf(other))?.id)
        assertNull(CastSources.match(CastSources.ref(jelly).copy(serverId = "nope"), listOf(mine)))
        assertNull(CastSources.match(CastSources.ref(jelly), emptyList()))
    }

    @Test
    fun `the receiver finds a share by address, else by its folder, a pair under SMB`() {
        assertEquals("s1", CastSources.match(CastSources.ref(smb), listOf(webdav, smb))?.id)
        // sent from the WebDAV half: the pair's SMB source
        assertEquals("s1", CastSources.match(CastSources.ref(webdav), listOf(webdav, smb))?.id)
        // the TV has the share by another host name: the same folder
        val tv = smb.copy(id = "tv-s", address = "smb://192.168.1.20/filme", pairWith = null)
        assertEquals("tv-s", CastSources.match(CastSources.ref(smb), listOf(jelly, tv))?.id)
        assertNull(CastSources.match(CastSources.ref(smb), listOf(jelly)))
        assertNull(CastSources.match(CastSources.ref(smb).copy(folder = "serien", address = "smb://nas/serien"), listOf(tv)))
    }

    // ─── the controller ─────────────────────────────────────────────

    private fun state(seq: Long, phase: CastPhase, pos: Long, dur: Long = 6_000_000) =
        CastMessage.State(session, seq, 0, phase, pos, dur, device = "Wohnzimmer-TV")

    @Test
    fun `connecting, then playing with the place running on`() {
        var s = CastRemote.start(session, "tv", "TV", "Kobaltnacht", 2_530_000, now = 1_000)
        assertEquals(CastRemotePhase.CONNECTING, s.phase)
        assertNull(CastRemote.command(s, CastCmd.PAUSE, 1_100))
        s = CastRemote.onState(s, state(1, CastPhase.LOADING, 2_530_000), "tv", 2_000)
        assertEquals(CastRemotePhase.LOADING, s.phase)
        s = CastRemote.onState(s, state(2, CastPhase.PLAYING, 2_530_000), "tv", 3_000)
        assertEquals(2_535_000, CastRemote.position(s, 8_000))
        assertEquals("Wohnzimmer-TV", s.deviceName)
        // up to the end, never past it
        assertEquals(6_000_000, CastRemote.position(s, 100_000_000))
    }

    @Test
    fun `states of another session, device or an older one are dropped`() {
        var s = CastRemote.start(session, "tv", "TV", "K", 0, now = 0)
        s = CastRemote.onState(s, state(5, CastPhase.PLAYING, 10), "tv", 1)
        assertEquals(s, CastRemote.onState(s, state(4, CastPhase.PAUSED, 99), "tv", 2))
        assertEquals(s, CastRemote.onState(s, state(6, CastPhase.PAUSED, 99), "pc", 2))
        assertEquals(s, CastRemote.onState(s, state(6, CastPhase.PAUSED, 99).copy(session = "zzzzzzzzzzzz"), "tv", 2))
        val stopped = CastRemote.onState(s, state(6, CastPhase.STOPPED, 99), "tv", 2)
        assertTrue(stopped.phase.final)
        assertEquals(stopped, CastRemote.onState(stopped, state(7, CastPhase.PLAYING, 99), "tv", 3))
    }

    @Test
    fun `keys show at once and count the seq up`() {
        var s = CastRemote.start(session, "tv", "TV", "K", 0, now = 0)
        s = CastRemote.onState(s, state(1, CastPhase.PLAYING, 60_000).copy(audio = listOf(CastTrack("a:1", "DE", true), CastTrack("a:2", "EN"))), "tv", 1_000)
        val (pause, paused) = CastRemote.command(s, CastCmd.PAUSE, 3_000)!!
        assertEquals(1, pause.seq)
        assertEquals(CastRemotePhase.PAUSED, paused.phase)
        assertEquals(62_000, paused.positionMs)
        val (back, moved) = CastRemote.command(paused, CastCmd.SEEK_BY, 4_000, deltaMs = -100_000)!!
        assertEquals(2, back.seq)
        assertEquals(0, moved.positionMs)
        val (_, english) = CastRemote.command(moved, CastCmd.AUDIO, 5_000, track = "a:2")!!
        assertEquals("a:2", english.audio.single { it.selected }.id)
        val (stop, stopped) = CastRemote.command(english, CastCmd.STOP, 6_000)!!
        assertEquals(CastCmd.STOP, stop.cmd)
        assertTrue(stopped.phase.final)
        assertNull(CastRemote.command(stopped, CastCmd.PLAY, 7_000))
    }

    @Test
    fun `no answer in time, or none for long`() {
        val s = CastRemote.start(session, "tv", "TV", "K", 0, now = 0)
        assertEquals(CastRemotePhase.CONNECTING, CastRemote.tick(s, CastProtocol.ANSWER_TIMEOUT_MS - 1).phase)
        assertEquals(CastRemotePhase.NO_ANSWER, CastRemote.tick(s, CastProtocol.ANSWER_TIMEOUT_MS).phase)
        val playing = CastRemote.onState(s, state(1, CastPhase.PLAYING, 0), "tv", 1_000)
        assertEquals(CastRemotePhase.PLAYING, CastRemote.tick(playing, 1_000 + CastProtocol.HEARTBEAT_MS + 1).phase)
        assertEquals(CastRemotePhase.NO_ANSWER, CastRemote.tick(playing, 1_000 + CastProtocol.STALE_MS).phase)
    }

    // ─── the receiver ───────────────────────────────────────────────

    @Test
    fun `a start too old is not played, commands only from the controller and newer`() {
        val p = play(CastSources.ref(jelly)).copy(seq = 1, at = 0)
        assertTrue(CastReceiverRules.freshPlay(p, sentAt = 1_000, now = 1_000 + CastProtocol.PLAY_MAX_AGE_MS))
        assertFalse(CastReceiverRules.freshPlay(p, sentAt = 1_000, now = 1_001 + CastProtocol.PLAY_MAX_AGE_MS))
        assertFalse(CastReceiverRules.freshPlay(p, sentAt = 1_000_000, now = 1_000))
        val s = CastReceiverRules.begin(p, "phone")
        val c = CastMessage.Command(session, 2, 0, CastCmd.PAUSE)
        val next = CastReceiverRules.accept(s, c, "phone", 10, 20)
        assertNotNull(next)
        assertEquals(2, next!!.lastSeq)
        assertNull(CastReceiverRules.accept(next, c, "phone", 10, 20))
        assertNull(CastReceiverRules.accept(s, c, "other", 10, 20))
        assertNull(CastReceiverRules.accept(s, c.copy(session = "zzzzzzzzzzzz"), "phone", 10, 20))
        assertNull(CastReceiverRules.accept(null, c, "phone", 10, 20))
        assertNull(CastReceiverRules.accept(s, c, "phone", 0, CastProtocol.COMMAND_MAX_AGE_MS + 1))
    }

    @Test
    fun `the receiver reports changes, jumps and a heartbeat only`() {
        val a = CastSnapshot(CastPhase.PLAYING, 10_000, 6_000_000, "a:1", "t:off", 3)
        assertTrue(CastReceiverRules.shouldReport(null, 0, a, 0))
        assertFalse(CastReceiverRules.shouldReport(a, 0, a.copy(positionMs = 70_000), 60_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(phase = CastPhase.PAUSED), 1_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(audio = "a:2"), 1_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(positionMs = 300_000), 1_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(positionMs = 10_000 + CastProtocol.HEARTBEAT_MS), CastProtocol.HEARTBEAT_MS))
        val paused = a.copy(phase = CastPhase.PAUSED)
        assertFalse(CastReceiverRules.shouldReport(paused, 0, paused, CastProtocol.HEARTBEAT_MS * 3))
        assertEquals(0, CastReceiverRules.seekTarget(5_000, -10_000, 60_000))
        assertEquals(59_000, CastReceiverRules.seekTarget(55_000, 10_000, 60_000))
    }
}
