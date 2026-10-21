package com.gatecontrol.android.gatedrop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived listener of the direct LAN transfer (protocol C v1), opened
 * only while a transfer needs it: as a pull sender (the receiver fetches
 * `GET …/transfer/<id>/<index>`) or as a push receiver (the sender posts
 * `POST …/transfer/<id>`). Answers only private IPv4 peers that present
 * the transfer's ticket; everything else gets 403/404 and is closed.
 * Minimal HTTP/1.1, one request per connection.
 */
class GateDropListener(private val port: Int) {

    /** Files this device serves (pull mode, this device sends). */
    private class Outgoing(val ticket: String, val key: ByteArray, val files: List<GdLocalFile>, val served: MutableSet<Int>, val done: CompletableDeferred<Long>, val onProgress: (Long) -> Unit)

    /** Files this device expects (push mode, this device receives). */
    private class Incoming(val offer: GdTransferOffer, val sinkFor: (Int, GdOfferItem) -> OutputStream, val received: MutableSet<Int>, val done: CompletableDeferred<Long>, val onProgress: (Long) -> Unit)

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Opens the port; false when it is taken or not allowed. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port), 8)
            server = s
            acceptJob = scope.launch {
                while (!s.isClosed) {
                    val socket = runCatching { s.accept() }.getOrNull() ?: break
                    launch { runCatching { handle(socket) }.onFailure { Timber.d("GateDrop: listener request failed (%s)", it.javaClass.simpleName) } }
                }
            }
            true
        } catch (e: IOException) {
            Timber.d("GateDrop: listener not opened (%s)", e.javaClass.simpleName)
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        acceptJob?.cancel()
        outgoing.values.forEach { it.done.completeExceptionally(IOException("listener stopped")) }
        incoming.values.forEach { it.done.completeExceptionally(IOException("listener stopped")) }
        outgoing.clear()
        incoming.clear()
        scope.cancel()
    }

    /** Serve [files] for [direct] (pull); completes with the bytes once every file was fetched. */
    fun serve(direct: GdDirect, files: List<GdLocalFile>, onProgress: (Long) -> Unit = {}): CompletableDeferred<Long> {
        val d = CompletableDeferred<Long>()
        outgoing[direct.transferId] = Outgoing(direct.ticket, direct.key, files, ConcurrentHashMap.newKeySet(), d, onProgress)
        return d
    }

    /** Accept the files of [offer] (push); completes with the bytes once every file arrived intact. */
    fun expect(offer: GdTransferOffer, sinkFor: (Int, GdOfferItem) -> OutputStream, onProgress: (Long) -> Unit = {}): CompletableDeferred<Long> {
        val d = CompletableDeferred<Long>()
        incoming[offer.transferId] = Incoming(offer, sinkFor, ConcurrentHashMap.newKeySet(), d, onProgress)
        return d
    }

    private fun ticketOk(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(), given.trim().toByteArray())

    private fun handle(socket: Socket) = socket.use { s ->
        s.soTimeout = 30_000
        val peer = (s.inetAddress as? Inet4Address)?.hostAddress
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        if (peer == null || !GateDropJson.isPrivateIpv4(peer)) return@use status(out, 403)
        val head = readHead(input) ?: return@use status(out, 400)
        val (method, path, headers) = head
        if (!path.startsWith(GateDropDirect.PATH)) return@use status(out, 404)
        val parts = path.removePrefix(GateDropDirect.PATH).split('/')
        val transferId = GateDropJson.token(parts.getOrNull(0)) ?: return@use status(out, 404)
        when (method) {
            "GET" -> {
                val o = outgoing[transferId] ?: return@use status(out, 404)
                if (!ticketOk(o.ticket, headers["x-gd-ticket"])) return@use status(out, 403)
                val index = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in o.files.indices } ?: return@use status(out, 404)
                val f = o.files[index]
                val length = GateDropCrypto.encryptedLength(f.size)
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $length\r\n" +
                        "${GateDropDirect.H_NAME}: ${GateDropDirect.encodeName(f.name)}\r\n${GateDropDirect.H_SIZE}: ${f.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                GateDropCrypto.encryptingStream(FileInputStream(f.file), o.key, transferId, index, f.size).use { it.copyTo(out, 256 * 1024) }
                out.flush()
                o.served += index
                o.onProgress(o.served.sumOf { o.files[it].size })
                if (o.served.size == o.files.size) {
                    outgoing.remove(transferId)
                    o.done.complete(o.files.sumOf { it.size })
                }
            }
            "POST" -> {
                val i = incoming[transferId] ?: return@use status(out, 404)
                if (!ticketOk(i.offer.ticket, headers["x-gd-ticket"])) return@use status(out, 403)
                val index = headers["x-gd-item"]?.trim()?.toIntOrNull()?.takeIf { it in i.offer.items.indices } ?: return@use status(out, 400)
                val item = i.offer.items[index]
                val size = headers["x-gd-size"]?.trim()?.toLongOrNull()
                val length = headers["content-length"]?.trim()?.toLongOrNull()
                if (size != item.bytes || length != GateDropCrypto.encryptedLength(item.bytes)) return@use status(out, 400)
                val ok = runCatching {
                    i.sinkFor(index, item).use { sink -> GateDropCrypto.decryptStream(LimitedInput(input, length), sink, i.offer.key, transferId, index, item.bytes) }
                }.isSuccess
                if (!ok) {
                    status(out, 422)
                    i.done.completeExceptionally(IOException("item $index broken"))
                    incoming.remove(transferId)
                    return@use
                }
                status(out, 200)
                i.received += index
                i.onProgress(i.received.sumOf { i.offer.items[it].bytes })
                if (i.received.size == i.offer.items.size) {
                    incoming.remove(transferId)
                    i.done.complete(i.offer.items.sumOf { it.bytes })
                }
            }
            else -> status(out, 405)
        }
    }

    private fun status(out: OutputStream, code: Int) {
        val text = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Unprocessable"
        }
        runCatching {
            out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    /** Request line and headers (lower-case names), at most 16 KB; null when malformed. */
    private fun readHead(input: InputStream): Triple<String, String, Map<String, String>>? {
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
        val lines = buf.toString().split("\r\n").filter { it.isNotEmpty() }
        val req = lines.firstOrNull()?.split(' ') ?: return null
        if (req.size < 3 || !req[2].startsWith("HTTP/1.")) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return Triple(req[0], req[1].substringBefore('?'), headers)
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
}
