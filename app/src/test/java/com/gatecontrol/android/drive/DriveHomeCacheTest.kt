package com.gatecontrol.android.drive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Drive 1.20: the `lan` hint of `GET nodes/<id>/content` and "Offline verfügbar". */
class DriveHomeCacheTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val id = "0123456789abcdef0123456789abcdef"
    private val sha = "a".repeat(64)
    private val pin = "b".repeat(64)

    @Test
    fun `content with a lan hint`() {
        val c = DriveJson.content(json("""{"url":"/plugin-media/x","mediaId":"$id","size":12,"lan":{"url":"https://192.168.1.23:8443/blob/$sha?exp=1700000600&sig=${"c".repeat(64)}","sha256":"$sha","tlsSha256":"$pin","exp":1700000600}}"""))
        assertNotNull(c)
        assertEquals(id, c!!.mediaId)
        assertEquals(12L, c.size)
        assertEquals("192.168.1.23", c.lan?.host)
        assertEquals(sha, c.lan?.sha256)
    }

    @Test
    fun `content without a hint, or with one that is not right`() {
        assertNull(DriveJson.content(json("""{"mediaId":"$id","size":12}"""))!!.lan)
        // a public address, plain http: the normal download
        assertNull(DriveJson.content(json("""{"mediaId":"$id","size":12,"lan":{"url":"https://203.0.113.5:8443/blob/$sha","sha256":"$sha","tlsSha256":"$pin","exp":1700000600}}"""))!!.lan)
        assertNull(DriveJson.content(json("""{"mediaId":"$id","size":12,"lan":{"url":"http://192.168.1.23:8443/blob/$sha","sha256":"$sha","tlsSha256":"$pin","exp":1700000600}}"""))!!.lan)
        assertNull(DriveJson.content(json("""{"mediaId":"$id","size":12,"lan":null}"""))!!.lan)
        // an empty file has nothing to load
        val empty = DriveJson.content(json("""{"size":0,"mediaId":null,"empty":true,"lan":{"url":"https://192.168.1.23:8443/blob/$sha","sha256":"$sha","tlsSha256":"$pin","exp":1700000600}}"""))
        assertEquals(DriveJson.EMPTY_CONTENT, empty!!.mediaId)
        assertNull(empty.lan)
        assertNull(DriveJson.content(json("""{"size":12,"mediaId":null}""")))
    }

    @Test
    fun `the limit of 500 marks is recognised`() {
        assertTrue(DriveOfflineLogic.atLimit(DResult.Failed("invalid", 400, json("""{"code":"invalid","field":"offline","max":500}"""))))
        assertFalse(DriveOfflineLogic.atLimit(DResult.Failed("invalid", 400, json("""{"code":"invalid","field":"name"}"""))))
        assertFalse(DriveOfflineLogic.atLimit(DResult.Failed("not_found", 404, null)))
    }

    @Test
    fun `offline list is read as nodes`() {
        val items = DriveJson.items(json("""{"items":[{"id":7,"areaId":"u1","name":"Steuer","isDir":true,"offline":true,"offlineAt":"2026-11-05T10:00:00Z"},{"id":9,"areaId":"u1","name":"a.pdf","size":3,"offline":true}]}"""))
        assertEquals(listOf(7L, 9L), items?.map { it.id })
    }
}
