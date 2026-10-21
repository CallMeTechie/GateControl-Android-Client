package com.gatecontrol.android.chat

import com.gatecontrol.android.familie.FamilieDest
import com.gatecontrol.android.familie.school.SchoolView
import com.gatecontrol.android.push.PushNavTarget
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Messenger 1.6: pinned chats (`listPinned`), notice channels (`notice`), notice cards with a link. */
class ChatNoticeTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private fun conv(id: Long, extra: String = "", at: String = "2026-10-21T10:00:00.000Z"): ChatConversation =
        ChatJson.conversation(json("""{"id":$id,"kind":"group","title":"C$id","members":[],"me":{},"can":{},"updatedAt":"$at"$extra}"""))!!

    // ─── conversation fields ────────────────────────────────────────

    @Test
    fun `listPinned and notice are read`() {
        val family = conv(12, ""","managedBy":"gatecontrol-familie","listPinned":true""")
        assertTrue(family.listPinned)
        assertNull(family.notice)
        assertFalse(family.isNotice)

        val school = conv(30, ""","readonly":true,"notice":{"source":"plugin:gatecontrol-schule","label":"Schule"}""")
        assertFalse(school.listPinned)
        assertTrue(school.readonly)
        assertTrue(school.isNotice)
        assertEquals(ChatNotice("plugin:gatecontrol-schule", "Schule"), school.notice)

        val core = conv(31, ""","notice":{"source":"core","label":"GateControl"}""")
        assertEquals("core", core.notice?.source)
    }

    @Test
    fun `older messengers send neither field`() {
        val c = conv(5)
        assertFalse(c.listPinned)
        assertNull(c.notice)
    }

    @Test
    fun `malformed values are dropped or made safe`() {
        // listPinned only as a flag; 1 counts like true (SQLite).
        assertFalse(conv(1, ""","listPinned":"yes"""").listPinned)
        assertTrue(conv(2, ""","listPinned":1""").listPinned)
        // notice must be an object; a bad source is dropped, the channel stays a notice channel.
        assertNull(conv(3, ""","notice":"Schule"""").notice)
        val n = conv(4, ""","notice":{"source":"plugin:../evil","label":"  Schu\u0007le  "}""").notice
        assertNotNull(n)
        assertNull(n!!.source)
        assertEquals("Schu le", n.label)
        assertEquals(60, conv(6, ""","notice":{"source":"core","label":"${"x".repeat(200)}"}""").notice?.label?.length)
    }

    @Test
    fun `the cache keeps both fields (round trip)`() {
        val pinned = conv(12, ""","listPinned":true""")
        assertEquals(pinned, ChatJson.conversation(ChatSerializer.conversation(pinned)))
        val notice = conv(30, ""","readonly":true,"notice":{"source":"plugin:gatecontrol-schule","label":"Schule"}""")
        assertEquals(notice, ChatJson.conversation(ChatSerializer.conversation(notice)))
        val plain = conv(5)
        val written = ChatSerializer.conversation(plain)
        assertFalse(written.has("listPinned"))
        assertFalse(written.has("notice"))
        assertEquals(plain, ChatJson.conversation(written))
    }

    // ─── list sections ──────────────────────────────────────────────

    @Test
    fun `pinned chats go under Angeheftet, the rest under Chats, each in its order`() {
        val a = conv(1, at = "2026-10-21T10:05:00.000Z")
        val family = conv(2, ""","listPinned":true""", at = "2026-10-21T10:04:00.000Z")
        val b = conv(3, at = "2026-10-21T10:03:00.000Z")
        val other = conv(4, ""","listPinned":true""", at = "2026-10-21T10:02:00.000Z")
        val split = ChatListSections.split(listOf(a, family, b, other), sectioned = true)
        assertEquals(listOf(family, other), split.pinned)
        assertEquals(listOf(a, b), split.chats)
    }

    @Test
    fun `searching or sharing shows pinned chats among the results`() {
        val a = conv(1)
        val family = conv(2, ""","listPinned":true""")
        val split = ChatListSections.split(listOf(a, family), sectioned = false)
        assertTrue(split.pinned.isEmpty())
        assertEquals(listOf(a, family), split.chats)
    }

    @Test
    fun `no pinned chats leaves the list as it was`() {
        val list = listOf(conv(1), conv(2))
        val split = ChatListSections.split(list, sectioned = true)
        assertTrue(split.pinned.isEmpty())
        assertEquals(list, split.chats)
    }

    // ─── notice cards ───────────────────────────────────────────────

    private fun card(body: String): ChatPluginCard = ChatJson14.card(json(body))!!

    @Test
    fun `a notice card keeps the whole text and its link`() {
        val text = "Liebe Eltern, ".repeat(250) // 3500 characters
        val c = card("""{"kind":"notice","title":"Elternabend","text":"$text","link":{"label":"Ganze Nachricht lesen","route":"plg-gatecontrol-schule/messages/4711"},"actions":[]}""")
        assertEquals(text, c.text)
        val link = c.buttons.single()
        assertEquals("Ganze Nachricht lesen", link.label)
        assertEquals("plg-gatecontrol-schule/messages/4711", link.route)
        assertNull(link.actionId)
        // Capped at 4000 characters, the link's label at 40.
        val long = card("""{"kind":"notice","text":"${"a".repeat(5000)}","link":{"label":"${"b".repeat(60)}","route":"plg-gatecontrol-schule"}}""")
        assertEquals(4000, long.text?.length)
        assertEquals(40, long.buttons.single().label?.length)
    }

    @Test
    fun `a notice card of the messenger is read with its notification`() {
        val m = ChatJson.message(
            json(
                """
                {"id":901,"conversationId":30,"senderId":null,"kind":"card","mine":false,"createdAt":"2026-10-21T07:40:00.000Z",
                 "deleted":false,"undecryptable":false,"text":null,"reactions":[],
                 "card":{"kind":"notice","title":"Elternabend Klasse 6b","text":"Liebe Eltern,\n\nder Elternabend …","image":null,
                         "imageUrl":"/notify-media/abcdef0123456789.jpg",
                         "link":{"label":"Ganze Nachricht lesen","route":"plg-gatecontrol-schule","kind":null},
                         "actions":[{"plugin":"gatecontrol-schule","actionId":"confirm","label":"Bestätigen","icon":null,"needsConfirm":true,"canRun":true}],
                         "lastRun":null,
                         "notice":{"source":"plugin:gatecontrol-schule","sourceLabel":"Schule","topic":"plugin:gatecontrol-schule:messages",
                                   "topicLabel":"Nachrichten der Schule","priority":"normal","data":{"schule":{"messageId":"4711","studentId":3}}}}}
                """,
            ),
        )!!
        val card = m.card!!
        assertEquals("Liebe Eltern,\n\nder Elternabend …", card.text)
        assertEquals("/notify-media/abcdef0123456789.jpg", card.imageUrl)
        assertFalse(card.hasImage)
        val n = card.notice!!
        assertEquals("Schule", n.sourceLabel)
        assertEquals("Nachrichten der Schule", n.topicLabel)
        assertEquals("plugin:gatecontrol-schule:messages", n.topic)
        assertEquals(json("""{"schule":{"messageId":"4711","studentId":3}}"""), json(n.dataJson!!))
        // The action first, the link last.
        assertEquals(listOf("confirm", null), card.buttons.map { it.actionId })
        assertEquals("gatecontrol-schule", card.buttons[0].plugin)
        // "Ganze Nachricht lesen" opens that school message, as the push would.
        assertEquals(
            PushNavTarget.Familie(FamilieDest.School(studentId = 3, view = SchoolView.MESSAGES, messageId = "4711").key),
            ChatCardLinks.target(card.buttons[1].route!!, n),
        )
        // The cache keeps all of it.
        assertEquals(card, ChatJson14.card(ChatJson14.writeCard(card)))
        // A foreign picture address is dropped.
        assertNull(card("""{"kind":"notice","imageUrl":"https://evil.example/x.jpg"}""").imageUrl)
    }

    @Test
    fun `card routes stay app routes`() {
        for (bad in listOf("https://evil.example", "plg-x/../y", "plg-x//y", "plg-x/a b", "javascript:alert(1)", "plg-x/a/b/c/d")) {
            val c = card("""{"kind":"notice","text":"x","link":{"label":"L","route":"$bad"}}""")
            assertTrue(c.buttons.isEmpty(), bad)
        }
        assertEquals("plg-gatecontrol-door", card("""{"kind":"notice","link":{"label":"L","route":"plg-gatecontrol-door"}}""").buttons.single().route)
    }

    @Test
    fun `a card link opens the screen it names`() {
        // Schule: the message itself, its tab "Nachrichten" without one.
        assertEquals(
            PushNavTarget.Familie(FamilieDest.School(view = SchoolView.MESSAGES, messageId = "4711").key),
            ChatCardLinks.target("plg-gatecontrol-schule/messages/4711"),
        )
        assertEquals(
            PushNavTarget.Familie(FamilieDest.School(studentId = 3, view = SchoolView.MESSAGES, messageId = "a:9").key),
            ChatCardLinks.target("plg-gatecontrol-schule/messages/3/a:9"),
        )
        assertEquals(PushNavTarget.Familie("school/messages"), ChatCardLinks.target("plg-gatecontrol-schule"))
        assertEquals(PushNavTarget.Familie("school/absences/3"), ChatCardLinks.target("plg-gatecontrol-schule/absences/3"))
        assertEquals(PushNavTarget.Familie("school/messages"), ChatCardLinks.target("plg-gatecontrol-schule/nonsense/1/2"))
        // Familie: a screen of the tab, else its start.
        assertEquals(PushNavTarget.Familie("shopping"), ChatCardLinks.target("plg-gatecontrol-familie/shopping"))
        assertEquals(PushNavTarget.Familie("home"), ChatCardLinks.target("plg-gatecontrol-familie/nope"))
        // Everything else as for notifications.
        assertEquals(PushNavTarget.Door("home", null), ChatCardLinks.target("plg-gatecontrol-door"))
        assertEquals(PushNavTarget.Door("home", null), ChatCardLinks.target("plg-gatecontrol-door/live"))
        assertEquals(PushNavTarget.Vpn, ChatCardLinks.target("vpn"))
        assertEquals(PushNavTarget.Inbox, ChatCardLinks.target("plg-skoda"))
    }
}
