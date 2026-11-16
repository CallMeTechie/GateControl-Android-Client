package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.chat.ChatBridgeChat
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * The logos of the messengers (Messenger 1.20, instead of the letters „WA“): a plain glyph, only to say which service
 * a chat, a channel or a row belongs to — WhatsApp white on its green, GateControl's shield on the accent colour, a
 * speech bubble (grey) for a service this app does not know. The same paths as the portal (`MS.netLogo`).
 */
object ChatNetGlyphs {
    private fun glyph(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) b.addPath(pathData = addPathNodes(p), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
        return b.build()
    }

    val WhatsApp: ImageVector = glyph(
        "WhatsApp",
        "M12 2.6a9.3 9.3 0 0 0-8 14l-1.3 4.8 4.9-1.3A9.3 9.3 0 1 0 12 2.6zm0 1.7a7.6 7.6 0 1 1-3.9 14.1l-.3-.2-2.9.8.8-2.8-.2-.3A7.6 7.6 0 0 1 12 4.3z",
        "M9.2 7.6c.2-.4.4-.4.7-.4h.5c.2 0 .4 0 .6.5l.8 1.9c.1.2.1.4 0 .6l-.5.7c-.1.2-.2.3 0 .6.6 1 1.4 1.8 2.5 2.4.3.1.4.1.6-.1l.7-.8c.2-.2.4-.2.6-.1l1.8.9c.2.1.4.2.4.4.1.3 0 1-.3 1.4-.4.5-1.3 1-2 1-1 .1-2.3-.4-4-1.6-1.5-1.2-2.5-2.6-2.9-3.6-.4-1-.2-2 .5-2.8z",
    )
    val GateControl: ImageVector = glyph("GateControl", "M12 2.5l7.5 2.8v5.9c0 4.8-3.2 8.6-7.5 10.3-4.3-1.7-7.5-5.5-7.5-10.3V5.3z")

    /** Messenger 1.23: Signal — a speech bubble in a dashed ring (the portal's `NET_GLYPH.signal`). */
    val Signal: ImageVector = ImageVector.Builder(name = "Signal", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes("M12 5.6a6.4 6.4 0 0 0-5.5 9.7l-.8 3 3.1-.7A6.4 6.4 0 1 0 12 5.6z"), fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
        .addPath(pathData = addPathNodes("M21.60 12.00A9.6 9.6 0 0 1 21.02 15.28M20.31 16.80A9.6 9.6 0 0 1 18.17 19.35M16.80 20.31A9.6 9.6 0 0 1 13.67 21.45M12.00 21.60A9.6 9.6 0 0 1 8.72 21.02M7.20 20.31A9.6 9.6 0 0 1 4.65 18.17M3.69 16.80A9.6 9.6 0 0 1 2.55 13.67M2.40 12.00A9.6 9.6 0 0 1 2.98 8.72M3.69 7.20A9.6 9.6 0 0 1 5.83 4.65M7.20 3.69A9.6 9.6 0 0 1 10.33 2.55M12.00 2.40A9.6 9.6 0 0 1 15.28 2.98M16.80 3.69A9.6 9.6 0 0 1 19.35 5.83M20.31 7.20A9.6 9.6 0 0 1 21.45 10.33"), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f)
        .build()
    /** Messenger 1.24: Telegram — a paper plane (the portal's `NET_GLYPH.telegram`). */
    val Telegram: ImageVector = glyph(
        "Telegram",
        "M3.4 11.3l15.9-6.2c.8-.3 1.4.2 1.2 1.3l-2.7 12.7c-.2.9-.7 1.1-1.5.7l-4.1-3-2 1.9c-.2.2-.4.4-.8.4l.3-4.2 7.7-7c.3-.3-.1-.4-.5-.2l-9.5 6-4.1-1.3c-.9-.3-.9-.9.2-1.3z",
    )
    val Other: ImageVector = glyph("OtherMessenger", "M4 5h16v11H9l-5 4z")

    fun of(logo: ChatBridgeChat.Logo): ImageVector = when (logo) {
        ChatBridgeChat.Logo.WhatsApp -> WhatsApp
        ChatBridgeChat.Logo.Signal -> Signal
        ChatBridgeChat.Logo.Telegram -> Telegram
        ChatBridgeChat.Logo.GateControl -> GateControl
        ChatBridgeChat.Logo.Other -> Other
    }

    /** WhatsApp's green of the mark (the portal's `#1F8F5A`). */
    val WHATSAPP_GREEN = Color(0xFF1F8F5A)

    /** Signal's blue of the mark (the portal's `#3A76F0`). */
    val SIGNAL_BLUE = Color(0xFF3A76F0)

    /** Telegram's blue of the mark (the portal's `#27A7E7`). */
    val TELEGRAM_BLUE = Color(0xFF27A7E7)
}

/**
 * The logo of [network] in its circle, [size] across. [label] („über WhatsApp“) is read out; null: only decoration
 * (the row says it).
 */
@Composable
fun ChatNetLogo(network: String?, size: Dp, modifier: Modifier = Modifier, label: String? = null) {
    val logo = ChatBridgeChat.logo(network)
    val extra = GateControlTheme.extraColors
    val (bg, fg) = when (logo) {
        ChatBridgeChat.Logo.WhatsApp -> ChatNetGlyphs.WHATSAPP_GREEN to Color.White
        ChatBridgeChat.Logo.Signal -> ChatNetGlyphs.SIGNAL_BLUE to Color.White
        ChatBridgeChat.Logo.Telegram -> ChatNetGlyphs.TELEGRAM_BLUE to Color.White
        ChatBridgeChat.Logo.GateControl -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ChatBridgeChat.Logo.Other -> extra.panel2 to extra.muted
    }
    Box(
        modifier.size(size).clip(CircleShape).background(bg).then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ChatNetGlyphs.of(logo), contentDescription = null, tint = fg, modifier = Modifier.padding(size * 0.14f).size(size * 0.72f))
    }
}

/** The logo at a picture's lower right, ringed in the page's colour (avatars of bridged chats and channels). */
@Composable
fun ChatNetCorner(network: String?, avatar: Dp, modifier: Modifier = Modifier) {
    val h = (avatar * 0.4f).coerceIn(14.dp, 22.dp)
    Box(modifier.size(h + 3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        ChatNetLogo(network, h)
    }
}
