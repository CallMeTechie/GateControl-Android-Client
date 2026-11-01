package com.gatecontrol.android.ui.drive

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import com.gatecontrol.android.drive.DIncident
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.GcBanner
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DMe
import androidx.compose.runtime.saveable.rememberSaveable
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSectionLabel
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.gatecontrol.android.drive.DriveTree

/** Where the Drive tab leads. */
data class DriveNav(
    val folder: (Long) -> Unit,
    val search: () -> Unit,
    val trash: () -> Unit,
    /** The storage overview (Drive 1.7). */
    val storage: () -> Unit = {},
    /** "Ordner sichern". */
    val folders: () -> Unit = {},
    /** An album of the photos (Drive 1.7). */
    val album: (String) -> Unit = {},
    /** The page of a file (Drive 1.8: what the AI recognised, its deadlines). */
    val file: (Long) -> Unit = {},
    /** "Fristen" (Drive 1.8). */
    val deadlines: () -> Unit = {},
    /** Photos by what is in them (Drive 1.14, `photoSearch`): the search of the Photos page. */
    val photoSearch: () -> Unit = {},
    /** Drive 1.16 (`photoPlaces`): the map of the photos, a place. */
    val photoMap: () -> Unit = {},
    val place: (String) -> Unit = {},
    /** Drive 1.16 (`photoPeople`): the people, one person. */
    val people: () -> Unit = {},
    val person: (Long) -> Unit = {},
    /** A vault folder (Drive 1.9). */
    val vault: (String, Long) -> Unit = { _, _ -> },
    /** "Drive-Einstellungen" (Drive 1.11, the gear of the menu). */
    val settings: () -> Unit = {},
    /** "Aufräumen" of the work photos (Drive 1.13). */
    val cleanup: () -> Unit = {},
    /** The folder the Files page shows now (null: none, or the photos) — a folder page's path goes back to it. */
    val shown: (Long?) -> Unit = {},
)

/**
 * The Drive tab: the chosen area's files, the areas and the trash in the menu top left. The
 * photo backup is in the settings.
 */
