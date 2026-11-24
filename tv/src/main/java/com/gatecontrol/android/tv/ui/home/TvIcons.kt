package com.gatecontrol.android.tv.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons of the mockup "GateControl TV" (24 × 24, stroke 2), drawn in the tint of the Icon that shows them.
 * Own paths instead of material-icons-extended: the TV APK stays small.
 */
internal object TvIcons {
    val Home = icon("M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")
    val Cameras = icon("M5 6h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M16 10l5-3v10l-5-3")
    val Drive = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    /** Medien: a film strip. */
    val Media = icon("M4 4h16v16H4z", "M8 4v16M16 4v16", "M4 8h4M4 12h4M4 16h4M16 8h4M16 12h4M16 16h4")
    val GateDrop = icon("M12 3v12M7 10l5 5 5-5M4 19h16")
    val Family = icon(
        "M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M14.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "M3 19c0-3 3-5 6-5s6 2 6 5M14 19c0-2 1.5-4 4-4s3 1 3 3",
    )
    /** Notizen: a list with ticks. */
    /** Nachrichten: a speech bubble. */
    val Chat = icon("M4 5h16v11H9l-5 4z")
    val Notes = icon("M9 6h11M9 12h11M9 18h11", "M4 6l1 1 2-2M4 12l1 1 2-2M4 18l1 1 2-2")
    val Settings = icon(
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6" +
            "A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.6 2 3.4 2.4-1a7 7 0 0 0 2 1.2L10 21h4l.5-2.6a7 7 0 0 0 2-1.2l2.4 1 2-3.4" +
            "-2-1.6c.1-.4.1-.8.1-1.2z",
    )

    /** The arrow of the GateControl mark (on the green square). */
    val Mark = icon("M5 12h9M10 7l5 5-5 5M19 5v14", strokeWidth = 2.6f)

    /** A quick action without an icon of its own. */
    val Bolt = icon("M13 2L4 14h7l-1 8 9-12h-7z")
    val Door = icon("M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17M4 21h16M14 12h.01")
    val Thermo = icon("M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z")

    // Drive and the player
    val Folder = Drive
    val Photo = icon("M4 5h16v14H4z", "M4 16l5-5 4 4 3-3 4 4", "M15 9h.01")
    val Play = icon("M8 5v14l11-7z")
    val Pause = icon("M7 5h3v14H7zM14 5h3v14h-3z")
    val File = icon("M7 3h7l5 5v13H7z", "M14 3v5h5")
    val Rewind = icon("M11 6v12L3 12zM20 6v12l-8-6z")
    val Forward = icon("M13 6v12l8-6zM4 6v12l8-6z")
    val Frame = icon("M3 4h18v14H3z", "M7 21h10", "M3 15l5-5 4 4 3-3 6 6")

    // Zuhause
    val Trash = icon("M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13", "M10 11v6M14 11v6")
    val Calendar = icon("M4 6h16v14H4z", "M4 10h16", "M8 3v5M16 3v5")
    val Cart = icon("M3 4h2l2.4 11h10.2L20 8H6.2", "M9 19.5a1 1 0 1 0 0 .01M17 19.5a1 1 0 1 0 0 .01")
    val Check = icon("M5 12.5l4.5 4.5L19 7.5", strokeWidth = 3f)
    val Plus = icon("M12 5v14M5 12h14")
    val Mic = icon("M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0M12 18v3")
    /** „Am Handy weiterschreiben“: a phone. */
    val Phone = icon("M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")

    private val cache = object : LinkedHashMap<String, ImageVector?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageVector?>): Boolean = size > 64
    }

    /** A plugin's icon path (checked by `QuickActionPaths`), cached per path; [fallback] when it cannot be drawn. */
    fun fromPath(d: String?, fallback: ImageVector = Bolt): ImageVector {
        val s = com.gatecontrol.android.quickactions.QuickActionPaths.sanitize(d) ?: return fallback
        synchronized(cache) { if (cache.containsKey(s)) return cache[s] ?: fallback }
        val v = runCatching { icon(s) }.getOrNull()
        synchronized(cache) { cache[s] = v }
        return v ?: fallback
    }

    private fun icon(vararg paths: String, strokeWidth: Float = 2f): ImageVector {
        val builder = ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = null,
                // Black is replaced by the Icon's tint.
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
