package com.gatecontrol.android.ui.notizen

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NAttachment
import com.gatecontrol.android.notizen.NDue
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NPersonPick
import com.gatecontrol.android.notizen.NRepeat
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitch
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A file the person picked or took: its bytes (at most [max]), type and name. */
internal fun readPicked(context: android.content.Context, uri: Uri, max: Long): Triple<ByteArray, String, String>? = runCatching {
    val cr = context.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Datei"
    val mime = cr.getType(uri) ?: "application/octet-stream"
    val bytes = cr.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return@use null
        }
        out.toByteArray()
    } ?: return null
    Triple(bytes, mime, name)
}.getOrNull()

/** A day of the hub (`YYYY-MM-DD`); [onClear] takes it away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NDatePicker(initial: String?, today: String, onPick: (String) -> Unit, onDismiss: () -> Unit, onClear: (() -> Unit)? = null) {
    val start = runCatching { LocalDate.parse(initial ?: today) }.getOrElse { LocalDate.now() }
    val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick((state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start).toString()) }) {
                Text(stringResource(R.string.notizen_ok))
            }
        },
        dismissButton = {
            Row {
                onClear?.let { TextButton(onClick = it) { Text(stringResource(R.string.notizen_clear)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) }
            }
        },
    ) { DatePicker(state = state) }
}

/** A time `HH:MM`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NTimePicker(initial: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val h = initial?.substring(0, 2)?.toIntOrNull() ?: 9
    val m = initial?.substring(3, 5)?.toIntOrNull() ?: 0
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text(stringResource(R.string.notizen_ok)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.notizen_no_time)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) }
            }
        },
    )
}

/** „Wiederholen“: never, daily, weekdays, weekly on the due day, every 2 weeks, monthly, yearly, n days after completion. */
@Composable
internal fun RepeatDialog(current: NRepeat.Rule?, dueDay: String?, today: String, lang: String, onPick: (NRepeat.Rule?) -> Unit, onDismiss: () -> Unit) {
    val wd = NRepeat.weekday(dueDay ?: today) ?: 1
    var after by rememberSaveable { mutableStateOf("3") }
    val options = listOf(
        null,
        NRepeat.Rule("daily", 1),
        NRepeat.Rule("weekly", 1, listOf(1, 2, 3, 4, 5)),
        NRepeat.Rule("weekly", 1, listOf(wd)),
        NRepeat.Rule("weekly", 2, listOf(wd)),
        NRepeat.Rule("monthly", 1),
        NRepeat.Rule("yearly", 1),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_repeat)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { r ->
                    val selected = NRepeat.normalize(r) == NRepeat.normalize(current)
                    Text(
                        NRepeat.describe(r, lang),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (selected) FontWeight.Bold else null),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.RadioButton) { onPick(r) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(after, { after = it.filter(Char::isDigit).take(3) }, modifier = Modifier.size(width = 80.dp, height = 56.dp), singleLine = true)
                    TextButton(onClick = { after.toIntOrNull()?.takeIf { it in 1..999 }?.let { onPick(NRepeat.Rule("after", it)) } }) {
                        Text(stringResource(R.string.notizen_repeat_after))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** „Zugewiesen“: nobody, me, or a person the server lets one share with. */
@Composable
internal fun PeopleDialog(people: List<NPersonPick>, current: Long?, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notizen_assigned)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.notizen_nobody), modifier = Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 12.dp))
                people.forEach { p ->
                    Text(
                        if (p.me) stringResource(R.string.notizen_me_name, p.name) else p.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (p.id == current) FontWeight.Bold else null),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/** One row of the details (`label … value ›`). */
@Composable
internal fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: (() -> Unit)?, trailing: (@Composable () -> Unit)? = null) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = x.muted, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else Text(value, style = MaterialTheme.typography.titleSmall, color = x.muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
}

