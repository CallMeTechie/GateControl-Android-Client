package com.gatecontrol.android.ui.drive

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DChanged
import com.gatecontrol.android.drive.DIncident
import com.gatecontrol.android.drive.DriveLive
import com.gatecontrol.android.drive.DFolder
import com.gatecontrol.android.drive.DLink
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DVersions
import com.gatecontrol.android.drive.DriveApi
import com.gatecontrol.android.drive.DriveLocal
import com.gatecontrol.android.drive.DriveLogic
import com.gatecontrol.android.drive.DriveTransfers
import com.gatecontrol.android.drive.DriveTree
import com.gatecontrol.android.drive.DTreeDir
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import com.gatecontrol.android.drive.DriveScanner
import com.gatecontrol.android.drive.DriveTimeTravel
import java.io.File
import javax.inject.Inject

/** What a screen shows while it loads, when it failed (a Drive code, `offline`) or with its content. */
sealed class DriveLoad<out T> {
    data object Loading : DriveLoad<Nothing>()
    data class Error(val code: String) : DriveLoad<Nothing>()
    data class Ready<T>(val value: T) : DriveLoad<T>()
}

internal fun <T, R> DResult<T>.toLoad(map: (T) -> R): DriveLoad<R> = when (this) {
    is DResult.Ok -> DriveLoad.Ready(map(value))
    is DResult.Failed -> DriveLoad.Error(code)
    DResult.Offline -> DriveLoad.Error("offline")
    DResult.NotConfigured -> DriveLoad.Error("not_configured")
}

internal fun DResult<*>.errorCode(): String? = when (this) {
    is DResult.Ok -> null
    is DResult.Failed -> code
    DResult.Offline -> "offline"
    DResult.NotConfigured -> "not_configured"
}

