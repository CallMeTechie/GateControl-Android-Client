package com.gatecontrol.android.ui.drive

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DAnswer
import com.gatecontrol.android.drive.DDeadline
import com.gatecontrol.android.drive.DSource
import com.gatecontrol.android.drive.DriveFiling
import com.gatecontrol.android.drive.DriveKi
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChip
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// ─── Drive 1.14: AI in Drive — Scan prüfen (D5), Ablegen (D4), Fristen (D2), Fragen (D1) ──

/** "28.11." / "Nov 28" of a `YYYY-MM-DD`; with the weekday "Sa 28.11." / "Sat, Nov 28". */
internal fun kiDay(date: String?, weekday: Boolean = false): String? = date?.let {
    runCatching {
        val d = LocalDate.parse(it)
        val de = Locale.getDefault().language == "de"
        val pattern = when {
            weekday && de -> "EE dd.MM."
            weekday -> "EEE, MMM d"
            de -> "dd.MM."
            else -> "MMM d"
        }
        val withYear = if (d.year != LocalDate.now().year) (if (de) "$pattern yyyy" else "$pattern, yyyy") else pattern
        DateTimeFormatter.ofPattern(withYear, Locale.getDefault()).format(d).replace("..", ".")
    }.getOrNull()
}

/** A folder path of a suggestion ("Finanzen/Strom" → "Finanzen › Strom"; empty: the top folder). */
@Composable
internal fun kiPath(path: String?): String =
    path?.split('/')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(" › ") ?: stringResource(R.string.drive_insight_top)

/** A small button of a card (`.btn`): outlined, or tinted with a tick when [done]. */
@Composable
internal fun KiButton(text: String, icon: ImageVector, done: Boolean = false, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (done) x.accentBg else Color.Transparent)
            .border(1.dp, if (done) Color.Transparent else x.border2, shape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = x.muted)
        } else {
            Icon(if (done) GcIcons.Check else icon, contentDescription = null, tint = if (done) x.accentText else x.muted, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (done) x.accentText else if (enabled) MaterialTheme.colorScheme.onSurface else x.faint,
        )
    }
}

/** Its children side by side, wrapping to the next line when the row is full ([gap] between them). */
@Composable
internal fun KiFlow(modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = 8.dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val g = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val spots = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var rowH = 0
        var width = 0
        placeables.forEach { p ->
            if (x > 0 && x + p.width > max) {
                x = 0
                y += rowH + g
                rowH = 0
            }
            spots += x to y
            x += p.width + g
            width = maxOf(width, x - g)
            rowH = maxOf(rowH, p.height)
        }
        val height = if (placeables.isEmpty()) 0 else y + rowH
        layout(width.coerceIn(constraints.minWidth, max), height.coerceAtLeast(constraints.minHeight)) {
            placeables.forEachIndexed { i, p -> p.placeRelative(spots[i].first, spots[i].second) }
        }
    }
}

// ─── D2: a deadline as a card ───────────────────────────────────────

internal fun deadlineTone(kind: String): GcTone = when (kind) {
    "cancellation" -> GcTone.Error
    "payment" -> GcTone.Warn
    "warranty" -> GcTone.Info
    "renewal" -> GcTone.Pro
    else -> GcTone.Neutral
}

/**
 * A deadline (mockup "Fristen"): its kind, the day and how far off, what it is about, the file; "In den
 * Familienkalender" (with `deadlineCalendar`) and "Erinnern" (the days before to choose).
 */
