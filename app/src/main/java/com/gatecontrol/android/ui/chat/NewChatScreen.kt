package com.gatecontrol.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewChatUiState(
    val people: List<ChatPerson> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false,
)

/** "Neuer Chat" / "Neue Gruppe": the people (`GET people`), direct chats and groups. */
@HiltViewModel
class NewChatViewModel @Inject constructor(private val repository: ChatRepository) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    private val opened = Channel<Long>(Channel.BUFFERED)
    /** The conversation to open (direct chat found or created, group created). */
    val open = opened.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = repository.people()) {
                is ChatResult.Ok -> _state.value = NewChatUiState(people = r.value, loading = false)
                is ChatResult.Failed -> _state.value = _state.value.copy(loading = false, error = r.code)
                else -> _state.value = _state.value.copy(loading = false, error = "offline")
            }
        }
    }

    fun openDirect(peerId: Long) = act { repository.openDirect(peerId) }

    private var pendingIds: List<Long> = emptyList()
    private val skippedCh = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.BUFFERED)
    /** Some picked people were left out (their `groupAdd` privacy). */
    val skipped = skippedCh.receiveAsFlow()

    fun createGroup(title: String, ids: List<Long>) {
        pendingIds = ids
        act { repository.createGroup(title.trim(), ids) }
    }

    private fun act(block: suspend () -> ChatResult<com.gatecontrol.android.chat.ChatConversation>) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            when (val r = block()) {
                is ChatResult.Ok -> {
                    // Messenger 1.4: people whose privacy keeps them out of groups are left out.
                    if (r.value.isGroup && pendingIds.any { id -> r.value.members.none { it.userId == id } }) skippedCh.trySend(Unit)
                    opened.trySend(r.value.id)
                }
                is ChatResult.Failed -> _state.value = _state.value.copy(error = r.code)
                else -> _state.value = _state.value.copy(error = "offline")
            }
            _state.value = _state.value.copy(busy = false)
        }
    }
}

/**
 * "Neuer Chat": search, "Neue Gruppe" and every GateControl account (the
 * plugin leaves out kiosk devices and the caller). With [group] the
 * people are picked for a new group and it gets a name.
 */
@Composable
fun NewChatScreen(
    group: Boolean,
    onBack: () -> Unit,
    onNewGroup: () -> Unit,
    onOpened: (Long) -> Unit,
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }

    LaunchedEffect(Unit) { viewModel.open.collect { onOpened(it) } }
    val ctxSkipped = androidx.compose.ui.platform.LocalContext.current
    val skippedText = androidx.compose.ui.res.stringResource(R.string.chat_err_skipped)
    LaunchedEffect(Unit) { viewModel.skipped.collect { android.widget.Toast.makeText(ctxSkipped, skippedText, android.widget.Toast.LENGTH_LONG).show() } }

    val visible = state.people.filter { p -> query.isBlank() || (p.name ?: "").contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(
            title = stringResource(if (group) R.string.chat_new_group else R.string.chat_new),
            onBack = onBack,
            backDescription = stringResource(R.string.chat_back),
        )
        if (group) {
            GcTextField(
                value = title,
                onValueChange = { title = it.take(80) },
                label = stringResource(R.string.chat_group_name),
                placeholder = stringResource(R.string.chat_group_name_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        GcTextField(
            value = query,
            onValueChange = { query = it.take(60) },
            label = stringResource(R.string.chat_people_search),
            placeholder = stringResource(R.string.chat_people_search_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        state.error?.let {
            Text(ChatText.actionError(ctx, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!group) {
                item(key = "group") {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onNewGroup).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.GroupAdd, contentDescription = null, tint = extra.accentText)
                        }
                        Column {
                            Text(stringResource(R.string.chat_new_group), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.chat_new_group_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                        }
                    }
                }
            }
            item(key = "label") {
                Text(
                    stringResource(R.string.chat_people_all, state.people.size).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.faint,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(visible, key = { it.userId }) { p ->
                val checked = p.userId in picked
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .clickable(role = if (group) Role.Checkbox else Role.Button) {
                            if (group) picked = if (checked) picked - p.userId else picked + p.userId
                            else viewModel.openDirect(p.userId)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ChatPersonAvatar(p, 44.dp)
                    Text(ChatText.name(ctx, p), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (group) Checkbox(checked = checked, onCheckedChange = null)
                }
            }
            if (!state.loading && state.people.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(R.string.chat_people_empty), style = MaterialTheme.typography.bodyMedium, color = extra.muted, modifier = Modifier.padding(24.dp))
                }
            }
            item(key = "foot") {
                Text(stringResource(R.string.chat_people_foot), style = MaterialTheme.typography.bodySmall, color = extra.faint, modifier = Modifier.padding(24.dp))
            }
        }
        if (group) {
            GcPrimaryButton(
                text = stringResource(R.string.chat_group_create, picked.size),
                onClick = { viewModel.createGroup(title, picked) },
                enabled = title.isNotBlank() && picked.isNotEmpty(),
                loading = state.busy,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
