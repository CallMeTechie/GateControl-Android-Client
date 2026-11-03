package com.gatecontrol.android.scan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import retrofit2.Response

/**
 * The host's virus scan of a plugin file (`scan { state, blocked, finding }`, GateControl 1.223; Drive 1.19,
 * GateDrop 1.4, Mail 1.8, Messenger 1.11). There only when the host scans and knows a result: without the field (an
 * older host or plugin, an old file) nothing is shown and nothing changes. [blocked] decides, not [state]: a blocked
 * file is never opened, downloaded, previewed, shared or handed on.
 */
data class MediaScan(
    /** pending | clean | infected | unscannable | unchecked (an unknown state is kept as it came). */
    val state: String,
    val blocked: Boolean,
    /** What was found (`Eicar-Test-Signature`, …) or, with `unscannable`, why (`encrypted`, `too_large`, …). */
    val finding: String? = null,
    /** Mail: the host deleted the attachment at once — it can never be loaded again. */
    val removed: Boolean = false,
) {
    /** The mark of the file in a list, null when there is nothing to show (clean, unchecked, pending but open). */
    val badge: ScanBadge?
        get() = when {
            removed -> ScanBadge.REMOVED
            blocked -> when (state) {
                PENDING -> ScanBadge.CHECKING
                INFECTED -> ScanBadge.BLOCKED_MALWARE
                else -> ScanBadge.BLOCKED_UNCHECKABLE
            }
            state == INFECTED -> ScanBadge.WARN_MALWARE
            state == UNSCANNABLE -> ScanBadge.NOT_CHECKABLE
            else -> null
        }

    /** Marked as malware but not blocked: open only after asking. */
    val warn: Boolean get() = !blocked && !removed && state == INFECTED

    /** Not to be opened (blocked or removed). */
    val closed: Boolean get() = blocked || removed

    /** The code the host answers a blocked file's routes with (`423 …`, Mail `410`); null when the file is open. */
    val refusalCode: String?
        get() = when {
            removed -> MediaScanJson.ATTACHMENT_REMOVED
            !blocked -> null
            state == PENDING -> MediaScanJson.SCAN_PENDING
            state == INFECTED -> MediaScanJson.QUARANTINED
            else -> MediaScanJson.SCAN_BLOCKED
        }

    companion object {
        /** Which mark speaks for several files (the gravest first). */
        private val RANK = listOf(
            ScanBadge.REMOVED, ScanBadge.BLOCKED_MALWARE, ScanBadge.BLOCKED_UNCHECKABLE, ScanBadge.CHECKING,
            ScanBadge.WARN_MALWARE, ScanBadge.NOT_CHECKABLE,
        )

        /** The gravest of [scans] that has a mark (a send with several files shows one); null when none has. */
        fun worst(scans: Collection<MediaScan?>): MediaScan? =
            scans.filterNotNull().filter { it.badge != null }.minByOrNull { RANK.indexOf(it.badge) }

        const val PENDING = "pending"
        const val CLEAN = "clean"
        const val INFECTED = "infected"
        const val UNSCANNABLE = "unscannable"
        const val UNCHECKED = "unchecked"
    }
}

/** What a file's mark says ("Wird geprüft …", "Gesperrt – Schadsoftware", …). */
enum class ScanBadge {
    /** `pending` + blocked: "Wird geprüft …". */
    CHECKING,

    /** `infected` + blocked: "Gesperrt – Schadsoftware". */
    BLOCKED_MALWARE,

    /** `unscannable` (or anything else) + blocked: "Gesperrt – nicht prüfbar". */
    BLOCKED_UNCHECKABLE,

    /** `infected`, only marked: "Warnung – Schadsoftware". */
    WARN_MALWARE,

    /** `unscannable`, open: quietly "Nicht prüfbar". */
    NOT_CHECKABLE,

    /** Mail: "Anhang entfernt (Schadsoftware)". */
    REMOVED,
    ;

    /** Blocked or removed: shown instead of open / download / share. */
    val closed: Boolean get() = this == CHECKING || this == BLOCKED_MALWARE || this == BLOCKED_UNCHECKABLE || this == REMOVED
}