@Composable
internal fun DeadlineCard(
    d: DDeadline,
    fresh: Boolean,
    calendar: Boolean,
    calendarBusy: Boolean,
    onCalendar: () -> Unit,
    onRemind: (DDeadline, Int?) -> Unit,
    onFile: (() -> Unit)?,
) {
    val x = GateControlTheme.extraColors
    val today = remember { LocalDate.now() }
    val left = DriveLogic.daysUntil(d.date, today)
    val day = kiDay(d.date, weekday = true) ?: d.date
    GcCard(
        modifier = if (onFile != null) Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onFile) else Modifier,
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcChip(stringResource(deadlineLabel(d.kind)), tone = deadlineTone(d.kind), small = true)
            val whenText = if (d.kind == "renewal") stringResource(R.string.drive_ki_renewal_until, day) else listOfNotNull(day, deadlineWhen(left)).joinToString(" · ")
            Text(whenText, style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.weight(1f, fill = false))
            if (fresh) GcChip(stringResource(R.string.drive_ki_new), tone = GcTone.Ok, small = true)
        }
        val file = d.node?.let { nodeTitle(it) }
        Text(d.label ?: file ?: stringResource(deadlineLabel(d.kind)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (d.label != null && file != null) Text(file, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val open = DriveLogic.canRemind(d, today)
        if (open || d.reminder != null || d.calendar != null) {
            KiFlow {
                if (calendar && (d.calendar != null || DriveKi.calendarOpen(d, today))) {
                    if (d.calendar != null) {
                        KiButton(stringResource(R.string.drive_ki_calendar_done), GcIcons.Calendar, done = true, enabled = false) {}
                    } else {
                        KiButton(stringResource(R.string.drive_ki_calendar_add), GcIcons.Calendar, busy = calendarBusy, onClick = onCalendar)
                    }
                }
                RemindButton(d, open, onRemind)
            }
        }
    }
}

/** "Erinnern" → how many days before; set: "Erinnerung N Tage vorher ✓" (another day, or none). */
@Composable
private fun RemindButton(d: DDeadline, open: Boolean, onRemind: (DDeadline, Int?) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    val r = d.reminder
    Box {
        when {
            r?.sent == true -> KiButton(stringResource(R.string.drive_deadline_sent), GcIcons.Bell, done = true, enabled = false) {}
            r != null -> KiButton(remindDoneText(r.daysBefore), GcIcons.Bell, done = true, enabled = open) { pick = true }
            open -> KiButton(stringResource(R.string.drive_ki_remind), GcIcons.Bell) { pick = true }
            else -> return@Box
        }
        DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
            DriveLogic.REMIND_DAYS.forEach { n ->
                DropdownMenuItem(
                    text = { Text(remindText(n)) },
                    leadingIcon = { if (n == r?.daysBefore) Icon(GcIcons.Check, contentDescription = null) },
                    onClick = { pick = false; onRemind(d, n) },
                )
            }
            if (r != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drive_ki_remind_off)) },
                    leadingIcon = { Icon(GcIcons.BellOff, contentDescription = null) },
                    onClick = { pick = false; onRemind(d, null) },
                )
            }
        }
    }
}

@Composable
private fun remindDoneText(days: Int): String =
    if (days == 0) stringResource(R.string.drive_ki_reminded_day0) else pluralStringResource(R.plurals.drive_ki_reminded_days, days, days)

// ─── D4: the filing bar ─────────────────────────────────────────────

/**
 * "Vorschlag · Ablegen als <Name> in <Pfad>" (mockup "Ablegen") of a file just uploaded, with "An die Zahlung am …
 * erinnern" for a payment deadline: "Übernehmen", "Anderer Ordner", ✕.
 */
