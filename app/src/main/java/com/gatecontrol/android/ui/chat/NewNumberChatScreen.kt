package com.gatecontrol.android.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContactPhone
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatBridgeNetwork
import com.gatecontrol.android.chat.ChatBridgesRepository
import com.gatecontrol.android.chat.ChatCountry
import com.gatecontrol.android.chat.ChatNewChat
import com.gatecontrol.android.chat.ChatNumber
import com.gatecontrol.android.chat.ChatPerson
import com.gatecontrol.android.chat.ChatRepository
import com.gatecontrol.android.chat.ChatResult
import com.gatecontrol.android.profile.AvatarRing
import com.gatecontrol.android.ui.chat.settings.networkName
import com.gatecontrol.android.ui.components.GcAvatar
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/*
 * "WhatsApp-Chat mit Nummer" (Messenger 1.15/1.16, draft "Neuer WhatsApp-Chat per Nummer", approved 15.11.): its own
 * page like "WhatsApp verknüpfen". The number lives in this view model's memory only while the page is open — not in
 * the saved state, a preference or a log, not in `toString()`.
 */

/** Where the page stands. Classes that carry a number keep it out of `toString()`. */
sealed interface NumberPhase {
    /** Typing (the field says live how the number is read). */
    data object Input : NumberPhase

    /** The server did not take the number as one (`400 invalid_number`). */
    data object InvalidServer : NumberPhase

    data object Checking : NumberPhase

    /** At the service: its [name] there, the chat the person has with it already ([existing]); [starting] the chat. */
    class Found(val name: String?, val display: String, val existing: Long?, val starting: Boolean = false) : NumberPhase {
        fun starting(on: Boolean) = Found(name, display, existing, on)
        override fun toString(): String = "Found(existing=${existing != null}, starting=$starting)"
    }

    class NotFound(val display: String) : NumberPhase {
        override fun toString(): String = "NotFound"
    }

    /** Too many checks: the next from [untilMs] (null: the server did not say). */
    data class RateLimited(val untilMs: Long?) : NumberPhase

    data object Unreachable : NumberPhase
    data object Offline : NumberPhase
    data object Failed : NumberPhase

    /** Nothing can be checked now: signed out, not linked, locked for a child, switched off. */
    data class Blocked(val why: ChatNewChat.Blocked) : NumberPhase
}

class NumberChatUi(
    val network: String? = null,
    val loading: Boolean = true,
    val country: ChatCountry = ChatNewChat.DEFAULT_COUNTRY,
    val input: String = "",
    val phase: NumberPhase = NumberPhase.Input,
    /** The service's limit per hour (Messenger 1.16 `newChat.limit.perHour`), for the sentence of "Zu viele Prüfungen". */
    val perHour: Int? = null,
) {
    /** How the field reads what was typed. */
    val number: ChatNumber get() = ChatNewChat.number(input, country)

    fun copy(
        network: String? = this.network,
        loading: Boolean = this.loading,
        country: ChatCountry = this.country,
        input: String = this.input,
        phase: NumberPhase = this.phase,
        perHour: Int? = this.perHour,
    ) = NumberChatUi(network, loading, country, input, phase, perHour)

    /** Without the number. */
    override fun toString(): String = "NumberChatUi(network=$network, loading=$loading, phase=$phase)"
}

