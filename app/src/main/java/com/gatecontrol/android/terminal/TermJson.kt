package com.gatecontrol.android.terminal

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.time.Instant

/**
 * An SSH device of the plugin „Fernzugriff“ for this person (`GET …/api/devices`, app contract stage 3 §1).
 * [engine]: `term` opens the native terminal, `guac` (or none: older plugin/server) only the browser.
 * [tmux]: the device's own tmux session, null without tmux. [recording]: `off` | `optional` | `always`.
 */
data class SshDevice(
    val id: Long,
    val name: String,
    val description: String?,
    val online: Boolean,
    val engine: String?,
    val tmux: String?,
    val recording: String,
    val maintenance: Boolean = false,
    val credentialUnreadable: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    val native: Boolean get() = engine == TermJson.ENGINE_TERM
}

/** The SSH devices and whether the person has the right `ssh` at all. */
data class SshDevices(val devices: List<SshDevice>, val sshRight: Boolean)

/** One window of a tmux session (`?windows=1`, plugin 1.8): [command] is what runs in its active pane. */
data class TmuxWindow(val id: String, val index: Int, val name: String, val active: Boolean, val command: String?)

/**
 * One tmux session on the device (`GET …/devices/<id>/tmux`). With `?windows=1` and `kill: true` (plugin 1.8) also
 * [id] (`$n`), [windowList] and [rawName]/[created] exactly as listed — the server compares them before it ends one.
 */
data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Int,
    val activity: Long?,
    val attachable: Boolean,
    val id: String? = null,
    val windowList: List<TmuxWindow> = emptyList(),
    val rawName: String = name,
    val created: JsonPrimitive? = null,
)

/**
 * The tmux sessions; [hostKey] `pinned` | `unpinned` (no list before the first connection). [kill]: the server can end
 * sessions and close windows (plugin 1.8 with GateControl `gc.remote` version 9); false on older ones — no buttons.
 */
data class TmuxList(val sessions: List<TmuxSession>, val tmux: String?, val hostKey: String?, val kill: Boolean = false) {
    /** Sessions this app can end: only with [kill] and a session number. */
    fun killable(s: TmuxSession): Boolean = kill && s.id != null

    /** The sessions nobody is attached to (for „Alle getrennten Sitzungen beenden“). */
    val detached: List<TmuxSession> get() = sessions.filter { killable(it) && it.attached == 0 }
}

/** What to do with tmux: attach to a running session, create a named one, or the device's own (null). */
sealed interface TmuxChoice {
    data class Attach(val name: String) : TmuxChoice
    data class Create(val name: String) : TmuxChoice
}

/** A ticket of engine term (`POST …/devices/<id>/connect` with `via: 'app'`). Never logged. */
data class TermTicket(val ref: String, val expiresAt: Long, val recording: Boolean, val tmux: String?, val deviceId: Long, val deviceName: String)

/** A single-use token of the core (`POST /api/v1/client/remote/term-token`). Never logged. */
data class TermToken(val token: String, val wsPath: String)

/**
 * Parsing of the plugin's and the core's answers for the SSH terminal. Untrusted input: every field is checked, a
 * missing or wrong one gives null (or leaves the entry out). Pure Kotlin — unit tested.
 */
object TermJson {
    const val ENGINE_TERM = "term"
    /** tmux names the core can attach (contract §2); the same rule as the plugin. */
    val TMUX_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")
    /** The base64url refs and tokens of GateControl (32 random bytes). */
    private val REF = Regex("^[A-Za-z0-9_-]{43}$")
    private val WS_PATH = Regex("^/api/v1/remote/[a-z-]{1,40}$")
    private const val MAX_DEVICES = 500

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.num(k: String): Number? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun time(v: String?): Long? = v?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** A time as ISO text or as a number (seconds, or ms when that large) — tmux itself gives seconds. */
    private fun JsonObject.stamp(k: String): Long? = num(k)?.toDouble()?.takeIf { it > 0 && it < 9.0e15 }
        ?.let { if (it < 1.0e11) (it * 1000).toLong() else it.toLong() } ?: time(str(k))

    private val SESSION_ID = Regex("^\\$\\d{1,6}$")
    private val WINDOW_ID = Regex("^@\\d{1,6}$")

    /** One line of text without control characters. */
    fun clean(v: String?, max: Int = 100): String? =
        v?.filter { it >= ' ' && it != '\u007f' && !Character.isISOControl(it) }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    private fun id(o: JsonObject, k: String): Long? = o.num(k)?.toDouble()?.takeIf { it >= 1 && it <= 9.0e15 && it == Math.floor(it) }?.toLong()