/** The Drive tab: my areas (with their storage) and the newest files. */
@HiltViewModel
class DriveHomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: DriveApi,
    val transfers: DriveTransfers,
    private val local: DriveLocal,
    val live: DriveLive,
    private val portalLinks: com.gatecontrol.android.push.PortalLinkResolver,
    private val setup: com.gatecontrol.android.data.SetupRepository,
    private val vaultSession: com.gatecontrol.android.drive.vault.DriveVaultSession,
) : ViewModel() {

    /**
     * "Aus Google Drive / OneDrive importieren" (Drive 1.8): the portal's direct link `/portal?drive=import#plg-…` on
     * the portal of Drive's own direct links (else the portal GateControl named at setup); null without a portal.
     */
    fun importUrl(): String? {
        val base = DriveLogic.portalDriveUrl(live.me.value) ?: setup.getPortalUrl()?.takeIf { it.startsWith("https://") } ?: return null
        return portalLinks.withPath(base, DriveLogic.IMPORT_PORTAL_PATH).takeIf { it.contains("drive=import") }
    }

    data class Home(val me: DMe, val areas: List<DArea>)

    /** Files deleted "everywhere" on another device that are still in this phone's gallery (the user confirms). */
    private val _wipes = MutableStateFlow<DriveLocal.PendingWipes?>(null)
    val wipes: StateFlow<DriveLocal.PendingWipes?> = _wipes.asStateFlow()

    fun trashRequest(w: DriveLocal.PendingWipes) = local.trashRequest(w.gallery)

    /** The system moved the gallery copies to the trash (or there was nothing to ask): done on this device. */
    fun wipesDone(w: DriveLocal.PendingWipes) {
        _wipes.value = null
        viewModelScope.launch { local.wipesDone(w.ids) }
    }

    private val _state = MutableStateFlow<DriveLoad<Home>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<Home>> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("drive_ui", Context.MODE_PRIVATE)
    private val _selected = MutableStateFlow(prefs.getString(KEY_AREA, null))

    /** The area shown on the tab (menu top left): the last one chosen, else Meine Dateien. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(area: DArea) {
        _selected.value = area.id
        prefs.edit().putString(KEY_AREA, area.id).apply()
    }

    /** The area to show of [areas]: the chosen one while it exists, else Meine Dateien, else the first. */
    fun current(areas: List<DArea>): DArea? =
        areas.firstOrNull { it.id == _selected.value } ?: areas.firstOrNull { it.kind == "mine" } ?: areas.firstOrNull()

    private var job: Job? = null

    init {
        refresh()
        viewModelScope.launch { transfers.changed.collect { refresh(quiet = true) } }
    }

    private var shown = false

    /** The tab is shown again (back from another tab or from the background): load quietly. */
    fun onResume() {
        if (shown) refresh(quiet = true)
        shown = true
    }

    fun refresh(quiet: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val me = live.refresh()
            if (me !is DResult.Ok) {
                _state.value = me.toLoad { error("unreachable") }
                return@launch
            }
            val m = me.value
            if (!m.licenceValid || m.seat == null) {
                _state.value = DriveLoad.Ready(Home(m, emptyList()))
                return@launch
            }
            // The storage of each area comes with GET areas; without it the list of GET me is enough.
            val areas = api.areas().valueOrNull() ?: m.areas
            _state.value = DriveLoad.Ready(Home(m, DriveLogic.sortAreas(areas)))
            // Deleted everywhere on another device: copies only in the cache go at once, gallery ones ask.
            if (m.wipes > 0) _wipes.value = local.pendingWipes()
        }
    }

    // ─── ransomware protection (feature `guard`) ────────────────────

    /** A short message after undo / release: a Drive code, `guard_released`, or `guard_undone:<restored>`. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _guardBusy = MutableStateFlow<String?>(null)
    /** The incident an action runs for. */
    val guardBusy: StateFlow<String?> = _guardBusy.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Puts back what [incident] changed. */
    fun guardUndo(incident: DIncident) = guard(incident, undo = true)

    /** "That was me": the device may write again. */
    fun guardRelease(incident: DIncident) = guard(incident, undo = false)

    private fun guard(incident: DIncident, undo: Boolean) {
        if (_guardBusy.value != null) return
        _guardBusy.value = incident.id
        viewModelScope.launch {
            val r = if (undo) api.guardUndo(incident.id) else api.guardRelease(incident.id)
            _message.value = when (r) {
                is DResult.Ok -> if (undo) "guard_undone:${r.value.restored}" else "guard_released"
                else -> r.errorCode()
            }
            // The protection's state now (a lifted stop lets the uploads and the photo backup go on).
            live.refresh()
            _guardBusy.value = null
        }
    }

    // ─── the menu's folder tree (Drive 1.11) ────────────────────────

    private val _expanded = MutableStateFlow(DriveTree.decode(prefs.getStringSet(KEY_TREE, null)))
    /** The opened folders of the menu's tree (kept on the device). */
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    private val _children = MutableStateFlow<Map<Long, List<DTreeDir>>>(emptyMap())
    /** The loaded subfolders by folder. */
    val children: StateFlow<Map<Long, List<DTreeDir>>> = _children.asStateFlow()
    private val loadingDirs = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** The arrow of [id]: opened or closed; opening loads its subfolders (once). */
    fun toggle(id: Long) {
        val next = DriveTree.toggle(_expanded.value, id)
        _expanded.value = next
        prefs.edit().putStringSet(KEY_TREE, DriveTree.encode(next)).apply()
        if (id in next) loadDirs(id)
    }

    /** The subfolders of [id] (again: [force]). A folder that is gone closes. */
    fun loadDirs(id: Long, force: Boolean = false) {
        if (!force && _children.value.containsKey(id)) return
        if (!loadingDirs.add(id)) return
        viewModelScope.launch {
            try {
                val apart = live.me.value?.has(DMe.FEATURE_VAULTS_APART) == true
                when (val r = api.dirs(id, apart)) {
                    is DResult.Ok -> _children.update { it + (id to r.value) }
                    // gone or no longer readable: closed for good
                    is DResult.Failed -> if (r.http == 404 || r.http == 403) {
                        _children.update { it + (id to emptyList()) }
                        _expanded.update { it - id }
                        prefs.edit().putStringSet(KEY_TREE, DriveTree.encode(_expanded.value)).apply()
                    } else {
                        _expanded.update { it - id }
                    }
                    // offline: closed for now (the arrow opens it again)
                    else -> if (!_children.value.containsKey(id)) _expanded.update { it - id }
                }
            } finally {
                loadingDirs.remove(id)
            }
        }
    }

    /** The menu opens: the opened folders load again quietly (the tree stays as it is meanwhile). */
    fun refreshTree() {
        val open = _expanded.value
        _children.value.keys.filter { it in open }.forEach { loadDirs(it, force = true) }
    }

    // ─── vaults in the menu (Drive 1.9, feature `vault`; Drive 1.11: only there) ─

    /** A vault of the menu: its name (decrypted while open, else remembered on this phone) and where this device stands. */
    data class VaultEntry(val vault: com.gatecontrol.android.drive.DVault, val name: String?, val state: VaultState)

    /** [OPEN]: its keys are in memory; [LOCKED]: approved, opens after the confirmation; [PENDING]: asked; [NEW]: not yet. */
    enum class VaultState { OPEN, LOCKED, PENDING, NEW }

    private val _vaults = MutableStateFlow<List<VaultEntry>>(emptyList())
    /** Every vault the person may use (their areas), for the menu. */
    val vaults: StateFlow<List<VaultEntry>> = _vaults.asStateFlow()
    private var vaultJob: Job? = null

    init {
        // locked (time without use, the app in the background): the menu says so
        viewModelScope.launch { vaultSession.locks.collect { loadVaults() } }
    }

    /** Loads the vaults (the menu opens). Nothing is opened here: that asks for the person's confirmation first. */
    fun loadVaults() {
        if (live.me.value?.has(DMe.FEATURE_VAULT) != true) {
            _vaults.value = emptyList()
            return
        }
        if (vaultJob?.isActive == true) return
        vaultJob = viewModelScope.launch {
            val list = api.vaults().valueOrNull() ?: return@launch
            _vaults.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vaultSession.keepNames(list.map { it.id }.toSet())
                val me = runCatching { vaultSession.deviceId() }.getOrNull()
                list.map { v ->
                    val state = when {
                        vaultSession.unlocked(v.id) -> VaultState.OPEN
                        vaultSession.approved(v) -> VaultState.LOCKED
                        v.myDevices.any { it.deviceId == me && it.state == "pending" } -> VaultState.PENDING
                        else -> VaultState.NEW
                    }
                    VaultEntry(v, vaultSession.label(v), state)
                }
            }
        }
    }

    private companion object {
        const val KEY_AREA = "area"
        const val KEY_TREE = "tree_open"
    }
}