@HiltViewModel
class NewNumberChatViewModel @Inject constructor(
    private val bridges: ChatBridgesRepository,
    private val repository: ChatRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(NumberChatUi())
    val state: StateFlow<NumberChatUi> = _state.asStateFlow()

    private val opened = Channel<Long>(Channel.BUFFERED)
    /** The chat to open (started, or the one there was). */
    val open = opened.receiveAsFlow()

    /** The clock (tests set their own). */
    internal var now: () -> Long = System::currentTimeMillis

    /** The number of the last check, as sent — what "Chat starten" starts. */
    private var checked: String? = null
    private var rateJob: Job? = null

    fun bind(network: String) {
        if (_state.value.network == network) return
        _state.value = NumberChatUi(network = network)
        viewModelScope.launch {
            val r = bridges.load()
            val n = (r as? ChatResult.Ok)?.value?.takeIf { it.available }?.networks?.firstOrNull { it.id == network }
            _state.update { s ->
                when {
                    r is ChatResult.Ok && n == null -> s.copy(loading = false, phase = NumberPhase.Blocked(ChatNewChat.Blocked.UNLINKED))
                    n == null -> s.copy(loading = false)
                    else -> s.copy(loading = false, perHour = n.newChat?.perHour, phase = first(n))
                }
            }
            (_state.value.phase as? NumberPhase.RateLimited)?.let { waitOut(it) }
        }
    }

    private fun first(n: ChatBridgeNetwork): NumberPhase {
        ChatNewChat.blocked(n)?.let { return if (it == ChatNewChat.Blocked.UNREACHABLE) NumberPhase.Unreachable else NumberPhase.Blocked(it) }
        val nc = n.newChat
        if (nc != null && nc.left == 0 && (nc.retryAfterS ?: 0) > 0) return NumberPhase.RateLimited(now() + nc.retryAfterS!! * 1000L)
        return NumberPhase.Input
    }

    /** A change of the field: a result shown for the number before goes. */
    fun setInput(value: String) {
        val s = _state.value
        if (s.phase is NumberPhase.Checking || s.phase is NumberPhase.Blocked || (s.phase as? NumberPhase.Found)?.starting == true) return
        _state.value = s.copy(input = value.take(MAX_INPUT), phase = afterEdit(s.phase))
    }

    fun setCountry(c: ChatCountry) {
        val s = _state.value
        if (s.phase is NumberPhase.Checking || s.phase is NumberPhase.Blocked) return
        _state.value = s.copy(country = c, phase = afterEdit(s.phase))
    }

    private fun afterEdit(p: NumberPhase): NumberPhase = if (p is NumberPhase.RateLimited || p is NumberPhase.Blocked) p else NumberPhase.Input

    /** A number from the phone's contacts (the system's picker); null: the contact has none. */
    fun picked(raw: String) {
        val v = raw.trim().take(MAX_INPUT)
        ChatNewChat.countryOf(v)?.let { _state.update { s -> s.copy(country = it) } }
        setInput(v)
    }

    /** "Andere Nummer": an empty field. */
    fun other() {
        checked = null
        _state.update { s -> s.copy(input = "", phase = afterEdit(s.phase)) }
    }

    /** "Prüfen" / "Erneut versuchen": one question to the service — only on the button, never while typing. */
    fun check() {
        val s = _state.value
        val net = s.network ?: return
        val n = s.number
        val e164 = n.e164 ?: return
        val display = n.display ?: return
        if (s.phase is NumberPhase.Checking || s.phase is NumberPhase.Blocked) return
        if (s.phase is NumberPhase.RateLimited && (s.phase.untilMs ?: Long.MAX_VALUE) > now()) return
        checked = e164
        _state.value = s.copy(phase = NumberPhase.Checking)
        viewModelScope.launch {
            val r = bridges.resolve(net, e164)
            val next = when {
                r is ChatResult.Ok && r.value.found -> NumberPhase.Found(r.value.name, display, r.value.existingConversationId)
                r is ChatResult.Ok -> NumberPhase.NotFound(display)
                else -> refused(r, display)
            }
            _state.update { it.copy(phase = next) }
            (next as? NumberPhase.RateLimited)?.let { waitOut(it) }
        }
    }

    /** "Chat starten" / "Chat öffnen". */
    fun start() {
        val s = _state.value
        val f = s.phase as? NumberPhase.Found ?: return
        if (f.starting) return
        val net = s.network ?: return
        _state.value = s.copy(phase = f.starting(true))
        viewModelScope.launch {
            val existing = f.existing
            if (existing != null) {
                // the chat is there already: fetched, then opened
                repository.refreshConversation(existing)
                _state.update { it.copy(phase = f) }
                opened.trySend(existing)
                return@launch
            }
            val e164 = checked ?: run { _state.update { it.copy(phase = NumberPhase.Input) }; return@launch }
            when (val r = repository.openNumberChat(net, e164)) {
                is ChatResult.Ok -> {
                    // a chat that came from outside before is found again (`created: false`) — just opened
                    if (r.value.created) com.gatecontrol.android.chat.ChatNumberStarted.put(r.value.conversation.id, f.display, f.name)
                    _state.update { it.copy(phase = f) }
                    opened.trySend(r.value.conversation.id)
                }
                else -> _state.update { it.copy(phase = refused(r, f.display)) }
            }
        }
    }

    private suspend fun refused(r: ChatResult<*>, display: String): NumberPhase = when (ChatNewChat.refusal(r)) {
        ChatNewChat.Refusal.INVALID -> NumberPhase.InvalidServer
        ChatNewChat.Refusal.NOT_FOUND -> NumberPhase.NotFound(display)
        ChatNewChat.Refusal.RATE_LIMITED -> {
            val after = (r as? ChatResult.Failed)?.retryAfterS ?: limitAfter()
            NumberPhase.RateLimited(after?.let { now() + it * 1000L })
        }
        ChatNewChat.Refusal.UNLINKED -> NumberPhase.Blocked(ChatNewChat.Blocked.UNLINKED)
        ChatNewChat.Refusal.LOGGED_OUT -> NumberPhase.Blocked(ChatNewChat.Blocked.LOGGED_OUT)
        ChatNewChat.Refusal.LOCKED -> NumberPhase.Blocked(ChatNewChat.Blocked.LOCKED)
        ChatNewChat.Refusal.OFF -> NumberPhase.Blocked(ChatNewChat.Blocked.OFF)
        ChatNewChat.Refusal.UNREACHABLE -> NumberPhase.Unreachable
        ChatNewChat.Refusal.OFFLINE -> NumberPhase.Offline
        ChatNewChat.Refusal.FAILED -> NumberPhase.Failed
    }

    /** The wait the server names in `newChat.limit` when the refusal itself did not. */
    private suspend fun limitAfter(): Int? {
        val net = _state.value.network ?: return null
        val n = (bridges.load() as? ChatResult.Ok)?.value?.networks?.firstOrNull { it.id == net } ?: return null
        n.newChat?.perHour?.let { p -> _state.update { it.copy(perHour = p) } }
        return n.newChat?.retryAfterS?.takeIf { it > 0 }
    }

    /** Back to typing once the wait is over. */
    private fun waitOut(p: NumberPhase.RateLimited) {
        val until = p.untilMs ?: return
        rateJob?.cancel()
        rateJob = viewModelScope.launch {
            delay((until - now()).coerceAtLeast(0))
            _state.update { if (it.phase == p) it.copy(phase = NumberPhase.Input) else it }
        }
    }

    override fun onCleared() {
        checked = null
    }

    private companion object {
        const val MAX_INPUT = 40
    }
}

