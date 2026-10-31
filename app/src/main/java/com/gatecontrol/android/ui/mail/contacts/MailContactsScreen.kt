package com.gatecontrol.android.ui.mail.contacts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.mail.MCardSummary
import com.gatecontrol.android.mail.MExport
import com.gatecontrol.android.mail.MGroup
import com.gatecontrol.android.mail.MMe
import com.gatecontrol.android.mail.MailCache
import com.gatecontrol.android.mail.MailPimLogic
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.mail.MailDivider
import com.gatecontrol.android.ui.mail.mailContext
import com.gatecontrol.android.ui.mail.mailText
import com.gatecontrol.android.ui.mail.toast
import com.gatecontrol.android.ui.mail.calendar.CalendarBands
import com.gatecontrol.android.ui.mail.calendar.NameDialog
import com.gatecontrol.android.ui.start.StartPageMenuButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the contacts lead. */
data class ContactsNav(
    val menu: () -> Unit,
    val contact: (Long) -> Unit,
    val newContact: () -> Unit,
    val group: (Long) -> Unit,
    val duplicates: () -> Unit,
    val relogin: (Long) -> Unit,
)

/** A vCard file of at most this size is read for the import (the server takes 5 MB). */
private const val VCF_MAX = 5L * 1024 * 1024

/**
 * The contacts area of the Mail tab (mockup "Kontakte"): the bar with the menu, the count and "⋯" (import, export,
 * duplicates), the search, Kontakte | Gruppen, the band of possible duplicates, the letters' sections with the A–Z
 * bar, the rows (photo or initials, name, position · company or the first address, the source's dot) and "Neuer
 * Kontakt".
 */
@Composable
fun MailContactsContent(nav: ContactsNav, viewModel: MailContactsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val all by viewModel.total.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val relogins by viewModel.relogins.collectAsStateWithLifecycle()
    val context = mailContext()
    val scope = rememberCoroutineScope()
    val extra = GateControlTheme.extraColors
    var menu by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var saveAfter by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.onShown()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        val parts = m.split(':')
        toast(context, if (parts[0] == "import" && parts.size == 4) context.getString(R.string.con_imported, parts[1].toInt(), parts[2].toInt(), parts[3].toInt()) else mailText(context, m))
        viewModel.clearMessage()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            if (text == null) viewModel.failed("too_large") else viewModel.importVcf(text)
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri ->
        val ex = state.export
        viewModel.exported()
        if (uri == null || ex == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(ex.bytes) } != null }.getOrDefault(false) }
            toast(context, context.getString(if (ok) R.string.con_exported else R.string.mail_err_failed))
        }
    }
    LaunchedEffect(state.export) {
        val ex = state.export ?: return@LaunchedEffect
        if (saveAfter) {
            saver.launch(ex.name)
        } else {
            share(context, ex)
            viewModel.exported()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GcIconButton(GcIcons.Menu, contentDescription = stringResource(R.string.mail_menu), onClick = nav.menu, tint = MaterialTheme.colorScheme.onBackground)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    val title = state.filter.group?.let { g -> books?.groups?.firstOrNull { it.id == g }?.name }
                        ?: state.filter.book?.let { b -> books?.book(b)?.name }
                        ?: stringResource(R.string.mail_contacts)
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    val n = all.size
                    val b = books?.items?.size ?: 0
                    Text(
                        pluralStringResource(R.plurals.con_count, n, n) + if (b > 0) " · " + pluralStringResource(R.plurals.con_books, b, b) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.muted,
                    )
                }
                Box {
                    GcIconButton(GcIcons.MoreVertical, contentDescription = stringResource(R.string.con_more), onClick = { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.con_import)) },
                            leadingIcon = { Icon(GcIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            enabled = !offline && !state.busy,
                            onClick = { menu = false; importer.launch(arrayOf("text/vcard", "text/x-vcard", "text/directory", "text/plain", "application/octet-stream")) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.con_export_share)) },
                            leadingIcon = { Icon(GcIcons.Share, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            enabled = !offline && !state.busy,
                            onClick = { menu = false; saveAfter = false; viewModel.export() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.con_export_save)) },
                            leadingIcon = { Icon(GcIcons.Upload, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            enabled = !offline && !state.busy,
                            onClick = { menu = false; saveAfter = true; viewModel.export() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.con_duplicates)) },
                            leadingIcon = { Icon(GcIcons.Users, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = { menu = false; nav.duplicates() },
                        )
                    }
                }
                StartPageMenuButton()
            }
            SearchField(state.query, viewModel::setQuery)
            TabsRow(state.tab, books?.groups?.size ?: 0, viewModel::setTab)
            CalendarBands(me, null, offline, relogins, onRelogin = { id -> viewModel.relogin(id); nav.relogin(id) }, contacts = true)
            if (state.tab == ContactsState.TAB_GROUPS) {
                GroupsList(books?.groups.orEmpty(), all, onGroup = nav.group, onNew = { newGroup = true }, offline = offline)
            } else {
                ContactsList(shown, me, books, state, nav, viewModel)
            }
        }
        if (state.tab == ContactsState.TAB_CONTACTS && !offline) {
            NewButton(stringResource(R.string.con_new), Modifier.align(Alignment.BottomEnd).padding(16.dp), nav.newContact)
        }
        if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
    }
    if (newGroup) NameDialog(stringResource(R.string.con_new_group), "", onDone = viewModel::createGroup, onDismiss = { newGroup = false })
}

