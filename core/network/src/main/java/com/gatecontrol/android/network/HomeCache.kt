package com.gatecontrol.android.network

import android.annotation.SuppressLint
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/**
 * The "Zwischenspeicher im Heimnetz" (home cache, GateControl ≥ 1.231.0): a home gateway keeps files and app
 * updates and hands them out on its LAN address. The server (or a plugin such as Drive) adds a `lan` hint to a
 * download when this device is at the gateway's site: `{ url, sha256, tlsSha256, exp }`. The app loads `url`
 * directly in the LAN, pins the gateway's self-signed certificate to `tlsSha256`, checks the bytes against
 * `sha256` and, on any error, quietly takes the normal download.
 *
 * [url] carries a signature in its query (`exp`, `sig`): it is never logged — [logLabel] is the loggable form.
 */
data class LanHint(
    val url: String,
    /** The gateway's LAN address in [url] (a private IPv4 literal when it came through [HomeCache.parse]). */
    val host: String,
    val port: Int,
    /** SHA-256 of the content, 64 hex, lower case. */
    val sha256: String,
    /** SHA-256 of the gateway certificate's DER, 64 hex, lower case. */
    val tlsSha256: String,
    /** Unix seconds after which the gateway refuses [url]. */
    val exp: Long,
) {
    /** For logs: where and what, never the signature. */
    val logLabel: String get() = "$host:$port/blob/${sha256.take(12)}"

    override fun toString(): String = "LanHint($logLabel, exp=$exp)"
}

object HomeCache {
    /** Header with this device's LAN IPv4 addresses (`a.b.c.d[,…]`): the server's site check for the `lan` hint. */
    const val HEADER_LOCAL = "X-GC-Local"

    /** A hint is used only while at least this much of its time is left (the request must reach the gateway in time). */
    const val EXP_MARGIN_SECONDS = 5L

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /** A SHA-256 in hex (colons and upper case allowed, as in certificate fingerprints) → 64 hex lower case; else null. */
    fun normalizeSha256(value: String?): String? =
        value?.trim()?.replace(":", "")?.lowercase()?.takeIf { HEX64.matches(it) }

    /**
     * The `lan` object of an answer → [LanHint], or null when it is missing or anything about it is off: [LanHint.url]
     * must be `https://<private IPv4>[:port]/blob/<sha256>` (no user info, no fragment), `sha256` and `tlsSha256`
     * SHA-256 hex, `exp` positive Unix seconds. A hint that is not right is simply not used.
     */
    fun parse(element: JsonElement?): LanHint? {
        val o = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun text(key: String): String? = o.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
        val sha256 = normalizeSha256(text("sha256")) ?: return null
        val pin = normalizeSha256(text("tlsSha256")) ?: return null
        val exp = o.get("exp")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it > 0 } ?: return null
        val raw = text("url")?.trim()?.takeIf { it.length <= 2048 } ?: return null
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (!isPrivateIpv4(url.host)) return null
        if (url.encodedPath != "/blob/$sha256") return null
        return LanHint(url = raw, host = url.host, port = url.port, sha256 = sha256, tlsSha256 = pin, exp = exp)
    }

    /** [hint] still has time ([EXP_MARGIN_SECONDS] at least). */
    fun usable(hint: LanHint, nowSeconds: Long): Boolean = hint.exp > nowSeconds + EXP_MARGIN_SECONDS

    /**
     * The [HEADER_LOCAL] value for [addresses] (the device's own addresses on its Wi-Fi / Ethernet networks): private
     * IPv4 (RFC 1918) only, without the ones [excluded] names (the VPN's own subnet), at most
     * [HOME_NETWORK_MAX_LOCAL]; null when nothing is left.
     */
    fun localHeader(addresses: List<String>, excluded: (String) -> Boolean = { false }): String? =
        homeNetworkLocalQuery(addresses.map { it.trim() }.filterNot(excluded))

    /** [host] (dotted IPv4) lies in the subnet of [address]/[prefix] (IPv4 only). */
    fun inSubnet(host: String, address: String, prefix: Int): Boolean {
        val h = ipv4(host) ?: return false
        val a = ipv4(address) ?: return false
        if (prefix !in 0..32) return false
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (h and mask) == (a and mask)
    }

    private fun ipv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/**
 * Trusts exactly one certificate: the one whose DER has the SHA-256 [pinHex]. Only for the connection to a home
 * gateway's cache ([HomeCacheFetcher]), whose certificate is self-signed and carries no name — the pin from the
 * server's signed hint is the only trust there is; no CA, no other certificate, no client certificates.
 */
@SuppressLint("CustomX509TrustManager") // deliberate: pinning to the one certificate the server named, nothing else
class PinnedCertTrustManager(pinHex: String) : X509TrustManager {
    private val pin: ByteArray = HomeCache.hexToBytes(
        requireNotNull(HomeCache.normalizeSha256(pinHex)) { "pin must be a SHA-256 in hex" },
    )

    /** [cert] is the pinned one. */
    fun matches(cert: Certificate): Boolean = try {
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert.encoded), pin)
    } catch (_: Exception) {
        false
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
        if (!matches(leaf)) throw CertificateException("server certificate does not match the pin")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * The host name check of a pinned connection: the address asked for is [host] and the session's certificate is the
 * pinned one. The gateway's certificate has no name to compare, so the pin takes the place of the name.
 */
class PinnedHostnameVerifier(private val host: String, private val trust: PinnedCertTrustManager) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        if (hostname == null || session == null || !hostname.equals(host, ignoreCase = true)) return false
        val leaf = try {
            session.peerCertificates.firstOrNull()
        } catch (_: SSLPeerUnverifiedException) {
            null
        } ?: return false
        return trust.matches(leaf)
    }
}

