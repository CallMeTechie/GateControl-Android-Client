package com.gatecontrol.android.ui.drive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.res.pluralStringResource
import com.gatecontrol.android.drive.DriveBulk
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.gatecontrol.android.drive.DriveLocal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DLink
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One folder of an area as a page of its own (with back). */
@Composable
fun DriveFolderScreen(
    onBack: () -> Unit,
    onFolder: (Long) -> Unit,
    onFile: (Long) -> Unit = {},
    onVault: (String, Long) -> Unit = { _, _ -> },
    /** A tap on the path above: back to the page with that folder, else a new page. */
    onCrumb: ((Long) -> Unit)? = null,
    viewModel: DriveFolderViewModel = hiltViewModel(),
) {
    DriveFolderContent(
        viewModel = viewModel,
        onFolder = onFolder,
        onFile = onFile,
        onVault = onVault,
        onCrumb = onCrumb,
        topBar = { title, actions -> GcSubpageBar(title, onBack = onBack, backDescription = stringResource(R.string.drive_back)) { actions() } },
    )
}

/** "N ausgewählt" of a folder: the summary of what is ticked, "Alle" / "Keine". */
@Composable
internal fun FolderSelectionHead(viewModel: DriveFolderViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.bulk.selected.collectAsStateWithLifecycle()
    val items = (state as? DriveLoad.Ready)?.value?.items.orEmpty()
    val chosen = remember(selected, items) { items.filter { it.id in selected } }
    SelectionHead(
        count = chosen.size,
        line = summaryText(DriveBulk.summary(chosen)),
        allSelected = DriveBulk.allSelected(selected, items.map { it.id }),
        onClose = viewModel.bulk::clear,
        onAll = viewModel.bulk::allOrNone,
    )
}

