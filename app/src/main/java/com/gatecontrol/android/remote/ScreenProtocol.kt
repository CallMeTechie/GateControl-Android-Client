package com.gatecontrol.android.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The plugin „Fernzugriff“ (gatecontrol-remote) — stage 5: this phone shares its screen, a helper watches. */
const val REMOTE_PLUGIN = "gatecontrol-remote"

/**
 * The relay `gc-screen.v1` of GateControl (core with gc.remote version 4, contract „Fernzugriff Stufe 5“): one
 * WebSocket `/api/v1/remote/screen-relay`, the token only in `Sec-WebSocket-Protocol: gc-screen.v1, token.<t>`.
 * The phone is the sender: `hello`, then `config` before the first frame and on every change, binary frames
 * `[1, flags (bit 0 key frame), 0, 0, timestamp µs uint64 BE] + one H.264 access unit (Annex B)`. The server
 * passes them on unchanged. Pure Kotlin — no Android, unit tested.
 */
object ScreenProtocol {
    const val PROTOCOL = "gc-screen.v1"
    const val HEADER = 12
    const val VERSION: Byte = 1
    const val FLAG_KEY = 1
    /** The server's limits (also in `ready.limits`): the encoder never goes above them. */
    const val MAX_EDGE = 1920
    const val MAX_SHORT = 1080
    const val MAX_FPS = 30
    const val MAX_BITRATE = 4_000_000
    const val MAX_FRAME = 1024 * 1024

    /** Recommended for a phone (contract §2): 1280 px on the long edge, 15 fps, 1.5 Mbit/s, a key frame every 2 s. */
    const val TARGET_EDGE = 1280
    const val TARGET_FPS = 15
    const val TARGET_BITRATE = 1_500_000
    const val KEY_INTERVAL_S = 2
    const val MIN_BITRATE = 300_000

    /** The two entries of `Sec-WebSocket-Protocol`: the protocol and the token. Never in the address. */
    fun protocolHeader(token: String): String = "$PROTOCOL, token.$token"

    /** A binary frame: the 12-byte head and [len] bytes of [data] from [offset]. */
    fun frame(key: Boolean, timestampUs: Long, data: ByteArray, offset: Int = 0, len: Int = data.size - offset): ByteArray {
        require(offset >= 0 && len >= 0 && offset + len <= data.size) { "range" }
        val out = ByteArray(HEADER + len)
        out[0] = VERSION
        out[1] = if (key) FLAG_KEY.toByte() else 0
        val ts = timestampUs.coerceAtLeast(0L)
        for (i in 0 until 8) out[4 + i] = (ts ushr (56 - 8 * i)).toByte()
        System.arraycopy(data, offset, out, HEADER, len)
        return out
    }

    /** The head of a frame → (key, timestamp µs) or null (no frame of version 1). */
    fun head(frame: ByteArray): Pair<Boolean, Long>? {
        if (frame.size <= HEADER || frame[0] != VERSION) return null
        var ts = 0L
        for (i in 4 until 12) ts = (ts shl 8) or (frame[i].toLong() and 0xFF)
        return ((frame[1].toInt() and FLAG_KEY) == FLAG_KEY) to ts
    }

