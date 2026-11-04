package com.gatecontrol.android.ui.mail.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MResource
import com.gatecontrol.android.mail.MResult
import com.gatecontrol.android.mail.MBooking
import com.gatecontrol.android.mail.MailPimJson
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.mail.MailPlanLogic
import com.gatecontrol.android.mail.MailRepository
import com.gatecontrol.android.mail.MResChanged
import com.gatecontrol.android.navigation.Screen
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** What „Ressource buchen“ shows. */
data class ResourceState(
    val day: LocalDate = LocalDate.now(),
    val from: LocalTime = LocalTime.of(9, 0),
    val to: LocalTime = LocalTime.of(10, 0),
    val title: String = "",
    /** `all` or a kind of [MResource.KINDS]. */
    val kind: String = ALL,
    val selected: Long? = null,
    val list: List<MResource>? = null,
    val loading: Boolean = true,
    val error: MResult<*>? = null,
    val booking: Boolean = false,
    /** Booked or asked for: the booking (its state says which). */
    val booked: Pair<MResource, MBooking?>? = null,
    /** A refusal to show (a toast). */
    val failure: MResult<*>? = null,
) {
    companion object {
        const val ALL = "all"
    }
}

/**
 * „Ressource buchen“ (mockup Ressource, Mail 1.9.0): the resources the person may book with their day, the time and
 * what for; booked at once or asked for at the approvers. Never kept offline.
 */
@HiltViewModel
class ResourceBookingViewModel @Inject constructor(
    private val repository: MailRepository,
    saved: SavedStateHandle,
) : ViewModel() {

    private val api = repository.api
    val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(ResourceState())
    val state: StateFlow<ResourceState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        val start = saved.get<String>(Screen.MailResources.ARG_START)?.toLongOrNull()
        val end = saved.get<String>(Screen.MailResources.ARG_END)?.toLongOrNull()
        val (a, b) = if (start != null) start to (end?.takeIf { it > start } ?: (start + 3_600_000L)) else MailPimLogic.nextHour(System.currentTimeMillis(), zone)
        val s = MailPimLogic.local(a, zone)
        val e = MailPimLogic.local(b, zone)
        _state.update { it.copy(day = s.toLocalDate(), from = s.toLocalTime(), to = if (e.toLocalDate() == s.toLocalDate()) e.toLocalTime() else LocalTime.of(23, 59)) }
        load()
        viewModelScope.launch {
            repository.pim.resChanged.collect { c -> if (c.kind == MResChanged.RESOURCES || c.kind == MResChanged.BOOKING || c.kind == MResChanged.REQUEST) load(quiet = true) }
        }
    }

    fun load(quiet: Boolean = false) {
        loadJob?.cancel()
        val day = _state.value.day
        if (!quiet) _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val (from, to) = MailPimLogic.range(day, day, zone)
            when (val r = api.resources(from, to)) {
                is MResult.Ok -> _state.update { s ->
                    s.copy(list = r.value.items, loading = false, error = null, selected = s.selected?.takeIf { id -> r.value.items.any { it.id == id } })
                }
                else -> _state.update { it.copy(loading = false, error = r) }
            }
        }
    }

    fun setDay(day: LocalDate) {
        if (day == _state.value.day) return
        _state.update { it.copy(day = day) }
        load()
    }

    /** The start moves: the end keeps the length (within the day). */
    fun setFrom(t: LocalTime) = _state.update { s ->
        val len = java.time.Duration.between(s.from, s.to).toMinutes().coerceAtLeast(15)
        val end = t.plusMinutes(len).takeIf { it.isAfter(t) } ?: LocalTime.of(23, 59)
        s.copy(from = t, to = end)
    }

    fun setTo(t: LocalTime) = _state.update { it.copy(to = t) }

    fun setTitle(v: String) = _state.update { it.copy(title = v.take(255)) }

    fun setKind(k: String) = _state.update { it.copy(kind = k) }

    fun select(id: Long) = _state.update { it.copy(selected = if (it.selected == id) null else id) }

    /** The chosen time in ms (start, end). */
    fun span(s: ResourceState = _state.value): Pair<Long, Long> = MailPimLogic.ms(s.day, s.from, zone) to MailPimLogic.ms(s.day, s.to, zone)

    fun book(r: MResource) {
        val s = _state.value
        if (s.booking) return
        val (start, end) = span(s)
        if (end <= start) return
        _state.update { it.copy(booking = true) }
        viewModelScope.launch {
            when (val x = api.book(r.id, MailPimJson.bookBody(s.title.trim().ifEmpty { null }, start, end, timeZone = zone.id))) {
                is MResult.Ok -> {
                    _state.update { it.copy(booking = false, booked = r to x.value.bookings.firstOrNull { b -> b.resourceId == r.id }) }
                }
                else -> _state.update { it.copy(booking = false, failure = x) }
            }
        }
    }

    fun clearFailure() = _state.update { it.copy(failure = null) }
}

