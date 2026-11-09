package com.gatecontrol.android.ui.notizen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.notizen.NEntry
import com.gatecontrol.android.notizen.NItem
import com.gatecontrol.android.notizen.NNav
import com.gatecontrol.android.notizen.NNoteSummary
import com.gatecontrol.android.notizen.NNotebook
import com.gatecontrol.android.notizen.NOpen
import com.gatecontrol.android.notizen.NTask
import com.gatecontrol.android.notizen.NotizenFormat
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.DisplayFontFamily

/** Where the screens of the plugin lead (the navigation's side). */
data class NotizenNav(
    val task: (Long) -> Unit,
    val item: (Long) -> Unit,
    val view: (String) -> Unit,
    val quick: (note: Boolean) -> Unit,
    val note: (Long) -> Unit,
    val newNote: (notebook: Long?) -> Unit,
    val sources: () -> Unit,
    val import: () -> Unit,
    val search: () -> Unit,
    val vault: (String) -> Unit,
    /** The original of an item in its plugin; false when this app has no screen for it. */
    val openSource: (NOpen) -> Boolean,
    val back: () -> Unit,
)

/** Opens [e]: a task here, an item's details (its original stays in its plugin). */
internal fun NotizenNav.open(e: NEntry) = when (e) {
    is NTask -> task(e.id)
    is NItem -> item(e.id)
}

/** The list path of a task's row: „Privat › Gesundheit“. */
internal fun listPathOf(nav: NNav?, areaWord: String): (Long?) -> String? = { id ->
    nav?.lists?.firstOrNull { it.id == id }?.let { l -> if (l.isDefault) null else "$areaWord › ${l.name}" }
}

