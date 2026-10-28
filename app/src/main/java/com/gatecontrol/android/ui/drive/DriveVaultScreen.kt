package com.gatecontrol.android.ui.drive

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.R
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVault
import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.drive.vault.DriveVaultSession
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
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
import com.gatecontrol.android.ui.theme.MonoFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ─── vault folders (Drive 1.9, feature `vault`) ─────────────────────

/** A folder of a vault: its names decrypted on this phone, or what this device must do to open the vault. */
@HiltViewModel
class DriveVaultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val session: DriveVaultSession,
    private val live: DriveLive,
) : ViewModel() {

    val vaultId: String = savedStateHandle.get<String>("vault").orEmpty()
    private val folderId: Long = savedStateHandle.get<String>("folder")?.toLongOrNull() ?: 0L
    /** Opened by a request's push: the requests show at once. */
    private val showRequests: Boolean = savedStateHandle.get<String>("requests") == "1"

    sealed class Ui {
        data object Loading : Ui()
        data class Error(val code: String) : Ui()
        /** This device holds no keys: ask, wait, or the recovery key. */
        data class Locked(val vault: DVault, val access: DriveVaultSession.Access) : Ui()
        data class Ready(val vault: DVault, val folder: DFolder, val title: String, val items: List<Pair<DNode, String>>) : Ui()
    }

    private val _ui = MutableStateFlow<Ui>(Ui.Loading)
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The upload running now: its name and 0..1. */
    private val _upload = MutableStateFlow<Pair<String, Float>?>(null)
    val upload: StateFlow<Pair<String, Float>?> = _upload.asStateFlow()

    /** The requests sheet (null: closed). */
    private val _requests = MutableStateFlow<DriveLoad<List<DVaultDevice>>?>(null)
    val requests: StateFlow<DriveLoad<List<DVaultDevice>>?> = _requests.asStateFlow()

    private var job: Job? = null
    private var requestsShown = false

    init {
        refresh()
        viewModelScope.launch {
            live.vault.collect { e ->
                if (e.vault != vaultId) return@collect
                when (e.event) {
                    // approved (here or elsewhere), a new key: the keys again
                    "approved", "rotated" -> { session.reload(vaultId); refresh(quiet = true) }
                    "request" -> { refresh(quiet = true); if (_requests.value != null) openRequests() }
                }
            }
        }
        viewModelScope.launch { live.changed.collect { if (_ui.value is Ui.Ready) refresh(quiet = true) } }
    }

    fun clearMessage() { _message.value = null }

    fun refresh(quiet: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _ui.value !is Ui.Ready) _ui.value = Ui.Loading
            val v = api.vault(vaultId)
            if (v !is DResult.Ok) {
                _ui.value = Ui.Error(v.errorCode() ?: "failed")
                return@launch
            }
            val vault = v.value
            when (val a = session.open(vault)) {
                is DResult.Ok -> if (a.value != DriveVaultSession.Access.Open) {
                    _ui.value = Ui.Locked(vault, a.value)
                    return@launch
                }
                else -> {
                    _ui.value = Ui.Error(a.errorCode() ?: "failed")
                    return@launch
                }
            }
            val f = api.folder(if (folderId > 0) folderId else vault.nodeId)
            if (f !is DResult.Ok) {
                _ui.value = Ui.Error(f.errorCode() ?: "failed")
                return@launch
            }
            val folder = f.value
            val title = (if (folder.node.id == vault.nodeId) session.nameOf(vault) else session.nameOf(folder.node)) ?: "…"
            val items = DriveLogic.sortVault(folder.items.filter { it.vaultId == vaultId }.map { it to (session.nameOf(it) ?: "…") })
            _ui.value = Ui.Ready(vault, folder, title, items)
            if (showRequests && !requestsShown && vault.pending > 0) {
                requestsShown = true
                openRequests()
            }
        }
    }

    private fun locked(): DVault? = (_ui.value as? Ui.Locked)?.vault

    fun ask() {
        if (locked() == null) return
        act { session.ask(vaultId).also { r -> if (r is DResult.Ok) refresh(quiet = true) } }
    }

    /** Unlocks with the written-down recovery key. */
    fun recover(text: String) {
        val vault = locked() ?: return
        act { session.recover(vault, text).also { r -> if (r is DResult.Ok) refresh() } }
    }

    private fun act(done: String? = null, block: suspend () -> DResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            val r = block()
            _busy.value = false
            _message.value = r.errorCode() ?: done
        }
    }

    // ─── requests of new devices ────────────────────────────────────

    fun openRequests() {
        if (_requests.value == null) _requests.value = DriveLoad.Loading
        viewModelScope.launch { _requests.value = api.vaultDevices(vaultId).toLoad { l -> l.filter { it.state == "pending" } } }
    }

    fun closeRequests() { _requests.value = null }

    /** The person compared the safety number: the keys go to [device]. */
    fun approve(device: DVaultDevice) = act("done_approved") {
        session.approve(vaultId, device).also { openRequests(); refresh(quiet = true) }
    }

    fun decline(device: DVaultDevice) = act("done_declined") {
        api.vaultRevoke(vaultId, device.deviceId).also { openRequests(); refresh(quiet = true) }
    }

    // ─── the folder ─────────────────────────────────────────────────

    private fun here(): Ui.Ready? = _ui.value as? Ui.Ready

    /** Encrypts and uploads the picked files one after the other. */
    fun upload(uris: List<Uri>, names: (Uri) -> String) {
        val r = here() ?: return
        viewModelScope.launch {
            var last: DResult<*> = DResult.Ok(Unit)
            for (uri in uris) {
                val name = names(uri)
                _upload.value = name to 0f
                last = session.upload(vaultId, r.folder.node.id, uri) { p -> _upload.value = name to p }
                if (last !is DResult.Ok) break
            }
            _upload.value = null
            _message.value = last.errorCode() ?: "done_upload"
            refresh(quiet = true)
        }
    }

    fun createFolder(name: String) {
        val r = here() ?: return
        act("done_folder") { session.createFolder(vaultId, r.folder.node.id, name).also { if (it is DResult.Ok) refresh(quiet = true) } }
    }

    fun rename(node: DNode, name: String) = act("done_rename") { session.rename(node, name).also { if (it is DResult.Ok) refresh(quiet = true) } }

    fun delete(node: DNode) = act("done_trash") { api.delete(node.id).also { if (it is DResult.Ok) refresh(quiet = true) } }

    /** The file decrypted into the cache (removed when the vault closes). */
    suspend fun fetch(node: DNode, name: String): DResult<File> {
        val r = session.fetch(node, name)
        // a key version this device did not hold: loaded now, so once more
        return if (r is DResult.Failed && r.code == "stale_key") session.fetch(node, name) else r
    }

    override fun onCleared() {
        // decrypted copies only while the vault is open
        session.wipeOpened()
        super.onCleared()
    }
}

