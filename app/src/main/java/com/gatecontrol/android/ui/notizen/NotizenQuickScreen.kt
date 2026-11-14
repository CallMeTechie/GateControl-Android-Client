package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.gatecontrol.android.ui.components.keyboardPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NArea
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NRepeat
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.notizen.QuickParse
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme

/** The colour of a kind of mark — the same in the text and on its chip. */
@Composable
private fun markColor(kind: String): Color {
    val dark = GateControlTheme.extraColors.isDark
    val c = when (kind) {
        "date", "time" -> Color(0xFFDBEAFE)
        "repeat" -> Color(0xFFEDE9FE)
        "important" -> Color(0xFFFDE7C8)
        "list" -> Color(0xFFDCEFE5)
        "person" -> Color(0xFFFCE7F3)
        else -> Color(0xFFE5E7EB)
    }
    return if (dark) c.copy(alpha = 0.28f) else c
}

/** What was understood, coloured in the line itself (mockup Schnell). */
private class MarksTransformation(private val marks: List<QuickParse.Mark>, private val colors: Map<String, Color>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = buildAnnotatedString {
            append(text)
            marks.filter { it.end <= text.length && it.start < it.end }.forEach { m ->
                addStyle(SpanStyle(background = colors[m.kind] ?: Color.Transparent, fontWeight = FontWeight.SemiBold), m.start, m.end)
            }
        }
        return TransformedText(s, OffsetMapping.Identity)
    }
}

