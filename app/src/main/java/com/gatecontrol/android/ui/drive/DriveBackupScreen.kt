package com.gatecontrol.android.ui.drive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.backup.DriveBackup
import com.gatecontrol.android.drive.backup.DriveBackupConfig
import com.gatecontrol.android.drive.backup.DriveBackupLogic
import com.gatecontrol.android.drive.backup.DriveBackupMark
import com.gatecontrol.android.drive.backup.DriveBackupWork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcSwitchRow
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings and state of the photo backup. */
@HiltViewModel
class DriveBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val backup: DriveBackup,
    private val api: DriveApi,
) : ViewModel() {

    data class Picker(val areas: List<DArea>, val folder: DriveLoad<DFolder>?)

    private val _pending = MutableStateFlow<Int?>(null)
    val pending: StateFlow<Int?> = _pending.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _pending.value = backup.pending() }
    }

    fun clearMessage() { _message.value = null }

    /** The backup never ran for this login: ask whether the photos there are already go up too. */
    fun firstTime(): Boolean = backup.mark() == null

    /** Switches it on, into the chosen folder or the default one (made when it is missing). */
    fun enable(all: Boolean) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val cfg = backup.config.value
                val target = cfg.folderId?.let { it to (cfg.folderLabel ?: "") } ?: defaultFolder() ?: return@launch
                val mark = backup.mark() ?: if (all) DriveBackupMark.ALL else backup.markNow()
                backup.enable(target.first, target.second, mark)
                _pending.value = backup.pending()
            } finally {
                _busy.value = false
            }
        }
    }

    fun disable() = backup.update { it.copy(enabled = false) }

    fun set(f: (DriveBackupConfig) -> DriveBackupConfig) {
        backup.update(f)
        refresh()
    }

    fun runNow() = DriveBackupWork.runNow(context)

    private suspend fun defaultFolder(): Pair<Long, String>? {
        val name = api.me().valueOrNull()?.name
        val areas = when (val r = api.areas()) {
            is DResult.Ok -> DriveLogic.sortAreas(r.value.filter { it.writable })
            else -> return fail(r)
        }
        val family = areas.firstOrNull { it.kind == "family" }
        val area = family ?: areas.firstOrNull { it.kind == "mine" } ?: areas.firstOrNull() ?: return fail(null, "no_folder")
        val path = DriveBackupLogic.defaultPath(name, family != null, context.getString(R.string.drive_backup_photos))
        var parent = area.rootId
        for (segment in path) parent = child(parent, segment) ?: return null
        return parent to (listOf(area.label) + path).joinToString(" › ")
    }

    /** The folder [name] in [parent], made when it is missing. */
    private suspend fun child(parent: Long, name: String): Long? {
        suspend fun find() = api.folder(parent, limit = 1000).valueOrNull()?.items?.firstOrNull { it.isDir && it.name.equals(name, ignoreCase = true) }?.id
        find()?.let { return it }
        return when (val r = api.createFolder(parent, name, rename = false)) {
            is DResult.Ok -> r.value.id
            is DResult.Failed -> if (r.code == "exists") find() else fail(r)
            else -> fail(r)
        }
    }

    private fun <T> fail(r: DResult<*>?, code: String? = null): T? {
        _message.value = code ?: when (r) {
            is DResult.Failed -> r.code
            DResult.NotConfigured -> "not_configured"
            else -> "offline"
        }
        return null
    }

    // ─── choosing the folder ────────────────────────────────────────

    /** "Neuer Ordner" in the folder shown by the picker. */
    suspend fun createFolderIn(parentId: Long, name: String): DResult<DNode> = api.createFolder(parentId, name.trim())

    fun openPicker() {
        viewModelScope.launch {
            val areas = api.areas().valueOrNull().orEmpty().filter { it.writable }
            _picker.value = Picker(DriveLogic.sortAreas(areas), null)
        }
    }

    fun browse(folderId: Long?) {
        val p = _picker.value ?: return
        if (folderId == null) {
            _picker.value = p.copy(folder = null)
            return
        }
        _picker.value = p.copy(folder = DriveLoad.Loading)
        viewModelScope.launch { _picker.value = _picker.value?.copy(folder = api.folder(folderId).toLoad { it }) }
    }

    fun closePicker() { _picker.value = null }

    fun pick() {
        val p = _picker.value ?: return
        val f = (p.folder as? DriveLoad.Ready)?.value?.takeIf { it.writable } ?: return
        val area = p.areas.firstOrNull { it.id == f.node.areaId }
        val names = f.crumbs.map { it.name }.filter { it.isNotEmpty() } + listOfNotNull(f.node.name.takeIf { f.node.parentId != null })
        val label = (listOfNotNull(area?.label) + names).joinToString(" › ")
        _picker.value = null
        set { it.copy(folderId = f.node.id, folderLabel = label) }
        if (backup.config.value.enabled) runNow()
    }
}

/** What the photo backup needs: the photos (and videos), their original location data, the progress notification. */
private fun mediaPermissions(videos: Boolean): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        if (videos) add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    add(Manifest.permission.ACCESS_MEDIA_LOCATION)
}.toTypedArray()

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A backup code as a sentence. */
internal fun backupText(context: Context, code: String): String = when (code) {
    "no_permission" -> context.getString(R.string.drive_backup_error_permission)
    "no_folder", "not_found" -> context.getString(R.string.drive_backup_error_folder)
    else -> driveText(context, code)
}