/** The number of a contact the system's picker returned ([uri] of a phone row; read with the picker's grant only). */
private suspend fun pickedNumber(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * The page "WhatsApp-Chat mit Nummer": the number (country code, field, "Aus Kontakten wählen"), what the check found,
 * one sentence on what happens to the number, the button of the moment.
 */
@Composable
fun NewNumberChatScreen(
    network: String,
    onBack: () -> Unit,
    onOpened: (Long) -> Unit,
    onOpenLinked: () -> Unit,
    viewModel: NewNumberChatViewModel = hiltViewModel(),
) {
    LaunchedEffect(network) { viewModel.bind(network) }
    LaunchedEffect(Unit) { viewModel.open.collect { onOpened(it) } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noNumber = stringResource(R.string.chat_newnum_contacts_none)
    // Only the system's own contact picker: no permission to read the contacts, nothing leaves the phone but the one
    // number the person picked (and only on "Prüfen").
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val n = pickedNumber(context, uri)
            if (n == null) Toast.makeText(context, noNumber, Toast.LENGTH_SHORT).show() else viewModel.picked(n)
        }
    }
    NewNumberChatContent(
        ui = state,
        name = stringResource(networkName(network)),
        onBack = onBack,
        onInput = viewModel::setInput,
        onCountry = viewModel::setCountry,
        onContacts = {
            try {
                picker.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, noNumber, Toast.LENGTH_SHORT).show()
            }
        },
        onCheck = viewModel::check,
        onStart = viewModel::start,
        onOther = viewModel::other,
        onOpenLinked = onOpenLinked,
    )
}

