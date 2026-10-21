package com.gatecontrol.android.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import com.gatecontrol.android.chat.ChatMediaStore
import com.gatecontrol.android.profile.AvatarEncoder
import com.gatecontrol.android.profile.CropRect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatConversation
import com.gatecontrol.android.chat.ChatPatches
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatProfile
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.google.gson.JsonObject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Chat / group info: members, name, mute, disappearing messages, leave. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatInfoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ChatRepository,
    private val media: ChatMediaStore,
    private val extras: com.gatecontrol.android.chat.ChatExtrasRepository,
) : ViewModel() {
    private val id = MutableStateFlow<Long?>(null)

    // ─── Messenger 1.4: archive, block, export ───
    private val exportCh = Channel<Pair<java.io.File, Boolean>>(Channel.BUFFERED)
    /** An exported chat to share (file, truncated). */
    val exported = exportCh.receiveAsFlow()
    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting

    fun archive(archived: Boolean) {
        val cid = id.value ?: return
        viewModelScope.launch { extras.archive(cid, archived).let { if (it !is ChatResult.Ok) errors.trySend(codeOf(it)) } }
    }

    fun block(p: ChatPerson, block: Boolean) {
        val cid = id.value ?: return
        viewModelScope.launch {
            val r = if (block) extras.block(p) else extras.unblock(p.userId)
            if (r !is ChatResult.Ok) errors.trySend(codeOf(r)) else repository.refreshConversation(cid)
        }
    }

    fun export(title: String) {
        val cid = id.value ?: return
        if (_exporting.value) return
        _exporting.value = true
        viewModelScope.launch {
            try {
                when (val r = extras.export(cid)) {
                    is ChatResult.Ok -> extras.exportFile(java.io.File(context.cacheDir, "chat-export"), com.gatecontrol.android.chat.ChatToolsLogic.exportName(title, java.time.LocalDate.now()), r.value.text)
                        ?.let { exportCh.trySend(it to r.value.truncated) }
                    else -> errors.trySend(codeOf(r))
                }
            } finally {
                _exporting.value = false
            }
        }
    }

    private fun codeOf(r: ChatResult<*>): String? = (r as? ChatResult.Failed)?.code ?: "offline"
    val conversation: StateFlow<ChatConversation?> = id.filterNotNull().flatMapLatest { repository.conversation(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<ChatProfile?> = repository.profile

    /** Removed from the group by others (or the group deleted) while this screen is open. */
    val removed: StateFlow<Boolean> = combine(id, repository.dropped) { i, d -> i != null && i in d && !repository.leftHere(i) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _people = MutableStateFlow<List<ChatPerson>>(emptyList())
    val people: StateFlow<List<ChatPerson>> = _people

    private val errors = Channel<String?>(Channel.BUFFERED)
    val error = errors.receiveAsFlow()
    private val leftCh = Channel<Unit>(Channel.BUFFERED)
    val left = leftCh.receiveAsFlow()

    fun bind(conversationId: Long) {
        if (id.value == conversationId) return
        id.value = conversationId
        viewModelScope.launch { repository.refreshConversation(conversationId) }
    }

    fun loadPeople() {
        viewModelScope.launch { (repository.people() as? ChatResult.Ok)?.let { _people.value = it.value } }
    }

    private fun patch(body: JsonObject) {
        val cid = id.value ?: return
        viewModelScope.launch {
            when (val r = repository.patch(cid, body)) {
                is ChatResult.Ok -> {
                    if (r.value.left) leftCh.trySend(Unit)
                    if (r.value.skipped.isNotEmpty()) errors.trySend("skipped")
                }
                is ChatResult.Failed -> errors.trySend(r.code)
                else -> errors.trySend("offline")
            }
        }
    }

    fun rename(title: String) = patch(ChatPatches.title(title.trim()))

    /** [hours] null = until switched on again (notify none); 0 = not muted. */
    fun mute(hours: Int?) {
        when (hours) {
            null -> patch(ChatPatches.notify(false))
            0 -> patch(ChatPatches.mute(null).apply { addProperty("notify", "all") })
            else -> patch(ChatPatches.mute(Instant.now().plusSeconds(hours * 3600L).toString()).apply { addProperty("notify", "all") })
        }
    }

    fun disappearing(seconds: Int) = patch(ChatPatches.disappearing(seconds))

    fun add(ids: List<Long>) = patch(ChatPatches.add(ids))

    fun remove(userId: Long) = patch(ChatPatches.remove(listOf(userId)))

    fun setAdmin(userId: Long, admin: Boolean) = patch(ChatPatches.role(userId, admin))

    // ─── group picture ──────────────────────────────────────────────

    private val _avatarSource = MutableStateFlow<Bitmap?>(null)
    /** The picked photo while its crop is open. */
    val avatarSource: StateFlow<Bitmap?> = _avatarSource
    private val _avatarSaving = MutableStateFlow(false)
    val avatarSaving: StateFlow<Boolean> = _avatarSaving

    /** A photo from the photo picker: the crop opens. */
    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { AvatarEncoder.decode(context, uri) }
            if (bmp == null) errors.trySend("unreadable") else _avatarSource.value = bmp
        }
    }

    /** A photo taken with the camera app ([path] in `cache/camera`): the crop opens, the file goes. */
    fun cameraAvatar(path: String) {
        val f = media.cameraFile(path) ?: run {
            errors.trySend("unreadable")
            return
        }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { AvatarEncoder.decode(context, Uri.fromFile(f)).also { f.delete() } }
            if (bmp == null) errors.trySend("unreadable") else _avatarSource.value = bmp
        }
    }

    fun newCameraTarget(): Pair<File, Uri>? = runCatching { media.newCameraTarget() }.getOrNull()

    fun cancelAvatar() {
        if (!_avatarSaving.value) _avatarSource.value = null
    }

    /** "Speichern" of the crop: square, 256 px, uploaded and set as the group picture. */
    fun saveAvatar(rect: CropRect) {
        val cid = id.value ?: return
        val src = _avatarSource.value ?: return
        if (_avatarSaving.value) return
        _avatarSaving.value = true
        viewModelScope.launch {
            try {
                val encoded = withContext(Dispatchers.Default) { runCatching { AvatarEncoder.encode(src, rect) }.getOrNull() }
                if (encoded == null) {
                    errors.trySend("unreadable")
                    return@launch
                }
                val ext = if (encoded.mime == "image/webp") "webp" else "jpg"
                val file = withContext(Dispatchers.IO) {
                    File(File(context.cacheDir, AVATAR_DIR).apply { mkdirs() }, "group-$cid.$ext").apply { writeBytes(encoded.bytes) }
                }
                val r = repository.setGroupAvatar(cid, file, encoded.mime)
                withContext(Dispatchers.IO) { file.delete() }
                when (r) {
                    is ChatResult.Ok -> _avatarSource.value = null
                    is ChatResult.Failed -> errors.trySend(r.code)
                    else -> errors.trySend("offline")
                }
            } finally {
                _avatarSaving.value = false
            }
        }
    }

    fun removeAvatar() {
        val cid = id.value ?: return
        viewModelScope.launch {
            when (val r = repository.removeGroupAvatar(cid)) {
                is ChatResult.Ok -> Unit
                is ChatResult.Failed -> errors.trySend(r.code)
                else -> errors.trySend("offline")
            }
        }
    }

    fun leave() {
        val cid = id.value ?: return
        viewModelScope.launch {
            when (val r = repository.leave(cid)) {
                is ChatResult.Ok -> leftCh.trySend(Unit)
                is ChatResult.Failed -> errors.trySend(r.code)
                else -> errors.trySend("offline")
            }
        }
    }
}

