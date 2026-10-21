package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushMessage
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Core 1.177: plugin realtime events (`event: plugin`) and chat notifications (`kind: "message"`). */
class PushChatTest {

    private val chatPush = """
        {"seq":130,"id":52,"event_id":"plugin:gatecontrol-messenger:message","topic":"plugin:gatecontrol-messenger:message",
         "priority":"normal","title":"Anna","body":"Bist du schon unterwegs?","kind":"message",
         "created_at":"2026-10-17T18:02:11Z","expires_at":"2026-10-20T18:02:11Z",
         "collapse_key":"plugin:gatecontrol-messenger:chat:12","silent":false,"imageUrl":null,
         "data":{"route":"plg-gatecontrol-messenger","messageId":4711,
                 "conversation":{"id":"12","title":"Familie","isGroup":true},
                 "sender":{"id":7,"name":"Anna","avatarUrl":"/avatars/Xk3.webp"},
                 "actions":[{"id":"reply","label":"Antworten","type":"reply","path":"conversations/12/messages"},
                            {"id":"mark_read","label":"Gelesen","type":"mark_read","path":"conversations/12/read","body":{"upTo":4711}}]}}
    """.trimIndent()

    // ─── parser ─────────────────────────────────────────────────────

    @Test
    fun `a plugin event carries plugin, type and data`() {
        val e = PushEventParser.parse(
            "plugin", null,
            """{"plugin":"gatecontrol-messenger","type":"message.new","data":{"conversationId":12,"messageId":4711}}""",
        )
        val p = assertInstanceOf(PushEvent.Plugin::class.java, e)
        assertEquals("gatecontrol-messenger", p.plugin)
        assertEquals("message.new", p.type)
        assertEquals(4711, p.data!!.asJsonObject.get("messageId").asInt)
    }

    @Test
    fun `a plugin event without data has null data`() {
        val p = assertInstanceOf(PushEvent.Plugin::class.java, PushEventParser.parse("plugin", null, """{"plugin":"x-y","type":"ping","data":null}"""))
        assertNull(p.data)
    }

    @Test
    fun `malformed plugin events are ignored`() {
        listOf(
            """{"plugin":"Bad Plugin","type":"message.new","data":{}}""",
            """{"plugin":"gatecontrol-messenger","type":"Message New","data":{}}""",
            """{"plugin":"gatecontrol-messenger"}""",
            """[1,2]""",
            "not json",
            """{"plugin":"gatecontrol-messenger","type":"t","data":"${"x".repeat(9000)}"}""",
        ).forEach { raw ->
            assertInstanceOf(PushEvent.Ignored::class.java, PushEventParser.parse("plugin", null, raw), raw.take(60))
        }
    }

    @Test
    fun `a chat notification is marked as kind message, others are not`() {
        val n = assertInstanceOf(PushEvent.Notification::class.java, PushEventParser.parse("notification", "130", chatPush))
        assertTrue(n.message.isChat)
        assertEquals(PushMessage.KIND_MESSAGE, n.message.kind)
        assertTrue(n.message.dataJson!!.contains("conversations/12/messages"))
        val other = PushEventParser.parse("notification", "1", chatPush.replace("\"kind\":\"message\",", "\"kind\":\"weird\","))
        assertFalse(assertInstanceOf(PushEvent.Notification::class.java, other).message.isChat)
    }

    @Test
    fun `a data push is kind data and needs no title`() {
        val data = PushEventParser.parse(
            "notification", "9",
            """{"seq":9,"id":19,"topic":"plugin:gatecontrol-gatedrop:received","priority":"info","title":"","kind":"data","silent":true,"data":{"gatedrop":{"transfer":"tr_1"}}}""",
        ) as PushEvent.Notification
        assertEquals(PushMessage.KIND_DATA, data.message.kind)
        assertTrue(data.message.isData)
        assertTrue(data.message.dataJson!!.contains("gatedrop"))
        // Without kind data an empty title is still no notification.
        val none = PushEventParser.parse("notification", "9", """{"seq":9,"id":19,"title":"","data":{}}""")
        assertTrue(none !is PushEvent.Notification)
    }

    // ─── repository ─────────────────────────────────────────────────

    @TempDir lateinit var dir: File
    private lateinit var server: MockWebServer
    private val acks = CopyOnWriteArrayList<String>()
    private lateinit var store: PushPrefsStore
    private lateinit var inbox: InboxCache
    private val presenter = FakePresenter()
    private lateinit var repo: PushRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().startsWith("/api/v1/client/push/ack")) acks += request.body.readUtf8()
                return MockResponse().setBody("""{"ok":true}""")
            }
        }
        server.start()
        store = testStore(dir)
        inbox = InboxCache(File(dir, "inbox.json"))
        repo = PushRepository(TestEndpoint(server.url("/").toString()), store, inbox, presenter, FakePlatform())
        runBlocking { store.setEnabled(true) }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun chatMessage(): PushMessage =
        (PushEventParser.parse("notification", "130", chatPush) as PushEvent.Notification).message

    @Test
    fun `a chat push goes to the chat presenter, never into the inbox, and is acked delivered`() = runBlocking {
        repo.onNotification(chatMessage())
        assertEquals(listOf(52L), presenter.chats.map { it.id })
        assertTrue(presenter.shown.isEmpty())
        assertTrue(inbox.items.value.isEmpty())
        assertEquals(130L, store.lastSeq())
        val ack = JsonParser.parseString(acks.single()).asJsonObject
        assertEquals("delivered", ack.get("state").asString)
        assertEquals(130L, ack.getAsJsonArray("seqs").single().asLong)
    }

    @Test
    fun `a chat push of a topic switched off here is not shown but still acked`() = runBlocking {
        store.setTopicMuted("plugin:gatecontrol-messenger:message", true)
        repo.onNotification(chatMessage())
        assertTrue(presenter.chats.isEmpty())
        assertEquals(1, acks.size)
    }

    @Test
    fun `read and revoke events reach the chat notifications`() = runBlocking {
        repo.onRead(listOf(52L))
        repo.onRevoke(listOf(53L))
        assertEquals(listOf(52L, 53L), presenter.chatGone.toList())
    }

    @Test
    fun `reading a conversation acks its pushes as read with the action`() = runBlocking {
        repo.ackChatRead(listOf(130L, 131L, 130L, 0L), "mark_read")
        val ack = JsonParser.parseString(acks.single()).asJsonObject
        assertEquals("read", ack.get("state").asString)
        assertEquals("mark_read", ack.get("action").asString)
        assertEquals(listOf(130L, 131L), ack.getAsJsonArray("seqs").map { it.asLong })
    }
}
