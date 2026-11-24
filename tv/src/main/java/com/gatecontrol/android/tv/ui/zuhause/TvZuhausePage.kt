package com.gatecontrol.android.tv.ui.zuhause

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.familie.AgendaEntry
import com.gatecontrol.android.familie.shopping.ShoppingItem
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvOptionRow
import com.gatecontrol.android.tv.ui.components.TvRowEnd
import com.gatecontrol.android.tv.ui.components.TvTextInput
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.zuhause.TvCalendarDay
import com.gatecontrol.android.tv.zuhause.TvShoppingState
import com.gatecontrol.android.tv.zuhause.TvSourceState
import com.gatecontrol.android.tv.zuhause.TvWaste
import com.gatecontrol.android.tv.zuhause.TvZuhauseLogic
import com.gatecontrol.android.tv.zuhause.TvZuhauseUi
import com.gatecontrol.android.ui.theme.MonoFontFamily
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What the page does with the remote. */
data class TvZuhauseActions(
    val onStart: () -> Unit = {},
    val onToggle: (ShoppingItem) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onAdd: (String) -> Unit = {},
    val onChooseList: (String) -> Unit = {},
)

/**
 * The page "Zuhause" of the rail (mockup "3 · Zuhause"): three columns the whole family sees at a glance — the waste
 * collection (highlighted from the evening before), the next seven days of Familie and Alltag, the shopping list.
 * OK ticks an entry ("Rückgängig" for a few seconds), ◀ ▶ go between the columns, ☰ chooses another list.
 */
