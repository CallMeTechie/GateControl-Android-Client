package com.gatecontrol.android.drive

import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.ApiClientProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
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
        /** At most this many ids per album call (plugin README). */
        private const val ALBUM_IDS_MAX = 500
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
                if (DriveJson.isRefusal(body)) return DResult.Failed(DriveJson.errorCode(body) ?: "failed", response.code(), body).also(::seen)
                parse(body)?.let { DResult.Ok(it) } ?: DResult.Failed(CODE_INVALID, response.code())
            } else {
                val err = errorJson(response)
                DResult.Failed(DriveJson.failureCode(response.code(), err), response.code(), err).also(::seen)
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

    private val _locked = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A write was refused with `423 device_locked`: Drive stopped this device (ransomware protection, Drive 1.6). */
    val locked: SharedFlow<Unit> = _locked.asSharedFlow()

    private fun seen(f: DResult.Failed) {
        if (f.code == DriveJson.DEVICE_LOCKED) _locked.tryEmit(Unit)
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

    private suspend fun <T> delWithBody(path: String, body: JsonObject, parse: (JsonObject) -> T?) =
        call(request = { pluginDeleteWithBody(DRIVE_PLUGIN, path, body, lang()) }, parse = parse)

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
        // Drive 1.11: a vault folder is opened from the menu only, never from a list (an older Drive still lists them)
        return get("nodes/$id/children", q) { DriveJson.folder(head.value, it)?.let(DriveLogic::withoutVaults) }
    }

    /**
     * The subfolders of [id] for the menu's tree, by name, never a vault folder. With [apart] (Drive 1.11
     * `vaultsApart`) the server sends folders only, each with `hasDirs`; else the folders of the usual list.
     */
    suspend fun dirs(id: Long, apart: Boolean, limit: Int = 500): DResult<List<DTreeDir>> {
        val q = buildMap {
            put("sort", "name")
            put("order", "asc")
            put("limit", limit.toString())
            if (apart) put("dirs", "1")
        }
        return get("nodes/$id/children", q, DriveJson::treeDirs)
    }

    /** A new folder; a taken name gets a free one ([rename]) or `409 exists`. */
    suspend fun createFolder(parentId: Long, name: String, rename: Boolean = true): DResult<DNode> =
        post("nodes", DriveJson.mkdirBody(parentId, name, rename), DriveJson::nodeAnswer)

    suspend fun rename(id: Long, name: String): DResult<DNode> = patch("nodes/$id", DriveJson.patchBody(name, null), DriveJson::nodeAnswer)

    suspend fun move(id: Long, parentId: Long): DResult<DNode> = patch("nodes/$id", DriveJson.patchBody(null, parentId), DriveJson::nodeAnswer)

    /** Moved under another name (keep both, without Drive 1.12). */
    suspend fun renameAndMove(id: Long, name: String, parentId: Long): DResult<DNode> =
        patch("nodes/$id", DriveJson.patchBody(name, parentId), DriveJson::nodeAnswer)

    /** Into the trash. */
    /**
     * To the trash. [everywhere]: the user's other devices also remove their copies when next online (Drive 1.2+;
     * an older Drive ignores it and just trashes).
     */
    suspend fun delete(id: Long, everywhere: Boolean = false): DResult<Unit> =
        del("nodes/$id", unit, if (everywhere) mapOf("everywhere" to "1") else emptyMap())

    /**
     * "Deleted on this device" (Drive 1.5 `deleteAfter`): the file goes into the trash in [seconds], unless it comes
     * back before ([unmark]). A node marked already keeps its earlier time. [device] names the asking device.
     */
    suspend fun deleteAfter(id: Long, seconds: Long, device: String?): DResult<DMark> {
        val q = buildMap {
            put("after", seconds.coerceIn(1, 365L * 86_400).toString())
            device?.takeIf { DriveJson.DEVICE_ID.matches(it) }?.let { put("device", it) }
        }
        return del("nodes/$id", DriveJson::mark, q)
    }

    /** Ends the mark of [deleteAfter]: the file stays (`PATCH nodes/<id> { deleteAt: null }`). */
    suspend fun unmark(id: Long): DResult<Unit> {
        val body = DriveJson.UNMARK_BODY.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        return call(request = { pluginPatchRaw(DRIVE_PLUGIN, "nodes/$id", body, lang()) }, parse = unit)
    }

    /** Files deleted "everywhere" on another device that this one still has to remove (Drive 1.2+). */
    suspend fun wipes(): DResult<List<DWipe>> = get("wipes", parse = DriveJson::wipes)

    /** This device has removed (or never had) these files. */
    suspend fun wipesDone(ids: List<Long>): DResult<Unit> = post("wipes/done", DriveJson.wipesDoneBody(ids), unit)

    suspend fun search(q: String, area: String? = null): DResult<List<DNode>> {
        val query = buildMap {
            put("q", q)
            if (area != null) put("area", DriveJson.areaId(area) ?: return bad())
        }
        return get("search", query) { o -> DriveJson.items(o)?.let(DriveLogic::withoutVaults) }
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

    // ─── time travel (Drive 1.7, feature `timeTravel`) ──────────────

    /** What the folder [folderId] back at [at] (ISO 8601) would mean; changes nothing. */
    suspend fun timeTravelPreview(folderId: Long, at: String): DResult<DTimeTravelPreview> =
        get("nodes/$folderId/timetravel", mapOf("at" to at), DriveJson::timeTravelPreview)

    /** The folder [folderId] back at [at]: nothing is deleted for good, new files go into the trash. */
    suspend fun timeTravel(folderId: Long, at: String): DResult<DTimeTravelResult> =
        post("nodes/$folderId/timetravel", DriveJson.timeTravelBody(at), DriveJson::timeTravelResult)

    // ─── photos and albums (Drive 1.7, features `photos`, `albums`, `frame`) ──

    /**
     * Photos and videos, newest first; [before] is the cursor of the page before. Without [area]: all I can read.
     * [camera]: only the camera uploads (Drive 1.10, feature `photosFolder`).
     */
    suspend fun photos(area: String? = null, before: String? = null, limit: Int = 120, camera: Boolean = false): DResult<DPhotos> =
        get("photos", DriveJson.photosQuery(area, before, limit, camera) ?: return bad()) { o -> DriveJson.photos(o)?.let { it.copy(items = DriveLogic.withoutVaults(it.items)) } }

    suspend fun photoMonths(area: String? = null, camera: Boolean = false): DResult<List<DMonth>> =
        get("photos/months", DriveJson.photosQuery(area, camera = camera) ?: return bad(), DriveJson::months)

    /** Photos of this calendar day ([date] `YYYY-MM-DD`, else the server's today) in earlier years. */
    suspend fun memories(date: String? = null, camera: Boolean = false): DResult<List<DMemories>> =
        get("photos/memories", DriveJson.photosQuery(camera = camera, date = date) ?: return bad()) { o ->
            DriveJson.memories(o)?.map { it.copy(items = DriveLogic.withoutVaults(it.items)) }
        }

    /**
     * The camera uploads' folder ("Meine Dateien › Bilder › Kamera-Uploads"), made when it is missing (`POST photos/camera`,
     * Drive 1.10, feature `photosFolder`).
     */
    suspend fun cameraFolder(): DResult<DCameraFolder> = post("photos/camera", JsonObject(), DriveJson::cameraFolder)

    suspend fun albums(): DResult<List<DAlbum>> = get("albums", parse = DriveJson::albums)

    suspend fun createAlbum(name: String, areaId: String? = null): DResult<DAlbum> =
        post("albums", DriveJson.albumBody(name, areaId?.let { DriveJson.areaId(it) ?: return bad() }), DriveJson::albumAnswer)

    suspend fun album(id: String, before: String? = null, limit: Int = 120): DResult<DAlbumPage> {
        val album = DriveJson.albumId(id) ?: return bad()
        val q = buildMap {
            if (before != null) put("before", before)
            put("limit", limit.coerceIn(1, 200).toString())
        }
        return get("albums/$album", q) { o -> DriveJson.albumPage(o)?.let { it.copy(items = DriveLogic.withoutVaults(it.items)) } }
    }

    suspend fun updateAlbum(id: String, name: String? = null, coverId: Long? = null, frame: Boolean? = null): DResult<DAlbum> {
        val album = DriveJson.albumId(id) ?: return bad()
        return patch("albums/$album", DriveJson.albumPatchBody(name, coverId, frame), DriveJson::albumAnswer)
    }

    /** The album goes, its photos stay. */
    suspend fun deleteAlbum(id: String): DResult<Unit> {
        val album = DriveJson.albumId(id) ?: return bad()
        return del("albums/$album", unit)
    }

    /** Puts [ids] into the album (at most 500 a call, so in parts). */
    suspend fun addToAlbum(id: String, ids: List<Long>): DResult<DAlbum> = albumItems(id, ids, add = true)

    suspend fun removeFromAlbum(id: String, ids: List<Long>): DResult<DAlbum> = albumItems(id, ids, add = false)

    private suspend fun albumItems(id: String, ids: List<Long>, add: Boolean): DResult<DAlbum> {
        val album = DriveJson.albumId(id) ?: return bad()
        if (ids.isEmpty()) return bad()
        var last: DResult<DAlbum> = bad()
        for (part in ids.distinct().chunked(ALBUM_IDS_MAX)) {
            val body = DriveJson.idsBody(part)
            last = if (add) post("albums/$album/items", body, DriveJson::albumAnswer) else delWithBody("albums/$album/items", body, DriveJson::albumAnswer)
            if (last !is DResult.Ok) return last
        }
        return last
    }

    // ─── storage (Drive 1.7, feature `storage`) ─────────────────────

    /** How much is used and by what; [all] (administrators): everything, not only the areas I can read. */
    suspend fun storage(all: Boolean = false): DResult<DStorageReport> =
        get("storage", if (all) mapOf("all" to "1") else emptyMap(), DriveJson::storage)

    // ─── ransomware protection (Drive 1.6, feature `guard`) ─────────

    /** Puts back what the incident changed (restores the files, the new ones go to the trash). */
    suspend fun guardUndo(incidentId: String): DResult<DGuardResult> = guardAction(incidentId, "undo")

    /** "That was me": the device may write again, the changes stay. */
    suspend fun guardRelease(incidentId: String): DResult<DGuardResult> = guardAction(incidentId, "release")

    private suspend fun guardAction(incidentId: String, action: String): DResult<DGuardResult> {
        if (!Regex("^[A-Za-z0-9_-]{1,80}$").matches(incidentId)) return bad()
        return post("guard/incidents/$incidentId/$action", JsonObject(), DriveJson::guardResult)
    }

    // ─── AI for documents (Drive 1.8, feature `docAi`) ──────────────

    /** What the AI read in file [id], its suggestion and the file's deadlines. */
    suspend fun insight(id: Long): DResult<DInsight> = get("nodes/$id/insight", parse = DriveJson::insight)

    /** Recognise again (write right); nothing readable: `422 unsupported` with `reason` `no_text` | `media_disabled`. */
    suspend fun insightAgain(id: Long): DResult<DInsight> = post("nodes/$id/insight", JsonObject(), DriveJson::insight)

    /** Takes the suggestion: renamed ([name]) and / or moved ([folder]). */
    suspend fun insightApply(id: Long, name: Boolean, folder: Boolean): DResult<DInsightApplied> =
        post("nodes/$id/insight/apply", DriveJson.applyBody(name, folder), DriveJson::insightApplied)

    /** The deadlines of the files I read, [from]..[to] (`YYYY-MM-DD`; default today to a year on). */
    suspend fun deadlines(from: String? = null, to: String? = null): DResult<List<DDeadline>> {
        val q = buildMap {
            from?.let { put("from", it) }
            to?.let { put("to", it) }
        }
        return get("deadlines", q, DriveJson::deadlines)
    }

    /** My reminder of deadline [id], [daysBefore] (0…90) days before. */
    suspend fun remind(id: Long, daysBefore: Int): DResult<DDeadline> = post("deadlines/$id/remind", DriveJson.remindBody(daysBefore), DriveJson::deadlineAnswer)

    suspend fun unremind(id: Long): DResult<DDeadline> = del("deadlines/$id/remind", DriveJson::deadlineAnswer)

    /** AI for documents in area [id] on or off (`PATCH areas/<id> { docAi }`, the write right) → the setting now. */
    suspend fun setAreaDocAi(id: String, on: Boolean): DResult<Boolean> {
        val area = DriveJson.areaId(id) ?: return bad()
        return patch("areas/$area", DriveJson.docAiBody(on), DriveJson::areaDocAi)
    }

    // ─── office in the browser (Drive 1.9, feature `office`) ────────

    /** The address of file [id] in the office (edit with the write right, else view). */
    suspend fun office(id: Long): DResult<DOffice> = post("nodes/$id/office", JsonObject(), DriveJson::office)

    // ─── vault folders (Drive 1.9, feature `vault`) ─────────────────

    private fun vaultPath(id: String): String? = DriveJson.vaultId(id)?.let { "vaults/$it" }

    suspend fun vaults(): DResult<List<DVault>> = get("vaults", parse = DriveJson::vaults)

    suspend fun vault(id: String): DResult<DVault> = get(vaultPath(id) ?: return bad(), parse = DriveJson::vaultAnswer)

    suspend fun createVault(body: JsonObject): DResult<DVaultCreated> = post("vaults", body, DriveJson::vaultCreated)

    /** My device's keys of the vault (only one of my own devices; none while it waits). */
    suspend fun vaultKeys(id: String, deviceId: String): DResult<DVaultKeys> =
        get((vaultPath(id) ?: return bad()) + "/keys", mapOf("deviceId" to deviceId), DriveJson::vaultKeys)

    suspend fun vaultRecovery(id: String): DResult<DVaultRecovery> = get((vaultPath(id) ?: return bad()) + "/recovery", parse = DriveJson::vaultRecovery)

    suspend fun vaultDevices(id: String): DResult<List<DVaultDevice>> = get((vaultPath(id) ?: return bad()) + "/devices", parse = DriveJson::vaultDevices)

    /** This device asks for the vault (pending; an approved one stays approved). */
    suspend fun vaultAsk(id: String, device: JsonObject): DResult<DVaultDevice> =
        post((vaultPath(id) ?: return bad()) + "/devices", device, DriveJson::vaultDeviceAnswer)

    suspend fun vaultApprove(id: String, deviceId: String, wraps: List<DVaultWrap>, recoveryAuth: String? = null): DResult<DVaultDevice> {
        val did = DriveJson.vaultId(deviceId) ?: return bad()
        return post((vaultPath(id) ?: return bad()) + "/devices/$did/approve", DriveJson.approveBody(wraps, recoveryAuth), DriveJson::vaultDeviceAnswer)
    }

    /** A request declined (or a device removed). */
    suspend fun vaultRevoke(id: String, deviceId: String): DResult<DVaultDevice> {
        val did = DriveJson.vaultId(deviceId) ?: return bad()
        return del((vaultPath(id) ?: return bad()) + "/devices/$did", DriveJson::vaultDeviceAnswer)
    }

    suspend fun createVaultFolder(parentId: Long, nameCipher: String, keyVersion: Int): DResult<DNode> =
        post("nodes", DriveJson.vaultFolderBody(parentId, nameCipher, keyVersion), DriveJson::nodeAnswer)

    /** The uploaded ciphertext [mediaId] becomes a file of the vault (a new version of [opaqueName] when given). */
    suspend fun commitVault(mediaId: String, parentId: Long, nameCipher: String?, keyVersion: Int, mtimeMs: Long?, opaqueName: String? = null): DResult<Pair<DNode, Boolean>> =
        post("uploads/commit", DriveJson.vaultCommitBody(mediaId, parentId, nameCipher, keyVersion, mtimeMs, opaqueName), DriveJson::commit)

    /**
     * A new vault key (`POST vaults/<id>/rotate`, [DriveJson.rotateBody]); `409 { devices }` names the approved devices,
     * `409 { reason: recovery_required }` asks for a new recovery key.
     */
    suspend fun vaultRotate(id: String, body: JsonObject): DResult<DVault> = post((vaultPath(id) ?: return bad()) + "/rotate", body, DriveJson::vaultAnswer)

    /** A new recovery key (`PUT vaults/<id>/recovery`, [DriveJson.recoveryBody]). */
    suspend fun vaultSetRecovery(id: String, body: JsonObject): DResult<DVault> {
        val path = (vaultPath(id) ?: return bad()) + "/recovery"
        return call(request = { pluginPut(DRIVE_PLUGIN, path, body, lang()) }, parse = DriveJson::vaultAnswer)
    }

    /** A vault node's new encrypted name. */
    suspend fun renameVault(id: Long, nameCipher: String): DResult<DNode> = patch("nodes/$id", DriveJson.nameCipherPatchBody(nameCipher), DriveJson::nodeAnswer)

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

    // ─── several items at once (Drive 1.12, features `bulk`, `zip`, `multiLink`) ─

    /** What a job would do: how much, the names taken at [target], what cannot go (changes nothing). */
    suspend fun opCheck(kind: String, ids: List<Long>, target: Long?): DResult<DOpCheck> {
        if (kind !in DriveJson.OP_KINDS || ids.isEmpty()) return bad()
        return post("ops/check", DriveJson.opCheckBody(kind, ids, target), DriveJson::opCheck)
    }

    /** Starts a job on the server (small ones come back `done` at once); [onConflict] `version` | `rename` | `skip`. */
    suspend fun startOp(kind: String, ids: List<Long>, target: Long?, onConflict: String?): DResult<DOp> {
        if (kind !in DriveJson.OP_KINDS || ids.isEmpty()) return bad()
        return post("ops", DriveJson.opBody(kind, ids, target, onConflict), DriveJson::opAnswer)
    }

    /** The job [id] with its items one by one (Drive 1.12.1; an older server leaves them out). */
    suspend fun op(id: String): DResult<DOp> = get("ops/" + (DriveJson.opId(id) ?: return bad()), mapOf("files" to "1"), DriveJson::opAnswer)

    /** My jobs still running (after the app was closed, they go on on the server). */
    suspend fun runningOps(): DResult<List<DOp>> = get("ops", mapOf("state" to "running", "files" to "1"), DriveJson::ops)

    /** Stops a job: what is done stays done. */
    suspend fun cancelOp(id: String): DResult<DOp> = del("ops/" + (DriveJson.opId(id) ?: return bad()), DriveJson::opAnswer)

    /** "Rückgängig" (until `undoUntil`): a move goes back, a trash is restored, copies go into the trash. */
    suspend fun undoOp(id: String): DResult<DOp> = post("ops/" + (DriveJson.opId(id) ?: return bad()) + "/undo", JsonObject(), DriveJson::opAnswer)

    /** A ZIP of [ids] (folders with their content; never a vault's content), for a short time. */
    suspend fun zip(ids: List<Long>): DResult<DZip> {
        if (ids.isEmpty()) return bad()
        return post("zip", DriveJson.zipBody(ids), DriveJson::zip)
    }

    /**
     * The ZIP of [zip] as a stream (the caller closes it): its media, the server's own relative address with the app's
     * credentials, or GateControl's public bearer address without any ([DriveJson.zipFetch]).
     */
    suspend fun zipStream(zip: DZip): DResult<ResponseBody> {
        val base = setupRepository.getServerUrl()
        if (base.isBlank() || !setupRepository.isConfigured()) return DResult.NotConfigured
        val fetch = if (zip.mediaId != null) null else DriveJson.zipFetch(base, zip.url ?: zip.path ?: return bad()) ?: return bad()
        return try {
            if (fetch == null || fetch.auth) {
                val client = apiClientProvider.mediaClient(base)
                val r = withTimeoutOrNull(TIMEOUT_MS) {
                    if (fetch == null) client.pluginMediaGet(DRIVE_PLUGIN, zip.mediaId!!) else client.getRaw(fetch.url)
                } ?: return DResult.Offline
                val body = r.body()
                if (r.isSuccessful && body != null) DResult.Ok(body) else {
                    r.errorBody()?.close()
                    DResult.Failed("http_${r.code()}", r.code())
                }
            } else {
                // a bearer address: no token, no cookies, no device headers — and never on to plain http
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    plainHttp.newCall(okhttp3.Request.Builder().url(fetch.url).get().build()).execute()
                }
                val body = r.body
                if (r.isSuccessful) DResult.Ok(body) else {
                    r.close()
                    DResult.Failed("http_${r.code}", r.code)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            DResult.Offline
        } catch (e: Exception) {
            Timber.d("Drive: zip failed (%s)", e.javaClass.simpleName)
            DResult.Failed(CODE_INVALID, 0)
        }
    }

    /** A client without the app's credentials, cookies or interceptors (public bearer addresses only). */
    private val plainHttp: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }

    /** One link for several files (feature `multiLink`); folders mixed in: `400 invalid { reason: mixed }`. */
    suspend fun createMultiLink(ids: List<Long>, mode: String, expires: String, pin: String?): DResult<DLink> {
        if (ids.isEmpty()) return bad()
        return post("links", DriveJson.multiLinkBody(ids, mode, expires, pin), DriveJson::linkAnswer)
    }

    // ─── files ──────────────────────────────────────────────────────

    suspend fun uploadCheck(parentId: Long, name: String, sha256: String?, size: Long?): DResult<DUploadCheck> =
        post("uploads/check", DriveJson.checkBody(parentId, name, sha256, size), DriveJson::uploadCheck)

    /**
     * Makes the uploaded [mediaId] a file `name` in folder [parentId]; [onConflict] `rename` | `version`; [takenAt] when
     * a photo was taken (ISO 8601, only for a Drive with `takenAt`).
     */
    suspend fun commit(
        mediaId: String,
        parentId: Long,
        name: String,
        mtimeMs: Long?,
        onConflict: String,
        text: String? = null,
        takenAt: String? = null,
    ): DResult<Pair<DNode, Boolean>> =
        post("uploads/commit", DriveJson.commitBody(mediaId, parentId, name, mtimeMs, onConflict, text, takenAt), DriveJson::commit)

    /** An empty file `name` in [parentId] without media (feature `emptyFiles`, Drive 1.5+). */
    suspend fun commitEmpty(parentId: Long, name: String, mime: String?, onConflict: String): DResult<Pair<DNode, Boolean>> =
        post("uploads/commit", DriveJson.emptyCommitBody(parentId, name, mime, onConflict), DriveJson::commit)

    /**
     * The file [nodeId] (or one of its [versionId]s) as a stream. `GET …/content` comes first: it names the media and
     * tells a child that a parent looked into its folder. The caller closes the body; null when unreachable or refused.
     */
    suspend fun download(nodeId: Long, versionId: Long? = null): DResult<Response<ResponseBody>> {
        val path = if (versionId == null) "nodes/$nodeId/content" else "nodes/$nodeId/versions/$versionId/content"
        val media = get(path, parse = DriveJson::contentMediaId)
        if (media !is DResult.Ok) return media.notOk()
        // An empty file has no media to fetch: hand back an empty body.
        if (media.value == DriveJson.EMPTY_CONTENT) return DResult.Ok(Response.success(ByteArray(0).toResponseBody(null)))
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
                else -> return DResult.Failed(answer.code ?: DriveJson.failureCode(response.code(), null), response.code()).also(::seen)
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