@Composable
fun DriveHomeScreen(nav: DriveNav, viewModel: DriveHomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val wipes by viewModel.wipes.collectAsStateWithLifecycle()
    val wipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) wipes?.let(viewModel::wipesDone)
    }
    val folderViewModel: DriveFolderViewModel = hiltViewModel(key = "drive-tab")
    val photosViewModel: DrivePhotosViewModel = hiltViewModel(key = "drive-photos")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val guardBusy by viewModel.guardBusy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var guardAsk by remember { mutableStateOf<Pair<DIncident, Boolean>?>(null) }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, guardText(context, it), Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }
    val home = (state as? DriveLoad.Ready)?.value
    val usable = home != null && home.me.licenceValid && home.me.seat != null && home.areas.isNotEmpty()
    val current = if (usable) home?.let { h -> h.areas.firstOrNull { it.id == selected } ?: viewModel.current(h.areas) } else null
    val folderState by folderViewModel.state.collectAsStateWithLifecycle()
    /** The folder shown on the Files page (the area's top or, from the menu's tree, one below). */
    val shown = (folderState as? DriveLoad.Ready)?.value?.node
    /** A folder picked in the menu's tree of another area: opened once that area is the current one. */
    var pendingFolder by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(current?.rootId) {
        val c = current ?: return@LaunchedEffect
        val target = pendingFolder
        pendingFolder = null
        when {
            target != null -> folderViewModel.open(target)
            // back on the tab: a folder of this area stays open
            (folderViewModel.state.value as? DriveLoad.Ready)?.value?.node?.areaId == c.id -> Unit
            else -> folderViewModel.open(c.rootId)
        }
    }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    // Drive 1.7 (feature `photos`): the tab has two pages, the files and the photos; a swipe goes from one to the other.
    val hasPhotos = me?.has(DMe.FEATURE_PHOTOS) == true
    val ticked by photosViewModel.selected.collectAsStateWithLifecycle()
    val folderTicked by folderViewModel.bulk.selected.collectAsStateWithLifecycle()
    /** Items are ticked on one of the pages: its own bar shows instead of the tab's, swiping stays off. */
    val selecting = ticked.isNotEmpty() || folderTicked.isNotEmpty()
    val pager = rememberPagerState(initialPage = DriveLogic.tabOf(DriveViewPref.photos(context), hasPhotos)) { DriveLogic.tabCount(hasPhotos) }
    val onPhotosPage = DriveLogic.isPhotosTab(pager.currentPage, hasPhotos)
    val showPage: (Boolean) -> Unit = { photos -> scope.launch { pager.animateScrollToPage(DriveLogic.tabOf(photos, hasPhotos)) } }
    // the folder on the Files page: the path of a folder page goes back here when it names this one
    val shownId = shown?.id?.takeIf { !onPhotosPage }
    // Drive 1.14 (`photoSearch`): photos without words get them now (in the background, under the backup's conditions)
    val photoSearch = me?.has(DMe.FEATURE_PHOTO_SEARCH) == true
    LaunchedEffect(onPhotosPage, photoSearch) {
        if (onPhotosPage && photoSearch) com.gatecontrol.android.drive.backup.DrivePhotoLabelsWork.request(context)
    }
    LaunchedEffect(shownId) { nav.shown(shownId) }
    // the page the person ends on is kept, and shown again when the person comes back to the tab
    var synced by remember { mutableStateOf(false) }
    LaunchedEffect(hasPhotos) {
        if (!hasPhotos) return@LaunchedEffect
        val want = DriveLogic.tabOf(DriveViewPref.photos(context), true)
        if (pager.currentPage != want) pager.scrollToPage(want)
        synced = true
    }
    LaunchedEffect(pager.settledPage, synced) { if (synced && hasPhotos) DriveViewPref.setPhotos(context, DriveLogic.isPhotosTab(pager.settledPage, true)) }
    LifecycleResumeEffect(pager, hasPhotos, synced) {
        val want = DriveLogic.tabOf(DriveViewPref.photos(context), hasPhotos)
        if (synced && pager.currentPage != want && !pager.isScrollInProgress) scope.launch { pager.scrollToPage(want) }
        onPauseOrDispose { }
    }
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val expanded by viewModel.expanded.collectAsStateWithLifecycle()
    val children by viewModel.children.collectAsStateWithLifecycle()
    LaunchedEffect(drawer.isOpen, me?.features) {
        if (drawer.isOpen) {
            viewModel.loadVaults()
            viewModel.refreshTree()
        }
    }
    val grid = rememberDriveGrid()
    // a folder below the area's top (opened from the menu): back goes up a level
    BackHandler(enabled = !drawer.isOpen && !selecting && !onPhotosPage && shown != null && shown.parentId != null && shown.areaId == current?.id) {
        shown?.parentId?.let(folderViewModel::open)
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                val close: () -> Unit = { scope.launch { drawer.close() } }
                val areas = home?.areas.orEmpty().takeIf { usable }.orEmpty()
                val personal = DriveTree.flatten(DriveTree.personal(areas), expanded, children)
                val shared = DriveTree.flatten(DriveTree.shared(areas), expanded, children)
                // opened folders of an earlier run: their subfolders load when the menu shows them
                val needs = DriveTree.needs(personal + shared)
                LaunchedEffect(needs) { needs.forEach { viewModel.loadDirs(it) } }
                DriveDrawer(
                    me = me ?: home?.me,
                    areas = areas,
                    personal = personal,
                    shared = shared,
                    selected = shown?.id?.takeIf { !onPhotosPage },
                    onToggle = viewModel::toggle,
                    onOpen = { row ->
                        val area = areas.firstOrNull { it.id == row.areaId }
                        if (area != null) {
                            if (area.id != current?.id) {
                                pendingFolder = row.id.takeIf { row.area == null }
                                viewModel.select(area)
                            } else {
                                folderViewModel.open(row.id)
                            }
                        }
                        if (onPhotosPage) showPage(false)
                        close()
                    },
                    // Drive 1.9 (`vault`): the vaults are opened from here only (Drive 1.11)
                    vaults = if (me?.has(DMe.FEATURE_VAULT) == true) vaults else null,
                    onVault = { e -> close(); nav.vault(e.vault.id, e.vault.nodeId) },
                    icons = buildList {
                        add(DrawerIcon(GcIcons.Trash, R.string.drive_trash) { close(); nav.trash() })
                        if (me?.has(DMe.FEATURE_STORAGE) == true) add(DrawerIcon(GcIcons.Activity, R.string.drive_storage_title) { close(); nav.storage() })
                        add(DrawerIcon(GcIcons.Upload, R.string.drive_folders_title) { close(); nav.folders() })
                        if (me?.has(DMe.FEATURE_DOC_AI) == true) add(DrawerIcon(GcIcons.Calendar, R.string.drive_deadlines) { close(); nav.deadlines() })
                        // Drive 1.8 (`cloudImport`): the import runs in the portal only
                        if (me?.has(DMe.FEATURE_CLOUD_IMPORT) == true) {
                            add(
                                DrawerIcon(GcIcons.Download, R.string.drive_import) {
                                    scope.launch {
                                        drawer.close()
                                        viewModel.importUrl()?.let { openPortal(context, it) }
                                            ?: Toast.makeText(context, R.string.drive_portal_unavailable, Toast.LENGTH_LONG).show()
                                    }
                                },
                            )
                        }
                        add(DrawerIcon(GcIcons.Settings, R.string.drive_settings_title) { close(); nav.settings() })
                    },
                )
            }
        },
    ) {
        if (home != null && current != null) {
            Column(Modifier.fillMaxSize()) {
                // while ticking, the page's own bar ("N ausgewählt") is on top
                // while ticking, the page's head ("N ausgewählt") takes the title row only, as high as it; the switch
                // below stays (dimmed), so the page does not move under the finger of the long press
                SelectionSwap(
                    selecting,
                    selection = { if (ticked.isNotEmpty()) PhotosSelectionHead(photosViewModel) else FolderSelectionHead(folderViewModel) },
                    bar = {
                    TabBar(
                        when {
                            onPhotosPage -> stringResource(R.string.drive_mode_photos)
                            shown != null && shown.parentId != null && shown.areaId == current.id -> shown.name
                            else -> current.label
                        },
                        onMenu = openMenu,
                        onSearch = if (onPhotosPage && DriveLogic.photoSearchable(me)) nav.photoSearch else nav.search,
                        actions = {
                            if (!onPhotosPage) {
                                FolderViewActions(folderViewModel, grid)
                            } else if (me?.has(DMe.FEATURE_PHOTO_PLACES) == true) {
                                // Drive 1.16: the photos on the map
                                GcIconButton(GcIcons.Map, contentDescription = stringResource(R.string.drive_pp_map), onClick = nav.photoMap, tint = MaterialTheme.colorScheme.onBackground)
                            }
                        },
                    )
                    },
                )
                if (hasPhotos) Box(Modifier.inert(selecting)) { DriveModeSwitch(photos = onPhotosPage, onPhotos = showPage) }
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f),
                    // ticked items keep their page (the selection's bars belong to it)
                    // As a page beside Start, swiping changes the page: Dateien and Fotos by tapping only.
                    userScrollEnabled = DriveLogic.tabSwipe(hasPhotos, selecting) && !com.gatecontrol.android.ui.start.LocalInStartPager.current,
                    key = { it },
                ) { page ->
                    if (DriveLogic.isPhotosTab(page, hasPhotos)) {
                        DrivePhotosContent(
                            viewModel = photosViewModel,
                            topBar = {},
                            onAlbum = nav.album,
                            onCleanup = nav.cleanup,
                            onFile = nav.file,
                            places = PhotosPlacesNav(search = nav.photoSearch, map = nav.photoMap, place = nav.place, people = nav.people, person = nav.person),
                            ownHead = false,
                        )
                    } else {
                        DriveFilesPage(
                            folderViewModel = folderViewModel,
                            nav = nav,
                            grid = grid,
                            onCrumb = folderViewModel::open,
                            header = {
                                // Drive 1.6 (feature `guard`): unusual changes stopped — undo, release or look in the portal.
                                val portal = DriveLogic.portalDriveUrl(me)
                                DriveLogic.openIncidents(me).take(3).forEach { incident ->
                                    GuardBanner(
                                        incident = incident,
                                        busy = guardBusy == incident.id,
                                        onUndo = { guardAsk = incident to true },
                                        onRelease = { guardAsk = incident to false },
                                        onPortal = portal?.let { url -> { openPortal(context, url) } },
                                    )
                                }
                                wipes?.let { w ->
                                    GcBanner(tone = GcTone.Warn, icon = GcIcons.Trash) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(pluralStringResource(R.plurals.drive_wipes_banner, w.names.size, w.names.size), style = MaterialTheme.typography.bodyMedium)
                                            TextButton(onClick = {
                                                val sender = viewModel.trashRequest(w)
                                                if (sender == null) viewModel.wipesDone(w) else wipeLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                            }) { Text(stringResource(R.string.drive_wipes_action)) }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                TabBar(stringResource(R.string.drive_title), onMenu = null, onSearch = null)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val s = state) {
                        DriveLoad.Loading -> DriveLoading()
                        is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                        is DriveLoad.Ready -> when {
                            !s.value.me.licenceValid -> DriveErrorBox("not_licensed", null)
                            s.value.me.seat == null -> DriveErrorBox("no_seat", null)
                            else -> Text(stringResource(R.string.drive_no_areas), color = GateControlTheme.extraColors.muted)
                        }
                    }
                }
            }
        }
    }
    guardAsk?.let { (incident, undo) ->
        GuardConfirm(
            incident = incident,
            undo = undo,
            onConfirm = {
                guardAsk = null
                if (undo) viewModel.guardUndo(incident) else viewModel.guardRelease(incident)
            },
            onDismiss = { guardAsk = null },
        )
    }
}

