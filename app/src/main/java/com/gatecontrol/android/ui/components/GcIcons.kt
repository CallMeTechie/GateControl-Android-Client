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
    val Call = icon("Call", 2f, "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2")
    val Car = icon("Car", 2f, "M5 17H3v-5l2-5h14l2 5v5h-2", "M3 12h18", circle(7.5f, 17f, 2f), circle(16.5f, 17f, 2f), "M9.5 17h5")
    val Medical = icon("Medical", 2f, "M9 3h6v6h6v6h-6v6H9v-6H3V9h6z")
    val Download = icon("Download", 2.2f, "M12 3v12M7 10l5 5 5-5M5 21h14")
    val Eye = icon("Eye", 2f, "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    /** Taschengeld (1.2). */
    val Wallet = icon("Wallet", 2f, "M3 7a2 2 0 0 1 2-2h13v4", "M3 7v11a2 2 0 0 0 2 2h15V9H5a2 2 0 0 1-2-2z", "M16 14h1")
    /** Wunschlisten (1.2). */
    val Gift = icon("Gift", 2f, rect(3f, 8f, 18f, 4f, 1f), "M5 12v9h14v-9M12 8v13", "M12 8S10.5 3 8 3a2.5 2.5 0 0 0 0 5M12 8s1.5-5 4-5a2.5 2.5 0 0 1 0 5")
    /** Haushaltsbuch (1.2). */
    val Receipt = icon("Receipt", 2f, "M5 3v18l2.5-1.5L10 21l2-1.5 2 1.5 2.5-1.5L19 21V3l-2.5 1.5L14 3l-2 1.5L10 3 7.5 4.5z", "M9 9h6M9 13h6")
    /** Pinnwand & Packlisten (1.2). */
    val Board = icon("Board", 2f, rect(4f, 4f, 16f, 17f, 2f), "M9 2h6v4H9z", "M8 11h8M8 15h5")
    /** Essensplan (1.2). */
    val Meal = icon("Meal", 2f, "M7 2v8a2 2 0 0 0 4 0V2M9 10v12", "M17 2c-2 2-2 6 0 8v12")
    /** Stundenplan (Schule plugin): a mortarboard. */
    val School = icon("School", 2f, "M22 9 12 4 2 9l10 5 10-5z", "M6 11v5c3 2.7 9 2.7 12 0v-5", "M22 9v6")
    /** School messages (Schule plugin 1.4+): an envelope. */
    val Mail = icon("Mail", 2f, "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7")
    /** A message with attachments. */
    val Paperclip = icon("Paperclip", 2f, "m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48")
    val ChevronLeft = icon("ChevronLeft", 2f, "m15 18-6-6 6-6")
    val ChevronUp = icon("ChevronUp", 2f, "m18 15-6-6-6 6")
    val ChevronDown = icon("ChevronDown", 2f, "m6 9 6 6 6-6")
    /** Settings (gear), top right of the profile screen. */
    val Settings = icon(
        "Settings", 2f,
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        circle(12f, 12f, 3f),
    )
    val Link = icon("Link", 2f, "M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1", "M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1")

    /** Profile (own avatar, settings). */
    val User = icon("User", 2f, circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    /** Photo picker ("Foto wählen"). */
    val Photo = icon("Photo", 2f, rect(3f, 3f, 18f, 18f, 2f), circle(8.5f, 8.5f, 1.5f), "m21 15-5-5L5 21")
    /** Ring colour (profile avatar badge). */
    val Palette = icon(
        "Palette", 2f,
        "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.93 0 1.65-.75 1.65-1.69 0-.44-.18-.84-.44-1.13-.29-.29-.44-.65-.44-1.13a1.64 1.64 0 0 1 1.67-1.67h2c3.05 0 5.55-2.5 5.55-5.55C21.97 6.01 17.46 2 12 2z",
        circle(13.5f, 6.5f, 0.5f), circle(17.5f, 10.5f, 0.5f), circle(8.5f, 7.5f, 0.5f), circle(6.5f, 12.5f, 0.5f),
    )

    // Tür & Kamera (door plugin).
    /** A door (the plugin's own tab icon). */
    val Door = icon("Door", 2f, "M5 21V4a1 1 0 0 1 1-1h12a1 1 0 0 1 1 1v17M3 21h18M15 12h.01")
    val LockOpen = icon("LockOpen", 2f, rect(4f, 11f, 16f, 10f, 2f), "M8 11V7a4 4 0 0 1 7.9-1")
    val Camera = icon("Camera", 2f, "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", circle(12f, 13f, 4f))

    /**
     * A 24×24 stroke icon from an SVG path a server sends (plugin tab icon).
     * Throws on a path that cannot be parsed; callers fall back to their own icon.
     */
    fun fromPath(name: String, path: String): ImageVector = icon(name, 2f, path)
}
