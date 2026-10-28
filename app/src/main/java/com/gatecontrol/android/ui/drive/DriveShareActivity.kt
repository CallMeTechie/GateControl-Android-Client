package com.gatecontrol.android.ui.drive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.chat.ChatShareRules
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.data.SetupRepository
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.familie.PluginDirectory
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcChevron
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Share target "In Drive speichern": files (or a text) from any app go into a Drive folder the person picks (the
 * last one again next time). Exported for the share sheet, switched on only while the server offers Drive
 * ([setEnabled]). The shared files are copied into the cache here, while this activity holds the read permission,
 * then go up in the app's upload queue (the round progress button in the Drive tab).
 */
@AndroidEntryPoint
class DriveShareActivity : ComponentActivity() {

    @Inject lateinit var setupRepository: SetupRepository
    @Inject lateinit var pluginDirectory: PluginDirectory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val share: DriveShareViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!setupRepository.isConfigured() || !pluginDirectory.state.value.drive) {
            Toast.makeText(this, R.string.drive_share_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val files = uris.filter { ChatShareRules.acceptable(it, packageName) }.take(MAX_FILES).map { uri ->
                val (name, size) = describe(uri)
                DriveShareViewModel.Item(uri, DriveLogic.safeName(name) ?: getString(R.string.drive_share_file), size)
            }
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            val items = files.ifEmpty {
                text?.let {
                    val name = getString(R.string.drive_share_note_name, SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.GERMANY).format(Date())) + ".txt"
                    listOf(DriveShareViewModel.Item(null, DriveLogic.safeName(name) ?: "note.txt", it.length.toLong(), it.take(1_000_000)))
                }.orEmpty()
            }
            if (items.isEmpty()) {
                Toast.makeText(this, R.string.drive_share_nothing, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            share.start(items)
        }
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                val ui by share.ui.collectAsStateWithLifecycle()
                val picker by share.picker.collectAsStateWithLifecycle()
                LaunchedEffect(ui.queued) {
                    val n = ui.queued ?: return@LaunchedEffect
                    Toast.makeText(this@DriveShareActivity, resources.getQuantityString(R.plurals.drive_share_queued, n, n), Toast.LENGTH_SHORT).show()
                    finish()
                }
                ModalBottomSheet(onDismissRequest = { finish() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    ShareSheet(ui, onPickFolder = share::pickFolder, onSave = share::save, onCancel = { finish() })
                }
                picker?.let { p ->
                    FolderPickerDialog(
                        title = stringResource(R.string.drive_scan_folder_title),
                        areas = p.areas,
                        folder = p.folder,
                        confirm = stringResource(R.string.drive_scan_folder_here),
                        canPick = { it.writable },
                        onBrowse = share::browse,
                        onPick = share::pickHere,
                        onDismiss = share::closePicker,
                        onNewFolder = share::createFolderIn,
                    )
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        } ?: (null to null)
    }.getOrDefault(null to null)

    companion object {
        private const val MAX_FILES = 100

        /** On while the server offers Drive: only then "In Drive speichern" shows up in the share sheet. */
        fun setEnabled(context: Context, enabled: Boolean) {
            runCatching {
                val component = ComponentName(context, DriveShareActivity::class.java)
                val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (context.packageManager.getComponentEnabledSetting(component) != want) {
                    context.packageManager.setComponentEnabledSetting(component, want, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Timber.w("Drive: share target not switched (%s)", it.javaClass.simpleName) }
        }
    }
}

@Composable
private fun ShareSheet(ui: DriveShareViewModel.Ui, onPickFolder: () -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.drive_share_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        GcCard(verticalArrangement = Arrangement.spacedBy(0.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            ui.items.take(SHOWN).forEachIndexed { i, item ->
                if (i > 0) GcRowDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (item.uri == null) GcIcons.Note else GcIcons.File, contentDescription = null, tint = x.muted)
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    item.size?.takeIf { it > 0 && item.uri != null }?.let { Text(DriveLogic.size(it), style = MaterialTheme.typography.bodySmall, color = x.muted) }
                }
            }
            if (ui.items.size > SHOWN) {
                GcRowDivider()
                Text(
                    pluralStringResource(R.plurals.drive_share_more, ui.items.size - SHOWN, ui.items.size - SHOWN),
                    style = MaterialTheme.typography.bodySmall,
                    color = x.muted,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
        GcCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = ui.folderId != null || ui.error != null, onClick = onPickFolder).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.drive_scan_folder), color = x.muted, style = MaterialTheme.typography.bodyMedium)
                Text(
                    ui.folderLabel ?: if (ui.error == null) "…" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                GcChevron()
            }
        }
        ui.error?.let { code ->
            val context = LocalContext.current
            Text(driveText(context, code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GcOutlineButton(stringResource(R.string.drive_cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            GcPrimaryButton(
                stringResource(R.string.drive_share_save),
                onClick = onSave,
                enabled = ui.folderId != null && !ui.saving,
                loading = ui.saving,
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

private const val SHOWN = 5

/** The state behind "In Drive speichern": the shared items, the chosen folder and its folder picker. */
@HiltViewModel
class DriveShareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
) : ViewModel() {

    /** A shared file ([uri]) or a shared text (no uri: saved as a .txt). */
    data class Item(val uri: Uri?, val name: String, val size: Long?, val text: String? = null)

    data class Ui(
        val items: List<Item> = emptyList(),
        val folderId: Long? = null,
        val folderLabel: String? = null,
        val error: String? = null,
        val saving: Boolean = false,
        /** How many went into the upload queue (then the sheet closes). */
        val queued: Int? = null,
    )

    data class Picker(val areas: List<DArea>, val folder: DriveLoad<DFolder>?)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()
    private val prefs = context.getSharedPreferences("drive_ui", Context.MODE_PRIVATE)

    /** Starts with [items]: the last folder used here (when it is still there and writable), else my files. */
    fun start(items: List<Item>) {
        _ui.value = Ui(items = items)
        viewModelScope.launch {
            val areas = when (val r = api.areas()) {
                is DResult.Ok -> r.value
                is DResult.Failed -> return@launch _ui.update { it.copy(error = r.code) }
                DResult.Offline -> return@launch _ui.update { it.copy(error = "offline") }
                DResult.NotConfigured -> return@launch _ui.update { it.copy(error = "not_configured") }
            }
            val last = prefs.getLong(KEY_FOLDER, 0L).takeIf { it > 0 }?.let { api.folder(it, limit = 1).valueOrNull() }?.takeIf { it.writable }
            val target = last ?: areas.firstOrNull { it.kind == "mine" && it.writable }?.let { api.folder(it.rootId, limit = 1).valueOrNull() }
            if (target == null) {
                _ui.update { it.copy(error = if (areas.none { a -> a.writable }) "forbidden" else null) }
                if (areas.any { it.writable }) pickFolder()
                return@launch
            }
            choose(target, areas)
        }
    }

    fun pickFolder() {
        viewModelScope.launch {
            val areas = api.areas().valueOrNull().orEmpty().filter { it.writable }
            _picker.value = Picker(DriveLogic.sortAreas(areas), null)
            _ui.value.folderId?.let { browse(it) }
        }
    }

    fun browse(folderId: Long?) {
        val p = _picker.value ?: return
        if (folderId == null) {
            _picker.value = p.copy(folder = null)
            return
        }
        _picker.value = p.copy(folder = DriveLoad.Loading)
        viewModelScope.launch { _picker.value = _picker.value?.copy(folder = api.folder(folderId, limit = 500).toLoad { it }) }
    }

    fun pickHere() {
        val p = _picker.value ?: return
        val f = (p.folder as? DriveLoad.Ready)?.value ?: return
        _picker.value = null
        choose(f, p.areas)
    }

    fun closePicker() { _picker.value = null }

    /** "Neuer Ordner" in the folder shown by the picker. */
    suspend fun createFolderIn(parentId: Long, name: String): DResult<DNode> = api.createFolder(parentId, name.trim())

    private fun choose(f: DFolder, areas: List<DArea>) {
        val area = areas.firstOrNull { it.id == f.node.areaId }?.label ?: "…"
        val label = (listOf(area) + f.crumbs.drop(1).map { it.name } + listOfNotNull(f.node.name.takeIf { f.node.parentId != null && it.isNotEmpty() }))
            .joinToString(" › ")
        _ui.update { it.copy(folderId = f.node.id, folderLabel = label, error = null) }
    }

    /** Copies everything into the cache (the read permission is this activity's) and queues the uploads. */
    fun save() {
        val u = _ui.value
        val folderId = u.folderId ?: return
        if (u.saving) return
        _ui.update { it.copy(saving = true) }
        prefs.edit().putLong(KEY_FOLDER, folderId).apply()
        viewModelScope.launch {
            var queued = 0
            for (item in u.items) {
                val file = withContext(Dispatchers.IO) { copy(item) } ?: continue
                val mime = if (item.uri == null) "text/plain" else runCatching { context.contentResolver.getType(item.uri) }.getOrNull()
                transfers.uploadWritten(file, item.name, mime, folderId, DriveLogic.ON_CONFLICT_RENAME)
                queued++
            }
            _ui.update { if (queued == 0) it.copy(saving = false, error = "read") else it.copy(saving = false, queued = queued) }
        }
    }

    private fun copy(item: Item): File? {
        val dir = File(context.cacheDir, "drive-share").apply { mkdirs() }
        val target = File.createTempFile("share", ".part", dir)
        return try {
            if (item.uri == null) {
                target.writeText(item.text.orEmpty())
            } else {
                val input = context.contentResolver.openInputStream(item.uri) ?: run { target.delete(); return null }
                input.use { inp -> target.outputStream().use { inp.copyTo(it, 256 * 1024) } }
            }
            target
        } catch (e: Exception) {
            Timber.d("Drive: shared file not read (%s)", e.javaClass.simpleName)
            target.delete()
            null
        }
    }

    private companion object {
        const val KEY_FOLDER = "share_folder"
    }
}
