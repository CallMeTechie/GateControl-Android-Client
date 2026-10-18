package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPending
import com.gatecontrol.android.chat.ChatReaction
import com.gatecontrol.android.chat.VoicePlayer
import com.gatecontrol.android.chat.VoiceWave
import com.gatecontrol.android.ui.theme.GateControlTheme
import java.util.Locale

/** Colours of a bubble: own messages on the accent tone, others on the panel. */
private data class BubbleColors(val bg: Color, val fg: Color, val meta: Color, val border: Color)

@Composable
private fun bubbleColors(mine: Boolean): BubbleColors {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    // Messenger 1.4 settings: the own bubble's colour (this device).
    val own = LocalChatLook.current.ownBubble
    return if (mine) BubbleColors(own ?: extra.accentBg, scheme.onSurface, extra.accentText, Color.Transparent)
    else BubbleColors(scheme.surface, scheme.onSurface, extra.muted, extra.border)
}

private fun shape(mine: Boolean) = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)

/** Actions a bubble reports to the screen. */
class BubbleActions(
    val onLongPress: (ChatMessage) -> Unit,
    val onOpenImage: (ChatMessage) -> Unit,
    val onOpenFile: (ChatMessage) -> Unit,
    val onOpenLocation: (ChatMessage) -> Unit,
    val onTogglePlay: (ChatMessage) -> Unit,
    val onSeek: (ChatMessage, Float) -> Unit,
    val onReact: (ChatMessage, String) -> Unit,
    val onQuote: (Long) -> Unit,
    /** Messenger 1.4 cards: polls, events, arrival, actions, plugin cards. */
    val rich: RichActions = RichActions(),
)

