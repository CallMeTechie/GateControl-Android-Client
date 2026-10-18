package com.gatecontrol.android.notify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PushClientTest {

    @TempDir lateinit var dir: File

    private lateinit var server: MockWebServer
    private val streams = ConcurrentLinkedQueue<MockResponse>()
    private val streamRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val ackBodies = CopyOnWriteArrayList<String>()

    private lateinit var endpoint: TestEndpoint
    private lateinit var store: PushPrefsStore
    private val presenter = FakePresenter()
    private val platform = FakePlatform()
    private val control = FakeControlSink()
    private val pluginEvents = CopyOnWriteArrayList<String>()
    @Volatile private var streamOpens = 0
    private val pluginSink = object : PluginEventSink {
        override fun onPluginEvent(plugin: String, type: String, data: com.google.gson.JsonElement?) {
            pluginEvents += "$plugin/$type/${data?.asJsonObject?.get("conversationId")}"
        }

        override fun onStreamOpen() {
            streamOpens++
        }
    }
    private lateinit var client: PushClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    /** A stream that stays open (nothing more to say) until the test ends. */
    private fun openSse(body: String) = sse(body + ": ping\n\n".repeat(500)).throttleBody(9, 200, TimeUnit.MILLISECONDS)

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/api/v1/client/push" -> {
                        streamRequests += request
                        streams.poll() ?: MockResponse().setResponseCode(503).setBody("""{"ok":false,"error":"push_disabled"}""")
                    }
                    path.startsWith("/api/v1/client/push/ack") -> {
                        ackBodies += request.body.readUtf8()
                        MockResponse().setBody("""{"ok":true}""")
                    }
                    path.startsWith("/api/v1/client/push/inbox") -> MockResponse().setBody("""{"ok":true,"items":[],"unread":0}""")
                    else -> MockResponse().setBody("""{"ok":true}""")
                }
            }
        }
        server.start()
        endpoint = TestEndpoint(server.url("/").toString())
        store = testStore(dir)
        runBlocking { store.setEnabled(true) }
        val repo = PushRepository(endpoint, store, InboxCache(File(dir, "inbox.json")), presenter, platform)
        client = PushClient(endpoint, store, repo, platform, control, pluginSink).apply {
            stream = PushStreamClient(watchdogTickMs = 50)
            policy = PushRetryPolicy(Random(1))
        }
    }

    @AfterEach
    fun tearDown() {
        client.stop()
        scope.cancel()
        server.shutdown()
    }

    private fun awaitStatus(timeoutMs: Long = 10_000, predicate: (PushStatus) -> Boolean): PushStatus = runBlocking {
        withTimeout(timeoutMs) { client.status.first(predicate) }
    }

    @Test
    fun `stream resumes with the last processed seq after a reconnect`() {
        streams += sse(
            "event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\"}\n\n" +
                "id: 5\nevent: notification\ndata: {\"seq\":5,\"id\":1,\"topic\":\"devices\",\"title\":\"A\"}\n\n" +
                "id: 6\nevent: notification\ndata: {\"seq\":6,\"id\":2,\"topic\":\"devices\",\"title\":\"B\"}\n\n",
        )
        streams += openSse("event: hello\ndata: {\"keepalive_s\":25,\"via\":\"tunnel\"}\n\n")

        client.start(scope, "test")
        val connected = awaitStatus { it is PushStatus.Connected && it.via == "tunnel" }
        assertInstanceOf(PushStatus.Connected::class.java, connected)

        assertEquals(2, streamRequests.size)
        assertNull(streamRequests[0].getHeader("Last-Event-ID"))
        assertEquals("6", streamRequests[1].getHeader("Last-Event-ID"))
        assertEquals(listOf(1L, 2L), presenter.shown.map { it.message.id })
        assertEquals(6L, runBlocking { store.lastSeq() })
        runBlocking { withTimeout(5_000) { while (ackBodies.size < 2) kotlinx.coroutines.delay(20) } }
        assertTrue(ackBodies.any { it.contains("\"seqs\":[5]") && it.contains("delivered") })
        assertTrue(ackBodies.any { it.contains("\"seqs\":[6]") })
    }

    @Test
    fun `plugin events reach the plugin sink, which also learns when the stream opened`() {
        streams += openSse(
            "event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\"}\n\n" +
                "event: plugin\ndata: {\"plugin\":\"gatecontrol-messenger\",\"type\":\"typing\",\"data\":{\"conversationId\":12,\"userId\":7}}\n\n" +
                "event: plugin\ndata: {\"plugin\":\"BAD\",\"type\":\"x\"}\n\n",
        )
        client.start(scope, "test")
        awaitStatus { it is PushStatus.Connected }
        runBlocking { withTimeout(5_000) { while (pluginEvents.isEmpty()) kotlinx.coroutines.delay(20) } }
        assertEquals(listOf("gatecontrol-messenger/typing/12"), pluginEvents.toList())
        assertEquals(1, streamOpens)
        // Not a notification: nothing shown, nothing acked for it.
        assertTrue(presenter.shown.isEmpty())
    }

    @Test
    fun `push disabled on the server waits an hour`() {
        client.start(scope, "test")
        val waiting = awaitStatus { it is PushStatus.Waiting } as PushStatus.Waiting
        assertEquals(WaitReason.PUSH_DISABLED, waiting.reason)
        assertTrue(waiting.retryAt - System.currentTimeMillis() > 50 * 60_000L)
        assertEquals(1, streamRequests.size)
    }

    @Test
    fun `401 blocks until the setup changes`() {
        streams += MockResponse().setResponseCode(401).setBody("""{"error":"Invalid API token"}""")
        streams += openSse("event: hello\ndata: {}\n\n")
        client.start(scope, "test")
        assertEquals(PushStatus.Blocked(BlockReason.AUTH), awaitStatus { it is PushStatus.Blocked })

        // A network change alone does not retry with the rejected token.
        client.kick("default_network")
        Thread.sleep(300)
        assertEquals(1, streamRequests.size)

        endpoint.token = "gc_new"
        client.kick("default_network")
        awaitStatus { it is PushStatus.Connected }
        assertEquals(2, streamRequests.size)
    }

    @Test
    fun `a network change reconnects an open stream right away`() {
        streams += openSse("event: hello\ndata: {}\n\n")
        streams += openSse("event: hello\ndata: {\"via\":\"tunnel\"}\n\n")
        client.start(scope, "test")
        awaitStatus { it is PushStatus.Connected }

        // A network that merely appeared changes nothing.
        platform.onChange!!("network_available")
        Thread.sleep(300)
        assertEquals(1, streamRequests.size)

        platform.onChange!!("vpn_up")
        awaitStatus { it is PushStatus.Connected && it.via == "tunnel" }
        assertEquals(2, streamRequests.size)
    }

    @Test
    fun `stop by another owner is ignored`() {
        streams += openSse("event: hello\ndata: {}\n\n")
        client.start(scope, "service")
        awaitStatus { it is PushStatus.Connected }
        client.stop(owner = "tunnel")
        assertTrue(client.isRunning)
        client.stop(owner = "service")
        assertEquals(PushStatus.Off, client.status.value)
    }

    @Test
    fun `control events reach the sink and hello state marks the stream as control-live`() {
        streams += openSse(
            "event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\",\"quiet\":null," +
                "\"state\":{\"policy_version\":\"00000000000000a7\",\"peer_enabled\":true,\"support_bundle_requested\":false}}\n\n" +
                "event: control\ndata: {\"type\":\"policy_changed\",\"policy_version\":\"00000000000000a8\"}\n\n" +
                "event: control\ndata: {\"type\":\"config_changed\"}\n\n",
        )
        client.start(scope, "test")
        val connected = awaitStatus { it is PushStatus.Connected } as PushStatus.Connected
        assertTrue(connected.serverControl)
        runBlocking { withTimeout(5_000) { client.controlLive.first { it } } }
        runBlocking { withTimeout(5_000) { while (control.controls.size < 2) kotlinx.coroutines.delay(20) } }
        assertEquals(listOf(com.gatecontrol.android.notify.model.ServerState("00000000000000a7", true, false, null, null)), control.states.toList())
        assertEquals(
            listOf(
                com.gatecontrol.android.notify.model.ServerControl.PolicyChanged("00000000000000a8"),
                com.gatecontrol.android.notify.model.ServerControl.ConfigChanged,
            ),
            control.controls.toList(),
        )
        // Control events carry no id: nothing to acknowledge, the resume position stays.
        assertNull(runBlocking { store.lastSeq() })
        assertEquals(com.gatecontrol.android.notify.model.QuietInfo.NONE, runBlocking { store.quiet.first() })

        client.stop()
        runBlocking { withTimeout(5_000) { client.controlLive.first { !it } } }
    }

    @Test
    fun `an older server without hello state is not control-live`() {
        streams += openSse("event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\"}\n\n")
        client.start(scope, "test")
        val connected = awaitStatus { it is PushStatus.Connected } as PushStatus.Connected
        assertFalse(connected.serverControl)
        assertFalse(client.controlLive.value)
        assertTrue(control.states.isEmpty())
        assertEquals(com.gatecontrol.android.notify.model.QuietInfo.UNKNOWN, runBlocking { store.quiet.first() })
    }
}
