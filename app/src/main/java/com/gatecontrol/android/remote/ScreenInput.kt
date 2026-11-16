package com.gatecontrol.android.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The helper's input of Fernzugriff stage 6 (contract „Fernzugriff Stufe 6“ §2): `{t:'input', k, …}` from the relay
 * `gc-screen.v1`, only while the mode in force is control. Coordinates are 0…1 of the shared picture (x right, y down).
 * The server already cleans every frame; the phone checks again (never trusts the line) and limits on its own.
 * Pure Kotlin — no Android, unit tested. Nothing of an input is ever logged.
 */
sealed interface ControlInput {
    /** The kind for `{t:'input_fail', k}`. */
    val kind: String

    data class Tap(val x: Double, val y: Double) : ControlInput { override val kind = "tap" }
    data class LongPress(val x: Double, val y: Double, val ms: Long) : ControlInput { override val kind = "long" }
    data class Swipe(val points: List<Pair<Double, Double>>, val ms: Long) : ControlInput { override val kind = "swipe" }
    data class Scroll(val x: Double, val y: Double, val dx: Double, val dy: Double) : ControlInput { override val kind = "scroll" }
    data class Key(val key: String) : ControlInput { override val kind = "key" }
    data class Text(val text: String) : ControlInput { override val kind = "text" }
}

/** A gesture in pixels of the display: one stroke along [points] (≥ 1), [durationMs] long. */
data class GesturePlan(val points: List<Pair<Float, Float>>, val durationMs: Long)

object ScreenInput {
    const val MAX_PATH = 32
    const val MAX_TEXT = 500
    const val TAP_MS = 50L
    const val LONG_DEFAULT_MS = 650L
    const val SCROLL_MS = 250L
    val KEYS = setOf("back", "home", "recents", "enter", "backspace")
    /** `{t:'input_fail', reason}` the phone may send. */
    val FAIL_REASONS = setOf("no_field", "blocked", "locked", "secure")

    private fun JsonObject.num(k: String): Double? =
        get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun unit(v: Double?): Double? = v?.takeIf { it in 0.0..1.0 }
    private fun delta(v: Double?): Double? = v?.takeIf { it in -1.0..1.0 }
    private fun ms(v: Double?, min: Long, max: Long): Long? = v?.takeIf { it == Math.floor(it) }?.toLong()?.takeIf { it in min..max }

    /** No control characters (line breaks included: Enter is a key of its own). */
    private fun cleanText(s: String): Boolean = s.none { it < ' ' || it in '\u007f'..'\u009f' || it == ' ' || it == ' ' }

