package com.gatecontrol.android.tv.ui.notizen

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NParsedLine
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NTaskDetail
import com.gatecontrol.android.notizen.NoteMd
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.notizen.TvNotesAdd
import com.gatecontrol.android.tv.notizen.TvNotesMessage
import com.gatecontrol.android.tv.notizen.TvNotesPlace
import com.gatecontrol.android.tv.notizen.TvNotizenLogic
import com.gatecontrol.android.tv.notizen.TvNotizenUi
import com.gatecontrol.android.tv.ui.components.TvButton
import com.gatecontrol.android.tv.ui.components.TvButtonStyle
import com.gatecontrol.android.tv.ui.components.TvDialog
import com.gatecontrol.android.tv.ui.components.TvTextInput
import com.gatecontrol.android.tv.ui.drive.requestSoon
import com.gatecontrol.android.tv.ui.home.TvIcons
import com.gatecontrol.android.tv.ui.theme.GateControlTvTheme
import com.gatecontrol.android.tv.ui.zuhause.dayWord
import com.gatecontrol.android.tv.ui.zuhause.shortDate
import com.gatecontrol.android.tv.zuhause.TvSourceState
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What the page does with the remote. */
data class TvNotizenActions(
    val onStart: () -> Unit = {},
    val onChoose: (TvNotesPlace) -> Unit = {},
    val onToggle: (NEntry) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onMoveTomorrow: (NTask) -> Unit = {},
    val detail: suspend (NTask) -> NTaskDetail? = { null },
    /** A sub task in the details → whether it went. */
    val onSub: suspend (Long, Boolean) -> Boolean = { _, _ -> false },
    val note: suspend (Long) -> NNote? = { null },
    val onAdd: () -> Unit = {},
    val onRead: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onCancelAdd: () -> Unit = {},
)

/**
 * The page „Notizen“ of the rail (mockup „2 · Notizen und Aufgaben“): on the left Mein Tag, Demnächst, the lists and
 * notebooks of the area „Privat“, on the right the tasks. OK ticks one off („Rückgängig“ for a few seconds), ☰ opens the
 * details (tick the sub tasks, move it to tomorrow). A notebook's notes open as a card to read, its Markdown simply
 * drawn. „Aufgabe hinzufügen“ in Mein Tag: the line goes to the server's quick entry, which reads „morgen 17 Uhr“.
 */
@Composable
fun TvNotizenPage(ui: TvNotizenUi, actions: TvNotizenActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        actions.onStart()
        actions.onChoose(ui.place)
    }
    var focused by remember { mutableStateOf<NEntry?>(null) }
    var details by remember { mutableStateOf<NEntry?>(null) }
    var reading by remember { mutableStateOf<NNoteSummary?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .padding(end = 30.dp, bottom = 12.dp)
            .onPreviewKeyEvent { e ->
                val target = focused
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU && target != null) {
                    details = target
                    true
                } else {
                    false
                }
            },
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlacesColumn(ui, actions, Modifier.width(220.dp))
            when (val place = ui.place) {
                is TvNotesPlace.Notebook -> NotesColumn(ui, onOpen = { reading = it }, modifier = Modifier.weight(1f))
                else -> TasksColumn(ui, place, actions, onFocus = { focused = it }, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (ui.place is TvNotesPlace.Notebook) {
                KeyHint("OK", stringResource(R.string.tv_notes_key_read))
            } else {
                KeyHint("OK", stringResource(R.string.tv_notes_key_tick))
                KeyHint("☰", stringResource(R.string.tv_notes_key_menu))
            }
            KeyHint("◀ ▶", stringResource(R.string.tv_home_key_column))
        }
    }
    details?.let { e -> DetailDialog(e, ui, actions, onDismiss = { details = null }) }
    reading?.let { n -> NoteDialog(n, actions.note, onDismiss = { reading = null }) }
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
        Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTvTheme.colors.muted, modifier = Modifier.padding(top = 4.dp))
}

