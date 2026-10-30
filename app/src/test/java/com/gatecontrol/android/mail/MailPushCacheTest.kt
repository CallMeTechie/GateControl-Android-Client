package com.gatecontrol.android.mail

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MailPushCacheTest {

    private fun msg(topic: String, data: String?) = PushMessage(
        seq = 1, id = 1, eventId = "e", topic = topic, priority = "normal", title = "Stadtwerke", body = "Rechnung",
        createdAt = null, expiresAt = null, collapseKey = null, silent = false, route = null, actions = emptyList(), dataJson = data,
    )

    @Test
    fun `pushes - a new mail opens it, a failed send the outbox`() {
        val m = msg("plugin:gatecontrol-mail:mail", """{"mail":{"messageId":42,"accountId":3}}""")
        assertTrue(MailPush.isMailMessage(m))
        assertEquals("message:42", MailPush.destination(m))
        assertEquals(42L, MailPush.messageOf("message:42"))
        assertNull(MailPush.messageOf("message:0"))
        assertEquals("message:7", MailPush.destination(msg("plugin:gatecontrol-mail:mail", """{"mail":{"messageId":"7"}}""")))
        assertEquals("outbox:4", MailPush.destination(msg("plugin:gatecontrol-mail:mail_failed", """{"mail":{"outboxId":4}}""")))
        assertEquals("outbox", MailPush.destination(msg("plugin:gatecontrol-mail:mail_failed", null)))
        assertTrue(MailPush.isOutbox("outbox:4"))
        assertFalse(MailPush.isOutbox("message:4"))
        assertNull(MailPush.destination(msg("plugin:gatecontrol-mail:mail", """{"mail":{"messageId":-1}}""")))
        assertNull(MailPush.destination(msg("plugin:gatecontrol-drive:mail", """{"mail":{"messageId":42}}""")), "another plugin")
        assertNull(MailPush.destination(msg("plugin:gatecontrol-mail:mail", "not json")))
        assertTrue(MailPush.isMailTopic("plugin:gatecontrol-mail:mail"))
        assertFalse(MailPush.isMailTopic("plugin:gatecontrol-mail:mail_failed"))
    }

    private fun item(id: Long, date: Long) = JsonParser.parseString("""{"id":$id,"date":$date}""").asJsonObject

    @Test
    fun `offline - at least 30 days of the inbox, and the newest hundred`() {
        val day = 24 * 3600_000L
        val now = 400 * day
        val items = (1..150L).map { item(it, now - it * day / 2) } // two per day: 75 days back
        val kept = MailCacheLogic.prune(items, now)
        assertTrue(kept.size >= MailCacheLogic.MIN_ITEMS)
        assertTrue(kept.all { it.get("id").asLong <= MailCacheLogic.MIN_ITEMS || it.get("date").asLong >= now - 30 * day })
        // few but old: kept all the same
        assertEquals(3, MailCacheLogic.prune(listOf(item(1, 0), item(2, 0), item(3, 0)), now).size)
        // no id: gone
        assertEquals(0, MailCacheLogic.prune(listOf(JsonParser.parseString("""{"date":5}""").asJsonObject), now).size)
    }

    @Test
    fun `offline - pages merged, opened mails pruned`() {
        val merged = MailCacheLogic.mergeRaw(listOf(item(1, 1), item(2, 2)), listOf(item(2, 20), item(3, 3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.get("id").asLong })
        assertEquals(20L, merged[1].get("date").asLong)
        val now = 100 * 24 * 3600_000L
        val files = listOf("old.json" to 0L) + (1..(MailCacheLogic.MAX_MESSAGES + 2)).map { "m$it.json" to now - it }
        val gone = MailCacheLogic.overflow(files, now)
        assertTrue("old.json" in gone)
        assertEquals(3, gone.size)
        assertTrue("m${MailCacheLogic.MAX_MESSAGES + 2}.json" in gone, "the oldest above the limit")
    }
}
