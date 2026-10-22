package com.gatecontrol.android.gatedrop

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GateDrop plugin's app API (contract B) through the core's client
 * plugin bridge (`/api/v1/client/plugins/gatecontrol-gatedrop/api/…`) and
 * its media routes (`…/media`, resumable for big files, contract A1). The
 * only place that knows the routes: one method per route, answers parsed
 * by [GateDropJson]. Never throws (except cancellation).
 */
@Singleton
class GateDropApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 20_000L
        /** A whole upload of up to 2 GB on a slow line. */
        private const val UPLOAD_TIMEOUT_MS = 60 * 60_000L
        private const val RESUME_TRIES = 5
        /** Part size of a resumable upload: a multiple of 64 KiB (core 1.193). */
        const val PART_BYTES = 8L * 1024 * 1024
        /** One part; the server cuts a body after 5 min without data. */
        private const val PART_TIMEOUT_MS = 15 * 60_000L
        private val random = SecureRandom()
    }

    private suspend fun lang(): Map<String, String> =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" }
            ?.let { mapOf("lang" to it) } ?: emptyMap()

    private suspend fun <T> call(
        timeoutMs: Long = TIMEOUT_MS,
        media: Boolean = false,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): GdResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return GdResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return GdResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (GateDropJson.isRefusal(body)) return GdResult.Failed(GateDropJson.errorCode(body) ?: "failed", response.code())
                parse(body)?.let { GdResult.Ok(it) } ?: GdResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                Timber.d("GateDrop: HTTP %d", response.code())
                GdResult.Failed(GateDropJson.errorCode(err) ?: "http_${response.code()}", response.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            GdResult.Offline
        } catch (e: Exception) {
            Timber.d("GateDrop: call failed (%s)", e.javaClass.simpleName)
            GdResult.Failed(CODE_INVALID, 0)
        }
    }

    private val unit: (JsonObject) -> Unit = { }

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?) =
        call(request = { pluginGet(GATEDROP_PLUGIN, path, query + lang()) }, parse = parse)

    private suspend fun <T> post(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPost(GATEDROP_PLUGIN, path, body, lang()) }, parse = parse)

    private suspend fun <T> put(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPut(GATEDROP_PLUGIN, path, body) }, parse = parse)

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?) =
        call(request = { pluginDelete(GATEDROP_PLUGIN, path) }, parse = parse)

    /** Path segment of a server id or token (validated by [GateDropJson]); null when unsafe. */
    private fun seg(value: String): String? = value.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    // ─── targets, presence ──────────────────────────────────────────

    suspend fun targets(): GdResult<GdTargets> = get("targets", parse = GateDropJson::targets)

    suspend fun presence(lan: List<Pair<String, Int>>, port: Int?, listening: Boolean): GdResult<Unit> =
        put("presence", GateDropJson.presenceBody(lan, port, listening), unit)

    // ─── sending ────────────────────────────────────────────────────

    suspend fun send(items: List<GdSendItem>, text: String?, link: String?, note: String?, to: GdRecipients): GdResult<GdSendResult> =
        post("sends", GateDropJson.sendBody(items, text, link, note, to), GateDropJson::sendResult)

    /** The files uploaded after a failed direct transfer (`POST sends/<id>/items`, item index → media id). */
    suspend fun addItems(sendId: String, items: Map<Int, String>): GdResult<Unit> {
        val id = seg(sendId) ?: return GdResult.Failed(CODE_INVALID, 0)
        return post("sends/$id/items", GateDropJson.addItemsBody(items), unit)
    }

    /** Outcome of a direct transfer (sender and receiver); → what the server wants now (`fallback`, `missing`). */
    suspend fun transferResult(transferId: String, ok: Boolean, bytes: Long? = null, error: String? = null): GdResult<GdTransferAnswer> {
        val id = seg(transferId) ?: return GdResult.Failed(CODE_INVALID, 0)
        return post("transfers/$id/result", GateDropJson.resultBody(ok, bytes, error), GateDropJson::transferAnswer)
    }

    /** A transfer this device should take part in (`GET transfers/<id>`), e.g. to learn its ticket. */
    suspend fun transfer(transferId: String): GdResult<GdTransferOffer> {
        val id = seg(transferId) ?: return GdResult.Failed(CODE_INVALID, 0)
        return get("transfers/$id", parse = GateDropJson::offer)
    }

    // ─── inbox, links ───────────────────────────────────────────────

    suspend fun inbox(kind: GdInboxKind): GdResult<List<GdEntry>> = get("inbox", mapOf("kind" to kind.wire), GateDropJson::entries)

    suspend fun links(): GdResult<List<GdLink>> = get("inbox", mapOf("kind" to GdInboxKind.LINKS.wire), GateDropJson::links)

    suspend fun sendDetail(sendId: String): GdResult<GdEntry> {
        val id = seg(sendId) ?: return GdResult.Failed(CODE_INVALID, 0)
        return get("sends/$id", parse = GateDropJson::entry)
    }

    /**
     * The sends waiting for this device (`GET pending`, GateDrop 1.1),
     * oldest first. An older plugin answers 404, a login without a device
     * 409 `no_device`.
     */
    suspend fun pending(): GdResult<List<GdEntry>> = get("pending", parse = GateDropJson::pending)

    /** Receipt of a send (`POST sends/<id>/opened`): it leaves `pending`. */
    suspend fun opened(sendId: String): GdResult<Unit> {
        val id = seg(sendId) ?: return GdResult.Failed(CODE_INVALID, 0)
        return post("sends/$id/opened", JsonObject(), unit)
    }

    suspend fun endLink(token: String): GdResult<Unit> {
        val t = seg(token) ?: return GdResult.Failed(CODE_INVALID, 0)
        return del("links/$t", unit)
    }

    // ─── media ──────────────────────────────────────────────────────

    /**
     * Uploads [file] as [mime] → its media id. Files above
     * [GateDropLogic.RESUMABLE_FROM] go resumable (core 1.193, contract
     * A1): parts of [PART_BYTES] (a multiple of 64 KiB) as
     * `PUT …/media?uploadId&offset` with `Content-Range`; after an
     * interruption or a 409 the upload continues at the server's offset.
     * A server without resumable uploads gets a plain upload.
     */
    suspend fun upload(file: File, mime: String, onProgress: (Long) -> Unit = {}): GdResult<String> {
        val type = mime.toMediaTypeOrNull() ?: GateDropJson.DEFAULT_MIME.toMediaTypeOrNull()!!
        val length = file.length()
        if (length <= GateDropLogic.RESUMABLE_FROM) return plainUpload(file, type, onProgress)
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return GdResult.NotConfigured
        val client = apiClientProvider.mediaClient(url)
        val uploadId = newUploadId()
        var offset = 0L
        var failures = 0
        while (true) {
            val part = minOf(PART_BYTES, length - offset)
            if (part <= 0) return GdResult.Failed("incomplete", 0)
            val end = offset + part - 1
            val query = mapOf("uploadId" to uploadId, "offset" to offset.toString())
            val response = try {
                withTimeoutOrNull(PART_TIMEOUT_MS) {
                    client.pluginMediaUploadPart(GATEDROP_PLUGIN, query, "bytes $offset-$end/$length", body(file, type, offset, part, onProgress))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            }
            if (response == null) {
                // Cut off: continue where the server is.
                if (++failures > RESUME_TRIES) return GdResult.Offline
                delay(minOf(30_000L, 1_000L shl failures))
                offset = uploadOffset(uploadId)?.takeIf { it in 0..length } ?: offset
                continue
            }
            val bodyJson = if (response.isSuccessful) response.body() else errorJson(response)
            val answer = GateDropJson.uploadPart(bodyJson)
            when {
                response.isSuccessful && answer.complete == true -> {
                    return answer.mediaId?.let { GdResult.Ok(it) } ?: GdResult.Failed(CODE_INVALID, response.code())
                }
                response.isSuccessful && answer.complete == false -> {
                    failures = 0
                    offset = answer.offset?.takeIf { it in (offset + 1)..length } ?: (end + 1)
                }
                // A server that does not know the resumable form stored the part as a file: send the whole file plainly.
                response.isSuccessful -> return plainUpload(file, type, onProgress)
                response.code() == 409 -> {
                    if (++failures > RESUME_TRIES) return GdResult.Failed(answer.code ?: "http_409", 409)
                    if (answer.code == "busy") delay(2_000)
                    offset = answer.offset?.takeIf { it in 0..length } ?: uploadOffset(uploadId) ?: offset
                }
                offset == 0L && response.code() in setOf(400, 404, 405, 416) -> return plainUpload(file, type, onProgress)
                response.code() >= 500 || response.code() == 429 -> {
                    if (++failures > RESUME_TRIES) return GdResult.Failed(answer.code ?: "http_${response.code()}", response.code())
                    delay(minOf(30_000L, 1_000L shl failures))
                    offset = uploadOffset(uploadId)?.takeIf { it in 0..length } ?: offset
                }
                else -> return GdResult.Failed(answer.code ?: "http_${response.code()}", response.code())
            }
        }
    }

    private suspend fun plainUpload(file: File, type: okhttp3.MediaType, onProgress: (Long) -> Unit): GdResult<String> =
        call(UPLOAD_TIMEOUT_MS, media = true, request = { pluginMediaUpload(GATEDROP_PLUGIN, body(file, type, 0, file.length(), onProgress)) }, parse = GateDropJson::uploadedMediaId)

    private fun errorJson(response: Response<*>): JsonObject? = runCatching {
        response.errorBody()?.use { b -> b.string().takeIf { it.length < 16_384 } }
            ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
    }.getOrNull()

    private fun newUploadId(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /**
     * How far a resumable upload got: `GET …/media?uploadId` (`{ offset }`),
     * else `HEAD` (`Upload-Offset`), else the older `…/media/uploads/<id>`
     * form. Null when the server cannot say.
     */
    private suspend fun uploadOffset(uploadId: String): Long? {
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return null
        fun header(r: Response<*>?): Long? = r?.takeIf { it.isSuccessful }?.headers()?.let { h ->
            (h["Upload-Offset"] ?: h["X-Upload-Offset"])?.trim()?.toLongOrNull()
        }
        return try {
            val client = apiClientProvider.getClient(url)
            withTimeoutOrNull(TIMEOUT_MS) { client.pluginMediaStatus(GATEDROP_PLUGIN, uploadId) }
                ?.takeIf { it.isSuccessful }?.body()?.let { GateDropJson.uploadOffset(it) }
                ?: header(withTimeoutOrNull(TIMEOUT_MS) { client.pluginMediaHead(GATEDROP_PLUGIN, uploadId) })
                ?: header(withTimeoutOrNull(TIMEOUT_MS) { client.pluginMediaUploadHead(GATEDROP_PLUGIN, uploadId) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** [length] bytes of [file] from [offset], streamed; [onProgress] gets the file position. */
    private fun body(file: File, type: okhttp3.MediaType, offset: Long, length: Long, onProgress: (Long) -> Unit): RequestBody {
        return object : RequestBody() {
            override fun contentType() = type
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    Channels.newInputStream(raf.channel).source().use { src ->
                        var sent = 0L
                        val buffer = okio.Buffer()
                        while (sent < length) {
                            val n = src.read(buffer, minOf(256 * 1024L, length - sent))
                            if (n < 0) throw IOException("file shorter than announced")
                            sink.write(buffer, n)
                            sent += n
                            onProgress(offset + sent)
                        }
                    }
                }
            }
        }
    }

    /**
     * A received file: `GET items/<mediaId>` (the plugin checks the caller)
     * names the media route (`{ item, url }`), the file comes from there.
     * The caller closes the body; null when unreachable or refused.
     */
    suspend fun download(mediaId: String): Response<ResponseBody>? {
        val id = GateDropJson.mediaId(mediaId) ?: return null
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        val target = when (val r = get("items/$id", parse = { o -> GateDropJson.itemMediaId(o) ?: id })) {
            is GdResult.Ok -> r.value
            // Older plugin build without the route: the media route itself.
            is GdResult.Failed -> if (r.http == 404 && r.code.startsWith("http_")) id else return null
            else -> return null
        }
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.mediaClient(url).pluginMediaGet(GATEDROP_PLUGIN, target) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d("GateDrop: download failed (%s)", e.javaClass.simpleName)
            null
        }
    }

}
