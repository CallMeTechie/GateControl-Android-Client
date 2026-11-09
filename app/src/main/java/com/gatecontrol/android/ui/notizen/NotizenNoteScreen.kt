package com.gatecontrol.android.ui.notizen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.AddTask
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NNote
import com.gatecontrol.android.notizen.NoteMd
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.notizen.NotizenJson
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** While a Tresor note is open: no screenshot, no picture in the recent apps (as Drive's vault viewer). */
@Composable
private fun SecureWindow() {
    val activity = LocalContext.current.activity()
    DisposableEffect(activity) {
        val window = activity?.window
        val had = window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(false)
        onDispose {
            if (!had) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity?.setRecentsScreenshotEnabled(true)
        }
    }
}

/** The text a picture shows, read on the phone (ML Kit, nothing leaves it for that); null without text. */
internal suspend fun recognizeText(bytes: ByteArray): String? = withContext(Dispatchers.Default) {
    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return@withContext null
    val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
        suspendCancellableCoroutine { c ->
            recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { c.resume(it.text.trim().ifEmpty { null }) }
                .addOnFailureListener { c.resume(null) }
        }
    } finally {
        recognizer.close()
    }
}

/** One note (mockup Notiz), route `notizen/note/<id>` (0: a new one). */
@Composable
fun NotizenNoteScreen(nav: NotizenNav, vm: NotizenNoteViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) { if (text != null) { snack.showSnackbar(text); vm.consumeMessage() } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val x = GateControlTheme.extraColors
    var more by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(ui.body)) }
    if (field.text != ui.body && !ui.editing) field = TextFieldValue(ui.body)
    LaunchedEffect(ui.editing) { if (ui.editing) field = TextFieldValue(ui.body, TextRange(ui.body.length)) }
    val note = ui.note
    if (ui.vaultId != null) SecureWindow()
    BackHandler(ui.editing) { vm.stopEditing() }
    val maxBytes = (ui.me?.attachMB ?: 25) * 1024L * 1024L
    val tooLarge = stringResource(R.string.notizen_msg_too_large)
    val col1 = stringResource(R.string.notizen_table_col1)
    val col2 = stringResource(R.string.notizen_table_col2)
    val attach: (Uri) -> Unit = { u ->
        scope.launch {
            val f = withContext(Dispatchers.IO) { readPicked(context, u, maxBytes) }
            if (f == null) snack.showSnackbar(tooLarge) else vm.attach(f.first, f.second, f.third, field.selection.end, ::recognizeText)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) attach(u) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> photo?.let { if (ok) attach(it) } }
    val takePhoto: () -> Unit = {
        runCatching {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "notiz-" + System.currentTimeMillis() + ".jpg")
            val u = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", f)
            photo = u
            camera.launch(u)
        }
    }
    val notebookName = note?.notebookName ?: ui.notebooks.firstOrNull { it.id == note?.summary?.notebookId }?.name ?: if (ui.vaultId != null) stringResource(R.string.notizen_tresor) else null
    val status = when {
        ui.saving -> stringResource(R.string.notizen_saving)
        ui.dirty -> stringResource(R.string.notizen_unsaved)
        note != null -> stringResource(R.string.notizen_saved)
        else -> null
    }
    val edit: (NoteMd.Edit) -> Unit = { e ->
        field = TextFieldValue(e.text, TextRange(e.selStart, e.selEnd))
        vm.setBody(e.text)
    }
    val ensureEdit: (() -> Unit) -> Unit = { action ->
        if (!ui.editing) vm.startEditing()
        if (ui.editing || ((!ui.offline || ui.offlineEdits) && !ui.locked && ui.me?.readOnly != true)) action()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            GcSubpageBar(listOfNotNull(notebookName, status).joinToString(" · "), { if (ui.editing) vm.stopEditing(); vm.saveNow(); nav.back() }, stringResource(R.string.notizen_back)) {
                if (note != null && !ui.locked) {
                    GcIconButton(GcIcons.Share, stringResource(R.string.notizen_share), {
                        runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, ui.title).putExtra(Intent.EXTRA_TEXT, (ui.title + "\n\n" + ui.body).trim()), null))
                        }
                    })
                    GcIconButton(GcIcons.Pin, stringResource(if (note.summary.pinned) R.string.notizen_unpin else R.string.notizen_pin), { vm.setPinned(!note.summary.pinned) }, tint = if (note.summary.pinned) MaterialTheme.colorScheme.primary else x.muted)
                }
                Box {
                    GcIconButton(GcIcons.MoreVertical, stringResource(R.string.notizen_more), { more = true })
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (ui.editing) DropdownMenuItem(text = { Text(stringResource(R.string.notizen_done_editing)) }, onClick = { more = false; vm.stopEditing() })
                        else DropdownMenuItem(text = { Text(stringResource(R.string.notizen_edit)) }, onClick = { more = false; vm.startEditing() })
                        if (note != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_color)) }, onClick = { more = false; colors = true }, leadingIcon = { Icon(GcIcons.Palette, null) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_delete)) }, onClick = { more = false; vm.delete(nav.back) }, leadingIcon = { Icon(GcIcons.Trash, null) })
                        }
                    }
                }
            }
            NOfflineHint(ui.offline, 0, ui.offlineEdits)
            note?.summary?.let { if (it.pending) Box(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) { NSyncMark(true, false) } }
            if (ui.locked) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.notizen_tresor_locked_note), style = MaterialTheme.typography.bodyMedium)
                    ui.vaultId?.let { v -> GcOutlineButton(stringResource(R.string.notizen_tresor_unlock), { nav.vault(v) }, icon = GcIcons.Lock) }
                }
                return@Column
            }
            if (ui.loading) { NotizenLoading(); return@Column }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.editing) {
                    BasicTextField(
                        ui.title,
                        vm::setTitle,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box { if (ui.title.isEmpty()) Text(stringResource(R.string.notizen_title_placeholder), style = MaterialTheme.typography.headlineSmall, color = x.faint); inner() } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    BasicTextField(
                        field,
                        { v -> field = v; if (v.text != ui.body) vm.setBody(v.text) },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground, fontFamily = MonoFontFamily, fontSize = 15.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner -> Box { if (field.text.isEmpty()) Text(stringResource(R.string.notizen_body_placeholder), color = x.faint); inner() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    )
                } else {
                    Text(
                        ui.title.ifEmpty { stringResource(R.string.notizen_untitled) },
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(role = Role.Button, onClickLabel = stringResource(R.string.notizen_edit)) { vm.startEditing() },
                    )
                    MarkdownView(
                        md = ui.body,
                        note = note,
                        pictures = ui.pictures,
                        today = ui.me?.today ?: "",
                        lang = ui.me?.lang ?: "de",
                        onCheck = vm::check,
                        onTask = nav.task,
                        onLineTask = vm::lineToTask,
                        onFile = { id ->
                            val a = note?.attachments?.firstOrNull { it.id == id } ?: return@MarkdownView
                            if (a.encrypted) return@MarkdownView
                            scope.launch {
                                val bytes = vm.download(a) ?: return@launch
                                openBytes(context, bytes, a.mime ?: "application/octet-stream", a.name ?: "datei")
                            }
                        },
                        onLink = { u -> runCatching { uri.openUri(u) } },
                        onEdit = { vm.startEditing() },
                    )
                    val tags = note?.summary?.tags?.takeIf { it.isNotEmpty() } ?: NoteMd.tags(ui.body)
                    if (tags.isNotEmpty()) Text(tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.height(24.dp))
            }
            if (ui.me?.readOnly != true) {
                Toolbar(
                    vault = ui.vaultId != null,
                    onBold = { ensureEdit { edit(NoteMd.wrap(field.text, field.selection.min, field.selection.max, "**")) } },
                    onItalic = { ensureEdit { edit(NoteMd.wrap(field.text, field.selection.min, field.selection.max, "*")) } },
                    onHeading = { ensureEdit { edit(NoteMd.linePrefix(field.text, field.selection.start, "## ")) } },
                    onList = { ensureEdit { edit(NoteMd.linePrefix(field.text, field.selection.start, "- ")) } },
                    onCheck = { ensureEdit { edit(NoteMd.linePrefix(field.text, field.selection.start, "- [ ] ")) } },
                    onTable = { ensureEdit { edit(NoteMd.table(field.text, field.selection.start, listOf(col1, col2))) } },
                    onAttach = { pick.launch(arrayOf("*/*")) },
                    onPhoto = takePhoto,
                    onLineTask = { vm.lineToTask(NoteMd.lineOf(field.text, field.selection.start)) },
                )
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp)) { Snackbar(it) }
    }
    if (colors) {
        AlertDialog(
            onDismissRequest = { colors = false },
            title = { Text(stringResource(R.string.notizen_color)) },
            text = {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (listOf<String?>(null) + NotizenJson.COLORS).forEach { c ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(noteColor(c) ?: MaterialTheme.colorScheme.surface).border(2.dp, if (note?.summary?.color == c) MaterialTheme.colorScheme.primary else x.border2, CircleShape)
                                .clickable(role = Role.Button, onClickLabel = c ?: stringResource(R.string.notizen_no_color)) { colors = false; vm.setColor(c) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { colors = false }) { Text(stringResource(R.string.notizen_cancel)) } },
        )
    }
    if (ui.conflict) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.notizen_conflict_title)) },
            text = { Text(stringResource(R.string.notizen_conflict_text)) },
            confirmButton = { TextButton(onClick = vm::keepMine) { Text(stringResource(R.string.notizen_conflict_mine)) } },
            dismissButton = { TextButton(onClick = vm::takeTheirs) { Text(stringResource(R.string.notizen_conflict_theirs)) } },
        )
    }
}

