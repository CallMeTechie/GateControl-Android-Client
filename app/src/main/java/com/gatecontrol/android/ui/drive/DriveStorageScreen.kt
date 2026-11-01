package com.gatecontrol.android.ui.drive

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DDuplicates
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DStorageArea
import com.gatecontrol.android.drive.DStorageFile
import com.gatecontrol.android.drive.DStorageReport
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveBulk
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The storage overview (Drive 1.7, feature `storage`): what is used, where, the largest files, duplicates to clean up. */
@HiltViewModel
class DriveStorageViewModel @Inject constructor(private val api: DriveApi, val live: DriveLive) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<DStorageReport>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<DStorageReport>> = _state.asStateFlow()
    private val _all = MutableStateFlow(false)
    /** Administrators: everything, not only the areas they can read. */
    val all: StateFlow<Boolean> = _all.asStateFlow()
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    /** Copies of duplicates ticked for the trash. */
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val r = api.storage(_all.value)
            _state.value = r.toLoad { it }
            // copies gone meanwhile are no longer ticked
            val ids = (r as? DResult.Ok)?.value?.duplicates?.flatMap { g -> g.items.map { it.id } }?.toSet().orEmpty()
            _selected.update { it intersect ids }
        }
    }

    fun setAll(on: Boolean) {
        if (on == _all.value) return
        _all.value = on
        _selected.value = emptySet()
        _state.value = DriveLoad.Loading
        refresh()
    }

    fun toggle(group: DDuplicates, id: Long) = _selected.update { DriveLogic.toggleDuplicate(it, group, id) }

    fun allButOne() {
        val groups = (_state.value as? DriveLoad.Ready)?.value?.duplicates ?: return
        _selected.value = DriveLogic.allButOne(groups)
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun clearMessage() { _message.value = null }

    /** The ticked copies into the trash (one by one), then the overview again. */
    fun deleteSelected() {
        val ids = _selected.value.toList()
        if (ids.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var done = 0
            var failed: String? = null
            for (id in ids) {
                when (val r = api.delete(id)) {
                    is DResult.Ok -> done++
                    else -> failed = r.errorCode()
                }
            }
            _selected.value = emptySet()
            _message.value = failed ?: "dup_deleted:$done"
            _busy.value = false
            refresh()
        }
    }
}

