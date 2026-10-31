package com.gatecontrol.android.ui.mail.contacts

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.gatecontrol.android.mail.MMerge
import com.gatecontrol.android.mail.MPreview
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MSource
import com.gatecontrol.android.mail.MailContactLogic
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.MailHomeViewModel
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.mailTime
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
    /** The cases to look at (Mail 1.4.0: the uncertain ones; the server merged the certain ones). */
    val items: List<MDuplicate> = emptyList(),
    /** Mail 1.4.0: the automatic merges ("Automatisch zusammengeführt"); null: an older server. */
    val merges: List<MMerge>? = null,
    /** Before Mail 1.4.0: the full cards of a case, loaded here (the server sends no details). */
    val cards: Map<String, List<MCard>> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
    /** A case or a merge being answered. */
    val busy: String? = null,
    val message: String? = null,
)

/**
 * "Dubletten" (Mail 1.4.0 Nachtrag §6): the cases to confirm — each card with where it comes from and its numbers,
 * addresses, postal address and photo, "So wird der Kontakt", "Zusammenführen" and "Keine Dublette" — and above them
 * the automatic merges with "Rückgängig". An older server: its cases with the cards loaded here and a preview made here.
 */
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
            when (val r = api.duplicates(details = true)) {
                is MResult.Ok -> {
                    val items = r.value.filter { it.certain != true }
                    _state.update { it.copy(items = items, loading = false) }
                    // the protocol of the automatic merges (an older server has none: 404, nothing shown)
                    api.merges().valueOrNull()?.let { m -> _state.update { it.copy(merges = m.filter { x -> x.automatic && x.undoneAt == null }) } }
                    // an older server: the cards of the cases from here
                    items.filter { it.details.isEmpty() }.take(30).forEach { d ->
                        val cards = d.cards.mapNotNull { c -> pim.card(c.id).valueOrNull() }
                        _state.update { s -> s.copy(cards = s.cards + (d.key to cards)) }
                    }
                }
                else -> _state.update { it.copy(loading = false, error = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Zusammenführen": one GC contact (Mail 1.4.0, as previewed); an older server keeps the GC card (else the first). */
    fun merge(d: MDuplicate) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = d.key) }
        viewModelScope.launch {
            val modern = d.certain != null
            val cards = cardsOf(d)
            val primary = if (modern) null else (cards.firstOrNull { it.source == "local" } ?: cards.firstOrNull())?.id ?: d.cards.first().id
            when (val r = api.merge(MailPimJson.mergeBody(d.cards.map { it.id }, primary, null, joinNotes = !modern))) {
                is MResult.Ok -> {
                    _state.update { s -> s.copy(busy = null, items = s.items - d, message = "merged") }
                    pim.syncCards(full = true)
                }
                else -> _state.update { it.copy(busy = null, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Keine Dublette": the pair is not shown again (and never merged by itself). */
    fun ignore(d: MDuplicate) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = d.key) }
        viewModelScope.launch {
            when (val r = api.ignoreDuplicates(d.cards.map { it.id })) {
                is MResult.Ok -> _state.update { s -> s.copy(busy = null, items = s.items - d) }
                else -> _state.update { it.copy(busy = null, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** "Rückgängig" of an automatic merge: the cards stand on their own again (nothing changes at the provider). */
    fun undo(m: MMerge) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "m${m.id}") }
        viewModelScope.launch {
            when (val r = api.undoMerge(m.id, keepMaster = false)) {
                is MResult.Ok -> {
                    _state.update { s -> s.copy(busy = null, merges = s.merges?.filter { it.id != m.id }, message = "undone") }
                    pim.syncCards(full = true)
                }
                else -> _state.update { it.copy(busy = null, message = MailHomeViewModel.code(r)) }
            }
        }
    }

    /** The full cards of a case: the server's details, else the ones loaded here. */
    fun cardsOf(d: MDuplicate, s: DuplicatesState = _state.value): List<MCard> = d.details.map { it.card }.ifEmpty { s.cards[d.key].orEmpty() }

    fun clearMessage() = _state.update { it.copy(message = null) }
}

@Composable
fun MailDuplicatesScreen(onBack: () -> Unit, onContact: (Long) -> Unit, viewModel: MailDuplicatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    var autoOpen by rememberSaveable { mutableStateOf(false) }
    var undoing by remember { mutableStateOf<MMerge?>(null) }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(
                context,
                when (it) {
                    "merged" -> context.getString(R.string.con_merged)
                    "undone" -> context.getString(R.string.dup_undone)
                    else -> mailText(context, it)
                },
            )
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(title = stringResource(R.string.con_duplicates), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            state.error != null -> ErrorBox(mailText(context, state.error), onRetry = viewModel::load)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val merges = state.merges.orEmpty()
                if (merges.isNotEmpty()) {
                    item(key = "auto") {
                        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { autoOpen = !autoOpen }.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(GcIcons.Check, contentDescription = null, tint = extra.accentText, modifier = Modifier.size(18.dp))
                                Text(
                                    stringResource(R.string.dup_auto, merges.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f).semantics { heading() },
                                )
                                Icon(if (autoOpen) GcIcons.ChevronUp else GcIcons.ChevronDown, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
                            }
                            if (autoOpen) {
                                merges.forEach { m -> MergeRow(m, books, busy = state.busy == "m${m.id}", onOpen = { onContact(m.masterId) }, onUndo = { undoing = m }) }
                            }
                        }
                    }
                }
                if (state.items.isEmpty()) {
                    item(key = "none") { ErrorBox(stringResource(R.string.con_no_duplicates), onRetry = null) }
                } else {
                    item(key = "h") {
                        GcSectionLabel(pluralStringResource(R.plurals.dup_open, state.items.size, state.items.size), Modifier.padding(start = 4.dp, top = 4.dp))
                    }
                }
                items(state.items, key = { it.key }) { d ->
                    DuplicateCase(
                        d = d,
                        cards = viewModel.cardsOf(d, state),
                        me = me,
                        busy = state.busy == d.key,
                        onContact = onContact,
                        onMerge = { viewModel.merge(d) },
                        onIgnore = { viewModel.ignore(d) },
                    )
                }
            }
        }
    }
    undoing?.let { m ->
        AlertDialog(
            onDismissRequest = { undoing = null },
            title = { Text(stringResource(R.string.dup_undo_title)) },
            text = { Text(stringResource(if (m.created) R.string.dup_undo_text_created else R.string.dup_undo_text)) },
            confirmButton = { TextButton(onClick = { undoing = null; viewModel.undo(m) }) { Text(stringResource(R.string.dup_undo), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

/** An automatic merge: the names it joined, from where, when; "Rückgängig". */
@Composable
private fun MergeRow(m: MMerge, books: MBooks?, busy: Boolean, onOpen: () -> Unit, onUndo: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                m.cards.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "–" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val from = m.cards.map { c -> MailContactLogic.providerName(c.source, c.addressbook) ?: c.addressbook.ifBlank { books?.book(c.addressbookId)?.name.orEmpty() } }
                .filter { it.isNotBlank() }.distinct()
            Text((from + mailTime(m.at)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) CircularProgressIndicator(Modifier.size(20.dp)) else TextButton(onClick = onUndo) { Text(stringResource(R.string.dup_undo)) }
    }
}

/**
 * One case to confirm: why (same number …), each card with where it comes from and what it holds, "So wird der
 * Kontakt", "Zusammenführen" and "Keine Dublette".
 */
@Composable
private fun DuplicateCase(
    d: MDuplicate,
    cards: List<MCard>,
    me: MMe?,
    busy: Boolean,
    onContact: (Long) -> Unit,
    onMerge: () -> Unit,
    onIgnore: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val sources: Map<Long, MSource?> = remember(d) { d.details.associate { it.card.id to it.source } }
    GcCard(contentPadding = PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reasonText(d.reason), style = MaterialTheme.typography.labelMedium, color = extra.warn, modifier = Modifier.padding(horizontal = 14.dp))
        if (cards.size < 2) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
        } else {
            cards.forEach { c ->
                DuplicateCard(c, sources[c.id], me, own = MailContactLogic.ownValues(c, cards.filter { it.id != c.id }), onClick = { onContact(c.id) })
            }
            val preview: MPreview? = d.preview
            val result = preview?.card ?: MailContactLogic.localPreview(cards)
            if (result != null) {
                val photoCard = preview?.photoFrom?.let { p -> cards.firstOrNull { it.id == p } } ?: cards.firstOrNull { it.photo }
                PreviewCard(result, photoCard, preview)
            }
        }
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcPrimaryButton(stringResource(R.string.con_merge), onClick = onMerge, enabled = !busy && cards.size >= 2, modifier = Modifier.weight(1f))
            GcOutlineButton(stringResource(R.string.con_not_duplicate), onClick = onIgnore, enabled = !busy, modifier = Modifier.weight(1f))
        }
    }
}

/** A card of a case: photo (small), name, address book and account, its numbers, addresses and postal addresses; what only it has stands out. */
@Composable
private fun DuplicateCard(c: MCard, source: MSource?, me: MMe?, own: Set<String>, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ContactAvatar(c.label, c.emails.firstOrNull()?.value, c.id.takeIf { c.photo }, c.photoEtag, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(c.label.ifBlank { "–" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val origin = when {
                    source != null && source.local -> stringResource(R.string.con_source_gc) + " · " + source.addressbook
                    source != null -> listOf(MailContactLogic.originLabel(source), source.addressbook).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    c.source == "local" -> stringResource(R.string.con_source_gc)
                    else -> MailContactLogic.providerName(c.source, null).orEmpty()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceDots(me, listOfNotNull(source?.accountId))
                    if (origin.isNotBlank()) Text(origin, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val job = listOfNotNull(c.title, c.company).joinToString(" · ")
        if (job.isNotEmpty()) Line(job, false)
        c.phones.forEach { p -> Line(typeName(p.type) + " " + p.value, "p:" + MailPimLogic.phoneKey(p.value) in own) }
        c.emails.forEach { e -> Line(e.value, "e:" + e.value.trim().lowercase() in own) }
        c.addresses.forEach { a -> Line(addressLine(a), "a:" + MailContactLogic.addressKey(a) in own) }
        if (c.phones.isEmpty() && c.emails.isEmpty() && c.addresses.isEmpty()) Line(stringResource(R.string.dup_nothing), false)
    }
}

private fun addressLine(a: com.gatecontrol.android.mail.MCardAddress): String =
    listOf(a.street, listOf(a.postalCode, a.city).filter { it.isNotBlank() }.joinToString(" ")).filter { it.isNotBlank() }.joinToString(", ")

@Composable
private fun Line(text: String, own: Boolean) {
    val extra = GateControlTheme.extraColors
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (own) extra.accentText else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (own) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 46.dp),
    )
}

/** "So wird der Kontakt": the name, the main number, every number, address and postal address, what is left as a hint. */
@Composable
private fun PreviewCard(c: MCard, photoCard: MCard?, preview: MPreview?) {
    val extra = GateControlTheme.extraColors
    Column(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth().background(extra.accentBg.copy(alpha = 0.45f), MaterialTheme.shapes.small).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(stringResource(R.string.dup_result), style = MaterialTheme.typography.labelMedium, color = extra.accentText, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ContactAvatar(c.label, c.emails.firstOrNull()?.value, photoCard?.id?.takeIf { photoCard.photo }, photoCard?.photoEtag, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(c.label.ifBlank { "–" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val job = listOfNotNull(c.title, c.department, c.company).joinToString(" · ")
                if (job.isNotEmpty()) Text(job, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val main = c.mainPhone ?: MailContactLogic.mainPhoneOf(c.phones, null)
        c.phones.forEach { p ->
            val isMain = main != null && c.phones.size > 1 && MailPimLogic.phoneKey(p.value) == MailPimLogic.phoneKey(main.value)
            Line(typeName(p.type) + " " + p.value + if (isMain) " · " + stringResource(R.string.con_main_phone) else "", false)
        }
        c.emails.forEach { e -> Line(e.value, false) }
        c.addresses.forEach { a -> Line(addressLine(a), false) }
        c.birthday?.let { b -> MailPimLogic.birthdayParts(b)?.let { (m, d, y) -> Line(stringResource(R.string.con_birthday) + " " + "%02d.%02d.".format(d, m) + (y?.toString() ?: ""), false) } }
        preview?.losers?.forEach { l ->
            Text(
                stringResource(R.string.dup_loser, l.value, MailContactLogic.originLabel(l.source)),
                style = MaterialTheme.typography.bodySmall,
                color = extra.warn,
                modifier = Modifier.padding(start = 46.dp),
            )
        }
    }
}

@Composable
private fun reasonText(reason: String): String = stringResource(
    when (reason) {
        "email" -> R.string.con_reason_email
        "phone" -> R.string.con_reason_phone
        else -> R.string.con_reason_name
    },
)