@Composable
private fun Toolbar(
    vault: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onHeading: () -> Unit,
    onList: () -> Unit,
    onCheck: () -> Unit,
    onTable: () -> Unit,
    onAttach: () -> Unit,
    onPhoto: () -> Unit,
    onLineTask: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = MaterialTheme.colorScheme.onSurface
        GcIconButton(Icons.Outlined.FormatBold, stringResource(R.string.notizen_tb_bold), onBold, tint = tint)
        GcIconButton(Icons.Outlined.FormatItalic, stringResource(R.string.notizen_tb_italic), onItalic, tint = tint)
        GcIconButton(Icons.Outlined.Title, stringResource(R.string.notizen_tb_heading), onHeading, tint = tint)
        GcIconButton(Icons.AutoMirrored.Outlined.FormatListBulleted, stringResource(R.string.notizen_tb_list), onList, tint = tint)
        GcIconButton(Icons.Outlined.Checklist, stringResource(R.string.notizen_tb_check), onCheck, tint = tint)
        GcIconButton(Icons.Outlined.TableChart, stringResource(R.string.notizen_tb_table), onTable, tint = tint)
        Box(Modifier.width(1.dp).height(24.dp).background(x.border))
        GcIconButton(Icons.Outlined.AttachFile, stringResource(R.string.notizen_tb_attach), onAttach, tint = tint)
        GcIconButton(Icons.Outlined.PhotoCamera, stringResource(R.string.notizen_tb_photo), onPhoto, tint = tint)
        if (!vault) GcIconButton(Icons.Outlined.AddTask, stringResource(R.string.notizen_tb_line_task), onLineTask, tint = MaterialTheme.colorScheme.primary)
    }
}

