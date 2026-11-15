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
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBookContact
import com.gatecontrol.android.chat.ChatBookPhone
import com.gatecontrol.android.chat.ChatGcContact
import com.gatecontrol.android.chat.ChatNewChatContacts
import com.gatecontrol.android.chat.ChatNewContacts
import com.gatecontrol.android.chat.ChatNumberHit
import com.gatecontrol.android.chat.ChatNumberStarted
import com.gatecontrol.android.chat.ChatWaContact
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewChatUiState(
    val people: List<ChatPerson> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val busy: Boolean = false,
    /**
     * Messenger 1.15/1.16 (`features.bridge_new_chat`): the linked services that offer "… -Chat mit Nummer" (ids,
     * the server's order).
     */
    val numberNetworks: List<String> = emptyList(),
    /** Messenger 1.18 (`features.bridge_contacts`): the three sections; null: the page of 1.16.0. */
    val contacts: ContactsUi? = null,
)

/** "Neuer Chat" / "Neue Gruppe": the people (`GET people`), direct chats and groups. */
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val bridges: com.gatecontrol.android.chat.ChatBridgesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NewChatUiState())
    val state: StateFlow<NewChatUiState> = _state.asStateFlow()

    private val opened = Channel<Long>(Channel.BUFFERED)
    /** The conversation to open (direct chat found or created, group created). */
    val open = opened.receiveAsFlow()

    private val numberPageCh = Channel<String>(Channel.BUFFERED)
    /** Messenger 1.18: the page "Nummer eingeben" of this service (a number handed over in [ChatNumberHandoff]). */
    val numberPage = numberPageCh.receiveAsFlow()

    /** The clock (tests set their own). */
    internal var now: () -> Long = System::currentTimeMillis

    /** Messenger 1.18 `bridge_contacts`: "Neuer Chat" shows the three sections (not "Neue Gruppe"). */
    val withContacts = ChatNewContacts.enabled(repository.profile.value?.features)

    init {
        load()
        if (!withContacts) loadNumberNetworks()
    }

    /** "WhatsApp-Chat mit Nummer": only when the server can (`bridge_new_chat`) and the person linked the service. */
    private fun loadNumberNetworks() {
        val features = repository.profile.value?.features
        if (features?.tokens?.contains(com.gatecontrol.android.chat.ChatBridgeChat.FEATURE_NEW_CHAT) != true) return
        viewModelScope.launch {
            val info = (bridges.load() as? ChatResult.Ok)?.value
            val ids = com.gatecontrol.android.chat.ChatNewChat.networks(features, info).map { it.id }
            _state.value = _state.value.copy(numberNetworks = ids)
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            when (val r = repository.people()) {
                is ChatResult.Ok -> _state.update { it.copy(people = r.value, loading = false, error = null) }
                is ChatResult.Failed -> _state.value = _state.value.copy(loading = false, error = r.code)
                else -> _state.value = _state.value.copy(loading = false, error = "offline")
            }
        }
    }

    fun openDirect(peerId: Long) = act { repository.openDirect(peerId) }

    // ─── Messenger 1.18: "Neuer Chat" with contacts ─────────────────

    private var seq = 0
    private var searchJob: Job? = null

    /** The three sections (only with `bridge_contacts`, not for "Neue Gruppe"): asked once, then on every search. */
    fun contacts() {
        if (!withContacts || _state.value.contacts != null) return
        _state.update { it.copy(contacts = ContactsUi()) }
        fetch()
    }

    /** The search: the list anew after [ChatNewContacts.DEBOUNCE_MS] (older answers are dropped). */
    fun setQuery(raw: String) {
        val c = _state.value.contacts ?: return
        val q = raw.take(ChatNewContacts.QUERY_MAX)
        if (q == c.query) return
        val changed = ChatNewContacts.query(q) != ChatNewContacts.query(c.query)
        _state.update { s -> s.copy(contacts = s.contacts?.copy(query = q, pick = if (changed) null else s.contacts.pick, rows = if (changed) emptyMap() else s.contacts.rows)) }
        if (!changed) return
        searchJob?.cancel()
        val my = ++seq
        searchJob = viewModelScope.launch {
            delay(ChatNewContacts.DEBOUNCE_MS)
            fetch(my)
        }
    }

    /** "Erneut versuchen", a reference that ran out, a signed-out service: the list anew, now. */
    fun reload() {
        searchJob?.cancel()
        _state.update { s -> s.copy(contacts = s.contacts?.copy(rows = emptyMap(), pick = null)) }
        fetch()
    }

    private fun fetch(my: Int = ++seq) {
        val c = _state.value.contacts ?: return
        viewModelScope.launch {
            _state.update { s -> s.copy(contacts = s.contacts?.copy(loading = true)) }
            val r = repository.newChatContacts(c.network, ChatNewContacts.query(_state.value.contacts?.query.orEmpty()))
            if (my != seq) return@launch
            _state.update { s ->
                val cur = s.contacts ?: return@update s
                s.copy(
                    contacts = when (r) {
                        is ChatResult.Ok -> cur.copy(data = r.value, loading = false, error = null)
                        is ChatResult.Failed -> cur.copy(loading = false, error = r.code)
                        else -> cur.copy(loading = false, error = "offline")
                    },
                )
            }
        }
    }

    fun showAll(whatsapp: Boolean) = _state.update { s ->
        s.copy(contacts = s.contacts?.let { if (whatsapp) it.copy(moreWa = true) else it.copy(moreBook = true) })
    }

    /** A row with a chat there is ("Chat öffnen"): fetched, then opened — nothing goes to the service. */
    fun openExisting(conversationId: Long) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            repository.refreshConversation(conversationId)
            _state.update { it.copy(busy = false) }
            opened.trySend(conversationId)
        }
    }

    fun tapAccount(c: ChatGcContact) {
        val id = c.conversationId
        if (id != null) openExisting(id) else openDirect(c.person.userId)
    }

    /** A WhatsApp contact: the chat at once (`{ ref }`) — nothing checked, nothing counted. */
    fun tapContact(c: ChatWaContact) {
        c.conversationId?.let { openExisting(it); return }
        val cs = _state.value.contacts ?: return
        if (_state.value.busy || cs.data?.whatsapp?.ok != true) return
        _state.update { s -> s.copy(busy = true, contacts = s.contacts?.copy(startingRef = c.ref, error = null)) }
        viewModelScope.launch {
            val r = repository.openContactChat(cs.network, c.ref)
            _state.update { s -> s.copy(busy = false, contacts = s.contacts?.copy(startingRef = null)) }
            when {
                r is ChatResult.Ok -> {
                    if (r.value.created) ChatNumberStarted.putContact(r.value.conversation.id, c.name)
                    opened.trySend(r.value.conversation.id)
                }
                ChatNewContacts.refusal(r) == ChatNewContacts.Refusal.RELOAD -> reload()
                else -> _state.update { s -> s.copy(contacts = s.contacts?.copy(error = code(r))) }
            }
        }
    }

    /** A contact of the address book: the chat there is, the choice of several numbers, or the one number checked. */
    fun tapBook(c: ChatBookContact) {
        c.conversationId?.let { openExisting(it); return }
        val cs = _state.value.contacts ?: return
        if (_state.value.busy || cs.data?.whatsapp?.ok != true) return
        val one = ChatNewContacts.single(c)
        if (one == null) {
            _state.update { s -> s.copy(contacts = s.contacts?.copy(pick = if (cs.pick == c.contactId) null else c.contactId, rows = cs.rows - c.contactId)) }
            return
        }
        tapNumber(c, one)
    }

    /** One number of the address book: asked at the service now (counted in the brake), then the chat opens. */
    fun tapNumber(c: ChatBookContact, p: ChatBookPhone) {
        val cs = _state.value.contacts ?: return
        if (_state.value.busy || cs.data?.whatsapp?.ok != true) return
        _state.update { s -> s.copy(busy = true, contacts = s.contacts?.copy(pick = null, rows = cs.rows + (c.contactId to BookRow.Checking(p.key)))) }
        viewModelScope.launch {
            val r = repository.openBookChat(cs.network, c.contactId, p.key)
            if (r is ChatResult.Ok) {
                _state.update { s -> s.copy(busy = false, contacts = s.contacts?.copy(rows = s.contacts.rows - c.contactId)) }
                if (r.value.created) ChatNumberStarted.putBook(r.value.conversation.id, c.name, p.number)
                opened.trySend(r.value.conversation.id)
                return@launch
            }
            val row: BookRow? = when (ChatNewContacts.refusal(r)) {
                ChatNewContacts.Refusal.NOT_ON -> BookRow.NotOn(p.key)
                ChatNewContacts.Refusal.RATE_LIMITED -> BookRow.RateLimited(
                    p.key,
                    now() + ((r as? ChatResult.Failed)?.retryAfterS?.takeIf { it > 0 } ?: 3600) * 1000L,
                    _state.value.contacts?.data?.perHour ?: DEFAULT_PER_HOUR,
                )
                ChatNewContacts.Refusal.UNREACHABLE -> BookRow.Unreachable(p.key)
                ChatNewContacts.Refusal.RELOAD -> null
                ChatNewContacts.Refusal.MAIL_MISSING -> BookRow.Failed(p.key, "mail_missing")
                ChatNewContacts.Refusal.OFFLINE -> BookRow.Failed(p.key, "offline")
                ChatNewContacts.Refusal.FAILED -> BookRow.Failed(p.key, code(r))
            }
            _state.update { s ->
                val cur = s.contacts ?: return@update s.copy(busy = false)
                s.copy(busy = false, contacts = if (row == null) cur.copy(rows = cur.rows - c.contactId) else cur.copy(rows = cur.rows + (c.contactId to row), data = if (row is BookRow.NotOn) cur.data?.let { notOn(it, c.contactId, p.key) } else cur.data))
            }
            if (row == null) reload()
        }
    }

    /** "+49 … bei WhatsApp prüfen": the chat there is, else the page "Nummer eingeben" with it, checked at once. */
    fun tapNumberHit(n: ChatNumberHit) {
        n.conversationId?.let { openExisting(it); return }
        val net = _state.value.contacts?.network ?: return
        ChatNumberHandoff.put(net, n.e164)
        numberPageCh.trySend(net)
    }

    /** "Nicht dabei? Nummer eingeben". */
    fun enterNumber() {
        val net = _state.value.contacts?.network ?: return
        numberPageCh.trySend(net)
    }

    private fun notOn(d: ChatNewChatContacts, contactId: Long, key: String): ChatNewChatContacts {
        val book = d.addressBook ?: return d
        return d.copy(addressBook = book.copy(items = book.items.map { x ->
            if (x.contactId != contactId) x else x.copy(phones = x.phones.map { if (it.key == key) it.copy(notOnNetwork = true) else it })
        }))
    }

    private fun code(r: ChatResult<*>): String = when (r) {
        is ChatResult.Failed -> r.code
        ChatResult.Offline -> "offline"
        else -> "failed"
    }

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

    private companion object {
        /** The brake's default (Messenger 1.16 `newChatPerHour`) when the list did not name it. */
        const val DEFAULT_PER_HOUR = 10
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
    /** Messenger 1.15/1.16: "WhatsApp-Chat mit Nummer" of a linked service; 1.18: "Nummer eingeben". */
    onNewNumber: (String) -> Unit = {},
    /** Messenger 1.18: "Neu verknüpfen" of a signed-out service ("Verknüpfte Dienste"). */
    onOpenLinked: () -> Unit = {},
    viewModel: NewChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val extra = GateControlTheme.extraColors
    var query by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    // Messenger 1.18: GateControl, WhatsApp and the address book under one search (the search lives in the view model)
    val withContacts = !group && viewModel.withContacts
    if (withContacts) {
        LaunchedEffect(Unit) { viewModel.contacts() }
        LaunchedEffect(Unit) { viewModel.numberPage.collect { onNewNumber(it) } }
    }

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
        val contacts = state.contacts
        if (withContacts) {
            GcTextField(
                value = contacts?.query.orEmpty(),
                onValueChange = viewModel::setQuery,
                label = stringResource(R.string.chat_people_search),
                placeholder = stringResource(R.string.chat_ncc_search),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("ncc-search"),
            )
        } else {
            GcTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                label = stringResource(R.string.chat_people_search),
                placeholder = stringResource(R.string.chat_people_search_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        (state.error ?: contacts?.error)?.let {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ChatText.actionError(ctx, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (contacts?.error != null && state.error == null) {
                    TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.chat_ncc_retry), fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (withContacts && contacts != null) {
            val netName = stringResource(com.gatecontrol.android.ui.chat.settings.networkName(contacts.network))
            val accounts = accountsOf(contacts, state.people)
            val actions = ContactsActions(
                onAccount = viewModel::tapAccount,
                onContact = viewModel::tapContact,
                onBook = viewModel::tapBook,
                onNumber = viewModel::tapNumber,
                onNumberHit = viewModel::tapNumberHit,
                onShowAll = viewModel::showAll,
                onReload = viewModel::reload,
                onRelink = onOpenLinked,
                onEnterNumber = viewModel::enterNumber,
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                // "Neue Gruppe" stays on top — and goes while searching
                if (!contacts.searching) item(key = "group") { NewGroupRow(onNewGroup) }
                contactSections(contacts, accounts, netName, actions)
            }
            ContactsFoot(contacts, accounts, netName, viewModel::enterNumber)
            return@Column
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!group) {
                item(key = "group") { NewGroupRow(onNewGroup) }
                // Messenger 1.15/1.16: a chat by phone number over a linked service, right under "Neue Gruppe"
                items(state.numberNetworks, key = { "num-$it" }) { net ->
                    val name = stringResource(com.gatecontrol.android.ui.chat.settings.networkName(net))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onNewNumber(net) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(extra.accentBg), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Call, contentDescription = null, tint = extra.accentText)
                            }
                            BridgeMark(com.gatecontrol.android.chat.ChatConvBridge(net, name, com.gatecontrol.android.chat.ChatBridgeChat.OK), 44.dp, Modifier.align(Alignment.BottomEnd))
                        }
                        Column {
                            Text(stringResource(R.string.chat_newnum_row, name), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.chat_newnum_row_desc), style = MaterialTheme.typography.bodySmall, color = extra.muted)
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

/** "Neue Gruppe" at the top of "Neuer Chat". */
@Composable
private fun NewGroupRow(onNewGroup: () -> Unit) {
    val extra = GateControlTheme.extraColors
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
