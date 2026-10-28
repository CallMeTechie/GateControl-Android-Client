package com.gatecontrol.android.ui.drive

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.backup.DriveBackupFolder
import com.gatecontrol.android.drive.backup.DriveFolderBackup
import com.gatecontrol.android.drive.backup.DriveFolderBackupLogic
import com.gatecontrol.android.drive.backup.DriveFolderBackupWork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
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

/** Settings and state of the folder backup ("Ordner sichern"). */
@HiltViewModel
class DriveFolderBackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val backup: DriveFolderBackup,
    private val api: DriveApi,
    val live: DriveLive,
) : ViewModel() {

    /** Choosing the Drive folder: for a phone folder just picked ([tree]) or another one for [retarget]. */
    data class Picker(val areas: List<DArea>, val folder: DriveLoad<DFolder>?, val tree: Uri? = null, val treeName: String = "", val retarget: String? = null)

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    fun clearMessage() { _message.value = null }

    /** The system's folder picker brought [tree]: now the Drive folder it goes to. */
    fun treePicked(tree: Uri) {
        val name = runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: tree.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Ordner"
        openPicker(Picker(emptyList(), null, tree = tree, treeName = name))
    }

    fun retarget(folder: DriveBackupFolder) = openPicker(Picker(emptyList(), null, retarget = folder.id))

    private fun openPicker(p: Picker) {
        _picker.value = p
        viewModelScope.launch {
            val areas = DriveLogic.sortAreas(api.areas().valueOrNull().orEmpty().filter { it.writable })
            _picker.value = _picker.value?.copy(areas = areas)
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

    suspend fun createFolderIn(parentId: Long, name: String): DResult<DNode> = api.createFolder(parentId, name.trim())

    fun closePicker() { _picker.value = null }

    /** The Drive folder shown is the target. */
    fun pick() {
        val p = _picker.value ?: return
        val f = (p.folder as? DriveLoad.Ready)?.value?.takeIf { it.writable } ?: return
        val area = p.areas.firstOrNull { it.id == f.node.areaId }
        val names = f.crumbs.drop(1).map { it.name }.filter { it.isNotEmpty() } + listOfNotNull(f.node.name.takeIf { f.node.parentId != null })
        val label = (listOfNotNull(area?.label) + names).joinToString(" › ")
        _picker.value = null
        when {
            p.retarget != null -> backup.retarget(p.retarget, f.node.id, label)
            p.tree != null -> _message.value = backup.add(p.tree, p.treeName, f.node.id, label) ?: "folder_added"
        }
    }

    fun remove(folder: DriveBackupFolder) = backup.remove(folder.id)

    fun setPaused(folder: DriveBackupFolder, paused: Boolean) = backup.setPaused(folder.id, paused)

    fun answer(folder: DriveBackupFolder, transfer: Boolean) = backup.answerDeletes(folder.id, transfer)

    fun runNow() = DriveFolderBackupWork.runNow(context)
}

/** A folder backup code as a sentence. */
internal fun folderText(context: Context, code: String): String = when (code) {
    "no_access" -> context.getString(R.string.drive_folders_error_access)
    "overlap" -> context.getString(R.string.drive_folders_error_overlap)
    "too_many" -> context.getString(R.string.drive_folders_error_too_many)
    "folder_added" -> context.getString(R.string.drive_folders_added)
    else -> backupText(context, code)
}

/** Where the system's folder picker opens for a suggestion (the shared storage of the phone). */
private fun suggestionUri(path: String): Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$path")

/** Drive tab › Menu › Ordner sichern (also in the settings next to the photo backup). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFolderBackupScreen(onBack: () -> Unit, viewModel: DriveFolderBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val folders by viewModel.backup.folders.collectAsStateWithLifecycle()
    val settings by viewModel.backup.settings.collectAsStateWithLifecycle()
    val status by viewModel.backup.status.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val me by viewModel.live.me.collectAsStateWithLifecycle()
    var suggest by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DriveBackupFolder?>(null) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::treePicked) }
    // the progress notification of a run (Android 13+ asks)
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val choose: (Uri?) -> Unit = { initial ->
        suggest = false
        runCatching { tree.launch(initial) }
    }
    LaunchedEffect(message) {
        if (message == "folder_added" && Build.VERSION.SDK_INT >= 33) runCatching { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }
    val deleteAfter = me?.has(DMe.FEATURE_DELETE_AFTER) == true

    Column(Modifier.fillMaxSize()) {
        GcSubpageBar(stringResource(R.string.drive_folders_title), onBack = onBack, backDescription = stringResource(R.string.drive_back))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (deleteAfter) R.string.drive_folders_intro else R.string.drive_folders_intro_keep),
                style = MaterialTheme.typography.bodyMedium,
                color = GateControlTheme.extraColors.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            message?.let { code ->
                GcBanner(tone = if (code == "folder_added") GcTone.Info else GcTone.Error, icon = GcIcons.Alert) {
                    Text(folderText(context, code), modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessage) { Text(stringResource(R.string.drive_backup_dismiss)) }
                }
            }
            if (status.running) {
                GcCard {
                    Text(stringResource(R.string.drive_folders_running, status.folder.orEmpty(), (status.done + 1).coerceAtMost(status.total), status.total), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { if (status.total > 0) status.done.toFloat() / status.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    status.current?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted) }
                }
            }
            if (folders.isEmpty()) {
                Text(stringResource(R.string.drive_folders_empty), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(4.dp))
            }
            folders.forEach { f ->
                FolderCard(
                    f,
                    onPause = { viewModel.setPaused(f, !f.paused) },
                    onRetarget = { viewModel.retarget(f) },
                    onRemove = { removing = f },
                    onAnswer = { viewModel.answer(f, it) },
                )
            }
            GcPrimaryButton(stringResource(R.string.drive_folders_add), onClick = { suggest = true }, icon = GcIcons.FolderPlus)
            if (folders.any { !it.paused }) GcOutlineButton(stringResource(R.string.drive_backup_now), onClick = viewModel::runNow, icon = GcIcons.Refresh)
            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_wifi),
                    description = stringResource(R.string.drive_backup_wifi_text),
                    checked = settings.wifiOnly,
                    onCheckedChange = { v -> viewModel.backup.setSettings { it.copy(wifiOnly = v) } },
                )
                GcRowDivider()
                GcSwitchRow(
                    label = stringResource(R.string.drive_backup_charging),
                    description = null,
                    checked = settings.chargingOnly,
                    onCheckedChange = { v -> viewModel.backup.setSettings { it.copy(chargingOnly = v) } },
                )
            }
            Text(
                stringResource(R.string.drive_folders_note),
                style = MaterialTheme.typography.bodySmall,
                color = GateControlTheme.extraColors.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (suggest) {
        ModalBottomSheet(onDismissRequest = { suggest = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(stringResource(R.string.drive_folders_suggest_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                DriveFolderBackupLogic.SUGGESTIONS.forEach { s ->
                    val (title, desc) = when (s.id) {
                        "whatsapp" -> R.string.drive_folders_suggest_whatsapp to R.string.drive_folders_suggest_whatsapp_desc
                        "download" -> R.string.drive_folders_suggest_download to
                            if (Build.VERSION.SDK_INT >= 30) R.string.drive_folders_suggest_download_note else R.string.drive_folders_suggest_download_desc
                        else -> R.string.drive_folders_suggest_documents to R.string.drive_folders_suggest_documents_desc
                    }
                    GcListRow(
                        title = stringResource(title),
                        description = stringResource(desc),
                        onClick = { choose(suggestionUri(s.path)) },
                        leading = { Icon(GcIcons.Folder, contentDescription = null, tint = GateControlTheme.extraColors.accentText) },
                    )
                }
                GcListRow(
                    title = stringResource(R.string.drive_folders_suggest_other),
                    onClick = { choose(null) },
                    leading = { Icon(GcIcons.Search, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                )
            }
        }
    }
    picker?.let { p ->
        FolderPickerDialog(
            title = if (p.tree != null) stringResource(R.string.drive_folders_target_title, p.treeName) else stringResource(R.string.drive_folders_retarget),
            areas = p.areas,
            folder = p.folder,
            confirm = stringResource(R.string.drive_folders_target_here),
            canPick = { it.writable },
            onBrowse = viewModel::browse,
            onPick = viewModel::pick,
            onDismiss = viewModel::closePicker,
            onNewFolder = viewModel::createFolderIn,
        )
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            text = { Text(stringResource(R.string.drive_folders_remove_confirm, f.name)) },
            confirmButton = { TextButton(onClick = { removing = null; viewModel.remove(f) }) { Text(stringResource(R.string.drive_folders_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

@Composable
private fun FolderCard(
    f: DriveBackupFolder,
    onPause: () -> Unit,
    onRetarget: () -> Unit,
    onRemove: () -> Unit,
    onAnswer: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val x = GateControlTheme.extraColors
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GcIconSquare(size = 40.dp, background = x.accentBg) {
                Icon(GcIcons.Folder, contentDescription = null, tint = x.accentText, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.drive_folders_to, f.targetLabel), style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
        }
        f.confirm?.let { n ->
            GcBanner(tone = GcTone.Warn, icon = GcIcons.Trash) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pluralStringResource(R.plurals.drive_folders_confirm, n, n), style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.drive_folders_confirm_delete)) }
                        TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.drive_folders_confirm_keep)) }
                    }
                }
            }
        }
        val lines = buildList {
            when {
                f.paused -> add(stringResource(R.string.drive_folders_paused))
                f.lastRunAt == 0L && f.error == null -> add(stringResource(R.string.drive_folders_waiting))
            }
            if (f.files > 0 || f.lastRunAt > 0) add(pluralStringResource(R.plurals.drive_folders_files, f.files, f.files))
            if (f.lastRunAt > 0) {
                add(stringResource(R.string.drive_folders_last, DateUtils.getRelativeTimeSpanString(f.lastRunAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()))
            }
        }
        if (lines.isNotEmpty()) Text(lines.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = x.muted)
        if (f.pendingDeletes > 0) {
            Text(pluralStringResource(R.plurals.drive_folders_pending, f.pendingDeletes, f.pendingDeletes), style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
        if (f.skipped > 0) Text(pluralStringResource(R.plurals.drive_folders_skipped, f.skipped, f.skipped), style = MaterialTheme.typography.bodySmall, color = x.muted)
        f.error?.let { Text(folderText(context, it), style = MaterialTheme.typography.bodyMedium, color = x.warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPause) { Text(stringResource(if (f.paused) R.string.drive_folders_resume else R.string.drive_folders_pause)) }
            TextButton(onClick = onRetarget) { Text(stringResource(R.string.drive_folders_retarget)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.drive_folders_remove), color = MaterialTheme.colorScheme.error) }
        }
    }
}