/** The Files page of the Drive tab: the chosen area's top folder (its bar is the tab's own, above the pages). */
@Composable
private fun DriveFilesPage(
    folderViewModel: DriveFolderViewModel,
    nav: DriveNav,
    grid: androidx.compose.runtime.MutableState<Boolean>,
    header: @Composable () -> Unit,
    onCrumb: (Long) -> Unit,
) {
    DriveFolderContent(
        viewModel = folderViewModel,
        onFolder = nav.folder,
        onFile = nav.file,
        onVault = nav.vault,
        gridState = grid,
        topBar = { _, _ -> },
        header = header,
        onCrumb = onCrumb,
        // the tab shows the selection head in its own title row
        ownHead = false,
    )
}

/** Asks before undo (files are put back) or release (the device may write again). */
@Composable
private fun GuardConfirm(incident: DIncident, undo: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val device = incident.deviceName ?: stringResource(R.string.drive_guard_device_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (undo) R.string.drive_guard_undo_title else R.string.drive_guard_release_title)) },
        text = { Text(stringResource(if (undo) R.string.drive_guard_undo_text else R.string.drive_guard_release_text, device)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(if (undo) R.string.drive_guard_undo else R.string.drive_guard_release)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** "Drive hat ungewöhnliche Änderungen von <Gerät> gestoppt": how many files, some names, and what to do. */
@Composable
private fun GuardBanner(incident: DIncident, busy: Boolean, onUndo: () -> Unit, onRelease: () -> Unit, onPortal: (() -> Unit)?) {
    val device = incident.deviceName ?: stringResource(R.string.drive_guard_device_unknown)
    GcBanner(tone = GcTone.Error, icon = GcIcons.ShieldX) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.drive_guard_title, device), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val details = listOfNotNull(
                incident.files.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.drive_guard_files, it, it) },
                incident.sample.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.drive_guard_sample, it.take(3).joinToString(", ")) },
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onUndo) { Text(stringResource(R.string.drive_guard_undo)) }
                    TextButton(onClick = onRelease) { Text(stringResource(R.string.drive_guard_release)) }
                    if (onPortal != null) TextButton(onClick = onPortal) { Text(stringResource(R.string.drive_guard_portal)) }
                }
            }
        }
    }
}