    /** A text frame of the relay `{t:'input', …}` → the input, or null (dropped). */
    fun parse(o: JsonObject): ControlInput? = when (o.str("k")) {
        "tap" -> {
            val x = unit(o.num("x")); val y = unit(o.num("y"))
            if (x != null && y != null) ControlInput.Tap(x, y) else null
        }
        "long" -> {
            val x = unit(o.num("x")); val y = unit(o.num("y"))
            val d = if (o.has("ms")) ms(o.num("ms"), 400, 3000) else LONG_DEFAULT_MS
            if (x != null && y != null && d != null) ControlInput.LongPress(x, y, d) else null
        }
        "swipe" -> {
            val d = ms(o.num("ms"), 50, 5000)
            val arr = o.get("pts")?.takeIf { it.isJsonArray }?.asJsonArray
            val pts = arr?.takeIf { it.size() in 2..MAX_PATH }?.let(::points)
            if (d != null && pts != null) ControlInput.Swipe(pts, d) else null
        }
        "scroll" -> {
            val x = unit(o.num("x")); val y = unit(o.num("y"))
            val dx = delta(o.num("dx")); val dy = delta(o.num("dy"))
            if (x != null && y != null && dx != null && dy != null && (dx != 0.0 || dy != 0.0)) ControlInput.Scroll(x, y, dx, dy) else null
        }
        "key" -> o.str("key")?.takeIf { it in KEYS }?.let { ControlInput.Key(it) }
        "text" -> o.str("text")?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_TEXT && cleanText(it) }?.let { ControlInput.Text(it) }
        else -> null
    }

    private fun points(arr: JsonArray): List<Pair<Double, Double>>? {
        val out = ArrayList<Pair<Double, Double>>(arr.size())
        for (e in arr) {
            val p = e.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 2 } ?: return null
            val x = p[0].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }.let(::unit) ?: return null
            val y = p[1].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }.let(::unit) ?: return null
            out.add(x to y)
        }
        return out
    }

    private fun px(v: Double, size: Int): Float = (v * (size - 1).coerceAtLeast(1)).toFloat().coerceIn(0f, (size - 1).coerceAtLeast(0).toFloat())

    /**
     * The gesture of a touch input on a display of [width] × [height] pixels (the shared picture is the whole display,
     * scaled): taps and long presses are one point, a swipe follows its path, a scroll swipes from the point against
     * the wheel (`dy > 0` = the content moves on like a mouse wheel down → the finger goes up). null for a key or text.
     */
    fun plan(input: ControlInput, width: Int, height: Int): GesturePlan? {
        if (width <= 0 || height <= 0) return null
        return when (input) {
            is ControlInput.Tap -> GesturePlan(listOf(px(input.x, width) to px(input.y, height)), TAP_MS)
            is ControlInput.LongPress -> GesturePlan(listOf(px(input.x, width) to px(input.y, height)), input.ms)
            is ControlInput.Swipe -> GesturePlan(input.points.map { (x, y) -> px(x, width) to px(y, height) }, input.ms)
            is ControlInput.Scroll -> {
                val x2 = (input.x - input.dx).coerceIn(0.0, 1.0)
                val y2 = (input.y - input.dy).coerceIn(0.0, 1.0)
                GesturePlan(listOf(px(input.x, width) to px(input.y, height), px(x2, width) to px(y2, height)), SCROLL_MS)
            }
            is ControlInput.Key, is ControlInput.Text -> null
        }
    }

    /** [text] put into [current] at the selection [start]…[end] (−1: at the end) → the new text and the cursor after it. */
    fun insert(current: String, start: Int, end: Int, text: String): Pair<String, Int> {
        val (a, b) = selection(current, start, end)
        val out = current.substring(0, a) + text + current.substring(b)
        return out to (a + text.length)
    }

    /** Backspace in [current] at [start]…[end]: the selection goes, else the character (code point) before the cursor. */
    fun backspace(current: String, start: Int, end: Int): Pair<String, Int> {
        val (a, b) = selection(current, start, end)
        if (a != b) return (current.substring(0, a) + current.substring(b)) to a
        if (a == 0) return current to 0
        val before = current.offsetByCodePoints(a, -1)
        return (current.substring(0, before) + current.substring(a)) to before
    }

    private fun selection(current: String, start: Int, end: Int): Pair<Int, Int> {
        val n = current.length
        if (start < 0 || end < 0 || start > n || end > n) return n to n
        return minOf(start, end) to maxOf(start, end)
    }
}

/**
 * The phone's own limit (contract §2), on top of the server's: at most 30 inputs a second, text 5 a second and
 * 4000 characters in 10 s. Not thread safe — one relay connection feeds it.
 */
class InputLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private var tokens = PER_S.toDouble()
    private var tokensAt = clock()
    private var textsAt = clock()
    private var texts = 0
    private val chars = ArrayDeque<Pair<Long, Int>>()

    fun allow(input: ControlInput): Boolean {
        val now = clock()
        tokens = minOf(PER_S.toDouble(), tokens + (now - tokensAt) / 1000.0 * PER_S)
        tokensAt = now
        if (tokens < 1.0) return false
        if (input is ControlInput.Text) {
            if (now - textsAt >= 1000) { textsAt = now; texts = 0 }
            if (texts >= TEXTS_PER_S) return false
            while (chars.isNotEmpty() && now - chars.first().first >= CHAR_WINDOW_MS) chars.removeFirst()
            val n = input.text.codePointCount(0, input.text.length)
            if (chars.sumOf { it.second } + n > CHARS_PER_WINDOW) return false
            texts++
            chars.addLast(now to n)
        }
        tokens -= 1.0
        return true
    }

    companion object {
        const val PER_S = 30
        const val TEXTS_PER_S = 5
        const val CHAR_WINDOW_MS = 10_000L
        const val CHARS_PER_WINDOW = 4000
    }
}
