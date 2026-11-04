package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MBooking
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.mail.ErrorBox
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** What „Buchungsanfragen“ shows. */
data class BookingsState(
    /** open | done | mine */
    val tab: String = TAB_MINE,
    val items: List<MBooking>? = null,
    val loading: Boolean = true,
    val error: MResult<*>? = null,
    /** Bookings being decided or called off. */
    val busy: Set<Long> = emptySet(),
    /** A booking shown first (a push). */
    val focus: Long? = null,
    /** What happened, for a toast: a text key with its name, or a refusal. */
    val note: Pair<Int, String>? = null,
    val failure: MResult<*>? = null,
) {
    companion object {
        const val TAB_OPEN = "open"
        const val TAB_DONE = "done"
        const val TAB_MINE = "mine"
    }
}

/**
 * „Buchungsanfragen“ (mockup Anfragen, Mail 1.9.0): the open requests the person decides on, the decided ones and the
 * person's own bookings; Freigeben / Ablehnen / Trotzdem freigeben, Absagen. Never kept offline.
 */
@HiltViewModel
class BookingRequestsViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    val me: StateFlow<MMe?> = repository.me
    val open: StateFlow<Int?> = repository.pim.openRequests
    val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(BookingsState())
    val state: StateFlow<BookingsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** The tabs: without deciding on requests only „Meine Buchungen“. */
    fun tabs(me: MMe? = repository.me.value): List<String> =
        if (me?.has(MMe.FEATURE_BOOKING_REQUESTS) == true) listOf(BookingsState.TAB_OPEN, BookingsState.TAB_DONE, BookingsState.TAB_MINE) else listOf(BookingsState.TAB_MINE)

    init {
        val want = saved.get<String>(Screen.MailBookings.ARG_TAB)
        val tabs = tabs()
        val tab = want?.takeIf { it in tabs } ?: tabs.first()
        _state.update { it.copy(tab = tab, focus = saved.get<String>(Screen.MailBookings.ARG_FOCUS)?.toLongOrNull()) }
        load()
        viewModelScope.launch { repository.pim.resChanged.collect { load(quiet = true) } }
    }

    fun onShown() = load(quiet = true)

    fun setTab(tab: String) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab, items = null, focus = null) }
        load()
    }

    fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        val tab = _state.value.tab
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            when (val r = api.bookings(tab)) {
                is MResult.Ok -> {
                    if (tabs().contains(BookingsState.TAB_OPEN)) repository.pim.putOpenRequests(r.value.open)
                    _state.update { if (it.tab == tab) it.copy(items = r.value.items, loading = false, error = null) else it }
                }
                else -> _state.update { if (it.tab == tab) it.copy(loading = false, error = r) else it }
            }
        }
        // the count of „Offen · n“ while another tab is shown
        if (tab != BookingsState.TAB_OPEN && tabs().contains(BookingsState.TAB_OPEN)) repository.pim.refreshOpenRequestsSoon()
    }

    fun decide(b: MBooking, approve: Boolean, override: Boolean = false) {
        if (b.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + b.id) }
        viewModelScope.launch {
            val r = api.decideBooking(b.id, approve, override)
            _state.update { it.copy(busy = it.busy - b.id) }
            when {
                r is MResult.Ok -> _state.update { it.copy(note = (if (approve) R.string.res_approved else R.string.res_declined) to b.resourceName) }
                r is MResult.Failed && r.code == "already_decided" ->
                    _state.update { it.copy(note = R.string.res_already to (MailPimJson.decidedBy(r.error) ?: "")) }
                else -> _state.update { it.copy(failure = r) }
            }
            load(quiet = true)
        }
    }

    fun cancel(b: MBooking) {
        if (b.id in _state.value.busy) return
        _state.update { it.copy(busy = it.busy + b.id) }
        viewModelScope.launch {
            val r = api.cancelBooking(b.id)
            _state.update { it.copy(busy = it.busy - b.id) }
            if (r is MResult.Ok) _state.update { it.copy(note = R.string.res_cancelled to b.resourceName) } else _state.update { it.copy(failure = r) }
            load(quiet = true)
        }
    }

    fun clearNote() = _state.update { it.copy(note = null, failure = null) }
}