/** Drive tab › Menu › Speicher. */
@Composable
fun DriveStorageScreen(onBack: () -> Unit, onFolder: (Long) -> Unit, viewModel: DriveStorageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val all by viewModel.all.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let { m ->
            val text = if (m.startsWith("dup_deleted:")) {
                val n = m.substringAfter(':').toIntOrNull() ?: 0
                context.resources.getQuantityString(R.plurals.drive_storage_deleted, n, n)
            } else {
                driveText(context, m)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val report = (state as? DriveLoad.Ready)?.value
    val selecting = selected.isNotEmpty() && report != null
    androidx.activity.compose.BackHandler(enabled = selecting) { viewModel.clearSelection() }
    Column(Modifier.fillMaxSize()) {
        SelectionSwap(
            selecting,
            bar = { GcSubpageBar(stringResource(R.string.drive_storage_title), onBack = onBack, backDescription = stringResource(R.string.drive_back)) },
            selection = {
                SelectionHead(
                    count = selected.size,
                    line = report?.let { DriveLogic.size(DriveLogic.freed(selected, it.duplicates)) },
                    allSelected = false,
                    onClose = viewModel::clearSelection,
                    onAll = null,
                )
            },
        )
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (me?.admin == true) {
                    GcSegmented(
                        options = listOf(false to stringResource(R.string.drive_storage_mine), true to stringResource(R.string.drive_storage_all)),
                        selected = all,
                        onSelect = viewModel::setAll,
                    )
                }
                when (val s = state) {
                    DriveLoad.Loading -> DriveLoading()
                    is DriveLoad.Error -> DriveErrorBox(s.code, onRetry = viewModel::refresh)
                    is DriveLoad.Ready -> StorageReport(s.value, selected, busy, onFolder, onToggle = viewModel::toggle, onAllButOne = viewModel::allButOne)
                }
            }
        }
        if (selecting) {
            SelectionActionBar(
                listOf(
                    BarAction(GcIcons.Trash, stringResource(R.string.drive_storage_delete_selected), DriveBulk.Can.YES, danger = true) { if (!busy) confirm = true },
                ),
            )
        }
    }
    if (confirm && report != null) {
        val bytes = DriveLogic.freed(selected, report.duplicates)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.drive_storage_delete_selected)) },
            text = { Text(pluralStringResource(R.plurals.drive_storage_delete_confirm, selected.size, selected.size, DriveLogic.size(bytes))) },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.deleteSelected() }) { Text(stringResource(R.string.drive_storage_delete_selected), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

@Composable
private fun Bar(part: Long, whole: Long) {
    LinearProgressIndicator(
        progress = { if (whole > 0) (part.toFloat() / whole).coerceIn(0f, 1f) else 0f },
        trackColor = GateControlTheme.extraColors.panel2,
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun StorageReport(
    r: DStorageReport,
    selected: Set<Long>,
    busy: Boolean,
    onFolder: (Long) -> Unit,
    onToggle: (DDuplicates, Long) -> Unit,
    onAllButOne: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    GcCard {
        Text(
            r.maxBytes?.let { stringResource(R.string.drive_storage_of_limit, DriveLogic.size(r.total.bytes), DriveLogic.size(it)) }
                ?: stringResource(R.string.drive_storage_used, DriveLogic.size(r.total.bytes)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        r.maxBytes?.let { Bar(r.total.bytes, it) }
        Text(pluralStringResource(R.plurals.drive_storage_files, r.total.files, r.total.files), color = x.muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOf(
                stringResource(R.string.drive_storage_versions, DriveLogic.size(r.total.versionsBytes)),
                stringResource(R.string.drive_storage_trash, DriveLogic.size(r.total.trashBytes)),
            ).joinToString(" · "),
            color = x.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (r.areas.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.drive_storage_areas))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            val max = r.areas.maxOf { it.quota ?: it.use.bytes }.coerceAtLeast(1)
            r.areas.sortedByDescending { it.use.bytes }.forEachIndexed { i, a ->
                if (i > 0) GcRowDivider()
                AreaUse(a, max)
            }
        }
    }
    if (r.people.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.drive_storage_people))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            r.people.sortedByDescending { it.bytes }.forEachIndexed { i, p ->
                if (i > 0) GcRowDivider()
                GcListRow(
                    title = p.name,
                    description = pluralStringResource(R.plurals.drive_storage_files, p.files, p.files),
                    trailing = { Text(DriveLogic.size(p.bytes), style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
    if (r.largest.isNotEmpty()) {
        GcSectionLabel(stringResource(R.string.drive_storage_largest))
        GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            r.largest.forEachIndexed { i, f ->
                if (i > 0) GcRowDivider()
                GcListRow(
                    title = f.name,
                    description = place(f),
                    onClick = f.parentId?.let { p -> { onFolder(p) } },
                    trailing = { Text(DriveLogic.size(f.size), style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
    GcSectionLabel(stringResource(R.string.drive_storage_duplicates))
    if (r.duplicates.isEmpty()) {
        Text(stringResource(R.string.drive_storage_no_duplicates), color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
    } else {
        Text(
            stringResource(R.string.drive_storage_wasted, DriveLogic.size(r.duplicates.sumOf { it.wasted })),
            color = x.muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        GcOutlineButton(stringResource(R.string.drive_storage_all_but_one), onClick = onAllButOne, icon = GcIcons.Check)
        r.duplicates.forEach { g ->
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    stringResource(R.string.drive_storage_dup_group, g.items.size, DriveLogic.size(g.size)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                g.items.forEach { f ->
                    GcRowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                        Checkbox(
                            checked = f.id in selected,
                            onCheckedChange = { onToggle(g, f.id) },
                            enabled = !busy && f.canDelete,
                        )
                        Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium)
                            Text(place(f), style = MaterialTheme.typography.bodySmall, color = x.muted)
                        }
                    }
                }
            }
        }
    }
}

/** Where a file is: its folder path, else its area. */
private fun place(f: DStorageFile): String =
    f.path?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() } ?: f.path ?: f.areaId.orEmpty()

@Composable
private fun AreaUse(a: DStorageArea, max: Long) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                a.quota?.let { stringResource(R.string.drive_storage_of_limit, DriveLogic.size(a.use.bytes), DriveLogic.size(it)) } ?: DriveLogic.size(a.use.bytes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Bar(a.use.bytes, a.quota ?: max)
        Text(
            listOf(
                pluralStringResource(R.plurals.drive_storage_files, a.use.files, a.use.files),
                stringResource(R.string.drive_storage_versions, DriveLogic.size(a.use.versionsBytes)),
                stringResource(R.string.drive_storage_trash, DriveLogic.size(a.use.trashBytes)),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = x.muted,
        )
    }
}