@Composable
fun TvZuhausePage(ui: TvZuhauseUi, actions: TvZuhauseActions, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    LaunchedEffect(Unit) { actions.onStart() }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var opened by remember { mutableStateOf<AgendaEntry?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .padding(end = 30.dp, bottom = 12.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && ui.lists.size > 1) {
                    choosing = true
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WasteColumn(ui, Modifier.weight(1f))
            CalendarColumn(ui, onOpen = { opened = it }, modifier = Modifier.weight(1.25f))
            ShoppingColumn(ui, actions, onChoose = { choosing = true }, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            KeyHint("OK", stringResource(R.string.tv_home_key_ok))
            KeyHint("◀ ▶", stringResource(R.string.tv_home_key_column))
            if (ui.lists.size > 1) KeyHint("☰", stringResource(R.string.tv_home_other_list))
        }
    }
    if (choosing) {
        ListDialog(ui, onChoose = { id ->
            choosing = false
            actions.onChooseList(id)
        }, onDismiss = { choosing = false })
    }
    opened?.let { EntryDialog(it, onDismiss = { opened = null }) }
}

@Composable
private fun KeyHint(key: String, text: String) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.background(colors.panel2, RoundedCornerShape(5.dp)).border(BorderStroke(1.dp, colors.line2), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(key, style = MaterialTheme.typography.labelMedium, color = colors.text)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun ColumnHead(title: String, extra: String?) {
    val colors = GateControlTvTheme.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTvTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

/** A line of a column: focusable with the TV focus (ring, glow, slight zoom); [warn]: the yellow of a bin due. */
@Composable
private fun Line(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (warn) colors.warnBg else colors.panel,
            contentColor = colors.text,
            focusedContainerColor = if (warn) colors.warnBg else colors.panel2,
            focusedContentColor = colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (warn) colors.warn.copy(alpha = 0.55f) else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

// ─── Müllabfuhr ──────────────────────────────────────────────────────────

@Composable
private fun WasteColumn(ui: TvZuhauseUi, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        ColumnHead(stringResource(R.string.tv_home_waste), stringResource(R.string.tv_home_waste_source))
        when {
            ui.waste.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ui.waste, key = { it.kind.lowercase() }) { w -> WasteLine(w, ui.today) }
            }
            ui.wasteState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.wasteState == TvSourceState.MISSING -> Note(stringResource(R.string.tv_home_waste_missing))
            ui.wasteState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            else -> Note(stringResource(R.string.tv_home_waste_none))
        }
    }
}

@Composable
private fun WasteLine(w: TvWaste, today: LocalDate) {
    val colors = GateControlTvTheme.colors
    val whenText = dayWord(w.date, today) ?: shortDate(w.date)
    val every = everyText(w.everyDays)
    Line(onClick = {}, description = listOfNotNull(w.kind, every, whenText).joinToString(", "), warn = w.soon) {
        Box(Modifier.size(width = 16.dp, height = 19.dp).background(hexColor(w.color), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomStart = 4.dp, bottomEnd = 4.dp)))
        Column(Modifier.weight(1f)) {
            Text(w.kind, style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (every != null) Text(every, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(whenText, style = MaterialTheme.typography.labelLarge, color = if (w.soon) colors.warn else colors.text, maxLines = 1)
            if (dayWord(w.date, today) != null) Text(shortDate(w.date), style = MaterialTheme.typography.bodySmall, color = if (w.soon) colors.warn else colors.muted, maxLines = 1)
        }
    }
}

@Composable
private fun everyText(days: Int?): String? = when (days) {
    null -> null
    7 -> stringResource(R.string.tv_home_every_week)
    14 -> stringResource(R.string.tv_home_every_two_weeks)
    in 28..31 -> stringResource(R.string.tv_home_every_month)
    else -> stringResource(R.string.tv_home_every_days, days)
}

// ─── Kalender ────────────────────────────────────────────────────────────

@Composable
private fun CalendarColumn(ui: TvZuhauseUi, onOpen: (AgendaEntry) -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    Column(modifier.fillMaxHeight()) {
        ColumnHead(stringResource(R.string.tv_home_calendar), stringResource(R.string.tv_home_calendar_source))
        when {
            ui.days.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (day in ui.days) {
                    item(key = "d:" + day.date) {
                        Text(
                            dayHeading(day, ui.today).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.faint,
                            modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                        )
                    }
                    items(day.entries, key = { e -> "e:" + day.date + ":" + entryKey(e) }) { e -> EntryLine(e, onClick = { onOpen(e) }) }
                }
            }
            ui.calendarState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.calendarState == TvSourceState.MISSING -> Note(stringResource(R.string.tv_home_calendar_missing))
            ui.calendarState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            else -> Note(stringResource(R.string.tv_home_calendar_empty))
        }
    }
}

private fun entryKey(e: AgendaEntry): String = when (e) {
    is AgendaEntry.Own -> "o" + e.event.id
    is AgendaEntry.Alltag -> "a" + e.event.id
    is AgendaEntry.Waste -> "w" + e.waste.kinds.joinToString()
}

@Composable
private fun dayHeading(day: TvCalendarDay, today: LocalDate): String = when (day.date) {
    today -> stringResource(R.string.tv_home_day_today, shortDate(day.date))
    today.plusDays(1) -> stringResource(R.string.tv_home_day_tomorrow, shortDate(day.date))
    else -> shortDate(day.date)
}

@Composable
private fun EntryLine(e: AgendaEntry, onClick: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val time = e.time ?: stringResource(R.string.tv_home_all_day)
    val title = entryTitle(e)
    val sub = entrySubtitle(e)
    Line(onClick = onClick, description = listOf(time, title, sub).joinToString(", ")) {
        Text(time, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily), color = colors.muted, maxLines = 1, modifier = Modifier.width(62.dp))
        Box(Modifier.width(3.dp).height(30.dp).background(entryColor(e), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun entryTitle(e: AgendaEntry): String = when (e) {
    is AgendaEntry.Own -> e.event.title
    is AgendaEntry.Alltag -> e.event.title
    is AgendaEntry.Waste -> e.waste.kinds.joinToString(", ")
}

@Composable
internal fun entrySubtitle(e: AgendaEntry): String = when (e) {
    is AgendaEntry.Own -> e.event.note.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        ?: stringResource(if (e.event.isBirthday) R.string.tv_home_birthday else R.string.tv_home_familie)
    is AgendaEntry.Alltag -> e.event.calendarName ?: stringResource(R.string.tv_home_waste_source)
    is AgendaEntry.Waste -> stringResource(R.string.tv_home_waste)
}

@Composable
private fun entryColor(e: AgendaEntry): Color {
    val colors = GateControlTvTheme.colors
    return when (e) {
        is AgendaEntry.Own -> if (e.event.isBirthday) colors.warn else colors.info
        is AgendaEntry.Alltag -> e.event.calendarColor?.let(::hexColor) ?: colors.accent
        is AgendaEntry.Waste -> hexColor(TvZuhauseLogic.color(e.waste.kinds.firstOrNull().orEmpty(), e.waste.color.takeIf { e.waste.kinds.size == 1 }))
    }
}

/** OK on a date: its whole text (title, day, time, note or calendar). */
@Composable
private fun EntryDialog(e: AgendaEntry, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val close = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(entryTitle(e), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        val date = runCatching { LocalDate.parse(e.date) }.getOrNull()
        Text(
            listOfNotNull(date?.let { shortDate(it) }, e.time ?: stringResource(R.string.tv_home_all_day)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        val note = (e as? AgendaEntry.Own)?.event?.note?.takeIf { it.isNotBlank() }
        Text(note ?: entrySubtitle(e), style = MaterialTheme.typography.bodyMedium, color = colors.text)
        TvButton(stringResource(R.string.tv_dictation_close), onClick = onDismiss, modifier = Modifier.focusRequester(close))
    }
    LaunchedEffect(Unit) { close.requestSoon() }
}

// ─── Einkauf ─────────────────────────────────────────────────────────────

@Composable
private fun ShoppingColumn(ui: TvZuhauseUi, actions: TvZuhauseActions, onChoose: () -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val list = ui.list
    LaunchedEffect(list?.id != null) { if (list != null) first.requestSoon() }
    LaunchedEffect(adding) { if (adding) field.requestSoon() }
    fun submit() {
        if (text.isNotBlank()) actions.onAdd(text)
        text = ""
        adding = false
    }
    Column(modifier.fillMaxHeight()) {
        ColumnHead(
            stringResource(R.string.tv_home_shopping),
            list?.let { listOf(it.name, pluralStringResource(R.plurals.tv_home_open, ui.open, ui.open)).joinToString(" · ") },
        )
        when {
            list != null -> LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.items.isEmpty()) item(key = "empty") { Note(stringResource(R.string.tv_home_list_empty)) }
                items(ui.items, key = { it.id }) { item ->
                    ItemLine(item, onClick = { actions.onToggle(item) }, modifier = if (item == ui.items.first()) Modifier.focusRequester(first) else Modifier)
                }
                item(key = "add") {
                    if (adding) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvTextInput(
                                value = text,
                                onValueChange = { text = it.take(120) },
                                label = stringResource(R.string.tv_home_add_label),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                fieldModifier = Modifier.focusRequester(field),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(TvIcons.Mic, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
                                Text(stringResource(R.string.tv_home_voice_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TvButton(stringResource(R.string.tv_home_add_send), onClick = ::submit, style = TvButtonStyle.Primary, enabled = text.isNotBlank())
                                TvButton(stringResource(R.string.tv_cancel), onClick = {
                                    text = ""
                                    adding = false
                                })
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                            TvButton(
                                stringResource(R.string.tv_home_add),
                                onClick = { adding = true },
                                modifier = if (ui.items.isEmpty()) Modifier.focusRequester(first) else Modifier,
                            )
                            if (ui.lists.size > 1) TvButton(stringResource(R.string.tv_home_other_list), onClick = onChoose)
                        }
                    }
                }
            }
            ui.shoppingState == TvShoppingState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.shoppingState == TvShoppingState.MISSING -> Note(stringResource(R.string.tv_home_shopping_missing))
            ui.shoppingState == TvShoppingState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            else -> Note(stringResource(R.string.tv_home_no_lists))
        }
        ui.undo?.let { item ->
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().background(colors.panel2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.tv_home_checked, item.text), style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TvButton(stringResource(R.string.tv_home_undo), onClick = actions.onUndo, style = TvButtonStyle.Primary)
            }
        }
        if (ui.pending > 0 && ui.shoppingState == TvShoppingState.OFFLINE) {
            Text(pluralStringResource(R.plurals.tv_home_pending, ui.pending, ui.pending), style = MaterialTheme.typography.bodySmall, color = colors.warn, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun ItemLine(item: ShoppingItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val sub = when {
        item.checked -> TvZuhauseLogic.checkedByName(item)?.let { stringResource(R.string.tv_home_done_by, it) } ?: stringResource(R.string.tv_home_done)
        else -> item.qty
    }
    Line(onClick = onClick, description = listOfNotNull(item.text, item.qty, if (item.checked) stringResource(R.string.tv_home_done) else null).joinToString(", "), modifier = modifier) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier
                .size(16.dp)
                .background(if (item.checked) colors.accent else Color.Transparent, shape)
                .border(BorderStroke(1.5.dp, if (item.checked) colors.accent else colors.line2), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) Icon(TvIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.text,
                style = MaterialTheme.typography.labelLarge.copy(textDecoration = if (item.checked) TextDecoration.LineThrough else null),
                color = if (item.checked) colors.faint else colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** ☰ or "Andere Liste": the lists of the person with their open entries. */
@Composable
private fun ListDialog(ui: TvZuhauseUi, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val first = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(stringResource(R.string.tv_home_choose_list), style = MaterialTheme.typography.headlineSmall, color = colors.text)
        LazyColumn(Modifier.height(260.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
            items(ui.lists, key = { it.id }) { l ->
                TvOptionRow(
                    title = l.name,
                    description = pluralStringResource(R.plurals.tv_home_open, ui.openOf(l.id), ui.openOf(l.id)),
                    end = if (l.id == ui.list?.id) TvRowEnd.Switch(true) else TvRowEnd.Arrow,
                    onClick = { onChoose(l.id) },
                    modifier = if (l.id == (ui.list?.id ?: ui.lists.first().id)) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
    LaunchedEffect(Unit) { first.requestSoon() }
}

// ─── Formats ─────────────────────────────────────────────────────────────

/** "Mi 25.11." / "Wed 25.11.". */
internal fun shortDate(date: LocalDate, locale: Locale = Locale.getDefault()): String {
    val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".")
    return "%s %02d.%02d.".format(locale, day, date.dayOfMonth, date.monthValue)
}

/** "heute" / "morgen", else null. */
@Composable
internal fun dayWord(date: LocalDate, today: LocalDate): String? = when (date) {
    today -> stringResource(R.string.tv_home_today)
    today.plusDays(1) -> stringResource(R.string.tv_home_tomorrow)
    else -> null
}

internal fun hexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF6B6F76))
