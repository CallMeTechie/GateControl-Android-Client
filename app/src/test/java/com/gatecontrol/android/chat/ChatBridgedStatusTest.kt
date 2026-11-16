package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Messenger 1.25 (Entwurf „Status über WhatsApp“): the contacts' WhatsApp status updates in the status row —
 * `GET status/feed` → `bridged`, mixed with the accounts (unseen first, then the newest), the switch
 * `privacy.bridgedStatusSeen`, the live event `story.bridged`. Names are made up.
 */
class ChatBridgedStatusTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val feedJson = """{"ok":true,
      "items":[
        {"user":{"id":8,"name":"Kim Muster"},"status":null,"stories":[{"id":"s1","kind":"text","text":"Hallo","createdAt":"2026-11-16T09:00:00.000Z","seen":false}],"unseen":true},
        {"user":{"id":9,"name":"Jo Test"},"status":null,"stories":[{"id":"s2","kind":"text","text":"Alt","createdAt":"2026-11-16T06:00:00.000Z","seen":true}],"unseen":false}],
      "bridged":[
        {"network":"whatsapp","contact":{"id":21,"name":"Alex Beispiel","avatarMediaId":"0123456789abcdef0123456789abcdef","avatarUrl":null},"chatId":44,"canReply":true,
         "stories":[{"id":101,"kind":"image","text":"Abendrunde am See","mediaId":"0123456789abcdef0123456789abcdee","createdAt":"2026-11-16T09:30:00.000Z","expiresAt":"2026-11-17T09:30:00.000Z","seen":false}],"unseen":true},
        {"network":"whatsapp","contact":{"id":22,"name":"Robin Platz"},"chatId":null,"canReply":false,
         "stories":[{"id":102,"kind":"unsupported","text":null,"createdAt":"2026-11-16T05:00:00.000Z","seen":true}],"unseen":false},
        {"network":"whatsapp","contact":{"id":23,"name":"Leer"},"stories":[]},
        {"network":"WhatsApp!","contact":{"id":24},"stories":[{"id":103,"kind":"text","text":"x"}]},
        {"network":"whatsapp","contact":{"id":-1},"stories":[{"id":104,"kind":"text","text":"x"}]}],
      "me":{"stories":[]}}"""

    @Test
    fun `bridged entries are mixed in — unseen first, then the newest — with their own key and no account id`() {
        val feed = ChatJson14.feed(json(feedJson))!!
        assertEquals(listOf("wa:21", "8", "9", "wa:22"), feed.items.map { it.key }, "Alex (09:30, unseen), Kim (09:00, unseen), Jo, Robin")
        val alex = feed.items[0]
        assertEquals(ChatBridgedStatus("whatsapp", 21, 44, true, "0123456789abcdef0123456789abcdef"), alex.bridged)
        assertTrue(alex.user.userId < 0, "never an account's id")
        assertTrue(ChatBridgedStatus.isBridgedId(alex.user.userId))
        assertEquals("Alex Beispiel", alex.user.name)
        assertEquals(ChatStory.KIND_IMAGE, alex.stories[0].kind)
        val robin = feed.items[3]
        assertFalse(robin.bridged!!.canReply)
        assertNull(robin.bridged!!.chatId)
        assertEquals(ChatStory.KIND_UNSUPPORTED, robin.stories[0].kind, "a status this app cannot show: „in WhatsApp öffnen“")
        assertEquals(4, ChatStatusLogic.strip(feed).size, "empty, a bad network and a bad contact id are dropped")
    }

    @Test
    fun `an account's story may not be unsupported, an older server sends no bridged`() {
        val f = ChatJson14.feed(json("""{"items":[{"user":{"id":8},"stories":[{"id":"s1","kind":"unsupported"}]}],"me":null}"""))!!
        assertTrue(f.items[0].stories.isEmpty())
        assertTrue(f.items.none { it.bridged != null })
    }

    @Test
    fun `seen marks the bridged status by its stand-in id`() {
        val feed = ChatJson14.feed(json(feedJson))!!
        val alex = feed.items[0]
        val after = ChatStatusLogic.markSeen(feed, alex.user.userId, "101")
        assertFalse(after.items.first { it.key == "wa:21" }.unseen)
        assertFalse(ChatStatusLogic.markSeenAnywhere(feed, "101").items.first { it.key == "wa:21" }.unseen)
        assertEquals(feed, ChatStatusLogic.markSeenAnywhere(feed, "nope"))
    }

    @Test
    fun `the switch is read and only sent back to a server that knows it`() {
        val p = ChatJson14.prefs14(json("""{"privacy":{"lastSeen":"all","bridgedStatusSeen":false}}"""))
        assertEquals(false, p.privacy.bridgedStatusSeen)
        assertEquals(false, ChatJson14.writePrivacy(p.privacy).get("bridgedStatusSeen").asBoolean)
        val old = ChatJson14.prefs14(json("""{"privacy":{"lastSeen":"all"}}"""))
        assertNull(old.privacy.bridgedStatusSeen)
        assertFalse(ChatJson14.writePrivacy(old.privacy).has("bridgedStatusSeen"), "an older server refuses an unknown key")
        assertTrue(ChatJson14.writePrivacy(p.privacy.copy(bridgedStatusSeen = true)).get("bridgedStatusSeen").asBoolean)
    }

    @Test
    fun `the feature and the live event`() {
        val f = ChatJson.features(json("""{"status":true,"bridge_status":true}"""))
        assertTrue(f.bridgeStatus)
        assertEquals(emptySet<String>(), ChatFeatureHint.unknown(f), "known to this app: no update hint")
        val e = ChatEvents.parse(MESSENGER_PLUGIN, "story.bridged", json("""{"network":"whatsapp","storyId":101,"action":"new"}"""))
        assertInstanceOf(ChatEvent.StatusChanged::class.java, e)
        assertEquals(0L, (e as ChatEvent.StatusChanged).userId)
        assertEquals("101", e.storyId)
    }
}