/** One task (mockup Aufgabe), route `notizen/task/<id>`. */
@Composable
fun NotizenTaskScreen(nav: NotizenNav, vm: NotizenTaskViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val x = GateControlTheme.extraColors
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val maxBytes = (ui.me?.attachMB ?: 25) * 1024L * 1024L
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { readPicked(context, uri, maxBytes) }
            if (f == null) vm.consumeMessage().also { snack.showSnackbar(context.getString(R.string.notizen_msg_too_large)) } else vm.attach(f.first, f.second, f.third)
        }
    }
    val d = ui.detail
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GcSubpageBar(
                title = listOfNotNull(d?.listArea?.let { areaLabel(it) }, d?.listName).joinToString(" › "),
                onBack = nav.back,
                backDescription = stringResource(R.string.notizen_back),
            ) {
                if (d != null) {
                    GcIconButton(if (d.task.important) GcIcons.StarFilled else GcIcons.Star, stringResource(R.string.notizen_important), { vm.setImportant(!d.task.important) }, tint = if (d.task.important) androidx.compose.ui.graphics.Color(0xFFB45309) else x.muted)
                    Box {
                        GcIconButton(GcIcons.MoreVertical, stringResource(R.string.notizen_more), { more = true })
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_delete)) }, onClick = { more = false; vm.delete(nav.back) }, leadingIcon = { Icon(GcIcons.Trash, null) })
                        }
                    }
                }
            }
            if (d == null) {
                if (ui.loading) NotizenLoading() else Text(stringResource(if (ui.gone) R.string.notizen_msg_not_found else R.string.notizen_msg_offline), modifier = Modifier.padding(24.dp))
                return@Column
            }
            val me = ui.me
            val today = me?.today ?: LocalDate.now().toString()
            val lang = me?.lang ?: "de"
            val words = Triple(stringResource(R.string.notizen_today_word_cap), stringResource(R.string.notizen_tomorrow_word_cap), stringResource(R.string.notizen_yesterday_word_cap))
            var title by rememberSaveable(d.task.id) { mutableStateOf(d.task.title) }
            var note by rememberSaveable(d.task.id) { mutableStateOf(d.note.orEmpty()) }
            var sub by rememberSaveable { mutableStateOf("") }
            NOfflineHint(ui.offline, 0)
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NCheck(d.task.done, stringResource(R.string.notizen_mark_done), vm::complete)
                        androidx.compose.foundation.text.BasicTextField(
                            title,
                            { title = it.take(500) },
                            textStyle = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                textDecoration = if (d.task.done) TextDecoration.LineThrough else null,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.setTitle(title) }),
                            modifier = Modifier.weight(1f).padding(8.dp).onFocusLost { vm.setTitle(title) },
                        )
                    }
                }
                d.sourceItem?.let { src ->
                    item { SourceLine(src, nav) }
                }
                item {
                    NCard(Modifier.padding(top = 12.dp)) {
                        d.subtasks.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 4.dp)) {
                                NCheck(s.done, stringResource(R.string.notizen_done_cd, s.title), { vm.toggleSub(s.id, !s.done) })
                                Text(s.title, style = MaterialTheme.typography.bodyMedium.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null), color = if (s.done) x.faint else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                GcIconButton(GcIcons.Close, stringResource(R.string.notizen_delete_sub, s.title), { vm.deleteSub(s.id) }, iconSize = 16.dp)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp)) {
                            Icon(GcIcons.Plus, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                sub,
                                { sub = it.take(500) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { vm.addSubtask(sub); sub = "" }),
                                decorationBox = { inner -> Box(Modifier.padding(12.dp)) { if (sub.isEmpty()) Text(stringResource(R.string.notizen_subtask), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium); inner() } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    NCard(Modifier.padding(top = 12.dp)) {
                        DetailRow(GcIcons.Sun, stringResource(R.string.notizen_in_my_day), "", null) { GcSwitch(d.task.myDay, { vm.setMyDay(it) }) }
                        DetailRow(GcIcons.Calendar, stringResource(R.string.notizen_due), NotizenFormat.due(d.task.due?.copy(time = d.task.due.time), today, lang, words) ?: stringResource(R.string.notizen_none), { dialog = "due" })
                        val remind = NotizenFormat.remindParts(d.task.remindAt)
                        DetailRow(GcIcons.Bell, stringResource(R.string.notizen_reminder), NotizenFormat.due(remind, today, lang, words) ?: stringResource(R.string.notizen_none), { dialog = "remind" })
                        DetailRow(GcIcons.Refresh, stringResource(R.string.notizen_repeat), NRepeat.describe(d.task.repeat, lang), { dialog = "repeat" })
                        DetailRow(GcIcons.User, stringResource(R.string.notizen_assigned), d.task.assignee?.let { a -> if (a.id == me?.userId) stringResource(R.string.notizen_me) else a.name } ?: stringResource(R.string.notizen_nobody), { dialog = "people" })
                    }
                }
                item {
                    NCard(Modifier.padding(top = 12.dp)) {
                        d.attachmentList.forEach { a -> AttachmentRow(a, vm, scope, context) }
                        DetailRow(GcIcons.Paperclip, stringResource(R.string.notizen_attach), "", { pick.launch(arrayOf("*/*")) }) { Icon(GcIcons.Plus, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.notizen_note), style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(note, { note = it.take(20_000) }, modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).onFocusLost { vm.setNote(note) }, placeholder = { Text(stringResource(R.string.notizen_note_hint)) })
                        val src = d.sourceItem
                        if (src != null) Text(stringResource(R.string.notizen_done_there, src.sourceLabel), style = MaterialTheme.typography.bodySmall, color = x.muted)
                    }
                }
            }
            when (dialog) {
                "due" -> NDatePicker(d.task.due?.date, today, { day -> dialog = null; vm.setDue(NDue(day, d.task.due?.time)) }, { dialog = null }, onClear = { dialog = null; vm.setDue(null) })
                "remind" -> {
                    var day by rememberSaveable { mutableStateOf<String?>(null) }
                    val r = NotizenFormat.remindParts(d.task.remindAt)
                    if (day == null) NDatePicker(r?.date ?: d.task.due?.date, today, { day = it }, { dialog = null }, onClear = { dialog = null; vm.setRemind(null) })
                    else NTimePicker(r?.time ?: d.task.due?.time ?: "09:00", { t -> dialog = null; vm.setRemind(NDue(day!!, t ?: "09:00")) }, { dialog = null })
                }
                "repeat" -> RepeatDialog(d.task.repeat, d.task.due?.date, today, lang, { r -> dialog = null; vm.setRepeat(r) }, { dialog = null })
                "people" -> PeopleDialog(ui.people, d.task.assignee?.id, { p -> dialog = null; vm.setAssignee(p) }, { dialog = null })
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
    }
}

