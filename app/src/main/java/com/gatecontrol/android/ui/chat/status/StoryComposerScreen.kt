package com.gatecontrol.android.ui.chat.status

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatStatus
import com.gatecontrol.android.chat.ChatStory
import com.gatecontrol.android.ui.chat.ChatText
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Backgrounds of a text status. */
internal val STORY_BACKGROUNDS = listOf(ChatStory.DEFAULT_BG, "#7C3AED", "#1D4ED8", "#B45309", "#BE185D", "#15803D", "#0F172A")

internal fun storyColor(hex: String?): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex ?: ChatStory.DEFAULT_BG)) }.getOrDefault(Color(0xFF0F766E))

internal fun storyFont(font: String?): FontFamily = when (font) {
    ChatStory.FONT_SERIF -> FontFamily.Serif
    ChatStory.FONT_MONO -> MonoFontFamily
    else -> FontFamily.SansSerif
}

/**
 * "Status erstellen" (mockup): a text status on a colour with a font, or
 * a photo / video with a caption; who sees it; 24 h. Replies come as chat
 * messages.
 */
@Composable
fun StoryComposerScreen(
    initialKind: String,
    onClose: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    // The composer is always dark (like the viewer): fields, tabs and menus take the dark colours too.
    GateControlTheme(darkTheme = true) { StoryComposer(initialKind, onClose, viewModel) }
}

