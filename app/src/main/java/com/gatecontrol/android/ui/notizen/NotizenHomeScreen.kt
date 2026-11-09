package com.gatecontrol.android.ui.notizen

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
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
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

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
 * The tab „Aufgaben“ of Notizen & Aufgaben: „Mein Tag“ (mockup Main) or the notes (mockup Notizen); the rest — the
 * fixed views, the lists and groups, the other plugins, the notebooks, the settings — is in the side menu behind ☰
 * (mockup „Neue Navigation“). Pulling down loads again; the quick entry at the foot, the area (Privat / Arbeit) at the top.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NotizenHomeScreen(nav: NotizenNav, vm: NotizenHomeViewModel = hiltViewModel(), manage: NotizenManageViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val segment by vm.segment.collectAsStateWithLifecycle()
    val collapsed by vm.collapsed.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val text = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (text != null) {
            snack.showSnackbar(text)
            vm.consumeMessage()
        }
    }
    var askNotebook by rememberSaveable { mutableStateOf(false) }
    var newTresor by rememberSaveable { mutableStateOf(false) }
    val manageState = remember { NotizenManageState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { scope.launch { drawer.close() } }
    // back from a note, a task or the Tresor page: the lists and the decrypted names as they are now
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.load(quiet = true)
        onPauseOrDispose { }
    }
    BackHandler(enabled = drawer.isOpen) { closeDrawer() }
    val notesPage = segment == NotizenHomeViewModel.SEG_NOTES
    val selectedNb = ui.notebook?.let { id -> ui.nav?.notebooks?.firstOrNull { it.id == id } }
    val me = ui.me

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                NotizenDrawer(
                    me = me,
                    nav = ui.nav,
                    area = ui.area,
                    selected = if (!notesPage) "myday" else ui.notebook?.let { "nb:$it" } ?: "notes",
                    collapsed = collapsed,
                    notebookName = vm::notebookName,
                    actions = NotizenDrawerActions(
                        area = vm::setArea,
                        myDay = { closeDrawer(); vm.setSegment(NotizenHomeViewModel.SEG_DAY) },
                        view = { name -> closeDrawer(); nav.view(name) },
                        notes = { nb ->
                            closeDrawer()
                            if (nb?.vault != null && !vm.vault.unlocked(nb.vault.id)) {
                                nav.vault(nb.vault.id)
                            } else {
                                vm.selectNotebook(nb?.id)
                                vm.setSegment(NotizenHomeViewModel.SEG_NOTES)
                            }
                        },
                        toggleGroup = vm::toggleGroup,
                        listMenu = { manageState.listSheet = it },
                        groupMenu = { manageState.groupSheet = it },
                        notebookMenu = { manageState.nbSheet = it },
                        newListOrGroup = { manageState.newList() },
                        newNotebook = { askNotebook = true },
                        newTresor = if (me?.vaults == true && me.has("vault")) ({ newTresor = true }) else null,
                        settings = { closeDrawer(); nav.sources() },
                    ),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // no statusBarsPadding: the app's Scaffold (AppNavigation) already keeps the page below the status bar
            Column(Modifier.fillMaxSize()) {
                // ── head ──
                val sub = when {
                    !notesPage -> me?.let { NotizenFormat.dayTitle(it.today, it.lang) }
                    selectedNb != null && selectedNb.shared && selectedNb.isOwner -> stringResource(R.string.notizen_shared_with, selectedNb.members.mapNotNull { it.name }.joinToString(", "))
                    selectedNb != null && selectedNb.shared -> stringResource(if (selectedNb.canEdit) R.string.notizen_shared_by else R.string.notizen_shared_by_read, selectedNb.owner?.name.orEmpty())
                    else -> null
                }
                NotizenHomeHead(
                    title = when {
                        !notesPage -> stringResource(R.string.notizen_seg_day)
                        selectedNb != null -> vm.notebookName(selectedNb) ?: stringResource(R.string.notizen_tresor)
                        else -> stringResource(R.string.notizen_all_notes)
                    },
                    subtitle = sub,
                    onMenu = { scope.launch { drawer.open() } },
                ) {
                    if (selectedNb != null && notesPage && com.gatecontrol.android.notizen.NotizenLists.notebookActions(selectedNb, me).isNotEmpty()) {
                        GcIconButton(GcIcons.MoreHorizontal, stringResource(R.string.notizen_menu_of, vm.notebookName(selectedNb) ?: stringResource(R.string.notizen_tresor)), { manageState.nbSheet = selectedNb }, tint = MaterialTheme.colorScheme.onBackground)
                    }
                    NAreaPill(ui.area, vm::setArea)
                    Spacer(Modifier.width(6.dp))
                    GcIconButton(GcIcons.Search, stringResource(if (notesPage) R.string.notizen_search_notes else R.string.notizen_search), nav.search, tint = MaterialTheme.colorScheme.onBackground)
                }
                NOfflineHint(ui.offline, ui.pending, ui.offlineEdits)
                when {
                    ui.me == null && ui.meError != null -> NotizenUnavailable(ui.meError!!, vm::load)
                    ui.me == null -> NotizenLoading()
                    else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = ui.refreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (notesPage) NotesPane(ui, vm, nav) else MyDayPane(ui, vm, nav)
                    }
                }
            }
            // the quick entry (mockup: the bar above the tab bar), the new note on the notes
            if (me != null && !me.readOnly) {
                if (notesPage) {
                    if (selectedNb?.canEdit != false) {
                        FloatingActionButton(
                            onClick = { nav.newNote(ui.notebook) },
                            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                        ) { Icon(GcIcons.Plus, stringResource(R.string.notizen_new_note)) }
                    }
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
    }

    NotizenManageHost(
        manageState, manage, me, ui.nav, ui.area, snack, vm::notebookName,
        onGone = { kind, id -> if (kind == "notebook" && ui.notebook == id) vm.selectNotebook(null) },
    )
    if (askNotebook) {
        NameDialog(
            title = stringResource(R.string.notizen_new_notebook),
            onDismiss = { askNotebook = false },
            onDone = { name ->
                askNotebook = false
                vm.createNotebook(name)
            },
        )
    }
    if (newTresor) {
        NewTresorFlow(vm.vault, ui.area, onDone = { newTresor = false; vm.load(quiet = true) })
    }
}

