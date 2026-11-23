package com.gatecontrol.android.tv.drive

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gatecontrol.android.drive.DArea
import com.gatecontrol.android.drive.DMe
import com.gatecontrol.android.drive.DNode
import com.gatecontrol.android.drive.DResult
import com.gatecontrol.android.drive.DriveApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the Drive page shows nothing. */
enum class TvDriveProblem {
    OFFLINE,
    /** Drive is not switched on for this person (`no_seat`) or the plugin is not there. */
    NOT_ON,
    NOT_LICENSED,
    FAILED,
}

data class TvDriveUi(
    val stack: TvDriveStack = TvDriveStack(),
    val areas: List<DArea> = emptyList(),
    val items: List<DNode> = emptyList(),
    val loading: Boolean = false,
    val problem: TvDriveProblem? = null,
    /** The offset of the next page of the folder; null: all loaded. */
    val next: Int? = null,
)

/**
 * The page "Drive" of the rail: the areas, then their folders by D-pad, and the pictures, videos and document previews
 * of a folder (the viewer is [com.gatecontrol.android.tv.ui.drive.TvMediaActivity]). Reads only: nothing is uploaded,
 * changed or deleted from the TV. Vault folders never show (Drive 1.11 keeps them out of the lists).
 */
@HiltViewModel
class TvDriveViewModel @Inject constructor(
    private val api: DriveApi,
    private val media: TvDriveMedia,
    private val selection: TvMediaSelection,
) : ViewModel() {

    private val _ui = MutableStateFlow(TvDriveUi())
    val ui: StateFlow<TvDriveUi> = _ui.asStateFlow()

    private val _previews = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())

    /** The small previews loaded so far (node id → picture), for the cards. */
    val previews: StateFlow<Map<Long, Bitmap>> = _previews.asStateFlow()

    private var loadJob: Job? = null

    private val _recent = MutableStateFlow<List<DNode>>(emptyList())

    /** "Zuletzt in Drive" on the start page: the files opened last (Drive 1.18 overview), never a vault's. */
    val recent: StateFlow<List<DNode>> = _recent.asStateFlow()

    /** The start page came to the front: the files opened last, when this Drive has the overview. */
    fun loadRecent() {
        viewModelScope.launch {
            val me = api.me().valueOrNull() ?: return@launch
            if (!me.licenceValid || me.seat == null || !me.has(DMe.FEATURE_OVERVIEW)) {
                _recent.value = emptyList()
                return@launch
            }
            val overview = api.overview().valueOrNull() ?: return@launch
            _recent.value = TvDriveLogic.recent(overview.recent.map { it.node })
        }
    }

    /** OK on a card of "Zuletzt in Drive": as [press], the viewer goes through the row's pictures or videos. */
    fun pressRecent(node: DNode): TvDriveOpen {
        val open = TvDriveLogic.openOf(node)
        when (open) {
            TvDriveOpen.FOLDER -> {
                _ui.update { it.copy(stack = TvDriveStack().open(node.id, node.name), items = emptyList(), next = null) }
                load(node.id, append = false)
            }
            TvDriveOpen.PHOTO, TvDriveOpen.VIDEO -> {
                val (list, at) = TvDriveLogic.viewerList(_recent.value, node)
                selection.set(list, at)
            }
            TvDriveOpen.PREVIEW -> selection.set(listOf(node), 0, document = true)
            TvDriveOpen.BLOCKED, TvDriveOpen.NONE -> Unit
        }
        return open
    }

    /** The page came to the front: the areas once (`GET me` also says what the server offers, e.g. the home cache). */
    fun start() {
        val ui = _ui.value
        if (ui.areas.isNotEmpty() || ui.loading) return
        reload()
    }

    /** Again (after a problem): the areas, or the folder shown. */
    fun reload() {
        val top = _ui.value.stack.top
        if (top == null) loadAreas() else load(top.id, append = false)
    }

    private fun loadAreas() {
        loadJob?.cancel()
        _ui.update { it.copy(loading = true, problem = null) }
        loadJob = viewModelScope.launch {
            when (val r = api.me()) {
                is DResult.Ok -> _ui.update {
                    // as the phone app: an invalid licence or no seat says so instead of empty areas
                    val problem = when {
                        !r.value.licenceValid -> TvDriveProblem.NOT_LICENSED
                        r.value.seat == null -> TvDriveProblem.NOT_ON
                        else -> null
                    }
                    it.copy(areas = if (problem == null) TvDriveLogic.areas(r.value.areas) else emptyList(), loading = false, problem = problem)
                }
                else -> _ui.update { it.copy(loading = false, problem = problemOf(r)) }
            }
        }
    }

    fun openArea(area: DArea) = openFolder(area.rootId, area.label)

    fun openFolder(id: Long, name: String) {
        _ui.update { it.copy(stack = it.stack.open(id, name), items = emptyList(), next = null) }
        load(id, append = false)
    }

    /** Zurück on the page: one level up; false at the areas (the rail takes it). */
    fun back(): Boolean {
        val up = _ui.value.stack.back() ?: return false
        loadJob?.cancel()
        _ui.update { it.copy(stack = up, items = emptyList(), next = null, problem = null, loading = false) }
        val top = up.top
        // a folder of "Zuletzt in Drive" came without the areas: they load now
        if (top != null) load(top.id, append = false) else if (_ui.value.areas.isEmpty()) loadAreas()
        return true
    }

    /** The focus is near the end of the cards: the next page, if there is one. */
    fun more() {
        val ui = _ui.value
        val top = ui.stack.top ?: return
        if (ui.next == null || ui.loading) return
        load(top.id, append = true)
    }

    private fun load(id: Long, append: Boolean) {
        loadJob?.cancel()
        val offset = if (append) _ui.value.next ?: return else 0
        _ui.update { it.copy(loading = true, problem = null) }
        loadJob = viewModelScope.launch {
            when (val r = api.folder(id, offset = offset, limit = TvDriveLogic.PAGE)) {
                is DResult.Ok -> _ui.update { ui ->
                    if (ui.stack.top?.id != id) return@update ui
                    val items = if (append) ui.items + r.value.items else r.value.items
                    ui.copy(items = TvDriveLogic.ordered(items.distinctBy { it.id }), next = r.value.next, loading = false)
                }
                else -> _ui.update { it.copy(loading = false, problem = problemOf(r)) }
            }
        }
    }

    /** The card of [node] came into view: its small preview. */
    fun wantPreview(node: DNode) {
        if (!node.preview || node.isDir || node.encrypted || _previews.value.containsKey(node.id)) return
        media.peek(node)?.let { bmp ->
            _previews.update { it + (node.id to bmp) }
            return
        }
        viewModelScope.launch {
            val bmp = media.preview(node) ?: return@launch
            _previews.update { (it + (node.id to bmp)).trimmed() }
        }
    }

    /** At most this many previews in the page's map (the rest stays in the shared cache). */
    private fun Map<Long, Bitmap>.trimmed(): Map<Long, Bitmap> =
        if (size <= MAX_PREVIEWS) this else entries.drop(size - MAX_PREVIEWS).associate { it.key to it.value }

    /**
     * OK on [node]: what to do — a folder opens here; for a picture, a video or a document's preview the viewer's list
     * is handed over ([TvMediaSelection]) and the caller starts the viewer.
     */
    fun press(node: DNode): TvDriveOpen {
        val open = TvDriveLogic.openOf(node)
        when (open) {
            TvDriveOpen.FOLDER -> openFolder(node.id, node.name)
            TvDriveOpen.PHOTO, TvDriveOpen.VIDEO -> {
                val (list, at) = TvDriveLogic.viewerList(_ui.value.items, node)
                selection.set(list, at)
            }
            TvDriveOpen.PREVIEW -> selection.set(listOf(node), 0, document = true)
            TvDriveOpen.BLOCKED, TvDriveOpen.NONE -> Unit
        }
        return open
    }

    companion object {
        private const val MAX_PREVIEWS = 120

        fun problemOf(r: DResult<*>): TvDriveProblem = when (r) {
            is DResult.Offline -> TvDriveProblem.OFFLINE
            is DResult.Failed -> when (r.code) {
                "no_seat", "not_found", "plugin_unavailable", "plugin_not_found", "forbidden" -> TvDriveProblem.NOT_ON
                "not_licensed", "licence_invalid" -> TvDriveProblem.NOT_LICENSED
                else -> if (r.http == 404) TvDriveProblem.NOT_ON else TvDriveProblem.FAILED
            }
            is DResult.NotConfigured -> TvDriveProblem.NOT_ON
            is DResult.Ok -> TvDriveProblem.FAILED
        }
    }
}