/** Drive tab › Foto-Sicherung. */
@Composable
fun DriveBackupScreen(onBack: () -> Unit, viewModel: DriveBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val config by viewModel.backup.config.collectAsStateWithLifecycle()
    val stats by viewModel.backup.stats.collectAsStateWithLifecycle()
    val status by viewModel.backup.status.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    var askWhich by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var canRead by remember { mutableStateOf(viewModel.backup.canRead()) }
    var partial by remember { mutableStateOf(viewModel.backup.partialAccess()) }

    LifecycleResumeEffect(Unit) {
        canRead = viewModel.backup.canRead()
        partial = viewModel.backup.partialAccess()
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val start = { if (viewModel.firstTime()) askWhich = true else viewModel.enable(all = true) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        canRead = viewModel.backup.canRead()
        partial = viewModel.backup.partialAccess()
        denied = !canRead
        if (canRead && !config.enabled) start()
        viewModel.refresh()
    }
    val turnOn = {
        if (viewModel.backup.canRead()) start() else permissions.launch(mediaPermissions(config.videos))
    }

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_backup_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_switch),
                    description = stringResource(R.string.drive_backup_switch_text),
                    checked = config.enabled,
                    enabled = !busy,
                    onCheckedChange = { on -> if (on) turnOn() else viewModel.disable() },
                )
                GcRowDivider()
                GcListRow(
                    title = stringResource(R.string.drive_backup_folder),
                    description = config.folderLabel ?: stringResource(R.string.drive_backup_folder_default),
                    onClick = viewModel::openPicker,
                    trailing = { GcChevron() },
                )
            }

            if (busy) DriveLoading()
            message?.let { code ->
                GcBanner(tone = GcTone.Error, icon = GcIcons.Alert) {
                    Text(backupText(context, code), modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) { Text(stringResource(R.string.drive_backup_dismiss)) }
                }
            }
            if (denied || (config.enabled && !canRead)) {
                GcBanner(tone = GcTone.Warn, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.drive_backup_error_permission), modifier = Modifier.weight(1f))
                    TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.drive_backup_settings)) }
                }
            } else if (partial && config.enabled) {
                GcBanner(tone = GcTone.Info, icon = GcIcons.Alert) {
                    Text(stringResource(R.string.drive_backup_partial), modifier = Modifier.weight(1f))
                    TextButton(onClick = { permissions.launch(mediaPermissions(config.videos)) }) { Text(stringResource(R.string.drive_backup_allow_all)) }
                }
            }

            if (config.enabled) {
                BackupStatusCard(config, stats, status, pending, onRun = viewModel::runNow)
            }

            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_wifi),
                    description = stringResource(R.string.drive_backup_wifi_text),
                    checked = config.wifiOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(wifiOnly = v) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_charging),
                    description = null,
                    checked = config.chargingOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(chargingOnly = v) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_videos),
                    description = stringResource(R.string.drive_backup_videos_text),
                    checked = config.videos,
                    onCheckedChange = { v ->
                        viewModel.set { it.copy(videos = v) }
                        if (v && config.enabled && !viewModel.backup.canRead(videos = true)) permissions.launch(mediaPermissions(true))
                    },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_camera),
                    description = stringResource(R.string.drive_backup_camera_text),
                    checked = config.cameraOnly,
                    onCheckedChange = { v -> viewModel.set { it.copy(cameraOnly = v) } },
                )
            }
            Text(
                stringResource(R.string.drive_backup_note),
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (askWhich) {
        AlertDialog(
            onDismissRequest = { askWhich = false },
            title = { Text(stringResource(R.string.drive_backup_which_title)) },
            text = { Text(stringResource(R.string.drive_backup_which_text)) },
            confirmButton = { TextButton(onClick = { askWhich = false; viewModel.enable(all = true) }) { Text(stringResource(R.string.drive_backup_which_all)) } },
            dismissButton = { TextButton(onClick = { askWhich = false; viewModel.enable(all = false) }) { Text(stringResource(R.string.drive_backup_which_new)) } },
        )
    }
    picker?.let { p ->
        FolderPickerDialog(
            title = stringResource(R.string.drive_backup_folder),
            areas = p.areas,
            folder = p.folder,
            confirm = stringResource(R.string.drive_backup_folder_here),
            canPick = { it.writable },
            onBrowse = viewModel::browse,
            onPick = viewModel::pick,
            onDismiss = viewModel::closePicker,
            onNewFolder = viewModel::createFolderIn,
        )
    }
}

@Composable
private fun BackupStatusCard(config: DriveBackupConfig, stats: DriveBackup.Stats, status: DriveBackup.Status, pending: Int?, onRun: () -> Unit) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    GcCard {
        Text(pluralStringResource(R.plurals.drive_backup_saved, stats.saved, stats.saved), style = MaterialTheme.typography.titleMedium)
        when {
            status.running -> {
                LinearProgressIndicator(
                    progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(stringResource(R.string.drive_backup_running, status.done + 1, status.total), color = x.muted, style = MaterialTheme.typography.bodyMedium)
            }
            stats.error != null -> Text(backupText(context, stats.error), color = x.warn, style = MaterialTheme.typography.bodyMedium)
            pending != null && pending > 0 -> Text(
                pluralStringResource(if (config.wifiOnly) R.plurals.drive_backup_waiting_wifi else R.plurals.drive_backup_waiting, pending, pending),
                color = x.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(stringResource(R.string.drive_backup_uptodate), color = x.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (stats.lastRunAt > 0) {
            Text(
                stringResource(R.string.drive_backup_last, DateUtils.getRelativeTimeSpanString(stats.lastRunAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)),
                color = x.muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!status.running) GcOutlineButton(stringResource(R.string.drive_backup_now), onClick = onRun, icon = GcIcons.Refresh)
    }
}

/** The photo backup's state for its row in the settings. */
@HiltViewModel
class DriveBackupSettingsViewModel @Inject constructor(backup: DriveBackup) : ViewModel() {
    val config: StateFlow<DriveBackupConfig> = backup.config
    val stats: StateFlow<DriveBackup.Stats> = backup.stats
}
