package com.gatecontrol.android.quickactions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap
import com.gatecontrol.android.ui.components.GcIcons

/**
 * Icons of the quick actions: the server sends per action an SVG path `d`
 * for a 24×24 viewBox (stroke icon, like a plugin's tab icon). Parsed once
 * per distinct string; anything unusable falls back to the bolt.
 */
object QuickActionIcons {

    private const val MAX_LENGTH = 4_000
    private const val CACHE_SIZE = 64

    /** Path commands, numbers and separators only. */
    private val PATH_RE = Regex("^[MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]+$")

    /** A path the app may try to draw, trimmed; null when empty, too long or with foreign characters. */
    fun sanitize(d: String?): String? {
        val s = d?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH } ?: return null
        if (!PATH_RE.matches(s) || (s[0] != 'M' && s[0] != 'm')) return null
        return s
    }

    /** Parsed path nodes, null when [d] is unusable (no throw). */
    fun nodes(d: String?): List<PathNode>? {
        val s = sanitize(d) ?: return null
        val nodes = runCatching { addPathNodes(s) }.getOrNull() ?: return null
        if (nodes.isEmpty() || nodes.first() !is PathNode.MoveTo && nodes.first() !is PathNode.RelativeMoveTo) return null
        return nodes
    }

    private val cache = object : LinkedHashMap<String, ImageVector?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageVector?>): Boolean = size > CACHE_SIZE
    }

    /** The icon of [d] as a vector (cached per string), null when unusable. */
    fun vectorOrNull(d: String?): ImageVector? {
        val s = sanitize(d) ?: return null
        synchronized(cache) { if (cache.containsKey(s)) return cache[s] }
        val vector = if (nodes(s) == null) null else runCatching { GcIcons.fromPath("QuickAction", s) }.getOrNull()
        synchronized(cache) { cache[s] = vector }
        return vector
    }

    /** The icon of [d], the bolt when there is none or it cannot be drawn. */
    fun vector(d: String?): ImageVector = vectorOrNull(d) ?: GcIcons.Bolt

    // ─── Bitmaps (widget, app shortcuts, Quick Settings tiles) ─────────

    private val bitmaps = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>): Boolean = size > 32
    }

    /**
     * The icon of [d] drawn as a stroke icon into a [sizePx] square bitmap
     * in [color] (ARGB) on [background] (ARGB circle, 0 = none); null when
     * [d] is unusable. [glyphFraction]: share of the side the 24×24 icon fills.
     */
    fun bitmap(d: String?, sizePx: Int, color: Int, background: Int = 0, glyphFraction: Float = 1f): Bitmap? {
        val s = sanitize(d) ?: return null
        if (sizePx <= 0) return null
        val key = "$sizePx|$color|$background|$glyphFraction|$s"
        synchronized(bitmaps) { if (bitmaps.containsKey(key)) return bitmaps[key] }
        val bmp = runCatching { draw(s, sizePx, color, background, glyphFraction) }.getOrNull()
        synchronized(bitmaps) { bitmaps[key] = bmp }
        return bmp
    }

    private fun draw(d: String, sizePx: Int, color: Int, background: Int, glyphFraction: Float): Bitmap? {
        if (nodes(d) == null) return null
        val path = PathParser.createPathFromPathData(d) ?: return null
        val bmp = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bmp)
        if (background != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = background; style = Paint.Style.FILL }
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bg)
        }
        val glyph = sizePx * glyphFraction
        val scale = glyph / 24f
        canvas.translate((sizePx - glyph) / 2f, (sizePx - glyph) / 2f)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(path, paint)
        return bmp
    }
}
