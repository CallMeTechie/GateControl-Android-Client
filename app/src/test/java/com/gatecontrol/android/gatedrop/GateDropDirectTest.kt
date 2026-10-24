package com.gatecontrol.android.gatedrop

import android.content.Context
import android.net.ConnectivityManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The phone's side of the direct LAN transfer against a stand-in for the
 * Windows listener (client-core gatedrop-listener.js: Node http, JSON
 * errors with Content-Length and `Connection: close`).
 *
 * Root cause of "PC → Android always takes a minute and then the server
 * path": the LAN requests went through OkHttp, which on Android refuses
 * plain HTTP under the app's network security policy (targetSdk 36, no
 * network_security_config) — `UnknownServiceException: CLEARTEXT
 * communication to 192.168.2.171 not permitted`, before any packet. The
 * JVM build of OkHttp does not check the policy, so unit tests never saw
 * it. The transfer now uses plain sockets ([GateDropHttp]).
 */
class GateDropDirectTest {

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val ticket = "tkt_" + "A".repeat(20)
    private val transferId = "0123456789abcdef01234567"

    @TempDir
    lateinit var dir: File

    private val servers = CopyOnWriteArrayList<ServerSocket>()

    @AfterEach
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    private fun direct(): GateDropDirect {
        val context = mockk<Context>()
        every { context.getSystemService(ConnectivityManager::class.java) } returns null
        return GateDropDirect(context)
    }

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val input: InputStream)

    /** A one-request-per-connection HTTP server on 127.0.0.1 (like the PC's listener). */
    private fun pcListener(handle: (Req, OutputStream) -> Unit): Int {
        val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s: Socket = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val input = BufferedInputStream(it.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) return@thread
                            head.append(b.toChar())
                        }
                        val lines = head.split("\r\n").filter { l -> l.isNotEmpty() }
                        val (method, path) = lines[0].split(' ')
                        val headers = lines.drop(1).associate { l -> l.substringBefore(':').trim().lowercase() to l.substringAfter(':').trim() }
                        runCatching { handle(Req(method, path, headers, input), it.getOutputStream()) }
                        it.getOutputStream().flush()
                    }
                }
            }
        }
        return ss.localPort
    }

    private fun json(out: OutputStream, status: Int, body: String) {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
    }

    private fun offer(port: Int, items: List<GdOfferItem>) =
        GdTransferOffer(transferId, GdMode.PULL, "Büro-PC", items, listOf(GdCandidate("127.0.0.1", port)), key, ticket)

    @Test
    fun `pull fetches every item from the PC over a plain socket and decrypts it`() = runBlocking {
        val files = listOf(Random.nextBytes(1_500_000), Random.nextBytes(10))
        val port = pcListener { req, out ->
            val index = req.path.substringAfterLast('/').toInt()
            if (req.method != "GET" || req.headers["x-gd-ticket"] != ticket || !req.path.startsWith("${GateDropDirect.PATH}$transferId/")) return@pcListener json(out, 403, """{"error":"forbidden"}""")
            val plain = files[index]
            val sealed = GateDropCrypto.encryptingStream(ByteArrayInputStream(plain), key, transferId, index, plain.size.toLong()).readBytes()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${sealed.size}\r\nX-GD-Size: ${plain.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(sealed)
        }
        val got = HashMap<Int, ByteArrayOutputStream>()
        val r = direct().pull(offer(port, files.mapIndexed { i, f -> GdOfferItem("f$i.bin", f.size.toLong(), "application/octet-stream") }), { i, _ -> ByteArrayOutputStream().also { got[i] = it } })
        assertEquals(GdDirectOutcome.Done(files.sumOf { it.size.toLong() }), r)
        files.forEachIndexed { i, f -> assertArrayEquals(f, got[i]!!.toByteArray()) }
    }

    @Test
    fun `pull - a refusal of the PC is reported with its status`() = runBlocking {
        val port = pcListener { _, out -> json(out, 403, """{"error":"forbidden"}""") }
        val r = direct().pull(offer(port, listOf(GdOfferItem("a", 3, "text/plain"))), { _, _ -> ByteArrayOutputStream() })
        assertEquals(GdDirectOutcome.Failed("http_403"), r)
    }

    @Test
    fun `pull - a changed block fails the transfer (auth_failed)`() = runBlocking {
        val port = pcListener { _, out ->
            val sealed = GateDropCrypto.encryptingStream(ByteArrayInputStream(ByteArray(5)), key, transferId, 0, 5).readBytes()
            sealed[2] = (sealed[2] + 1).toByte()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${sealed.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(sealed)
        }
        val r = direct().pull(offer(port, listOf(GdOfferItem("a", 5, "text/plain"))), { _, _ -> ByteArrayOutputStream() })
        assertEquals(GdDirectOutcome.Failed("auth_failed"), r)
    }

    @Test
    fun `pull - nobody listens at the address - fails fast with the connect error`() = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        val t0 = System.currentTimeMillis()
        val r = direct().pull(offer(free, listOf(GdOfferItem("a", 3, "text/plain"))), { _, _ -> ByteArrayOutputStream() })
        assertEquals(GdDirectOutcome.Failed("refused"), r)
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test
    fun `push posts every file to the PC, encrypted, with the protocol headers`() = runBlocking {
        val plain = Random.nextBytes(2_100_000)
        val f = File(dir, "video.mp4").apply { writeBytes(plain) }
        val received = ByteArrayOutputStream()
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        val port = pcListener { req, out ->
            seen += req.headers
            val len = req.headers["content-length"]!!.toLong()
            val body = ByteArray(len.toInt()).also { b -> var o = 0; while (o < b.size) { o += req.input.read(b, o, b.size - o) } }
            GateDropCrypto.decryptStream(ByteArrayInputStream(body), received, key, transferId, req.headers["x-gd-item"]!!.toInt(), req.headers["x-gd-size"]!!.toLong())
            json(out, 200, """{"ok":true,"bytes":${plain.size}}""")
        }
        val d = GdDirect("tok_pc", transferId, GdMode.PUSH, listOf(GdCandidate("127.0.0.1", port)), key, ticket)
        val r = direct().push(d, listOf(GdLocalFile(f, "Ürlaub.mp4", plain.size.toLong())))
        assertEquals(GdDirectOutcome.Done(plain.size.toLong()), r)
        assertArrayEquals(plain, received.toByteArray())
        val h = seen.single()
        assertEquals(ticket, h["x-gd-ticket"])
        assertEquals("0", h["x-gd-item"])
        assertEquals("Ürlaub.mp4", String(Base64.getUrlDecoder().decode(h["x-gd-name"]), Charsets.UTF_8))
        assertEquals(GateDropCrypto.encryptedLength(plain.size.toLong()).toString(), h["content-length"])
        assertEquals("close", h["connection"])
    }

    @Test
    fun `push - the PC refuses (wrong ticket) - http_403, no second candidate`() = runBlocking {
        val f = File(dir, "a.txt").apply { writeText("hallo") }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val port = pcListener { req, out ->
            calls.incrementAndGet()
            req.input.skip(req.headers["content-length"]!!.toLong())
            json(out, 403, """{"error":"forbidden"}""")
        }
        val d = GdDirect("tok_pc", transferId, GdMode.PUSH, listOf(GdCandidate("127.0.0.1", port), GdCandidate("127.0.0.1", port)), key, ticket)
        assertEquals(GdDirectOutcome.Failed("http_403"), direct().push(d, listOf(GdLocalFile(f, "a.txt", 5))))
        assertEquals(1, calls.get())
    }

    @Test
    fun `no candidate`() = runBlocking {
        val d = GdDirect("tok_pc", transferId, GdMode.PUSH, emptyList(), key, ticket)
        assertEquals(GdDirectOutcome.Failed("no_candidate"), direct().push(d, emptyList()))
    }

    @Test
    fun `the LAN transfer does not go through OkHttp (cleartext policy)`() {
        // OkHttp on Android refuses http:// to LAN addresses without a network_security_config.
        val src = listOf(File("src/main/java/com/gatecontrol/android/gatedrop/GateDropDirect.kt"), File("app/src/main/java/com/gatecontrol/android/gatedrop/GateDropDirect.kt"))
            .first { it.isFile }.readText()
        assertFalse(src.contains("import okhttp3"), "GateDropDirect must use GateDropHttp (plain sockets), not OkHttp")
    }

    @Test
    fun `connect errors map to short codes for the plugin`() {
        assertEquals("connect_timeout", GateDropHttp.connectCode(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("refused", GateDropHttp.connectCode(java.net.ConnectException("Connection refused")))
        assertEquals("unreachable", GateDropHttp.connectCode(java.net.NoRouteToHostException("No route to host")))
        assertEquals("not_permitted", GateDropHttp.connectCode(java.net.SocketException("connect failed: EPERM (Operation not permitted)")))
    }
}
