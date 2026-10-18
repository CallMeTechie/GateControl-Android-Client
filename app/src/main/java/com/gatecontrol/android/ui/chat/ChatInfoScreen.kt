package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
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
class ChatInfoViewModel @Inject constructor(private val repository: ChatRepository) : ViewModel() {
    private val id = MutableStateFlow<Long?>(null)
    val conversation: StateFlow<ChatConversation?> = id.filterNotNull().flatMapLatest { repository.conversation(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile: StateFlow<ChatProfile?> = repository.profile

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
                is ChatResult.Ok -> if (r.value.left) leftCh.trySend(Unit)
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

@Composable
fun ChatInfoScreen(
    conversationId: Long,
    onBack: () -> Unit,
    onLeft: () -> Unit,
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

    LaunchedEffect(Unit) { viewModel.error.collect { error = ChatText.actionError(ctx, it) } }
    LaunchedEffect(Unit) { viewModel.left.collect { onLeft() } }

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
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ConversationAvatar(conv, 88.dp)
                Text(ChatText.title(ctx, conv), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
                val sub = buildList {
                    add(resources.getString(if (conv.isGroup) R.string.chat_kind_group else R.string.chat_kind_direct))
                    if (conv.isGroup) add(resources.getQuantityString(R.plurals.chat_members, conv.members.size, conv.members.size))
                    if (conv.managedBy != null) add(resources.getString(R.string.chat_managed_short))
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = extra.muted, textAlign = TextAlign.Center)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }

            GcSectionLabel(stringResource(R.string.chat_settings), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
            if (conv.isGroup && conv.can.editInfo && conv.managedBy == null) {
                GcListRow(title = stringResource(R.string.chat_rename), description = conv.title, onClick = { renaming = true }, trailing = { GcChevron() })
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
                    Text(ChatText.name(ctx, conv.peer), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
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