private const val AVATAR_DIR = "chat-avatar"

@Composable
fun ChatInfoScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    onOpenMedia: () -> Unit,
    viewModel: ChatInfoViewModel = hiltViewModel(key = "chat-info-$conversationId"),
) {
    LaunchedEffect(conversationId) { viewModel.bind(conversationId) }
    val c by viewModel.conversation.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val extra = GateControlTheme.extraColors
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var muting by remember { mutableStateOf(false) }
    var disappearing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<ChatPerson?>(null) }
    var pictureMenu by remember { mutableStateOf(false) }
    val avatarSource by viewModel.avatarSource.collectAsStateWithLifecycle()
    val avatarSaving by viewModel.avatarSaving.collectAsStateWithLifecycle()

    val removed by viewModel.removed.collectAsStateWithLifecycle()
    LaunchedEffect(removed) {
        if (removed) {
            Toast.makeText(ctx, R.string.chat_gone, Toast.LENGTH_LONG).show()
            onLeft()
        }
    }
    LaunchedEffect(Unit) { viewModel.error.collect { error = ChatText.actionError(ctx, it) } }
    var confirmBlock by remember { mutableStateOf<ChatPerson?>(null) }
    val exporting by viewModel.exporting.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.exported.collect { (file, truncated) ->
            if (truncated) Toast.makeText(ctx, R.string.chat_export_truncated, Toast.LENGTH_LONG).show()
            shareTextFile(ctx, file)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.left.collect {
            Toast.makeText(ctx, R.string.chat_left, Toast.LENGTH_SHORT).show()
            onLeft()
        }
    }

    // Group picture: photo picker or camera, then the square crop.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraPath
        cameraPath = null
        if (ok && path != null) viewModel.cameraAvatar(path) else path?.let { File(it).delete() }
    }
    val launchCamera: () -> Unit = {
        val target = viewModel.newCameraTarget()
        if (target == null) {
            Toast.makeText(ctx, R.string.chat_camera_failed, Toast.LENGTH_LONG).show()
        } else {
            cameraPath = target.first.path
            try {
                takePicture.launch(target.second)
            } catch (e: ActivityNotFoundException) {
                cameraPath = null
                target.first.delete()
                Toast.makeText(ctx, R.string.chat_camera_no_app, Toast.LENGTH_LONG).show()
            }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(ctx, R.string.chat_camera_no_permission, Toast.LENGTH_LONG).show()
    }

    val conv = c
    val myId = profile?.user?.userId
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(if (conv?.isGroup == true) R.string.chat_group_info else R.string.chat_info),
            onBack = onBack,
            backDescription = stringResource(R.string.chat_back),
        )
        if (conv == null) {
            Text(stringResource(R.string.chat_loading), color = extra.muted, modifier = Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val canPicture = conv.isGroup && conv.can.editInfo && conv.managedBy == null
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canPicture) {
                    Box(
                        Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_group_picture)) { pictureMenu = true },
                    ) {
                        ConversationAvatar(conv, 88.dp)
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    ConversationAvatar(conv, 88.dp)
                }
                Text(ChatText.title(ctx, conv), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                val sub = buildList {
                    add(resources.getString(if (conv.isGroup) R.string.chat_kind_group else R.string.chat_kind_direct))
                    if (conv.isGroup) add(resources.getQuantityString(R.plurals.chat_members, conv.members.size, conv.members.size))
                    if (conv.isNotice) add(resources.getString(R.string.chat_notice_tag))
                    else if (conv.managedBy != null) add(resources.getString(R.string.chat_managed_short))
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = extra.muted, textAlign = TextAlign.Center)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }

            GcListRow(
                title = stringResource(R.string.chat_media_title),
                description = stringResource(R.string.chat_media_row_desc),
                onClick = onOpenMedia,
                leading = { Icon(Icons.Outlined.PermMedia, contentDescription = null, tint = extra.muted) },
                trailing = { GcChevron() },
            )

            GcSectionLabel(stringResource(R.string.chat_settings), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            if (conv.isGroup && conv.can.editInfo && conv.managedBy == null) {
                GcListRow(title = stringResource(R.string.chat_rename), description = conv.title, onClick = { renaming = true }, trailing = { GcChevron() })
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(title = stringResource(R.string.chat_group_picture), onClick = { pictureMenu = true }, trailing = { GcChevron() })
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
            }
            GcListRow(
                title = stringResource(R.string.chat_mute),
                description = when {
                    conv.me.notify == "none" -> stringResource(R.string.chat_mute_always)
                    conv.muted && conv.me.mutedUntil != null -> stringResource(R.string.chat_mute_until, ChatText.listTime(ctx, conv.me.mutedUntil) + " " + ChatText.clock(ctx, conv.me.mutedUntil))
                    else -> stringResource(R.string.chat_mute_off)
                },
                onClick = { muting = true },
                trailing = { GcChevron() },
            )
            if (profile?.features?.disappearing == true || conv.disappearingS > 0) {
                GcRowDivider(Modifier.padding(horizontal = 16.dp))
                GcListRow(
                    title = stringResource(R.string.chat_disappearing),
                    description = ChatText.disappearing(ctx, conv.disappearingS),
                    onClick = if (conv.can.setDisappearing && profile?.features?.disappearing == true) ({ disappearing = true }) else null,
                    leading = { Icon(Icons.Outlined.Timer, contentDescription = null, tint = extra.muted) },
                    trailing = if (conv.can.setDisappearing) ({ GcChevron() }) else null,
                )
            }

            if (conv.isGroup) {
                GcSectionLabel(
                    stringResource(R.string.chat_members_label, conv.members.size),
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
                if (conv.can.manageMembers && conv.managedBy == null) {
                    GcListRow(
                        title = stringResource(R.string.chat_add_members),
                        onClick = {
                            viewModel.loadPeople()
                            adding = true
                        },
                        leading = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
                conv.members.sortedWith(compareBy({ it.userId != myId }, { !it.isAdmin }, { it.name ?: "" })).forEach { m ->
                    val me = m.userId == myId
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 60.dp)
                            .then(if (!me && conv.can.manageMembers && conv.managedBy == null) Modifier.clickable(role = Role.Button) { memberMenu = m } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChatPersonAvatar(m, 40.dp)
                        Text(
                            if (me) stringResource(R.string.chat_you_name, ChatText.name(ctx, m)) else ChatText.name(ctx, m),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (m.isAdmin) Text(stringResource(R.string.chat_admin), style = MaterialTheme.typography.labelMedium, color = extra.accentText)
                    }
                }
            } else if (conv.peer != null) {
                GcSectionLabel(stringResource(R.string.chat_person), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatPersonAvatar(conv.peer, 40.dp)
                    Column {
                        Text(ChatText.name(ctx, conv.peer), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        ChatText.presence(ctx, conv.peer)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = extra.muted) }
                    }
                }
                if (conv.peer.blocked) Text(stringResource(R.string.chat_blocked_hint, ChatText.name(ctx, conv.peer)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Messenger 1.4: archive, export, block.
            if (profile?.features?.let { it.tokens.contains(com.gatecontrol.android.chat.ChatFeatures14.MSG_TOOLS) } == true) {
                GcRowDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                GcListRow(
                    title = stringResource(if (conv.archived) R.string.chat_unarchive else R.string.chat_archive),
                    onClick = { viewModel.archive(!conv.archived) },
                    leading = { Icon(if (conv.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null, tint = extra.muted) },
                )
                GcListRow(
                    title = stringResource(R.string.chat_export),
                    onClick = { viewModel.export(ChatText.title(ctx, conv)) },
                    leading = { Icon(Icons.Outlined.IosShare, contentDescription = null, tint = extra.muted) },
                    trailing = if (exporting) ({ androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }) else null,
                )
                val peer = conv.peer
                if (!conv.isGroup && peer != null && !peer.gone) {
                    GcListRow(
                        title = stringResource(if (peer.blocked) R.string.chat_unblock else R.string.chat_block),
                        titleColor = if (peer.blocked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        onClick = { if (peer.blocked) viewModel.block(peer, false) else confirmBlock = peer },
                        leading = { Icon(Icons.Outlined.Block, contentDescription = null, tint = if (peer.blocked) extra.muted else MaterialTheme.colorScheme.error) },
                    )
                }
            }

            if (conv.can.leave && conv.isGroup) {
                GcRowDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                GcListRow(
                    title = stringResource(R.string.chat_leave),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { leaving = true },
                    leading = { Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                )
            }
            Text(stringResource(R.string.chat_footer), style = MaterialTheme.typography.bodySmall, color = extra.faint, modifier = Modifier.padding(24.dp))
        }
    }

    confirmBlock?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmBlock = null },
            title = { Text(stringResource(R.string.chat_block_title, ChatText.name(ctx, p))) },
            text = { Text(stringResource(R.string.chat_block_text, ChatText.name(ctx, p))) },
            confirmButton = { TextButton(onClick = { confirmBlock = null; viewModel.block(p, true) }) { Text(stringResource(R.string.chat_block), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmBlock = null }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }

    if (renaming && conv != null) {
        var t by remember { mutableStateOf(conv.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.chat_rename)) },
            text = { OutlinedTextField(value = t, onValueChange = { t = it.take(profile?.limits?.titleMax ?: 80) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = t.isNotBlank(), onClick = {
                    renaming = false
                    viewModel.rename(t)
                }) { Text(stringResource(R.string.chat_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }

    if (muting) {
        val options = listOf(0 to R.string.chat_mute_off, 1 to R.string.chat_mute_1h, 8 to R.string.chat_mute_8h, 168 to R.string.chat_mute_week, -1 to R.string.chat_mute_always)
        ChoiceDialog(
            title = stringResource(R.string.chat_mute),
            options = options.map { stringResource(it.second) },
            onPick = { i ->
                muting = false
                val h = options[i].first
                viewModel.mute(if (h < 0) null else h)
            },
            onDismiss = { muting = false },
        )
    }

    if (disappearing && conv != null) {
        val values = profile?.limits?.disappearingS ?: listOf(0, 86_400, 604_800, 2_592_000)
        ChoiceDialog(
            title = stringResource(R.string.chat_disappearing),
            options = values.map { ChatText.disappearing(ctx, it) },
            selected = values.indexOf(conv.disappearingS),
            onPick = { i ->
                disappearing = false
                viewModel.disappearing(values[i])
            },
            onDismiss = { disappearing = false },
        )
    }

    if (adding && conv != null) {
        val candidates = people.filter { p -> conv.members.none { it.userId == p.userId } }
        var picked by remember { mutableStateOf(setOf<Long>()) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.chat_add_members)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (candidates.isEmpty()) item { Text(stringResource(R.string.chat_people_empty), color = extra.muted) }
                    items(candidates, key = { it.userId }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { picked = if (p.userId in picked) picked - p.userId else picked + p.userId }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ChatPersonAvatar(p, 32.dp)
                            Text(ChatText.name(ctx, p), modifier = Modifier.weight(1f))
                            Checkbox(checked = p.userId in picked, onCheckedChange = null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = picked.isNotEmpty(), onClick = {
                    adding = false
                    viewModel.add(picked.toList())
                }) { Text(stringResource(R.string.chat_add)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }

    memberMenu?.let { m ->
        AlertDialog(
            onDismissRequest = { memberMenu = null },
            title = { Text(ChatText.name(ctx, m)) },
            text = {
                Column {
                    TextButton(onClick = {
                        memberMenu = null
                        viewModel.setAdmin(m.userId, !m.isAdmin)
                    }) { Text(stringResource(if (m.isAdmin) R.string.chat_admin_revoke else R.string.chat_admin_make)) }
                    TextButton(onClick = {
                        memberMenu = null
                        viewModel.remove(m.userId)
                    }) { Text(stringResource(R.string.chat_remove_member), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { memberMenu = null }) { Text(stringResource(R.string.chat_close)) } },
        )
    }

    if (pictureMenu && conv != null) {
        val hasCamera = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
        AlertDialog(
            onDismissRequest = { pictureMenu = false },
            title = { Text(stringResource(R.string.chat_group_picture)) },
            text = {
                Column {
                    TextButton(onClick = {
                        pictureMenu = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.chat_attach_gallery)) }
                    if (hasCamera) {
                        TextButton(onClick = {
                            pictureMenu = false
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }) { Text(stringResource(R.string.chat_attach_camera)) }
                    }
                    if (conv.avatarMediaId != null) {
                        TextButton(onClick = {
                            pictureMenu = false
                            viewModel.removeAvatar()
                        }) { Text(stringResource(R.string.chat_group_picture_remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pictureMenu = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }

    avatarSource?.let { src ->
        ChatAvatarCropDialog(source = src, saving = avatarSaving, onDone = viewModel::saveAvatar, onCancel = viewModel::cancelAvatar)
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.chat_leave)) },
            text = { Text(stringResource(R.string.chat_leave_text)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    viewModel.leave()
                }) { Text(stringResource(R.string.chat_leave), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.chat_cancel)) } },
        )
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit, selected: Int = -1) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onPick(i) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

/** Shares a text file (a chat export) through the FileProvider. */
internal fun shareTextFile(context: Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .putExtra(android.content.Intent.EXTRA_SUBJECT, file.name).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
