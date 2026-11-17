package com.gatecontrol.android.terminal

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The protocol `gc-term.v1` of GateControl's SSH terminal (app contract stage 3 §3) and the rules of the app around
 * it: which error ends for good, the backoff of a reconnect, tmux names, the fingerprint in groups, pasting. Text
 * frames are JSON, binary frames the bytes of the terminal. Pure Kotlin — unit tested.
 */
object TermProtocol {
    const val PROTOCOL = "gc-term.v1"
    const val TERM = "xterm-256color"
    const val PING_SECONDS = 25L
    /** Seconds between the attempts after a lost socket (contract §5, design „1, 2, 4, 8 und 15 Sekunden“). */
    val BACKOFF_SECONDS = listOf(1, 2, 4, 8, 15)

    /** `Sec-WebSocket-Protocol: gc-term.v1, token.<token>` — the token never goes into the URL. */
    fun protocolHeader(token: String): String = "$PROTOCOL, token.$token"

    private fun frame(t: String, fill: JsonObject.() -> Unit = {}): String = JsonObject().apply { addProperty("t", t); fill() }.toString()

    fun hello(cols: Int, rows: Int): String = frame("hello") {
        addProperty("cols", cols.coerceIn(10, 500))
        addProperty("rows", rows.coerceIn(5, 200))
        addProperty("term", TERM)
    }

    fun resize(cols: Int, rows: Int): String = frame("resize") {
        addProperty("cols", cols.coerceIn(10, 500))
        addProperty("rows", rows.coerceIn(5, 200))
    }

    fun ping(): String = frame("ping")
    fun trust(yes: Boolean): String = frame(if (yes) "trust" else "distrust")
    fun bye(): String = frame("bye")

    /** The host key of a device: algorithm and the base64 SHA-256 (never logged). */
    data class HostKey(val alg: String, val sha256: String)

    sealed interface Message {
        data class AskHostKey(val hostKey: HostKey) : Message
        data class Ready(val tmuxAvailable: Boolean?, val tmuxSession: String?, val recording: Boolean, val view: Boolean, val hostKeyNew: Boolean) : Message
        data class Exit(val code: Int?, val reason: String) : Message
        data class Error(val code: String) : Message
        data object Pong : Message
    }

    private val CODE = Regex("^[a-z_]{1,40}$")
    private val ALG = Regex("^[A-Za-z0-9@._+-]{1,40}$")
    private val SHA = Regex("^[A-Za-z0-9+/=_-]{20,90}$")

    fun cleanCode(v: String?): String? = v?.takeIf { CODE.matches(it) }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(k: String): Boolean? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun hostKey(o: JsonObject?): HostKey? {
        val alg = o?.str("alg")?.takeIf { ALG.matches(it) } ?: return null
        val sha = o.str("sha256")?.takeIf { SHA.matches(it) } ?: return null
        return HostKey(alg, sha)
    }

    /** A text frame of the server → a message, or null for anything unknown or broken. */
    fun parse(text: String): Message? {
        if (text.length > 16_384) return null
        val o = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return when (o.str("t")) {
            "hostkey" -> hostKey(o.obj("hostKey"))?.let { Message.AskHostKey(it) }
            "ready" -> {
                val t = o.obj("tmux")
                Message.Ready(
                    tmuxAvailable = t?.bool("available"),
                    tmuxSession = t?.str("session")?.takeIf { TermJson.TMUX_NAME.matches(it) },
                    recording = o.bool("recording") == true,
                    view = o.str("mode") == "view",
                    hostKeyNew = o.bool("hostKeyNew") == true,
                )
            }
            "exit" -> Message.Exit(
                o.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt,
                cleanCode(o.str("reason")) ?: "closed",
            )
            "error" -> Message.Error(cleanCode(o.str("code")) ?: "error")
            "pong" -> Message.Pong
            else -> null
        }
    }

    /** What the app does after an error code (of the socket, the token or the ticket). */
    enum class Verdict {
        /** Reconnect by itself with the backoff. */
        RETRY,
        /** End; „Neu verbinden“ may try again (it asks the host key again, for instance). */
        FINAL,
        /** End, the device is locked until an administrator resets its host key. */
        LOCKED,
        /** End, the ticket is gone: back to the list of devices. */
        EXPIRED,
        /** End without an automatic attempt (too many sessions). */
        LIMIT,
    }

