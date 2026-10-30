package com.gatecontrol.android.mail

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Mail plugin's app API (contract C2) through the core's plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-mail/api/…`) and GateControl's media routes. The only place that knows the
 * routes: one method per route, answers parsed by [MailJson]. Paths are built from fixed segments and server ids only.
 * Never throws (except cancellation).
 */
@Singleton
class MailApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val CODE_INVALID = "invalid_answer"
        const val CODE_OFFLINE = "offline"
        /** The plugin API under the server address (the WebView's pictures and `GET providers` are read raw). */
        const val API_PREFIX = "api/v1/client/plugins/$MAIL_PLUGIN/api/"
        private const val TIMEOUT_MS = 25_000L
        /** Adding an account checks IMAP and SMTP first; the server's own time-outs are 30 s each. */
        private const val ACCOUNT_TIMEOUT_MS = 75_000L
        private const val UPLOAD_TIMEOUT_MS = 30 * 60_000L
        private const val IMAGE_TIMEOUT_MS = 20_000L
        private const val IMAGE_MAX_BYTES = 6L * 1024 * 1024
    }

    private val _unauthorized = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A call answered 401 (the device's token is gone): the offline copy is no longer this person's to keep. */
    val unauthorized: SharedFlow<Int> = _unauthorized.asSharedFlow()

    suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    private suspend fun langQuery(): Map<String, String> = mapOf("lang" to lang())

    private suspend fun <T> call(
        timeoutMs: Long = TIMEOUT_MS,
        media: Boolean = false,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): MResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return MResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return MResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (MailJson.isRefusal(body)) return MResult.Failed(MailJson.failureCode(response.code(), body), response.code(), body.obj())
                parse(body)?.let { MResult.Ok(it) } ?: MResult.Failed(CODE_INVALID, response.code())
            } else {
                if (response.code() == 401) _unauthorized.tryEmit(401)
                val err = errorJson(response)
                MResult.Failed(MailJson.failureCode(response.code(), err), response.code(), err?.obj())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MResult.Offline
        } catch (e: Exception) {
            Timber.d("Mail: call failed (%s)", e.javaClass.simpleName)
            MResult.Failed(CODE_INVALID, 0)
        }
    }

    /** The error object (`{ error: { code, … } }`), or the whole answer when it has none. */
    private fun JsonObject.obj(): JsonObject = get("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: this

    private fun errorJson(response: Response<*>): JsonObject? = runCatching {
        response.errorBody()?.use { b -> b.string().takeIf { it.length < 65_536 } }
            ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
    }.getOrNull()

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?) =
        call(request = { pluginGet(MAIL_PLUGIN, path, query + langQuery()) }, parse = parse)

    private suspend fun <T> post(path: String, body: JsonObject, timeoutMs: Long = TIMEOUT_MS, parse: (JsonObject) -> T?) =
        call(timeoutMs = timeoutMs, request = { pluginPost(MAIL_PLUGIN, path, body, langQuery()) }, parse = parse)

    private suspend fun <T> put(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPut(MAIL_PLUGIN, path, body, langQuery()) }, parse = parse)

    private suspend fun <T> patch(path: String, body: JsonObject, timeoutMs: Long = TIMEOUT_MS, parse: (JsonObject) -> T?) =
        call(timeoutMs = timeoutMs, request = { pluginPatch(MAIL_PLUGIN, path, body, langQuery()) }, parse = parse)

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?) =
        call(request = { pluginDelete(MAIL_PLUGIN, path, langQuery()) }, parse = parse)

    private val unit: (JsonObject) -> Unit = { }

    /** A GET whose answer is not an object (`GET providers` is an array): read raw, parsed here. */
    private suspend fun <T> getAny(path: String, parse: (JsonElement) -> T?): MResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return MResult.NotConfigured
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) {
                apiClientProvider.getClient(url).getRaw(API_PREFIX + path + "?lang=" + URLEncoder.encode(lang(), "UTF-8"))
            } ?: return MResult.Offline
            val text = r.body()?.use { b -> b.string().takeIf { it.length < 1_000_000 } }
            if (!r.isSuccessful) {
                if (r.code() == 401) _unauthorized.tryEmit(401)
                val err = r.errorBody()?.use { b -> runCatching { JsonParser.parseString(b.string()).asJsonObject }.getOrNull() }
                return MResult.Failed(MailJson.failureCode(r.code(), err), r.code(), err)
            }
            val el = text?.let { runCatching { JsonParser.parseString(it) }.getOrNull() } ?: return MResult.Failed(CODE_INVALID, r.code())
            parse(el)?.let { MResult.Ok(it) } ?: MResult.Failed(CODE_INVALID, r.code())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MResult.Offline
        } catch (e: Exception) {
            Timber.d("Mail: call failed (%s)", e.javaClass.simpleName)
            MResult.Failed(CODE_INVALID, 0)
        }
    }

    // ─── account, status ────────────────────────────────────────────

    /** `GET me` with the raw answer (kept for offline). */
    suspend fun me(): MResult<Pair<MMe, JsonObject>> = get("me") { o -> MailJson.me(o)?.let { it to o } }

    suspend fun putSettings(threads: Boolean? = null, swipeLeft: String? = null, swipeRight: String? = null): MResult<MSettings> =
        put("settings", MailJson.settingsBody(threads, swipeLeft, swipeRight), MailJson::settingsAnswer)

    suspend fun providers(): MResult<List<MProvider>> = getAny("providers", MailJson::providers)

    suspend fun discover(email: String): MResult<MDiscover> = post("accounts/discover", MailJson.discoverBody(email), parse = MailJson::discover)

    suspend fun addAccount(body: JsonObject): MResult<MAccount> = post("accounts", body, ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

    suspend fun addMicrosoft(body: JsonObject): MResult<MAccount> = post("accounts/microsoft", body, ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

    suspend fun reloginMicrosoft(id: Long, body: JsonObject): MResult<MAccount> = post("accounts/$id/microsoft", body, ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

    suspend fun patchAccount(id: Long, body: JsonObject): MResult<MAccount> = patch("accounts/$id", body, ACCOUNT_TIMEOUT_MS, MailJson::accountAnswer)

    suspend fun deleteAccount(id: Long): MResult<Unit> = del("accounts/$id", unit)

    // ─── folders, lists, search ─────────────────────────────────────

    suspend fun folders(): MResult<Pair<Map<Long, List<MFolder>>, JsonObject>> = get("folders") { o -> MailJson.folders(o)?.let { it to o } }

    suspend fun messages(query: Map<String, String>): MResult<Pair<MPage, JsonObject>> = get("messages", query) { o -> MailJson.page(o)?.let { it to o } }

    suspend fun search(query: Map<String, String>): MResult<MSearchPage> = get("search", query, MailJson::searchPage)

    suspend fun thread(threadId: Long): MResult<List<MMessage>> = get("threads/$threadId", parse = MailJson::thread)

    suspend fun message(id: Long): MResult<Pair<MMessage, JsonObject>> = get("messages/$id") { o -> MailJson.message(o)?.let { it to o } }

    suspend fun allowImages(id: Long, sender: Boolean): MResult<Pair<MMessage, JsonObject>> =
        post("messages/$id/images", MailJson.imagesBody(sender)) { o -> MailJson.message(o)?.let { it to o } }

    suspend fun flags(ids: Collection<Long>, unread: Boolean? = null, flagged: Boolean? = null): MResult<List<MSummary>> =
        post("messages/flags", MailJson.flagsBody(ids, unread, flagged), parse = MailJson::changedItems)

    suspend fun move(ids: Collection<Long>, folderId: Long): MResult<List<MSummary>> =
        post("messages/move", MailJson.moveBody(ids, folderId), parse = MailJson::changedItems)

    suspend fun archive(ids: Collection<Long>): MResult<List<MSummary>> = post("messages/archive", MailJson.idsBody(ids), parse = MailJson::changedItems)

    suspend fun delete(ids: Collection<Long>): MResult<List<MSummary>> = post("messages/delete", MailJson.idsBody(ids), parse = MailJson::changedItems)

    suspend fun junk(ids: Collection<Long>): MResult<List<MSummary>> = post("messages/junk", MailJson.idsBody(ids), parse = MailJson::changedItems)

    suspend fun setTab(ids: Collection<Long>, tab: String): MResult<List<MSummary>> = post("messages/tab", MailJson.tabBody(ids, tab), parse = MailJson::changedItems)

    suspend fun attachmentMedia(messageId: Long, attachmentId: Long): MResult<MAttachmentMedia> =
        get("messages/$messageId/attachments/$attachmentId", parse = MailJson::attachmentMedia)

    /** A media file of the plugin as a stream (the caller closes it). */
    suspend fun media(mediaId: String): MResult<ResponseBody> {
        if (!Regex("^[A-Za-z0-9_.~-]{1,200}$").matches(mediaId)) return MResult.Failed(CODE_INVALID, 0)
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return MResult.NotConfigured
        return try {
            val r = withTimeoutOrNull(TIMEOUT_MS) { apiClientProvider.mediaClient(url).pluginMediaGet(MAIL_PLUGIN, mediaId) } ?: return MResult.Offline
            val body = r.body()
            if (r.isSuccessful && body != null) MResult.Ok(body) else {
                r.errorBody()?.close()
                MResult.Failed("http_${r.code()}", r.code())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            MResult.Offline
        } catch (e: Exception) {
            MResult.Failed(CODE_INVALID, 0)
        }
    }

    /**
     * A picture the mail's HTML asks for ([MailLogic.interceptPath]), with the app's own sign-in; null when it cannot
     * be had. `img/<token>` answers `{ mime, size, data }` (Base64, at most 700 KB); `cid/<m>/<a>` answers
     * `{ mediaId, mime, size }` and the picture is the plugin's media file. Only raster pictures (never SVG), a few MB.
     */
    suspend fun picture(path: String): Pair<ByteArray, String>? {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        return try {
            withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
                val r = apiClientProvider.getClient(url).pluginGet(MAIL_PLUGIN, path)
                val o = r.body()?.takeIf { r.isSuccessful && !MailJson.isRefusal(it) } ?: run {
                    r.errorBody()?.close()
                    return@withTimeoutOrNull null
                }
                val picture = MailJson.picture(o) ?: return@withTimeoutOrNull null
                val bytes = picture.data ?: picture.mediaId?.let { media ->
                    val m = apiClientProvider.mediaClient(url).pluginMediaGet(MAIL_PLUGIN, media)
                    val body = m.body()
                    if (!m.isSuccessful || body == null) {
                        m.errorBody()?.close()
                        body?.close()
                        null
                    } else {
                        body.use { b -> b.byteStream().use { input -> readAtMost(input, IMAGE_MAX_BYTES) } }
                    }
                } ?: return@withTimeoutOrNull null
                bytes to picture.mime
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** All of [input], null when it is longer than [max]. */
    private fun readAtMost(input: java.io.InputStream, max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    /** A device file → its media id (`PUT …/media`, raw body). */
    suspend fun upload(file: File, mime: String): MResult<String> {
        val type = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()!!
        return call(UPLOAD_TIMEOUT_MS, media = true, request = { pluginMediaUpload(MAIL_PLUGIN, file.asRequestBody(type)) }) { o ->
            o.get("mediaId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { Regex("^[A-Za-z0-9_.~-]{1,200}$").matches(it) }
        }
    }

    // ─── writing ────────────────────────────────────────────────────

    suspend fun createDraft(body: JsonObject): MResult<MDraft> = post("drafts", body, parse = MailJson::draftAnswer)

    suspend fun updateDraft(id: Long, body: JsonObject): MResult<MDraft> = put("drafts/$id", body, MailJson::draftAnswer)

    suspend fun draft(id: Long): MResult<MDraft> = get("drafts/$id", parse = MailJson::draftAnswer)

    suspend fun deleteDraft(id: Long): MResult<Unit> = del("drafts/$id", unit)

    suspend fun send(draftId: Long, sendAt: Long?): MResult<MSend> = post("drafts/$draftId/send", MailJson.sendBody(sendAt), parse = MailJson::send)

    /** Stops a send still waiting: the draft it came from (null when the server names none). */
    suspend fun cancel(outboxId: Long): MResult<Long?> =
        when (val r = post("outbox/$outboxId/cancel", JsonObject()) { o -> listOfNotNull(MailJson.cancelled(o)) }) {
            is MResult.Ok -> MResult.Ok(r.value.firstOrNull())
            else -> r.notOk()
        }

    suspend fun outbox(): MResult<List<MOutboxItem>> = get("outbox", parse = MailJson::outbox)

    suspend fun contacts(q: String): MResult<List<MContact>> = get("contacts", mapOf("q" to q.take(100)), MailJson::contacts)

    // ─── AI ─────────────────────────────────────────────────────────

    suspend fun ai(messageId: Long): MResult<MAi> = get("ai/$messageId", parse = MailJson::ai)

    /** "Kurz gesagt" etc.: the result that is there, or (with [again]) asked anew (at most 60 a person and hour). */
    suspend fun runAi(messageId: Long, again: Boolean = false): MResult<MAi> =
        post("ai/$messageId", JsonObject().apply { if (again) addProperty("again", true) }, timeoutMs = 60_000L, parse = MailJson::ai)

    suspend fun todoToFamily(messageId: Long, index: Int): MResult<String> =
        post("ai/$messageId/todo/${index.coerceAtLeast(0)}", MailJson.todoBody(), parse = MailJson::where)

    // ─── Drive ──────────────────────────────────────────────────────

    suspend fun suggest(messageId: Long, attachmentId: Long): MResult<MSuggest> =
        post("messages/$messageId/attachments/$attachmentId/drive/suggest", JsonObject(), timeoutMs = 45_000L, parse = MailJson::suggest)

    suspend fun saveToDrive(messageId: Long, attachmentId: Long, folderId: Long?, name: String?, remember: Boolean?): MResult<MSavedTo> =
        post("messages/$messageId/attachments/$attachmentId/drive/save", MailJson.driveSaveBody(folderId, name, remember), timeoutMs = 60_000L, parse = MailJson::savedAnswer)

    suspend fun saveAll(messageId: Long): MResult<MSaveAll> = post("messages/$messageId/drive/save-all", JsonObject(), timeoutMs = 90_000L, parse = MailJson::saveAll)

    suspend fun filing(): MResult<List<MFilingItem>> = get("filing", parse = MailJson::filing)

    suspend fun confirmFiling(id: Long, folderId: Long?, name: String?): MResult<MSavedTo> =
        post("filing/$id/confirm", MailJson.driveSaveBody(folderId, name, null), timeoutMs = 60_000L, parse = MailJson::savedAnswer)

    suspend fun dismissFiling(id: Long): MResult<Unit> = post("filing/$id/dismiss", JsonObject(), parse = unit)
}