/** A stored message: bubble (or system line), reactions below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    m: ChatMessage,
    status: String?,
    showSender: Boolean,
    conversation: ChatConversation?,
    myId: Long?,
    player: VoicePlayer.State,
    actions: BubbleActions,
    highlighted: Boolean = false,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    if (m.isSystem) {
        SystemLine(ChatText.system(ctx, m, conversation, myId), notice = m.system?.event == "notice" || m.system == null, via = m.via)
        return
    }
    val colors = bubbleColors(m.mine)
    // Pictures and videos fill the bubble up to a small margin.
    val visual = !m.deleted && !m.undecryptable && (m.kind == ChatMessage.KIND_IMAGE || m.isVideo)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (conversation?.isGroup == true && !m.mine) {
                if (showSender) {
                    val sender = conversation.members.firstOrNull { it.userId == m.senderId }
                    ChatPersonAvatar(sender, 28.dp)
                } else {
                    Spacer(Modifier.width(28.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(shape(m.mine))
                    .background(if (highlighted) extra.blueBg else colors.bg)
                    .border(1.dp, colors.border, shape(m.mine))
                    .combinedClickable(
                        onClick = {
                            when {
                                m.deleted -> Unit
                                m.kind == ChatMessage.KIND_IMAGE -> actions.onOpenImage(m)
                                m.kind == ChatMessage.KIND_FILE -> actions.onOpenFile(m)
                                m.kind == ChatMessage.KIND_LOCATION -> actions.onOpenLocation(m)
                                else -> Unit
                            }
                        },
                        onLongClick = { actions.onLongPress(m) },
                        onLongClickLabel = stringResource(R.string.chat_actions),
                    )
                    .padding(if (visual) 4.dp else 10.dp),
            ) {
                if (showSender && conversation?.isGroup == true && !m.mine) {
                    Text(
                        ChatText.nameOf(ctx, conversation, m.senderId, myId),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = extra.accentText,
                        modifier = Modifier.padding(start = if (visual) 6.dp else 0.dp, bottom = 2.dp),
                    )
                }
                m.replyTo?.let { r -> Quote(r.senderId, if (r.deleted || r.missing) null else r.text, r.kind, r.deleted || r.missing, conversation, myId) { actions.onQuote(r.id) } }
                m.replyStory?.let { r -> if (!m.deleted) ReplyStoryQuote(r, conversation, myId) }
                when {
                    m.deleted -> Text(
                        ChatText.content(ctx, m, conversation, myId),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = colors.meta,
                    )
                    m.undecryptable -> Text(stringResource(R.string.chat_undecryptable), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = colors.meta)
                    // Without a preview the full picture loads only when automatic downloads allow it (or on tap).
                    m.kind == ChatMessage.KIND_IMAGE -> ImageContent(m.media?.thumbId ?: m.media?.id?.takeIf { LocalChatLook.current.autoPhotos }, null, m.meta?.w, m.meta?.h)
                    m.kind == ChatMessage.KIND_VOICE -> VoiceContent(
                        durationMs = m.meta?.durationMs ?: 0L,
                        waveform = m.meta?.waveform.orEmpty(),
                        state = player.takeIf { it.key == "m:${m.id}" },
                        onToggle = { actions.onTogglePlay(m) },
                        onSeek = { f -> actions.onSeek(m, f) },
                    )
                    m.isVideo -> VideoContent(
                        thumbId = m.media?.thumbId,
                        localThumb = null,
                        durationMs = m.videoDurationMs,
                        w = m.video?.width ?: m.meta?.w,
                        h = m.video?.height ?: m.meta?.h,
                    )
                    m.kind == ChatMessage.KIND_FILE -> FileContent(
                        m.meta?.fileName ?: stringResource(R.string.chat_kind_file), m.media?.bytes, m.media?.mime,
                        thumbId = m.media?.thumbId,
                    )
                    m.kind == ChatMessage.KIND_LOCATION -> m.location?.let { LocationContent(it.label, it.lat, it.lon) }
                    // Messenger 1.4 kinds.
                    m.poll != null -> PollContent(m, m.poll, conversation, myId, actions.rich)
                    m.event != null -> EventContent(m, m.event, conversation, myId, actions.rich)
                    m.arrival != null -> ArrivalContent(m, m.arrival, actions.rich)
                    m.action != null -> ActionContent(m, m.action, conversation, myId, actions.rich)
                    m.card != null -> PluginCardContent(m, actions.rich)
                    m.isUnknownKind || m.kind in RICH_KINDS -> UnknownKindContent(m, actions.rich)
                }
                val caption = m.text?.takeIf { !m.deleted && it.isNotBlank() && !m.isUnknownKind && m.kind !in RICH_KINDS }
                if (caption != null) {
                    val scale = LocalChatLook.current.fontScale
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyLarge.let { if (scale == 1f) it else it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
                        color = colors.fg,
                        modifier = Modifier.padding(
                            start = if (visual) 6.dp else 0.dp,
                            end = if (visual) 6.dp else 0.dp,
                            top = if (m.kind == ChatMessage.KIND_TEXT) 0.dp else 6.dp,
                        ),
                    )
                }
                MetaLine(
                    time = ChatText.clock(ctx, m.createdAt),
                    edited = m.editedAt != null && !m.deleted,
                    disappearing = m.expiresAt != null,
                    status = status,
                    pending = false,
                    color = colors.meta,
                    modifier = Modifier.align(Alignment.End).padding(end = if (visual) 6.dp else 0.dp),
                )
            }
        }
        if (m.reactions.isNotEmpty()) Reactions(m.reactions, myId, m.mine) { e -> actions.onReact(m, e) }
    }
}

/** A message still in the outbox: shown right away, with a clock — or a red mark when it failed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PendingBubble(
    p: ChatPending,
    conversation: ChatConversation?,
    myId: Long?,
    player: VoicePlayer.State,
    onLongPress: (ChatPending) -> Unit,
    onTogglePlay: (ChatPending) -> Unit,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    val colors = bubbleColors(true)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalAlignment = Alignment.End) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape(true))
                .background(colors.bg)
                .combinedClickable(onClick = { if (p.failed) onLongPress(p) }, onLongClick = { onLongPress(p) })
                .padding(if (p.kind == ChatMessage.KIND_IMAGE || p.isVideo) 4.dp else 10.dp),
        ) {
            when {
                p.isVideo -> VideoContent(thumbId = null, localThumb = p.thumbPath, durationMs = p.meta?.durationMs, w = p.meta?.w, h = p.meta?.h)
                p.kind == ChatMessage.KIND_IMAGE -> ImageContent(null, p.localPath, p.meta?.w, p.meta?.h)
                p.kind == ChatMessage.KIND_VOICE -> VoiceContent(
                    durationMs = p.meta?.durationMs ?: 0L,
                    waveform = p.meta?.waveform.orEmpty(),
                    state = player.takeIf { it.key == "c:${p.clientId}" },
                    onToggle = { onTogglePlay(p) },
                    onSeek = { },
                )
                p.kind == ChatMessage.KIND_FILE -> FileContent(
                    p.meta?.fileName ?: stringResource(R.string.chat_kind_file), p.localPath?.let { java.io.File(it).length() }, p.mime,
                    localThumb = p.thumbPath,
                )
                p.kind == ChatMessage.KIND_LOCATION -> p.location?.let { LocationContent(it.label, it.lat, it.lon) }
            }
            p.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.fg,
                    modifier = Modifier.padding(top = if (p.kind == ChatMessage.KIND_TEXT) 0.dp else 6.dp, start = if (p.kind == ChatMessage.KIND_IMAGE || p.isVideo) 6.dp else 0.dp),
                )
            }
            MetaLine(ChatText.clockMs(ctx, p.createdAtMs), edited = false, disappearing = false, status = null, pending = !p.failed, color = colors.meta, modifier = Modifier.align(Alignment.End))
        }
        if (p.failed) {
            Row(Modifier.padding(top = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                Text(
                    ChatText.sendError(ctx, p.error) + " · " + stringResource(R.string.chat_tap_for_options),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (p.error == "offline") {
            Text(stringResource(R.string.chat_waiting_network), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(top = 2.dp, end = 4.dp))
        }
    }
}

@Composable
fun DaySeparator(text: String) {
    val extra = GateControlTheme.extraColors
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = extra.muted,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(extra.panel2).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SystemLine(text: String, notice: Boolean, via: String?) {
    val extra = GateControlTheme.extraColors
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (notice) extra.blueBg else extra.panel2).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice) {
                Text(
                    stringResource(R.string.chat_gatecontrol),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = extra.blue,
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (notice) MaterialTheme.colorScheme.onSurface else extra.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MetaLine(time: String, edited: Boolean, disappearing: Boolean, status: String?, pending: Boolean, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (disappearing) Text("⏱", fontSize = 10.sp, color = color)
        if (edited) Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = color)
        Text(time, style = MaterialTheme.typography.labelSmall, color = color)
        if (status != null || pending) ChatTicks(status, pending, tint = color)
    }
}

@Composable
fun Quote(senderId: Long?, text: String?, kind: String?, gone: Boolean, conversation: ChatConversation?, myId: Long?, onClick: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(extra.panel2)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 40.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().heightIn(min = 40.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                ChatText.nameOf(ctx, conversation, senderId, myId),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = extra.accentText,
                maxLines = 1,
            )
            Text(
                when {
                    gone -> stringResource(R.string.chat_quote_gone)
                    !text.isNullOrBlank() -> text
                    kind == ChatMessage.KIND_IMAGE -> stringResource(R.string.chat_kind_image)
                    kind == ChatMessage.KIND_VOICE -> stringResource(R.string.chat_kind_voice)
                    kind == ChatMessage.KIND_FILE -> stringResource(R.string.chat_kind_file)
                    kind == ChatMessage.KIND_LOCATION -> stringResource(R.string.chat_kind_location)
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = extra.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ImageContent(mediaId: String?, localPath: String?, w: Int?, h: Int?) {
    val extra = GateControlTheme.extraColors
    val px = with(LocalDensity.current) { 280.dp.roundToPx() }
    val bmp = if (localPath != null) rememberLocalBitmap(localPath, px) else rememberChatBitmap(mediaId, px)
    val ratio = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f) else 4f / 3f
    Box(
        Modifier.width(260.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(extra.panel2),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = stringResource(R.string.chat_kind_image), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
        } else {
            Icon(Icons.Outlined.Image, contentDescription = stringResource(R.string.chat_kind_image), tint = extra.faint, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun VoiceContent(durationMs: Long, waveform: List<Int>, state: VoicePlayer.State?, onToggle: () -> Unit, onSeek: (Float) -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val total = state?.durationMs?.takeIf { it > 0 } ?: durationMs
    val progress = if (state != null && total > 0) (state.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(240.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(scheme.primary).clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state?.playing == true) R.string.chat_pause else R.string.chat_play),
                tint = scheme.onPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            val bars = VoiceWave.bars(waveform, 36)
            Row(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(total) {
                    detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bars.forEachIndexed { i, v ->
                    val played = (i + 0.5f) / bars.size <= progress
                    Box(
                        Modifier.weight(1f).fillMaxHeight(v).clip(RoundedCornerShape(1.dp))
                            .background(if (played) scheme.primary else extra.faint),
                    )
                }
            }
            Text(
                if (state != null && (state.playing || state.positionMs > 0)) VoiceWave.clock(state.positionMs) + " / " + VoiceWave.clock(total) else VoiceWave.clock(total),
                style = MaterialTheme.typography.labelSmall,
                color = extra.muted,
            )
        }
    }
}

/**
 * A video file: its preview frame (when the sender's app made one) with a
 * play symbol and the length; tapping opens it in a video app.
 */
