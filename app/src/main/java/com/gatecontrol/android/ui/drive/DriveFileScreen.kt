package com.gatecontrol.android.ui.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DDeadline
import com.gatecontrol.android.drive.DDeadlineCalendars
import com.gatecontrol.android.drive.DDeadlineTarget
import com.gatecontrol.android.drive.DDeadlineTargetRow
import com.gatecontrol.android.drive.DInsight
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DOffice
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveDeadlineTargets
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.DriveKi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import javax.inject.Inject

// ─── the file's page: details, AI for documents (Drive 1.8), office (Drive 1.9) ──

/** One file: what it is, open / send / in the browser, and (Drive 1.8 `docAi`) what the AI recognised and its deadlines. */
@HiltViewModel
class DriveFileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val transfers: DriveTransfers,
    val live: DriveLive,
) : ViewModel() {

    private val nodeId: Long = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L

    data class Info(val node: DNode, val writable: Boolean, val areaLabel: String?)

    private val _state = MutableStateFlow<DriveLoad<Info>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<Info>> = _state.asStateFlow()

    /** null: no AI for documents (the server has none, a folder or a vault file). */
    private val _insight = MutableStateFlow<DriveLoad<DInsight>?>(null)
    val insight: StateFlow<DriveLoad<DInsight>?> = _insight.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
        // a finished recognition sends `changed`: the page reads again
        viewModelScope.launch {
            live.changed.collect { e ->
                val here = (_state.value as? DriveLoad.Ready)?.value?.node ?: return@collect
                if (DriveLogic.affects(e, here.areaId) && (_insight.value as? DriveLoad.Ready)?.value?.status != "ready") refresh(quiet = true)
            }
        }
    }

    fun clearMessage() { _message.value = null }

    fun refresh(quiet: Boolean = false) {
        if (nodeId <= 0) {
            _state.value = DriveLoad.Error("not_found")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val r = api.node(nodeId)
            if (r !is DResult.Ok) {
                _state.value = r.toLoad { error("unreachable") }
                return@launch
            }
            val node = r.value
            val area = api.areas().valueOrNull()?.firstOrNull { it.id == node.areaId }
            _state.value = DriveLoad.Ready(Info(node, area?.writable == true, area?.label))
            val me = live.current()
            if (me?.has(DMe.FEATURE_DOC_AI) == true && !node.isDir && !node.encrypted) {
                if (!quiet || _insight.value !is DriveLoad.Ready) _insight.value = DriveLoad.Loading
                _insight.value = api.insight(node.id).toLoad { it }
            } else {
                _insight.value = null
            }
        }
    }

    private fun failure(r: DResult<*>): String? = when (r) {
        is DResult.Failed -> if (r.code == "unsupported") DriveJson.reason(r.body) ?: r.code else r.code
        else -> r.errorCode()
    }

    /** Takes the AI's suggestion: the new [name] and / or the [folder]. */
    fun apply(name: Boolean, folder: Boolean) {
        val info = (_state.value as? DriveLoad.Ready)?.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val r = api.insightApply(info.node.id, name, folder)
            _busy.value = false
            _message.value = failure(r) ?: "done_insight"
            if (r is DResult.Ok) {
                _state.update { s -> (s as? DriveLoad.Ready)?.let { DriveLoad.Ready(it.value.copy(node = r.value.node)) } ?: s }
                r.value.insight?.let { _insight.value = DriveLoad.Ready(it) } ?: refresh(quiet = true)
            }
        }
    }

    /** "Erneut erkennen". */
    fun recognise() {
        val info = (_state.value as? DriveLoad.Ready)?.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val r = api.insightAgain(info.node.id)
            _busy.value = false
            if (r is DResult.Ok) {
                _insight.value = DriveLoad.Ready(r.value)
                _message.value = "done_insight_again"
            } else {
                _message.value = failure(r)
            }
        }
    }

    /** My reminder of [d], [days] before (null: none). */
    fun remind(d: DDeadline, days: Int?) {
        viewModelScope.launch {
            val r = if (days == null) api.unremind(d.id) else api.remind(d.id, days)
            if (r is DResult.Ok) {
                _insight.update { l ->
                    (l as? DriveLoad.Ready)?.let { ready ->
                        DriveLoad.Ready(ready.value.copy(deadlines = ready.value.deadlines.map { if (it.id == d.id) r.value.copy(node = it.node) else it }))
                    } ?: l
                }
            } else {
                _message.value = r.errorCode()
            }
        }
    }

    suspend fun fetch(node: DNode) = transfers.fetch(node)

    suspend fun office(node: DNode): DResult<DOffice> = api.office(node.id)
}

