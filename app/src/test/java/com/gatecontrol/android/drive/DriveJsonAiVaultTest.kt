package com.gatecontrol.android.drive

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Drive 1.8 (AI for documents, deadlines, import) and 1.9 (vaults, office): answers, bodies, pushes, helpers. */
class DriveJsonAiVaultTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val vaultId = "Vt9bJm0n2QwErTyUiOpAsD"
    private val cipher = "AQAAAAFAQUJDREVGR0hJSktgLqh3hHU8ihOdgqV6JtVriMSoDC68CGa3kjF881n4hiiLHyxtAABrlUJMnnUWgAICQpPzIXYULIMf_tNq18mf3aK8gCpR5uVwUOGL-lovxw"
    private val wrap = "AVhpr_RQVJcyy6rtXl35swptoxyw5XQrrVrUoado8aZ7O9odo5wfJujUDVHB7Ng9gamFzmYya5c5JDCfG1EsOnc4DpqCERyYnhQL7jK5E9XZ"

    @Test
    fun `me reports the new features`() {
        val me = DriveJson.me(json("""{"me":{"id":7,"seat":"family"},"licence":{"valid":true},"features":["docAi","cloudImport","vault","office"]}"""))!!
        listOf(DMe.FEATURE_DOC_AI, DMe.FEATURE_CLOUD_IMPORT, DMe.FEATURE_VAULT, DMe.FEATURE_OFFICE).forEach { assertTrue(me.has(it), it) }
    }

    @Test
    fun `node carries the short insight, a vault node its cipher`() {
        val n = DriveJson.node(json("""{"id":5,"areaId":"mine:7","name":"a.pdf","insight":{"status":"ready","kind":"invoice"}}"""))!!
        assertEquals(DInsightShort("ready", "invoice"), n.insight)
        assertEquals("invoice", DriveLogic.docKind(n))
        assertFalse(n.encrypted)
        val pending = DriveJson.node(json("""{"id":5,"areaId":"mine:7","name":"a.pdf","insight":{"status":"pending","kind":"invoice"}}"""))!!
        assertNull(pending.insight!!.kind)
        assertNull(DriveLogic.docKind(pending))
        assertNull(DriveJson.node(json("""{"id":5,"areaId":"mine:7","name":"a","insight":{"status":"weird"}}"""))!!.insight)

        val v = DriveJson.node(
            json("""{"id":9,"areaId":"mine:7","name":"0123456789abcdef0123456789abcdef","isDir":false,"encrypted":true,"vaultId":"$vaultId","nameCipher":"$cipher","keyVersion":1,"preview":{"url":"/x"},"insight":{"status":"off","kind":null}}"""),
        )!!
        assertTrue(v.encrypted)
        assertEquals(vaultId, v.vaultId)
        assertEquals(cipher, v.nameCipher)
        assertEquals(1, v.keyVersion)
        assertFalse(v.preview)
        assertNull(v.insight)
        // encrypted, but without a readable vault id: still never treated as a plain file
        val broken = DriveJson.node(json("""{"id":9,"areaId":"mine:7","name":"x","encrypted":true,"vaultId":"bad"}"""))!!
        assertTrue(broken.encrypted)
        assertEquals(DriveJson.INVALID_VAULT, broken.vaultId)
    }

    @Test
    fun `insight answer, suggestion and deadlines`() {
        val i = DriveJson.insight(
            json(
                """{"ok":true,"status":"ready","kind":"invoice","title":"Stadtwerke","date":"2026-03-14","sender":"Stadtwerke","amount":84.5,"currency":"eur",
                "error":null,"at":"2026-03-15T08:00:00.000Z","suggestion":{"folderId":12,"folderPath":"Rechnungen/2026","name":"2026-03-14 Stadtwerke Rechnung.pdf"},
                "deadlines":[{"id":3,"kind":"payment","date":"2026-03-28","label":"Zahlung","reminder":{"at":"2026-03-25T07:00:00.000Z","daysBefore":3,"sent":false}},
                {"id":4,"kind":"weird","date":"2026-03-28"},{"id":5,"kind":"objection","date":"28.03.2026"}]}""",
            ),
        )!!
        assertEquals("ready", i.status)
        assertEquals("invoice", i.kind)
        assertEquals(84.5, i.amount)
        assertEquals("EUR", i.currency)
        assertEquals(DSuggestion(12, "Rechnungen/2026", "2026-03-14 Stadtwerke Rechnung.pdf"), i.suggestion)
        assertEquals(1, i.deadlines.size)
        assertEquals(DReminder("2026-03-25T07:00:00.000Z", 3, false), i.deadlines[0].reminder)
        val none = DriveJson.insight(json("""{"status":"none","kind":null,"suggestion":null,"deadlines":[]}"""))!!
        assertNull(none.suggestion)
        assertNull(DriveJson.insight(json("""{"suggestion":{"folderId":null,"folderPath":null,"name":null}}""")))
        val top = DriveJson.insight(json("""{"status":"ready","kind":"other","suggestion":{"folderId":2,"folderPath":"","name":null}}"""))!!
        assertEquals("", top.suggestion!!.folderPath)
        assertNull(DriveJson.insight(json("""{"status":"ready","suggestion":{"folderId":null,"name":null}}"""))!!.suggestion)
    }

    @Test
    fun `deadlines list, remind answer, apply answer and bodies`() {
        val list = DriveJson.deadlines(
            json("""{"deadlines":[{"id":3,"kind":"cancellation","date":"2026-11-30","label":null,"reminder":null,"node":{"id":5,"areaId":"mine:7","name":"Vertrag.pdf","path":"Verträge"}}]}"""),
        )!!
        assertEquals("Vertrag.pdf", list[0].node!!.name)
        assertNull(list[0].reminder)
        val d = DriveJson.deadlineAnswer(json("""{"deadline":{"id":3,"kind":"payment","date":"2026-03-28","label":null,"reminder":{"at":null,"daysBefore":7,"sent":false}}}"""))!!
        assertEquals(7, d.reminder!!.daysBefore)
        val applied = DriveJson.insightApplied(json("""{"node":{"id":5,"areaId":"mine:7","name":"neu.pdf"},"insight":{"status":"ready","kind":"invoice","suggestion":null,"deadlines":[]}}"""))!!
        assertEquals("neu.pdf", applied.node.name)
        assertEquals("""{"name":true,"folder":true}""", DriveJson.applyBody(name = true, folder = true).toString())
        assertEquals("""{"folder":true}""", DriveJson.applyBody(name = false, folder = true).toString())
        assertEquals("""{"daysBefore":90}""", DriveJson.remindBody(200).toString())
    }

    @Test
    fun `office answer and which files may go`() {
        val o = DriveJson.office(json("""{"url":"https://gc.example/pub/office/open/5?access_token=x","mode":"edit","expiresAt":"2026-10-28T12:00:00.000Z"}"""))!!
        assertEquals("edit", o.mode)
        assertNull(DriveJson.office(json("""{"url":"javascript:alert(1)","mode":"edit"}""")))
        val me = DMe(1, "x", "family", true, null, emptyList(), 0, null, null, emptyMap(), features = setOf(DMe.FEATURE_OFFICE))
        fun file(name: String, vault: String? = null) = DNode(1, "mine:1", 2, name, false, 1, null, null, 1, null, null, null, vaultId = vault)
        assertTrue(DriveLogic.officeAllowed(me, file("Brief.DOCX")))
        listOf("a.xlsx", "a.pptx", "a.odt", "a.ods", "a.odp", "a.csv").forEach { assertTrue(DriveLogic.officeAllowed(me, file(it)), it) }
        assertFalse(DriveLogic.officeAllowed(me, file("a.pdf")))
        assertFalse(DriveLogic.officeAllowed(me, file("a.docx", vault = vaultId)))
        assertFalse(DriveLogic.officeAllowed(me.copy(features = emptySet()), file("a.docx")))
    }

    @Test
    fun `vault answers and bodies`() {
        val v = DriveJson.vaultAnswer(
            json(
                """{"vault":{"id":"$vaultId","nodeId":40,"areaId":"family:1","parentId":3,"nameCipher":"$cipher","keyVersion":2,
                "recoveryPub":"_ByL0BDaonvTsGkS5Yl8crZ6i9GnRsOTpDlIQGrd_1w","needsRotation":true,
                "myDevices":[{"deviceId":"a7K-bh8_xeRZDI08-8JcCw","state":"approved","name":"Pixel"}],"pending":2}}""",
            ),
        )!!
        assertEquals(2, v.keyVersion)
        assertTrue(v.needsRotation)
        assertEquals(2, v.pending)
        assertEquals("approved", v.myDevices[0].state)
        val keys = DriveJson.vaultKeys(json("""{"deviceId":"a7K-bh8_xeRZDI08-8JcCw","state":"approved","keyVersion":2,"keys":[{"keyVersion":1,"wrap":"$wrap"},{"keyVersion":2,"wrap":"short"}]}"""))!!
        assertEquals(listOf(DVaultWrap(1, wrap)), keys.keys)
        val pending = DriveJson.vaultKeys(json("""{"deviceId":"a7K-bh8_xeRZDI08-8JcCw","state":"pending","keys":[]}"""))!!
        assertEquals("pending", pending.state)
        val dev = DriveJson.vaultDevices(
            json("""{"devices":[{"deviceId":"a7K-bh8_xeRZDI08-8JcCw","userId":7,"name":"Pixel","platform":"android","publicKey":"B6N8vBQgk8i3VdwbEOhstCY3StFqqFPtC9_AsrhtHHw","state":"pending"},{"deviceId":"x","publicKey":"y","state":"pending"}]}"""),
        )!!
        assertEquals(1, dev.size)
        val approve = DriveJson.approveBody(listOf(DVaultWrap(1, wrap), DVaultWrap(2, wrap)), recoveryAuth = "auth")
        assertEquals(2, approve.getAsJsonArray("wraps").size())
        assertEquals("recovery", approve.get("via").asString)
        assertFalse(DriveJson.approveBody(listOf(DVaultWrap(1, wrap))).has("via"))
        val commit = DriveJson.vaultCommitBody("media123", 40, cipher, 2, null, null)
        assertTrue(commit.get("encrypted").asBoolean)
        assertFalse(commit.has("name"))
        val version = DriveJson.vaultCommitBody("media123", 40, null, 2, 5L, "0123456789abcdef0123456789abcdef")
        assertEquals("version", version.get("onConflict").asString)
        assertTrue(DriveJson.staleKey(json("""{"ok":false,"error":"x","reason":"stale_key","keyVersion":3}""")))
        assertEquals(3, DriveJson.staleKeyVersion(json("""{"reason":"stale_key","keyVersion":3}""")))
        assertEquals(DVaultEvent(vaultId, "request", "a7K-bh8_xeRZDI08-8JcCw", null), DriveJson.vaultEvent(json("""{"vault":"$vaultId","event":"request","device":"a7K-bh8_xeRZDI08-8JcCw"}""")))
        assertNull(DriveJson.vaultEvent(json("""{"vault":"bad","event":"request"}""")))
    }

    private fun msg(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "t", body = "",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `pushes - a deadline opens its file, a request the vault`() {
        val d = msg("plugin:gatecontrol-drive:deadline", """{"drive":{"node":5,"area":"mine:7","deadline":3},"link":"https://x"}""")
        assertEquals("file:5", DrivePush.destination(d))
        assertEquals(5L, DrivePush.fileOf("file:5"))
        val v = msg("plugin:gatecontrol-drive:vault", """{"drive":{"vault":"$vaultId","node":40,"area":"mine:7","event":"request"}}""")
        assertEquals("vault:$vaultId:40", DrivePush.destination(v))
        assertEquals(vaultId to 40L, DrivePush.vaultOf("vault:$vaultId:40"))
        assertNull(DrivePush.vaultOf("vault:bad:40"))
        assertNull(DrivePush.destination(msg("plugin:gatecontrol-drive:guard", """{"drive":{"node":5}}""")))
        assertNull(DrivePush.destination(msg("plugin:gatecontrol-door:deadline", """{"drive":{"node":5}}""")))
        assertNull(DrivePush.destination(msg("plugin:gatecontrol-drive:deadline", null)))
    }

    @Test
    fun `helpers - days to a deadline, reminders, vault order`() {
        val today = LocalDate.of(2026, 3, 20)
        assertEquals(8L, DriveLogic.daysUntil("2026-03-28", today))
        assertEquals(-1L, DriveLogic.daysUntil("2026-03-19", today))
        assertNull(DriveLogic.daysUntil("bad", today))
        assertTrue(DriveLogic.canRemind(DDeadline(1, "payment", "2026-03-20", null, null), today))
        assertFalse(DriveLogic.canRemind(DDeadline(1, "payment", "2026-03-19", null, null), today))
        fun n(id: Long, dir: Boolean) = DNode(id, "mine:1", 2, "x", dir, 0, null, null, 1, null, null, null)
        val sorted = DriveLogic.sortVault(listOf(n(1, false) to "b", n(2, true) to "z", n(3, false) to "A"))
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.first.id })
        assertEquals("/portal?drive=import#plg-gatecontrol-drive", DriveLogic.IMPORT_PORTAL_PATH)
    }
}
