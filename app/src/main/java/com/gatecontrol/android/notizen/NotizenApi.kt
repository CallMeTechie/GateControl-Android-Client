package com.gatecontrol.android.notizen

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DVaultKeys
import com.gatecontrol.android.drive.DVaultRecovery
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The API of „Notizen & Aufgaben“ (gatecontrol-notizen 1.0.0 / 1.1.0, README "API") through the core's plugin bridge
 * (`/api/v1/client/plugins/gatecontrol-notizen/api/…`) and GateControl's media routes. The only place that knows the
 * routes: one method per route, answers parsed by [NotizenJson]. Paths are built from fixed segments and server ids
 * only. Never throws (except cancellation).
 */
@Singleton
class NotizenApi @Inject constructor(
    private val setupRepository: SetupRepository,
    private val apiClientProvider: ApiClientProvider,
    private val settingsRepository: SettingsRepository,
) {
    private companion object {
        const val TIMEOUT_MS = 25_000L
        const val UPLOAD_TIMEOUT_MS = 30 * 60_000L
        const val MEDIA_MAX_BYTES = 110L * 1024 * 1024
        val MEDIA_ID = Regex("^[A-Za-z0-9_.~-]{1,200}$")
    }

    suspend fun lang(): String =
        runCatching { settingsRepository.getLocale().first() }.getOrNull()?.takeIf { it == "de" || it == "en" } ?: "de"

    private suspend fun <T> call(
        timeoutMs: Long = TIMEOUT_MS,
        media: Boolean = false,
        request: suspend ApiClient.() -> Response<JsonObject>,
        parse: (JsonObject) -> T?,
    ): NResult<T> {
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return NResult.NotConfigured
        return try {
            val client = if (media) apiClientProvider.mediaClient(url) else apiClientProvider.getClient(url)
            val response = withTimeoutOrNull(timeoutMs) { client.request() } ?: return NResult.Offline
            if (response.isSuccessful) {
                val body = response.body() ?: JsonObject()
                if (NotizenJson.isRefusal(body)) return NResult.Failed(NotizenJson.failureCode(response.code(), body), response.code(), body)
                parse(body)?.let { NResult.Ok(it) } ?: NResult.Failed(NotizenJson.CODE_INVALID, response.code())
            } else {
                val err = runCatching {
                    response.errorBody()?.use { b -> b.string().takeIf { it.length < 65_536 } }
                        ?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
                }.getOrNull()
                NResult.Failed(NotizenJson.failureCode(response.code(), err), response.code(), err)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            NResult.Offline
        } catch (e: Exception) {
            Timber.d("Notizen: call failed (%s)", e.javaClass.simpleName)
            NResult.Failed(NotizenJson.CODE_INVALID, 0)
        }
    }

    private suspend fun q(extra: Map<String, String> = emptyMap()) = extra + ("lang" to lang())

    private suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), parse: (JsonObject) -> T?) =
        call(request = { pluginGet(NOTIZEN_PLUGIN, path, q(query)) }, parse = parse)

    private suspend fun <T> post(path: String, body: JsonObject = JsonObject(), timeoutMs: Long = TIMEOUT_MS, parse: (JsonObject) -> T?) =
        call(timeoutMs = timeoutMs, request = { pluginPost(NOTIZEN_PLUGIN, path, body, q()) }, parse = parse)

    private suspend fun <T> put(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginPut(NOTIZEN_PLUGIN, path, body, q()) }, parse = parse)

    /** PATCH with `null` members kept (they clear a field: due, reminder, repeat …). */
    private suspend fun <T> patch(path: String, body: JsonObject, parse: (JsonObject) -> T?) = call(
        request = {
            val raw = com.google.gson.GsonBuilder().serializeNulls().create().toJson(body)
            pluginPatchRaw(NOTIZEN_PLUGIN, path, raw.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()), q())
        },
        parse = parse,
    )

    private suspend fun <T> del(path: String, parse: (JsonObject) -> T?, query: Map<String, String> = emptyMap()) =
        call(request = { pluginDelete(NOTIZEN_PLUGIN, path, q(query)) }, parse = parse)

    private val unit: (JsonObject) -> Unit = { }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // ── me, nav, views ──────────────────────────────────────

    /** `GET me` with the raw answer (kept for offline). */
    suspend fun me(): NResult<Pair<NMe, JsonObject>> = get("me") { o -> NotizenJson.me(o)?.let { it to o } }

    suspend fun nav(area: String): NResult<Pair<NNav, JsonObject>> = get("nav", mapOf("area" to area)) { o -> NotizenJson.nav(o)?.let { it to o } }

    /** `myday`, `important`, `planned`, `assigned`, `list:<id>`, `source:<plugin>`. */
    suspend fun view(name: String, area: String): NResult<Pair<NView, JsonObject>> {
        if (!Regex("^(myday|important|planned|assigned|list:\\d{1,15}|source:gatecontrol-[a-z]{1,40})$").matches(name)) return NResult.Failed("invalid", 0)
        return get("views/" + enc(name), mapOf("area" to area)) { o -> NotizenJson.view(o)?.let { it to o } }
    }

    suspend fun people(): NResult<List<NPersonPick>> = get("people", parse = NotizenJson::people)

    suspend fun search(text: String, area: String): NResult<NSearchResult> = get("search", mapOf("q" to text.take(200), "area" to area)) { NotizenJson.search(it) }

    suspend fun putPrefs(body: JsonObject): NResult<NPrefs> = put("prefs", body) { o -> NotizenJson.prefs(o.getAsJsonObject("prefs")) }

    suspend fun sources(): NResult<List<NSourceStatus>> = get("sources", parse = NotizenJson::sources)

    suspend fun syncSource(plugin: String): NResult<Boolean> {
        if (!Regex("^gatecontrol-[a-z]{1,40}$").matches(plugin)) return NResult.Failed("invalid", 0)
        return post("sources/$plugin/sync") { o -> o.get("scheduled")?.asBoolean ?: false }
    }

    // ── lists ───────────────────────────────────────────────

    suspend fun createList(name: String, area: String): NResult<NList> =
        post("lists", JsonObject().apply { addProperty("name", name.take(120)); addProperty("area", area) }, parse = NotizenJson::listAnswer)

    /** `POST lists` with a body of its own (plugin 1.1.0: with `clientId`). */
    suspend fun createListBody(body: JsonObject): NResult<NList> = post("lists", body, parse = NotizenJson::listAnswer)

    suspend fun patchList(id: Long, body: JsonObject): NResult<NList> = patch("lists/$id", body, NotizenJson::listAnswer)

    suspend fun deleteList(id: Long): NResult<Unit> = del("lists/$id", unit)

    /**
     * `DELETE lists/<id>`: the owner deletes it, a member leaves it. [tasks] (plugin 1.2.0, `deleteKeep`): `move` (the
     * open tasks go to „Aufgaben“) or `delete`; null: as 1.1 (all of them go).
     */
    suspend fun deleteList(id: Long, tasks: String?): NResult<NDeleted> =
        del("lists/$id", NotizenJson::deleted, tasks?.takeIf { it == "move" || it == "delete" }?.let { mapOf("tasks" to it) }.orEmpty())

    /** `GET lists/<id>/usage` (plugin 1.2.0): what deleting it touches. */
    suspend fun listUsage(id: Long): NResult<NListUsage> = get("lists/$id/usage", parse = NotizenJson::listUsage)

    /** `POST lists/<id>/restore` (plugin 1.2.0): „Rückgängig“ after deleting. */
    suspend fun restoreList(id: Long): NResult<NList> = post("lists/$id/restore", parse = NotizenJson::listAnswer)

    suspend fun listMembers(id: Long, members: List<Long>): NResult<NList> = put("lists/$id/members", NotizenJson.membersBody(members), NotizenJson::listAnswer)

    /** `PUT lists/<id>/members` — everyone who may see it besides the owner; [roles]: with `read` / `write` (1.2.0). */
    suspend fun listMembers(id: Long, members: List<Pair<Long, String>>, roles: Boolean): NResult<NList> =
        put("lists/$id/members", NotizenJson.membersBody(members, roles), NotizenJson::listAnswer)

    suspend fun createGroup(name: String, area: String): NResult<Unit> =
        post("groups", JsonObject().apply { addProperty("name", name.take(120)); addProperty("area", area) }, parse = unit)

    /** `POST groups` → the new group (its id); [lists] go into it (plugin 1.2.0, `groupEdit`). */
    suspend fun createGroup(name: String, area: String, lists: List<Long>): NResult<NGroup> = post("groups", NotizenJson.groupBody(name, area, lists), parse = NotizenJson::groupAnswer)

    suspend fun renameGroup(id: Long, name: String): NResult<Unit> = patch("groups/$id", JsonObject().apply { addProperty("name", name.trim().take(80)) }, unit)

    /** „Gruppe auflösen“: the folder goes, its lists stay. */
    suspend fun deleteGroup(id: Long): NResult<Unit> = del("groups/$id", unit)

    // ── tasks ───────────────────────────────────────────────

    suspend fun createTask(body: JsonObject): NResult<NTask> = post("tasks", body, parse = NotizenJson::taskAnswer)

    suspend fun task(id: Long): NResult<Pair<NTaskDetail, JsonObject>> = get("tasks/$id") { o -> NotizenJson.taskDetail(o)?.let { it to o } }

    suspend fun patchTask(id: Long, body: JsonObject): NResult<NTaskDetail> = patch("tasks/$id", body, NotizenJson::taskDetail)

    suspend fun completeTask(id: Long, done: Boolean): NResult<Pair<NTask, NTask?>> = post("tasks/$id/complete", NotizenJson.doneBody(done), parse = NotizenJson::completeAnswer)

    /** `POST tasks/<id>/complete { done, userId }` (plugin 1.6.0, `familyTasks`): a parent ticks off for a member of the family. */
    suspend fun completeTaskFor(id: Long, done: Boolean, userId: Long?): NResult<Pair<NTask, NTask?>> =
        post("tasks/$id/complete", NotizenJson.completeBody(done, userId), parse = NotizenJson::completeAnswer)

    /** `POST family/seen` (plugin 1.6.0): the note „Umzug abgeschlossen“ was read. */
    suspend fun familySeen(): NResult<Unit> = post("family/seen", parse = unit)

    suspend fun deleteTask(id: Long): NResult<Unit> = del("tasks/$id", unit)

    // ── reminders and „Gestern offen geblieben“ (plugin 1.3.0; an older plugin answers 404) ──

    /** `POST tasks/<id>/snooze` („Später“) → whether it moved; with [tag] only while the task still has that reminder. */
    suspend fun snoozeTask(id: Long, until: String, tag: String?): NResult<Boolean> =
        post("tasks/$id/snooze", NotizenJson.snoozeBody(until, tag), parse = NotizenJson::snoozeAnswer)

    /** `POST tasks/<id>/snooze` with a body of its own (the outbox). */
    suspend fun snoozeTaskBody(id: Long, body: JsonObject): NResult<Boolean> = post("tasks/$id/snooze", body, parse = NotizenJson::snoozeAnswer)

    /** `POST tasks/<id>/move { to }`: the due day goes there (`today`, `tomorrow`, `weekend`, a date). */
    suspend fun moveTask(id: Long, body: JsonObject): NResult<NTask> = post("tasks/$id/move", body, parse = NotizenJson::taskAnswer)

    /** `GET yesterday` — [area] null: both areas. */
    suspend fun yesterday(area: String?): NResult<NYesterday> = get("yesterday", area?.let { mapOf("area" to it) }.orEmpty()) { NotizenJson.yesterday(it) }

    /** `POST yesterday`: all of the card (or [ids]) done, to today, or moved. */
    suspend fun postYesterday(action: String, to: String? = null, ids: List<Long>? = null, area: String? = null): NResult<NYesterdayResult> =
        post("yesterday", NotizenJson.yesterdayBody(action, to, ids, area)) { NotizenJson.yesterdayResult(it) }

    /** `GET reminders`: what the server would remind me of in the next 30 days (for the phone's own alarms). */
    suspend fun reminders(): NResult<NReminderFeed> = get("reminders", parse = NotizenJson::reminders)

    // ── shopping lists (plugin 1.4.0, `shoppingLists`) ──────

    /** `GET shopping[?since=<serverTime>]`: every list I see; with [since] the entries changed since, with tombstones. */
    suspend fun shopping(since: String?): NResult<com.gatecontrol.android.familie.shopping.ShoppingSnapshot> =
        get("shopping", if (since != null) mapOf("since" to since) else emptyMap()) { com.gatecontrol.android.familie.FamilieJson.shopping(it) }

    /** `POST shopping/ops { ops }`: at most 200, in order, safe to send again. */
    suspend fun shoppingOps(ops: List<com.gatecontrol.android.familie.shopping.ShoppingOp>): NResult<com.gatecontrol.android.familie.FamilieJson.OpsResult> =
        post("shopping/ops", com.gatecontrol.android.familie.FamilieJson.opsBody(ops)) { com.gatecontrol.android.familie.FamilieJson.opsResult(it) }

    /** „Ich gehe einkaufen“ for the family list [uid] (`familyShopping`). */
    suspend fun shoppingGoing(uid: String): NResult<Unit> {
        val id = NotizenJson.listUid(uid) ?: return NResult.Failed("invalid", 0)
        return post("shopping/lists/$id/going", parse = unit)
    }

    /** „Kannst du einkaufen gehen?“: asks [userId] (null: nobody any more) for the family list [uid]; → `notified` as the Familie plugin says it. */
    suspend fun shoppingAssign(uid: String, userId: Int?, dueAt: String?, notify: String): NResult<NDelivery> {
        val id = NotizenJson.listUid(uid) ?: return NResult.Failed("invalid", 0)
        return post("shopping/lists/$id/assign", NotizenJson.shoppingAssignBody(userId, dueAt, notify), parse = NotizenJson::delivery)
    }

    /** `POST parse { text, area }` → the list the server suggests for the line (`taskList`); null inside: none. */
    suspend fun parseAttach(text: String, area: String): NResult<NParseAttach> =
        post("parse", NotizenJson.parseBody(text, area)) { o -> NParseAttach(NotizenJson.attach(o.get("attach")?.takeIf { it.isJsonObject }?.asJsonObject)) }

    // ── places (plugin 1.5.0, `places`; an older plugin answers 404 — the app does not call these without the word) ──

    /** `POST parse { text, area }` → the list (`taskList`) and the place (`places`) the server suggests for the line. */
    suspend fun parse(text: String, area: String, listId: Long? = null): NResult<NParsed> = post("parse", NotizenJson.parseBody(text, area, listId)) { NotizenPlacesJson.parsed(it) }

    /** `GET places`: „Meine Orte“, the places of the family, the remembered place per kind. */
    suspend fun places(): NResult<NPlaces> = get("places") { NotizenPlacesJson.places(it) }

    suspend fun createPlace(body: JsonObject): NResult<NSpot> = post("places", body, parse = NotizenPlacesJson::placeAnswer)

    suspend fun patchPlace(id: Long, body: JsonObject): NResult<NSpot> = patch("places/$id", body, NotizenPlacesJson::placeAnswer)

    /** Tasks that hang on the place keep it. */
    suspend fun deletePlace(id: Long): NResult<Unit> = del("places/$id", unit)

    /** `PUT places/favs { key, placeId | null }`: remember / no longer remember → the favourites. */
    suspend fun placeFav(key: String, placeId: Long?): NResult<List<NFav>> = put("places/favs", NotizenPlacesJson.favBody(key, placeId)) { NotizenPlacesJson.favs(it) }

    /**
     * `POST places/search` (POST: the position is in no URL). [near] must be rounded already ([PlaceLogic.near]) — the
     * only place search the app knows: it never asks a map service itself. The server queues its requests to the map
     * service, so this may take some seconds.
     */
    suspend fun placeSearch(key: String?, q: String?, near: Pair<Double, Double>?, nearSpot: NSpot? = null): NResult<NPlaceSearch> =
        post("places/search", NotizenPlacesJson.searchBody(key, q, near, nearSpot), timeoutMs = 40_000L) { NotizenPlacesJson.search(it) }

    /** `POST places/watch { near?, budget }` → the places this phone is to watch, with the raw answer (kept for offline). */
    suspend fun placesWatch(near: Pair<Double, Double>?, budget: Int): NResult<Pair<NWatch, JsonObject>> =
        post("places/watch", NotizenPlacesJson.watchBody(near, budget), timeoutMs = 40_000L) { o -> NotizenPlacesJson.watch(o) to o }

    /** `POST places/fired` — carries no position: only "this place fired for this task". */
    suspend fun placeFired(taskId: Long, fence: String, transition: String, timeoutMs: Long): NResult<NFired> =
        post("places/fired", NotizenPlacesJson.firedBody(taskId, fence, transition), timeoutMs = timeoutMs) { NotizenPlacesJson.fired(it) }

    /** `POST tasks/<id>/place/<later|skip>` with a body of its own (also from the outbox). */
    suspend fun placeAction(id: Long, what: String, body: JsonObject): NResult<Unit> {
        if (what != NotizenOutbox.PLACE_LATER && what != NotizenOutbox.PLACE_SKIP) return NResult.Failed("invalid", 0)
        return post("tasks/$id/$what", body, parse = unit)
    }

    /** `POST tasks/<id>/place/consent { accept }` → `accepted` | `declined`. */
    suspend fun placeConsent(id: Long, accept: Boolean): NResult<String> =
        post("tasks/$id/place/consent", NotizenPlacesJson.consentBody(accept)) { o -> o.get("consent")?.takeIf { it.isJsonPrimitive }?.asString }

    suspend fun item(id: Long): NResult<Pair<NItem, Long?>> = get("items/$id", parse = NotizenJson::itemAnswer)

    suspend fun completeItem(id: Long, done: Boolean): NResult<NItem> = post("items/$id/complete", NotizenJson.doneBody(done)) { o -> NotizenJson.itemAnswer(o)?.first }

    /** The item gets a task of its own (sub tasks, note, reminder). */
    suspend fun carryItem(id: Long): NResult<NTaskDetail> = post("items/$id/carry", parse = NotizenJson::taskDetail)

    // ── notebooks, notes ────────────────────────────────────

    suspend fun createNotebook(name: String, area: String, color: String?): NResult<NNotebook> = post(
        "notebooks",
        JsonObject().apply { addProperty("name", name.take(80)); addProperty("area", area); color?.let { addProperty("color", it) } },
        parse = NotizenJson::notebookAnswer,
    )

    /** `POST notebooks` with a body of its own (plugin 1.1.0: with `clientId`). */
    suspend fun createNotebookBody(body: JsonObject): NResult<NNotebook> = post("notebooks", body, parse = NotizenJson::notebookAnswer)

    suspend fun patchNotebook(id: Long, body: JsonObject): NResult<NNotebook> = patch("notebooks/$id", body, NotizenJson::notebookAnswer)

    suspend fun deleteNotebook(id: Long): NResult<Unit> = del("notebooks/$id", unit)

    /** `DELETE notebooks/<id>`; [notes] (plugin 1.2.0): `move` (to „Notizen“) or `delete`. A member leaves it. */
    suspend fun deleteNotebook(id: Long, notes: String?): NResult<NDeleted> =
        del("notebooks/$id", NotizenJson::deleted, notes?.takeIf { it == "move" || it == "delete" }?.let { mapOf("notes" to it) }.orEmpty())

    suspend fun notebookUsage(id: Long): NResult<NNotebookUsage> = get("notebooks/$id/usage", parse = NotizenJson::notebookUsage)

    suspend fun restoreNotebook(id: Long): NResult<NNotebook> = post("notebooks/$id/restore", parse = NotizenJson::notebookAnswer)

    suspend fun notebookMembers(id: Long, members: List<Pair<Long, String>>, roles: Boolean): NResult<NNotebook> =
        put("notebooks/$id/members", NotizenJson.membersBody(members, roles), NotizenJson::notebookAnswer)

    suspend fun notes(area: String, notebookId: Long? = null, tag: String? = null, pinned: Boolean = false, before: String? = null): NResult<Pair<NNotesPage, JsonObject>> {
        val query = buildMap {
            put("area", area)
            notebookId?.let { put("notebook", it.toString()) }
            tag?.let { put("tag", it.take(40)) }
            if (pinned) put("pinned", "1")
            before?.let { put("before", it) }
            put("limit", "50")
        }
        return get("notes", query) { o -> NotizenJson.notesPage(o)?.let { it to o } }
    }

    suspend fun createNote(body: JsonObject): NResult<NNote> = post("notes", body, parse = NotizenJson::note)

    suspend fun note(id: Long): NResult<Pair<NNote, JsonObject>> = get("notes/$id") { o -> NotizenJson.note(o)?.let { it to o } }

    /** `PATCH notes/<id>`; with [NNoteSummary.rev] a change made elsewhere meanwhile is refused (`409 conflict`). */
    suspend fun patchNote(id: Long, body: JsonObject): NResult<NNote> = patch("notes/$id", body, NotizenJson::note)

    suspend fun deleteNote(id: Long): NResult<Unit> = del("notes/$id", unit)

    suspend fun checkLine(id: Long, line: Int, done: Boolean): NResult<NNote> =
        post("notes/$id/check", JsonObject().apply { addProperty("line", line); addProperty("done", done) }, parse = NotizenJson::note)

    suspend fun lineToTask(id: Long, line: Int, myDay: Boolean = false): NResult<NTask> =
        post("notes/$id/task", JsonObject().apply { addProperty("line", line); if (myDay) addProperty("myDay", true) }, parse = NotizenJson::taskAnswer)

    // ── attachments ─────────────────────────────────────────

    /** Bytes → a media id (`PUT …/media`, raw body). */
    suspend fun upload(bytes: ByteArray, mime: String): NResult<String> {
        val type = mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()!!
        return call(UPLOAD_TIMEOUT_MS, media = true, request = { pluginMediaUpload(NOTIZEN_PLUGIN, bytes.toRequestBody(type)) }) { o ->
            o.get("mediaId")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { MEDIA_ID.matches(it) }
        }
    }

    suspend fun attach(body: JsonObject): NResult<NAttachment> = post("attachments", body) { o -> NotizenJson.attachment(o.getAsJsonObject("attachment")) }

    suspend fun attachment(id: Long): NResult<NAttachment> = get("attachments/$id") { o -> NotizenJson.attachment(o.getAsJsonObject("attachment")) }

    suspend fun deleteAttachment(id: Long): NResult<Unit> = del("attachments/$id", unit)

    /** The text this phone recognised in a picture (ML Kit), for the search. */
    suspend fun attachmentText(id: Long, text: String): NResult<Unit> = put("attachments/$id/text", JsonObject().apply { addProperty("text", text.take(20_000)) }, unit)

    /** A signed `/plugin-media/…` path of an attachment → its bytes (at most ~110 MB); null when it cannot be had. */
    suspend fun download(path: String): ByteArray? {
        if (!path.startsWith("/plugin-media/") || path.contains("..")) return null
        val url = setupRepository.getServerUrl()
        if (url.isBlank() || !setupRepository.isConfigured()) return null
        return try {
            withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                val r = apiClientProvider.mediaClient(url).getRaw(path.removePrefix("/"))
                val body = r.body()
                if (!r.isSuccessful || body == null) {
                    r.errorBody()?.close()
                    body?.close()
                    return@withTimeoutOrNull null
                }
                body.use { b ->
                    if (b.contentLength() > MEDIA_MAX_BYTES) return@use null
                    b.byteStream().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MEDIA_MAX_BYTES) return@use null
                        }
                        out.toByteArray()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ── Tresor notebooks (Drive's vault routes) ─────────────

    suspend fun createVault(body: JsonObject): NResult<Pair<NVault, NNotebook?>> =
        post("vaults", body) { o -> NotizenJson.vaultAnswer(o)?.let { it to NotizenJson.notebookAnswer(o) } }

    suspend fun vault(id: String): NResult<NVault> = get("vaults/$id", parse = NotizenJson::vaultAnswer)

    suspend fun vaultKeys(id: String, deviceId: String): NResult<DVaultKeys> = get("vaults/$id/keys", mapOf("deviceId" to deviceId), DriveJson::vaultKeys)

    suspend fun vaultRecovery(id: String): NResult<DVaultRecovery> = get("vaults/$id/recovery") { DriveJson.vaultRecovery(it) }

    suspend fun vaultSetRecovery(id: String, body: JsonObject): NResult<NVault> = put("vaults/$id/recovery", body, NotizenJson::vaultAnswer)

    suspend fun vaultDevices(id: String): NResult<List<DVaultDevice>> = get("vaults/$id/devices", parse = DriveJson::vaultDevices)

    suspend fun vaultAsk(id: String, device: JsonObject): NResult<DVaultDevice> = post("vaults/$id/devices", device, parse = DriveJson::vaultDeviceAnswer)

    suspend fun vaultApprove(id: String, deviceId: String, wraps: List<DVaultWrap>, recoveryAuth: String? = null): NResult<DVaultDevice> =
        post("vaults/$id/devices/${enc(deviceId)}/approve", DriveJson.approveBody(wraps, recoveryAuth), parse = DriveJson::vaultDeviceAnswer)

    suspend fun vaultRevoke(id: String, deviceId: String): NResult<DVaultDevice> = del("vaults/$id/devices/${enc(deviceId)}", DriveJson::vaultDeviceAnswer)

    suspend fun vaultRotate(id: String, body: JsonObject): NResult<NVault> = post("vaults/$id/rotate", body, parse = NotizenJson::vaultAnswer)

    // ── moving over ─────────────────────────────────────────

    suspend fun msAccounts(): NResult<NMsAccounts> = get("import/mstodo/accounts") { NotizenJson.msAccounts(it) }

    suspend fun msConsent(accountId: Long, deviceId: String? = null): NResult<NMsConsent> = post(
        "import/mstodo/consent",
        JsonObject().apply { addProperty("accountId", accountId); deviceId?.let { addProperty("deviceId", it) } },
        timeoutMs = 45_000L,
    ) { NotizenJson.msConsent(it) }

    suspend fun msPreview(accountId: Long): NResult<NMsPreview> = call(timeoutMs = 120_000L, request = {
        pluginGet(NOTIZEN_PLUGIN, "import/mstodo/preview", q(mapOf("accountId" to accountId.toString())))
    }, parse = NotizenJson::msPreview)

    suspend fun msStart(body: JsonObject): NResult<NJob> = post("import/mstodo/start", body, parse = NotizenJson::job)

    suspend fun msLinks(): NResult<List<NMsLink>> = get("import/mstodo/links") { NotizenJson.msLinks(it) }

    suspend fun msUnlink(id: Long): NResult<Unit> = del("import/mstodo/links/$id", unit)

    suspend fun job(id: Long): NResult<NJob> = get("import/jobs/$id", parse = NotizenJson::job)

    suspend fun dsStart(file: String, notebooks: Int, notes: Int, bytes: Long): NResult<NJob> = post(
        "import/dsnote/start",
        JsonObject().apply { addProperty("file", file.take(200)); addProperty("notebooks", notebooks); addProperty("notes", notes); addProperty("bytes", bytes) },
        parse = NotizenJson::job,
    )

    suspend fun dsNotebook(job: Long, ref: String, name: String, area: String): NResult<Long> = post(
        "import/dsnote/$job/notebook",
        JsonObject().apply { addProperty("ref", ref); addProperty("name", name.take(80)); addProperty("area", area) },
    ) { o -> o.get("notebookId")?.takeIf { it.isJsonPrimitive }?.asLong }

    /** → imported, skipped. */
    suspend fun dsNotes(job: Long, body: JsonObject): NResult<Pair<Int, Int>> = post("import/dsnote/$job/notes", body, timeoutMs = 60_000L) { o ->
        (o.get("imported")?.asInt ?: 0) to (o.get("skipped")?.asInt ?: 0)
    }

    suspend fun dsFinish(job: Long, skipped: Int, protectedSkipped: Int): NResult<NJob> = post(
        "import/dsnote/$job/finish",
        JsonObject().apply { addProperty("skipped", skipped); addProperty("protectedSkipped", protectedSkipped) },
        parse = NotizenJson::job,
    )
}
