package com.gatecontrol.android.ui.mail.contacts

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MBooks
import com.gatecontrol.android.mail.MCard
import com.gatecontrol.android.mail.MDuplicate
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MMergePick
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DuplicatesState(
    val items: List<MDuplicate> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The group being merged: its full contacts. */
    val merging: MDuplicate? = null,
    val cards: List<MCard> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

/** "Dubletten" (contract D4): the possible duplicates, merging (with the fields to keep) and "Keine Dublette". */
@HiltViewModel
class MailDuplicatesViewModel @Inject constructor(private val repository: MailRepository) : ViewModel() {
    private val pim = repository.pim
    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val books: StateFlow<MBooks?> = pim.books

    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = api.duplicates()) {
                is MResult.Ok -> _state.update { it.copy(items = r.value, loading = false) }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Zusammenführen": the full contacts for the preview. */
    fun open(d: MDuplicate) {
        _state.update { it.copy(merging = d, cards = emptyList(), busy = true) }
        viewModelScope.launch {
            val cards = d.cards.mapNotNull { c -> pim.card(c.id).valueOrNull() }
            _state.update { it.copy(cards = cards, busy = false) }
        }
    }

    fun close() = _state.update { it.copy(merging = null, cards = emptyList()) }

    fun merge(primary: Long, pick: MMergePick) {
        val d = _state.value.merging ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val r = api.merge(MailPimJson.mergeBody(d.cards.map { it.id }, primary, pick, joinNotes = true))) {
                is MResult.Ok -> {
                    _state.update { s -> s.copy(busy = false, merging = null, items = s.items - d, message = "merged") }
                    pim.syncCards(full = true)
                }
                else -> _state.update { it.copy(busy = false, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Keine Dublette": the pair is not shown again. */
    fun ignore(d: MDuplicate) {
        viewModelScope.launch {
            when (val r = api.ignoreDuplicates(d.cards.map { it.id })) {
                is MResult.Ok -> _state.update { s -> s.copy(items = s.items - d) }
                else -> _state.update { it.copy(message = MailHomeViewModel.code(r)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

/** "Dubletten" (mockup "Kontakte" band): the groups with their reason, "Zusammenführen" and "Keine Dublette". */
@Composable
fun MailDuplicatesScreen(onBack: () -> Unit, onContact: (Long) -> Unit, viewModel: MailDuplicatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, if (it == "merged") context.getString(R.string.con_merged) else mailText(context, it))
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.con_duplicates), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            state.error != null -> ErrorBox(mailText(context, state.error), onRetry = viewModel::load)
            state.items.isEmpty() -> ErrorBox(stringResource(R.string.con_no_duplicates), onRetry = null)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.items, key = { it.key }) { d ->
                    GcCard(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.Top) {
                        Text(reasonText(d.reason), style = MaterialTheme.typography.labelMedium, color = extra.warn, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                        d.cards.forEach { c ->
                            val book = books?.book(c.addressbookId)
                            ContactRow(c, sourceColor(me, book?.accountId), accountLabel(me, book?.accountId)) { onContact(c.id) }
                        }
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GcPrimaryButton(stringResource(R.string.con_merge), onClick = { viewModel.open(d) }, modifier = Modifier.weight(1f))
                            GcOutlineButton(stringResource(R.string.con_not_duplicate), onClick = { viewModel.ignore(d) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    state.merging?.let { d -> MergeDialog(d, state.cards, state.busy, onMerge = viewModel::merge, onDismiss = viewModel::close) }
}

@Composable
private fun reasonText(reason: String): String = stringResource(
    when (reason) {
        "email" -> R.string.con_reason_email
        "phone" -> R.string.con_reason_phone
        else -> R.string.con_reason_name
    },
)

/**
 * The merge preview: which contact stays, per differing field which value; contacts of different address books are
 * linked ("beim Anbieter bleibt beides").
 */
@Composable
private fun MergeDialog(d: MDuplicate, cards: List<MCard>, busy: Boolean, onMerge: (Long, MMergePick) -> Unit, onDismiss: () -> Unit) {
    var primary by remember(d.key) { mutableLongStateOf(d.cards.first().id) }
    var pick by remember(d.key) { mutableStateOf(MMergePick()) }
    val extra = GateControlTheme.extraColors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.con_merge)) },
        text = {
            if (cards.size < 2) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                return@AlertDialog
            }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (MailPimLogic.links(d.cards)) {
                    GcBanner(tone = GcTone.Info, icon = GcIcons.Link) { Text(stringResource(R.string.con_merge_link), style = MaterialTheme.typography.bodySmall) }
                }
                Text(stringResource(R.string.con_merge_keep), style = MaterialTheme.typography.labelMedium, color = extra.muted)
                cards.forEach { c -> Choice(c.label + (c.company?.let { " · $it" } ?: ""), primary == c.id) { primary = c.id } }
                val conflicts = MailPimLogic.conflicts(cards)
                conflicts.forEach { field ->
                    Text(fieldName(field), style = MaterialTheme.typography.labelMedium, color = extra.muted, modifier = Modifier.padding(top = 6.dp))
                    cards.forEach { c ->
                        val value = when (field) {
                            "name" -> c.label
                            "company" -> c.company
                            "title" -> c.title
                            else -> c.birthday
                        } ?: return@forEach
                        val chosen = when (field) {
                            "name" -> pick.name
                            "company" -> pick.company
                            "title" -> pick.title
                            else -> pick.birthday
                        } ?: primary
                        Choice(value, chosen == c.id) {
                            pick = when (field) {
                                "name" -> pick.copy(name = c.id)
                                "company" -> pick.copy(company = c.id)
                                "title" -> pick.copy(title = c.id)
                                else -> pick.copy(birthday = c.id)
                            }
                        }
                    }
                }
                val preview = MailPimLogic.merged(cards, primary, pick)
                Text(
                    stringResource(R.string.con_merge_result, preview.emails.size, preview.phones.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = !busy && cards.size >= 2, onClick = { onMerge(primary, pick) }) { Text(stringResource(R.string.con_merge), fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.mail_cancel)) } },
    )
}

@Composable
private fun fieldName(field: String): String = stringResource(
    when (field) {
        "name" -> R.string.con_name
        "company" -> R.string.con_company
        "title" -> R.string.con_position
        else -> R.string.con_birthday
    },
)

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}