/** The page of one file (from a long press "Details", the deadlines, a deadline push). */
@Composable
fun DriveFileScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveFileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val insight by viewModel.insight.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val downloading by viewModel.transfers.downloading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        if (shown) viewModel.refresh(quiet = true)
        shown = true
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val info = (state as? DriveLoad.Ready)?.value
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(info?.let { nodeTitle(it.node) } ?: stringResource(R.string.drive_details), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                is DriveLoad.Ready -> {
                    val node = s.value.node
                    FileHeader(node, s.value.areaLabel, downloading[node.id], onFolder = node.parentId?.let { p -> { onFolder(p) } })
                    if (!node.isDir && !node.encrypted) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GcPrimaryButton(stringResource(R.string.drive_open_file), icon = GcIcons.External, modifier = Modifier.weight(1f), onClick = {
                                scope.launch { fetchThen(context, node, { viewModel.fetch(it) }, then = { f -> openFile(context, node, f) }) { Toast.makeText(context, driveText(context, it), Toast.LENGTH_LONG).show() } }
                            })
                            GcOutlineButton(stringResource(R.string.drive_send), icon = GcIcons.Share, modifier = Modifier.weight(1f), onClick = {
                                scope.launch { fetchThen(context, node, { viewModel.fetch(it) }, then = { f -> sendFile(context, node, f) }) { Toast.makeText(context, driveText(context, it), Toast.LENGTH_LONG).show() } }
                            })
                        }
                        if (DriveLogic.officeAllowed(me, node)) {
                            GcOutlineButton(
                                stringResource(if (s.value.writable) R.string.drive_office_edit else R.string.drive_office_view),
                                icon = GcIcons.Globe,
                                onClick = { scope.launch { openOffice(context, viewModel.office(node)) } },
                            )
                        }
                    }
                    insight?.let { l ->
                        InsightSection(
                            load = l,
                            writable = s.value.writable,
                            busy = busy,
                            onApply = viewModel::apply,
                            onRecognise = viewModel::recognise,
                            onRemind = viewModel::remind,
                            onRetry = { viewModel.refresh() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHeader(node: DNode, areaLabel: String?, progress: Float?, onFolder: (() -> Unit)?) {
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        NodeRow(node, onClick = null, progress = progress)
        if (!node.isDir) {
            GcRowDivider()
            GcListRow(title = stringResource(R.string.drive_file_size), trailing = { Text(DriveLogic.size(node.size), color = GateControlTheme.extraColors.muted) })
        }
        val changed = shortDateTime(node.mtime ?: node.updatedAt)
        if (changed != null) {
            GcRowDivider()
            GcListRow(title = stringResource(R.string.drive_file_changed), trailing = { Text(changed, color = GateControlTheme.extraColors.muted) })
        }
        if (onFolder != null) {
            GcRowDivider()
            GcListRow(
                title = stringResource(R.string.drive_file_folder),
                description = node.path ?: areaLabel,
                onClick = onFolder,
                leading = { Icon(GcIcons.Folder, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                trailing = { GcChevron() },
            )
        }
    }
}

/** "Erkannt": kind, title, date, sender, amount; the suggestion; "Erneut erkennen"; the deadlines with their reminder. */
@Composable
private fun InsightSection(
    load: DriveLoad<DInsight>,
    writable: Boolean,
    busy: Boolean,
    onApply: (name: Boolean, folder: Boolean) -> Unit,
    onRecognise: () -> Unit,
    onRemind: (DDeadline, Int?) -> Unit,
    onRetry: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    when (load) {
        DriveLoad.Loading -> DriveLoading()
        is DriveLoad.Error -> DriveErrorBox(load.code, onRetry = onRetry)
        is DriveLoad.Ready -> {
            val i = load.value
            if (i.status != "off") {
                GcSectionLabel(stringResource(R.string.drive_insight_title), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    when (i.status) {
                        "ready" -> {
                            val rows = buildList {
                                add(stringResource(R.string.drive_insight_kind) to stringResource(docKindLabel(i.kind)))
                                i.title?.let { add(stringResource(R.string.drive_insight_doc_title) to it) }
                                i.date?.let { add(stringResource(R.string.drive_insight_date) to (shortDay(it) ?: it)) }
                                i.sender?.let { add(stringResource(R.string.drive_insight_sender) to it) }
                                i.amount?.let { add(stringResource(R.string.drive_insight_amount) to money(it, i.currency)) }
                            }
                            rows.forEachIndexed { n, (label, value) ->
                                if (n > 0) GcRowDivider()
                                GcListRow(
                                    title = value,
                                    description = label,
                                    leading = if (n == 0) ({ Icon(docKindIcon(i.kind), contentDescription = null, tint = x.accentText) }) else null,
                                )
                            }
                        }
                        "pending" -> GcListRow(title = stringResource(R.string.drive_insight_pending), leading = { Icon(GcIcons.Clock, contentDescription = null, tint = x.muted) })
                        "failed" -> GcListRow(
                            title = stringResource(R.string.drive_insight_failed, i.error ?: "?"),
                            leading = { Icon(GcIcons.Alert, contentDescription = null, tint = x.warn) },
                        )
                        else -> GcListRow(title = stringResource(R.string.drive_insight_none), leading = { Icon(GcIcons.Search, contentDescription = null, tint = x.muted) })
                    }
                }
                val sug = i.suggestion
                if (i.status == "ready" && sug != null && writable) {
                    GcCard {
                        Text(stringResource(R.string.drive_insight_suggestion), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        sug.name?.let { name ->
                            SuggestionRow(stringResource(R.string.drive_insight_rename, name), enabled = !busy) { onApply(true, false) }
                        }
                        if (sug.folderId != null) {
                            val path = sug.folderPath?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.drive_insight_top)
                            SuggestionRow(stringResource(R.string.drive_insight_move, path), enabled = !busy) { onApply(false, true) }
                        }
                        if (sug.name != null && sug.folderId != null) {
                            GcPrimaryButton(stringResource(R.string.drive_insight_apply_both), onClick = { onApply(true, true) }, enabled = !busy, loading = busy, icon = GcIcons.Check)
                        }
                    }
                }
                if (writable && i.status != "pending") {
                    GcOutlineButton(
                        stringResource(if (i.status == "none") R.string.drive_insight_start else R.string.drive_insight_again),
                        onClick = onRecognise,
                        enabled = !busy,
                        icon = GcIcons.Refresh,
                    )
                }
            }
            if (i.deadlines.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.drive_deadlines), modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    i.deadlines.forEachIndexed { n, d ->
                        if (n > 0) GcRowDivider()
                        DeadlineReminderRow(d, onRemind)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, enabled: Boolean, onApply: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onApply, enabled = enabled) { Text(stringResource(R.string.drive_insight_apply)) }
    }
}

/** A deadline: kind, date, label — and the switch "Erinnern N Tage vorher" (N can be chosen). */
@Composable
internal fun DeadlineReminderRow(d: DDeadline, onRemind: (DDeadline, Int?) -> Unit) {
    val x = GateControlTheme.extraColors
    val today = remember { LocalDate.now() }
    val left = DriveLogic.daysUntil(d.date, today)
    val open = DriveLogic.canRemind(d, today)
    var days by remember(d.id, d.reminder?.daysBefore) { mutableStateOf(d.reminder?.daysBefore ?: DriveLogic.REMIND_DEFAULT) }
    var pick by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        GcListRow(
            title = listOfNotNull(stringResource(deadlineLabel(d.kind)), shortDay(d.date) ?: d.date).joinToString(" · "),
            description = listOfNotNull(d.label, deadlineWhen(left)).joinToString(" · ").ifEmpty { null },
            leading = { Icon(GcIcons.Calendar, contentDescription = null, tint = if (open) x.accentText else x.muted) },
        )
        if (open) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(GcIcons.Bell, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        remindText(days),
                        style = MaterialTheme.typography.bodyMedium,
                        color = x.accentText,
                        modifier = Modifier.clickable(role = Role.Button) { pick = true }.padding(vertical = 6.dp),
                    )
                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                        DriveLogic.REMIND_DAYS.forEach { n ->
                            DropdownMenuItem(
                                text = { Text(remindText(n)) },
                                leadingIcon = { if (n == days) Icon(GcIcons.Check, contentDescription = null) },
                                onClick = {
                                    pick = false
                                    days = n
                                    if (d.reminder != null) onRemind(d, n)
                                },
                            )
                        }
                    }
                }
                GcSwitch(checked = d.reminder != null, onCheckedChange = { on -> onRemind(d, if (on) days else null) })
            }
        } else if (d.reminder?.sent == true) {
            Text(stringResource(R.string.drive_deadline_sent), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        }
    }
}

@Composable
internal fun remindText(days: Int): String =
    if (days == 0) stringResource(R.string.drive_deadline_remind_day0) else pluralStringResource(R.plurals.drive_deadline_remind_days, days, days)

@Composable
internal fun deadlineWhen(left: Long?): String? = when {
    left == null -> null
    left < 0 -> stringResource(R.string.drive_deadline_past)
    left == 0L -> stringResource(R.string.drive_deadline_today)
    else -> pluralStringResource(R.plurals.drive_deadline_in_days, left.toInt(), left.toInt())
}

internal fun deadlineLabel(kind: String): Int = when (kind) {
    "payment" -> R.string.drive_deadline_payment
    "cancellation" -> R.string.drive_deadline_cancellation
    "warranty" -> R.string.drive_ki_deadline_warranty
    "renewal" -> R.string.drive_ki_deadline_renewal
    else -> R.string.drive_deadline_objection
}

/** `2026-03-14` → "14.03.2026" / "Mar 14, 2026". */
internal fun shortDay(date: String?): String? = date?.let {
    runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.parse(it)) }.getOrNull()
}