    fun device(o: JsonObject): SshDevice? {
        if (o.str("protocol") != "ssh") return null
        val id = id(o, "id") ?: return null
        val name = clean(o.str("name")) ?: return null
        val engine = o.str("engine")?.takeIf { it == ENGINE_TERM || it == "guac" }
        val tmux = if (engine == ENGINE_TERM) o.str("tmux")?.takeIf { TMUX_NAME.matches(it) } else null
        val rec = o.str("recording")?.takeIf { it == "off" || it == "optional" || it == "always" } ?: "off"
        val tags = o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let { clean(it.asString, 40) } }?.take(20).orEmpty()
        return SshDevice(
            id = id, name = name, description = clean(o.str("description"), 200), online = o.bool("online") == true,
            engine = engine, tmux = tmux, recording = rec, maintenance = o.bool("maintenance") == true,
            credentialUnreadable = o.bool("credentialUnreadable") == true, tags = tags,
        )
    }

    /** `GET devices` → the SSH devices (others are not ours here) and the right `ssh`. */
    fun devices(body: JsonObject): SshDevices? {
        val arr = body.get("devices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val list = arr.asSequence().mapNotNull { e -> e.takeIf { it.isJsonObject }?.let { device(it.asJsonObject) } }
            .distinctBy { it.id }.take(MAX_DEVICES).toList()
        val right = body.obj("rights")?.bool("ssh") ?: list.isNotEmpty()
        return SshDevices(list, right)
    }

    fun tmux(body: JsonObject): TmuxList? {
        val arr = body.get("sessions")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val kill = body.bool("kill") == true
        val sessions = arr.asSequence().mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val raw = o.str("name")?.takeIf { it.length <= 256 }
            val name = clean(raw, 64) ?: return@mapNotNull null
            val windowList = o.get("windowList")?.takeIf { it.isJsonArray }?.asJsonArray?.let { windows(it) }.orEmpty()
            TmuxSession(
                name = name,
                windows = o.num("windows")?.toInt()?.coerceIn(0, 9999) ?: windowList.size,
                attached = o.num("attached")?.toInt()?.coerceIn(0, 9999) ?: 0,
                activity = o.stamp("activity"),
                // only names the core can attach are ever offered (contract §2)
                attachable = o.bool("attachable") == true && TMUX_NAME.matches(name),
                id = if (kill) o.str("id")?.takeIf { SESSION_ID.matches(it) } else null,
                windowList = if (kill) windowList else emptyList(),
                rawName = raw ?: name,
                created = o.get("created")?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }?.asJsonPrimitive,
            )
        }.take(200).toList()
        val hostKey = body.str("hostKey")?.takeIf { it == "pinned" || it == "unpinned" }
        return TmuxList(sessions, body.str("tmux")?.takeIf { TMUX_NAME.matches(it) }, hostKey, kill)
    }

    /** The windows of a session, by index; a window without a valid number is left out (it cannot be closed). */
    private fun windows(arr: JsonArray): List<TmuxWindow> = arr.asSequence().mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o.str("id")?.takeIf { WINDOW_ID.matches(it) } ?: return@mapNotNull null
        val index = o.num("index")?.toDouble()?.takeIf { it >= 0 && it <= 99_999 && it == Math.floor(it) }?.toInt() ?: return@mapNotNull null
        TmuxWindow(id, index, clean(o.str("name"), 64) ?: "", o.bool("active") == true, clean(o.str("command"), 64))
    }.distinctBy { it.id }.take(500).sortedBy { it.index }.toList()

    /** `session` of `POST …/tmux/kill` and of the list of `…/tmux/kill-detached`: number, name and creation as listed. */
    fun killTarget(s: TmuxSession): JsonObject = JsonObject().apply {
        addProperty("id", s.id)
        addProperty("name", s.rawName)
        add("created", s.created ?: JsonNull.INSTANCE)
    }

    /** The body of `POST devices/<id>/tmux/kill`: the session, and with [window] only that window. */
    fun killBody(s: TmuxSession, window: TmuxWindow? = null): JsonObject = JsonObject().apply {
        add("session", killTarget(s))
        if (window != null) {
            add(
                "window",
                JsonObject().apply {
                    addProperty("id", window.id)
                    addProperty("index", window.index)
                },
            )
        }
    }

    /** The body of `POST devices/<id>/tmux/kill-detached` (1–50 sessions). */
    fun killDetachedBody(sessions: List<TmuxSession>): JsonObject = JsonObject().apply {
        add("sessions", JsonArray().apply { sessions.take(50).forEach { add(killTarget(it)) } })
    }

    /** `POST devices/<id>/connect` → the ticket of the native terminal; any other engine is not ours. */
    fun ticket(body: JsonObject, now: Long = System.currentTimeMillis()): TermTicket? {
        val s = body.obj("session") ?: return null
        if (s.str("engine") != ENGINE_TERM) return null
        val ref = s.str("ref")?.takeIf { REF.matches(it) } ?: return null
        val t = s.obj("tmux")
        val tmux = (t?.str("attach") ?: t?.str("create"))?.takeIf { TMUX_NAME.matches(it) }
        val d = s.obj("device")
        return TermTicket(
            ref = ref, expiresAt = time(s.str("expiresAt")) ?: (now + 120_000L), recording = s.bool("recording") == true,
            tmux = tmux, deviceId = d?.let { id(it, "id") } ?: 0L, deviceName = clean(d?.str("name")) ?: "",
        )
    }

    fun token(body: JsonObject): TermToken? {
        if (body.bool("ok") == false) return null
        val token = body.str("token")?.takeIf { REF.matches(it) } ?: return null
        val path = body.str("wsPath")?.takeIf { WS_PATH.matches(it) } ?: return null
        return TermToken(token, path)
    }

    /** The body of `POST devices/<id>/connect` (contract §2.4); `record` only when the policy is not `off`. */
    fun connectBody(tmux: TmuxChoice?, recordingPolicy: String, record: Boolean): JsonObject = JsonObject().apply {
        addProperty("via", "app")
        addProperty("protocol", "ssh")
        when (tmux) {
            is TmuxChoice.Attach -> add("tmux", JsonObject().apply { addProperty("attach", tmux.name) })
            is TmuxChoice.Create -> add("tmux", JsonObject().apply { addProperty("create", tmux.name) })
            null -> Unit
        }
        when (recordingPolicy) {
            "always" -> addProperty("record", true)
            "optional" -> addProperty("record", record)
        }
    }
}
