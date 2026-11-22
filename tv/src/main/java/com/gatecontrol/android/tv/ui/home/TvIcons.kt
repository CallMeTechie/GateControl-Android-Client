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
    val GateDrop = icon("M12 3v12M7 10l5 5 5-5M4 19h16")
    val Family = icon(
        "M6 8a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M14.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0",
        "M3 19c0-3 3-5 6-5s6 2 6 5M14 19c0-2 1.5-4 4-4s3 1 3 3",
    )
    val Settings = icon(
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6" +
            "A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.6 2 3.4 2.4-1a7 7 0 0 0 2 1.2L10 21h4l.5-2.6a7 7 0 0 0 2-1.2l2.4 1 2-3.4" +
            "-2-1.6c.1-.4.1-.8.1-1.2z",
    )

    /** The arrow of the GateControl mark (on the green square). */
    val Mark = icon("M5 12h9M10 7l5 5-5 5M19 5v14", strokeWidth = 2.6f)

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
