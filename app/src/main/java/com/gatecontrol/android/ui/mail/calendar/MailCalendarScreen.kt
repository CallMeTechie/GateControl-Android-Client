package com.gatecontrol.android.ui.mail.calendar

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MAccount
import com.gatecontrol.android.mail.MCalPrefs
import com.gatecontrol.android.mail.MCalendars
import com.gatecontrol.android.mail.MEvents
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MPimSide
import com.gatecontrol.android.mail.MailMicrosoft
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.start.LocalInStartPager
import com.gatecontrol.android.ui.start.StartPageMenuButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.update.currentLocale
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Where the calendar leads. */
data class CalendarNav(
    val menu: () -> Unit,
    /** An occurrence (its event, the occurrence of a series). */
    val event: (eventId: Long, occurrence: String?) -> Unit,
    /** "Neuer Termin": with the tapped time, else the next hour. */
    val newEvent: (start: Long?, end: Long?, allDay: Boolean) -> Unit,
    /** "Neu anmelden" of a Microsoft account (calendar and contacts rights). */
    val relogin: (accountId: Long) -> Unit,
)

/** The grid's height of an hour. */
private val HOUR: Dp = 56.dp
private val HOURS_COL: Dp = 40.dp

/**
 * The calendar area of the Mail tab (mockup "Kalender"): the bar with the menu, month, "Heute", ‹ › and the view,
 * the chips of the calendars (a tap shows or hides one), the bands (sign in again, offline), the view and "Neuer
 * Termin" below.
 */
@Composable
fun MailCalendarContent(nav: CalendarNav, viewModel: MailCalendarViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val cals by viewModel.calendars.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val relogins by viewModel.relogins.collectAsStateWithLifecycle()
    val context = mailContext()
    val zone = viewModel.zone
    val locale = currentLocale()
    val today = LocalDate.now(zone)
    val view = state.view ?: MCalPrefs.VIEW_3DAY

    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            toast(context, mailText(context, it))
            viewModel.clearMessage()
        }
    }

    val slots = remember(state.events, cals) { slotsOf(state.events, cals) }
    Column(Modifier.fillMaxSize()) {
        CalendarBar(
            title = monthTitle(if (view == MCalPrefs.VIEW_MONTH) state.anchor else viewModel.shownDays(state).firstOrNull() ?: state.anchor, today, locale),
            view = view,
            onMenu = nav.menu,
            onToday = viewModel::today,
            onStep = viewModel::step,
            onView = viewModel::setView,
        )
        CalendarChips(cals, onCalendar = { id -> cals?.calendar(id)?.let(viewModel::toggleCalendar) }, onOverlay = viewModel::toggleOverlay)
        CalendarBands(me, cals, offline, relogins, onRelogin = { id ->
            viewModel.relogin(id)
            nav.relogin(id)
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val swipe = if (LocalInStartPager.current || view == MCalPrefs.VIEW_LIST) Modifier else Modifier.swipeToPage(viewModel::step)
            when (view) {
                MCalPrefs.VIEW_MONTH -> MonthView(state.anchor, today, cals?.prefs?.weekStart ?: 1, slots, zone, swipe, onDay = viewModel::openDay)
                MCalPrefs.VIEW_LIST -> AgendaView(state.anchor, state.listDays, today, slots, zone, onSlot = { s -> s.occurrence?.let { nav.event(it.eventId, it.occurrence) } }, onEnd = viewModel::moreList)
                else -> TimeGrid(
                    days = viewModel.shownDays(state),
                    today = today,
                    slots = slots,
                    zone = zone,
                    modifier = swipe,
                    onSlot = { s -> s.occurrence?.let { nav.event(it.eventId, it.occurrence) } },
                    onEmpty = { day, minute ->
                        if (!offline) {
                            val (a, b) = MailPimLogic.newAt(day, minute, zone)
                            nav.newEvent(a, b, false)
                        }
                    },
                    onDayHeader = viewModel::openDay,
                )
            }
            if (state.loading && state.events == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
            state.error?.takeIf { state.events == null && !state.loading }?.let {
                Text(mailText(context, it), color = GateControlTheme.extraColors.muted, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        // "Neuer Termin" (mockup: the primary button in the bar below; "Ressource buchen" comes with phase 3)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            GcPrimaryButton(
                stringResource(R.string.cal_new_event),
                onClick = { nav.newEvent(null, null, false) },
                enabled = !offline && cals?.calendars?.any { it.writable } != false,
                icon = GcIcons.Plus,
            )
        }
    }
}

/** The occurrences and overlay entries as the grid's slots, in their calendar's color. */
internal fun slotsOf(events: MEvents?, cals: MCalendars?): List<MailPimLogic.Slot> {
    events ?: return emptyList()
    val colors = cals?.calendars?.associate { it.id to it.color }.orEmpty()
    val overlayColors = cals?.overlays?.associate { it.id to it.color }.orEmpty()
    return events.items.map { o ->
        MailPimLogic.Slot(o.key, o.title, o.start, o.end, o.allDay, o.startDate, o.endDate, colors[o.calendarId] ?: "#46E0A0",
            open = o.open, cancelled = o.cancelled, location = o.location, occurrence = o)
    } + events.overlays.map { i ->
        MailPimLogic.Slot(i.key, i.title, i.start, i.end, i.allDay, i.startDate, i.endDate, overlayColors[i.overlayId] ?: "#8C96A5", overlay = true, location = i.location)
    }
}

/** A sideways swipe pages ‹ / › (not on a page beside Start: there it changes the page). */
private fun Modifier.swipeToPage(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    var total = 0f
    detectHorizontalDragGestures(
        onDragStart = { total = 0f },
        onDragEnd = {
            if (abs(total) > 120f) onStep(if (total < 0) 1 else -1)
            total = 0f
        },
        onHorizontalDrag = { _, d -> total += d },
    )
}

@Composable
private fun CalendarBar(title: String, view: String, onMenu: () -> Unit, onToday: () -> Unit, onStep: (Int) -> Unit, onView: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GcIconButton(GcIcons.Menu, contentDescription = stringResource(R.string.mail_menu), onClick = onMenu, tint = MaterialTheme.colorScheme.onBackground)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
        GcIconButton(GcIcons.ChevronLeft, contentDescription = stringResource(R.string.cal_previous), onClick = { onStep(-1) }, size = 40.dp)
        TextButton(onClick = onToday) { Text(stringResource(R.string.cal_today), fontWeight = FontWeight.SemiBold) }
        GcIconButton(GcIcons.ChevronRight, contentDescription = stringResource(R.string.cal_next), onClick = { onStep(1) }, size = 40.dp)
        Box {
            val label = viewName(view)
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, extra.border2, RoundedCornerShape(10.dp))
                    .clickable(role = Role.DropdownList) { menu = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = label },
            ) { Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MCalPrefs.VIEWS.forEach { v ->
                    DropdownMenuItem(text = { Text(viewName(v)) }, onClick = { menu = false; onView(v) })
                }
            }
        }
        StartPageMenuButton()
    }
}