/**
 * The tab „Aufgaben“ of Notizen & Aufgaben: „Mein Tag“ (mockup Main), the lists (mockup Listen) and the notes
 * (mockup Notizen) as segments; the quick entry at the foot, the area (Privat / Arbeit) at the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotizenHomeScreen(nav: NotizenNav, vm: NotizenHomeViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val segment by vm.segment.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snack.showSnackbar(text)
            vm.consumeMessage()
        }
    }
    var menu by remember { mutableStateOf(false) }
    var ask by rememberSaveable { mutableStateOf<String?>(null) }
    var newTresor by rememberSaveable { mutableStateOf(false) }
    // back from a note, a task or the Tresor page: the lists and the decrypted names as they are now
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.load(quiet = true)
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ── head ──
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    GcIconButton(GcIcons.Menu, stringResource(R.string.notizen_menu), { menu = true }, tint = MaterialTheme.colorScheme.onBackground)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_list)) }, onClick = { menu = false; ask = "list" }, leadingIcon = { Icon(GcIcons.Tasks, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_group)) }, onClick = { menu = false; ask = "group" }, leadingIcon = { Icon(GcIcons.Folder, null) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_notebook)) }, onClick = { menu = false; ask = "notebook" }, leadingIcon = { Icon(GcIcons.Note, null) })
                        if (ui.me?.vaults == true && ui.me?.has("vault") == true) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_new_tresor)) }, onClick = { menu = false; newTresor = true }, leadingIcon = { Icon(GcIcons.Lock, null) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.notizen_sources)) }, onClick = { menu = false; nav.sources() }, leadingIcon = { Icon(GcIcons.Link, null) })
                        if (ui.me?.imports?.let { it.mstodo || it.dsnote } == true) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.notizen_import)) }, onClick = { menu = false; nav.import() }, leadingIcon = { Icon(GcIcons.FolderMove, null) })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.notizen_reload)) }, onClick = { menu = false; vm.load() }, leadingIcon = { Icon(GcIcons.Refresh, null) })
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            when (segment) {
                                NotizenHomeViewModel.SEG_TASKS -> R.string.notizen_seg_tasks
                                NotizenHomeViewModel.SEG_NOTES -> R.string.notizen_seg_notes
                                else -> R.string.notizen_seg_day
                            },
                        ),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp),
                        maxLines = 1,
                    )
                    if (segment == NotizenHomeViewModel.SEG_DAY) ui.me?.let { Text(NotizenFormat.dayTitle(it.today, it.lang), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
                }
                NAreaPill(ui.area, vm::setArea)
                GcIconButton(GcIcons.Search, stringResource(if (segment == NotizenHomeViewModel.SEG_NOTES) R.string.notizen_search_notes else R.string.notizen_search), nav.search, tint = MaterialTheme.colorScheme.onBackground)
            }
            GcSegmented(
                options = listOf(
                    NotizenHomeViewModel.SEG_DAY to stringResource(R.string.notizen_seg_day),
                    NotizenHomeViewModel.SEG_TASKS to stringResource(R.string.notizen_seg_tasks),
                    NotizenHomeViewModel.SEG_NOTES to stringResource(R.string.notizen_seg_notes),
                ),
                selected = segment,
                onSelect = vm::setSegment,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            NOfflineHint(ui.offline, ui.pending)
            when {
                ui.me == null && ui.meError != null -> NotizenUnavailable(ui.meError!!, vm::load)
                ui.me == null -> NotizenLoading()
                segment == NotizenHomeViewModel.SEG_TASKS -> ListsPane(ui, nav) { ask = "list" }
                segment == NotizenHomeViewModel.SEG_NOTES -> NotesPane(ui, vm, nav)
                else -> MyDayPane(ui, vm, nav)
            }
        }
        // the quick entry (mockup: the bar above the tab bar), the new note on the notes
        if (ui.me != null && ui.me?.readOnly != true) {
            if (segment == NotizenHomeViewModel.SEG_NOTES) {
                FloatingActionButton(
                    onClick = { nav.newNote(ui.notebook) },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                ) { Icon(GcIcons.Plus, stringResource(R.string.notizen_new_note)) }
            } else {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(role = Role.Button) { nav.quick(false) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(GcIcons.Plus, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.notizen_quick_bar), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) { Snackbar(it) }
    }

    ask?.let { kind ->
        NameDialog(
            title = stringResource(
                when (kind) {
                    "group" -> R.string.notizen_new_group
                    "notebook" -> R.string.notizen_new_notebook
                    else -> R.string.notizen_new_list
                },
            ),
            onDismiss = { ask = null },
            onDone = { name ->
                ask = null
                when (kind) {
                    "group" -> vm.createGroup(name)
                    "notebook" -> vm.createNotebook(name)
                    else -> vm.createList(name)
                }
            },
        )
    }
    if (newTresor) {
        NewTresorFlow(vm.vault, ui.area, onDone = { newTresor = false; vm.load(quiet = true) })
    }
}

@Composable
internal fun NotizenLoading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

/** The plugin does not answer for this person: not licensed, not allowed, or the server offline. */
@Composable
internal fun NotizenUnavailable(code: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(
                when (code) {
                    "offline" -> R.string.notizen_unavailable_offline
                    "forbidden", "http_403" -> R.string.notizen_unavailable_forbidden
                    "not_found", "http_404" -> R.string.notizen_unavailable_gone
                    else -> R.string.notizen_unavailable
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        com.gatecontrol.android.ui.components.GcOutlineButton(stringResource(R.string.notizen_retry), retry, fillWidth = false)
    }
}

// ── Mein Tag (mockup Main) ──────────────────────────────────

@Composable
private fun MyDayPane(ui: NotizenHomeViewModel.Ui, vm: NotizenHomeViewModel, nav: NotizenNav) {
    val me = ui.me ?: return
    val day = ui.myDay
    var showDone by rememberSaveable { mutableStateOf(false) }
    val path = listPathOf(ui.nav, areaLabel(ui.area))
    if (day == null) {
        if (ui.loading) NotizenLoading()
        return
    }
    val rc = rowCtx(me.today, me.lang, path, showDay = false)
    val rcDays = rowCtx(me.today, me.lang, path, showDay = true)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        for (s in day.sections) {
            val open = s.items.filter { !it.done || s.id == "done" }
            if (s.id == "done") {
                if (open.isNotEmpty()) {
                    item(key = "done-toggle") {
                        Text(
                            stringResource(R.string.notizen_done_today, open.size),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { showDone = !showDone }.padding(6.dp),
                        )
                    }
                    if (showDone) item(key = "done") { EntryCard(open, rcDays, vm::toggle, nav) }
                }
                continue
            }
            if (open.isEmpty()) continue
            item(key = "h-" + s.id) {
                NSectionHeader(
                    s.title,
                    open.size,
                    danger = s.id == "overdue",
                    action = if (s.id == "upcoming") stringResource(R.string.notizen_smart_planned) else null,
                    onAction = if (s.id == "upcoming") ({ nav.view("planned") }) else null,
                )
            }
            item(key = "s-" + s.id) {
                EntryCard(open, if (s.id == "events") rc.copy(timeFirst = true) else if (s.id == "upcoming" || s.id == "overdue") rcDays else rc, vm::toggle, nav)
            }
        }
        if (day.sections.all { s -> s.id == "done" || s.items.none { !it.done } }) {
            item { Text(stringResource(R.string.notizen_day_empty), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(24.dp)) }
        }
    }
}

