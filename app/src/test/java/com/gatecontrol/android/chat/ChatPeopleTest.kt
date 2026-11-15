package com.gatecontrol.android.chat

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.Response

/**
 * Messenger 1.21 (Chats v2, Teil B): the feature word `chat_people`, `?people=1`, `person` of a list entry, `folded`,
 * `way` of a message, the routes `persons/…` (entry, read, merge, separate, suggestions, candidates), `recognition` —
 * as the plugin sends them (server/persons.js) — and what the app makes of them: the list without the folded ways, the
 * search over every name, the chips per service, the mixed history without holes, the way of an answer, the names.
 */
class ChatPeopleTest {

    private fun json(s: String): JsonObject = JsonParser.parseString(s).asJsonObject

    private val personJson = """{"id":7,"name":"Jenny Backes","nameFrom":"account","userId":3,"conversationId":41,
        "names":["Jenny Backes","Schatzimausi 💕"],
        "ways":[
          {"way":"whatsapp","label":"WhatsApp","conversationId":17,"title":"Schatzimausi 💕","avatarUrl":null,"avatarMediaId":null,"unread":2,"muted":false,"archived":false,
            "lastMessageAt":"2026-11-15T14:12:00.000Z","bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text","image","file","voice"]},"can":{"send":true,"sendReason":null,"react":true}},
          {"way":"gatecontrol","label":"GateControl","conversationId":41,"title":"Jenny Backes","avatarUrl":"/avatars/3.webp","avatarMediaId":null,"unread":1,"muted":false,"archived":false,
            "lastMessageAt":"2026-11-15T12:02:00.000Z","bridge":null,"can":{"send":true,"sendReason":null,"react":true}}
        ],
        "reply":{"way":"whatsapp","label":"WhatsApp","conversationId":17},"createdAt":"2026-11-15T10:00:00.000Z"}"""

    private fun entryJson(person: String = personJson, id: Long = 41, title: String = "Jenny Backes", unread: Int = 3) =
        """{"id":$id,"kind":"direct","title":"$title","peer":{"userId":3,"name":"Jenny Backes","initials":"JB","colorHex":"#DB2777"},"avatarUrl":"/avatars/3.webp",
            "members":[{"userId":7,"name":"Kevin"},{"userId":3,"name":"Jenny Backes"}],"me":{"role":"member","notify":"all","lastReadId":400},"muted":false,"unread":$unread,
            "updatedAt":"2026-11-15T14:12:00.000Z",
            "lastMessage":{"id":905,"conversationId":17,"senderId":null,"kind":"text","mine":false,"createdAt":"2026-11-15T14:12:00.000Z","text":"Bis später, ich bring Brot mit","reactions":[],"way":"whatsapp",
              "bridge":{"sender":{"contactId":12,"name":"Schatzimausi 💕"},"remote":false,"state":null,"reason":null,"placeholder":null}},
            "can":{"send":true,"editInfo":false,"manageMembers":false,"leave":false,"setDisappearing":true},"person":$person}"""

    private fun waJson(id: Long = 17, person: String? = null) =
        """{"id":$id,"kind":"direct","title":"Schatzimausi 💕","peer":null,"avatarMediaId":null,"members":[{"userId":7,"name":"Kevin"}],"me":{"role":"member","notify":"all"},
            "muted":false,"unread":2,"updatedAt":"2026-11-15T14:12:00.000Z","lastMessage":null,
            "bridge":{"network":"whatsapp","label":"WhatsApp","state":"ok","supports":["text"]},"can":{"send":true}${person?.let { ",\"person\":$it" } ?: ""}}"""

    private fun conv(s: String) = ChatJson.conversation(json(s))!!

    private fun msg(id: Long, conv: Long, at: String, way: String? = null, mine: Boolean = false) = ChatJson.message(
        json("""{"id":$id,"conversationId":$conv,"senderId":${if (mine) 7 else 3},"kind":"text","mine":$mine,"createdAt":"$at","text":"m$id","reactions":[]${way?.let { ",\"way\":\"$it\"" } ?: ""}}"""),
    )!!

    // ─── the word, the query ────────────────────────────────────────

