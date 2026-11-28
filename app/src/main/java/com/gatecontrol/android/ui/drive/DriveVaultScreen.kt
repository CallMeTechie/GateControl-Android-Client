package com.gatecontrol.android.ui.drive

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.gatecontrol.android.ui.components.GcChevron
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.activity.compose.BackHandler
import com.gatecontrol.android.drive.DriveBulk
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DriveOffline
import com.gatecontrol.android.drive.DriveOfflineStore
import com.gatecontrol.android.drive.vault.DriveVaultOfflineLogic
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.gatecontrol.android.drive.vault.DriveVaultCrypto
import com.gatecontrol.android.drive.vault.DriveVaultHeld
import com.gatecontrol.android.drive.vault.DriveVaultShares
import com.gatecontrol.android.drive.vault.DriveVaultView
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import com.gatecontrol.android.drive.vault.DriveVaultSession
import com.gatecontrol.android.drive.vault.DriveVaultMove
import java.util.concurrent.atomic.AtomicBoolean
import com.gatecontrol.android.drive.vault.DriveVaultLock
import com.gatecontrol.android.drive.DriveSettings
import com.gatecontrol.android.drive.DriveUploadNetwork
import com.gatecontrol.android.ui.components.GcBanner
import com.gatecontrol.android.ui.components.DeviceAuthResult
import com.gatecontrol.android.ui.components.rememberGcDeviceAuth
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcListRow
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcShowOnce
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.components.GcTone
import com.gatecontrol.android.ui.components.SecureScreenEffect
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val session: DriveVaultSession,
    private val live: DriveLive,
    private val lock: DriveVaultLock,
    private val settings: DriveSettings,
    private val network: DriveUploadNetwork,
    val ops: com.gatecontrol.android.drive.DriveOps,
    transfers: com.gatecontrol.android.drive.DriveTransfers,
    /** "Offline verfügbar" (Drive 1.20): the marks, here for the vault's files, folders and the whole vault. */
    private val marks: DriveOffline,
    /** The ciphertext copies on this phone ("Tresor-Dateien offline"). */
    val offlineStore: DriveOfflineStore,
    /** "In Tresor verschieben": the originals go once their encrypted upload is through and verified. */
    private val vaultMove: DriveVaultMove,
    /** "Nur auf diesem Gerät" (Drive 1.32): the folders kept only here, the moves there and back, the work inside. */
    val localStore: com.gatecontrol.android.drive.vault.DriveVaultLocalStore,
    val localJobs: com.gatecontrol.android.drive.vault.DriveVaultLocalJobs,
    private val localEdit: com.gatecontrol.android.drive.vault.DriveVaultLocalEdit,
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
        /**
         * Drive 1.11: the vault is locked — it opens after the person confirms (fingerprint, face or the phone's PIN).
         * [noScreenLock]: the phone has none, vaults cannot be opened. [title] is the remembered name, if any.
         */
        data class Gate(val vault: DVault, val title: String?, val noScreenLock: Boolean) : Ui()
        /**
         * The folder. [ancestors]: its folders from the top (for the marks above it). [offline]: no connection — [items]
         * are the files on this phone below the folder (each with its folder's name in [places]), [netOnly] the other
         * files as last seen (greyed out, "Nur mit Netz").
         */
        data class Ready(
            val vault: DVault,
            val folder: DFolder,
            val title: String,
            val items: List<Pair<DNode, String>>,
            val ancestors: List<Long> = emptyList(),
            val offline: Boolean = false,
            val netOnly: List<Pair<DNode, String>> = emptyList(),
            val places: Map<Long, String> = emptyMap(),
            /** A folder kept only on this phone (or a folder inside it): its content comes from here, never the server. */
            val local: com.gatecontrol.android.drive.vault.DriveVaultLocalFolder? = null,
        ) : Ui()
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

    /** The viewer of a file (Drive plugin 1.22; null: closed) — what it shows lives in memory only. */
    private val _viewer = MutableStateFlow<VaultViewer?>(null)
    val viewer: StateFlow<VaultViewer?> = _viewer.asStateFlow()
    private var viewerJob: Job? = null
    /** What the open viewer holds (bitmaps, a PDF, a video's ciphertext): released on close and on lock. */
    private var held = DriveVaultHeld()
    /** The player of the video shown: here, not in the screen — a fold, an unfold or a turn of the phone does not stop it. */
    private val videoPlayers = VaultKeptPlayer<ExoPlayer> { it.release() }

    /** The requests sheet (null: closed). */
    private val _requests = MutableStateFlow<DriveLoad<List<DVaultDevice>>?>(null)
    val requests: StateFlow<DriveLoad<List<DVaultDevice>>?> = _requests.asStateFlow()

    private var job: Job? = null
    private var requestsShown = false

    /**
     * The ticked items of the vault: moved within it, copied (Drive 1.12, when the server can do it without decrypting)
     * or put in the trash — nothing leaves the vault (no sending, no link, no ZIP).
     */
    internal val bulk = DriveBulkActions(
        context = context,
        scope = viewModelScope,
        api = api,
        ops = ops,
        transfers = transfers,
        live = live,
        items = { (_ui.value as? Ui.Ready)?.items?.map { it.first }.orEmpty() },
        here = { (_ui.value as? Ui.Ready)?.folder },
        changed = { refresh(quiet = true) },
        vaultId = vaultId,
        nameOf = { session.nameOf(it) ?: "…" },
        vaultRoot = { (_ui.value as? Ui.Ready)?.vault?.nodeId },
    ).also { b ->
        b.newVaultFolder = { parent, name -> lock.touch(); session.createFolder(vaultId, parent, name) }
        // sending and saving never apply to a vault (DriveBulk: Why.VAULT); nothing of it is decrypted into a file
        b.fetch = { DResult.Failed("vault", 0) }
    }

    /** The person just confirmed: the next opening may read the device key (and moves it, [DriveVaultSession.protectDeviceKey]). */
    private var confirmed = false

    init {
        refresh()
        // locked (time without use, the app in the background): the content goes, the vault asks again
        viewModelScope.launch {
            session.locks.collect {
                val vault = when (val u = _ui.value) {
                    is Ui.Ready -> u.vault
                    is Ui.Locked -> u.vault
                    else -> null
                } ?: return@collect
                job?.cancel()
                // the viewer first: its plaintext goes with the keys
                closeViewer()
                confirmed = false
                _requests.value = null
                _move.value = null
                bulk.clear()
                bulk.closeTarget()
                _conflict.value?.let { resolve(it, null) }
                _ui.value = Ui.Gate(vault, session.label(vault), noScreenLock = !deviceSecure())
            }
        }
        viewModelScope.launch {
            live.vault.collect { e ->
                if (e.vault != vaultId) return@collect
                when (e.event) {
                    // approved (here or elsewhere), a new key: the keys again
                    "approved", "rotated" -> if (session.unlocked(vaultId) || _ui.value is Ui.Locked) { session.reload(vaultId); refresh(quiet = true) }
                    "request" -> { refresh(quiet = true); if (_requests.value != null) openRequests() }
                }
            }
        }
        viewModelScope.launch { live.changed.collect { if (_ui.value is Ui.Ready) refresh(quiet = true) } }
        // a folder kept here changed (a move or the way back through, a file added): the page follows
        viewModelScope.launch {
            var first = true
            localStore.folders.collect {
                if (!first && _ui.value is Ui.Ready) refresh(quiet = true)
                first = false
            }
        }
        // a job of this vault through: the page anew, a move's end with "Rückgängig"
        viewModelScope.launch {
            val seen = HashSet<Long>()
            localJobs.progress.collect { m ->
                m.values.filter { it.vaultId == vaultId && it.finished && seen.add(it.nodeId) }.forEach { p ->
                    if (p.kind == com.gatecontrol.android.drive.vault.DriveVaultLocalJobs.Kind.MOVE) _localDone.value = p.nodeId
                    else _message.value = "local_back_done"
                    localJobs.dismiss(p.nodeId)
                    refresh(quiet = true)
                }
            }
        }
    }

    fun clearMessage() { _message.value = null }

    // ─── "Nur auf diesem Gerät" (Drive 1.32, feature `vaultLocal`) ────────────

    private val _localDone = MutableStateFlow<Long?>(null)
    /** A move onto this phone is through: the snackbar "Liegt jetzt nur auf diesem Handy" with "Rückgängig". */
    val localDone: StateFlow<Long?> = _localDone.asStateFlow()

    fun localDoneShown() { _localDone.value = null }

    /**
     * The choice "Nur auf diesem Handy" is offered: Drive can do it (`vaultLocal`, the administrator's setting), the vault
     * is "Nur ich" (in my own files), this device is approved and may write.
     */
    val canLocal: StateFlow<Boolean> = combine(live.me, _ui) { me, u ->
        val r = u as? Ui.Ready ?: return@combine false
        me?.has(DMe.FEATURE_VAULT_LOCAL) == true && !r.offline && r.local == null && r.folder.writable &&
            me.areas.any { it.id == r.vault.areaId && it.kind == "mine" } && session.approved(r.vault)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The page of a folder kept only here, or of a folder inside it (null: not one). */
    private fun localPage(vault: DVault): Ui.Ready? {
        val fid = if (folderId != 0L) folderId else return null
        val lf = localStore.get(fid)
            ?: localStore.folderOfItem(fid)?.takeIf { f -> f.items.any { it.id == fid && it.isDir } }
            ?: return null
        if (lf.vaultId != vaultId || lf.state == com.gatecontrol.android.drive.vault.DriveVaultLocalFolder.State.MOVING) return null
        val L = com.gatecontrol.android.drive.vault.DriveVaultLocalLogic
        val pageNode = if (fid == lf.nodeId) {
            DNode(
                id = lf.nodeId, areaId = lf.areaId, parentId = lf.parentId, name = L.opaque(lf.nodeId), isDir = true, size = 0, mime = null,
                mediaId = null, rev = 1, mtime = null, updatedAt = null, updatedBy = null, vaultId = lf.vaultId, nameCipher = lf.nameCipher,
            )
        } else {
            L.asNode(lf, lf.items.first { it.id == fid })
        }
        val nodes = L.children(lf, fid).map { L.asNode(lf, it) }
        val items = DriveLogic.sortVault(nodes.map { it to (session.nameOf(it) ?: "…") })
        val title = session.nameOf(pageNode) ?: "…"
        val folder = DFolder(pageNode, crumbs = emptyList(), role = if (lf.revoked) "read" else "write", items = nodes, total = nodes.size, next = null)
        return Ui.Ready(vault, folder, title, items, local = lf)
    }

    /** What the confirmation (A3) shows: the server's numbers of the move begun, the free room here. */
    data class LocalAsk(val node: DNode, val name: String, val start: DriveLoad<com.gatecontrol.android.drive.DVaultLocalStart>, val free: Long = 0)

    private val _localAsk = MutableStateFlow<LocalAsk?>(null)
    val localAsk: StateFlow<LocalAsk?> = _localAsk.asStateFlow()

    /**
     * "Nur auf diesem Handy" chosen for [node]: the move begins on the server (the numbers of the confirmation; other
     * devices see "wird verschoben"), the room here is checked. Nothing is deleted until the person confirms and every
     * file is here and checked.
     */
    fun askLocal(node: DNode, name: String) {
        val r = here() ?: return
        lock.touch()
        if (_upload.value != null) {
            _message.value = "local_busy"
            return
        }
        _localAsk.value = LocalAsk(node, name, DriveLoad.Loading)
        viewModelScope.launch {
            val did = runCatching { session.deviceId() }.getOrNull() ?: return@launch
            when (val s = api.vaultLocalStart(r.vault.id, node.id, did)) {
                is DResult.Ok -> {
                    val free = withContext(Dispatchers.IO) { localStore.usableSpace() }
                    if (!com.gatecontrol.android.drive.vault.DriveVaultLocalLogic.enoughRoom(s.value.bytes, free)) {
                        api.vaultLocalCancel(r.vault.id, node.id, did)
                        _localAsk.value = null
                        _message.value = "local_no_space"
                    } else if (_localAsk.value?.node?.id == node.id) {
                        _localAsk.value = LocalAsk(node, name, DriveLoad.Ready(s.value), free)
                    } else {
                        api.vaultLocalCancel(r.vault.id, node.id, did)
                    }
                }
                else -> {
                    _localAsk.value = null
                    _message.value = localErrorCode(s)
                }
            }
        }
    }

    /** The code of a refused step: `local_only`, `local_nested`, … (the server's text is in the body). */
    private fun localErrorCode(r: DResult<*>): String = when {
        r is DResult.Failed && r.code == "local_nested" -> "local_nested"
        r is DResult.Failed && r.code == "local_shared" -> "local_shared"
        r is DResult.Failed && r.code == "local_only" -> "local_only"
        r is DResult.Failed && r.code == "local_moving" -> "local_moving"
        r is DResult.Failed && r.code == "too_large" -> "local_too_large"
        r is DResult.Failed && r.code == "feature_off" -> "local_off"
        else -> r.errorCode() ?: "failed"
    }

    /** The confirmation closed without a move: the server forgets the move begun (nothing was deleted). */
    fun dismissLocalAsk() {
        val a = _localAsk.value ?: return
        _localAsk.value = null
        val r = here() ?: return
        if (a.start is DriveLoad.Ready) viewModelScope.launch { runCatching { api.vaultLocalCancel(r.vault.id, a.node.id, session.deviceId()) } }
    }

    /** The device key for a job (read right after the person confirmed); null with a message when it needs the person. */
    private fun keyPair(): com.gatecontrol.android.drive.vault.DriveVaultCrypto.KeyPair? = try {
        session.deviceKeyPair()
    } catch (e: com.gatecontrol.android.drive.vault.DriveVaultDeviceKey.AuthRequired) {
        _message.value = "local_auth"
        null
    } catch (e: Exception) {
        _message.value = "failed"
        null
    }

    /** The person ticked and confirmed (A3) — and confirmed with fingerprint or PIN: the move runs. */
    fun confirmLocal() {
        val a = _localAsk.value ?: return
        val r = here() ?: return
        val pair = keyPair() ?: return
        _localAsk.value = null
        lock.touch()
        localJobs.move(r.vault, a.node, a.name, pair)
    }

    /** A move or a way back that stopped (no connection, the confirmation needed): on where it stopped. */
    fun retryLocal(p: com.gatecontrol.android.drive.vault.DriveVaultLocalJobs.Progress) {
        val r = here() ?: return
        val node = placeholderNode(p.nodeId) ?: return
        val pair = keyPair() ?: return
        localJobs.dismiss(p.nodeId)
        if (p.kind == com.gatecontrol.android.drive.vault.DriveVaultLocalJobs.Kind.MOVE) localJobs.move(r.vault, node, p.name, pair)
        else localJobs.back(r.vault, node, p.name, pair, keepOffline = false)
    }

    fun cancelLocal(nodeId: Long) = localJobs.cancel(nodeId)

    fun dismissLocal(nodeId: Long) = localJobs.dismiss(nodeId)

    /** The placeholder [nodeId] as a node (a row of this page, else from the folder kept here). */
    private fun placeholderNode(nodeId: Long): DNode? {
        here()?.items?.firstOrNull { it.first.id == nodeId }?.first?.let { return it }
        val f = localStore.get(nodeId) ?: return null
        return DNode(
            id = f.nodeId, areaId = f.areaId, parentId = f.parentId, name = com.gatecontrol.android.drive.vault.DriveVaultLocalLogic.opaque(f.nodeId),
            isDir = true, size = 0, mime = null, mediaId = null, rev = 1, mtime = null, updatedAt = null, updatedBy = null,
            vaultId = f.vaultId, nameCipher = f.nameCipher,
        )
    }

    /** "Zurück auf den Server" ([keepOffline]: "… und zusätzlich offline"), after the person confirmed. */
    fun backToServer(nodeId: Long, name: String, keepOffline: Boolean) {
        val r = here() ?: return
        val node = placeholderNode(nodeId) ?: return
        val pair = keyPair() ?: return
        lock.touch()
        localJobs.back(r.vault, node, name, pair, keepOffline)
    }

    /** "Rückgängig" (within 24 h): the content back on the server as it was. */
    fun undoLocal(nodeId: Long) = act("local_undone") { localJobs.undo(nodeId).also { if (it is DResult.Ok) refresh(quiet = true) } }

    /** "Ordner löschen" of a folder kept only here (with the device's proof): gone for good. */
    fun deleteLocalFolder(nodeId: Long) {
        val pair = keyPair() ?: return
        act("done_trash") { localJobs.deleteHere(nodeId, pair).also { if (it is DResult.Ok) refresh(quiet = true) } }
    }

    /** "Platzhalter entfernen" of a folder on another device of mine (that device lost; after the 24 hours). */
    fun forgetPlaceholder(node: DNode) = act("local_forgotten") {
        api.vaultLocalForget(vaultId, node.id, session.deviceId(), null).also { if (it is DResult.Ok) refresh(quiet = true) }
    }

    // ─── working inside a folder kept here ──────────────────────────

    private fun localHere(): Pair<com.gatecontrol.android.drive.vault.DriveVaultLocalFolder, Long>? {
        val r = here() ?: return null
        val lf = r.local ?: return null
        return lf to r.folder.node.id
    }

    fun createLocalFolder(name: String) {
        val (lf, parent) = localHere() ?: return
        act("done_folder") { localEdit.newFolder(lf.nodeId, parent, name) }
    }

    fun renameLocal(node: DNode, name: String) {
        val (lf, _) = localHere() ?: return
        act("done_rename") { localEdit.rename(lf.nodeId, node.id, name) }
    }

    fun deleteLocal(node: DNode) {
        val (lf, _) = localHere() ?: return
        act("local_deleted") { localEdit.delete(lf.nodeId, node.id) }
    }

    /** New files into a folder kept here: encrypted on this phone, never sent; the original goes once checked. */
    fun uploadLocal(uris: List<Uri>, names: (Uri) -> String) {
        val (lf, parent) = localHere() ?: return
        val r = here() ?: return
        viewModelScope.launch {
            var outcome = DriveVaultMove.Outcome()
            var last: DResult<*> = DResult.Ok(Unit)
            val taken = r.items.map { it.second }.toMutableList()
            for (uri in uris) {
                val name = DriveLogic.freeName(DriveLogic.safeName(names(uri)) ?: "file", taken)
                taken += name
                _upload.value = name to 0f
                last = lock.busy { localEdit.add(lf.nodeId, parent, uri, name) { p -> _upload.value = name to p } }
                if (last !is DResult.Ok) break
                outcome += lock.busy { vaultMove.remove(uri) }
            }
            _upload.value = null
            last.errorCode()?.let { _message.value = it }
            finishMove(outcome)
        }
    }

    /** The phone has a screen lock (PIN, pattern or password): without one no vault opens (Drive 1.11). */
    private fun deviceSecure(): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true

    /** The person confirmed with fingerprint, face or PIN: the device key moves behind the confirmation, the vault opens. */
    fun confirmed() {
        val gate = _ui.value as? Ui.Gate ?: return
        if (gate.noScreenLock) return
        confirmed = true
        viewModelScope.launch {
            session.protectDeviceKey(settings.current.vaultBiometric)
            refresh()
        }
    }

    /** The vault was used: the time until it locks starts again. */
    fun touch() = lock.touch()

    /** Another app opens now (picker, viewer): leaving the app this once does not lock the vault. */
    fun away() = lock.away()

    /** "Sperren" by hand: every vault. */
    fun lockNow() = lock.lockNow()

    /** Marks of Drive read since this page opened (else the marks the copies on this phone came from). */
    private val marksKnown = MutableStateFlow(false)

    /** The marked nodes ("offline verfügbar"): Drive's, or without a connection those the copies here came from. */
    val offlineMarks: StateFlow<Set<Long>> = combine(marks.ids, offlineStore.copies, marksKnown) { ids, copies, known ->
        if (known) ids else ids + copies.values.filter { it.vaultId == vaultId }.flatMap { it.roots }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Drive keeps marks (feature `offline`): the switches show. */
    val canOffline: StateFlow<Boolean> = live.me.map { it?.has(DMe.FEATURE_OFFLINE) == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "Offline verfügbar" on or off for a file or folder of the vault, or the vault's folder ("Ganzen Tresor offline halten"). */
    fun setOffline(node: DNode, on: Boolean) {
        lock.touch()
        viewModelScope.launch { _message.value = marks.set(node, on) ?: if (on) "done_offline_on" else "done_offline_off" }
    }

    private fun loadMarks() {
        viewModelScope.launch {
            if (live.me.value == null) live.current()
            if (marks.list() is DResult.Ok) marksKnown.value = true
        }
    }

    fun refresh(quiet: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _ui.value !is Ui.Ready) _ui.value = Ui.Loading
            val v = api.vault(vaultId)
            // no connection: the vault as this phone kept it for its offline files (nothing kept: the error as before)
            val kept = if (v is DResult.Offline) withContext(Dispatchers.IO) { session.offlineVault(vaultId) } else null
            if (v !is DResult.Ok && kept == null) {
                // the vault gone or no longer mine: what this phone kept of it offline goes
                if (v is DResult.Failed && (v.http == 403 || v.http == 404)) withContext(Dispatchers.IO) { offlineStore.vaultRevoked(vaultId) }
                _ui.value = Ui.Error(v.errorCode() ?: "failed")
                return@launch
            }
            val vault = (v as? DResult.Ok)?.value ?: kept!!
            if (kept == null) loadMarks()
            // Drive 1.11: an approved device opens the vault only after the person confirmed (no screen lock: never)
            // (a vault answer without this person's devices counts as approved: never opened without the confirmation)
            if (!session.unlocked(vault.id) && (vault.myDevices.isEmpty() || session.approved(vault))) {
                val secure = deviceSecure()
                if (!secure || (settings.current.vaultBiometric && !confirmed)) {
                    _ui.value = Ui.Gate(vault, session.label(vault), noScreenLock = !secure)
                    return@launch
                }
            }
            val opened = session.open(vault)
            confirmed = false
            when (val a = opened) {
                is DResult.Ok -> if (a.value != DriveVaultSession.Access.Open) {
                    _ui.value = Ui.Locked(vault, a.value)
                    return@launch
                }
                is DResult.Failed -> if (a.code == DriveVaultSession.CODE_AUTH || a.code == DriveVaultSession.CODE_NO_LOCK) {
                    _ui.value = Ui.Gate(vault, session.label(vault), noScreenLock = a.code == DriveVaultSession.CODE_NO_LOCK || !deviceSecure())
                    return@launch
                } else {
                    _ui.value = Ui.Error(a.code)
                    return@launch
                }
                else -> {
                    _ui.value = Ui.Error(a.errorCode() ?: "failed")
                    return@launch
                }
            }
            lock.touch()
            // a folder kept only on this phone opens from here (also without a connection)
            localPage(vault)?.let { page ->
                _ui.value = page
                return@launch
            }
            val f = if (kept == null) api.folder(if (folderId != 0L) folderId else vault.nodeId, vaultDevice = runCatching { session.deviceId() }.getOrNull()) else DResult.Offline
            if (f is DResult.Offline && withContext(Dispatchers.IO) { session.offlineVault(vaultId) } != null) {
                _ui.value = withContext(Dispatchers.IO) { offlinePage(vault) }
                return@launch
            }
            if (f !is DResult.Ok) {
                _ui.value = Ui.Error(f.errorCode() ?: "failed")
                return@launch
            }
            val folder = f.value
            val title = (if (folder.node.id == vault.nodeId) session.nameOf(vault) else session.nameOf(folder.node)) ?: "…"
            val items = DriveLogic.sortVault(folder.items.filter { it.vaultId == vaultId }.map { it to (session.nameOf(it) ?: "…") })
            // the listing (opaque and encrypted names only) for opening without a connection, while files are offline
            withContext(Dispatchers.IO) {
                session.keepListing(vaultId, folder)
                // a folder kept only here renamed or moved (its placeholder): the copy here follows
                folder.items.filter { it.local != null }.forEach { n ->
                    localStore.get(n.id)?.takeIf { it.nameCipher != n.nameCipher || it.parentId != n.parentId }?.let {
                        runCatching { localStore.update(n.id) { f -> f.copy(nameCipher = n.nameCipher ?: f.nameCipher, parentId = n.parentId) } }
                    }
                }
            }
            _ui.value = Ui.Ready(vault, folder, title, items, ancestors = DriveVaultOfflineLogic.ancestors(folder))
            bulk.keepPresent()
            // a move or a way back of an earlier run that did not end: its card with "Weiter"
            localStore.ofVault(vaultId).filter { it.state != com.gatecontrol.android.drive.vault.DriveVaultLocalFolder.State.LOCAL }.forEach { f ->
                val name = items.firstOrNull { it.first.id == f.nodeId }?.second
                    ?: session.nameOf(DNode(f.nodeId, f.areaId, f.parentId, "", true, 0, null, null, 1, null, null, null, vaultId = f.vaultId, nameCipher = f.nameCipher))
                    ?: "…"
                localJobs.stalled(f, name)
            }
            if (showRequests && !requestsShown && vault.pending > 0) {
                requestsShown = true
                openRequests()
            }
        }
    }

    /**
     * The page without a connection: the files on this phone below the folder (they open as always, decrypted in memory
     * from the ciphertext here) and the other files of the folder as last seen, greyed out. Nothing can be changed.
     */
    private fun offlinePage(vault: DVault): Ui.Ready {
        val fid = if (folderId != 0L) folderId else vault.nodeId
        val seen = session.offlineFolder(vaultId, fid)
        val page = DriveVaultOfflineLogic.offlinePage(vaultId, vault.nodeId, fid, seen?.items, offlineStore.copies.value.values)
        val title = (if (fid == vault.nodeId) session.nameOf(vault) else seen?.node?.let { session.nameOf(it) } ?: session.offlineFolderName(vaultId, fid)) ?: "…"
        val ready = DriveLogic.sortVault(page.ready.map { c -> c.asNode().copy(areaId = vault.areaId.orEmpty()).let { n -> n to (session.nameOf(n) ?: "…") } })
        val places = page.ready.mapNotNull { c ->
            val parent = c.parents.lastOrNull()?.takeIf { it != fid && it != vault.nodeId } ?: return@mapNotNull null
            session.offlineFolderName(vaultId, parent)?.let { c.id to it }
        }.toMap()
        val netOnly = DriveLogic.sortVault(page.netOnly.map { it to (session.nameOf(it) ?: "…") })
        val folder = (seen ?: DFolder(
            node = DNode(
                id = fid, areaId = vault.areaId.orEmpty(), parentId = null, name = "", isDir = true, size = 0, mime = null, mediaId = null,
                rev = 0, mtime = null, updatedAt = null, updatedBy = null, vaultId = vaultId, nameCipher = if (fid == vault.nodeId) vault.nameCipher else null,
            ),
            crumbs = emptyList(), role = "read", items = emptyList(), total = 0, next = null,
        )).copy(role = "read")
        return Ui.Ready(vault, folder, title, ready, ancestors = DriveVaultOfflineLogic.ancestors(folder), offline = true, netOnly = netOnly, places = places)
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
        lock.touch()
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

    /** A picked file whose (decrypted) name is taken in the folder: new version, keep both or skip. */
    data class Conflict(val name: String, val existing: DNode, val answer: CompletableDeferred<String?>)

    private val _conflict = MutableStateFlow<Conflict?>(null)
    val conflict: StateFlow<Conflict?> = _conflict.asStateFlow()

    /** The person chose for [conflict]: `version`, `rename` or null (skip). */
    fun resolve(conflict: Conflict, choice: String?) {
        _conflict.value = null
        conflict.answer.complete(choice)
    }

    // ─── "In Tresor verschieben": the originals go ────────────────────

    private val _moved = MutableStateFlow<DriveVaultMove.Outcome?>(null)
    /** A batch is in the vault: what became of the originals (the snackbar; taken with [movedShown]). */
    val moved: StateFlow<DriveVaultMove.Outcome?> = _moved.asStateFlow()

    fun movedShown() { _moved.value = null }

    /** Android's dialog before gallery originals go for good ([sender]), shown once ([take]); [outcome] waits for it. */
    class DeleteAsk(val sender: IntentSender, val outcome: DriveVaultMove.Outcome) {
        private val taken = AtomicBoolean(false)
        /** True the first time only: the dialog opens once (not again after the page is drawn anew). */
        fun take(): Boolean = taken.compareAndSet(false, true)
    }

    private val _deleteAsk = MutableStateFlow<DeleteAsk?>(null)
    val deleteAsk: StateFlow<DeleteAsk?> = _deleteAsk.asStateFlow()

    /** The person answered Android's dialog ([ok]: deleted): the snackbar says what became of the originals. */
    fun deleteAnswered(ok: Boolean) {
        val a = _deleteAsk.value ?: return
        _deleteAsk.value = null
        viewModelScope.launch { _moved.value = a.outcome.asked(vaultMove.confirmAsked(a.outcome.ask, ok)) }
    }

    /** The batch is through: gallery originals wait for Android's dialog, else the snackbar at once. */
    private fun finishMove(o: DriveVaultMove.Outcome) {
        if (o.moved == 0) return
        val sender = vaultMove.deleteRequest(o.ask)
        if (sender != null) _deleteAsk.value = DeleteAsk(sender, o) else _moved.value = o.asked(0, answered = false)
    }

    /**
     * Encrypts and uploads the picked files one after the other — and moves them: once a file's upload is through and
     * verified its original goes ([DriveVaultMove]); a file that did not go up keeps it, the batch stops at the first
     * failure. A name already in the folder asks first (the server cannot tell: it only sees opaque names): a new
     * version of that file, both (a free name), or skip (the original stays).
     */
    fun upload(uris: List<Uri>, names: (Uri) -> String) {
        val r = here() ?: return
        if (network.waitsForWifi()) {
            _message.value = "wifi_wait"
            return
        }
        viewModelScope.launch {
            var last: DResult<*> = DResult.Ok(Unit)
            var outcome = DriveVaultMove.Outcome()
            val taken = r.items.map { it.second }.toMutableList()
            for (uri in uris) {
                var name = DriveLogic.safeName(names(uri)) ?: "file"
                var version: DNode? = null
                val existing = r.items.firstOrNull { (_, have) -> have.equals(name, ignoreCase = true) }
                if (existing != null) {
                    if (existing.first.isDir) {
                        name = DriveLogic.freeName(name, taken)
                    } else {
                        val c = Conflict(name, existing.first, CompletableDeferred())
                        _conflict.value = c
                        when (c.answer.await()) {
                            DriveLogic.ON_CONFLICT_VERSION -> { version = existing.first; name = existing.second }
                            DriveLogic.ON_CONFLICT_RENAME -> name = DriveLogic.freeName(name, taken)
                            else -> continue
                        }
                    }
                }
                taken += name
                _upload.value = name to 0f
                last = lock.busy { session.upload(vaultId, r.folder.node.id, uri, name = name, version = version) { p -> _upload.value = name to p } }
                if (last !is DResult.Ok) break
                // in the vault and verified: now (and only now) the unencrypted original goes
                outcome += lock.busy { vaultMove.remove(uri) }
            }
            _upload.value = null
            last.errorCode()?.let { _message.value = it }
            finishMove(outcome)
            refresh(quiet = true)
        }
    }

    /** "Neue Version hochladen": the picked file becomes the new content of [node] (its name stays). */
    fun uploadVersion(node: DNode, name: String, uri: Uri) {
        val r = here() ?: return
        if (node.isDir) return
        if (network.waitsForWifi()) {
            _message.value = "wifi_wait"
            return
        }
        viewModelScope.launch {
            _upload.value = name to 0f
            val res = lock.busy { session.upload(vaultId, r.folder.node.id, uri, name = name, version = node) { p -> _upload.value = name to p } }
            // the new content is in the vault and verified: its unencrypted original goes too
            val outcome = if (res is DResult.Ok) DriveVaultMove.Outcome() + lock.busy { vaultMove.remove(uri) } else DriveVaultMove.Outcome()
            _upload.value = null
            res.errorCode()?.let { _message.value = it }
            finishMove(outcome)
            refresh(quiet = true)
        }
    }

    // ─── a new key, a new recovery key ──────────────────────────────

    private val _recoveryKey = MutableStateFlow<String?>(null)
    /** A new recovery key to write down (shown once). */
    val recoveryKey: StateFlow<String?> = _recoveryKey.asStateFlow()

    fun recoveryKeyNoted() { _recoveryKey.value = null }

    /** "Tresor-Schlüssel wechseln": a new key for the devices approved now (and, if needed, a new recovery key). */
    fun rotate() = act("done_rotated") {
        session.rotate(vaultId).also { r ->
            if (r is DResult.Ok) {
                r.value.recoveryKey?.let { _recoveryKey.value = it }
                // the new key version's wrap for the offline files (nothing unwrapped is kept)
                session.keepKeys(vaultId)
                refresh(quiet = true)
            }
        }
    }

    /** "Neuen Wiederherstellungsschlüssel erzeugen": the old one stops working. */
    fun newRecovery() = act {
        session.newRecovery(vaultId).also { r -> if (r is DResult.Ok) _recoveryKey.value = r.value }
    }

    // ─── moving inside the vault ────────────────────────────────────

    /** Where to move [node] ([name]): folders of this vault only. */
    data class MovePicker(val node: DNode, val name: String, val folder: DriveLoad<MoveFolder>)

    /** A folder of the vault while choosing: its decrypted name, its folders, whether it is the vault's own folder. */
    data class MoveFolder(val folder: DFolder, val title: String, val dirs: List<Pair<DNode, String>>, val root: Boolean)

    private val _move = MutableStateFlow<MovePicker?>(null)
    val move: StateFlow<MovePicker?> = _move.asStateFlow()

    fun startMove(node: DNode, name: String) {
        val r = here() ?: return
        lock.touch()
        _move.value = MovePicker(node, name, DriveLoad.Loading)
        browseMove(r.folder.node.id)
    }

    /** Shows the folder [id] of the vault in the picker (the node being moved never: nothing goes into itself). */
    fun browseMove(id: Long) {
        val p = _move.value ?: return
        val vault = here()?.vault ?: return
        _move.value = p.copy(folder = DriveLoad.Loading)
        viewModelScope.launch {
            val f = api.folder(id)
            _move.value = _move.value?.copy(
                folder = f.toLoad { folder ->
                    val root = folder.node.id == vault.nodeId
                    val title = (if (root) session.nameOf(vault) else session.nameOf(folder.node)) ?: "…"
                    val dirs = DriveLogic.sortVault(
                        folder.items.filter { it.isDir && it.vaultId == vaultId && it.id != p.node.id }.map { it to (session.nameOf(it) ?: "…") },
                    )
                    MoveFolder(folder, title, dirs, root)
                },
            )
        }
    }

    fun closeMove() { _move.value = null }

    /** Moves the node into the folder shown: only within the vault; its encrypted name stays as it is (the server keeps it). */
    fun moveHere() {
        val p = _move.value ?: return
        val target = (p.folder as? DriveLoad.Ready)?.value?.folder?.node ?: return
        if (target.vaultId != vaultId || target.id == p.node.parentId || target.id == p.node.id) return
        _move.value = null
        act("done_move") { api.move(p.node.id, target.id).also { if (it is DResult.Ok) refresh(quiet = true) } }
    }

    fun createFolder(name: String) {
        val r = here() ?: return
        act("done_folder") { session.createFolder(vaultId, r.folder.node.id, name).also { if (it is DResult.Ok) refresh(quiet = true) } }
    }

    fun rename(node: DNode, name: String) = act("done_rename") { session.rename(node, name).also { if (it is DResult.Ok) refresh(quiet = true) } }

    fun delete(node: DNode) = act("done_trash") { api.delete(node.id).also { if (it is DResult.Ok) refresh(quiet = true) } }

    /**
     * Opens [node] in the viewer: decrypted in memory only (a picture, a text, a PDF in a memory file), a video chunk by
     * chunk while it plays — never a file in plaintext, never another app (Drive plugin 1.22, DriveVaultView).
     */
    fun view(node: DNode, name: String) {
        closeViewer()
        lock.touch()
        val kind = DriveVaultView.kindOf(name)
        val note = when {
            kind == DriveVaultView.Kind.OFFICE -> "office"
            kind == DriveVaultView.Kind.OTHER -> "other"
            DriveVaultView.tooLarge(kind, node.size) -> "too_large"
            else -> null
        }
        if (note != null) {
            _viewer.value = VaultViewer.Note(node, name, note)
            return
        }
        val mine = held
        _viewer.value = VaultViewer.Loading(node, name)
        viewerJob = viewModelScope.launch {
            val shown = lock.busy { load(node, name, kind, mine) }
            if (!mine.closed && _viewer.value?.node?.id == node.id) _viewer.value = shown else (shown as? Closeable)?.close()
        }
    }

    private suspend fun load(node: DNode, name: String, kind: DriveVaultView.Kind, mine: DriveVaultHeld): VaultViewer {
        suspend fun <T> twice(f: suspend () -> DResult<T>): DResult<T> = f().let { r -> if (r is DResult.Failed && r.code == "stale_key") f() else r }
        val limit = DriveVaultView.LIMITS[kind] ?: 0L
        val failed = { r: DResult<*> -> VaultViewer.Note(node, name, if (r is DResult.Offline) "offline" else r.errorCode() ?: "failed") }
        return when (kind) {
            DriveVaultView.Kind.IMAGE -> when (val r = twice { session.decryptToMemory(node, limit) }) {
                is DResult.Ok -> try {
                    val bmp = withContext(Dispatchers.Default) { decodeVaultPicture(r.value) }
                    mine.add(Closeable { bmp.recycle() })
                    VaultViewer.Picture(node, name, bmp.asImageBitmap())
                } catch (e: Exception) {
                    VaultViewer.Note(node, name, "failed")
                } finally {
                    r.value.fill(0)
                }
                else -> failed(r)
            }
            DriveVaultView.Kind.TEXT, DriveVaultView.Kind.MARKDOWN -> when (val r = twice { session.decryptToMemory(node, limit) }) {
                is DResult.Ok -> {
                    val text = String(r.value, Charsets.UTF_8)
                    r.value.fill(0)
                    VaultViewer.Text(node, name, text, kind == DriveVaultView.Kind.MARKDOWN)
                }
                else -> failed(r)
            }
            DriveVaultView.Kind.PDF -> when (val r = twice { session.decryptToMemFile(node, limit) }) {
                is DResult.Ok -> try {
                    val pdf = withContext(Dispatchers.IO) { DrivePdf.fromMemory(r.value) }
                    mine.add(pdf)
                    VaultViewer.Pdf(node, name, pdf)
                } catch (e: Exception) {
                    r.value.close()
                    VaultViewer.Note(node, name, "failed")
                }
                else -> failed(r)
            }
            DriveVaultView.Kind.VIDEO -> when (val r = twice { session.fetchCipher(node) { p -> if (_viewer.value is VaultViewer.Loading && !mine.closed) _viewer.value = VaultViewer.Loading(node, name, p) } }) {
                is DResult.Ok -> {
                    mine.add(r.value)
                    VaultViewer.Video(node, name, r.value)
                }
                else -> failed(r)
            }
            else -> VaultViewer.Note(node, name, "other")
        }
    }

    /** The player of [video] (the one already playing it, else a new one); null once the viewer closed. Main thread. */
    internal fun videoPlayer(video: VaultViewer.Video): Player? =
        if (_viewer.value !== video) null else videoPlayers.get(video.cipher, held) { vaultVideoPlayer(context, video.cipher) }

    /** Closes the viewer: what it held is released (bitmaps recycled, the PDF's memory emptied, the ciphertext deleted). */
    fun closeViewer() {
        viewerJob?.cancel()
        viewerJob = null
        held.close()
        held = DriveVaultHeld()
        _viewer.value = null
        session.wipeViewed()
    }

    /**
     * "Mit anderer App öffnen" (asked first, [OpenWithDialog]): [node] decrypted into a memory file and offered to the app
     * the person picks as a content URI of [DriveVaultShares] — no file in plaintext. It goes when the person comes
     * back ([backFromOtherApp]) or the vaults lock. [then] gets the URI and the MIME type, or null with a message.
     */
    fun openWith(node: DNode, name: String, then: (android.net.Uri, String) -> Unit) {
        lock.touch()
        viewModelScope.launch {
            _busy.value = true
            val r = lock.busy {
                session.decryptToMemFile(node, DriveVaultView.SHARE_LIMIT).let { x ->
                    if (x is DResult.Failed && x.code == "stale_key") session.decryptToMemFile(node, DriveVaultView.SHARE_LIMIT) else x
                }
            }
            _busy.value = false
            when (r) {
                is DResult.Ok -> {
                    if (!session.unlocked(vaultId)) { r.value.close(); return@launch }
                    val mime = DriveVaultView.mimeOf(name)
                    val uri = DriveVaultShares.add(context.packageName, r.value, DriveLogic.safeName(name) ?: "file", mime)
                    lock.away()
                    then(uri, mime)
                }
                else -> _message.value = if (r is DResult.Failed && r.code == "too_large") "vault_share_too_large" else r.errorCode() ?: "failed"
            }
        }
    }

    /** Back from the other app: what it was given goes (it keeps only what it already read). */
    fun backFromOtherApp() {
        if (!DriveVaultShares.isEmpty()) DriveVaultShares.clear()
    }

    override fun onCleared() {
        // the viewer's plaintext and what another app was given go with the page
        closeViewer()
        DriveVaultShares.clear()
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
    val conflict by viewModel.conflict.collectAsStateWithLifecycle()
    val move by viewModel.move.collectAsStateWithLifecycle()
    val recoveryKey by viewModel.recoveryKey.collectAsStateWithLifecycle()
    val marks by viewModel.offlineMarks.collectAsStateWithLifecycle()
    val canOffline by viewModel.canOffline.collectAsStateWithLifecycle()
    // "Nur auf diesem Gerät" (Drive 1.32)
    val canLocal by viewModel.canLocal.collectAsStateWithLifecycle()
    val localFolders by viewModel.localStore.folders.collectAsStateWithLifecycle()
    val localJobs by viewModel.localJobs.progress.collectAsStateWithLifecycle()
    val localAsk by viewModel.localAsk.collectAsStateWithLifecycle()
    var placeholderFor by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    var forgetFor by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    var backFor by remember { mutableStateOf<Triple<Long, String, Boolean>?>(null) }
    var deleteLocalFolderFor by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deletingLocal by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    val localAuth = rememberGcDeviceAuth()
    val localAuthTitle = stringResource(R.string.drive_vault_local_auth)
    // a job that proves this device (move, way back, delete here) reads the device key: the person confirms first
    val confirmThen: (() -> Unit) -> Unit = { go ->
        if (viewModel.session.deviceKeyNeedsAuth() && localAuth.available()) {
            localAuth.authenticate(localAuthTitle, null) { r -> if (r == DeviceAuthResult.SUCCESS) go() }
        } else {
            go()
        }
    }
    // The whole page, locked or not, with the viewer over it and its dialogs: no screenshot, no picture in the recent apps.
    SecureScreenEffect()
    val copies by viewModel.offlineStore.copies.collectAsStateWithLifecycle()
    var keysOpen by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var versionFor by remember { mutableStateOf<Pair<DNode, String>?>(null) }
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
        viewModel.touch()
        // back from "Mit anderer App öffnen": the memory file it was given goes
        viewModel.backFromOtherApp()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, vaultLocalText(context, it) ?: vaultViewText(context, it) ?: driveText(context, it), Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.upload(uris) { u -> displayName(context, u) }
    }
    // into a folder kept only here: encrypted on this phone, nothing goes to the server
    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadLocal(uris) { u -> displayName(context, u) }
    }
    // "In Tresor verschieben": Android asks before gallery originals go for good (one dialog for the batch)
    val deleteAsk by viewModel.deleteAsk.collectAsStateWithLifecycle()
    val askDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        viewModel.deleteAnswered(r.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(deleteAsk) {
        val a = deleteAsk ?: return@LaunchedEffect
        if (!a.take()) return@LaunchedEffect
        viewModel.away()
        try {
            askDelete.launch(IntentSenderRequest.Builder(a.sender).build())
        } catch (e: android.content.ActivityNotFoundException) {
            viewModel.deleteAnswered(false)
        }
    }
    val pickVersion = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = versionFor
        versionFor = null
        if (uri != null && target != null) viewModel.uploadVersion(target.first, target.second, uri)
    }
    // Drive plugin 1.22: a file opens in the viewer of this app, decrypted in memory only
    val open: (DNode, String) -> Unit = { n, name -> viewModel.view(n, name) }
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    var openWithFor by remember { mutableStateOf<Pair<DNode, String>?>(null) }
    val openWith: (DNode, String) -> Unit = { n, name ->
        viewModel.openWith(n, name) { uri, mime ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(Intent.createChooser(intent, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(context, R.string.drive_open_none, Toast.LENGTH_LONG).show()
            }
        }
    }
    val ready = ui as? DriveVaultViewModel.Ui.Ready
    val barTitle = ready?.title ?: (ui as? DriveVaultViewModel.Ui.Gate)?.title ?: stringResource(R.string.drive_vault)
    val selected by viewModel.bulk.selected.collectAsStateWithLifecycle()
    val running by viewModel.ops.running.collectAsStateWithLifecycle()
    val bulkBusy by viewModel.bulk.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var moreOpen by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty() && ready != null
    BackHandler(enabled = selecting) { viewModel.bulk.clear() }
    val vaultItems = ready?.items.orEmpty()
    val chosen = remember(selected, vaultItems) { vaultItems.filter { it.first.id in selected } }
    val where = DriveBulk.Where(writable = { ready?.folder?.writable == true }, vault = true)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ticking changes the title row only (as high either way)
            SelectionSwap(
                selecting,
                bar = {
                    GcSubpageBar(barTitle, onBack = onBack, backDescription = stringResource(R.string.drive_back)) {
                        if (ready != null) {
                            GcIconButton(GcIcons.Lock, contentDescription = stringResource(R.string.drive_vault_lock_now), onClick = viewModel::lockNow, tint = MaterialTheme.colorScheme.onBackground)
                            // the requests of other devices need the server (without a connection: nothing to answer)
                            if (!ready.offline) GcIconButton(GcIcons.Users, contentDescription = stringResource(R.string.drive_vault_requests), onClick = viewModel::openRequests, tint = MaterialTheme.colorScheme.onBackground)
                            // a new key and a new recovery key: the write right (the server's rule)
                            if (ready.folder.writable) {
                                Box {
                                    GcIconButton(GcIcons.Key, contentDescription = stringResource(R.string.drive_vault_keys), onClick = { keysOpen = true }, tint = MaterialTheme.colorScheme.onBackground)
                                    DropdownMenu(expanded = keysOpen, onDismissRequest = { keysOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.drive_vault_rotate)) },
                                            leadingIcon = { Icon(GcIcons.Refresh, contentDescription = null) },
                                            onClick = { keysOpen = false; confirmRotate = true },
                                            enabled = !busy,
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.drive_vault_new_recovery)) },
                                            leadingIcon = { Icon(GcIcons.Lifebuoy, contentDescription = null) },
                                            onClick = { keysOpen = false; confirmRecovery = true },
                                            enabled = !busy,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                selection = {
                    SelectionHead(
                        count = chosen.size,
                        line = summaryText(DriveBulk.summary(chosen.map { it.first })),
                        allSelected = DriveBulk.allSelected(selected, vaultItems.map { it.first.id }),
                        onClose = viewModel.bulk::clear,
                        onAll = { viewModel.touch(); viewModel.bulk.allOrNone() },
                    )
                },
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = ui) {
                    DriveVaultViewModel.Ui.Loading -> DriveLoading()
                    is DriveVaultViewModel.Ui.Error -> DriveErrorBox(s.code, onRetry = { viewModel.refresh() })
                    is DriveVaultViewModel.Ui.Gate -> VaultGate(s.noScreenLock, onConfirmed = viewModel::confirmed)
                    is DriveVaultViewModel.Ui.Locked -> LockedVault(s.access, busy, safety = viewModel.session.safetyNumber(), onAsk = viewModel::ask, onCheck = { viewModel.refresh() }, onRecover = viewModel::recover)
                    is DriveVaultViewModel.Ui.Ready -> if (s.offline && s.local == null) {
                        VaultOfflinePage(s, onOpen = { n, name -> viewModel.touch(); open(n, name) }, onMore = { n, name -> menuFor = n to name }, onRetry = { viewModel.refresh() })
                    } else if (s.local != null) {
                        // a folder kept only on this phone (A5): its content from here, also without a connection
                        val lf = s.local
                        val now = System.currentTimeMillis()
                        val canUndo = com.gatecontrol.android.drive.vault.DriveVaultLocalLogic.canUndo(lf, now)
                        val top = s.folder.node.id == lf.nodeId
                        VaultLocalCard(
                            lf,
                            undoUntil = if (canUndo) lf.purgeAt else null,
                            onBack = if (top && !lf.revoked && !s.offline && localJobs[lf.nodeId]?.running != true) ({ backFor = Triple(lf.nodeId, s.title, true) }) else null,
                            onUndo = if (top && canUndo && !s.offline) ({ viewModel.undoLocal(lf.nodeId) }) else null,
                        )
                        localJobs[lf.nodeId]?.let { p ->
                            VaultLocalJobCard(p, onCancel = { viewModel.cancelLocal(p.nodeId) }, onRetry = { confirmThen { viewModel.retryLocal(p) } }, onClose = { viewModel.dismissLocal(p.nodeId) })
                        }
                        upload?.let { (name, p) ->
                            GcCard {
                                Text(stringResource(R.string.drive_vault_local_encrypting, name), style = MaterialTheme.typography.bodyMedium)
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (s.items.isEmpty()) {
                            Text(stringResource(R.string.drive_empty_folder), color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
                        } else {
                            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                s.items.forEachIndexed { i, (n, name) ->
                                    if (i > 0) GcRowDivider()
                                    NodeRow(
                                        node = n,
                                        title = name,
                                        description = nodeLine(n),
                                        onClick = {
                                            viewModel.touch()
                                            if (n.isDir) onFolder(viewModel.vaultId, n.id) else open(n, name)
                                        },
                                        onMore = { menuFor = n to name },
                                    )
                                }
                            }
                        }
                    } else {
                        if (s.vault.pending > 0) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Users) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(pluralStringResource(R.plurals.drive_vault_requests_banner, s.vault.pending, s.vault.pending), style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = viewModel::openRequests) { Text(stringResource(R.string.drive_vault_requests)) }
                                }
                            }
                        }
                        if (s.vault.needsRotation) {
                            GcBanner(tone = GcTone.Warn, icon = GcIcons.Key) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        stringResource(if (s.folder.writable) R.string.drive_vault_rotation_now else R.string.drive_vault_rotation_read),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (s.folder.writable) {
                                        if (busy) {
                                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                        } else {
                                            TextButton(onClick = { confirmRotate = true }) { Text(stringResource(R.string.drive_vault_rotate)) }
                                        }
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Icon(GcIcons.Lock, contentDescription = null, tint = GateControlTheme.extraColors.muted, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.drive_vault_note), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted)
                        }
                        val vaultCopies = remember(copies, s.vault.id) { DriveVaultOfflineLogic.ofVault(copies.values, s.vault.id) }
                        if (canOffline) {
                            val here = s.folder.node
                            VaultOfflineCard(
                                whole = here.id == s.vault.nodeId,
                                mark = DriveVaultOfflineLogic.markOf(here.id, s.ancestors.dropLast(1), s.vault.nodeId, marks),
                                summary = DriveVaultOfflineLogic.summary(DriveVaultOfflineLogic.below(vaultCopies, s.vault.id, s.vault.nodeId, here.id)),
                                onSet = { on -> viewModel.setOffline(here, on) },
                            )
                        }
                        BulkBusyCard(bulkBusy, viewModel.bulk::cancelBusy)
                        // a folder going onto this phone or back (A4)
                        localJobs.values.filter { it.vaultId == s.vault.id && !it.finished && localFolders[it.nodeId]?.let { f -> f.nodeId == s.folder.node.id } != true }.forEach { p ->
                            VaultLocalJobCard(p, onCancel = { viewModel.cancelLocal(p.nodeId) }, onRetry = { confirmThen { viewModel.retryLocal(p) } }, onClose = { viewModel.dismissLocal(p.nodeId) })
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
                                    val lp = n.local
                                    val hereFolder = localFolders[n.id]
                                    val job = localJobs[n.id]
                                    val movingHere = (job != null && job.kind == com.gatecontrol.android.drive.vault.DriveVaultLocalJobs.Kind.MOVE && !job.finished) ||
                                        hereFolder?.state == com.gatecontrol.android.drive.vault.DriveVaultLocalFolder.State.MOVING
                                    when {
                                        // another device's folder (A6): greyed out, never opened here
                                        lp != null && hereFolder == null && lp.thisDevice != true && !movingHere ->
                                            VaultPlaceholderRow(n, name, lp) { viewModel.touch(); placeholderFor = n to name }
                                        // coming onto this phone right now (A4)
                                        movingHere -> NodeRow(
                                            node = n,
                                            title = name,
                                            description = stringResource(R.string.drive_vault_local_row_moving),
                                            onClick = null,
                                            icon = { Box(Modifier.alpha(0.6f)) { VaultLocalIcon(n, here = true) } },
                                            trailing = job?.let { p -> ({ Text("${(p.step.ordinal + 1)}/3", style = MaterialTheme.typography.labelLarge, color = GateControlTheme.extraColors.blue) }) },
                                        )
                                        // kept only on this phone (A1): "Nur hier", opens from here
                                        hereFolder != null || lp?.thisDevice == true -> NodeRow(
                                            node = n,
                                            title = name,
                                            description = when {
                                                hereFolder?.state == com.gatecontrol.android.drive.vault.DriveVaultLocalFolder.State.RETURNING || lp?.returning == true -> stringResource(R.string.drive_vault_local_row_returning)
                                                hereFolder != null -> pluralStringResource(R.plurals.drive_vault_local_row_line, hereFolder.files.size, hereFolder.files.size, DriveLogic.size(hereFolder.bytes))
                                                else -> nodeLine(n)
                                            },
                                            onClick = { viewModel.touch(); if (!selecting) onFolder(viewModel.vaultId, n.id) },
                                            icon = { VaultLocalIcon(n, here = true) },
                                            trailing = { VaultLocalChip() },
                                            onMore = { menuFor = n to name },
                                        )
                                        else -> {
                                            val mark = DriveVaultOfflineLogic.markOf(n.id, s.ancestors, s.vault.nodeId, marks)
                                            val row = if (n.isDir) DriveVaultOfflineLogic.RowState.NONE else DriveVaultOfflineLogic.rowState(copies[n.id]?.takeIf { it.vaultId == n.vaultId })
                                            val inside = if (n.isDir && mark == DriveVaultOfflineLogic.Mark.NONE) vaultCopies.count { it.hasFile && n.id in it.parents } else 0
                                            NodeRow(
                                                node = n,
                                                title = name,
                                                description = vaultRowLine(n, copies[n.id]?.takeIf { it.vaultId == n.vaultId }, inside),
                                                trailing = when {
                                                    n.isDir && mark != DriveVaultOfflineLogic.Mark.NONE -> ({ VaultOfflineChip(loading = false) })
                                                    row == DriveVaultOfflineLogic.RowState.OFFLINE -> ({ VaultOfflineChip(loading = false) })
                                                    row == DriveVaultOfflineLogic.RowState.LOADING -> ({ VaultOfflineChip(loading = true) })
                                                    else -> null
                                                },
                                                onClick = {
                                                    viewModel.touch()
                                                    when {
                                                        selecting -> viewModel.bulk.toggle(n)
                                                        n.isDir -> onFolder(viewModel.vaultId, n.id)
                                                        else -> open(n, name)
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    viewModel.touch()
                                                    if (selecting) viewModel.bulk.toggle(n) else viewModel.bulk.select(n)
                                                },
                                                selecting = selecting,
                                                selected = n.id in selected,
                                                onMore = { menuFor = n to name },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (vaultCopies.isNotEmpty()) {
                            Text(stringResource(R.string.drive_vault_offline_note), style = MaterialTheme.typography.bodySmall, color = GateControlTheme.extraColors.muted, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
            if (selecting) {
                SelectionActionBar(
                    listOf(
                        BarAction(GcIcons.Share, stringResource(R.string.drive_bar_send), viewModel.bulk.can(DriveBulk.Action.SEND, chosen.map { it.first }, where)) {},
                        BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), viewModel.bulk.can(DriveBulk.Action.MOVE, chosen.map { it.first }, where)) { viewModel.touch(); viewModel.bulk.openTarget(DriveBulk.Mode.MOVE) },
                        BarAction(GcIcons.Copy, stringResource(R.string.drive_bar_copy), viewModel.bulk.can(DriveBulk.Action.COPY, chosen.map { it.first }, where)) { viewModel.touch(); viewModel.bulk.openTarget(DriveBulk.Mode.COPY) },
                        BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), viewModel.bulk.can(DriveBulk.Action.DELETE, chosen.map { it.first }, where), danger = true) { viewModel.touch(); viewModel.bulk.delete() },
                        BarAction(GcIcons.MoreHorizontal, stringResource(R.string.drive_bar_more), DriveBulk.Can.YES) { moreOpen = true },
                    ),
                )
            }
        }
        if (ready != null && !selecting && !ready.offline) {
            JobsFab(
                jobs = running,
                onCancel = { id -> scope.launch { viewModel.ops.cancel(id) } },
                onAdd = if (ready.folder.writable && upload == null) ({ addOpen = true }) else null,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = if (selecting) 88.dp else 84.dp))
        // the viewer of a file, over everything of the vault page (Drive plugin 1.22)
        viewer?.let { v ->
            DriveVaultViewerScreen(
                v,
                onClose = viewModel::closeViewer,
                onUse = viewModel::touch,
                onLock = viewModel::lockNow,
                onOpenWith = { openWith(v.node, v.name) },
                videoPlayer = viewModel::videoPlayer,
            )
        }
    }
    DriveBulkHost(viewModel.bulk, snackbar)
    // what became of the originals of a batch put into the vault (in the page's scope: GcShowOnce)
    val moved by viewModel.moved.collectAsStateWithLifecycle()
    // a move onto this phone through: "Liegt jetzt nur auf diesem Handy." with "Rückgängig" (24 h)
    val localDone by viewModel.localDone.collectAsStateWithLifecycle()
    val doneText = stringResource(R.string.drive_vault_local_done)
    val undoText = stringResource(R.string.drive_vault_local_undo)
    GcShowOnce(localDone, consume = viewModel::localDoneShown) { id ->
        snackbar.currentSnackbarData?.dismiss()
        val r = snackbar.showSnackbar(doneText, actionLabel = undoText, withDismissAction = true, duration = SnackbarDuration.Long)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoLocal(id)
    }
    GcShowOnce(moved, consume = viewModel::movedShown) { o ->
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(vaultMoveText(context, o), withDismissAction = true, duration = SnackbarDuration.Long)
    }

    if (addOpen) {
        ModalBottomSheet(onDismissRequest = { addOpen = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                GcListRow(
                    title = stringResource(R.string.drive_new_folder),
                    onClick = { addOpen = false; newFolder = true },
                    leading = { Icon(GcIcons.FolderPlus, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                )
                if (ready?.local != null) {
                    // kept only here: encrypted on this phone, never sent
                    GcListRow(
                        title = stringResource(R.string.drive_vault_local_add),
                        description = stringResource(R.string.drive_vault_local_add_desc),
                        onClick = { addOpen = false; viewModel.away(); pickLocal.launch(arrayOf("*/*")) },
                        leading = { Icon(GcIcons.Smartphone, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                    )
                } else {
                    // a move: the unencrypted original goes once the file is in the vault (said before, too)
                    GcListRow(
                        title = stringResource(R.string.drive_vault_move_in),
                        description = stringResource(R.string.drive_vault_move_in_desc),
                        onClick = { addOpen = false; viewModel.away(); pick.launch(arrayOf("*/*")) },
                        leading = { Icon(GcIcons.Upload, contentDescription = null, tint = GateControlTheme.extraColors.muted) },
                    )
                }
            }
        }
    }
    /** ⋯ of a row ([fromRow], with the everyday actions as icons) or "Mehr" of the selection: what a vault allows. */
    val moreSheet: @Composable (List<Pair<DNode, String>>, Boolean, () -> Unit) -> Unit = { ns, fromRow, close ->
        val writable = ready?.folder?.writable == true
        val nodes = ns.map { it.first }
        val one = ns.singleOrNull()
        val bulk = viewModel.bulk
        val can: (DriveBulk.Action) -> DriveBulk.Can = { a -> bulk.can(a, nodes, where) }
        // a folder kept only here (A5): its content is changed on this phone only
        val inLocal = ready?.local != null
        val hereFolder = one?.first?.let { localFolders[it.id] }
        // without a connection only what works on this phone: looking at a file (and the explicit way out)
        val noNet = ready?.offline == true && !inLocal
        val quick = if (!fromRow || one == null || noNet) emptyList() else if (inLocal) listOf(
            BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), if (writable) DriveBulk.Can.YES else DriveBulk.Can.no(DriveBulk.Why.READ_ONLY), danger = true) { deletingLocal = one },
        ) else if (hereFolder != null) listOf(
            BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), can(DriveBulk.Action.MOVE)) { bulk.openTarget(DriveBulk.Mode.MOVE, nodes) },
            BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), DriveBulk.Can.YES, danger = true) { deleteLocalFolderFor = one.first.id to one.second },
        ) else listOf(
            BarAction(GcIcons.FolderMove, stringResource(R.string.drive_bar_move), can(DriveBulk.Action.MOVE)) { bulk.openTarget(DriveBulk.Mode.MOVE, nodes) },
            BarAction(GcIcons.Copy, stringResource(R.string.drive_bar_copy), can(DriveBulk.Action.COPY)) { bulk.openTarget(DriveBulk.Mode.COPY, nodes) },
            BarAction(GcIcons.Trash, stringResource(R.string.drive_bar_delete), can(DriveBulk.Action.DELETE), danger = true) { deleting = one },
        )
        val multi = if (noNet || inLocal) emptyList() else listOf(
            MoreItem(GcIcons.Download, stringResource(R.string.drive_more_save), stringResource(R.string.drive_more_save_desc), can(DriveBulk.Action.SAVE)) {},
            MoreItem(GcIcons.Archive, stringResource(R.string.drive_more_zip), stringResource(R.string.drive_more_zip_desc), can(DriveBulk.Action.ZIP)) {},
            MoreItem(GcIcons.Link, stringResource(R.string.drive_share_link), null, can(DriveBulk.Action.LINK)) {},
        )
        val single = buildList {
            if (one != null && !one.first.isDir) {
                add(MoreItem(GcIcons.Eye, stringResource(R.string.drive_vault_view)) { open(one.first, one.second) })
                // the explicit way out of the vault, asked first
                add(MoreItem(GcIcons.External, stringResource(R.string.drive_vault_open_with)) { openWithFor = one })
            }
            if (noNet) return@buildList
            if (inLocal) {
                add(MoreItem(GcIcons.Edit, stringResource(R.string.drive_rename), can = if (writable) DriveBulk.Can.YES else DriveBulk.Can.no(DriveBulk.Why.READ_ONLY)) { one?.let { renaming = it } })
                return@buildList
            }
            add(MoreItem(GcIcons.Edit, stringResource(R.string.drive_rename), can = can(DriveBulk.Action.RENAME)) { one?.let { renaming = it } })
            if (one == null || !one.first.isDir) {
                add(
                    MoreItem(
                        GcIcons.Upload,
                        stringResource(R.string.drive_vault_new_version),
                        stringResource(R.string.drive_vault_new_version_desc),
                        can = when {
                            one == null -> DriveBulk.Can.no(DriveBulk.Why.ONLY_ONE)
                            !writable -> DriveBulk.Can.no(DriveBulk.Why.READ_ONLY)
                            else -> DriveBulk.Can.YES
                        },
                    ) {
                        if (one != null && upload == null) { versionFor = one; viewModel.away(); pickVersion.launch(arrayOf("*/*")) }
                    },
                )
            }
        }
        val header: (@Composable () -> Unit)? = if (fromRow && one != null && ready != null && !inLocal && one.first.isDir && (canOffline || canLocal || hereFolder != null)) {
            {
                // "Wo liegt dieser Ordner?" (A2, A8): only on the server, also offline here, or only on this phone
                val n = one.first
                val mark = DriveVaultOfflineLogic.markOf(n.id, ready.ancestors, ready.vault.nodeId, marks)
                val current = when {
                    hereFolder != null -> VaultWhere.HERE
                    mark != DriveVaultOfflineLogic.Mark.NONE -> VaultWhere.EXTRA
                    else -> VaultWhere.SERVER
                }
                VaultWhereChoice(
                    current = current,
                    localOffered = canLocal && localJobs[n.id] == null,
                    online = !ready.offline,
                    fixedExtra = mark == DriveVaultOfflineLogic.Mark.FOLDER || mark == DriveVaultOfflineLogic.Mark.VAULT,
                    since = hereFolder?.let { localDate(context, it.since) },
                ) { w ->
                    if (w == current) return@VaultWhereChoice
                    close()
                    when {
                        current == VaultWhere.HERE -> backFor = Triple(n.id, one.second, w == VaultWhere.EXTRA)
                        w == VaultWhere.HERE -> viewModel.askLocal(n, one.second)
                        else -> viewModel.setOffline(n, w == VaultWhere.EXTRA)
                    }
                }
            }
        } else if (fromRow && one != null && ready != null && !inLocal && (canOffline || ready.offline)) {
            {
                VaultOfflineSwitch(
                    mark = DriveVaultOfflineLogic.markOf(one.first.id, ready.ancestors, ready.vault.nodeId, marks),
                    online = !ready.offline,
                    onSet = { on -> viewModel.setOffline(one.first, on) },
                )
            }
        } else null
        BulkMoreSheet(ns.size, one?.second.takeIf { fromRow }, summaryText(DriveBulk.summary(nodes)), quick, multi, single, emptyList(), onDismiss = close, header = header)
    }
    menuFor?.let { m -> moreSheet(listOf(m), true) { menuFor = null } }
    if (moreOpen && chosen.isNotEmpty()) moreSheet(chosen, false) { moreOpen = false }
    openWithFor?.let { (n, name) ->
        OpenWithDialog(onConfirm = { openWithFor = null; openWith(n, name) }, onDismiss = { openWithFor = null })
    }
    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.drive_new_folder),
            initial = "",
            confirm = stringResource(R.string.drive_create),
            onConfirm = { newFolder = false; if (ready?.local != null) viewModel.createLocalFolder(it) else viewModel.createFolder(it) },
            onDismiss = { newFolder = false },
        )
    }
    renaming?.let { (n, name) ->
        NameDialog(
            title = stringResource(R.string.drive_rename),
            initial = name,
            confirm = stringResource(R.string.drive_save),
            onConfirm = { renaming = null; if (it != name) { if (ready?.local != null) viewModel.renameLocal(n, it) else viewModel.rename(n, it) } },
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
    conflict?.let { c ->
        AlertDialog(
            onDismissRequest = { viewModel.resolve(c, null) },
            title = { Text(stringResource(R.string.drive_conflict_title, c.name)) },
            text = { Text(stringResource(R.string.drive_conflict_text)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { viewModel.resolve(c, null) }) { Text(stringResource(R.string.drive_conflict_skip)) }
                    TextButton(onClick = { viewModel.resolve(c, DriveLogic.ON_CONFLICT_RENAME) }) { Text(stringResource(R.string.drive_conflict_keep)) }
                    TextButton(onClick = { viewModel.resolve(c, DriveLogic.ON_CONFLICT_VERSION) }) { Text(stringResource(R.string.drive_conflict_version)) }
                }
            },
        )
    }
    move?.let { p -> VaultMovePicker(p, onBrowse = viewModel::browseMove, onMove = viewModel::moveHere, onDismiss = viewModel::closeMove) }
    // ─── "Nur auf diesem Gerät" ───
    localAsk?.let { a ->
        VaultLocalAskDialog(
            name = a.name,
            start = (a.start as? DriveLoad.Ready)?.value,
            free = a.free,
            onConfirm = { confirmThen { viewModel.confirmLocal() } },
            onDismiss = viewModel::dismissLocalAsk,
        )
    }
    placeholderFor?.let { (n, name) ->
        val l = n.local
        if (l == null) {
            placeholderFor = null
        } else {
            VaultPlaceholderSheet(
                title = name,
                local = l,
                onForget = if (l.mine && l.state == com.gatecontrol.android.drive.DVaultLocal.STATE_LOCAL && l.undo != true && ready?.offline != true) ({ placeholderFor = null; forgetFor = n to name }) else null,
                onDismiss = { placeholderFor = null },
            )
        }
    }
    forgetFor?.let { (n, name) ->
        VaultForgetDialog(name, n.local?.let { localDeviceName(it) } ?: "", onConfirm = { forgetFor = null; viewModel.forgetPlaceholder(n) }, onDismiss = { forgetFor = null })
    }
    backFor?.let { (id, name, keep) ->
        VaultLocalBackDialog(name, keep, onConfirm = { k -> backFor = null; confirmThen { viewModel.backToServer(id, name, k) } }, onDismiss = { backFor = null })
    }
    deleteLocalFolderFor?.let { (id, name) ->
        VaultLocalDeleteDialog(name, onConfirm = { deleteLocalFolderFor = null; confirmThen { viewModel.deleteLocalFolder(id) } }, onDismiss = { deleteLocalFolderFor = null })
    }
    deletingLocal?.let { (n, name) ->
        VaultLocalDeleteDialog(name, onConfirm = { deletingLocal = null; viewModel.deleteLocal(n) }, onDismiss = { deletingLocal = null })
    }
    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.drive_vault_rotate_title)) },
            text = { Text(stringResource(R.string.drive_vault_rotate_text)) },
            confirmButton = { TextButton(onClick = { confirmRotate = false; viewModel.rotate() }) { Text(stringResource(R.string.drive_vault_rotate)) } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    if (confirmRecovery) {
        AlertDialog(
            onDismissRequest = { confirmRecovery = false },
            title = { Text(stringResource(R.string.drive_vault_new_recovery)) },
            text = { Text(stringResource(R.string.drive_vault_new_recovery_text)) },
            confirmButton = { TextButton(onClick = { confirmRecovery = false; viewModel.newRecovery() }) { Text(stringResource(R.string.drive_vault_new_recovery_go)) } },
            dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text(stringResource(R.string.drive_cancel)) } },
        )
    }
    recoveryKey?.let { key -> RecoveryKeyDialog(key, onDone = viewModel::recoveryKeyNoted, replaced = true) }
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

