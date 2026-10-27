package com.gatecontrol.android.ui.drive

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DArea
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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) : ViewModel() {

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

    fun refresh(quiet: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (!quiet || _state.value !is DriveLoad.Ready) _state.value = DriveLoad.Loading
            val me = api.me()
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

    private companion object {
        const val KEY_AREA = "area"
    }
}

/** A folder: its content and what can be done with it and with each item. */
@HiltViewModel
class DriveFolderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: DriveApi,
    val transfers: DriveTransfers,
    val local: DriveLocal,
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

    var sort: String = "name"
        private set
    private var job: Job? = null

    init {
        if (folderId > 0) refresh()
        viewModelScope.launch { transfers.changed.collect { if (it == folderId) refresh(quiet = true) } }
    }

    /** Shows the folder [id] (the Drive tab when another area is chosen). */
    fun open(id: Long) {
        if (id == folderId && _state.value !is DriveLoad.Error) return
        folderId = id
        _area.value = null
        refresh()
    }

    fun clearMessage() { _message.value = null }

    fun setSort(value: String) {
        if (value == sort) return
        sort = value
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
            val order = if (sort == "name") "asc" else "desc"
            val r = api.folder(folderId, sort = sort, order = order)
            _state.value = r.toLoad { it }
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

    data class Picker(val node: DNode, val areas: List<DArea>, val folder: DriveLoad<DFolder>?)

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
        val target = ((p.folder as? DriveLoad.Ready)?.value?.node?.id) ?: return
        _picker.value = null
        move(p.node, target)
    }
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

/** Search by name across the areas. */
@HiltViewModel
class DriveSearchViewModel @Inject constructor(
    private val api: DriveApi,
    private val transfers: DriveTransfers,
) : ViewModel() {
    private val _state = MutableStateFlow<DriveLoad<List<DNode>>?>(null)
    val state: StateFlow<DriveLoad<List<DNode>>?> = _state.asStateFlow()
    private var job: Job? = null

    fun search(q: String) {
        job?.cancel()
        val term = q.trim()
        if (term.length < 2) {
            _state.value = null
            return
        }
        job = viewModelScope.launch {
            delay(300)
            _state.value = DriveLoad.Loading
            _state.value = api.search(term.take(100)).toLoad { it }
        }
    }

    suspend fun fetch(node: DNode): DResult<File> = transfers.fetch(node)
}