/** A folder of a vault (its own page; a folder inside opens as the next page). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveVaultScreen(onBack: () -> Unit, onFolder: (vaultId: String, folderId: Long) -> Unit, viewModel: DriveVaultViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    var renaming by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    var deleting by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var approving by remember { mutableStateOf<DVaultDevice?>(null) }
    var shown by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        if (shown) viewModel.refresh(quiet = true)
        shown = true
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris) { u -> displayName(context, u) }
    }
    val open: (DNode, String) -> Unit = { n, name ->
        scope.launch {
            when (val r = viewModel.fetch(n, name)) {
                is DResult.Ok -> openFile(context, n.copy(name = name, mime = null), r.value)
                else -> Toast.makeText(context, driveText(context, r.errorCode() ?: "failed"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val ready = ui as? DriveVaultViewModel.Ui.Ready
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(ready?.title ?: stringResource(R.string.drive_vault), onBack = onBack, backDescription = stringResource(R.string.drive_back)) {
                if (ready != null) {
                    GcIconButton(GcIcons.Users, contentDescription = stringResource(R.string.drive_vault_requests), onClick = viewModel::openRequests, tint = MaterialTheme.colorScheme.onBackground)
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = ui) {
                    DriveVaultViewModel.Ui.Loading -> DriveLoading()
                    is DriveVaultViewModel.Ui.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                    is DriveVaultViewModel.Ui.Locked -> LockedVault(s.access, busy, safety = viewModel.session.safetyNumber(), onAsk = viewModel::ask, onCheck = { viewModel.refresh() }, onRecover = viewModel::recover)
                    is DriveVaultViewModel.Ui.Ready -> {
                        if (s.vault.pending > 0) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Users) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pluralStringResource(R.plurals.drive_vault_requests_banner, s.vault.pending, s.vault.pending), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = viewModel::openRequests) { Text(stringResource(R.string.drive_vault_requests)) }
                                }
                            }
                        }
                        if (s.vault.needsRotation) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Key) { Text(stringResource(R.string.drive_vault_rotation), style = MaterialTheme.typography.bodyMedium) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(GcIcons.Lock, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.drive_vault_note), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                        }
                        upload?.let { (name, p) ->
                            GcCard {
                                Text(stringResource(R.string.drive_vault_uploading, name), style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (s.items.isEmpty()) {
                            Text(stringResource(R.string.drive_empty_folder), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
                        } else {
                            val haptic = LocalHapticFeedback.current
                            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                s.items.forEachIndexed { i, (n, name) ->
                                    if (i > 0) GcRowDivider()
                                    NodeRow(
                                        node = n,
                                        onClick = { if (n.isDir) onFolder(viewModel.vaultId, n.id) else open(n, name) },
                                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = n to name },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (ready != null) {
            DriveFab(
                uploads = emptyList(),
                onAdd = if (ready.folder.writable && upload == null) ({ addOpen = true }) else null,
                onOpenUploads = {},
                onAllDone = {},
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp),
            )
        }
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
            }
        }
    }
    menuFor?.let { (n, name) ->
        val writable = ready?.folder?.writable == true
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                val item: @Composable (androidx.compose.ui.graphics.vector.ImageVector, Int, () -> Unit) -> Unit = { icon, label, action ->
                    GcListRow(
                        title = stringResource(label),
                        onClick = { menuFor = null; action() },
                        leading = { Icon(icon, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                    )
                }
                if (!n.isDir) item(GcIcons.External, R.string.drive_open) { open(n, name) }
                if (writable) item(GcIcons.Edit, R.string.drive_rename) { renaming = n to name }
                if (writable) item(GcIcons.Trash, R.string.drive_delete_menu) { deleting = n to name }
            }
        }
    }
    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            onConfirm = { newFolder = false; viewModel.createFolder(it) },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { (n, name) ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = name,
            confirm = stringResource(R.string.drive_save),
            onConfirm = { renaming = null; if (it != name) viewModel.rename(n, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { (n, name) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.drive_delete_title, name)) },
            text = { Text(stringResource(R.string.drive_delete_server_desc)) },
            confirmButton = { TextButton(onClick = { deleting = null; viewModel.delete(n) }) { Text(stringResource(R.string.drive_delete_server)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    requests?.let { r ->
        ModalBottomSheet(onDismissRequest = viewModel::closeRequests) {
            RequestsSheet(r, busy, onApprove = { approving = it }, onDecline = viewModel::decline)
        }
    }
    approving?.let { d ->
        val safety = remember(d.publicKey) { runCatching { DriveVaultCrypto.safetyNumber(DriveVaultCrypto.unb64u(d.publicKey)) }.getOrNull() }
        AlertDialog(
            onDismissRequest = { approving = null },
            title = { Text(stringResource(R.string.drive_vault_approve_title, d.name ?: stringResource(R.string.drive_vault_unnamed_device))) },
            text = { Text(stringResource(R.string.drive_vault_approve_text, safety ?: "?")) },
            confirmButton = {
                TextButton(onClick = { approving = null; viewModel.approve(d) }, enabled = safety != null) { Text(stringResource(R.string.drive_vault_approve_yes)) }
            },
            dismissButton = { TextButton(onClick = { approving = null }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
}

/** This device holds no keys: ask (then the safety number), or the recovery key. */
@Composable
private fun LockedVault(
    access: DriveVaultSession.Access,
    busy: Boolean,
    safety: String,
    onAsk: () -> Unit,
    onCheck: () -> Unit,
    onRecover: (String) -> Unit,
) {
    val x = GateControlTheme.extraColors
    var recovering by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GcIcons.Vault, contentDescription = null, tint = x.warn, modifier = Modifier.padding(end = 12.dp))
            Text(
                stringResource(if (access is DriveVaultSession.Access.Pending) R.string.drive_vault_pending_title else R.string.drive_vault_locked_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when (access) {
            is DriveVaultSession.Access.Pending -> {
                Text(stringResource(R.string.drive_vault_pending_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                SafetyNumber(access.safetyNumber)
                GcOutlineButton(stringResource(R.string.drive_vault_check_again), onClick = onCheck, enabled = !busy, icon = GcIcons.Refresh)
            }
            is DriveVaultSession.Access.Unknown -> {
                Text(stringResource(if (access.revoked) R.string.drive_vault_revoked else R.string.drive_vault_locked_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                GcPrimaryButton(stringResource(R.string.drive_vault_ask), onClick = onAsk, enabled = !busy, loading = busy && !recovering, icon = GcIcons.Users)
            }
            DriveVaultSession.Access.Open -> Unit
        }
    }
    GcCard {
        if (!recovering) {
            GcOutlineButton(stringResource(R.string.drive_vault_recover), onClick = { recovering = true }, icon = GcIcons.Key)
        } else {
            Text(stringResource(R.string.drive_vault_recover), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            GcTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                label = stringResource(R.string.drive_vault_recovery_title),
                hint = stringResource(R.string.drive_vault_recover_hint),
                mono = true,
            )
            GcPrimaryButton(stringResource(R.string.drive_vault_recover_go), onClick = { onRecover(text) }, enabled = !busy && text.count { it.isLetterOrDigit() } >= 56, loading = busy, icon = GcIcons.LockOpen)
        }
    }
    if (access !is DriveVaultSession.Access.Pending) {
        // this device's number is also shown here: an approving device can compare it after "Freigabe anfragen"
        Text(stringResource(R.string.drive_vault_safety) + ": " + safety, style = MaterialTheme.typography.bodySmall, color = x.faint, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** "44626 75008 87340 28578", large and monospaced. */
@Composable
internal fun SafetyNumber(number: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GcSectionLabel(stringResource(R.string.drive_vault_safety))
        Text(
            number,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFontFamily),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun RequestsSheet(load: DriveLoad<List<DVaultDevice>>, busy: Boolean, onApprove: (DVaultDevice) -> Unit, onDecline: (DVaultDevice) -> Unit) {
    val x = GateControlTheme.extraColors
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.drive_vault_requests), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        when (load) {
            DriveLoad.Loading -> DriveLoading()
            is DriveLoad.Error -> DriveErrorBox(load.code, null)
            is DriveLoad.Ready -> if (load.value.isEmpty()) {
                Text(stringResource(R.string.drive_vault_requests_none), color = x.muted, modifier = Modifier.padding(4.dp))
            } else {
                Text(stringResource(R.string.drive_vault_request_hint), style = MaterialTheme.typography.bodyMedium, color = x.muted, modifier = Modifier.padding(horizontal = 4.dp))
                load.value.forEach { d ->
                    // the number is computed here from the device's public key — never taken from the server
                    val safety = remember(d.publicKey) { runCatching { DriveVaultCrypto.safetyNumber(DriveVaultCrypto.unb64u(d.publicKey)) }.getOrNull() }
                    GcCard {
                        GcListRow(
                            title = d.name ?: stringResource(R.string.drive_vault_unnamed_device),
                            description = listOfNotNull(d.platform, shortDay(d.createdAt?.take(10))).joinToString(" · ").ifEmpty { null },
                            leading = { Icon(GcIcons.Phone, contentDescription = null, tint = x.muted) },
                        )
                        if (safety != null) SafetyNumber(safety)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GcOutlineButton(stringResource(R.string.drive_vault_decline), onClick = { onDecline(d) }, enabled = !busy, modifier = Modifier.weight(1f))
                            GcPrimaryButton(stringResource(R.string.drive_vault_approve), onClick = { onApprove(d) }, enabled = !busy && safety != null, modifier = Modifier.weight(1f), icon = GcIcons.Check)
                        }
                    }
                }
            }
        }
    }
}

/** The recovery key of a new vault: shown once, copy / share, and only "Fertig" after "notiert". */
@Composable
internal fun RecoveryKeyDialog(key: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var noted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.drive_vault_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_vault_recovery_text), style = MaterialTheme.typography.bodyMedium)
                GcCard {
                    Text(key.replace("-", " "), style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFontFamily), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcOutlineButton(stringResource(R.string.drive_vault_copy), onClick = { copySecret(context, key) }, icon = GcIcons.Copy, modifier = Modifier.weight(1f))
                    GcOutlineButton(stringResource(R.string.drive_vault_share), onClick = {
                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key)
                        runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, icon = GcIcons.Share, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = noted, onCheckedChange = { noted = it })
                    Text(stringResource(R.string.drive_vault_recovery_noted), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone, enabled = noted) { Text(stringResource(R.string.drive_vault_done)) } },
    )
}

/** Copies a secret (marked sensitive: Android 13+ hides it in the clipboard preview). */
private fun copySecret(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("GateControl", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    runCatching { cm.setPrimaryClip(clip) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.drive_vault_copied, Toast.LENGTH_SHORT).show()
}

/** "Neuer Tresor": its name (encrypted on this phone), then the recovery key. */
@Composable
internal fun NewVaultDialog(busy: Boolean, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val valid = DriveLogic.validName(value)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.drive_vault_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_vault_create_text), style = MaterialTheme.typography.bodyMedium, color = GateControlTheme.extraColors.muted)
                GcTextField(
                    value = value,
                    onValueChange = { value = it.take(255) },
                    label = stringResource(R.string.drive_name),
                    error = if (value.isNotEmpty() && !valid) stringResource(R.string.drive_name_invalid) else null,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(value.trim()) }, enabled = valid && !busy) { Text(stringResource(R.string.drive_vault_create)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** The display name of a picked file. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "…"
