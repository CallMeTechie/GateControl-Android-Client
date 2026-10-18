package com.gatecontrol.android.ui.chat.status

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatFeedItem
import com.gatecontrol.android.chat.ChatStatusLogic
import com.gatecontrol.android.chat.ChatStory
import com.gatecontrol.android.chat.ChatStoryViewer
import com.gatecontrol.android.chat.ChatTimeline
import com.gatecontrol.android.ui.chat.ChatPersonAvatar
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.chat.ConfirmDialog
import com.gatecontrol.android.ui.chat.VideoContent
import com.gatecontrol.android.ui.chat.openFile
import com.gatecontrol.android.ui.chat.rememberChatBitmap
import com.gatecontrol.android.ui.chat.rememberChatMediaStore
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import kotlinx.coroutines.launch

private const val STORY_MS = 6_000

private val REACTIONS = listOf("👏", "😂", "💪", "❤️")

/** Pure: how a story's age and time left read ("vor 12 Min. · noch 23 Std."). */
object ChatStoryTime {
    /** Minutes since [createdAt]; null when unknown. */
    fun minutesAgo(createdAt: String?, nowMs: Long): Long? = ChatTimeline.epochMs(createdAt)?.let { ((nowMs - it) / 60_000L).coerceAtLeast(0) }

    /** Minutes left until [expiresAt]. */
    fun minutesLeft(expiresAt: String?, nowMs: Long): Long? = ChatTimeline.epochMs(expiresAt)?.let { ((it - nowMs) / 60_000L).coerceAtLeast(0) }
}