@Composable
internal fun EntryCard(entries: List<NEntry>, ctx: RowCtx, toggle: (NEntry) -> Unit, nav: NotizenNav) {
    NCard {
        entries.forEachIndexed { i, e -> NEntryRow(e, ctx, { toggle(e) }, { nav.open(e) }, divider = i < entries.size - 1) }
    }
}

// ── Aufgaben (mockup Listen) ────────────────────────────────

@Composable
private fun ListsPane(ui: NotizenHomeViewModel.Ui, nav: NotizenNav, onNew: () -> Unit) {
    val n = ui.nav
    if (n == null) {
        if (ui.loading) NotizenLoading()
        return
    }
    val x = GateControlTheme.extraColors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            val smart = listOf(
                Triple("myday", R.string.notizen_seg_day, GcIcons.Sun),
                Triple("important", R.string.notizen_smart_important, GcIcons.Star),
                Triple("planned", R.string.notizen_smart_planned, GcIcons.Calendar),
                Triple("assigned", R.string.notizen_smart_assigned, GcIcons.User),
            )
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                smart.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (key, label, icon) ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, x.border, RoundedCornerShape(16.dp))
                                    .clickable(role = Role.Button) { nav.view(key) }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.weight(1f))
                                    Text((n.smart[key] ?: 0).toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                }
                                Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }
        }
        if (n.sources.isNotEmpty()) {
            item { NSectionHeader(stringResource(R.string.notizen_from_plugins), null) }
            item {
                NCard {
                    n.sources.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { nav.view("source:" + s.plugin) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            NSourceBadge(s.plugin, s.label)
                            Text(s.hint.orEmpty(), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.weight(1f), maxLines = 2)
                            Text(s.open.toString(), style = MaterialTheme.typography.labelLarge, color = x.muted)
                        }
                        if (i < n.sources.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                    }
                }
            }
        }
        item { NSectionHeader(stringResource(R.string.notizen_my_lists), null) }
        item {
            NCard {
                val groups = n.groups.associateBy { it.id }
                n.lists.sortedWith(compareBy({ it.groupId ?: 0 }, { it.position })).forEachIndexed { i, l ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { nav.view("list:" + l.id) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(noteColor(l.color) ?: MaterialTheme.colorScheme.primary))
                        Column(Modifier.weight(1f)) {
                            Text(l.name, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
                            val sub = listOfNotNull(
                                l.groupId?.let { groups[it]?.name },
                                if (l.shared) stringResource(R.string.notizen_shared_with, l.members.mapNotNull { it.name }.ifEmpty { listOfNotNull(l.owner?.name) }.joinToString(", ")) else null,
                                if (l.ext == "mstodo") stringResource(R.string.notizen_list_mstodo) else null,
                            )
                            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                        Text(l.open.toString(), style = MaterialTheme.typography.labelLarge, color = x.muted)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                }
                Text(
                    stringResource(R.string.notizen_new_list_or_group),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onNew).padding(16.dp),
                )
            }
        }
    }
}

// ── Notizen (mockup Notizen) ────────────────────────────────