/** Where to move a node of the vault: its folders only (the names decrypted here), never into itself. */
@Composable
private fun VaultMovePicker(p: DriveVaultViewModel.MovePicker, onBrowse: (Long) -> Unit, onMove: () -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    val ready = (p.folder as? DriveLoad.Ready)?.value
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drive_move_title, p.name)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (val f = p.folder) {
                    DriveLoad.Loading -> DriveLoading()
                    is DriveLoad.Error -> DriveErrorBox(f.code, null)
                    is DriveLoad.Ready -> {
                        val here = f.value
                        GcListRow(
                            title = here.title,
                            leading = { Icon(GcIcons.Vault, contentDescription = null, tint = x.warn, modifier = Modifier.size(22.dp)) },
                        )
                        // up: never above the vault's own folder
                        val up = here.folder.node.parentId
                        if (!here.root && up != null) {
                            GcListRow(title = "‹ " + stringResource(R.string.drive_back), onClick = { onBrowse(up) }, titleColor = x.accentText)
                        }
                        here.dirs.forEach { (d, name) ->
                            GcListRow(title = name, onClick = { onBrowse(d.id) }, leading = { NodeIcon(d, 32) }, trailing = { GcChevron() })
                        }
                    }
                }
            }
        },
        confirmButton = {
            val target = ready?.folder?.node
            TextButton(onClick = onMove, enabled = target != null && target.id != p.node.parentId && target.id != p.node.id) {
                Text(stringResource(R.string.drive_move_here))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/**
 * Drive 1.11: the vault is locked. It opens after the person confirms with fingerprint, face or the phone's PIN (asked at
 * once when the page opens); without a screen lock it cannot be opened at all.
 */
@Composable
private fun VaultGate(noScreenLock: Boolean, onConfirmed: () -> Unit) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val auth = rememberGcDeviceAuth()
    val title = stringResource(R.string.drive_vault_gate_prompt)
    var failed by remember { mutableStateOf(false) }
    val ask: () -> Unit = {
        failed = false
        auth.authenticate(title, null) { r ->
            when (r) {
                DeviceAuthResult.SUCCESS -> onConfirmed()
                DeviceAuthResult.CANCELLED -> Unit
                DeviceAuthResult.FAILED, DeviceAuthResult.UNAVAILABLE -> failed = true
            }
        }
    }
    // asked once when the locked vault shows (not again after cancelling)
    var asked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(noScreenLock) {
        if (!noScreenLock && !asked && auth.available()) {
            asked = true
            ask()
        }
    }
    GcCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GcIcons.Lock, contentDescription = null, tint = x.warn, modifier = Modifier.padding(end = 12.dp))
            Text(stringResource(R.string.drive_vault_gate_title), style = MaterialTheme.typography.titleMedium)
        }
        if (noScreenLock) {
            Text(stringResource(R.string.drive_vault_no_lock), style = MaterialTheme.typography.bodyMedium, color = x.muted)
            GcOutlineButton(stringResource(R.string.drive_vault_no_lock_settings), onClick = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, icon = GcIcons.Settings)
        } else {
            Text(stringResource(R.string.drive_vault_gate_text), style = MaterialTheme.typography.bodyMedium, color = x.muted)
            if (failed) Text(stringResource(R.string.drive_vault_gate_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            GcPrimaryButton(stringResource(R.string.drive_vault_gate_unlock), onClick = ask, icon = GcIcons.Fingerprint)
        }
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
internal fun RecoveryKeyDialog(key: String, onDone: () -> Unit, replaced: Boolean = false) {
    val context = LocalContext.current
    var noted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.drive_vault_recovery_title)) },
        text = {
            // The dialog's own window: it also shows over a folder's page, which is not secure.
            SecureScreenEffect()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.drive_vault_recovery_text), style = MaterialTheme.typography.bodyMedium)
                // a new key of an existing vault: the one written down before no longer opens it
                if (replaced) Text(stringResource(R.string.drive_vault_recovery_replaced), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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

/**
 * "Neuer Tresor" (Drive 1.11): its name (encrypted on this phone) and who may open it — "Nur ich" (Meine Dateien), "Mit
 * der Familie teilen" (with a family seat) or a team folder with the write right. It lies in that area's top folder and
 * shows in the menu under "Tresore"; then the recovery key.
 */
@Composable
internal fun NewVaultDialog(state: DriveFolderViewModel.NewVault, onCreate: (String, com.gatecontrol.android.drive.DArea) -> Unit, onDismiss: () -> Unit) {
    val x = GateControlTheme.extraColors
    var value by remember { mutableStateOf("") }
    var areaId by remember { mutableStateOf<String?>(null) }
    val areas = state.areas
    val chosen = areas?.firstOrNull { it.id == areaId } ?: areas?.firstOrNull()
    val valid = DriveLogic.validName(value)
    val busy = state.busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(GcIcons.Lock, contentDescription = null, tint = x.warn) },
        title = { Text(stringResource(R.string.drive_vault_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GcTextField(
                    value = value,
                    onValueChange = { value = it.take(255) },
                    label = stringResource(R.string.drive_name),
                    error = if (value.isNotEmpty() && !valid) stringResource(R.string.drive_name_invalid) else null,
                )
                Text(stringResource(R.string.drive_vault_who), style = MaterialTheme.typography.labelLarge, color = x.muted)
                when {
                    areas == null -> DriveLoading()
                    areas.isEmpty() -> Text(stringResource(R.string.drive_vault_no_area), style = MaterialTheme.typography.bodyMedium, color = x.muted)
                    else -> Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        areas.forEach { a ->
                            VaultAreaChoice(a, selected = a.id == chosen?.id, enabled = !busy, onSelect = { areaId = a.id })
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(x.panel2, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(GcIcons.Fingerprint, contentDescription = null, tint = x.accentText, modifier = Modifier.size(20.dp))
                    Text(
                        pluralStringResource(R.plurals.drive_vault_new_hint, state.lockMinutes, state.lockMinutes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onCreate(value.trim(), it) } }, enabled = valid && !busy && chosen != null) {
                Text(stringResource(R.string.drive_vault_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.drive_cancel)) } },
    )
}

/** One answer to "Wer darf ihn öffnen?": a radio row (≥ 48 dp) with what it means. */
@Composable
private fun VaultAreaChoice(a: com.gatecontrol.android.drive.DArea, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val x = GateControlTheme.extraColors
    val (title, desc) = when (a.kind) {
        "mine" -> stringResource(R.string.drive_vault_who_me) to stringResource(R.string.drive_vault_who_me_desc)
        "family" -> stringResource(R.string.drive_vault_who_family) to stringResource(R.string.drive_vault_who_family_desc)
        else -> stringResource(R.string.drive_vault_who_team, a.label) to stringResource(R.string.drive_vault_who_team_desc)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) x.accentBg else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) x.accentText else x.border, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .selectable(selected = selected, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = x.muted)
        }
    }
}

/** The display name of a picked file. */
private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment ?: "…"

/**
 * The snackbar after files went into the vault: all originals gone ("3 Dateien in den Tresor verschoben – die
 * Originale wurden gelöscht."), else what went up and which originals are still there and why.
 */
internal fun vaultMoveText(context: Context, o: DriveVaultMove.Outcome): String {
    val r = context.resources
    if (o.clean) return r.getQuantityString(R.plurals.drive_vault_moved, o.moved, o.moved)
    val kept = o.kept + o.ask.size
    return buildList {
        add(r.getQuantityString(R.plurals.drive_vault_uploaded, o.moved, o.moved))
        if (o.deleted > 0) add(r.getQuantityString(R.plurals.drive_vault_orig_deleted, o.deleted, o.deleted))
        if (kept > 0) add(r.getQuantityString(R.plurals.drive_vault_orig_kept, kept, kept))
        if (o.declined > 0) add(r.getQuantityString(R.plurals.drive_vault_orig_declined, o.declined, o.declined))
        if (o.inTrash > 0) add(r.getQuantityString(R.plurals.drive_vault_orig_trash, o.inTrash, o.inTrash))
    }.joinToString(" ")
}