@Composable
private fun StoryComposer(initialKind: String, onClose: () -> Unit, viewModel: StatusViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val extra = GateControlTheme.extraColors
    var kind by rememberSaveable { mutableStateOf(initialKind.takeIf { it in listOf(ChatStory.KIND_TEXT, ChatStory.KIND_IMAGE, ChatStory.KIND_VIDEO) } ?: ChatStory.KIND_TEXT) }
    var text by rememberSaveable { mutableStateOf("") }
    var bg by rememberSaveable { mutableStateOf(ChatStory.DEFAULT_BG) }
    var font by rememberSaveable { mutableStateOf(ChatStory.FONT_SANS) }
    var media by rememberSaveable { mutableStateOf<String?>(null) }
    var visibility by rememberSaveable { mutableStateOf(viewModel.defaultVisibility()) }
    val chosen = remember { mutableStateListOf<Long>() }
    var pickPeople by remember { mutableStateOf(false) }
    var durationH by rememberSaveable { mutableIntStateOf(ChatStory.DEFAULT_DURATION_H) }
    var pickDuration by remember { mutableStateOf(false) }
    // A failed post stays visible here (not only as a toast).
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.notice.collect { n ->
            val msg = if (n is StatusNotice.Done) res.getString(n.res) else ChatText.actionError(ctx, (n as StatusNotice.Error).code)
            if (n is StatusNotice.Error) error = msg
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) media = uri.toString() }
    val pick: () -> Unit = {
        picker.launch(PickVisualMediaRequest(if (kind == ChatStory.KIND_VIDEO) ActivityResultContracts.PickVisualMedia.VideoOnly else ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val mediaUri = media?.let(Uri::parse)
    val preview by produceState<ImageBitmap?>(null, media, kind) {
        value = mediaUri?.let { uri -> withContext(Dispatchers.IO) { previewOf(ctx, uri, kind == ChatStory.KIND_VIDEO) } }
    }
    val canSend = !ui.busy && when (kind) {
        ChatStory.KIND_TEXT -> text.isNotBlank()
        else -> mediaUri != null
    } && (visibility != ChatStatus.VIS_CUSTOM || chosen.isNotEmpty())
    val send: () -> Unit = {
        error = null
        if (kind == ChatStory.KIND_TEXT) viewModel.postText(text.trim(), bg, font, visibility, chosen.toList(), durationH, onClose)
        else mediaUri?.let { viewModel.postMedia(it, kind == ChatStory.KIND_VIDEO, text.trim().takeIf { t -> t.isNotEmpty() }, visibility, chosen.toList(), durationH, onClose) }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF05090D)).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GcIconButton(GcIcons.Close, stringResource(R.string.chat_close), onClick = onClose, tint = Color.White)
            Text(stringResource(R.string.chat_story_compose), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
        }
        GcSegmented(
            options = listOf(
                ChatStory.KIND_TEXT to stringResource(R.string.chat_story_text),
                ChatStory.KIND_IMAGE to stringResource(R.string.chat_story_photo),
                ChatStory.KIND_VIDEO to stringResource(R.string.chat_story_video),
            ),
            selected = kind,
            onSelect = { k -> if (k != kind) { kind = k; media = null } },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))
                .background(if (kind == ChatStory.KIND_TEXT) storyColor(bg) else Color(0xFF111A22)),
            contentAlignment = Alignment.Center,
        ) {
            if (kind == ChatStory.KIND_TEXT) {
                val hint = stringResource(R.string.chat_story_text_hint)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(ChatStory.TEXT_MAX) },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.White, textAlign = TextAlign.Center, fontFamily = storyFont(font), fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth().padding(28.dp).semantics { contentDescription = hint },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center, fontFamily = storyFont(font))
                            inner()
                        }
                    },
                )
                Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoundTool(stringResource(R.string.chat_story_font), { font = ChatStory.FONTS[(ChatStory.FONTS.indexOf(font) + 1) % ChatStory.FONTS.size] }) {
                        Text("Aa", color = Color.White, fontWeight = FontWeight.ExtraBold, fontFamily = storyFont(font), fontSize = 16.sp)
                    }
                    RoundTool(stringResource(R.string.chat_story_bg), { bg = STORY_BACKGROUNDS[(STORY_BACKGROUNDS.indexOf(bg) + 1) % STORY_BACKGROUNDS.size] }) {
                        Icon(Icons.Outlined.Palette, contentDescription = null, tint = Color.White)
                    }
                }
                Text(
                    stringResource(R.string.chat_story_hint) + " · ${text.length}/${ChatStory.TEXT_MAX}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            } else {
                val img = preview
                if (img != null) {
                    Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().clickable(onClick = pick))
                } else {
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = pick).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(if (kind == ChatStory.KIND_VIDEO) Icons.Outlined.Videocam else Icons.Outlined.Image, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        Text(stringResource(if (kind == ChatStory.KIND_VIDEO) R.string.chat_story_pick_video else R.string.chat_story_pick_photo), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (kind != ChatStory.KIND_TEXT) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ChatStory.TEXT_MAX) },
                label = { Text(stringResource(R.string.chat_story_caption)) },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DarkChip(
                Icons.Outlined.Group,
                when (visibility) {
                    ChatStatus.VIS_FAMILY -> stringResource(R.string.chat_status_vis_family)
                    ChatStatus.VIS_CUSTOM -> stringResource(R.string.chat_status_vis_custom_n, chosen.size)
                    else -> stringResource(R.string.chat_status_vis_all)
                },
            ) {
                visibility = when (visibility) {
                    ChatStatus.VIS_ALL -> ChatStatus.VIS_FAMILY
                    ChatStatus.VIS_FAMILY -> {
                        viewModel.loadPeople()
                        pickPeople = true
                        ChatStatus.VIS_CUSTOM
                    }
                    else -> ChatStatus.VIS_ALL
                }
            }
            if (ui.durationOn) {
                Box {
                    DarkChip(Icons.Outlined.Schedule, durationLabel(durationH)) { pickDuration = true }
                    DropdownMenu(expanded = pickDuration, onDismissRequest = { pickDuration = false }) {
                        ChatStory.DURATIONS_H.forEach { h ->
                            DropdownMenuItem(
                                text = { Text(durationLabel(h), fontWeight = if (h == durationH) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { durationH = h; pickDuration = false },
                            )
                        }
                    }
                }
            } else {
                // Older server: always 24 h — shown, not offered.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.chat_story_24h), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(if (canSend) MaterialTheme.colorScheme.primary else extra.panel2)
                    .clickable(enabled = canSend, role = Role.Button, onClick = send),
                contentAlignment = Alignment.Center,
            ) {
                if (ui.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_story_send), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        error?.let { e ->
            Text(
                e,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }
        Text(
            stringResource(
                R.string.chat_story_footer,
                when (visibility) {
                    ChatStatus.VIS_FAMILY -> stringResource(R.string.chat_status_vis_family)
                    ChatStatus.VIS_CUSTOM -> stringResource(R.string.chat_status_vis_custom_n, chosen.size)
                    else -> stringResource(R.string.chat_status_vis_all)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
    if (pickPeople) {
        PeoplePickerDialog(people, chosen.toList(), ui.profile?.user?.userId, onDone = { ids ->
            chosen.clear()
            chosen.addAll(ids)
            if (ids.isEmpty()) visibility = ChatStatus.VIS_ALL
            pickPeople = false
        }, onDismiss = {
            if (chosen.isEmpty()) visibility = ChatStatus.VIS_ALL
            pickPeople = false
        })
    }
}

@Composable
private fun durationLabel(hours: Int): String =
    if (hours == ChatStory.DEFAULT_DURATION_H) stringResource(R.string.chat_story_24h)
    else pluralStringResource(R.plurals.chat_story_hours, hours, hours)

@Composable
private fun RoundTool(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.3f)).clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DarkChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).width(16.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** A preview of a picked picture or video (a frame), scaled down. */
private fun previewOf(ctx: android.content.Context, uri: Uri, video: Boolean): ImageBitmap? = runCatching {
    val bmp: Bitmap? = if (video) {
        MediaMetadataRetriever().run {
            try {
                setDataSource(ctx, uri)
                getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1080, 1080)
            } finally {
                release()
            }
        }
    } else {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
    bmp?.asImageBitmap()
}.getOrNull()