/**
 * The head of the tab, laid out like Drive's (DriveHomeScreen › TabBar): ☰, the title in the screen title's style and
 * the actions on one row at the same height as Drive's, the [subtitle] (the day, who shares the notebook) below the
 * title. No inset of its own: the app's Scaffold keeps the page below the status bar.
 */
@Composable
internal fun NotizenHomeHead(title: String, subtitle: String?, onMenu: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = if (subtitle == null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GcIconButton(GcIcons.Menu, stringResource(R.string.notizen_menu), onMenu, tint = MaterialTheme.colorScheme.onBackground)
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            actions()
            // "⋯" of the page when Notizen is pinned to Start (as in Drive)
            com.gatecontrol.android.ui.start.StartPageMenuButton()
        }
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // under the title: the row's start padding, ☰ and the title's own padding
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp),
            )
        }
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
        // plugin 1.2.0: „Heute ist Einkaufstag“ — the lists whose day is today, on top
        day.listdays.forEach { card ->
            item(key = "ld-" + card.list.id) { ListDayCard(card, me, vm::toggle, nav) }
        }
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
        if (day.listdays.isEmpty() && day.sections.all { s -> s.id == "done" || s.items.none { !it.done } }) {
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
            NSyncMark(s.pending, s.conflict)
        }
        if (s.pinned) Icon(GcIcons.Pin, stringResource(R.string.notizen_pinned_one), tint = x.faint, modifier = Modifier.size(16.dp))
    }
}

/** A name for a new list, group or notebook. */
@Composable
internal fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, confirm: String? = null, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { com.gatecontrol.android.ui.components.GcTextField(name, { name = it.take(80) }, stringResource(R.string.notizen_name)) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm ?: stringResource(R.string.notizen_create)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.notizen_cancel)) } },
    )
}

/**
 * „Heute ist Einkaufstag“ (plugin 1.2.0, mockup „Mein Tag am Einkaufstag“): the list, how many things are open and
 * whose, its first entries to tick off right here, and „Ganze Liste öffnen“.
 */
@Composable
internal fun ListDayCard(card: com.gatecontrol.android.notizen.NListDay, me: com.gatecontrol.android.notizen.NMe, toggle: (NEntry) -> Unit, nav: NotizenNav) {
    val x = GateControlTheme.extraColors
    val l = card.list
    val others = com.gatecontrol.android.notizen.NotizenLists.others(l, me.userId)
    val rc = rowCtx(me.today, me.lang, { null })
    NCard(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.view("list:" + l.id) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NListBadge(l, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.notizen_listday_open, l.name, card.open), style = MaterialTheme.typography.bodyMedium)
                val sub = listOfNotNull(
                    others.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.notizen_listday_with, it.joinToString(", ")) },
                    card.assignedToMe.takeIf { it > 0 }?.let { stringResource(R.string.notizen_listday_mine, it) },
                )
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(x.border))
        card.items.forEach { t ->
            NEntryRow(
                t, rc,
                if (com.gatecontrol.android.notizen.NotizenLists.canTick(t, l, me.userId) && !me.readOnly) ({ toggle(t) }) else null,
                { nav.open(t) },
                showAssignee = l.shared,
            )
        }
        if (card.more > 0) {
            Text(stringResource(R.string.notizen_listday_more, card.more), style = MaterialTheme.typography.bodySmall, color = x.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (card.items.isEmpty()) {
            Text(stringResource(R.string.notizen_listday_empty), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(16.dp))
        }
        Text(
            stringResource(R.string.notizen_listday_open_all),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { nav.view("list:" + l.id) }.padding(16.dp),
        )
    }
}
