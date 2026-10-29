package com.gatecontrol.android.drive

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Pure parsers of the Drive plugin's answers (README "API"). Every parser returns null for an answer that does not
 * have the shape it needs; fields that may be missing get sensible defaults. Names and ids are taken as they come,
 * but bounded, so a broken server cannot blow up the UI.
 */
internal object DriveJson {

    private val SHA256 = Regex("^[0-9a-f]{64}$")
    /** The server keeps at most this much recognised text of a file. */
    const val TEXT_MAX = 100_000
    private val MEDIA_ID = Regex("^[A-Za-z0-9_-]{6,100}$")
    private val AREA_ID = Regex("^[A-Za-z0-9:_-]{1,80}$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{6,200}$")

    fun isRefusal(o: JsonObject?): Boolean = o != null && o.has("ok") && runCatching { !o.get("ok").asBoolean }.getOrDefault(false)

    fun errorCode(o: JsonObject?): String? = o?.str("code")?.take(60)

    fun mediaId(v: String?): String? = v?.takeIf { MEDIA_ID.matches(it) }

    fun areaId(v: String?): String? = v?.takeIf { AREA_ID.matches(it) }

    // ─── values ─────────────────────────────────────────────────────

    fun me(o: JsonObject): DMe? {
        val me = o.obj("me") ?: return null
        val licence = o.obj("licence")
        return DMe(
            userId = me.int("id"),
            name = me.str("name")?.take(200),
            seat = me.str("seat")?.takeIf { it == "family" || it == "business" },
            licenceValid = licence?.bool("valid") ?: false,
            tier = licence?.str("tier")?.take(20),
            areas = o.arr("areas")?.mapNotNull { (it as? JsonObject)?.let(::area) }.orEmpty(),
            latest = o.long("latest") ?: 0L,
            trashDays = o.obj("settings")?.int("trashDays"),
            maxBytes = o.obj("limits")?.long("maxBytes"),
            people = o.arr("people")?.mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val id = po.int("id") ?: return@mapNotNull null
                id to (po.str("name")?.take(200) ?: return@mapNotNull null)
            }?.toMap().orEmpty(),
            wipes = o.int("wipes")?.coerceAtLeast(0) ?: 0,
            scanFormat = if (o.obj("settings")?.str("scanFormat") == "image") "image" else "pdf",
            admin = me.bool("admin") ?: false,
            deviceId = me.str("deviceId")?.takeIf { DEVICE_ID.matches(it) },
        ).let { base ->
            val features = features(o.get("features") ?: me.get("features"))
            base.copy(
                features = features,
                links = if (DMe.FEATURE_LINKS in features) portalLinks(o.obj("links") ?: me.obj("links")) else null,
                guard = if (DMe.FEATURE_GUARD in features) guard(o.obj("guard") ?: me.obj("guard")) else null,
                incidents = if (DMe.FEATURE_GUARD in features) {
                    (o.arr("incidents") ?: me.arr("incidents"))?.mapNotNull { incident(it as? JsonObject) }?.take(20).orEmpty()
                } else {
                    emptyList()
                },
            )
        }
    }

    private val FEATURE = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,39}$")
    /** A device id as Drive takes it (`device=` of a delete later). */
    val DEVICE_ID = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val INCIDENT_ID = Regex("^[A-Za-z0-9_-]{1,80}$")

    /** `features`: a list of names (`["links","guard"]`) or an object of flags (`{ links: true }`). */
    fun features(e: JsonElement?): Set<String> = when {
        e == null -> emptySet()
        e.isJsonArray -> e.asJsonArray.mapNotNull { f -> f.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() } }
        e.isJsonObject -> e.asJsonObject.entrySet().filter { (_, v) -> v.isJsonPrimitive && runCatching { v.asBoolean }.getOrDefault(false) }.map { it.key }
        else -> emptyList()
    }.filter { FEATURE.matches(it) }.take(64).toSet()

    /** A portal address as the server gives it: https (http only for a local test server), bounded. */
    private fun webUrl(v: String?): String? =
        v?.trim()?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 2000 && it.none(Char::isWhitespace) }

    fun portalLinks(o: JsonObject?): DPortalLinks? {
        if (o == null) return null
        val node = webUrl(o.str("node"))?.takeIf { it.contains("{id}") }
        val drive = webUrl(o.str("drive"))
        return if (node == null && drive == null) null else DPortalLinks(node, drive)
    }

    /** An id the server may send as a number or a string. */
    private fun JsonObject.idString(key: String): String? = prim(key)?.let { p ->
        runCatching { if (p.asJsonPrimitive.isNumber) p.asNumber.toLong().toString() else p.asString }.getOrNull()
    }

    fun incident(o: JsonObject?): DIncident? {
        if (o == null) return null
        val id = o.idString("id")?.takeIf { INCIDENT_ID.matches(it) } ?: return null
        return DIncident(
            id = id,
            at = o.str("at")?.take(40),
            device = o.idString("device")?.take(100),
            deviceName = o.str("deviceName")?.trim()?.take(100)?.ifEmpty { null },
            files = o.int("files")?.coerceAtLeast(0) ?: 0,
            sample = o.arr("sample")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }?.take(255) }
                ?.filter { it.isNotBlank() }?.take(5).orEmpty(),
            state = (o.str("state") ?: o.str("status"))?.take(20),
        )
    }

    fun guard(o: JsonObject?): DGuard? = o?.let { DGuard(locked = it.bool("locked") ?: false, incident = incident(it.obj("incident"))) }

    /** `POST guard/incidents/<id>/undo|release` → `{ incident, restored, trashed }`. */
    fun guardResult(o: JsonObject): DGuardResult =
        DGuardResult(incident(o.obj("incident")), restored = o.int("restored")?.coerceAtLeast(0) ?: 0, trashed = o.int("trashed")?.coerceAtLeast(0) ?: 0)

    /** Push event `changed { areas, seq }`; without areas it stands for all of them. */
    fun changedEvent(data: JsonElement?): DChanged {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return DChanged(emptySet(), null)
        val areas = o.arr("areas")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.let { areaId(runCatching { it.asString }.getOrNull()) } }
        return DChanged(areas?.take(100)?.toSet().orEmpty(), o.long("seq"))
    }

    /**
     * The code of a refused call: the body's `code`, else from the status — `423` is Drive's ransomware protection
     * (this device's writes are stopped).
     */
    fun failureCode(http: Int, body: JsonObject?): String = errorCode(body) ?: if (http == 423) DEVICE_LOCKED else "http_$http"

    const val DEVICE_LOCKED = "device_locked"

    fun areas(o: JsonObject): List<DArea>? = o.arr("areas")?.mapNotNull { (it as? JsonObject)?.let(::area) }

    fun area(o: JsonObject): DArea? {
        val id = areaId(o.str("id")) ?: return null
        val root = o.long("rootId") ?: return null
        val st = o.obj("storage")
        return DArea(
            id = id,
            kind = o.str("kind")?.take(20) ?: "mine",
            label = o.str("label")?.take(200) ?: id,
            role = o.str("role")?.takeIf { it in setOf("read", "write", "manage") } ?: "read",
            via = o.str("via")?.take(20),
            rootId = root,
            archived = o.bool("archived") ?: false,
            docAi = o.bool("docAi"),
            storage = st?.let {
                DStorage(
                    bytes = it.long("bytes"), quota = it.long("quota"), form = it.str("form")?.take(20),
                    target = it.targetLabel(), state = it.str("state")?.take(40),
                )
            },
        )
    }

    /** `target` may be a label or `{ kind, label }`. */
    private fun JsonObject.targetLabel(): String? {
        val t = get("target") ?: return null
        return when {
            t.isJsonPrimitive -> runCatching { t.asString }.getOrNull()?.take(100)
            t.isJsonObject -> t.asJsonObject.let { it.str("label") ?: it.str("kind") }?.take(100)
            else -> null
        }
    }

    fun node(o: JsonObject?): DNode? {
        if (o == null) return null
        val id = o.long("id") ?: return null
        val area = areaId(o.str("areaId")) ?: return null
        val name = o.str("name")?.take(255) ?: return null
        return DNode(
            id = id,
            areaId = area,
            parentId = o.long("parentId"),
            name = name,
            isDir = o.bool("isDir") ?: false,
            size = o.long("size") ?: 0L,
            mime = o.str("mime")?.take(100),
            mediaId = mediaId(o.str("mediaId")),
            rev = o.int("rev") ?: 0,
            mtime = o.str("mtime")?.take(40),
            updatedAt = o.str("updatedAt")?.take(40),
            updatedBy = o.int("updatedBy"),
            path = o.str("path")?.take(2000),
            from = o.str("from")?.take(2000),
            files = o.int("files"),
            deletedAt = o.str("deletedAt")?.take(40),
            // an object `{ url, largeUrl }` in node answers, a plain `true` in the photo lists (Drive 1.7)
            preview = o.obj("preview") != null || o.bool("preview") == true,
            sha256 = sha256(o.str("sha256")),
            snippet = o.str("snippet")?.takeIf { o.str("match") == "text" }?.take(300),
            takenAt = o.str("takenAt")?.take(40),
            deleteAt = o.str("deleteAt")?.take(40),
            insight = o.obj("insight")?.let(::insightShort),
        ).let { n ->
            // a node of a vault (Drive 1.9): its name is opaque, the encrypted one comes as nameCipher
            val vault = vaultId(o.str("vaultId"))
            if (vault == null && o.bool("encrypted") != true) n
            else n.copy(vaultId = vault ?: INVALID_VAULT, nameCipher = nameCipher(o.str("nameCipher")), keyVersion = o.int("keyVersion")?.takeIf { it > 0 }, preview = false, insight = null)
        }
    }

    fun nodeAnswer(o: JsonObject): DNode? = node(o.obj("node"))

    fun sha256(v: String?): String? = v?.lowercase()?.takeIf { SHA256.matches(it) }

    /** `GET wipes` → the files this device still has to remove. */
    fun wipes(o: JsonObject): List<DWipe>? = o.arr("wipes")?.mapNotNull { e ->
        val w = e as? JsonObject ?: return@mapNotNull null
        DWipe(
            id = w.long("id") ?: return@mapNotNull null,
            nodeId = w.long("nodeId"),
            name = w.str("name")?.take(255) ?: "",
            sha256 = sha256(w.str("sha256")),
            size = w.long("size") ?: -1L,
            mime = w.str("mime")?.take(100),
        )
    }

    fun wipesDoneBody(ids: List<Long>) = JsonObject().apply {
        add("ids", com.google.gson.JsonArray().apply { ids.forEach { add(it) } })
    }

    /** `GET nodes/<id>` + `GET nodes/<id>/children` → the folder. */
    fun folder(nodeAnswer: JsonObject, childrenAnswer: JsonObject): DFolder? {
        val n = nodeAnswer.obj("node") ?: return null
        val node = node(n) ?: return null
        val items = childrenAnswer.arr("items")?.mapNotNull { (it as? JsonObject)?.let(::node) } ?: return null
        return DFolder(
            node = node,
            crumbs = n.arr("crumbs")?.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                DCrumb(co.long("id") ?: return@mapNotNull null, co.str("name")?.take(255) ?: return@mapNotNull null)
            }.orEmpty(),
            role = (childrenAnswer.str("role") ?: n.str("role"))?.takeIf { it in setOf("read", "write", "manage") } ?: "read",
            items = items,
            total = childrenAnswer.int("total") ?: items.size,
            next = childrenAnswer.int("next"),
        )
    }

    fun items(o: JsonObject): List<DNode>? = o.arr("items")?.mapNotNull { (it as? JsonObject)?.let(::node) }

    /** `GET nodes/<id>/children[?dirs=1]` → the folders for the menu's tree (`hasDirs` from Drive 1.11 on). */
    fun treeDirs(o: JsonObject): List<DTreeDir>? {
        val arr = o.arr("items") ?: return null
        val nodes = ArrayList<DNode>()
        val hasDirs = HashMap<Long, Boolean>()
        for (e in arr) {
            val item = e as? JsonObject ?: continue
            val n = node(item) ?: continue
            nodes += n
            item.bool("hasDirs")?.let { hasDirs[n.id] = it }
        }
        return DriveTree.dirsOf(nodes, hasDirs)
    }

    /**
     * `GET nodes/<id>/content` → the media id to download (the call also tells a child that a parent looked), or
     * [EMPTY_CONTENT] for an empty file: Drive 1.5 keeps those without media (`empty: true`, `size: 0`).
     */
    fun contentMediaId(o: JsonObject): String? {
        mediaId(o.str("mediaId"))?.let { return it }
        val empty = o.get("empty")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        return if (empty || o.long("size") == 0L) EMPTY_CONTENT else null
    }

    /** Stands for "no media, the file is empty" in [contentMediaId]; never a real media id. */
    const val EMPTY_CONTENT = ""

    fun versions(o: JsonObject): DVersions? {
        val current = node(o.obj("current")) ?: return null
        return DVersions(
            current = current,
            versions = o.arr("versions")?.mapNotNull { v ->
                val vo = v as? JsonObject ?: return@mapNotNull null
                DVersion(
                    id = vo.long("id") ?: return@mapNotNull null,
                    rev = vo.int("rev") ?: 0,
                    size = vo.long("size") ?: 0L,
                    at = vo.str("at")?.take(40),
                    by = vo.int("by"),
                )
            }.orEmpty(),
        )
    }

    fun uploadCheck(o: JsonObject): DUploadCheck? {
        if (!o.has("exists")) return null
        return DUploadCheck(exists = o.bool("exists") ?: false, same = o.bool("same") ?: false, node = node(o.obj("node")))
    }

    /** `POST uploads/commit` → `{ node, created }`. */
    fun commit(o: JsonObject): Pair<DNode, Boolean>? = node(o.obj("node"))?.let { it to (o.bool("created") ?: true) }

    fun changes(o: JsonObject): DChanges? {
        val list = o.arr("changes") ?: return null
        return DChanges(
            changes = list.mapNotNull { c ->
                val co = c as? JsonObject ?: return@mapNotNull null
                DChange(
                    seq = co.long("seq") ?: return@mapNotNull null,
                    op = co.str("op")?.take(20) ?: return@mapNotNull null,
                    areaId = areaId(co.str("areaId")),
                    nodeId = co.long("nodeId"),
                    at = co.str("at")?.take(40),
                    node = node(co.obj("node")),
                )
            },
            next = o.long("next") ?: 0L,
            more = o.bool("more") ?: false,
        )
    }

    fun link(o: JsonObject?): DLink? {
        if (o == null) return null
        val token = o.str("token")?.takeIf { TOKEN.matches(it) } ?: return null
        return DLink(
            token = token,
            url = o.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(2000),
            nodeId = o.long("nodeId"),
            kind = o.str("kind")?.take(20),
            mode = o.str("mode")?.take(20),
            name = o.str("name")?.take(255),
            active = o.bool("active") ?: false,
            expiresAt = o.str("expiresAt")?.take(40),
            pin = o.bool("pin") ?: false,
            opens = o.int("opens") ?: 0,
            downloads = o.int("downloads") ?: 0,
        )
    }

    fun linkAnswer(o: JsonObject): DLink? = link(o.obj("link"))

    fun links(o: JsonObject): DLinks? {
        val list = o.arr("links") ?: return null
        return DLinks(list.mapNotNull { link(it as? JsonObject) }, enabled = o.bool("enabled") ?: true)
    }

    fun uploadedMediaId(o: JsonObject): String? = mediaId(o.str("mediaId"))

    data class UploadPart(val complete: Boolean?, val offset: Long?, val mediaId: String?, val code: String?)

    /** One part of a resumable upload: `{ complete, offset }` or, at the end, `{ mediaId }`. */
    fun uploadPart(o: JsonObject?): UploadPart {
        if (o == null) return UploadPart(null, null, null, null)
        val media = mediaId(o.str("mediaId"))
        val complete = o.bool("complete") ?: if (media != null) true else null
        return UploadPart(complete, o.long("offset"), media, errorCode(o))
    }

    fun uploadOffset(o: JsonObject): Long? = o.long("offset")

    // ─── bodies ─────────────────────────────────────────────────────

    fun mkdirBody(parentId: Long, name: String, rename: Boolean = true) = JsonObject().apply {
        addProperty("parentId", parentId)
        addProperty("name", name)
        if (rename) addProperty("rename", true)
    }

    fun patchBody(name: String?, parentId: Long?) = JsonObject().apply {
        if (name != null) addProperty("name", name)
        if (parentId != null) addProperty("parentId", parentId)
    }

    fun checkBody(parentId: Long, name: String, sha256: String?, size: Long?) = JsonObject().apply {
        addProperty("parentId", parentId)
        addProperty("name", name)
        if (sha256 != null) addProperty("sha256", sha256)
        if (size != null) addProperty("size", size)
    }

    fun commitBody(
        mediaId: String,
        parentId: Long,
        name: String,
        mtimeMs: Long?,
        onConflict: String,
        text: String? = null,
        takenAt: String? = null,
    ) = JsonObject().apply {
        addProperty("mediaId", mediaId)
        addProperty("parentId", parentId)
        addProperty("name", name)
        if (mtimeMs != null && mtimeMs > 0) addProperty("mtime", mtimeMs)
        addProperty("onConflict", onConflict)
        // The recognised text of a scan (Drive 1.3+ makes it searchable; older ones ignore it).
        if (!text.isNullOrBlank()) addProperty("text", text.take(TEXT_MAX))
        // When a photo was taken (only sent when the server has `takenAt`).
        if (!takenAt.isNullOrBlank()) addProperty("takenAt", takenAt.take(40))
    }

    /** An empty file (feature `emptyFiles`): no media, just its place, name and size 0. */
    fun emptyCommitBody(parentId: Long, name: String, mime: String?, onConflict: String) = JsonObject().apply {
        addProperty("parentId", parentId)
        addProperty("name", name)
        addProperty("size", 0)
        mime?.takeIf { it.isNotBlank() && it.length <= 100 }?.let { addProperty("mime", it) }
        addProperty("onConflict", onConflict)
    }

    fun linkBody(nodeId: Long, mode: String, expires: String, pin: String?) = JsonObject().apply {
        addProperty("nodeId", nodeId)
        addProperty("mode", mode)
        addProperty("expires", expires)
        if (!pin.isNullOrBlank()) addProperty("pin", pin)
    }

    // ─── Drive 1.7: photos, albums, time travel, storage ────────────

    private val ALBUM_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val MONTH = Regex("^[0-9]{4}-[0-9]{2}$")

    fun albumId(v: String?): String? = v?.takeIf { ALBUM_ID.matches(it) }

    /** A cursor of a photo list (an ISO time or a number); null at the end. */
    private fun JsonObject.cursor(key: String): String? = idString(key)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }

    private fun nodes(o: JsonObject, key: String = "items"): List<DNode> = o.arr(key)?.mapNotNull { node(it as? JsonObject) }.orEmpty()

    /** `GET photos` → `{ items, next }`. */
    fun photos(o: JsonObject): DPhotos? {
        if (o.arr("items") == null) return null
        return DPhotos(nodes(o), o.cursor("next"))
    }

    /** `GET photos/months` → `{ months: [{ month, count }] }`. */
    fun months(o: JsonObject): List<DMonth>? = o.arr("months")?.mapNotNull { e ->
        val m = e as? JsonObject ?: return@mapNotNull null
        val month = m.str("month")?.takeIf { MONTH.matches(it) } ?: return@mapNotNull null
        DMonth(month, m.int("count")?.coerceAtLeast(0) ?: 0)
    }

    /** `GET|POST photos/camera` → `{ folder: { id, path, areaId } }` (Drive 1.10); null without a valid folder. */
    fun cameraFolder(o: JsonObject): DCameraFolder? {
        val f = o.obj("folder") ?: return null
        val id = f.long("id")?.takeIf { it > 0 } ?: return null
        return DCameraFolder(id, f.str("path")?.take(1000), areaId(f.str("areaId")) ?: return null)
    }

    /**
     * The query of `GET photos`, `photos/months` and `photos/memories`: [area] (else every area), the page's [before],
     * [limit] (1–200), the day of the memories ([date]) — and `camera=1` for only the camera uploads (Drive 1.10,
     * feature `photosFolder`). Null for an invalid area.
     */
    fun photosQuery(area: String? = null, before: String? = null, limit: Int? = null, camera: Boolean = false, date: String? = null): Map<String, String>? =
        buildMap {
            if (area != null) put("area", areaId(area) ?: return null)
            if (before != null) put("before", before)
            if (limit != null) put("limit", limit.coerceIn(1, 200).toString())
            if (date != null) put("date", date)
            if (camera) put("camera", "1")
        }

    /** `GET photos/memories` → `{ groups: [{ yearsAgo, date, items }] }`; groups without photos are left out. */
    fun memories(o: JsonObject): List<DMemories>? = o.arr("groups")?.mapNotNull { e ->
        val g = e as? JsonObject ?: return@mapNotNull null
        val items = nodes(g).take(MEMORIES_MAX)
        if (items.isEmpty()) return@mapNotNull null
        DMemories(g.int("yearsAgo")?.coerceAtLeast(0) ?: 0, g.str("date")?.take(40), items)
    }?.sortedBy { it.yearsAgo }

    private const val MEMORIES_MAX = 30

    fun album(o: JsonObject?): DAlbum? {
        if (o == null) return null
        val id = albumId(o.idString("id")) ?: return null
        return DAlbum(
            id = id,
            name = o.str("name")?.take(200) ?: return null,
            areaId = areaId(o.str("areaId")),
            count = o.int("count")?.coerceAtLeast(0) ?: 0,
            coverId = o.long("coverId"),
            frame = o.bool("frame") ?: false,
            updatedAt = o.str("updatedAt")?.take(40),
            canEdit = o.bool("canEdit") ?: false,
            canFrame = o.bool("canFrame") ?: false,
        )
    }

    fun albums(o: JsonObject): List<DAlbum>? = o.arr("albums")?.mapNotNull { album(it as? JsonObject) }

    fun albumAnswer(o: JsonObject): DAlbum? = album(o.obj("album"))

    /** `GET albums/<id>` → `{ album, items, next }`. */
    fun albumPage(o: JsonObject): DAlbumPage? {
        val album = album(o.obj("album")) ?: return null
        return DAlbumPage(album, nodes(o), o.cursor("next"))
    }

    fun albumBody(name: String?, areaId: String?) = JsonObject().apply {
        if (name != null) addProperty("name", name)
        if (areaId != null) addProperty("areaId", areaId)
    }

    fun albumPatchBody(name: String? = null, coverId: Long? = null, frame: Boolean? = null) = JsonObject().apply {
        if (name != null) addProperty("name", name)
        if (coverId != null) addProperty("coverId", coverId)
        if (frame != null) addProperty("frame", frame)
    }

    fun idsBody(ids: List<Long>) = JsonObject().apply {
        add("ids", JsonArray().apply { ids.forEach { add(it) } })
    }

    private fun JsonObject.count(key: String): Int = when (val e = get(key)) {
        null -> 0
        else -> when {
            e.isJsonArray -> e.asJsonArray.size()
            e.isJsonPrimitive -> runCatching { e.asNumber.toInt() }.getOrDefault(0)
            else -> 0
        }
    }.coerceAtLeast(0)

    /** `GET nodes/<id>/timetravel?at=` → what it would do. */
    fun timeTravelPreview(o: JsonObject): DTimeTravelPreview? {
        if (!o.has("restore") && !o.has("revert") && !o.has("trash") && !o.has("move")) return null
        return DTimeTravelPreview(
            at = o.str("at")?.take(40),
            historySince = o.str("historySince")?.take(40),
            restore = o.count("restore"),
            revert = o.count("revert"),
            trash = o.count("trash"),
            move = o.count("move"),
            sample = o.arr("sample")?.mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                DTimeTravelSample(s.str("op")?.take(20) ?: return@mapNotNull null, s.str("name")?.take(255) ?: return@mapNotNull null, s.str("path")?.take(2000))
            }?.take(20).orEmpty(),
            limited = o.bool("limited") ?: false,
            lost = o.count("lost"),
        )
    }

    /** `POST nodes/<id>/timetravel` → what it did. */
    fun timeTravelResult(o: JsonObject): DTimeTravelResult? {
        if (!o.has("restored") && !o.has("reverted") && !o.has("trashed") && !o.has("moved") && !o.has("before")) return null
        return DTimeTravelResult(
            at = o.str("at")?.take(40),
            before = o.str("before")?.take(40),
            restored = o.count("restored"),
            reverted = o.count("reverted"),
            trashed = o.count("trashed"),
            moved = o.count("moved"),
            failed = o.count("failed"),
            lost = o.count("lost"),
        )
    }

    fun timeTravelBody(at: String) = JsonObject().apply { addProperty("at", at) }

    private fun use(o: JsonObject) = DStorageUse(
        bytes = o.long("bytes")?.coerceAtLeast(0) ?: 0L,
        files = o.int("files")?.coerceAtLeast(0) ?: 0,
        versionsBytes = o.long("versionsBytes")?.coerceAtLeast(0) ?: 0L,
        trashBytes = o.long("trashBytes")?.coerceAtLeast(0) ?: 0L,
    )

    private fun storageFile(o: JsonObject?): DStorageFile? {
        if (o == null) return null
        return DStorageFile(
            id = o.long("id") ?: return null,
            name = o.str("name")?.take(255) ?: return null,
            size = o.long("size")?.coerceAtLeast(0) ?: 0L,
            areaId = areaId(o.str("areaId")),
            parentId = o.long("parentId"),
            path = o.str("path")?.take(2000),
            updatedAt = o.str("updatedAt")?.take(40),
            // the largest files have no `canDelete`: the delete itself checks the right
            canDelete = o.bool("canDelete") ?: true,
        )
    }

    /** `GET storage[?all=1]` → the overview. */
    fun storage(o: JsonObject): DStorageReport? {
        val total = o.obj("total") ?: return null
        return DStorageReport(
            total = use(total),
            maxBytes = o.obj("limit")?.long("maxBytes")?.takeIf { it > 0 },
            areas = o.arr("areas")?.mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                val id = areaId(a.str("id")) ?: return@mapNotNull null
                DStorageArea(id, a.str("label")?.take(200) ?: id, a.str("kind")?.take(20) ?: "mine", use(a), a.long("quota")?.takeIf { it > 0 })
            }.orEmpty(),
            people = o.arr("people")?.mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                DStoragePerson(p.int("user"), p.str("name")?.take(200) ?: return@mapNotNull null, p.long("bytes")?.coerceAtLeast(0) ?: 0L, p.int("files")?.coerceAtLeast(0) ?: 0)
            }.orEmpty(),
            largest = o.arr("largest")?.mapNotNull { storageFile(it as? JsonObject) }?.take(100).orEmpty(),
            duplicates = o.arr("duplicates")?.mapNotNull { e ->
                val d = e as? JsonObject ?: return@mapNotNull null
                val items = d.arr("items")?.mapNotNull { storageFile(it as? JsonObject) }.orEmpty()
                if (items.size < 2) return@mapNotNull null
                DDuplicates(
                    sha256 = sha256(d.str("sha256")) ?: return@mapNotNull null,
                    size = d.long("size")?.coerceAtLeast(0) ?: 0L,
                    count = d.int("count") ?: items.size,
                    wasted = d.long("wasted")?.coerceAtLeast(0) ?: 0L,
                    items = items,
                )
            }?.take(100).orEmpty(),
        )
    }

    /** `DELETE nodes/<id>?after=` → `{ marked, deleteAt }`. */
    fun mark(o: JsonObject): DMark = DMark(o.str("deleteAt")?.take(40) ?: node(o.obj("node"))?.deleteAt)

    /** `PATCH nodes/<id> { deleteAt: null }`: the mark of a delete later ends (the null must be sent). */
    const val UNMARK_BODY = "{\"deleteAt\":null}"

    // ─── Drive 1.8: AI for documents ────────────────────────────────

    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val INSIGHT_STATUS = setOf("none", "pending", "ready", "failed", "off")
    val DOC_KINDS = setOf("invoice", "contract", "notice", "medical", "certificate", "statement", "insurance", "warranty", "other")
    val DEADLINE_KINDS = setOf("payment", "cancellation", "objection")

    fun insightShort(o: JsonObject): DInsightShort? {
        val status = o.str("status")?.takeIf { it in INSIGHT_STATUS } ?: return null
        return DInsightShort(status, o.str("kind")?.takeIf { status == "ready" && it in DOC_KINDS })
    }

    /** `GET|POST nodes/<id>/insight` → the flat answer. */
    fun insight(o: JsonObject): DInsight? {
        val status = o.str("status")?.takeIf { it in INSIGHT_STATUS } ?: return null
        val s = o.obj("suggestion")
        return DInsight(
            status = status,
            kind = o.str("kind")?.takeIf { it in DOC_KINDS },
            title = o.str("title")?.trim()?.take(200)?.ifEmpty { null },
            date = o.str("date")?.takeIf { DATE.matches(it) },
            sender = o.str("sender")?.trim()?.take(200)?.ifEmpty { null },
            amount = o.prim("amount")?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() },
            currency = o.str("currency")?.trim()?.uppercase()?.takeIf { Regex("^[A-Z]{3}$").matches(it) },
            error = o.str("error")?.take(60),
            at = o.str("at")?.take(40),
            suggestion = s?.let {
                val folderId = it.long("folderId")
                val name = it.str("name")?.trim()?.take(255)?.ifEmpty { null }
                if (folderId == null && name == null) null else DSuggestion(folderId, it.str("folderPath")?.take(2000), name)
            },
            deadlines = o.arr("deadlines")?.mapNotNull { deadline(it as? JsonObject) }?.take(20).orEmpty(),
        )
    }

    fun deadline(o: JsonObject?): DDeadline? {
        if (o == null) return null
        val r = o.obj("reminder")
        return DDeadline(
            id = o.long("id") ?: return null,
            kind = o.str("kind")?.takeIf { it in DEADLINE_KINDS } ?: return null,
            date = o.str("date")?.takeIf { DATE.matches(it) } ?: return null,
            label = o.str("label")?.trim()?.take(200)?.ifEmpty { null },
            reminder = r?.let { DReminder(it.str("at")?.take(40), it.int("daysBefore")?.coerceIn(0, 90) ?: 0, it.bool("sent") ?: false) },
            node = node(o.obj("node")),
        )
    }

    /** `POST|DELETE deadlines/<id>/remind` → `{ deadline }`. */
    fun deadlineAnswer(o: JsonObject): DDeadline? = deadline(o.obj("deadline"))

    /** `GET deadlines` → `{ deadlines }`. */
    fun deadlines(o: JsonObject): List<DDeadline>? = o.arr("deadlines")?.mapNotNull { deadline(it as? JsonObject) }

    /** `POST nodes/<id>/insight/apply` → `{ node, insight }`. */
    fun insightApplied(o: JsonObject): DInsightApplied? {
        val node = node(o.obj("node")) ?: return null
        return DInsightApplied(node, o.obj("insight")?.let(::insight))
    }

    fun applyBody(name: Boolean, folder: Boolean) = JsonObject().apply {
        if (name) addProperty("name", true)
        if (folder) addProperty("folder", true)
    }

    fun remindBody(daysBefore: Int) = JsonObject().apply { addProperty("daysBefore", daysBefore.coerceIn(0, 90)) }

    // ─── Drive 1.9: office ──────────────────────────────────────────

    /** `POST nodes/<id>/office` → `{ url, mode, expiresAt }`. */
    fun office(o: JsonObject): DOffice? {
        val url = webUrl(o.str("url")) ?: return null
        return DOffice(url, if (o.str("mode") == "edit") "edit" else "view", o.str("expiresAt")?.take(40))
    }

    // ─── Drive 1.9: vault folders ───────────────────────────────────

    private val VAULT_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")
    /** Stands for "encrypted, but the vault id is not readable" (never a real vault id: too short). */
    const val INVALID_VAULT = "-"

    fun vaultId(v: String?): String? = v?.takeIf { VAULT_ID.matches(it) }

    fun nameCipher(v: String?): String? = v?.takeIf { it.length in 80..2000 && B64URL.matches(it) }

    private fun pub(v: String?): String? = v?.takeIf { it.length == 43 && B64URL.matches(it) }

    private fun wrap(v: String?): String? = v?.takeIf { it.length == 108 && B64URL.matches(it) }

    fun vault(o: JsonObject?): DVault? {
        if (o == null) return null
        return DVault(
            id = vaultId(o.str("id")) ?: return null,
            nodeId = o.long("nodeId") ?: return null,
            areaId = areaId(o.str("areaId")),
            parentId = o.long("parentId"),
            nameCipher = nameCipher(o.str("nameCipher")),
            keyVersion = o.int("keyVersion")?.takeIf { it > 0 } ?: return null,
            recoveryPub = pub(o.str("recoveryPub")),
            needsRotation = o.bool("needsRotation") ?: false,
            myDevices = o.arr("myDevices")?.mapNotNull { e ->
                val d = e as? JsonObject ?: return@mapNotNull null
                DVaultMyDevice(vaultId(d.str("deviceId")) ?: return@mapNotNull null, d.str("state")?.take(20) ?: "pending", d.str("name")?.take(80))
            }.orEmpty(),
            pending = o.int("pending")?.coerceAtLeast(0) ?: 0,
        )
    }

    fun vaults(o: JsonObject): List<DVault>? = o.arr("vaults")?.mapNotNull { vault(it as? JsonObject) }

    fun vaultAnswer(o: JsonObject): DVault? = vault(o.obj("vault"))

    fun vaultCreated(o: JsonObject): DVaultCreated? = vault(o.obj("vault"))?.let { DVaultCreated(it, node(o.obj("node"))) }

    fun vaultDevice(o: JsonObject?): DVaultDevice? {
        if (o == null) return null
        return DVaultDevice(
            deviceId = vaultId(o.str("deviceId")) ?: return null,
            userId = o.int("userId"),
            name = o.str("name")?.trim()?.take(80)?.ifEmpty { null },
            platform = o.str("platform")?.take(16),
            publicKey = pub(o.str("publicKey")) ?: return null,
            state = o.str("state")?.takeIf { it in setOf("pending", "approved", "revoked") } ?: return null,
            createdAt = o.str("createdAt")?.take(40),
        )
    }

    fun vaultDevices(o: JsonObject): List<DVaultDevice>? = o.arr("devices")?.mapNotNull { vaultDevice(it as? JsonObject) }

    fun vaultDeviceAnswer(o: JsonObject): DVaultDevice? = vaultDevice(o.obj("device"))

    private fun wraps(o: JsonObject): List<DVaultWrap> = o.arr("keys")?.mapNotNull { e ->
        val w = e as? JsonObject ?: return@mapNotNull null
        DVaultWrap(w.int("keyVersion")?.takeIf { it > 0 } ?: return@mapNotNull null, wrap(w.str("wrap")) ?: return@mapNotNull null)
    }.orEmpty()

    fun vaultKeys(o: JsonObject): DVaultKeys? = DVaultKeys(
        deviceId = vaultId(o.str("deviceId")) ?: return null,
        state = o.str("state")?.take(20) ?: return null,
        keyVersion = o.int("keyVersion")?.takeIf { it > 0 },
        keys = wraps(o),
    )

    fun vaultRecovery(o: JsonObject): DVaultRecovery = DVaultRecovery(pub(o.str("recoveryPub")), o.int("keyVersion")?.takeIf { it > 0 }, wraps(o))

    fun deviceBody(publicKey: String, name: String?, platform: String) = JsonObject().apply {
        addProperty("publicKey", publicKey)
        name?.trim()?.take(80)?.ifEmpty { null }?.let { addProperty("name", it) }
        addProperty("platform", platform)
    }

    fun vaultBody(
        id: String,
        parentId: Long,
        nameCipher: String,
        recoveryPub: String,
        recoveryAuth: String,
        recoveryWrap: String,
        device: JsonObject,
        wrap: String,
    ) = JsonObject().apply {
        addProperty("id", id)
        addProperty("parentId", parentId)
        addProperty("nameCipher", nameCipher)
        addProperty("recoveryPub", recoveryPub)
        addProperty("recoveryAuth", recoveryAuth)
        addProperty("recoveryWrap", recoveryWrap)
        add("device", device)
        addProperty("wrap", wrap)
    }

    /** `POST vaults/<id>/devices/<did>/approve`: the keys wrapped for that device; [recoveryAuth] for `via: recovery`. */
    fun approveBody(wraps: List<DVaultWrap>, recoveryAuth: String? = null) = JsonObject().apply {
        add("wraps", JsonArray().apply {
            wraps.forEach { w -> add(JsonObject().apply { addProperty("keyVersion", w.keyVersion); addProperty("wrap", w.wrap) }) }
        })
        if (recoveryAuth != null) {
            addProperty("via", "recovery")
            addProperty("recoveryAuth", recoveryAuth)
        }
    }

    /** A folder in a vault: `{ parentId, encrypted, nameCipher, keyVersion }`. */
    fun vaultFolderBody(parentId: Long, nameCipher: String, keyVersion: Int) = JsonObject().apply {
        addProperty("parentId", parentId)
        addProperty("encrypted", true)
        addProperty("nameCipher", nameCipher)
        addProperty("keyVersion", keyVersion)
    }

    /** A file of a vault: its ciphertext [mediaId]; [opaqueName] makes a new version of that file. */
    fun vaultCommitBody(mediaId: String, parentId: Long, nameCipher: String?, keyVersion: Int, mtimeMs: Long?, opaqueName: String?) = JsonObject().apply {
        addProperty("mediaId", mediaId)
        addProperty("parentId", parentId)
        addProperty("encrypted", true)
        if (nameCipher != null) addProperty("nameCipher", nameCipher)
        addProperty("keyVersion", keyVersion)
        if (mtimeMs != null && mtimeMs > 0) addProperty("mtime", mtimeMs)
        if (opaqueName != null) {
            addProperty("name", opaqueName)
            addProperty("onConflict", "version")
        }
    }

    fun nameCipherPatchBody(nameCipher: String) = JsonObject().apply { addProperty("nameCipher", nameCipher) }

    /** A new recovery key: its public key, its proof and the wraps of every key version (`recovery` of a rotation, `PUT …/recovery`). */
    data class RecoveryPart(val pub: String, val auth: String, val wraps: List<DVaultWrap>)

    private fun wrapsArray(wraps: List<DVaultWrap>) = JsonArray().apply {
        wraps.sortedBy { it.keyVersion }.forEach { w -> add(JsonObject().apply { addProperty("keyVersion", w.keyVersion); addProperty("wrap", w.wrap) }) }
    }

    /**
     * `POST vaults/<id>/rotate`: the new [keyVersion], its wraps for exactly every approved device (`deviceId` → wrap),
     * and either the wrap for the recovery key there is ([recoveryWrap]) or a new recovery key ([recovery]).
     */
    fun rotateBody(keyVersion: Int, wraps: List<Pair<String, String>>, recoveryWrap: String?, recovery: RecoveryPart?) = JsonObject().apply {
        addProperty("keyVersion", keyVersion)
        add("wraps", JsonArray().apply {
            wraps.forEach { (device, wrap) -> add(JsonObject().apply { addProperty("deviceId", device); addProperty("wrap", wrap) }) }
        })
        if (recovery != null) {
            add("recovery", JsonObject().apply {
                addProperty("pub", recovery.pub)
                addProperty("auth", recovery.auth)
                add("wraps", wrapsArray(recovery.wraps))
            })
        } else if (recoveryWrap != null) {
            addProperty("recoveryWrap", recoveryWrap)
        }
    }

    /** `PUT vaults/<id>/recovery { pub, auth, wraps }`. */
    fun recoveryBody(recovery: RecoveryPart) = JsonObject().apply {
        addProperty("pub", recovery.pub)
        addProperty("auth", recovery.auth)
        add("wraps", wrapsArray(recovery.wraps))
    }

    /** A rotation refused with `409 { devices }`: the devices the server wants a wrap for (else null). */
    fun rotateDevices(body: JsonObject?): List<String>? =
        body?.arr("devices")?.mapNotNull { e -> runCatching { e.asString }.getOrNull()?.let(::vaultId) }

    /** A rotation refused with `409 { reason: 'recovery_required' }`: a new recovery key is needed. */
    fun recoveryRequired(body: JsonObject?): Boolean = body?.str("reason") == "recovery_required"

    /** `PATCH areas/<id> { docAi }`. */
    fun docAiBody(on: Boolean) = JsonObject().apply { addProperty("docAi", on) }

    /** `PATCH areas/<id>` → `{ area: { id, docAi } }`: the area's setting now. */
    fun areaDocAi(o: JsonObject): Boolean? = o.obj("area")?.bool("docAi")

    fun vaultEvent(data: JsonElement?): DVaultEvent? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return DVaultEvent(vaultId(o.str("vault")) ?: return null, o.str("event")?.take(20) ?: return null, vaultId(o.str("device")), o.int("keyVersion"))
    }

    /** `409 { reason: 'stale_key', keyVersion }`: the vault has a newer key. */
    fun staleKey(body: JsonObject?): Boolean = body?.str("reason") == "stale_key"

    /** The vault's key version of a `stale_key` refusal. */
    fun staleKeyVersion(body: JsonObject?): Int? = body?.int("keyVersion")?.takeIf { it > 0 }

    /** The `reason` of a refusal (`no_text`, `media_disabled`, `stale_key`, …). */
    fun reason(body: JsonObject?): String? = body?.str("reason")?.take(40)

    // ─── Drive 1.12: several items at once ──────────────────────────

    private val OP_ID = Regex("^[A-Za-z0-9_-]{1,80}$")
    val OP_KINDS = setOf("move", "copy", "trash", "restore", "purge")
    private val OP_STATES = setOf("running", "done", "failed", "cancelled")
    /** At most this many ids in one job (the server expands folders and has its own limit). */
    const val OP_IDS_MAX = 10_000

    fun opId(v: String?): String? = v?.takeIf { OP_ID.matches(it) }

    private fun blocked(e: JsonElement?): DOpBlocked? {
        val o = e as? JsonObject ?: return null
        return DOpBlocked(o.long("id") ?: return null, o.str("reason")?.take(40) ?: "failed", o.str("name")?.take(255))
    }

    private fun ids(a: JsonArray?, max: Int): List<Long>? = a?.mapNotNull { e ->
        e.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it > 0 }
    }?.take(max)

    /**
     * `POST ops/check` → `{ items, files, folders, bytes, conflicts, conflictsMore, merges, blocked, quota, tooMany }`.
     */
    fun opCheck(o: JsonObject): DOpCheck? {
        val c = o.obj("check") ?: o
        if (!c.has("files") && !c.has("conflicts") && !c.has("blocked") && !c.has("items")) return null
        val quota = c.obj("quota")
        return DOpCheck(
            files = c.int("files")?.coerceAtLeast(0) ?: 0,
            folders = c.int("folders")?.coerceAtLeast(0) ?: 0,
            bytes = c.long("bytes")?.coerceAtLeast(0) ?: 0L,
            conflicts = c.arr("conflicts")?.mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                val existing = x.obj("existing")
                DOpConflict(
                    id = x.long("id") ?: return@mapNotNull null,
                    name = x.str("name")?.take(255) ?: return@mapNotNull null,
                    existingId = existing?.long("id"),
                    existingSize = existing?.long("size")?.coerceAtLeast(0),
                    existingMtime = existing?.str("mtime")?.take(40),
                    same = x.bool("same") ?: false,
                    path = x.str("path")?.take(2000)?.ifEmpty { null },
                    isDir = x.bool("isDir") ?: false,
                    size = x.long("size")?.coerceAtLeast(0),
                    mtime = x.str("mtime")?.take(40),
                    existingIsDir = existing?.bool("isDir") ?: false,
                )
            }?.take(OP_IDS_MAX).orEmpty(),
            blocked = c.arr("blocked")?.mapNotNull(::blocked)?.take(OP_IDS_MAX).orEmpty(),
            items = ids(c.arr("items"), OP_IDS_MAX),
            conflictsMore = c.int("conflictsMore")?.coerceAtLeast(0) ?: 0,
            merges = c.int("merges")?.coerceAtLeast(0) ?: 0,
            quotaNeeded = quota?.long("needed")?.coerceAtLeast(0),
            quotaFree = quota?.long("free")?.coerceAtLeast(0),
            tooMany = c.bool("tooMany") ?: false,
        )
    }

    /** A job (`{ id, kind, state, items, total, done, bytes…, label, target…, transfer…, results, undoUntil, error }`). */
    fun op(o: JsonObject?): DOp? {
        if (o == null) return null
        val r = o.obj("results")
        val failed = r?.arr("failed")?.mapNotNull(::blocked)?.take(1000).orEmpty()
        return DOp(
            id = opId(o.idString("id")) ?: return null,
            kind = o.str("kind")?.takeIf { it in OP_KINDS || it == "undo" } ?: return null,
            state = o.str("state")?.takeIf { it in OP_STATES } ?: return null,
            total = o.int("total")?.coerceAtLeast(0) ?: 0,
            done = o.int("done")?.coerceAtLeast(0) ?: 0,
            bytesTotal = o.long("bytesTotal")?.coerceAtLeast(0) ?: 0L,
            bytesDone = o.long("bytesDone")?.coerceAtLeast(0) ?: 0L,
            targetId = (o.long("targetId") ?: o.long("target"))?.takeIf { it > 0 },
            targetPath = o.str("targetPath")?.take(2000),
            results = r?.let {
                DOpResults(
                    done = it.int("done")?.coerceAtLeast(0) ?: 0,
                    skipped = it.int("skipped")?.coerceAtLeast(0) ?: 0,
                    failed = failed,
                    failedCount = it.int("failedCount")?.coerceAtLeast(failed.size) ?: failed.size,
                    resultIds = ids(it.arr("resultIds"), 500).orEmpty(),
                )
            },
            undoUntil = o.str("undoUntil")?.take(40),
            items = o.int("items")?.coerceAtLeast(0) ?: 0,
            label = o.str("label")?.take(255),
            targetName = o.str("targetName")?.take(255),
            transfer = o.str("transfer")?.takeIf { it == "client" || it == "server" },
            transfersLeft = o.int("transfersLeft")?.coerceAtLeast(0) ?: 0,
            error = o.str("error")?.take(40),
        )
    }

    /** `POST ops`, `GET ops/<id>`, `DELETE ops/<id>`, `POST ops/<id>/undo` → `{ op }`. */
    fun opAnswer(o: JsonObject): DOp? = op(o.obj("op"))

    /** `GET ops?state=running` → `{ ops: [...] }`. */
    fun ops(o: JsonObject): List<DOp>? = o.arr("ops")?.mapNotNull { op(it as? JsonObject) }?.take(50)

    /** Live event `op`: the job as it is now (`{ op }` or the job itself). */
    fun opEvent(data: JsonElement?): DOp? {
        val o = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return op(o.obj("op") ?: o)
    }

    private fun idsArray(ids: List<Long>) = JsonArray().apply { ids.distinct().take(OP_IDS_MAX).forEach { add(it) } }

    /** `POST ops/check { op, ids, target? }`. */
    fun opCheckBody(kind: String, ids: List<Long>, target: Long?) = JsonObject().apply {
        addProperty("op", kind)
        add("ids", idsArray(ids))
        if (target != null) addProperty("target", target)
    }

    /** `POST ops { op, ids, target?, onConflict? }` ([onConflict] `version` | `rename` | `skip`). */
    fun opBody(kind: String, ids: List<Long>, target: Long?, onConflict: String?) = JsonObject().apply {
        addProperty("op", kind)
        add("ids", idsArray(ids))
        if (target != null) addProperty("target", target)
        if (onConflict != null && onConflict in setOf("version", "rename", "skip")) addProperty("onConflict", onConflict)
    }

    /** `POST zip { ids }`. */
    fun zipBody(ids: List<Long>) = JsonObject().apply { add("ids", idsArray(ids)) }

    /** `POST zip` → `{ url | path | mediaId, name, expiresAt }` (null without any of the three). */
    fun zip(o: JsonObject): DZip? {
        val z = o.obj("zip") ?: o
        val url = webUrl(z.str("url"))
        val path = z.str("path")?.takeIf { it.startsWith("/") && it.length <= 2000 && it.none(Char::isWhitespace) && !it.contains("..") }
            ?: z.str("url")?.takeIf { url == null && it.startsWith("/") && it.length <= 2000 && it.none(Char::isWhitespace) && !it.contains("..") }
        val media = mediaId(z.str("mediaId"))
        if (url == null && path == null && media == null) return null
        val name = DriveLogic.safeName(z.str("name"))?.let { if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip" } ?: "Drive.zip"
        return DZip(url, path, media, name, z.str("expiresAt")?.take(40))
    }

    /** One link for several files (Drive 1.12 `multiLink`): `POST links { nodeIds, mode, expires, pin? }`. */
    fun multiLinkBody(ids: List<Long>, mode: String, expires: String, pin: String?) = JsonObject().apply {
        add("nodeIds", idsArray(ids))
        addProperty("mode", mode)
        addProperty("expires", expires)
        if (!pin.isNullOrBlank()) addProperty("pin", pin)
    }

    // ─── helpers ────────────────────────────────────────────────────

    private fun JsonObject.prim(key: String): JsonElement? = get(key)?.takeIf { it.isJsonPrimitive }
    fun JsonObject.str(key: String): String? = prim(key)?.let { runCatching { it.asString }.getOrNull() }
    fun JsonObject.long(key: String): Long? = prim(key)?.let { runCatching { it.asNumber.toLong() }.getOrNull() }
    fun JsonObject.int(key: String): Int? = prim(key)?.let { runCatching { it.asNumber.toInt() }.getOrNull() }
    fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { runCatching { it.asBoolean }.getOrNull() }
    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