/**
 * Loads a [LanHint] from the home gateway into a file: pinned TLS ([PinnedCertTrustManager], [PinnedHostnameVerifier]
 * — for this one connection only), no proxy, no redirects, no credentials of the app. The bytes are checked against
 * [LanHint.sha256] (and the expected size) before the file is there; anything wrong gives [Result.Failed] and no file,
 * so the caller takes the normal download. [socketFactoryFor] binds the socket to the Wi-Fi / Ethernet network that
 * holds the gateway's address (not the VPN); null: the system routes it.
 */
class HomeCacheFetcher(
    private val socketFactoryFor: (String) -> SocketFactory? = { null },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val base: OkHttpClient = DEFAULT_BASE,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
) {
    sealed interface Result {
        data class Ok(val file: File, val bytes: Long) : Result

        /** [reason]: `expired`, `sha256_differs`, `tls`, `timeout`, `unreachable`, `http_<code>`, `size`, `sha256`, `io`. */
        data class Failed(val reason: String) : Result
    }

    /**
     * [hint] into [target] (replaced only on success). [expectedSha256] / [expectedSize]: what the caller knows of the
     * content (e.g. the node's) — a hint for other content is not used. [onProgress] gets bytes done and the total (0
     * when unknown).
     */
    suspend fun fetch(
        hint: LanHint,
        target: File,
        expectedSha256: String? = null,
        expectedSize: Long? = null,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val expected = HomeCache.normalizeSha256(expectedSha256)
        if (expected != null && expected != hint.sha256) return failed(hint, "sha256_differs")
        if (!HomeCache.usable(hint, nowSeconds())) return failed(hint, "expired")
        val job = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            val part = File(target.parentFile, target.name + ".part")
            val r = try {
                load(hint, part, expectedSize, onProgress, job)
            } catch (e: CancellationException) {
                part.delete()
                throw e
            }
            if (r !is Result.Ok) {
                part.delete()
                return@withContext r
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext failed(hint, "io")
            }
            Timber.i("Home cache: loaded %s from the gateway (%d bytes)", hint.logLabel, r.bytes)
            Result.Ok(target, r.bytes)
        }
    }

    private fun load(hint: LanHint, part: File, expectedSize: Long?, onProgress: (Long, Long) -> Unit, job: Job?): Result {
        val trust = PinnedCertTrustManager(hint.tlsSha256)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = base.newBuilder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier(PinnedHostnameVerifier(hint.host, trust))
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .socketFactory(socketFactoryFor(hint.host) ?: SocketFactory.getDefault())
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            // checked in the handshake already (trust manager, host name check); once more on the socket actually used,
            // before a byte of the request leaves
            .addNetworkInterceptor { chain ->
                val leaf = (chain.connection()?.socket() as? SSLSocket)?.session
                    ?.let { s -> runCatching { s.peerCertificates.firstOrNull() }.getOrNull() }
                if (leaf == null || !trust.matches(leaf)) throw SSLPeerUnverifiedException("not the pinned certificate")
                chain.proceed(chain.request())
            }
            .build()
        // the bytes as they are on the gateway (no transparent gzip): what is hashed is what is stored
        val request = Request.Builder().url(hint.url).header("Accept-Encoding", "identity").get().build()
        val call = client.newCall(request)
        val cancel = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (response.code != 200) return failed(hint, "http_${response.code}")
                val body = response.body
                val length = body.contentLength()
                if (expectedSize != null && expectedSize >= 0 && length >= 0 && length != expectedSize) return failed(hint, "size")
                val total = if (length >= 0) length else expectedSize ?: 0L
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (expectedSize != null && expectedSize >= 0 && done > expectedSize) return failed(hint, "size")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            onProgress(done, total)
                        }
                    }
                }
                if (expectedSize != null && expectedSize >= 0 && done != expectedSize) return failed(hint, "size")
                val got = digest.digest().joinToString("") { "%02x".format(it) }
                if (got != hint.sha256) return failed(hint, "sha256")
                return Result.Ok(part, done)
            }
        } catch (e: SocketTimeoutException) {
            return failed(hint, "timeout")
        } catch (e: SSLException) {
            // a certificate other than the pinned one ends the handshake here
            return failed(hint, "tls")
        } catch (e: ConnectException) {
            return failed(hint, "unreachable")
        } catch (e: NoRouteToHostException) {
            return failed(hint, "unreachable")
        } catch (e: IOException) {
            if (job?.isCancelled == true) throw CancellationException("cancelled")
            return failed(hint, if (e.cause is CertificateException) "tls" else "io")
        } catch (e: IllegalArgumentException) {
            return failed(hint, "io")
        } finally {
            cancel?.dispose()
        }
    }

    private fun failed(hint: LanHint, reason: String): Result.Failed {
        // the label only: the URL's query is a signature
        Timber.i("Home cache: %s not loaded from the gateway (%s), normal download", hint.logLabel, reason)
        return Result.Failed(reason)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 15_000L

        /** A plain client: none of the app's interceptors, tokens or cookies ever go to a LAN address. */
        private val DEFAULT_BASE: OkHttpClient by lazy { OkHttpClient.Builder().build() }
    }
}