@Composable
private fun NotesPane(ui: NotizenHomeViewModel.Ui, vm: NotizenHomeViewModel, nav: NotizenNav) {
    val n = ui.nav
    val me = ui.me ?: return
    val x = GateControlTheme.extraColors
    val books = n?.notebooks.orEmpty()
    val byId = books.associateBy { it.id }
    val selected = ui.notebook?.let { byId[it] }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.gatecontrol.android.ui.components.GcFilterChip(stringResource(R.string.notizen_all), ui.notebook == null, { vm.selectNotebook(null) })
                books.forEach { b ->
                    val name = vm.notebookName(b) ?: stringResource(R.string.notizen_tresor)
                    com.gatecontrol.android.ui.components.GcFilterChip(if (b.vault != null) stringResource(R.string.notizen_tresor_chip, name) else name, ui.notebook == b.id, {
                        if (b.vault != null && !vm.vault.unlocked(b.vault.id)) nav.vault(b.vault.id) else vm.selectNotebook(b.id)
                    })
                }
            }
        }
        val tags = n?.tags.orEmpty().filter { selected?.vault == null }
        if (tags.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.take(20).forEach { t -> com.gatecontrol.android.ui.components.GcFilterChip("#" + t.tag, ui.tag == t.tag, { vm.selectTag(t.tag) }) }
                }
            }
        }
        if (selected?.vault != null && !vm.vault.unlocked(selected.vault.id)) {
            item { LockedRow(selected, vm, nav) }
            return@LazyColumn
        }
        if (ui.pinned.isNotEmpty()) {
            item { NSectionHeader(stringResource(R.string.notizen_pinned), null) }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ui.pinned.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { s -> PinnedCard(s, byId[s.notebookId], vm, nav, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item { NSectionHeader(stringResource(R.string.notizen_recent), null) }
        item {
            NCard {
                val list = ui.notes
                if (list.isEmpty()) Text(stringResource(R.string.notizen_notes_empty), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(16.dp))
                list.forEachIndexed { i, s ->
                    NoteRow(s, byId[s.notebookId], vm, me.today, me.lang) { nav.note(s.id) }
                    if (i < list.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                }
                // the locked Tresor notebooks as rows of their own (mockup: „Versicherungen – Tresor – zum Öffnen entsperren“)
                if (ui.notebook == null) {
                    books.filter { it.vault != null && !vm.vault.unlocked(it.vault.id) }.forEach { b ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
                        LockedRow(b, vm, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun LockedRow(b: NNotebook, vm: NotizenHomeViewModel, nav: NotizenNav) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { b.vault?.let { nav.vault(it.id) } }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(x.panel2), contentAlignment = Alignment.Center) { Icon(GcIcons.Lock, null, tint = x.muted) }
        Column(Modifier.weight(1f)) {
            Text(vm.notebookName(b) ?: stringResource(R.string.notizen_tresor), style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp))
            Text(stringResource(R.string.notizen_tresor_locked_hint), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        Text(stringResource(if (b.vault?.needsRotation == true) R.string.notizen_tresor_new_key_short else R.string.notizen_tresor), style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

private fun noteTitle(s: NNoteSummary, nb: NNotebook?, vm: NotizenHomeViewModel): String? =
    if (s.encrypted) nb?.vault?.let { vm.vault.name(it.id, s.titleCipher) } else s.title

@Composable
private fun PinnedCard(s: NNoteSummary, nb: NNotebook?, vm: NotizenHomeViewModel, nav: NotizenNav, modifier: Modifier) {
    val x = GateControlTheme.extraColors
    val bg = noteColor(s.color) ?: MaterialTheme.colorScheme.surface
    Column(
        modifier
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, x.border, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { nav.note(s.id) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(noteTitle(s, nb, vm).orEmpty().ifEmpty { stringResource(R.string.notizen_untitled) }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.snippet.orEmpty(), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(
            (s.tags.take(2).map { "#$it" } + listOfNotNull(nb?.let { vm.notebookName(it) })).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = x.faint,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoteRow(s: NNoteSummary, nb: NNotebook?, vm: NotizenHomeViewModel, today: String, lang: String, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.images > 0) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(noteColor(s.color) ?: x.panel2), contentAlignment = Alignment.Center) {
                Icon(GcIcons.Photo, stringResource(R.string.notizen_pictures_n, s.images), tint = x.muted)
            }
        } else if (s.color != null) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(noteColor(s.color) ?: x.panel2))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(noteTitle(s, nb, vm).orEmpty().ifEmpty { stringResource(R.string.notizen_untitled) }, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!s.snippet.isNullOrBlank()) Text(s.snippet, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val day = java.time.Instant.ofEpochMilli(s.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            val meta = listOfNotNull(
                s.tags.firstOrNull()?.let { "#$it" },
                if (s.ext == "dsnote") stringResource(R.string.notizen_from_dsnote) else nb?.let { vm.notebookName(it) },
                s.check?.let { "${it.done}/${it.total}" },
                if (s.images > 0) stringResource(R.string.notizen_pictures_n, s.images) else null,
                NotizenFormat.due(com.gatecontrol.android.notizen.NDue(day), today, lang, Triple(stringResource(R.string.notizen_today_word), stringResource(R.string.notizen_tomorrow_word), stringResource(R.string.notizen_yesterday_word))),
            )
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = x.faint, maxLines = 1)
        }
        if (s.pinned) Icon(GcIcons.Pin, stringResource(R.string.notizen_pinned_one), tint = x.faint, modifier = Modifier.size(16.dp))
    }
}

/** A name for a new list, group or notebook. */
@Composable
internal fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { com.gatecontrol.android.ui.components.GcTextField(name, { name = it.take(80) }, stringResource(R.string.notizen_name)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.notizen_create)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}