private fun shortDateTime(iso: String?): String? = iso?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(it))
    }.getOrNull()
}

private fun money(amount: Double, currency: String?): String = runCatching {
    NumberFormat.getCurrencyInstance().apply { if (currency != null) this.currency = Currency.getInstance(currency) }.format(amount)
}.getOrElse { listOfNotNull(String.format(java.util.Locale.getDefault(), "%.2f", amount), currency).joinToString(" ") }

/** Opens the office's address (Drive 1.9) in a Custom Tab; a refusal becomes a toast. */
internal fun openOffice(context: Context, r: DResult<DOffice>) {
    when (r) {
        is DResult.Ok -> openInBrowser(context, r.value.url)
        else -> Toast.makeText(context, driveText(context, r.errorCode() ?: "failed"), Toast.LENGTH_LONG).show()
    }
}

/** An address in a Custom Tab (the in-app browser), else the default browser. */
internal fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    val ok = runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }.isSuccess ||
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!ok) Toast.makeText(context, R.string.drive_portal_unavailable, Toast.LENGTH_LONG).show()
}

// ─── "Fristen": the deadlines of all my documents (Drive 1.8; as cards with the family calendar, Drive 1.14) ──

@HiltViewModel
class DriveDeadlinesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val api: DriveApi,
    val live: DriveLive,
) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<List<DDeadline>>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<List<DDeadline>>> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Deadlines not there at the last visit ("neu", Drive 1.14). */
    private val _fresh = MutableStateFlow<Set<Long>>(emptySet())
    val fresh: StateFlow<Set<Long>> = _fresh.asStateFlow()

    /** The deadline going into the family calendar right now. */
    private val _calendarBusy = MutableStateFlow<Long?>(null)
    val calendarBusy: StateFlow<Long?> = _calendarBusy.asStateFlow()

    /** Drive 1.17 (`deadlineMailCalendar`): the calendars a deadline can go to, and my target per area (the preselection). */
    private val _calendars = MutableStateFlow(DDeadlineCalendars.NONE)
    val calendars: StateFlow<DDeadlineCalendars> = _calendars.asStateFlow()
    private val _targets = MutableStateFlow<List<DDeadlineTargetRow>>(emptyList())
    val targets: StateFlow<List<DDeadlineTargetRow>> = _targets.asStateFlow()

    private var seen: Set<Long>? = DriveViewPref.deadlinesSeen(context)

    init {
        refresh()
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    fun clearMessage() { _message.value = null }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet || _state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val r = api.deadlines().toLoad { list -> list.filter { it.node?.encrypted != true } }
            if (r is DriveLoad.Ready) {
                val ids = r.value.map { it.id }
                // "neu" stays for this visit; the next one compares with what is here now
                _fresh.value = _fresh.value + DriveKi.fresh(ids, seen)
                DriveViewPref.setDeadlinesSeen(context, ids)
            }
            _state.value = r
        }
        viewModelScope.launch {
            val me = live.me.value ?: live.current()
            if (me?.has(DMe.FEATURE_DEADLINE_MAIL_CALENDAR) != true) return@launch
            api.deadlineCalendars().valueOrNull()?.let { _calendars.value = it }
            api.deadlineTargets().valueOrNull()?.let { _targets.value = it }
        }
    }

    fun remind(d: DDeadline, days: Int?) {
        viewModelScope.launch {
            val r = if (days == null) api.unremind(d.id) else api.remind(d.id, days)
            if (r is DResult.Ok) took(d, r.value) else _message.value = r.errorCode()
        }
    }

    /**
     * "In den Familienkalender" (Drive 1.14, feature `deadlineCalendar`); Drive 1.17 (`deadlineMailCalendar`): "In
     * Kalender eintragen" into [target], a mail calendar or the family calendar.
     */
    fun calendar(d: DDeadline, target: DDeadlineTarget? = null) {
        if (_calendarBusy.value != null) return
        viewModelScope.launch {
            _calendarBusy.value = d.id
            val r = if (target == null) api.deadlineToCalendar(d.id) else api.deadlineToCalendar(d.id, target)
            _calendarBusy.value = null
            if (r is DResult.Ok) {
                took(d, r.value)
                _message.value = if (target?.mail == true) "done_calendar_any" else "done_calendar"
            } else {
                _message.value = r.errorCode()
            }
        }
    }

    private fun took(d: DDeadline, now: DDeadline) {
        _state.update { s -> (s as? DriveLoad.Ready)?.let { DriveLoad.Ready(it.value.map { x -> if (x.id == d.id) now.copy(node = now.node ?: x.node) else x }) } ?: s }
    }
}

