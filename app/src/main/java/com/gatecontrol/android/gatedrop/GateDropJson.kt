package com.gatecontrol.android.gatedrop

import com.gatecontrol.android.chat.ChatJson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.time.Instant
import java.util.Base64

/**
 * Parses the GateDrop plugin's app API (contract B) and the GateDrop
 * pushes, and builds the request bodies. Tolerant on purpose: unknown
 * fields are ignored, missing ones get defaults and a few alternative
 * names are accepted, so small contract changes stay cheap. Everything the
 * server (or a push) sends is untrusted: ids, tokens and names are
 * validated and capped, LAN candidates must be private IPv4 addresses.
 * Pure (Gson tree only), unit-tested, no reflection (R8-safe).
 */
object GateDropJson {

    private val ID_RE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    /** Transfer ids and tickets go into URL paths and headers: url-safe only. */
    private val TOKEN_RE = Regex("^[A-Za-z0-9_-]{6,128}$")
    private val MEDIA_ID_RE = Regex("^[A-Za-z0-9_-]{8,64}$")
    private val MIME_RE = Regex("^[a-z0-9.+-]{1,40}/[A-Za-z0-9.+-]{1,120}$")
    private val IPV4_RE = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val MEDIA_URL_RE = Regex("/api/v1/client/plugins/gatecontrol-gatedrop/media/([A-Za-z0-9_-]{8,64})(?:[?#].*)?$")
    private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val NAME_FORBIDDEN_RE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    const val DEFAULT_MIME = "application/octet-stream"
    const val TEXT_MAX = 20_000
    const val NOTE_MAX = 500
    const val NAME_MAX = 180
    const val MAX_ITEMS = 20
    private const val MAX_LIST = 500
    private const val MAX_CANDIDATES = 8
    const val KEY_BYTES = 32

    // ─── small helpers ──────────────────────────────────────────────

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.prim(key: String): JsonPrimitive? = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.asString

    private fun JsonObject.firstStr(vararg keys: String): String? = keys.firstNotNullOfOrNull { str(it) }

