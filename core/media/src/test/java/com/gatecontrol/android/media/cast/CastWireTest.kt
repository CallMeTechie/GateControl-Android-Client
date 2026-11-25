package com.gatecontrol.android.media.cast

import com.gatecontrol.android.media.MediaSourceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CastWireTest {

    private val session = "a1b2c3d4e5f6a1b2c3d4e5f6"

    private val play = CastMessage.Play(
        session, 1, 1_764_000_000_000,
        CastSourceRef(MediaSourceKind.JELLYFIN, serverId = "f00dfeed", address = "http://192.168.1.20:8096", name = "DS918+"),
        CastItem("item-1", "Kobaltnacht", CastItem.KIND_MOVIE, runtimeMs = 6_480_000),
        positionMs = 2_530_000,
        audio = 1,
        subtitle = CastProtocol.SUBTITLE_OFF,
        from = "Pixel 8",
    )

    @Test
    fun `a start goes out and comes back the same`() {
        val json = CastWire.encode(play)
        assertTrue(json.contains("\"type\":\"cast.play\""))
        assertTrue(json.contains("\"v\":1"))
        assertEquals(play, CastWire.parse(json))
    }

    @Test
    fun `commands and states go out and come back the same`() {
        val cmds = listOf(
            CastMessage.Command(session, 2, 5, CastCmd.PAUSE),
            CastMessage.Command(session, 3, 5, CastCmd.SEEK, positionMs = 60_000),
            CastMessage.Command(session, 4, 5, CastCmd.SEEK_BY, deltaMs = -10_000),
            CastMessage.Command(session, 5, 5, CastCmd.SUBTITLE, track = CastProtocol.TRACK_OFF),
            CastMessage.Command(session, 6, 5, CastCmd.AUDIO, track = "a:2"),
            CastMessage.Command(session, 7, 5, CastCmd.STOP),
        )
        cmds.forEach { assertEquals(it, CastWire.parse(CastWire.encode(it))) }
        val state = CastMessage.State(
            session, 9, 7, CastPhase.PLAYING, 2_531_000, 6_480_000, "Kobaltnacht",
            audio = listOf(CastTrack("a:1", "Deutsch · DTS 5.1", true), CastTrack("a:2", "English · AC3")),
            subtitles = listOf(CastTrack("t:off", "Aus", true)),
            device = "Wohnzimmer-TV",
        )
        assertEquals(state, CastWire.parse(CastWire.encode(state)))
        val failed = CastMessage.State(session, 10, 7, CastPhase.FAILED, error = CastProtocol.ERROR_SOURCE_MISSING)
        assertEquals(failed, CastWire.parse(CastWire.encode(failed)))
    }

    @Test
    fun `a share's start needs a safe path below the share`() {
        val share = play.copy(
            source = CastSourceRef(MediaSourceKind.SMB, address = "smb://nas/filme", folder = "filme"),
            item = CastItem("f:Neu/Kobaltnacht.mkv", "Kobaltnacht", path = "Neu/Kobaltnacht.mkv"),
        )
        assertEquals(share, CastWire.parse(CastWire.encode(share)))
        listOf("../etc/passwd", "/abs.mkv", "a//b.mkv", "a\\\\b.mkv", "a/./b.mkv").forEach { bad ->
            val json = CastWire.encode(share).replace("\"path\":\"Neu/Kobaltnacht.mkv\"", "\"path\":\"$bad\"")
            assertNull(CastWire.parse(json), bad)
        }
    }

    @Test
    fun `refuses other versions, types and broken fields`() {
        val json = CastWire.encode(play)
        assertNull(CastWire.parse(json.replace("\"v\":1", "\"v\":2")))
        assertNull(CastWire.parse(json.replace("cast.play", "cast.dance")))
        assertNull(CastWire.parse(json.replace(session, "short")))
        assertNull(CastWire.parse(json.replace("\"seq\":1", "\"seq\":-1")))
        assertNull(CastWire.parse(json.replace("\"kind\":\"jellyfin\"", "\"kind\":\"nfs\"")))
        assertNull(CastWire.parse("""{"v":1,"type":"cast.cmd","session":"$session","seq":1,"at":1,"cmd":"seek"}"""))
        assertNull(CastWire.parse("""{"v":1,"type":"cast.cmd","session":"$session","seq":1,"at":1,"cmd":"audio","track":"bad id"}"""))
        assertNull(CastWire.parse("""{"v":1,"type":"cast.state","session":"$session","seq":1,"at":1,"state":"dancing"}"""))
        assertNull(CastWire.parse("[]"))
        assertNull(CastWire.parse("nope"))
        assertNull(CastWire.parse(null))
        // a Jellyfin start without server id and address cannot be found
        assertNull(CastWire.parse(json.replace("\"serverId\":\"f00dfeed\",", "").replace("\"address\":\"http://192.168.1.20:8096\",", "")))
    }

    @Test
    fun `unknown fields are ignored, odd tracks dropped`() {
        val m = CastWire.parse(
            """{"v":1,"type":"cast.state","session":"$session","seq":3,"at":1,"state":"paused","positionMs":5,"future":{"x":1},
            "audio":[{"id":"a:1","label":"Deutsch","selected":true},{"id":"bad id","label":"x"},{"label":"no id"}],"error":"Bad Code"}""",
        ) as CastMessage.State
        assertEquals(CastPhase.PAUSED, m.phase)
        assertEquals(listOf(CastTrack("a:1", "Deutsch", true)), m.audio)
        assertNull(m.error)
    }

    @Test
    fun `sessions are long random ids`() {
        val a = CastProtocol.newSession()
        assertTrue(CastProtocol.validSession(a))
        assertFalse(a == CastProtocol.newSession())
        assertTrue(CastPhase.STOPPED.final)
        assertFalse(CastPhase.PAUSED.final)
    }
}