    @Test
    fun `the word chat_people is known and switches persons on`() {
        assertTrue(ChatPeople.FEATURE in ChatFeatureHint.KNOWN)
        assertFalse(ChatFeatureHint.show(ChatFeatures(tokens = setOf("bridge_chats", "bridge_channels", "chat_people")), null))
        assertTrue(ChatPeople.on(ChatFeatures(tokens = setOf("chat_people"))))
        assertFalse(ChatPeople.on(ChatFeatures(tokens = setOf("bridge_chats"))))
        assertFalse(ChatPeople.on(null))
    }

    private val client: ApiClient = mockk()

    private fun api(): ChatApi {
        val setup: SetupRepository = mockk()
        every { setup.getServerUrl() } returns "https://gc.example"
        every { setup.isConfigured() } returns true
        val provider: ApiClientProvider = mockk()
        every { provider.getClient(any()) } returns client
        every { provider.mediaClient(any()) } returns client
        val settings: SettingsRepository = mockk()
        every { settings.getLocale() } returns flowOf("de")
        return ChatApi(setup, provider, settings)
    }

    @Test
    fun `with the word every call says people=1 - the list folds the ways, persons routes`() = runBlocking {
        val api = api()
        val q = slot<Map<String, String>>()
        val queries = mutableListOf<Map<String, String>>()
        coEvery { client.pluginGet(MESSENGER_PLUGIN, "conversations", capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"conversations":[${entryJson()}],"removed":[],"folded":[17],"serverTime":"2026-11-15T14:13:00.000Z"}"""))
        }
        api.conversations(null)
        assertEquals(mapOf("lang" to "de", "bridged" to "1"), queries.last(), "without the word: as before")
        api.channels = true
        api.people = true
        val list = api.conversations(null).valueOrNull()!!
        assertEquals(mapOf("lang" to "de", "bridged" to "1", "channels" to "1", "people" to "1"), queries.last())
        assertEquals(listOf(17L), list.folded)
        val e = list.conversations.single()
        assertEquals(7L, e.person!!.id)
        assertTrue(ChatPeople.isEntry(e))

        coEvery { client.pluginGet(MESSENGER_PLUGIN, "persons/7", capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"conversation":${entryJson(person = "null")},"person":$personJson}"""))
        }
        val entry = api.person(7).valueOrNull()!!
        assertEquals(41L, entry.person!!.conversationId, "the person beside the conversation is taken in")

        val body = slot<JsonObject>()
        coEvery { client.pluginPost(MESSENGER_PLUGIN, "persons/7/read", capture(body), capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"unread":0,"ways":[{"conversationId":41,"way":"gatecontrol","lastReadId":420,"unread":0},{"conversationId":17,"way":"whatsapp","lastReadId":905,"unread":0}]}"""))
        }
        val read = api.personRead(7, 905).valueOrNull()!!
        assertEquals(905L, body.captured.get("upTo").asLong)
        assertEquals(0, read.unread)
        assertEquals(Triple(17L, 905L, 0), read.ways[1])

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "persons", capture(body), capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"conversation":${entryJson()},"person":$personJson}"""))
        }
        api.merge(listOf(17), 3, "account")
        assertEquals("""{"conversationIds":[17],"userId":3,"name":"account"}""", body.captured.toString())
        api.merge(listOf(41, 17), null, "17")
        assertEquals("""{"conversationIds":[41,17],"name":17}""", body.captured.toString(), "a way's name as its id")

        coEvery { client.pluginDelete(MESSENGER_PLUGIN, "persons/7/ways/17", capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"person":null,"conversations":[${entryJson(person = "null", title = "Jenny Backes", unread = 1)},${waJson()}]}"""))
        }
        val split = api.splitWay(7, 17).valueOrNull()!!
        assertNull(split.person, "one way left: dissolved")
        assertEquals(listOf(41L, 17L), split.conversations.map { it.id })
        assertTrue(split.conversations.all { it.person == null })

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "persons/suggestions/17-3", capture(body), capture(q)) } answers {
            queries += q.captured
            Response.success(json("""{"ok":true,"suggestion":{"id":"17-3","state":"later","until":"2026-11-22T14:00:00.000Z"}}"""))
        }
        assertTrue(api.answerSuggestion("17-3", ChatPeople.ACTION_LATER) is ChatResult.Ok)
        assertEquals("later", body.captured.get("action").asString)
        assertTrue(api.answerSuggestion("../x", ChatPeople.ACTION_LATER) is ChatResult.Failed, "only the server's shape of id")
        assertTrue(api.answerSuggestion("17-3", "merge") is ChatResult.Failed)

        coEvery { client.pluginPost(MESSENGER_PLUGIN, "persons", any(), capture(q)) } answers {
            Response.error(409, """{"ok":false,"code":"cannot_merge","reason":"group","error":"…"}""".toResponseBody("application/json".toMediaType()))
        }
        val refused = api.merge(listOf(55), null, null)
        assertEquals(ChatPeople.CANNOT_MERGE, (refused as ChatResult.Failed).code)
        assertTrue(queries.drop(1).all { it["people"] == "1" && it["bridged"] == "1" })
    }

    // ─── fields ─────────────────────────────────────────────────────

    @Test
    fun `person of an entry - ways GateControl first, the reply, the names - and the cache keeps them`() {
        val e = conv(entryJson())
        val p = e.person!!
        assertEquals("Jenny Backes", p.name)
        assertTrue(p.nameFromAccount)
        assertNull(p.nameFromId)
        assertEquals(3L, p.userId)
        assertEquals(listOf(41L, 17L), p.wayIds, "GateControl first")
        assertEquals(listOf("gatecontrol", "whatsapp"), p.kinds.map { it.way })
        assertEquals(ChatPersonReply("whatsapp", "WhatsApp", 17), p.reply)
        assertEquals("whatsapp", p.way(17)!!.bridge!!.network)
        assertEquals(listOf("Jenny Backes", "Schatzimausi 💕"), p.names)
        assertEquals("whatsapp", e.lastMessage!!.way)
        // the cache: stored in the server's shape and read again
        val again = ChatJson.conversation(ChatSerializer.conversation(e))!!
        assertEquals(e.person, again.person)
        assertEquals(e.lastMessage, again.lastMessage)

        // a way's own name
        val named = conv(entryJson(person = personJson.replace("\"nameFrom\":\"account\"", "\"nameFrom\":17")))
        assertFalse(named.person!!.nameFromAccount)
        assertEquals(17L, named.person!!.nameFromId)
        assertEquals("17", ChatPeople.currentName(named.person!!))
        assertEquals(17L, ChatJson.conversation(ChatSerializer.conversation(named))!!.person!!.nameFromId)
    }

    @Test
    fun `nonsense is no person - a chat as before`() {
        assertNull(ChatPeople.person(json("""{"id":7,"ways":[]}""")))
        assertNull(ChatPeople.person(json("""{"conversationId":41,"ways":[{"way":"gatecontrol","conversationId":41}]}""")))
        assertNull(ChatPeople.person(json("""{"id":7,"conversationId":41,"ways":[{"way":"GC!","conversationId":41}]}""")))
        val noReply = ChatPeople.person(json("""{"id":7,"conversationId":41,"ways":[{"way":"gatecontrol","conversationId":41}],"reply":{"way":"whatsapp","conversationId":99}}"""))!!
        assertNull(noReply.reply, "a reply over a way it does not have")
        assertNull(ChatJson.message(json("""{"id":1,"conversationId":2,"kind":"text","createdAt":"2026-11-15T14:12:00.000Z","way":"<b>"}"""))!!.way)
    }

    @Test
    fun `suggestions, candidates, recognition`() {
        val s = ChatPeople.suggestions(
            json(
                """{"suggestions":[
                  {"id":"18-4","conversationId":18,"userId":4,"network":"whatsapp","label":"WhatsApp","strength":"medium","via":"profile_phone","contact":{"name":"Opa"},"account":{"name":"Karl"},"text":"„Opa“ bei WhatsApp ist vielleicht Karl.","reason":"…"},
                  {"id":"17-3","conversationId":17,"userId":3,"network":"whatsapp","label":"WhatsApp","strength":"strong","via":"own_whatsapp",
                   "contact":{"name":"Schatzimausi 💕","avatarUrl":null,"avatarMediaId":null},"account":{"name":"Jenny Backes","avatarUrl":"/avatars/3.webp","initials":"JB","colorHex":"#DB2777"},
                   "text":"„Schatzimausi 💕“ bei WhatsApp ist vielleicht Jenny Backes.","reason":"Jenny Backes hat das eigene WhatsApp mit GateControl verknüpft, und es ist dein Kontakt „Schatzimausi 💕“."},
                  {"id":"x","conversationId":1,"userId":1,"network":"whatsapp","text":"bad id"}]}""",
            ),
        )!!
        assertEquals(listOf("17-3", "18-4"), s.map { it.id }, "strong first, a bad id left out")
        assertTrue(s[0].strong)
        assertEquals("#DB2777", s[0].accountColorHex)
        assertEquals(listOf(ChatNameOption("account", "Jenny Backes"), ChatNameOption("17", "Schatzimausi 💕")), ChatPeople.suggestionNames(s[0]))

        val c = ChatPeople.candidates(
            json("""{"accounts":[{"userId":3,"name":"Jenny Backes","initials":"JB","personId":null}],"chats":[{"conversationId":18,"title":"Opa","network":"whatsapp","label":"WhatsApp","personId":null}]}"""),
        )!!
        assertEquals(3L, c.accounts.single().userId)
        assertEquals("whatsapp", c.chats.single().network)

        val r = ChatPeople.recognition(json("""{"available":true,"network":"whatsapp","asked":false,"allowed":false}"""))!!
        assertTrue(r.ask)
        assertFalse(ChatPeople.recognition(json("""{"available":true,"network":"whatsapp","asked":true,"allowed":true}"""))!!.ask)
        assertNull(ChatPeople.recognition(null))
        assertEquals(r, ChatBridgesJson.info(json("""{"bridges":{"available":true,"location":"server","networks":[],"recognition":{"available":true,"network":"whatsapp","asked":false,"allowed":false}}}"""))!!.recognition)
        assertNull(ChatBridgesJson.info(json("""{"bridges":{"available":true,"networks":[],"recognition":null}}"""))!!.recognition, "an older server: nothing is asked")
    }

    // ─── the list ───────────────────────────────────────────────────

    @Test
    fun `the list - one entry per person, the folded ways out, without the word all as before`() {
        val e = conv(entryJson())
        val wa = conv(waJson())
        val other = conv(waJson(id = 18).replace("Schatzimausi 💕", "Opa"))
        assertEquals(setOf(17L), ChatPeople.folded(listOf(e, wa, other)))
        assertEquals(listOf(41L, 18L), ChatPeople.inList(listOf(e, wa, other), on = true).map { it.id })
        assertEquals(listOf(41L, 17L, 18L), ChatPeople.inList(listOf(e, wa, other), on = false).map { it.id })
        // a way's own view names its person: out of the list even before the entry is there
        val way = conv(waJson(person = personJson))
        assertEquals(setOf(17L), ChatPeople.folded(listOf(way)))
    }

    @Test
    fun `the search finds every name, the chips per service count the ways, lists count for the person`() {
        val e = conv(entryJson())
        assertTrue(ChatPeople.matches(e, "schatz"))
        assertTrue(ChatPeople.matches(e, "jenny"))
        assertFalse(ChatPeople.matches(e, "opa"))
        assertTrue(ChatPeople.inNet(e, "whatsapp"), "„WhatsApp“: persons with a WhatsApp way")
        assertTrue(ChatPeople.inNet(e, ChatBridgeChat.NET_GC), "„Nur GateControl“: persons with a GateControl way")
        assertFalse(ChatPeople.inNet(e, "signal"))
        assertEquals(listOf("whatsapp" to "WhatsApp"), ChatPeople.networks(listOf(e)), "the chip „WhatsApp“ though the entry runs inside GateControl")
        assertTrue(ChatPeople.listed(e, setOf(17L)), "a list with any way holds the person")
        assertFalse(ChatPeople.listed(e, setOf(18L)))
        assertEquals(listOf(3L), ChatPeople.typingOf(e, mapOf(17L to listOf(3L))))
    }

    @Test
    fun `the cache keeps a person - a way stays a way, the lead its face, a fresh view without person ends it`() {
        val e = conv(entryJson())
        val plainLead = conv(entryJson(person = "null", title = "Jenny Backes", unread = 1)).copy(lastMessage = null)
        val kept = ChatPeopleStore.keep(plainLead, e, fresh = false)
        assertEquals(e.person, kept.person, "the answer of an action has no person — kept")
        assertEquals(3, kept.unread, "the unread of all ways")
        assertEquals(905L, kept.lastMessage!!.id, "the newest message of any way")
        assertEquals(plainLead.copy(person = null), ChatPeopleStore.keep(plainLead, e, fresh = true).copy(person = null))
        assertNull(ChatPeopleStore.keep(plainLead, e, fresh = true).person, "GET conversations/:id without person: no person any more")
        val wayOld = conv(waJson(person = personJson))
        val wayNew = conv(waJson())
        assertEquals(e.person!!.conversationId, ChatPeopleStore.keep(wayNew, wayOld, fresh = false).person!!.conversationId, "a folded way stays out of the list")
        assertEquals(wayNew, ChatPeopleStore.keep(wayNew, null, fresh = false))
    }

    // ─── the chat of a person ───────────────────────────────────────

    @Test
    fun `the mixed history - by time over the ways, no holes, the source chips`() {
        val gc = listOf(msg(401, 41, "2026-11-15T12:02:00.000Z"), msg(402, 41, "2026-11-15T12:05:00.000Z", mine = true))
        val wa = listOf(msg(900, 17, "2026-11-15T13:40:00.000Z", way = "whatsapp"), msg(901, 17, "2026-11-15T13:42:00.000Z", mine = true), msg(905, 17, "2026-11-15T14:12:00.000Z"))
        val all = gc + wa
        assertEquals(listOf(401L, 402L, 900L, 901L, 905L), ChatPeople.timeline(all.shuffled(), older = emptySet(), only = null).map { it.id })
        // WhatsApp has older messages on the server: nothing of GateControl before its oldest cached one
        val older = listOf(msg(300, 41, "2026-11-14T08:00:00.000Z")) + all
        assertEquals(listOf(900L, 901L, 905L), ChatPeople.timeline(older, older = setOf(41L, 17L), only = null).map { it.id }, "nothing older than WhatsApp's oldest cached one")
        assertEquals("2026-11-15T13:40:00.000Z", ChatPeople.floorOf(older, setOf(41L, 17L)))
        assertTrue(ChatPeople.timeline(older, older = setOf(41L, 17L), only = null).none { it.id == 300L })
        assertEquals(17L, ChatPeople.nextOlder(older, setOf(41L, 17L)), "the way whose oldest cached message is the newest loads first")
        assertEquals(42L, ChatPeople.nextOlder(older, setOf(41L, 17L, 42L)), "a way without cache first")
        assertNull(ChatPeople.nextOlder(older, emptySet()))
        // a source chip: only its ways, its own floor
        assertEquals(listOf(300L, 401L, 402L), ChatPeople.timeline(older, older = setOf(17L), only = setOf(41L)).map { it.id })
        val p = conv(entryJson()).person!!
        assertEquals(setOf(17L), ChatPeople.waysOf(p, "whatsapp"))
        assertNull(ChatPeople.waysOf(p, null))
        assertEquals("whatsapp", ChatPeople.wayOf(wa[1], p), "without `way` the way of its conversation")
        assertEquals("gatecontrol", ChatPeople.wayOf(gc[0], p))
        assertEquals("WhatsApp", ChatPeople.labelOf(p, "whatsapp"))
    }

    @Test
    fun `ticks per way in the mixed history`() {
        val lead = conv(entryJson()).copy(readUpTo = 402, deliveredUpTo = 402)
        val wa = conv(waJson()).copy(readUpTo = null, deliveredUpTo = null)
        val mineGc = msg(402, 41, "2026-11-15T12:05:00.000Z", mine = true)
        val mineWa = ChatJson.message(json("""{"id":901,"conversationId":17,"senderId":7,"kind":"text","mine":true,"createdAt":"2026-11-15T13:42:00.000Z","text":"Klar","status":"delivered","reactions":[]}"""))!!
        val items = ChatTimeline.build(listOf(mineWa, mineGc), emptyList(), lead, byTime = true, ways = mapOf(41L to lead, 17L to wa))
        val rows = items.filterIsInstance<TimelineItem.Message>()
        assertEquals(listOf(402L, 901L), rows.map { it.message.id })
        assertEquals(ChatMessage.STATUS_READ, rows[0].status, "GateControl: the GC way's receipts")
        assertEquals(ChatMessage.STATUS_DELIVERED, rows[1].status, "WhatsApp: the message's own status")
    }

    @Test
    fun `the way of an answer - the server's reply, an answer to a message its way, the hint only over a bridge`() {
        val p = conv(entryJson()).person!!
        assertEquals(17L, ChatPeople.sendTo(p, null))
        assertEquals("WhatsApp", ChatPeople.replyWay(p, null)!!.label)
        assertEquals("WhatsApp", ChatPeople.hint(p, null))
        val gcMsg = msg(401, 41, "2026-11-15T12:02:00.000Z")
        assertEquals(41L, ChatPeople.sendTo(p, gcMsg), "an answer to a GateControl message goes over GateControl")
        assertNull(ChatPeople.hint(p, gcMsg))
        val gcReply = p.copy(reply = ChatPersonReply("gatecontrol", "GateControl", 41))
        assertEquals(41L, ChatPeople.sendTo(gcReply, null))
        assertNull(ChatPeople.hint(gcReply, null))
        // a way that cannot send: the answer to its message goes the server's way
        val waDown = p.copy(ways = p.ways.map { if (it.conversationId == 17L) it.copy(can = ChatWayCan(send = false)) else it }, reply = ChatPersonReply("gatecontrol", "GateControl", 41))
        assertEquals(41L, ChatPeople.sendTo(waDown, msg(905, 17, "2026-11-15T14:12:00.000Z")))
        assertEquals(41L, ChatPeople.sendTo(p.copy(reply = null), null), "no reply named: the lead")
    }

    @Test
    fun `the names - the account's first, then the chats', by hand as the portal`() {
        val e = conv(entryJson())
        assertEquals(listOf(ChatNameOption("account", "Jenny Backes"), ChatNameOption("17", "Schatzimausi 💕")), ChatPeople.nameOptions(e.person!!))
        assertEquals("account", ChatPeople.currentName(e.person!!))
        // a GateControl chat with another WhatsApp chat
        val gcOnly = conv(entryJson(person = "null"))
        assertEquals(
            listOf(ChatNameOption("account", "Jenny Backes"), ChatNameOption("18", "Opa")),
            ChatPeople.manualNames(gcOnly, null, 18, "Opa"),
        )
        assertEquals(listOf(41L, 18L), ChatPeople.manualIds(gcOnly, 18))
        // a WhatsApp chat with an account
        val wa = conv(waJson())
        assertEquals(listOf(ChatNameOption("account", "Jenny Backes"), ChatNameOption("17", "Schatzimausi 💕")), ChatPeople.manualNames(wa, "Jenny Backes", null, null))
        assertEquals(listOf(17L), ChatPeople.manualIds(wa, null))
        // the person: its lead
        assertEquals(listOf(41L, 18L), ChatPeople.manualIds(e, 18))
    }

    @Test
    fun `Mit Person zusammenfuehren - chats with one person only`() {
        assertTrue(ChatPeople.canMerge(conv(entryJson()), on = true))
        assertTrue(ChatPeople.canMerge(conv(waJson()), on = true))
        assertTrue(ChatPeople.canMerge(conv(entryJson(person = "null")), on = true))
        assertFalse(ChatPeople.canMerge(conv(waJson()), on = false), "without the word: nothing")
        val group = conv(waJson().replace("\"kind\":\"direct\"", "\"kind\":\"group\""))
        assertFalse(ChatPeople.canMerge(group, on = true))
        val channel = conv(waJson().replace("\"state\":\"ok\"", "\"state\":\"ok\",\"channel\":true"))
        assertFalse(ChatPeople.canMerge(channel, on = true))
    }

    @Test
    fun `a push of a way names the person`() {
        val p = ChatNotifyLogic.payload(
            json("""{"messageId":905,"conversationId":17,"bridge":"whatsapp","person":{"id":7,"conversationId":41},"sender":{"id":"ext-12","name":"Schatzimausi 💕"},"actions":[]}"""),
        )!!
        assertEquals(17L, p.conversationId)
        assertEquals(7L, p.personId)
        assertEquals(41L, p.personConversationId)
        assertNull(ChatNotifyLogic.payload(json("""{"messageId":1,"conversationId":17,"actions":[]}"""))!!.personId)
    }
}
