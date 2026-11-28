package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.32 (`vaultLocal`, "Nur auf diesem Gerät"): the field `local`, the manifest and the bodies (vertrag-apps 3–5). */
class DriveJsonVaultLocalTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val vault = "Vt9bJm0n2QwErTyUiOpAsD"
    private val dev = "a7K-bh8_xeRZDI08-8JcCw"
    private val cipher = "A".repeat(88)

    private fun local(nodeId: Long = 10, extra: String = "", state: String = "local") =
        """{"nodeId":$nodeId,"vaultId":"$vault","deviceId":"$dev","deviceName":"Pixel 8","platform":"android","deviceState":"approved","state":"$state","since":"2026-11-27T10:00:00.000Z","mine":true$extra}"""

    @Test
    fun `the feature`() {
        val me = DriveJson.me(json("""{"me":{"id":1},"features":["vault","vaultLocal"]}"""))!!
        assertTrue(me.has(DMe.FEATURE_VAULT_LOCAL))
    }

    @Test
    fun `a placeholder in a list carries local`() {
        val n = DriveJson.node(json("""{"id":10,"areaId":"mine:1","parentId":1,"name":"${"a".repeat(32)}","isDir":true,"vaultId":"$vault","nameCipher":"$cipher","keyVersion":1,"local":${local(extra = ""","thisDevice":false,"purgeAt":"2026-11-28T10:00:00.000Z","undo":true""")}}"""))!!
        val l = assertNotNullL(n.local)
        assertEquals(dev, l.deviceId)
        assertEquals("Pixel 8", l.deviceName)
        assertEquals("approved", l.deviceState)
        assertEquals(DVaultLocal.STATE_LOCAL, l.state)
        assertEquals(false, l.thisDevice)
        assertEquals(true, l.undo)
        assertTrue(l.mine)
        assertFalse(l.moving)
    }

    private fun assertNotNullL(l: DVaultLocal?): DVaultLocal {
        assertNotNull(l)
        return l!!
    }

    @Test
    fun `local of another node, of a file or outside a vault is dropped`() {
        assertNull(DriveJson.node(json("""{"id":11,"areaId":"mine:1","name":"x","isDir":true,"vaultId":"$vault","nameCipher":"$cipher","local":${local(nodeId = 10)}}"""))!!.local)
        assertNull(DriveJson.node(json("""{"id":10,"areaId":"mine:1","name":"x","isDir":false,"vaultId":"$vault","nameCipher":"$cipher","local":${local()}}"""))!!.local)
        assertNull(DriveJson.node(json("""{"id":10,"areaId":"mine:1","name":"x","isDir":true,"local":${local()}}"""))!!.local)
        assertNull(DriveJson.vaultLocal(json("""{"nodeId":10,"vaultId":"$vault","deviceId":"$dev","state":"gone","mine":true}""")))
    }

    @Test
    fun `the children's answer of a placeholder names it at the top`() {
        val f = DriveJson.folder(
            json("""{"node":{"id":10,"areaId":"mine:1","name":"x","isDir":true,"vaultId":"$vault","nameCipher":"$cipher"}}"""),
            json("""{"items":[],"total":0,"role":"write","local":${local()}}"""),
        )!!
        assertEquals(10L, f.node.local?.nodeId)
    }

    @Test
    fun `the start's manifest and numbers`() {
        val s = DriveJson.vaultLocalStart(json("""{"local":${local(state = "moving")},
            "manifest":{"items":[{"id":20,"parentId":10,"isDir":true,"nameCipher":"$cipher","keyVersion":1},
              {"id":21,"parentId":20,"isDir":false,"nameCipher":"$cipher","keyVersion":1,"rev":3,"sha256":"${"ab".repeat(32)}","size":120,"mediaId":"m","mtime":"2026-01-01T00:00:00.000Z"}],
              "files":1,"folders":1,"bytes":120},
            "versions":{"count":4,"bytes":900},"trash":{"entries":1,"files":2,"bytes":30}}"""))!!
        assertEquals(DVaultLocal.STATE_MOVING, s.local?.state)
        assertEquals(2, s.items.size)
        assertEquals(3, s.items[1].rev)
        assertEquals("ab".repeat(32), s.items[1].sha256)
        assertEquals(20L, s.items[1].parentId)
        assertEquals(1, s.files)
        assertEquals(120L, s.bytes)
        assertEquals(4, s.versions)
        assertEquals(1, s.trashEntries)
        assertEquals(2, s.trashFiles)
    }

    @Test
    fun `a 409 changed and the other reasons`() {
        val c = DriveJson.vaultLocalChanged(json("""{"code":"conflict","reason":"changed","changed":[{"id":21,"parentId":10,"isDir":false,"rev":4}],"added":[],"removed":[22]}"""))!!
        assertEquals(4, c.changed.single().rev)
        assertEquals(listOf(22L), c.removed)
        assertNull(DriveJson.vaultLocalChanged(json("""{"reason":"state"}""")))
        assertEquals("purge_pending", DriveJson.conflictReason(json("""{"reason":"purge_pending"}""")))
    }

    @Test
    fun `an answer local null is a plain folder again`() {
        assertNull(DriveJson.vaultLocalAnswer(json("""{"local":null}"""))!!.local)
        assertEquals(10L, DriveJson.vaultLocalAnswer(json("""{"local":${local()}}"""))!!.local?.nodeId)
        assertNull(DriveJson.vaultLocalAnswer(json("""{"other":1}""")))
    }

    @Test
    fun `the commit body names folders by id, files with rev and sha256`() {
        val b = DriveJson.vaultLocalCommitBody(dev, "p".repeat(43), listOf(
            DVaultLocalItem(20, 10, true, null, null),
            DVaultLocalItem(21, 10, false, null, null, rev = 2, sha256 = "cd".repeat(32)),
            DVaultLocalItem(22, 10, false, null, null, rev = 1, sha256 = null),
        ))
        assertEquals(dev, b.get("deviceId").asString)
        val m = b.getAsJsonArray("manifest")
        assertFalse(m[0].asJsonObject.has("rev"))
        assertEquals(2, m[1].asJsonObject.get("rev").asInt)
        assertTrue(m[2].asJsonObject.get("sha256").isJsonNull)
        assertFalse(DriveJson.vaultLocalForgetBody(dev, null).has("proof"))
        assertEquals(3, DriveJson.vaultLocalReturnBody(dev, "x", 3).get("count").asInt)
    }
}
