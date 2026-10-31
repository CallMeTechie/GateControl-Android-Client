package com.gatecontrol.android.mail

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.GsonBuilder
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        private const val AVATAR_MAX_BYTES = 2L * 1024 * 1024
        /** Keeps `null` members (a PATCH that clears a field); Retrofit's Gson leaves them out. */
        private val NULLS_GSON = GsonBuilder().serializeNulls().create()
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OVERLAY_ID = Regex("^(familie|birthdays|alltag:[0-9]{1,15})$")
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
     * A picture the mail's HTML asks for ([MailLogic.interceptPath]), with the app's own sign-in. `img/<token>` answers
     * `{ mime, size, data }` (Base64, at most 700 KB); `cid/<m>/<a>` answers `{ mediaId, mime, size }` and the picture
     * is the plugin's media file, or `409 busy { reason: 'loading', retryAfter }` while the provider still sends it
     * ([MPicture.Busy]). Only raster pictures (never SVG), a few MB.
     */
    suspend fun picture(path: String): MPicture {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return MPicture.None
        return try {
            withTimeoutOrNull(IMAGE_TIMEOUT_MS) {
                val r = apiClientProvider.getClient(url).pluginGet(MAIL_PLUGIN, path)
                val o = r.body()?.takeIf { r.isSuccessful && !MailJson.isRefusal(it) } ?: run {
                    val err = if (r.isSuccessful) r.body() else errorJson(r)
                    val code = MailJson.failureCode(r.code(), err)
                    return@withTimeoutOrNull MailJson.busyRetryAfter(code, err)?.let { MPicture.Busy(it) } ?: MPicture.None
                }
                val picture = MailJson.picture(o) ?: return@withTimeoutOrNull MPicture.None
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
                } ?: return@withTimeoutOrNull MPicture.None
                MPicture.Got(bytes, picture.mime)
            } ?: MPicture.None
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MPicture.None
        }
    }

    /** The account's picture ([MAccount.avatar]): the plugin's media file, at most [AVATAR_MAX_BYTES]; null when not had. */
    suspend fun avatar(mediaId: String): ByteArray? = when (val r = media(mediaId)) {
        is MResult.Ok -> runCatching { r.value.use { b -> b.byteStream().use { readAtMost(it, AVATAR_MAX_BYTES) } } }.getOrNull()
        else -> null
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

    // ─── calendars (since Mail 1.2.0) ───────────────────────────────

    /** A PATCH whose body keeps its `null` members (clearing a field: a rule, the online link, a birthday). */
    private suspend fun <T> patchNulls(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = {
            pluginPatchRaw(MAIL_PLUGIN, path, NULLS_GSON.toJson(body).toRequestBody(JSON_TYPE), langQuery())
        }, parse = parse)

    /** `GET calendars` with the raw answer (kept for offline). */
    suspend fun calendars(): MResult<Pair<MCalendars, JsonObject>> = get("calendars") { o -> MailPimJson.calendars(o)?.let { it to o } }

    suspend fun createCalendar(name: String, color: String?): MResult<MCalendar> =
        post("calendars", MailPimJson.calendarBody(name, color), parse = MailPimJson::calendarAnswer)

    suspend fun patchCalendar(id: Long, body: JsonObject): MResult<MCalendar> = patch("calendars/$id", body, parse = MailPimJson::calendarAnswer)

    suspend fun deleteCalendar(id: Long): MResult<Unit> = del("calendars/$id", unit)

    suspend fun patchOverlay(id: String, body: JsonObject): MResult<MOverlay> = patch("overlays/" + id.takeIf { OVERLAY_ID.matches(it) }.orEmpty(), body, parse = MailPimJson::overlayAnswer)

    suspend fun subscribeCalendar(url: String, name: String?): MResult<MCalendar> =
        post("calendars/subscribe", MailPimJson.subscribeBody(url, name), timeoutMs = 45_000L, parse = MailPimJson::calendarAnswer)

    suspend fun calendarSettings(body: JsonObject): MResult<MCalPrefs> = put("calendar/settings", body, MailPimJson::prefsAnswer)

    /** `GET events` in `[from, to)` (≤ 62 days) of [calendars] (ids and overlay ids; null: all visible). */
    suspend fun events(from: Long, to: Long, calendars: List<String>? = null): MResult<Pair<MEvents, JsonObject>> =
        get("events", buildMap {
            put("from", from.toString())
            put("to", to.toString())
            calendars?.let { put("calendars", it.joinToString(",")) }
        }) { o -> MailPimJson.events(o)?.let { it to o } }

    suspend fun event(id: Long, occurrence: String?): MResult<Pair<MEvent, JsonObject>> =
        get("events/$id", occurrence?.let { mapOf("occurrence" to it) }.orEmpty()) { o -> MailPimJson.event(o)?.let { it to o } }

    suspend fun createEvent(body: JsonObject): MResult<MEventSaved> = post("events", body, timeoutMs = 60_000L, parse = MailPimJson::eventSaved)

    /** `PATCH events/<id>`: `409 conflict { current }` when [body]'s etag is old ([MailPimJson.conflictEvent]). */
    suspend fun updateEvent(id: Long, body: JsonObject): MResult<MEventSaved> = patchNulls("events/$id", body, MailPimJson::eventSaved)

    suspend fun deleteEvent(id: Long, scope: String, occurrence: String?, notify: Boolean, comment: String?): MResult<Unit> =
        call(request = {
            pluginDelete(MAIL_PLUGIN, "events/$id", buildMap {
                put("scope", scope)
                occurrence?.let { put("occurrence", it) }
                if (notify) put("notify", "1")
                comment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("comment", it.take(1000)) }
            } + langQuery())
        }, parse = unit)

    suspend fun respondEvent(id: Long, body: JsonObject): MResult<MEvent> = post("events/$id/respond", body, timeoutMs = 60_000L, parse = MailPimJson::eventAnswer)

    suspend fun invitations(): MResult<List<MInvitation>> = get("invitations", mapOf("state" to "open"), MailPimJson::invitations)

    suspend fun invitation(id: Long): MResult<MInvitation> = get("invitations/$id", parse = MailPimJson::invitation)

    suspend fun respondInvitation(id: Long, body: JsonObject): MResult<MInviteAnswer> =
        post("invitations/$id/respond", body, timeoutMs = 60_000L, parse = MailPimJson::inviteAnswer)

    suspend fun applyInvitation(id: Long): MResult<MInviteAnswer> = post("invitations/$id/apply", JsonObject(), timeoutMs = 60_000L, parse = MailPimJson::inviteAnswer)

    suspend fun dismissInvitation(id: Long, decline: Boolean): MResult<Unit> = post("invitations/$id/dismiss", MailPimJson.dismissBody(decline), timeoutMs = 60_000L, parse = unit)

    // ─── contacts (since Mail 1.2.0, path `cards`) ──────────────────

    suspend fun addressbooks(): MResult<Pair<MBooks, JsonObject>> = get("addressbooks") { o -> MailPimJson.books(o)?.let { it to o } }

    suspend fun patchAddressbook(id: Long, visible: Boolean): MResult<MAddressbook> = patch("addressbooks/$id", MailPimJson.bookPatch(visible), parse = MailPimJson::bookAnswer)

    /** `GET cards`: a page of the list ([since]: only what changed, for the offline copy). */
    suspend fun cards(q: String? = null, book: Long? = null, group: Long? = null, cursor: String? = null, since: Long? = null, limit: Int = 200): MResult<Pair<MCardPage, JsonObject>> =
        get("cards", buildMap {
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { put("q", it.take(100)) }
            put("book", book?.toString() ?: "all")
            group?.let { put("group", it.toString()) }
            cursor?.let { put("cursor", it) }
            since?.let { put("since", it.toString()) }
            put("limit", limit.coerceIn(1, 1000).toString())
        }) { o -> MailPimJson.cardPage(o)?.let { it to o } }

    suspend fun card(id: Long): MResult<Pair<MCard, JsonObject>> = get("cards/$id") { o -> MailPimJson.fullCard(o)?.let { it to o } }

    suspend fun createCard(body: JsonObject): MResult<MCard> = post("cards", body, parse = MailPimJson::cardAnswer)

    /** `PATCH cards/<id>`: `409 conflict { current }` ([MailPimJson.conflictCard]), `409 read_only`. */
    suspend fun patchCard(id: Long, body: JsonObject): MResult<MCard> = patchNulls("cards/$id", body, MailPimJson::cardAnswer)

    suspend fun deleteCard(id: Long): MResult<Unit> = del("cards/$id", unit)

    suspend fun cardPhoto(id: Long): MResult<MPhoto> = get("cards/$id/photo", parse = MailPimJson::photo)

    suspend fun putCardPhoto(id: Long, mime: String, bytes: ByteArray): MResult<MCard> = put("cards/$id/photo", MailPimJson.photoBody(mime, bytes), MailPimJson::cardAnswer)

    suspend fun deleteCardPhoto(id: Long): MResult<MCard> = del("cards/$id/photo", MailPimJson::cardAnswer)

    suspend fun lookup(email: String): MResult<MLookup> = get("cards/lookup", mapOf("email" to email.trim().take(320)), MailPimJson::lookup)

    suspend fun activity(id: Long): MResult<MActivity> = get("cards/$id/activity", parse = MailPimJson::activity)

    suspend fun cardDraft(messageId: Long?, email: String): MResult<MCardDraft> =
        get("cards/draft", buildMap {
            messageId?.let { put("messageId", it.toString()) }
            put("email", email.trim().take(320))
        }, MailPimJson::cardDraft)

    suspend fun duplicates(): MResult<List<MDuplicate>> = get("cards/duplicates", parse = MailPimJson::duplicates)

    suspend fun merge(body: JsonObject): MResult<MCard> = post("cards/merge", body, timeoutMs = 60_000L, parse = MailPimJson::cardAnswer)

    suspend fun ignoreDuplicates(ids: List<Long>): MResult<Unit> = post("cards/duplicates/ignore", MailJson.idsBody(ids), parse = unit)

    suspend fun importVcf(vcf: String, addressbookId: Long?): MResult<MImport> =
        post("cards/import", MailPimJson.importBody(vcf, addressbookId), timeoutMs = 120_000L, parse = MailPimJson::importAnswer)

    suspend fun exportVcf(book: Long? = null, group: Long? = null): MResult<MExport> =
        get("cards/export", buildMap {
            put("book", book?.toString() ?: "all")
            group?.let { put("group", it.toString()) }
        }, MailPimJson::export)

    suspend fun createGroup(name: String): MResult<MGroup> = post("groups", MailPimJson.groupBody(name), parse = MailPimJson::groupAnswer)

    suspend fun renameGroup(id: Long, name: String): MResult<MGroup> = patch("groups/$id", MailPimJson.groupBody(name), parse = MailPimJson::groupAnswer)

    suspend fun deleteGroup(id: Long): MResult<Unit> = del("groups/$id", unit)

    suspend fun group(id: Long): MResult<MGroupDetail> = get("groups/$id", parse = MailPimJson::groupDetail)

    suspend fun groupMembers(id: Long, body: JsonObject): MResult<MGroupDetail> = post("groups/$id/members", body, parse = MailPimJson::groupDetail)
}
