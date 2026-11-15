package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.style.TextDecoration
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.chat.ChatPerson
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import com.gatecontrol.android.scan.MediaScan
import com.gatecontrol.android.scan.ScanBadge
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.scan.ScanLine
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
    /** Messenger 1.14: "Erneut senden" of an own message the bridge did not take. */
    val onResend: (ChatMessage) -> Unit = {},
    /** Messenger 1.17: "Laden" of a file of the older history; the second argument is called once the answer is there. */
    val onFetch: (ChatMessage, () -> Unit) -> Unit = { _, done -> done() },
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
    /** Messenger 1.21, the chat of a person: the way of the message — its logo beside the time (read „über [wayLabel]“). */
    way: String? = null,
    wayLabel: String? = null,
) {
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    if (m.isSystem) {
        // Messenger 1.14: what the bridge itself says carries the service's name, not "GateControl".
        val bridgeNotice = m.system?.event == com.gatecontrol.android.chat.ChatBridgeChat.EVENT_NOTICE
        SystemLine(
            ChatText.system(ctx, m, conversation, myId),
            notice = m.system?.event == "notice" || m.system == null || bridgeNotice,
            via = m.via,
            label = if (bridgeNotice) conversation?.bridge?.label ?: stringResource(R.string.chat_linked_title) else null,
        )
        return
    }
    val bridge = conversation?.bridge
    val failed = m.mine && !m.deleted && m.bridge?.failed == true
    val colors = bubbleColors(m.mine)
    // Pictures and videos fill the bubble up to a small margin.
    // the virus scan blocked the file (Messenger 1.11): a mark instead of the picture, player or file
    val blocked = !m.deleted && !m.undecryptable && m.media?.blocked == true && m.kind in MEDIA_KINDS
    val visual = !m.deleted && !m.undecryptable && !blocked && (m.kind == ChatMessage.KIND_IMAGE || m.isVideo)
    // Messenger 1.20: a channel's post — the channel writes it, no sender's picture or name
    val group = conversation?.isGroup == true && conversation.bridge?.channel != true && m.bridge?.channel != true
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (group && !m.mine) {
                if (showSender) {
                    val sender = conversation.members.firstOrNull { it.userId == m.senderId }
                    // a contact of a bridged chat: the contact's picture (Messenger 1.15, once fetched), else the initials
                    if (sender == null && m.bridge?.fromContact == true) {
                        ContactAvatar(m.bridge.senderAvatarMediaId, m.bridge.senderName, 28.dp)
                    } else {
                        ChatPersonAvatar(sender, 28.dp)
                    }
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
                if (showSender && group && !m.mine) {
                    Text(
                        ChatText.senderOf(ctx, conversation!!, m, myId),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = extra.accentText,
                        modifier = Modifier.padding(start = if (visual) 6.dp else 0.dp, bottom = 2.dp),
                    )
                }
                // Messenger 1.20: a link's preview on top of the bubble
                m.preview?.takeIf { !m.deleted }?.let { LinkPreviewCard(it) }
                m.replyTo?.let { r ->
                    Quote(r.senderId, if (r.deleted || r.missing) null else r.text, r.kind, r.deleted || r.missing, conversation, myId, name = ChatText.quotedOf(ctx, conversation, r, myId)) { actions.onQuote(r.id) }
                }
                // Messenger 1.14: a file or kind of message that has not crossed the bridge — a line says it arrived;
                // 1.15: "wird geladen …" while the server fetches it, why it will not come, a location to the map.
                m.bridge?.placeholder?.takeIf { !m.deleted }?.let { ph ->
                    BridgePlaceholderLine(
                        ph, bridge?.label ?: stringResource(R.string.chat_linked_title), colors.meta, hasText = !m.text.isNullOrBlank(),
                        onFetch = { done -> actions.onFetch(m, done) },
                    ) {
                        actions.onOpenLocation(m.copy(location = com.gatecontrol.android.chat.ChatLocation(ph.lat ?: 0.0, ph.lon ?: 0.0, m.text?.takeIf { it.isNotBlank() })))
                    }
                }
                m.replyStory?.let { r -> if (!m.deleted) ReplyStoryQuote(r, conversation, myId) }
                when {
                    m.deleted -> Text(
                        ChatText.content(ctx, m, conversation, myId),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = colors.meta,
                    )
                    m.undecryptable -> Text(stringResource(R.string.chat_undecryptable), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = colors.meta)
                    blocked -> m.media?.scan?.let { ScanBlockedContent(it, colors.fg, colors.meta) }
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
                // marked as malware (not blocked) or not checkable: a line under the file (beim Empfänger und beim Absender)
                if (!m.deleted && !blocked && m.kind in MEDIA_KINDS) {
                    ScanLine(m.media?.scan, Modifier.padding(top = 6.dp, start = if (visual) 6.dp else 0.dp, end = if (visual) 6.dp else 0.dp))
                }
                val caption = m.text?.takeIf { !m.deleted && it.isNotBlank() && !m.isUnknownKind && m.kind !in RICH_KINDS }
                if (caption != null) {
                    val scale = LocalChatLook.current.fontScale
                    // Messenger 1.20: the format of a bridged message, and addresses as links in every chat
                    Text(
                        chatRichText(caption, m.marks),
                        style = MaterialTheme.typography.bodyLarge.let { if (scale == 1f) it else it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
                        color = colors.fg,
                        modifier = Modifier.padding(
                            start = if (visual) 6.dp else 0.dp,
                            end = if (visual) 6.dp else 0.dp,
                            top = if (m.kind == ChatMessage.KIND_TEXT) 0.dp else 6.dp,
                        ),
                    )
                }
                // Messenger 1.14: the bridge did not take it — why, and again (while sending works at all).
                if (failed) {
                    Text(
                        stringResource(R.string.chat_bridged_failed) + " " + ChatText.bridgeReason(ctx, m.bridge?.reason),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.fg,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (conversation?.can?.send == true) {
                        Text(
                            stringResource(R.string.chat_bridged_resend),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                            color = colors.fg,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { actions.onResend(m) }.heightIn(min = 32.dp).padding(vertical = 6.dp),
                        )
                    }
                }
                MetaLine(
                    time = ChatText.clock(ctx, m.createdAt),
                    edited = m.editedAt != null && !m.deleted,
                    disappearing = m.expiresAt != null,
                    status = status,
                    pending = false,
                    color = colors.meta,
                    modifier = Modifier.align(Alignment.End).padding(end = if (visual) 6.dp else 0.dp),
                    way = way,
                    wayLabel = wayLabel?.let { stringResource(R.string.chat_pp_via, it) },
                )
            }
        }
        // a bridged chat: a reaction goes to the other messenger first — not while nothing crosses
        if (m.reactions.isNotEmpty()) Reactions(m.reactions, myId, indent = !m.mine && conversation?.bridge?.channel != true) { e -> if (conversation == null || com.gatecontrol.android.chat.ChatBridgeChat.reacts(conversation, m)) actions.onReact(m, e) }
        // Messenger 1.20: in a channel only the own reaction crosses — WhatsApp shows the others in its own app
        if (com.gatecontrol.android.chat.ChatBridgeChat.ownReactionNote(conversation, m, myId)) {
            Row(Modifier.padding(start = if (m.mine) 0.dp else 4.dp, top = 4.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = extra.muted, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                Text(stringResource(R.string.chat_ch_own_reaction), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        }
    }
}

/**
 * The line of a file that has not crossed a bridge: kind and name, then "wird geladen …" (pulsing), why it did not
 * come or "folgt"; a location with its place offers the map ([onMap]); a file of the older history (Messenger 1.17)
 * "Laden" ([onFetch], once until the answer is there).
 */
@Composable
private fun BridgePlaceholderLine(
    ph: com.gatecontrol.android.chat.ChatBridgePlaceholder,
    network: String,
    tint: Color,
    hasText: Boolean,
    onFetch: (done: () -> Unit) -> Unit = { it() },
    onMap: () -> Unit,
) {
    val ctx = LocalContext.current
    // asked for: no second tap until the server answered (then the line says "wird geladen …", or the button returns)
    var asking by remember(ph.state) { mutableStateOf(false) }
    val infected = ph.failed && ph.reason == com.gatecontrol.android.chat.ChatBridgeChat.PH_REASON_INFECTED
    val alpha = if (ph.loading) {
        val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "ph")
        pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "ph-alpha",
        ).value
    } else {
        1f
    }
    Column(Modifier.padding(bottom = if (hasText) 4.dp else 0.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.graphicsLayer { this.alpha = alpha }) {
            Icon(
                when {
                    infected -> GcIcons.Alert
                    ph.kind == com.gatecontrol.android.chat.ChatBridgeChat.PH_LOCATION -> Icons.Outlined.Place
                    ph.kind == "image" || ph.kind == "sticker" -> Icons.Outlined.Image
                    ph.kind == "video" -> Icons.Outlined.Videocam
                    ph.kind == com.gatecontrol.android.chat.ChatBridgeChat.PH_UNSUPPORTED -> Icons.Outlined.Info
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = if (infected) MaterialTheme.colorScheme.error else tint,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Text(
                ChatText.placeholder(ctx, ph, network),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = tint,
            )
        }
        if (ph.onRequest) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, enabled = !asking) { asking = true; onFetch { asking = false } }
                    .heightIn(min = 32.dp)
                    .padding(top = 6.dp, bottom = 4.dp)
                    .testTag("ph-fetch"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.chat_bridged_ph_fetch),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                    color = if (asking) tint else MaterialTheme.colorScheme.primary,
                )
                if (asking) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint)
            }
        }
        // Messenger 1.19: a file that failed and may come when asked for again
        if (ph.retryable) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, enabled = !asking) { asking = true; onFetch { asking = false } }
                    .heightIn(min = 32.dp)
                    .padding(top = 6.dp, bottom = 4.dp)
                    .testTag("ph-retry"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.chat_bridged_ph_retry),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                    color = if (asking) tint else MaterialTheme.colorScheme.primary,
                )
                if (asking) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint)
            }
        }
        if (ph.geo) {
            Text(
                stringResource(R.string.chat_bridged_ph_map),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onMap).heightIn(min = 32.dp).padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

/** A contact of a bridged chat: their picture ([mediaId], Messenger 1.15), else the initials of [name]. */
@Composable
internal fun ContactAvatar(mediaId: String?, name: String?, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp = rememberChatBitmap(mediaId, px)
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    } else {
        GcAvatar(url = null, initials = ChatPerson.initialsOf(name), colorHex = null, ring = AvatarRing.None, size = size)
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
                    chatRichText(it, null),
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
private fun SystemLine(text: String, notice: Boolean, via: String?, label: String? = null) {
    val extra = GateControlTheme.extraColors
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (notice) extra.blueBg else extra.panel2).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notice) {
                Text(
                    label ?: stringResource(R.string.chat_gatecontrol),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = extra.blue,
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (notice) MaterialTheme.colorScheme.onSurface else extra.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MetaLine(time: String, edited: Boolean, disappearing: Boolean, status: String?, pending: Boolean, color: Color, modifier: Modifier = Modifier, way: String? = null, wayLabel: String? = null) {
    Row(modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (way != null) ChatNetLogo(way, 12.dp, label = wayLabel)
        if (disappearing) Text("⏱", fontSize = 10.sp, color = color)
        if (edited) Text(stringResource(R.string.chat_edited), style = MaterialTheme.typography.labelSmall, color = color)
        Text(time, style = MaterialTheme.typography.labelSmall, color = color)
        if (status != null || pending) ChatTicks(status, pending, tint = color)
    }
}

@Composable
fun Quote(
    senderId: Long?,
    text: String?,
    kind: String?,
    gone: Boolean,
    conversation: ChatConversation?,
    myId: Long?,
    /** Who is quoted when it is not an account (a contact of a bridged chat, Messenger 1.14). */
    name: String? = null,
    onClick: (() -> Unit)? = null,
) {
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
                name ?: ChatText.nameOf(ctx, conversation, senderId, myId),
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

/** Kinds that carry a file (the virus scan checks them, Messenger 1.11). */
private val MEDIA_KINDS = setOf(ChatMessage.KIND_IMAGE, ChatMessage.KIND_VOICE, ChatMessage.KIND_FILE)

/**
 * A file the virus scan blocked (Messenger 1.11), for receiver and sender alike: "Wird auf Viren geprüft …" (it opens
 * after the check), "Datei gesperrt" with "Schadsoftware gefunden (…)", or "Datei gesperrt – konnte nicht geprüft werden".
 */
@Composable
private fun ScanBlockedContent(scan: MediaScan, fg: Color, meta: Color) {
    val checking = scan.badge == ScanBadge.CHECKING
    val title = when (scan.badge) {
        ScanBadge.CHECKING -> stringResource(R.string.scan_detail_checking_open)
        ScanBadge.BLOCKED_MALWARE -> stringResource(R.string.scan_chat_blocked)
        else -> stringResource(R.string.scan_chat_blocked_unscannable)
    }
    val line = if (scan.badge == ScanBadge.BLOCKED_MALWARE) {
        scan.finding?.let { stringResource(R.string.scan_chat_blocked_malware, it) } ?: stringResource(R.string.scan_chat_blocked_malware_plain)
    } else {
        null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (checking) GcIcons.Clock else GcIcons.Alert,
            contentDescription = null,
            tint = if (checking) meta else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.widthIn(max = 240.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
            line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = meta) }
        }
    }
}

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
private fun Reactions(reactions: List<ChatReaction>, myId: Long?, indent: Boolean, onToggle: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.padding(start = if (indent) 34.dp else 0.dp, top = 2.dp),
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
