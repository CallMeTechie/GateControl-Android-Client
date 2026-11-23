package com.gatecontrol.android.drive

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.25 (`mediaFilter`): only pictures or only videos, searching videos, a video's length. */
class DriveMediaFilterTest {

    private fun me(vararg features: String) = DMe(1, "x", "family", true, null, emptyList(), 0, null, null, emptyMap(), features = features.toSet())

    @Test
    fun `the filter is sent only while Drive knows it`() {
        assertEquals("video", DriveLogic.mediaFilter("video", me(DMe.FEATURE_MEDIA_FILTER)))
        assertEquals("image", DriveLogic.mediaFilter("image", me(DMe.FEATURE_MEDIA_FILTER)))
        assertNull(DriveLogic.mediaFilter("video", me()), "an older Drive: both")
        assertNull(DriveLogic.mediaFilter("audio", me(DMe.FEATURE_MEDIA_FILTER)))
        assertNull(DriveLogic.mediaFilter(null, me(DMe.FEATURE_MEDIA_FILTER)))
        assertNull(DriveLogic.mediaFilter("video", null))
    }

    @Test
    fun `the photos query carries media, an unknown one is refused`() {
        assertEquals("video", DriveJson.photosQuery(filter = DPhotoFilter(media = "video"))!!["media"])
        assertFalse(DriveJson.photosQuery()!!.containsKey("media"))
        assertNull(DriveJson.photosQuery(filter = DPhotoFilter(media = "gif")))
    }

    @Test
    fun `the search - a chip alone lists the newest, else two characters`() {
        assertTrue(DriveLogic.searchable("ur", null))
        assertFalse(DriveLogic.searchable("u", null))
        assertFalse(DriveLogic.searchable("", null))
        assertTrue(DriveLogic.searchable("", "video"))
        assertFalse(DriveLogic.searchable("u", "video"), "one character is still too few")
    }

    @Test
    fun `a video's length on its tile`() {
        assertEquals("0:07", DriveLogic.durationText(7_000))
        assertEquals("0:01", DriveLogic.durationText(600))
        assertEquals("1:23", DriveLogic.durationText(83_456))
        assertEquals("1:02:03", DriveLogic.durationText(3_723_000))
        assertNull(DriveLogic.durationText(0))
        assertNull(DriveLogic.durationText(null))
    }

    @Test
    fun `durationMs from the server, and in the commit`() {
        val n = DriveJson.node(JsonParser.parseString("""{"id":4,"areaId":"mine:7","name":"a.mp4","mime":"video/mp4","durationMs":83456}""").asJsonObject)!!
        assertEquals(83_456L, n.durationMs)
        val bad = DriveJson.node(JsonParser.parseString("""{"id":4,"areaId":"mine:7","name":"a.mp4","durationMs":-5}""").asJsonObject)!!
        assertNull(bad.durationMs)
        val body = DriveJson.commitBody("m".repeat(32), 5, "a.mp4", null, DriveLogic.ON_CONFLICT_RENAME, durationMs = 12_000)
        assertEquals(12_000L, body.get("durationMs").asLong)
        assertFalse(DriveJson.commitBody("m".repeat(32), 5, "a.mp4", null, DriveLogic.ON_CONFLICT_RENAME).has("durationMs"))
        assertFalse(DriveJson.commitBody("m".repeat(32), 5, "a.mp4", null, DriveLogic.ON_CONFLICT_RENAME, durationMs = 0).has("durationMs"))
    }
}
