package com.gatecontrol.android.gatedrop

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * A failed LAN request. [code] goes to the plugin as the transfer's error
 * (`[a-z0-9_.-]`); [connected]: the other device was reached (then another
 * address of it will not do better).
 */
class GdHttpException(val code: String, val connected: Boolean, message: String? = null, cause: Throwable? = null) :
    IOException(message ?: code, cause)

/**
 * Minimal HTTP/1.1 client of the direct LAN transfer (protocol C v1):
 * one request per connection (`Connection: close`), body with a known
 * length, answer read by `Content-Length` (or up to the close).
 *
 * Why not OkHttp: on Android, OkHttp asks the Network Security Policy
 * before every plain-HTTP connection, and with targetSdk ≥ 28 and no
 * `network_security_config` cleartext is refused for every host —
 * `UnknownServiceException: CLEARTEXT communication to 192.168.x.y not
 * permitted by network security policy`, before a single packet leaves.
 * That made every direct transfer of this app fail (PC → phone: the PC
 * waited a minute for nothing; phone → PC: the PC waited ten minutes).
 * The transfer must be plain HTTP to whatever private address the other
 * device has (the data is AES-256-GCM encrypted end to end, the ticket
 * is the credential), which a security config cannot allow without
 * opening cleartext for the whole app. Plain sockets do not consult the
 * policy; [socketFactory] binds them to the Wi-Fi network (not the VPN).
 */
class GateDropHttp(
    private val socketFactory: SocketFactory,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {
    /** An answer; [body] is limited to `Content-Length`. Close it (closes the connection). */
    class Response(val status: Int, val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : Closeable {
        val contentLength: Long? get() = headers["content-length"]?.trim()?.toLongOrNull()
        val isSuccessful: Boolean get() = status in 200..299

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /**
     * [method] [path] to [host]:[port]. With [writeBody] the request
     * carries [contentLength] bytes written by it. Throws [GdHttpException]:
     * `connect_timeout` / `refused` / `unreachable` (not connected),
     * `broken` / `bad_response` (connected).
     */
    fun execute(
        host: String,
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        contentLength: Long? = null,
        writeBody: ((OutputStream) -> Unit)? = null,
    ): Response {
        val socket = try {
            socketFactory.createSocket()
        } catch (e: IOException) {
            // Binding to the Wi-Fi network failed (e.g. EPERM).
            throw GdHttpException("bind_failed", connected = false, message = e.message, cause = e)
        }
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw GdHttpException(connectCode(e), connected = false, message = "${e.javaClass.simpleName}: ${e.message}", cause = e)
        }
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val out = socket.getOutputStream().buffered(64 * 1024)
            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(':').append(port).append("\r\n")
                .append("Connection: close\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n") }
            if (writeBody != null) head.append("Content-Length: ").append(contentLength ?: 0).append("\r\n")
            head.append("\r\n")
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            try {
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                writeBody?.invoke(out)
                out.flush()
            } catch (e: IOException) {
                // The listener may answer early (403, 400) and close: its answer says why.
                val early = runCatching { readResponse(input, socket) }.getOrNull()
                if (early != null && !early.isSuccessful) return early
                throw GdHttpException("broken", connected = true, message = "${e.javaClass.simpleName}: ${e.message}", cause = e)
            }
            return readResponse(input, socket)
        } catch (e: GdHttpException) {
            runCatching { socket.close() }
            throw e
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw GdHttpException(if (e is SocketTimeoutException) "timeout" else "broken", connected = true, message = "${e.javaClass.simpleName}: ${e.message}", cause = e)
        }
    }

    private fun readResponse(input: InputStream, socket: Socket): Response {
        val lines = readHead(input) ?: throw GdHttpException("bad_response", connected = true)
        val status = Regex("^HTTP/1\\.[01] (\\d{3})").find(lines.first())?.groupValues?.get(1)?.toIntOrNull()
            ?: throw GdHttpException("bad_response", connected = true, message = lines.first().take(60))
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        if (length == null && headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            // Only short error answers come chunked; the body is not needed then.
            if (status in 200..299) throw GdHttpException("bad_response", connected = true, message = "chunked")
            return Response(status, headers, ByteArrayInputStream(ByteArray(0)), socket)
        }
        val body = if (length != null) LimitedInput(input, length) else input
        return Response(status, headers, body, socket)
    }

    /** Status line and headers (at most 16 KB) → lines; null when malformed. */
    private fun readHead(input: InputStream): List<String>? {
        val buf = StringBuilder()
        var last4 = 0
        while (buf.length < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        if (last4 != 0x0D0A0D0A) return null
        return buf.toString().split("\r\n").filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** Reads at most [limit] bytes of [input]. */
    private class LimitedInput(private val input: InputStream, private var limit: Long) : InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val b = input.read()
            if (b >= 0) limit--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }
    }

    companion object {
        /** Why a connection could not be made → error code for the plugin. */
        fun connectCode(e: IOException): String = when {
            e is SocketTimeoutException -> "connect_timeout"
            e is NoRouteToHostException -> "unreachable"
            e is ConnectException && e.message?.contains("refused", ignoreCase = true) == true -> "refused"
            e.message?.contains("EPERM") == true || e.message?.contains("EACCES") == true -> "not_permitted"
            else -> "unreachable"
        }
    }
}
