package com.gatecontrol.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons of the redesign (24×24 viewBox, round caps/joins), drawn from
 * the same SVG paths as the mockup. Tint them via Icon(tint = …).
 */
object GcIcons {

    private fun icon(name: String, strokeWidth: Float = 2f, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    /** Same as [icon], filled as well (e.g. the active favourite star). */
    private fun filledIcon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}" +
            "a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}" +
            "v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private const val SHIELD = "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"

    val Shield = icon("Shield", 2f, SHIELD)
    val ShieldCheck = icon("ShieldCheck", 2f, SHIELD, "m9 12 2 2 4-4")
    val ShieldX = icon("ShieldX", 2f, SHIELD, "M9 9l6 6M15 9l-6 6")
    val Power = icon("Power", 2.2f, "M12 2v10", "M18.4 6.6a9 9 0 1 1-12.8 0")
    val External = icon("External", 2f, "M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    val Lock = icon("Lock", 2f, rect(4f, 11f, 16f, 10f, 2f), "M8 11V7a4 4 0 0 1 8 0v4")
    val Split = icon("Split", 2f, "M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5")
    val Globe = icon("Globe", 2f, circle(12f, 12f, 10f), "M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20")
    val Clock = icon("Clock", 2f, circle(12f, 12f, 10f), "M12 6v6l4 2")
    val Close = icon("Close", 2f, "M18 6 6 18M6 6l12 12")
    val Monitor = icon("Monitor", 2f, rect(2f, 3f, 20f, 14f, 2f), "M8 21h8M12 17v4")
    val Search = icon("Search", 2f, circle(11f, 11f, 7f), "m21 21-4.3-4.3")
    val ChevronRight = icon("ChevronRight", 2f, "m9 18 6-6-6-6")
    val Zap = icon("Zap", 2f, "M13 2 3 14h9l-1 8 10-12h-9l1-8z")
    val Sliders = icon("Sliders", 2f, "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6")
    val Back = icon("Back", 2f, "M19 12H5M12 19l-7-7 7-7")
    val Share = icon("Share", 2f, "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13")
    val Trash = icon("Trash", 2f, "M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14")
    val Plus = icon("Plus", 2.2f, "M12 5v14M5 12h14")
    val Check = icon("Check", 2.4f, "M20 6 9 17l-5-5")
    val Moon = icon("Moon", 1.8f, "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Sun = icon(
        "Sun", 1.8f, circle(12f, 12f, 4f),
        "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    )
    val Qr = icon(
        "Qr", 2f,
        rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f),
        "M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3",
    )
    val Key = icon("Key", 2f, circle(7.5f, 15.5f, 5.5f), "m21 2-9.6 9.6M15.5 7.5l3 3L22 7l-3-3")
    val File = icon("File", 2f, "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2v6h6")
    val Refresh = icon("Refresh", 2f, "M21 12a9 9 0 1 1-3-6.7L21 8", "M21 3v5h-5")
    val Spinner = icon("Spinner", 3f, "M21 12a9 9 0 1 1-9-9")
    val Alert = icon("Alert", 2f, circle(12f, 12f, 10f), "M12 8v4M12 16h.01")
    val Logs = icon("Logs", 2f, "M4 6h16M4 12h16M4 18h10")
    val Bell = icon("Bell", 2f, "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9", "M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val BellOff = icon("BellOff", 2f, "M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5", "M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7", "M10.3 21a1.94 1.94 0 0 0 3.4 0", "M2 2l20 20")
    /** Location sharing on tap (start screen, settings). */
    val Satellite = icon("Satellite", 2f, "M13 7 9 3 5 7l4 4", "m17 11 4 4-4 4-4-4", "m8 12 4 4 6-6-4-4Z", "m16 8 3-3", "M9 21a6 6 0 0 0-6-6")
    val Home = icon("Home", 2f, "M3 10l9-7 9 7v11H3z")
    val Battery = icon("Battery", 2f, rect(2f, 7f, 16f, 10f, 2f), "M22 11v2")
    val Wifi = icon(
        "Wifi", 2f,
        "M2 8.8a15 15 0 0 1 20 0", "M5 12.9a10 10 0 0 1 14 0", "M8.5 16.4a5 5 0 0 1 7 0", "M12 20h.01",
    )
    private const val STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"
    val Star = icon("Star", 2f, STAR)
    val StarFilled = filledIcon("StarFilled", STAR)
    val Bolt = icon("Bolt", 2f, "M13 2 4 14h7l-1 8 9-12h-7l1-8z")
    /** Pulse line: diagnosis ("Diagnose"). */
    val Activity = icon("Activity", 2f, "M22 12h-4l-3 9L9 3l-3 9H2")
    /** Skipped check. */
    val Minus = icon("Minus", 2.2f, "M5 12h14")

    // ─── Familie ──────────────────────────────────────────────────────
    /** House of the Familie tab (fallback when the plugin sends no icon). */
    val Family = icon("Family", 2f, "M3 11l9-7 9 7", "M5 10v10h14V10", "M10 20v-5h4v5")
    val Cart = icon("Cart", 2f, circle(9f, 20f, 1f), circle(18f, 20f, 1f), "M2 3h3l2.7 12.4a1 1 0 0 0 1 .8h9.6a1 1 0 0 0 1-.8L21 7H6")
    val Tasks = icon("Tasks", 2f, "M9 11l3 3 8-8", "M20 12v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h9")
    val Calendar = icon("Calendar", 2f, rect(3f, 4f, 18f, 18f, 2f), "M16 2v4M8 2v4M3 10h18")
    val Pin = icon("Pin", 2f, "M12 22s7-6.3 7-12a7 7 0 0 0-14 0c0 5.7 7 12 7 12z", circle(12f, 10f, 2.5f))
    val Crosshair = icon("Crosshair", 2f, circle(12f, 12f, 8f), "M12 2v4M12 18v4M2 12h4M18 12h4")
    val Siren = icon("Siren", 2f, "M7 18v-6a5 5 0 0 1 10 0v6", "M5 21h14", "M12 3v1M4.2 6.2l.7.7M19.8 6.2l-.7.7")
    val Flag = icon("Flag", 2f, "M4 22V4", "M4 4h12l-2 4 2 4H4")
    val Edit = icon("Edit", 2f, "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Phone = icon("Phone", 2f, rect(6f, 2f, 12f, 20f, 2f), "M11 18h2")

    /**
     * A 24×24 stroke icon from an SVG path a server sends (plugin tab icon).
     * Throws on a path that cannot be parsed; callers fall back to their own icon.
     */
    fun fromPath(name: String, path: String): ImageVector = icon(name, 2f, path)
}