/** A message of the Drive tab as a sentence (outside of composition): undo / release, else a Drive code. */
internal fun guardText(context: Context, message: String): String = when {
    message.startsWith("guard_undone:") -> {
        val n = message.substringAfter(':').toIntOrNull() ?: 0
        context.resources.getQuantityString(R.plurals.drive_guard_undone, n, n)
    }
    else -> driveText(context, message)
}

/** Menu, title (the area), sort, search. */
@Composable
private fun TabBar(title: String, onMenu: (() -> Unit)?, onSearch: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) {
            GcIconButton(GcIcons.Menu, contentDescription = stringResource(R.string.drive_menu), onClick = onMenu, tint = MaterialTheme.colorScheme.onBackground)
        } else {
            Spacer(Modifier.width(12.dp))
        }
        GcScreenTitle(title, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
        if (onSearch != null) GcIconButton(GcIcons.Search, contentDescription = stringResource(R.string.drive_search), onClick = onSearch, tint = MaterialTheme.colorScheme.onBackground)
        // "⋯" of the page when Drive is pinned to Start.
        com.gatecontrol.android.ui.start.StartPageMenuButton()
    }
}

/** An icon of the menu's bottom bar: [label] is its description (read out, shown on a long press). */
private data class DrawerIcon(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: Int, val onClick: () -> Unit)