    /**
     * Endgültig (contract §3, plus `shell_refused` — decision 9 of the design: a refused shell does not change with
     * another attempt). `timeout`, `unreachable`, `listener_required`, `hello_timeout` and a lost network retry.
     */
    fun verdict(code: String): Verdict = when (code) {
        "host_key_mismatch" -> Verdict.LOCKED
        "host_key_rejected", "host_key_unpinned", "auth_failed", "key_unreadable",
        "credential_required", "credential_unreadable", "credential_missing", "recording_unavailable",
        "recording_required", "plugin_unavailable", "shell_refused", "no_right", "forbidden", "unlicensed",
        "disabled", "maintenance", "invalid", "no_user", "mint_failed", "invalid_answer" -> Verdict.FINAL
        "not_found", "http_404" -> Verdict.EXPIRED
        "limit", "limit_reached", "rate_limited", "http_429" -> Verdict.LIMIT
        "http_503" -> Verdict.FINAL
        else -> Verdict.RETRY
    }

    /** `exit` reasons: an administrator ended it (no „Neu verbinden“), the shell closed, the device was lost. */
    fun endedByAdmin(reason: String): Boolean = reason == "ended_by_plugin"

    /** A name for „Neue Sitzung“: `tmux`, taken → `tmux-2`, `tmux-3` … (design: „belegt tmux-2“). */
    fun suggestName(taken: Collection<String>, base: String = "tmux"): String {
        val b = base.takeIf { TermJson.TMUX_NAME.matches(it) && it.length <= 28 } ?: "tmux"
        if (b !in taken) return b
        var i = 2
        while ("$b-$i" in taken && i < 999) i++
        return "$b-$i"
    }

    fun validName(name: String): Boolean = TermJson.TMUX_NAME.matches(name)

    /** `ssh-ed25519 · SHA256 q7Xv 2mL0 …`: the fingerprint in groups of four for comparing by eye. */
    fun groups(sha256: String): String = sha256.trimEnd('=').chunked(4).joinToString(" ")

    /** Lines of a paste: more than one asks first (decision 6). A trailing line break does not count. */
    fun pasteLines(text: String): Int {
        val t = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        if (t.isEmpty()) return 0
        return t.count { it == '\n' } + 1
    }

    /** What a paste sends: line breaks as CR like a key press; other control characters dropped (no ESC ends a bracketed paste). */
    fun pasteText(text: String): String =
        text.replace("\r\n", "\r").replace('\n', '\r').filter { it == '\t' || it == '\r' || it >= ' ' && it != '\u007f' }

    /** The sessions to resume: only attachable names, the most recently active first (design: preselected). */
    fun resumable(list: TmuxList): List<TmuxSession> =
        list.sessions.filter { it.attachable }.sortedByDescending { it.activity ?: Long.MIN_VALUE }

    /** Sessions with a name the core cannot attach: not offered, only counted (with `kill` they are listed to end). */
    fun hidden(list: TmuxList): Int = if (list.kill) 0 else list.sessions.count { !it.attachable }

    /**
     * „Fenster öffnen“: the keys that make tmux show window [index] once attached — tmux's prefix Ctrl-B, then the
     * digit (0–9) or `'` with the index and Enter (tmux's own „select window by index“). Null for no valid index.
     */
    fun selectWindow(index: Int): ByteArray? = when (index) {
        in 0..9 -> byteArrayOf(0x02, ('0' + index).code.toByte())
        in 10..99_999 -> byteArrayOf(0x02, '\''.code.toByte()) + "$index\r".toByteArray(Charsets.US_ASCII)
        else -> null
    }

    /** The commands of a session's windows for the questions (`bash, journalctl, htop`), at most [max]. */
    fun commands(s: TmuxSession, max: Int = 4): String {
        val all = s.windowList.mapNotNull { it.command ?: it.name.takeIf { n -> n.isNotEmpty() } }
        return (all.take(max) + if (all.size > max) listOf("…") else emptyList()).joinToString(", ")
    }

    /** Closing [w] is closing the last window: the whole session ends (question T3 says so). */
    fun lastWindow(s: TmuxSession): Boolean = s.windowList.size <= 1 && s.windows <= 1
}
