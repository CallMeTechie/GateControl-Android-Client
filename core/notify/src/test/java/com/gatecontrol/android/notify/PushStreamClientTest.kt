package com.gatecontrol.android.notify

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class PushStreamClientTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setHeader("Cache-Control", "no-cache")
        .setBody(body)

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun connect(
        lastEventId: Long? = null,
        stream: PushStreamClient = PushStreamClient(watchdogTickMs = 50),
    ): Pair<StreamOutcome, List<PushEvent>> = runBlocking {
        val events = mutableListOf<PushEvent>()
        val outcome = withTimeout(10_000) {
            stream.connect(client, server.url("/"), lastEventId, onEvent = { events += it })
        }
        outcome to events
    }

    @Test
    fun `every attempt opens a fresh connection instead of reusing a pooled one`() {
        val hello = "event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\"}\n\n"
        server.enqueue(sse(hello))
        server.enqueue(sse(hello))
        connect()
        connect()
        assertEquals(0, server.takeRequest().sequenceNumber)
        // 0 again: not the second request on the first (possibly dead) connection.
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    @Test
    fun `events arrive in order and keepalive comments are skipped`() {
        server.enqueue(
            sse(
                "event: hello\ndata: {\"keepalive_s\":25,\"via\":\"direct\",\"unread\":1,\"topics\":[{\"id\":\"devices\",\"label\":\"Geräte\"}]}\n\n" +
                    ": ping\n\n" +
                    "id: 123\nevent: notification\ndata: {\"seq\":123,\"id\":45,\"topic\":\"devices\",\"priority\":\"high\",\"title\":\"Gateway offline\"}\n\n" +
                    "event: read\ndata: {\"ids\":[44]}\n\n" +
                    "event: revoke\ndata: {\"ids\":[45]}\n\n",
            ),
        )
        val (outcome, events) = connect()
        val ended = assertInstanceOf(StreamOutcome.Ended::class.java, outcome)
        assertTrue(ended.sawHello)
        assertEquals(4, events.size)
        assertInstanceOf(PushEvent.Hello::class.java, events[0])
        assertEquals(123L, (events[1] as PushEvent.Notification).message.seq)
        assertEquals(listOf(44L), (events[2] as PushEvent.Read).ids)
        assertEquals(listOf(45L), (events[3] as PushEvent.Revoke).ids)

        val req = server.takeRequest()
        assertEquals("/api/v1/client/push", req.path)
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertNull(req.getHeader("Last-Event-ID"))
    }

    @Test
    fun `resume sends Last-Event-ID`() {
        server.enqueue(sse("event: hello\ndata: {}\n\n"))
        connect(lastEventId = 130)
        assertEquals("130", server.takeRequest().getHeader("Last-Event-ID"))
    }

    @Test
    fun `contract error codes are decoded`() {
        server.enqueue(json(503, """{"ok":false,"error":"push_disabled"}"""))
        assertEquals(StreamOutcome.HttpError(503, "push_disabled"), connect().first)

        server.enqueue(json(429, """{"ok":false,"error":"rate_limited"}""").setHeader("Retry-After", "900"))
        assertEquals(StreamOutcome.HttpError(429, "rate_limited", retryAfterS = 900), connect().first)

        server.enqueue(json(403, """{"ok":false,"error":"direct_not_allowed"}"""))
        assertEquals(StreamOutcome.HttpError(403, "direct_not_allowed"), connect().first)

        server.enqueue(MockResponse().setResponseCode(404).setBody("<html>Not found</html>"))
        assertEquals(StreamOutcome.HttpError(404, null), connect().first)

        server.enqueue(json(403, """{"ok":false,"error":"Token is bound to a different machine"}"""))
        val binding = assertInstanceOf(StreamOutcome.HttpError::class.java, connect().first)
        assertTrue(binding.bindingRejected)
    }

    @Test
    fun `a page that is not an event stream is no stream`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>Login</html>"))
        assertEquals(StreamOutcome.NotAStream, connect().first)
    }

    @Test
    fun `a silent connection is detected as stale`() {
        // Headers, then one byte every 5 s: alive on the socket, silent for the watchdog.
        server.enqueue(sse(": ping\n\n".repeat(10)).throttleBody(1, 5, TimeUnit.SECONDS))
        val (outcome, _) = connect(stream = PushStreamClient(watchdogTickMs = 50, initialStaleMs = 400))
        assertEquals(StreamOutcome.Stale, outcome)
    }

    @Test
    fun `keepalive comments keep a quiet stream alive`() {
        // 30 comments, one every ~100 ms: far longer than the 400 ms allowed between bytes.
        server.enqueue(sse(": ping\n\n".repeat(30)).throttleBody(8, 100, TimeUnit.MILLISECONDS))
        val (outcome, events) = connect(stream = PushStreamClient(watchdogTickMs = 50, initialStaleMs = 400))
        assertInstanceOf(StreamOutcome.Ended::class.java, outcome)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `no server is a network error`() {
        val port = server.port
        server.shutdown()
        val stream = PushStreamClient(watchdogTickMs = 50)
        val outcome = runBlocking {
            stream.connect(client, okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port).build(), null, onEvent = {})
        }
        assertInstanceOf(StreamOutcome.NetworkError::class.java, outcome)
        server = MockWebServer().also { it.start() }
    }
}