@Composable
internal fun FilingBar(
    p: DriveFiling.Proposal,
    onAccept: (folder: Boolean, remind: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val x = GateControlTheme.extraColors
    var remind by rememberSaveable(p.node.id) { mutableStateOf(true) }
    val desc = stringResource(R.string.drive_ki_filing_title)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, x.border2),
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(14.dp).semantics { paneTitle = desc },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(GcIcons.Zap, contentDescription = null, tint = x.accentText, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.drive_ki_suggestion), style = MaterialTheme.typography.titleSmall, color = x.accentText)
            }
            val f = p.filing
            if (f.name != null) {
                Text(stringResource(R.string.drive_ki_file_as), style = MaterialTheme.typography.bodySmall, color = x.muted)
                Text(f.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            } else {
                Text(nodeTitle(p.node), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (f.folderId != null) {
                Text(stringResource(R.string.drive_ki_file_in), style = MaterialTheme.typography.bodySmall, color = x.muted)
                Text(kiPath(f.folderPath), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            f.payment?.let { pay ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(value = remind, role = Role.Checkbox, onValueChange = { remind = it }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(checked = remind, onCheckedChange = null)
                    Text(stringResource(R.string.drive_ki_filing_remind, kiDay(pay.date) ?: pay.date), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GcPrimaryButton(stringResource(R.string.drive_ki_accept), onClick = { onAccept(true, remind) }, modifier = Modifier.weight(1f))
                if (f.folderId != null || f.name != null) {
                    GcOutlineButton(stringResource(R.string.drive_ki_other_folder), onClick = { onAccept(false, remind) }, fillWidth = false)
                }
                GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_ki_filing_dismiss), onClick = onDismiss, size = 44.dp, iconSize = 18.dp)
            }
        }
    }
}

// ─── D5: "Scan prüfen" ──────────────────────────────────────────────

@Composable
private fun formatLabel(f: DriveKi.Format): String = when (f) {
    DriveKi.Format.PDF -> "PDF"
    DriveKi.Format.DOCX -> stringResource(R.string.drive_ki_format_word)
    DriveKi.Format.XLSX -> stringResource(R.string.drive_ki_format_excel)
    DriveKi.Format.IMAGE -> stringResource(R.string.drive_ki_format_image)
}

private fun kindLabel(kind: String): Int = when (kind) {
    DriveKi.KIND_PRINTED -> R.string.drive_ki_kind_printed
    DriveKi.KIND_HANDWRITING -> R.string.drive_ki_kind_handwriting
    DriveKi.KIND_TABLE -> R.string.drive_ki_kind_table
    DriveKi.KIND_WHITEBOARD -> R.string.drive_ki_kind_whiteboard
    else -> R.string.drive_ki_kind_other
}

/**
 * "Scan prüfen" (mockups "Main" and "Whiteboard", Drive 1.14 `scanDocs`): what Drive recognised, the format (PDF ·
 * Word · Excel · Nur Bild), a preview, name and folder, "Original-Scan behalten", the cost line and the save button.
 */
@Composable
internal fun ScanReviewDialog(
    draft: DriveFolderViewModel.ScanDraft,
    onFormat: (DriveKi.Format) -> Unit,
    onName: (String) -> Unit,
    onPickFolder: () -> Unit,
    onKeepOriginal: (Boolean) -> Unit,
    onSave: (base: String) -> Unit,
    onClose: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val fallback = remember(draft.scan) { "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.GERMANY).format(Date()) }
    val today = remember(draft.scan) { LocalDate.now() }
    val base = draft.name ?: DriveKi.scanName(DriveKi.title(draft.lines), today, fallback)
    val format = draft.formatNow
    val kind = draft.kindNow
    val known = draft.kind != null && kind != DriveKi.KIND_OTHER
    val waiting = draft.ai && !draft.classified
    var renaming by remember { mutableStateOf(false) }
    val first by produceState<Bitmap?>(null, draft.scan) { value = withContext(Dispatchers.IO) { draft.scan.pages.firstOrNull()?.let { decodeSmall(context, it, 900) } } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_cancel), onClick = onClose, tint = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(R.string.drive_ki_scan_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        pluralStringResource(R.plurals.drive_scan_pages, draft.scan.pages.size, draft.scan.pages.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // what was recognised
                    GcCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(width = 52.dp, height = 68.dp).clip(RoundedCornerShape(6.dp)).background(x.panel2), contentAlignment = Alignment.Center) {
                                first?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (waiting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = x.accentText)
                                    Text(
                                        when {
                                            waiting -> stringResource(R.string.drive_ki_recognising)
                                            known -> stringResource(R.string.drive_ki_recognised, stringResource(kindLabel(kind)))
                                            draft.texts?.any { it != null } == true -> stringResource(R.string.drive_scan_text_yes)
                                            draft.texts == null -> stringResource(R.string.drive_scan_text_running)
                                            else -> stringResource(R.string.drive_scan_text_no)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = x.accentText,
                                    )
                                }
                                draft.kind?.summary?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                                Text(question(format, kind), style = MaterialTheme.typography.bodySmall, color = x.muted)
                            }
                        }
                    }
                    // the format
                    KiFlow(Modifier.selectableGroup()) {
                        draft.formats.forEach { f -> GcFilterChip(formatLabel(f), selected = f == format, onClick = { onFormat(f) }) }
                    }
                    // the preview
                    GcCard(contentPadding = PaddingValues(12.dp)) {
                        Text(stringResource(R.string.drive_ki_preview), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        ScanPreview(draft, format, kind, first)
                        Text(note(format, kind), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    }
                    // name, folder, original
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        ChangeRow(stringResource(R.string.drive_scan_name), DriveKi.fileName(base, format)) { renaming = true }
                        GcRowDivider()
                        ChangeRow(stringResource(R.string.drive_ki_file_in_label), draft.folderLabel, onPickFolder)
                        if (format.target != null) {
                            GcRowDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = draft.keepOriginal, role = Role.Switch, onValueChange = onKeepOriginal)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.drive_ki_keep_original), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                GcSwitch(checked = draft.keepOriginal, onCheckedChange = null)
                            }
                        }
                    }
                    Text(cost(format), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, x.border)) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        GcPrimaryButton(
                            saveLabel(format),
                            onClick = { onSave(DriveKi.cleanName(base) ?: fallback) },
                            enabled = !draft.saving && (format.target == null || draft.aiReady),
                            loading = draft.saving,
                        )
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = base,
            confirm = stringResource(R.string.drive_save),
            onConfirm = { renaming = false; onName(it) },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun ChangeRow(label: String, value: String, onChange: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = x.muted)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        GcFilterChip(stringResource(R.string.drive_ki_change), selected = false, onClick = onChange)
    }
}