    /** The NAL units of an Annex-B buffer as (type, start of the payload after the start code, end). */
    fun nalUnits(buf: ByteArray, offset: Int = 0, len: Int = buf.size - offset): List<Triple<Int, Int, Int>> {
        val end = offset + len
        val starts = ArrayList<Int>()
        var i = offset
        while (i + 2 < end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<Triple<Int, Int, Int>>(starts.size)
        for ((n, s) in starts.withIndex()) {
            var e = if (n + 1 < starts.size) starts[n + 1] - 3 else end
            // a four-byte start code leaves a zero in front of the next one
            if (n + 1 < starts.size && e > s && buf[e - 1].toInt() == 0) e--
            if (s < e) out.add(Triple(buf[s].toInt() and 0x1F, s, e))
        }
        return out
    }

    /** True when the buffer carries an SPS (type 7) — a key frame the encoder already prefixed with SPS/PPS. */
    fun hasSps(buf: ByteArray, offset: Int = 0, len: Int = buf.size - offset): Boolean = nalUnits(buf, offset, len).any { it.first == 7 }

    /**
     * The RFC 6381 codec string of the first SPS in an Annex-B buffer (`avc1.PPCCLL`, e.g. `avc1.42E01F`), or null.
     * profile_idc, the constraint flags and level_idc are the three bytes after the NAL header.
     */
    fun codecString(buf: ByteArray, offset: Int = 0, len: Int = buf.size - offset): String? {
        val sps = nalUnits(buf, offset, len).firstOrNull { it.first == 7 } ?: return null
        val (_, s, e) = sps
        if (e - s < 4) return null
        return "avc1.%02X%02X%02X".format(buf[s + 1].toInt() and 0xFF, buf[s + 2].toInt() and 0xFF, buf[s + 3].toInt() and 0xFF)
    }

    /** The size the encoder works with: the display scaled to [targetEdge] on its long edge, both even, within the limits. */
    fun encodeSize(displayWidth: Int, displayHeight: Int, targetEdge: Int = TARGET_EDGE): Pair<Int, Int> {
        if (displayWidth <= 0 || displayHeight <= 0) return 720 to 1280
        val long = maxOf(displayWidth, displayHeight)
        val short = minOf(displayWidth, displayHeight)
        val edge = minOf(targetEdge, MAX_EDGE, long)
        var scale = edge.toDouble() / long
        if (short * scale > MAX_SHORT) scale = MAX_SHORT.toDouble() / short
        fun even(v: Double): Int = (v.toInt() / 2 * 2).coerceAtLeast(16)
        val w = even(displayWidth * scale)
        val h = even(displayHeight * scale)
        return w to h
    }

    fun hello(width: Int, height: Int, fps: Int, appOnly: Boolean, appVersion: String): String = JsonObject().apply {
        addProperty("t", "hello")
        addProperty("role", "sender")
        addProperty("width", width)
        addProperty("height", height)
        addProperty("fps", fps.coerceIn(1, MAX_FPS))
        addProperty("scope", if (appOnly) "app" else "display")
        add("app", JsonObject().apply {
            addProperty("platform", "android")
            addProperty("version", appVersion.take(40))
        })
    }.toString()

    fun config(codec: String, width: Int, height: Int): String = JsonObject().apply {
        addProperty("t", "config")
        addProperty("codec", codec)
        addProperty("width", width)
        addProperty("height", height)
    }.toString()

    fun state(paused: Boolean, reason: String? = null): String = JsonObject().apply {
        addProperty("t", "state")
        addProperty("paused", paused)
        if (reason != null) addProperty("reason", reason)
    }.toString()

    const val PING = """{"t":"ping"}"""
    const val BYE = """{"t":"bye"}"""

    /** What the server says to the phone. */
    sealed interface Message {
        data class Ready(val viewerName: String?, val maxBitrate: Int?) : Message
        data class Viewer(val joined: Boolean, val name: String?) : Message
        data object Keyframe : Message
        data class Rate(val bitrate: Int) : Message
        data class End(val reason: String) : Message
        data class Error(val code: String) : Message
        data object Pong : Message
    }

    /** A text frame of the server → [Message] or null (unknown types are ignored: forward compatible). */
    fun parse(text: String): Message? {
        val o = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        fun str(k: String, src: JsonObject = o): String? = src.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun int(k: String, src: JsonObject = o): Int? = src.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        fun obj(k: String): JsonObject? = o.get(k)?.takeIf { it.isJsonObject }?.asJsonObject
        return when (str("t")) {
            "ready" -> Message.Ready(obj("peer")?.let { str("name", it) }?.take(100), obj("limits")?.let { int("maxBitrate", it) })
            "viewer" -> Message.Viewer(str("state") == "joined", str("name")?.take(100))
            "keyframe" -> Message.Keyframe
            "rate" -> int("bitrate")?.let { Message.Rate(it.coerceIn(MIN_BITRATE, MAX_BITRATE)) }
            "end" -> Message.End(cleanCode(str("reason")) ?: "other")
            "error" -> Message.Error(cleanCode(str("code")) ?: "failed")
            "pong" -> Message.Pong
            else -> null
        }
    }

    private val CODE_RE = Regex("^[a-z_]{1,40}$")

    /** Only short lowercase words from the server (they are shown and stored). */
    fun cleanCode(v: String?): String? = v?.takeIf { CODE_RE.matches(it) }
}