/** A folder: its content and what can be done with it and with each item. */
@HiltViewModel
class DriveFolderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val transfers: DriveTransfers,
    val local: DriveLocal,
    val scanner: DriveScanner,
    val live: DriveLive,
    private val vaults: com.gatecontrol.android.drive.vault.DriveVaultSession,
    private val vaultLock: com.gatecontrol.android.drive.vault.DriveVaultLock,
    private val settings: com.gatecontrol.android.drive.DriveSettings,
    val ops: com.gatecontrol.android.drive.DriveOps,
) : ViewModel() {

    /** The route's `id` (a string argument, see Screen.DriveFolder); on the Drive tab the chosen area's top ([open]). */
    var folderId: Long = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
        private set

    private val _state = MutableStateFlow<DriveLoad<DFolder>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<DFolder>> = _state.asStateFlow()

    /** A short message after an action (a Drive code or `done_*`); the screen shows and clears it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _sort = MutableStateFlow(DriveViewPref.sort(context))
    /** How the folder is sorted (kept on the device, the same for every folder). */
    internal val sort: StateFlow<DriveLogic.FolderSort> = _sort.asStateFlow()
    private var job: Job? = null

    /** The selection and what is done with several items at once (Drive 1.12). */
    internal val bulk = DriveBulkActions(
        context = context,
        scope = viewModelScope,
        api = api,
        ops = ops,
        transfers = transfers,
        live = live,
        items = { (_state.value as? DriveLoad.Ready)?.value?.items.orEmpty() },
        here = { (_state.value as? DriveLoad.Ready)?.value },
        changed = { refresh(quiet = true) },
    )

    init {
        if (folderId > 0) refresh()
        // jobs that still run on the server (the app was closed meanwhile) show their cards again
        ops.restore()
        viewModelScope.launch { transfers.changed.collect { if (it == folderId) refresh(quiet = true) } }
        viewModelScope.launch { live.changed.collect(::onChanged) }
        // Portal links and features for the menu (usually known from the Drive tab already).
        if (live.me.value == null) viewModelScope.launch { live.current() }
    }

    private var shown = false
    private var visible = false
    private var lastLive = 0L
    private var liveJob: Job? = null

    /** On screen again (back from another tab, a page on top or the background): load quietly. */
    fun onResume() {
        visible = true
        // the sort may have changed in the Drive settings meanwhile
        val pref = DriveViewPref.sort(context)
        if (pref != _sort.value) _sort.value = pref
        if (shown) refresh(quiet = true)
        shown = true
    }

    fun onPause() {
        visible = false
    }

    /**
     * Something changed on the server: the folder loads again while it is on screen — at most every
     * [DriveLogic.LIVE_GAP_MS], keeping its place (the list stays while it loads). Hidden, [onResume] loads it.
     */
    private fun onChanged(e: DChanged) {
        if (!visible || folderId <= 0) return
        val here = (_state.value as? DriveLoad.Ready)?.value ?: return
        if (!DriveLogic.affects(e, here.node.areaId)) return
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            val wait = DriveLogic.liveDelay(lastLive, android.os.SystemClock.elapsedRealtime())
            if (wait > 0) delay(wait)
            lastLive = android.os.SystemClock.elapsedRealtime()
            if (visible) refresh(quiet = true)
        }
    }

    /** A new folder in a folder picker ([FolderPickerDialog]). */
    suspend fun createFolderIn(parentId: Long, name: String): DResult<DNode> = api.createFolder(parentId, name.trim())

    /** Shows the folder [id] (the Drive tab when another area is chosen). */
    fun open(id: Long) {
        if (id == folderId && _state.value !is DriveLoad.Error) return
        folderId = id
        _area.value = null
        bulk.clear()
        refresh()
    }

    fun clearMessage() { _message.value = null }

    /** Sorts by [key] (`name`, `mtime`, `size`, `type`) in its usual direction. */
    fun setSort(key: String) {
        if (key == _sort.value.key) return
        applySort(DriveLogic.sortOf(key, null))
    }

    /** Up (A–Z, oldest, smallest) or down. */
    fun setSortDesc(desc: Boolean) {
        if (desc == _sort.value.desc) return
        applySort(_sort.value.copy(desc = desc))
    }

    private fun applySort(value: DriveLogic.FolderSort) {
        _sort.value = value
        DriveViewPref.setSort(context, value)
        refresh(quiet = true)
    }

    /** The folder's area: its label names the top folder, `via` tells whether a child is told. */
    private val _area = MutableStateFlow<DArea?>(null)
    val area: StateFlow<DArea?> = _area.asStateFlow()

    fun refresh(quiet: Boolean = false) {
        if (folderId <= 0) return
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val sort = _sort.value
            val r = api.folder(folderId, sort = DriveLogic.serverSort(sort.key), order = if (sort.desc) "desc" else "asc")
            _state.value = r.toLoad { it.copy(items = DriveLogic.ordered(it.items, sort)) }
            bulk.keepPresent()
            val areaId = (r as? DResult.Ok)?.value?.node?.areaId
            if (areaId != null && _area.value?.id != areaId) {
                _area.value = api.areas().valueOrNull()?.firstOrNull { it.id == areaId }
            }
        }
    }

    private fun act(done: String, block: suspend () -> DResult<*>) {
        viewModelScope.launch {
            _busy.value = true
            val r = block()
            _busy.value = false
            _message.value = r.errorCode() ?: done
            if (r is DResult.Ok) refresh(quiet = true)
        }
    }

    fun upload(uris: List<Uri>) = transfers.upload(uris, folderId)

    fun createFolder(name: String) = act("done_folder") { api.createFolder(folderId, name.trim()) }

    fun rename(node: DNode, name: String) = act("done_rename") { api.rename(node.id, name.trim()) }

    fun move(node: DNode, target: Long) = act("done_move") { api.move(node.id, target) }

    /** In Drive's trash; [everywhere]: the user's other devices remove their copies too, this phone's go at once. */
    fun delete(node: DNode, everywhere: Boolean = false, copies: DriveLocal.Copies? = null) = act("done_trash") {
        val r = api.delete(node.id, everywhere)
        if (r is DResult.Ok && copies != null) local.dropCache(copies)
        r
    }

    /**
     * This phone's copies of a file: picked files go directly; returns the gallery entries (and picked ones that would
     * not go) for the system's "move to trash" dialog. Nothing left: done.
     */
    suspend fun deleteLocal(copies: DriveLocal.Copies): List<Uri> {
        val left = local.deleteDocs(copies.docs).mapNotNull { local.mediaUri(it) }
        val ask = copies.gallery + left
        if (ask.isEmpty()) deletedLocally(copies)
        return ask
    }

    /** After the gallery copies went to the phone's trash (the system asked): only on this device. */
    fun deletedLocally(copies: DriveLocal.Copies) {
        local.dropCache(copies)
        _message.value = "done_local"
    }

    suspend fun fetch(node: DNode, versionId: Long? = null): DResult<File> = transfers.fetch(node, versionId)

    // ─── versions ───────────────────────────────────────────────────

    private val _versions = MutableStateFlow<DriveLoad<DVersions>?>(null)
    val versions: StateFlow<DriveLoad<DVersions>?> = _versions.asStateFlow()

    fun openVersions(node: DNode) {
        _versions.value = DriveLoad.Loading
        viewModelScope.launch { _versions.value = api.versions(node.id).toLoad { it } }
    }

    fun closeVersions() { _versions.value = null }

    fun restoreVersion(node: DNode, versionId: Long) {
        viewModelScope.launch {
            val r = api.restoreVersion(node.id, versionId)
            _message.value = r.errorCode() ?: "done_version"
            if (r is DResult.Ok) {
                openVersions(r.value)
                refresh(quiet = true)
            }
        }
    }

    fun deleteVersion(node: DNode, versionId: Long) {
        viewModelScope.launch {
            val r = api.deleteVersion(node.id, versionId)
            _message.value = r.errorCode()
            if (r is DResult.Ok) openVersions(node)
        }
    }

    // ─── links ──────────────────────────────────────────────────────

    data class LinkSheet(val node: DNode, val links: DriveLoad<List<DLink>>, val enabled: Boolean = true, val created: DLink? = null, val error: String? = null)

    private val _linkSheet = MutableStateFlow<LinkSheet?>(null)
    val linkSheet: StateFlow<LinkSheet?> = _linkSheet.asStateFlow()

    fun openLinks(node: DNode) {
        _linkSheet.value = LinkSheet(node, DriveLoad.Loading)
        viewModelScope.launch {
            val r = api.links(node.id)
            _linkSheet.value = _linkSheet.value?.copy(
                links = r.toLoad { l -> l.links.filter { it.active } },
                enabled = (r as? DResult.Ok)?.value?.enabled ?: true,
            )
        }
    }

    fun closeLinks() { _linkSheet.value = null }

    fun createLink(mode: String, expires: String, pin: String?) {
        val sheet = _linkSheet.value ?: return
        viewModelScope.launch {
            when (val r = api.createLink(sheet.node.id, mode, expires, pin?.takeIf { it.isNotBlank() })) {
                is DResult.Ok -> {
                    _linkSheet.value = sheet.copy(created = r.value, error = null)
                    val list = api.links(sheet.node.id)
                    _linkSheet.value = _linkSheet.value?.copy(links = list.toLoad { l -> l.links.filter { it.active } })
                }
                else -> _linkSheet.value = sheet.copy(error = r.errorCode())
            }
        }
    }

    fun endLink(token: String) {
        val sheet = _linkSheet.value ?: return
        viewModelScope.launch {
            val r = api.endLink(token)
            if (r !is DResult.Ok) {
                _linkSheet.value = sheet.copy(error = r.errorCode())
                return@launch
            }
            val list = api.links(sheet.node.id)
            _linkSheet.value = _linkSheet.value?.copy(links = list.toLoad { l -> l.links.filter { it.active } }, created = null)
        }
    }

    // ─── move: a folder picker ──────────────────────────────────────

    /** Choosing a folder: to move [node] there, or (null) where a scan goes. */
    data class Picker(val node: DNode?, val areas: List<DArea>, val folder: DriveLoad<DFolder>?)

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    /** Starts moving [node]: the areas the person writes to, then folders below. */
    fun startMove(node: DNode) {
        viewModelScope.launch {
            val areas = api.areas().valueOrNull().orEmpty().filter { it.writable }
            _picker.value = Picker(node, DriveLogic.sortAreas(areas), null)
            val here = (_state.value as? DriveLoad.Ready)?.value?.node
            if (here != null) browse(here.id)
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

    fun moveHere() {
        val p = _picker.value ?: return
        val folder = (p.folder as? DriveLoad.Ready)?.value ?: return
        _picker.value = null
        if (p.node == null) {
            val area = p.areas.firstOrNull { it.id == folder.node.areaId }?.label
            _scan.update { it?.copy(folderId = folder.node.id, folderLabel = label(folder, area)) }
        } else {
            move(p.node, folder.node.id)
        }
    }

    // ─── scanning ───────────────────────────────────────────────────

    /**
     * A scan waiting to be saved: its pages, their recognised text (null while that runs), PDF or pictures (the
     * server's "Scans speichern als" until the person picks), and the folder it goes to (this one at first).
     */
    data class ScanDraft(
        val scan: DriveScanner.Pages,
        val texts: List<String?>? = null,
        val pdf: Boolean = true,
        val formatPicked: Boolean = false,
        val folderId: Long,
        val folderLabel: String,
    )

    private val _scan = MutableStateFlow<ScanDraft?>(null)
    val scan: StateFlow<ScanDraft?> = _scan.asStateFlow()
    private var recognizing: Deferred<List<String?>>? = null

    /** The scanner is back with [pages]: the save sheet opens, the text is recognised meanwhile. */
    fun scanned(pages: DriveScanner.Pages) {
        val here = (_state.value as? DriveLoad.Ready)?.value ?: return
        if (pages.pages.isEmpty()) return
        _scan.value = ScanDraft(pages, folderId = here.node.id, folderLabel = label(here, _area.value?.label))
        viewModelScope.launch {
            val format = api.me().valueOrNull()?.scanFormat
            _scan.update { d -> if (d != null && d.scan == pages && !d.formatPicked && format != null) d.copy(pdf = format != "image") else d }
        }
        val job = viewModelScope.async { scanner.recognize(pages.pages) }
        recognizing = job
        viewModelScope.launch {
            val texts = job.await()
            _scan.update { d -> if (d?.scan == pages) d.copy(texts = texts) else d }
        }
    }

    fun setScanPdf(pdf: Boolean) = _scan.update { it?.copy(pdf = pdf, formatPicked = true) }

    /** Another folder for the scan: the folder picker, starting here. */
    fun pickScanFolder() {
        val d = _scan.value ?: return
        viewModelScope.launch {
            val areas = api.areas().valueOrNull().orEmpty().filter { it.writable }
            _picker.value = Picker(null, DriveLogic.sortAreas(areas), null)
            browse(d.folderId)
        }
    }

    fun closeScan() {
        _scan.value = null
    }

    /** Saves the scan as [typedName] ([fallback] when empty), with its text (waits for it if still running). */
    fun saveScan(typedName: String, fallback: String) {
        val d = _scan.value ?: return
        val job = recognizing
        _scan.value = null
        viewModelScope.launch {
            val texts = d.texts ?: job?.await().orEmpty()
            val ok = scanner.save(d.scan, texts, DriveLogic.scanBase(typedName, fallback), d.pdf, d.folderId)
            if (!ok) _message.value = "scan_read"
        }
    }

    // ─── time travel (Drive 1.7, feature `timeTravel`) ──────────────

    private val _timeTravel = MutableStateFlow<DriveTimeTravel?>(null)
    /** "Auf früheren Stand zurücksetzen…" of a folder, while it is open. */
    val timeTravel: StateFlow<DriveTimeTravel?> = _timeTravel.asStateFlow()

    fun startTimeTravel(folder: DNode) {
        if (folder.isDir) _timeTravel.value = DriveTimeTravel(folder)
    }

    fun closeTimeTravel() {
        _timeTravel.value = null
    }

    /** Back to choosing the time (from the preview). */
    fun timeTravelRepick() = _timeTravel.update { it?.copy(step = DriveTimeTravel.Step.PICK, error = null) }

    /** The time is chosen: the preview is fetched. */
    fun timeTravelAt(ms: Long) {
        val t = _timeTravel.value?.pick(ms, System.currentTimeMillis()) ?: return
        _timeTravel.value = t
        val at = t.atIso ?: return
        if (t.step != DriveTimeTravel.Step.PREVIEWING) return
        viewModelScope.launch {
            val r = api.timeTravelPreview(t.folder.id, at)
            _timeTravel.update { cur -> if (cur != null && cur.atMs == t.atMs) cur.previewed(r) else cur }
        }
    }

    fun timeTravelRun() {
        val t = _timeTravel.value?.takeIf { it.canRun } ?: return
        val at = t.atIso ?: return
        _timeTravel.value = t.running()
        viewModelScope.launch {
            val r = api.timeTravel(t.folder.id, at)
            _timeTravel.update { it?.ran(r) }
            if (r is DResult.Ok) refresh(quiet = true)
        }
    }

    /** "Rückgängig": a time travel to the moment just before the last one. */
    fun timeTravelUndo() {
        val t = _timeTravel.value?.takeIf { it.canUndo } ?: return
        val before = t.result?.before ?: return
        _timeTravel.value = t.undoing()
        viewModelScope.launch {
            val r = api.timeTravel(t.folder.id, before)
            _timeTravel.update { it?.undid(r) }
            refresh(quiet = true)
        }
    }

    // ─── office, vaults (Drive 1.9) ─────────────────────────────────

    /** The office's address of [node] ("Im Browser bearbeiten / ansehen"). */
    suspend fun office(node: DNode): DResult<com.gatecontrol.android.drive.DOffice> = api.office(node.id)

    /**
     * "Neuer Tresor" (Drive 1.11: only from the plus menu): the areas it can go to ([DriveLogic.vaultAreas], null while
     * they load), busy while it is made, then the recovery key to show once.
     */
    data class NewVault(
        val areas: List<DArea>? = null,
        val busy: Boolean = false,
        val recoveryKey: String? = null,
        val vaultId: String? = null,
        val nodeId: Long? = null,
        /** Minutes until an open vault locks (the hint of the dialog). */
        val lockMinutes: Int = com.gatecontrol.android.drive.DriveSettingsLogic.DEFAULT_AUTO_LOCK,
    )

    private val _newVault = MutableStateFlow<NewVault?>(null)
    val newVault: StateFlow<NewVault?> = _newVault.asStateFlow()

    fun startVault() {
        val start = NewVault(lockMinutes = settings.current.autoLockMinutes)
        _newVault.value = start
        viewModelScope.launch {
            val seat = live.me.value?.seat ?: api.me().valueOrNull()?.seat
            val areas = api.areas().valueOrNull()
            _newVault.update { v -> if (v === start) v.copy(areas = DriveLogic.vaultAreas(DriveLogic.sortAreas(areas.orEmpty()), seat)) else v }
            if (areas == null) {
                _newVault.value = null
                _message.value = "offline"
            }
        }
    }

    fun closeVault() { _newVault.value = null }

    /**
     * A new vault [name] for [area]: it always lies in the area's top folder (wherever the plus was pressed; the
     * server of Drive 1.11 puts it there too). Encrypted here, the recovery key comes back to be written down.
     */
    fun createVault(name: String, area: DArea) {
        val v = _newVault.value ?: return
        if (v.busy) return
        _newVault.value = v.copy(busy = true)
        viewModelScope.launch {
            when (val r = vaults.create(area.rootId, name)) {
                is DResult.Ok -> {
                    vaultLock.touch()
                    _newVault.value = v.copy(busy = false, recoveryKey = r.value.recoveryKey, vaultId = r.value.vault.id, nodeId = r.value.vault.nodeId)
                    refresh(quiet = true)
                }
                else -> {
                    _newVault.value = null
                    _message.value = r.errorCode()
                }
            }
        }
    }

    /** "Familie › Dokumente › Ausweise": the area, then the folders down to [f]. */
    private fun label(f: DFolder, area: String?): String =
        (listOf(area ?: "…") + f.crumbs.drop(1).map { it.name } + listOfNotNull(f.node.name.takeIf { f.node.parentId != null && it.isNotEmpty() }))
            .joinToString(" › ")
}

/** The trash of the areas the person writes to. */
@HiltViewModel
class DriveTrashViewModel @Inject constructor(private val api: DriveApi) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<List<DNode>>>(DriveLoad.Loading)
    val state: StateFlow<DriveLoad<List<DNode>>> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { _state.value = api.trash().toLoad { it } }
    }

    fun clearMessage() { _message.value = null }

    fun restore(node: DNode) {
        viewModelScope.launch {
            val r = api.restoreFromTrash(node.id)
            _message.value = r.errorCode() ?: "done_restore"
            refresh()
        }
    }

    fun purge(node: DNode) {
        viewModelScope.launch {
            val r = api.purge(node.id)
            _message.value = r.errorCode() ?: "done_purge"
            refresh()
        }
    }
}

