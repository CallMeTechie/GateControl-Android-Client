package com.gatecontrol.android.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every answer shape of the messenger's app API (plugin README "App-API"). */
class ChatJsonTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val me = json(
        """
        {"ok":true,"user":{"id":7,"name":"Marc","avatarUrl":"/avatars/abc.webp","initials":"MA","colorHex":"#2DD4BF"},
         "prefs":{"readReceipts":false,"typing":true,"preview":false},
         "license":{"licensed":true,"state":"active","expiresAt":null},
         "features":{"direct":true,"groups":true,"voice":true,"files":true,"location":true,"reactions":true,"edit":false,"search":true,"disappearing":true,"bridges":false,"e2e":false},
         "limits":{"maxBytes":26214400,"types":["image/jpeg","image/webp","audio/mp4","application/pdf","Bad Type"],"quotaFull":false,
                   "editWindowS":900,"maxVoiceS":180,"textMax":4000,"thumbMaxBytes":65536,"imageMaxSide":2048,"groupMax":256,
                   "titleMax":80,"pageMax":100,"typingS":6,"typingEveryS":3,"activeS":30,"disappearingS":[0,86400,604800,2592000],"retentionDays":0},
         "serverTime":"2026-10-17T10:00:00.000Z"}
        """,
    )

    private val conversations = json(
        """
        {"ok":true,"serverTime":"2026-10-17T10:00:05.000Z","removed":[3,"4",-1],
         "conversations":[
          {"id":12,"kind":"group","title":"Familie","peer":null,"avatarUrl":null,"avatarMediaId":"0123456789abcdef0123456789abcdef",
           "managedBy":"gatecontrol-familie","readonly":false,"disappearingS":604800,"createdBy":7,
           "createdAt":"2026-10-01T10:00:00.000Z","updatedAt":"2026-10-17T09:59:00.000Z","lastMessageId":4711,
           "members":[{"userId":7,"name":"Marc","avatarUrl":null,"initials":"MA","colorHex":null,"gone":false,"role":"admin","joinedAt":"2026-10-01T10:00:00.000Z"},
                      {"userId":8,"name":null,"avatarUrl":null,"initials":null,"colorHex":null,"gone":true,"role":"member"}],
           "me":{"role":"admin","mutedUntil":null,"notify":"all","lastReadId":4700,"lastDeliveredId":4711},
           "muted":false,"unread":3,
           "lastMessage":{"id":4711,"conversationId":12,"senderId":8,"clientId":null,"kind":"voice","via":null,"mine":false,
                          "createdAt":"2026-10-17T09:59:00.000Z","editedAt":null,"deletedAt":null,"expiresAt":"2026-10-24T09:59:00.000Z",
                          "deleted":false,"undecryptable":false,"text":null,"replyTo":null,
                          "media":{"id":"fedcba9876543210fedcba9876543210","mime":"audio/mp4","bytes":48211,"url":null,"thumbId":null,"thumbUrl":null},
                          "meta":{"durationMs":14000,"waveform":[0,128,300,-5]},"location":null,"system":null,"reactions":[],"status":null},
           "typing":[8],"readUpTo":4705,"deliveredUpTo":4711,"receipts":[{"userId":8,"lastReadId":4705,"lastDeliveredId":4711}],
           "can":{"send":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":true}},
          {"id":13,"kind":"direct","title":"Anna","peer":{"userId":9,"name":"Anna","avatarUrl":"https://evil.example/x.png","initials":"AN","colorHex":"red"},
           "members":[],"me":{},"unread":0,"can":{}},
          {"id":"x","kind":"group"},
          {"id":14,"kind":"channel"}
         ]}
        """,
    )

    @Test
    fun `me is parsed with user, prefs, licence, features and limits`() {
        val p = ChatJson.profile(me)!!
        assertEquals(7L, p.user.userId)
        assertEquals("Marc", p.user.name)
        assertEquals("/avatars/abc.webp", p.user.avatarUrl)
        assertFalse(p.prefs.readReceipts)
        assertFalse(p.prefs.preview)
        assertEquals(true, p.licensed)
        assertFalse(p.features.edit)
        assertTrue(p.features.disappearing)
        assertEquals(26_214_400L, p.limits.maxBytes)
        assertEquals(listOf("image/jpeg", "image/webp", "audio/mp4", "application/pdf"), p.limits.types)
        assertEquals(180, p.limits.maxVoiceS)
        assertEquals(listOf(0, 86_400, 604_800, 2_592_000), p.limits.disappearingS)
        assertEquals("2026-10-17T10:00:00.000Z", p.serverTime)
    }

    @Test
    fun `me without a user is refused`() {
        assertNull(ChatJson.profile(json("""{"ok":true,"prefs":{}}""")))
    }

    @Test
    fun `the conversation list keeps valid conversations and removed ids`() {
        val l = ChatJson.conversationList(conversations)!!
        assertEquals(listOf(12L, 13L), l.conversations.map { it.id })
        assertEquals(listOf(3L, 4L), l.removed)
        assertEquals("2026-10-17T10:00:05.000Z", l.serverTime)
        val g = l.conversations[0]
        assertTrue(g.isGroup)
        assertEquals("gatecontrol-familie", g.managedBy)
        assertEquals("0123456789abcdef0123456789abcdef", g.avatarMediaId)
        assertEquals(604_800, g.disappearingS)
        assertEquals(3, g.unread)
        assertEquals(4700L, g.me.lastReadId)
        assertEquals(listOf(8L), g.typing)
        assertEquals(4705L, g.readUpTo)
        assertTrue(g.can.setDisappearing)
        assertFalse(g.can.manageMembers)
        assertTrue(g.members[1].gone)
        assertNull(g.members[1].name)
        assertEquals(ChatPerson.ROLE_ADMIN, g.members[0].role)
        val voice = g.lastMessage!!
        assertEquals(ChatMessage.KIND_VOICE, voice.kind)
        assertEquals(14_000L, voice.meta!!.durationMs)
        assertEquals(listOf(0, 128, 255, 0), voice.meta!!.waveform)
        assertEquals("audio/mp4", voice.media!!.mime)
    }

    @Test
    fun `untrusted values are dropped`() {
        val d = ChatJson.conversationList(conversations)!!.conversations[1]
        assertNull(d.peer!!.avatarUrl, "only paths of the own server")
        assertNull(d.peer!!.colorHex)
        assertEquals("all", d.me.notify)
        assertTrue(d.can.send, "can.send defaults to true")
        assertFalse(d.can.leave)
    }

    @Test
    fun `a message page with text, reply, reactions, location, system and tombstone`() {
        val page = ChatJson.messagePage(
            json(
                """
                {"ok":true,"hasMore":true,"readUpTo":10,"deliveredUpTo":11,"receipts":[],
                 "messages":[
                  {"id":10,"conversationId":12,"senderId":7,"clientId":"abcdefgh12","kind":"text","mine":true,"createdAt":"2026-10-17T09:00:00.000Z",
                   "editedAt":"2026-10-17T09:01:00.000Z","text":"Hallo\r\nWelt\u0007","replyTo":{"id":9,"senderId":8,"kind":"image","text":"Bild","deleted":false,"missing":false},
                   "reactions":[{"emoji":"❤️","count":2,"userIds":[8,9]},{"emoji":"","count":1}],"status":"read"},
                  {"id":11,"conversationId":12,"senderId":8,"kind":"location","mine":false,"createdAt":"2026-10-17T09:02:00.000Z",
                   "location":{"lat":52.52,"lon":13.405,"label":"Sporthalle Nord","accuracy":12},"status":"read"},
                  {"id":12,"conversationId":12,"senderId":null,"kind":"system","mine":false,"createdAt":"2026-10-17T09:03:00.000Z",
                   "system":{"event":"member_added","by":7,"userIds":[9]}},
                  {"id":13,"conversationId":12,"senderId":8,"kind":"text","mine":false,"createdAt":"2026-10-17T09:04:00.000Z",
                   "deleted":true,"text":"should not show","reactions":[{"emoji":"👍","count":1,"userIds":[7]}]},
                  {"id":14,"conversationId":12,"kind":"sticker","createdAt":"2026-10-17T09:05:00.000Z"},
                  {"id":15,"conversationId":12,"kind":"location","createdAt":"2026-10-17T09:05:00.000Z","location":{"lat":95,"lon":0}}
                 ]}
                """,
            ),
        )!!
        assertTrue(page.hasMore)
        // An unknown kind (a newer messenger) is kept and shown as "needs a newer app"; a bad location is dropped.
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L, 15L), page.messages.map { it.id })
        assertTrue(page.messages[4].isUnknownKind)
        assertNull(page.messages[5].location)
        val t = page.messages[0]
        assertEquals("Hallo\nWelt", t.text)
        assertEquals("abcdefgh12", t.clientId)
        assertEquals(ChatMessage.STATUS_READ, t.status)
        assertEquals(9L, t.replyTo!!.id)
        assertEquals(listOf("❤️"), t.reactions.map { it.emoji })
        assertEquals(listOf(8L, 9L), t.reactions[0].userIds)
        assertNull(page.messages[1].status, "status only for own messages")
        assertEquals("Sporthalle Nord", page.messages[1].location!!.label)
        assertEquals(12, page.messages[1].location!!.accuracy)
        assertEquals("member_added", page.messages[2].system!!.event)
        assertEquals(listOf(9L), page.messages[2].system!!.userIds)
        val tomb = page.messages[3]
        assertTrue(tomb.deleted)
        assertNull(tomb.text)
        assertTrue(tomb.reactions.isEmpty())
        assertNull(page.messages[4].location, "latitude out of range")
    }

    @Test
    fun `serialised models parse back to the same models`() {
        val l = ChatJson.conversationList(conversations)!!
        for (c in l.conversations) {
            assertEquals(c, ChatJson.conversation(ChatSerializer.conversation(c)))
        }
        val p = ChatJson.profile(me)!!
        assertEquals(p, ChatJson.profile(ChatSerializer.profile(p)))
        val msg = l.conversations[0].lastMessage!!
        assertEquals(msg, ChatJson.message(ChatSerializer.message(msg)))
    }

    @Test
    fun `the send body carries clientId, kind and only what is set`() {
        val b = ChatJson.sendBody(
            clientId = "c1c1c1c1c1",
            kind = ChatMessage.KIND_VOICE,
            text = null,
            replyTo = 9,
            mediaId = "0123456789abcdef0123456789abcdef",
            meta = ChatMeta(durationMs = 1400, waveform = listOf(0, 300, 12)),
            source = "notification",
        )
        assertEquals("c1c1c1c1c1", b.get("clientId").asString)
        assertEquals("voice", b.get("kind").asString)
        assertFalse(b.has("text"))
        assertEquals(9, b.get("replyTo").asInt)
        assertEquals(1400, b.getAsJsonObject("meta").get("durationMs").asInt)
        assertEquals(listOf(0, 255, 12), b.getAsJsonObject("meta").getAsJsonArray("waveform").map { it.asInt })
        assertEquals("notification", b.get("source").asString)
        val loc = ChatJson.sendBody("c2c2c2c2c2", ChatMessage.KIND_LOCATION, null, location = ChatLocation(1.5, 2.5, "Hier", 7))
        assertEquals(1.5, loc.getAsJsonObject("location").get("lat").asDouble)
        assertEquals("Hier", loc.getAsJsonObject("location").get("label").asString)
    }

    @Test
    fun `request bodies of conversations, reactions and read marks`() {
        assertEquals("""{"kind":"direct","userIds":[9]}""", ChatJson.createDirectBody(9).toString())
        assertEquals("""{"kind":"group","title":"Familie","userIds":[8,9]}""", ChatJson.createGroupBody("Familie", listOf(8, 9, 8)).toString())
        assertEquals("""{"emoji":null}""", ChatJson.reactionBody(null).toString())
        assertEquals("""{"upTo":4711}""", ChatJson.readBody(4711).toString())
        assertEquals("""{"mutedUntil":null}""", ChatPatches.mute(null).toString())
        assertEquals("""{"role":{"userId":8,"role":"admin"}}""", ChatPatches.role(8, true).toString())
        assertEquals("""{"remove":[7]}""", ChatPatches.remove(listOf(7)).toString())
    }

    @Test
    fun `small answers`() {
        assertEquals("edit_window", ChatJson.errorCode(json("""{"ok":false,"code":"edit_window","error":"Zu spät"}""")))
        assertNull(ChatJson.errorCode(json("""{"ok":false,"code":"<script>"}""")))
        assertEquals("0123456789abcdef0123456789abcdef", ChatJson.uploadedMediaId(json("""{"ok":true,"mediaId":"0123456789abcdef0123456789abcdef","mime":"image/webp","bytes":10}""")))
        assertNull(ChatJson.uploadedMediaId(json("""{"ok":true,"mediaId":"../etc"}""")))
        assertEquals(2, ChatJson.unreadOf(json("""{"ok":true,"lastReadId":5,"unread":2}""")))
        assertEquals(1, ChatJson.search(json("""{"ok":true,"results":[{"id":1,"conversationId":2,"kind":"text","createdAt":"2026-10-17T09:00:00.000Z","text":"x"}],"scanned":10,"truncated":false}"""))!!.results.size)
        assertNotNull(ChatJson.people(json("""{"ok":true,"people":[{"id":9,"name":"Anna"},{"id":9,"name":"Dup"},{"name":"no id"}]}""")))
        assertEquals(listOf(9L), ChatJson.people(json("""{"ok":true,"people":[{"id":9,"name":"Anna"},{"id":9,"name":"Dup"},{"name":"no id"}]}"""))!!.map { it.userId })
    }

    @Test
    fun `conversation ids may come as strings (push payloads)`() {
        assertEquals(12L, ChatJson.idOf(JsonParser.parseString("\"12\"")))
        assertEquals(12L, ChatJson.idOf(JsonParser.parseString("12")))
        assertNull(ChatJson.idOf(JsonParser.parseString("\"12a\"")))
        assertNull(ChatJson.idOf(JsonParser.parseString("1.5")))
        assertNull(ChatJson.idOf(JsonParser.parseString("0")))
    }
}
