package com.gatecontrol.android.drive

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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Drive plugin's app API (plugin README "API") through the core's plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-drive/api/…`) and GateControl's media routes (`…/media`, resumable).
 * The only place that knows the routes: one method per route, answers parsed by [DriveJson]. Works over the
 * server's public address — no VPN needed. Never throws (except cancellation).
 */
@Singleton
class DriveApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        private const val TIMEOUT_MS = 25_000L
        /** Small files go in one piece. */
        private const val RESUMABLE_FROM = 8L * 1024 * 1024
        /** Part size of a resumable upload: a multiple of 64 KiB. */
        private const val PART_BYTES = 8L * 1024 * 1024
        private const val PART_TIMEOUT_MS = 15 * 60_000L
        private const val PLAIN_TIMEOUT_MS = 60 * 60_000L
        private const val RESUME_TRIES = 5
        private const val DEFAULT_MIME = "application/octet-stream"
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
    ): DResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return DResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return DResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (DriveJson.isRefusal(body)) return DResult.Failed(DriveJson.errorCode(body) ?: "failed", response.code(), body)
                parse(body)?.let { DResult.Ok(it) } ?: DResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = errorJson(response)
                DResult.Failed(DriveJson.errorCode(err) ?: "http_${response.code()}", response.code(), err)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Offline
        } catch (e: Exception) {
            Timber.d("Drive: call failed (%s)", e.javaClass.simpleName)
            DResult.Failed(CODE_INVALID, 0)
        }
    }

    private fun errorJson(response: Response<*>): JsonObject? = runCatching {
        response.errorBody()?.use { b -> b.string().takeIf { it.length < 65_536 } }
            ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
    }.getOrNull()

    private val unit: (JsonObject) -> Unit = { }

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?) =
        call(request = { pluginGet(DRIVE_PLUGIN, path, query + lang()) }, parse = parse)

    private suspend fun <T> post(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPost(DRIVE_PLUGIN, path, body, lang()) }, parse = parse)

    private suspend fun <T> patch(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPatch(DRIVE_PLUGIN, path, body, lang()) }, parse = parse)

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?, query: Map<String, String> = emptyMap()) =
        call(request = { pluginDelete(DRIVE_PLUGIN, path, query + lang()) }, parse = parse)

    private fun bad(): DResult<Nothing> = DResult.Failed(CODE_INVALID, 0)

    // ─── who, where ─────────────────────────────────────────────────

    suspend fun me(): DResult<DMe> = get("me", parse = DriveJson::me)

    suspend fun areas(): DResult<List<DArea>> = get("areas", parse = DriveJson::areas)

    // ─── the tree ───────────────────────────────────────────────────

    suspend fun node(id: Long): DResult<DNode> = get("nodes/$id", parse = DriveJson::nodeAnswer)

    /** The folder [id]: its place (crumbs) and its content, folders first. */
    suspend fun folder(id: Long, sort: String = "name", order: String = "asc", offset: Int = 0, limit: Int = 500): DResult<DFolder> {
        val head = call(request = { pluginGet(DRIVE_PLUGIN, "nodes/$id", lang()) }, parse = { it })
        if (head !is DResult.Ok) return head.notOk()
        val q = mapOf("sort" to sort, "order" to order, "offset" to offset.toString(), "limit" to limit.toString())
        return get("nodes/$id/children", q) { DriveJson.folder(head.value, it) }
    }

    /** A new folder; a taken name gets a free one ([rename]) or `409 exists`. */
    suspend fun createFolder(parentId: Long, name: String, rename: Boolean = true): DResult<DNode> =
        post("nodes", DriveJson.mkdirBody(parentId, name, rename), DriveJson::nodeAnswer)

    suspend fun rename(id: Long, name: String): DResult<DNode> = patch("nodes/$id", DriveJson.patchBody(name, null), DriveJson::nodeAnswer)

    suspend fun move(id: Long, parentId: Long): DResult<DNode> = patch("nodes/$id", DriveJson.patchBody(null, parentId), DriveJson::nodeAnswer)

    /** Into the trash. */
    /**
     * To the trash. [everywhere]: the user's other devices also remove their copies when next online (Drive 1.2+;
     * an older Drive ignores it and just trashes).
     */
    suspend fun delete(id: Long, everywhere: Boolean = false): DResult<Unit> =
        del("nodes/$id", unit, if (everywhere) mapOf("everywhere" to "1") else emptyMap())

    /** Files deleted "everywhere" on another device that this one still has to remove (Drive 1.2+). */
    suspend fun wipes(): DResult<List<DWipe>> = get("wipes", parse = DriveJson::wipes)

    /** This device has removed (or never had) these files. */
    suspend fun wipesDone(ids: List<Long>): DResult<Unit> = post("wipes/done", DriveJson.wipesDoneBody(ids), unit)

    suspend fun search(q: String, area: String? = null): DResult<List<DNode>> {
        val query = buildMap {
            put("q", q)
            if (area != null) put("area", DriveJson.areaId(area) ?: return bad())
        }
        return get("search", query, DriveJson::items)
    }

    suspend fun changes(since: Long, limit: Int = 500): DResult<DChanges> =
        get("changes", mapOf("since" to since.toString(), "limit" to limit.toString()), DriveJson::changes)

    // ─── versions, trash ────────────────────────────────────────────

    suspend fun versions(id: Long): DResult<DVersions> = get("nodes/$id/versions", parse = DriveJson::versions)

    suspend fun restoreVersion(id: Long, versionId: Long): DResult<DNode> = post("nodes/$id/versions/$versionId/restore", JsonObject(), DriveJson::nodeAnswer)

    suspend fun deleteVersion(id: Long, versionId: Long): DResult<Unit> = del("nodes/$id/versions/$versionId", unit)

    suspend fun trash(area: String? = null): DResult<List<DNode>> {
        val q = if (area == null) emptyMap() else mapOf("area" to (DriveJson.areaId(area) ?: return bad()))
        return get("trash", q, DriveJson::items)
    }

    suspend fun restoreFromTrash(id: Long): DResult<DNode> = post("trash/$id/restore", JsonObject(), DriveJson::nodeAnswer)

    /** Gone for good. */
    suspend fun purge(id: Long): DResult<Unit> = del("trash/$id", unit)

    // ─── links ──────────────────────────────────────────────────────

    /** [mode] `view` | `download` | `upload` (folders), [expires] `1d` | `7d` | `30d` | `never`. */
    suspend fun createLink(nodeId: Long, mode: String, expires: String, pin: String?): DResult<DLink> =
        post("links", DriveJson.linkBody(nodeId, mode, expires, pin), DriveJson::linkAnswer)

    suspend fun links(nodeId: Long? = null): DResult<DLinks> =
        get("links", if (nodeId == null) emptyMap() else mapOf("nodeId" to nodeId.toString()), DriveJson::links)

    suspend fun endLink(token: String): DResult<Unit> {
        if (!Regex("^[A-Za-z0-9_-]{6,200}$").matches(token)) return bad()
        return del("links/$token", unit)
    }

    // ─── files ──────────────────────────────────────────────────────

    suspend fun uploadCheck(parentId: Long, name: String, sha256: String?, size: Long?): DResult<DUploadCheck> =
        post("uploads/check", DriveJson.checkBody(parentId, name, sha256, size), DriveJson::uploadCheck)

    /** Makes the uploaded [mediaId] a file `name` in folder [parentId]; [onConflict] `rename` | `version`. */
    suspend fun commit(mediaId: String, parentId: Long, name: String, mtimeMs: Long?, onConflict: String): DResult<Pair<DNode, Boolean>> =
        post("uploads/commit", DriveJson.commitBody(mediaId, parentId, name, mtimeMs, onConflict), DriveJson::commit)

    /**
     * The file [nodeId] (or one of its [versionId]s) as a stream. `GET …/content` comes first: it names the media and
     * tells a child that a parent looked into its folder. The caller closes the body; null when unreachable or refused.
     */
    suspend fun download(nodeId: Long, versionId: Long? = null): DResult<Response<ResponseBody>> {
        val path = if (versionId == null) "nodes/$nodeId/content" else "nodes/$nodeId/versions/$versionId/content"
        val media = get(path, parse = DriveJson::contentMediaId)
        if (media !is DResult.Ok) return media.notOk()
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return DResult.NotConfigured
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.mediaClient(url).pluginMediaGet(DRIVE_PLUGIN, media.value) }
                ?: return DResult.Offline
            if (r.isSuccessful && r.body() != null) DResult.Ok(r) else {
                r.errorBody()?.close()
                DResult.Failed("http_${r.code()}", r.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Offline
        } catch (e: Exception) {
            Timber.d("Drive: download failed (%s)", e.javaClass.simpleName)
            DResult.Failed(CODE_INVALID, 0)
        }
    }

    /**
     * Uploads [file] as [mime] → its media id. Files above [RESUMABLE_FROM] go resumable: parts of [PART_BYTES] as
     * `PUT …/media?uploadId&offset` with `Content-Range`; after an interruption or a 409 the upload continues at the
     * server's offset. [onProgress] gets the bytes sent so far.
     */
    suspend fun upload(file: File, mime: String?, onProgress: (Long) -> Unit = {}): DResult<String> =
        upload(DriveSource.of(file), mime, onProgress)

    /** Uploads [source] (a file, or a photo straight from the media store) as [mime] → its media id. */
    suspend fun upload(source: DriveSource, mime: String?, onProgress: (Long) -> Unit = {}): DResult<String> {
        val type = mime?.toMediaTypeOrNull() ?: DEFAULT_MIME.toMediaTypeOrNull()!!
        val length = source.length
        if (length <= RESUMABLE_FROM) {
            return call(PLAIN_TIMEOUT_MS, media = true, request = { pluginMediaUpload(DRIVE_PLUGIN, body(source, type, 0, length, onProgress)) }, parse = DriveJson::uploadedMediaId)
        }
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return DResult.NotConfigured
        val client = apiClientProvider.mediaClient(url)
        val uploadId = newUploadId()
        var offset = 0L
        var failures = 0
        while (true) {
            val part = minOf(PART_BYTES, length - offset)
            if (part <= 0) return DResult.Failed("incomplete", 0)
            val end = offset + part - 1
            val query = mapOf("uploadId" to uploadId, "offset" to offset.toString())
            val response = try {
                withTimeoutOrNull(PART_TIMEOUT_MS) {
                    client.pluginMediaUploadPart(DRIVE_PLUGIN, query, "bytes $offset-$end/$length", body(source, type, offset, part, onProgress))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            }
            if (response == null) {
                if (++failures > RESUME_TRIES) return DResult.Offline
                delay(minOf(30_000L, 1_000L shl failures))
                offset = uploadOffset(uploadId)?.takeIf { it in 0..length } ?: offset
                continue
            }
            val answer = DriveJson.uploadPart(if (response.isSuccessful) response.body() else errorJson(response))
            when {
                response.isSuccessful && answer.complete == true ->
                    return answer.mediaId?.let { DResult.Ok(it) } ?: DResult.Failed(CODE_INVALID, response.code())
                response.isSuccessful && answer.complete == false -> {
                    failures = 0
                    offset = answer.offset?.takeIf { it in (offset + 1)..length } ?: (end + 1)
                }
                response.isSuccessful -> return DResult.Failed(CODE_INVALID, response.code())
                response.code() == 409 -> {
                    if (++failures > RESUME_TRIES) return DResult.Failed(answer.code ?: "http_409", 409)
                    if (answer.code == "busy") delay(2_000)
                    offset = answer.offset?.takeIf { it in 0..length } ?: uploadOffset(uploadId) ?: offset
                }
                response.code() >= 500 || response.code() == 429 -> {
                    if (++failures > RESUME_TRIES) return DResult.Failed(answer.code ?: "http_${response.code()}", response.code())
                    delay(minOf(30_000L, 1_000L shl failures))
                    offset = uploadOffset(uploadId)?.takeIf { it in 0..length } ?: offset
                }
                else -> return DResult.Failed(answer.code ?: "http_${response.code()}", response.code())
            }
        }
    }

    private fun newUploadId(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** How far a resumable upload got (`GET …/media?uploadId`, else `HEAD` with `Upload-Offset`); null when unknown. */
    private suspend fun uploadOffset(uploadId: String): Long? {
        val url = setupRepository.getServerUrl()
        if (url.isBlank()) return null
        return try {
            val client = apiClientProvider.getClient(url)
            withTimeoutOrNull(TIMEOUT_MS) { client.pluginMediaStatus(DRIVE_PLUGIN, uploadId) }
                ?.takeIf { it.isSuccessful }?.body()?.let { DriveJson.uploadOffset(it) }
                ?: withTimeoutOrNull(TIMEOUT_MS) { client.pluginMediaHead(DRIVE_PLUGIN, uploadId) }
                    ?.takeIf { it.isSuccessful }?.headers()?.get("Upload-Offset")?.trim()?.toLongOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** [length] bytes of [source] from [offset], streamed; [onProgress] gets the position in the file. */
    private fun body(source: DriveSource, type: MediaType, offset: Long, length: Long, onProgress: (Long) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType() = type
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                source.open(offset).use { input ->
                    input.source().use { src ->
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
