package com.gatecontrol.android.ui.mail.contacts

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAddress
import com.gatecontrol.android.mail.MCardSummary
import com.gatecontrol.android.mail.MGroupDetail
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailLogic
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.MailDivider
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.calendar.NameDialog
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupState(
    val detail: MGroupDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val gone: Boolean = false,
)

/** A group (distribution list): its members, add and remove, rename, delete. */
@HiltViewModel
class MailGroupViewModel @Inject constructor(private val repository: MailRepository, saved: SavedStateHandle) : ViewModel() {
    private val pim = repository.pim
    private val api = repository.api
    val cards: StateFlow<List<MCardSummary>> = pim.cards
    val offline: StateFlow<Boolean> = pim.offline
    val id: Long = saved.get<String>(Screen.MailGroup.ARG_ID)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(GroupState())
    val state: StateFlow<GroupState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            when (val r = api.group(id)) {
                is MResult.Ok -> _state.update { it.copy(detail = r.value, loading = false, error = null) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    private fun members(body: com.google.gson.JsonObject) {
        viewModelScope.launch {
            when (val r = api.groupMembers(id, body)) {
                is MResult.Ok -> {
                    _state.update { it.copy(detail = r.value) }
                    pim.refreshBooks()
                    pim.syncCardsSoon()
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun addContact(cardId: Long) = members(MailPimJson.membersBody(addContacts = listOf(cardId)))

    fun addAddress(a: MAddress) = members(MailPimJson.membersBody(addEmails = listOf(a)))

    fun remove(memberId: Long) = members(MailPimJson.membersBody(remove = listOf(memberId)))

    fun rename(name: String) {
        viewModelScope.launch {
            when (val r = api.renameGroup(id, name)) {
                is MResult.Ok -> {
                    _state.update { s -> s.copy(detail = s.detail?.copy(group = r.value)) }
                    pim.refreshBooks()
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            when (val r = api.deleteGroup(id)) {
                is MResult.Ok -> {
                    pim.refreshBooks()
                    _state.update { it.copy(gone = true) }
                }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** A group: "Mail an alle", the members (a tap opens the contact), add and remove; rename and delete in "⋯". */
@Composable
fun MailGroupScreen(onBack: () -> Unit, onContact: (Long) -> Unit, mailTo: (String) -> Unit, viewModel: MailGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, mailText(context, it))
            viewModel.clearMessage()
        }
    }
    val d = state.detail
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = d?.group?.name ?: stringResource(R.string.con_group), onBack = onBack, backDescription = stringResource(R.string.mail_back)) {
            Box {
                GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.con_more), onClick = { menu = true }, enabled = d != null && !offline)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.cal_rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.con_group_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                }
            }
        }
        when {
            d != null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "actions") {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val emails = d.members.mapNotNull { it.email }
                        GcPrimaryButton(stringResource(R.string.con_mail_all), onClick = { mailTo(emails.joinToString(", ")) }, enabled = emails.isNotEmpty(), modifier = Modifier.weight(1f), icon = GcIcons.Mail)
                        com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.con_add_member), onClick = { adding = true }, enabled = !offline, modifier = Modifier.weight(1f), icon = GcIcons.Plus)
                    }
                }
                if (d.members.isEmpty()) item(key = "none") { Text(stringResource(R.string.con_group_empty), color = extra.muted, modifier = Modifier.padding(16.dp)) }
                items(d.members, key = { it.id }) { m ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(role = Role.Button, enabled = m.contactId != null) { m.contactId?.let(onContact) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val card = cards.firstOrNull { it.id == m.contactId }
                        ContactAvatar(m.name.ifBlank { m.email.orEmpty() }, m.email, card?.id?.takeIf { card.photo }, card?.photoEtag)
                        Column(Modifier.weight(1f)) {
                            Text(m.name.ifBlank { m.email ?: "–" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            if (m.name.isNotBlank() && m.email != null) Text(m.email, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1)
                        }
                        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.mail_remove), onClick = { viewModel.remove(m.id) }, enabled = !offline, size = 40.dp)
                    }
                    MailDivider()
                }
            }
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            else -> ErrorBox(mailText(context, state.error), onRetry = viewModel::load)
        }
    }
    if (renaming && d != null) NameDialog(stringResource(R.string.cal_rename), d.group.name, onDone = viewModel::rename, onDismiss = { renaming = false })
    if (deleting && d != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.con_group_delete_title, d.group.name)) },
            text = { Text(stringResource(R.string.con_group_delete_text)) },
            confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete() }) { Text(stringResource(R.string.mail_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
    if (adding && d != null) {
        AddMemberDialog(cards.filter { c -> d.members.none { it.contactId == c.id } }, onContact = { adding = false; viewModel.addContact(it) }, onAddress = { adding = false; viewModel.addAddress(it) }, onDismiss = { adding = false })
    }
}

/** Members to add: a contact (searched on the device) or an address typed in. */
@Composable
private fun AddMemberDialog(cards: List<MCardSummary>, onContact: (Long) -> Unit, onAddress: (MAddress) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val found = remember(q, cards) { MailPimLogic.search(cards, q).take(8) }
    val typed = MailLogic.parseAddress(q.trim())?.takeIf { MailLogic.isEmail(it.email) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.con_add_member)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GcTextField(q, { q = it.take(100) }, stringResource(R.string.con_search), placeholder = stringResource(R.string.con_search_hint))
                if (q.isNotBlank()) found.forEach { c -> ContactRow(c, null, null) { onContact(c.id) } }
                if (typed != null) TextButton(onClick = { onAddress(typed) }) { Text(stringResource(R.string.con_add_address_member, typed.email)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}