/** Search by name across the areas; the hits can be ticked and handled together (Drive 1.12). */
@HiltViewModel
class DriveSearchViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val api: DriveApi,
    private val transfers: DriveTransfers,
    val live: DriveLive,
    val ops: com.gatecontrol.android.drive.DriveOps,
) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<List<DNode>>?>(null)
    val state: StateFlow<DriveLoad<List<DNode>>?> = _state.asStateFlow()
    private var job: Job? = null
    private var term = ""

    private val _areas = MutableStateFlow<List<DArea>>(emptyList())
    /** The areas with the person's role in each (what may be changed among the hits). */
    val areas: StateFlow<List<DArea>> = _areas.asStateFlow()

    /** A short message after an action (a Drive code or `done_*`); the screen shows and clears it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    internal val bulk = DriveBulkActions(
        context = context,
        scope = viewModelScope,
        api = api,
        ops = ops,
        transfers = transfers,
        live = live,
        items = { (_state.value as? DriveLoad.Ready)?.value.orEmpty() },
        changed = { again() },
    )

    init {
        viewModelScope.launch { _areas.value = api.areas().valueOrNull() ?: live.current()?.areas.orEmpty() }
    }

    fun search(q: String) {
        job?.cancel()
        val t = q.trim()
        term = t
        bulk.clear()
        if (t.length < 2) {
            _state.value = null
            return
        }
        job = viewModelScope.launch {
            delay(300)
            _state.value = DriveLoad.Loading
            _state.value = api.search(t.take(100)).toLoad { it }
        }
    }

    /** The same search again (after a job changed the hits), quietly. */
    private fun again() {
        if (term.length < 2) return
        viewModelScope.launch {
            val r = api.search(term.take(100))
            if (r is DResult.Ok) _state.value = DriveLoad.Ready(r.value)
            bulk.keepPresent()
        }
    }

    fun rename(node: DNode, name: String) {
        viewModelScope.launch {
            val r = api.rename(node.id, name.trim())
            _message.value = r.errorCode() ?: "done_rename"
            if (r is DResult.Ok) again()
        }
    }

    suspend fun office(node: DNode): DResult<com.gatecontrol.android.drive.DOffice> = api.office(node.id)

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}
