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
            preview = o.obj("preview") != null,
            sha256 = sha256(o.str("sha256")),
            snippet = o.str("snippet")?.takeIf { o.str("match") == "text" }?.take(300),
        )
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

    fun commitBody(mediaId: String, parentId: Long, name: String, mtimeMs: Long?, onConflict: String, text: String? = null) = JsonObject().apply {
        addProperty("mediaId", mediaId)
        addProperty("parentId", parentId)
        addProperty("name", name)
        if (mtimeMs != null && mtimeMs > 0) addProperty("mtime", mtimeMs)
        addProperty("onConflict", onConflict)
        // The recognised text of a scan (Drive 1.3+ makes it searchable; older ones ignore it).
        if (!text.isNullOrBlank()) addProperty("text", text.take(TEXT_MAX))
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

    // ─── helpers ────────────────────────────────────────────────────

    private fun JsonObject.prim(key: String): JsonElement? = get(key)?.takeIf { it.isJsonPrimitive }
    fun JsonObject.str(key: String): String? = prim(key)?.let { runCatching { it.asString }.getOrNull() }
    fun JsonObject.long(key: String): Long? = prim(key)?.let { runCatching { it.asNumber.toLong() }.getOrNull() }
    fun JsonObject.int(key: String): Int? = prim(key)?.let { runCatching { it.asNumber.toInt() }.getOrNull() }
    fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { runCatching { it.asBoolean }.getOrNull() }
    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