/**
 * A refusal because of the scan: the `code` of `{ ok: false, code }` (Drive, GateDrop, Messenger, media routes) or
 * `{ error: { code } }` (Mail), with what the body adds (`finding`, GateDrop's `name`, Mail's `attachmentId`, `scan`).
 */
data class ScanRefusal(
    val code: String,
    val http: Int,
    val finding: String? = null,
    val name: String? = null,
    val attachmentId: Long? = null,
    val scan: MediaScan? = null,
) {
    /** `423 scan_pending`: not an error — the file is being checked, ask again later. */
    val pending: Boolean get() = code == MediaScanJson.SCAN_PENDING
}

/** Reading the scan fields and refusals of the plugins' answers (contract "Virenscan – Ergänzung für die Apps"). */
object MediaScanJson {
    const val SCAN_PENDING = "scan_pending"
    const val QUARANTINED = "quarantined"
    const val SCAN_BLOCKED = "scan_blocked"
    const val INFECTED = "infected"
    const val SCAN_UNAVAILABLE = "scan_unavailable"
    const val ATTACHMENT_REMOVED = "attachment_removed"
    const val ATTACHMENT_BLOCKED = "attachment_blocked"

    /** Every code the scan adds; anything else is not the scan's. */
    val CODES = setOf(SCAN_PENDING, QUARANTINED, SCAN_BLOCKED, INFECTED, SCAN_UNAVAILABLE, ATTACHMENT_REMOVED, ATTACHMENT_BLOCKED)

    /** A refusal that will not go away by asking again (the file is blocked or was rejected). */
    val FINAL = setOf(QUARANTINED, SCAN_BLOCKED, INFECTED, ATTACHMENT_REMOVED, ATTACHMENT_BLOCKED)

    private val STATE = Regex("^[a-z_]{1,20}$")

    fun isScanCode(code: String?): Boolean = code != null && code in CODES

    /** `scan` as an object; null when missing or not understood (then nothing is shown). */
    fun scan(e: JsonElement?): MediaScan? {
        val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val state = o.text("state")?.lowercase()?.takeIf { STATE.matches(it) } ?: return null
        return MediaScan(
            state = state,
            blocked = o.flag("blocked") == true,
            finding = finding(o.text("finding")),
            removed = o.flag("removed") == true,
        )
    }

    /** `scan` of an object (a node, an item, an attachment, a message's media). */
    fun of(o: JsonObject?): MediaScan? = scan(o?.get("scan"))

    /**
     * Mail: the attachment's `scan`, with `removed` read from the scan or the attachment itself (`removed: true` without
     * a scan still means the host deleted it).
     */
    fun attachment(o: JsonObject?): MediaScan? {
        val s = of(o)
        val removed = o?.flag("removed") == true
        return when {
            s != null -> if (removed) s.copy(removed = true) else s
            removed -> MediaScan(MediaScan.INFECTED, blocked = true, removed = true)
            else -> null
        }
    }

    /** [scan] as stored on the device (read back by [scan]). */
    fun toJson(scan: MediaScan): JsonObject = JsonObject().apply {
        addProperty("state", scan.state)
        addProperty("blocked", scan.blocked)
        scan.finding?.let { addProperty("finding", it) }
        if (scan.removed) addProperty("removed", true)
    }

    /** The state a refusal tells about its file: the body's `scan`, else what the code says. */
    fun scanOf(r: ScanRefusal): MediaScan? = (r.scan ?: stateOf(r)).let { s -> if (r.code == ATTACHMENT_REMOVED && s != null && !s.removed) s.copy(removed = true) else s }

    private fun stateOf(r: ScanRefusal): MediaScan? = when (r.code) {
        SCAN_PENDING -> MediaScan(MediaScan.PENDING, blocked = true)
        QUARANTINED -> MediaScan(MediaScan.INFECTED, blocked = true, finding = r.finding)
        SCAN_BLOCKED -> MediaScan(MediaScan.UNSCANNABLE, blocked = true)
        ATTACHMENT_REMOVED -> MediaScan(MediaScan.INFECTED, blocked = true, finding = r.finding, removed = true)
        else -> null
    }

