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
    // sources of the plugin („Medien“): its ids jf-1 / sh-2, on the device under their own ids
    private val jelly = MediaSource(
        "j1", MediaSourceKind.JELLYFIN, "DS918+", "http://192.168.1.20:8096", userId = "u", token = "secret-token", serverId = "F00DFEED",
        managed = true, remoteId = "jf-1",
    )
    private val smb = MediaSource("s1", MediaSourceKind.SMB, "nas", "smb://nas/filme", userName = "anna", password = "secret-pw", pairWith = "w1", managed = true, remoteId = "sh-2")
    private val webdav = MediaSource("w1", MediaSourceKind.WEBDAV, "nas", "https://192.168.1.20:5006/filme", pairWith = "s1", managed = true, remoteId = "sh-2")

    /** One set up on the device alone (before the plugin): the receiver could not know it. */
    private val local = MediaSource("l1", MediaSourceKind.JELLYFIN, "Alt", "http://192.168.1.30:8096", serverId = "BEEF")

    // ─── sources ────────────────────────────────────────────────────

    @Test
    fun `the reference is the plugin's source id and never carries the access`() {
        val r = CastSources.ref(jelly)!!
        assertEquals(CastSourceRef("jf-1", "jellyfin", "F00DFEED", "DS918+"), r)
        val s = CastSources.ref(smb)!!
        assertEquals("sh-2", s.id)
        assertNull(s.serverId)
        assertEquals("sh-2", CastSources.ref(webdav)!!.id)
        listOf(r, s).forEach { ref ->
            val json = CastWire.encode(play(ref))
            listOf("secret", "anna", "smb://", "192.168").forEach { assertFalse(json.contains(it), it) }
        }
        // a source of the device alone has no id of the plugin: not castable
        assertNull(CastSources.ref(local))
        assertNull(CastSources.ref(jelly.copy(managed = false)))
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
    fun `the receiver finds its own source by the plugin's id, a share under its SMB half`() {
        // the TV's own copies of the plugin's sources: other ids on the device, other addresses, the same remoteId
        val tvJelly = jelly.copy(id = "tv-j", address = "http://nas.local:8096", serverId = null, token = "tv-token")
        val tvSmb = smb.copy(id = "tv-s", address = "smb://192.168.1.20/filme", pairWith = "tv-w")
        val tvDav = webdav.copy(id = "tv-w", pairWith = "tv-s")
        val mine = listOf(local, tvDav, tvSmb, tvJelly)
        assertEquals("tv-j", CastSources.match(CastSourceRef("jf-1"), mine)?.id)
        assertEquals("tv-s", CastSources.match(CastSourceRef("sh-2", kind = "webdav"), mine)?.id)
        // only the WebDAV half there: that one
        assertEquals("tv-w", CastSources.match(CastSourceRef("sh-2"), listOf(tvDav.copy(pairWith = null)))?.id)
        // no longer by server id or address: another id is not ours, a source of the device alone never matches
        assertNull(CastSources.match(CastSourceRef("jf-9", serverId = "F00DFEED"), mine))
        assertNull(CastSources.match(CastSourceRef("jf-1"), listOf(local, tvJelly.copy(managed = false))))
        assertNull(CastSources.match(CastSourceRef("jf-1"), emptyList()))
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
        val p = play(CastSources.ref(jelly)!!).copy(seq = 1, at = 0)
        assertTrue(CastReceiverRules.freshPlay(p, sentAt = 1_000, now = 1_000 + CastProtocol.PLAY_MAX_AGE_MS))
        assertFalse(CastReceiverRules.freshPlay(p, sentAt = 1_000, now = 1_001 + CastProtocol.PLAY_MAX_AGE_MS))
        assertFalse(CastReceiverRules.freshPlay(p, sentAt = 1_000_000, now = 1_000))
        // the plugin gives no time: the phone's own `at` (a silent push that waited while the TV was off is too old)
        assertTrue(CastReceiverRules.freshPlay(p.copy(at = 5_000), sentAt = null, now = 5_000 + CastProtocol.PLAY_MAX_AGE_MS))
        assertFalse(CastReceiverRules.freshPlay(p.copy(at = 5_000), sentAt = null, now = 5_001 + CastProtocol.PLAY_MAX_AGE_MS))
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

    // ─── volume (plugin 1.4.1) ──────────────────────────────────────

    @Test
    fun `volume only with a receiver that tells it, keys step and louder ends muting`() {
        var s = CastRemote.start(session, "tv", "TV", "K", 0, now = 0)
        assertFalse(CastRemote.hasVolume(s))
        assertNull(CastRemote.setVolume(s, 1, delta = 5))
        // an older receiver: no volume in its states, nothing to change
        s = CastRemote.onState(s, state(1, CastPhase.PLAYING, 0), "tv", 1_000)
        assertNull(s.volume)
        assertFalse(CastRemote.hasVolume(s))
        s = CastRemote.onState(s, state(2, CastPhase.PLAYING, 0).copy(volume = 40, muted = true), "tv", 2_000)
        assertTrue(CastRemote.hasVolume(s))
        assertEquals(40, s.volume)
        assertTrue(s.muted)
        val down = CastRemote.setVolume(s, 3_000, delta = -CastRemote.VOLUME_STEP)!!
        assertEquals(35, down.volume)
        assertTrue(down.muted, "quieter keeps muting")
        val up = CastRemote.setVolume(s, 3_000, delta = 3 * CastRemote.VOLUME_STEP)!!
        assertEquals(55, up.volume)
        assertFalse(up.muted, "louder ends muting")
        assertEquals(100, CastRemote.setVolume(s, 3_000, delta = 100)!!.volume)
        assertEquals(0, CastRemote.setVolume(s, 3_000, delta = -100)!!.volume)
        assertFalse(CastRemote.setVolume(s, 3_000, muted = false)!!.muted)
        assertEquals(70, CastRemote.setVolume(s, 3_000, volume = 70)!!.volume)
        val over = CastRemote.onState(s, state(3, CastPhase.STOPPED, 0), "tv", 4_000)
        assertNull(CastRemote.setVolume(over, 5_000, delta = 1))
    }

    @Test
    fun `the volume command carries volume and muted, a change here wins a moment over older states`() {
        var s = CastRemote.start(session, "tv", "TV", "K", 0, now = 0)
        s = CastRemote.onState(s, state(1, CastPhase.PLAYING, 0).copy(volume = 40, muted = false), "tv", 1_000)
        assertNull(CastRemote.command(s, CastCmd.VOLUME, 2_000), "a volume command needs a value")
        val (c, shown) = CastRemote.command(s, CastCmd.VOLUME, 2_000, volume = 120, muted = true)!!
        assertEquals(CastCmd.VOLUME, c.cmd)
        assertEquals(100, c.volume)
        assertEquals(true, c.muted)
        assertNull(c.positionMs)
        assertEquals(100, shown.volume)
        assertTrue(shown.muted)
        assertEquals(s.nextSeq + 1, shown.nextSeq)
        // a state that left the TV before the command arrived: the volume shown here stays
        val early = CastRemote.onState(shown, state(2, CastPhase.PLAYING, 0).copy(volume = 40, muted = false), "tv", 2_000 + 1_000)
        assertEquals(100, early.volume)
        assertTrue(early.muted)
        val later = CastRemote.onState(early, state(3, CastPhase.PLAYING, 0).copy(volume = 90, muted = false), "tv", 2_000 + CastRemote.VOLUME_HOLD_MS)
        assertEquals(90, later.volume)
        assertFalse(later.muted)
        // a state without volume keeps the last one
        assertEquals(90, CastRemote.onState(later, state(4, CastPhase.PLAYING, 0), "tv", 9_000).volume)
    }

    @Test
    fun `the receiver applies the volume and tells a change of it`() {
        val c = CastMessage.Command(session, 1, 1, CastCmd.VOLUME, volume = 30)
        assertEquals(30 to false, CastReceiverRules.volume(c, 80, false))
        assertEquals(80 to true, CastReceiverRules.volume(c.copy(volume = null, muted = true), 80, false))
        assertEquals(100 to false, CastReceiverRules.volume(c.copy(volume = 300), 80, false))
        assertEquals(0.3f, CastReceiverRules.playerVolume(30, false))
        assertEquals(0f, CastReceiverRules.playerVolume(30, true))
        assertEquals(1f, CastReceiverRules.playerVolume(100, false))
        val a = CastSnapshot(CastPhase.PAUSED, 0, 1_000, volume = 50, muted = false)
        assertFalse(CastReceiverRules.shouldReport(a, 0, a, 1_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(volume = 55), 1_000))
        assertTrue(CastReceiverRules.shouldReport(a, 0, a.copy(muted = true), 1_000))
    }
}
