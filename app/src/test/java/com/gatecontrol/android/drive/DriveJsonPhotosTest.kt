package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.7: photos, albums, time travel, storage, takenAt, delete later. */
class DriveJsonPhotosTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me knows admins, the device and the new features`() {
        val me = DriveJson.me(
            json(
                """{"me":{"id":7,"name":"Ma","seat":"family","deviceId":"dev-1","admin":true},"licence":{"valid":true},
                "features":["photos","albums","frame","timeTravel","takenAt","storage","deleteAfter"]}""",
            ),
        )!!
        assertTrue(me.admin)
        assertEquals("dev-1", me.deviceId)
        listOf(DMe.FEATURE_PHOTOS, DMe.FEATURE_ALBUMS, DMe.FEATURE_FRAME, DMe.FEATURE_TIME_TRAVEL, DMe.FEATURE_TAKEN_AT, DMe.FEATURE_STORAGE, DMe.FEATURE_DELETE_AFTER)
            .forEach { assertTrue(me.has(it), it) }
        val plain = DriveJson.me(json("""{"me":{"id":7,"deviceId":"bad id with spaces"}}"""))!!
        assertFalse(plain.admin)
        assertNull(plain.deviceId)
        assertFalse(plain.has(DMe.FEATURE_PHOTOS))
    }

    @Test
    fun `photo items with a preview flag, takenAt and the cursor`() {
        val p = DriveJson.photos(
            json(
                """{"items":[{"id":5,"name":"IMG_1.jpg","mime":"image/jpeg","size":12,"mediaId":"abcdefgh12","previewUrl":"/p","largeUrl":"/l","takenAt":"2026-10-27T12:00:00.000Z","areaId":"mine:7","parentId":3,"preview":true},
                {"id":6,"name":"VID.mp4","mime":"video/mp4","size":99,"takenAt":null,"areaId":"mine:7","parentId":3,"preview":false},
                {"id":7,"name":"no area"}],"next":"2026-10-27T12:00:00.000Z"}""",
            ),
        )!!
        assertEquals(listOf(5L, 6L), p.items.map { it.id })
        assertTrue(p.items[0].preview)
        assertEquals("abcdefgh12", p.items[0].mediaId)
        assertNull(p.items[1].mediaId)
        assertFalse(p.items[1].preview)
        assertEquals("2026-10-27T12:00:00.000Z", p.items[0].takenAt)
        assertNull(p.items[1].takenAt)
        assertFalse(p.items[0].isDir)
        assertEquals("2026-10-27T12:00:00.000Z", p.next)
        assertNull(DriveJson.photos(json("""{"items":[],"next":null}"""))!!.next)
        assertNull(DriveJson.photos(json("""{"ok":true}""")))
        // the object form of 1.1 still counts
        assertTrue(DriveJson.node(json("""{"id":1,"areaId":"a","name":"x","preview":{"url":"/p"}}"""))!!.preview)
    }

    @Test
    fun `months and memories`() {
        val months = DriveJson.months(json("""{"months":[{"month":"2026-10","count":12},{"month":"10/2026","count":1},{"month":"2025-01"}]}"""))!!
        assertEquals(listOf(DMonth("2026-10", 12), DMonth("2025-01", 0)), months)
        val mem = DriveJson.memories(
            json(
                """{"groups":[{"yearsAgo":3,"date":"2023-10-28","items":[{"id":1,"areaId":"a","name":"a.jpg"}]},
                {"yearsAgo":1,"date":"2025-10-28","items":[{"id":2,"areaId":"a","name":"b.jpg"}]},
                {"yearsAgo":2,"items":[]}]}""",
            ),
        )!!
        assertEquals(listOf(1, 3), mem.map { it.yearsAgo })
        assertEquals(2L, mem[0].items.single().id)
    }

    @Test
    fun `albums, an album page and the bodies`() {
        val list = DriveJson.albums(
            json(
                """{"albums":[{"id":"a1","name":"Urlaub","areaId":"family:1","count":4,"coverId":9,"frame":true,"updatedAt":"2026-10-01T00:00:00Z","canEdit":true,"canFrame":false,"coverUrl":"/x","createdBy":7},
                {"id":12,"name":"Zahl","count":0},{"id":"../x","name":"bad"},{"id":"b"}]}""",
            ),
        )!!
        assertEquals(listOf("a1", "12"), list.map { it.id })
        assertTrue(list[0].frame)
        assertTrue(list[0].canEdit)
        assertFalse(list[0].canFrame)
        assertTrue(DriveJson.album(json("""{"id":"c","name":"Rahmen","canEdit":true,"canFrame":true}"""))!!.canFrame)
        assertEquals(9L, list[0].coverId)
        assertFalse(list[1].canEdit)
        val page = DriveJson.albumPage(json("""{"album":{"id":"a1","name":"U"},"items":[{"id":1,"areaId":"a","name":"x.jpg"}],"next":null}"""))!!
        assertEquals("a1", page.album.id)
        assertEquals(1, page.items.size)
        assertNull(page.next)
        assertNull(DriveJson.albumPage(json("""{"items":[]}""")))
        assertEquals("""{"ids":[1,2]}""", DriveJson.idsBody(listOf(1, 2)).toString())
        assertEquals("""{"frame":true}""", DriveJson.albumPatchBody(frame = true).toString())
        assertEquals("""{"name":"N","areaId":"mine:7"}""", DriveJson.albumBody("N", "mine:7").toString())
    }

    @Test
    fun `time travel preview and result`() {
        val p = DriveJson.timeTravelPreview(
            json(
                """{"at":"2026-10-01T10:00:00Z","historySince":"2026-09-01T00:00:00Z","restore":2,"revert":1,"trash":3,"move":0,
                "sample":[{"op":"restore","name":"a.txt","path":"Docs/a.txt"},{"op":"trash"}],"limited":true,"lost":2,"ops":6,"max":2000}""",
            ),
        )!!
        assertEquals(6, p.total)
        assertEquals(1, p.sample.size)
        assertTrue(p.limited)
        assertEquals(2, p.lost)
        assertEquals("2026-09-01T00:00:00Z", p.historySince)
        assertNull(DriveJson.timeTravelPreview(json("""{"ok":true}""")))
        val r = DriveJson.timeTravelResult(json("""{"at":"x","before":"2026-10-28T09:00:00Z","restored":2,"reverted":1,"trashed":3,"moved":0,"failed":[{"id":1}],"lost":1}"""))!!
        assertEquals("2026-10-28T09:00:00Z", r.before)
        assertEquals(1, r.failed)
        assertEquals(1, r.lost)
        assertEquals(0, DriveJson.timeTravelResult(json("""{"before":"b","failed":0}"""))!!.failed)
        assertEquals("""{"at":"2026-10-01T10:00:00Z"}""", DriveJson.timeTravelBody("2026-10-01T10:00:00Z").toString())
    }

    @Test
    fun `storage overview`() {
        val sha = "a".repeat(64)
        val s = DriveJson.storage(
            json(
                """{"total":{"bytes":1000,"files":3,"versionsBytes":10,"trashBytes":5},"limit":{"maxBytes":5000},
                "areas":[{"id":"mine:7","label":"Meine Dateien","kind":"mine","bytes":900,"files":2,"versionsBytes":1,"trashBytes":0,"quota":2000}],
                "people":[{"user":7,"name":"Ma","bytes":900,"files":2}],
                "largest":[{"id":1,"name":"big.mov","size":800,"areaId":"mine:7","parentId":3,"path":"Videos/big.mov","updatedAt":"2026-01-01T00:00:00Z"}],
                "duplicates":[{"sha256":"$sha","size":50,"count":2,"wasted":50,"items":[
                   {"id":2,"name":"a.jpg","areaId":"mine:7","parentId":3,"path":"a.jpg","canDelete":true},
                   {"id":3,"name":"a (2).jpg","areaId":"mine:7","parentId":3,"path":"a (2).jpg","canDelete":false}]},
                  {"sha256":"$sha","size":1,"items":[{"id":4,"name":"single"}]}]}""",
            ),
        )!!
        assertEquals(1000L, s.total.bytes)
        assertEquals(5000L, s.maxBytes)
        assertEquals(2000L, s.areas.single().quota)
        assertEquals("Ma", s.people.single().name)
        assertTrue(s.largest.single().canDelete)
        // a group of one is no duplicate
        assertEquals(1, s.duplicates.size)
        assertFalse(s.duplicates[0].items[1].canDelete)
        assertNull(DriveJson.storage(json("""{"areas":[]}""")))
        assertNull(DriveJson.storage(json("""{"total":{},"limit":null}"""))!!.maxBytes)
    }

    @Test
    fun `commit with takenAt, delete later and its end`() {
        val body = DriveJson.commitBody("m".repeat(10), 3, "a.jpg", 1000, "rename", takenAt = "2026-10-27T14:05:00+02:00")
        assertEquals("2026-10-27T14:05:00+02:00", body.get("takenAt").asString)
        assertFalse(DriveJson.commitBody("m".repeat(10), 3, "a.jpg", 1000, "rename").has("takenAt"))
        assertEquals("2026-11-27T10:00:00Z", DriveJson.mark(json("""{"id":1,"marked":true,"deleteAt":"2026-11-27T10:00:00Z"}""")).deleteAt)
        assertEquals("2026-11-27T10:00:00Z", DriveJson.mark(json("""{"node":{"id":1,"areaId":"a","name":"x","deleteAt":"2026-11-27T10:00:00Z"}}""")).deleteAt)
        assertNull(DriveJson.mark(json("""{"id":1}""")).deleteAt)
        // the null must reach the server (Gson leaves null members out)
        assertTrue(JsonParser.parseString(DriveJson.UNMARK_BODY).asJsonObject.get("deleteAt").isJsonNull)
    }
}
