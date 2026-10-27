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
        )
    }

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
        )
    }

    fun nodeAnswer(o: JsonObject): DNode? = node(o.obj("node"))

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

    /** `GET nodes/<id>/content` → the media id to download (the call also tells a child that a parent looked). */
    fun contentMediaId(o: JsonObject): String? = mediaId(o.str("mediaId"))

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

    fun commitBody(mediaId: String, parentId: Long, name: String, mtimeMs: Long?, onConflict: String) = JsonObject().apply {
        addProperty("mediaId", mediaId)
        addProperty("parentId", parentId)
        addProperty("name", name)
        if (mtimeMs != null && mtimeMs > 0) addProperty("mtime", mtimeMs)
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