/** „Schnell hinzufügen“ (mockup Schnell), route `notizen/quick`: a task or a note in plain language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotizenQuickScreen(nav: NotizenNav, vm: NotizenQuickViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    LaunchedEffect(ui.done) { if (ui.done) nav.back() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    /** The chip „Kalender“ was tapped: what it means (the calendar's name). */
    var calendarHint by remember { mutableStateOf<String?>(null) }
    val calendarText = calendarHint?.let { stringResource(R.string.notizen_quick_calendar_hint, it) }
    LaunchedEffect(calendarHint) { if (calendarText != null) { snack.showSnackbar(calendarText); calendarHint = null } }
    val x = GateControlTheme.extraColors
    val kinds = listOf("date", "time", "repeat", "important", "list", "person")
    val colors = kinds.associateWith { markColor(it) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().keyboardPadding()) {
            GcSubpageBar(stringResource(R.string.notizen_quick_title), nav.back, stringResource(R.string.notizen_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcSegmented(listOf(false to stringResource(R.string.notizen_quick_task), true to stringResource(R.string.notizen_quick_note)), ui.note, vm::setNote)
                Text(stringResource(if (ui.note) R.string.notizen_new_note else R.string.notizen_new_task), style = MaterialTheme.typography.titleMedium)
                BasicTextField(
                    ui.text,
                    vm::setText,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (ui.note) VisualTransformation.None else MarksTransformation(ui.parsed?.marks.orEmpty().filter { it.kind !in ui.off }, colors),
                    keyboardOptions = KeyboardOptions(imeAction = if (ui.note) ImeAction.Default else ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.save() }),
                    minLines = if (ui.note) 6 else 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, x.border2, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    decorationBox = { inner ->
                        Box { if (ui.text.isEmpty()) Text(stringResource(if (ui.note) R.string.notizen_quick_note_placeholder else R.string.notizen_quick_placeholder), color = x.faint, style = MaterialTheme.typography.bodyLarge); inner() }
                    },
                )
                if (!ui.note) {
                    val e = vm.effective()
                    val p = ui.parsed
                    if (e != null && p != null && ui.text.isNotBlank()) {
                        Text(stringResource(R.string.notizen_quick_understood), style = MaterialTheme.typography.labelLarge, color = x.muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val lang = ui.me?.lang ?: "de"
                            if (e.due != null) {
                                Chip(listOfNotNull(NotizenFormat.shortDate(e.due.date, lang), NotizenFormat.time(e.due.time)).joinToString(", "), colors.getValue("date"), GcIcons.Calendar) { dialog = "date" }
                            } else {
                                Chip(stringResource(R.string.notizen_quick_add_date), x.panel2, GcIcons.Calendar) { dialog = "date" }
                            }
                            e.remind?.time?.let { Chip(stringResource(R.string.notizen_quick_remind, NotizenFormat.time(it) ?: it), colors.getValue("time"), GcIcons.Bell) { vm.drop("time") } }
                            // plugin 1.3.0: a task with a time becomes an event in my calendar in Mail (only while Mail
                            // offers it and the administrator and I have it on) — the chip says so, the switch is in the settings
                            val calendar = ui.me?.takeIf { it.calendarShown && e.due?.time != null }?.calendar
                            if (calendar != null) {
                                val name = calendar.name ?: stringResource(R.string.notizen_default_list)
                                Chip(stringResource(R.string.notizen_quick_calendar, name), MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), GcIcons.Calendar) { calendarHint = name }
                            }
                            e.repeat?.let { Chip(NRepeat.describe(it, lang), colors.getValue("repeat"), GcIcons.Refresh) { vm.drop("repeat") } }
                            if (e.important) Chip(stringResource(R.string.notizen_important), colors.getValue("important"), GcIcons.Star) { vm.drop("important") }
                            val listName = e.listId?.let { id -> ui.nav?.lists?.firstOrNull { it.id == id }?.name } ?: e.newList?.let { stringResource(R.string.notizen_quick_new_list, it) }
                            Chip(stringResource(R.string.notizen_quick_list, listName ?: (ui.nav?.lists?.firstOrNull { it.isDefault }?.name ?: stringResource(R.string.notizen_default_list))), colors.getValue("list"), GcIcons.Tasks) { dialog = "list" }
                            if (e.assigneeId != null) {
                                Chip(ui.people.firstOrNull { it.id == e.assigneeId }?.name ?: p.assignee?.name.orEmpty(), colors.getValue("person"), GcIcons.User) { vm.drop("person") }
                            } else if (p.assigneeName != null && "person" !in ui.off) {
                                Text(stringResource(R.string.notizen_quick_no_person, p.assigneeName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            Chip(areaLabel(ui.area), x.panel2, if (ui.area == NArea.WORK) GcIcons.Briefcase else GcIcons.House) { vm.setArea(if (ui.area == NArea.WORK) NArea.PRIVATE else NArea.WORK) }
                        }
                    }
                    Text(stringResource(R.string.notizen_quick_help), style = MaterialTheme.typography.bodySmall, color = x.muted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(areaLabel(ui.area), x.panel2, if (ui.area == NArea.WORK) GcIcons.Briefcase else GcIcons.House) { vm.setArea(if (ui.area == NArea.WORK) NArea.PRIVATE else NArea.WORK) }
                    }
                    Text(stringResource(R.string.notizen_quick_note_help), style = MaterialTheme.typography.bodySmall, color = x.muted)
                }
            }
            GcPrimaryButton(
                stringResource(R.string.notizen_add),
                vm::save,
                enabled = ui.text.isNotBlank() && ui.me?.readOnly != true,
                loading = ui.busy,
                modifier = Modifier.navigationBarsPadding().padding(16.dp),
            )
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) { Snackbar(it) }
    }
    when (dialog) {
        "date" -> {
            var day by rememberSaveable { mutableStateOf<String?>(null) }
            val e = vm.effective()
            val today = ui.me?.today ?: java.time.LocalDate.now().toString()
            if (day == null) {
                NDatePicker(e?.due?.date, today, { day = it }, { dialog = null }, onClear = { dialog = null; vm.drop("date"); vm.drop("time") })
            } else {
                NTimePicker(e?.due?.time, { t -> dialog = null; vm.pickDue(NDue(day!!, t)) }, { dialog = null })
            }
        }
        "list" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.notizen_list)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ui.nav?.lists.orEmpty().filter { it.canEdit }.forEach { l ->
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { dialog = null; vm.pickList(l.id) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.notizen_cancel)) } },
        )
    }
}

/** A chip of what was understood; a tap changes or removes it. */
@Composable
private fun Chip(label: String, bg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
