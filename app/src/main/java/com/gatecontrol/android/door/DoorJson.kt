package com.gatecontrol.android.door

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Base64

/**
 * Parses the answers of the door plugin's app API (README "App API").
 * Everything is untrusted: ids must be positive, enums known, texts plain
 * and capped; a malformed lock or camera is dropped, a malformed answer is
 * null. Pure (Gson tree only), unit-tested.
 */
object DoorJson {

    /** A snapshot is ≤ 300 KB decoded; anything far bigger is refused. */
    const val MAX_IMAGE_BYTES = 1024 * 1024
    private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")
    private val HHMM_RE = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
    private val CODE_RE = Regex("^[a-z][a-z0-9_]{0,40}$")
    private val TOKEN_RE = Regex("^[a-z][a-z0-9_-]{0,40}$")
    private val TIME_RE = Regex("^[0-9TZ:+.-]{10,40}$")
    private val CONTROL_RE = Regex("[\\u0000-\\u001F\\u007F]")
    private val LOCK_STATES = setOf(
        "locked", "unlocked", "unlatched", "locking", "unlocking", "unlatching", "jammed", "uncalibrated",
        "online", "rto_active", "open", "opening", "untrained", "boot_run", "unknown",
    )
    private val DOOR_STATES = setOf("open", "closed", "unknown", "deactivated", "calibrating", "uncalibrated", "removed")

    // ─── small helpers ──────────────────────────────────────────────

    fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.prim(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.asString

    /** Positive integer id (number or digit string). */
    fun JsonObject.id(key: String): Long? {
        val p = prim(key) ?: return null
        val n = when {
            p.isNumber -> p.asBigDecimal.takeIf { it.signum() > 0 && it.stripTrailingZeros().scale() <= 0 }?.toLong()
            p.isString -> p.asString.takeIf { it.length in 1..15 && it.all(Char::isDigit) }?.toLong()
            else -> null
        }
        return n?.takeIf { it > 0 }
    }

    private fun JsonObject.int(key: String): Int? {
        val p = prim(key)?.takeIf { it.isNumber } ?: return null
        val d = p.asDouble
        return if (d.isFinite() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** true/false, also 1/0 (SQLite flags). */
    fun JsonObject.flag(key: String): Boolean? {
        val p = prim(key) ?: return null
        return when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asDouble != 0.0
            else -> null
        }
    }

    private fun JsonObject.arr(key: String) = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonElement.objOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    /** Plain single-line text, capped; blank → null. */
    fun text(value: String?, max: Int = 80): String? {
        if (value == null) return null
        return CONTROL_RE.replace(value, " ").trim().take(max).takeIf { it.isNotBlank() }
    }

    private fun time(value: String?): String? = value?.trim()?.takeIf { TIME_RE.matches(it) }

    private fun token(value: String?): String? = value?.takeIf { TOKEN_RE.matches(it) }

    // ─── errors ─────────────────────────────────────────────────────

    /** `{ok:false, code}` → code (also the older `error` code field). */
    fun errorCode(body: JsonObject?): String? =
        body?.str("code")?.takeIf { CODE_RE.matches(it) } ?: body?.str("error")?.takeIf { CODE_RE.matches(it) }

    /** Human text of an error (`error` when it is not a code). */
    fun errorText(body: JsonObject?): String? =
        body?.str("error")?.takeIf { !CODE_RE.matches(it) }?.let { text(it, 200) }

    fun retryAfter(body: JsonObject?): Int? = body?.int("retryAfter")?.takeIf { it in 1..86_400 }

    fun errorField(body: JsonObject?): String? = body?.str("field")?.let { text(it, 60) }

    // ─── me ─────────────────────────────────────────────────────────

    fun overview(o: JsonObject): DoorOverview? {
        val me = o.obj("me")?.let(::person) ?: return null
        val ring = o.obj("ring")
        return DoorOverview(
            me = me,
            locks = o.arr("locks")?.mapNotNull { it.objOrNull()?.let(::lock) }?.distinctBy { it.id }.orEmpty(),
            cameras = o.arr("cameras")?.mapNotNull { it.objOrNull()?.let(::camera) }?.distinctBy { it.id }.orEmpty(),
            ring = DoorRing(connected = ring?.flag("connected") == true, broken = ring?.flag("broken") == true),
            serverTime = time(o.str("serverTime")),
        )
    }

    fun person(o: JsonObject): DoorPerson? {
        val userId = o.id("userId") ?: return null
        return DoorPerson(
            userId = userId,
            name = text(o.str("name")) ?: "",
            // Security first: unknown means no.
            canAct = o.flag("canAct") == true,
            autoUnlock = o.flag("autoUnlock") == true,
            window = o.obj("window")?.let(::window),
            windowOpen = o.flag("windowOpen") ?: true,
        )
    }

    fun window(o: JsonObject): DoorWindow? {
        val days = o.arr("days")?.mapNotNull { e ->
            e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 1..7 }
        }?.distinct()?.sorted() ?: return null
        val from = o.str("from")?.takeIf { HHMM_RE.matches(it) } ?: return null
        val to = o.str("to")?.takeIf { HHMM_RE.matches(it) } ?: return null
        return DoorWindow(days, from, to)
    }

    fun lock(o: JsonObject): DoorLock? {
        val id = o.id("id") ?: return null
        val type = if (o.str("type") == DoorLock.TYPE_OPENER) DoorLock.TYPE_OPENER else DoorLock.TYPE_SMARTLOCK
        val right = o.str("right")?.takeIf { it in DoorRight.ALL } ?: return null
        val actions = actionList(o.arr("actions"))
        val blockedEl = o.get("blocked")
        val blocked = when {
            blockedEl == null || blockedEl.isJsonNull -> null
            // Any other value (future reasons, garbage) disables the buttons too.
            else -> o.str("blocked")?.let(::token) ?: "unknown"
        }
        val state = o.obj("state")
        val doorSensor = o.flag("doorSensor") == true
        return DoorLock(
            id = id,
            name = text(o.str("name")) ?: "#$id",
            type = type,
            right = right,
            actions = actions,
            blocked = blocked,
            confirm = actionList(o.arr("confirm")),
            doorSensor = doorSensor,
            state = lockState(state, doorSensor),
        )
    }

    private fun actionList(arr: com.google.gson.JsonArray?): List<String> =
        arr?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it in DoorAction.ALL } }
            ?.distinct()
            // Fixed order on the card: zusperren, aufsperren, öffnen.
            ?.sortedBy { DoorAction.ALL.indexOf(it) }
            .orEmpty()

    fun lockState(o: JsonObject?, doorSensor: Boolean): DoorLockState = DoorLockState(
        lock = o?.str("lock")?.takeIf { it in LOCK_STATES } ?: "unknown",
        door = if (!doorSensor) null else o?.str("door")?.takeIf { it in DOOR_STATES } ?: "unknown",
        batteryCritical = o?.flag("batteryCritical") == true,
        batteryPercent = o?.int("batteryPercent")?.takeIf { it in 0..100 },
        batteryCharging = o?.flag("batteryCharging") == true,
        reachable = o?.flag("reachable") == true,
        updatedAt = time(o?.str("updatedAt")),
    )

    fun camera(o: JsonObject): DoorCamera? {
        val id = o.id("id") ?: return null
        val prefs = o.obj("prefs")
        return DoorCamera(
            id = id,
            name = text(o.str("name")) ?: "#$id",
            kind = text(o.str("kind"), 40),
            doorbell = o.flag("doorbell") == true,
            lockId = o.id("lockId"),
            online = o.flag("online"),
            battery = o.int("battery")?.takeIf { it in 0..100 },
            prefs = DoorCameraPrefs(ding = prefs?.flag("ding") ?: true, motion = prefs?.flag("motion") ?: false),
            lastImageAt = time(o.str("lastImageAt")),
        )
    }

    fun prefs(o: JsonObject): DoorCameraPrefs? {
        val p = o.obj("prefs") ?: return null
        return DoorCameraPrefs(ding = p.flag("ding") ?: return null, motion = p.flag("motion") ?: return null)
    }

    // ─── actions ────────────────────────────────────────────────────

    fun actionResult(o: JsonObject): DoorActionResult =
        DoorActionResult(message = text(o.str("message"), 200), lock = o.obj("lock")?.let(::lock))

    // ─── pictures ───────────────────────────────────────────────────

    /** `{mime, size, at, data}` → picture, null when missing, too big or not base64. */
    fun image(o: JsonObject?): DoorImage? {
        if (o == null) return null
        val mime = o.str("mime")?.takeIf { it in IMAGE_MIMES } ?: return null
        val data = o.str("data") ?: return null
        if (data.length > MAX_IMAGE_BYTES * 4 / 3 + 16) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
        return DoorImage(mime, time(o.str("at")), bytes)
    }

    fun snapshot(o: JsonObject): DoorSnapshot? {
        val s = o.obj("snapshot") ?: return null
        val image = image(s) ?: return null
        return DoorSnapshot(s.id("cameraId") ?: 0L, image)
    }

    // ─── history ────────────────────────────────────────────────────

    fun historyItem(o: JsonObject): DoorHistoryItem? {
        val id = o.id("id") ?: return null
        val kind = o.str("kind")?.takeIf { it in DoorHistoryItem.KINDS } ?: return null
        return DoorHistoryItem(
            id = id,
            at = time(o.str("at")),
            kind = kind,
            lockId = o.id("lockId"),
            lockName = text(o.str("lockName")),
            cameraId = o.id("cameraId"),
            cameraName = text(o.str("cameraName")),
            userId = o.id("userId"),
            userName = text(o.str("userName")),
            via = token(o.str("via")),
            caller = o.str("caller")?.takeIf { it.length <= 64 && Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$").matches(it) },
            action = token(o.str("action")),
            result = o.str("result")?.takeIf { it == "ok" || it == "refused" || it == "failed" },
            reason = token(o.str("reason")),
            reasonText = text(o.str("reasonText"), 160),
            hasImage = o.flag("hasImage") == true,
        )
    }

    fun history(o: JsonObject): DoorHistoryPage? {
        val items = o.arr("items") ?: return null
        return DoorHistoryPage(
            items = items.mapNotNull { it.objOrNull()?.let(::historyItem) }.distinctBy { it.id },
            nextCursor = o.id("nextCursor"),
        )
    }

    fun event(o: JsonObject): DoorEventDetail? {
        val e = o.obj("event") ?: return null
        val item = historyItem(e) ?: return null
        return DoorEventDetail(item, image(e.obj("image")), e.obj("lock")?.let(::lock))
    }
}
