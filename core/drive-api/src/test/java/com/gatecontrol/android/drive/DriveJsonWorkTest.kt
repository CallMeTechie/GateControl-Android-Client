package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.13: work photos apart (`photoClasses`). */
class DriveJsonWorkTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `photo items carry the review mark and the work flag`() {
        val p = DriveJson.photos(
            json(
                """{"items":[
                {"id":5,"name":"a.jpg","areaId":"mine:7","parentId":3,"review":{"hint":72},"work":false},
                {"id":6,"name":"b.jpg","areaId":"mine:7","parentId":3,"review":{"hint":null},"work":false},
                {"id":7,"name":"c.jpg","areaId":"mine:7","parentId":4,"review":null,"work":true},
                {"id":8,"name":"d.jpg","areaId":"mine:7","parentId":4,"review":{"hint":180}}]}""",
            ),
        )!!
        assertEquals(DReview(72), p.items[0].review)
        assertEquals(DReview(null), p.items[1].review)
        assertNull(p.items[2].review)
        assertTrue(p.items[2].work)
        assertFalse(p.items[0].work)
        assertEquals(DReview(100), p.items[3].review)
        assertFalse(p.items[3].work)
    }

    @Test
    fun `the settings, with the folder's path`() {
        val s = DriveJson.photoSettings(json("""{"ok":true,"workFolder":123,"workSubfolders":false,"workInMemories":true,"folder":{"id":123,"path":"Arbeit/Fotos","areaId":"mine:7"}}"""))!!
        assertEquals(DPhotoSettings(123, false, true, "Arbeit/Fotos", "mine:7"), s)
        val unset = DriveJson.photoSettings(json("""{"ok":true,"workFolder":null,"workSubfolders":true,"workInMemories":false,"folder":null}"""))!!
        assertEquals(DPhotoSettings(), unset)
        assertNull(DriveJson.photoSettings(json("""{"ok":true}""")))
    }

    @Test
    fun `settings, review and class bodies`() {
        assertEquals("""{"workFolder":5}""", DriveJson.photoSettingsBody(workFolder = 5).toString())
        assertEquals("""{"workFolder":null,"workSubfolders":true}""", DriveJson.photoSettingsBody(clearFolder = true, workSubfolders = true).toString())
        assertEquals("""{"workInMemories":false}""", DriveJson.photoSettingsBody(workInMemories = false).toString())
        assertEquals("""{"ids":[1,2],"review":true,"hint":100}""", DriveJson.reviewBody(listOf(1, 2, 2), true, 140).toString())
        assertEquals("""{"ids":[1],"review":false}""", DriveJson.reviewBody(listOf(1), false, 40).toString())
        assertEquals(mapOf("class" to "review", "camera" to "1"), DriveJson.photosQuery(camera = true, cls = "review"))
        assertNull(DriveJson.photosQuery(cls = "everything"))
    }

    @Test
    fun `review answer and the counts`() {
        val r = DriveJson.reviewAnswer(json("""{"ok":true,"changed":2,"items":[{"id":5,"review":{"hint":72}},{"id":6,"review":null},{"x":1}]}"""))!!
        assertEquals(mapOf(5L to DReview(72), 6L to null), r)
        assertNull(DriveJson.reviewAnswer(json("""{"ok":true}""")))
        assertEquals(DPhotoClasses(4, 10, 2), DriveJson.photoClasses(json("""{"ok":true,"work":4,"private":10,"review":2}""")))
        assertEquals(16, DPhotoClasses(4, 10, 2).all)
        assertNull(DriveJson.photoClasses(json("""{"ok":true,"work":4}""")))
    }

    @Test
    fun `me knows photoClasses`() {
        val me = DriveJson.me(json("""{"me":{"id":7},"features":["photoClasses"]}"""))!!
        assertTrue(me.has(DMe.FEATURE_PHOTO_CLASSES))
    }
}
