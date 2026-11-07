package com.gatecontrol.android.door

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

class HomeCameraLogicTest {

    private val t1 = "eyJrIjoibGl2ZSIsInMiOiJjM2Y5In0xxxx" + "." + "A".repeat(43)
    private val t2 = "eyJrIjoibGl2ZSIsInMiOiJjM2Y5In0yyyy" + "." + "B".repeat(43)
    private val server = "https://gc.example.org"

    private fun cam(id: String, online: Boolean) = HomeCamera(
        id = id, name = id, gateway = null, model = null, state = "ok", online = online, codec = null,
        settings = HomeCameraJson.settings(null), snapshotAt = null, lastEventAt = null,
    )

    @Test
    fun `camera media paths only for the own server and the right file`() {
        assertEquals("/camera-media/$t1/index.m3u8", CameraMediaUrls.path("/camera-media/$t1/index.m3u8", CameraMediaUrls.FILE_HLS))
        assertNull(CameraMediaUrls.path("/camera-media/$t1/index.m3u8", CameraMediaUrls.FILE_SNAPSHOT))
        assertNull(CameraMediaUrls.path("https://other.example/camera-media/$t1/index.m3u8", CameraMediaUrls.FILE_HLS))
        assertNull(CameraMediaUrls.path("/camera-media/short.x/index.m3u8", CameraMediaUrls.FILE_HLS))
        assertNull(CameraMediaUrls.path("/camera-media/$t1/../index.m3u8", CameraMediaUrls.FILE_HLS))
        assertNull(CameraMediaUrls.path("/notify-media/$t1/index.m3u8", CameraMediaUrls.FILE_HLS))
        assertEquals("$server/camera-media/$t1/snapshot.jpg", CameraMediaUrls.resolve("$server/", "/camera-media/$t1/snapshot.jpg"))
        assertNull(CameraMediaUrls.resolve("ftp://gc.example.org", "/camera-media/$t1/snapshot.jpg"))
        assertNull(CameraMediaUrls.resolve(server, null))
        assertEquals("$server/camera-media/$t1/snapshot.jpg?t=7", CameraMediaUrls.fresh("$server/camera-media/$t1/snapshot.jpg", 7))
    }

    @Test
    fun `the player loads live files under the newest token, nothing else`() {
        val current = "$server/camera-media/$t2/index.m3u8"
        // A segment or a reload of the list under the old token: the same file under the new one.
        assertEquals("$server/camera-media/$t2/s123.m4s", CameraMediaUrls.retoken("$server/camera-media/$t1/s123.m4s", current))
        assertEquals("$server/camera-media/$t2/index.m3u8", CameraMediaUrls.retoken("$server/camera-media/$t1/index.m3u8", current))
        assertEquals("$server/camera-media/$t2/init.mp4", CameraMediaUrls.retoken("$server/camera-media/$t2/init.mp4", current))
        // Another host, another file, a query, a path outside: refused.
        assertNull(CameraMediaUrls.retoken("https://evil.example/camera-media/$t1/s1.m4s", current))
        assertNull(CameraMediaUrls.retoken("$server/camera-media/$t1/clip.mp4", current))
        assertNull(CameraMediaUrls.retoken("$server/camera-media/$t1/s1.m4s?x=1", current))
        assertNull(CameraMediaUrls.retoken("$server/api/v1/client/me", current))
        assertNull(CameraMediaUrls.retoken("$server/camera-media/$t1/s1.m4s", "not a url"))
        // A clip: only its own address.
        val clip = "$server/camera-media/$t1/clip.mp4"
        assertTrue(CameraMediaUrls.sameFile(clip, clip))
        assertFalse(CameraMediaUrls.sameFile("$server/camera-media/$t2/clip.mp4", clip))
    }

    @Test
    fun `tokens are renewed a minute before they end, within 2 to 9 minutes`() {
        val now = Instant.parse("2026-11-07T14:00:00Z").toEpochMilli()
        assertEquals(9 * 60_000L, HomeCameraLogic.renewInMs("2026-11-07T14:10:00.000Z", now))
        assertEquals(4 * 60_000L, HomeCameraLogic.renewInMs("2026-11-07T14:05:00Z", now))
        // A device clock far off: never a tight loop, never past the token.
        assertEquals(2 * 60_000L, HomeCameraLogic.renewInMs("2026-11-07T13:00:00Z", now))
        assertEquals(9 * 60_000L, HomeCameraLogic.renewInMs("2026-11-07T15:00:00Z", now))
        assertEquals(9 * 60_000L, HomeCameraLogic.renewInMs(null, now))
        assertEquals(9 * 60_000L, HomeCameraLogic.renewInMs("garbage", now))
        assertTrue(HomeCameraLogic.renewInMs(null, now) < HomeCameraLogic.TOKEN_TTL_MS)
    }

    @Test
    fun `retries back off up to a minute`() {
        assertEquals(3_000L, HomeCameraLogic.retryInMs(1))
        assertEquals(6_000L, HomeCameraLogic.retryInMs(2))
        assertEquals(48_000L, HomeCameraLogic.retryInMs(5))
        assertEquals(60_000L, HomeCameraLogic.retryInMs(6))
        assertEquals(60_000L, HomeCameraLogic.retryInMs(50))
        assertEquals(3_000L, HomeCameraLogic.retryInMs(0))
    }

    @Test
    fun `which camera is shown`() {
        val list = listOf(cam("c000000000000001", online = false), cam("c000000000000002", online = true), cam("c000000000000003", online = true))
        assertEquals(2, HomeCameraLogic.onlineCount(list))
        assertEquals("c000000000000002", HomeCameraLogic.pick(list, wanted = null, current = null))
        assertEquals("c000000000000003", HomeCameraLogic.pick(list, wanted = "c000000000000003", current = "c000000000000002"))
        assertEquals("c000000000000001", HomeCameraLogic.pick(list, wanted = "c00000000000000f", current = "c000000000000001"))
        assertEquals("c000000000000001", HomeCameraLogic.pick(list.take(1), wanted = null, current = null))
        assertNull(HomeCameraLogic.pick(emptyList(), wanted = "c000000000000001", current = null))
    }

    @Test
    fun `quality, clip length and the file name of a still`() {
        assertTrue(HomeCameraLogic.isHd(1080, "sub"))
        assertFalse(HomeCameraLogic.isHd(480, "main"))
        assertTrue(HomeCameraLogic.isHd(null, "main"))
        assertFalse(HomeCameraLogic.isHd(0, "sub"))
        assertEquals("0:24", HomeCameraLogic.duration(24_000))
        assertEquals("0:08", HomeCameraLogic.duration(7_200))
        assertEquals("2:00", HomeCameraLogic.duration(120_000))
        val at = Instant.parse("2026-11-07T14:02:31Z")
        assertEquals("GateControl_Einfahrt_20261107_140231.jpg", HomeCameraLogic.stillFileName("Einfahrt", at, ZoneOffset.UTC))
        assertEquals("GateControl_Haus-T_r_vorne_20261107_140231.jpg", HomeCameraLogic.stillFileName("Haus-T/r vorne", at, ZoneOffset.UTC))
        assertEquals("GateControl_Kamera_20261107_140231.jpg", HomeCameraLogic.stillFileName("../", at, ZoneOffset.UTC))
    }
}
