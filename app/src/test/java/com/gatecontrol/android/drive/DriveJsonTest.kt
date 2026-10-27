package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `me with licence, seat and areas`() {
        val me = DriveJson.me(
            json(
                """{"ok":true,"me":{"id":7,"name":"Ma","seat":"family"},"licence":{"valid":true,"tier":"combo"},
                "areas":[{"id":"u:7","kind":"mine","label":"Meine Dateien","role":"manage","via":"owner","rootId":3,
                  "storage":{"bytes":1024,"quota":2048,"target":{"kind":"nas","label":"NAS"}}}],
                "latest":42,"settings":{"trashDays":30},"limits":{"maxBytes":100},"people":[{"id":7,"name":"Ma"},{"id":"x"}]}""",
            ),
        )!!
        assertEquals("family", me.seat)
        assertTrue(me.licenceValid)
        assertEquals("combo", me.tier)
        assertEquals(42L, me.latest)
        assertEquals(30, me.trashDays)
        assertEquals(mapOf(7 to "Ma"), me.people)
        val a = me.areas.single()
        assertEquals(3L, a.rootId)
        assertEquals("NAS", a.storage?.target)
        assertTrue(a.writable)
    }

    @Test
    fun `an unknown seat or a missing licence means none`() {
        val me = DriveJson.me(json("""{"me":{"id":1,"seat":"gold"}}"""))!!
        assertNull(me.seat)
        assertFalse(me.licenceValid)
        assertNull(DriveJson.me(json("""{"ok":true}""")))
    }

    @Test
    fun `areas without an id or root are dropped, unknown roles read only`() {
        val list = DriveJson.areas(json("""{"areas":[{"id":"bad id!","rootId":1},{"id":"t:1","rootId":2,"role":"admin"},{"id":"t:2"}]}"""))!!
        assertEquals(listOf("t:1"), list.map { it.id })
        assertEquals("read", list.single().role)
        assertEquals("t:1", list.single().label)
    }

    @Test
    fun `a folder from the node and its children`() {
        val f = DriveJson.folder(
            json("""{"node":{"id":5,"areaId":"u:7","parentId":3,"name":"Fotos","isDir":true,"crumbs":[{"id":3,"name":""},{"id":5,"name":"Fotos"}]}}"""),
            json(
                """{"role":"write","total":3,"next":2,"items":[
                {"id":6,"areaId":"u:7","parentId":5,"name":"a.jpg","size":10,"mime":"image/jpeg","mediaId":"0123456789abcdef0123456789abcdef","rev":2,"preview":{"url":"/x","largeUrl":"/x?size=large"}},
                {"id":7,"areaId":"u:7","parentId":5,"name":"b.jpg","mediaId":"../etc","preview":null},
                {"areaId":"u:7","name":"no id"}]}""",
            ),
        )!!
        assertEquals("Fotos", f.node.name)
        assertEquals(listOf(3L, 5L), f.crumbs.map { it.id })
        assertEquals(listOf(6L, 7L), f.items.map { it.id })
        assertNull(f.items[1].mediaId, "a media id of the wrong shape is dropped")
        assertTrue(f.items[0].preview)
        assertFalse(f.items[1].preview, "preview null (or an older Drive without the field): none")
        assertEquals(3, f.total)
        assertEquals(2, f.next)
        assertTrue(f.writable)
        assertNull(DriveJson.folder(json("""{"node":{"id":5,"areaId":"u:7","name":"x"}}"""), json("""{}""")))
    }

    @Test
    fun `wipes, their count in me and the sha256 of a node`() {
        val sha = "a".repeat(64)
        val w = DriveJson.wipes(json("""{"ok":true,"wipes":[
            {"id":4,"wipeId":2,"nodeId":9,"name":"IMG_1.jpg","sha256":"${sha.uppercase()}","size":1200,"mime":"image/jpeg"},
            {"id":5,"name":"x","sha256":"nothex","size":3},
            {"name":"no id"}],"more":false}"""))!!
        assertEquals(listOf(4L, 5L), w.map { it.id })
        assertEquals(sha, w[0].sha256, "upper case is folded")
        assertNull(w[1].sha256, "a hash of the wrong shape is dropped")
        assertNull(w[1].nodeId)
        assertEquals("""{"ids":[4,5]}""", DriveJson.wipesDoneBody(listOf(4L, 5L)).toString())
        val node = DriveJson.node(json("""{"id":1,"areaId":"u:7","name":"a","sha256":"$sha"}"""))!!
        assertEquals(sha, node.sha256)
        val me = DriveJson.me(json("""{"me":{"id":1,"seat":"family"},"licence":{"valid":true},"wipes":3}"""))!!
        assertEquals(3, me.wipes)
        assertEquals(0, DriveJson.me(json("""{"me":{"id":1}}"""))!!.wipes, "older Drive: none")
    }

    @Test
    fun `upload check, commit and changes`() {
        val check = DriveJson.uploadCheck(json("""{"exists":true,"same":false,"node":{"id":9,"areaId":"u:7","name":"a.pdf"}}"""))!!
        assertTrue(check.exists)
        assertFalse(check.same)
        assertEquals(9L, check.node?.id)
        assertNull(DriveJson.uploadCheck(json("""{"ok":false,"code":"forbidden"}""")))

        val (n, created) = DriveJson.commit(json("""{"node":{"id":9,"areaId":"u:7","name":"a.pdf","rev":3},"created":false}"""))!!
        assertEquals(3, n.rev)
        assertFalse(created)

        val ch = DriveJson.changes(json("""{"changes":[{"seq":1,"op":"create","nodeId":9,"node":{"id":9,"areaId":"u:7","name":"a"}},{"op":"x"}],"next":2,"more":true}"""))!!
        assertEquals(1, ch.changes.size)
        assertEquals(2L, ch.next)
        assertTrue(ch.more)
    }

    @Test
    fun `links keep only http urls and sane tokens`() {
        val links = DriveJson.links(
            json(
                """{"enabled":true,"links":[{"token":"abcDEF123","url":"https://x.de/s/abcDEF123","mode":"view","active":true,"pin":true,"opens":4},
                {"token":"no","url":"https://x"},{"token":"zzzzzzzz","url":"javascript:alert(1)"}]}""",
            ),
        )!!
        assertEquals(listOf("abcDEF123", "zzzzzzzz"), links.links.map { it.token })
        assertEquals(4, links.links[0].opens)
        assertNull(links.links[1].url)
    }

    @Test
    fun `refusals and resumable parts`() {
        assertTrue(DriveJson.isRefusal(json("""{"ok":false,"code":"quota"}""")))
        assertFalse(DriveJson.isRefusal(json("""{"ok":true}""")))
        assertEquals("quota", DriveJson.errorCode(json("""{"ok":false,"code":"quota"}""")))
        val end = DriveJson.uploadPart(json("""{"mediaId":"0123456789abcdef0123456789abcdef"}"""))
        assertEquals(true, end.complete)
        val mid = DriveJson.uploadPart(json("""{"complete":false,"offset":8388608}"""))
        assertEquals(false, mid.complete)
        assertEquals(8388608L, mid.offset)
    }

    @Test
    fun `bodies carry only what is set`() {
        assertEquals(json("""{"parentId":3,"name":"Neu","rename":true}"""), DriveJson.mkdirBody(3, "Neu"))
        assertEquals(json("""{"name":"b"}"""), DriveJson.patchBody("b", null))
        assertEquals(json("""{"parentId":4}"""), DriveJson.patchBody(null, 4))
        assertEquals(
            json("""{"mediaId":"m","parentId":3,"name":"a","onConflict":"version"}"""),
            DriveJson.commitBody("m", 3, "a", 0, DriveLogic.ON_CONFLICT_VERSION),
        )
        assertEquals(json("""{"nodeId":1,"mode":"view","expires":"7d"}"""), DriveJson.linkBody(1, "view", "7d", " "))
    }
}