@Composable
private fun AttachmentRow(a: NAttachment, vm: NotizenTaskViewModel, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    DetailRow(GcIcons.File, a.name ?: stringResource(R.string.notizen_attachment), "", {
        scope.launch {
            val bytes = vm.download(a) ?: return@launch
            openBytes(context, bytes, a.mime ?: "application/octet-stream", a.name ?: "datei")
        }
    }) { GcIconButton(GcIcons.Trash, stringResource(R.string.notizen_delete), { vm.removeAttachment(a) }, iconSize = 18.dp) }
}

/** Hands a downloaded attachment to another app through the app's file provider (a copy in the cache, deleted next time). */
internal suspend fun openBytes(context: android.content.Context, bytes: ByteArray, mime: String, name: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "notizen-open").apply { deleteRecursively(); mkdirs() }
        val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifEmpty { "datei" }
        val f = java.io.File(dir, safe).apply { writeBytes(bytes) }
        runCatching { androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", f) }.getOrNull()
    } ?: return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** „Aus Mail · „Ihr Angebot 2026-114“ · Mail öffnen“. */
@Composable
private fun SourceLine(src: NItem, nav: NotizenNav) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NSourceBadge(src.plugin, badgeText(src))
        Text(src.subtitle ?: src.title, style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.weight(1f), maxLines = 2)
        if (src.open != null) {
            Text(
                stringResource(R.string.notizen_open_in, src.sourceLabel),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { nav.openSource(src.open) }.padding(6.dp),
            )
        }
    }
}

/** Saves a field when the person leaves it. */
internal fun Modifier.onFocusLost(block: () -> Unit): Modifier = composed {
    var had by remember { mutableStateOf(false) }
    this.onFocusChanged { s ->
        if (had && !s.isFocused) block()
        had = s.isFocused
    }
}

