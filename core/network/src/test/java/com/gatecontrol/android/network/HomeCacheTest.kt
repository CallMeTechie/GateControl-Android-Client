package com.gatecontrol.android.network

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

// runBlocking, not runTest: the TLS connection runs on real time.
class HomeCacheTest {

    companion object {
        private const val PASS = "home-cache-test"
        private lateinit var serverTls: SSLSocketFactory
        private lateinit var cert: X509Certificate
        private lateinit var otherCert: X509Certificate

        /** A self-signed certificate without a name, as the gateway has (made with the JDK's keytool, nothing kept). */
        private fun keyStore(dir: File, alias: String): KeyStore {
            val file = File(dir, "$alias.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=gatecontrol-home-cache", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASS, "-keypass", PASS,
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "keytool failed" }
            return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASS.toCharArray()) } }
        }

        @JvmStatic
        @BeforeAll
        fun certificates(@TempDir dir: File) {
            val ks = keyStore(dir, "gw")
            cert = ks.getCertificate("gw") as X509Certificate
            otherCert = keyStore(dir, "other").getCertificate("other") as X509Certificate
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASS.toCharArray()) }
            serverTls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.socketFactory
        }

        private fun pinOf(c: X509Certificate) = HomeCache.sha256Hex(c.encoded)
    }

    private lateinit var server: MockWebServer
    private var now = 1_000_000L

    @TempDir
    lateinit var tmp: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.useHttps(serverTls, false)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private val content = "Urlaubsfotos 2026".toByteArray()
    private val contentSha = HomeCache.sha256Hex(content)

    private fun hint(sha: String = contentSha, pin: String = pinOf(cert), exp: Long = now + 600) = LanHint(
        url = server.url("/blob/$sha?exp=$exp&sig=00ff").toString(),
        host = server.hostName,
        port = server.port,
        sha256 = sha,
        tlsSha256 = pin,
        exp = exp,
    )

    private fun fetcher() = HomeCacheFetcher(nowSeconds = { now }, connectTimeoutMs = 2_000, readTimeoutMs = 5_000)

    private fun ok(body: ByteArray = content) = MockResponse().setResponseCode(200).setBody(Buffer().write(body))

    // ─── loading ────────────────────────────────────────────────────

    @Test
    fun `pin matches and the bytes are right - the file is there`() = runBlocking {
        server.enqueue(ok())
        val target = File(tmp, "out.bin")
        val r = fetcher().fetch(hint(), target, expectedSha256 = contentSha, expectedSize = content.size.toLong())
        assertTrue(r is HomeCacheFetcher.Result.Ok, "$r")
        assertArrayEquals(content, target.readBytes())
        assertFalse(File(tmp, "out.bin.part").exists())
        val req = server.takeRequest()
        assertEquals("/blob/$contentSha?exp=${now + 600}&sig=00ff", req.path)
        // none of the app's credentials ever go to the LAN address
        assertNull(req.getHeader("Authorization"))
        assertNull(req.getHeader("X-API-Token"))
        assertNull(req.getHeader(HomeCache.HEADER_LOCAL))
    }

    @Test
    fun `a pin given with colons and in upper case works too`() = runBlocking {
        server.enqueue(ok())
        val pin = pinOf(cert).uppercase().chunked(2).joinToString(":")
        val r = fetcher().fetch(hint(pin = HomeCache.normalizeSha256(pin)!!), File(tmp, "a"))
        assertTrue(r is HomeCacheFetcher.Result.Ok, "$r")
    }

    @Test
    fun `wrong pin - nothing loaded, fall back`() = runBlocking {
        server.enqueue(ok())
        val target = File(tmp, "out.bin")
        val r = fetcher().fetch(hint(pin = pinOf(otherCert)), target)
        assertEquals(HomeCacheFetcher.Result.Failed("tls"), r)
        assertFalse(target.exists())
        assertFalse(File(tmp, "out.bin.part").exists())
    }

    @Test
    fun `other bytes than the hash says - no file, fall back`() = runBlocking {
        server.enqueue(ok("manipuliert!!!!!!".toByteArray()))
        val target = File(tmp, "out.bin")
        val r = fetcher().fetch(hint(), target)
        assertEquals(HomeCacheFetcher.Result.Failed("sha256"), r)
        assertFalse(target.exists())
        assertFalse(File(tmp, "out.bin.part").exists())
    }

    @Test
    fun `a file that was there stays when the new load fails`() = runBlocking {
        val target = File(tmp, "out.bin").apply { writeText("vorher") }
        server.enqueue(ok("anders".toByteArray()))
        val r = fetcher().fetch(hint(), target)
        assertTrue(r is HomeCacheFetcher.Result.Failed)
        assertEquals("vorher", target.readText())
    }

    @Test
    fun `refused or missing on the gateway - fall back`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"expired"}"""))
        assertEquals(HomeCacheFetcher.Result.Failed("http_403"), fetcher().fetch(hint(), File(tmp, "a")))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_cached"}"""))
        assertEquals(HomeCacheFetcher.Result.Failed("http_404"), fetcher().fetch(hint(), File(tmp, "b")))
    }

    @Test
    fun `a redirect is not followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/x"))
        assertEquals(HomeCacheFetcher.Result.Failed("http_302"), fetcher().fetch(hint(), File(tmp, "a")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `expired hint - not even asked`() = runBlocking {
        val r = fetcher().fetch(hint(exp = now + 3), File(tmp, "a"))
        assertEquals(HomeCacheFetcher.Result.Failed("expired"), r)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a hint for other content than the file's - not used`() = runBlocking {
        val r = fetcher().fetch(hint(), File(tmp, "a"), expectedSha256 = "ab".repeat(32))
        assertEquals(HomeCacheFetcher.Result.Failed("sha256_differs"), r)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `more bytes than the file has - fall back`() = runBlocking {
        server.enqueue(ok())
        val r = fetcher().fetch(hint(), File(tmp, "a"), expectedSize = content.size - 1L)
        assertEquals(HomeCacheFetcher.Result.Failed("size"), r)
    }

    @Test
    fun `nobody listening - fall back`() = runBlocking {
        val h = hint()
        server.shutdown()
        val r = fetcher().fetch(h, File(tmp, "a"))
        assertTrue(r is HomeCacheFetcher.Result.Failed, "$r")
    }

    @Test
    fun `progress is reported`() = runBlocking {
        server.enqueue(ok())
        var last = 0L to 0L
        fetcher().fetch(hint(), File(tmp, "a")) { done, total -> last = done to total }
        assertEquals(content.size.toLong() to content.size.toLong(), last)
    }

    // ─── the pin itself ─────────────────────────────────────────────

    @Test
    fun `trust manager takes the pinned certificate only`() {
        val tm = PinnedCertTrustManager(pinOf(cert))
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        assertThrowsCert { tm.checkServerTrusted(arrayOf(otherCert), "ECDHE_ECDSA") }
        assertThrowsCert { tm.checkServerTrusted(arrayOf(), "ECDHE_ECDSA") }
        assertThrowsCert { tm.checkClientTrusted(arrayOf(cert), "ECDHE_ECDSA") }
        assertEquals(0, tm.acceptedIssuers.size)
    }

    private fun assertThrowsCert(block: () -> Unit) {
        try {
            block()
        } catch (_: CertificateException) {
            return
        }
        throw AssertionError("CertificateException expected")
    }

    // ─── the hint ───────────────────────────────────────────────────

    private val sha = "a".repeat(64)
    private val pin = "b".repeat(64)

    private fun parse(json: String) = HomeCache.parse(JsonParser.parseString(json))

    @Test
    fun `hint is read`() {
        val h = parse("""{"url":"https://192.168.1.23:8443/blob/$sha?exp=1700000600&sig=${"c".repeat(64)}","sha256":"$sha","tlsSha256":"${pin.uppercase()}","exp":1700000600}""")
        assertNotNull(h)
        assertEquals("192.168.1.23", h!!.host)
        assertEquals(8443, h.port)
        assertEquals(pin, h.tlsSha256)
        assertEquals(1_700_000_600L, h.exp)
        // logs never see the signature
        assertFalse(h.toString().contains("sig"))
        assertFalse(h.logLabel.contains("?"))
    }

    @Test
    fun `hint that is not right is not used`() {
        val good = mapOf(
            "url" to "\"https://10.0.0.5:8443/blob/$sha?exp=1&sig=00\"",
            "sha256" to "\"$sha\"",
            "tlsSha256" to "\"$pin\"",
            "exp" to "1700000600",
        )
        fun with(key: String, value: String?) = "{" + good.toMutableMap().apply { if (value == null) remove(key) else put(key, value) }
            .entries.joinToString(",") { "\"${it.key}\":${it.value}" } + "}"
        assertNotNull(parse(with("exp", "1700000600")))
        // plain http, a public address, a name, IPv6, a path for other content, user info
        assertNull(parse(with("url", "\"http://10.0.0.5:8443/blob/$sha?exp=1&sig=00\"")))
        assertNull(parse(with("url", "\"https://8.8.8.8:8443/blob/$sha?exp=1&sig=00\"")))
        assertNull(parse(with("url", "\"https://gateway.example.com:8443/blob/$sha\"")))
        assertNull(parse(with("url", "\"https://[fd00::1]:8443/blob/$sha\"")))
        assertNull(parse(with("url", "\"https://10.0.0.5:8443/blob/${"d".repeat(64)}\"")))
        assertNull(parse(with("url", "\"https://u:p@10.0.0.5:8443/blob/$sha\"")))
        assertNull(parse(with("url", null)))
        // hashes that are no SHA-256, no or a bad expiry
        assertNull(parse(with("sha256", "\"xyz\"")))
        assertNull(parse(with("tlsSha256", "\"${"b".repeat(63)}\"")))
        assertNull(parse(with("tlsSha256", null)))
        assertNull(parse(with("exp", null)))
        assertNull(parse(with("exp", "\"soon\"")))
        assertNull(parse(with("exp", "0")))
        assertNull(HomeCache.parse(null))
        assertNull(HomeCache.parse(JsonParser.parseString("null")))
        assertNull(HomeCache.parse(JsonParser.parseString("\"https://10.0.0.5/blob/$sha\"")))
    }

    @Test
    fun `expiry leaves a margin`() {
        val h = LanHint("https://10.0.0.5/blob/$sha", "10.0.0.5", 443, sha, pin, exp = 1_000L)
        assertTrue(HomeCache.usable(h, 900))
        assertTrue(HomeCache.usable(h, 1_000L - HomeCache.EXP_MARGIN_SECONDS - 1))
        assertFalse(HomeCache.usable(h, 1_000L - HomeCache.EXP_MARGIN_SECONDS))
        assertFalse(HomeCache.usable(h, 1_001))
    }

    // ─── the X-GC-Local header ──────────────────────────────────────

    @Test
    fun `header carries private IPv4 addresses only`() {
        assertEquals(
            "192.168.1.5,10.0.0.2,172.16.4.1",
            HomeCache.localHeader(listOf("192.168.1.5", "8.8.8.8", "10.0.0.2", "fe80::1", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.16.4.1", "172.32.0.1")),
        )
        assertNull(HomeCache.localHeader(listOf("8.8.8.8", "2001:db8::1")))
        assertNull(HomeCache.localHeader(emptyList()))
    }

    @Test
    fun `header leaves out the VPN's own addresses`() {
        val vpn: (String) -> Boolean = { HomeCache.inSubnet(it, "10.8.0.0", 24) }
        assertEquals("192.168.1.5", HomeCache.localHeader(listOf("10.8.0.7", "192.168.1.5"), vpn))
        assertNull(HomeCache.localHeader(listOf("10.8.0.7"), vpn))
    }

    @Test
    fun `header has at most four addresses`() {
        val many = (1..8).map { "192.168.1.$it" }
        assertEquals(4, HomeCache.localHeader(many)!!.split(',').size)
    }

    @Test
    fun `subnet check`() {
        assertTrue(HomeCache.inSubnet("192.168.1.23", "192.168.1.5", 24))
        assertFalse(HomeCache.inSubnet("192.168.2.23", "192.168.1.5", 24))
        assertTrue(HomeCache.inSubnet("10.1.2.3", "10.0.0.1", 8))
        assertTrue(HomeCache.inSubnet("1.2.3.4", "9.9.9.9", 0))
        assertFalse(HomeCache.inSubnet("192.168.1.23", "192.168.1.5", 33))
        assertFalse(HomeCache.inSubnet("fd00::1", "192.168.1.5", 24))
    }
}
