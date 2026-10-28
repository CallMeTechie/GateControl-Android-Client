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
    fun `content answer names the media, or empty for a file without media`() {
        val id = "a".repeat(32)
        assertEquals(id, DriveJson.contentMediaId(json("""{"url":"/plugin-media/x","mediaId":"$id","size":12}""")))
        assertEquals(DriveJson.EMPTY_CONTENT, DriveJson.contentMediaId(json("""{"url":"data:text/plain;base64,","size":0,"mediaId":null,"empty":true}""")))
        assertEquals(DriveJson.EMPTY_CONTENT, DriveJson.contentMediaId(json("""{"size":0,"mediaId":null}""")))
        assertNull(DriveJson.contentMediaId(json("""{"size":12,"mediaId":null}""")))
        assertNull(DriveJson.contentMediaId(json("""{"mediaId":"../etc"}""")))
    }

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

    @Test
    fun `scans - text in the commit, the format in me, snippets of text hits`() {
        assertEquals(
            json("""{"mediaId":"m","parentId":3,"name":"Scan.pdf","onConflict":"rename","text":"Reisepass"}"""),
            DriveJson.commitBody("m", 3, "Scan.pdf", null, DriveLogic.ON_CONFLICT_RENAME, "Reisepass"),
        )
        assertEquals(DriveJson.TEXT_MAX, DriveJson.commitBody("m", 3, "a", null, "rename", "x".repeat(DriveJson.TEXT_MAX + 5)).get("text").asString.length)
        assertNull(DriveJson.commitBody("m", 3, "a", null, "rename", "  ").get("text"), "blank text is left out")
        assertEquals("image", DriveJson.me(json("""{"me":{"id":1},"settings":{"scanFormat":"image"}}"""))!!.scanFormat)
        assertEquals("pdf", DriveJson.me(json("""{"me":{"id":1},"settings":{"scanFormat":"tiff"}}"""))!!.scanFormat)
        assertEquals("pdf", DriveJson.me(json("""{"me":{"id":1}}"""))!!.scanFormat, "older Drive: PDF")
        val hit = DriveJson.node(json("""{"id":1,"areaId":"u:7","name":"a.pdf","match":"text","snippet":"…Bundesrepublik…"}"""))!!
        assertEquals("…Bundesrepublik…", hit.snippet)
        assertNull(DriveJson.node(json("""{"id":1,"areaId":"u:7","name":"a.pdf","match":"name","snippet":"x"}"""))!!.snippet)
    }

    // ─── Drive 1.6: features, portal links, ransomware protection ───

    private val base = """"ok":true,"me":{"id":7,"name":"Ma","seat":"family"},"licence":{"valid":true},"areas":[]"""

    @Test
    fun `me with links and guard when the features say so`() {
        val me = DriveJson.me(
            json(
                """{$base,"features":["devices","emptyFiles","links","guard"],
                "links":{"node":"https://portal.example/portal#drive/{id}","drive":"https://portal.example/portal#drive"},
                "guard":{"locked":true,"incident":{"id":12,"at":"2026-10-28T10:00:00Z","device":"d1","deviceName":"Laptop","files":250,"sample":["a.docx","b.xlsx",""]}},
                "incidents":[{"id":"12","deviceName":"Laptop","files":250},{"id":"13","deviceName":"PC","files":3,"state":"released"},{"id":"../x"}]}""",
            ),
        )!!
        assertEquals(setOf("devices", "emptyFiles", "links", "guard"), me.features)
        assertTrue(me.has(DMe.FEATURE_EMPTY_FILES))
        assertEquals("https://portal.example/portal#drive/{id}", me.links?.node)
        assertEquals("https://portal.example/portal#drive", me.links?.drive)
        val guard = me.guard!!
        assertTrue(guard.locked)
        assertEquals("12", guard.incident?.id)
        assertEquals("Laptop", guard.incident?.deviceName)
        assertEquals(250, guard.incident?.files)
        assertEquals(listOf("a.docx", "b.xlsx"), guard.incident?.sample)
        assertTrue(guard.incident!!.open)
        assertEquals(listOf("12", "13"), me.incidents.map { it.id })
        assertFalse(me.incidents[1].open)
    }

    @Test
    fun `without the features links and guard stay unused`() {
        val me = DriveJson.me(
            json("""{$base,"features":["devices"],"links":{"node":"https://p/{id}"},"guard":{"locked":true},"incidents":[{"id":"1"}]}"""),
        )!!
        assertNull(me.links)
        assertNull(me.guard)
        assertTrue(me.incidents.isEmpty())
        val old = DriveJson.me(json("""{$base}"""))!!
        assertTrue(old.features.isEmpty())
        assertNull(old.links)
    }

    @Test
    fun `features may come as an object of flags`() {
        assertEquals(setOf("links", "guard"), DriveJson.features(json("""{"links":true,"guard":true,"devices":false,"x y":true}""")))
        assertTrue(DriveJson.features(null).isEmpty())
    }

    @Test
    fun `portal links must be web addresses, the node one with a place for the id`() {
        assertNull(DriveJson.portalLinks(json("""{"node":"https://p/portal","drive":"javascript:alert(1)"}""")))
        assertEquals(DPortalLinks(null, "https://p/drive"), DriveJson.portalLinks(json("""{"node":"ftp://p/{id}","drive":"https://p/drive"}""")))
        assertNull(DriveJson.portalLinks(null))
    }

    @Test
    fun `undo and release answers`() {
        val r = DriveJson.guardResult(json("""{"ok":true,"incident":{"id":"12","state":"undone"},"restored":240,"trashed":10}"""))
        assertEquals("12", r.incident?.id)
        assertFalse(r.incident!!.open)
        assertEquals(240, r.restored)
        assertEquals(10, r.trashed)
        assertEquals(0, DriveJson.guardResult(json("""{"ok":true}""")).restored)
    }

    @Test
    fun `changed event names areas and a sequence`() {
        val e = DriveJson.changedEvent(json("""{"areas":["u:7","fam:1","bad area"],"seq":99}"""))
        assertEquals(setOf("u:7", "fam:1"), e.areas)
        assertEquals(99L, e.seq)
        val none = DriveJson.changedEvent(null)
        assertTrue(none.areas.isEmpty())
        assertNull(none.seq)
    }

    @Test
    fun `423 is the stopped device`() {
        assertEquals(DriveJson.DEVICE_LOCKED, DriveJson.failureCode(423, json("""{"code":"device_locked","incident":{"id":"1"}}""")))
        assertEquals(DriveJson.DEVICE_LOCKED, DriveJson.failureCode(423, null))
        assertEquals("http_500", DriveJson.failureCode(500, null))
        assertEquals("quota", DriveJson.failureCode(507, json("""{"code":"quota"}""")))
    }

    @Test
    fun `an empty file is committed without media`() {
        val b = DriveJson.emptyCommitBody(5, "leer.txt", "text/plain", "rename")
        assertFalse(b.has("mediaId"))
        assertEquals(5L, b.get("parentId").asLong)
        assertEquals("leer.txt", b.get("name").asString)
        assertEquals(0, b.get("size").asInt)
        assertEquals("text/plain", b.get("mime").asString)
        assertEquals("rename", b.get("onConflict").asString)
        assertFalse(DriveJson.emptyCommitBody(5, "x", null, "version").has("mime"))
    }
}
