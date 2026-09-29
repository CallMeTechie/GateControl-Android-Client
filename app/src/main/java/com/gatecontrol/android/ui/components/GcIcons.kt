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
}
