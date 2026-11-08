package com.gatecontrol.android.ui.drive

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.device.DeviceEntry
import com.gatecontrol.android.drive.device.DeviceFileOps
import com.gatecontrol.android.drive.device.DeviceFileProvider
import com.gatecontrol.android.drive.device.DeviceFiles
import com.gatecontrol.android.drive.device.DeviceFilesLogic
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Category
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Filter
import com.gatecontrol.android.drive.device.DeviceFilesLogic.InDrive
import com.gatecontrol.android.drive.device.DeviceFilesLogic.Sort
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class DriveDeviceFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    val files: DeviceFiles,
    private val ops: DeviceFileOps,
    private val transfers: DriveTransfers,
) : ViewModel() {

    /** A folder of a place ([start] first, then where the person goes), a category on all places, or the newest files. */
    sealed interface Mode {
        data class Folder(val start: String) : Mode
        data class Kind(val category: Category) : Mode
        data object Recent : Mode
    }

    sealed interface Load {
        data object Loading : Load
        data class Ready(val entries: List<DeviceEntry>) : Load
        /** Android does not let the app read the folder (or the access was taken back). */
        data object NoAccess : Load
        /** The place is gone (the SD card or the USB stick was taken out). */
        data object Gone : Load
    }

    sealed interface Msg {
        data class Queued(val files: Int, val folders: Boolean) : Msg
        data object FoldersOnly : Msg
        data class Deleted(val count: Int, val failed: Boolean) : Msg
        data class Moved(val ok: Int, val failed: Int, val copy: Boolean) : Msg
        data object RenameFailed : Msg
        data class Unzipped(val result: DeviceFileOps.Unzip) : Msg
    }

    val mode: Mode = savedState.get<String>(ARG_PATH)?.takeIf { it.isNotBlank() }?.let { Mode.Folder(it) }
        ?: savedState.get<String>(ARG_CAT)?.let { Category.of(it) }?.let { Mode.Kind(it) }
        ?: Mode.Recent

    private val history = ArrayList<String>()
    private val _path = MutableStateFlow((mode as? Mode.Folder)?.start)
    /** The folder shown (folder mode only). */
    val path: StateFlow<String?> = _path.asStateFlow()
    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load.asStateFlow()
    private val _sort = MutableStateFlow(defaultSort())
    val sort: StateFlow<Sort> = _sort.asStateFlow()
    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<Msg?>(null)
    val message: StateFlow<Msg?> = _message.asStateFlow()
    private val _trash = MutableStateFlow<IntentSender?>(null)
    /** Android's dialog "In den Papierkorb verschieben?" to show. */
    val trash: StateFlow<IntentSender?> = _trash.asStateFlow()
    private val _volumes = MutableStateFlow<List<DeviceFiles.Volume>>(emptyList())
    val volumes: StateFlow<List<DeviceFiles.Volume>> = _volumes.asStateFlow()

    init {
        viewModelScope.launch { files.volumeChanges.collect { loadVolumes(); reload() } }
    }

    /** Newest first for the Download folder, the categories and the newest files; by name in other folders. */
    private fun defaultSort(): Sort = when (val m = mode) {
        is Mode.Folder -> if (m.start.trimEnd('/').endsWith("/Download")) Sort.NEWEST else Sort.NAME
        else -> Sort.NEWEST
    }

    fun onResume() {
        loadVolumes()
        reload()
    }

    private fun loadVolumes() {
        viewModelScope.launch { _volumes.value = withContext(Dispatchers.IO) { files.volumes() } }
    }

    fun reload() {
        viewModelScope.launch {
            if (!files.hasAccess()) {
                _load.value = Load.NoAccess
                return@launch
            }
            if (_load.value !is Load.Ready) _load.value = Load.Loading
            _load.value = when (mode) {
                is Mode.Folder -> {
                    val p = _path.value ?: mode.start
                    files.list(p)?.let { Load.Ready(it) } ?: if (withContext(Dispatchers.IO) { File(p).exists() }) Load.NoAccess else Load.Gone
                }
                is Mode.Kind -> Load.Ready(files.category(mode.category))
                Mode.Recent -> Load.Ready(files.recent())
            }
            // ticked ones that are gone are no longer ticked
            val there = (_load.value as? Load.Ready)?.entries?.map { it.path }?.toSet().orEmpty()
            _selected.update { it intersect there }
        }
    }

    /** Into folder [p] (back comes here again). */
    fun open(p: String) {
        val now = _path.value ?: return
        if (p == now) return
        history += now
        go(p)
    }

    /** Back one folder; false when this is the first one. */
    fun up(): Boolean {
        val p = history.removeLastOrNull() ?: return false
        go(p)
        return true
    }

    val canGoUp: Boolean get() = history.isNotEmpty()

    private fun go(p: String) {
        _path.value = p
        _filter.value = Filter.ALL
        _selected.value = emptySet()
        _load.value = Load.Loading
        reload()
    }

    fun setSort(s: Sort) { _sort.value = s }

    fun setFilter(f: Filter) { _filter.value = f }

    fun toggle(e: DeviceEntry) = _selected.update { if (e.path in it) it - e.path else it + e.path }

    fun selectAll(shown: List<DeviceEntry>) = _selected.update { s -> if (shown.all { it.path in s }) emptySet() else shown.map { it.path }.toSet() }

    fun clearSelection() { _selected.value = emptySet() }

    fun clearMessage() { _message.value = null }

    fun entries(): List<DeviceEntry> = (_load.value as? Load.Ready)?.entries.orEmpty()

    fun chosen(): List<DeviceEntry> = _selected.value.let { s -> entries().filter { it.path in s } }

    suspend fun inDrive(e: DeviceEntry): InDrive = files.inDrive(e)

    /** "In Drive": the ticked files go up into Drive folder [folderId] (the upload queue of the Drive tab). */
    fun toDrive(folderId: Long) {
        val sel = chosen()
        val uris = sel.filter { !it.isDir }.mapNotNull { DeviceFileProvider.uriOf(context, File(it.path)) }
        if (uris.isEmpty()) {
            _message.value = Msg.FoldersOnly
            return
        }
        transfers.upload(uris, folderId)
        _message.value = Msg.Queued(uris.size, sel.any { it.isDir })
        clearSelection()
    }

    /** "In Drive" with only folders ticked: they go through "Ordner sichern". */
    fun foldersOnly() { _message.value = Msg.FoldersOnly }

    suspend fun planDelete(): DeviceFileOps.DeletePlan = ops.planDelete(chosen())

    /** Deletes what has no trash (the person said yes), then asks Android to move the rest to its trash. */
    fun delete(plan: DeviceFileOps.DeletePlan) = work {
        val done = if (plan.direct.isEmpty()) 0 else ops.deleteDirect(plan.direct)
        val sender = ops.trashRequest(plan.trash)
        if (plan.direct.isNotEmpty()) _message.value = Msg.Deleted(done, done < plan.direct.size || (plan.trash.isNotEmpty() && sender == null))
        else if (sender == null) _message.value = Msg.Deleted(0, true)
        _trash.value = sender
        clearSelection()
    }

    /** Android's trash dialog was shown ([ok]: the person said yes). */
    fun trashDone(ok: Boolean, count: Int) {
        _trash.value = null
        if (ok) _message.value = Msg.Deleted(count, false)
        changed()
    }

    fun trashShown() { _trash.value = null }

    fun transfer(target: String, copy: Boolean) = work {
        val d = ops.transfer(chosen(), target, copy)
        _message.value = Msg.Moved(d.ok, d.failed, copy)
        clearSelection()
    }

    fun rename(e: DeviceEntry, name: String) = work {
        if (ops.rename(e, name) == null) _message.value = Msg.RenameFailed
        clearSelection()
    }

    fun unzip(e: DeviceEntry) = work {
        _message.value = Msg.Unzipped(ops.unzip(e))
        clearSelection()
    }

    /** The folders in [p] (the target picker of "Verschieben" / "Kopieren"). */
    suspend fun folders(p: String): List<DeviceEntry>? = files.list(p)?.filter { it.isDir }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
                changed()
            }
        }
    }

    /** Something changed on the phone: this list again, the start page's counts in the background. */
    private fun changed() {
        reload()
        viewModelScope.launch { files.refresh(force = true) }
    }

    companion object {
        const val ARG_PATH = "path"
        const val ARG_CAT = "cat"
    }
}

