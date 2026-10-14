package com.gatecontrol.android.notify

import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushState
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class PushRepositoryTest {

    @TempDir lateinit var dir: File

    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile private var ackStatus = 200
    @Volatile private var inboxBody = """{"ok":true,"items":[],"unread":0}"""
    @Volatile private var prefsGet: MockResponse = MockResponse().setBody("""{"ok":true,"enabled":true}""")

    private lateinit var store: PushPrefsStore
    private lateinit var inbox: InboxCache
    private val presenter = FakePresenter()
    private val platform = FakePlatform()
    private lateinit var repo: PushRepository

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += path to request.body.readUtf8()
                return when {
                    path.startsWith("/api/v1/client/push/ack") -> MockResponse().setResponseCode(ackStatus).setBody("""{"ok":${ackStatus == 200}}""")
                    path.startsWith("/api/v1/client/push/inbox") -> MockResponse().setBody(inboxBody)
                    path.startsWith("/api/v1/client/push/prefs") && request.method == "GET" -> prefsGet
                    path.startsWith("/api/v1/client/push/prefs") -> MockResponse().setBody("""{"ok":true}""")
                    path.startsWith("/api/v1/client/push/test") -> MockResponse().setBody("""{"ok":true,"seq":130}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        store = testStore(dir)
        inbox = InboxCache(File(dir, "inbox.json"))
        repo = PushRepository(TestEndpoint(server.url("/").toString()), store, inbox, presenter, platform)
        runBlocking { store.setEnabled(true) }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun acks(): List<Pair<List<Long>, String>> = requests.filter { it.first.startsWith("/api/v1/client/push/ack") }.map { (_, body) ->
        val o = JsonParser.parseString(body).asJsonObject
        o.getAsJsonArray("seqs").map { it.asLong } to (o.get("state").asString + (o.get("action")?.let { ":" + it.asString } ?: ""))
    }

    @Test
    fun `the message hook sees a message once, also when its topic is muted, never a replay`() = runBlocking {
        store.setTopicMuted("devices", true)
        repo.onNotification(message(id = 46, seq = 200))
        repo.onNotification(message(id = 46, seq = 200))
        repo.onNotification(message(id = 47, seq = 201, state = "read"))

        assertEquals(listOf(46L), presenter.fresh.map { it.id })
        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `a notification is shown, stored, remembered and acknowledged as delivered`() = runBlocking {
        repo.onNotification(message(id = 45, seq = 123, priority = "critical"))

        assertEquals(1, presenter.shown.size)
        assertEquals(PushChannels.CRITICAL, presenter.shown[0].channel.id)
        assertEquals(45L, inbox.items.value.single().id)
        assertEquals(123L, store.lastSeq())
        assertEquals(listOf(listOf(123L) to "delivered"), acks())
        assertTrue(store.pendingAcks().isEmpty())
    }

    @Test
    fun `failed acks stay pending and are sent later`() = runBlocking {
        ackStatus = 503
        repo.onNotification(message(id = 1, seq = 10))
        repo.onNotification(message(id = 2, seq = 11))
        assertEquals(listOf(10L, 11L), store.pendingAcks().map { it.seq })

        ackStatus = 200
        assertTrue(repo.flushAcks())
        assertTrue(store.pendingAcks().isEmpty())
        assertEquals(listOf(10L, 11L) to "delivered", acks().last())
    }

    @Test
    fun `same collapse key replaces the shown notification`() = runBlocking {
        repo.onNotification(message(id = 1, seq = 10, collapseKey = "waf:ban"))
        repo.onNotification(message(id = 2, seq = 11, collapseKey = "waf:ban"))
        assertEquals(2, presenter.shown.size)
        assertEquals(presenter.shown[0].id, presenter.shown[1].id)
    }

    @Test
    fun `a bundled notification under a new seq replaces its inbox entry`() = runBlocking {
        repo.onNotification(message(id = 7, seq = 10, collapseKey = "waf:ban"))
        repo.onNotification(message(id = 7, seq = 14, collapseKey = "waf:ban").copy(title = "3× IP gesperrt"))
        val items = inbox.items.value
        assertEquals(1, items.size)
        assertEquals(14L, items[0].seq)
        assertEquals("3× IP gesperrt", items[0].title)
    }

    @Test
    fun `read elsewhere cancels the notification and marks it read`() = runBlocking {
        repo.onNotification(message(id = 45, seq = 123))
        repo.onRead(listOf(45))
        assertEquals(listOf(PushChannels.notificationId(45, null)), presenter.cancelled)
        assertFalse(inbox.get(45)!!.isUnread)
    }

    @Test
    fun `revoke removes it from inbox and shade`() = runBlocking {
        repo.onNotification(message(id = 45, seq = 123, collapseKey = "gateway:3"))
        repo.onRevoke(listOf(45))
        assertTrue(inbox.items.value.isEmpty())
        assertEquals(listOf(PushChannels.notificationId(45, "gateway:3")), presenter.cancelled)
    }

    @Test
    fun `revoking an older message keeps the newer one of the same collapse key in the shade`() = runBlocking {
        repo.onNotification(message(id = 1, seq = 10, collapseKey = "gateway:3"))
        repo.onNotification(message(id = 2, seq = 11, collapseKey = "gateway:3"))
        repo.onRevoke(listOf(1))
        assertTrue(presenter.cancelled.isEmpty())
        repo.onRevoke(listOf(2))
        assertEquals(1, presenter.cancelled.size)
    }

    @Test
    fun `muted topics land in the inbox but not in the shade, critical and admin notices go through`() = runBlocking {
        store.setTopicMuted("services", true)
        store.setTopicMuted("devices", true)
        repo.onNotification(message(id = 1, seq = 1, topic = "services"))
        repo.onNotification(message(id = 2, seq = 2, topic = "devices", priority = "critical"))
        repo.onNotification(message(id = 3, seq = 3, topic = "admin_notice"))
        assertEquals(listOf(2L, 3L), presenter.shown.map { it.message.id })
        assertEquals(3, inbox.items.value.size)
    }

    @Test
    fun `mute for an hour posts the topic quietly and acks read with the action`() = runBlocking {
        repo.onNotification(message(id = 1, seq = 1, topic = "devices"))
        repo.runAction(1, PushAction("mute_1h", "1 h stumm", PushActionType.MUTE_1H))
        repo.onNotification(message(id = 2, seq = 2, topic = "devices"))
        repo.onNotification(message(id = 3, seq = 3, topic = "services"))
        assertTrue(presenter.shown.first { it.message.id == 2L }.quiet)
        assertFalse(presenter.shown.first { it.message.id == 3L }.quiet)
        assertTrue(acks().contains(listOf(1L) to "read:mute_1h"))
    }

    @Test
    fun `mark read and done send read acks with the action`() = runBlocking {
        repo.onNotification(message(id = 1, seq = 5))
        repo.onNotification(message(id = 2, seq = 6))
        repo.runAction(2, PushAction("done", "Erledigt", PushActionType.DONE))
        repo.markAllRead()
        assertEquals(listOf(6L) to "read:done", acks()[2])
        assertEquals(listOf(5L) to "read", acks()[3])
        assertTrue(inbox.items.value.none { it.isUnread })
    }

    @Test
    fun `a read message replayed after reconnect is not shown again`() = runBlocking {
        ackStatus = 503
        repo.onNotification(message(id = 1, seq = 5))
        repo.markRead(listOf(1))
        repo.onNotification(message(id = 1, seq = 5))
        assertEquals(1, presenter.shown.size)
        assertEquals(PushState.READ, inbox.get(1)!!.state)
    }

    @Test
    fun `inbox refresh replaces the cache and clears notifications read elsewhere`() = runBlocking {
        repo.onNotification(message(id = 1, seq = 5))
        repo.onNotification(message(id = 2, seq = 6))
        inboxBody = """{"ok":true,"unread":1,"items":[
            {"seq":6,"id":2,"topic":"devices","priority":"normal","title":"Title 2","state":"delivered"},
            {"seq":5,"id":1,"topic":"devices","priority":"normal","title":"Title 1","state":"read"}]}"""
        assertTrue(repo.refreshInbox())
        assertEquals(listOf(PushChannels.notificationId(1, null)), presenter.cancelled)
        assertEquals(listOf(true, false), inbox.items.value.map { it.isUnread })
    }

    @Test
    fun `prefs are sent once per change, only after push was switched on`() = runBlocking {
        platform.restricted = true
        assertTrue(repo.syncPrefs())
        assertTrue(repo.syncPrefs())
        val prefs = requests.filter { it.first.startsWith("/api/v1/client/push/prefs") }
        assertEquals(1, prefs.size)
        val body = JsonParser.parseString(prefs[0].second).asJsonObject
        assertTrue(body.get("enabled").asBoolean)
        assertEquals("always", body.get("mode").asString)
        assertTrue(body.get("restricted").asBoolean)

        platform.restricted = false
        repo.syncPrefs()
        assertEquals(2, requests.count { it.first.startsWith("/api/v1/client/push/prefs") })
    }

    @Test
    fun `nothing is reported for a device that never enabled push`() = runBlocking {
        val fresh = testStore(File(dir, "fresh").apply { mkdirs() })
        val r = PushRepository(TestEndpoint(server.url("/").toString()), fresh, InboxCache(File(dir, "i2.json")), presenter, platform)
        assertTrue(r.syncPrefs())
        assertTrue(requests.none { it.first.startsWith("/api/v1/client/push/prefs") })
    }

    @Test
    fun `test message returns its seq`() = runBlocking {
        assertEquals(130L, repo.requestTest().getOrNull())
    }

    @Test
    fun `quiet hours come from GET push prefs and older servers change nothing`() = runBlocking {
        prefsGet = MockResponse().setBody(
            """{"ok":true,"enabled":true,"mode":"always","muted_topics":[],"restricted":false,
               "quiet":{"from":"22:00","to":"07:00","tz":"Europe/Berlin","critical_bypass":false}}""",
        )
        assertTrue(repo.refreshQuiet())
        val known = store.quiet.first()
        assertEquals("07:00", known.hours?.to)
        assertFalse(known.hours!!.criticalBypass)
        assertTrue(requests.any { it.first == "/api/v1/client/push/prefs" })

        // Server without quiet hours (field missing) or without the route: what is known stays.
        prefsGet = MockResponse().setBody("""{"ok":true,"enabled":true}""")
        assertFalse(repo.refreshQuiet())
        prefsGet = MockResponse().setResponseCode(404)
        assertFalse(repo.refreshQuiet())
        assertEquals(known, store.quiet.first())

        // Removed in the portal: none.
        prefsGet = MockResponse().setBody("""{"ok":true,"quiet":null}""")
        assertTrue(repo.refreshQuiet())
        assertEquals(com.gatecontrol.android.notify.model.QuietInfo.NONE, store.quiet.first())
    }
}
