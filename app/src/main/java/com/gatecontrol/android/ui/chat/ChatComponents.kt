package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.chat.ChatMessage
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatMediaEntryPoint {
    fun chatMediaStore(): ChatMediaStore
}

@Composable
fun rememberChatMediaStore(): ChatMediaStore? {
    if (LocalInspectionMode.current) return null
    val context = LocalContext.current.applicationContext
    return remember(context) {
        runCatching { EntryPointAccessors.fromApplication(context, ChatMediaEntryPoint::class.java).chatMediaStore() }.getOrNull()
    }
}

/** A chat picture ([mediaId]) decoded to about [sizePx]; null while loading, offline or not allowed. */
@Composable
fun rememberChatBitmap(mediaId: String?, sizePx: Int): ImageBitmap? {
    if (mediaId == null) return null
    val store = rememberChatMediaStore() ?: return null
    val bitmap by produceState(store.peekBitmap(mediaId, sizePx)?.asImageBitmap(), mediaId, sizePx) {
        if (value == null) value = store.bitmap(mediaId, sizePx)?.asImageBitmap()
    }
    return bitmap
}

/** A picture still in the outbox (local copy). */
@Composable
fun rememberLocalBitmap(path: String?, sizePx: Int): ImageBitmap? {
    if (path == null) return null
    val store = rememberChatMediaStore() ?: return null
    val bitmap by produceState<ImageBitmap?>(null, path, sizePx) {
        value = store.localBitmap(path, sizePx)?.asImageBitmap()
    }
    return bitmap
}

/** Avatar of a person in the chats (picture or initials on the favourite colour). */
@Composable
fun ChatPersonAvatar(person: ChatPerson?, size: Dp, modifier: Modifier = Modifier) {
    val name = person?.name
    GcAvatar(
        url = person?.avatarUrl,
        initials = person?.initials ?: ChatPerson.initialsOf(name),
        colorHex = person?.colorHex,
        ring = AvatarRing.None,
        size = size,
        modifier = modifier,
    )
}

/**
 * Avatar of a conversation: the other person, or the group's picture / a group symbol. A chat over a bridge
 * (Messenger 1.14) carries the service's mark at the lower right ("WA"); contacts have no picture yet.
 */
@Composable
fun ConversationAvatar(c: ChatConversation, size: Dp, modifier: Modifier = Modifier) {
    val bridge = c.bridge
    if (bridge != null) {
        val via = stringResource(R.string.chat_bridged_via, bridge.label)
        Box(modifier.size(size).semantics { contentDescription = via }) {
            if (c.isGroup) PlainAvatar(c, size)
            else GcAvatar(url = null, initials = ChatPerson.initialsOf(c.title), colorHex = null, ring = AvatarRing.None, size = size)
            BridgeMark(bridge, size, Modifier.align(Alignment.BottomEnd))
        }
        return
    }
    PlainAvatar(c, size, modifier)
}

/** The service's mark of a bridged chat: two letters on the accent colour, ringed in the page's colour. */
@Composable
private fun BridgeMark(bridge: com.gatecontrol.android.chat.ChatConvBridge, avatar: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val h = (avatar * 0.4f).coerceIn(14.dp, 22.dp)
    Box(
        modifier.offset(x = 3.dp, y = 2.dp).height(h).widthIn(min = h * 1.3f)
            .clip(RoundedCornerShape(h / 3)).background(scheme.background).padding(1.5.dp)
            .clip(RoundedCornerShape(h / 3)).background(scheme.primary).padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            com.gatecontrol.android.chat.ChatBridgeChat.mark(bridge),
            color = scheme.onPrimary,
            fontSize = (h.value * 0.48f).sp,
            lineHeight = (h.value * 0.5f).sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlainAvatar(c: ChatConversation, size: Dp, modifier: Modifier = Modifier) {
    if (!c.isGroup) {
        ChatPersonAvatar(c.peer, size, modifier)
        return
    }
    val extra = GateControlTheme.extraColors
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp = rememberChatBitmap(c.avatarMediaId, px)
    Box(
        modifier.size(size).clip(CircleShape).background(extra.accentBg),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Outlined.Groups, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/**
 * Ticks of an own message: pending clock, sent ✓, delivered ✓✓, read ✓✓ in the accent colour — in a bridged chat
 * (Messenger 1.14) also "on its way to the bridge" (clock) and "not sent" (a mark in the error colour).
 */
@Composable
fun ChatTicks(status: String?, pending: Boolean, modifier: Modifier = Modifier, tint: Color = GateControlTheme.extraColors.muted) {
    val size = Modifier.size(16.dp)
    when {
        pending || status == ChatMessage.STATUS_SENDING -> Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.chat_status_pending), tint = tint, modifier = modifier.then(size))
        status == ChatMessage.STATUS_READ -> Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.chat_status_read), tint = MaterialTheme.colorScheme.primary, modifier = modifier.then(size))
        status == ChatMessage.STATUS_DELIVERED -> Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.chat_status_delivered), tint = tint, modifier = modifier.then(size))
        status == ChatMessage.STATUS_SENT -> Icon(Icons.Filled.Done, contentDescription = stringResource(R.string.chat_status_sent), tint = tint, modifier = modifier.then(size))
        status == ChatMessage.STATUS_FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = stringResource(R.string.chat_bridged_status_failed), tint = MaterialTheme.colorScheme.error, modifier = modifier.then(size))
    }
}