/**
 * Drive › "Auf dem Gerät" › a folder, a category or "Zuletzt hinzugefügt": the path, sorting, the kinds as chips, a long
 * press ticks. Below a selection: "In Drive" (into a Drive folder, the upload queue of the Drive tab), "Teilen",
 * "Verschieben", "Löschen" and "Mehr" (Kopieren, Umbenennen, ZIP entpacken, Details). A tap opens a folder, shows a
 * file with Drive's viewers (picture, video, PDF, text) or hands it to another app; a ZIP asks to be unpacked.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DriveDeviceFilesScreen(onBack: () -> Unit, viewModel: DriveDeviceFilesViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val volumes by viewModel.volumes.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    var trashCount by remember { mutableIntStateOf(0) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        viewModel.trashDone(r.resultCode == Activity.RESULT_OK, trashCount)
    }
    LaunchedEffect(trash) {
        trash?.let {
            viewModel.trashShown()
            runCatching { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) }
        }
    }
    LaunchedEffect(message) {
        message?.let {
            messageText(context, it).takeIf { t -> t.isNotBlank() }?.let { t -> Toast.makeText(context, t, Toast.LENGTH_LONG).show() }
            viewModel.clearMessage()
        }
    }

    val entries = (load as? DriveDeviceFilesViewModel.Load.Ready)?.entries.orEmpty()
    val shown = remember(entries, sort, filter) { DeviceFilesLogic.view(entries, sort, filter) }
    val chips = remember(entries) { DeviceFilesLogic.filters(entries) }
    val chosen = remember(entries, selected) { entries.filter { it.path in selected } }
    val selecting = selected.isNotEmpty()
    val mode = viewModel.mode
    val volume = path?.let { p -> volumes.filter { p == it.path || DeviceFilesLogic.inside(it.path, p) }.maxByOrNull { it.path.length } }

    var viewing by remember { mutableStateOf<DeviceEntry?>(null) }
    var toDrive by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<DeviceFileOps.DeletePlan?>(null) }
    var target by remember { mutableStateOf<Boolean?>(null) }
    var renaming by remember { mutableStateOf<DeviceEntry?>(null) }
    var unzipping by remember { mutableStateOf<DeviceEntry?>(null) }
    var details by remember { mutableStateOf<DeviceEntry?>(null) }

    BackHandler(enabled = selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !selecting && viewModel.canGoUp && path != null) { viewModel.up() }

    val title = when (mode) {
        is DriveDeviceFilesViewModel.Mode.Kind -> stringResource(categoryLabel(mode.category))
        DriveDeviceFilesViewModel.Mode.Recent -> stringResource(R.string.drive_dev_recent)
        is DriveDeviceFilesViewModel.Mode.Folder -> when {
            volume != null && path?.trimEnd('/') == volume.path.trimEnd('/') -> volumeName(volume)
            else -> path?.substringAfterLast('/').orEmpty()
        }
    }
    val delete: () -> Unit = {
        scope.launch {
            val plan = viewModel.planDelete()
            trashCount = plan.trash.size
            // only what has a trash: Android asks itself; anything else is asked here first
            if (plan.direct.isEmpty()) viewModel.delete(plan) else confirmDelete = plan
        }
    }

    Column(Modifier.fillMaxSize()) {
        SelectionSwap(
            selecting,
            bar = { GcSubpageBar(title, onBack = { if (!viewModel.up()) onBack() }, backDescription = stringResource(R.string.drive_back)) },
            selection = {
                SelectionHead(
                    count = selected.size,
                    line = DriveLogic.size(chosen.sumOf { it.size }),
                    allSelected = shown.isNotEmpty() && shown.all { it.path in selected },
                    onClose = viewModel::clearSelection,
                    onAll = { viewModel.selectAll(shown) },
                )
            },
        )
        if (mode is DriveDeviceFilesViewModel.Mode.Folder && path != null && volume != null) {
            Crumbs(volumeName(volume), volume.path, path!!, onOpen = viewModel::open)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SortChip(sort, viewModel::setSort)
            if (chips.size > 2) chips.forEach { f -> GcFilterChip(stringResource(filterLabel(f)), selected = f == filter, onClick = { viewModel.setFilter(f) }) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (load) {
                DriveDeviceFilesViewModel.Load.Loading -> DriveLoading()
                DriveDeviceFilesViewModel.Load.NoAccess -> Note(stringResource(R.string.drive_dev_no_access_folder))
                DriveDeviceFilesViewModel.Load.Gone -> Note(stringResource(R.string.drive_dev_gone))
                is DriveDeviceFilesViewModel.Load.Ready -> if (shown.isEmpty()) {
                    Note(stringResource(R.string.drive_dev_empty))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shown, key = { it.path }) { e ->
                            val where = if (mode is DriveDeviceFilesViewModel.Mode.Folder) null else File(e.path).parentFile?.name
                            DeviceRow(
                                e = e,
                                selecting = selecting,
                                selected = e.path in selected,
                                where = where,
                                ask = viewModel::inDrive,
                                onClick = {
                                    when {
                                        selecting -> viewModel.toggle(e)
                                        e.isDir -> viewModel.open(e.path)
                                        DeviceFilesLogic.typeOf(e) == DeviceFilesLogic.Type.ARCHIVE && DeviceFilesLogic.ext(e.name) == "zip" -> unzipping = e
                                        else -> viewing = e
                                    }
                                },
                                onLongClick = { viewModel.toggle(e) },
                            )
                        }
                    }
                }
            }
        }
        if (selecting) {
            val folders = chosen.any { it.isDir }
            SelectionActionBar(
                listOf(
                    BarAction(GcIcons.CloudUp, stringResource(R.string.drive_dev_to_drive), DriveBulk.Can.YES) {
                        if (chosen.all { it.isDir }) viewModel.foldersOnly() else toDrive = true
                    },
                    BarAction(GcIcons.Share, stringResource(R.string.drive_dev_share), DriveBulk.Can.YES) {
                        if (folders) Toast.makeText(context, R.string.drive_dev_share_folders, Toast.LENGTH_LONG).show()
                        shareDeviceFiles(context, chosen)
                    },
                    BarAction(GcIcons.FolderMove, stringResource(R.string.drive_move), DriveBulk.Can.YES) { if (!busy) target = false },
                    BarAction(GcIcons.Trash, stringResource(R.string.drive_dev_delete), DriveBulk.Can.YES, danger = true) { if (!busy) delete() },
                    BarAction(GcIcons.MoreHorizontal, stringResource(R.string.drive_dev_more), DriveBulk.Can.YES) { more = true },
                ),
            )
        }
    }

    viewing?.let { e -> DeviceViewer(e, onClose = { viewing = null }) }
    if (toDrive) {
        DriveFolderChooser(onPicked = { id -> toDrive = false; viewModel.toDrive(id) }, onDismiss = { toDrive = false })
    }
    if (more) {
        val one = chosen.singleOrNull()
        val single = if (chosen.size == 1) DriveBulk.Can.YES else DriveBulk.Can.no(DriveBulk.Why.ONLY_ONE)
        BulkMoreSheet(
            count = chosen.size,
            title = one?.name,
            summary = DriveLogic.size(chosen.sumOf { it.size }),
            quick = emptyList(),
            multi = listOf(MoreItem(GcIcons.Copy, stringResource(R.string.drive_dev_copy)) { if (!busy) target = true }),
            single = listOfNotNull(
                MoreItem(GcIcons.Edit, stringResource(R.string.drive_rename), can = single) { renaming = one },
                one?.takeIf { DeviceFilesLogic.ext(it.name) == "zip" && !it.isDir }?.let { z ->
                    MoreItem(GcIcons.Archive, stringResource(R.string.drive_dev_unzip), can = single) { unzipping = z }
                },
                MoreItem(GcIcons.Info, stringResource(R.string.drive_details), can = single) { details = one },
            ),
            last = emptyList(),
            onDismiss = { more = false },
        )
    }
    confirmDelete?.let { plan ->
        val n = plan.direct.size + plan.trash.size
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(pluralStringResource(R.plurals.drive_dev_delete_title, n, n)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.drive_dev_delete_direct, plan.direct.size, plan.direct.size))
                    if (plan.trash.isNotEmpty()) Text(pluralStringResource(R.plurals.drive_dev_delete_trash, plan.trash.size, plan.trash.size))
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; viewModel.delete(plan) }) {
                    Text(stringResource(R.string.drive_dev_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    target?.let { copy ->
        DeviceFolderPicker(
            title = stringResource(if (copy) R.string.drive_dev_target_copy else R.string.drive_dev_target_move),
            start = path ?: volumes.firstOrNull()?.path,
            volumes = volumes.filter { !it.readOnly },
            sources = chosen.filter { it.isDir }.map { it.path },
            folders = viewModel::folders,
            onPick = { t -> target = null; viewModel.transfer(t, copy) },
            onDismiss = { target = null },
        )
    }
    renaming?.let { e ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = e.name,
            confirm = stringResource(R.string.drive_rename),
            onConfirm = { name -> renaming = null; if (name != e.name) viewModel.rename(e, name) },
            onDismiss = { renaming = null },
        )
    }
    unzipping?.let { e ->
        AlertDialog(
            onDismissRequest = { unzipping = null },
            title = { Text(stringResource(R.string.drive_dev_unzip)) },
            text = { Text(stringResource(R.string.drive_dev_unzip_ask, e.name, DeviceFilesLogic.zipFolder(e.name))) },
            confirmButton = { TextButton(onClick = { unzipping = null; viewModel.unzip(e) }) { Text(stringResource(R.string.drive_dev_unzip)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { unzipping = null; openDeviceFile(context, e) }) { Text(stringResource(R.string.drive_dev_open_other)) }
                    TextButton(onClick = { unzipping = null }) { Text(stringResource(R.string.drive_cancel)) }
                }
            },
        )
    }
    details?.let { e -> DetailsDialog(e, viewModel::inDrive) { details = null } }
}

private fun messageText(context: Context, m: DriveDeviceFilesViewModel.Msg): String {
    val r = context.resources
    return when (m) {
        is DriveDeviceFilesViewModel.Msg.Queued -> buildString {
            append(r.getQuantityString(R.plurals.drive_dev_queued, m.files, m.files))
            if (m.folders) append(". ").append(context.getString(R.string.drive_dev_folders_skipped))
        }
        DriveDeviceFilesViewModel.Msg.FoldersOnly -> context.getString(R.string.drive_dev_folders_skipped)
        is DriveDeviceFilesViewModel.Msg.Deleted -> listOfNotNull(
            m.count.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.drive_dev_deleted, it, it) },
            context.getString(R.string.drive_dev_delete_failed).takeIf { m.failed },
        ).joinToString(". ")
        is DriveDeviceFilesViewModel.Msg.Moved -> listOfNotNull(
            m.ok.takeIf { it > 0 }?.let { r.getQuantityString(if (m.copy) R.plurals.drive_dev_copied else R.plurals.drive_dev_moved, it, it) },
            m.failed.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.drive_dev_transfer_failed, it, it) },
        ).joinToString(". ")
        DriveDeviceFilesViewModel.Msg.RenameFailed -> context.getString(R.string.drive_dev_rename_failed)
        is DriveDeviceFilesViewModel.Msg.Unzipped -> when (val u = m.result) {
            is DeviceFileOps.Unzip.Ok -> listOfNotNull(
                r.getQuantityString(R.plurals.drive_dev_unzip_done, u.files, u.files),
                u.skipped.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.drive_dev_unzip_skipped, it, it) },
            ).joinToString(" ")
            DeviceFileOps.Unzip.TooMany -> context.getString(R.string.drive_dev_unzip_too_many, DeviceFilesLogic.ZIP_MAX_ENTRIES)
            DeviceFileOps.Unzip.TooLarge -> context.getString(R.string.drive_dev_unzip_too_large)
            DeviceFileOps.Unzip.Failed -> context.getString(R.string.drive_dev_unzip_failed)
        }
    }
}

private fun filterLabel(f: Filter): Int = when (f) {
    Filter.ALL -> R.string.drive_dev_filter_all
    Filter.PDF -> R.string.drive_dev_filter_pdf
    Filter.IMAGES -> R.string.drive_dev_filter_images
    Filter.VIDEOS -> R.string.drive_dev_filter_videos
    Filter.AUDIO -> R.string.drive_dev_filter_audio
    Filter.DOCUMENTS -> R.string.drive_dev_filter_documents
    Filter.ARCHIVES -> R.string.drive_dev_filter_archives
}

private fun sortLabel(s: Sort): Int = when (s) {
    Sort.NEWEST -> R.string.drive_dev_sort_newest
    Sort.NAME -> R.string.drive_dev_sort_name
    Sort.SIZE -> R.string.drive_dev_sort_size
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(24.dp))
}

/** "Interner Speicher › Download › Rechnungen": each part leads there. */
@Composable
private fun Crumbs(rootName: String, root: String, path: String, onOpen: (String) -> Unit) {
    val x = GateControlTheme.extraColors
    val parts = listOf(rootName to root) + DeviceFilesLogic.crumbs(root, path)
    val label = stringResource(R.string.drive_dev_path)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, (name, p) ->
            val last = i == parts.size - 1
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (last) FontWeight.Bold else FontWeight.SemiBold,
                color = if (last) MaterialTheme.colorScheme.onBackground else x.muted,
                modifier = Modifier.heightIn(min = 36.dp).then(if (last) Modifier else Modifier.clickable(role = Role.Button) { onOpen(p) }).padding(vertical = 8.dp),
            )
            if (!last) Text("  ›  ", style = MaterialTheme.typography.bodyMedium, color = x.faint)
        }
    }
}