@Composable
fun DriveDeadlinesScreen(onBack: () -> Unit, onFile: (Long) -> Unit, viewModel: DriveDeadlinesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val fresh by viewModel.fresh.collectAsStateWithLifecycle()
    val busy by viewModel.calendarBusy.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val calendar = me?.has(DMe.FEATURE_DEADLINE_CALENDAR) == true
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val rows by viewModel.targets.collectAsStateWithLifecycle()
    // Drive 1.17: the family calendar and / or the mail calendars; more than one: "In Kalender eintragen" asks
    val targets = DriveDeadlineTargets.targets(calendars, mailFeature = me?.has(DMe.FEATURE_DEADLINE_MAIL_CALENDAR) == true)
    var pick by remember { mutableStateOf<DDeadline?>(null) }
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        if (shown) viewModel.refresh(quiet = true)
        shown = true
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_deadlines), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.drive_ki_deadlines_desc), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    GcBanner(tone = GcTone.Neutral, icon = GcIcons.Calendar) { Text(stringResource(R.string.drive_deadlines_none), style = MaterialTheme.typography.bodyMedium) }
                } else {
                    // by month: "März 2026"
                    s.value.sortedBy { it.date }.groupBy { it.date.take(7) }.forEach { (month, list) ->
                        GcSectionLabel(monthTitle(month), modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
                        list.forEach { d ->
                            DeadlineCard(
                                d = d,
                                fresh = d.id in fresh,
                                calendar = calendar,
                                calendarBusy = busy == d.id,
                                onCalendar = {
                                    if (DriveDeadlineTargets.ask(targets)) pick = d else viewModel.calendar(d, targets.singleOrNull())
                                },
                                onRemind = viewModel::remind,
                                onFile = d.node?.let { n -> { onFile(n.id) } },
                                calendarLabel = stringResource(
                                    if (targets.any { it.mail }) R.string.drive_dl_calendar_add else R.string.drive_ki_calendar_add,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
    pick?.let { d ->
        DeadlineTargetDialog(
            title = stringResource(R.string.drive_dl_calendar_pick),
            groups = DriveDeadlineTargets.groups(calendars, mailFeature = true, off = false),
            selected = DriveDeadlineTargets.preselect(rows, d.node?.areaId, targets),
            onSelect = { t -> pick = null; viewModel.calendar(d, t) },
            onDismiss = { pick = null },
            confirm = stringResource(R.string.drive_dl_calendar_go),
        )
    }
}

private fun monthTitle(month: String): String = runCatching {
    DateTimeFormatter.ofPattern("LLLL yyyy").format(java.time.YearMonth.parse(month))
}.getOrDefault(month)