private fun readText(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readNBytesCompat(VCF_MAX + 1)
        if (bytes.size > VCF_MAX) null else String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

private fun java.io.InputStream.readNBytesCompat(max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() <= max) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** The vCard file shared (FileProvider `mail_open`, removed with Mail's copy). */
private fun share(context: Context, ex: MExport) {
    runCatching {
        val dir = File(context.cacheDir, MailCache.OPEN_DIR + "/export").apply { mkdirs() }
        val file = File(dir, ex.name).apply { writeBytes(ex.bytes) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".provider", file)
        val send = Intent(Intent.ACTION_SEND).setType(ex.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, ex.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context, context.getString(R.string.mail_no_app)) }
}

@Composable
private fun SearchField(q: String, onChange: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    val label = stringResource(R.string.con_search)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(GcIcons.Search, contentDescription = null, tint = extra.muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) Text(stringResource(R.string.con_search_hint), color = extra.faint, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = q,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (q.isNotEmpty()) GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.con_search_clear), onClick = { onChange("") }, size = 32.dp, iconSize = 16.dp)
    }
}

@Composable
private fun TabsRow(tab: String, groups: Int, onTab: (String) -> Unit) {
    val extra = GateControlTheme.extraColors
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(
                ContactsState.TAB_CONTACTS to stringResource(R.string.mail_contacts),
                ContactsState.TAB_GROUPS to stringResource(R.string.con_groups) + " · $groups",
            ).forEach { (id, label) ->
                val on = tab == id
                Column(Modifier.weight(1f).heightIn(min = 44.dp).selectable(selected = on, role = Role.Tab) { onTab(id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (on) extra.accentText else extra.muted, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 2.dp, max = 2.dp).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        MailDivider()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactsList(shown: List<MCardSummary>, me: MMe?, books: com.gatecontrol.android.mail.MBooks?, state: ContactsState, nav: ContactsNav, viewModel: MailContactsViewModel) {
    val extra = GateControlTheme.extraColors
    val context = mailContext()
    val sections = remember(shown) { MailPimLogic.sections(shown) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // the index of each letter's head in the list (for the A–Z bar)
    val heads = remember(sections, state.duplicates) {
        var i = if (state.duplicates > 0) 1 else 0
        sections.associate { (letter, list) -> (letter to i).also { i += list.size + 1 } }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
            if (state.duplicates > 0) {
                item(key = "dup") {
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(extra.warnBg).clickable(role = Role.Button, onClick = nav.duplicates).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(GcIcons.Users, contentDescription = null, tint = extra.warn, modifier = Modifier.size(18.dp))
                        Text(pluralStringResource(R.plurals.con_duplicates_n, state.duplicates, state.duplicates), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.con_look), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = extra.warn)
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.size(28.dp))
                            state.error != null -> Text(mailText(context, state.error), color = extra.muted)
                            else -> Text(stringResource(if (state.query.isBlank()) R.string.con_none else R.string.mail_search_none), color = extra.muted)
                        }
                    }
                }
            }
            sections.forEach { (letter, list) ->
                item(key = "l$letter") {
                    Text(letter, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extra.accentText, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                }
                items(list, key = { "c${it.id}" }) { c ->
                    val book = books?.book(c.addressbookId)
                    ContactRow(c, sourceColor(me, book?.accountId), accountLabel(me, book?.accountId)) { nav.contact(c.id) }
                }
            }
        }
        if (sections.size > 4 && state.query.isBlank()) {
            Column(
                Modifier.align(Alignment.CenterEnd).width(20.dp).padding(end = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                sections.forEach { (letter, _) ->
                    Text(
                        letter,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = extra.muted,
                        modifier = Modifier.clickable(role = Role.Button, onClickLabel = letter) {
                            heads[letter]?.let { scope.launch { listState.scrollToItem(it) } }
                        }.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** A contact in the list (mockup): photo or initials, name, position · company or the first address, the source's dot. */
@Composable
internal fun ContactRow(c: MCardSummary, source: Color?, sourceName: String?, onClick: () -> Unit) {
    val extra = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ContactAvatar(c.name, c.emails.firstOrNull(), c.id.takeIf { c.photo }, c.photoEtag)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name.ifBlank { c.emails.firstOrNull() ?: "–" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = MailPimLogic.subline(c)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = extra.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (source != null) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(99.dp)).background(source).semantics { sourceName?.let { contentDescription = it } })
        }
    }
}

@Composable
private fun GroupsList(groups: List<MGroup>, all: List<MCardSummary>, onGroup: (Long) -> Unit, onNew: () -> Unit, offline: Boolean) {
    val extra = GateControlTheme.extraColors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(groups, key = { it.id }) { g ->
            val names = all.filter { g.id in it.groups }.map { it.name }.filter { it.isNotBlank() }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { onGroup(g.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tint = com.gatecontrol.android.ui.mail.calendar.calColor(g.color, extra.accentText)
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(GcIcons.Users, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(g.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (names.isNotEmpty()) {
                        val more = g.count - names.take(2).size
                        Text(
                            names.take(2).joinToString(", ") + if (more > 0) " " + pluralStringResource(R.plurals.con_and_more, more, more) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = extra.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(g.count.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = extra.muted)
            }
            MailDivider()
        }
        item(key = "new") {
            com.gatecontrol.android.ui.components.GcOutlineButton(
                stringResource(R.string.con_new_group),
                onClick = onNew,
                enabled = !offline,
                icon = GcIcons.Plus,
                fillWidth = false,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** The accent pill with "+" (like "Neue Mail"). */
@Composable
internal fun NewButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(scheme.primary).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GcIcons.Plus, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
        Text(label, color = scheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}