/** "Neueste zuerst ⌄": the three orders. */
@Composable
private fun SortChip(sort: Sort, onSort: (Sort) -> Unit) {
    val x = GateControlTheme.extraColors
    var open by remember { mutableStateOf(false) }
    val label = stringResource(sortLabel(sort))
    val desc = stringResource(R.string.drive_dev_sort_desc, label)
    Box {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, x.border2, RoundedCornerShape(10.dp))
                .clickable(role = Role.DropdownList) { open = true }.semantics { contentDescription = desc }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Icon(GcIcons.ChevronDown, contentDescription = null, tint = x.muted, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Sort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(stringResource(sortLabel(s)), fontWeight = if (s == sort) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSort(s) },
                    leadingIcon = { if (s == sort) Icon(GcIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/** A file or folder: ✓ while ticking, the picture or symbol, the name, "240 KB · vor 12 Min. · Nur hier". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    e: DeviceEntry,
    selecting: Boolean,
    selected: Boolean,
    where: String?,
    ask: suspend (DeviceEntry) -> InDrive,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    val state = rememberInDrive(e, ask)
    val stateText = stringResource(if (selected) R.string.drive_sel_selected else R.string.drive_sel_not_selected)
    val clickLabel = if (selecting) stringResource(if (selected) R.string.drive_sel_deselect else R.string.drive_sel_select) else null
    val longLabel = stringResource(R.string.drive_sel_select)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (selected) Modifier.background(x.accentBg) else Modifier)
            .combinedClickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longLabel)
            .semantics { if (selecting) { this.selected = selected; stateDescription = stateText } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selecting) SelectCheck(selected)
        DeviceFileIcon(e)
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(deviceLine(e, listOfNotNull(where, inDriveText(state)).joinToString(" · ").ifEmpty { null }), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
    }
}

/**
 * The target of "Verschieben" / "Kopieren": the places, then their folders; "Hierher" takes the folder shown. A ticked
 * folder ([sources]) and what is inside it cannot be the target.
 */
@Composable
private fun DeviceFolderPicker(
    title: String,
    start: String?,
    volumes: List<DeviceFiles.Volume>,
    sources: List<String>,
    folders: suspend (String) -> List<DeviceEntry>?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    var at by remember { mutableStateOf(start?.takeIf { s -> volumes.any { s == it.path || DeviceFilesLogic.inside(it.path, s) } }) }
    var list by remember { mutableStateOf<List<DeviceEntry>?>(null) }
    LaunchedEffect(at) {
        list = null
        list = at?.let { folders(it) }.orEmpty()
    }
    val volume = at?.let { p -> volumes.filter { p == it.path || DeviceFilesLogic.inside(it.path, p) }.maxByOrNull { it.path.length } }
    val blocked = at != null && sources.any { DeviceFilesLogic.intoItself(it, at!!) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val here = when {
                    at == null -> stringResource(R.string.drive_dev_places)
                    volume != null -> (listOf(volumeName(volume)) + DeviceFilesLogic.crumbs(volume.path, at!!).map { it.first }).joinToString(" › ")
                    else -> at!!
                }
                Text(here, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (blocked) Text(stringResource(R.string.drive_dev_target_into_itself), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (at != null) {
                        item(key = "up") {
                            val up = if (volume != null && at!!.trimEnd('/') == volume.path.trimEnd('/')) null else File(at!!).parent
                            PickerRow(GcIcons.ChevronLeft, if (up == null) stringResource(R.string.drive_dev_places) else up.substringAfterLast('/')) { at = up }
                        }
                    }
                    if (at == null) {
                        items(volumes, key = { it.path }) { v -> PickerRow(volumeIcon(v.kind), volumeName(v)) { at = v.path } }
                    } else {
                        val l = list
                        if (l == null) item(key = "loading") { DriveLoading() }
                        else items(l, key = { it.path }) { f ->
                            val own = sources.any { DeviceFilesLogic.intoItself(it, f.path) }
                            PickerRow(GcIcons.Folder, f.name, enabled = !own) { at = f.path }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { at?.let(onPick) }, enabled = at != null && !blocked) { Text(stringResource(R.string.drive_dev_target_here)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcIconSquare(size = 36.dp, background = x.panel2) {
                Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else x.faint, modifier = Modifier.size(18.dp))
            }
            Text(name, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else x.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GcRowDivider()
    }
}

/** Name, place, size, time, kind and whether it is in Drive. */
@Composable
private fun DetailsDialog(e: DeviceEntry, ask: suspend (DeviceEntry) -> InDrive, onDismiss: () -> Unit) {
    val state = rememberInDrive(e, ask)
    val rows = listOfNotNull(
        stringResource(R.string.drive_dev_details_where) to (File(e.path).parent ?: e.path),
        stringResource(R.string.drive_dev_details_size) to (if (e.isDir) e.children?.let { pluralStringResource(R.plurals.drive_dev_items, it, it) }.orEmpty() else DriveLogic.size(e.size)),
        e.modified.takeIf { it > 0 }?.let { stringResource(R.string.drive_dev_details_modified) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
        (if (e.isDir) null else stringResource(R.string.drive_dev_details_type) to deviceMime(e)),
        (if (e.isDir) null else stringResource(R.string.drive_dev_details_drive) to (inDriveText(state) ?: stringResource(R.string.drive_dev_details_drive_unknown))),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = GateControlTheme.extraColors.muted)
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = Color.Unspecified)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}