@Composable
fun NewNumberChatContent(
    ui: NumberChatUi,
    name: String,
    onBack: () -> Unit,
    onInput: (String) -> Unit,
    onCountry: (ChatCountry) -> Unit,
    onContacts: () -> Unit,
    onCheck: () -> Unit,
    onStart: () -> Unit,
    onOther: () -> Unit,
    onOpenLinked: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val phase = ui.phase
    val blocked = phase as? NumberPhase.Blocked
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        GcSubpageBar(title = stringResource(R.string.chat_newnum_title, name), onBack = onBack, backDescription = stringResource(R.string.chat_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = extra.accentText)
                }
            } else if (blocked == null) {
                NumberField(ui, onInput, onCountry, onContacts, onCheck)
            }
            if (!ui.loading) ResultCard(ui, name)
            if (!ui.loading && blocked == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = extra.faint, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                    Text(stringResource(R.string.chat_newnum_privacy, name), style = MaterialTheme.typography.bodySmall, color = extra.muted)
                }
            }
        }
        if (!ui.loading) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Buttons(ui, onCheck, onStart, onOther, onBack, onOpenLinked)
            }
        }
    }
}

@Composable
private fun Buttons(ui: NumberChatUi, onCheck: () -> Unit, onStart: () -> Unit, onOther: () -> Unit, onBack: () -> Unit, onOpenLinked: () -> Unit) {
    when (val p = ui.phase) {
        is NumberPhase.Found -> {
            GcPrimaryButton(text = stringResource(if (p.existing != null) R.string.chat_newnum_open else R.string.chat_newnum_start), onClick = onStart, loading = p.starting, modifier = Modifier.testTag("newnum-primary"))
            TextButton(onClick = onOther, enabled = !p.starting) { Text(stringResource(R.string.chat_newnum_other), fontWeight = FontWeight.Bold) }
        }
        // the card above turns; the button says what happens
        NumberPhase.Checking -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_checking_btn), onClick = {}, enabled = false, modifier = Modifier.testTag("newnum-primary"))
        NumberPhase.Unreachable -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_retry), onClick = onCheck, enabled = ui.number.ok, icon = Icons.Outlined.Refresh, modifier = Modifier.testTag("newnum-primary"))
        is NumberPhase.Blocked -> when (p.why) {
            ChatNewChat.Blocked.LOGGED_OUT -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_relink), onClick = onOpenLinked, modifier = Modifier.testTag("newnum-primary"))
            ChatNewChat.Blocked.UNLINKED -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_link), onClick = onOpenLinked, modifier = Modifier.testTag("newnum-primary"))
            else -> GcOutlineButton(text = stringResource(R.string.chat_newnum_close), onClick = onBack, modifier = Modifier.testTag("newnum-primary"))
        }
        is NumberPhase.RateLimited -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_check), onClick = onCheck, enabled = false, modifier = Modifier.testTag("newnum-primary"))
        else -> GcPrimaryButton(text = stringResource(R.string.chat_newnum_check), onClick = onCheck, enabled = ui.number.ok, modifier = Modifier.testTag("newnum-primary"))
    }
}

