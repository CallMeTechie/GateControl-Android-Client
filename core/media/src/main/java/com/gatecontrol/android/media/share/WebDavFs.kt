package com.gatecontrol.android.media.share

import com.gatecontrol.android.media.MediaResult
import com.gatecontrol.android.media.MediaSourceKind
import com.gatecontrol.android.media.jellyfin.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A WebDAV folder (`https://192.168.1.20:5006/filme`, Synology's WebDAV server, Nextcloud, a PC): its folders by
 * `PROPFIND` (Depth 1), its files by `GET` with `Range` (the player jumps without loading what lies before). Robust
 * away: plain HTTP requests that bear the tunnel's latency better than the chatty SMB. Basic or Digest by [WebDavAuth].
 */
class WebDavFs(
    baseUrl: String,
    http: OkHttpClient,
    user: String?,
    password: String?,
) : ShareFs {
    override val kind = MediaSourceKind.WEBDAV

    val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    private val client: OkHttpClient = http.newBuilder().addInterceptor(WebDavAuth(base, user, password)).build()

    /** [path] below the root; a folder with its trailing slash (some servers answer a folder without one by a redirect). */
    fun url(path: String, directory: Boolean): HttpUrl {
        val b = base.newBuilder()
        // the base's own trailing empty segment ("/filme/") is not a folder name
        if (base.pathSegments.lastOrNull() == "") b.removePathSegment(base.pathSegments.size - 1)
        path.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (directory) b.addPathSegment("")
        return b.build()
    }

    override suspend fun list(dir: String): MediaResult<List<ShareEntry>> = withContext(Dispatchers.IO) {
        val clean = SharePaths.clean(dir) ?: return@withContext MediaResult.NotFound
        val request = Request.Builder()
            .url(url(clean, directory = true))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "1")
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.d("WebDAV: folder not reached (%s)", e.javaClass.simpleName)
            return@withContext MediaResult.Offline
        }
        response.use { r ->
            when {
                r.code == 401 || r.code == 403 -> MediaResult.Unauthorized
                r.code == 404 -> MediaResult.NotFound
                r.code != 207 && !r.isSuccessful -> MediaResult.Failed(r.code)
                else -> {
                    val body = try {
                        r.body.byteStream().use { readLimited(it, MAX_LISTING_BYTES) }
                    } catch (e: IOException) {
                        return@use MediaResult.Offline
                    } ?: return@use MediaResult.Failed(r.code)
                    parse(body, clean)?.let { MediaResult.Ok(it) } ?: MediaResult.Failed(r.code)
                }
            }
        }
    }

    /** The entries of a `207 Multi-Status` for folder [dir], the folder itself left out; null when it is not one. */
    internal fun parse(xml: ByteArray, dir: String): List<ShareEntry>? {
        val doc = try {
            secureFactory().newDocumentBuilder().parse(xml.inputStream())
        } catch (e: Exception) {
            Timber.d("WebDAV: listing not read (%s)", e.javaClass.simpleName)
            return null
        }
        val root = base.pathSegments.filter { it.isNotEmpty() }
        val own = root + dir.split('/').filter { it.isNotEmpty() }
        val out = ArrayList<ShareEntry>()
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        for (i in 0 until responses.length) {
            val resp = responses.item(i) as? Element ?: continue
            val href = child(resp, "href")?.textContent?.trim() ?: continue
            val url = (if (href.startsWith("http://", true) || href.startsWith("https://", true)) href.toHttpUrlOrNull() else base.resolve(href)) ?: continue
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            // only what lies in the folder asked for, one level down
            if (segments.size != own.size + 1 || segments.subList(0, own.size) != own) continue
            val name = segments.last()
            if (ShareNames.hidden(name) || SharePaths.clean(name) != name) continue
            val prop = okProp(resp) ?: continue
            val directory = child(prop, "resourcetype")?.let { child(it, "collection") } != null
            val size = child(prop, "getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0
            val modified = child(prop, "getlastmodified")?.textContent?.trim()?.let { t ->
                runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            }
            out += ShareEntry(name, SharePaths.join(dir, name), directory, size, modified)
        }
        return out
    }

    /** The `prop` of the `propstat` with status 200 (a server may list the properties it lacks with 404). */
    private fun okProp(resp: Element): Element? {
        val stats = resp.getElementsByTagNameNS(DAV, "propstat")
        for (i in 0 until stats.length) {
            val st = stats.item(i) as? Element ?: continue
            val status = child(st, "status")?.textContent.orEmpty()
            if (status.isEmpty() || status.contains(" 200")) return child(st, "prop")
        }
        return null
    }

    private fun child(e: Element, local: String): Element? {
        var n = e.firstChild
        while (n != null) {
            if (n is Element && n.localName == local && n.namespaceURI == DAV) return n
            n = n.nextSibling
        }
        return null
    }

    override suspend fun bytes(path: String, max: Int): ByteArray? = withContext(Dispatchers.IO) {
        val clean = SharePaths.clean(path)?.takeIf { it.isNotEmpty() } ?: return@withContext null
        try {
            client.newCall(Request.Builder().url(url(clean, directory = false)).get().build()).await().use { r ->
                if (!r.isSuccessful) return@use null
                if (r.body.contentLength() > max) return@use null
                r.body.byteStream().use { readLimited(it, max) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.d("WebDAV: picture not loaded (%s)", e.javaClass.simpleName)
            null
        }
    }

    override fun open(path: String, position: Long): ShareInput {
        val clean = SharePaths.clean(path)?.takeIf { it.isNotEmpty() } ?: throw IOException("bad path")
        val request = Request.Builder().url(url(clean, directory = false)).get()
            .apply { if (position > 0) header("Range", "bytes=$position-") }
            // the player reads the bytes as they are (no gzip of a video)
            .header("Accept-Encoding", "identity")
            .build()
        val r = client.newCall(request).execute()
        if (r.code == 401 || r.code == 403) {
            r.close()
            throw IOException("WebDAV: access refused (${r.code})")
        }
        if (r.code != 206 && r.code != 200) {
            r.close()
            throw IOException("WebDAV: HTTP ${r.code}")
        }
        val stream = r.body.byteStream()
        if (r.code == 206) {
            val total = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
            return ShareInput(total, stream)
        }
        // the server ignored the range: skip to the place
        val length = r.body.contentLength()
        var skip = position
        try {
            while (skip > 0) {
                val n = stream.skip(skip)
                if (n <= 0) {
                    if (stream.read() < 0) throw IOException("WebDAV: shorter than the place asked for")
                    skip--
                } else {
                    skip -= n
                }
            }
        } catch (e: IOException) {
            r.close()
            throw e
        }
        return ShareInput(length, stream)
    }

    companion object {
        private const val DAV = "DAV:"
        private val XML = "application/xml; charset=utf-8".toMediaType()

        /** A folder of thousands of files is a few MB of XML; more is not read. */
        const val MAX_LISTING_BYTES = 16 * 1024 * 1024

        internal const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        /** No DTD, no external entities: the listing comes from the network. */
        internal fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            isXIncludeAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }

        /** [input] to its end, null when it is longer than [max]. */
        internal fun readLimited(input: InputStream, max: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