/** „Ressource buchen“ (mockup Ressource): chips of the kinds, the day and time, what for, a card per resource. */
@Composable
fun ResourceBookingScreen(onBack: () -> Unit, viewModel: ResourceBookingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val zone = viewModel.zone
    LaunchedEffect(state.booked) {
        val (r, b) = state.booked ?: return@LaunchedEffect
        toast(context, context.getString(if (b?.state == MBooking.PENDING) R.string.res_requested else R.string.res_booked, r.name))
        onBack()
    }
    LaunchedEffect(state.failure) {
        state.failure?.let {
            toast(context, planErrorText(context, it))
            viewModel.clearFailure()
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        GcSubpageBar(title = stringResource(R.string.res_title), onBack = onBack, backDescription = stringResource(R.string.mail_back))
        val list = state.list.orEmpty()
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf(ResourceState.ALL) + MailPlanLogic.kindChips(list)).forEach { k ->
                GcFilterChip(kindText(k), selected = state.kind == k, onClick = { viewModel.setKind(k) })
            }
        }
        val (start, end) = viewModel.span(state)
        val (wFrom, wTo) = MailPlanLogic.dayWindow(state.day, null, start, end, zone)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "when") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DayChip(state.day, locale, viewModel::setDay)
                        Box(Modifier.weight(1f))
                        TimeChip(state.from, stringResource(R.string.res_from), locale, viewModel::setFrom)
                        Text("–", color = extra.muted)
                        TimeChip(state.to, stringResource(R.string.res_to), locale, viewModel::setTo)
                    }
                    if (end <= start) Text(stringResource(R.string.cal_end_before), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.fb_hours, clock(wFrom, zone, locale), clock(wTo, zone, locale)),
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                    GcTextField(state.title, viewModel::setTitle, stringResource(R.string.res_what), placeholder = stringResource(R.string.res_title_ph))
                }
            }
            when {
                state.loading && state.list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
                }
                state.error != null && state.list == null -> item(key = "error") { ErrorBox(planErrorText(context, state.error!!), onRetry = { viewModel.load() }) }
                else -> {
                    val shown = list.filter { state.kind == ResourceState.ALL || it.kind == state.kind }
                    if (shown.isEmpty()) item(key = "none") { Text(stringResource(R.string.res_none), color = extra.muted, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(shown, key = { "r${it.id}" }) { r ->
                        ResourceCard(
                            r = r,
                            on = state.selected == r.id,
                            from = wFrom,
                            to = wTo,
                            start = start,
                            end = end,
                            zone = zone,
                            title = state.title,
                            busy = state.booking,
                            onSelect = { viewModel.select(r.id) },
                            onBook = { viewModel.book(r) },
                        )
                    }
                    item(key = "legend") { PlanLegend(listOf(MailPlanLogic.FREE, MailPlanLogic.BUSY, MailPlanLogic.TENTATIVE, "sel")) }
                }
            }
        }
    }
}

@Composable
private fun kindText(kind: String): String = stringResource(
    when (kind) {
        MResource.ROOM -> R.string.res_kind_room
        MResource.VEHICLE -> R.string.res_kind_vehicle
        MResource.DEVICE -> R.string.res_kind_device
        MResource.OTHER -> R.string.res_kind_other
        else -> R.string.res_kind_all
    },
)

/** One resource (mockup): its symbol, name and line, the day as a bar with the chosen time, „Buchen“ when chosen. */
@Composable
private fun ResourceCard(
    r: MResource,
    on: Boolean,
    from: Long,
    to: Long,
    start: Long,
    end: Long,
    zone: ZoneId,
    title: String,
    busy: Boolean,
    onSelect: () -> Unit,
    onBook: () -> Unit,
) {
    val context = mailContext()
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (on) PlanColors.chosen.copy(alpha = 0.45f) else extra.border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val chosen = stringResource(R.string.fb_legend_sel)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onSelect)
                .semantics { if (on) stateDescription = chosen },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KindIcon(r.kind, r.color)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(resourceSub(context, r), style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        PlanBar(
            blocks = r.busy,
            from = from,
            to = to,
            description = r.name + ": " + barDescription(context, r.busy, from, to, zone),
            filled = if (on && end > start) start to end else null,
            height = 28.dp,
        )
        PlanScale(from, to, zone)
        if (on) {
            val what = title.trim()
            Text(
                stringResource(R.string.res_chosen, clock(start, zone, locale), clock(end, zone, locale)) + if (what.isNotEmpty()) " · $what" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            GcPrimaryButton(
                if (r.needsApproval) stringResource(R.string.res_request_to, approversText(context, r)) else stringResource(R.string.res_book_now),
                onClick = onBook,
                enabled = end > start,
                loading = busy,
            )
        }
    }
}

/** The day („Do 5.11.“) opening the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayChip(day: LocalDate, locale: java.util.Locale, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerChip(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), stringResource(R.string.res_day_field) + ": " + dayLong(day, locale)) { open = true }
    if (open) {
        val st = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    st.selectedDateMillis?.let { ms -> onPick(java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text(stringResource(R.string.mail_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.mail_cancel)) } },
        ) { DatePicker(st) }
    }
}

/** A time („12:00“) opening the time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeChip(time: LocalTime, label: String, locale: java.util.Locale, onPick: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val text = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    PickerChip(text, "$label: $text") { open = true }
    if (open) {
        val st = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = locale.language != "en")
        AlertDialog(
            onDismissRequest = { open = false },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { open = false; onPick(LocalTime.of(st.hour, st.minute)) }) { Text(stringResource(R.string.mail_ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.mail_cancel)) } },
        )
    }
}
