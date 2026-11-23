package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DriveJsonBulkTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    @Test
    fun `the features of Drive 1_12`() {
        val me = DriveJson.me(json("""{"me":{"id":1},"features":["bulk","zip","multiLink"]}"""))!!
        assertTrue(me.has(DMe.FEATURE_BULK))
        assertTrue(me.has(DMe.FEATURE_ZIP))
        assertTrue(me.has(DMe.FEATURE_MULTI_LINK))
    }

    @Test
    fun `ops check with conflicts and blocked items`() {
        val c = DriveJson.opCheck(
            json(
                """{"ok":true,"files":3,"folders":1,"bytes":1600,
                "conflicts":[{"id":5,"name":"Rechnung.pdf","existing":{"id":9,"size":412,"mtime":"2026-10-03T08:00:00Z"},"same":false},
                             {"id":6,"name":"Scan.pdf","existing":{"id":10,"size":1200},"same":true},{"name":"no id"}],
                "blocked":[{"id":7,"reason":"into_itself"},{"reason":"x"}]}""",
            ),
        )!!
        assertEquals(3, c.files)
        assertEquals(1, c.folders)
        assertEquals(1600L, c.bytes)
        assertEquals(listOf(5L, 6L), c.conflicts.map { it.id })
        assertEquals(9L, c.conflicts[0].existingId)
        assertEquals(412L, c.conflicts[0].existingSize)
        assertEquals("2026-10-03T08:00:00Z", c.conflicts[0].existingMtime)
        assertFalse(c.conflicts[0].same)
        assertTrue(c.conflicts[1].same)
        assertEquals(listOf(DOpBlocked(7, "into_itself")), c.blocked)
        assertNull(c.items)
        // also wrapped as { check }; nothing that looks like one: null
        assertNotNull(DriveJson.opCheck(json("""{"check":{"files":0,"conflicts":[]}}""")))
        assertNull(DriveJson.opCheck(json("""{"ok":true}""")))
    }

    @Test
    fun `ops check of Drive 1_12_0 with items, merges, quota and paths`() {
        val c = DriveJson.opCheck(
            json(
                """{"ok":true,"items":[5,6,"x",-1],"files":12,"folders":2,"bytes":9000,"merges":1,"conflictsMore":3,"tooMany":false,
                "quota":{"needed":9000,"free":null},"limit":10000,
                "conflicts":[{"id":8,"name":"a.pdf","path":"Ordner/a.pdf","isDir":false,"size":7,"mtime":"2026-10-02T00:00:00Z",
                  "existing":{"id":9,"size":5,"mtime":"2026-10-01T00:00:00Z","isDir":false},"same":false}],
                "blocked":[{"id":4,"name":"Meine Dateien","reason":"root"}]}""",
            ),
        )!!
        assertEquals(listOf(5L, 6L), c.items)
        assertEquals(1, c.merges)
        assertEquals(3, c.conflictsMore)
        assertEquals(9000L, c.quotaNeeded)
        assertNull(c.quotaFree)
        assertFalse(c.tooMany)
        val k = c.conflicts.single()
        assertEquals("Ordner/a.pdf", k.path)
        assertEquals(7L, k.size)
        assertEquals("2026-10-02T00:00:00Z", k.mtime)
        assertFalse(k.existingIsDir)
        assertEquals(DOpBlocked(4, "root", "Meine Dateien"), c.blocked.single())
    }

    @Test
    fun `a job of Drive 1_12_0 with its label, target name, transfers and new items`() {
        val op = DriveJson.opAnswer(
            json(
                """{"op":{"id":"${"a".repeat(22)}","kind":"copy","state":"running","items":3,"total":128,"done":58,"bytesTotal":3100,"bytesDone":1400,
                "label":"Urlaub 2026","targetId":42,"targetAreaId":"f:1","targetPath":"Haus","targetName":"Familie","transfer":"client","transfersLeft":70,
                "results":{"done":58,"skipped":0,"failedCount":2,"failed":[{"id":3,"name":"x","reason":"changed"}],"resultIds":[100,"y",101]}}}""",
            ),
        )!!
        assertEquals(3, op.items)
        assertEquals("Urlaub 2026", op.label)
        assertEquals(42L, op.targetId)
        assertEquals("Familie", op.targetName)
        assertEquals("client", op.transfer)
        assertEquals(70, op.transfersLeft)
        assertEquals(2, op.results!!.failedCount)
        assertEquals(listOf(100L, 101L), op.results!!.resultIds)
        assertEquals("undo", DriveJson.op(json("""{"id":"u","kind":"undo","state":"done","error":"internal"}"""))?.kind)
    }

    @Test
    fun `a job with its results`() {
        val op = DriveJson.opAnswer(
            json(
                """{"op":{"id":"op_1a","kind":"copy","state":"done","total":128,"done":128,"bytesTotal":3300000000,"bytesDone":3300000000,
                "target":42,"targetPath":"Familie › Haus","results":{"done":120,"skipped":7,"failed":[{"id":3,"reason":"locked"}]},
                "undoUntil":"2026-10-29T10:15:00Z"}}""",
            ),
        )!!
        assertEquals("op_1a", op.id)
        assertEquals("copy", op.kind)
        assertFalse(op.running)
        assertEquals(128, op.total)
        assertEquals(3_300_000_000L, op.bytesTotal)
        assertEquals(42L, op.targetId)
        assertEquals("Familie › Haus", op.targetPath)
        assertEquals(DOpResults(120, 7, listOf(DOpBlocked(3, "locked"))), op.results)
        assertEquals("2026-10-29T10:15:00Z", op.undoUntil)
    }

    @Test
    fun `a job with a number id, an unknown kind or state is not one`() {
        assertEquals("17", DriveJson.op(json("""{"id":17,"kind":"move","state":"running"}"""))?.id)
        assertNull(DriveJson.op(json("""{"id":"a/b","kind":"move","state":"running"}""")))
        assertNull(DriveJson.op(json("""{"id":"x","kind":"explode","state":"running"}""")))
        assertNull(DriveJson.op(json("""{"id":"x","kind":"move","state":"sleeping"}""")))
        val r = DriveJson.op(json("""{"id":"x","kind":"trash","state":"running","total":-3}"""))!!
        assertTrue(r.running)
        assertEquals(0, r.total)
    }

    @Test
    fun `the running jobs and the live event`() {
        val list = DriveJson.ops(json("""{"ops":[{"id":"a","kind":"move","state":"running"},{"id":"b"}]}"""))!!
        assertEquals(listOf("a"), list.map { it.id })
        assertEquals("a", DriveJson.opEvent(json("""{"op":{"id":"a","kind":"move","state":"running","done":3}}"""))?.id)
        assertEquals(3, DriveJson.opEvent(json("""{"id":"a","kind":"move","state":"running","done":3}"""))?.done)
        assertNull(DriveJson.opEvent(null))
    }

    @Test
    fun `bodies of a check and a job`() {
        val b = DriveJson.opBody("move", listOf(1, 2, 2), 9, "rename")
        assertEquals("move", b.get("op").asString)
        assertEquals(2, b.getAsJsonArray("ids").size())
        assertEquals(9L, b.get("target").asLong)
        assertEquals("rename", b.get("onConflict").asString)
        val t = DriveJson.opBody("trash", listOf(1), null, "explode")
        assertFalse(t.has("target"))
        assertFalse(t.has("onConflict"))
        val c = DriveJson.opCheckBody("copy", listOf(4), 5)
        assertEquals("copy", c.get("op").asString)
        assertEquals(5L, c.get("target").asLong)
        assertEquals(2, DriveJson.zipBody(listOf(1, 2)).getAsJsonArray("ids").size())
        val l = DriveJson.multiLinkBody(listOf(1, 2), "download", "7d", "1234")
        assertEquals(2, l.getAsJsonArray("nodeIds").size())
        assertEquals("1234", l.get("pin").asString)
        assertFalse(DriveJson.multiLinkBody(listOf(1), "view", "1d", " ").has("pin"))
    }

    @Test
    fun `a zip by its address, a path or a media id, with a safe name`() {
        val z = DriveJson.zip(json("""{"url":"https://gc.example/plugin-files/zip/abc","name":"Dokumente","expiresAt":"2026-10-29T10:00:00Z"}"""))!!
        assertEquals("https://gc.example/plugin-files/zip/abc", z.url)
        assertEquals("Dokumente.zip", z.name)
        val p = DriveJson.zip(json("""{"zip":{"url":"/api/v1/client/plugins/gatecontrol-drive/api/zip/abc","name":"a/b.zip"}}"""))!!
        assertNull(p.url)
        assertEquals("/api/v1/client/plugins/gatecontrol-drive/api/zip/abc", p.path)
        assertEquals("a_b.zip", p.name)
        assertEquals("a".repeat(20), DriveJson.zip(json("""{"mediaId":"${"a".repeat(20)}"}"""))?.mediaId)
        assertEquals("Drive.zip", DriveJson.zip(json("""{"mediaId":"${"a".repeat(20)}"}"""))?.name)
        assertNull(DriveJson.zip(json("""{"url":"/../etc/passwd"}""")))
        assertNull(DriveJson.zip(json("""{"url":"javascript:alert(1)"}""")))
    }

    @Test
    fun `a zip is fetched from the server with credentials only by its relative address`() {
        // relative: below the configured server, its path kept, with the app's credentials
        assertEquals(DriveJson.ZipFetch("https://gc.home.lan/gc/s/tok/zip", auth = true), DriveJson.zipFetch("https://gc.home.lan/gc/", "/s/tok/zip"))
        assertEquals(DriveJson.ZipFetch("https://gc.home.lan/s/tok/zip?x=1", auth = true), DriveJson.zipFetch("https://gc.home.lan", "/s/tok/zip?x=1"))
        // absolute: GateControl's public bearer address, often another host — https, and never with credentials
        assertEquals(DriveJson.ZipFetch("https://gc.example.org/s/tok/zip", auth = false), DriveJson.zipFetch("http://192.168.1.5:3000", "https://gc.example.org/s/tok/zip"))
        assertEquals(DriveJson.ZipFetch("http://192.168.1.5:3000/s/tok/zip", auth = false), DriveJson.zipFetch("http://192.168.1.5:3000", "http://192.168.1.5:3000/s/tok/zip"))
        // plain http elsewhere, other schemes, protocol-relative, climbing up: refused
        assertNull(DriveJson.zipFetch("https://gc.home.lan", "http://evil.example/s/tok/zip"))
        assertNull(DriveJson.zipFetch("https://gc.home.lan", "ftp://gc.home.lan/x"))
        assertNull(DriveJson.zipFetch("https://gc.home.lan", "//evil.example/x"))
        assertNull(DriveJson.zipFetch("https://gc.home.lan/gc", "/../admin"))
        assertNull(DriveJson.zipFetch("not a url", "/s/tok/zip"))
        assertNull(DriveJson.zipFetch("https://gc.home.lan", "s/tok zip"))
    }

    @Test
    fun `a job of Drive 1_12_1 with its files one by one`() {
        val op = DriveJson.opAnswer(
            json(
                """{"op":{"id":"${"b".repeat(22)}","kind":"move","state":"running","items":3,"total":3,"done":1,"bytesTotal":30,"bytesDone":10,"filesMore":7,
                "files":[{"seq":1,"nodeId":5,"name":"a.pdf","size":10,"isDir":false,"state":"done","bytesDone":10},
                {"seq":2,"nodeId":6,"name":"Fotos","size":20,"isDir":true,"state":"running"},
                {"seq":3,"nodeId":7,"name":null,"size":0,"isDir":false,"state":"failed","reason":"into_itself","encrypted":true},
                {"seq":4,"nodeId":8,"name":"x","state":"flying"},{"nodeId":9,"name":"y","state":"done"}]}}""",
            ),
        )!!
        val f = op.files!!
        assertEquals(listOf(1, 2, 3), f.map { it.seq })
        assertEquals(10L, f[0].bytesDone)
        assertTrue(f[1].isDir)
        assertNull(f[1].bytesDone, "running without a measure")
        assertEquals("into_itself", f[2].reason)
        assertTrue(f[2].encrypted)
        assertNull(f[2].name)
        assertEquals(7, op.filesMore)
        // Drive 1.12.0: no files
        assertNull(DriveJson.op(json("""{"id":"u","kind":"move","state":"running"}"""))!!.files)
    }
}