/**
 * The menu (Drive 1.11): who and which seat, "Persönlich" and "Gemeinsam" with each area's folders as a tree (the arrow
 * opens, the name shows the folder), the vaults (only here), and a bar of icons below — trash, storage, "Ordner sichern",
 * deadlines, import (where Drive has them) and the Drive settings.
 */
@Composable
private fun DriveDrawer(
    me: DMe?,
    areas: List<DArea>,
    personal: List<DriveTree.Row>,
    shared: List<DriveTree.Row>,
    selected: Long?,
    onToggle: (Long) -> Unit,
    onOpen: (DriveTree.Row) -> Unit,
    /** The vaults (null: Drive has none). */
    vaults: List<DriveHomeViewModel.VaultEntry>?,
    onVault: (DriveHomeViewModel.VaultEntry) -> Unit,
    icons: List<DrawerIcon>,
) {
    Column(Modifier.fillMaxHeight()) {
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
            item(key = "head") { DrawerHead(me) }
            if (personal.isNotEmpty()) {
                item(key = "l-personal") { DrawerLabel(stringResource(R.string.drive_menu_personal)) }
                items(personal, key = { "p" + it.id }) { r -> TreeRow(r, selected = r.id == selected, onToggle = onToggle, onOpen = onOpen) }
            }
            if (shared.isNotEmpty()) {
                item(key = "l-shared") { DrawerLabel(stringResource(R.string.drive_menu_shared)) }
                items(shared, key = { "s" + it.id }) { r -> TreeRow(r, selected = r.id == selected, onToggle = onToggle, onOpen = onOpen) }
            }
            if (!vaults.isNullOrEmpty()) {
                item(key = "l-vaults") { DrawerLabel(stringResource(R.string.drive_vaults)) }
                items(vaults, key = { "v" + it.vault.id }) { e -> VaultRow(e, areas.firstOrNull { it.id == e.vault.areaId }, onClick = { onVault(e) }) }
            }
        }
        GcRowDivider()
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icons.forEach { DrawerIconButton(it) }
        }
    }
}

@Composable
private fun DrawerHead(me: DMe?) {
    val x = GateControlTheme.extraColors
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GcScreenTitle(stringResource(R.string.drive_title))
        val name = me?.name?.takeIf { it.isNotBlank() }
        if (name != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(x.accentBg), contentAlignment = Alignment.Center) {
                    Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = x.accentText, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (me.seat) {
                        "family" -> stringResource(R.string.drive_seat_family)
                        "business" -> stringResource(R.string.drive_seat_business)
                        else -> null
                    }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.muted) }
                }
            }
        }
    }
}