@Composable
internal fun VideoContent(thumbId: String?, localThumb: String?, durationMs: Long?, w: Int?, h: Int?, width: Dp = 260.dp) {
    val px = with(LocalDensity.current) { width.roundToPx() }
    val bmp = if (localThumb != null) rememberLocalBitmap(localThumb, px) else rememberChatBitmap(thumbId, px)
    val ratio = if (w != null && h != null && w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2f)
    else bmp?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.5f, 2f) } ?: (16f / 9f)
    val label = stringResource(R.string.chat_kind_video)
    Box(
        Modifier.width(width).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(VIDEO_BG),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(ratio))
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.chat_play_video), tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Text(
                durationMs?.takeIf { it > 0 }?.let { VoiceWave.clock(it) } ?: label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

internal val VIDEO_BG = Color(0xFF1B1F24)

/** Kinds whose content is an object (no caption of their own). */
private val RICH_KINDS = setOf(
    ChatMessage.KIND_POLL, ChatMessage.KIND_EVENT, ChatMessage.KIND_ARRIVAL, ChatMessage.KIND_ACTION, ChatMessage.KIND_CARD,
)

@Composable
private fun FileContent(name: String, bytes: Long?, mime: String?, thumbId: String? = null, localThumb: String? = null) {
    val extra = GateControlTheme.extraColors
    if (thumbId != null || localThumb != null) {
        // A preview of the file (a PDF's first page) above its name.
        val px = with(LocalDensity.current) { 260.dp.roundToPx() }
        val bmp = if (localThumb != null) rememberLocalBitmap(localThumb, px) else rememberChatBitmap(thumbId, px)
        if (bmp != null) {
            val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.6f, 2f)
            Image(
                bmp,
                contentDescription = stringResource(R.string.chat_file_preview),
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 8.dp).widthIn(max = 260.dp).fillMaxWidth().aspectRatio(ratio)
                    .clip(RoundedCornerShape(10.dp)).border(1.dp, extra.border, RoundedCornerShape(10.dp)),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(extra.panel2), contentAlignment = Alignment.Center) {
            val ext = name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
            if (ext.isNotEmpty()) {
                Text(ext, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold), color = extra.accentText)
            } else {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = extra.accentText)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(ChatText.bytes(bytes).takeIf { it.isNotEmpty() }, mime?.substringAfter('/')?.take(20)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

@Composable
private fun LocationContent(label: String?, lat: Double, lon: Double) {
    val extra = GateControlTheme.extraColors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(extra.blueBg), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Place, contentDescription = null, tint = extra.blue)
        }
        Column(Modifier.weight(1f)) {
            Text(
                label ?: String.format(Locale.ROOT, "%.5f, %.5f", lat, lon),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.chat_location_once), style = MaterialTheme.typography.bodySmall, color = extra.muted)
        }
    }
}

@Composable
private fun Reactions(reactions: List<ChatReaction>, myId: Long?, mine: Boolean, onToggle: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.padding(start = if (mine) 0.dp else 34.dp, top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.take(6).forEach { r ->
            val me = myId != null && myId in r.userIds
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (me) extra.accentBg else extra.panel2)
                    .border(1.dp, extra.border, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { onToggle(r.emoji) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.emoji, fontSize = 14.sp)
                if (r.count > 1) Text(" ${r.count}", style = MaterialTheme.typography.labelSmall, color = extra.muted)
            }
        }
    }
}
