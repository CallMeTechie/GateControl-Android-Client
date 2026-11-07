package com.gatecontrol.android.print

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses the answers of the print plugin's person API (plugin README "API"). Everything is untrusted: ids are
 * checked, texts plain and capped, a malformed printer or job is dropped, a malformed answer is null.
 * Pure (Gson tree only), unit-tested.
 */
object PrintJson {

    /** Device ids are `u:<uuid>` or `h:<sha1>` (gateway docs/print.md); allow a little more, never a path. */
    private val DEVICE_ID_RE = Regex("^[A-Za-z0-9:_.-]{1,120}$")
    private val JOB_ID_RE = Regex("^[A-Za-z0-9_-]{1,80}$")
    private val MEDIA_ID_RE = Regex("^[A-Za-z0-9_.~-]{1,200}$")
    private val CODE_RE = Regex("^[a-z][a-z0-9_]{0,40}$")
    /** A job's `message`: a code, or a printer's stop reason with hyphens (`media-empty`, plugin 1.1.0). */
    private val MESSAGE_RE = Regex("^[a-z][a-z0-9_-]{0,40}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val PRINTER_STATES = setOf("idle", "processing", "stopped", "offline")
    private val MEDIA_RE = Regex("^[a-z0-9]+_[a-z0-9.-]+_[0-9.]+x[0-9.]+(mm|in)$")

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.asString

    private fun JsonObject.flag(key: String): Boolean? {
        val p = prim(key) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            else -> null
        }
    }

    private fun JsonObject.int(key: String): Int? {
        val p = prim(key)?.takeIf { it.isNumber } ?: return null
        val d = p.asDouble
        return if (d.isFinite() && d >= 0 && d <= 1_000_000) d.toInt() else null
    }

    /** Plain single-line text, at most [max] characters; null when empty. */
    fun text(v: String?, max: Int): String? =
        v?.replace(CONTROL_RE, " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private fun code(v: String?): String? = v?.takeIf { CODE_RE.matches(it) }

    private fun list(e: JsonElement?): List<JsonObject> =
        e?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject }.orEmpty()

    /** A refusal inside a 200 (`{ ok: false, code }`). */
    fun isRefusal(o: JsonObject): Boolean = o.flag("ok") == false

    /** `code` of an error answer (plugin and core), null when there is none. */
    fun errorCode(o: JsonObject?): String? = o?.let { code(it.str("code")) ?: it.obj("error")?.let { e -> code(e.str("code")) } }

    /** The plugin's own words for an error (`error` is a sentence in the asked language). */
    fun errorText(o: JsonObject?): String? = o?.str("error")?.let { text(it, 300) }

    /** `limit`: `left` (pages still possible today) and `pages` (what the job would have been). */
    fun errorLeft(o: JsonObject?): Int? = o?.int("left")

    fun errorPages(o: JsonObject?): Int? = o?.int("pages")

    fun printer(o: JsonObject): PPrinter? {
        val id = o.str("id")?.takeIf { DEVICE_ID_RE.matches(it) } ?: return null
        val p = o.obj("printer") ?: return null
        val name = text(o.str("name"), 120) ?: return null
        return PPrinter(
            id = id,
            name = name,
            location = text(o.str("location"), 120),
            online = o.flag("online") ?: false,
            problem = code(o.str("problem")),
            state = p.str("state")?.takeIf { it in PRINTER_STATES } ?: "idle",
            color = p.flag("color") ?: false,
            duplex = p.flag("duplex") ?: false,
            copiesMax = (p.int("copiesMax") ?: 99).coerceIn(1, 99),
            office = p.flag("office") ?: false,
            mediaSizes = mediaSizes(p.obj("media")),
            mediaDefault = p.obj("media")?.str("default")?.takeIf { it in mediaSizes(p.obj("media")) },
        )
    }

    /** `printer.media.sizes` (plugin 1.1.0): PWG names only, at most 12. */
    private fun mediaSizes(m: JsonObject?): List<String> =
        m?.get("sizes")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.length <= 64 && MEDIA_RE.matches(it) } }
            ?.distinct()?.take(12).orEmpty()

    fun job(o: JsonObject?): PJob? {
        if (o == null) return null
        val id = o.str("id")?.takeIf { JOB_ID_RE.matches(it) } ?: return null
        val state = code(o.str("state")) ?: return null
        return PJob(
            id = id,
            name = text(o.str("name"), 200) ?: "GateControl",
            device = text(o.str("device"), 120),
            state = state,
            error = code(o.str("error")),
            message = o.str("message")?.takeIf { MESSAGE_RE.matches(it) },
            pages = (o.int("pages") ?: 1).coerceAtLeast(1),
            copies = (o.int("copies") ?: 1).coerceAtLeast(1),
            media = o.str("media")?.takeIf { it.length <= 64 && MEDIA_RE.matches(it) },
        )
    }

    /** `GET jobs/<id>` (plugin 1.1.0) → `{ job }`. */
    fun jobAnswer(o: JsonObject): PJob? = job(o.obj("job"))

    fun validMedia(m: String?): Boolean = m != null && m.length <= 64 && MEDIA_RE.matches(m)

    /** `GET overview`: rights, pages left, printers (only with the print right), the last jobs. */
    fun overview(o: JsonObject): POverview? {
        val r = o.obj("rights") ?: return null
        val rights = PRights(
            print = r.flag("print") ?: false,
            pagesPerDay = (r.int("pagesPerDay") ?: 0),
            bwOnly = r.flag("bwOnly") ?: false,
        )
        val printers = if (rights.print) list(o.get("printers")).mapNotNull(::printer).distinctBy { it.id } else emptyList()
        val jobs = list(o.get("jobs")).filter { it.str("kind") == null || it.str("kind") == "print" }.mapNotNull(::job)
        return POverview(rights, if (rights.print) o.int("pagesLeft") else null, printers, jobs)
    }

    /** `POST print` → `{ job, pagesLeft }`. */
    fun printed(o: JsonObject): PPrinted? {
        val job = job(o.obj("job")) ?: return null
        return PPrinted(job, o.int("pagesLeft"))
    }

    /** `PUT …/media` → `{ mediaId }`. */
    fun mediaId(o: JsonObject): String? = o.str("mediaId")?.takeIf { MEDIA_ID_RE.matches(it) }

    /** `POST jobs/<id>/cancel` → `{ canceled }`. */
    fun canceled(o: JsonObject): Boolean? = o.flag("canceled")

    fun validJobId(id: String?): Boolean = id != null && JOB_ID_RE.matches(id)

    fun validDeviceId(id: String?): Boolean = id != null && DEVICE_ID_RE.matches(id)
}