/**
 * Story viewer (mockup "Status ansehen"): progress segments, tap left /
 * right, hold or the button to pause; reactions and an answer that lands
 * in the direct chat (`replyStory`); the own stories show who saw them.
 * [userId] 0 = the own stories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    userId: Long,
    onClose: () -> Unit,
    onOpenComposer: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val feed = ui.feed
    // Who is shown in order: from the tapped person on through the strip.
    val queue: List<ChatFeedItem> = remember(feed, userId) {
        if (feed == null) emptyList()
        else if (userId == 0L) listOfNotNull(feed.me?.takeIf { it.stories.isNotEmpty() })
        else {
            val strip = ChatStatusLogic.strip(feed)
            val start = strip.indexOfFirst { it.user.userId == userId }.coerceAtLeast(0)
            strip.drop(start)
        }
    }
    var userIdx by remember(userId) { mutableIntStateOf(0) }
    val item = queue.getOrNull(userIdx)
    var storyIdx by remember(userId, userIdx) { mutableIntStateOf(item?.let { ChatStatusLogic.startIndex(it) } ?: 0) }
    var paused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var viewers by remember { mutableStateOf<List<ChatStoryViewer>?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val own = userId == 0L

    LaunchedEffect(Unit) {
        viewModel.notice.collect { n ->
            Toast.makeText(ctx, if (n is StatusNotice.Done) res.getString(n.res) else ChatText.actionError(ctx, (n as StatusNotice.Error).code), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(feed == null) { if (feed == null) viewModel.refresh() }

    fun next() {
        val it = item ?: return onClose()
        if (storyIdx < it.stories.size - 1) storyIdx++
        else if (userIdx < queue.size - 1) userIdx++
        else onClose()
    }

    fun prev() {
        if (storyIdx > 0) storyIdx--
        else if (userIdx > 0) userIdx--
    }

    val story = item?.stories?.getOrNull(storyIdx)
    // Seen once shown; the progress runs unless paused or typing.
    LaunchedEffect(item?.user?.userId, story?.id) {
        progress.snapTo(0f)
        if (item != null && story != null && !own) viewModel.markSeen(item, story)
        viewers = null
    }
    LaunchedEffect(item?.user?.userId, story?.id, paused, typing, showViewers) {
        if (item == null || (story == null && item.status == null)) return@LaunchedEffect
        if (paused || typing || showViewers || confirmDelete) return@LaunchedEffect
        val left = ((1f - progress.value) * STORY_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        next()
    }
    LaunchedEffect(story?.id, own) {
        if (own && story != null) viewers = viewModel.viewers(story)
    }

    Box(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        if (item == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.chat_story_none), color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (own) Text(
                    stringResource(R.string.chat_story_add),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp).clickable(role = Role.Button, onClick = onOpenComposer),
                )
            }
            GcIconButton(GcIcons.Back, stringResource(R.string.chat_back), onClick = onClose, tint = Color.White, modifier = Modifier.padding(8.dp))
            return@Box
        }
        // ─── content ───
        Box(
            Modifier.fillMaxSize().padding(top = 72.dp, bottom = 132.dp).clip(RoundedCornerShape(20.dp))
                .background(if (story == null || story.kind == ChatStory.KIND_TEXT) storyColor(story?.bg) else Color(0xFF0B0F14))
                .pointerInput(item.user.userId, story?.id) {
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { offset -> if (offset.x < size.width / 3) prev() else next() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                story == null -> Text(
                    StatusText.line(ctx, item.status).orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(28.dp),
                )
                story.kind == ChatStory.KIND_TEXT -> Text(
                    story.text.orEmpty(),
                    color = Color.White,
                    fontFamily = storyFont(story.font),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState()),
                )
                story.kind == ChatStory.KIND_IMAGE -> {
                    val px = with(LocalDensity.current) { 1080.dp.roundToPx() }
                    val bmp = rememberChatBitmap(story.mediaId, px) ?: rememberChatBitmap(story.thumbMediaId, px)
                    if (bmp != null) Image(bmp, contentDescription = story.text, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                else -> {
                    val store = rememberChatMediaStore()
                    Box(
                        Modifier.clickable(role = Role.Button) {
                            paused = true
                            scope.launch {
                                val f = story.mediaId?.let { store?.file(it) }
                                if (f != null) openFile(ctx, f, "status-${story.id}.mp4", "video/mp4", share = false)
                            }
                        },
                    ) {
                        VideoContent(story.thumbMediaId, null, story.video?.durationMs, story.video?.width, story.video?.height, width = 300.dp)
                    }
                }
            }
            if (story != null && story.kind != ChatStory.KIND_TEXT && !story.text.isNullOrBlank()) {
                Text(
                    story.text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(12.dp),
                )
            }
            val place = story?.place ?: item.status?.place
            if (place != null) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    val since = item.status?.since?.let { ChatText.clock(ctx, it) }
                    Text(if (since != null) stringResource(R.string.chat_story_place_since, place, since) else place, color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // ─── header: segments, person, time, pause ───
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            val count = item.stories.size.coerceAtLeast(1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(count) { i ->
                    val fill = when {
                        i < storyIdx -> 1f
                        i == storyIdx -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(fill).height(3.dp).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcIconButton(GcIcons.Back, stringResource(R.string.chat_back), onClick = onClose, tint = Color.White, size = 40.dp)
                ChatPersonAvatar(if (own) ui.profile?.user else item.user, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (own) stringResource(R.string.chat_status_mine) else ChatText.name(ctx, item.user), color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                    val now = System.currentTimeMillis()
                    val ago = ChatStoryTime.minutesAgo(story?.createdAt, now)
                    val left = ChatStoryTime.minutesLeft(story?.expiresAt, now)
                    if (ago != null) {
                        val agoText = when {
                            ago < 1 -> stringResource(R.string.chat_story_just_now)
                            ago < 60 -> stringResource(R.string.chat_story_min_ago, ago.toInt())
                            else -> stringResource(R.string.chat_story_h_ago, (ago / 60).toInt())
                        }
                        val leftText = left?.let { if (it >= 60) stringResource(R.string.chat_story_h_left, (it / 60).toInt()) else stringResource(R.string.chat_story_min_left, it.toInt()) }
                        Text(if (leftText != null) stringResource(R.string.chat_story_ago, agoText, leftText) else agoText, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                GcIconButton(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    stringResource(if (paused) R.string.chat_story_play else R.string.chat_story_pause),
                    onClick = { paused = !paused },
                    tint = Color.White,
                    size = 40.dp,
                )
                if (own && story != null) {
                    GcIconButton(Icons.Outlined.Delete, stringResource(R.string.chat_story_delete), onClick = { confirmDelete = true }, tint = Color.White, size = 40.dp)
                }
            }
        }
        // ─── footer: reactions and answer, or the own viewers ───
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (own) {
                Row(
                    Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.12f))
                        .clickable(role = Role.Button) { showViewers = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, tint = Color.White)
                    Text(stringResource(R.string.chat_story_viewers, viewers?.size ?: story?.viewerCount ?: 0), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            } else if (story != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    REACTIONS.forEach { e ->
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(role = Role.Button) { viewModel.react(story, e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
                val name = ChatText.name(ctx, item.user)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(Color.White.copy(alpha = 0.12f)).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hint = stringResource(R.string.chat_story_reply_hint, name)
                    BasicTextField(
                        value = reply,
                        onValueChange = { reply = it.take(1000) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.weight(1f).semantics { contentDescription = hint }.onFocusChanged { typing = it.isFocused },
                        decorationBox = { inner ->
                            Box {
                                if (reply.isEmpty()) Text(hint, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
                                inner()
                            }
                        },
                    )
                    GcIconButton(
                        Icons.AutoMirrored.Filled.Send,
                        stringResource(R.string.chat_send),
                        onClick = { viewModel.reply(item.user, story, reply.trim()) { reply = "" } },
                        enabled = reply.isNotBlank() && !ui.busy,
                        tint = Color.White,
                        size = 44.dp,
                    )
                }
                Text(stringResource(R.string.chat_story_reply_info, name), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
    if (showViewers) {
        ModalBottomSheet(onDismissRequest = { showViewers = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chat_story_viewers_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                val list = viewers.orEmpty()
                if (list.isEmpty()) Text(stringResource(R.string.chat_story_viewers_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { v ->
                    Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChatPersonAvatar(v.user, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(ChatText.name(ctx, v.user), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(ChatText.listTime(ctx, v.seenAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        v.reaction?.let { Text(it, fontSize = 22.sp) }
                    }
                }
            }
        }
    }
    if (confirmDelete && story != null) {
        ConfirmDialog(stringResource(R.string.chat_story_delete), null, stringResource(R.string.chat_story_delete), {
            confirmDelete = false
            viewModel.deleteStory(story) { if (item.stories.size <= 1) onClose() }
        }) { confirmDelete = false }
    }
}