    /** true/false, 1/0, "true"/"false". */
    private fun JsonObject.flag(key: String): Boolean? {
        val p = prim(key) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> runCatching { p.asDouble != 0.0 }.getOrNull()
            p.isString -> when (p.asString.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun JsonObject.long(key: String): Long? {
        val p = prim(key) ?: return null
        val d = when {
            p.isNumber -> runCatching { p.asDouble }.getOrNull()
            p.isString -> p.asString.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return if (d.isFinite() && d >= 0 && d <= 9.0E15) d.toLong() else null
    }

    private fun JsonElement?.objOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

    /** An id (number or string) as text; null when it is not a plain id. */
    fun idOf(e: JsonElement?): String? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val s = when {
            p.isString -> p.asString.trim()
            p.isNumber -> runCatching {
                p.asBigDecimal.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 }?.toBigInteger()?.toString()
            }.getOrNull()
            else -> null
        } ?: return null
        return s.takeIf { ID_RE.matches(it) }
    }

    private fun JsonObject.id(vararg keys: String): String? = keys.firstNotNullOfOrNull { idOf(get(it)) }

    /** A recipient id back to the server: digits as a number (user ids), anything else as text. */
    fun idElement(id: String): JsonPrimitive =
        if (id.length in 1..15 && id.all(Char::isDigit)) JsonPrimitive(id.toLong()) else JsonPrimitive(id)

    /** Single-line text, control characters removed, capped; blank → null. */
    fun line(value: String?, max: Int = 120): String? {
        if (value == null) return null
        return CONTROL_RE.replace(value, " ").trim().take(max).takeIf { it.isNotBlank() }
    }

    /** Multi-line text (newlines and tabs kept), capped; blank → null. */
    fun text(value: String?, max: Int = TEXT_MAX): String? {
        if (value == null) return null
        val clean = value.replace("\r\n", "\n").filter { it == '\n' || it == '\t' || (it >= ' ' && it != '\u007F') }
        return clean.take(max).takeIf { it.isNotBlank() }
    }

    fun token(value: String?): String? = value?.trim()?.takeIf { TOKEN_RE.matches(it) }

    fun mediaId(value: String?): String? = value?.trim()?.takeIf { MEDIA_ID_RE.matches(it) }

    fun mime(value: String?): String = value?.trim()?.lowercase()?.substringBefore(';')?.trim()?.takeIf { MIME_RE.matches(it) } ?: DEFAULT_MIME

    /**
     * A file name that is safe to save: no path, no control or reserved
     * characters, no leading dots, capped (extension kept); [fallback] when
     * nothing is left.
     */
    fun safeName(value: String?, fallback: String = "file"): String {
        val base = value?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
        var clean = NAME_FORBIDDEN_RE.replace(base, "_").trim().trimStart('.').trim()
        if (clean.length > NAME_MAX) {
            val ext = clean.substringAfterLast('.', "").take(16)
            clean = if (ext.isNotEmpty() && ext.length < clean.length) clean.take(NAME_MAX - ext.length - 1) + "." + ext else clean.take(NAME_MAX)
        }
        return clean.ifBlank { fallback }
    }

    /** An http(s) URL (a shared link, a created link); anything else → null. */
    fun webUrl(value: String?): String? {
        val v = value?.trim()?.takeIf { it.length in 8..2048 } ?: return null
        if (CONTROL_RE.containsMatchIn(v) || v.any { it.isWhitespace() }) return null
        val lower = v.lowercase()
        return v.takeIf { lower.startsWith("https://") || lower.startsWith("http://") }
    }

    /** Epoch ms from an ISO-8601 string or a number (ms; seconds below 1e11). */
    fun time(e: JsonElement?): Long? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return when {
            p.isNumber -> runCatching { p.asDouble }.getOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { if (it < 1e11) (it * 1000).toLong() else it.toLong() }
            p.isString -> {
                val s = p.asString.trim()
                s.toDoubleOrNull()?.let { return time(JsonPrimitive(it)) }
                runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(s.replace(' ', 'T') + if (s.endsWith("Z") || s.contains('+')) "" else "Z").toEpochMilli() }.getOrNull()
            }
            else -> null
        }
    }

    private fun JsonObject.time(vararg keys: String): Long? = keys.firstNotNullOfOrNull { time(get(it)) }

    /** The 32-byte transfer key (base64, standard or url-safe); null when it is not exactly 32 bytes. */
    fun key(value: String?): ByteArray? {
        val v = value?.trim()?.takeIf { it.length in 40..64 } ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(v) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(v) }.getOrNull()
            ?: return null
        return bytes.takeIf { it.size == KEY_BYTES }
    }

    // ─── LAN candidates ─────────────────────────────────────────────

    /** Private IPv4 (10/8, 172.16/12, 192.168/16) or link-local (169.254/16). */
    fun isPrivateIpv4(host: String): Boolean {
        val m = IPV4_RE.matchEntire(host) ?: return false
        val o = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (o.any { it > 255 } || m.groupValues.drop(1).any { it.length > 1 && it.startsWith("0") }) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254 && o[2] in 1..254)
    }

    /** `ip:port` (or `{ ip|host, port }`) of a private IPv4 listener; anything else → null. */
    fun candidate(e: JsonElement?): GdCandidate? {
        val (host, port) = when {
            e == null -> return null
            e.isJsonPrimitive && e.asJsonPrimitive.isString -> {
                val s = e.asString.trim()
                val i = s.lastIndexOf(':').takeIf { it > 0 } ?: return null
                s.substring(0, i) to s.substring(i + 1).toIntOrNull()
            }
            e.isJsonObject -> {
                val o = e.asJsonObject
                (o.firstStr("ip", "host", "address") ?: return null) to o.long("port")?.toInt()
            }
            else -> return null
        }
        if (port == null || port !in 1..65535) return null
        return GdCandidate(host, port).takeIf { isPrivateIpv4(host) }
    }

    fun candidates(a: JsonArray?): List<GdCandidate> =
        a?.mapNotNull { candidate(it) }?.distinct()?.take(MAX_CANDIDATES).orEmpty()

    // ─── errors ─────────────────────────────────────────────────────

    /** `{ ok:false, code }` / `{ error }` → a short code. */
    fun errorCode(o: JsonObject?): String? =
        (o?.str("code") ?: o?.str("error"))?.takeIf { it.length <= 60 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }

    /** The answer carries `ok:false`. */
    fun isRefusal(o: JsonObject): Boolean = o.flag("ok") == false

    // ─── targets ────────────────────────────────────────────────────

    fun targets(o: JsonObject?): GdTargets? {
        if (o == null) return null
        val root = o.obj("targets") ?: o
        val me = root.obj("me")
        val people = root.arr("people")?.mapNotNull { person(it.objOrNull()) }?.distinctBy { it.id }?.take(MAX_LIST).orEmpty()
        val devices = root.arr("devices")?.mapNotNull { device(it.objOrNull())?.copy(owner = null) }?.distinctBy { it.id }?.take(MAX_LIST).orEmpty()
        // Other people's devices need their owner; one listed as own as well stays own.
        val peopleDevices = root.arr("peopleDevices")?.mapNotNull { device(it.objOrNull())?.takeIf { d -> d.owner != null } }
            ?.distinctBy { it.id }?.filter { d -> devices.none { it.id == d.id } }?.take(MAX_LIST).orEmpty()
        val nas = root.arr("nas")?.mapNotNull { nas(it.objOrNull()) }?.distinctBy { it.id }?.take(MAX_LIST).orEmpty()
        val links = root.obj("links")
        return GdTargets(
            me = GdMe(canLink = me?.flag("canLink") == true, canNas = me?.flag("canNas") == true, receiveFrom = GdReceiveFrom.of(me?.str("receiveFrom"))),
            people = people,
            devices = devices,
            peopleDevices = peopleDevices,
            nas = nas,
            linksEnabled = links?.flag("enabled") ?: (root.flag("linksEnabled") == true),
        )
    }

    fun person(o: JsonObject?): GdPerson? {
        if (o == null) return null
        val kind = when (o.str("kind")?.lowercase()) {
            GdPerson.KIND_FAMILY -> GdPerson.KIND_FAMILY
            else -> GdPerson.KIND_USER
        }
        val id = o.id("id", "userId") ?: if (kind == GdPerson.KIND_FAMILY) GdPerson.FAMILY_ID else return null
        val name = line(o.firstStr("name", "displayName", "label"), 80) ?: return null
        return GdPerson(
            id = id,
            name = name,
            avatar = ChatJson.avatarPath(o.firstStr("avatar", "avatarUrl")),
            kind = kind,
            initials = line(o.str("initials"), 3),
            colorHex = o.str("colorHex")?.takeIf { COLOR_RE.matches(it) },
        )
    }

    fun device(o: JsonObject?): GdDevice? {
        if (o == null) return null
        val id = o.id("id", "deviceId") ?: return null
        return GdDevice(
            id = id,
            name = line(o.firstStr("name", "label"), 80) ?: return null,
            platform = o.str("platform")?.lowercase()?.takeIf { it.length <= 20 && it.all { c -> c.isLetterOrDigit() } },
            online = o.flag("online") == true,
            direct = o.flag("direct") == true,
            owner = o.obj("owner")?.let { w ->
                val ownerId = w.id("id", "userId") ?: return@let null
                GdOwner(ownerId, line(w.firstStr("name", "displayName"), 80) ?: return@let null)
            },
        )
    }

    /** `GET`/`PUT device` → this device's [GdReceiveFrom]; null when the answer has none. */
    fun receiveFrom(o: JsonObject?): String? = GdReceiveFrom.of((o?.obj("device") ?: o)?.str("receiveFrom"))

    /** Body of `PUT device`. */
    fun deviceBody(receiveFrom: String): JsonObject = JsonObject().apply { addProperty("receiveFrom", receiveFrom) }

    /**
     * Who sent it, as the recipient reads it: one's own device by its name ("Büro-PC"),
     * another person by theirs (`from.own == false`, GateDrop ≥ 1.2).
     */
    private fun senderOf(from: JsonObject): String? =
        if (from.flag("own") == false) from.firstStr("name", "deviceName", "device", "label")
        else from.firstStr("deviceName", "device", "name", "label")

    fun nas(o: JsonObject?): GdNas? {
        if (o == null) return null
        val id = o.id("id") ?: return null
        val path = line(o.str("path"), 300)
        return GdNas(id = id, label = line(o.firstStr("label", "name"), 80) ?: path ?: return null, path = path)
    }

    // ─── sends ──────────────────────────────────────────────────────

    /** Answer of `POST sends`. */
    fun sendResult(o: JsonObject?): GdSendResult? {
        if (o == null) return null
        val root = o.obj("send")?.takeIf { it.has("id") && !o.has("id") }?.let { s ->
            // `{ send: { id, … }, direct, link }` as well as the flat form.
            JsonObject().apply {
                s.entrySet().forEach { (k, v) -> add(k, v) }
                o.entrySet().filter { it.key != "send" }.forEach { (k, v) -> add(k, v) }
            }
        } ?: o
        val id = root.id("id", "sendId") ?: return null
        val link = root.obj("link")?.let { l -> webUrl(l.str("url"))?.let { GdLinkResult(it, pin(l.get("pin"))) } }
        return GdSendResult(
            id = id,
            deliveries = root.arr("deliveries")?.mapNotNull { delivery(it.objOrNull()) }?.take(MAX_LIST).orEmpty(),
            link = link,
            direct = root.arr("direct")?.mapNotNull { direct(it.objOrNull()) }?.distinctBy { it.transferId }?.take(MAX_LIST).orEmpty(),
        )
    }

    /** A link PIN: 4–8 digits (string or number). */
    fun pin(e: JsonElement?): String? {
        val p = e?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        val s = when {
            p.isString -> p.asString.trim()
            p.isNumber -> p.asString
            else -> return null
        }
        return s.takeIf { it.length in 4..8 && it.all(Char::isDigit) }
    }

    fun direct(o: JsonObject?): GdDirect? {
        if (o == null) return null
        return GdDirect(
            deviceId = o.id("deviceId", "device") ?: return null,
            transferId = token(o.firstStr("transferId", "transfer", "id")) ?: return null,
            mode = GdMode.fromWire(o.str("mode")) ?: return null,
            candidates = candidates(o.arr("candidates")),
            key = key(o.str("key")) ?: return null,
            ticket = token(o.str("ticket")) ?: return null,
        )
    }

    fun delivery(o: JsonObject?): GdDelivery? {
        if (o == null) return null
        val kind = o.str("kind")?.lowercase()?.takeIf { it.length <= 20 } ?: return null
        val target = o.get("target")
        val targetObj = target.objOrNull()
        return GdDelivery(
            kind = kind,
            target = idOf(target) ?: targetObj?.let { it.id("id") },
            targetName = line(o.firstStr("targetName", "name", "label") ?: targetObj?.firstStr("name", "label"), 80),
            status = o.str("status")?.lowercase()?.takeIf { it.length <= 20 } ?: GdDelivery.PENDING,
            via = o.str("via")?.lowercase()?.takeIf { it == "direct" || it == "server" },
            error = line(o.str("error"), 120),
        )
    }

    /** Answer of `POST transfers/<id>/result`: `fallback` = what the server wants now (`upload`), else null. */
    fun fallbackOf(o: JsonObject?): String? = o?.str("fallback")?.lowercase()?.takeIf { it.length <= 20 }

    /** Answer of `POST transfers/<id>/result`: `{ fallback: null|"server"|"upload", missing?: [indexes] }`. */
    private val ERROR_CODE_RE = Regex("^[a-z0-9_.-]{1,64}$")

    /**
     * `GET transfers/<id>` → its status (also after it ended, when key and
     * ticket are gone and [offer] has nothing); null when no transfer.
     */
    fun transferStatus(o: JsonObject?): GdTransferStatus? {
        val t = o?.obj("transfer") ?: o ?: return null
        if (!t.has("status") && !t.has("id")) return null
        val status = t.str("status")?.takeIf { it in setOf(GdTransferStatus.OPEN, GdTransferStatus.DONE, GdTransferStatus.FAILED, GdTransferStatus.EXPIRED) }
        val peer = t.obj("peer")
        val peerOk = peer?.prim("ok")?.takeIf { it.isBoolean }?.asBoolean
        val peerError = if (peerOk == false) peer?.str("error")?.takeIf { ERROR_CODE_RE.matches(it) } else null
        return GdTransferStatus(status, peerOk, peerError)
    }

    fun transferAnswer(o: JsonObject?): GdTransferAnswer = GdTransferAnswer(
        fallback = fallbackOf(o),
        missing = o?.arr("missing")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 0 until MAX_ITEMS } }?.distinct()?.sorted(),
    )

    fun item(o: JsonObject?): GdItem? {
        if (o == null) return null
        val media = mediaId(o.firstStr("mediaId", "id"))
        val nameRaw = o.firstStr("name", "fileName")
        if (media == null && nameRaw == null) return null
        return GdItem(
            mediaId = media,
            name = safeName(nameRaw),
            mime = mime(o.firstStr("mime", "type")),
            bytes = o.long("bytes") ?: o.long("size") ?: 0L,
            available = o.flag("available") != false,
        )
    }

    /** A send (inbox entry or `GET sends/<id>`). */
    fun entry(o: JsonObject?): GdEntry? {
        if (o == null) return null
        val root = o.obj("send") ?: o
        val id = root.id("id", "sendId") ?: return null
        val fromEl = root.get("from") ?: root.get("sender")
        val from = when {
            fromEl == null -> root.firstStr("fromName", "senderName")
            // An own device ("Büro-PC") names the sender better than the person behind it.
            fromEl.isJsonObject -> senderOf(fromEl.asJsonObject)
            fromEl.isJsonPrimitive && fromEl.asJsonPrimitive.isString -> fromEl.asString
            else -> null
        }
        val linkEl = root.get("link")
        return GdEntry(
            id = id,
            at = root.time("at", "createdAt", "created_at", "time", "sentAt"),
            from = line(from, 80),
            text = text(root.str("text")),
            // The shared link (a URL); a link for externals is an object and listed under "Links".
            link = webUrl(linkEl?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString),
            note = text(root.str("note"), NOTE_MAX),
            items = root.arr("items")?.mapNotNull { item(it.objOrNull()) }?.take(MAX_ITEMS * 5).orEmpty(),
            deliveries = root.arr("deliveries")?.mapNotNull { delivery(it.objOrNull()) }?.take(MAX_LIST).orEmpty(),
            opened = root.flag("opened") == true || root.str("status") == GdDelivery.OPENED,
            expired = root.flag("expired") == true,
        )
    }

    /** `GET inbox?kind=received|sent` → the sends; null when the answer holds no list. */
    fun entries(o: JsonObject?): List<GdEntry>? {
        if (o == null) return null
        val list = listOf("sends", "entries", "list", "received", "sent", "inbox", "items").firstNotNullOfOrNull { o.arr(it) } ?: return null
        return list.mapNotNull { entry(it.objOrNull()) }.distinctBy { it.id }.take(MAX_LIST)
    }

    /**
     * `GET pending` (GateDrop 1.1) → the sends waiting for this device, in
     * the server's order (oldest first); null when the answer holds no list.
     */
    fun pending(o: JsonObject?): List<GdEntry>? {
        if (o == null) return null
        val list = listOf("items", "sends", "pending").firstNotNullOfOrNull { o.arr(it) } ?: return null
        return list.mapNotNull { entry(it.objOrNull()) }.distinctBy { it.id }.take(MAX_LIST)
    }

    fun linkEntry(o: JsonObject?): GdLink? {
        if (o == null) return null
        val token = token(o.firstStr("token", "id")) ?: return null
        val expiresAt = o.time("expiresAt", "expires_at", "expires")
        val state = when {
            o.flag("revoked") == true -> GdLink.STATE_REVOKED
            o.flag("locked") == true -> GdLink.STATE_LOCKED
            o.flag("expired") == true -> GdLink.STATE_EXPIRED
            else -> when (o.firstStr("state", "status")?.lowercase()) {
                GdLink.STATE_REVOKED, "ended" -> GdLink.STATE_REVOKED
                GdLink.STATE_LOCKED -> GdLink.STATE_LOCKED
                // `used` (a once-link was fetched), `gone` (the send expired).
                GdLink.STATE_EXPIRED, "used", "gone" -> GdLink.STATE_EXPIRED
                GdLink.STATE_ACTIVE -> GdLink.STATE_ACTIVE
                else -> if (o.flag("active") == false) GdLink.STATE_EXPIRED else GdLink.STATE_ACTIVE
            }
        }
        return GdLink(
            token = token,
            url = webUrl(o.str("url")),
            title = line(o.firstStr("title", "name"), 120),
            expiresAt = expiresAt,
            downloads = (o.long("downloads") ?: o.long("downloadCount") ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            maxDownloads = o.long("maxDownloads")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            pin = o.flag("pin") == true || o.flag("hasPin") == true,
            state = state,
        )
    }

    /** `GET inbox?kind=links` → the links; null when the answer holds no list. */
    fun links(o: JsonObject?): List<GdLink>? {
        if (o == null) return null
        val list = listOf("links", "entries", "list", "items").firstNotNullOfOrNull { o.arr(it) } ?: return null
        return list.mapNotNull { linkEntry(it.objOrNull()) }.distinctBy { it.token }.take(MAX_LIST)
    }

    /** `{ ok, mediaId }` of the media upload. */
    fun uploadedMediaId(o: JsonObject?): String? = mediaId(o?.str("mediaId") ?: o?.obj("media")?.str("id"))

    /**
     * Answer to one part of a resumable upload (core 1.193):
     * `{ complete:false, uploadId, offset, total }` or
     * `{ complete:true, mediaId, mime, bytes }`; a 409 body `{ code, offset }`.
     */
    fun uploadPart(o: JsonObject?): GdUploadPart = GdUploadPart(
        complete = o?.flag("complete"),
        mediaId = uploadedMediaId(o),
        offset = o?.long("offset"),
        code = errorCode(o),
    )

    /** `{ offset }` of the resumable upload status. */
    fun uploadOffset(o: JsonObject?): Long? = o?.long("offset")

    // ─── pushes ─────────────────────────────────────────────────────

    /** The `gatedrop` object of a push's data (JSON text); null when it has none. */
    fun pushData(dataJson: String?): JsonObject? {
        if (dataJson.isNullOrBlank() || dataJson.length > 64_000) return null
        val o = runCatching { JsonParser.parseString(dataJson) }.getOrNull().objOrNull() ?: return null
        return o.obj("gatedrop")
    }

    /**
     * The silent transfer push (`gatedrop.transfer` present) or the answer
     * of `GET transfers/<id>` (`{ transfer: { id, sendId, … } }`); null for
     * anything else. `items: null` (too long for a push) gives an offer
     * without items: the receiver asks `GET transfers/<id>` ([needsDetails]).
     * Items are ordered by `index` and must be 0…n-1.
     */
    fun offer(o: JsonObject?): GdTransferOffer? {
        if (o == null) return null
        val outer = o.obj("gatedrop") ?: o
        val root = outer.obj("transfer") ?: outer
        val transferId = token(
            (if (root === outer) idOf(root.get("transfer")) else idOf(root.get("id"))) ?: root.firstStr("transferId"),
        ) ?: return null
        val raw = root.arr("items")?.mapIndexedNotNull { pos, e ->
            val io = e.objOrNull() ?: return@mapIndexedNotNull null
            val index = io.long("index")?.toInt() ?: pos
            index to GdOfferItem(
                name = safeName(io.firstStr("name", "fileName"), "file-${index + 1}"),
                bytes = io.long("bytes") ?: io.long("size") ?: return@mapIndexedNotNull null,
                mime = mime(io.firstStr("mime", "type")),
            )
        }?.sortedBy { it.first }.orEmpty()
        // The index is the position on the wire (X-GD-Item, …/<index>): it must be 0…n-1.
        if (raw.withIndex().any { (pos, it) -> it.first != pos } || raw.size > MAX_ITEMS) return null
        val fromEl = root.get("from")
        val from = when {
            fromEl == null -> null
            // An own device ("Büro-PC") names the sender better than the person.
            fromEl.isJsonObject -> senderOf(fromEl.asJsonObject)
            fromEl.isJsonPrimitive && fromEl.asJsonPrimitive.isString -> fromEl.asString
            else -> null
        }
        return GdTransferOffer(
            transferId = transferId,
            mode = GdMode.fromWire(root.str("mode")) ?: return null,
            from = line(from, 80),
            items = raw.map { it.second },
            candidates = candidates(root.arr("candidates")),
            key = key(root.str("key")) ?: return null,
            ticket = token(root.str("ticket")) ?: return null,
            sendId = idOf(root.get("send")) ?: idOf(root.get("sendId")) ?: root.obj("send")?.let { idOf(it.get("id")) },
            itemCount = root.long("itemCount")?.toInt()?.takeIf { it in 1..MAX_ITEMS } ?: raw.size,
        )
    }

    /** Transfer id of a transfer push (also a malformed one, to report it); null when there is none. */
    fun transferIdOf(o: JsonObject?): String? {
        if (o == null) return null
        val outer = o.obj("gatedrop") ?: o
        val root = outer.obj("transfer") ?: outer
        return token((if (root === outer) idOf(root.get("transfer")) else idOf(root.get("id"))) ?: root.firstStr("transferId"))
    }

    /** Send id of a server delivery push `{ gatedrop: { send } }`; null for a transfer push or anything else. */
    fun noticeSendId(o: JsonObject?): String? {
        // A transfer push also names its send; a link push (`{ link, send }`) is no delivery.
        if (o == null || o.has("transfer") || o.has("link")) return null
        return idOf(o.get("send")) ?: o.obj("send")?.let { idOf(it.get("id")) } ?: idOf(o.get("sendId"))
    }

    // ─── request bodies ─────────────────────────────────────────────

    /** Body of `POST sends`. */
    fun sendBody(items: List<GdSendItem>, text: String?, link: String?, note: String?, to: GdRecipients): JsonObject = JsonObject().apply {
        add("items", itemsArray(items))
        text(text)?.let { addProperty("text", it) }
        webUrl(link)?.let { addProperty("link", it) }
        text(note, NOTE_MAX)?.let { addProperty("note", it) }
        add("to", JsonObject().apply {
            add("people", JsonArray().apply { to.people.distinct().forEach { add(idElement(it)) } })
            // device ids are GateControl token ids: text, even when only digits (GateDrop ≤ 1.1.0 refused numbers)
            add("devices", JsonArray().apply { to.devices.distinct().forEach { add(JsonPrimitive(it)) } })
            add("nas", JsonArray().apply {
                to.nas.distinctBy { it.id }.forEach { n ->
                    add(JsonObject().apply {
                        add("id", idElement(n.id))
                        n.path?.let { addProperty("path", it) }
                    })
                }
            })
            to.link?.let { l ->
                add("link", JsonObject().apply {
                    addProperty("expires", l.expiry.wire)
                    addProperty("pin", l.pin)
                })
            }
        })
    }

    private fun itemsArray(items: List<GdSendItem>): JsonArray = JsonArray().apply {
        items.forEach { i ->
            add(JsonObject().apply {
                if (i.mediaId != null && !i.local) {
                    addProperty("mediaId", i.mediaId)
                    addProperty("name", i.name)
                } else {
                    addProperty("name", i.name)
                    addProperty("bytes", i.bytes)
                    addProperty("mime", i.mime)
                    addProperty("local", true)
                }
            })
        }
    }

    /** Body of `POST sends/<id>/items`: `{ items: [{ index, mediaId }] }` — the files uploaded after a failed direct transfer. */
    fun addItemsBody(items: Map<Int, String>): JsonObject = JsonObject().apply {
        add("items", JsonArray().apply {
            items.toSortedMap().forEach { (index, mediaId) ->
                add(JsonObject().apply {
                    addProperty("index", index)
                    addProperty("mediaId", mediaId)
                })
            }
        })
    }

    /** `{ item, url }` of `GET items/<mediaId>` → the media id to fetch from the apps' media route; null when none. */
    fun itemMediaId(o: JsonObject?): String? {
        val url = o?.str("url")
        val fromUrl = url?.let { MEDIA_URL_RE.find(it)?.groupValues?.get(1) }
        return mediaId(fromUrl) ?: mediaId(o?.obj("item")?.str("mediaId"))
    }

    /** Body of `PUT presence`. */
    fun presenceBody(lan: List<Pair<String, Int>>, port: Int?, listening: Boolean): JsonObject = JsonObject().apply {
        add("lan", JsonArray().apply {
            lan.distinct().forEach { (ip, prefix) ->
                add(JsonObject().apply {
                    addProperty("ip", ip)
                    addProperty("prefix", prefix)
                })
            }
        })
        port?.let { addProperty("port", it) }
        addProperty("listening", listening)
    }

    /** Body of `POST transfers/<id>/result`. */
    fun resultBody(ok: Boolean, bytes: Long? = null, error: String? = null): JsonObject = JsonObject().apply {
        addProperty("ok", ok)
        bytes?.let { addProperty("bytes", it) }
        error?.let { addProperty("error", it.take(60)) }
    }
}