@Composable
private fun question(format: DriveKi.Format, kind: String): String = stringResource(
    when (format) {
        DriveKi.Format.XLSX -> R.string.drive_ki_ask_xlsx
        DriveKi.Format.DOCX -> if (kind == DriveKi.KIND_WHITEBOARD) R.string.drive_ki_ask_docx_outline else R.string.drive_ki_ask_docx
        DriveKi.Format.PDF -> R.string.drive_ki_ask_pdf
        DriveKi.Format.IMAGE -> R.string.drive_ki_ask_image
    },
)

@Composable
private fun note(format: DriveKi.Format, kind: String): String = stringResource(
    when (format) {
        DriveKi.Format.XLSX -> R.string.drive_ki_note_xlsx
        DriveKi.Format.PDF -> R.string.drive_ki_note_pdf
        DriveKi.Format.IMAGE -> R.string.drive_ki_note_image
        DriveKi.Format.DOCX -> when (kind) {
            DriveKi.KIND_TABLE -> R.string.drive_ki_note_docx_table
            DriveKi.KIND_WHITEBOARD -> R.string.drive_ki_note_docx_whiteboard
            DriveKi.KIND_HANDWRITING -> R.string.drive_ki_note_docx_handwriting
            else -> R.string.drive_ki_note_docx
        }
    },
)

@Composable
private fun cost(format: DriveKi.Format): String = stringResource(
    when (format) {
        DriveKi.Format.PDF -> R.string.drive_ki_cost_pdf
        DriveKi.Format.IMAGE -> R.string.drive_ki_cost_image
        else -> R.string.drive_ki_cost_ai
    },
)

@Composable
private fun saveLabel(format: DriveKi.Format): String = stringResource(
    when (format) {
        DriveKi.Format.XLSX -> R.string.drive_ki_save_xlsx
        DriveKi.Format.DOCX -> R.string.drive_ki_save_docx
        DriveKi.Format.PDF -> R.string.drive_ki_save_pdf
        DriveKi.Format.IMAGE -> R.string.drive_ki_save_image
    },
)