/**
 * A folder: its content, upload, new folder, and per item open, send, link, rename, move, versions, trash. [topBar]
 * gets the title and the sort button; [header] goes above the items (the Drive tab: "Zuletzt").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DriveFolderContent(
    viewModel: DriveFolderViewModel,
    onFolder: (Long) -> Unit,
    topBar: @Composable (title: String, actions: @Composable () -> Unit) -> Unit,
    header: @Composable () -> Unit = {},
    /** The page of a file ("Details"). */
    onFile: (Long) -> Unit = {},
    /** A vault folder (Drive 1.9): its own pages, the names decrypted there. */
    onVault: (vaultId: String, folderId: Long) -> Unit = { _, _ -> },
    /** List or tiles (shared with the Drive tab's own bar, [FolderViewActions]). */
    gridState: androidx.compose.runtime.MutableState<Boolean> = rememberDriveGrid(),
    /** A tap on the path above (the Drive tab: the folder shows in the tab itself); else [onFolder]. */
    onCrumb: ((Long) -> Unit)? = null,
    /** The page shows its own selection head in place of [topBar] (false: the Drive tab shows it, [FolderSelectionHead]). */
    ownHead: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val area by viewModel.area.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val uploads by viewModel.transfers.uploads.collectAsStateWithLifecycle()
    val downloading by viewModel.transfers.downloading.collectAsStateWithLifecycle()
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    val linkSheet by viewModel.linkSheet.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val scanDraft by viewModel.scan.collectAsStateWithLifecycle()
    val timeTravel by viewModel.timeTravel.collectAsStateWithLifecycle()
    val newVault by viewModel.newVault.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var menuFor by remember { mutableStateOf<DNode?>(null) }
    var renaming by remember { mutableStateOf<DNode?>(null) }
    var deleting by remember { mutableStateOf<DNode?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<DriveTransfers.Upload?>(null) }
    val grid by gridState
    var uploadsOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    /** The node being deleted and what this phone has of it (null while it looks). */
    var deleteCopies by remember { mutableStateOf<DriveLocal.Copies?>(null) }
    var localDelete by remember { mutableStateOf<DriveLocal.Copies?>(null) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val c = localDelete
        localDelete = null
        if (result.resultCode == Activity.RESULT_OK && c != null) viewModel.deletedLocally(c)
    }
    val me by viewModel.live.me.collectAsStateWithLifecycle()

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris)
    }
    val startScan = rememberDriveScan(viewModel.scanner, viewModel::scanned)

    val folder = (state as? DriveLoad.Ready)?.value
    val title = when {
        folder == null -> stringResource(R.string.drive_title)
        folder.node.parentId == null -> area?.label ?: stringResource(R.string.drive_title)
        else -> folder.node.name
    }
    val open: (DNode, Boolean) -> Unit = { n, send ->
        scope.launch {
            fetchThen(context, n, { viewModel.fetch(it) }, then = { f -> if (send) sendFile(context, n, f) else openFile(context, n, f) }) { code ->
                Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
            }
        }
    }

    val selected by viewModel.bulk.selected.collectAsStateWithLifecycle()
    val running by viewModel.ops.running.collectAsStateWithLifecycle()
    val busy by viewModel.bulk.busy.collectAsStateWithLifecycle()
    val fresh by viewModel.ops.fresh.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var moreOpen by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val haptic = LocalHapticFeedback.current
    // back ends the selection first
    BackHandler(enabled = selecting) { viewModel.bulk.clear() }
    val items = folder?.items.orEmpty()
    val chosen = remember(selected, items) { items.filter { it.id in selected } }
    val where = DriveBulk.Where(writable = { folder?.writable == true }, child = { area?.via == "parent" })

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (ownHead) {
            SelectionSwap(selecting, bar = { topBar(title) { FolderViewActions(viewModel, gridState) } }, selection = { FolderSelectionHead(viewModel) })
        } else {
            topBar(title) { FolderViewActions(viewModel, gridState) }
        }
        if (folder != null && folder.crumbs.isNotEmpty()) Crumbs(folder, area?.label, onCrumb ?: onFolder)
        Column(
            // bottom: room for the round button (or the selection's bar) over the last item
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (area?.via == "parent") {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Eye) { Text(stringResource(R.string.drive_child_note), style = MaterialTheme.typography.bodyMedium) }
            }
            // sending or zipping the selection here (the jobs on several items show in the round button)
            BulkBusyCard(busy, viewModel.bulk::cancelBusy)
            header()
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                is DriveLoad.Ready -> {
                    if (s.value.items.isEmpty()) {
                        Text(stringResource(R.string.drive_empty_folder), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
                    } else {
                        val onItem: (DNode) -> Unit = { n ->
                            val vault = n.vaultId?.takeIf { it != com.gatecontrol.android.drive.DriveJson.INVALID_VAULT }
                            when {
                                // while ticking, a tap ticks
                                selecting -> viewModel.bulk.toggle(n)
                                n.isDir && vault != null -> onVault(vault, n.id)
                                n.isDir -> onFolder(n.id)
                                // a vault's file never opens here (its content is ciphertext)
                                n.encrypted -> Unit
                                else -> open(n, false)
                            }
                        }
                        val onLong: (DNode) -> Unit = { n -> if (selecting) viewModel.bulk.toggle(n) else viewModel.bulk.select(n) }
                        val isFresh: (DNode) -> Boolean = { n -> n.id in fresh }
                        if (grid) {
                            NodeGrid(
                                s.value.items,
                                onClick = onItem,
                                onLongClick = onLong,
                                progress = { downloading[it.id] },
                                selected = selected,
                                onMore = { menuFor = it },
                                fresh = isFresh,
                            )
                        } else {
                            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                s.value.items.forEachIndexed { i, n ->
                                    if (i > 0) GcRowDivider()
                                    NodeRow(
                                        node = n,
                                        onClick = { onItem(n) },
                                        progress = downloading[n.id],
                                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLong(n) },
                                        selecting = selecting,
                                        selected = n.id in selected,
                                        onMore = { menuFor = n },
                                        fresh = isFresh(n),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (selecting) {
            SelectionActionBar(
                listOf(
                    BarAction(GcIcons.Share, stringResource(R.string.drive_bar_send), viewModel.bulk.can(DriveBulk.Action.SEND, chosen, where)) { viewModel.bulk.send() },
                    BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), viewModel.bulk.can(DriveBulk.Action.MOVE, chosen, where)) { viewModel.bulk.openTarget(DriveBulk.Mode.MOVE) },
                    BarAction(GcIcons.Copy, stringResource(R.string.drive_bar_copy), viewModel.bulk.can(DriveBulk.Action.COPY, chosen, where)) { viewModel.bulk.openTarget(DriveBulk.Mode.COPY) },
                    BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), viewModel.bulk.can(DriveBulk.Action.DELETE, chosen, where), danger = true) { viewModel.bulk.delete() },
                    BarAction(GcIcons.MoreHorizontal, stringResource(R.string.drive_bar_more), DriveBulk.Can.YES) { moreOpen = true },
                ),
            )
        }
    }
    // Drive 1.14 (D4): file the new upload where Drive suggests
    val filing by viewModel.filingProposal.collectAsStateWithLifecycle()
    filing?.takeIf { !selecting }?.let { p ->
        FilingBar(
            p,
            onAccept = viewModel::acceptFiling,
            onDismiss = viewModel::dismissFiling,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
        )
    }
    if (!selecting) {
        DriveFab(
            uploads,
            onAdd = if (folder?.writable == true) ({ addOpen = true }) else null,
            onOpenUploads = { uploadsOpen = true },
            onAllDone = viewModel.transfers::clearFinished,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp),
            jobs = running,
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (selecting) 88.dp else 84.dp))
    }

    if (addOpen) {
        ModalBottomSheet(onDismissRequest = { addOpen = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                GcListRow(
                    title = stringResource(R.string.drive_new_folder),
                    onClick = { addOpen = false; newFolder = true },
                    leading = { Icon(GcIcons.FolderPlus, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                )
                GcListRow(
                    title = stringResource(R.string.drive_upload),
                    onClick = { addOpen = false; pick.launch(arrayOf("*/*")) },
                    leading = { Icon(GcIcons.Upload, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                )
                GcListRow(
                    title = stringResource(R.string.drive_scan),
                    description = stringResource(R.string.drive_scan_desc),
                    onClick = { addOpen = false; startScan() },
                    leading = { Icon(GcIcons.Scan, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                )
                // Drive 1.9 (feature `vault`): an end-to-end encrypted folder. Drive 1.11: only here, in the top folder of
                // the area chosen in the dialog, and it shows in the menu under "Tresore" (never in the lists)
                if (me?.has(DMe.FEATURE_VAULT) == true && folder?.node?.encrypted == false) {
                    GcRowDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    GcListRow(
                        title = stringResource(R.string.drive_vault_new),
                        description = stringResource(R.string.drive_vault_new_menu_hint),
                        onClick = { addOpen = false; viewModel.startVault() },
                        leading = {
                            GcIconSquare(size = 40.dp, background = GateControlTheme.extraColors.warnBg) {
                                Icon(GcIcons.Lock, contentDescription = null, tint = GateControlTheme.extraColors.warn, modifier = Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }
        }
    }

    if (uploadsOpen && (uploads.isNotEmpty() || running.isNotEmpty())) {
        ModalBottomSheet(onDismissRequest = { uploadsOpen = false }) {
            UploadsSheet(
                uploads,
                onConflict = { uploadsOpen = false; conflict = it },
                onClear = viewModel.transfers::clearFinished,
                jobs = running,
                onCancelJob = { id -> scope.launch { viewModel.ops.cancel(id) } },
            )
        }
    }

    // ─── sheets and dialogs ─────────────────────────────────────────

    /**
     * "Mehr" of the selection ([fromRow] false) or ⋯ of one row ([fromRow]: the everyday actions as icons on top). With
     * one item every action of before is there; with several the ones for one only are greyed out.
     */
    val moreSheet: @Composable (List<DNode>, Boolean, () -> Unit) -> Unit = { ns, fromRow, close ->
        val one = ns.singleOrNull()
        val writable = folder?.writable == true
        val bulk = viewModel.bulk
        val can: (DriveBulk.Action) -> DriveBulk.Can = { a -> bulk.can(a, ns, where) }
        val quick = if (!fromRow || one == null) emptyList() else buildList {
            add(BarAction(GcIcons.Share, stringResource(R.string.drive_bar_send), can(DriveBulk.Action.SEND)) { if (one.isDir) bulk.sendZip(ns) else open(one, true) })
            add(BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), can(DriveBulk.Action.MOVE)) { bulk.openTarget(DriveBulk.Mode.MOVE, ns) })
            add(BarAction(GcIcons.Copy, stringResource(R.string.drive_bar_copy), can(DriveBulk.Action.COPY)) { bulk.openTarget(DriveBulk.Mode.COPY, ns) })
            // Read only: a file can still go from this phone (its own copy), the sheet offers what applies.
            add(BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), if (writable || !one.isDir) DriveBulk.Can.YES else can(DriveBulk.Action.DELETE), danger = true) { deleting = one })
        }
        val linkCan = can(DriveBulk.Action.LINK)
        val multi = listOf(
            MoreItem(GcIcons.Download, stringResource(R.string.drive_more_save), stringResource(R.string.drive_more_save_desc), can(DriveBulk.Action.SAVE)) { bulk.save(ns) },
            MoreItem(GcIcons.Archive, stringResource(R.string.drive_more_zip), stringResource(R.string.drive_more_zip_desc), can(DriveBulk.Action.ZIP)) { bulk.sendZip(ns) },
            MoreItem(
                GcIcons.Link,
                stringResource(R.string.drive_share_link),
                if (one != null) stringResource(R.string.drive_more_link_one) else pluralStringResource(R.plurals.drive_more_link, ns.size, ns.size),
                linkCan,
            ) { if (one != null) viewModel.openLinks(one) else bulk.openLink(ns) },
        )
        val single = buildList {
            if (one != null && !one.isDir) add(MoreItem(GcIcons.External, stringResource(R.string.drive_open)) { open(one, false) })
            add(MoreItem(GcIcons.Edit, stringResource(R.string.drive_rename), can = can(DriveBulk.Action.RENAME)) { one?.let { renaming = it } })
            if (one == null) {
                add(MoreItem(GcIcons.Info, stringResource(R.string.drive_more_details), can = DriveBulk.Can.no(DriveBulk.Why.ONLY_ONE)) {})
            } else if (!one.isDir) {
                // Drive 1.8 (`docAi`) and more: the file's page with what was recognised and its deadlines
                val kind = DriveLogic.docKind(one)
                add(MoreItem(if (kind != null) docKindIcon(kind) else GcIcons.Info, stringResource(R.string.drive_details), kind?.let { stringResource(R.string.drive_insight_title) + ": " + stringResource(docKindLabel(it)) }) { onFile(one.id) })
                add(MoreItem(GcIcons.History, stringResource(R.string.drive_versions)) { viewModel.openVersions(one) })
            }
            // Drive 1.9 (`office`): the document in the browser
            if (one == null || DriveLogic.officeAllowed(me, one)) {
                add(MoreItem(GcIcons.Globe, stringResource(if (writable) R.string.drive_office_edit else R.string.drive_office_view), can = can(DriveBulk.Action.OFFICE)) {
                    one?.let { n -> scope.launch { openOffice(context, viewModel.office(n)) } }
                })
            }
            // Drive 1.7 (feature `timeTravel`): the folder back to an earlier state.
            if (one != null && one.isDir && writable && me?.has(DMe.FEATURE_TIME_TRAVEL) == true) add(MoreItem(GcIcons.History, stringResource(R.string.drive_tt_menu)) { viewModel.startTimeTravel(one) })
            // Drive 1.6 (feature `links`): the file or folder in the portal.
            one?.let { n -> DriveLogic.portalNodeUrl(me, n.id) }?.let { url -> add(MoreItem(GcIcons.Globe, stringResource(R.string.drive_open_portal)) { openPortal(context, url) }) }
        }
        val last = if (me?.has(DMe.FEATURE_ALBUMS) == true) {
            listOf(MoreItem(GcIcons.Photo, stringResource(R.string.drive_more_album), can = can(DriveBulk.Action.ALBUM)) { bulk.openAlbums(ns) })
        } else {
            emptyList()
        }
        BulkMoreSheet(
            count = ns.size,
            title = one?.let { nodeTitle(it) }.takeIf { fromRow },
            summary = summaryText(DriveBulk.summary(ns)),
            quick = quick,
            multi = multi,
            single = single,
            last = last,
            onDismiss = close,
        )
    }
    menuFor?.let { n -> moreSheet(listOf(n), true) { menuFor = null } }
    if (moreOpen && chosen.isNotEmpty()) moreSheet(chosen, false) { moreOpen = false }
    DriveBulkHost(viewModel.bulk, snackbar)

    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            onConfirm = { newFolder = false; viewModel.createFolder(it) },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { n ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = n.name,
            confirm = stringResource(R.string.drive_save),
            onConfirm = { renaming = null; if (it != n.name) viewModel.rename(n, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { n ->
        LaunchedEffect(n.id) { deleteCopies = null; deleteCopies = viewModel.local.copies(n) }
        val copies = deleteCopies
        val hasLocal = copies?.any == true
        val writable = folder?.writable == true
        val close = { deleting = null; deleteCopies = null }
        ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.drive_delete_title, nodeTitle(n)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                if (copies != null && !hasLocal && !writable) {
                    Text(stringResource(R.string.drive_delete_nothing), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if (copies == null) {
                    Text(stringResource(R.string.drive_delete_checking), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                val option: @Composable (ImageVector, Int, Int, () -> Unit) -> Unit = { icon, label, desc, action ->
                    GcListRow(
                        title = stringResource(label),
                        description = stringResource(desc),
                        onClick = { close(); action() },
                        leading = { Icon(icon, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                    )
                }
                if (hasLocal) {
                    option(GcIcons.Phone, R.string.drive_delete_device, R.string.drive_delete_device_desc) {
                        val c = copies!!
                        scope.launch {
                            val sender = viewModel.deleteLocal(c).takeIf { it.isNotEmpty() }?.let { viewModel.local.trashRequest(it) }
                            if (sender != null) {
                                localDelete = c
                                trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        }
                    }
                }
                if (writable) {
                    option(GcIcons.Cloud, R.string.drive_delete_server, if (hasLocal) R.string.drive_delete_server_desc_local else R.string.drive_delete_server_desc) {
                        viewModel.delete(n, everywhere = false, copies = copies)
                    }
                    option(GcIcons.Trash, R.string.drive_delete_everywhere, R.string.drive_delete_everywhere_desc) {
                        viewModel.delete(n, everywhere = true, copies = copies)
                        // This phone's copy goes now (a gallery one after Android asks); the other devices follow when online.
                        val c = copies
                        if (c != null && c.any) {
                            scope.launch {
                                val sender = viewModel.deleteLocal(c).takeIf { it.isNotEmpty() }?.let { viewModel.local.trashRequest(it) }
                                if (sender != null) {
                                    localDelete = c
                                    trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    conflict?.let { u -> ConflictDialog(u, onChoice = { viewModel.transfers.resolve(u.id, it); conflict = null }, onDismiss = { conflict = null }) }

    versions?.let { v ->
        ModalBottomSheet(onDismissRequest = viewModel::closeVersions) {
            VersionsSheet(v, writable = folder?.writable == true, onOpen = { node, vid ->
                scope.launch {
                    fetchThen(context, node, { viewModel.fetch(it, vid) }, then = { f -> openFile(context, node, f) }) { code ->
                        Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
                    }
                }
            }, onRestore = viewModel::restoreVersion, onDelete = viewModel::deleteVersion)
        }
    }
    linkSheet?.let { sheet ->
        ModalBottomSheet(onDismissRequest = viewModel::closeLinks) {
            LinkSheetContent(sheet, onCreate = viewModel::createLink, onEnd = viewModel::endLink)
        }
    }
    scanDraft?.takeIf { it.review }?.let { d ->
        ScanReviewDialog(
            draft = d,
            onFormat = viewModel::setScanFormat,
            onName = viewModel::setScanName,
            onPickFolder = viewModel::pickScanFolder,
            onKeepOriginal = viewModel::setKeepOriginal,
            onSave = viewModel::saveReviewed,
            onClose = viewModel::closeScan,
        )
    }
    scanDraft?.takeIf { !it.review }?.let { d ->
        ScanSaveDialog(
            draft = d,
            onPdf = viewModel::setScanPdf,
            onPickFolder = viewModel::pickScanFolder,
            onRescan = { viewModel.closeScan(); startScan() },
            onSave = viewModel::saveScan,
            onClose = viewModel::closeScan,
        )
    }
    newVault?.let { v ->
        val key = v.recoveryKey
        if (key == null) {
            NewVaultDialog(v, onCreate = viewModel::createVault, onDismiss = viewModel::closeVault)
        } else {
            RecoveryKeyDialog(key, onDone = {
                viewModel.closeVault()
                val id = v.vaultId
                val node = v.nodeId
                if (id != null && node != null) onVault(id, node)
            })
        }
    }
    timeTravel?.let { t ->
        TimeTravelDialogs(
            t,
            onPick = viewModel::timeTravelAt,
            onRepick = viewModel::timeTravelRepick,
            onRun = viewModel::timeTravelRun,
            onUndo = viewModel::timeTravelUndo,
            onClose = viewModel::closeTimeTravel,
        )
    }
    // after the scan dialog: its folder choice opens on top of it
    picker?.let { p ->
        MovePicker(p, onBrowse = viewModel::browse, onMove = viewModel::moveHere, onDismiss = viewModel::closePicker, onNewFolder = viewModel::createFolderIn)
    }
}

/**
 * List or tiles and the sort (name, date, size, file type; up or down) of a folder — on the folder's bar, on the Drive
 * tab on its own bar above the pages.
 */
@Composable
internal fun FolderViewActions(viewModel: DriveFolderViewModel, gridState: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    var grid by gridState
    var sortOpen by remember { mutableStateOf(false) }
    GcIconButton(
        if (grid) GcIcons.ViewList else GcIcons.ViewGrid,
        contentDescription = stringResource(if (grid) R.string.drive_view_list else R.string.drive_view_grid),
        onClick = { grid = !grid; DriveViewPref.setGrid(context, grid) },
        tint = MaterialTheme.colorScheme.onBackground,
    )
    Box {
        GcIconButton(GcIcons.Sliders, contentDescription = stringResource(R.string.drive_sort), onClick = { sortOpen = true }, tint = MaterialTheme.colorScheme.onBackground)
        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
            listOf(
                "name" to R.string.drive_sort_name,
                "mtime" to R.string.drive_sort_date,
                "size" to R.string.drive_sort_size,
                "type" to R.string.drive_sort_type,
            ).forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { if (sort.key == key) Icon(GcIcons.Check, contentDescription = null) },
                    onClick = { sortOpen = false; viewModel.setSort(key) },
                )
            }
            HorizontalDivider()
            listOf(false to R.string.drive_sort_asc, true to R.string.drive_sort_desc).forEach { (desc, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { if (sort.desc == desc) Icon(GcIcons.Check, contentDescription = null) },
                    onClick = { sortOpen = false; viewModel.setSortDesc(desc) },
                )
            }
        }
    }
}

/** A portal page (a direct link of Drive) in the browser. */
internal fun openPortal(context: Context, url: String) {
    val ok = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!ok) Toast.makeText(context, R.string.drive_portal_unavailable, Toast.LENGTH_LONG).show()
}

/** Area › Folder › Folder: tap to go up. */
@Composable
private fun Crumbs(folder: DFolder, areaLabel: String?, onFolder: (Long) -> Unit) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        folder.crumbs.forEachIndexed { i, c ->
            val label = if (i == 0) areaLabel ?: "…" else c.name
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = x.accentText,
                modifier = Modifier.heightIn(min = 32.dp).clickable(role = Role.Button) { onFolder(c.id) }.padding(vertical = 6.dp),
            )
            Text("  ›  ", style = MaterialTheme.typography.bodySmall, color = x.faint)
        }
        Text(folder.node.name, style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}

/** Name of a new folder or a rename; refuses the characters a NAS cannot store. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val valid = DriveLogic.validName(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            GcTextField(
                value = value,
                onValueChange = { value = it.take(255) },
                label = stringResource(R.string.drive_name),
                error = if (value.isNotEmpty() && !valid) stringResource(R.string.drive_name_invalid) else null,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

private fun shortDate(iso: String?): String? = iso?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.parse(it))
    }.getOrNull()
}

@Composable
private fun VersionsSheet(
    v: DriveLoad<com.gatecontrol.android.drive.DVersions>,
    writable: Boolean,
    onOpen: (DNode, Long?) -> Unit,
    onRestore: (DNode, Long) -> Unit,
    onDelete: (DNode, Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.drive_versions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        when (v) {
            DriveLoad.Loading -> DriveLoading()
            is DriveLoad.Error -> DriveErrorBox(v.code, null)
            is DriveLoad.Ready -> {
                val cur = v.value.current
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    GcListRow(
                        title = stringResource(R.string.drive_version_current),
                        description = listOfNotNull(DriveLogic.size(cur.size), shortDate(cur.mtime ?: cur.updatedAt)).joinToString(" · "),
                        onClick = { onOpen(cur, null) },
                        leading = { NodeIcon(cur, 36) },
                    )
                    v.value.versions.forEach { ver ->
                        GcRowDivider()
                        GcListRow(
                            title = shortDate(ver.at) ?: "#${ver.rev}",
                            description = DriveLogic.size(ver.size),
                            onClick = { onOpen(cur, ver.id) },
                            leading = { Icon(GcIcons.History, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.size(22.dp)) },
                            trailing = if (writable) ({
                                Row {
                                    TextButton(onClick = { onRestore(cur, ver.id) }) { Text(stringResource(R.string.drive_version_restore)) }
                                    GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.drive_version_delete), onClick = { onDelete(cur, ver.id) }, size = 40.dp, iconSize = 18.dp)
                                }
                            }) else null,
                        )
                    }
                }
                if (v.value.versions.isEmpty()) {
                    Text(stringResource(R.string.drive_versions_none), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun LinkSheetContent(sheet: DriveFolderViewModel.LinkSheet, onCreate: (String, String, String?) -> Unit, onEnd: (String) -> Unit) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    var mode by remember { mutableStateOf(if (sheet.node.isDir) "view" else "download") }
    var expires by remember { mutableStateOf("7d") }
    var pin by remember { mutableStateOf("") }
    val pinOk = pin.isEmpty() || Regex("^[0-9]{4,12}$").matches(pin)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.drive_share_link) + " · " + sheet.node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        sheet.created?.url?.let { url -> CreatedLink(context, url) }
        sheet.error?.let { GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) { Text(driveMessage(it).orEmpty(), style = MaterialTheme.typography.bodyMedium) } }
        if (sheet.enabled && sheet.created == null) {
            GcSectionLabel(stringResource(R.string.drive_link_who))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GcFilterChip(stringResource(R.string.drive_link_view), selected = mode == "view", onClick = { mode = "view" })
                GcFilterChip(stringResource(R.string.drive_link_download), selected = mode == "download", onClick = { mode = "download" })
                if (sheet.node.isDir) GcFilterChip(stringResource(R.string.drive_link_upload), selected = mode == "upload", onClick = { mode = "upload" })
            }
            GcSectionLabel(stringResource(R.string.drive_link_valid))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1d" to R.string.drive_link_1d, "7d" to R.string.drive_link_7d, "30d" to R.string.drive_link_30d, "never" to R.string.drive_link_never).forEach { (k, l) ->
                    GcFilterChip(stringResource(l), selected = expires == k, onClick = { expires = k })
                }
            }
            GcTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                label = stringResource(R.string.drive_link_pin),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                error = if (!pinOk) stringResource(R.string.drive_link_pin) else null,
            )
            GcPrimaryButton(stringResource(R.string.drive_link_create), onClick = { onCreate(mode, expires, pin.ifBlank { null }) }, enabled = pinOk, icon = GcIcons.Link)
        }
        when (val l = sheet.links) {
            DriveLoad.Loading -> DriveLoading()
            is DriveLoad.Error -> if (sheet.error == null) DriveErrorBox(l.code, null)
            is DriveLoad.Ready -> if (l.value.isNotEmpty()) {
                GcSectionLabel(stringResource(R.string.drive_links_active))
                GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    l.value.forEachIndexed { i, link ->
                        if (i > 0) GcRowDivider()
                        LinkRow(context, link, onEnd)
                    }
                }
            }
        }
        if (!sheet.enabled) Text(stringResource(R.string.drive_code_links_disabled), color = x.muted)
    }
}

@Composable
private fun CreatedLink(context: Context, url: String) {
    GcCard {
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GcOutlineButton(stringResource(R.string.drive_link_copy), onClick = { copy(context, url) }, icon = GcIcons.Copy, modifier = Modifier.weight(1f))
            GcPrimaryButton(stringResource(R.string.drive_link_share), onClick = { shareText(context, url) }, icon = GcIcons.Share, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LinkRow(context: Context, link: DLink, onEnd: (String) -> Unit) {
    val mode = when (link.mode) {
        "view" -> stringResource(R.string.drive_link_view)
        "upload" -> stringResource(R.string.drive_link_upload)
        else -> stringResource(R.string.drive_link_download)
    }
    val until = shortDate(link.expiresAt)?.let { stringResource(R.string.drive_link_until, it) } ?: stringResource(R.string.drive_link_endless)
    GcListRow(
        title = mode,
        description = listOf(until, stringResource(R.string.drive_link_stats, link.opens, link.downloads)).joinToString(" · "),
        onClick = link.url?.let { u -> { copy(context, u) } },
        leading = { Icon(GcIcons.Link, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
        trailing = { TextButton(onClick = { onEnd(link.token) }) { Text(stringResource(R.string.drive_link_end)) } },
    )
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Drive", text))
    Toast.makeText(context, R.string.drive_link_copied, Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Where to move an item: the writable areas, then their folders. */
@Composable
private fun MovePicker(
    p: DriveFolderViewModel.Picker,
    onBrowse: (Long?) -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
    onNewFolder: suspend (Long, String) -> DResult<DNode>,
) {
    val node = p.node
    FolderPickerDialog(
        title = if (node == null) stringResource(R.string.drive_scan_folder_title) else stringResource(R.string.drive_move_title, node.name),
        areas = p.areas,
        folder = p.folder,
        confirm = stringResource(if (node == null) R.string.drive_scan_folder_here else R.string.drive_move_here),
        canPick = { it.writable && (node == null || (it.node.id != node.parentId && it.node.id != node.id)) },
        hide = node?.id,
        onBrowse = onBrowse,
        onPick = onMove,
        onDismiss = onDismiss,
        onNewFolder = onNewFolder,
    )
}

/**
 * Choosing a folder: the areas first, then the folders below; [hide] leaves one out (the folder being moved). With
 * [onNewFolder] a writable folder offers "Neuer Ordner": it is made there and shown at once, so it is the one picked.
 */
@Composable
internal fun FolderPickerDialog(
    title: String,
    areas: List<DArea>,
    folder: DriveLoad<DFolder>?,
    confirm: String,
    canPick: (DFolder) -> Boolean,
    onBrowse: (Long?) -> Unit,
    onPick: () -> Unit,
    onDismiss: () -> Unit,
    hide: Long? = null,
    onNewFolder: (suspend (parentId: Long, name: String) -> DResult<DNode>)? = null,
    /** A further button next to "Abbrechen" (e.g. "Kein Ordner für Arbeitsfotos"). */
    extra: (@Composable () -> Unit)? = null,
) {
    val x = GateControlTheme.extraColors
    val ready = (folder as? DriveLoad.Ready)?.value
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newError by remember { mutableStateOf<String?>(null) }
    if (naming && ready != null && onNewFolder != null) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            onConfirm = { name ->
                naming = false
                creating = true
                newError = null
                val parent = ready.node.id
                scope.launch {
                    when (val r = onNewFolder(parent, name)) {
                        is DResult.Ok -> onBrowse(r.value.id)
                        else -> newError = r.errorCode()
                    }
                    creating = false
                }
            },
            onDismiss = { naming = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (val f = folder) {
                    null -> {
                        GcSectionLabel(stringResource(R.string.drive_move_areas))
                        areas.forEach { a ->
                            GcListRow(title = a.label, onClick = { onBrowse(a.rootId) }, leading = { AreaIcon(a, 32) }, trailing = { GcChevron() })
                        }
                    }
                    DriveLoad.Loading -> DriveLoading()
                    is DriveLoad.Error -> DriveErrorBox(f.code, null)
                    is DriveLoad.Ready -> {
                        val up = f.value.crumbs.lastOrNull()?.id
                        GcListRow(
                            title = if (f.value.node.parentId == null) "‹ " + stringResource(R.string.drive_move_areas) else "‹ " + (f.value.crumbs.lastOrNull()?.name?.ifEmpty { null } ?: "…"),
                            onClick = { onBrowse(up) },
                            titleColor = x.accentText,
                        )
                        if (onNewFolder != null && f.value.writable) {
                            GcListRow(
                                title = stringResource(R.string.drive_new_folder),
                                onClick = if (creating) null else ({ newError = null; naming = true }),
                                leading = { Icon(GcIcons.FolderPlus, contentDescription = null, tint = x.accentText, modifier = Modifier.size(22.dp)) },
                                titleColor = x.accentText,
                            )
                            if (creating) DriveLoading()
                            newError?.let { Text(driveMessage(it).orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        }
                        // never into a vault (Drive 1.9): moves, scans and backups stay out of it
                        f.value.items.filter { it.isDir && it.id != hide && !it.encrypted }.forEach { d ->
                            GcListRow(title = d.name, onClick = { onBrowse(d.id) }, leading = { NodeIcon(d, 32) }, trailing = { GcChevron() })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onPick, enabled = ready != null && canPick(ready)) { Text(confirm) } },
        dismissButton = {
            Row {
                extra?.invoke()
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) }
            }
        },
    )
}