/** „Buchungsanfragen“ (mockup Anfragen): chips Offen · n / Erledigt / Meine Buchungen, a card per booking. */
@Composable
fun BookingRequestsScreen(onBack: () -> Unit, onEvent: (Long) -> Unit, viewModel: BookingRequestsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    var cancelling by remember { mutableStateOf<MBooking?>(null) }
    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.note, state.failure) {
        state.note?.let { (key, name) -> toast(context, if (key == R.string.res_already && name.isBlank()) context.getString(R.string.mail_err_already_decided) else context.getString(key, name)) }
        state.failure?.let { toast(context, planErrorText(context, it)) }
        if (state.note != null || state.failure != null) viewModel.clearNote()
    }
    val tabs = viewModel.tabs(me)
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(
            title = stringResource(if (tabs.size == 1) R.string.res_mine else R.string.res_requests),
            onBack = onBack,
            backDescription = stringResource(R.string.mail_back),
        )
        if (tabs.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEach { t ->
                    val label = when (t) {
                        BookingsState.TAB_OPEN -> stringResource(R.string.res_tab_open, open ?: state.items?.takeIf { state.tab == t }?.size ?: 0)
                        BookingsState.TAB_DONE -> stringResource(R.string.res_tab_done)
                        else -> stringResource(R.string.res_mine)
                    }
                    GcFilterChip(label, selected = state.tab == t, onClick = { viewModel.setTab(t) })
                }
            }
        }
        val list = state.items
        val listState = rememberLazyListState()
        LaunchedEffect(list, state.focus) {
            val f = state.focus ?: return@LaunchedEffect
            val i = list?.indexOfFirst { it.id == f } ?: -1
            if (i >= 0) listState.animateScrollToItem(i)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.loading && list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
                state.error != null && list == null -> item(key = "error") { ErrorBox(planErrorText(context, state.error!!), onRetry = { viewModel.load() }) }
                list != null -> {
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(
                                    when (state.tab) {
                                        BookingsState.TAB_OPEN -> R.string.res_empty_open
                                        BookingsState.TAB_DONE -> R.string.res_empty_done
                                        else -> R.string.res_empty_mine
                                    },
                                ),
                                color = extra.muted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(list, key = { "b${it.id}" }) { b ->
                        BookingCard(
                            b = b,
                            tab = state.tab,
                            zone = viewModel.zone,
                            busy = b.id in state.busy,
                            focus = b.id == state.focus,
                            onDecide = { approve, override -> viewModel.decide(b, approve, override) },
                            onCancel = { cancelling = b },
                            onEvent = b.eventId?.let { id -> { onEvent(id) } },
                        )
                    }
                }
            }
            if (tabs.size > 1) {
                item(key = "note") {
                    Text(stringResource(R.string.res_requests_note), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(2.dp))
                }
            }
        }
    }
    cancelling?.let { b ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text(stringResource(R.string.res_cancel_title, b.resourceName)) },
            text = { Text(stringResource(R.string.res_cancel_text)) },
            confirmButton = { TextButton(onClick = { cancelling = null; viewModel.cancel(b) }) { Text(stringResource(R.string.res_cancel), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}

/** The time of a booking: „Do 5.11. · 12:00 – 15:00“, all-day „Fr 6.11. – Sa 7.11. · ganztägig“. */
@Composable
internal fun bookingWhen(b: MBooking, zone: ZoneId): String {
    val locale = currentLocale()
    val fmt = DateTimeFormatter.ofPattern(if (locale.language == "de") "EEE d.M." else "EEE M/d", locale)
    if (b.allDay) {
        val a = MailPimLogic.date(b.startDate) ?: Instant.ofEpochMilli(b.start).atZone(zone).toLocalDate()
        val z = MailPimLogic.date(b.endDate) ?: a
        val days = if (a == z) a.format(fmt) else "${a.format(fmt)} – ${z.format(fmt)}"
        return days + " · " + stringResource(R.string.cal_all_day)
    }
    val day = Instant.ofEpochMilli(b.start).atZone(zone).toLocalDate()
    return day.format(fmt) + " · " + clock(b.start, zone, locale) + " – " + clock(b.end, zone, locale)
}

/** A booking (mockup Anfragen): resource, who and what for, its pill, the time, the day's bar, what is wrong, the answers. */
@Composable
private fun BookingCard(
    b: MBooking,
    tab: String,
    zone: ZoneId,
    busy: Boolean,
    focus: Boolean,
    onDecide: (approve: Boolean, override: Boolean) -> Unit,
    onCancel: () -> Unit,
    onEvent: (() -> Unit)?,
) {
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val pill = MailPlanLogic.pill(b)
    val (pillText, pillColor) = when (pill) {
        MailPlanLogic.PILL_WAITING -> stringResource(R.string.res_pill_waiting) to PlanColors.warn
        MailPlanLogic.PILL_CONFLICT -> stringResource(R.string.res_pill_conflict) to PlanColors.bad
        MailPlanLogic.PILL_CONFIRMED -> stringResource(R.string.res_pill_confirmed) to Color(0xFF46E0A0)
        MailPlanLogic.PILL_DECLINED -> stringResource(R.string.res_pill_declined) to PlanColors.bad
        else -> stringResource(R.string.res_pill_cancelled) to extra.muted
    }
    val pending = b.state == MBooking.PENDING
    val border = when {
        pending && pill == MailPlanLogic.PILL_CONFLICT -> PlanColors.bad.copy(alpha = 0.4f)
        pending -> PlanColors.warn.copy(alpha = 0.4f)
        focus -> PlanColors.chosen.copy(alpha = 0.45f)
        else -> extra.border
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KindIcon(b.resourceKind, b.resourceColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(b.resourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (tab == BookingsState.TAB_MINE) b.title else listOf(b.bookerName, b.title).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlanPill(pillText, pillColor)
        }
        Text(bookingWhen(b, zone), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (pending && !b.allDay) {
            val day = Instant.ofEpochMilli(b.start).atZone(zone).toLocalDate()
            val (from, to) = MailPlanLogic.dayWindow(day, null, b.start, b.end, zone)
            val blocks = MailPlanLogic.bookingBlocks(b)
            PlanBar(blocks, from, to, description = barDescription(context, blocks, from, to, zone), height = 18.dp)
        }
        val lines = buildList {
            if (b.violations.isNotEmpty()) add(stringResource(R.string.res_breaks, b.violations.joinToString(", ") { ruleText(context, it) }))
            if (b.conflicts.isNotEmpty()) add(stringResource(R.string.res_taken, b.conflicts.joinToString(", ") { "${clock(it.start, zone, locale)}–${clock(it.end, zone, locale)}" }))
            if (pending && isEmpty()) add(stringResource(R.string.res_free_ok))
            if (!pending && b.decidedBy != null) {
                val at = b.decidedAt?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)) }.orEmpty()
                add(stringResource(R.string.res_decided_by, b.decidedBy, at) + if (b.override) " · " + stringResource(R.string.res_override) else "")
            }
            b.comment?.let { add("„$it“") }
        }
        if (lines.isNotEmpty()) {
            Text(
                lines.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (pending && pill == MailPlanLogic.PILL_CONFLICT) PlanColors.bad else extra.muted,
            )
        }
        if (tab == BookingsState.TAB_OPEN && b.canDecide && pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcOutlineButton(stringResource(R.string.res_decline), onClick = { onDecide(false, false) }, enabled = !busy, modifier = Modifier.weight(1f))
                when (MailPlanLogic.approve(b)) {
                    MailPlanLogic.Approve.APPROVE -> GcPrimaryButton(stringResource(R.string.res_approve), onClick = { onDecide(true, false) }, loading = busy, modifier = Modifier.weight(1f))
                    MailPlanLogic.Approve.OVERRIDE -> GcOutlineButton(stringResource(R.string.res_approve_anyway), onClick = { onDecide(true, true) }, enabled = !busy, modifier = Modifier.weight(1f))
                    MailPlanLogic.Approve.NONE -> Unit
                }
            }
        }
        if (tab == BookingsState.TAB_MINE && (MailPlanLogic.canCancel(b, System.currentTimeMillis()) || onEvent != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onEvent != null) GcOutlineButton(stringResource(R.string.res_open_event), onClick = onEvent, modifier = Modifier.weight(1f))
                if (MailPlanLogic.canCancel(b, System.currentTimeMillis())) {
                    GcOutlineButton(stringResource(R.string.res_cancel), onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