/** The note's Markdown drawn (a minimal, safe reader: [NoteMd]) — check boxes tick, „Aufgabe · Mi.“ opens the task. */
@Composable
internal fun MarkdownView(
    md: String,
    note: NNote?,
    pictures: Map<Long, ByteArray>,
    today: String,
    lang: String,
    onCheck: (Int, Boolean) -> Unit,
    onTask: (Long) -> Unit,
    onLineTask: (Int) -> Unit,
    onFile: (Long) -> Unit,
    onLink: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val scheme = MaterialTheme.colorScheme
    val words = Triple(stringResource(R.string.notizen_today_word_short), stringResource(R.string.notizen_tomorrow_word_short), stringResource(R.string.notizen_yesterday_word_short))
    val taskWord = stringResource(R.string.notizen_task_chip)
    val fileWord = stringResource(R.string.notizen_attachment)
    fun annotated(text: String): AnnotatedString = buildAnnotatedString {
        NoteMd.inline(text).forEach { s ->
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (s.strike) TextDecoration.LineThrough else null,
                fontFamily = if (s.code) MonoFontFamily else null,
                background = if (s.code) x.panel2 else androidx.compose.ui.graphics.Color.Unspecified,
            )
            when {
                s.url != null -> withLink(LinkAnnotation.Clickable("u", TextLinkStyles(SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline))) { onLink(s.url) }) { append(s.text) }
                s.task != null -> {
                    val t = note?.tasks?.firstOrNull { it.id == s.task }
                    val label = listOfNotNull(taskWord, t?.due?.let { NotizenFormat.due(it.copy(time = null), today, lang, words) }).joinToString(" · ")
                    append(" ")
                    withLink(LinkAnnotation.Clickable("t", TextLinkStyles(SpanStyle(color = scheme.primary, background = x.accentBg, fontWeight = FontWeight.SemiBold, textDecoration = if (t?.done == true) TextDecoration.LineThrough else null))) { onTask(s.task) }) {
                        append(" $label ")
                    }
                }
                s.att != null && !s.image -> withLink(LinkAnnotation.Clickable("a", TextLinkStyles(SpanStyle(color = scheme.primary))) { onFile(s.att) }) {
                    append(s.text.ifEmpty { note?.attachments?.firstOrNull { it.id == s.att }?.name ?: fileWord })
                }
                s.att != null -> Unit
                s.tag != null -> withStyle(SpanStyle(color = scheme.primary)) { append(s.text) }
                else -> withStyle(style) { append(s.text) }
            }
        }
    }
    val blocks = remember(md) { NoteMd.blocks(md) }
    if (blocks.isEmpty()) {
        Text(stringResource(R.string.notizen_body_empty), style = MaterialTheme.typography.bodyMedium, color = x.faint, modifier = Modifier.fillMaxWidth().clickable { onEdit() }.padding(vertical = 12.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is NoteMd.Heading -> Text(annotated(b.text), style = when (b.level) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.titleSmall
                }.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
                is NoteMd.Paragraph -> {
                    val pics = b.lines.flatMap { l -> NoteMd.inline(l).filter { it.att != null && it.image } }
                    val rest = b.lines.filter { l -> NoteMd.inline(l).any { !(it.att != null && it.image) && it.text.isNotBlank() } }
                    if (rest.isNotEmpty()) Text(annotated(rest.joinToString("\n")), style = MaterialTheme.typography.bodyLarge)
                    pics.forEach { p -> Picture(p.att!!, p.text, pictures, note, onFile) }
                }
                is NoteMd.Quote -> Row {
                    Box(Modifier.width(3.dp).height(24.dp).background(x.border2))
                    Text(annotated(b.lines.joinToString("\n")), style = MaterialTheme.typography.bodyLarge, color = x.muted, modifier = Modifier.padding(start = 10.dp))
                }
                is NoteMd.Code -> Text(b.text, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(x.panel2).horizontalScroll(rememberScrollState()).padding(10.dp))
                NoteMd.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(x.border))
                is NoteMd.Item -> Row(Modifier.padding(start = (b.depth * 18).dp)) {
                    Text(if (b.number != null) "${b.number}." else "•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(24.dp))
                    Text(annotated(b.text), style = MaterialTheme.typography.bodyLarge)
                }
                is NoteMd.Check -> Row(
                    Modifier.padding(start = (b.depth * 18).dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(
                        role = Role.Checkbox,
                        onClick = { onCheck(b.line, !b.done) },
                        onLongClickLabel = stringResource(R.string.notizen_tb_line_task),
                        onLongClick = { if (!b.text.contains("](task:")) onLineTask(b.line) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(b.done, { onCheck(b.line, it) })
                    Text(annotated(b.text), style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (b.done) TextDecoration.LineThrough else null), color = if (b.done) x.faint else scheme.onBackground)
                }
                is NoteMd.Table -> Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, x.border, RoundedCornerShape(8.dp))) {
                    (listOf(b.head) + b.rows).forEachIndexed { r, row ->
                        Row(Modifier.background(if (r == 0) x.panel2 else androidx.compose.ui.graphics.Color.Transparent)) {
                            row.forEach { c -> Text(annotated(c), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.Bold else null), modifier = Modifier.width(140.dp).padding(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Picture(id: Long, label: String, pictures: Map<Long, ByteArray>, note: NNote?, onFile: (Long) -> Unit) {
    val x = GateControlTheme.extraColors
    val bytes = pictures[id]
    val bmp = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    val name = note?.attachments?.firstOrNull { it.id == id }?.name ?: label
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (bmp != null) {
            Image(bmp, contentDescription = stringResource(R.string.notizen_picture_cd, name), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Photo, stringResource(R.string.notizen_picture_cd, name), tint = x.faint)
            }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = x.muted)
    }
}