/** Excel: the first rows as cells (A, B, C …); Word: the beginning of the text; PDF and picture: the first page. */
@Composable
private fun ScanPreview(draft: DriveFolderViewModel.ScanDraft, format: DriveKi.Format, kind: String, first: Bitmap?) {
    val x = GateControlTheme.extraColors
    val grid = remember(draft.reads) { DriveKi.tableGrid(draft.reads?.firstOrNull()?.lines.orEmpty()) }
    when (format) {
        DriveKi.Format.XLSX -> if (grid.isNotEmpty()) SheetGrid(grid) else TextPreview(DriveKi.docPreview(draft.text))
        DriveKi.Format.DOCX -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val lines = DriveKi.docPreview(draft.text)
            if (kind == DriveKi.KIND_TABLE && grid.isNotEmpty()) {
                DriveKi.title(draft.lines)?.let { Text(it, color = Color(0xFF1A1D22), fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                grid.take(3).forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { cell ->
                            Text(cell, color = Color(0xFF1A1D22), fontSize = 11.sp, fontWeight = if (r == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(4.dp))
                        }
                    }
                }
            } else if (lines.isEmpty()) {
                Text(stringResource(R.string.drive_scan_text_no), color = Color(0xFF465262), fontSize = 13.sp)
            } else {
                lines.forEachIndexed { i, l ->
                    Text(l, color = Color(0xFF1A1D22), fontSize = if (i == 0) 14.sp else 12.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DriveKi.Format.PDF, DriveKi.Format.IMAGE -> Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)).background(x.panel2),
            contentAlignment = Alignment.Center,
        ) {
            if (first != null) {
                Image(first.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = x.muted)
            }
        }
    }
}

@Composable
private fun TextPreview(lines: List<String>) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(x.panel2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (lines.isEmpty()) Text(stringResource(R.string.drive_scan_text_no), style = MaterialTheme.typography.bodySmall, color = x.muted)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** The first rows as a sheet: column letters on top, row numbers on the left, the first row in bold. */
@Composable
private fun SheetGrid(rows: List<List<String>>) {
    val x = GateControlTheme.extraColors
    val cols = rows.maxOf { it.size }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, x.border, shape)) {
        Row(Modifier.fillMaxWidth().background(x.panel2)) {
            SheetCell("", head = true, modifier = Modifier.width(28.dp))
            (0 until cols).forEach { c -> SheetCell(('A' + c).toString(), head = true, modifier = Modifier.weight(1f)) }
        }
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                SheetCell((r + 1).toString(), head = true, modifier = Modifier.width(28.dp))
                (0 until cols).forEach { c ->
                    SheetCell(row.getOrElse(c) { "" }, bold = r == 0, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SheetCell(text: String, modifier: Modifier, head: Boolean = false, bold: Boolean = false) {
    val x = GateControlTheme.extraColors
    Text(
        text,
        modifier = modifier.border(0.5.dp, x.border).padding(horizontal = 6.dp, vertical = 5.dp),
        fontSize = 12.sp,
        color = if (head) x.faint else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ─── D1: the answer card ────────────────────────────────────────────

/** "Meine Dateien und Familie" / "A, B and C". */
@Composable
internal fun joinLabels(labels: List<String>): String {
    val and = stringResource(R.string.drive_ki_and)
    return when (labels.size) {
        0 -> ""
        1 -> labels[0]
        else -> labels.dropLast(1).joinToString(", ") + " $and " + labels.last()
    }
}

/** [text] with its dates in bold. */
internal fun boldDates(text: String): AnnotatedString {
    val b = AnnotatedString.Builder(text)
    DriveKi.dateSpans(text).forEach { r -> b.addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1) }
    return b.toAnnotatedString()
}

/**
 * The answer to a question (mockup "Fragen"): the answer with its dates in bold, "Frist in den Familienkalender" when a
 * recognised deadline of a source fits, the sources with their quotes (a tap opens the file); nothing found: "Dazu
 * habe ich in deinen Dateien nichts gefunden." with what was searched for and where.
 */
@Composable
internal fun AnswerCard(
    a: DAnswer,
    areas: String,
    deadline: DDeadline?,
    calendar: Boolean,
    calendarBusy: Boolean,
    onCalendar: (DDeadline) -> Unit,
    onSource: (DSource) -> Unit,
) {
    val x = GateControlTheme.extraColors
    if (!a.answered) {
        GcCard {
            Text(stringResource(R.string.drive_ki_ask_none), style = MaterialTheme.typography.titleMedium)
            val terms = a.terms.joinToString(", ")
            val areas = joinLabels(a.areas).ifEmpty { areas }
            Text(
                if (terms.isEmpty()) stringResource(R.string.drive_ki_ask_where, areas) else stringResource(R.string.drive_ki_ask_searched, terms, areas),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
            )
        }
        return
    }
    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(boldDates(a.answer.orEmpty()), style = MaterialTheme.typography.bodyLarge)
            if (deadline != null) {
                // the deadline the answer names: its kind, day and what it is
                val today = remember { LocalDate.now() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcChip(stringResource(deadlineLabel(deadline.kind)), tone = deadlineTone(deadline.kind), small = true)
                    Text(
                        listOfNotNull(kiDay(deadline.date, weekday = true) ?: deadline.date, deadlineWhen(DriveLogic.daysUntil(deadline.date, today)), deadline.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = x.muted,
                    )
                }
                if (calendar && deadline.calendar != null) {
                    KiButton(stringResource(R.string.drive_ki_calendar_done), GcIcons.Calendar, done = true, enabled = false) {}
                } else if (calendar && DriveKi.calendarOpen(deadline, today)) {
                    KiButton(stringResource(R.string.drive_ki_ask_calendar), GcIcons.Calendar, busy = calendarBusy) { onCalendar(deadline) }
                }
            }
        }
        if (a.sources.isNotEmpty()) {
            GcRowDivider()
            Text(
                pluralStringResource(R.plurals.drive_ki_sources, a.sources.size, a.sources.size),
                style = MaterialTheme.typography.bodySmall,
                color = x.muted,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
            )
            a.sources.forEach { s ->
                SourceRow(s) { onSource(s) }
            }
        }
    }
}

@Composable
private fun SourceRow(s: DSource, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val ext = s.name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
    val look = kindLook(DriveLogic.kind(null, s.name))
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(look.bg), contentAlignment = Alignment.Center) {
            Text(ext.ifEmpty { "…" }, color = look.fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val where = listOfNotNull(s.path?.split('/')?.filter { it.isNotBlank() }?.joinToString(" › ")?.ifEmpty { null }, s.page?.let { stringResource(R.string.drive_ki_page, it) })
            if (where.isNotEmpty()) Text(where.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
            s.quote?.let {
                Text(
                    "„$it“",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