    /** A finding as shown: one line, at most 200 characters. */
    fun finding(v: String?): String? = v?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

    /**
     * The scan's refusal in an error body ([http] the status); null when the code is not the scan's. Reads
     * `{ ok: false, code, finding, name, scan }` and `{ error: { code, message, scan, attachmentId, name } }`.
     */
    fun refusal(http: Int, body: JsonObject?): ScanRefusal? {
        if (body == null) return null
        val err = body.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        val code = err?.text("code") ?: body.text("code") ?: return null
        if (code !in CODES) return null
        val scan = of(err) ?: of(body)
        return ScanRefusal(
            code = code,
            http = http,
            finding = finding(err?.text("finding") ?: body.text("finding")) ?: scan?.finding,
            name = (err?.text("name") ?: body.text("name"))?.trim()?.take(255)?.takeIf { it.isNotEmpty() },
            attachmentId = err?.long("attachmentId") ?: body.long("attachmentId"),
            scan = scan,
        )
    }

    /** An error body as JSON (at most 64 KB); null when there is none or it is not an object. */
    fun parse(text: String?): JsonObject? = runCatching {
        text?.takeIf { it.length < 65_536 }?.let { JsonParser.parseString(it).takeIf { e -> e.isJsonObject }?.asJsonObject }
    }.getOrNull()

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.flag(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
}

/** How long to wait for a file that is still being checked (`423 scan_pending` + `Retry-After`). */
object ScanRetry {
    /** Media loads: asked again at most this many times (≈ half a minute with the server's 5 s). */
    const val PENDING_TRIES = 6

    /** GateDrop sends: asked again every [SEND_EVERY_MS] for at most [SEND_WAIT_MS] (like the portal: 5 s, 10 min). */
    const val SEND_EVERY_MS = 5_000L
    const val SEND_WAIT_MS = 10 * 60_000L

    private const val DEFAULT_S = 5L
    private const val MAX_S = 30L

    /** `Retry-After` in seconds (1–30; missing or a date: 5) as milliseconds. */
    fun delayMs(retryAfter: String?): Long = (retryAfter?.trim()?.toLongOrNull() ?: DEFAULT_S).coerceIn(1, MAX_S) * 1000
}

/** A plugin media answer: the response and — when it was refused — what the scan says about it. */
class MediaAnswer<T>(val response: Response<T>, val refusal: ScanRefusal?) {
    /** A short code for a refusal: the scan's, else `http_<status>`. */
    val code: String get() = refusal?.code ?: "http_${response.code()}"
}

/** The media routes (`GET …/plugins/<id>/media/<mediaId>`, `/preview`) with the scan's `423`. */
object MediaScanHttp {
    /**
     * [request] once, and again after `Retry-After` while it answers `423 scan_pending` (at most [tries] times); null
     * when [request] got no answer. The error body of a refusal is read (and closed) here.
     */
    suspend fun <T> get(
        tries: Int = ScanRetry.PENDING_TRIES,
        wait: suspend (Long) -> Unit = { delay(it) },
        request: suspend () -> Response<T>?,
    ): MediaAnswer<T>? {
        var left = tries
        while (true) {
            val r = request() ?: return null
            if (r.isSuccessful) return MediaAnswer(r, null)
            val refusal = refusalOf(r)
            if (refusal?.pending != true || left <= 0) return MediaAnswer(r, refusal)
            left--
            wait(ScanRetry.delayMs(r.headers()["Retry-After"]))
        }
    }

    /** The scan's refusal of a failed answer (reads and closes its error body); null for any other failure. */
    fun refusalOf(r: Response<*>): ScanRefusal? {
        if (r.isSuccessful || r.code() !in SCAN_STATUSES) {
            r.errorBody()?.close()
            return null
        }
        val text = runCatching { r.errorBody()?.use { it.string() } }.getOrNull()
        return MediaScanJson.refusal(r.code(), MediaScanJson.parse(text))
    }

    /** Statuses the scan answers with: 409 attachment_blocked, 410 attachment_removed, 422 infected, 423, 503. */
    private val SCAN_STATUSES = setOf(409, 410, 422, 423, 503)
}
