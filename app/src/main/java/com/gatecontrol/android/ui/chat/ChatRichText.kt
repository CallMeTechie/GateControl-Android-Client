package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatFormat
import com.gatecontrol.android.chat.ChatLinkPreview
import com.gatecontrol.android.chat.ChatMark
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily

/**
 * A message's text with its format and links (Messenger 1.20): the `marks` of a bridged message — bold, italic,
 * struck through, code (monospace on a light ground), links — and the http(s) and `www.` addresses of every chat, which
 * open in the browser. Built from the text's pieces ([ChatFormat.spans]), never from HTML.
 */
@Composable
fun chatRichText(text: String, marks: List<ChatMark>?, linkColor: Color = MaterialTheme.colorScheme.primary): AnnotatedString {
    val codeBg = GateControlTheme.extraColors.panel2
    val uri = LocalUriHandler.current
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        for (s in ChatFormat.spans(text, marks)) {
            val piece = text.substring(s.start, s.end)
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                fontFamily = if (s.code) MonoFontFamily else null,
                background = if (s.code) codeBg else Color.Unspecified,
            )
            val url = s.url
            if (url != null) {
                // a tap opens the address; the bubble's long press stays
                withLink(LinkAnnotation.Clickable(url, linkStyle) { runCatching { uri.openUri(url) } }) { withStyle(style) { append(piece) } }
            } else {
                withStyle(style) { append(piece) }
            }
        }
    }
}

/**
 * The preview of a link on top of a bubble (Messenger 1.20): the picture (full width, at most about 220 dp high),
 * the title (bold, two lines), the description (grey, three lines), the site (small, grey). A tap opens the address.
 * A picture still on its way: „Bild wird geladen …“ in its place; none or failed: the card without a picture.
 */
@Composable
fun LinkPreviewCard(p: ChatLinkPreview, width: Dp = 280.dp) {
    val extra = GateControlTheme.extraColors
    val uri = LocalUriHandler.current
    val open = stringResource(R.string.chat_link_open)
    Column(
        Modifier
            .width(width)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(extra.panel2)
            .clickable(role = Role.Button, onClickLabel = open) { runCatching { uri.openUri(p.url) } }
            .semantics { onClick(label = open) { runCatching { uri.openUri(p.url) }; true } },
    ) {
        val img = p.image
        if (img != null && img.shown) {
            val ratio = if (img.w != null && img.h != null) (img.w.toFloat() / img.h).coerceIn(0.8f, 3f) else 16f / 9f
            val px = with(LocalDensity.current) { width.roundToPx() }
            val bmp = if (img.loading) null else rememberChatBitmap(img.mediaId, px)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = 220.dp).background(extra.border), contentAlignment = Alignment.Center) {
                if (bmp != null) {
                    Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
                } else {
                    Text(stringResource(R.string.chat_preview_loading), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            p.title?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            p.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(p.site ?: p.url, style = MaterialTheme.typography.labelSmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
