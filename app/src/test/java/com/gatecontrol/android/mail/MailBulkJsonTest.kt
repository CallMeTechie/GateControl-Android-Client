package com.gatecontrol.android.mail

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Mail 1.13's answers and bodies: the swipe settings (old and new fields), bulk answers with undo, undo, ids. */
class MailBulkJsonTest {

    private fun obj(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    // ─── settings ───────────────────────────────────────────────────

    @Test
    fun `settings - the full swipe of Mail 1-13 wins over the old fields`() {
        val s = MailJson.settings(obj("""{"threads":true,"swipeLeft":"none","swipeRight":"read","swipe":{"left":"flag","right":"move"},"confirmDelete":true}"""))
        assertEquals("flag", s.swipeLeft)
        assertEquals("move", s.swipeRight)
        assertTrue(s.confirmDelete)
    }

    @Test
    fun `settings - an old server - the old four, an unknown value does nothing, a missing one the default`() {
        val s = MailJson.settings(obj("""{"swipeLeft":"delete","swipeRight":"explode"}"""))
        assertEquals("delete", s.swipeLeft)
        assertEquals(MSettings.SWIPE_NONE, s.swipeRight, "unknown → nothing, never archive")
        assertFalse(s.confirmDelete)
        // the old fields never carry the new actions: one there is unknown to them too
        assertEquals(MSettings.SWIPE_NONE, MailJson.settings(obj("""{"swipeLeft":"junk"}""")).swipeLeft)
        val empty = MailJson.settings(obj("{}"))
        assertEquals(MSettings.DEFAULT, empty)
    }

    @Test
    fun `settings - an unknown full value does nothing, a missing side falls back to the old field`() {
        val s = MailJson.settings(obj("""{"swipeLeft":"archive","swipeRight":"read","swipe":{"left":"teleport"}}"""))
        assertEquals(MSettings.SWIPE_NONE, s.swipeLeft)
        assertEquals("read", s.swipeRight)
        assertEquals("archive", MailJson.settings(obj("""{"swipe":{"right":"junk"}}""")).swipeLeft, "nothing at all: the default")
    }

    @Test
    fun `settingsBody - swipeV2 writes swipe left right and confirmDelete, else the old four only`() {
        val v2 = MailJson.settingsBody(swipeLeft = "flag", confirmDelete = true, v2 = true)
        assertEquals("""{"swipe":{"left":"flag"},"confirmDelete":true}""", v2.toString())
        assertFalse(v2.has("swipeLeft"))
        assertFalse(MailJson.settingsBody(swipeLeft = "explode", v2 = true).has("swipe"))
        val old = MailJson.settingsBody(swipeLeft = "flag", swipeRight = "read", confirmDelete = true, v2 = false)
        assertEquals("""{"swipeRight":"read"}""", old.toString(), "an old server gets no new action and no question")
        assertEquals("""{"threads":false}""", MailJson.settingsBody(threads = false).toString())
    }

    @Test
    fun `settingsAnswer - the settings of PUT settings`() {
        val s = MailJson.settingsAnswer(obj("""{"settings":{"threads":false,"swipe":{"left":"junk","right":"none"},"confirmDelete":false}}"""))!!
        assertFalse(s.threads)
        assertEquals("junk", s.swipeLeft)
        assertEquals("none", s.swipeRight)
    }

    // ─── bulk answers ───────────────────────────────────────────────

    @Test
    fun `bulkDone - count, ids, undo and purged of Mail 1-13`() {
        val d = MailJson.bulkDone(
            obj(
                """{"ok":true,"items":[{"id":17,"accountId":3,"folderId":14,"from":{"email":"a@b.de"},"subject":"x","date":1,"tab":"relevant"}],
                "count":12,"ids":[1,2,"3",0,2],"undo":{"token":"Uq3-_x","kind":"move","count":12,"expiresAt":1731150000000},"purged":[5,6]}""",
            ),
        )
        assertEquals(12, d.count)
        assertEquals(listOf(1L, 2L, 3L), d.ids)
        assertEquals("Uq3-_x", d.undo!!.token)
        assertEquals("move", d.undo!!.kind)
        assertEquals(1731150000000L, d.undo!!.expiresAt)
        assertEquals(listOf(5L, 6L), d.purged)
        assertEquals(listOf(17L), d.items.map { it.id })
    }

    @Test
    fun `bulkDone - an old server - the items count, no undo`() {
        val d = MailJson.bulkDone(obj("""{"ok":true,"items":[]}"""))
        assertEquals(0, d.count)
        assertNull(d.undo)
        assertEquals(2, MailJson.bulkDone(obj("""{"ok":true,"ids":[4,5]}""")).count)
        // a token that is not base64url (or too long) is none
        assertNull(MailJson.bulkDone(obj("""{"undo":{"token":"a b/c"}}""")).undo)
        assertNull(MailJson.bulkDone(obj("""{"undo":{"token":"${"x".repeat(65)}"}}""")).undo)
        assertNull(MailJson.bulkDone(obj("""{"undo":null}""")).undo)
    }

    @Test
    fun `undone and ids`() {
        val u = MailJson.undone(obj("""{"ok":true,"kind":"delete","restored":11,"failed":1,"items":[]}"""))
        assertEquals("delete", u.kind)
        assertEquals(11, u.restored)
        assertEquals(1, u.failed)
        val ids = MailJson.messageIds(obj("""{"ids":[9,8,7],"total":4000,"max":2000,"truncated":true}"""))!!
        assertEquals(listOf(9L, 8L, 7L), ids.ids)
        assertEquals(4000, ids.total)
        assertTrue(ids.truncated)
        assertNull(MailJson.messageIds(obj("""{"total":3}""")))
        assertEquals(2, MailJson.messageIds(obj("""{"ids":[1,2],"total":1}"""))!!.total, "total is never below the ids")
        assertEquals("""{"token":"abc"}""", MailJson.undoBody("abc").toString())
    }
}
