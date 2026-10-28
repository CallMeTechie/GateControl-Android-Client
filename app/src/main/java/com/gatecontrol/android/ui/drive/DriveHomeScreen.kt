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
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcScreenTitle
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSwitchRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/** Where the Drive tab leads. */
data class DriveNav(
    val folder: (Long) -> Unit,
    val search: () -> Unit,
    /** The search of the Photos tab: photos and videos only. */
    val searchPhotos: () -> Unit = search,
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
    /** A vault folder (Drive 1.9). */
    val vault: (String, Long) -> Unit = { _, _ -> },
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
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val home = (state as? DriveLoad.Ready)?.value
    val usable = home != null && home.me.licenceValid && home.me.seat != null && home.areas.isNotEmpty()
    val current = if (usable) home?.let { h -> h.areas.firstOrNull { it.id == selected } ?: viewModel.current(h.areas) } else null
    LaunchedEffect(current?.rootId) { current?.let { folderViewModel.open(it.rootId) } }
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    // Drive 1.7 (feature `photos`): the tab has two pages, the files and the photos; a swipe goes from one to the other.
    val hasPhotos = me?.has(DMe.FEATURE_PHOTOS) == true
    val ticked by photosViewModel.selected.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = DriveLogic.tabOf(DriveViewPref.photos(context), hasPhotos)) { DriveLogic.tabCount(hasPhotos) }
    val onPhotosPage = DriveLogic.isPhotosTab(pager.currentPage, hasPhotos)
    val showPage: (Boolean) -> Unit = { photos -> scope.launch { pager.animateScrollToPage(DriveLogic.tabOf(photos, hasPhotos)) } }
    // the page the person ends on is kept; back on the tab (another page asked for the photos) it is shown again
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
    LaunchedEffect(drawer.isOpen, me?.features) { if (drawer.isOpen) viewModel.loadVaults() }
    val grid = rememberDriveGrid()

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.background) {
                val close: () -> Unit = { scope.launch { drawer.close() } }
                DriveDrawer(
                    areas = home?.areas.orEmpty().takeIf { usable }.orEmpty(),
                    current = current,
                    onArea = { a -> viewModel.select(a); if (onPhotosPage) showPage(false); close() },
                    onTrash = { close(); nav.trash() },
                    onFolders = { close(); nav.folders() },
                    onStorage = if (me?.has(DMe.FEATURE_STORAGE) == true) ({ close(); nav.storage() }) else null,
                    onDeadlines = if (me?.has(DMe.FEATURE_DOC_AI) == true) ({ close(); nav.deadlines() }) else null,
                    onImport = if (me?.has(DMe.FEATURE_CLOUD_IMPORT) == true) ({
                        scope.launch {
                            drawer.close()
                            viewModel.importUrl()?.let { openPortal(context, it) } ?: Toast.makeText(context, R.string.drive_portal_unavailable, Toast.LENGTH_LONG).show()
                        }
                    }) else null,
                    // Drive 1.8 (`docAi`): the AI per area, with the write right
                    docAi = current?.takeIf { me?.has(DMe.FEATURE_DOC_AI) == true && it.writable && it.docAi != null },
                    onDocAi = viewModel::setDocAi,
                    // Drive 1.9 (`vault`): every vault, opened from here
                    vaults = if (me?.has(DMe.FEATURE_VAULT) == true) vaults else null,
                    onVault = { e -> close(); nav.vault(e.vault.id, e.vault.nodeId) },
                    onNewVault = if (me?.has(DMe.FEATURE_VAULT) == true && current?.writable == true) ({
                        close()
                        showPage(false)
                        folderViewModel.startVault()
                    }) else null,
                )
            }
        },
    ) {
        if (home != null && current != null) {
            Column(Modifier.fillMaxSize()) {
                TabBar(
                    if (onPhotosPage) stringResource(R.string.drive_mode_photos) else current.label,
                    onMenu = openMenu,
                    onSearch = if (onPhotosPage) nav.searchPhotos else nav.search,
                    actions = { if (!onPhotosPage) FolderViewActions(folderViewModel, grid) },
                )
                if (hasPhotos) DriveModeSwitch(photos = onPhotosPage, onPhotos = showPage)
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f),
                    // ticked photos keep their page (the selection bar belongs to it)
                    userScrollEnabled = DriveLogic.tabSwipe(hasPhotos, ticked.isNotEmpty()),
                    key = { it },
                ) { page ->
                    if (DriveLogic.isPhotosTab(page, hasPhotos)) {
                        DrivePhotosContent(viewModel = photosViewModel, topBar = {}, onAlbum = nav.album)
                    } else {
                        DriveFilesPage(
                            folderViewModel = folderViewModel,
                            nav = nav,
                            grid = grid,
                            onPhotos = if (hasPhotos) ({ showPage(true) }) else null,
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
    onPhotos: (() -> Unit)?,
    header: @Composable () -> Unit,
) {
    DriveFolderContent(
        viewModel = folderViewModel,
        onFolder = nav.folder,
        onFile = nav.file,
        onVault = nav.vault,
        onPhotos = onPhotos,
        gridState = grid,
        topBar = { _, _ -> },
        header = header,
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
    }
}

/** The menu: the areas (with their storage), the trash, "Ordner sichern" and (Drive 1.7) the storage overview. */
@Composable
private fun DriveDrawer(
    areas: List<DArea>,
    current: DArea?,
    onArea: (DArea) -> Unit,
    onTrash: () -> Unit,
    onFolders: () -> Unit,
    onStorage: (() -> Unit)?,
    onDeadlines: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    /** The area whose "KI für Dokumente" can be switched here (null: none). */
    docAi: DArea? = null,
    onDocAi: (DArea, Boolean) -> Unit = { _, _ -> },
    /** The vaults (null: Drive has none). */
    vaults: List<DriveHomeViewModel.VaultEntry>? = null,
    onVault: (DriveHomeViewModel.VaultEntry) -> Unit = {},
    onNewVault: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        GcScreenTitle(stringResource(R.string.drive_title), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (areas.isNotEmpty()) {
            GcSectionLabel(stringResource(R.string.drive_move_areas), modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
            areas.forEach { a ->
                GcListRow(
                    title = a.label,
                    description = areaLine(a),
                    onClick = { onArea(a) },
                    leading = { AreaIcon(a) },
                    selected = a.id == current?.id,
                )
            }
            if (docAi != null) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_area_doc_ai),
                    description = stringResource(R.string.drive_area_doc_ai_desc, docAi.label),
                    checked = docAi.docAi == true,
                    onCheckedChange = { onDocAi(docAi, it) },
                    icon = GcIcons.Receipt,
                )
            }
            GcRowDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        // Drive 1.9 (`vault`): the vaults are opened from here (their folders also stay in the lists)
        if (vaults != null && (vaults.isNotEmpty() || onNewVault != null)) {
            GcSectionLabel(stringResource(R.string.drive_vaults), modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
            vaults.forEach { e ->
                val area = areas.firstOrNull { it.id == e.vault.areaId }?.label
                val status = when (e.state) {
                    DriveHomeViewModel.VaultState.OPEN -> if (e.vault.pending > 0) pluralStringResource(R.plurals.drive_vault_requests_banner, e.vault.pending, e.vault.pending) else null
                    DriveHomeViewModel.VaultState.PENDING -> stringResource(R.string.drive_vault_menu_pending)
                    DriveHomeViewModel.VaultState.LOCKED -> stringResource(R.string.drive_vault_menu_locked)
                }
                val x = GateControlTheme.extraColors
                GcListRow(
                    title = e.name ?: stringResource(R.string.drive_vault_menu_locked_title),
                    description = listOfNotNull(area, status).joinToString(" · ").ifEmpty { null },
                    onClick = { onVault(e) },
                    leading = {
                        GcIconSquare(size = 40.dp, background = x.warnBg) {
                            Icon(if (e.state == DriveHomeViewModel.VaultState.OPEN) GcIcons.Vault else GcIcons.Lock, contentDescription = null, tint = x.warn, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
            if (onNewVault != null) {
                GcListRow(
                    title = stringResource(R.string.drive_vault_new),
                    description = stringResource(R.string.drive_vault_new_desc),
                    onClick = onNewVault,
                    leading = {
                        GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                            Icon(GcIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
            GcRowDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        GcListRow(
            title = stringResource(R.string.drive_trash),
            onClick = onTrash,
            leading = {
                GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                    Icon(GcIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            },
        )
        if (onStorage != null) {
            GcListRow(
                title = stringResource(R.string.drive_storage_title),
                onClick = onStorage,
                leading = {
                    GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                        Icon(GcIcons.Activity, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                },
            )
        }
        if (onDeadlines != null) {
            GcListRow(
                title = stringResource(R.string.drive_deadlines),
                onClick = onDeadlines,
                leading = {
                    GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                        Icon(GcIcons.Calendar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                },
            )
        }
        GcListRow(
            title = stringResource(R.string.drive_folders_title),
            onClick = onFolders,
            leading = {
                GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                    Icon(GcIcons.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            },
        )
        // Drive 1.8 (`cloudImport`): the import runs in the portal only
        if (onImport != null) {
            GcListRow(
                title = stringResource(R.string.drive_import),
                description = stringResource(R.string.drive_import_desc),
                onClick = onImport,
                leading = {
                    GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.panel2) {
                        Icon(GcIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                    }
                },
                trailing = { Icon(GcIcons.External, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(18.dp)) },
            )
        }
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
