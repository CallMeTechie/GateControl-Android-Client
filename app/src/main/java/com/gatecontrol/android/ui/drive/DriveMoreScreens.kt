package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLogic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import kotlinx.coroutines.launch

/** The trash of every area the person writes to: restore or delete for good. */
@Composable
fun DriveTrashScreen(onBack: () -> Unit, viewModel: DriveTrashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var purging by remember { mutableStateOf<DNode?>(null) }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_trash), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val s = state) {
                DriveLoad.Loading -> DriveLoading()
                is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
                is DriveLoad.Ready -> if (s.value.isEmpty()) {
                    Text(stringResource(R.string.drive_trash_empty), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(4.dp))
                } else {
                    GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        s.value.forEachIndexed { i, n ->
                            if (i > 0) GcRowDivider()
                            val from = n.from?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.drive_trash_from, it) }
                            NodeRow(
                                node = n,
                                onClick = null,
                                description = nodeLine(n, from),
                                trailing = {
                                    Row {
                                        TextButton(onClick = { viewModel.restore(n) }) { Text(stringResource(R.string.drive_trash_restore)) }
                                        GcIconButton(GcIcons.Trash, contentDescription = stringResource(R.string.drive_trash_purge), onClick = { purging = n }, size = 40.dp, iconSize = 18.dp)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    purging?.let { n ->
        AlertDialog(
            onDismissRequest = { purging = null },
            text = { Text(stringResource(R.string.drive_trash_purge_confirm, nodeTitle(n))) },
            confirmButton = { TextButton(onClick = { purging = null; viewModel.purge(n) }) { Text(stringResource(R.string.drive_trash_purge)) } },
            dismissButton = { TextButton(onClick = { purging = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

/**
 * Search by name across the areas (photos and videos too, like every file); a folder opens, a file opens in an app. A
 * long press ticks hits (then taps tick more): send, move, copy, delete and "Mehr" for all of them.
 */
@Composable
fun DriveSearchScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.bulk.selected.collectAsStateWithLifecycle()
    val running by viewModel.ops.running.collectAsStateWithLifecycle()
    val busy by viewModel.bulk.busy.collectAsStateWithLifecycle()
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var q by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<DNode?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DNode?>(null) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { viewModel.bulk.clear() }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val hits = (state as? DriveLoad.Ready)?.value.orEmpty()
    val chosen = remember(selected, hits) { hits.filter { it.id in selected } }
    val areaOf: (DNode) -> com.gatecontrol.android.drive.DArea? = { n -> areas.firstOrNull { it.id == n.areaId } }
    val where = DriveBulk.Where(writable = { n -> areaOf(n)?.writable == true }, child = { n -> areaOf(n)?.via == "parent" })
    val open: (DNode, Boolean) -> Unit = { n, send ->
        scope.launch {
            fetchThen(context, n, viewModel::fetch, then = { f -> if (send) sendFile(context, n, f) else openFile(context, n, f) }) { code ->
                Toast.makeText(context, driveText(context, code), Toast.LENGTH_LONG).show()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionHead(
                    count = chosen.size,
                    line = summaryText(DriveBulk.summary(chosen)),
                    allSelected = DriveBulk.allSelected(selected, hits.map { it.id }),
                    onClose = viewModel.bulk::clear,
                    onAll = viewModel.bulk::allOrNone,
                )
            } else {
                GcSubpageBar(stringResource(R.string.drive_search), onBack = onBack, backDescription = stringResource(R.string.drive_back))
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!selecting) {
                    GcTextField(
                        value = q,
                        onValueChange = { q = it.take(100); viewModel.search(q) },
                        label = stringResource(R.string.drive_search_hint),
                    )
                }
                BulkBusyCard(busy, viewModel.bulk::cancelBusy)
                when (val s = state) {
                    null -> if (q.isNotBlank()) Text(stringResource(R.string.drive_search_min), color = GateControlTheme.extraColors.muted)
                    DriveLoad.Loading -> DriveLoading()
                    is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = { viewModel.search(q) })
                    is DriveLoad.Ready -> if (s.value.isEmpty()) {
                        Text(stringResource(R.string.drive_search_empty), color = GateControlTheme.extraColors.muted)
                    } else {
                        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            s.value.forEachIndexed { i, n ->
                                if (i > 0) GcRowDivider()
                                NodeRow(
                                    node = n,
                                    // a hit in a scan's recognised text shows the words around it
                                    description = n.snippet?.let { "„$it“" } ?: nodeLine(n, n.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }),
                                    onClick = {
                                        when {
                                            selecting -> viewModel.bulk.toggle(n)
                                            n.isDir -> onFolder(n.id)
                                            else -> open(n, false)
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (selecting) viewModel.bulk.toggle(n) else viewModel.bulk.select(n)
                                    },
                                    selecting = selecting,
                                    selected = n.id in selected,
                                    onMore = { menuFor = n },
                                )
                            }
                        }
                    }
                }
                // room for the selection's bar over the last hit
                if (selecting) Spacer(Modifier.height(72.dp))
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
        // jobs on several items: the ring of the round button (a tap shows them one by one)
        if (!selecting) {
            JobsFab(
                jobs = running,
                onCancel = { id -> scope.launch { viewModel.ops.cancel(id) } },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (selecting) 88.dp else if (running.isNotEmpty()) 84.dp else 16.dp))
    }

    val moreSheet: @Composable (List<DNode>, Boolean, () -> Unit) -> Unit = { ns, fromRow, close ->
        val one = ns.singleOrNull()
        val bulk = viewModel.bulk
        val can: (DriveBulk.Action) -> DriveBulk.Can = { a -> bulk.can(a, ns, where) }
        val quick = if (!fromRow || one == null) emptyList() else listOf(
            BarAction(GcIcons.Share, stringResource(R.string.drive_bar_send), can(DriveBulk.Action.SEND)) { if (one.isDir) bulk.sendZip(ns) else open(one, true) },
            BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), can(DriveBulk.Action.MOVE)) { bulk.openTarget(DriveBulk.Mode.MOVE, ns) },
            BarAction(GcIcons.Copy, stringResource(R.string.drive_bar_copy), can(DriveBulk.Action.COPY)) { bulk.openTarget(DriveBulk.Mode.COPY, ns) },
            BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), can(DriveBulk.Action.DELETE), danger = true) { bulk.delete(ns) },
        )
        val multi = listOf(
            MoreItem(GcIcons.Download, stringResource(R.string.drive_more_save), stringResource(R.string.drive_more_save_desc), can(DriveBulk.Action.SAVE)) { bulk.save(ns) },
            MoreItem(GcIcons.Archive, stringResource(R.string.drive_more_zip), stringResource(R.string.drive_more_zip_desc), can(DriveBulk.Action.ZIP)) { bulk.sendZip(ns) },
            MoreItem(
                GcIcons.Link,
                stringResource(R.string.drive_share_link),
                if (one != null) stringResource(R.string.drive_more_link_one) else pluralStringResource(R.plurals.drive_more_link, ns.size, ns.size),
                can(DriveBulk.Action.LINK),
            ) { bulk.openLink(ns) },
        )
        val single = buildList {
            if (one != null && !one.isDir) add(MoreItem(GcIcons.External, stringResource(R.string.drive_open)) { open(one, false) })
            if (one != null && one.isDir) add(MoreItem(GcIcons.Folder, stringResource(R.string.drive_open)) { onFolder(one.id) })
            add(MoreItem(GcIcons.Edit, stringResource(R.string.drive_rename), can = can(DriveBulk.Action.RENAME)) { one?.let { renaming = it } })
            if (one == null || DriveLogic.officeAllowed(me, one)) {
                val writable = one?.let { where.writable(it) } == true
                add(MoreItem(GcIcons.Globe, stringResource(if (writable || one == null) R.string.drive_office_edit else R.string.drive_office_view), can = can(DriveBulk.Action.OFFICE)) {
                    one?.let { n -> scope.launch { openOffice(context, viewModel.office(n)) } }
                })
            }
            one?.let { n -> DriveLogic.portalNodeUrl(me, n.id) }?.let { url -> add(MoreItem(GcIcons.Globe, stringResource(R.string.drive_open_portal)) { openPortal(context, url) }) }
        }
        val last = if (me?.has(DMe.FEATURE_ALBUMS) == true) {
            listOf(MoreItem(GcIcons.Photo, stringResource(R.string.drive_more_album), can = can(DriveBulk.Action.ALBUM)) { bulk.openAlbums(ns) })
        } else {
            emptyList()
        }
        BulkMoreSheet(ns.size, one?.let { nodeTitle(it) }.takeIf { fromRow }, summaryText(DriveBulk.summary(ns)), quick, multi, single, last, onDismiss = close)
    }
    menuFor?.let { n -> moreSheet(listOf(n), true) { menuFor = null } }
    if (moreOpen && chosen.isNotEmpty()) moreSheet(chosen, false) { moreOpen = false }
    renaming?.let { n ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = n.name,
            confirm = stringResource(R.string.drive_save),
            onConfirm = { renaming = null; if (it != n.name) viewModel.rename(n, it) },
            onDismiss = { renaming = null },
        )
    }
    DriveBulkHost(viewModel.bulk, snackbar)
}