/** A focusable line with the TV focus (ring, glow, slight zoom); [selected]: the chosen place of the left column. */
@Composable
private fun Line(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GateControlTvTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.accentBg else colors.panel,
            contentColor = if (selected) colors.accentText else colors.text,
            focusedContainerColor = if (selected) colors.accentBg else colors.panel2,
            focusedContentColor = if (selected) colors.accentText else colors.text,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else colors.line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.focus), shape = shape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = colors.focusGlow, elevation = 10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

// ─── left: Mein Tag, Demnächst, Listen, Notizbücher ─────────────────────

@Composable
private fun PlacesColumn(ui: TvNotizenUi, actions: TvNotizenActions, modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(ui.navState == TvSourceState.OK) { if (ui.navState == TvSourceState.OK) first.requestSoon() }
    Column(modifier.fillMaxHeight()) {
        ColumnHead(stringResource(R.string.tv_nav_notes), null)
        when {
            ui.navState == TvSourceState.OK || ui.lists.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                item(key = "myday") {
                    PlaceLine(stringResource(R.string.tv_notes_myday), ui.myDayCount, ui.place == TvNotesPlace.MyDay, { actions.onChoose(TvNotesPlace.MyDay) }, Modifier.focusRequester(first))
                }
                item(key = "planned") {
                    PlaceLine(stringResource(R.string.tv_notes_planned), ui.plannedCount, ui.place == TvNotesPlace.Planned, { actions.onChoose(TvNotesPlace.Planned) })
                }
                if (ui.lists.isNotEmpty()) {
                    item(key = "h:lists") { SubHead(stringResource(R.string.tv_notes_lists)) }
                    items(ui.lists, key = { "l:" + it.id }) { l ->
                        PlaceLine(l.name, l.open, ui.place == TvNotesPlace.List(l.id), { actions.onChoose(TvNotesPlace.List(l.id)) })
                    }
                }
                if (ui.notebooks.isNotEmpty()) {
                    item(key = "h:notebooks") { SubHead(stringResource(R.string.tv_notes_notebooks)) }
                    items(ui.notebooks, key = { "n:" + it.id }) { n ->
                        PlaceLine(n.name.orEmpty(), null, ui.place == TvNotesPlace.Notebook(n.id), { actions.onChoose(TvNotesPlace.Notebook(n.id)) })
                    }
                }
            }
            ui.navState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.navState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            else -> Note(stringResource(R.string.tv_notes_missing))
        }
    }
}

@Composable
private fun SubHead(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = GateControlTvTheme.colors.faint, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
private fun PlaceLine(name: String, count: Int?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    Line(onClick = onClick, description = listOfNotNull(name, count?.toString()).joinToString(", "), modifier = modifier, selected = selected) {
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (count != null && count > 0) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = if (selected) colors.accentText else colors.muted)
    }
}

// ─── right: the tasks ───────────────────────────────────────────────────

@Composable
private fun placeTitle(ui: TvNotizenUi, place: TvNotesPlace): String = when (place) {
    TvNotesPlace.MyDay -> stringResource(R.string.tv_notes_myday)
    TvNotesPlace.Planned -> stringResource(R.string.tv_notes_planned)
    is TvNotesPlace.List -> ui.lists.firstOrNull { it.id == place.id }?.name.orEmpty()
    is TvNotesPlace.Notebook -> ui.notebooks.firstOrNull { it.id == place.id }?.name.orEmpty()
}

/** „Dienstag, 24. November“. */
internal fun longDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern(if (locale.language == "de") "EEEE, d. MMMM" else "EEEE, MMMM d", locale))