/** An item of another plugin (route `notizen/item/<id>`): its details, „In … öffnen“, ticking it off, a task of its own. */
@Composable
fun NotizenItemScreen(nav: NotizenNav, vm: NotizenItemViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    // the item already has a task here: that one is the page
    LaunchedEffect(ui.taskId) { ui.taskId?.let { nav.back(); nav.task(it) } }
    val x = GateControlTheme.extraColors
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
            val item = ui.item
            GcSubpageBar(item?.sourceLabel.orEmpty(), nav.back, stringResource(R.string.notizen_back))
            if (item == null) {
                if (ui.loading) NotizenLoading() else Text(messageText(ui.error ?: "not_found"), modifier = Modifier.padding(24.dp))
                return@Column
            }
            val me = ui.me
            val words = Triple(stringResource(R.string.notizen_today_word_cap), stringResource(R.string.notizen_tomorrow_word_cap), stringResource(R.string.notizen_yesterday_word_cap))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                NCheck(item.done, stringResource(R.string.notizen_mark_done), if (item.canComplete) vm::toggle else null)
                Text(item.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, textDecoration = if (item.done) TextDecoration.LineThrough else null), modifier = Modifier.padding(8.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NSourceBadge(item.plugin, badgeText(item))
                item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = x.muted) }
                NotizenFormat.due(item.due, me?.today ?: "", me?.lang ?: "de", words)?.let { Text(stringResource(R.string.notizen_due_is, it), style = MaterialTheme.typography.bodyMedium) }
                val meta = listOfNotNull(item.meta["child"], item.assignee?.name, item.meta["amount"], item.progress?.let { "${it.done}/${it.total}" }, item.groupLabel)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                if (!item.canComplete) Text(stringResource(R.string.notizen_item_read_only), style = MaterialTheme.typography.bodySmall, color = x.muted)
                item.open?.let { o ->
                    GcPrimaryButton(stringResource(R.string.notizen_open_in, item.sourceLabel), {
                        if (!nav.openSource(o)) vm.notHere()
                    }, icon = GcIcons.External)
                }
                GcOutlineButton(stringResource(R.string.notizen_carry), { vm.carry { id -> nav.back(); nav.task(id) } }, icon = GcIcons.Plus)
                Text(stringResource(R.string.notizen_done_there, item.sourceLabel), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
    }
}

/** A list (Wichtig, Geplant, Mir zugewiesen, an own list, a source), route `notizen/view/<name>`. */
@Composable
fun NotizenViewScreen(nav: NotizenNav, vm: NotizenViewViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    var add by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val title = ui.view?.title?.takeIf { it.isNotBlank() } ?: when (vm.name) {
        "important" -> stringResource(R.string.notizen_smart_important)
        "planned" -> stringResource(R.string.notizen_smart_planned)
        "assigned" -> stringResource(R.string.notizen_smart_assigned)
        "myday" -> stringResource(R.string.notizen_seg_day)
        else -> ""
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GcSubpageBar(title, nav.back, stringResource(R.string.notizen_back))
            NOfflineHint(ui.offline, 0)
            val v = ui.view
            val me = ui.me
            if (v == null || me == null) {
                if (ui.loading) NotizenLoading() else Text(messageText(ui.error ?: "failed"), modifier = Modifier.padding(24.dp))
                return@Column
            }
            val rc = rowCtx(me.today, me.lang, { null })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                v.sections.forEach { s ->
                    if (s.items.isEmpty() && s.id != "open") return@forEach
                    if (s.collapsed) {
                        item(key = "t-" + s.id) {
                            Text(
                                stringResource(R.string.notizen_done_n, s.count),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { showDone = !showDone }.padding(6.dp),
                            )
                        }
                        if (!showDone) return@forEach
                    } else if (v.sections.size > 1) {
                        item(key = "h-" + s.id) { NSectionHeader(s.title, s.items.size, danger = s.id == "overdue") }
                    }
                    item(key = "s-" + s.id) {
                        if (s.items.isEmpty()) Text(stringResource(R.string.notizen_list_empty), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(16.dp))
                        else EntryCard(s.items, rc, vm::toggle, nav)
                    }
                }
            }
            if (vm.name.startsWith("list:") && me.readOnly.not()) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(GcIcons.Plus, null, tint = MaterialTheme.colorScheme.primary)
                    androidx.compose.foundation.text.BasicTextField(
                        add,
                        { add = it.take(500) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (add.isNotBlank()) { vm.add(add.trim()); add = "" } }),
                        decorationBox = { inner -> Box(Modifier.padding(16.dp)) { if (add.isEmpty()) Text(stringResource(R.string.notizen_add_task), color = GateControlTheme.extraColors.muted); inner() } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
    }
}