/** The chips of the calendars and overlays (mockup): the color square, filled when shown; a tap shows or hides. */
@Composable
private fun CalendarChips(cals: MCalendars?, onCalendar: (Long) -> Unit, onOverlay: (String) -> Unit) {
    cals ?: return
    val extra = GateControlTheme.extraColors
    val shown = stringResource(R.string.cal_shown)
    val hidden = stringResource(R.string.cal_hidden)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val chips = cals.calendars.map { Triple(it.name, it.color, it.visible) to { onCalendar(it.id) } } +
            cals.overlays.map { Triple(it.name, it.color, it.visible) to { onOverlay(it.id) } }
        chips.forEach { (c, click) ->
            val (name, color, on) = c
            val col = calColor(color)
            Row(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, extra.border2, RoundedCornerShape(999.dp))
                    .selectable(selected = on, role = Role.Checkbox, onClick = click)
                    .semantics { contentDescription = "$name, " + if (on) shown else hidden }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(12.dp).clip(RoundedCornerShape(4.dp))
                        .then(if (on) Modifier.background(col) else Modifier.border(2.dp, col, RoundedCornerShape(4.dp))),
                )
                Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onBackground else extra.faint, maxLines = 1)
            }
        }
    }
}

/**
 * The bands above the view: a Microsoft account without the calendar rights ("Neu anmelden"; after that still
 * missing: the company has to allow them, with its link), GMX / Web.de without the host's CalDAV, offline.
 */