@Composable
private fun DrawerLabel(text: String) {
    GcSectionLabel(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}

/**
 * A row of the tree: the arrow (opens or closes, ≥ 44 dp) and the name (shows the folder on the Files page). An area
 * has its icon and its storage line; the folder shown now is highlighted.
 */
@Composable
private fun TreeRow(r: DriveTree.Row, selected: Boolean, onToggle: (Long) -> Unit, onOpen: (DriveTree.Row) -> Unit) {
    val x = GateControlTheme.extraColors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(x.accentBg) else Modifier)
            .heightIn(min = if (r.area != null) 56.dp else 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (r.depth > 0) Spacer(Modifier.width((20 * r.depth).dp))
        if (r.expandable) {
            val label = stringResource(if (r.expanded) R.string.drive_tree_collapse else R.string.drive_tree_expand, r.name)
            Box(
                Modifier
                    .size(width = 40.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = label) { onToggle(r.id) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (r.loading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = x.muted)
                } else {
                    Icon(if (r.expanded) GcIcons.ChevronDown else GcIcons.ChevronRight, contentDescription = null, tint = x.muted, modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Spacer(Modifier.width(40.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button) { onOpen(r) }
                .padding(top = 6.dp, bottom = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (r.area != null) {
                AreaIcon(r.area, size = 36)
            } else {
                Icon(GcIcons.Folder, contentDescription = null, tint = if (selected) x.accentText else x.muted, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (r.area != null) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) x.accentText else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (r.area != null) Text(areaLine(r.area), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A vault in the menu: lock icon, its name, whose it is ("Privat", "Mit Familie geteilt", a team) and whether it is locked. */
@Composable
private fun VaultRow(e: DriveHomeViewModel.VaultEntry, area: DArea?, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    val whose = when (area?.kind) {
        "mine" -> stringResource(R.string.drive_vault_whose_mine)
        "family" -> stringResource(R.string.drive_vault_whose_family)
        "team" -> stringResource(R.string.drive_vault_whose_team, area.label)
        null -> null
        else -> area.label
    }
    val state = when (e.state) {
        DriveHomeViewModel.VaultState.OPEN ->
            if (e.vault.pending > 0) pluralStringResource(R.plurals.drive_vault_requests_banner, e.vault.pending, e.vault.pending) else stringResource(R.string.drive_vault_state_open)
        DriveHomeViewModel.VaultState.LOCKED -> stringResource(R.string.drive_vault_state_locked)
        DriveHomeViewModel.VaultState.PENDING -> stringResource(R.string.drive_vault_menu_pending)
        DriveHomeViewModel.VaultState.NEW -> stringResource(R.string.drive_vault_state_new)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 40.dp, top = 6.dp, bottom = 6.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GcIconSquare(size = 36.dp, background = x.warnBg) {
            Icon(if (e.state == DriveHomeViewModel.VaultState.OPEN) GcIcons.LockOpen else GcIcons.Lock, contentDescription = null, tint = x.warn, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                e.name ?: stringResource(R.string.drive_vault),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(listOfNotNull(whose, state).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A 44 dp icon of the menu's bottom bar, described for TalkBack and on a long press. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DrawerIconButton(i: DrawerIcon) {
    val label = stringResource(i.label)
    val context = LocalContext.current
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(GateControlTheme.extraColors.panel2)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onLongClick = { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() },
                onClick = i.onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(i.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun AreaIcon(a: DArea, size: Int = 40) {
    val x = GateControlTheme.extraColors
    val (icon, fg, bg) = when (a.kind) {
        "family" -> Triple(GcIcons.Family, x.accentText, x.accentBg)
        "child" -> Triple(GcIcons.School, x.warn, x.warnBg)
        "team" -> Triple(GcIcons.Briefcase, x.blue, x.blueBg)
        else -> Triple(GcIcons.User, MaterialTheme.colorScheme.onSurface, x.panel2)
    }
    GcIconSquare(size = size.dp, background = bg) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size((size / 2).dp))
    }
}


/** "38 GB · nur für dich" — what an area is and how full it is. */
@Composable
private fun areaLine(a: DArea): String {
    val usage = DriveLogic.usage(a.storage, stringResource(R.string.drive_of))
    val what = when {
        a.archived -> stringResource(R.string.drive_area_archived)
        a.kind == "mine" -> stringResource(R.string.drive_area_mine)
        a.kind == "family" -> stringResource(R.string.drive_area_family)
        a.kind == "child" && a.via == "parent" -> stringResource(R.string.drive_area_child_parent)
        a.kind == "child" -> stringResource(R.string.drive_area_child_own)
        a.kind == "team" && !a.writable -> stringResource(R.string.drive_area_team_read)
        else -> stringResource(R.string.drive_area_team)
    }
    return listOfNotNull(usage, what).joinToString(" · ")
}

/** A picked file's name is taken: new version, keep both or skip. */
@Composable
internal fun ConflictDialog(u: DriveTransfers.Upload, onChoice: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_conflict_title, u.name)) },
        text = { Text(stringResource(R.string.drive_conflict_text)) },
        confirmButton = {
            Row {
                TextButton(onClick = { onChoice(null) }) { Text(stringResource(R.string.drive_conflict_skip)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onChoice(DriveLogic.ON_CONFLICT_RENAME) }) { Text(stringResource(R.string.drive_conflict_keep)) }
                TextButton(onClick = { onChoice(DriveLogic.ON_CONFLICT_VERSION) }) { Text(stringResource(R.string.drive_conflict_version)) }
            }
        },
    )
}