@Composable
private fun TasksColumn(ui: TvNotizenUi, place: TvNotesPlace, actions: TvNotizenActions, onFocus: (NEntry?) -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    val counts = stringResource(R.string.tv_notes_counts, pluralStringResource(R.plurals.tv_home_open, ui.open, ui.open), pluralStringResource(R.plurals.tv_notes_done, ui.doneCount, ui.doneCount))
    val extra = if (place == TvNotesPlace.MyDay) "${longDate(ui.today)} · $counts" else counts
    Column(modifier.fillMaxHeight()) {
        ColumnHead(placeTitle(ui, place), extra.takeIf { ui.placeState == TvSourceState.OK })
        if (ui.placeState == TvSourceState.OK || ui.entries.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.entries.isEmpty()) item(key = "empty") { Note(stringResource(if (place == TvNotesPlace.MyDay) R.string.tv_notes_myday_empty else R.string.tv_notes_empty)) }
                items(ui.entries, key = { it.uid }) { e ->
                    TaskLine(e, ui, onClick = { actions.onToggle(e) }, modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(e) })
                }
                if (place == TvNotesPlace.MyDay) item(key = "add") { AddTask(ui, actions, Modifier.onFocusChanged { if (it.hasFocus) onFocus(null) }) }
            }
        } else {
            when (ui.placeState) {
                TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
                TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
                else -> Note(stringResource(R.string.tv_notes_missing))
            }
        }
        ui.undo?.let { e ->
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().background(colors.panel2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.tv_home_checked, e.title), style = MaterialTheme.typography.bodySmall, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TvButton(stringResource(R.string.tv_home_undo), onClick = actions.onUndo, style = TvButtonStyle.Primary)
            }
        }
        ui.message?.let { m ->
            Text(
                stringResource(
                    when (m) {
                        TvNotesMessage.SAVED -> R.string.tv_notes_saved
                        TvNotesMessage.MOVED -> R.string.tv_notes_moved
                        TvNotesMessage.REFUSED -> R.string.tv_notes_refused
                        TvNotesMessage.OFFLINE -> R.string.tv_notes_offline_change
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (m == TvNotesMessage.SAVED || m == TvNotesMessage.MOVED) colors.accentText else colors.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** The tags under a task: overdue, its day and time, the repeat, the sub tasks, shared, the list. */
@Composable
private fun taskTags(e: NEntry, ui: TvNotizenUi): List<Pair<String, Color?>> {
    val colors = GateControlTvTheme.colors
    val out = mutableListOf<Pair<String, Color?>>()
    when (e) {
        is NTask -> {
            if (e.done) {
                out += stringResource(R.string.tv_home_done) to null
            } else {
                val due = TvNotizenLogic.date(e.due?.date)
                if (due != null) {
                    val day = dayWord(due, ui.today) ?: if (due == ui.today.minusDays(1)) stringResource(R.string.tv_notes_yesterday) else shortDate(due)
                    val text = listOfNotNull(day, e.due?.time).joinToString(" ")
                    out += (if (e.overdue) stringResource(R.string.tv_notes_overdue, text) else text) to (if (e.overdue) colors.warn else colors.info)
                } else if (e.overdue) {
                    out += stringResource(R.string.tv_notes_overdue_plain) to colors.warn
                }
                if (e.remindAt != null && e.due?.time == null) out += stringResource(R.string.tv_notes_reminder) to null
            }
            e.repeatText?.takeIf { it.isNotBlank() }?.let { out += stringResource(R.string.tv_notes_repeats, it) to colors.accentText }
            e.progress?.takeIf { it.total > 0 }?.let { out += stringResource(R.string.tv_notes_progress, it.done, it.total) to null }
            if (e.important) out += stringResource(R.string.tv_notes_important) to colors.warn
            if (e.people.isEmpty() && e.assignee?.name != null) out += e.assignee?.name.orEmpty() to colors.info
            e.listId?.let { ui.listNames[it] }?.takeIf { ui.place !is TvNotesPlace.List }?.let { out += it to null }
        }
        is NItem -> {
            if (e.overdue) out += stringResource(R.string.tv_notes_overdue_plain) to colors.warn
            e.due?.let { d -> TvNotizenLogic.date(d.date)?.let { out += listOfNotNull(dayWord(it, ui.today) ?: shortDate(it), d.time).joinToString(" ") to colors.info } }
            out += e.sourceLabel to null
        }
    }
    return out
}

@Composable
private fun TaskLine(e: NEntry, ui: TvNotizenUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val tags = taskTags(e, ui)
    Line(onClick = onClick, description = (listOf(e.title) + tags.map { it.first }).joinToString(", "), modifier = modifier) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            Modifier
                .size(17.dp)
                .background(if (e.done) colors.accent else Color.Transparent, shape)
                .border(BorderStroke(1.5.dp, if (e.done) colors.accent else colors.line2), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (e.done) Icon(TvIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.title,
                style = MaterialTheme.typography.labelLarge.copy(textDecoration = if (e.done) TextDecoration.LineThrough else null),
                color = if (e.done) colors.faint else colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((text, tone) in tags.take(4)) Tag(text, tone)
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, tone: Color?) {
    val colors = GateControlTvTheme.colors
    if (tone == null) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.background(tone.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** „Aufgabe hinzufügen“: the field (dictation with the system keyboard), then what the server's quick entry read. */
@Composable
private fun AddTask(ui: TvNotizenUi, actions: TvNotizenActions, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val save = remember { FocusRequester() }
    val add = ui.add
    LaunchedEffect(add is TvNotesAdd.Typing) { if (add is TvNotesAdd.Typing) field.requestSoon() }
    LaunchedEffect(add is TvNotesAdd.Preview) { if (add is TvNotesAdd.Preview) save.requestSoon() }
    Column(modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (add) {
            TvNotesAdd.Closed -> TvButton(stringResource(R.string.tv_notes_add), onClick = {
                text = ""
                actions.onAdd()
            })
            TvNotesAdd.Typing -> {
                TvTextInput(
                    value = text,
                    onValueChange = { text = it.take(TvNotizenLogic.TEXT_MAX) },
                    label = stringResource(R.string.tv_notes_add_label),
                    placeholder = stringResource(R.string.tv_notes_add_example),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onRead(text) }),
                    fieldModifier = Modifier.focusRequester(field),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(TvIcons.Mic, contentDescription = null, tint = colors.muted, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.tv_home_voice_hint), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(stringResource(R.string.tv_notes_add_check), onClick = { actions.onRead(text) }, style = TvButtonStyle.Primary, enabled = text.isNotBlank())
                    TvButton(stringResource(R.string.tv_cancel), onClick = actions.onCancelAdd)
                }
            }
            is TvNotesAdd.Reading -> Note(stringResource(R.string.tv_notes_add_reading))
            is TvNotesAdd.Saving -> Note(stringResource(R.string.tv_notes_add_saving))
            is TvNotesAdd.Preview -> {
                Column(
                    Modifier.fillMaxWidth().background(colors.panel, RoundedCornerShape(8.dp)).border(BorderStroke(1.dp, colors.line2), RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.tv_notes_add_preview), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                    Text(add.parsed?.title ?: add.text, style = MaterialTheme.typography.headlineSmall, color = colors.text)
                    Text(previewDue(add.parsed, ui.today), style = MaterialTheme.typography.bodyMedium, color = if (add.parsed?.due != null) colors.warn else colors.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(stringResource(R.string.tv_pin_save), onClick = actions.onSave, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(save))
                    TvButton(stringResource(R.string.tv_notes_add_edit), onClick = {
                        text = add.text
                        actions.onAdd()
                    })
                    TvButton(stringResource(R.string.tv_cancel), onClick = actions.onCancelAdd)
                }
            }
        }
    }
}

/** „morgen · 17:00“ — the day and time are yellow: there the dictation mishears most. */
@Composable
private fun previewDue(parsed: NParsedLine?, today: LocalDate): String {
    val due = parsed?.due ?: return stringResource(R.string.tv_notes_add_no_day)
    val date = TvNotizenLogic.date(due.date) ?: return stringResource(R.string.tv_notes_add_no_day)
    return listOfNotNull(dayWord(date, today) ?: shortDate(date), due.time).joinToString(" · ")
}

// ─── the details (☰) ────────────────────────────────────────────────────

@Composable
private fun DetailDialog(e: NEntry, ui: TvNotizenUi, actions: TvNotizenActions, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val close = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<NTaskDetail?>(null) }
    var loaded by remember { mutableStateOf(e !is NTask) }
    LaunchedEffect(e.uid) {
        if (e is NTask) {
            detail = actions.detail(e)
            loaded = true
        }
        close.requestSoon()
    }
    TvDialog(onDismiss) {
        Text(e.title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        val tags = taskTags(e, ui)
        if (tags.isNotEmpty()) Text(tags.joinToString(" · ") { it.first }, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        if (e is NItem) e.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text) }
        val d = detail
        if (!loaded) Note(stringResource(R.string.tv_home_loading))
        d?.note?.takeIf { it.isNotBlank() }?.let { NoteText(it, Modifier.heightIn(max = 140.dp)) }
        if (d != null && d.subtasks.isNotEmpty()) {
            val (done, total) = TvNotizenLogic.progress(d.subtasks)
            Text(stringResource(R.string.tv_notes_subtasks, done, total), style = MaterialTheme.typography.labelMedium, color = colors.muted)
            LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(4.dp)) {
                items(d.subtasks, key = { it.id }) { s ->
                    val description = listOfNotNull(s.title, if (s.done) stringResource(R.string.tv_home_done) else null).joinToString(", ")
                    Line(onClick = {
                        val now = !s.done
                        detail = TvNotizenLogic.withSub(d, s.id, now)
                        scope.launch { if (!actions.onSub(s.id, now)) detail = detail?.let { TvNotizenLogic.withSub(it, s.id, !now) } }
                    }, description = description) {
                        val shape = RoundedCornerShape(4.dp)
                        Box(
                            Modifier.size(15.dp).background(if (s.done) colors.accent else Color.Transparent, shape).border(BorderStroke(1.5.dp, if (s.done) colors.accent else colors.line2), shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (s.done) Icon(TvIcons.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(10.dp))
                        }
                        Text(
                            s.title,
                            style = MaterialTheme.typography.labelLarge.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null),
                            color = if (s.done) colors.faint else colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e is NTask && TvNotizenLogic.canMove(e)) {
                TvButton(stringResource(R.string.tv_notes_tomorrow), onClick = {
                    actions.onMoveTomorrow(e)
                    onDismiss()
                })
            }
            if (TvNotizenLogic.canTick(e)) {
                TvButton(stringResource(if (e.done) R.string.tv_notes_reopen else R.string.tv_notes_tick), onClick = {
                    actions.onToggle(e)
                    onDismiss()
                })
            }
            TvButton(stringResource(R.string.tv_dictation_close), onClick = onDismiss, modifier = Modifier.focusRequester(close))
        }
    }
}

// ─── notebooks: the notes as cards to read ─────────────────────────────

@Composable
private fun NotesColumn(ui: TvNotizenUi, onOpen: (NNoteSummary) -> Unit, modifier: Modifier) {
    val colors = GateControlTvTheme.colors
    Column(modifier.fillMaxHeight()) {
        val place = ui.place
        ColumnHead(placeTitle(ui, place), pluralStringResource(R.plurals.tv_notes_count, ui.notes.size, ui.notes.size).takeIf { ui.placeState == TvSourceState.OK })
        when {
            ui.notes.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ui.notes, key = { it.id }) { n ->
                    val title = n.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tv_notes_untitled)
                    Line(onClick = { onOpen(n) }, description = listOfNotNull(title, n.snippet).joinToString(", ")) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            n.snippet?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        n.check?.takeIf { it.total > 0 }?.let { Text("${it.done}/${it.total}", style = MaterialTheme.typography.labelMedium, color = colors.muted) }
                    }
                }
            }
            ui.placeState == TvSourceState.OK -> Note(stringResource(R.string.tv_notes_no_notes))
            ui.placeState == TvSourceState.LOADING -> Note(stringResource(R.string.tv_home_loading))
            ui.placeState == TvSourceState.OFFLINE -> Note(stringResource(R.string.tv_home_offline))
            else -> Note(stringResource(R.string.tv_notes_missing))
        }
    }
}

/** OK on a note: its text as a card, the Markdown simply drawn (headings, lists, bold). */
@Composable
private fun NoteDialog(summary: NNoteSummary, load: suspend (Long) -> NNote?, onDismiss: () -> Unit) {
    val colors = GateControlTvTheme.colors
    val close = remember { FocusRequester() }
    var note by remember { mutableStateOf<NNote?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(summary.id) {
        note = load(summary.id)
        loaded = true
        close.requestSoon()
    }
    TvDialog(onDismiss) {
        val title = summary.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tv_notes_untitled)
        val book = note?.notebookName
        Text(listOfNotNull(book, stringResource(R.string.tv_notes_note)).joinToString(" · ").uppercase(), style = MaterialTheme.typography.labelMedium, color = colors.faint)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
        when {
            !loaded -> Note(stringResource(R.string.tv_home_loading))
            note == null -> Note(stringResource(R.string.tv_notes_note_missing))
            note?.body.isNullOrBlank() -> Note(stringResource(R.string.tv_notes_note_empty))
            else -> NoteText(note?.body.orEmpty(), Modifier.heightIn(max = 300.dp))
        }
        TvButton(stringResource(R.string.tv_dictation_close), onClick = onDismiss, modifier = Modifier.focusRequester(close))
    }
}

/** A note's Markdown ([NoteMd]): headings, paragraphs, lists and check boxes (read only), quotes, code, bold and italic. */
@Composable
internal fun NoteText(md: String, modifier: Modifier = Modifier) {
    val colors = GateControlTvTheme.colors
    val blocks = remember(md) { NoteMd.blocks(md) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items(blocks.size) { i ->
            when (val b = blocks[i]) {
                is NoteMd.Heading -> Text(
                    inline(b.text),
                    style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    color = colors.text,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is NoteMd.Paragraph -> Text(inline(b.lines.joinToString(" ")), style = MaterialTheme.typography.bodyMedium, color = colors.text)
                is NoteMd.Quote -> Text(
                    inline(b.lines.joinToString(" ")),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = colors.muted,
                    modifier = Modifier.padding(start = 10.dp),
                )
                is NoteMd.Code -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = colors.text,
                    modifier = Modifier.fillMaxWidth().background(colors.panel2, RoundedCornerShape(6.dp)).padding(8.dp),
                )
                NoteMd.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(colors.line))
                is NoteMd.Table -> Column {
                    Text(b.head.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                    for (r in b.rows) Text(inline(r.joinToString("  ·  ")), style = MaterialTheme.typography.bodySmall, color = colors.text)
                }
                is NoteMd.Item -> Text(
                    buildAnnotatedString {
                        append((b.number?.let { "$it." } ?: "•") + "  ")
                        append(inline(b.text))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text,
                    modifier = Modifier.padding(start = (8 + 14 * b.depth).dp),
                )
                is NoteMd.Check -> Text(
                    buildAnnotatedString {
                        append(if (b.done) "☑  " else "☐  ")
                        append(inline(b.text))
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (b.done) TextDecoration.LineThrough else null),
                    color = if (b.done) colors.faint else colors.text,
                    modifier = Modifier.padding(start = (8 + 14 * b.depth).dp),
                )
            }
        }
    }
}

/** A line's inline Markdown: bold, italic, struck, code; links and attachments as their text only. */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    for (s in NoteMd.inline(text)) {
        if (s.image) continue
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = if (s.strike) TextDecoration.LineThrough else null,
            fontFamily = if (s.code) MonoFontFamily else null,
        )
        withStyle(style) { append(s.text) }
    }
}