@Composable
private fun NumberField(ui: NumberChatUi, onInput: (String) -> Unit, onCountry: (ChatCountry) -> Unit, onContacts: () -> Unit, onCheck: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val phase = ui.phase
    val locked = phase is NumberPhase.Checking || phase is NumberPhase.RateLimited || (phase as? NumberPhase.Found)?.starting == true
    val n = ui.number
    val found = phase is NumberPhase.Found
    // a number that is obviously none says so while typing (from four digits on), nothing is sent for it
    val digits = ui.input.count { it.isDigit() }
    val showInvalid = !n.ok && n.invalid != ChatNumber.Invalid.EMPTY && (digits >= 4 || n.invalid == ChatNumber.Invalid.CHARS || n.invalid == ChatNumber.Invalid.LONG)
    val error = when {
        phase == NumberPhase.InvalidServer -> stringResource(R.string.chat_newnum_invalid_server)
        showInvalid -> stringResource(
            when (n.invalid) {
                ChatNumber.Invalid.SHORT_MOBILE_DE -> R.string.chat_newnum_invalid_short_de
                ChatNumber.Invalid.LONG -> R.string.chat_newnum_invalid_long
                ChatNumber.Invalid.CHARS -> R.string.chat_newnum_invalid_chars
                else -> R.string.chat_newnum_invalid_short
            },
        )
        else -> null
    }
    val shape = RoundedCornerShape(14.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.chat_newnum_label), style = MaterialTheme.typography.titleSmall, color = extra.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var menu by remember { mutableStateOf(false) }
            val countryLabel = stringResource(R.string.chat_newnum_country, "+" + ui.country.code)
            Box {
                Row(
                    Modifier.height(56.dp).clip(shape).border(1.dp, extra.border2, shape).background(scheme.background)
                        .clickable(enabled = !locked, role = Role.DropdownList, onClickLabel = countryLabel) { menu = true }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(ui.country.iso, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = extra.muted)
                    Text("+" + ui.country.code, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = scheme.onSurface)
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ChatNewChat.COUNTRIES.forEach { c ->
                        DropdownMenuItem(text = { Text("${c.iso}  +${c.code}") }, onClick = { menu = false; onCountry(c) })
                    }
                }
            }
            OutlinedTextField(
                value = ui.input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("newnum-field"),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(stringResource(R.string.chat_newnum_placeholder), style = MaterialTheme.typography.bodyLarge, color = extra.faint) },
                trailingIcon = if (found) ({ Icon(Icons.Filled.Check, contentDescription = null, tint = extra.accentText) }) else null,
                singleLine = true,
                enabled = !locked,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onCheck() }),
                shape = shape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = scheme.background,
                    unfocusedContainerColor = scheme.background,
                    disabledContainerColor = scheme.background,
                    errorContainerColor = scheme.background,
                    focusedBorderColor = if (found) extra.accentText else extra.blue,
                    unfocusedBorderColor = if (found) extra.accentText else extra.border2,
                    disabledBorderColor = extra.border,
                    errorBorderColor = scheme.error,
                    cursorColor = extra.blue,
                    focusedTextColor = scheme.onSurface,
                    unfocusedTextColor = scheme.onSurface,
                    disabledTextColor = scheme.onSurface.copy(alpha = 0.6f),
                ),
            )
            val contacts = stringResource(R.string.chat_newnum_contacts)
            Box(
                Modifier.size(56.dp).clip(shape).border(1.dp, extra.border2, shape).background(scheme.background)
                    .clickable(enabled = !locked, role = Role.Button, onClickLabel = contacts, onClick = onContacts),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.ContactPhone, contentDescription = contacts, tint = extra.accentText)
            }
        }
        // under the field: how the number is read — not while a result stands in its place
        val result = phase is NumberPhase.Found || phase is NumberPhase.Checking || phase is NumberPhase.RateLimited ||
            phase == NumberPhase.Unreachable || phase == NumberPhase.Offline || phase == NumberPhase.Failed
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            result -> Unit
            n.ok -> Text(stringResource(R.string.chat_newnum_as, n.display.orEmpty()), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = extra.accentText)
            phase !is NumberPhase.NotFound -> Text(stringResource(R.string.chat_newnum_hint), style = MaterialTheme.typography.bodySmall, color = extra.faint)
        }
    }
}

