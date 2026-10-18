package com.gatecontrol.android.ui.start

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons of the arranging screens and the climate line of a widget
 * (24×24, round caps/joins, like [com.gatecontrol.android.ui.components.GcIcons]),
 * drawn from the mockups' SVG paths. Tint them via Icon(tint = …).
 */
object StartIcons {

    private fun icon(name: String, strokeWidth: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
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

    /** Arrows in four directions: "Start-Tab anordnen". */
    val Arrange: ImageVector = icon("Arrange", 2f, "M5 9l-2 3 2 3M19 9l2 3-2 3M9 5l3-2 3 2M9 19l3 2 3-2M3 12h18M12 3v18")

    /** Six dots: the handle to drag a block by. */
    val Grip: ImageVector = icon("Grip", 3.2f, "M9 6h.01M15 6h.01M9 12h.01M15 12h.01M9 18h.01M15 18h.01")

    /** Struck-through eye: a hidden card ("einblenden"). */
    val EyeOff: ImageVector = icon(
        "EyeOff",
        2f,
        "M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4M6.6 6.6A17 17 0 0 0 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6",
    )

    /** House with a thermometer: the temperature inside. */
    val HouseThermo: ImageVector = icon("HouseThermo", 2f, "M3 11l9-7 9 7v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z", "M12 10v5", "M12 14.9a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2")

    /** Sun behind a cloud: the temperature outside. */
    val CloudSun: ImageVector = icon("CloudSun", 2f, "M12 3v2M5.6 5.6L7 7M3 12h2M17 7l1.4-1.4", "M8.5 13a3.5 3.5 0 0 1 6.8-1.2A3 3 0 1 1 17 18H9a2.5 2.5 0 0 1-.5-5z")

    /** Bell with a clock hand: a reminder for a device. */
    val Reminder: ImageVector = icon("Reminder", 2f, "M6 16V11a6 6 0 0 1 12 0v5l2 2H4zM10 21a2 2 0 0 0 4 0", "M12 8v3l2 1")

    /** Three dots: a menu. */
    val More: ImageVector = icon("More", 3.2f, "M12 5h.01M12 12h.01M12 19h.01")
}
