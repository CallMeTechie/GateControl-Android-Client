package com.gatecontrol.android.door

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Answers of the door plugin's home-camera API (1.6.0, `server/home.js`). */
class HomeCameraJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val tok = "eyJrIjoibGl2ZSIsInMiOiJjM2Y5YTFiMmM0ZDVlNmY3In0" + "." + "A".repeat(42) + "b"
    private val cam = "c3f9a1b2c4d5e6f7"

    @Test
    fun `the list keeps the cameras of the home network the person sees`() {
        val l = HomeCameraJson.list(
            json(
                """
                {"supported":true,"minGatecontrol":"1.240.0","every":10,"cameras":[
                  {"id":"$cam","kind":"home","name":"Einfahrt","gateway":"NAS","model":"Reolink RLC-510A","source":"onvif",
                   "state":"ok","online":true,"codec":"h264","events":{"supported":true,"kinds":["person"]},
                   "settings":{"record":"motion","events":["vehicle","person","bogus"],"retentionDays":14,"stream":"sub","audio":true},
                   "push":true,"snapshotUrl":"/camera-media/$tok/snapshot.jpg","snapshotAt":"2026-11-07T14:02:00.000Z","lastEventAt":null},
                  {"id":"c0000000000000aa","name":"Garten","online":false,"state":"unreachable"},
                  {"id":"4","kind":"home","name":"Ring id"},
                  {"id":"c0000000000000bb","kind":"ring","name":"Ring"},
                  {"id":"$cam","kind":"home","name":"Doppelt"}
                ]}
                """,
            ),
        )!!
        assertTrue(l.supported)
        assertEquals("1.240.0", l.minGatecontrol)
        assertEquals(10, l.every)
        assertEquals(listOf(cam, "c0000000000000aa"), l.cameras.map { it.id })
        val c = l.cameras[0]
        assertEquals("Einfahrt", c.name)
        assertEquals("NAS", c.gateway)
        assertEquals("Reolink RLC-510A", c.model)
        assertTrue(c.online)
        assertEquals("ok", c.state)
        assertEquals(listOf("person", "vehicle"), c.settings.events)
        assertEquals("sub", c.settings.stream)
        assertTrue(c.settings.audio)
        assertEquals(14, c.settings.retentionDays)
        val g = l.cameras[1]
        assertFalse(g.online)
        // Defaults when the camera has no settings.
        assertEquals("motion", g.settings.record)
        assertEquals("main", g.settings.stream)
        assertFalse(g.settings.audio)
    }

    @Test
    fun `an older GateControl lists nothing, a broken answer is null`() {
        val l = HomeCameraJson.list(json("""{"supported":false,"minGatecontrol":"1.240.0","cameras":[{"id":"c3f9a1b2c4d5e6f7"}]}"""))!!
        assertFalse(l.supported)
        assertTrue(l.cameras.isEmpty())
        assertEquals(HomeCameraJson.DEFAULT_EVERY_S, l.every)
        assertNull(HomeCameraJson.list(json("""{"cameras":[]}""")))
        // An odd interval falls back to 10 s.
        assertEquals(10, HomeCameraJson.list(json("""{"supported":true,"every":1,"cameras":[]}"""))!!.every)
    }

    @Test
    fun `live through the VPN carries the HLS list of the own server`() {
        val l = HomeCameraJson.live(
            json(
                """
                {"live":{"mode":"live","via":"vpn","ready":false,"hls":"/camera-media/$tok/index.m3u8","mp4":"/camera-media/$tok/live.mp4",
                 "snapshot":"/camera-media/$tok/snapshot.jpg","codec":"avc1.640028","audio":true,"every":10,"expiresAt":"2026-11-07T14:12:00.000Z"}}
                """,
            ),
        )!!
        assertTrue(l.isLive)
        assertEquals("vpn", l.via)
        assertFalse(l.ready)
        assertEquals("/camera-media/$tok/index.m3u8", l.hlsPath)
        assertEquals("/camera-media/$tok/snapshot.jpg", l.snapshotPath)
        assertTrue(l.audio)
        assertEquals("2026-11-07T14:12:00.000Z", l.expiresAt)
    }

    @Test
    fun `without VPN only stills, foreign addresses are dropped`() {
        val s = HomeCameraJson.live(json("""{"live":{"mode":"snapshot","snapshot":"/camera-media/$tok/snapshot.jpg","every":10,"reason":"vpn_required","via":"internet"}}"""))!!
        assertFalse(s.isLive)
        assertEquals(HomeLive.MODE_SNAPSHOT, s.mode)
        assertEquals("vpn_required", s.reason)
        assertNull(s.hlsPath)
        assertFalse(s.audio)
        // A live answer whose list is not on the own server: stills only.
        val foreign = HomeCameraJson.live(
            json("""{"live":{"mode":"live","hls":"https://evil.example/camera-media/$tok/index.m3u8","snapshot":"/camera-media/$tok/snapshot.jpg","audio":true}}"""),
        )!!
        assertFalse(foreign.isLive)
        assertNull(foreign.hlsPath)
        assertFalse(foreign.audio)
        // The wrong file for the field, no picture at all, unknown mode: nothing.
        assertNull(HomeCameraJson.live(json("""{"live":{"mode":"snapshot","snapshot":"/camera-media/$tok/clip.mp4"}}""")))
        assertNull(HomeCameraJson.live(json("""{"live":{"mode":"webrtc","snapshot":"/camera-media/$tok/snapshot.jpg"}}""")))
        assertNull(HomeCameraJson.live(json("""{"ok":true}""")))
    }

    @Test
    fun `the still is a JPEG of at most 1 MB`() {
        val bytes = byteArrayOf(-1, -40, -1, -32, 1, 2, 3)
        val data = Base64.getEncoder().encodeToString(bytes)
        val img = HomeCameraJson.still(json("""{"still":{"data":"$data","mime":"image/jpeg","at":"2026-11-07T14:02:31.000Z"}}"""))!!
        assertArrayEquals(bytes, img.bytes)
        assertEquals("2026-11-07T14:02:31.000Z", img.at)
        // Without mime: JPEG as well; another type or no data: nothing.
        assertArrayEquals(bytes, HomeCameraJson.still(json("""{"still":{"data":"$data"}}"""))!!.bytes)
        assertNull(HomeCameraJson.still(json("""{"still":{"data":"$data","mime":"text/html"}}""")))
        assertNull(HomeCameraJson.still(json("""{"still":{"mime":"image/jpeg"}}""")))
    }

    @Test
    fun `clips of today with kinds, length and their own addresses`() {
        val c = HomeCameraJson.clips(
            json(
                """
                {"day":"2026-11-07","clips":[
                  {"id":"e0123456789abcde","kind":"person","kinds":["motion","person"],"startedAt":"2026-11-07T13:02:00.000Z",
                   "endedAt":"2026-11-07T13:02:24.000Z","nobodyHome":true,"ms":24000,
                   "thumbUrl":"/camera-media/$tok/thumb.jpg","clipUrl":"/camera-media/$tok/clip.mp4"},
                  {"id":"e0123456789abcdf","kind":"vehicle","kinds":["vehicle"],"startedAt":"2026-11-07T11:47:00.000Z","nobodyHome":null,
                   "ms":null,"thumbUrl":null,"clipUrl":null},
                  {"id":"e0123456789abce0","kind":"dragon","kinds":["animal"],"ms":8000,"clipUrl":"/camera-media/$tok/thumb.jpg"},
                  {"id":"120","kind":"person"}
                ]}
                """,
            ),
        )!!
        assertEquals("2026-11-07", c.day)
        assertEquals(3, c.clips.size)
        val p = c.clips[0]
        assertEquals("person", p.kind)
        assertEquals(listOf("person", "motion"), p.kinds)
        assertEquals(true, p.nobodyHome)
        assertEquals(24_000L, p.ms)
        assertEquals("/camera-media/$tok/clip.mp4", p.clipPath)
        assertEquals("/camera-media/$tok/thumb.jpg", p.thumbPath)
        val v = c.clips[1]
        assertNull(v.ms)
        assertNull(v.clipPath)
        assertNull(v.nobodyHome)
        // An unknown kind takes the first known one of `kinds`; a thumbnail address is no clip.
        assertEquals("animal", c.clips[2].kind)
        assertNull(c.clips[2].clipPath)
        assertNull(HomeCameraJson.clips(json("""{"day":"2026-11-07"}""")))
    }

    @Test
    fun `plugin 1_7_0 resolution, sound and the own push`() {
        val l = HomeCameraJson.list(
            json(
                """
                {"supported":true,"cameras":[
                  {"id":"$cam","kind":"home","name":"Einfahrt","online":true,"width":2560,"height":1440,"audio":true,"hasAudio":true,
                   "push":true,"prefs":{"push":false},"settings":{"audio":true}},
                  {"id":"c0000000000000aa","name":"Garten","width":2.5,"height":99999,"hasAudio":"yes","push":false},
                  {"id":"c0000000000000bb","name":"Alt"}
                ]}
                """,
            ),
        )!!
        val (a, b, old) = l.cameras
        assertEquals(2560, a.width)
        assertEquals(1440, a.height)
        assertTrue(a.audio)
        assertEquals(true, a.hasAudio)
        assertTrue(a.push)
        assertEquals(false, a.myPush)
        // Not whole pixels, out of range or not a flag: unknown.
        assertNull(b.width)
        assertNull(b.height)
        assertNull(b.hasAudio)
        assertFalse(b.audio)
        assertFalse(b.push)
        // An older plugin: no own switch (no row), the admin's push counts as on.
        assertNull(old.myPush)
        assertTrue(old.push)
        assertNull(old.width)
        assertEquals(true, HomeCameraJson.prefs(json("""{"ok":true,"prefs":{"push":true}}""")))
        assertNull(HomeCameraJson.prefs(json("""{"ok":true}""")))
    }

    @Test
    fun `plugin 1_7_0 the resolution decides HD before the first frame`() {
        assertTrue(HomeCameraLogic.isHd(1440, "sub"))
        assertFalse(HomeCameraLogic.isHd(360, "main"))
    }
}