@Composable
private fun ResultCard(ui: NumberChatUi, name: String) {
    val extra = GateControlTheme.extraColors
    val ctx = LocalContext.current
    when (val p = ui.phase) {
        NumberPhase.Checking -> Card(extra.panel2, null, stringResource(R.string.chat_newnum_checking_title, name), stringResource(R.string.chat_newnum_checking_text), spinner = true)
        is NumberPhase.Found -> {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(extra.accentBg).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GcAvatar(url = null, initials = if (p.name != null) ChatPerson.initialsOf(p.name) else "#", colorHex = null, ring = AvatarRing.None, size = 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name ?: p.display, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
                    if (p.name != null) Text(p.display, style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (p.existing != null) stringResource(R.string.chat_newnum_exists) else stringResource(R.string.chat_newnum_has, name),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        is NumberPhase.NotFound -> Card(extra.panel2, Icons.Outlined.PersonOff, stringResource(R.string.chat_newnum_none_title, name), stringResource(R.string.chat_newnum_none_text, p.display, name))
        is NumberPhase.RateLimited -> {
            val text = when {
                p.untilMs == null -> stringResource(R.string.chat_newnum_rate_text_later, name)
                ui.perHour != null -> pluralStringResource(R.plurals.chat_newnum_rate_text, ui.perHour, ui.perHour, name, clock(ctx, p.untilMs))
                else -> stringResource(R.string.chat_newnum_rate_text_plain, name, clock(ctx, p.untilMs))
            }
            Card(extra.warnBg, Icons.Outlined.Schedule, stringResource(R.string.chat_newnum_rate_title), text, tint = extra.warn)
        }
        NumberPhase.Unreachable -> Card(extra.errorBg, Icons.Outlined.PowerOff, stringResource(R.string.chat_newnum_unreach_title), stringResource(R.string.chat_newnum_unreach_text, name), tint = MaterialTheme.colorScheme.error)
        NumberPhase.Offline -> Card(extra.errorBg, Icons.Outlined.CloudOff, stringResource(R.string.chat_newnum_offline_title), stringResource(R.string.chat_newnum_offline_text), tint = MaterialTheme.colorScheme.error)
        NumberPhase.Failed -> Card(extra.errorBg, Icons.Outlined.ErrorOutline, stringResource(R.string.chat_newnum_failed_title), stringResource(R.string.chat_newnum_failed_text), tint = MaterialTheme.colorScheme.error)
        is NumberPhase.Blocked -> when (p.why) {
            ChatNewChat.Blocked.LOGGED_OUT -> Card(extra.warnBg, Icons.AutoMirrored.Outlined.Logout, stringResource(R.string.chat_newnum_loggedout_title, name), stringResource(R.string.chat_newnum_loggedout_text, name), tint = extra.warn)
            ChatNewChat.Blocked.UNLINKED -> Card(extra.panel2, Icons.Outlined.LinkOff, stringResource(R.string.chat_newnum_unlinked_title, name), stringResource(R.string.chat_newnum_unlinked_text))
            ChatNewChat.Blocked.LOCKED -> Card(extra.panel2, Icons.Outlined.Lock, stringResource(R.string.chat_newnum_locked_title), stringResource(R.string.chat_newnum_locked_text, name))
            ChatNewChat.Blocked.OFF, ChatNewChat.Blocked.UNREACHABLE -> Card(extra.panel2, Icons.Outlined.PowerOff, stringResource(R.string.chat_newnum_off_title), stringResource(R.string.chat_newnum_off_text, name))
        }
        NumberPhase.Input, NumberPhase.InvalidServer -> Unit
    }
}

private fun clock(ctx: Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))

@Composable
private fun Card(bg: Color, icon: ImageVector?, title: String, text: String, tint: Color = GateControlTheme.extraColors.muted, spinner: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (spinner) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GateControlTheme.extraColors.accentText)
            else if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