@Composable
internal fun CalendarBands(me: MMe?, cals: MCalendars?, offline: Boolean, relogins: Set<Long>, onRelogin: (Long) -> Unit, contacts: Boolean = false) {
    val context = mailContext()
    val scope = if (contacts) MailMicrosoft.CONTACTS_SCOPE else MailMicrosoft.CALENDAR_SCOPE
    val missing = me?.accounts.orEmpty().filter { a ->
        a.microsoft && (a.missingScopes.any { it.equals(scope, true) } || (!contacts && cals?.consent?.any { it.accountId == a.id } == true))
    }
    val needsUpdate = me != null && !me.has(FEATURE_DAV) && me.accounts.any { a ->
        (a.provider == MAccount.PROVIDER_GMX || a.provider == MAccount.PROVIDER_WEBDE) && (if (contacts) a.contacts else a.calendar)?.state == MPimSide.STATE_NONE
    }
    if (missing.isEmpty() && !offline && !needsUpdate) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offline) {
            GcBanner(tone = GcTone.Neutral, icon = GcIcons.Wifi) {
                Text(stringResource(R.string.pim_offline), style = MaterialTheme.typography.bodySmall)
            }
        }
        missing.take(2).forEach { a ->
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Key) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.pim_consent, a.label), style = MaterialTheme.typography.bodySmall)
                    val consentUrl = me?.microsoft?.adminConsentUrl
                    if (a.id in relogins && consentUrl != null) {
                        Text(stringResource(R.string.pim_consent_company), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, consentUrl)
                            runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(stringResource(R.string.pim_consent_share)) }
                    }
                }
                TextButton(onClick = { onRelogin(a.id) }) { Text(stringResource(R.string.pim_relogin), fontWeight = FontWeight.Bold) }
            }
        }
        if (needsUpdate) {
            GcBanner(tone = GcTone.Info, icon = GcIcons.Info) {
                Text(stringResource(if (contacts) R.string.pim_needs_update_contacts else R.string.pim_needs_update), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val FEATURE_DAV = "dav"

// ─── day / 3 days / week ──────────────────────────────────────────

/**
 * The grid (mockup): the days' heads (today in the accent), the all-day entries above, the hours 0–24 (opened at 7),
 * the entries in their calendar's color with the color line left, overlapping ones side by side, an open invitation
 * dashed, a cancelled one struck through, the overlays "nur ansehen", the now line. A tap on free time: a new event.
 */
@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    slots: List<MailPimLogic.Slot>,
    zone: ZoneId,
    modifier: Modifier,
    onSlot: (MailPimLogic.Slot) -> Unit,
    onEmpty: (LocalDate, Int) -> Unit,
    onDayHeader: (LocalDate) -> Unit,
) {
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR.toPx() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo((7 * hourPx).toInt()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = HOURS_COL + 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEach { d ->
                val on = d == today
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) extra.accentBg else Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = dayLong(d, locale)) { onDayHeader(d) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE", locale)).removeSuffix("."), style = MaterialTheme.typography.bodySmall, color = if (on) extra.accentText else extra.muted)
                    Text(d.dayOfMonth.toString(), fontWeight = FontWeight.Bold, color = if (on) extra.accentText else MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        val allDay = days.map { d -> MailPimLogic.allDayOn(slots, d, zone) }
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(start = HOURS_COL + 8.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                allDay.forEach { list ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(3).forEach { s -> SlotBlock(s, Modifier.fillMaxWidth().heightIn(min = 22.dp), compact = true) { onSlot(s) } }
                        if (list.size > 3) Text("+${list.size - 3}", style = MaterialTheme.typography.labelSmall, color = extra.muted)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR * 24).padding(end = 8.dp)) {
                Box(Modifier.width(HOURS_COL + 8.dp).fillMaxHeight()) {
                    (1 until 24).forEach { h ->
                        Text(
                            h.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = extra.faint,
                            modifier = Modifier.offset(x = 8.dp, y = HOUR * h - 8.dp),
                        )
                    }
                }
                days.forEach { d ->
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp)
                            .drawBehind {
                                for (h in 0..24) {
                                    val y = h * hourPx
                                    drawLine(extra.border, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                                }
                            }
                            .pointerInput(d) { detectTapGestures { p -> onEmpty(d, (p.y / hourPx * 60).toInt()) } },
                    ) {
                        val w = maxWidth
                        MailPimLogic.layoutDay(slots, d, zone).forEach { p ->
                            val colW = w / p.cols
                            SlotBlock(
                                p.slot,
                                Modifier
                                    .offset(x = colW * p.col, y = HOUR * (p.top / 60f))
                                    .width(colW - 2.dp)
                                    .height(HOUR * ((p.bottom - p.top) / 60f) - 2.dp),
                            ) { onSlot(p.slot) }
                        }
                        if (d == today) {
                            val y = HOUR * (MailPimLogic.minuteOfDay(now, zone) / 60f)
                            Box(Modifier.offset(y = y - 1.dp).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error))
                            Box(Modifier.offset(x = (-4).dp, y = y - 4.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One entry: the color's surface (20 %) with the color line left; an open invitation dashed in the color; a
 * cancelled one struck through; an overlay's with "nur ansehen".
 */
@Composable
private fun SlotBlock(s: MailPimLogic.Slot, modifier: Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val c = calColor(s.color)
    val dashed = s.open
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (dashed) {
                    Modifier.drawBehind {
                        drawRoundRect(c, cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                    }
                } else {
                    Modifier.background(c.copy(alpha = if (s.overlay) 0.12f else 0.2f)).drawBehind {
                        drawRect(c, size = Size(3.dp.toPx(), size.height))
                    }
                },
            )
            .clickable(role = Role.Button, enabled = !s.overlay || s.occurrence != null, onClick = onClick)
            .padding(start = if (dashed) 6.dp else 7.dp, end = 4.dp, top = if (compact) 2.dp else 3.dp, bottom = 2.dp),
    ) {
        Column {
            Text(
                s.title.ifBlank { stringResource(R.string.cal_no_title) },
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dashed) c else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (s.cancelled) TextDecoration.LineThrough else null,
                maxLines = if (compact) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                val sub = when {
                    s.open -> stringResource(R.string.cal_invite_open)
                    s.overlay -> stringResource(R.string.cal_read_only)
                    else -> s.location
                }
                if (!sub.isNullOrBlank()) Text(sub, fontSize = 11.sp, lineHeight = 14.sp, color = if (dashed) c else extra.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ─── month ────────────────────────────────────────────────────────

/** The month: the weeks, per day its number and up to three entries as colored lines; a tap opens the day. */
@Composable
private fun MonthView(anchor: LocalDate, today: LocalDate, weekStart: Int, slots: List<MailPimLogic.Slot>, zone: ZoneId, modifier: Modifier, onDay: (LocalDate) -> Unit) {
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val weeks = MailPimLogic.monthGrid(anchor, weekStart)
    Column(modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            weeks.first().forEach { d ->
                Text(
                    d.format(DateTimeFormatter.ofPattern("EEEEE", locale)),
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.muted,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { d ->
                    val inMonth = d.month == anchor.month
                    val list = MailPimLogic.allDayOn(slots, d, zone) + MailPimLogic.timedOn(slots, d, zone).sortedBy { it.start }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClickLabel = dayLong(d, locale)) { onDay(d) }
                            .padding(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    d == today -> MaterialTheme.colorScheme.onPrimary
                                    inMonth -> MaterialTheme.colorScheme.onBackground
                                    else -> extra.faint
                                },
                            )
                        }
                        list.take(3).forEach { s ->
                            val c = calColor(s.color)
                            Text(
                                s.title,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                color = if (s.open) c else MaterialTheme.colorScheme.onSurface,
                                textDecoration = if (s.cancelled) TextDecoration.LineThrough else null,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.2f)).padding(horizontal = 3.dp),
                            )
                        }
                        if (list.size > 3) Text("+${list.size - 3}", fontSize = 10.sp, color = extra.muted)
                    }
                }
            }
        }
    }
}

// ─── list ─────────────────────────────────────────────────────────

/** The agenda from [first] on, by days (today marked), down to its end and further while scrolling. */
@Composable
private fun AgendaView(first: LocalDate, days: Int, today: LocalDate, slots: List<MailPimLogic.Slot>, zone: ZoneId, onSlot: (MailPimLogic.Slot) -> Unit, onEnd: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val locale = currentLocale()
    val groups = remember(slots, first, days) { MailPimLogic.agenda(slots, first, days, zone) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, groups.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= listState.layoutInfo.totalItemsCount - 3) onEnd()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (groups.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.cal_nothing), color = extra.muted, modifier = Modifier.padding(24.dp)) }
        }
        groups.forEach { (day, list) ->
            item(key = "d$day") {
                Text(
                    (if (day == today) stringResource(R.string.cal_today) + " · " else "") + dayText(day, locale),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (day == today) extra.accentText else extra.muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            items(list, key = { "s$day-${it.key}" }) { s -> AgendaRow(s, zone) { onSlot(s) } }
        }
    }
}

@Composable
internal fun AgendaRow(s: MailPimLogic.Slot, zone: ZoneId, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val c = calColor(s.color)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, enabled = s.occurrence != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp))
                .then(if (s.open) Modifier.border(1.dp, c, RoundedCornerShape(2.dp)) else Modifier.background(c)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                s.title.ifBlank { stringResource(R.string.cal_no_title) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (s.cancelled) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val parts = listOfNotNull(
                whenText(s.start, s.end, s.allDay, s.startDate, s.endDate, zone),
                stringResource(R.string.cal_invite_open).takeIf { s.open },
                stringResource(R.string.cal_read_only).takeIf { s.overlay },
                s.location,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (s.open) c else extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
